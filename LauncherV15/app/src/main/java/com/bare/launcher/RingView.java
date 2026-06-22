package com.bare.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Single-stroke selection halo that wraps the focused shelf / drawer icon.
 *
 * Visual brief: premium clean ring — no shadow, no glow, single crisp
 * stroke that hugs the icon edge with ZERO gap. As of v1.5.0 the icons are
 * TV-friendly rounded squares (see {@link IconRenderer}), so the halo is a
 * rounded-rectangle stroke whose corner radius is kept concentric with the
 * icon's corner (icon corner + half the stroke width) — the stroke's inner
 * edge sits exactly on the icon's outer edge so it reads as a halo wrapped
 * around the tile, not a separate floating frame.
 *
 * Pulled out of {@link LauncherActivity} as a top-level class so the activity
 * stays a touch smaller and the ring is independently testable. It carries
 * no implicit reference to the activity (the original was a {@code static
 * final} inner class for the same reason); position tracking is owned by
 * the activity which calls {@code setX/setY} every animation frame.
 */
final class RingView extends View {

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float iconHalf;
    private final float iconCorner;
    private float half, cornerR;

    RingView(Context ctx, int strokePx, int iconPx) {
        super(ctx);
        // No hardware layer. The original setLayerType(LAYER_TYPE_HARDWARE)
        // forced an offscreen FBO + texture upload every frame the ring
        // moved or scaled (every focus animation, every shelf scroll). For
        // a single anti-aliased stroked shape, that's pure overhead — the
        // GPU draws the stroke into the display list just as fast as into
        // a texture, and skipping the FBO removes a ~0.3 ms hop per frame
        // on cheaper TV ROMs. Default LAYER_TYPE_NONE is correct here.
        this.iconHalf   = iconPx / 2f;
        this.iconCorner = IconRenderer.cornerRadiusPx(iconPx);

        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.WHITE);
        ring.setStrokeWidth(strokePx);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Inner edge of the stroke = icon outer edge (zero gap). Stroke is
        // centred on its path, so the half-extent = iconHalf + strokeWidth/2
        // and the corner radius tracks the icon corner + strokeWidth/2 so the
        // rounded corners stay concentric with the icon's.
        half    = iconHalf + ring.getStrokeWidth() / 2f;
        cornerR = iconCorner + ring.getStrokeWidth() / 2f;
    }

    @Override protected void onDraw(Canvas c) {
        if (half <= 0) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        rect.set(cx - half, cy - half, cx + half, cy + half);
        c.drawRoundRect(rect, cornerR, cornerR, ring);
    }
}
