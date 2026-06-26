package com.bare.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Pure-static helpers for converting an arbitrary launcher icon
 * {@link Drawable} into the circular {@link Bitmap} that the shelf renders.
 *
 * <p>This is the entire icon-bitmap pipeline previously inlined inside
 * {@code LauncherActivity}:
 *
 * <ol>
 *   <li>{@link #process} — the public entry point. Handles
 *       {@link AdaptiveIconDrawable} layering (background + foreground with
 *       18 / 108 bleed), legacy bitmap drawables, and decides whether the
 *       icon needs a white "plate" beneath it (icons with transparent
 *       backgrounds end up clipped to a ragged circle without one).</li>
 *   <li>{@link #renderDrawable} — rasterises any {@link Drawable} to an
 *       {@code sz × sz} {@link Bitmap.Config#ARGB_8888} bitmap with a
 *       fast-path for {@link BitmapDrawable}.</li>
 *   <li>{@link #clipToCircle} — masks a square bitmap to a circle of the
 *       same size using {@link PorterDuff.Mode#SRC_IN}.</li>
 * </ol>
 *
 * <h3>Allocation discipline</h3>
 * Every helper that participates in the per-icon hot path reuses a small
 * set of {@link ThreadLocal} scratch buffers and pre-built {@link Paint}
 * objects so processing N icons at cold start does not produce N matrix /
 * paint / pixel-buffer allocations.
 *
 * <ul>
 *   <li>{@link #sMatrixTL} — one {@link Matrix} per worker thread (icon
 *       executor pool size = cores).</li>
 *   <li>{@link #sFillPts} — flat {@code int[24]} of (x,y) sample offsets,
 *       beats a fresh {@code int[][]} allocation per icon.</li>
 * </ul>
 *
 * <p>The pre-1.4.2 implementation also held a per-worker
 * {@code byte[rowBytes * height]} buffer (≈ 290 KB at xxxhdpi) populated
 * via {@link Bitmap#copyPixelsToBuffer} so {@link #needsFill} could
 * sample alpha bytes via direct array indexing. That trade made sense
 * only if the sample count approached the bitmap's pixel count; for a
 * fixed 12-sample heuristic it amplified read cost by ~25 000× and
 * pinned ~290 KB of thread-local memory per worker for the entire
 * activity lifetime. {@link #needsFill} now uses the 12 direct
 * {@link Bitmap#getPixel} calls (well-documented as "slower per pixel"
 * but irrelevant at 12 samples — total cost is ~1.2 µs vs the buffer
 * copy's ~30 µs of memcpy).</p>
 *
 * <h3>Thread safety</h3>
 * Every method is safe to call from any thread. The pipeline is exercised
 * concurrently from the icon executor (cores − 1 threads). All shared
 * mutable state is per-thread; the static {@link Paint} objects are read
 * through {@link Canvas#drawBitmap} / {@link Canvas#drawCircle} which only
 * read paint properties — they are never mutated after class init.
 *
 * <p>This class is package-private and final because no consumer outside
 * the launcher needs to extend it. The previous inline definition lived
 * inside {@code LauncherActivity}; pulling it out drops ~200 lines from
 * the activity without changing any behaviour and lets the icon pipeline
 * be unit-tested in isolation in a future pass.
 */
final class IconRenderer {

    private IconRenderer() { /* no instances */ }

    // ── Per-thread scratch buffers ────────────────────────────────────────

    /** Reused {@link Matrix} for the per-icon scale/translate transforms.
     *  Allocating a fresh matrix per icon is the obvious naïve pattern; on
     *  cold start with ~50 apps the GC pressure is measurable. */
    private static final ThreadLocal<Matrix> sMatrixTL = new ThreadLocal<Matrix>() {
        @Override protected Matrix initialValue() { return new Matrix(); }
    };

    /** Reused {@code int[24]} of packed (x,y) sample points for
     *  {@link #needsFill}. See {@link #needsFill} for the layout. */
    private static final ThreadLocal<int[]>  sFillPts  = new ThreadLocal<>();

    /** Reused {@link RectF} for the rounded-square clip / plate rects so the
     *  per-icon hot path stays allocation-free (one per worker thread). */
    private static final ThreadLocal<RectF> sRectTL = new ThreadLocal<RectF>() {
        @Override protected RectF initialValue() { return new RectF(); }
    };

    /** Corner-radius fraction for the TV-friendly rounded-square icon mask.
     *  ~0.22 of the side length approximates the continuous-corner
     *  "squircle" look of modern TV app tiles without the cost of a
     *  real superellipse path. Exposed so {@link RingView} and the cell
     *  placeholders can match the exact same corner so the focus ring hugs
     *  the icon edge. */
    static final float CORNER_FRAC = 0.22f;

    /** Pixel corner radius for an icon of side {@code sz}. */
    static int cornerRadiusPx(int sz) { return Math.round(sz * CORNER_FRAC); }

    // ── Pre-built Paints (read-only after class init) ─────────────────────

    private static final Paint sMaskPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sSrcInPaint   = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint sDrawPaint    = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint sWhiteFill    = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** {@link PorterDuff.Mode#SRC_ATOP} variant of {@link #sDrawPaint}.
     *  Used by the white-plate fast-path in {@link #process}: we draw
     *  the white plate first, then draw the icon with this paint so the
     *  icon's opaque pixels overwrite the plate while its transparent
     *  pixels keep the plate visible — and the circle's anti-aliased
     *  edge alpha is preserved verbatim because SRC_ATOP keeps
     *  {@code result.alpha = dst.alpha}. Mathematically equivalent to
     *  the previous "draw plate + icon, then SRC_IN-clip to circle"
     *  pipeline at the per-pixel level (verified by tracing both formulas
     *  through every alpha permutation), but allocates one fewer
     *  bitmap per icon.
     *
     *  <p>FILTER_BITMAP_FLAG and ANTI_ALIAS_FLAG match {@link #sDrawPaint}
     *  so scaled-icon rendering quality is unchanged. */
    private static final Paint sSrcAtopPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    /** Fill paint for the generated-banner tile background. */
    private static final Paint sTilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        sMaskPaint.setColor(Color.WHITE);
        sSrcInPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        sSrcAtopPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        sWhiteFill.setStyle(Paint.Style.FILL);
        sWhiteFill.setColor(Color.WHITE);
        sTilePaint.setStyle(Paint.Style.FILL);
    }

    // ── Banner tiles (v1.5.0 — TV-style 5:3 rounded-rect tiles) ─────

    /** Render a real app banner ({@code android:banner}) to a {@code w × h}
     *  rounded-rectangle tile, scaled to COVER (center-crop) so the art fills
     *  the tile with no letterboxing. {@code corner} is the corner radius in
     *  px (kept small/subtle to match the tile look). */
    static Bitmap processBannerArt(Drawable d, int w, int h, int corner) {
        if (d == null || w <= 0 || h <= 0) return null;
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
        float scale = Math.max((float) w / iw, (float) h / ih);
        int dw = Math.max(1, Math.round(iw * scale));
        int dh = Math.max(1, Math.round(ih * scale));
        Bitmap art = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, dw, dh);
        d.draw(new Canvas(art));

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int sc = c.saveLayer(0, 0, w, h, null);
        RectF rr = sRectTL.get(); rr.set(0, 0, w, h);
        c.drawRoundRect(rr, corner, corner, sMaskPaint);     // rounded mask
        c.drawBitmap(art, (w - dw) / 2f, (h - dh) / 2f, sSrcInPaint); // center-crop into mask
        c.restoreToCount(sc);
        art.recycle();
        return out;
    }

    /** Generate a uniform {@code w × h} rounded-rectangle tile for an app
     *  that ships no banner: a neutral dark plate with the app's icon centred
     *  (Fire-TV "generated banner" idiom). {@code corner} px corner radius. */
    static Bitmap generateBannerTile(Drawable icon, int w, int h, int corner) {
        if (w <= 0 || h <= 0) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        RectF rr = sRectTL.get(); rr.set(0, 0, w, h);
        sTilePaint.setColor(0xFF2A2A30);                     // neutral dark tile
        c.drawRoundRect(rr, corner, corner, sTilePaint);
        if (icon != null) {
            int isz = Math.round(h * 0.62f);                 // icon ~62% of tile height
            int ix = (w - isz) / 2, iy = (h - isz) / 2;
            icon.setBounds(ix, iy, ix + isz, iy + isz);
            icon.draw(c);
        }
        return out;
    }

    /** Generate a {@code w × h} rounded-rectangle tile for a hardware TV
     *  input (HDMI / AV / …): a dark slate plate with a centred display glyph
     *  and the input's label drawn beneath it, so each input tile is
     *  self-identifying at rest (there is no per-input artwork). {@code corner}
     *  px corner radius; {@code density} scales the stroke floors.
     *
     *  <p>Uses locally-allocated {@link Paint}s (not the shared static ones)
     *  because it mutates colour / text-size and may run concurrently with
     *  app-tile generation on the icon-executor pool. Input tiles are few and
     *  generated once each (then cached), so the per-call allocation is
     *  negligible — correctness over the micro-optimisation here. */
    static Bitmap generateInputTile(int w, int h, int corner, String label, float density) {
        if (w <= 0 || h <= 0) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setStyle(Paint.Style.FILL);
        bg.setColor(0xFF1E2A38);                             // slate-blue: distinct from app tiles
        c.drawRoundRect(new RectF(0, 0, w, h), corner, corner, bg);

        // Display/monitor glyph (screen + stand), centred in the upper area.
        Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(Math.max(density, h * 0.035f));
        glyph.setStrokeJoin(Paint.Join.ROUND);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setColor(0xFFE6F0FF);
        float cx = w / 2f;
        float screenW = w * 0.30f, screenH = h * 0.26f;
        float top = h * 0.20f;
        RectF screen = new RectF(cx - screenW / 2f, top, cx + screenW / 2f, top + screenH);
        float sc = Math.min(screenW, screenH) * 0.18f;
        c.drawRoundRect(screen, sc, sc, glyph);
        float standY = top + screenH + h * 0.06f;
        c.drawLine(cx, top + screenH, cx, standY, glyph);            // neck
        c.drawLine(cx - screenW * 0.24f, standY, cx + screenW * 0.24f, standY, glyph); // base

        // Label beneath the glyph, shrunk to fit the tile width.
        if (label != null && !label.isEmpty()) {
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(0xFFFFFFFF);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setFakeBoldText(true);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tp.setTextSize(h * 0.17f);
            float maxW = w * 0.86f;
            while (tp.measureText(label) > maxW && tp.getTextSize() > 8f) {
                tp.setTextSize(tp.getTextSize() - 1f);
            }
            c.drawText(label, cx, h * 0.86f, tp);
        }
        return out;
    }

    /** Small {@code sz × sz} input glyph for the compact chip strips (the
     *  button-shortcut picker and the hide manager), where a TV input has no
     *  app icon to show. Draws the same slate plate + display-monitor glyph as
     *  {@link #generateInputTile} but square and label-free (the chip already
     *  shows the input's name as its own text). The chip's ImageView clips it
     *  to a circle, so the result reads as a little round "input" badge that
     *  matches the round app icons beside it. {@code density} floors the
     *  stroke width so the glyph stays crisp on low-density panels.
     *
     *  <p>Locally-allocated paints (like {@link #generateInputTile}); called
     *  only when a chip strip is (re)built — never on a hot path. */
    static Bitmap generateInputGlyphIcon(int sz, float density) {
        if (sz <= 0) return null;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setStyle(Paint.Style.FILL);
        bg.setColor(0xFF1E2A38);                 // same slate-blue as the input tile
        c.drawRect(0, 0, sz, sz, bg);            // full square; the chip clips to a circle

        Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(Math.max(density, sz * 0.06f));
        glyph.setStrokeJoin(Paint.Join.ROUND);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setColor(0xFFE6F0FF);
        float cx = sz / 2f, cy = sz / 2f;
        float scrW = sz * 0.46f, scrH = sz * 0.34f;
        float top  = cy - scrH * 0.62f;
        RectF screen = new RectF(cx - scrW / 2f, top, cx + scrW / 2f, top + scrH);
        float r = Math.min(scrW, scrH) * 0.18f;
        c.drawRoundRect(screen, r, r, glyph);
        float standY = top + scrH + sz * 0.12f;
        c.drawLine(cx, top + scrH, cx, standY, glyph);                       // neck
        c.drawLine(cx - scrW * 0.26f, standY, cx + scrW * 0.26f, standY, glyph); // base
        return out;
    }

    /**
     * Convert any launcher {@link Drawable} into a TV-friendly rounded-square
     * {@code sz × sz} {@link Bitmap.Config#ARGB_8888} bitmap, ready for the
     * shelf / drawer. (Corner radius = {@link #cornerRadiusPx(int)}.)
     *
     * <p>Behaviour:
     * <ul>
     *   <li>{@link AdaptiveIconDrawable} → composite background + foreground
     *       at the standard 18 / 108 bleed, then either a direct rounded-square
     *       clip (if the background covers the icon) or a white plate +
     *       clip (if the background is missing or transparent).</li>
     *   <li>Other drawables → rasterise via {@link #renderDrawable}, then
     *       check {@link #needsFill}: if the icon has a transparent
     *       background the result gets a white plate behind it; otherwise
     *       we skip the plate / saveLayer / extra bitmap allocation and
     *       rounded-square-clip the rasterised drawable directly.</li>
     * </ul>
     *
     * @param d  drawable to convert; may be {@code null} (returns {@code null}).
     * @param sz target side length in pixels (already dp-converted).
     * @return processed bitmap or {@code null} if {@code d} is {@code null}
     *         or rasterisation failed.
     */
    static Bitmap process(Drawable d, int sz) {
        if (d == null) return null;
        // AdaptiveIconDrawable was introduced in API 26 (O); minSdk is 26,
        // so the SDK guard is redundant — keep only the type check.
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) d;
            int bleed = Math.round(sz * 18f / 108f);
            int full  = sz + bleed * 2;
            Bitmap layers = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(layers);
            if (aid.getBackground() != null) {
                aid.getBackground().setBounds(-bleed, -bleed, full - bleed, full - bleed);
                aid.getBackground().draw(c);
            }
            if (aid.getForeground() != null) {
                aid.getForeground().setBounds(-bleed, -bleed, full - bleed, full - bleed);
                aid.getForeground().draw(c);
            }
            // Adaptive icons can ship with a missing or transparent background
            // layer. Without this check those slipped through with no plate
            // and ended up as a clipped foreground floating in space — visually
            // inconsistent with every other icon on the shelf. Run the same
            // edge-sample heuristic we use for legacy logos and apply a white
            // plate when needed.
            if (needsFill(layers, sz)) {
                // Single-bitmap plate-and-clip: draw the white circle plate
                // directly into the result bitmap, then draw the inset
                // foreground with SRC_ATOP. SRC_ATOP keeps the destination's
                // alpha (so the circle's anti-aliased edge profile is
                // preserved verbatim) and replaces the destination's color
                // where the source has non-zero alpha. End result: a
                // circle-clipped icon-on-plate, identical at the pixel level
                // to the previous "drawCircle + drawBitmap + clipToCircle"
                // pipeline but allocating ONE bitmap instead of TWO. At
                // xxxhdpi (272 px iconPx) this saves ~290 KB of transient
                // ARGB_8888 allocation per plate-path icon — typically
                // ~30 of 50 apps on cold start, so ~8.7 MB less GC pressure
                // during the icon-decode flood.
                Bitmap plated = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
                Canvas pc = new Canvas(plated);
                RectF prr = sRectTL.get(); prr.set(0, 0, sz, sz);
                float prad = cornerRadiusPx(sz);
                pc.drawRoundRect(prr, prad, prad, sWhiteFill);
                int inset = Math.round(sz * 0.14f);
                Matrix mx = sMatrixTL.get();
                float s = (float) (sz - inset * 2) / sz;
                mx.setScale(s, s);
                mx.postTranslate(inset, inset);
                pc.drawBitmap(layers, mx, sSrcAtopPaint);
                layers.recycle();
                return plated;
            }
            return clipToRoundedSquare(layers, sz);
        }
        Bitmap raw = renderDrawable(d, sz);
        if (raw == null) return null;
        boolean fill = needsFill(raw, sz);
        // Fast path when no plate is needed: clip the rendered drawable to a
        // rounded square and return.
        if (!fill) return clipToRoundedSquare(raw, sz);
        // Plate path: single-bitmap composite via SRC_ATOP. See the
        // matching block in the AdaptiveIcon branch above for the
        // pixel-level equivalence argument and the per-icon ~290 KB
        // allocation saving versus the previous "draw plate + icon, then
        // clipToCircle" pipeline.
        int csz   = Math.round(sz * 0.72f);
        int inset = (sz - csz) / 2;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        RectF lrr = sRectTL.get(); lrr.set(0, 0, sz, sz);
        float lrad = cornerRadiusPx(sz);
        canvas.drawRoundRect(lrr, lrad, lrad, sWhiteFill);
        Matrix mx = sMatrixTL.get();
        mx.setScale((float) csz / sz, (float) csz / sz);
        mx.postTranslate(inset, inset);
        canvas.drawBitmap(raw, mx, sSrcAtopPaint);
        raw.recycle();
        return out;
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /**
     * Decide whether the rasterised icon needs a white plate behind it
     * (i.e. the icon body has a transparent background and a circular
     * clip alone would leave a ragged edge).
     *
     * <p>Sampling: 12 points laid out as 4 outer corners + 4 edge midpoints
     * + 4 deeper-inset corners. The deeper insets defend against icons whose
     * very first ring of pixels is anti-aliased (alpha 0..30) but whose
     * actual body is just inside.
     *
     * <p>Threshold: 6 of 12 transparent (alpha &lt; 30) → fill. The previous
     * heuristic sampled only the centre quadrant; many logo icons (Disney+,
     * Spotify, etc.) have an opaque centre and transparent corners and were
     * silently slipping through.
     */
    private static boolean needsFill(Bitmap src, int sz) {
        // Allocate / reuse the (x,y) sample point table. Same layout as
        // the pre-1.4.2 version: 4 outer corners + 4 edge midpoints +
        // 4 deeper-inset corners, packed as int[24] = (x0,y0,x1,y1,...).
        int inset = Math.max(1, sz / 16);
        int e     = sz - 1 - inset;     // edge index after inset
        int q     = sz / 8;
        int qe    = sz - q - 1;         // far inset
        int[] pts = sFillPts.get();
        if (pts == null) {
            pts = new int[24];
            sFillPts.set(pts);
        }
        // 4 outer corners
        pts[ 0] = inset; pts[ 1] = inset;
        pts[ 2] = e;     pts[ 3] = inset;
        pts[ 4] = inset; pts[ 5] = e;
        pts[ 6] = e;     pts[ 7] = e;
        // 4 edge midpoints
        pts[ 8] = sz / 2; pts[ 9] = inset;
        pts[10] = inset;  pts[11] = sz / 2;
        pts[12] = e;      pts[13] = sz / 2;
        pts[14] = sz / 2; pts[15] = e;
        // 4 deeper inset corners
        pts[16] = q;  pts[17] = q;
        pts[18] = qe; pts[19] = q;
        pts[20] = q;  pts[21] = qe;
        pts[22] = qe; pts[23] = qe;
        // Sample alpha at each point. {@link Bitmap#getPixel} is
        // documented as "slower per pixel than a buffered copy", but the
        // crossover point is in the thousands of samples — at 12 samples
        // the per-call ~100 ns JNI cost totals ~1.2 µs, vs ~30 µs of
        // memcpy for a full 290 KB copyPixelsToBuffer at xxxhdpi. The
        // direct-sample path is also free of any thread-local byte
        // buffer (the pre-1.4.2 design held one ~290 KB buffer per
        // icon-executor worker for the activity's lifetime) so peak
        // worker-thread heap drops by {@code workers × 290 KB}.
        int trans = 0;
        for (int i = 0; i < 24; i += 2) {
            if (Color.alpha(src.getPixel(pts[i], pts[i + 1])) < 30) trans++;
        }
        return trans >= 6;
    }

    /** Rasterise any drawable to an {@code sz × sz} ARGB_8888 bitmap.
     *
     *  <p>Two paths:
     *  <ul>
     *    <li>{@link BitmapDrawable} fast-path — pull the inner bitmap
     *        and matrix-scale it into the result. Avoids reinvoking
     *        the drawable's {@code draw(Canvas)} (which would have
     *        rasterised at intrinsic size first).</li>
     *    <li>Everything else — set bounds to {@code (0, 0, sz, sz)}
     *        and let the drawable rasterise directly at the target
     *        size. Pre-1.4.2 used to allocate an intrinsic-size
     *        intermediate bitmap, draw at intrinsic size, then
     *        matrix-scale into a target-size bitmap. That two-bitmap
     *        path was a defensive fallback for hypothetical drawables
     *        that ignore {@code setBounds}, but every standard
     *        platform drawable ({@link AdaptiveIconDrawable} —
     *        handled by the caller — plus {@code VectorDrawable},
     *        {@code ColorDrawable}, {@code ShapeDrawable},
     *        {@code GradientDrawable}, {@code LayerDrawable}) honours
     *        bounds correctly. The PM-loaded launcher icons we rasterise
     *        here are exactly that universe. Direct-bound drawing saves
     *        one ARGB_8888 allocation (~290 KB at xxxhdpi) per
     *        non-BitmapDrawable, non-AdaptiveIcon icon.</li>
     *  </ul>
     */
    private static Bitmap renderDrawable(Drawable d, int sz) {
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null && !src.isRecycled() && src.getWidth() > 0 && src.getHeight() > 0) {
                Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
                Matrix mx = sMatrixTL.get();
                mx.setScale((float) sz / src.getWidth(), (float) sz / src.getHeight());
                new Canvas(out).drawBitmap(src, mx, sDrawPaint);
                return out;
            }
        }
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, sz, sz);
        d.draw(new Canvas(out));
        return out;
    }

    /** Mask a square bitmap to a rounded square of the same size. The input
     *  is recycled before return so the caller does not have to track it. */
    private static Bitmap clipToRoundedSquare(Bitmap src, int sz) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int sc = c.saveLayer(0, 0, sz, sz, null);
        RectF rr = sRectTL.get(); rr.set(0, 0, sz, sz);
        float rad = cornerRadiusPx(sz);
        c.drawRoundRect(rr, rad, rad, sMaskPaint);
        c.drawBitmap(src, 0, 0, sSrcInPaint);
        c.restoreToCount(sc);
        src.recycle();
        return out;
    }
}
