package com.bare.launcher;

import android.content.ComponentName;
import android.content.pm.ResolveInfo;

/**
 * Immutable record describing one launchable app on the home shelf.
 *
 * <p>Hot-path access pattern:
 * <ul>
 *   <li>{@link #packageName} — keys for icon caches, the keymap
 *       SparseArray, the hidden-apps ArraySet, and the app-list
 *       persisted-order map.</li>
 *   <li>{@link #label} — drawn directly under each cell's icon.</li>
 *   <li>{@link #component} — fast-path launch via
 *       {@code Intent.setComponent} (skips PackageManager resolution
 *       binder hops; saves 50-200 ms per launch on slow ROMs).</li>
 *   <li>{@link #ri} — used by {@code preWarmIcon} / {@code loadIconAsync}
 *       to call {@link ResolveInfo#loadIcon}. May be {@code null} for
 *       AppInfo instances reconstructed from the on-disk
 *       {@link AppListCache} — those entries fall back to
 *       {@code PackageManager.getActivityIcon(component)}.</li>
 * </ul>
 *
 * <p>This class used to live as a {@code static final} nested class
 * inside {@link LauncherActivity}. Extracted to its own file in v1.4.0
 * so the v1.4.0 cold-start cache helpers
 * ({@link AppListCache#toAppInfo}, {@code IconDiskCache} key derivation)
 * can construct {@link AppInfo} instances without reaching into the
 * activity's nested namespace. Behaviour is identical — the class is
 * still package-private and the constructor is unchanged.
 */
final class AppInfo {
    final String        packageName;
    final String        label;
    final ComponentName component;
    /**
     * The platform {@link ResolveInfo} for this app, when known.
     *
     * <p>Non-final and {@code volatile} so the {@link LauncherActivity}
     * reconcile path can graft a fresh {@code ri} onto a cache-sourced
     * AppInfo that was reconstructed from {@link AppListCache} (which
     * cannot serialise ResolveInfo and therefore stores it as
     * {@code null}). Without the graft, every icon load for that
     * AppInfo falls through to the slower
     * {@link android.content.pm.PackageManager#getActivityIcon(android.content.ComponentName)}
     * binder path forever — even after the post-launch PM scan has
     * resolved the activity.
     *
     * <p>Volatile because the field is read on the
     * {@code iconExecutor} worker thread (inside
     * {@link LauncherActivity}'s {@code resolveIconDrawable}) and
     * written on the UI thread (inside the {@code loadApps} reconcile).
     * The volatile semantics give the worker an immediate visibility
     * guarantee on the upgraded value; absent it, a worker thread
     * already running could observe the stale {@code null} for an
     * arbitrarily long window after the upgrade and still hit the slow
     * fallback. Worst case is identical to the pre-graft behaviour
     * (slow path), but volatile makes the upgrade reliable rather than
     * timing-dependent.
     */
    volatile ResolveInfo ri;

    AppInfo(String pkg, String lbl, ComponentName cmp, ResolveInfo r) {
        packageName = pkg;
        label       = lbl;
        component   = cmp;
        ri          = r;
    }
}
