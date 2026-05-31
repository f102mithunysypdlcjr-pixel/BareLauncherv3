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
 * Wired through the existing {@link android.util.LruCache#create(Object)}
 * extension point: when the activity's in-memory icon cache misses, the
 * platform calls {@code create(key)} on the calling thread. The
 * activity's override delegates here via {@link #tryRead}; on a hit the
 * decoded bitmap returns up through {@link android.util.LruCache#get}
 * with no further thought from the caller. On a miss the existing async
 * load path runs (PM binder + {@link IconRenderer#process}); the
 * activity calls {@link #writeAsync} after a successful decode so the
 * result lands on disk for next time.
 *
 * <h3>Atomicity and concurrency</h3>
 * Writes go through a single-thread {@link ThreadPoolExecutor} with
 * {@code allowCoreThreadTimeOut(true)} so the writer thread exits when
 * idle. Each write goes to a {@code .tmp} sibling and renames on
 * success — process death mid-write leaves only a (best-effort
 * deleted) tmp file behind, never a half-written cache file the next
 * read would see as truncated. Reads are synchronous on the calling
 * thread (typically UI for {@code create()} callbacks); the WEBP
 * decode is fast enough to not skip a frame.
 *
 * <h3>Invalidation</h3>
 * The activity's {@code packageReceiver} calls {@link #delete(String)}
 * on every {@code ACTION_PACKAGE_REPLACED} / {@code _CHANGED} /
 * {@code _REMOVED} so a package update or uninstall does not leave
 * stale icon bytes behind. {@link android.util.LruCache#remove} fires
 * for the same broadcasts so the in-memory and on-disk views stay in
 * lockstep.
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

    /** Filename suffix. We do NOT use ".webp" because that suggests
     *  to the user that the file is a generic image they could open
     *  externally; it's an internal cache artifact. ".cache" makes
     *  the role explicit. */
    private static final String EXT         = ".cache";

    /** Temp-file suffix used for atomic writes. */
    private static final String TMP_EXT     = ".cache.tmp";

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
    }

    /**
     * Synchronous disk read. Decodes to {@link Bitmap.Config#ARGB_8888}
     * because the in-memory cache has consumers that read pixel bytes
     * (e.g. icon-mutation paths in a future plate / badge feature would
     * fail on a HARDWARE config bitmap). Returns {@code null} on miss
     * or any decode failure — same null-handling the existing
     * {@code create()} path already implements.
     *
     * <p>Safe to call from the UI thread: a 5-10 KB WEBP decodes in
     * 2-5 ms on TV-class hardware. Used as the {@link
     * android.util.LruCache#create(Object)} fallback so a memory-cache
     * miss transparently becomes a disk-cache hit.
     */
    Bitmap tryRead(String pkg) {
        if (pkg == null || pkg.isEmpty() || shuttingDown) return null;
        File f = fileFor(pkg);
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
            // extra ~5ms decode failure per icon per cold start
            // until the file is overwritten by a fresh write.
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        return bmp;
    }

    /**
     * Schedule an asynchronous write of {@code bmp} for {@code pkg}.
     * Returns immediately. The executor is bounded with
     * {@code DiscardOldestPolicy} so a write storm (e.g. cold start
     * with 200 apps decoding in parallel on the icon executor) cannot
     * back up unbounded; oldest queued writes get dropped as newer
     * ones come in. For a cache, "drop oldest" is the correct policy
     * — the freshest bitmap is the one we want on disk.
     *
     * <p>HARDWARE-config bitmaps are silently skipped because
     * {@link Bitmap#compress} returns false on them (pixels are in
     * graphics memory, not addressable from the encoder). Caller is
     * expected to provide an ARGB-style bitmap; the icon pipeline's
     * {@link IconRenderer#process} returns ARGB_8888 so this is the
     * normal case.
     *
     * <p>Idempotent at the file level — successive writes for the
     * same package overwrite the existing entry atomically (tmp +
     * rename). A failed write leaves the previous entry intact (or
     * no entry, on first write).
     */
    void writeAsync(String pkg, Bitmap bmp) {
        if (pkg == null || pkg.isEmpty() || shuttingDown) return;
        if (bmp == null || bmp.isRecycled()) return;
        if (bmp.getConfig() == Bitmap.Config.HARDWARE) return;
        // Capture by reference — Bitmap is reference-counted via the
        // underlying NativeAllocation; the LruCache holds it live until
        // eviction. We're racing against eviction here: if the cache
        // evicts and recycles the bitmap before our write task runs
        // we'll detect it via isRecycled() and bail.
        final Bitmap captured = bmp;
        try {
            writeExecutor.execute(() -> writeSync(pkg, captured));
        } catch (RejectedExecutionException ignored) {
            // Executor refused (closed or full + DiscardOldest dropping
            // the new task instead of the old one is impossible per
            // policy contract, but defensive). The cache entry stays
            // at its previous value — next icon load will retry.
        }
    }

    /** Worker-thread implementation of {@link #writeAsync}. */
    private void writeSync(String pkg, Bitmap bmp) {
        if (shuttingDown || bmp == null || bmp.isRecycled()) return;
        File tmp  = new File(dir, pkg + TMP_EXT);
        File dest = fileFor(pkg);
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
     * Delete the cache entry for {@code pkg}. Called from the
     * activity's {@code packageReceiver} on every
     * {@code ACTION_PACKAGE_REPLACED} / {@code _CHANGED} /
     * {@code _REMOVED} so a stale icon does not survive a package
     * update.
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
                File f = fileFor(pkg);
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                File tmp = new File(dir, pkg + TMP_EXT);
                if (tmp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
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
     */
    void shutdown() {
        shuttingDown = true;
        writeExecutor.shutdown();
        try {
            writeExecutor.awaitTermination(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            writeExecutor.shutdownNow();
        }
    }

    /** Path for a single package's cache file. */
    private File fileFor(String pkg) {
        return new File(dir, pkg + EXT);
    }
}
