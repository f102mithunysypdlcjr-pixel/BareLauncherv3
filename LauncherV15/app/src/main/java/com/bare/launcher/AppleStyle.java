package com.bare.launcher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Static helpers for the launcher's "Apple-TV pill" toolbar buttons.
 *
 * <p>Every glyph button in the top toolbar (network, mapper, wallpaper)
 * shares the same circular plate visual language:
 * <ul>
 *   <li><b>Idle</b> — dark glass plate (~40 % black) so the symbol reads
 *       on any wallpaper.</li>
 *   <li><b>Focused</b> — frosted near-white plate; the symbol inverts
 *       (white → near-black) so it stays legible.</li>
 *   <li>Both states carry a 1 px hairline rim that defines the plate edge.</li>
 * </ul>
 *
 * <p>The factories here build the exact {@link Paint} instances the buttons
 * draw with. Each call returns a <em>new</em> {@code Paint} because every
 * button has its own copy and mutates colour at draw time
 * ({@code stroke.setColor(symbolColor)}). Keeping construction here makes
 * the visual contract explicit and lets future buttons opt into the same
 * vocabulary by calling these factories instead of re-deriving them.
 *
 * <p>{@link #applyApplePillStyle(View)} is the focus-side counterpart:
 * round outline clipping, no platform focus rectangle, hardware layer,
 * sound effects on. Every toolbar button calls it once at construction.
 *
 * <p>This class is package-private and final. It used to live as a
 * cluster of {@code makeBtn*Paint} private methods inside
 * {@code LauncherActivity}; pulling them out drops ~70 lines from the
 * activity and makes the visual contract reusable.
 */
final class AppleStyle {

    private AppleStyle() { /* no instances */ }

    /** Shared CLEAR-mode xfermode used by {@link #drawGearGlyph} to punch
     *  the cog's centre hole when it floats plate-less over the wallpaper.
     *  Built once and reused so the idle gear's {@code onDraw} (which fires
     *  on every focus change / invalidate) stays allocation-free, matching
     *  the launcher's "zero per-draw allocation" invariant. PorterDuffXfermode
     *  is immutable, so a single shared instance is thread-safe. */
    private static final PorterDuffXfermode CLEAR_XFERMODE =
            new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

    /**
     * Apply the round-outline + no-system-focus-rect + hardware-layer
     * configuration shared by every toolbar pill button.
     *
     * <p>Idempotent: calling twice on the same view leaves it in the same
     * state as one call. Safe to call after the view is attached.
     */
    static void applyApplePillStyle(View v) {
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        v.setBackground(null);
        v.setForeground(null);
        v.setStateListAnimator(null);
        // Kills the platform's default rectangular focus highlight that
        // Theme.DeviceDefault paints under any focusable View on TV.
        v.setDefaultFocusHighlightEnabled(false);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                int w = view.getWidth(), h = view.getHeight();
                int s = Math.min(w, h);
                int x = (w - s) / 2;
                int y = (h - s) / 2;
                outline.setOval(x, y, x + s, y + s);
            }
        });
        v.setClipToOutline(true);
        v.setSoundEffectsEnabled(true);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setClickable(true);
    }

    /** White paint configured as either a solid fill or a flat stroke.
     *  The default stroke cap is BUTT — callers that want rounded caps
     *  override per draw or use {@link #makeBtnStrokePaint()}. */
    static Paint makeBtnPaint(boolean fill) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        return p;
    }

    /** White stroke paint with rounded caps + joins. The standard glyph
     *  paint for buttons drawing thick bands or curved arcs (WiFi fan,
     *  slider rails, etc.). */
    static Paint makeBtnStrokePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    /** Idle plate paint — dark glass that reads on any wallpaper. */
    static Paint makeBgIdlePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x66000000);
        return p;
    }

    /** Focused plate paint — frosted near-white that lifts the symbol
     *  via inversion. The Apple-TV "selected pill" look. */
    static Paint makeBgFocusPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF2F4F4F6);
        return p;
    }

    /** Hairline inner rim that defines the glass plate edge in any state.
     *  Stroke width is 1 dp scaled to the device density. */
    static Paint makeRimPaint(float density) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0x33FFFFFF);
        p.setStrokeWidth(Math.max(1f, density));
        return p;
    }

    /** Overload kept for inline-style callers that already have the
     *  density value as an int (rare). */
    static Paint makeRimPaint(int strokePx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0x33FFFFFF);
        p.setStrokeWidth(Math.max(1, strokePx));
        return p;
    }

    /** Convenience for buttons whose glyph paints all share the same colour
     *  (the symbol colour swap on focus). Updates an arbitrary number of
     *  paints in one call. Ignores nulls. */
    static void setSymbolColor(int color, Paint... paints) {
        for (Paint p : paints) {
            if (p != null) p.setColor(color);
        }
    }

    /** The two canonical symbol colours used everywhere a glyph inverts
     *  on focus. Exposed so callers don't sprinkle 0xFF0F0F12 / 0xFFFFFFFF
     *  literals across their {@code onDraw}. */
    static final int SYMBOL_FOCUSED = 0xFF0F0F12;
    static final int SYMBOL_IDLE    = Color.WHITE;

    /**
     * Draw a solid (filled) gear / settings glyph centred at
     * {@code (cx, cy)} with outer radius {@code r}. v1.3.3 swapped the
     * v1.3.0 stroke-only line gear for this filled silhouette per the
     * "more monochrome solid not line" design feedback. The result is
     * a chunky settings-cog that reads cleanly at TV viewing distance
     * and inverts cleanly with the rest of the toolbar pill vocabulary
     * (white-on-dark idle, dark-on-white focused).
     *
     * <p>Composition (proportional to {@code r}):
     * <ul>
     *   <li>Body: filled disc at radius {@code r * 0.40}.</li>
     *   <li>Teeth: 6 rounded rectangles at 60° increments, protruding
     *       outward from the body. Each tooth is {@code r * 0.32}
     *       wide and {@code r * 0.20} long, with a corner radius of
     *       {@code toothW * 0.45} (more rounded than v1.3.3's 0.30 —
     *       reads as visibly rounded teeth at TV viewing distance,
     *       per the v1.3.4 "rounded teeth" feedback). Teeth overlap
     *       the body by 1 px so the silhouette reads as one continuous
     *       shape with no AA seam at the join.</li>
     *   <li>Hole: filled disc at radius {@code r * 0.16} drawn in
     *       {@code plateColor} so the gear reads with a central
     *       opening. The plate colour matches the pill backdrop the
     *       gear sits on (idle dark glass, focused frosted-white) so
     *       the hole reads continuous with the surrounding plate even
     *       though no actual Porter-Duff alpha-clear is performed.</li>
     * </ul>
     *
     * <p>Outer extent: {@code r * 0.58} including the tooth tips —
     * comfortable breathing room before the pill rim.
     *
     * <p>Allocations: zero per draw. The 6 teeth are placed via
     * {@link Canvas#save} / {@link Canvas#rotate} / {@link Canvas#restore}
     * so no scratch matrix is allocated. The caller-owned {@link Paint}
     * is reused with only its {@code color} mutated between body /
     * hole; antialias setting is preserved.
     *
     * @param c            canvas to draw onto
     * @param cx           glyph centre X in canvas coordinates
     * @param cy           glyph centre Y in canvas coordinates
     * @param r            outer radius (typically the pill plate's inner radius)
     * @param symbolColor  ARGB symbol colour — pass {@link #SYMBOL_FOCUSED}
     *                     or {@link #SYMBOL_IDLE} to match the canonical
     *                     pill inversion
     * @param plateColor   ARGB pill backdrop colour the gear sits on. The
     *                     hole is drawn in this colour so the cut-out
     *                     reads as continuous with the surrounding pill.
     * @param paint        reusable {@link Paint} (caller-owned). The
     *                     method sets {@code Style.FILL}, the symbol
     *                     colour, then the plate colour for the hole.
     */
    static void drawGearGlyph(Canvas c, float cx, float cy, float r,
                              int symbolColor, int plateColor, Paint paint) {
        final float rBody    = r * 0.42f;
        final float rHole    = r * 0.19f;
        final float toothLen = r * 0.18f;
        final float toothW   = r * 0.28f;
        final float cornerR  = toothW * 0.5f;

        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(symbolColor);

        // Body — filled disc.
        c.drawCircle(cx, cy, rBody, paint);

        // 6 teeth — rounded rectangles, rotated to each 60° step. The
        // rect is drawn at the "north" position (cy − rBody − toothLen
        // … cy − rBody) and the canvas is rotated to place each tooth.
        // 1 px overlap with the body avoids an AA seam at the join.
        for (int i = 0; i < 6; i++) {
            c.save();
            c.rotate(i * 60f, cx, cy);
            c.drawRoundRect(
                    cx - toothW / 2f,
                    cy - rBody - toothLen + 1f,
                    cx + toothW / 2f,
                    cy - rBody + 1f,
                    cornerR, cornerR, paint);
            c.restore();
        }

        // Centre hole. A fully-transparent plateColor means "no plate behind
        // me" (idle, floating over the wallpaper): punch a real hole with a
        // CLEAR xfermode so the cog reads as a settings gear with a visible
        // round centre instead of a solid star. Otherwise (focused, on a
        // plate) draw the hole in the plate colour so it reads continuous.
        if (Color.alpha(plateColor) == 0) {
            Paint.Style prevStyle = paint.getStyle();
            paint.setXfermode(CLEAR_XFERMODE);
            c.drawCircle(cx, cy, rHole, paint);
            paint.setXfermode(null);
            paint.setStyle(prevStyle);
        } else {
            paint.setColor(plateColor);
            c.drawCircle(cx, cy, rHole, paint);
        }
    }

    /**
     * Pill background drawable that mirrors the toolbar buttons' idle plate
     * (dark glass + hairline rim) but as a rounded rectangle whose radius
     * is half the bound height — i.e. a true capsule that hugs whatever
     * text or content sits inside it.
     *
     * <p>Used by the home-screen clock so its visual vocabulary matches the
     * top-right toolbar pills (WiFi, mapper, wallpaper). Same fill colour,
     * same 1 dp rim, same 0x33FFFFFF rim alpha — only the corner radius
     * differs (full capsule vs perfect circle).
     *
     * <p>Each call returns a fresh {@link Drawable} because Android's
     * {@code Drawable} state machine (bounds, alpha, level) is per-instance.
     * The two paint factories return their own fresh paints already, so
     * mutating one drawable's colour-filter or alpha cannot leak into
     * another.
     */
    static Drawable makePillBackground(float density) {
        final Paint bg  = makeBgIdlePaint();
        final Paint rim = makeRimPaint(density);
        final RectF rect = new RectF();
        return new Drawable() {
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                float r = b.height() / 2f;
                rect.set(b.left, b.top, b.right, b.bottom);
                c.drawRoundRect(rect, r, r, bg);
                // Inset by half a stroke so the rim is rendered fully
                // inside the bounds (matches the inner-rim placement of
                // the circular plates).
                float inset = rim.getStrokeWidth() / 2f;
                rect.inset(inset, inset);
                float ir = Math.max(0f, r - inset);
                c.drawRoundRect(rect, ir, ir, rim);
            }
            @Override public void setAlpha(int a) { bg.setAlpha(a); rim.setAlpha(a); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter cf) {
                bg.setColorFilter(cf); rim.setColorFilter(cf); invalidateSelf();
            }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}
