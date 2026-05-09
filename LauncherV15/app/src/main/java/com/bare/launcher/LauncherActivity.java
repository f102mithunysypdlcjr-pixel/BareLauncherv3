package com.bare.launcher;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
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
import java.nio.ByteBuffer;
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
 * BareLauncher v6 — world's leanest Android TV 14 launcher.
 * Zero dependencies. Single file. Pure Android SDK.
 *
 * Fix log (v5 -> v6):
 *
 *  B1  drawPaint allocated as new Paint() per icon inside processIcon() on the
 *      thread pool. Now a static final cached instance (sBitmapDrawPaint).
 *
 *  B2  Wallpaper loaded on iconExecutor — large JPEG decode stole threads,
 *      blocking icon loads. Now a dedicated single-thread wpExecutor.
 *
 *  B3  CellView had 3 instance Paint fields. With 50 apps = 150 Paint objects.
 *      Now static finals shared across all cells.
 *
 *  B4  onLayout() called post(fillVisible) on every layout pass including
 *      scroll repositioning. Replaced with changed-only guard.
 *
 *  B5  onResume() did not restore focus to last focused icon. After returning
 *      from an app the TV remote lost its position. Fixed.
 *
 *  P1  Bitmap.createScaledBitmap() allocated an intermediate bitmap per icon.
 *      Now scaled directly onto the output canvas with a Matrix — one fewer
 *      allocation per icon.
 *
 *  P2  detectFillColour() called getPixel() per sample — each call crosses JNI.
 *      Now uses copyPixelsToBuffer() for a single bulk JNI read.
 *
 *  P3  iconExecutor used CallerRunsPolicy — ran icon work on UI thread when
 *      queue was full. Changed to DiscardOldestPolicy.
 *
 *  P4  iconCache sized at memMb/6 — up to 42MB on 256MB devices.
 *      Now min(memMb/8, 16) MB — enough for icons, leaves RAM for apps.
 *
 *  R1  queryIntentActivities(intent, 0) raw-int flag deprecated on API 33+.
 *      Now uses PackageManager.ResolveInfoFlags.of(0) on API 33+.
 *
 *  R2  onBackPressed() deprecated API 33+.
 *      Now registers OnBackInvokedCallback on API 33+ with legacy fallback.
 *
 *  R3  Wallpaper URI saved to prefs before verifying ContentResolver grant.
 *      Now saves URI only after bitmap loaded successfully, and aborts if
 *      takePersistableUriPermission() throws SecurityException.
 */
public class LauncherActivity extends Activity {

    // -- Constants -------------------------------------------------------------
    private static final int    ICON_SIZE_DP   = 68;
    private static final int    CELL_W_DP      = 84;
    private static final int    CELL_H_DP      = 80;
    private static final int    RING_STROKE_DP = 5;
    private static final long   CLOCK_MS       = 1_000L;
    private static final String PREFS          = "bare_launcher";
    private static final String KEY_WP_URI     = "wp_uri";
    private static final int    MATCH          = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int    WRAP           = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int    REQ_PICK_WP    = 42;

