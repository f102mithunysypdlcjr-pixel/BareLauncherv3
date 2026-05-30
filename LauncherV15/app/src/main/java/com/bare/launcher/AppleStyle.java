package com.bare.launcher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
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
     * Draw a gear / settings glyph centred at {@code (cx, cy)} with outer
     * radius {@code r} and the given symbol colour. Used by the toolbar
     * gear pill (the unified "open settings panel" entry point) — drawn
     * entirely with {@link Canvas} primitives so the launcher carries no
     * vector or raster icon resource for it.
     *
     * <p>Composition (all sizes proportional to {@code r} so the glyph
     * scales cleanly inside any pill diameter, with comfortable breathing
     * room around the rim):
     * <ul>
     *   <li>Outer body ring at radius {@code r * 0.56} — the gear body,
     *       inset from the pill rim so the glyph reads as "centred icon",
     *       not "rim-to-rim circle".</li>
     *   <li>Inner hole at radius {@code r * 0.22} — the central opening
     *       that visually identifies the symbol as "gear" rather than
     *       "circle".</li>
     *   <li>8 teeth as short radial strokes from the body radius out to
     *       {@code body + r * 0.14}, at multiples of 45°. Eight is the
     *       canonical count for "settings gear" in modern UI vocabulary
     *       (Material, iOS, tvOS all converge here).</li>
     * </ul>
     *
     * <p>Outer extent: roughly {@code r * 0.77} including the 0.13r
     * stroke width, leaving ~23% of the radius as breathing room before
     * the pill rim — visually balanced against the existing WiFi pill's
     * arc geometry.
     *
     * <p>Allocations: the caller owns {@code stroke} and reuses it across
     * paints, so this method is allocation-free per draw call. The
     * trigonometric constants for the 8 tooth angles are computed inline
     * (HotSpot inlines {@link Math#sin} / {@link Math#cos} on x86 and
     * arm64).
     *
     * @param c       canvas to draw onto
     * @param cx      glyph centre X in canvas coordinates
     * @param cy      glyph centre Y in canvas coordinates
     * @param r       outer radius (typically the pill plate's inner radius)
     * @param color   ARGB symbol colour — pass {@link #SYMBOL_FOCUSED} or
     *                {@link #SYMBOL_IDLE} to match the canonical inversion
     * @param stroke  reusable stroke {@link Paint} (caller-owned). The
     *                method mutates {@code stroke}'s colour and width but
     *                preserves cap / join settings.
     */
    static void drawGearGlyph(Canvas c, float cx, float cy, float r,
                              int color, Paint stroke) {
        final float bodyR    = r * 0.56f;
        final float holeR    = r * 0.22f;
        final float toothLen = r * 0.14f;
        final float strokeW  = r * 0.13f;

        stroke.setColor(color);
        stroke.setStrokeWidth(strokeW);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStyle(Paint.Style.STROKE);

        // 8 teeth — short radial strokes from bodyR out to bodyR + toothLen.
        // Drawing as line segments rather than filled rectangles keeps the
        // visual weight balanced with the two ring strokes.
        for (int i = 0; i < 8; i++) {
            double a = i * (Math.PI / 4.0);
            float dx = (float) Math.cos(a);
            float dy = (float) Math.sin(a);
            float x0 = cx + dx * bodyR;
            float y0 = cy + dy * bodyR;
            float x1 = cx + dx * (bodyR + toothLen);
            float y1 = cy + dy * (bodyR + toothLen);
            c.drawLine(x0, y0, x1, y1, stroke);
        }

        // Outer body ring + inner hole ring. Drawn after the teeth so the
        // ring caps cleanly cover the inner ends of the tooth strokes —
        // visually the teeth read as protrusions FROM the ring rather than
        // as separate strokes that happen to touch it.
        c.drawCircle(cx, cy, bodyR, stroke);
        c.drawCircle(cx, cy, holeR, stroke);
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
