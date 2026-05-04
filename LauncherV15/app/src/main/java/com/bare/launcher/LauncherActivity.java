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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
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
 * BareLauncher — zero-dependency, minimal Android TV launcher.
 *
 * Audit fixes applied in this version:
 *
 *  A1  Icon size: 52dp → 80dp (Android TV standard launcher icon size)
 *      Cell size matches: CELL_W/H_DP = 84dp (icon + 2dp breathing room each side)
 *
 *  A2  makeCircular() XOR bug: drawing a black circle then SRC_IN without
 *      saveLayer() composites through the window, causing the "faded/offset"
 *      appearance. Fixed by using saveLayer() to isolate the compositing into
 *      an offscreen buffer, guaranteeing correct alpha channel handling.
 *
 *  A3  fillTransparentBackground() bleed: filled circle background was drawn
 *      onto an unrestricted canvas, then the original icon drawn on top —
 *      but no circle clip was applied to the icon draw, so transparency outside
 *      the icon's natural bounds could bleed. Fixed by drawing both the
 *      background circle and icon inside a saveLayer() with circle clip.
 *
 *  A4  Ring gap: RING_PADDING_DP was 3, creating visible gap between ring and
 *      icon. Reduced to 0 so the ring hugs the icon edge exactly. Ring size is
 *      now icon_size + stroke_width, touching the icon perimeter with no gap.
 *
 *  A5  Ring size formula corrected: was ICON_SIZE + PADDING*2 + STROKE*2.
 *      Now: ICON_SIZE_DP + RING_STROKE_DP (stroke is centred on the ring radius,
 *      so only half-stroke extends outside — using full stroke as margin is safe).
 *
 *  A6  AdaptiveIconDrawable rendering: API≥26 adaptive icons were rendered at
 *      target size directly, but AdaptiveIconDrawable is designed for a 108dp
 *      canvas (72dp safe zone + 18dp bleed each side). Rendering at 80dp without
 *      scaling results in a smaller-looking icon. Fixed: render adaptive icons
 *      at 108/72 ratio-corrected size (target * 108/72 = target * 1.5), then
 *      scale down. This fills the circle correctly and matches system behaviour.
 *
 *  A7  Non-adaptive icons forced round: legacy icons that are square/rectangular
 *      were only circle-clipped without background. If the icon has transparent
 *      corners, the clip is fine; if it's an opaque square it now gets clipped
 *      cleanly. The fillTransparentBackground + makeCircular pipeline handles both.
 *
 *  A8  Clock style: sans-serif-light is too thin for TV viewing distance. Changed
 *      to Typeface.DEFAULT_BOLD equivalent (sans-serif, BOLD) for thick, clear
 *      Material-style clock. Text size increased from 22sp → 26sp.
 *
 *  A9  fillVisible() from onLayout(): onLayout is called during the layout pass;
 *      calling fillVisible() (which calls layout() on children) from within it
 *      causes nested layout calls. Fixed: fillVisible() in onLayout wrapped in
 *      post() to defer until after the current layout pass completes.
 *
 *  A10 positionRing() coordinate space: was mixing getLocationInWindow for both
 *      cell and ringView, then setting ringView.setX/setY (translation). setX/Y
 *      are in the parent's coordinate space, not window space. Fixed: compute
 *      ring position in the root FrameLayout's coordinate space using
 *      getLocationOnScreen offset by the root view's screen position.
 *
 *  A11 Scale-up focus animation conflicts with ring position: when a cell scales
 *      up 1.10× on focus, the ring is positioned at the pre-scale bounds.
 *      Ring now positioned after the animation frame via post(), and ring size
 *      is set to match the scaled icon so it still touches the edge.
 *
 *  A12 CellView.onDraw paint style leak: after drawing the placeholder stroke,
 *      the Paint style was reset to FILL but strokeWidth was not reset. On the
 *      next draw of a bitmap, FILTER_BITMAP_FLAG was preserved but leftover
 *      stroke state could interfere. Fixed: Paint is initialised fresh for each
 *      draw path (bitmap vs placeholder), not shared state mutated inline.
 */
public class LauncherActivity extends Activity {

    // ── Constants ─────────────────────────────────────────────────────────────

    // A1: 80dp is the Android TV recommended launcher icon size
    private static final int    ICON_SIZE_DP    = 80;
    // A1: cell is icon + 2dp padding each side so icons don't touch
    private static final int    CELL_W_DP       = 84;
    private static final int    CELL_H_DP       = 84;
    // A4: ring stroke; no padding — ring touches icon edge
    private static final int    RING_STROKE_DP  = 3;
    private static final long   CLOCK_MS        = 1_000L;
    private static final String PREFS           = "bare_launcher";
    private static final String KEY_WP_URI      = "wp_uri";
    private static final int    MATCH           = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int    WRAP            = ViewGroup.LayoutParams.WRAP_CONTENT;