    // Fix 2: cached Paint for the fill-circle behind transparent icons.
    // Style and flags never change; only setColor() is called per use.
    // Not static because setColor() mutates state — but created once here
    // rather than inside the hot path of processIcon() on the thread pool.
    private final Paint sFillCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        sFillCirclePaint.setStyle(Paint.Style.FILL);
    }
    // sMaskPaint        : white fill circle for alpha mask
    // sIconPaint        : SRC_IN xfermode punches bitmap through mask
    // sBitmapDrawPaint  : plain FILTER_BITMAP for content bitmaps   [B1]
    // sCellBitmapPaint  : FILTER_BITMAP for icon in CellView.onDraw [B3]
    // sCellPhFill/Ring  : placeholder circle while icon loads        [B3]
    private static final Paint sMaskPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sIconPaint       = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint sBitmapDrawPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint sCellBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint sCellPhFill      = new Paint(Paint.ANTI_ALIAS_FLAG);
    // sCellPhRing is intentionally NOT static: its strokeWidth depends on runtime
    // density, which is unknown at class-load time. Mutating a static Paint from
    // initCaches() is a thread-safety violation if two Activity instances exist
    // simultaneously. Each CellView creates its own instance in its constructor.
    static {
        sMaskPaint.setColor(Color.WHITE);
        sIconPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        sCellPhFill.setStyle(Paint.Style.FILL);
        sCellPhFill.setColor(0x33FFFFFF);
    }

    private static final Typeface TF_CLOCK = Typeface.create("sans-serif", Typeface.BOLD);

    // -- Cached metrics --------------------------------------------------------
    private float density;
    private int   screenW, screenH;

    // -- State -----------------------------------------------------------------
    private volatile boolean    destroyed       = false;
    private final AtomicBoolean systemWpLoading = new AtomicBoolean(false);
    private final AtomicBoolean userWpLoading   = new AtomicBoolean(false);
    private final AtomicBoolean appsLoading     = new AtomicBoolean(false);

    // -- PackageManager --------------------------------------------------------
    private PackageManager pm;

    // -- UI refs ---------------------------------------------------------------
    private RecyclingShelfView shelf;
    private ImageView          wallpaperView;
    private TextView           clockView;
    private TextView           wpBtn;
    private RingView           ringView;
    private FrameLayout        rootLayout;

    // -- Toast -----------------------------------------------------------------
    private Toast currentToast;

    // -- Clock -----------------------------------------------------------------
    private final Handler       clockHandler = new Handler(Looper.getMainLooper());
    private       boolean       clockRunning = false;
    private       SimpleDateFormat sdfTime;
    private final Calendar      tickCal  = Calendar.getInstance();
    private final StringBuilder clockSb  = new StringBuilder(16);

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (destroyed || !clockRunning) return;
            tickClock();
            long now = System.currentTimeMillis();
            clockHandler.postDelayed(this, CLOCK_MS - (now % CLOCK_MS));
        }
    };

    // -- Executors -------------------------------------------------------------
    // B2: wpExecutor is dedicated for wallpaper; previously shared iconExecutor
    //     so a large JPEG decode could starve icon loads.
    private ThreadPoolExecutor iconExecutor;
    private ExecutorService    wpExecutor;
    private ExecutorService    appExecutor;

    // -- Icon cache ------------------------------------------------------------
    private LruCache<String, Bitmap> iconCache;

    // -- App list --------------------------------------------------------------
    private final List<AppInfo> appList = new ArrayList<>();

    // -- Package receiver ------------------------------------------------------
    private final Runnable pkgReloadRunnable = this::loadApps;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            RecyclingShelfView s = shelf;
            if (s == null) return;
            s.removeCallbacks(pkgReloadRunnable);
            s.postDelayed(pkgReloadRunnable, 400);
        }
    };

    // -- App model -------------------------------------------------------------
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

        pm = getPackageManager();

        sdfTime = new SimpleDateFormat(
                getSharedPreferences(PREFS, MODE_PRIVATE).getString("clockFmt", "h:mm a"),
                Locale.getDefault());

        initCaches();
        setContentView(buildRootLayout());
        hideSystemUI();
        loadWallpaper();
        loadApps();
        registerPackageReceiver();
        registerBackHandler();
    }

    // Fix 1: held so onPause can remove it before layout fires, preventing
    // a leak where the listener keeps shelf → Activity alive after destroy.
    private android.view.ViewTreeObserver.OnGlobalLayoutListener focusRestoreListener;

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        startClock();
        // Restore focus after returning from an app. OnGlobalLayoutListener
        // guarantees shelf.getWidth() > 0 when the callback fires, so
        // ensureVisible() scroll math is always correct.
        RecyclingShelfView s = shelf;
        if (s != null) {
            focusRestoreListener = new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    s.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    focusRestoreListener = null;
                    if (!destroyed) s.requestFocusOnIndex(s.focusedIndex);
                }
            };
            s.getViewTreeObserver().addOnGlobalLayoutListener(focusRestoreListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopClock();
        // Fix 1: remove listener if it hasn't fired — prevents a leak when
        // the Activity is destroyed before the next layout pass completes.
        RecyclingShelfView s = shelf;
        if (s != null && focusRestoreListener != null) {
            s.getViewTreeObserver().removeOnGlobalLayoutListener(focusRestoreListener);
            focusRestoreListener = null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopClock();
        clockHandler.removeCallbacksAndMessages(null);
        unregisterPackageReceiver();
        shutdownExecutor(iconExecutor);
        shutdownExecutor(wpExecutor);
        shutdownExecutor(appExecutor);
        if (iconCache != null) iconCache.evictAll();
        wallpaperView = null; clockView = null;
        shelf = null; wpBtn = null; ringView = null; rootLayout = null;
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

    // R2: register modern back handler on API 33+; legacy onBackPressed handles older.
    @SuppressWarnings("deprecation")
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> { /* launcher: swallow back */ });
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { /* launcher: swallow back — never exit */ }

    // =========================================================================
    // Layout
    // =========================================================================

    private View buildRootLayout() {
        rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        rootLayout.setBackgroundColor(Color.BLACK);

        // Layer 1: Wallpaper
        wallpaperView = new ImageView(this);
        wallpaperView.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        rootLayout.addView(wallpaperView);

        // Layer 2: Shelf
        shelf = new RecyclingShelfView(this);
        FrameLayout.LayoutParams shelfLp =
                new FrameLayout.LayoutParams(MATCH, dp(CELL_H_DP) + dp(8));
        shelfLp.gravity = android.view.Gravity.BOTTOM;
        shelfLp.setMargins(0, 0, 0, dp(28));
        shelf.setLayoutParams(shelfLp);
        rootLayout.addView(shelf);

        // Layer 3: Clock pill — top-left
        clockView = new TextView(this);
        GradientDrawable clockBg = new GradientDrawable();
        clockBg.setColor(0x88000000);
        clockBg.setCornerRadius(dp(10));
        clockView.setBackground(clockBg);
        clockView.setPadding(dp(16), dp(8), dp(16), dp(8));
        FrameLayout.LayoutParams clkLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        clkLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        clkLp.setMargins(dp(32), dp(24), 0, 0);
        clockView.setLayoutParams(clkLp);
        clockView.setTextColor(Color.WHITE);
        clockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
        clockView.setTypeface(TF_CLOCK);
        clockView.setLetterSpacing(0.03f);
        rootLayout.addView(clockView);

        // Layer 4: Wallpaper picker button — top-right
        wpBtn = new TextView(this) {
            private final Paint wpPaint;
            private final Path  mtPath = new Path();
            private int lastW = 0, lastH = 0;
            {
                wpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                wpPaint.setColor(0xCCFFFFFF);
                wpPaint.setStyle(Paint.Style.STROKE);
                wpPaint.setStrokeCap(Paint.Cap.ROUND);
                wpPaint.setStrokeJoin(Paint.Join.ROUND);
            }
            @Override protected void onDraw(Canvas canvas) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                float s  = Math.min(w, h) * 0.80f;
                float cx = w / 2f, cy = h / 2f;
                wpPaint.setStrokeWidth(s * 0.10f);
                if (w != lastW || h != lastH) {
                    lastW = w; lastH = h;
                    float l = cx - s/2f, r = cx + s/2f;
                    float t = cy - s/2f, b = cy + s/2f;
                    mtPath.rewind();
                    mtPath.moveTo(l, b);
                    mtPath.lineTo(l + s*0.40f, t + s*0.50f);
                    mtPath.lineTo(l + s*0.60f, t + s*0.68f);
                    mtPath.lineTo(r, b);
                }
                canvas.drawCircle(cx, cy, s / 2f, wpPaint);
                canvas.drawCircle(cx + s*0.17f, cy - s/2f + s*0.23f, s*0.12f, wpPaint);
                canvas.drawPath(mtPath, wpPaint);
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
        rootLayout.addView(wpBtn);

        // Layer 5 (topmost): Ring — added LAST so it draws over everything
        int iconPx   = dp(ICON_SIZE_DP);
        int strokePx = dp(RING_STROKE_DP);
        int ringSize = iconPx + strokePx * 2;
        ringView = new RingView(this, strokePx);
        ringView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ringView.setLayoutParams(new FrameLayout.LayoutParams(ringSize, ringSize));
        ringView.setVisibility(View.INVISIBLE);
        rootLayout.addView(ringView);

        return rootLayout;
    }

    // =========================================================================
    // RecyclingShelfView
    // =========================================================================

    final class RecyclingShelfView extends ViewGroup {

        private static final int BUFFER = 2;

        private final ArrayList<CellView>                recyclePool = new ArrayList<>(8);
        private final android.util.SparseArray<CellView> attached    = new android.util.SparseArray<>();

        private final OverScroller scroller;
        private VelocityTracker velTracker;
        private float lastTouchX;
        private int   scrollX = 0;
        private int   totalW  = 0;
        private final int cellW, cellH, cellM;
        int focusedIndex = 0; // B5: package-visible; read in onResume

        RecyclingShelfView(Context ctx) {
            super(ctx);
            scroller = new OverScroller(ctx);
            cellW    = dp(CELL_W_DP);
            cellH    = dp(CELL_H_DP);
            cellM    = dp(10);
            setFocusable(false);
            setClipChildren(false);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (velTracker != null) { velTracker.recycle(); velTracker = null; }
        }

        // B4: only call fillVisible when layout dimensions actually change.
        //     Previous code called post(fillVisible) unconditionally, including
        //     during the layout passes triggered by repositionAttached() during
        //     scrolling, causing redundant icon work mid-scroll.
        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (changed) post(this::fillVisible);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(
                    resolveSize(Math.max(totalW, getSuggestedMinimumWidth()), wSpec),
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
            totalW = apps.size() * (cellW + cellM * 2) + dp(48);
            requestLayout();
            if (!apps.isEmpty()) post(() -> requestFocusOnIndex(0));
        }

        void requestFocusOnIndex(int idx) {
            if (appList.isEmpty()) return;
            int size = appList.size();
            idx = ((idx % size) + size) % size;
            focusedIndex = idx;
            ensureVisible(idx);
            CellView cv = attached.get(idx);
            if (cv != null) cv.requestFocus();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            post(this::fillVisible);
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
            int stride = stride();
            int first  = Math.max(0, (scrollX - offset) / stride - BUFFER);
            int last   = Math.min(appList.size() - 1,
                    (scrollX + getWidth() - offset) / stride + BUFFER);

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
            addView(cv);
            return cv;
        }

        private void bindCell(CellView cv, int index) {
            AppInfo app = appList.get(index);
            int left    = cellLeft(index);
            int topOff  = (getMeasuredHeight() - cellH) / 2;
            cv.bind(app, index);
            cv.layout(left, topOff, left + cellW, topOff + cellH);
        }

        private void repositionAttached() {
            for (int i = 0; i < attached.size(); i++) {
                int idx     = attached.keyAt(i);
                CellView cv = attached.valueAt(i);
                int left    = cellLeft(idx);
                int topOff  = (getMeasuredHeight() - cellH) / 2;
                cv.layout(left, topOff, left + cellW, topOff + cellH);
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
            if      (left  - pad < scrollX)              doScrollTo(Math.max(0, left - pad));
            else if (right + pad > scrollX + getWidth()) doScrollTo(right + pad - getWidth());
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

        // -- CellView ----------------------------------------------------------

        final class CellView extends View {

            private Bitmap  iconBitmap;
            private AppInfo boundApp;
            int             boundIndex;

            // sCellPhRing is per-instance (not static) because its strokeWidth
            // is density-dependent and must be set after density is known.
            private final Paint cellPhRing;

            CellView(Context ctx) {
                super(ctx);
                cellPhRing = new Paint(Paint.ANTI_ALIAS_FLAG);
                cellPhRing.setStyle(Paint.Style.STROKE);
                cellPhRing.setColor(0x55FFFFFF);
                cellPhRing.setStrokeWidth(dp(1));
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
                    } else {
                        RingView rv = ringView;
                        if (rv != null) rv.setVisibility(View.INVISIBLE);
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
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                int   iconPx = dp(ICON_SIZE_DP);
                float cx     = w / 2f;
                float iconCy = iconPx / 2f + dp(4);
                if (iconBitmap != null && !iconBitmap.isRecycled()) {
                    float half = iconBitmap.getWidth() / 2f;
                    canvas.drawBitmap(iconBitmap, cx - half, iconCy - half, sCellBitmapPaint);
                } else {
                    float r = iconPx / 2f - dp(2);
                    canvas.drawCircle(cx, iconCy, r, sCellPhFill);
                    canvas.drawCircle(cx, iconCy, r - dp(1) / 2f, cellPhRing);
                }
            }

            void setIconBitmap(Bitmap bmp) { iconBitmap = bmp; invalidate(); }

            void bind(AppInfo app, int index) {
                boundApp   = app;
                boundIndex = index;
                iconBitmap = null;
                invalidate();
                loadIconAsync(app, this);
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
        ArraySet<String> seen = new ArraySet<>();
        List<AppInfo>    out  = new ArrayList<>();

        // R1: use typed ResolveInfoFlags on API 33+ — raw int overload is deprecated
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.ResolveInfoFlags f = PackageManager.ResolveInfoFlags.of(0);
            addResolved(pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), f),
                    self, seen, out);
            addResolved(pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), f),
                    self, seen, out);
        } else {
            //noinspection deprecation
            addResolved(pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0),
                    self, seen, out);
            //noinspection deprecation
            addResolved(pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0),
                    self, seen, out);
        }

        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void addResolved(List<ResolveInfo> list, String self,
                             ArraySet<String> seen, List<AppInfo> out) {
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(self)) continue;
            if (!seen.add(ai.packageName + '/' + ai.name)) continue;
            String label = ri.loadLabel(pm).toString();
            out.add(new AppInfo(ai.packageName, label,
                    new ComponentName(ai.packageName, ai.name)));
        }
    }

    private void launchApp(AppInfo app) {
        Intent i = pm.getLaunchIntentForPackage(app.packageName);
        if (i != null) {
            i.setComponent(app.component);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); return; }
            catch (Exception ignored) {}
        }
        try {
            Intent direct = new Intent(Intent.ACTION_MAIN);
            direct.setComponent(app.component);
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(direct);
        } catch (Exception e) {
            showToast("App not available");
        }
    }

    // =========================================================================
    // Icon processing
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
                bmp = processIcon(d);
                if (bmp != null) iconCache.put(key, bmp);
            } catch (PackageManager.NameNotFoundException | OutOfMemoryError ignored) {}

            // Fix 6: check destroyed again after the slow icon decode.
            // Between the first check and here, onDestroy() may have run on the
            // main thread — setting destroyed=true and nulling UI refs.
            // Re-checking here prevents posting a UI lambda that touches null refs.
            if (destroyed) return;
            final Bitmap fb = bmp;
            runOnUiThread(() -> {
                // Fix 6: destroyed is volatile; re-check on UI thread because
                // onDestroy() runs there and nulls shelf between our check above
                // and this lambda executing.
                if (destroyed) return;
                if (app.packageName.equals(
                        target.boundApp != null ? target.boundApp.packageName : null)) {
                    target.setIconBitmap(fb);
                }
            });
        });
    }

    /**
     * Converts any Drawable to a circle-clipped Bitmap at ICON_SIZE_DP.
     *
     * Adaptive icons: draw bg+fg manually, then circle-clip.
     * Non-adaptive transparent: 82% scale + averaged fill circle behind.
     * Non-adaptive opaque: 110% scale (slight bleed — clipped to circle).
     *
     * P1: scaling uses Matrix drawn onto output canvas, eliminating the
     *     intermediate Bitmap that createScaledBitmap() produced.
     */
    private Bitmap processIcon(Drawable d) {
        if (d == null) return null;
        int sz = dp(ICON_SIZE_DP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) d;
            Bitmap raw = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(raw);
            if (aid.getBackground() != null) { aid.getBackground().setBounds(0,0,sz,sz); aid.getBackground().draw(c); }
            if (aid.getForeground() != null) { aid.getForeground().setBounds(0,0,sz,sz); aid.getForeground().draw(c); }
            return clipToCircle(raw, sz);
        }

        Bitmap raw = renderDrawable(d, sz);
        if (raw == null) return null;

        int     fillColour      = detectFillColour(raw, sz);
        boolean hasTransparency = (fillColour != 0);
        float   scale           = hasTransparency ? 0.82f : 1.10f;
        int     contentSz       = Math.round(sz * scale);
        int     inset           = (sz - contentSz) / 2;

        Bitmap out    = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        if (fillColour != 0) {
            int faded = (fillColour & 0x00FFFFFF) | (150 << 24);
            sFillCirclePaint.setColor(faded); // Fix 2: reuse cached Paint, no allocation
            canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, sFillCirclePaint);
        }

        // P1: Matrix scale onto canvas — no createScaledBitmap intermediate
        Matrix mx = new Matrix();
        float  sf = (float) contentSz / sz;
        mx.setScale(sf, sf);
        mx.postTranslate(inset, inset);
        canvas.drawBitmap(raw, mx, sBitmapDrawPaint); // B1: static paint
        raw.recycle();

        return clipToCircle(out, sz);
    }

    /** Renders any Drawable to a Bitmap at exactly sz x sz pixels. */
    private Bitmap renderDrawable(Drawable d, int sz) {
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null && !src.isRecycled()) {
                // P1: Matrix draw instead of createScaledBitmap
                Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
                Matrix mx  = new Matrix();
                mx.setScale((float) sz / src.getWidth(), (float) sz / src.getHeight());
                new Canvas(out).drawBitmap(src, mx, sBitmapDrawPaint);
                return out;
            }
        }
        int w = d.getIntrinsicWidth()  > 0 ? d.getIntrinsicWidth()  : sz;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : sz;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bmp));
        if (w == sz && h == sz) return bmp;
        // P1: Matrix scale to final size
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Matrix mx  = new Matrix();
        mx.setScale((float) sz / w, (float) sz / h);
        new Canvas(out).drawBitmap(bmp, mx, sBitmapDrawPaint);
        bmp.recycle();
        return out;
    }

    /**
     * P2: Single bulk JNI read via copyPixelsToBuffer() instead of per-pixel
     *     getPixel() calls. ARGB_8888 ByteBuffer layout: R,G,B,A per pixel
     *     in row-major order.
     *
     * Returns averaged fill colour (alpha 255) if >40% of sampled pixels are
     * transparent. Returns 0 if icon is mostly opaque (no fill needed).
     */
    private int detectFillColour(Bitmap src, int sz) {
        // copyPixelsToBuffer writes ARGB_8888 as R,G,B,A bytes on Android (row-major).
        // We rewind() before reading to guarantee buffer position is at index 0,
        // regardless of internal ByteBuffer state after the copy.
        ByteBuffer buf = ByteBuffer.allocate(src.getByteCount());
        src.copyPixelsToBuffer(buf);
        buf.rewind(); // Fix 2: guarantee position=0 before array read
        byte[] px = buf.array();

        int  step = Math.max(1, sz / 6);
        int  total = 0, transparent = 0, nOpaque = 0;
        long rSum = 0, gSum = 0, bSum = 0;

        for (int y = step / 2; y < sz; y += step) {
            for (int x = step / 2; x < sz; x += step) {
                int base = (y * sz + x) * 4; // ARGB_8888: 4 bytes/pixel, R G B A
                int r    = px[base    ] & 0xFF;
                int g    = px[base + 1] & 0xFF;
                int b    = px[base + 2] & 0xFF;
                int a    = px[base + 3] & 0xFF;
                if      (a < 30)   transparent++;
                else if (a >= 128) { rSum += r; gSum += g; bSum += b; nOpaque++; }
                total++;
            }
        }

        if (total == 0 || (float) transparent / total < 0.40f) return 0;
        if (nOpaque == 0) return 0xFF555555;
        return Color.argb(255, (int)(rSum/nOpaque), (int)(gSum/nOpaque), (int)(bSum/nOpaque));
    }

    /** Circle-clips src into a new Bitmap. src is recycled. */
    private Bitmap clipToCircle(Bitmap src, int sz) {
        if (src == null) return null;
        Bitmap out    = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        int sc = canvas.saveLayer(0, 0, sz, sz, null);
        canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, sMaskPaint);
        canvas.drawBitmap(src, 0, 0, sIconPaint);
        canvas.restoreToCount(sc);
        src.recycle();
        return out;
    }

    // =========================================================================
    // Ring positioning
    // =========================================================================

    private void positionRing(View cell) {
        final RingView    rv   = ringView;
        final FrameLayout root = rootLayout;
        if (rv == null || root == null || !cell.isAttachedToWindow()) return;

        int[] cellScreen = new int[2];
        int[] rootScreen = new int[2];
        cell.getLocationOnScreen(cellScreen);
        root.getLocationOnScreen(rootScreen);

        int   iconPx           = dp(ICON_SIZE_DP);
        float iconCentreInCellY = iconPx / 2f + dp(4);
        float cellCx = (cellScreen[0] - rootScreen[0]) + cell.getWidth() / 2f;
        float cellCy = (cellScreen[1] - rootScreen[1]) + iconCentreInCellY;

        int rvW = rv.getWidth();
        if (rvW == 0) rvW = iconPx + dp(RING_STROKE_DP) * 2;
        float half = rvW / 2f;
        rv.setX(cellCx - half);
        rv.setY(cellCy - half);
        rv.setVisibility(View.VISIBLE);
    }

    // =========================================================================
    // Clock
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
        tickCal.setTimeInMillis(System.currentTimeMillis());
        clockSb.setLength(0);
        clockSb.append(sdfTime.format(tickCal.getTime()));
        cv.setText(clockSb);
    }

    // =========================================================================
    // Wallpaper
    // =========================================================================

    private void loadWallpaper() {
        String uri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WP_URI, null);
        if (uri != null) applyWallpaperFromUri(Uri.parse(uri));
        else             loadSystemWallpaper();
    }

    // B2: runs on dedicated wpExecutor — no longer competes with icon loads
    private void loadSystemWallpaper() {
        if (!systemWpLoading.compareAndSet(false, true)) return;
        wpExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                Drawable d = WallpaperManager.getInstance(this).getDrawable();
                if (d != null) bmp = drawableToBitmapFullSize(d);
            } catch (Exception ignored) {}
            final Bitmap fb = bmp;
            systemWpLoading.set(false);
            if (!destroyed) runOnUiThread(() -> {
                final ImageView wv = wallpaperView;
                if (fb != null && wv != null) wv.setImageBitmap(fb);
            });
        });
    }

    // B2: dedicated wpExecutor
    // R3: URI persisted only after bitmap successfully loaded
    private void applyWallpaperFromUri(Uri uri) {
        if (!userWpLoading.compareAndSet(false, true)) return;
        wpExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(is, null, opts);
                }
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    userWpLoading.set(false); return;
                }
                opts.inSampleSize       = calcSampleSize(opts.outWidth, opts.outHeight, screenW, screenH);
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
                    // R3: persist URI only after bitmap loaded successfully
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
            // Fix 3: use || not && — subsample if EITHER dimension overflows.
            // With &&, an image exactly matching screen width but taller than
            // screen height (common for portrait photos) kept ss=1 and decoded
            // the full bitmap, causing OOM on constrained TV boxes.
            while ((halfH / ss) > reqH || (halfW / ss) > reqW) ss *= 2;
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
        try { startActivityForResult(i, REQ_PICK_WP); }
        catch (Exception e) { showToast("No file picker available"); }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_WP && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // R3: take grant first; abort entirely if it fails
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    showToast("Could not get permission for this image");
                    return;
                }
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

        // P4: cap at min(memMb/8, 16)MB — previous memMb/6 was up to 42MB
        int cacheMb = Math.min(memMb / 8, 16);
        iconCache = new LruCache<String, Bitmap>(cacheMb * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        };

        int cores = Runtime.getRuntime().availableProcessors();

        // Fix 4 (P3): DiscardOldestPolicy drops the head of the queue — which is
        // the oldest-submitted task, i.e. the FIRST visible icons. That is the
        // opposite of what we want. We want to discard the NEW incoming task
        // (for an icon that scrolled into view while the queue is already full)
        // and let the already-queued visible icons complete.
        // DiscardPolicy does exactly this: silently drops the new task if full.
        iconExecutor = new ThreadPoolExecutor(
                Math.max(1, cores - 1), cores,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                new ThreadPoolExecutor.DiscardPolicy());

        wpExecutor  = Executors.newSingleThreadExecutor(); // B2
        appExecutor = Executors.newSingleThreadExecutor();
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

        RingView(Context ctx, int strokePx) {
            super(ctx);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(strokePx);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx     = getWidth()  / 2f;
            float cy     = getHeight() / 2f;
            float radius = cx - paint.getStrokeWidth() / 2f;
            if (radius > 0) canvas.drawCircle(cx, cy, radius, paint);
        }
    }
}
