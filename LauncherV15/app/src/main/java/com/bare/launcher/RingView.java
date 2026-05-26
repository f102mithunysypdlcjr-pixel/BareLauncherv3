package com.bare.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Single-stroke selection halo that wraps the focused shelf icon.
 *
 * Visual brief: premium clean ring — no shadow, no glow, single crisp
 * stroke that hugs the icon edge with ZERO gap. The ring's inner edge sits
 * exactly on the icon's outer edge so it reads as a halo wrapped around
 * the icon, not a separate floating circle.
 *
 * Pulled out of {@link LauncherActivity} as a top-level class so the activity
 * stays a touch smaller and the ring is independently testable. It carries
 * no implicit reference to the activity (the original was a {@code static
 * final} inner class for the same reason); position tracking is owned by
 * the activity which calls {@code setX/setY} every animation frame.
 */
final class RingView extends View {

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float iconR;
    private float cx, cy, ringRadius;

    RingView(Context ctx, int strokePx, int iconPx) {
        super(ctx);
        // Hardware layer is fine for a solid stroke.
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        this.iconR = iconPx / 2f;

        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.WHITE);
        ring.setStrokeWidth(strokePx);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cx = w / 2f; cy = h / 2f;
        // Inner edge of the stroke = icon outer edge (zero gap). Stroke is
        // centred on the radius, so radius = iconR + strokeWidth / 2.
        ringRadius = iconR + ring.getStrokeWidth() / 2f;
    }

    @Override protected void onDraw(Canvas c) {
        if (ringRadius <= 0) return;
        c.drawCircle(cx, cy, ringRadius, ring);
    }
}
