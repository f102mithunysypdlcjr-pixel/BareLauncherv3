package com.bare.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.ArraySet;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LauncherActivity — hardened and optimized.
 *
 * v16 fixes over v15:
 * - Bug fix: PackageManager.MATCH_ALL guarded behind API 23 check (was crashing on API 21/22).
 * - Bug fix: initPlaceholder() wrapped in try/catch OutOfMemoryError.
 * - Bug fix: makeWallpaperIcon() wrapped in try/catch OutOfMemoryError; returns null safely.
 * - Bug fix: onTrimMemory race condition — lastSig reset only when loading is idle.
 * - drawableToBitmap: OOM guard added around Bitmap.createBitmap calls.
 * - makeCircular: OOM guard added.
 *
 * v15 notes:
 * - Single cache: roundIconsCache only. Raw bitmaps GC'd after circular conversion.
 * - No RecyclerView, no Kotlin, no external dependencies — pure Android SDK.
 * - ArraySet for package deduplication (lighter than HashSet for small sets).
 * - DiscardOldestPolicy: never blocks UI thread under load pressure.
 * - HorizontalScrollView + LinearLayout: fastest possible scroll for a launcher shelf.
 */
public class LauncherActivity extends Activity {

    private static final int REQ_PICK_WALLPAPER = 1;

    // UI
    private ImageView wallpaperView;
    private HorizontalScrollView scroll;
    private LinearLayout row;
    private TextView clockView;
    private View wpBtnRef;

    // Executor: bounded pool, discard oldest if full
    private final ExecutorService bgApps = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    // Single cache: final circular bitmaps only
    private LruCache<String, Bitmap> roundIconsCache;

    // State
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final Object lastSigLock = new Object();
    private String lastSig = "";
    private volatile boolean destroyed = false;

    // Density & sizes
    private float density;
    private int ICON_SIZE, CELL_W, CELL_H, GAP, RING_STROKE, RING_PADDING;

    // Clock
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable clockTick;
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final Date clockDate = new Date();
    private volatile boolean clockRunning = false;

