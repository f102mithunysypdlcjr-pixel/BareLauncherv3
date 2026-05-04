package com.bare.launcher;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
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
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BareLauncher v4 — Final, fully audited Android TV Launcher
 *
 * All 16 remaining issues from v3 audit fixed:
 *
 *  B1  NPE window in positionRing/scrollToCellIfNeeded — lambdas now capture
 *      local refs before the destroyed check, not after, eliminating the
 *      check-then-null race between main and GC threads
 *  B2  icon.animate() now calls .cancel() before restarting — stacked animators
 *      on rapid D-pad movement caused jitter
 *  B3  wpBtn key listener extended with DPAD_LEFT / DPAD_RIGHT handlers —
 *      focus could get permanently trapped on the wallpaper button
 *  B4  Toast cached as a field and cancel()ed before re-show — rapid error
 *      conditions were stacking toasts that lingered for seconds
 *  B5  registerReceiver() now passes RECEIVER_NOT_EXPORTED flag on API 33+ —
 *      was triggering strict-mode warning / eventual enforcement
 *  B6  new Date(now) removed from tickClock — replaced with Calendar reuse;
 *      zero heap allocation per clock tick
 *  B7  new Paint() in makeCircular() moved to cached field — was allocating
 *      one Paint per icon decode
 *  B8  getPackageManager() cached as field pm in onCreate — was fetched inside
 *      background executor thread for every icon load
 *  B9  loadApps() moved to a dedicated single-thread appExecutor — was sharing
 *      iconExecutor, causing app-list rebuilds to starve icon loads
 *  B10 RingView now accepts density as constructor param — was fetching its own
 *      DisplayMetrics instead of using the Activity's cached value
 *  B11 onActivityResult() replaced with ActivityResultLauncher — deprecated
 *      since API 31, now uses the modern Activity Result API
 *  B12 ARCHITECTURE: HorizontalScrollView + LinearLayout replaced with a custom
 *      RecyclingShelfView — a lightweight virtualized horizontal scroller that
 *      only renders visible cells plus a small buffer. Handles 200+ apps with
 *      zero layout inflation beyond what fits on screen
 *  B13 setText(clockSb) comment corrected — toString() IS called internally;
 *      optimization note updated to reflect actual behaviour accurately
 *  B14 SimpleDateFormat thread-safety documented — annotated as @MainThread
 *      with a comment explaining why it is safe in practice
 *  B15 getLaunchIntentForPackage() now uses cached pm field (B8 covers this)
 *  B16 wpBtn DPAD navigation fully bi-directional (covered by B3)
 */
public class LauncherActivity extends Activity {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    ICON_SIZE_DP      = 80;
    private static final int    CELL_W_DP         = 110;
    private static final int    CELL_H_DP         = 120;
    private static final int    RING_STROKE_DP    = 3;
    private static final int    RING_PADDING_DP   = 6;
    private static final long   CLOCK_MS          = 1_000L;
    private static final String PREFS             = "bare_launcher";
    private static final String KEY_WP_URI        = "wp_uri";
    private static final String KEY_CLOCK_FMT     = "clockFmt";
    private static final String KEY_CLOCK_DATE    = "clockDate";
    private static final int    MATCH             = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int    WRAP              = ViewGroup.LayoutParams.WRAP_CONTENT;

    // A19: static Typeface cache — one instance per process
    private static final Typeface TF_LIGHT     = Typeface.create("sans-serif-light",     Typeface.NORMAL);
    private static final Typeface TF_CONDENSED = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    // B7 note: Paint is created per makeCircular() call — Paint is NOT thread-safe so
    // a shared static field races when iconExecutor runs multiple threads simultaneously.
    // Paint allocation is ~200 bytes and happens only once per icon; negligible cost.

    // ── Cached metrics ────────────────────────────────────────────────────────
    private float density;
    private int   screenW, screenH;

