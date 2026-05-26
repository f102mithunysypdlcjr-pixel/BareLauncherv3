package com.bare.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.nio.ByteBuffer;

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
 *       executor pool size = cores − 1).</li>
 *   <li>{@link #sPixelBuf} — reusable {@code byte[]} backing
 *       {@link Bitmap#copyPixelsToBuffer} in {@link #needsFill}.</li>
 *   <li>{@link #sFillPts} — flat {@code int[24]} of (x,y) sample offsets,
 *       beats a fresh {@code int[][]} allocation per icon.</li>
 * </ul>
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

    /** Reusable byte buffer for {@link Bitmap#copyPixelsToBuffer}. Sized
     *  per-call inside {@link #needsFill}; the {@link ThreadLocal} just
     *  amortises the allocation across icons. */
    private static final ThreadLocal<byte[]> sPixelBuf = new ThreadLocal<>();

    /** Reused {@code int[24]} of packed (x,y) sample points for
     *  {@link #needsFill}. See {@link #needsFill} for the layout. */
    private static final ThreadLocal<int[]>  sFillPts  = new ThreadLocal<>();

    // ── Pre-built Paints (read-only after class init) ─────────────────────

    private static final Paint sMaskPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sSrcInPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint sDrawPaint  = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint sWhiteFill  = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        sMaskPaint.setColor(Color.WHITE);
        sSrcInPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        sWhiteFill.setStyle(Paint.Style.FILL);
        sWhiteFill.setColor(Color.WHITE);
    }

    /**
     * Convert any launcher {@link Drawable} into a circular {@code sz × sz}
     * {@link Bitmap.Config#ARGB_8888} bitmap, ready for the shelf.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>{@link AdaptiveIconDrawable} → composite background + foreground
     *       at the standard 18 / 108 bleed, then either a direct circle
     *       clip (if the background covers the icon) or a white plate +
     *       clip (if the background is missing or transparent).</li>
     *   <li>Other drawables → rasterise via {@link #renderDrawable}, then
     *       check {@link #needsFill}: if the icon has a transparent
     *       background the result gets a white plate behind it; otherwise
     *       we skip the plate / saveLayer / extra bitmap allocation and
     *       circle-clip the rasterised drawable directly.</li>
     * </ul>
     *
     * @param d  drawable to convert; may be {@code null} (returns {@code null}).
     * @param sz target side length in pixels (already dp-converted).
     * @return processed bitmap or {@code null} if {@code d} is {@code null}
     *         or rasterisation failed.
     */
    static Bitmap process(Drawable d, int sz) {
        if (d == null) return null;
        // AdaptiveIconDrawable was introduced in API 26 (O); minSdk is 30,
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
                Bitmap plated = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
                Canvas pc = new Canvas(plated);
                pc.drawCircle(sz / 2f, sz / 2f, sz / 2f, sWhiteFill);
                int inset = Math.round(sz * 0.14f);
                Matrix mx = sMatrixTL.get();
                float s = (float) (sz - inset * 2) / sz;
                mx.setScale(s, s);
                mx.postTranslate(inset, inset);
                pc.drawBitmap(layers, mx, sDrawPaint);
                layers.recycle();
                return clipToCircle(plated, sz);
            }
            return clipToCircle(layers, sz);
        }
        Bitmap raw = renderDrawable(d, sz);
        if (raw == null) return null;
        boolean fill = needsFill(raw, sz);
        // Fast path when no white plate is needed: csz==sz and inset==0 means
        // the matrix transform was an identity, the saveLayer/canvas was a
        // pointless copy, and the intermediate `out` bitmap was a clone of
        // `raw`. Just clip the rendered drawable to a circle and return —
        // saves one ARGB_8888 allocation (~iconPx²·4 bytes ≈ 18 KB at 68 dp
        // on hdpi, more on xxxhdpi) and one full-size canvas draw per icon.
        if (!fill) return clipToCircle(raw, sz);
        int csz   = Math.round(sz * 0.72f);
        int inset = (sz - csz) / 2;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, sWhiteFill);
        Matrix mx = sMatrixTL.get();
        mx.setScale((float) csz / sz, (float) csz / sz);
        mx.postTranslate(inset, inset);
        canvas.drawBitmap(raw, mx, sDrawPaint);
        raw.recycle();
        return clipToCircle(out, sz);
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
        boolean fast = src.getConfig() == Bitmap.Config.ARGB_8888;
        int rowBytes = src.getRowBytes();
        byte[] px = null;
        if (fast) {
            int needed = rowBytes * src.getHeight();
            px = sPixelBuf.get();
            if (px == null || px.length < needed) {
                px = new byte[needed];
                sPixelBuf.set(px);
            }
            ByteBuffer buf = ByteBuffer.wrap(px);
            buf.order(java.nio.ByteOrder.nativeOrder()).rewind();
            src.copyPixelsToBuffer(buf);
        }

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
        int trans = 0;
        for (int i = 0; i < 24; i += 2) {
            int x = pts[i], y = pts[i + 1];
            int a = fast
                    ? (px[y * rowBytes + x * 4 + 3] & 0xFF)
                    : Color.alpha(src.getPixel(x, y));
            if (a < 30) trans++;
        }
        return trans >= 6;
    }

    /** Rasterise any drawable to an {@code sz × sz} ARGB_8888 bitmap. */
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
        int w = d.getIntrinsicWidth()  > 0 ? d.getIntrinsicWidth()  : sz;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : sz;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bmp));
        if (w == sz && h == sz) return bmp;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Matrix mx = sMatrixTL.get();
        mx.setScale((float) sz / w, (float) sz / h);
        new Canvas(out).drawBitmap(bmp, mx, sDrawPaint);
        bmp.recycle();
        return out;
    }

    /** Mask a square bitmap to a circle of the same size. The input is
     *  recycled before return so the caller does not have to track it. */
    private static Bitmap clipToCircle(Bitmap src, int sz) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int sc = c.saveLayer(0, 0, sz, sz, null);
        c.drawCircle(sz / 2f, sz / 2f, sz / 2f, sMaskPaint);
        c.drawBitmap(src, 0, 0, sSrcInPaint);
        c.restoreToCount(sc);
        src.recycle();
        return out;
    }
}