    // Placeholder shown while icons load
    private Drawable placeholderDrawable;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);

        density      = getResources().getDisplayMetrics().density;
        ICON_SIZE    = dp(64);
        RING_PADDING = dp(6);
        RING_STROKE  = dp(3);
        CELL_W       = dp(84);
        CELL_H       = dp(84);
        GAP          = dp(14);

        initPlaceholder();
        initCaches();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper layer
        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaperView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Icon shelf
        scroll = new HorizontalScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(20), dp(16), dp(20), dp(16));
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        scroll.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams shelfLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        shelfLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        shelfLp.bottomMargin = dp(28);
        root.addView(scroll, shelfLp);

        // Wallpaper button (top-right)
        ImageView wpBtn = new ImageView(this);
        wpBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        wpBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        wpBtn.setImageDrawable(makeWallpaperIcon());
        wpBtn.setClickable(true);
        wpBtn.setFocusable(true);
        wpBtn.setFocusableInTouchMode(true);
        wpBtn.setAlpha(0.70f);
        wpBtn.setOnClickListener(v -> openStoragePicker());
        wpBtn.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            v.setAlpha(focused ? 1f : 0.70f);
            v.animate().scaleX(focused ? 1.2f : 1f)
                    .scaleY(focused ? 1.2f : 1f)
                    .setDuration(120).start();
        });
        wpBtn.setOnKeyListener((v, key, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                openStoragePicker();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                LinearLayout safeRow = row;
                if (safeRow != null && safeRow.getChildCount() > 0) {
                    safeRow.getChildAt(0).requestFocus();
                    return true;
                }
            }
            return false;
        });

        int btnSize = dp(52);
        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(btnSize, btnSize);
        btnLp.gravity = Gravity.TOP | Gravity.END;
        btnLp.topMargin  = dp(18);
        btnLp.rightMargin = dp(18);
        root.addView(wpBtn, btnLp);
        wpBtnRef = wpBtn;

        // Clock (top-left)
        clockView = new TextView(this);
        clockView.setTextColor(Color.WHITE);
        clockView.setTextSize(26f);
        clockView.setTypeface(null, Typeface.BOLD);
        clockView.setPadding(dp(10), dp(5), dp(10), dp(5));
        clockView.setFocusable(false);
        GradientDrawable clockBg = new GradientDrawable();
        clockBg.setShape(GradientDrawable.RECTANGLE);
        clockBg.setCornerRadius(dp(8));
        clockBg.setColor(0x99222222);
        clockView.setBackground(clockBg);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clockLp.gravity = Gravity.TOP | Gravity.START;
        clockLp.setMargins(dp(20), dp(20), 0, 0);
        root.addView(clockView, clockLp);

        setContentView(root);

        initClockTick();
        clockRunning = true;
        clockHandler.post(clockTick);

        loadWallpaper();
        load();
    }

    // ── Placeholder ───────────────────────────────────────────────────────────

    private void initPlaceholder() {
        // FIX: OOM guard — low-memory devices can fail Bitmap.createBitmap
        try {
            int size = ICON_SIZE;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF444444);
            c.drawCircle(size / 2f, size / 2f, size / 2f, p);
            placeholderDrawable = new BitmapDrawable(getResources(), bmp);
        } catch (OutOfMemoryError ignored) {
            placeholderDrawable = null; // icons will just show blank until loaded
        }
    }

    // ── Caches ────────────────────────────────────────────────────────────────

    private void initCaches() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        int memoryClassMb = (am != null) ? am.getMemoryClass() : 128;
        int roundCacheKb  = Math.max(1024, memoryClassMb * 1024 / 16);

        roundIconsCache = new LruCache<String, Bitmap>(roundCacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return (value != null) ? Math.max(1, value.getByteCount() / 1024) : 1;
            }
            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                // Do NOT recycle here — bitmap may still be displayed in an ImageView
            }
        };
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) immersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!clockRunning && clockTick != null) {
            clockRunning = true;
            clockHandler.post(clockTick);
        }
        load();
    }

    @Override
    public void onBackPressed() { /* swallow — launcher never exits */ }

    @Override
    protected void onPause() {
        super.onPause();
        if (clockRunning && clockTick != null) {
            clockRunning = false;
            clockHandler.removeCallbacks(clockTick);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE) {
            if (roundIconsCache != null) {
                roundIconsCache.trimToSize(roundIconsCache.size() / 2);
            }
            // FIX: Only reset lastSig when no load is in progress to avoid race
            // where background thread is writing to cache while we evict it
            if (loading.compareAndSet(false, false)) {
                synchronized (lastSigLock) { lastSig = ""; }
            }
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();

        clockRunning = false;
        if (clockTick != null) {
            clockHandler.removeCallbacks(clockTick);
            clockTick = null;
        }
        clockView = null;

        bgApps.shutdown();
        try {
            if (!bgApps.awaitTermination(300, TimeUnit.MILLISECONDS)) bgApps.shutdownNow();
        } catch (InterruptedException ignored) {
            bgApps.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (roundIconsCache != null) roundIconsCache.evictAll();

        wallpaperView = null;
        row           = null;
        scroll        = null;
        wpBtnRef      = null;
    }

    // ── Clock ─────────────────────────────────────────────────────────────────

    private void initClockTick() {
        clockTick = new Runnable() {
            @Override
            public void run() {
                TextView view = clockView;
                if (view == null || !clockRunning) return;
                clockDate.setTime(System.currentTimeMillis());
                view.setText(clockFmt.format(clockDate));
                clockHandler.postDelayed(this, 1000);
            }
        };
    }

    // ── Wallpaper ─────────────────────────────────────────────────────────────

    private void openStoragePicker() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(pick, REQ_PICK_WALLPAPER);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_WALLPAPER || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}
        getSharedPreferences("bare_wp", MODE_PRIVATE).edit().putString("wp_uri", uri.toString()).apply();
        applyWallpaperFromUri(uri);
    }

    private void applyWallpaperFromUri(Uri uri) {
        bgApps.execute(() -> {
            if (destroyed) return;
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try (InputStream s = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(s, null, opts);
                }
                int maxSide = 1920, sample = 1;
                while (Math.max(opts.outWidth, opts.outHeight) / sample > maxSide) sample *= 2;
                opts.inJustDecodeBounds = false;
                opts.inSampleSize       = sample;
                opts.inPreferredConfig  = Bitmap.Config.RGB_565;
                Bitmap bmp;
                try (InputStream s = getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(s, null, opts);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    ImageView view = wallpaperView;
                    if (view != null && bmp != null) {
                        view.setImageBitmap(bmp);
                        Toast.makeText(this, "Wallpaper set!", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "Could not load wallpaper", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadWallpaper() {
        String saved = getSharedPreferences("bare_wp", MODE_PRIVATE).getString("wp_uri", null);
        if (saved != null) {
            try {
                applyWallpaperFromUri(Uri.parse(saved));
            } catch (Exception e) {
                getSharedPreferences("bare_wp", MODE_PRIVATE).edit().remove("wp_uri").apply();
                loadSystemWallpaper();
            }
        } else {
            loadSystemWallpaper();
        }
    }

    private void loadSystemWallpaper() {
        bgApps.execute(() -> {
            if (destroyed) return;
            try {
                WallpaperManager wm = WallpaperManager.getInstance(this);
                Drawable w = wm.getDrawable();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    ImageView view = wallpaperView;
                    if (view != null && w != null) view.setImageDrawable(w);
                });
            } catch (Exception ignored) {}
        });
    }

    private Drawable makeWallpaperIcon() {
        // FIX: OOM guard — return null safely; button will just show no image
        try {
            int sz = dp(28);
            Bitmap bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            c.drawCircle(sz / 2f, sz / 2f, sz / 2f - dp(1), p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(sz * 0.65f, sz * 0.35f, dp(3), p);
            android.graphics.Path mt = new android.graphics.Path();
            mt.moveTo(sz * 0.10f, sz * 0.78f);
            mt.lineTo(sz * 0.38f, sz * 0.42f);
            mt.lineTo(sz * 0.55f, sz * 0.60f);
            mt.lineTo(sz * 0.70f, sz * 0.45f);
            mt.lineTo(sz * 0.90f, sz * 0.78f);
            mt.close();
            c.drawPath(mt, p);
            return new BitmapDrawable(getResources(), bmp);
        } catch (OutOfMemoryError ignored) {
            return null;
        }
    }

    // ── App loading ───────────────────────────────────────────────────────────

    private void load() {
        if (!loading.compareAndSet(false, true)) return;
        bgApps.execute(() -> {
            if (destroyed) { loading.set(false); return; }
            try {
                PackageManager pm = getPackageManager();

                // FIX: MATCH_ALL requires API 23 — use 0 flags on API 21/22
                int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        ? PackageManager.MATCH_ALL : 0;

                List<ResolveInfo> apps = pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                        flags);
                List<ResolveInfo> regular = pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                        flags);

                // Deduplicate: Leanback first, then Launcher-only
                ArraySet<String> seen = new ArraySet<>();
                for (ResolveInfo r : apps) seen.add(r.activityInfo.packageName);
                for (ResolveInfo r : regular) {
                    if (seen.add(r.activityInfo.packageName)) apps.add(r);
                }

                // Signature for change detection
                List<String> pkgs = new ArrayList<>(apps.size());
                for (ResolveInfo r : apps) pkgs.add(r.activityInfo.packageName);
                Collections.sort(pkgs);
                StringBuilder sig = new StringBuilder();
                for (String pkg : pkgs) sig.append(pkg).append('|');
                String newSig = sig.toString();

                synchronized (lastSigLock) {
                    if (newSig.equals(lastSig) && roundIconsCache.size() > 0) {
                        loading.set(false);
                        return;
                    }
                    if (!newSig.equals(lastSig)) roundIconsCache.evictAll();
                    lastSig = newSig;
                }

                // Build round bitmaps — raw Bitmap GC'd after conversion, not cached
                List<App> result = new ArrayList<>(apps.size());
                for (ResolveInfo r : apps) {
                    if (destroyed) break;
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName())) continue;

                    if (roundIconsCache.get(pkg) == null) {
                        try {
                            Drawable icon = r.loadIcon(pm);
                            if (icon != null) {
                                Bitmap raw = drawableToBitmap(icon, ICON_SIZE, ICON_SIZE);
                                if (raw != null) {
                                    Bitmap round = makeCircular(raw, ICON_SIZE);
                                    if (round != null) roundIconsCache.put(pkg, round);
                                    // raw not cached — let GC collect it
                                }
                            }
                        } catch (OutOfMemoryError ignored) {
                            // Skip this icon on OOM — placeholder will show
                        }
                    }

                    result.add(new App(pkg, r.activityInfo.name));
                }

                if (!destroyed && !isFinishing() && !isDestroyed()) {
                    runOnUiThread(() -> {
                        render(result);
                        loading.set(false);
                    });
                } else {
                    loading.set(false);
                }
            } catch (Exception e) {
                loading.set(false);
            }
        });
    }

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    private Bitmap drawableToBitmap(Drawable d, int width, int height) {
        if (d == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 26
                    && d instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                d.setBounds(0, 0, width, height);
                d.draw(c);
                return b;
            }
            int iw = d.getIntrinsicWidth()  > 0 ? d.getIntrinsicWidth()  : width;
            int ih = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : height;
            float scale = Math.max((float) width / iw, (float) height / ih);
            int bw = Math.max(1, Math.round(iw * scale));
            int bh = Math.max(1, Math.round(ih * scale));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, bw, bh);
            d.draw(c);
            return b;
        } catch (OutOfMemoryError ignored) {
            return null;
        }
    }

    private Bitmap makeCircular(Bitmap src, int size) {
        if (src == null) return null;
        try {
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale((float) size / src.getWidth(), (float) size / src.getHeight());
            shader.setLocalMatrix(m);
            paint.setShader(shader);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
            return out;
        } catch (OutOfMemoryError ignored) {
            return null;
        }
    }

    // ── Icon row renderer (UI thread only) ───────────────────────────────────

    private void render(List<App> apps) {
        LinearLayout safeRow = row;
        if (safeRow == null) return;
        safeRow.removeAllViews();

        for (int i = 0; i < apps.size(); i++) {
            App a   = apps.get(i);
            int idx = i;

            FrameLayout cell = new FrameLayout(this);
            cell.setFocusable(true);
            cell.setFocusableInTouchMode(true);
            cell.setClickable(true);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);

            ImageView iv = new ImageView(this);
            Bitmap roundBmp = roundIconsCache.get(a.pkg);
            if (roundBmp != null) {
                iv.setImageBitmap(roundBmp);
            } else {
                iv.setImageDrawable(placeholderDrawable);
            }
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(ICON_SIZE, ICON_SIZE);
            ilp.gravity = Gravity.CENTER;
            cell.addView(iv, ilp);

            int ringSize = ICON_SIZE + RING_PADDING * 2;
            RingView ring = new RingView(this, ringSize, RING_STROKE);
            ring.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ringSize, ringSize);
            rlp.gravity = Gravity.CENTER;
            cell.addView(ring, rlp);

            cell.setOnFocusChangeListener((v, focused) -> {
                ring.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
                v.animate().cancel();
                v.animate()
                        .scaleX(focused ? 1.15f : 1f)
                        .scaleY(focused ? 1.15f : 1f)
                        .setDuration(120).start();
                if (focused) scrollTo(v);
            });

            cell.setOnKeyListener((v, key, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                LinearLayout currentRow = row;
                if (currentRow == null) return false;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int next = (idx + 1 >= currentRow.getChildCount()) ? 0 : idx + 1;
                    View t = currentRow.getChildAt(next);
                    if (t != null) t.requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    int prev = (idx - 1 < 0) ? currentRow.getChildCount() - 1 : idx - 1;
                    View t = currentRow.getChildAt(prev);
                    if (t != null) t.requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    View wp = wpBtnRef;
                    if (wp != null) { wp.requestFocus(); return true; }
                    return false;
                }
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                    launch(a.pkg, a.cls);
                    return true;
                }
                return false;
            });

            cell.setOnClickListener(v -> launch(a.pkg, a.cls));

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(CELL_W, CELL_H);
            if (i > 0) clp.leftMargin = GAP;
            safeRow.addView(cell, clp);
        }

        // Focus first icon after layout pass
        safeRow.post(() -> {
            LinearLayout currentRow = row;
            if (currentRow != null && currentRow.getChildCount() > 0) {
                currentRow.getChildAt(0).requestFocus();
            }
        });
    }

    // ── RingView ──────────────────────────────────────────────────────────────

    private static final class RingView extends View {
        private final Paint paint;
        private final float radius;

        RingView(Context ctx, int sizePx, int strokePx) {
            super(ctx);
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokePx);
            paint.setColor(Color.WHITE);
            radius = sizePx / 2f - strokePx / 2f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, paint);
        }
    }

    // ── Immersive mode ────────────────────────────────────────────────────────

    private void immersive() {
        if (Build.VERSION.SDK_INT < 30) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            return;
        }
        WindowInsetsController w = getWindow().getInsetsController();
        if (w == null) return;
        w.hide(WindowInsets.Type.systemBars());
        w.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ── Scroll helper ─────────────────────────────────────────────────────────

    private void scrollTo(View v) {
        HorizontalScrollView safeScroll = scroll;
        if (safeScroll == null) return;
        safeScroll.post(() -> {
            HorizontalScrollView cur = scroll;
            if (cur == null) return;
            int l = v.getLeft(), r = l + v.getWidth();
            int vl = cur.getScrollX(), vr = vl + cur.getWidth();
            if (l < vl) cur.smoothScrollTo(l - dp(20), 0);
            else if (r > vr) cur.smoothScrollTo(r - cur.getWidth() + dp(20), 0);
        });
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    private void launch(String pkg, String cls) {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(pkg, cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception e1) {
            try {
                Intent fallback = getPackageManager().getLaunchIntentForPackage(pkg);
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(fallback);
                    return;
                }
            } catch (Exception ignored) {}
            Toast.makeText(this, "App not available", Toast.LENGTH_SHORT).show();
            synchronized (lastSigLock) { lastSig = ""; }
            load();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int dp(int v) {
        return Math.round(v * density);
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private static final class App {
        final String pkg, cls;
        App(String p, String c) { pkg = p; cls = c; }
    }
}
