package com.bare.launcher;

import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
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
}