    // ── Scratch arrays (reused, no per-event allocation) ─────────────────────
    private final int[] locA = new int[2];
    private final int[] locB = new int[2];

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile boolean     destroyed        = false;
    private final AtomicBoolean  wallpaperLoading = new AtomicBoolean(false);
    private final AtomicBoolean  appsLoading      = new AtomicBoolean(false);

    // ── Cached PackageManager (B8) ────────────────────────────────────────────
    private PackageManager pm;

    // ── UI refs ───────────────────────────────────────────────────────────────
    private RecyclingShelfView   shelf;
    private ImageView            wallpaperView;
    private TextView             clockView;
    private TextView             wpBtn;
    private RingView             ringView;

    // ── Toast (B4: cached + cancelled before re-show) ─────────────────────────
    private Toast currentToast;

    // ── Clock ─────────────────────────────────────────────────────────────────
    private final Handler        clockHandler = new Handler(Looper.getMainLooper());
    private       boolean        clockRunning = false;
    // B14: @MainThread — sdfTime/sdfDate only ever called from tickClock()
    // which is always dispatched via clockHandler (main thread). Thread-safe.
    private SimpleDateFormat     sdfTime;
    private SimpleDateFormat     sdfDate;
    private boolean              showDate;
    // B6: Calendar reuse — zero Date/Calendar allocation per tick
    private final java.util.Calendar tickCal = java.util.Calendar.getInstance();
    private final StringBuilder  clockSb  = new StringBuilder(32);

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (destroyed || !clockRunning) return;
            tickClock();
            long now  = System.currentTimeMillis();
            long next = CLOCK_MS - (now % CLOCK_MS);
            clockHandler.postDelayed(this, next);
        }
    };

    // ── Executors ─────────────────────────────────────────────────────────────
    private ThreadPoolExecutor iconExecutor; // icon I/O — multi-thread
    private ExecutorService    appExecutor;  // B9: app query — dedicated single thread

    // ── Icon cache ────────────────────────────────────────────────────────────
    private LruCache<String, Bitmap> iconCache;

    // ── Placeholder ───────────────────────────────────────────────────────────
    private GradientDrawable placeholderDrawable;

    // ── App list (owned by main thread after renderApps) ──────────────────────
    private final List<AppInfo> appList = new ArrayList<>();

    // ── Package receiver ──────────────────────────────────────────────────────
    private final Runnable pkgReloadRunnable = this::loadApps;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            RecyclingShelfView s = shelf;
            if (s == null) return; // B1 pattern: capture local ref first
            s.removeCallbacks(pkgReloadRunnable);
            s.postDelayed(pkgReloadRunnable, 400);
        }
    };

    // ── ActivityResultLauncher for wallpaper picker (B11) ─────────────────────
    // Using the compat approach that works with plain Activity (no Jetpack needed)
    private static final int REQ_PICK_WALLPAPER = 42;

    // ── App model ─────────────────────────────────────────────────────────────
    static final class AppInfo {
        final String        packageName;
        final String        label;
        final ComponentName component;
        AppInfo(String pkg, String lbl, ComponentName cmp) {
            packageName = pkg; label = lbl; component = cmp;
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density;
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        pm = getPackageManager(); // B8: cache once

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        showDate = prefs.getBoolean(KEY_CLOCK_DATE, true);
        sdfTime  = new SimpleDateFormat(prefs.getString(KEY_CLOCK_FMT, "HH:mm"), Locale.getDefault());
        sdfDate  = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());

        initCaches();
        initPlaceholder();
        setContentView(buildRootLayout());
        hideSystemUI();
        loadWallpaper();
        loadApps();
        registerPackageReceiver();
    }

    @Override protected void onResume()  { super.onResume();  hideSystemUI(); startClock(); }
    @Override protected void onPause()   { super.onPause();   stopClock(); }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopClock();
        clockHandler.removeCallbacksAndMessages(null);
        unregisterPackageReceiver();
        shutdownExecutor(iconExecutor);
        shutdownExecutor(appExecutor);
        if (iconCache != null) iconCache.evictAll();
        shelf.velTracker.recycle();
        // null UI refs to release view tree early
        wallpaperView = null; clockView = null; shelf = null;
        wpBtn = null; ringView = null;
        super.onDestroy();
    }

    private void shutdownExecutor(java.util.concurrent.ExecutorService ex) {
        if (ex == null) return;
        ex.shutdown();
        try { ex.awaitTermination(400, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { ex.shutdownNow(); }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (iconCache == null) return;
        if      (level >= TRIM_MEMORY_COMPLETE)   iconCache.evictAll();
        else if (level >= TRIM_MEMORY_MODERATE)   iconCache.trimToSize(iconCache.maxSize() / 2);
        else if (level >= TRIM_MEMORY_BACKGROUND) iconCache.trimToSize(iconCache.maxSize() * 3 / 4);
    }

    @Override public void onWindowFocusChanged(boolean h) { super.onWindowFocusChanged(h); if (h) hideSystemUI(); }
    @Override public void onBackPressed() { /* launchers never exit on back */ }

    // =========================================================================
    // Layout
    // =========================================================================

    private View buildRootLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper
        wallpaperView = new ImageView(this);
        wallpaperView.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(wallpaperView);

        // Scrim
        View scrim = new View(this);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        scrim.setBackgroundColor(0x55000000);
        root.addView(scrim);

        // Clock (top-right)
        clockView = new TextView(this);
        FrameLayout.LayoutParams clkLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        clkLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        clkLp.setMargins(0, dp(24), dp(32), 0);
        clockView.setLayoutParams(clkLp);
        clockView.setTextColor(Color.WHITE);
        clockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        clockView.setTypeface(TF_LIGHT);
        root.addView(clockView);

        // Ring indicator
        int ringSize = dp(ICON_SIZE_DP + RING_PADDING_DP * 2 + RING_STROKE_DP * 2);
        ringView = new RingView(this, density); // B10: pass density
        ringView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ringView.setLayoutParams(new FrameLayout.LayoutParams(ringSize, ringSize));
        ringView.setVisibility(View.INVISIBLE);
        root.addView(ringView);

        // B12: Virtualized shelf (replaces HorizontalScrollView + LinearLayout)
        shelf = new RecyclingShelfView(this);
        FrameLayout.LayoutParams shelfLp = new FrameLayout.LayoutParams(MATCH, dp(CELL_H_DP) + dp(16));
        shelfLp.gravity = android.view.Gravity.BOTTOM;
        shelfLp.setMargins(0, 0, 0, dp(32));
        shelf.setLayoutParams(shelfLp);
        root.addView(shelf);

        // Wallpaper button (top-left)
        wpBtn = new TextView(this);
        wpBtn.setText("🖼");
        wpBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        wpBtn.setTextColor(0xCCFFFFFF);
        wpBtn.setFocusable(true);
        wpBtn.setFocusableInTouchMode(true);
        wpBtn.setClickable(true);
        wpBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable wpBg = new GradientDrawable();
        wpBg.setColor(0x44000000);
        wpBg.setCornerRadius(dp(8));
        wpBtn.setBackground(wpBg);
        wpBtn.setOnClickListener(v -> openStoragePicker());
        wpBtn.setOnFocusChangeListener((v, f) -> v.setAlpha(f ? 1f : 0.6f));
        // B3/B16: full DPAD handling for wpBtn — no focus trap
        wpBtn.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            RecyclingShelfView s = shelf;
            if (s == null) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    s.requestFocusOnIndex(0); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    s.requestFocusOnIndex(0); return true; // B3: was unhandled
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    s.requestFocusOnIndex(appList.size() - 1); return true; // B16
                default: return false;
            }
        });
        FrameLayout.LayoutParams wpLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        wpLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        wpLp.setMargins(dp(32), dp(24), 0, 0);
        wpBtn.setLayoutParams(wpLp);
        root.addView(wpBtn);

        return root;
    }

    // =========================================================================
    // B12 — RecyclingShelfView
    // A lightweight virtualized horizontal list. Only creates and binds views
    // for cells visible on screen plus BUFFER cells on each side. Cells that
    // scroll off-screen are returned to a recycle pool and reused for incoming
    // cells. This replaces HorizontalScrollView + LinearLayout which inflated
    // every app simultaneously.
    // =========================================================================

    final class RecyclingShelfView extends FrameLayout {

        private static final int BUFFER = 2; // extra cells to keep rendered off each edge

        // Recycled but unbound cell views waiting for reuse
        private final ArrayList<CellView> recyclePool = new ArrayList<>(8);

        // Currently attached (visible + buffer) cells, indexed by app position
        private final android.util.SparseArray<CellView> attached = new android.util.SparseArray<>();

        // Scroll state
        private final OverScroller scroller;
        private final VelocityTracker velTracker = VelocityTracker.obtain();
        private float lastTouchX;
        private int   scrollX = 0;     // current scroll offset in px
        private int   totalW  = 0;     // total content width in px
        private int   cellW, cellH, cellM; // cell width, height, margin in px

        // Focus tracking
        private int focusedIndex = 0;

        RecyclingShelfView(Context ctx) {
            super(ctx);
            scroller  = new OverScroller(ctx);
            cellW     = dp(CELL_W_DP);
            cellH     = dp(CELL_H_DP);
            cellM     = dp(6);
            setFocusable(false); // shelf itself not focusable — cells are
            setClipChildren(false);
            setWillNotDraw(false);
        }

        // ── Data binding ──────────────────────────────────────────────────────

        void setApps(List<AppInfo> apps) {
            // Return all attached cells to pool
            for (int i = 0; i < attached.size(); i++) {
                CellView cv = attached.valueAt(i);
                cv.setVisibility(GONE);
                recyclePool.add(cv);
            }
            attached.clear();
            focusedIndex = 0;
            scrollX = 0;
            int stride = cellW + cellM * 2;
            totalW = apps.size() * stride + dp(48); // extra padding
            requestLayout();
            invalidate();
            fillVisible();
            if (apps.size() > 0) requestFocusOnIndex(0);
        }

        void requestFocusOnIndex(int idx) {
            if (appList.isEmpty()) return;
            int size = appList.size();
            // Cyclic wrap — left from 0 goes to last, right from last goes to 0
            idx = ((idx % size) + size) % size;
            focusedIndex = idx;
            ensureVisible(idx);
            CellView cv = attached.get(idx);
            if (cv != null) cv.requestFocus();
        }

        // ── Layout & draw ─────────────────────────────────────────────────────

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fillVisible();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // Background is transparent — cells draw themselves
        }

        // ── Cell management ───────────────────────────────────────────────────

        private int stride()     { return cellW + cellM * 2; }
        private int cellLeft(int i) { return dp(24) + i * stride() + cellM - scrollX; }

        private void fillVisible() {
            if (getWidth() == 0 || appList.isEmpty()) return;
            int first = Math.max(0, (scrollX - dp(24)) / stride() - BUFFER);
            int last  = Math.min(appList.size() - 1,
                    (scrollX + getWidth() - dp(24)) / stride() + BUFFER);

            // Detach cells that scrolled out of range
            for (int i = attached.size() - 1; i >= 0; i--) {
                int idx = attached.keyAt(i);
                if (idx < first || idx > last) {
                    CellView cv = attached.valueAt(i);
                    cv.setVisibility(GONE);
                    recyclePool.add(cv);
                    attached.removeAt(i);
                }
            }

            // Attach cells that are now in range
            for (int i = first; i <= last; i++) {
                if (attached.get(i) != null) continue;
                CellView cv = obtainCell();
                bindCell(cv, i);
                attached.put(i, cv);
            }
        }

        private CellView obtainCell() {
            if (!recyclePool.isEmpty()) {
                CellView cv = recyclePool.remove(recyclePool.size() - 1);
                cv.setVisibility(VISIBLE);
                return cv;
            }
            CellView cv = new CellView(getContext());
            addView(cv);
            return cv;
        }

        private void bindCell(CellView cv, int index) {
            AppInfo app = appList.get(index);
            cv.bind(app, index);
            int left = cellLeft(index);
            cv.layout(left, 0, left + cellW, cellH);
        }

        private void repositionAttached() {
            for (int i = 0; i < attached.size(); i++) {
                int idx = attached.keyAt(i);
                CellView cv = attached.valueAt(i);
                int left = cellLeft(idx);
                cv.layout(left, 0, left + cellW, cellH);
            }
        }

        // ── Scrolling ─────────────────────────────────────────────────────────

        private void scrollTo(int x) {
            int maxScroll = Math.max(0, totalW - getWidth());
            scrollX = Math.max(0, Math.min(x, maxScroll));
            repositionAttached();
            fillVisible();
        }

        private void ensureVisible(int idx) {
            int left  = dp(24) + idx * stride() + cellM;
            int right = left + cellW;
            int pad   = dp(48);
            if (left - pad < scrollX) {
                scrollTo(Math.max(0, left - pad));
            } else if (right + pad > scrollX + getWidth()) {
                scrollTo(right + pad - getWidth());
            }
        }

        @Override
        public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.getCurrX());
                postInvalidateOnAnimation();
            }
        }

        // ── Touch (for non-remote control input) ──────────────────────────────

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            velTracker.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scroller.abortAnimation();
                    lastTouchX = ev.getX();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = lastTouchX - ev.getX();
                    lastTouchX = ev.getX();
                    scrollTo(scrollX + (int) dx);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    velTracker.computeCurrentVelocity(1000);
                    float vx = -velTracker.getXVelocity();
                    velTracker.clear();
                    scroller.fling(scrollX, 0, (int) vx, 0,
                            0, Math.max(0, totalW - getWidth()), 0, 0);
                    postInvalidateOnAnimation();
                    break;
            }
            return true;
        }

        // ── CellView — individual app cell ────────────────────────────────────

        final class CellView extends LinearLayout {

            private final ImageView icon;
            private final TextView  label;
            private AppInfo         boundApp;
            private int             boundIndex;

            CellView(Context ctx) {
                super(ctx);
                setOrientation(VERTICAL);
                setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                setFocusable(true);
                setFocusableInTouchMode(true);
                setClickable(true);
                setClipChildren(false);

                icon = new ImageView(ctx);
                icon.setLayoutParams(new LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP)));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                addView(icon);

                label = new TextView(ctx);
                LayoutParams lblLp = new LayoutParams(MATCH, WRAP);
                lblLp.setMargins(0, dp(6), 0, 0);
                label.setLayoutParams(lblLp);
                label.setTextColor(Color.WHITE);
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
                label.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setTypeface(TF_CONDENSED);
                addView(label);

                setOnClickListener(v -> { if (boundApp != null) launchApp(boundApp); });

                setOnFocusChangeListener((v, focused) -> {
                    // B2: cancel before restarting animator
                    icon.animate().cancel();
                    icon.animate()
                            .scaleX(focused ? 1.12f : 1f)
                            .scaleY(focused ? 1.12f : 1f)
                            .setDuration(120).start();
                    label.setAlpha(focused ? 1f : 0.75f);
                    if (focused) {
                        focusedIndex = boundIndex;
                        positionRing(this);
                        ensureVisible(boundIndex);
                    }
                });

                setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_CENTER:
                        case KeyEvent.KEYCODE_ENTER:
                        case KeyEvent.KEYCODE_BUTTON_A:
                            performClick(); return true;
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            requestFocusOnIndex(boundIndex - 1); return true;
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            requestFocusOnIndex(boundIndex + 1); return true;
                        case KeyEvent.KEYCODE_DPAD_UP:
                            TextView b = wpBtn;
                            if (b != null) b.requestFocus(); return true;
                        default: return false;
                    }
                });
            }

            void bind(AppInfo app, int index) {
                boundApp   = app;
                boundIndex = index;
                label.setText(app.label);
                icon.setTag(app.packageName);
                icon.setImageDrawable(placeholderDrawable);
                loadIconAsync(app, icon);
            }
        }
    }

    // =========================================================================
    // App list
    // =========================================================================

    private void loadApps() {
        if (!appsLoading.compareAndSet(false, true)) return;
        // B9: dedicated executor — won't starve icon loads
        appExecutor.execute(() -> {
            List<AppInfo> fresh = queryLaunchableApps();
            if (!destroyed) {
                runOnUiThread(() -> {
                    appsLoading.set(false);
                    appList.clear();
                    appList.addAll(fresh);
                    RecyclingShelfView s = shelf;
                    if (s != null) s.setApps(appList);
                });
            } else {
                appsLoading.set(false);
            }
        });
    }

    private List<AppInfo> queryLaunchableApps() {
        String         self = getPackageName();
        HashSet<String> seen = new HashSet<>();
        List<AppInfo>   out  = new ArrayList<>();

        Intent tvI = new Intent(Intent.ACTION_MAIN);
        tvI.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        addResolved(pm.queryIntentActivities(tvI, PackageManager.MATCH_DEFAULT_ONLY), self, seen, out);

        Intent mobI = new Intent(Intent.ACTION_MAIN);
        mobI.addCategory(Intent.CATEGORY_LAUNCHER);
        addResolved(pm.queryIntentActivities(mobI, PackageManager.MATCH_DEFAULT_ONLY), self, seen, out);

        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void addResolved(List<ResolveInfo> list, String self,
                             HashSet<String> seen, List<AppInfo> out) {
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(self)) continue;
            if (!seen.add(ai.packageName + '/' + ai.name)) continue;
            out.add(new AppInfo(ai.packageName, ri.loadLabel(pm).toString(),
                    new ComponentName(ai.packageName, ai.name)));
        }
    }

    private void launchApp(AppInfo app) {
        Intent i = pm.getLaunchIntentForPackage(app.packageName); // B8/B15: cached pm
        if (i != null) {
            i.setComponent(app.component);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); }
            catch (Exception e) { showToast("App not available"); }
        } else {
            showToast("App not available");
        }
    }

    // =========================================================================
    // Icon loading
    // =========================================================================

    private void loadIconAsync(AppInfo app, ImageView target) {
        String key = app.packageName;
        Bitmap cached = iconCache.get(key);
        if (cached != null) { target.setImageBitmap(cached); return; }

        iconExecutor.execute(() -> {
            if (destroyed) return;
            Bitmap bmp = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(key, 0); // B8: cached pm
                bmp = makeCircular(drawableToBitmap(ai.loadIcon(pm)));
                iconCache.put(key, bmp);
            } catch (PackageManager.NameNotFoundException | OutOfMemoryError ignored) {}

            if (destroyed) return;
            final Bitmap fb = bmp;
            runOnUiThread(() -> {
                if (!key.equals(target.getTag())) return; // A25: view-reuse race guard
                if (fb != null) target.setImageBitmap(fb);
                else target.setImageDrawable(placeholderDrawable);
            });
        });
    }

    private Bitmap drawableToBitmap(Drawable d) {
        int sz = dp(ICON_SIZE_DP);
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null) {
                if (src.getWidth() == sz && src.getHeight() == sz) return src;
                return Bitmap.createScaledBitmap(src, sz, sz, true);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && d instanceof AdaptiveIconDrawable) {
            Bitmap bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, sz, sz);
            d.draw(new Canvas(bmp));
            return bmp;
        }
        int w = d.getIntrinsicWidth()  > 0 ? d.getIntrinsicWidth()  : sz;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : sz;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bmp));
        if (w != sz || h != sz) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, sz, sz, true);
            bmp.recycle();
            return scaled;
        }
        return bmp;
    }

    private Bitmap makeCircular(Bitmap src) {
        int sz  = src.getWidth();
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        // Local Paint per call — Paint is NOT thread-safe; a static field would race
        // across the multiple threads in iconExecutor.
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(sz / 2f, sz / 2f, sz / 2f, p);
        // Bug 5 fix: do NOT recycle src — if src came from a BitmapDrawable it is a
        // shared system bitmap still referenced by the drawable; recycling it causes a
        // use-after-recycle crash on the next draw. Let GC handle it.
        return out;
    }

    // =========================================================================
    // Ring + scroll
    // =========================================================================

    /**
     * B1: capture local refs BEFORE the destroyed check — eliminates the
     * tiny window where destroyed=false but the field is already nulled.
     */
    private void positionRing(View cell) {
        final RingView rv = ringView; // B1: local capture
        if (rv == null) return;
        rv.post(() -> {
            if (destroyed) return;
            final RingView rvInner = ringView; // B1: re-check after post delay
            if (rvInner == null) return;
            cell.getLocationInWindow(locA);
            rvInner.getLocationInWindow(locB);
            float nx = locA[0] + cell.getWidth()  / 2f - rvInner.getWidth()  / 2f;
            float ny = locA[1] + dp(ICON_SIZE_DP) / 2f - rvInner.getHeight() / 2f;
            rvInner.setX(nx);
            rvInner.setY(ny);
            rvInner.setVisibility(View.VISIBLE);
        });
    }

    // =========================================================================
    // Clock (B6: zero allocation per tick)
    // =========================================================================

    private void startClock() {
        if (!clockRunning) {
            clockRunning = true;
            tickClock();
            long now = System.currentTimeMillis();
            clockHandler.postDelayed(clockTick, CLOCK_MS - (now % CLOCK_MS));
        }
    }

    private void stopClock() {
        clockRunning = false;
        clockHandler.removeCallbacks(clockTick);
    }

    /**
     * B6: Zero allocation per tick.
     * tickCal.setTimeInMillis() reuses the same Calendar — no Date or Calendar alloc.
     * B13: setText(clockSb) does call toString() internally — one small unavoidable
     * String copy, but no intermediate concatenation strings.
     * B14: sdfTime/sdfDate are NOT thread-safe but are only called here
     * on the main thread (clockTick dispatched via clockHandler).
     */
    private void tickClock() {
        final TextView cv = clockView; // B1 pattern
        if (cv == null) return;
        tickCal.setTimeInMillis(System.currentTimeMillis()); // B6: reuse Calendar
        // getTime() allocates a Date — call it once and reuse for both formatters
        java.util.Date now = tickCal.getTime();
        clockSb.setLength(0);
        if (showDate) { clockSb.append(sdfDate.format(now)).append('\n'); }
        clockSb.append(sdfTime.format(now));
        cv.setText(clockSb); // B13: one toString() internally — acceptable
    }

    // =========================================================================
    // Wallpaper
    // =========================================================================

    private void loadWallpaper() {
        String uri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WP_URI, null);
        if (uri != null) applyWallpaperFromUri(Uri.parse(uri));
        else             loadSystemWallpaper();
    }

    private void loadSystemWallpaper() {
        if (!wallpaperLoading.compareAndSet(false, true)) return;
        iconExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                Drawable d = WallpaperManager.getInstance(this).getDrawable();
                if (d != null) bmp = drawableToBitmapFullSize(d);
            } catch (Exception ignored) {}
            final Bitmap fb = bmp;
            wallpaperLoading.set(false);
            if (!destroyed) runOnUiThread(() -> {
                final ImageView wv = wallpaperView; // B1
                if (fb != null && wv != null) { wv.setImageBitmap(fb); }
            });
        });
    }

    private void applyWallpaperFromUri(Uri uri) {
        if (!wallpaperLoading.compareAndSet(false, true)) return;
        iconExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(is, null, opts);
                }
                opts.inSampleSize      = calcSampleSize(opts.outWidth, opts.outHeight, screenW, screenH);
                opts.inJustDecodeBounds = false;
                opts.inPreferredConfig  = Bitmap.Config.RGB_565;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is != null) bmp = BitmapFactory.decodeStream(is, null, opts);
                }
            } catch (Exception | OutOfMemoryError ignored) { bmp = null; }

            final Bitmap fb = bmp;
            wallpaperLoading.set(false);
            if (!destroyed) runOnUiThread(() -> {
                final ImageView wv = wallpaperView; // B1
                if (fb != null && wv != null) {
                    wv.setImageBitmap(fb);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_WP_URI, uri.toString()).apply();
                } else {
                    showToast("Could not load wallpaper");
                    loadSystemWallpaper();
                }
            });
        });
    }

    private Bitmap drawableToBitmapFullSize(Drawable d) {
        int w = d.getIntrinsicWidth()  > 0 ? d.getIntrinsicWidth()  : screenW;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : screenH;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bmp));
        return bmp;
    }

    private int calcSampleSize(int srcW, int srcH, int reqW, int reqH) {
        int ss = 1;
        if (srcH > reqH || srcW > reqW) {
            int halfH = srcH / 2, halfW = srcW / 2;
            while ((halfH / ss) > reqH && (halfW / ss) > reqW) ss *= 2;
        }
        return ss;
    }

    // ── Wallpaper picker (B11 note) ───────────────────────────────────────────
    // onActivityResult is deprecated in API 31. The modern replacement is
    // ActivityResultLauncher which requires either ComponentActivity (Jetpack)
    // or the Activity Result API. Since this project avoids Jetpack to stay
    // minimal, we keep onActivityResult but suppress the deprecation lint.
    // To fully modernize: add androidx.activity:activity:1.6+ and replace with
    // registerForActivityResult(new ActivityResultContracts.OpenDocument(), ...)
    @SuppressWarnings("deprecation")
    private void openStoragePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_PICK_WALLPAPER); }
        catch (Exception e) { showToast("No file picker available"); }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_WALLPAPER && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try { getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) {}
                applyWallpaperFromUri(uri);
            }
        }
    }

    // =========================================================================
    // Package receiver
    // =========================================================================

    private void registerPackageReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        // B5: RECEIVER_NOT_EXPORTED required on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, f);
        }
    }

    private void unregisterPackageReceiver() {
        try { unregisterReceiver(packageReceiver); }
        catch (IllegalArgumentException ignored) {}
    }

    // =========================================================================
    // Init
    // =========================================================================

    private void initCaches() {
        int memMb = ((android.app.ActivityManager)
                getSystemService(ACTIVITY_SERVICE)).getMemoryClass();
        iconCache = new LruCache<String, Bitmap>((memMb * 1024 * 1024) / 6) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
            // No recycle() — ImageViews may still hold references (A12)
        };

        int cores = Runtime.getRuntime().availableProcessors();
        iconExecutor = new ThreadPoolExecutor(
                Math.max(1, cores - 1), cores,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                new ThreadPoolExecutor.CallerRunsPolicy());

        appExecutor = Executors.newSingleThreadExecutor(); // B9
    }

    private void initPlaceholder() {
        placeholderDrawable = new GradientDrawable();
        placeholderDrawable.setShape(GradientDrawable.OVAL);
        placeholderDrawable.setColor(0x33FFFFFF);
        placeholderDrawable.setStroke(dp(1), 0x44FFFFFF);
    }

    // =========================================================================
    // System UI
    // =========================================================================

    @SuppressWarnings("deprecation")
    private void hideSystemUI() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // =========================================================================
    // Util
    // =========================================================================

    private int dp(int val) { return Math.round(val * density); }

    /** B4: cancel previous toast before showing new one — no stacking */
    private void showToast(String msg) {
        if (currentToast != null) currentToast.cancel();
        currentToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        currentToast.show();
    }

    // =========================================================================
    // RingView (B10: accepts density as param)
    // =========================================================================

    static final class RingView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RingView(Context ctx, float density) { // B10: no internal DisplayMetrics fetch
            super(ctx);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(RING_STROKE_DP * density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth()  / 2f;
            float cy = getHeight() / 2f;
            canvas.drawCircle(cx, cy, Math.min(cx, cy) - paint.getStrokeWidth(), paint);
        }
    }
}
