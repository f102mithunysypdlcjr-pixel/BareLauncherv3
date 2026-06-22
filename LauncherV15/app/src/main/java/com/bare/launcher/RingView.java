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
    private final float tileW, tileH, tileCorner;
    private float halfW, halfH, cornerR;

    RingView(Context ctx, int strokePx, int tileWpx, int tileHpx, int cornerPx) {
        super(ctx);
        // No hardware layer (see git history) — a single stroked rounded rect
        // draws into the display list as fast as into an FBO.
        this.tileW      = tileWpx;
        this.tileH      = tileHpx;
        this.tileCorner = cornerPx;

        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.WHITE);
        ring.setStrokeWidth(strokePx);
    }

    /** Recolour the halo. The home shelf uses white over the wallpaper; the
     *  app drawer switches it to a dark colour so it reads on the frosted
     *  light surface. Cheap — repaints only when the colour actually changes. */
    void setRingColor(int color) {
        if (ring.getColor() != color) { ring.setColor(color); invalidate(); }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Inner edge of the stroke hugs the tile edge with zero gap. Stroke is
        // centred on its path, so each half-extent = tileHalf + strokeWidth/2,
        // and the corner radius tracks the tile corner + strokeWidth/2 so the
        // rounded corners stay concentric with the tile's.
        float hs = ring.getStrokeWidth() / 2f;
        halfW   = tileW / 2f + hs;
        halfH   = tileH / 2f + hs;
        cornerR = tileCorner + hs;
    }

    @Override protected void onDraw(Canvas c) {
        if (halfW <= 0 || halfH <= 0) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        c.drawRoundRect(rect, cornerR, cornerR, ring);
    }
}