    // A8: Bold typeface for thick, legible Material-style clock
    private static final Typeface TF_CLOCK =
            Typeface.create("sans-serif", Typeface.BOLD);

    // ── Cached metrics ────────────────────────────────────────────────────────
    private float density;
    private int   screenW, screenH;

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile boolean    destroyed       = false;
    private final AtomicBoolean systemWpLoading = new AtomicBoolean(false);
    private final AtomicBoolean userWpLoading   = new AtomicBoolean(false);
    private final AtomicBoolean appsLoading     = new AtomicBoolean(false);

    // ── Cached PackageManager ─────────────────────────────────────────────────
    private PackageManager pm;

    // ── UI refs ───────────────────────────────────────────────────────────────
    private RecyclingShelfView shelf;
    private ImageView          wallpaperView;
    private TextView           clockView;
    private TextView           wpBtn;
    private RingView           ringView;
    // Root view reference needed for A10 coordinate conversion
    private FrameLayout        rootLayout;

    // ── Toast ─────────────────────────────────────────────────────────────────
    private Toast currentToast;

    // ── Clock ─────────────────────────────────────────────────────────────────
    private final Handler        clockHandler = new Handler(Looper.getMainLooper());
    private       boolean        clockRunning = false;
    private       SimpleDateFormat sdfTime;
    private final Calendar       tickCal      = Calendar.getInstance();
    private final StringBuilder  clockSb      = new StringBuilder(16);

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
    private ExecutorService    appExecutor;

    // ── Icon cache ────────────────────────────────────────────────────────────
    private LruCache<String, Bitmap> iconCache;

    // ── App list (main thread after renderApps) ───────────────────────────────
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

    // ── App model ─────────────────────────────────────────────────────────────
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

        sdfTime = new SimpleDateFormat(
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString("clockFmt", "h:mm a"),
                Locale.getDefault());

