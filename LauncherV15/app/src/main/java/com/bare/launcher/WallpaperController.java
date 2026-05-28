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
import android.view.View;
import android.view.animation.Interpolator;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * <h3>Memory hygiene</h3>
 * Wallpaper bitmaps are screen-sized {@link Bitmap.Config#ARGB_8888}
 * (~8 MB at 1080p, up to ~32 MB at 4K). On TV ROMs without
 * {@code largeHeap} that footprint matters. This class:
 * <ul>
 *   <li>Caps decoded size at one screen of pixels (see {@link #wpDrawable}
 *       and {@link #calcSampleSize}). The previous 2× cap could allocate
 *       ~127 MB on a 4K panel — guaranteed OOM.</li>
 *   <li>Recycles the previous FRONT bitmap synchronously after every
 *       crossfade ends. No reliance on GC.</li>
 *   <li>Recycles both ImageViews' bitmaps explicitly on destroy.</li>
 * </ul>
 *
 * <h3>Threading</h3>
 * One single-threaded background executor runs all decode work, gated by
 * {@link AtomicBoolean} loading flags so a rapid sequence of "system" /
 * "user" loads cannot stack up two decodes for the same target. Cross-
 * fade and bitmap promotion run on the UI thread.
 *
 * <p>Pulled out of {@link LauncherActivity} so the activity stops carrying
 * the wallpaper state machine. Public surface is small and lifecycle-bound:
 * {@link #loadStored()}, {@link #applyFromUri(Uri)},
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
    private final ExecutorService         executor       = Executors.newSingleThreadExecutor();
    private final AtomicBoolean           systemLoading  = new AtomicBoolean(false);
    private final AtomicBoolean           userLoading    = new AtomicBoolean(false);

    /** Volatile because the executor thread reads them inside
     *  {@link #wpDrawable} / {@link #calcSampleSize}. Without volatile,
     *  weak-memory-model CPUs could observe stale zeros and produce a
     *  1 px-tall wallpaper bitmap. */
    private volatile int     screenW;
    private volatile int     screenH;
    private volatile boolean destroyed;

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
    }

    /** Refresh cached display metrics after a configuration change (HDMI
     *  swap, font scale, multi-window). Cheap; just stores the values. */
    void onConfigurationChanged(int newScreenW, int newScreenH) {
        this.screenW = newScreenW;
        this.screenH = newScreenH;
    }

    /** Apply the user-picked URI if one is stored, otherwise fall back to
     *  the system wallpaper. Single entry point used at activity create. */
    void loadStored() {
        String uri = prefs.getString(prefKeyUri, null);
        if (uri != null) applyFromUri(Uri.parse(uri));
        else             loadSystem();
    }

    /** Load the device's current system wallpaper into FRONT. No-op if a
     *  system load is already in flight. */
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
            final Bitmap fb = bmp;
            systemLoading.set(false);
            if (!destroyed) host.runOnUiThread(() -> { if (fb != null) crossfade(fb); });
        });
    }

    /**
     * Decode a content URI on the background executor, sub-sample as
     * needed to stay within one screen of pixels, and cross-fade onto
     * FRONT. On success the URI is persisted so the activity's next
     * cold-start applies it. On decode failure, falls back to the system
     * wallpaper and surfaces a toast.
     */
    void applyFromUri(Uri uri) {
        if (!userLoading.compareAndSet(false, true)) return;
        executor.execute(() -> {
            Bitmap bmp = null;
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
                opts.inPreferredConfig  = Bitmap.Config.ARGB_8888;
                try (InputStream is = host.getContentResolver().openInputStream(uri)) {
                    if (is != null) bmp = BitmapFactory.decodeStream(is, null, opts);
                }
            } catch (Exception | OutOfMemoryError ignored) {
                bmp = null;
            }
            final Bitmap fb = bmp;
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
     */
    void onDestroy() {
        destroyed = true;
        executor.shutdown();
        try {
            executor.awaitTermination(300, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }
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
        Drawable oldBackDrawable = back.getDrawable();
        if (oldBackDrawable instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) oldBackDrawable).getBitmap();
            if (b != null && !b.isRecycled() && b != fb && b != oldBmp) b.recycle();
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
                    if (oldBmp != null && !oldBmp.isRecycled()) oldBmp.recycle();
                })
                .start();
    }

    /** Rasterise a wallpaper drawable, capped at one screen's worth of
     *  pixels. The {@link ImageView} is sized to the screen and uses
     *  {@code CENTER_CROP}, so anything larger gives no visible benefit
     *  and just risks OOM on 4K TVs. */
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
        int sw = screenW, sh = screenH;
        int ss = 1;
        while ((srcH / ss > sh || srcW / ss > sw) && ss < 0x8000) ss *= 2;
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
}
