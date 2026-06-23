package com.bare.launcher;

/**
 * The surface that currently owns the shared reorder context menu (the
 * "Move / App Info / Uninstall" plate). Either the bottom home shelf
 * ({@code RecyclingShelfView}) or the pull-down app drawer
 * ({@code AppDrawer}) can be the active reorder host at any one time — never
 * both, since the drawer is only reorder-able while it is open (covering the
 * shelf).
 *
 * <p>Introduced in v1.5.0 so the single lazily-built menu overlay
 * ({@code LauncherActivity.ensureMenuOverlay}) and its highlight logic
 * ({@code updateMenuHighlight}) can drive whichever surface is reordering
 * without hard-wiring to the shelf. The activity holds the current host in
 * {@code menuHost}; the menu's click listeners and the highlight painter call
 * through this interface instead of reaching into the shelf directly.
 */
interface ReorderHost {

    /** Current menu cursor — one of {@code MENU_HIDE} / {@code MENU_UNINSTALL}
     *  / {@code MENU_APP_INFO} / {@code MENU_MOVE}. Read by the highlight
     *  painter. */
    int menuSelection();

    /** The user activated the Hide row (hides the app from the shelf/drawer;
     *  it stays installed and listed in the Manage-hidden-apps screen). */
    void onMenuHide();

    /** The user activated the Uninstall row (touch click or D-pad confirm). */
    void onMenuUninstall();

    /** The user activated the App Info row. */
    void onMenuAppInfo();

    /** The user activated the Move row (confirm reorder). */
    void onMenuMove();
}
