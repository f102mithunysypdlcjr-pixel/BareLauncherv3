package com.bare.launcher;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.animation.Interpolator;
import android.view.View;
import android.widget.ImageView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates everything wallpaper-related: bitmap loading, sub-sampling,
 * cross-fade animation, recycle-on-destroy, and SharedPreferences round-trip
 * for the user-picked URI.
 *
 * <h3>Why two stacked ImageViews?</h3>
 * The wallpaper sits in two stacked {@link ImageView}s permanently at the
 * bottom of the activity's root z-order. {@code wallpaperFront} is on top
 * (always), {@code wallpaperBack} sits below. To show a new bitmap we put
 * it on BACK then fade FRONT out — the user sees the new image revealed
 * from beneath. Once the fade ends the new bitmap is copied up to FRONT,
 * FRONT alpha returns to 1, and BACK is cleared.
 *
 * <p>Roles never swap. An earlier implementation called
 * {@code bringToFront()} on the freshly-loaded view and accidentally lifted
 * a wallpaper view ABOVE the shelf / clock / buttons, hiding the entire
 * home screen. The "front is always front" invariant prevents that
 * regression by construction.
 *
 * <h3>Cold-start snapshot cache (v1.4.0)</h3>
 * A WEBP-compressed copy of the last successfully-loaded user wallpaper
 * is kept in {@code filesDir/wallpaper.snap}. The activity calls
 * {@link #loadSnapshotSync()} synchronously from {@code buildLayout},
 * before the first frame paints — the bitmap appears in the very first
 * vsync without waiting for the multi-hundred-ms ContentResolver +
 * BitmapFactory pipeline. {@link #loadStored()} is then a no-op when
 * the snapshot pre-painted (the snapshot already represents the
 * user-picked wallpaper; re-decoding the same image and cross-fading
 * over it would read as visible flicker on every cold start).
 *
 * <p>Snapshots are written ONLY on a successful {@link #applyFromUri},
 * not on {@link #loadSystem}. The system path is fast on its own
 * (WallpaperManager keeps the drawable in {@code system_server} memory,
 * the call is a binder + drawable raster, ~10-30 ms) and snapshotting
 * it would introduce a "user changed system wallpaper externally"
 * staleness window we'd then have to detect and refresh. URI snapshots
 * are bulletproof because {@link #applyFromUri} is the only writer of
 * the persisted URI: a snapshot file always corresponds to that URI.
 *
 * <h3>HARDWARE bitmap config</h3>
 * Wallpaper bitmaps are decoded as {@link Bitmap.Config#HARDWARE} so the
 * pixel data lives in graphics memory (uploaded once, drawn forever) and
 * NOT on the Java heap. A 1080p wallpaper saves ~8 MB heap; a 4K
 * wallpaper saves ~32 MB. Available since API 26 (our minSdk floor
 * exactly). The conversion runs after the snapshot is written because
 * {@code Bitmap.compress} returns false on HARDWARE bitmaps — we keep
 * the ARGB intermediate just long enough to write the snapshot, then
 * promote to HARDWARE and recycle the ARGB.
 *
 * <p>Fallback: any failure of the HARDWARE conversion (rare GPU driver
 * bug, exhausted graphic-buffer pool) returns the ARGB bitmap unchanged
 * via {@link #toHardwareOrSelf} — same observable behaviour as the
 * pre-v1.4.0 code, no regression.
 *
 * <h3>Memory hygiene</h3>
 * Wallpaper bitmaps are screen-sized. This class:
 * <ul>
 *   <li>Caps decoded size at one screen of pixels (see {@link #wpDrawable}
 *       and {@link #computeSampleSize}). The pre-1.1.4 2× cap could
 *       allocate ~127 MB on a 4K panel — guaranteed OOM.</li>
 *   <li>Recycles the previous FRONT bitmap synchronously after every
 *       crossfade ends. No reliance on GC.</li>
 *   <li>Recycles both ImageViews' bitmaps explicitly on destroy.</li>
 * </ul>
 *
 * <h3>Threading</h3>
 * One single-thread {@link ThreadPoolExecutor} (with
 * {@code allowCoreThreadTimeOut(true)}) runs all decode work, gated by
 * {@link AtomicBoolean} loading flags so a rapid sequence of "system" /
 * "user" loads cannot stack up two decodes for the same target. v1.4.0
 * switched from {@link java.util.concurrent.Executors#newSingleThreadExecutor()}
 * (which kept its worker thread alive for the activity's lifetime —
 * millions of idle cycles for a thread used only during occasional
 * wallpaper changes) to a pool that lets the worker exit after the 30 s
 * keepAlive elapses; the platform recreates it on the next submit. Same
 * discipline as {@code iconExecutor} / {@code appExecutor} in the
 * activity. Cross-fade and bitmap promotion run on the UI thread.
 *
 * <p>Pulled out of {@link LauncherActivity} so the activity stops carrying
 * the wallpaper state machine. Public surface is small and lifecycle-bound:
 * {@link #loadSnapshotSync()}, {@link #loadStored()}, {@link #applyFromUri(Uri)},
 * {@link #onConfigurationChanged(int, int)}, {@link #onDestroy()}.
 */
final class WallpaperController {

    // ── Inputs from the host ──────────────────────────────────────────────
    private final Activity                host;
    private final SharedPreferences       prefs;
    private final String                  prefKeyUri;
    private final ImageView               front;
    private final ImageView               back;
    private final Interpolator            fadeEase;
    private final ToastFn                 toastFn;

    // ── Internal state ────────────────────────────────────────────────────
    private final ThreadPoolExecutor      executor;
    private final AtomicBoolean           systemLoading  = new AtomicBoolean(false);
    private final AtomicBoolean           userLoading    = new AtomicBoolean(false);

    /** Volatile because the executor thread reads them inside
     *  {@link #wpDrawable} / {@link #calcSampleSize}. Without volatile,
     *  weak-memory-model CPUs could observe stale zeros and produce a
     *  1 px-tall wallpaper bitmap. */
    private volatile int     screenW;
    private volatile int     screenH;
    private volatile boolean destroyed;

    /** Set true by {@link #loadSnapshotSync()} when the on-disk snapshot
     *  was successfully rendered into FRONT. {@link #loadStored()} reads
     *  this and short-circuits — re-decoding the URI when the snapshot
     *  already represents it would just re-render the same content and
     *  the resulting cross-fade reads as a flicker on every cold start. */
    private boolean snapshotPrePainted = false;

    // ── Snapshot constants ────────────────────────────────────────────────

    /** Snapshot file basename. Lives in {@link Activity#getFilesDir()},
     *  NOT {@code cacheDir}. Android's storage-low cleanup is allowed
     *  to wipe {@code cacheDir} at any time, which would defeat the
     *  whole point of a cold-start cache. {@code filesDir} is private
     *  app storage that survives across reboots. */
    private static final String SNAPSHOT_FILE = "wallpaper.snap";

    /** Temp file used during atomic snapshot writes. We write here, then
     *  rename to {@link #SNAPSHOT_FILE} so a process kill mid-write
     *  cannot leave a corrupt snapshot in place. */
    private static final String SNAPSHOT_TMP  = "wallpaper.snap.tmp";

    /** WEBP compression quality. 95 is visually indistinguishable from
     *  lossless for a wallpaper at TV viewing distance, ~3-5× smaller
     *  than PNG. The legacy {@link Bitmap.CompressFormat#WEBP} enum is
     *  API 17+ (lossy) and the only choice on our minSdk 26 floor;
     *  {@code WEBP_LOSSY} / {@code WEBP_LOSSLESS} require API 30+. The
     *  legacy enum routes to lossless on API 30+ at quality 100, so we
     *  pick 95 to stay in the lossy compression range across all our
     *  target APIs (consistent file size budget). */
    private static final int    SNAPSHOT_QUALITY = 95;

    /**
     * Tiny callback the host implements to surface a transient error toast
     * ("Could not load wallpaper" etc.). Kept as a single-method interface
     * so the controller doesn't depend on the activity's full surface.
     */
    interface ToastFn { void show(String msg); }

    /**
     * @param host       activity, used for {@link Activity#runOnUiThread}
     *                   and content resolver access. Snapshot of the
     *                   activity at creation time — destroy via
     *                   {@link #onDestroy()} before the activity finishes.
     * @param prefs      preferences instance to round-trip the picked URI.
     * @param prefKeyUri the SharedPreferences key under which the URI is
     *                   stored.
     * @param front      ImageView always on top of the stack (drawn last).
     * @param back       ImageView immediately below {@code front}.
     * @param screenW    initial screen width in pixels.
     * @param screenH    initial screen height in pixels.
     * @param fadeEase   interpolator for the cross-fade; the activity uses
     *                   the same easing curve as for focus animations to
     *                   keep visual vocabulary consistent.
     * @param toastFn    error-toast callback.
     */
    WallpaperController(Activity host, SharedPreferences prefs, String prefKeyUri,
                        ImageView front, ImageView back,
                        int screenW, int screenH,
                        Interpolator fadeEase, ToastFn toastFn) {
        this.host       = host;
        this.prefs      = prefs;
        this.prefKeyUri = prefKeyUri;
        this.front      = front;
        this.back       = back;
        this.screenW    = screenW;
        this.screenH    = screenH;
        this.fadeEase   = fadeEase;
        this.toastFn    = toastFn;

        // Single-thread pool with a 30 s keepAlive + core-thread timeout.
        // The previous Executors.newSingleThreadExecutor() kept its worker
        // alive for the activity's lifetime, holding ~512 KB of stack
        // through millions of idle cycles for a thread used only when
        // the user picks a wallpaper or the launcher cold-starts. The
        // pooled variant lets the worker exit after the keepAlive
        // elapses; the platform recreates it on the next submit at a
        // ~µs cost. Same discipline as iconExecutor / appExecutor in
        // the activity. ArrayBlockingQueue(4) caps the backlog (rapid
        // wallpaper-pick storms collapse via DiscardOldestPolicy — only
        // the most recent decode survives, which is what the user wants
        // anyway).
        this.executor = new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /** Refresh cached display metrics after a configuration change (HDMI
     *  swap, font scale, multi-window). Cheap; just stores the values. */
    void onConfigurationChanged(int newScreenW, int newScreenH) {
        this.screenW = newScreenW;
        this.screenH = newScreenH;
    }

    // ── Cold-start snapshot ──────────────────────────────────────────────

    /**
     * Synchronous attempt to render the on-disk snapshot directly into
     * FRONT. The activity calls this from {@code buildLayout} immediately
     * after the controller is constructed and the ImageViews are
     * attached, BEFORE the first vsync paints — the wallpaper appears
     * in the very first frame without waiting for the multi-hundred-ms
     * ContentResolver + BitmapFactory pipeline that
     * {@link #applyFromUri} kicks off in background.
     *
     * <p>Synchronous on the UI thread because:
     * <ol>
     *   <li>It runs during {@code onCreate} before the first vsync, so
     *       a ~30-50 ms blocking decode is invisible to the user.</li>
     *   <li>An async decode would create a window where the first frame
     *       paints with NO wallpaper, then a hundred ms later the
     *       bitmap "pops in" — which is exactly the regression the
     *       cache exists to fix.</li>
     * </ol>
     *
     * <p>Sets the {@link #snapshotPrePainted} flag on success so
     * {@link #loadStored()} short-circuits its URI re-decode.
     *
     * @return {@code true} if the snapshot was rendered, {@code false}
     *         if there was no snapshot file or it failed to decode.
     */
    boolean loadSnapshotSync() {
        if (front == null || destroyed) return false;
        File f = snapshotFile();
        if (!f.exists() || f.length() == 0) return false;
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            // HARDWARE bitmap: pixel data lives in graphics memory, NOT
            // on the Java heap. ~8 MB at 1080p, ~32 MB at 4K. ImageView
            // draws via Canvas.drawBitmap which on a TextureView path
            // costs nothing extra for HARDWARE config. Available since
            // API 26 (our minSdk floor). On the rare ROM where HARDWARE
            // decode fails (returns null), we fall through and return
            // false — caller proceeds with the URI/system path that has
            // its own ARGB_8888 fallback.
            opts.inPreferredConfig = Bitmap.Config.HARDWARE;
            try (FileInputStream fis = new FileInputStream(f)) {
                bmp = BitmapFactory.decodeStream(fis, null, opts);
            }
        } catch (IOException | OutOfMemoryError ignored) {
            // Snapshot is unreadable (corrupt mid-write, FS error, etc.).
            // Delete it so the next run regenerates from URI/system path
            // instead of repeatedly hitting the same bad bytes.
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        if (bmp == null) return false;
        front.setImageBitmap(bmp);
        front.setAlpha(1f);
        snapshotPrePainted = true;
        return true;
    }

    /** Apply the user-picked URI if one is stored, otherwise fall back to
     *  the system wallpaper. Single entry point used at activity create.
     *
     *  <p>Short-circuits when the snapshot was already pre-painted by
     *  {@link #loadSnapshotSync()} — the snapshot represents the
     *  user-picked wallpaper and re-decoding the same URI here would
     *  just kick off a 200-500 ms decode + cross-fade visible as a
     *  flicker on every cold start. */
    void loadStored() {
        if (snapshotPrePainted) return;
        String uri = prefs.getString(prefKeyUri, null);
        if (uri != null) applyFromUri(Uri.parse(uri));
        else             loadSystem();
    }

    /** Load the device's current system wallpaper into FRONT. No-op if a
     *  system load is already in flight. System wallpaper is NOT
     *  snapshotted (see class javadoc — it's already fast and snapshotting
     *  introduces a staleness window we'd have to detect). */
    void loadSystem() {
        if (!systemLoading.compareAndSet(false, true)) return;
        executor.execute(() -> {
            Bitmap bmp = null;
            try {
                Drawable d = WallpaperManager.getInstance(host).getDrawable();
                if (d != null) bmp = wpDrawable(d);
            } catch (Exception ignored) {
                // WallpaperManager throws SecurityException on Android TV
                // ROMs that strip the active-home implicit grant. Fall
                // through with bmp == null — the cross-fade just no-ops.
            }
            // Promote the ARGB to HARDWARE for display. The ARGB is
            // recycled inside the helper on success.
            final Bitmap fb = toHardwareOrSelf(bmp);
            systemLoading.set(false);
            if (!destroyed) host.runOnUiThread(() -> { if (fb != null) crossfade(fb); });
        });
    }

    /**
     * Decode a content URI on the background executor, sub-sample as
     * needed to stay within one screen of pixels, and cross-fade onto
     * FRONT. On success the URI is persisted so the activity's next
     * cold-start applies it, AND the decoded bitmap is written to the
     * snapshot file so the next cold start can render it instantly.
     * On decode failure, falls back to the system wallpaper and
     * surfaces a toast.
     */
    void applyFromUri(Uri uri) {
        if (!userLoading.compareAndSet(false, true)) return;
        executor.execute(() -> {
            Bitmap argb = null;
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try (InputStream is = host.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(is, null, opts);
                }
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    userLoading.set(false);
                    return;
                }
                opts.inSampleSize       = calcSampleSize(opts.outWidth, opts.outHeight);
                opts.inJustDecodeBounds = false;
                // Decode as ARGB_8888 first (NOT HARDWARE) because we need
                // to compress() the bitmap to write the snapshot, and
                // Bitmap.compress returns false on HARDWARE config. After
                // the snapshot is on disk we promote to HARDWARE for
                // display via toHardwareOrSelf — the ARGB intermediate
                // is recycled inside that helper.
                opts.inPreferredConfig  = Bitmap.Config.ARGB_8888;
                try (InputStream is = host.getContentResolver().openInputStream(uri)) {
                    if (is != null) argb = BitmapFactory.decodeStream(is, null, opts);
                }
            } catch (Exception | OutOfMemoryError ignored) {
                argb = null;
            }
            // Snapshot write happens BEFORE HARDWARE conversion. Best-
            // effort: a write failure does not block the user's wallpaper
            // change — it just means the next cold start does a full
            // URI decode instead of an instant snapshot render.
            if (argb != null && !destroyed) writeSnapshotBestEffort(argb);
            // Promote to HARDWARE for display. Recycles the ARGB on
            // success; falls through with the ARGB unchanged if the
            // platform fails the conversion (rare GPU driver issue).
            final Bitmap fb = toHardwareOrSelf(argb);
            userLoading.set(false);
            if (!destroyed) host.runOnUiThread(() -> {
                if (fb != null) {
                    crossfade(fb);
                    prefs.edit().putString(prefKeyUri, uri.toString()).apply();
                } else {
                    if (toastFn != null) toastFn.show(host.getString(R.string.toast_wallpaper_load_failed));
                    loadSystem();
                }
            });
        });
    }

    /** Reset the user-loading guard. Used by the activity when the system
     *  wallpaper picker returned a result and we need to allow a fresh
     *  load even if a previous one was still flagged in-flight. */
    void resetUserLoadingGuard() { userLoading.set(false); }

    /**
     * Tear down: shut the executor, recycle held bitmaps, drop view refs.
     * Idempotent — safe to call from {@code onDestroy} after the activity
     * has already cleaned up its own view tree.
     *
     * <p>Equivalent to calling {@link #beginShutdown()} immediately
     * followed by {@link #awaitShutdown(long)} with a 300 ms budget,
     * then {@link #releaseBitmaps()}. Prefer the multi-phase API in
     * callers that need to overlap shutdowns of several executors so
     * the wall-clock cap is shared.
     */
    void onDestroy() {
        beginShutdown();
        awaitShutdown(300);
        releaseBitmaps();
    }

    /**
     * Phase 1 of a parallel shutdown: flip the destroyed flag and call
     * {@code shutdown()} on the wallpaper executor. Returns immediately.
     * Pair with {@link #awaitShutdown(long)} to bound the wait, and with
     * {@link #releaseBitmaps()} to free the held bitmaps and drawable
     * references on the ImageViews.
     *
     * <p>Idempotent — safe to call multiple times.
     */
    void beginShutdown() {
        destroyed = true;
        try { executor.shutdown(); }
        catch (Throwable ignored) { /* best-effort */ }
    }

    /**
     * Phase 2 of a parallel shutdown: wait up to {@code timeoutMs} for
     * the wallpaper decode (if any) to complete, then force-stop with
     * {@code shutdownNow}. Pair with {@link #beginShutdown()} so the
     * wall-clock cap is shared across multiple executors.
     */
    void awaitShutdown(long timeoutMs) {
        try {
            if (timeoutMs > 0) executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try { executor.shutdownNow(); }
            catch (Throwable ignored) { /* best-effort */ }
        }
    }

    /**
     * Phase 3 of a parallel shutdown: recycle the bitmaps held by the
     * two ImageViews and clear their drawable references. Safe to call
     * after {@link #awaitShutdown(long)} so any pending UI runnable from
     * an in-flight cross-fade has had a chance to land first (those
     * runnables short-circuit on {@code destroyed} so order is not
     * strictly required, but the released-then-set sequence avoids any
     * window where a recycled bitmap is still referenced by an
     * ImageView's drawable).
     */
    void releaseBitmaps() {
        recycleImageViewBitmap(front);
        recycleImageViewBitmap(back);
        if (front != null) front.setImageDrawable(null);
        if (back  != null) back .setImageDrawable(null);
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** Cross-fade implementation. See class-level javadoc for the role
     *  invariant ("front is always front"). */
    private void crossfade(Bitmap fb) {
        if (front == null || back == null || fb == null) return;
        boolean coldStart = front.getDrawable() == null;
        if (coldStart) {
            front.setImageBitmap(fb);
            front.setAlpha(1f);
            return;
        }
        // Cancel any in-flight fade so rapid wallpaper changes don't leave
        // a half-faded view on screen.
        front.animate().cancel();
        back.animate().cancel();

        // Capture the bitmap currently on FRONT so we can recycle it after
        // promotion. Skip recycle if it happens to be the same instance as
        // the new bitmap (wallpaper observer resending the same drawable).
        final Bitmap oldBmp;
        Drawable oldDrawable = front.getDrawable();
        if (oldDrawable instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) oldDrawable).getBitmap();
            oldBmp = (b != null && !b.isRecycled() && b != fb) ? b : null;
        } else {
            oldBmp = null;
        }
        // Recycle the bitmap that was on BACK before the previous swap (if
        // any). After a previous cross-fade we cleared BACK's drawable to
        // null but didn't recycle its bitmap; do it now before reusing the
        // slot. In steady state this is a no-op.
        //
        // Recycle DEFERRED via {@link View#postOnAnimation} so it runs on
        // the next animation frame, AFTER the {@code back.setImageBitmap}
        // below has been applied to BACK's display list. Recycling
        // synchronously here would invalidate a bitmap whose pixels are
        // still queued for the current frame's draw — on SkiaGL TV ROMs
        // this surfaces as {@code RuntimeException: Canvas: trying to use
        // a recycled bitmap}, killing the launcher mid-cross-fade.
        Drawable oldBackDrawable = back.getDrawable();
        if (oldBackDrawable instanceof BitmapDrawable) {
            final Bitmap bRef = ((BitmapDrawable) oldBackDrawable).getBitmap();
            if (bRef != null && !bRef.isRecycled() && bRef != fb && bRef != oldBmp) {
                back.postOnAnimation(() -> {
                    if (!bRef.isRecycled()) bRef.recycle();
                });
            }
        }
        back.setImageBitmap(fb);
        back.setAlpha(1f);
        front.animate()
                .alpha(0f)
                .setDuration(200)
                .setInterpolator(fadeEase)
                .withEndAction(() -> {
                    if (destroyed) return;
                    // Promote the new bitmap up to FRONT. Role/z-order
                    // unchanged: front is still on top, back is still below.
                    front.setImageBitmap(fb);
                    front.setAlpha(1f);
                    back.setImageDrawable(null);
                    back.setAlpha(1f);
                    // Defer the recycle to the next animation frame so
                    // FRONT's display list has already been rebuilt with
                    // {@code fb} before the previous bitmap's pixels are
                    // freed. {@code setImageBitmap} only flags the view
                    // as dirty (calls invalidate); the actual display-list
                    // refresh happens at the next vsync. Recycling here
                    // synchronously could free pixels that are still being
                    // sampled by the in-flight frame on hardware-accelerated
                    // pipelines — same shape of crash as the BACK recycle
                    // above, just on the FRONT side.
                    if (oldBmp != null && !oldBmp.isRecycled()) {
                        front.postOnAnimation(() -> {
                            if (!oldBmp.isRecycled()) oldBmp.recycle();
                        });
                    }
                })
                .start();
    }

    /** Rasterise a wallpaper drawable, capped at one screen's worth of
     *  pixels. The {@link ImageView} is sized to the screen and uses
     *  {@code CENTER_CROP}, so anything larger gives no visible benefit
     *  and just risks OOM on 4K TVs. Returned bitmap is ARGB_8888 —
     *  callers that want HARDWARE config promote via
     *  {@link #toHardwareOrSelf}. */
    private Bitmap wpDrawable(Drawable d) {
        int sw = screenW, sh = screenH;
        int w = d.getIntrinsicWidth()  > 0 ? Math.min(d.getIntrinsicWidth(),  sw) : sw;
        int h = d.getIntrinsicHeight() > 0 ? Math.min(d.getIntrinsicHeight(), sh) : sh;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bmp));
        return bmp;
    }

    /** Match {@link #wpDrawable}'s cap: stop sub-sampling once we're at
     *  ~1× the display. Halves {@code inSampleSize} until the source
     *  fits the screen on BOTH axes. The previous {@code &&} short-
     *  circuited the loop the moment one axis fit, leaving extreme
     *  aspect-ratio sources (e.g. a 4000 × 500 panorama on a 1920 × 1080
     *  panel) at full source resolution and burning ~8 MB of bitmap
     *  memory that {@code CENTER_CROP} immediately scaled away. {@code ||}
     *  is the right operator: keep halving while EITHER dimension still
     *  exceeds the screen. */
    private int calcSampleSize(int srcW, int srcH) {
        return computeSampleSize(srcW, srcH, screenW, screenH);
    }

    /**
     * Pure function — sub-sampling factor (1, 2, 4, 8, …) for an image of
     * {@code srcW × srcH} pixels rendered into an {@code screenW × screenH}
     * viewport with {@code CENTER_CROP}. Loop halves while EITHER axis
     * still exceeds the screen, with a {@code 0x8000} safety cap so a
     * pathological input (zero or near-zero screen dim) cannot infinite-
     * loop. Package-private and static so it can be exercised by JVM
     * unit tests — see {@code WallpaperControllerSampleSizeTest}, which
     * pins the 1.1.4 panorama-OOM regression.
     */
    static int computeSampleSize(int srcW, int srcH, int screenW, int screenH) {
        int ss = 1;
        while ((srcH / ss > screenH || srcW / ss > screenW) && ss < 0x8000) ss *= 2;
        return ss;
    }

    /** Recycle the bitmap currently held by an ImageView, if any. Used on
     *  destroy to free wallpaper memory immediately rather than waiting
     *  for GC. The caller must clear the ImageView's drawable reference
     *  right after, so no consumer holds a live reference to the bitmap
     *  when this returns. */
    private static void recycleImageViewBitmap(View iv) {
        if (!(iv instanceof ImageView)) return;
        Drawable d = ((ImageView) iv).getDrawable();
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    /** Promote an ARGB bitmap to {@link Bitmap.Config#HARDWARE} for
     *  display, recycling the original on success. Returns the input
     *  unchanged if the platform fails the conversion (rare GPU driver
     *  bug) so the caller's contract — "this method returns a bitmap
     *  ready to setImageBitmap" — is preserved. The fallback path
     *  matches the pre-v1.4.0 behaviour exactly: ARGB bitmap on
     *  ImageView, no regression. */
    private static Bitmap toHardwareOrSelf(Bitmap argb) {
        if (argb == null) return null;
        if (argb.getConfig() == Bitmap.Config.HARDWARE) return argb;
        try {
            Bitmap hw = argb.copy(Bitmap.Config.HARDWARE, false);
            if (hw != null) {
                argb.recycle();
                return hw;
            }
        } catch (Throwable ignored) {
            // GPU-side failure — fall through and return the ARGB as-is.
            // ImageView accepts both configs; user sees no difference
            // beyond the ~8-32 MB of Java heap we didn't save.
        }
        return argb;
    }

    /** Path to the snapshot file on internal storage. Cheap: filesDir is
     *  cached by the platform's Context. */
    private File snapshotFile() {
        return new File(host.getFilesDir(), SNAPSHOT_FILE);
    }

    /** Path to the snapshot temp file for atomic write. */
    private File snapshotTmpFile() {
        return new File(host.getFilesDir(), SNAPSHOT_TMP);
    }

    /**
     * Atomically write an ARGB bitmap to the snapshot file. Best-effort:
     * any failure leaves the existing snapshot (if any) untouched and
     * silently returns. Snapshots are pure optimisation; their absence
     * just means the next cold start does a full URI decode.
     *
     * <p>Atomicity: write to {@code wallpaper.snap.tmp}, then rename to
     * {@code wallpaper.snap}. A process kill mid-compress leaves only
     * the tmp file behind (which {@link #loadSnapshotSync} ignores by
     * looking only at the canonical name). A failed rename leaves the
     * tmp file leaked — best-effort delete on the failure path so it
     * doesn't accumulate across crashes.
     *
     * <p>Caller MUST pass an ARGB-style config (not HARDWARE) — the
     * legacy {@link Bitmap#compress} returns false on HARDWARE bitmaps
     * because their pixels are in graphics memory and not addressable
     * from the encoder.
     */
    private void writeSnapshotBestEffort(Bitmap argb) {
        if (argb == null || argb.isRecycled()) return;
        if (argb.getConfig() == Bitmap.Config.HARDWARE) return;
        File dir = host.getFilesDir();
        if (dir == null) return;
        File tmp  = snapshotTmpFile();
        File dest = snapshotFile();
        boolean wrote = false;
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            //noinspection deprecation -- WEBP enum is the only choice on minSdk 26;
            // WEBP_LOSSY/LOSSLESS require API 30+.
            wrote = argb.compress(Bitmap.CompressFormat.WEBP, SNAPSHOT_QUALITY, out);
        } catch (IOException ignored) {
            wrote = false;
        }
        if (!wrote) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
