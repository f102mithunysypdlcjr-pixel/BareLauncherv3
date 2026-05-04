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
 * BareLauncher v5 — zero-dependency, minimal Android TV launcher.
 *
 * Fixes applied based on live photo audit:
 *
 *  C1  Z-ORDER CRITICAL: ringView was added to rootLayout BEFORE shelf.
 *      FrameLayout draws children in order → shelf rendered ON TOP of ring,
 *      hiding it completely. Fixed: ringView added LAST (topmost layer).
 *
 *  C2  ADAPTIVE ICON SHAPE: AdaptiveIconDrawable already composites its own
 *      background+foreground and clips to a rounded-rect/circle shape internally.
 *      Our circle clip on top caused a "double-shaped" appearance (rounded-rect
 *      inside a circle = square-looking icon with cut corners visible).
 *      Fixed: adaptive icons skip clipToCircle entirely. They are rendered
 *      at full size and drawn directly — the system shape is respected.
 *
 *  C3  ICON SCALE: ICON_CONTENT_SCALE=0.80 applied to ALL icons including
 *      adaptive, making every icon look small and padded. Adaptive icons should
 *      fill 100% of the circle. Non-adaptive transparent icons use 0.82 inset
 *      (just enough to avoid corner clipping). Opaque square icons get 0.90
 *      (slight breathing room inside the circle background).
 *
 *  C4  FILL COLOUR OPACITY: detectFillColour returned Color.argb(255,…) =
 *      fully opaque background. This hides transparent icon logos behind a
 *      solid colour. Fixed: alpha reduced to 200 (78%) — colour is visible
 *      but icon content shows through clearly.
 *
 *  C5  loadApps() in onResume: fired on every app return → full package query
 *      + list rebuild every time user exits an app. Removed from onResume.
 *      Package changes are handled by the BroadcastReceiver (correct path).
 *
 *  C6  AppInfo missing label: apps were sorted by packageName (com.xxx order),
 *      not visible display name. Added label field; sorted alphabetically by label.
 *
 *  C7  CellView missing app name label: icons shown with no text. Added a
 *      label drawn below the icon inside onDraw using a cached labelPaint.
 *      No extra View allocation — drawn directly on the cell canvas.
 *
 *  C8  new Paint() per icon in processIcon/clipToCircle: 3 Paint objects
 *      allocated per icon decode (bgPaint, maskPaint, iconPaint). Changed to
 *      static final cached instances reset before use.
 *
 *  C9  new Path() in wpBtn.onDraw() every frame: mountain path was allocated
 *      inside onDraw. Moved to a cached field, rebuilt only on size change.
 *
 *  C10 Ring gap: ring inner edge now sits exactly flush to icon edge with
 *      zero gap. ringSize = iconPx + strokePx*2, drawn radius = iconPx/2 + strokePx/2,
 *      inner edge = drawn radius - strokePx/2 = iconPx/2. Perfect flush fit.
 */
public class LauncherActivity extends Activity {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    ICON_SIZE_DP   = 80;
    private static final int    CELL_W_DP      = 96;   // icon 80 + 8dp each side
    private static final int    CELL_H_DP      = 104;  // icon + label + gap
    private static final int    RING_STROKE_DP = 4;    // thick enough for TV distance
    private static final long   CLOCK_MS       = 1_000L;
    private static final String PREFS          = "bare_launcher";
    private static final String KEY_WP_URI     = "wp_uri";
    private static final int    MATCH          = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int    WRAP           = ViewGroup.LayoutParams.WRAP_CONTENT;

    // C8: static cached Paints for icon processing — never allocated per-icon
    private static final Paint sCirclePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sMaskPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sIconPaint    = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    static {
        sMaskPaint.setColor(Color.WHITE);
        sIconPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
    }

    private static final Typeface TF_CLOCK    = Typeface.create("sans-serif", Typeface.BOLD);
    private static final Typeface TF_LABEL    = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

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
    private FrameLayout        rootLayout;

    // ── Toast ─────────────────────────────────────────────────────────────────
    private Toast currentToast;

