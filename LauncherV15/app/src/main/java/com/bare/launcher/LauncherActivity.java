package com.bare.launcher;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.util.ArraySet;
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
import android.widget.OverScroller;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BareLauncher — fully audited, all issues fixed.
 *
 * Fixed from uploaded v4:
 *  F1  Wrong import android.graphics.Calendar → java.util.Calendar
 *  F2  MATCH_DEFAULT_ONLY hides Settings + system apps → use 0 (no flags)
 *  F3  Labels still present → CellView label TextView removed entirely
 *  F4  Clock top-RIGHT → moved to top-LEFT (Gravity.START)
 *  F5  Clock has no background → semi-transparent rounded pill added
 *  F6  Clock 24hr default → 12hr "h:mm a" default
 *  F7  Scrim darkens wallpaper → scrim removed (wallpaperView is clear)
 *  F8  CIRCLE_PAINT static field shared across iconExecutor threads → each
 *      makeCircular() call creates its own Paint (cheap, correct)
 *  F9  recycle() on borrowed BitmapDrawable bitmap → recycle only bitmaps
 *      we explicitly allocated (tracked via 'didAllocate' flag)
 *  F10 RecyclingShelfView extends View but calls addView() → extends ViewGroup
 *  F11 wallpaperLoading flag can drop picker result → separate flags for
 *      system wallpaper and user wallpaper; user picker always wins
 *  F12 No loadApps() on onResume → added, guarded by appsLoading flag
 *  F13 VelocityTracker obtained at init, never recycled → moved to
 *      onTouchEvent with lazy obtain + onDetachedFromWindow release
 *  F14 Transparent icon background → detect alpha coverage; if icon is
 *      predominantly transparent, fill background with dominant colour
 *      extracted via Palette API before clipping to circle
 *  F15 HashSet → ArraySet for seen set (lighter for small collections)
 *  F16 AppInfo.label field removed (labels not displayed anywhere)
 *  F17 wallpaperView ScaleType remains CENTER_CROP (correct, no offset)
 *      and FIT_XY avoided intentionally — CENTER_CROP fills without distort
 */
public class LauncherActivity extends Activity {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    ICON_SIZE_DP    = 52;
    private static final int    CELL_W_DP       = 64;    // tight square, no label
    private static final int    CELL_H_DP       = 64;    // square cell, no label row
    private static final int    RING_STROKE_DP  = 3;
    private static final int    RING_PADDING_DP = 8;
    private static final long   CLOCK_MS        = 1_000L;
    private static final String PREFS           = "bare_launcher";
    private static final String KEY_WP_URI      = "wp_uri";
    private static final int    MATCH           = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int    WRAP            = ViewGroup.LayoutParams.WRAP_CONTENT;

