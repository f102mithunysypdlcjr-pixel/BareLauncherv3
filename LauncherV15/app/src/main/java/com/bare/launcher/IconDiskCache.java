package com.bare.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Per-package disk cache for processed launcher icons.
 *
 * <p>Sibling of {@link AppListCache} and the {@link WallpaperController}
 * snapshot — same philosophy: persist the result of an expensive
 * pipeline so the next cold start can skip it. Where the icon
 * pipeline normally costs:
 * <ol>
 *   <li>One binder hop to {@code system_server} for
 *       {@code ResolveInfo.loadIcon} / {@code PackageManager.getActivityIcon}
 *       (~5-15 ms per icon on cheap TV ROMs).</li>
 *   <li>{@link IconRenderer#process}: AdaptiveIconDrawable layering,
 *       white-plate detection via {@link Bitmap#copyPixelsToBuffer},
 *       circle clip + saveLayer ({@code SRC_IN}) compositing.
 *       (~5-20 ms per icon depending on icon shape.)</li>
 * </ol>
 * the cached path costs:
 * <ol>
 *   <li>One file read + {@link BitmapFactory#decodeStream} on the
 *       already-correct-resolution WEBP (~2-5 ms per icon).</li>
 * </ol>
 * Net: a 50-app cold start drops from ~500-1500 ms of icon work to
 * ~100-250 ms, all of which can run synchronously while the shelf is
 * already painted from {@link AppListCache}.
 *
 * <h3>Plug point</h3>
 * The activity calls {@link #tryRead} from inside the icon-executor
 * task body ({@code loadIconBlocking}); on a hit the bitmap goes
 * straight into the in-memory {@link android.util.LruCache} and on
 * to the cell. UI-thread callers ({@code cell.bind}, {@code preWarmIcon})
 * never touch the disk cache directly — their {@code iconCache.get}
 * checks are memory-only, and a memory miss queues the executor task
 * that runs {@link #tryRead} on a worker thread.
 *
 * <h3>Atomicity and concurrency</h3>
 * Writes go through a single-thread {@link ThreadPoolExecutor} with
 * {@code allowCoreThreadTimeOut(true)} so the writer thread exits when
 * idle. Each write goes to a {@code .tmp} sibling and renames on
 * success — process death mid-write leaves only a (best-effort
 * deleted) tmp file behind, never a half-written cache file the next
 * read would see as truncated. Reads are synchronous on the calling
 * thread (the icon executor's worker, not UI); the WEBP decode is
 * fast enough to not skip a frame.
 *
 * <h3>Resolution-keyed filenames</h3>
 * Cache filenames are {@code {pkg}-{px}.icn} where {@code px} is
 * {@code dp(ICON_DP)} at the time of the write. Including the icon's
 * pixel size in the filename makes the cache automatically self-
 * invalidate when the device DPI / font scale / display configuration
 * changes (the {@code px} value differs, so the new lookup misses and
 * a fresh decode runs at the new size). Without this, a DPI change
 * would leave previously-cached icons rendering at the OLD pixel size
 * inside cells sized at the NEW pixel size — visible as a one-pixel
 * mis-scale halo until the cache rebuilt naturally over many sessions.
 *
 * <p>The v1.4.0 first cut keyed filenames as just {@code {pkg}.cache}.
 * Those legacy entries become orphans the moment this version ships;
 * they are purged in {@link #purgeOrphans()} the first time the cache
 * is constructed under the new code so they don't accumulate disk
 * space.
 *
 * <h3>Invalidation</h3>
 * The activity's {@code packageReceiver} calls {@link #delete(String)}
 * on every {@code ACTION_PACKAGE_REPLACED} / {@code _CHANGED} /
 * {@code _REMOVED} so a package update or uninstall does not leave
 * stale icon bytes behind. {@link #delete} wildcards across every
 * resolution we may have cached for that package, so a rare boot-
 * with-larger-DPI-then-back-to-smaller-DPI session does not leak the
 * intermediate-size cache file. {@link android.util.LruCache#remove}
 * fires for the same broadcasts so the in-memory and on-disk views
 * stay in lockstep.
 *
 * <h3>Sizing</h3>
 * Typical icon WEBP at quality 95: ~3-8 KB. 50 apps → ~250 KB. 200 apps
 * → ~1 MB. No upper-bound enforcement — the cache only grows when new
 * packages are installed, and the package-removed broadcast prunes as
 * apps are uninstalled. Orphan entries from uninstall-while-launcher-
 * dead races accumulate at most a few KB each, well under any
 * reasonable disk budget. If this ever becomes a real problem, an LRU
 * sweep keyed by file mtime is one method-add away.
 */
final class IconDiskCache {

    /** Subdirectory of {@link Context#getFilesDir()} that holds the
     *  per-package icon files. Created on construct if missing. */
    private static final String DIR_NAME    = "icons";

    /** Filename suffix. The {@code .icn} short-form makes the role
     *  obvious without inviting users to open the file as a generic
     *  image (it's a WEBP, but it's also an internal cache artifact;
     *  the path is private app storage and never user-facing). */
    private static final String EXT         = ".icn";

    /** Temp-file suffix used for atomic writes via {@code rename}. */
    private static final String TMP_EXT     = ".icn.tmp";

    /** Filename suffix used by the v1.4.0 first cut, before the
     *  resolution key was added. Files matching this pattern are
     *  orphans under the current code and are deleted on construct.
     *  Two extensions tracked separately so a future format bump
     *  can extend this list without losing the v1.4.0 sweep. */
    private static final String LEGACY_EXT      = ".cache";
    private static final String LEGACY_TMP_EXT  = ".cache.tmp";

    /** WEBP quality. Same reasoning as {@link WallpaperController}'s
     *  snapshot: legacy {@link Bitmap.CompressFormat#WEBP} is the only
     *  enum available on minSdk 26 (WEBP_LOSSY/LOSSLESS need API 30+),
     *  routes to lossless on API 30+ at quality 100, and 95 stays in
     *  lossy mode across all our target APIs for a consistent file
     *  size budget. Visually indistinguishable from lossless on a
     *  68 dp icon. */
    private static final int    QUALITY     = 95;

    /** Backlog cap on the write executor. Each cache write is
     *  ~5-10 ms; 64 queued is ~half a second of work. {@link
     *  ThreadPoolExecutor.DiscardOldestPolicy} drops the oldest queued
     *  write when the bound is hit — which is the correct behaviour
     *  for a cache: the most recently produced bitmap is the most
     *  current one to persist. */
    private static final int    QUEUE_CAP   = 64;

    private final File                 dir;
    private final ThreadPoolExecutor   writeExecutor;
    private volatile boolean           shuttingDown = false;

    /**
     * @param appContext any context — we capture {@link Context#getFilesDir()}
     *                   eagerly. Application context is preferred over
     *                   activity context to avoid pinning the activity
     *                   to a long-lived runnable, but either works
     *                   functionally.
     */
    IconDiskCache(Context appContext) {
        this.dir = new File(appContext.getFilesDir(), DIR_NAME);
        // Best-effort mkdir. On the rare ROM where the call fails the
        // cache becomes inert (every read misses, every write skips on
        // the parent-directory absence) — same observable behaviour as
        // having no cache at all, which is exactly the pre-v1.4.0
        // baseline.
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        this.writeExecutor = new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAP),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        this.writeExecutor.allowCoreThreadTimeOut(true);
        // Defer the orphan-purge sweep to the write executor so the
        // activity's onCreate critical path is not blocked on disk
        // I/O. Running on the same single-thread executor that future
        // writes target naturally serialises the sweep before any
        // {@link #writeAsync} call (queue is FIFO), so a freshly-
        // written file cannot be mistakenly deleted by a sweep that
        // started later. listFiles + N small deletes is ~30 ms on a
        // heavy install — invisible when shifted off UI, would be a
        // one-time cold-start hit otherwise.
        try {
            writeExecutor.execute(this::purgeOrphans);
        } catch (RejectedExecutionException ignored) {
            // Executor cannot accept tasks (saturated + DiscardOldest
            // dropping a queue spot is impossible at construct time;
            // defensive). The legacy files remain until the next
            // construct or "Clear app data".
        }
    }

    /**
     * Synchronous disk read. Decodes to {@link Bitmap.Config#ARGB_8888}
     * because the in-memory cache has consumers that read pixel bytes
     * (e.g. icon-mutation paths in a future plate / badge feature would
     * fail on a HARDWARE config bitmap). Returns {@code null} on miss
     * or any decode failure — same null-handling the existing icon-load
     * path already implements.
     *
     * <p>Called from the icon executor's worker thread inside
     * {@code loadIconBlocking}, NEVER from the UI thread. A 5-10 KB
     * WEBP decodes in 2-5 ms but doing it on UI would still skip a
     * frame on a slow ROM if 50 cells all bind in the same vsync; the
     * worker-thread placement keeps the UI free.
     *
     * @param pkg     package name keying the cache entry.
     * @param iconPx  current target icon pixel size (typically
     *                {@code dp(ICON_DP)}). Mismatched sizes look like
     *                cache misses — guards against rendering a stale-
     *                resolution icon after a DPI change.
     */
    Bitmap tryRead(String pkg, int iconPx) {
        if (pkg == null || pkg.isEmpty() || iconPx <= 0 || shuttingDown) return null;
        File f = fileFor(pkg, iconPx);
        if (!f.exists() || f.length() == 0) return null;
        Bitmap bmp = null;
        try (FileInputStream fis = new FileInputStream(f)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            // ARGB_8888 NOT HARDWARE: future icon-pipeline consumers
            // (plate compositing, badge overlay) read pixels and would
            // fail on HARDWARE-config bitmaps. The wallpaper case can
            // safely use HARDWARE because the bitmap is read-only after
            // load; icons must stay byte-addressable.
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = BitmapFactory.decodeStream(fis, null, opts);
        } catch (IOException | OutOfMemoryError ignored) {
            // Corrupt or truncated cache file. Delete so the next
            // write doesn't compete with bad bytes. Best-effort —
            // failure to delete just means the next read will hit
            // the same bad path; the user-visible effect is one
            // extra ~5 ms decode failure per icon per cold start
            // until the file is overwritten by a fresh write.
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        return bmp;
    }

    /**
     * Schedule an asynchronous write of {@code bmp} for {@code pkg}
     * at resolution {@code iconPx}. Returns immediately. The executor
     * is bounded with {@code DiscardOldestPolicy} so a write storm
     * (e.g. cold start with 200 apps decoding in parallel on the icon
     * executor) cannot back up unbounded; oldest queued writes get
     * dropped as newer ones come in. For a cache, "drop oldest" is
     * the correct policy — the freshest bitmap is the one we want on
     * disk.
     *
     * <p>HARDWARE-config bitmaps are silently skipped because
     * {@link Bitmap#compress} returns false on them (pixels are in
     * graphics memory, not addressable from the encoder). Caller is
     * expected to provide an ARGB-style bitmap; the icon pipeline's
     * {@link IconRenderer#process} returns ARGB_8888 so this is the
     * normal case.
     *
     * <p>Idempotent at the file level — successive writes for the
     * same {@code (pkg, iconPx)} pair overwrite the existing entry
     * atomically (tmp + rename). A failed write leaves the previous
     * entry intact (or no entry, on first write).
     */
    void writeAsync(String pkg, int iconPx, Bitmap bmp) {
        if (pkg == null || pkg.isEmpty() || iconPx <= 0 || shuttingDown) return;
        if (bmp == null || bmp.isRecycled()) return;
        if (bmp.getConfig() == Bitmap.Config.HARDWARE) return;
        // Capture by reference — Bitmap is reference-counted via the
        // underlying NativeAllocation; the LruCache holds it live until
        // eviction. We're racing against eviction here: if the cache
        // evicts and recycles the bitmap before our write task runs
        // we'll detect it via isRecycled() and bail.
        final Bitmap captured = bmp;
        try {
            writeExecutor.execute(() -> writeSync(pkg, iconPx, captured));
        } catch (RejectedExecutionException ignored) {
            // Executor refused (closed or full + DiscardOldest dropping
            // the new task instead of the old one is impossible per
            // policy contract, but defensive). The cache entry stays
            // at its previous value — next icon load will retry.
        }
    }

    /** Worker-thread implementation of {@link #writeAsync}. */
    private void writeSync(String pkg, int iconPx, Bitmap bmp) {
        if (shuttingDown || bmp == null || bmp.isRecycled()) return;
        File tmp  = tmpFileFor(pkg, iconPx);
        File dest = fileFor(pkg, iconPx);
        // Defensive parent-dir mkdir in case Android cleared filesDir
        // between construct and now (rare, but possible if the user
        // did "Clear app data" while the launcher was paused).
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        boolean wrote = false;
        try (BufferedOutputStream out =
                     new BufferedOutputStream(new FileOutputStream(tmp))) {
            //noinspection deprecation -- legacy WEBP enum is the only choice
            // on minSdk 26; WEBP_LOSSY/LOSSLESS require API 30+.
            wrote = bmp.compress(Bitmap.CompressFormat.WEBP, QUALITY, out);
        } catch (IOException ignored) {
            wrote = false;
        }
        if (!wrote) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(dest)) {
            // Rename failed (rare; some FUSE mounts on overlay storage).
            // Leak-clean: drop the tmp so it doesn't accumulate across
            // retries.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * Delete every cache entry for {@code pkg} across all cached
     * resolutions. Called from the activity's {@code packageReceiver}
     * on every {@code ACTION_PACKAGE_REPLACED} / {@code _CHANGED} /
     * {@code _REMOVED} so a stale icon does not survive a package
     * update.
     *
     * <p>Resolution-blind so a rare two-DPI session (e.g. user
     * connects to a smaller-resolution external display, package is
     * updated, user disconnects back to the larger display) does not
     * leave the smaller-resolution cache file behind. The cost is one
     * directory scan per broadcast; the alternative — tracking every
     * resolution we have written for every package — is bookkeeping
     * we don't need on TV where DPI almost never changes.
     *
     * <p>Runs on the write executor (not the calling thread) so the
     * BroadcastReceiver hot path is not blocked on file I/O. The
     * delete is best-effort; failure leaves the stale file in place
     * but the in-memory cache invalidation has already evicted any
     * stale Bitmap, so the user-visible effect is just one transient
     * read of the stale file on next icon load.
     */
    void delete(String pkg) {
        if (pkg == null || pkg.isEmpty() || shuttingDown) return;
        try {
            writeExecutor.execute(() -> {
                File[] files = dir.listFiles();
                if (files == null) return;
                final String prefix = pkg + "-";
                for (File f : files) {
                    String n = f.getName();
                    if (n.startsWith(prefix) && (n.endsWith(EXT) || n.endsWith(TMP_EXT))) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Executor closed; the deletion will happen the next time
            // the user-data area is cleared, or never. The stale file
            // is harmless — package broadcasts already invalidated the
            // in-memory copy, so a subsequent read of the stale file
            // would just look like a normal icon load.
        }
    }

    /**
     * Tear down: shut the write executor (best-effort within 300 ms).
     * Mirrors the discipline of the activity's {@code shutdown} helper
     * for {@code iconExecutor} / {@code appExecutor}. After this
     * returns, {@link #tryRead}, {@link #writeAsync}, and
     * {@link #delete} are all no-ops via the {@code shuttingDown}
     * guard.
     *
     * <p>Equivalent to calling {@link #beginShutdown()} immediately
     * followed by {@link #awaitShutdown(long)} with a 300 ms budget.
     * Prefer the two-phase API in callers that need to overlap the
     * shutdown of multiple executors so the wall-clock cap is shared
     * across all of them rather than spent serially.
     */
    void shutdown() {
        beginShutdown();
        awaitShutdown(300);
    }

    /**
     * Phase 1 of a parallel shutdown: flip the shutting-down flag and
     * mark the write executor as shutdown. Returns immediately; in-flight
     * writes are allowed to complete on their own. Pair with
     * {@link #awaitShutdown(long)} to bound the wait.
     *
     * <p>Idempotent — safe to call multiple times. After this returns,
     * {@link #tryRead}, {@link #writeAsync} and {@link #delete} are
     * no-ops via the {@code shuttingDown} guard.
     */
    void beginShutdown() {
        shuttingDown = true;
        try { writeExecutor.shutdown(); }
        catch (Throwable ignored) { /* best-effort */ }
    }

    /**
     * Phase 2 of a parallel shutdown: wait up to {@code timeoutMs} for
     * the write executor's in-flight task to complete, then force any
     * remainder via {@code shutdownNow}. Pair with
     * {@link #beginShutdown()} so the wall-clock cap is shared across
     * multiple executors.
     */
    void awaitShutdown(long timeoutMs) {
        try {
            if (timeoutMs > 0) writeExecutor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try { writeExecutor.shutdownNow(); }
            catch (Throwable ignored) { /* best-effort */ }
        }
    }

    /** Path for a single package's cache file at the given pixel size. */
    private File fileFor(String pkg, int iconPx) {
        return new File(dir, pkg + "-" + iconPx + EXT);
    }

    /** Path for the tmp file used during atomic writes. */
    private File tmpFileFor(String pkg, int iconPx) {
        return new File(dir, pkg + "-" + iconPx + TMP_EXT);
    }

    /**
     * One-time sweep, queued onto the write executor at construct time:
     * delete every file in {@link #dir} that does not match the current
     * naming convention. Today this targets the v1.4.0 legacy
     * {@code {pkg}.cache} format (no size suffix), which is unreachable
     * under the resolution-keyed scheme. Future format bumps can extend
     * the predicate without changing the sweep's call site.
     *
     * <p>Runs on the write executor (background, not UI) so the
     * activity's onCreate critical path is not blocked. The cost is
     * one {@code listFiles} call plus N small {@code delete}s —
     * negligible when off-UI even for a heavy install (~30 ms total
     * at 200 entries on a slow eMMC). The sweep is queued FIRST on
     * the executor, so any {@link #writeAsync} that follows runs
     * after it: a freshly-written file cannot be mistakenly deleted.
     */
    private void purgeOrphans() {
        if (shuttingDown) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(LEGACY_EXT) || n.endsWith(LEGACY_TMP_EXT)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