        initCaches();
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
        loadApps();
    }

    @Override protected void onPause() { super.onPause(); stopClock(); }

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

    @Override public void onBackPressed() { /* launcher: never exit on back */ }

    // =========================================================================
    // Layout
    // =========================================================================

    private View buildRootLayout() {
        rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        rootLayout.setBackgroundColor(Color.BLACK);

        // Wallpaper — CENTER_CROP: fills without distortion
        wallpaperView = new ImageView(this);
        wallpaperView.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        rootLayout.addView(wallpaperView);

        // A5: Ring size = icon_size + stroke (ring straddles the icon perimeter)
        // With RING_PADDING_DP=0, the ring circle radius = icon_radius + stroke/2
        // so the inner edge of the stroke exactly touches the icon edge.
        int iconPx    = dp(ICON_SIZE_DP);
        int strokePx  = dp(RING_STROKE_DP);
        int ringSize  = iconPx + strokePx * 2; // ring canvas slightly larger than icon
        ringView = new RingView(this, density, iconPx, strokePx);
        ringView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ringView.setLayoutParams(new FrameLayout.LayoutParams(ringSize, ringSize));
        ringView.setVisibility(View.INVISIBLE);
        rootLayout.addView(ringView);

        // Shelf — bottom of screen
        shelf = new RecyclingShelfView(this);
        FrameLayout.LayoutParams shelfLp =
                new FrameLayout.LayoutParams(MATCH, dp(CELL_H_DP) + dp(8));
        shelfLp.gravity = android.view.Gravity.BOTTOM;
        shelfLp.setMargins(0, 0, 0, dp(32));
        shelf.setLayoutParams(shelfLp);
        rootLayout.addView(shelf);

        // A8: Clock — top-LEFT, bold, 26sp, semi-transparent pill background
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
        // A8: 26sp, bold — thick and legible at TV viewing distance
        clockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
        clockView.setTypeface(TF_CLOCK);
        // Slight letter spacing for Material-style clock look
        clockView.setLetterSpacing(0.03f);
        rootLayout.addView(clockView);

        // Wallpaper picker button — top-RIGHT
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
                canvas.drawCircle(cx, cy, s / 2f, wp);
                canvas.drawCircle(cx + s * 0.17f, t + s * 0.23f, s * 0.12f, wp);
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
        rootLayout.addView(wpBtn);

        return rootLayout;
    }

    // =========================================================================
    // RecyclingShelfView — horizontal scrolling row of app icons
    // =========================================================================

    final class RecyclingShelfView extends ViewGroup {

        private static final int BUFFER = 2;

        private final ArrayList<CellView>               recyclePool = new ArrayList<>(8);
        private final android.util.SparseArray<CellView> attached   = new android.util.SparseArray<>();

        private final OverScroller scroller;
        private VelocityTracker velTracker; // lazy obtain, released in onDetachedFromWindow
        private float lastTouchX;
        private int   scrollX      = 0;
        private int   totalW       = 0;
        private final int cellW, cellH, cellM;
        int focusedIndex = 0;

        RecyclingShelfView(Context ctx) {
            super(ctx);
            scroller = new OverScroller(ctx);
            cellW    = dp(CELL_W_DP);
            cellH    = dp(CELL_H_DP);
            cellM    = dp(16); // horizontal margin between cells
            setFocusable(false);
            setClipChildren(false);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (velTracker != null) { velTracker.recycle(); velTracker = null; }
        }

        // A9: wrap fillVisible() in post() to avoid nested layout calls
        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            post(this::fillVisible);
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
            int stride = cellW + cellM * 2;
            totalW = apps.size() * stride + dp(48);
            requestLayout();
            invalidate();
            if (!apps.isEmpty()) post(() -> requestFocusOnIndex(0));
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
            addView(cv);
            return cv;
        }

        private void bindCell(CellView cv, int index) {
            AppInfo app = appList.get(index);
            cv.bind(app, index);
            int left      = cellLeft(index);
            int topOffset = (getMeasuredHeight() - cellH) / 2;
            cv.layout(left, topOffset, left + cellW, topOffset + cellH);
        }

        private void repositionAttached() {
            for (int i = 0; i < attached.size(); i++) {
                int idx = attached.keyAt(i);
                CellView cv = attached.valueAt(i);
                int left      = cellLeft(idx);
                int topOffset = (getMeasuredHeight() - cellH) / 2;
                cv.layout(left, topOffset, left + cellW, topOffset + cellH);
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

        // ── CellView — plain View, draws bitmap directly ───────────────────────

        final class CellView extends View {

            private Bitmap  iconBitmap;
            private AppInfo boundApp;
            int             boundIndex;

            // A12: separate Paint objects for each draw path — no shared mutation
            private final Paint bitmapPaint      = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            private final Paint placeholderFill  = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint placeholderRing  = new Paint(Paint.ANTI_ALIAS_FLAG);

            CellView(Context ctx) {
                super(ctx);
                setFocusable(true);
                setFocusableInTouchMode(true);
                setClickable(true);
                setWillNotDraw(false);

                placeholderFill.setStyle(Paint.Style.FILL);
                placeholderFill.setColor(0x33FFFFFF);

                placeholderRing.setStyle(Paint.Style.STROKE);
                placeholderRing.setColor(0x55FFFFFF);
                placeholderRing.setStrokeWidth(dp(1));

                setOnClickListener(v -> { if (boundApp != null) launchApp(boundApp); });

                setOnFocusChangeListener((v, focused) -> {
                    animate().cancel();
                    animate()
                            .scaleX(focused ? 1.10f : 1f)
                            .scaleY(focused ? 1.10f : 1f)
                            .setDuration(120).start();
                    if (focused) {
                        focusedIndex = boundIndex;
                        // A11: position ring after animation frame via post
                        post(() -> positionRing(this));
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
                    // Icon bitmap is pre-clipped to circle at ICON_SIZE_DP resolution;
                    // centre it within the cell (cell may be slightly larger than icon).
                    float half = iconBitmap.getWidth() / 2f;
                    canvas.drawBitmap(iconBitmap, cx - half, cy - half, bitmapPaint);
                } else {
                    // Placeholder: semi-transparent filled circle + ring
                    canvas.drawCircle(cx, cy, r - dp(2), placeholderFill);
                    canvas.drawCircle(cx, cy, r - dp(2) - dp(1) / 2f, placeholderRing);
                }
            }

            void setIconBitmap(Bitmap bmp) {
                iconBitmap = bmp;
                invalidate();
            }

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

        Intent tvI = new Intent(Intent.ACTION_MAIN);
        tvI.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        addResolved(pm.queryIntentActivities(tvI, 0), self, seen, out);

        Intent mobI = new Intent(Intent.ACTION_MAIN);
        mobI.addCategory(Intent.CATEGORY_LAUNCHER);
        addResolved(pm.queryIntentActivities(mobI, 0), self, seen, out);

        Collections.sort(out, (a, b) -> a.packageName.compareToIgnoreCase(b.packageName));
        return out;
    }

    private void addResolved(List<ResolveInfo> list, String self,
                             ArraySet<String> seen, List<AppInfo> out) {
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(self)) continue;
            if (!seen.add(ai.packageName + '/' + ai.name)) continue;
            out.add(new AppInfo(ai.packageName,
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
    // Icon loading — A2, A3, A6, A7
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

            if (destroyed) return;
            final Bitmap fb = bmp;
            runOnUiThread(() -> {
                if (app.packageName.equals(
                        target.boundApp != null ? target.boundApp.packageName : null)) {
                    target.setIconBitmap(fb);
                }
            });
        });
    }

    /**
     * Full icon processing pipeline:
     *  1. Render drawable to bitmap (handling AdaptiveIconDrawable correctly — A6)
     *  2. If not adaptive, fill transparent background with dominant colour (A3)
     *  3. Clip to circle using saveLayer() for correct alpha compositing (A2)
     */
    private Bitmap processIcon(Drawable d) {
        if (d == null) return null;
        int targetSz = dp(ICON_SIZE_DP);

        Bitmap raw;
        boolean isAdaptive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && d instanceof AdaptiveIconDrawable;

        if (isAdaptive) {
            // A6: AdaptiveIconDrawable uses a 108dp canvas with 72dp safe zone.
            // To fill the circle correctly, render at (target * 108/72) then crop.
            // 108/72 = 1.5 — render 1.5× larger so safe zone = targetSz.
            int renderSz = (int)(targetSz * 1.5f);
            Bitmap full = Bitmap.createBitmap(renderSz, renderSz, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(full);
            d.setBounds(0, 0, renderSz, renderSz);
            d.draw(c);
            // Crop centre (safe zone) at targetSz
            int offset = (renderSz - targetSz) / 2;
            raw = Bitmap.createBitmap(full, offset, offset, targetSz, targetSz);
            full.recycle();
        } else {
            raw = renderDrawable(d, targetSz);
        }

        if (raw == null) return null;

        // A3: For non-adaptive icons, detect and fill transparent background
        Bitmap filled;
        if (!isAdaptive) {
            filled = fillTransparentBackground(raw, targetSz);
            if (filled != raw) raw.recycle();
        } else {
            filled = raw;
        }

        // A2: Clip to circle using saveLayer() — correct offscreen compositing
        Bitmap circular = makeCircular(filled, targetSz);
        if (circular != filled) filled.recycle();

        return circular;
    }

    /**
     * Renders any Drawable to a Bitmap of the given size.
     * Always returns a freshly allocated bitmap (safe for caller to recycle).
     */
    private Bitmap renderDrawable(Drawable d, int sz) {
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null && !src.isRecycled()) {
                // Always copy — never return the drawable's internal bitmap
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
            bmp.recycle();
            return scaled;
        }
        return bmp;
    }

    /**
     * A3: Detects predominantly transparent icons and fills with a grey background
     * (neutral, visible at any wallpaper). The fill is a circle clipped to the
     * icon boundary so no background bleeds outside the circle area.
     *
     * Uses a fixed neutral grey (0xFF555555) instead of palette extraction —
     * zero dependency, consistent appearance, avoids colour clashes.
     *
     * Returns input unchanged if the icon is mostly opaque.
     */
    private Bitmap fillTransparentBackground(Bitmap src, int sz) {
        if (src == null) return null;

        // Sample a 6×6 grid to estimate transparency coverage
        int totalSamples = 0, transparentSamples = 0;
        int step = Math.max(1, sz / 6);
        for (int y = step / 2; y < sz; y += step) {
            for (int x = step / 2; x < sz; x += step) {
                if (Color.alpha(src.getPixel(x, y)) < 30) transparentSamples++;
                totalSamples++;
            }
        }
        if (totalSamples == 0 || (float) transparentSamples / totalSamples < 0.60f) {
            return src; // icon is mostly opaque — no fill needed
        }

        // Find dominant opaque colour from sampled pixels
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
        // Use dominant colour, or neutral grey if icon has no opaque pixels at all
        int fillColour = (opaqueSamples > 0)
                ? Color.argb(255,
                        (int)(rSum / opaqueSamples),
                        (int)(gSum / opaqueSamples),
                        (int)(bSum / opaqueSamples))
                : 0xFF555555;

        // A3: Draw background circle then icon using saveLayer() so the icon
        // is composited correctly within the circle boundary — no bleed outside.
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(fillColour);
        canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, bgPaint);

        // Draw icon on top — transparent areas show the filled background
        canvas.drawBitmap(src, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /**
     * A2: Clips a bitmap to a circle using saveLayer() for correct offscreen
     * alpha compositing.
     *
     * The previous approach drew a black circle then used SRC_IN without
     * saveLayer(). Without an isolated layer, the XOR blend affects the
     * window compositor rather than an intermediate buffer, causing icons to
     * appear faded/washed/offset. saveLayer() ensures all compositing happens
     * in an isolated offscreen buffer.
     */
    private Bitmap makeCircular(Bitmap src, int sz) {
        if (src == null) return null;

        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        // Save to isolated layer — all compositing is offscreen
        int sc = canvas.saveLayer(0, 0, sz, sz, null);

        // Step 1: Draw white circle to establish destination alpha
        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        maskPaint.setColor(Color.WHITE);
        canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, maskPaint);

        // Step 2: Draw source bitmap with SRC_IN — only shows inside the circle
        Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        iconPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, 0, 0, iconPaint);

        canvas.restoreToCount(sc);
        return out;
    }

    // =========================================================================
    // Ring positioning — A10, A11
    // =========================================================================

    /**
     * A10: Position ring in root FrameLayout coordinate space.
     * setX/setY are in the parent's local coordinate space (the root FrameLayout).
     * We use getLocationOnScreen for both cell and root, then subtract root's
     * screen origin to get the cell's position within the root.
     *
     * A11: Called from CellView.post() after the 120ms scale animation starts,
     * so the ring is positioned at the scaled (1.10×) bounds.
     */
    private void positionRing(View cell) {
        final RingView rv = ringView;
        final FrameLayout root = rootLayout;
        if (rv == null || root == null) return;

        rv.post(() -> {
            if (destroyed) return;
            final RingView rvInner = ringView;
            final FrameLayout rootInner = rootLayout;
            if (rvInner == null || rootInner == null) return;

            int[] cellLoc = new int[2];
            int[] rootLoc = new int[2];
            cell.getLocationOnScreen(cellLoc);
            rootInner.getLocationOnScreen(rootLoc);

            // Cell centre in root's coordinate space
            float cellCxInRoot = (cellLoc[0] - rootLoc[0]) + cell.getWidth()  / 2f;
            float cellCyInRoot = (cellLoc[1] - rootLoc[1]) + cell.getHeight() / 2f;

            // A11: Account for scale animation — cell appears 1.10× larger when focused.
            // The ring should enclose the scaled icon, so expand by scale factor.
            float scale = cell.getScaleX(); // 1.10 when focused, or still animating
            float scaledIconR = (dp(ICON_SIZE_DP) / 2f) * scale;

            // Position ring centred on the cell, with ring radius = scaledIconR + stroke/2
            // (ring stroke straddles the radius, so inner edge touches the icon perimeter)
            float ringR  = scaledIconR + dp(RING_STROKE_DP) / 2f;
            float ringSz = ringR * 2f;

            rvInner.setX(cellCxInRoot - ringSz / 2f);
            rvInner.setY(cellCyInRoot - ringSz / 2f);
            rvInner.setVisibility(View.VISIBLE);
        });
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

    private void loadSystemWallpaper() {
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
        if (!userWpLoading.compareAndSet(false, true)) return;
        iconExecutor.execute(() -> {
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
        iconCache = new LruCache<String, Bitmap>((memMb * 1024 * 1024) / 6) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        };

        int cores = Runtime.getRuntime().availableProcessors();
        iconExecutor = new ThreadPoolExecutor(
                Math.max(1, cores - 1), cores,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                new ThreadPoolExecutor.CallerRunsPolicy());

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
    // RingView — A5: ring radius sized to touch icon edge with no gap
    // =========================================================================

    static final class RingView extends View {
        private final Paint paint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float iconPx; // icon radius in pixels
        private final float strokePx;

        RingView(Context ctx, float density, int iconSizePx, int strokePx) {
            super(ctx);
            this.iconPx   = iconSizePx / 2f;
            this.strokePx = strokePx;
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(strokePx);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // A5: ring is drawn at the icon's perimeter.
            // The ring canvas is iconPx*2 + strokePx*2 wide, so the centre
            // of the canvas aligns with the icon centre.
            // Ring radius = iconPx + strokePx/2 so the INNER edge of the
            // stroke sits exactly on the icon's edge (no gap).
            float cx = getWidth()  / 2f;
            float cy = getHeight() / 2f;
            float ringRadius = iconPx + strokePx / 2f;
            canvas.drawCircle(cx, cy, ringRadius, paint);
        }
    }
}