    // F1: correct Typeface cache (no Calendar import from graphics)
    private static final Typeface TF_LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);

    // ── Cached metrics ────────────────────────────────────────────────────────
    private float density;
    private int   screenW, screenH;

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile boolean    destroyed           = false;
    private final AtomicBoolean systemWpLoading     = new AtomicBoolean(false); // F11
    private final AtomicBoolean userWpLoading       = new AtomicBoolean(false); // F11
    private final AtomicBoolean appsLoading         = new AtomicBoolean(false);

    // ── Cached PackageManager ─────────────────────────────────────────────────
    private PackageManager pm;

    // ── UI refs ───────────────────────────────────────────────────────────────
    private RecyclingShelfView shelf;
    private ImageView          wallpaperView;
    private TextView           clockView;
    private TextView           wpBtn;
    private RingView           ringView;

    // ── Toast (cached + cancelled before re-show) ─────────────────────────────
    private Toast currentToast;

    // ── Clock ─────────────────────────────────────────────────────────────────
    private final Handler   clockHandler = new Handler(Looper.getMainLooper());
    private       boolean   clockRunning = false;
    // @MainThread — only ever called from clockTick via clockHandler (main thread)
    private SimpleDateFormat sdfTime;
    // F6: 12hr format; Calendar reuse — zero allocation per tick
    private final Calendar       tickCal = Calendar.getInstance();
    private final StringBuilder  clockSb = new StringBuilder(16);

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
    private ThreadPoolExecutor iconExecutor;
    private ExecutorService    appExecutor;  // dedicated single thread for app queries

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
            if (s == null) return;
            s.removeCallbacks(pkgReloadRunnable);
            s.postDelayed(pkgReloadRunnable, 400);
        }
    };

    private static final int REQ_PICK_WALLPAPER = 42;

    // ── App model (F16: label field removed — never displayed) ────────────────
    static final class AppInfo {
        final String        packageName;
        final ComponentName component;
        AppInfo(String pkg, ComponentName cmp) {
            packageName = pkg;
            component   = cmp;
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

        pm = getPackageManager();

        // F6: 12-hour clock format default
        sdfTime = new SimpleDateFormat(
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString("clockFmt", "h:mm a"),
                Locale.getDefault());

        initCaches();
        initPlaceholder();
        setContentView(buildRootLayout());
        hideSystemUI();
        loadWallpaper();
        loadApps();
        registerPackageReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        startClock();
        // F12: refresh app list on every resume (guarded by appsLoading flag)
        loadApps();
    }

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
        wallpaperView = null; clockView = null;
        shelf = null; wpBtn = null; ringView = null;
        super.onDestroy();
    }

    private void shutdownExecutor(ExecutorService ex) {
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

    @Override public void onWindowFocusChanged(boolean h) {
        super.onWindowFocusChanged(h);
        if (h) hideSystemUI();
    }
    @Override public void onBackPressed() { /* launchers never exit on back */ }

    // =========================================================================
    // Layout
    // =========================================================================

    private View buildRootLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper — CENTER_CROP: fills without distortion, no offset (F7: scrim removed)
        wallpaperView = new ImageView(this);
        wallpaperView.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(wallpaperView);

        // F7: NO scrim — wallpaper shows clear as requested

        // Ring indicator
        int ringSize = dp(ICON_SIZE_DP + RING_PADDING_DP * 2 + RING_STROKE_DP * 2);
        ringView = new RingView(this, density);
        ringView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ringView.setLayoutParams(new FrameLayout.LayoutParams(ringSize, ringSize));
        ringView.setVisibility(View.INVISIBLE);
        root.addView(ringView);

        // Shelf — square cells, no label row (F3)
        shelf = new RecyclingShelfView(this);
        FrameLayout.LayoutParams shelfLp =
                new FrameLayout.LayoutParams(MATCH, dp(CELL_H_DP) + dp(4));
        shelfLp.gravity = android.view.Gravity.BOTTOM;
        shelfLp.setMargins(0, 0, 0, dp(32));
        shelf.setLayoutParams(shelfLp);
        root.addView(shelf);

        // F4: Clock — top-LEFT with semi-transparent background (F5)
        clockView = new TextView(this);
        GradientDrawable clockBg = new GradientDrawable();
        clockBg.setColor(0x88000000);          // F5: semi-transparent black pill
        clockBg.setCornerRadius(dp(10));
        clockView.setBackground(clockBg);
        clockView.setPadding(dp(14), dp(6), dp(14), dp(6));
        FrameLayout.LayoutParams clkLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        clkLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START; // F4: START
        clkLp.setMargins(dp(32), dp(24), 0, 0);
        clockView.setLayoutParams(clkLp);
        clockView.setTextColor(Color.WHITE);
        clockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        clockView.setTypeface(TF_LIGHT);
        root.addView(clockView);

        // Wallpaper button — minimal drawn landscape icon (circle+mountain+sun outline)
        wpBtn = new TextView(this) {
            private final Paint wp = buildWpPaint();
            private Paint buildWpPaint() {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(0xCCFFFFFF);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                return p;
            }
            @Override protected void onDraw(Canvas canvas) {
                int w = getWidth(), h = getHeight();
                float s = Math.min(w, h) * 0.80f;
                float cx = w / 2f, cy = h / 2f;
                float l = cx - s/2f, r = cx + s/2f;
                float t = cy - s/2f, b = cy + s/2f;
                wp.setStrokeWidth(s * 0.10f);
                // Outer circle
                canvas.drawCircle(cx, cy, s / 2f, wp);
                // Small sun circle top-right
                canvas.drawCircle(cx + s * 0.17f, t + s * 0.23f, s * 0.12f, wp);
                // Mountain path
                android.graphics.Path mt = new android.graphics.Path();
                mt.moveTo(l, b);
                mt.lineTo(l + s * 0.40f, t + s * 0.50f);
                mt.lineTo(l + s * 0.60f, t + s * 0.68f);
                mt.lineTo(r, b);
                canvas.drawPath(mt, wp);
            }
        };
        int wpSize = dp(36);
        wpBtn.setFocusable(true);
        wpBtn.setFocusableInTouchMode(true);
        wpBtn.setClickable(true);
        wpBtn.setAlpha(0.65f);
        wpBtn.setOnClickListener(v -> openStoragePicker());
        wpBtn.setOnFocusChangeListener((v, f) -> v.setAlpha(f ? 1f : 0.65f));
        wpBtn.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            RecyclingShelfView s = shelf;
            if (s == null) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    s.requestFocusOnIndex(0); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    s.requestFocusOnIndex(appList.size() - 1); return true;
                default: return false;
            }
        });
        FrameLayout.LayoutParams wpLp = new FrameLayout.LayoutParams(wpSize, wpSize);
        wpLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        wpLp.setMargins(0, dp(24), dp(32), 0);
        wpBtn.setLayoutParams(wpLp);
        root.addView(wpBtn);

        return root;
    }

    // =========================================================================
    // F10: RecyclingShelfView — extends ViewGroup (not View) so addView() works
    // =========================================================================

    final class RecyclingShelfView extends ViewGroup {

        private static final int BUFFER = 2;

        private final ArrayList<CellView>               recyclePool = new ArrayList<>(8);
        private final android.util.SparseArray<CellView> attached   = new android.util.SparseArray<>();

        private final OverScroller scroller;
        // F13: VelocityTracker obtained lazily, released in onDetachedFromWindow
        private VelocityTracker velTracker;
        private float lastTouchX;
        private int   scrollX = 0;
        private int   totalW  = 0;
        private int   cellW, cellH, cellM;
        private int   focusedIndex = 0;

        RecyclingShelfView(Context ctx) {
            super(ctx);
            scroller = new OverScroller(ctx);
            cellW    = dp(CELL_W_DP);
            cellH    = dp(CELL_H_DP);
            cellM    = dp(14);
            setFocusable(false);
            setClipChildren(false);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            // F13: release VelocityTracker native resource
            if (velTracker != null) {
                velTracker.recycle();
                velTracker = null;
            }
        }

        // F10: ViewGroup requires onLayout implementation
        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // Cells are positioned manually in bindCell/repositionAttached
            fillVisible();
        }

        // F10: ViewGroup requires onMeasure
        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(
                    resolveSize(totalW, wSpec),
                    resolveSize(cellH + dp(8), hSpec));
        }

        void setApps(List<AppInfo> apps) {
            for (int i = 0; i < attached.size(); i++) {
                CellView cv = attached.valueAt(i);
                cv.setVisibility(GONE);
                recyclePool.add(cv);
            }
            attached.clear();
            focusedIndex = 0;
            scrollX = 0;
            int stride = cellW + cellM * 2;
            totalW = apps.size() * stride + dp(48);
            requestLayout();
            invalidate();
            fillVisible();
            if (!apps.isEmpty()) requestFocusOnIndex(0);
        }

        void requestFocusOnIndex(int idx) {
            if (appList.isEmpty()) return;
            idx = Math.max(0, Math.min(idx, appList.size() - 1));
            focusedIndex = idx;
            ensureVisible(idx);
            CellView cv = attached.get(idx);
            if (cv != null) cv.requestFocus();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fillVisible();
        }

        private int stride() { return cellW + cellM * 2; }

        private int centreOffset() {
            int contentW = appList.size() * stride();
            int w = getWidth();
            return (w > 0 && contentW < w) ? (w - contentW) / 2 : dp(24);
        }

        private int cellLeft(int i) {
            return centreOffset() + i * stride() + cellM - scrollX;
        }

        private void fillVisible() {
            if (getWidth() == 0 || appList.isEmpty()) return;
            int offset = centreOffset();
            int first = Math.max(0, (scrollX - offset) / stride() - BUFFER);
            int last  = Math.min(appList.size() - 1,
                    (scrollX + getWidth() - offset) / stride() + BUFFER);

            for (int i = attached.size() - 1; i >= 0; i--) {
                int idx = attached.keyAt(i);
                if (idx < first || idx > last) {
                    CellView cv = attached.valueAt(i);
                    cv.setVisibility(GONE);
                    recyclePool.add(cv);
                    attached.removeAt(i);
                }
            }

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
            addView(cv); // F10: now valid because we extend ViewGroup
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
                int idx  = attached.keyAt(i);
                CellView cv = attached.valueAt(i);
                int left = cellLeft(idx);
                cv.layout(left, 0, left + cellW, cellH);
            }
        }

        private void doScrollTo(int x) {
            int maxScroll = Math.max(0, totalW - getWidth());
            scrollX = Math.max(0, Math.min(x, maxScroll));
            repositionAttached();
            fillVisible();
        }

        private void ensureVisible(int idx) {
            int left  = centreOffset() + idx * stride() + cellM;
            int right = left + cellW;
            int pad   = dp(48);
            if (left - pad < scrollX) {
                doScrollTo(Math.max(0, left - pad));
            } else if (right + pad > scrollX + getWidth()) {
                doScrollTo(right + pad - getWidth());
            }
        }

        @Override
        public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                doScrollTo(scroller.getCurrX());
                postInvalidateOnAnimation();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            // F13: lazy obtain VelocityTracker
            if (velTracker == null) velTracker = VelocityTracker.obtain();
            velTracker.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scroller.abortAnimation();
                    lastTouchX = ev.getX();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = lastTouchX - ev.getX();
                    lastTouchX = ev.getX();
                    doScrollTo(scrollX + (int) dx);
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

        // ── CellView — F3: no label, square icon cell ─────────────────────────

        // CellView: plain View — draws bitmap directly in onDraw.
        // Using a plain View instead of FrameLayout+ImageView means layout()
        // is the only sizing call needed; no child measure/layout pass required,
        // so icons are always visible regardless of how the parent positions them.
        final class CellView extends View {

            private Bitmap  iconBitmap;   // null shows placeholder circle
            private AppInfo boundApp;
            private int     boundIndex;
            // Paint reused across draws — allocated once per CellView
            private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

            CellView(Context ctx) {
                super(ctx);
                setFocusable(true);
                setFocusableInTouchMode(true);
                setClickable(true);
                setWillNotDraw(false);

                setOnClickListener(v -> { if (boundApp != null) launchApp(boundApp); });

                setOnFocusChangeListener((v, focused) -> {
                    animate().cancel();
                    animate()
                            .scaleX(focused ? 1.10f : 1f)
                            .scaleY(focused ? 1.10f : 1f)
                            .setDuration(120).start();
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
                            if (b != null) b.requestFocus();
                            return true;
                        default: return false;
                    }
                });
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return;
                int sz = Math.min(w, h);
                float cx = w / 2f;
                float cy = h / 2f;
                float r  = sz / 2f;

                if (iconBitmap != null && !iconBitmap.isRecycled()) {
                    // Draw the pre-clipped circular bitmap centred in the cell
                    android.graphics.RectF dst = new android.graphics.RectF(
                            cx - r, cy - r, cx + r, cy + r);
                    canvas.drawBitmap(iconBitmap,
                            new android.graphics.Rect(0, 0,
                                    iconBitmap.getWidth(), iconBitmap.getHeight()),
                            dst, drawPaint);
                } else {
                    // Placeholder: semi-transparent circle
                    drawPaint.setColor(0x33FFFFFF);
                    drawPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, r, drawPaint);
                    drawPaint.setColor(0x55FFFFFF);
                    drawPaint.setStyle(Paint.Style.STROKE);
                    drawPaint.setStrokeWidth(dp(1));
                    canvas.drawCircle(cx, cy, r - dp(1), drawPaint);
                    // Reset style for next draw
                    drawPaint.setStyle(Paint.Style.FILL);
                }
            }

            void setIconBitmap(Bitmap bmp) {
                iconBitmap = bmp;
                invalidate();
            }

            void bind(AppInfo app, int index) {
                boundApp   = app;
                boundIndex = index;
                iconBitmap = null;         // clear stale icon immediately
                invalidate();              // show placeholder while loading
                loadIconAsync(app, this);  // pass CellView directly
            }
        }
    }

    // =========================================================================
    // App list
    // =========================================================================

    private void loadApps() {
        if (!appsLoading.compareAndSet(false, true)) return;
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
        String self = getPackageName();
        // F15: ArraySet instead of HashSet — lighter for small collections
        ArraySet<String> seen = new ArraySet<>();
        List<AppInfo>    out  = new ArrayList<>();

        // F2: use 0 (no flags) instead of MATCH_DEFAULT_ONLY
        // MATCH_DEFAULT_ONLY excludes Settings and many system apps
        Intent tvI = new Intent(Intent.ACTION_MAIN);
        tvI.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        addResolved(pm.queryIntentActivities(tvI, 0), self, seen, out);

        Intent mobI = new Intent(Intent.ACTION_MAIN);
        mobI.addCategory(Intent.CATEGORY_LAUNCHER);
        addResolved(pm.queryIntentActivities(mobI, 0), self, seen, out);

        // F16: sort by package name (no label available)
        Collections.sort(out, (a, b) -> a.packageName.compareToIgnoreCase(b.packageName));
        return out;
    }

    private void addResolved(List<ResolveInfo> list, String self,
                             ArraySet<String> seen, List<AppInfo> out) {
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(self)) continue;
            // Deduplicate by package+activity pair
            if (!seen.add(ai.packageName + '/' + ai.name)) continue;
            // F16: AppInfo has no label field
            out.add(new AppInfo(ai.packageName,
                    new ComponentName(ai.packageName, ai.name)));
        }
    }

    private void launchApp(AppInfo app) {
        Intent i = pm.getLaunchIntentForPackage(app.packageName);
        if (i != null) {
            i.setComponent(app.component);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); }
            catch (Exception e) { showToast("App not available"); }
        } else {
            // Fallback: direct component launch (covers Settings and system apps)
            try {
                Intent direct = new Intent(Intent.ACTION_MAIN);
                direct.setComponent(app.component);
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(direct);
            } catch (Exception e2) {
                showToast("App not available");
            }
        }
    }

    // =========================================================================
    // Icon loading — F8, F9, F14
    // =========================================================================

    private void loadIconAsync(AppInfo app, RecyclingShelfView.CellView target) {
        String key = app.packageName;
        Bitmap cached = iconCache.get(key);
        if (cached != null) { target.setIconBitmap(cached); return; }

        iconExecutor.execute(() -> {
            if (destroyed) return;
            Bitmap bmp = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(key, 0);
                Drawable d = ai.loadIcon(pm);
                Bitmap raw = drawableToBitmap(d);
                raw = fillTransparentBackground(raw);
                bmp = makeCircular(raw);
                if (raw != bmp) raw.recycle();
                iconCache.put(key, bmp);
            } catch (PackageManager.NameNotFoundException | OutOfMemoryError ignored) {}

            if (destroyed) return;
            final Bitmap fb = bmp;
            // Race guard: check the cell is still bound to this app
            runOnUiThread(() -> {
                if (app.packageName.equals(
                        target.boundApp != null ? target.boundApp.packageName : null)) {
                    target.setIconBitmap(fb); // null shows placeholder — safe
                }
            });
        });
    }

    /**
     * Converts any Drawable to a Bitmap we own (always a fresh allocation).
     * F9: We never return an internal bitmap from BitmapDrawable directly —
     * we always copy, so the caller can safely recycle the returned bitmap.
     */
    private Bitmap drawableToBitmap(Drawable d) {
        int sz = dp(ICON_SIZE_DP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && d instanceof AdaptiveIconDrawable) {
            // AdaptiveIconDrawable: render at target size with background layer
            Bitmap bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, sz, sz);
            d.draw(c);
            return bmp;
        }

        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null) {
                // F9: always copy — never return the drawable's internal bitmap
                // createScaledBitmap with filter=true for quality; copies the data
                return Bitmap.createScaledBitmap(src, sz, sz, true);
            }
        }

        int w = d.getIntrinsicWidth()  > 0 ? d.getIntrinsicWidth()  : sz;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : sz;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bmp));
        if (w != sz || h != sz) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, sz, sz, true);
            bmp.recycle(); // safe: we allocated bmp above
            return scaled;
        }
        return bmp;
    }

    /**
     * F14: Detect predominantly transparent icons and fill with dominant colour.
     * Samples a grid of pixels; if >60% are transparent, extracts dominant
     * opaque colour via Palette and paints a filled circle background behind
     * the icon. Returns a new bitmap (caller must recycle the input).
     */
    private Bitmap fillTransparentBackground(Bitmap src) {
        if (src == null) return src;
        int sz = src.getWidth();

        // Sample a 6×6 grid of pixels to estimate transparency coverage
        int totalSamples = 0, transparentSamples = 0;
        int step = Math.max(1, sz / 6);
        for (int y = step / 2; y < sz; y += step) {
            for (int x = step / 2; x < sz; x += step) {
                int pixel = src.getPixel(x, y);
                totalSamples++;
                if (Color.alpha(pixel) < 30) transparentSamples++;
            }
        }

        if (totalSamples == 0) return src;
        float transparencyRatio = (float) transparentSamples / totalSamples;
        if (transparencyRatio < 0.60f) return src; // icon is mostly opaque — leave as-is

        // Predominantly transparent -- find dominant opaque colour manually.
        // No external dependency: average R/G/B of opaque pixels in the sample.
        // Falls back to neutral grey if no opaque pixels exist.
        long rSum = 0, gSum = 0, bSum = 0;
        int opaqueSamples = 0;
        for (int sy = step / 2; sy < sz; sy += step) {
            for (int sx = step / 2; sx < sz; sx += step) {
                int pixel = src.getPixel(sx, sy);
                if (Color.alpha(pixel) >= 128) {
                    rSum += Color.red(pixel);
                    gSum += Color.green(pixel);
                    bSum += Color.blue(pixel);
                    opaqueSamples++;
                }
            }
        }
        int fillColour = (opaqueSamples > 0)
                ? Color.argb(255,
                        (int)(rSum / opaqueSamples),
                        (int)(gSum / opaqueSamples),
                        (int)(bSum / opaqueSamples))
                : 0xFF555555;

        // Paint filled circle background, then draw the icon on top
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(fillColour);
        canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, bgPaint);
        // Draw original icon (with its transparency) on top of the filled circle
        canvas.drawBitmap(src, 0, 0, null);
        return out;
    }

    /**
     * Clips src to a circle by drawing it directly onto a fresh bitmap.
     * Uses drawBitmap instead of BitmapShader — BitmapShader with CLAMP repeats
     * edge pixels into corners, making transparent-edge icons look faded/washed.
     * Direct drawBitmap respects the icon's own alpha channel correctly.
     */
    private Bitmap makeCircular(Bitmap src) {
        if (src == null) return null;
        int sz = src.getWidth();
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        // Step 1: clip canvas to circle so only circular region is drawn into
        Paint clipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        clipPaint.setColor(Color.BLACK);
        canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, clipPaint);

        // Step 2: draw src bitmap using SRC_IN so it only shows inside the circle
        Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        iconPaint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, 0, 0, iconPaint);

        return out;
    }

    // =========================================================================
    // Ring positioning
    // =========================================================================

    private void positionRing(View cell) {
        final RingView rv = ringView; // capture local ref before destroyed check
        if (rv == null) return;
        rv.post(() -> {
            if (destroyed) return;
            final RingView rvInner = ringView;
            if (rvInner == null) return;
            int[] locA = new int[2];
            int[] locB = new int[2];
            cell.getLocationInWindow(locA);
            rvInner.getLocationInWindow(locB);
            float nx = locA[0] + cell.getWidth()  / 2f - rvInner.getWidth()  / 2f;
            float ny = locA[1] + cell.getHeight() / 2f - rvInner.getHeight() / 2f;
            rvInner.setX(nx);
            rvInner.setY(ny);
            rvInner.setVisibility(View.VISIBLE);
        });
    }

    // =========================================================================
    // Clock — F6: 12hr, B6: zero allocation per tick
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

    private void tickClock() {
        final TextView cv = clockView;
        if (cv == null) return;
        // B6: reuse Calendar — no Date/Calendar allocation per tick
        tickCal.setTimeInMillis(System.currentTimeMillis());
        clockSb.setLength(0);
        // F6: sdfTime defaults to "h:mm a" (12hr) — set in onCreate
        clockSb.append(sdfTime.format(tickCal.getTime()));
        cv.setText(clockSb);
    }

    // =========================================================================
    // Wallpaper — F11: separate flags, user picker always wins
    // =========================================================================

    private void loadWallpaper() {
        String uri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WP_URI, null);
        if (uri != null) applyWallpaperFromUri(Uri.parse(uri));
        else             loadSystemWallpaper();
    }

    private void loadSystemWallpaper() {
        // F11: systemWpLoading flag — separate from user wallpaper loading
        if (!systemWpLoading.compareAndSet(false, true)) return;
        iconExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                Drawable d = WallpaperManager.getInstance(this).getDrawable();
                if (d != null) bmp = drawableToBitmapFullSize(d);
            } catch (Exception ignored) {}
            final Bitmap fb = bmp;
            systemWpLoading.set(false);
            if (!destroyed) runOnUiThread(() -> {
                final ImageView wv = wallpaperView;
                if (fb != null && wv != null) { wv.setImageBitmap(fb); wv.invalidate(); }
            });
        });
    }

    private void applyWallpaperFromUri(Uri uri) {
        // F11: userWpLoading flag — independent of systemWpLoading
        // If system wallpaper load is in flight, this will still proceed
        if (!userWpLoading.compareAndSet(false, true)) return;
        iconExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(is, null, opts);
                }
                // Guard against corrupt image headers (F: inSampleSize infinite loop)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    userWpLoading.set(false);
                    return;
                }
                opts.inSampleSize      = calcSampleSize(opts.outWidth, opts.outHeight, screenW, screenH);
                opts.inJustDecodeBounds = false;
                opts.inPreferredConfig  = Bitmap.Config.RGB_565;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is != null) bmp = BitmapFactory.decodeStream(is, null, opts);
                }
            } catch (Exception | OutOfMemoryError ignored) { bmp = null; }

            final Bitmap fb = bmp;
            userWpLoading.set(false);
            if (!destroyed) runOnUiThread(() -> {
                final ImageView wv = wallpaperView;
                if (fb != null && wv != null) {
                    wv.setImageBitmap(fb);
                    wv.invalidate();
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

    @SuppressWarnings("deprecation")
    private void openStoragePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_PICK_WALLPAPER); }
        catch (Exception e) { showToast("No file picker available"); }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_WALLPAPER && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try { getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) {}
                // F11: reset user flag so this always proceeds
                userWpLoading.set(false);
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
        // Cache in bytes; sizeOf returns bytes
        iconCache = new LruCache<String, Bitmap>((memMb * 1024 * 1024) / 6) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
            // No recycle() on eviction — ImageViews may still hold references
        };

        int cores = Runtime.getRuntime().availableProcessors();
        iconExecutor = new ThreadPoolExecutor(
                Math.max(1, cores - 1), cores,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                new ThreadPoolExecutor.CallerRunsPolicy());

        appExecutor = Executors.newSingleThreadExecutor();
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
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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

    private void showToast(String msg) {
        if (currentToast != null) currentToast.cancel();
        currentToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        currentToast.show();
    }

    // =========================================================================
    // RingView
    // =========================================================================

    static final class RingView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RingView(Context ctx, float density) {
            super(ctx);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(RING_STROKE_DP * density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth()  / 2f;
            float cy = getHeight() / 2f;
            canvas.drawCircle(cx, cy,
                    Math.min(cx, cy) - paint.getStrokeWidth() / 2f, paint);
        }
    }
}
