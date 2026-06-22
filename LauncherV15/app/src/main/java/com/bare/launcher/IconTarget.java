package com.bare.launcher;

import android.graphics.Bitmap;

/**
 * A view that can receive an asynchronously-decoded launcher icon.
 *
 * <p>Introduced in v1.5.0 so the single icon pipeline
 * ({@code LauncherActivity.preWarmIcon} / {@code loadIconAsync} /
 * {@code iconInflight}) can deliver bitmaps to <em>both</em> the horizontal
 * home-row cells ({@code RecyclingShelfView.CellView}) and the vertical app
 * drawer cells ({@code AppDrawer.DrawerCell}) without the pipeline knowing or
 * caring which concrete view it is feeding.
 *
 * <p>Before v1.5.0 the in-flight waiter lists were typed to the concrete
 * {@code CellView}; generalising to this interface is a purely mechanical
 * widening — every existing call site already worked against the
 * {@code setIconBitmap} + "is this cell still showing this package?" contract,
 * which the two helper methods below make explicit.
 */
interface IconTarget {

    /** Deliver a freshly decoded icon. Called on the UI thread. */
    void setIconBitmap(Bitmap bmp);

    /** Package name this cell is currently bound to, or {@code null} when the
     *  cell has been recycled back to its pool (so a late icon delivery for a
     *  package the cell no longer shows is dropped). */
    String iconTargetPackage();

    /** {@code true} when the cell is on screen ({@code VISIBLE}); a recycled
     *  cell sits {@code GONE} in its pool and must not be invalidated for an
     *  icon it will never paint. */
    boolean iconTargetVisible();
}