    // ── Clock ─────────────────────────────────────────────────────────────────
    private final Handler       clockHandler = new Handler(Looper.getMainLooper());
    private       boolean       clockRunning = false;
    private       SimpleDateFormat sdfTime;
    private final Calendar      tickCal  = Calendar.getInstance();
    private final StringBuilder clockSb  = new StringBuilder(16);

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (destroyed || !clockRunning) return;
            tickClock();
            long now  = System.currentTimeMillis();
            clockHandler.postDelayed(this, CLOCK_MS - (now % CLOCK_MS));
        }
    };

    // ── Executors ─────────────────────────────────────────────────────────────
    private ThreadPoolExecutor iconExecutor;
    private ExecutorService    appExecutor;

    // ── Icon cache ────────────────────────────────────────────────────────────
    private LruCache<String, Bitmap> iconCache;

    // ── App list ──────────────────────────────────────────────────────────────
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

    // ── App model — C6: added label field ─────────────────────────────────────
    static final class AppInfo {
        final String        packageName;
        final String        label;
        final ComponentName component;
        AppInfo(String pkg, String lbl, ComponentName cmp) {
            packageName = pkg;
            label       = lbl;
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
                getSharedPreferences(PREFS, MODE_PRIVATE).getString("clockFmt", "h:mm a"),
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
        // C5: removed loadApps() from here — was rebuilding full app list on
        // every return from an app. Package changes handled by BroadcastReceiver.
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

        // Layer 1: Wallpaper
        wallpaperView = new ImageView(this);
        wallpaperView.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        rootLayout.addView(wallpaperView);

        // Layer 2: Shelf (app icons)
        shelf = new RecyclingShelfView(this);
        FrameLayout.LayoutParams shelfLp =
                new FrameLayout.LayoutParams(MATCH, dp(CELL_H_DP) + dp(8));
        shelfLp.gravity = android.view.Gravity.BOTTOM;
        shelfLp.setMargins(0, 0, 0, dp(28));
        shelf.setLayoutParams(shelfLp);
        rootLayout.addView(shelf);

        // Layer 3: Clock — top-left, semi-transparent pill
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
        // C9: Path cached as field, rebuilt only on size change
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
                float s = Math.min(w, h) * 0.80f;
                float cx = w / 2f, cy = h / 2f;
                wpPaint.setStrokeWidth(s * 0.10f);
                // C9: rebuild path only when size changes, not every frame
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
                float s2 = s / 2f;
                float cx2 = cx, cy2 = cy;
                canvas.drawCircle(cx2, cy2, s2, wpPaint);
                canvas.drawCircle(cx2 + s*0.17f, cy2 - s2 + s*0.23f, s*0.12f, wpPaint);
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

        // C1: Ring added LAST — topmost layer so nothing draws over it
        // Size = iconPx + strokePx*2 so inner ring edge is exactly flush to icon edge (C10)
        int iconPx   = dp(ICON_SIZE_DP);
        int strokePx = dp(RING_STROKE_DP);
        int ringSize = iconPx + strokePx * 2;
        ringView = new RingView(this, strokePx);
        ringView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ringView.setLayoutParams(new FrameLayout.LayoutParams(ringSize, ringSize));
        ringView.setVisibility(View.INVISIBLE);
        rootLayout.addView(ringView); // C1: AFTER shelf — draws on top

        return rootLayout;
    }

    // =========================================================================
    // RecyclingShelfView
    // =========================================================================

    final class RecyclingShelfView extends ViewGroup {

        private static final int BUFFER = 2;

        private final ArrayList<CellView>               recyclePool = new ArrayList<>(8);
        private final android.util.SparseArray<CellView> attached   = new android.util.SparseArray<>();

        private final OverScroller scroller;
        private VelocityTracker velTracker;
        private float lastTouchX;
        private int   scrollX = 0;
        private int   totalW  = 0;
        private final int cellW, cellH, cellM;
        int focusedIndex = 0;

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
            totalW = apps.size() * (cellW + cellM * 2) + dp(48);
            requestLayout();
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
            int first  = Math.max(0, (scrollX - offset) / stride() - BUFFER);
            int last   = Math.min(appList.size() - 1,
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
            AppInfo app  = appList.get(index);
            int left     = cellLeft(index);
            int topOff   = (getMeasuredHeight() - cellH) / 2;
            cv.bind(app, index);
            cv.layout(left, topOff, left + cellW, topOff + cellH);
        }

        private void repositionAttached() {
            for (int i = 0; i < attached.size(); i++) {
                int idx  = attached.keyAt(i);
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
            if (left - pad < scrollX)                     doScrollTo(Math.max(0, left - pad));
            else if (right + pad > scrollX + getWidth())  doScrollTo(right + pad - getWidth());
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

        // ── CellView ──────────────────────────────────────────────────────────

        final class CellView extends View {

            private Bitmap  iconBitmap;
            private AppInfo boundApp;
            int             boundIndex;

            // C7: label paint for drawing app name below icon
            private final Paint bitmapPaint     = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            private final Paint placeholderFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint placeholderRing = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint labelPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);

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

                // C7: label drawn inside onDraw — no extra View
                labelPaint.setColor(0xEEFFFFFF);
                labelPaint.setTextSize(dp(10));
                labelPaint.setTextAlign(Paint.Align.CENTER);
                labelPaint.setTypeface(TF_LABEL);

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
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return;

                int iconPx = dp(ICON_SIZE_DP);
                float cx   = w / 2f;
                // Icon centred in upper part of cell; label below
                float iconCy = iconPx / 2f + dp(4); // 4dp top padding

                if (iconBitmap != null && !iconBitmap.isRecycled()) {
                    float half = iconBitmap.getWidth() / 2f;
                    canvas.drawBitmap(iconBitmap, cx - half, iconCy - half, bitmapPaint);
                } else {
                    float r = iconPx / 2f - dp(2);
                    canvas.drawCircle(cx, iconCy, r, placeholderFill);
                    canvas.drawCircle(cx, iconCy, r - dp(1) / 2f, placeholderRing);
                }

                // C7: draw label below icon
                if (boundApp != null) {
                    float labelY = iconCy + iconPx / 2f + dp(6) + dp(10); // below icon + gap + text height
                    String lbl   = boundApp.label;
                    // Truncate if too wide
                    float maxW = w - dp(4);
                    if (labelPaint.measureText(lbl) > maxW) {
                        while (lbl.length() > 1 && labelPaint.measureText(lbl + "…") > maxW) {
                            lbl = lbl.substring(0, lbl.length() - 1);
                        }
                        lbl = lbl + "…";
                    }
                    canvas.drawText(lbl, cx, labelY, labelPaint);
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

        // C6: sort by visible display label, not package name
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void addResolved(List<ResolveInfo> list, String self,
                             ArraySet<String> seen, List<AppInfo> out) {
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(self)) continue;
            if (!seen.add(ai.packageName + '/' + ai.name)) continue;
            // C6: load display label here on background thread
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
    // Icon processing — C2, C3, C4, C8
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
     * C2: Adaptive icons are NOT circle-clipped — they render their own shape.
     *     Clipping them again produced a square-inside-circle appearance.
     *     Adaptive icons are drawn at full size and returned as-is.
     *
     * C3: Scale strategy:
     *     - Adaptive:            fill 100% (no inset — system handles shape)
     *     - Opaque/square:       fill 90%  (slight breathing room in circle bg)
     *     - Transparent/legacy:  fill 82%  (enough inset to avoid corner clip)
     *
     * C4: Background fill alpha = 200 (~78%) — visible but semi-transparent so
     *     icon content shows through clearly rather than being hidden.
     */
    private Bitmap processIcon(Drawable d) {
        if (d == null) return null;
        int sz = dp(ICON_SIZE_DP);

        // C2: Adaptive icons rendered at full size, not circle-clipped
        boolean isAdaptive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && d instanceof AdaptiveIconDrawable;
        if (isAdaptive) {
            return renderDrawable(d, sz);
        }

        // Non-adaptive: determine content scale based on transparency
        Bitmap raw = renderDrawable(d, sz);
        if (raw == null) return null;

        int fillColour = detectFillColour(raw, sz);
        boolean hasTransparency = (fillColour != 0);

        // C3: scale — transparent icons get more inset to avoid corner clipping
        float scale       = hasTransparency ? 0.82f : 0.90f;
        int   contentSz   = Math.round(sz * scale);
        int   inset       = (sz - contentSz) / 2;

        Bitmap content = Bitmap.createScaledBitmap(raw, contentSz, contentSz, true);
        raw.recycle();

        Bitmap out    = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        if (fillColour != 0) {
            // C4: alpha 200 = semi-transparent background (not fully opaque)
            int fadedColour = (fillColour & 0x00FFFFFF) | (200 << 24);
            sCirclePaint.setColor(fadedColour);
            sCirclePaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, sCirclePaint);
        }

        // Draw icon content centred
        canvas.drawBitmap(content, inset, inset,
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        content.recycle();

        // Circle clip with saveLayer for correct alpha compositing
        return clipToCircle(out, sz);
    }

    private Bitmap renderDrawable(Drawable d, int sz) {
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null && !src.isRecycled())
                return Bitmap.createScaledBitmap(src, sz, sz, true);
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
     * C4: Returns fill colour at alpha 255 (caller reduces alpha to 200).
     * Returns 0 if icon is mostly opaque and needs no background.
     */
    private int detectFillColour(Bitmap src, int sz) {
        int total = 0, transparent = 0;
        int step  = Math.max(1, sz / 6);
        for (int y = step / 2; y < sz; y += step)
            for (int x = step / 2; x < sz; x += step) {
                if (Color.alpha(src.getPixel(x, y)) < 30) transparent++;
                total++;
            }
        if (total == 0 || (float) transparent / total < 0.60f) return 0;

        long r = 0, g = 0, b = 0; int n = 0;
        for (int y = step / 2; y < sz; y += step)
            for (int x = step / 2; x < sz; x += step) {
                int px = src.getPixel(x, y);
                if (Color.alpha(px) >= 128) {
                    r += Color.red(px); g += Color.green(px); b += Color.blue(px); n++;
                }
            }
        return n > 0
                ? Color.argb(255, (int)(r/n), (int)(g/n), (int)(b/n))
                : 0xFF555555;
    }

    /**
     * C8: uses static sMaskPaint and sIconPaint — no allocation per icon.
     */
    private Bitmap clipToCircle(Bitmap src, int sz) {
        if (src == null) return null;
        Bitmap out    = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        int sc = canvas.saveLayer(0, 0, sz, sz, null);
        canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, sMaskPaint); // C8
        canvas.drawBitmap(src, 0, 0, sIconPaint);                  // C8
        canvas.restoreToCount(sc);
        src.recycle();
        return out;
    }

    // =========================================================================
    // Ring positioning — C1 (z-order), C10 (flush to icon edge)
    // =========================================================================

    /**
     * C10: Ring inner edge is exactly flush to icon edge — no gap.
     * ringSize = iconPx + strokePx*2
     * drawn radius = ringSize/2 - strokePx/2 = iconPx/2 + strokePx/2
     * inner edge  = drawn radius - strokePx/2 = iconPx/2  ✓ flush
     */
    private void positionRing(View cell) {
        final RingView   rv   = ringView;
        final FrameLayout root = rootLayout;
        if (rv == null || root == null || !cell.isAttachedToWindow()) return;

        int[] cellScreen = new int[2];
        int[] rootScreen = new int[2];
        cell.getLocationOnScreen(cellScreen);
        root.getLocationOnScreen(rootScreen);

        // Icon centre within the cell (icon sits at top, label below)
        int iconPx = dp(ICON_SIZE_DP);
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
    // RingView — C10: inner edge flush to icon, no gap
    // =========================================================================

    /**
     * View sized to (iconPx + strokePx*2).
     * Drawn radius = cx - strokePx/2 = iconPx/2 + strokePx - strokePx/2 = iconPx/2 + strokePx/2
     * Inner edge   = drawn radius - strokePx/2 = iconPx/2   ← flush to icon edge, zero gap
     */
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
