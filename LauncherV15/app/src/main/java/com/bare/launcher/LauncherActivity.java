package com.bare.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.OverScroller;
import android.widget.TextView;
import android.widget.Toast;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LauncherActivity extends Activity {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    ICON_DP        = 68;
    private static final int    CELL_W_DP      = 84;
    private static final int    CELL_H_DP      = 80;
    private static final int    RING_STROKE_DP = 4;
    private static final long   CLOCK_MS       = 1_000L;
    private static final String PREFS          = "bare_launcher";
    private static final String KEY_WP_URI     = "wp_uri";
    private static final int    MATCH          = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int    WRAP           = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int    REQ_PICK_WP    = 42;

    // ── ThreadLocal Matrix — one per thread, zero contention ──────────────────
    private static final ThreadLocal<Matrix> sMatrixTL = new ThreadLocal<Matrix>() {
        @Override protected Matrix initialValue() { return new Matrix(); }
    };

    // ── ThreadLocal pixel buffer — one per thread, avoids per-icon allocation ─
    private static final ThreadLocal<byte[]> sPixelBuf = new ThreadLocal<>();

    // ── Static Paints — allocated once at class load ───────────────────────────
    private static final Paint sMaskPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sSrcInPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint sDrawPaint  = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint sCellPaint  = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint sPhFill     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sWhiteFill  = new Paint(Paint.ANTI_ALIAS_FLAG);
    static {
        sMaskPaint.setColor(Color.WHITE);
        sSrcInPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        sPhFill.setStyle(Paint.Style.FILL);
        sPhFill.setColor(0x33FFFFFF);
        sWhiteFill.setStyle(Paint.Style.FILL);
        sWhiteFill.setColor(Color.WHITE);
    }

    // ── Instance state ─────────────────────────────────────────────────────────
    private volatile float      density;
    private          int        screenW, screenH;
    private volatile boolean    destroyed       = false;
    private final AtomicBoolean systemWpLoading = new AtomicBoolean(false);
    private final AtomicBoolean userWpLoading   = new AtomicBoolean(false);
    private final AtomicBoolean appsLoading     = new AtomicBoolean(false);
    private volatile String     lastAppSig      = "";
    private volatile boolean    wifiConnected   = false;

    private PackageManager      pm;
    private ConnectivityManager cm;

    private RecyclingShelfView shelf;
    private ImageView          wallpaperView;
    private TextView           clockView;
    private View               wifiBtn;
    private TextView           wpBtn;
    private RingView           ringView;
    private FrameLayout        root;
    private Toast              currentToast;

    private final Handler          clockHandler = new Handler(Looper.getMainLooper());
    private       boolean          clockRunning = false;
    private       SimpleDateFormat sdfTime;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (destroyed || !clockRunning) return;
            TextView cv = clockView;
            if (cv != null) cv.setText(formatClock(System.currentTimeMillis()), TextView.BufferType.SPANNABLE);
            long now = System.currentTimeMillis();
            clockHandler.postDelayed(this, CLOCK_MS - (now % CLOCK_MS));
        }
    };

    // Returns a SpannableString where the AM/PM suffix (if present) is rendered
    // at 55% of the main text size — smaller but still legible.
    private SpannableString formatClock(long ms) {
        String full = sdfTime.format(ms);
        SpannableString ss = new SpannableString(full);
        // Find AM/PM marker — locale-safe: look for a space followed by 2 uppercase-ish chars at end
        int spaceIdx = full.lastIndexOf(' ');
        if (spaceIdx >= 0 && spaceIdx < full.length() - 1) {
            ss.setSpan(new RelativeSizeSpan(0.55f), spaceIdx, full.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    private ThreadPoolExecutor iconExecutor;
    private ExecutorService    wpExecutor;
    private ExecutorService    appExecutor;
    private LruCache<String, Bitmap> iconCache;

    // FIX 1: iconInflight is accessed only on the UI thread (preWarmIcon called
    // from setApps on UI thread; runOnUiThread lambdas run on UI thread).
    // Changed from ArrayMap to HashMap — same O(1) ops, no Android-only dependency,
    // and the access pattern is now explicitly documented as UI-thread-only.
    // All reads/writes are on the main thread — no synchronisation needed.
    private final Map<String, List<RecyclingShelfView.CellView>> iconInflight = new HashMap<>();

    private final List<AppInfo> appList = new ArrayList<>();

    // FIX 3 (onResume loadApps): flag set by packageReceiver; loadApps on
    // onResume only runs a full query when a package event occurred while paused.
    private volatile boolean pkgChangedWhilePaused = false;

    private ViewTreeObserver.OnGlobalLayoutListener focusRestoreListener;
    private final Runnable pkgReloadRunnable = this::loadApps;

    private ConnectivityManager.NetworkCallback networkCallback;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_REPLACED.equals(action)
                    || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                Uri data = intent.getData();
                if (data != null) {
                    String pkg = data.getSchemeSpecificPart();
                    if (iconCache != null) iconCache.remove(pkg);
                    iconInflight.remove(pkg);
                    lastAppSig = "";
                }
            }
            // FIX 3: mark that a package changed; onResume will trigger reload.
            // If already resumed (shelf non-null and posted), do it immediately.
            pkgChangedWhilePaused = true;
            RecyclingShelfView s = shelf;
            if (s == null) return;
            s.removeCallbacks(pkgReloadRunnable);
            s.postDelayed(pkgReloadRunnable, 400);
        }
    };

    // ── App model ─────────────────────────────────────────────────────────────
    static final class AppInfo {
        final String        packageName;
        final String        label;
        final ComponentName component;
        final ResolveInfo   ri;
        AppInfo(String pkg, String lbl, ComponentName cmp, ResolveInfo r) {
            packageName = pkg; label = lbl; component = cmp; ri = r;
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
        density = dm.density; screenW = dm.widthPixels; screenH = dm.heightPixels;
        pm = getPackageManager();
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        sdfTime = new SimpleDateFormat(
                getSharedPreferences(PREFS, MODE_PRIVATE).getString("clockFmt", "h:mm a"),
                Locale.getDefault());
        initCaches();
        setContentView(buildLayout());
        hideSystemUI();
        loadWallpaper();
        loadApps();
        registerPkgReceiver();
        registerNetworkCallback();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {});
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        startClock();
        checkWifiNow();

        // FIX 3: only call loadApps() if a package change occurred while paused.
        // Previously called unconditionally on every onResume — 2 Binder calls
        // (queryIntentActivities) on every return from every launched app.
        if (pkgChangedWhilePaused) {
            pkgChangedWhilePaused = false;
            loadApps();
        }

        RecyclingShelfView s = shelf;
        if (s != null) {
            focusRestoreListener = new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    ViewTreeObserver vto = s.getViewTreeObserver();
                    if (vto.isAlive()) vto.removeOnGlobalLayoutListener(this);
                    focusRestoreListener = null;
                    if (!destroyed) s.requestFocusOnIndex(s.focusedIndex);
                }
            };
            ViewTreeObserver vto = s.getViewTreeObserver();
            if (vto.isAlive()) vto.addOnGlobalLayoutListener(focusRestoreListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopClock();
        // Remove focus-restore listener if it hasn't fired — prevents leak on fast destroy
        RecyclingShelfView s = shelf;
        if (s != null && focusRestoreListener != null) {
            ViewTreeObserver vto = s.getViewTreeObserver();
            if (vto.isAlive()) vto.removeOnGlobalLayoutListener(focusRestoreListener);
            focusRestoreListener = null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopClock();
        clockHandler.removeCallbacksAndMessages(null);
        unregisterPkgReceiver();
        unregisterNetworkCallback();
        shutdown(iconExecutor); shutdown(wpExecutor); shutdown(appExecutor);
        if (iconCache != null) iconCache.evictAll();
        iconInflight.clear();
        wallpaperView = null; clockView = null; shelf = null;
        wpBtn = null; wifiBtn = null; ringView = null; root = null;
        super.onDestroy();
    }

    private void shutdown(ExecutorService ex) {
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
        if      (level >= TRIM_MEMORY_COMPLETE)   { iconCache.evictAll(); iconInflight.clear(); lastAppSig = ""; appList.clear(); }
        else if (level >= TRIM_MEMORY_MODERATE)   { iconCache.trimToSize(iconCache.maxSize() / 2); iconInflight.clear(); }
        else if (level >= TRIM_MEMORY_BACKGROUND) { iconCache.trimToSize(iconCache.maxSize() * 3 / 4); iconInflight.clear(); }
    }

    @Override public void onWindowFocusChanged(boolean h) { super.onWindowFocusChanged(h); if (h) hideSystemUI(); }
    @Override @SuppressWarnings("deprecation") public void onBackPressed() {}

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        sdfTime = new SimpleDateFormat(
                getSharedPreferences(PREFS, MODE_PRIVATE).getString("clockFmt", "h:mm a"),
                Locale.getDefault());
        TextView cv = clockView;
        if (cv != null) cv.setText(formatClock(System.currentTimeMillis()), TextView.BufferType.SPANNABLE);
    }

    // =========================================================================
    // Layout
    // =========================================================================

    private View buildLayout() {
        root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        root.setBackgroundColor(Color.BLACK);

        wallpaperView = new ImageView(this);
        wallpaperView.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(wallpaperView);

        shelf = new RecyclingShelfView(this);
        FrameLayout.LayoutParams shelfLp = new FrameLayout.LayoutParams(MATCH, dp(CELL_H_DP));
        shelfLp.gravity = android.view.Gravity.BOTTOM;
        shelfLp.setMargins(0, 0, 0, dp(28));
        shelf.setLayoutParams(shelfLp);
        root.addView(shelf);

        clockView = new TextView(this);
        GradientDrawable clockBg = new GradientDrawable();
        clockBg.setColor(0x66000000);            // semi-transparent dark — wallpaper shows through
        clockBg.setCornerRadius(dp(100));         // fully-rounded pill
        clockView.setBackground(clockBg);
        clockView.setPadding(dp(22), dp(11), dp(22), dp(11));
        clockView.setIncludeFontPadding(false);
        FrameLayout.LayoutParams clkLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        clkLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        clkLp.setMargins(dp(32), dp(24), 0, 0);
        clockView.setLayoutParams(clkLp);
        clockView.setTextColor(Color.WHITE);
        clockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 42);
        // BLACK weight — heaviest available without a custom font, very readable on TV
        clockView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        clockView.setLetterSpacing(0.01f);
        root.addView(clockView);

        int btnSz          = dp(40);
        int btnMarginTop   = dp(24);
        int btnMarginRight = dp(32);
        int btnGap         = dp(4);   // tight gap between the two buttons inside the pill
        int pillPadH       = dp(8);   // horizontal padding inside pill
        int pillPadV       = dp(8);   // vertical padding inside pill
        int pillH          = btnSz + pillPadV * 2;
        int pillW          = btnSz * 2 + btnGap + pillPadH * 2;

        // Semi-transparent dark pill — matches clock aesthetic, wallpaper visible through both
        FrameLayout btnPill = new FrameLayout(this);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0x66000000);
        pillBg.setCornerRadius(dp(100));
        btnPill.setBackground(pillBg);
        btnPill.setFocusable(false);   // pill itself is NOT focusable — children handle focus
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(pillW, pillH);
        pillLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        pillLp.setMargins(0, btnMarginTop, btnMarginRight, 0);
        btnPill.setLayoutParams(pillLp);

        // Icon colour: white on dark pill
        final int ICON_COL = 0xFFFFFFFF;
        final int ICON_DIM = 0x66FFFFFF;  // dimmed arcs when wifi disconnected

        // Focus-highlight helper: each button draws a semi-white rounded-rect
        // behind itself when focused, giving clear individual selection feedback
        // without affecting the other button.
        final int FOCUS_HL = 0x33FFFFFF;  // subtle white tint on focused button
        final int FOCUS_RADIUS = dp(20);

        // ── WiFi button — classic arc fan icon ────────────────────────────────
        // One dot + two arcs: the universal WiFi symbol.
        // Honest: shows connected/not, makes no claim about signal strength.
        // Simpler than the bar chart: 3 draw calls vs previous 5+.
        // Connected: all elements fully white. Disconnected: all elements dim.
        wifiBtn = new View(this) {
            private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint hlPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF oval     = new RectF();
            {
                arcPaint.setStyle(Paint.Style.STROKE);
                arcPaint.setStrokeCap(Paint.Cap.ROUND);
                dotPaint.setStyle(Paint.Style.FILL);
                hlPaint.setStyle(Paint.Style.FILL);
                hlPaint.setColor(FOCUS_HL);
            }
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                if (isFocused()) c.drawRoundRect(0, 0, w, h, FOCUS_RADIUS, FOCUS_RADIUS, hlPaint);
                boolean conn  = wifiConnected;
                int     col   = conn ? ICON_COL : ICON_DIM;
                float   ic    = Math.min(w, h) * 0.68f;
                float   cx    = w / 2f;
                // Dot sits at 78% of height — arcs fan upward from it
                float   dotY  = h * 0.78f;
                float   sw    = ic * 0.11f;
                arcPaint.setStrokeWidth(sw);
                arcPaint.setColor(col);
                dotPaint.setColor(col);
                // Dot
                c.drawCircle(cx, dotY, sw * 0.65f, dotPaint);
                // Inner arc
                float r1 = ic * 0.28f;
                oval.set(cx - r1, dotY - r1, cx + r1, dotY + r1);
                c.drawArc(oval, 210f, 120f, false, arcPaint);
                // Outer arc
                float r2 = ic * 0.52f;
                oval.set(cx - r2, dotY - r2, cx + r2, dotY + r2);
                c.drawArc(oval, 210f, 120f, false, arcPaint);
            }
        };
        wifiBtn.setFocusable(true); wifiBtn.setFocusableInTouchMode(true); wifiBtn.setClickable(true);
        wifiBtn.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { showToast("Cannot open WiFi settings"); }
        });
        wifiBtn.setOnFocusChangeListener((v, f) -> v.invalidate());  // redraw own highlight only
        wifiBtn.setOnKeyListener((v, kc, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A: v.performClick(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    RecyclingShelfView sd = shelf; if (sd != null) sd.requestFocusOnIndex(0); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    RecyclingShelfView sl = shelf;
                    if (sl != null) sl.requestFocusOnIndex(appList.isEmpty() ? 0 : appList.size() - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    TextView wb = wpBtn; if (wb != null) wb.requestFocus(); return true;
                default: return false;
            }
        });
        FrameLayout.LayoutParams wifiLp = new FrameLayout.LayoutParams(btnSz, btnSz);
        wifiLp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START;
        wifiLp.setMargins(pillPadH, 0, 0, 0);
        wifiBtn.setLayoutParams(wifiLp);
        btnPill.addView(wifiBtn);

        // ── Wallpaper button ─────────────────────────────────────────────────
        wpBtn = new TextView(this) {
            private final Paint p;
            private final Paint hlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path  mt = new Path();
            private int lw = 0, lh = 0;
            { p = new Paint(Paint.ANTI_ALIAS_FLAG);
              p.setColor(ICON_COL); p.setStyle(Paint.Style.STROKE);
              p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
              hlPaint.setStyle(Paint.Style.FILL); hlPaint.setColor(FOCUS_HL); }
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                // Focus highlight
                if (isFocused()) c.drawRoundRect(0, 0, w, h, FOCUS_RADIUS, FOCUS_RADIUS, hlPaint);
                float s = Math.min(w, h) * 0.72f, cx = w / 2f, cy = h / 2f;
                p.setStrokeWidth(s * 0.10f);
                if (w != lw || h != lh) {
                    lw = w; lh = h;
                    float l = cx-s/2f, r = cx+s/2f, t = cy-s/2f, b = cy+s/2f;
                    mt.rewind(); mt.moveTo(l, b); mt.lineTo(l+s*.38f, t+s*.48f);
                    mt.lineTo(l+s*.62f, t+s*.66f); mt.lineTo(r, b);
                }
                c.drawCircle(cx, cy, s/2f, p);
                c.drawCircle(cx+s*.17f, cy-s/2f+s*.23f, s*.12f, p);
                c.drawPath(mt, p);
            }
        };
        wpBtn.setFocusable(true); wpBtn.setFocusableInTouchMode(true); wpBtn.setClickable(true);
        wpBtn.setOnClickListener(v -> openStoragePicker());
        wpBtn.setOnFocusChangeListener((v, f) -> v.invalidate());  // redraw own highlight only
        wpBtn.setOnKeyListener((v, kc, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A: v.performClick(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    RecyclingShelfView s = shelf;
                    if (s != null) s.requestFocusOnIndex(appList.isEmpty() ? 0 : appList.size() - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    View wb = wifiBtn; if (wb != null) wb.requestFocus(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    RecyclingShelfView sr = shelf;
                    if (sr != null) sr.requestFocusOnIndex(0); return true;
                default: return false;
            }
        });
        FrameLayout.LayoutParams wpLp = new FrameLayout.LayoutParams(btnSz, btnSz);
        wpLp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END;
        wpLp.setMargins(0, 0, pillPadH, 0);
        wpBtn.setLayoutParams(wpLp);
        btnPill.addView(wpBtn);

        root.addView(btnPill);

        int iconPx = dp(ICON_DP), strokePx = dp(RING_STROKE_DP);
        ringView = new RingView(this, strokePx);
        ringView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ringView.setLayoutParams(new FrameLayout.LayoutParams(iconPx + strokePx * 2, iconPx + strokePx * 2));
        ringView.setVisibility(View.INVISIBLE);
        root.addView(ringView);

        return root;
    }

    // =========================================================================
    // RecyclingShelfView
    // =========================================================================

    final class RecyclingShelfView extends ViewGroup {

        private static final int BUFFER = 2;

        private final ArrayList<CellView>   pool     = new ArrayList<>(8);
        private final SparseArray<CellView> attached = new SparseArray<>();
        private final OverScroller scroller;
        private VelocityTracker velTracker;
        private float lastTouchX;
        private int   scrollX     = 0;
        private int   totalW      = 0;
        private int   centerX     = 0;
        private boolean needsRefill = false;
        private final int cellW, cellH, stride;
        int focusedIndex = 0;

        RecyclingShelfView(Context ctx) {
            super(ctx);
            scroller = new OverScroller(ctx);
            cellW  = dp(CELL_W_DP);
            cellH  = dp(CELL_H_DP);
            stride = cellW + dp(10) * 2;
            setFocusable(false);
            setClipChildren(false);
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (velTracker != null) { velTracker.recycle(); velTracker = null; }
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l;
            if (w > 0) centerX = (totalW < w) ? (w - totalW) / 2 : dp(24);
            if (changed || needsRefill) { needsRefill = false; fillVisible(); }
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(
                    resolveSize(Math.max(totalW, getSuggestedMinimumWidth()), wSpec),
                    resolveSize(cellH, hSpec));
        }

        void setApps(List<AppInfo> apps) {
            for (int i = 0; i < attached.size(); i++) {
                CellView cv = attached.valueAt(i);
                cv.setVisibility(GONE); pool.add(cv);
            }
            attached.clear();
            if (appList.isEmpty()) { focusedIndex = 0; scrollX = 0; }
            if (!apps.isEmpty()) focusedIndex = Math.min(focusedIndex, apps.size() - 1);
            totalW      = apps.size() * stride;
            centerX     = 0;
            needsRefill = true;
            requestLayout();
            for (AppInfo app : apps) preWarmIcon(app);
            post(() -> requestFocusOnIndex(focusedIndex));
        }

        void requestFocusOnIndex(int idx) {
            if (appList.isEmpty()) return;
            int sz = appList.size();
            idx = ((idx % sz) + sz) % sz;
            focusedIndex = idx;
            ensureVisible(idx);
            CellView cv = attached.get(idx);
            if (cv != null) cv.requestFocus();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w > 0) centerX = (totalW < w) ? (w - totalW) / 2 : dp(24);
            repositionAttached();
            fillVisible();
        }

        private int cellLeft(int i) { return centerX + i * stride + dp(10) - scrollX; }

        private void fillVisible() {
            int w = getWidth();
            if (w == 0 || appList.isEmpty()) return;
            if (centerX == 0) centerX = (totalW < w) ? (w - totalW) / 2 : dp(24);
            int first = Math.max(0, (scrollX - centerX) / stride - BUFFER);
            int last  = Math.min(appList.size() - 1, (scrollX + w - centerX) / stride + BUFFER);
            for (int i = attached.size() - 1; i >= 0; i--) {
                int idx = attached.keyAt(i);
                if (idx < first || idx > last) {
                    CellView cv = attached.valueAt(i);
                    cv.setVisibility(GONE); pool.add(cv); attached.removeAt(i);
                }
            }
            for (int i = first; i <= last; i++) {
                if (attached.get(i) != null) continue;
                CellView cv = obtainCell(); bindCell(cv, i); attached.put(i, cv);
            }
        }

        private CellView obtainCell() {
            if (!pool.isEmpty()) { CellView cv = pool.remove(pool.size() - 1); cv.setVisibility(VISIBLE); return cv; }
            CellView cv = new CellView(getContext()); addView(cv); return cv;
        }

        private void bindCell(CellView cv, int index) {
            AppInfo app = appList.get(index);
            int left = cellLeft(index), top = (getMeasuredHeight() - cellH) / 2;
            cv.bind(app, index);
            cv.layout(left, top, left + cellW, top + cellH);
        }

        private void repositionAttached() {
            for (int i = 0; i < attached.size(); i++) {
                int idx = attached.keyAt(i); CellView cv = attached.valueAt(i);
                int left = cellLeft(idx), top = (getMeasuredHeight() - cellH) / 2;
                cv.layout(left, top, left + cellW, top + cellH);
            }
        }

        private void doScrollTo(int x) {
            int max = Math.max(0, totalW - getWidth());
            scrollX = Math.max(0, Math.min(x, max));
            repositionAttached(); fillVisible();
        }

        private void ensureVisible(int idx) {
            int left = centerX + idx * stride + dp(10), right = left + cellW, pad = dp(48);
            if      (left - pad < scrollX)               doScrollTo(Math.max(0, left - pad));
            else if (right + pad > scrollX + getWidth()) doScrollTo(right + pad - getWidth());
        }

        @Override public void computeScroll() {
            if (scroller.computeScrollOffset()) { doScrollTo(scroller.getCurrX()); postInvalidateOnAnimation(); }
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (velTracker == null) velTracker = VelocityTracker.obtain();
            velTracker.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scroller.abortAnimation(); lastTouchX = ev.getX(); break;
                case MotionEvent.ACTION_MOVE:
                    float dx = lastTouchX - ev.getX(); lastTouchX = ev.getX();
                    doScrollTo(scrollX + (int) dx); break;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    velTracker.computeCurrentVelocity(1000);
                    scroller.fling(scrollX, 0, (int) -velTracker.getXVelocity(), 0,
                            0, Math.max(0, totalW - getWidth()), 0, 0);
                    velTracker.clear(); postInvalidateOnAnimation(); break;
            }
            return true;
        }

        // ── CellView ──────────────────────────────────────────────────────────

        final class CellView extends View {

            Bitmap  iconBitmap;
            AppInfo boundApp;
            int     boundIndex;

            private final Paint phRing;

            CellView(Context ctx) {
                super(ctx);
                phRing = new Paint(Paint.ANTI_ALIAS_FLAG);
                phRing.setStyle(Paint.Style.STROKE);
                phRing.setColor(0x55FFFFFF);
                phRing.setStrokeWidth(dp(1));
                setFocusable(true); setFocusableInTouchMode(true);
                setClickable(true); setWillNotDraw(false);

                setOnClickListener(v -> { if (boundApp != null) launchApp(boundApp); });

                setOnFocusChangeListener((v, focused) -> {
                    animate().cancel();
                    animate().scaleX(focused ? 1.10f : 1f).scaleY(focused ? 1.10f : 1f).setDuration(120).start();
                    if (focused) { focusedIndex = boundIndex; positionRing(this); ensureVisible(boundIndex); }
                    else { RingView rv = ringView; if (rv != null) rv.setVisibility(View.INVISIBLE); }
                });

                setOnKeyListener((v, kc, ev) -> {
                    if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                    switch (kc) {
                        case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                        case KeyEvent.KEYCODE_BUTTON_A: performClick(); return true;
                        case KeyEvent.KEYCODE_DPAD_LEFT:  requestFocusOnIndex(boundIndex - 1); return true;
                        case KeyEvent.KEYCODE_DPAD_RIGHT: requestFocusOnIndex(boundIndex + 1); return true;
                        case KeyEvent.KEYCODE_DPAD_UP:
                            View wb = wifiBtn; if (wb != null) wb.requestFocus(); return true;
                        default: return false;
                    }
                });
            }

            @Override protected void onDraw(Canvas canvas) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                int iconPx = dp(ICON_DP);
                float cx = w / 2f, cy = h / 2f;
                if (iconBitmap != null && !iconBitmap.isRecycled()) {
                    float half = iconBitmap.getWidth() / 2f;
                    canvas.drawBitmap(iconBitmap, cx - half, cy - half, sCellPaint);
                } else {
                    float r = iconPx / 2f - dp(2);
                    canvas.drawCircle(cx, cy, r, sPhFill);
                    canvas.drawCircle(cx, cy, r - dp(1) / 2f, phRing);
                }
            }

            void setIconBitmap(Bitmap bmp) { iconBitmap = bmp; invalidate(); }

            void bind(AppInfo app, int index) {
                boundApp = app; boundIndex = index;
                Bitmap cached = iconCache.get(app.packageName);
                if (cached != null) { iconBitmap = cached; invalidate(); }
                else { iconBitmap = null; invalidate(); loadIconAsync(app, this); }
            }
        }
    }

    // =========================================================================
    // App loading
    // =========================================================================

    private void loadApps() {
        if (!appsLoading.compareAndSet(false, true)) return;
        try {
            appExecutor.execute(() -> {
                // cheapSig(): package-names only — zero label Binder calls.
                // Short-circuits immediately if nothing changed.
                String sig = cheapSig();
                if (sig.equals(lastAppSig) && !appList.isEmpty()) { appsLoading.set(false); return; }
                List<AppInfo> fresh = queryApps();
                if (!destroyed) {
                    runOnUiThread(() -> {
                        appsLoading.set(false);
                        lastAppSig = sig;
                        // Evict icons for apps that are no longer installed
                        LruCache<String, Bitmap> cache = iconCache;
                        if (cache != null) {
                            ArraySet<String> pkgs = new ArraySet<>(fresh.size());
                            for (AppInfo a : fresh) pkgs.add(a.packageName);
                            for (AppInfo old : appList)
                                if (!pkgs.contains(old.packageName)) cache.remove(old.packageName);
                        }
                        appList.clear(); appList.addAll(fresh);
                        RecyclingShelfView s = shelf;
                        if (s != null) s.setApps(appList);
                    });
                } else { appsLoading.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { appsLoading.set(false); }
    }

    private String cheapSig() {
        try {
            ArraySet<String> pkgs = new ArraySet<>();
            Intent tvI  = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            Intent mobI = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> tv, mob;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PackageManager.ResolveInfoFlags f = PackageManager.ResolveInfoFlags.of(0);
                tv  = pm.queryIntentActivities(tvI,  f);
                mob = pm.queryIntentActivities(mobI, f);
            } else {
                //noinspection deprecation
                tv  = pm.queryIntentActivities(tvI,  0);
                //noinspection deprecation
                mob = pm.queryIntentActivities(mobI, 0);
            }
            for (ResolveInfo r : tv)  if (r.activityInfo != null) pkgs.add(r.activityInfo.packageName);
            for (ResolveInfo r : mob) if (r.activityInfo != null) pkgs.add(r.activityInfo.packageName);
            List<String> sorted = new ArrayList<>(pkgs);
            Collections.sort(sorted);
            StringBuilder sb = new StringBuilder();
            for (String p : sorted) sb.append(p).append('|');
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private List<AppInfo> queryApps() {
        String self = getPackageName();
        ArraySet<String> seen = new ArraySet<>();
        List<AppInfo> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.ResolveInfoFlags f = PackageManager.ResolveInfoFlags.of(0);
            addApps(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), f), self, seen, out);
            addApps(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), f), self, seen, out);
        } else {
            //noinspection deprecation
            addApps(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0), self, seen, out);
            //noinspection deprecation
            addApps(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0), self, seen, out);
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void addApps(List<ResolveInfo> list, String self, ArraySet<String> seen, List<AppInfo> out) {
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(self)) continue;
            if (!seen.add(ai.packageName + '/' + ai.name)) continue;
            out.add(new AppInfo(ai.packageName, ri.loadLabel(pm).toString(),
                    new ComponentName(ai.packageName, ai.name), ri));
        }
    }

    private void launchApp(AppInfo app) {
        Intent i = pm.getLaunchIntentForPackage(app.packageName);
        if (i != null) {
            i.setComponent(app.component); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); return; } catch (Exception ignored) {}
        }
        try {
            Intent d = new Intent(Intent.ACTION_MAIN);
            d.setComponent(app.component); d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(d);
        } catch (Exception e) { showToast("App not available"); }
    }

    // =========================================================================
    // Icon loading
    // FIX 1: iconInflight is a UI-thread-only map. All reads/writes happen on
    // the main thread: preWarmIcon() called from setApps() (UI thread),
    // loadIconAsync() called from bind() (UI thread), and all runOnUiThread
    // lambdas. No synchronisation needed. Using HashMap (same O(1) perf as
    // ArrayMap but documents the contract clearly with a standard type).
    // =========================================================================

    private void preWarmIcon(AppInfo app) {
        String key = app.packageName;
        if (iconCache.get(key) != null || iconInflight.containsKey(key)) return;
        List<RecyclingShelfView.CellView> waiters = new ArrayList<>(0);
        iconInflight.put(key, waiters);
        try {
            iconExecutor.execute(() -> {
                if (destroyed) return;
                Bitmap bmp = null;
                try {
                    bmp = processIcon(app.ri.loadIcon(pm));
                    if (bmp != null) iconCache.put(key, bmp);
                } catch (OutOfMemoryError | RuntimeException ignored) {}
                if (destroyed) return;
                final Bitmap fb = bmp;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    iconInflight.remove(key);
                    for (RecyclingShelfView.CellView cell : waiters)
                        if (key.equals(cell.boundApp != null ? cell.boundApp.packageName : null))
                            cell.setIconBitmap(fb);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { iconInflight.remove(key); }
    }

    private void loadIconAsync(AppInfo app, RecyclingShelfView.CellView target) {
        String key = app.packageName;
        Bitmap cached = iconCache.get(key);
        if (cached != null) { target.setIconBitmap(cached); return; }
        List<RecyclingShelfView.CellView> waiters = iconInflight.get(key);
        if (waiters != null) { waiters.add(target); return; }
        waiters = new ArrayList<>(2); waiters.add(target);
        iconInflight.put(key, waiters);
        final List<RecyclingShelfView.CellView> fw = waiters;
        try {
            iconExecutor.execute(() -> {
                if (destroyed) return;
                Bitmap bmp = null;
                try {
                    bmp = processIcon(app.ri.loadIcon(pm));
                    if (bmp != null) iconCache.put(key, bmp);
                } catch (OutOfMemoryError | RuntimeException ignored) {}
                if (destroyed) return;
                final Bitmap fb = bmp;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    iconInflight.remove(key);
                    for (RecyclingShelfView.CellView cell : fw)
                        if (key.equals(cell.boundApp != null ? cell.boundApp.packageName : null))
                            cell.setIconBitmap(fb);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { iconInflight.remove(key); }
    }

    // =========================================================================
    // Icon processing
    // =========================================================================

    private Bitmap processIcon(Drawable d) {
        if (d == null) return null;
        int sz = dp(ICON_DP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) d;
            int bleed = Math.round(sz * 18f / 108f);
            int full  = sz + bleed * 2;
            Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            if (aid.getBackground() != null) {
                aid.getBackground().setBounds(-bleed, -bleed, full - bleed, full - bleed);
                aid.getBackground().draw(c);
            }
            if (aid.getForeground() != null) {
                aid.getForeground().setBounds(-bleed, -bleed, full - bleed, full - bleed);
                aid.getForeground().draw(c);
            }
            return clipToCircle(out, sz);
        }

        Bitmap raw = renderDrawable(d, sz);
        if (raw == null) return null;

        int   fill    = iconFillColour(raw, sz);
        boolean needsFill = fill != 0;
        float   scale     = needsFill ? 0.82f : 1.10f;
        int     csz       = Math.round(sz * scale);
        int     inset     = (sz - csz) / 2;

        Bitmap out    = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        if (needsFill) {
            // sWhiteFill is initialised to Color.WHITE in the static block.
            // iconFillColour() returns only Color.WHITE or 0 — never any other
            // colour — so setColor() here is redundant AND a data race
            // (static Paint mutated on iconExecutor threads). Removed.
            canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, sWhiteFill);
        }

        Matrix mx = sMatrixTL.get();
        mx.setScale((float) csz / sz, (float) csz / sz);
        mx.postTranslate(inset, inset);
        canvas.drawBitmap(raw, mx, sDrawPaint);
        raw.recycle();

        return clipToCircle(out, sz);
    }

    // Returns the fill colour to paint behind a transparent icon, or 0 if no fill needed.
    // Two conditions must both be true to use a white fill:
    //   1. The icon has significant edge transparency (≥60% of sampled pixels transparent)
    //   2. The non-transparent content pixels are dark (avg luminance < 0.55)
    //      — skips white fill for apps like SmartTube whose logo is already light/white,
    //        where adding a white circle behind it makes the icon invisible.
    private int iconFillColour(Bitmap src, int sz) {
        int needed = src.getByteCount();
        byte[] px = sPixelBuf.get();
        if (px == null || px.length < needed) { px = new byte[needed]; sPixelBuf.set(px); }
        ByteBuffer buf = ByteBuffer.wrap(px);
        buf.rewind();
        src.copyPixelsToBuffer(buf);
        int step = Math.max(1, sz / 10);
        int total = 0, transparent = 0;
        float lumSum = 0f; int lumCount = 0;
        for (int y = step / 2; y < sz; y += step) {
            for (int x = step / 2; x < sz; x += step) {
                int base = (y * sz + x) * 4;
                int a = px[base + 3] & 0xFF;
                if (a < 30) { transparent++; }
                else {
                    // sRGB -> relative luminance (fast approximation)
                    float r = (px[base]     & 0xFF) / 255f;
                    float g = (px[base + 1] & 0xFF) / 255f;
                    float b = (px[base + 2] & 0xFF) / 255f;
                    lumSum += 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    lumCount++;
                }
                total++;
            }
        }
        if (total == 0) return 0;
        boolean hasTransparentEdge = (float) transparent / total >= 0.40f;
        if (!hasTransparentEdge) return 0;
        // Only add white fill if the visible content is actually dark
        float avgLum = lumCount > 0 ? lumSum / lumCount : 0f;
        return avgLum < 0.55f ? Color.WHITE : 0;
    }

    private Bitmap renderDrawable(Drawable d, int sz) {
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null && !src.isRecycled() && src.getWidth() > 0 && src.getHeight() > 0) {
                Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
                Matrix mx  = sMatrixTL.get();
                mx.setScale((float) sz / src.getWidth(), (float) sz / src.getHeight());
                new Canvas(out).drawBitmap(src, mx, sDrawPaint);
                return out;
            }
        }
        int w = d.getIntrinsicWidth()  > 0 ? d.getIntrinsicWidth()  : sz;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : sz;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h); d.draw(new Canvas(bmp));
        if (w == sz && h == sz) return bmp;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Matrix mx  = sMatrixTL.get();
        mx.setScale((float) sz / w, (float) sz / h);
        new Canvas(out).drawBitmap(bmp, mx, sDrawPaint);
        bmp.recycle(); return out;
    }

    private Bitmap clipToCircle(Bitmap src, int sz) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int sc = c.saveLayer(0, 0, sz, sz, null);
        c.drawCircle(sz / 2f, sz / 2f, sz / 2f, sMaskPaint);
        c.drawBitmap(src, 0, 0, sSrcInPaint);
        c.restoreToCount(sc); src.recycle(); return out;
    }

    // =========================================================================
    // Ring / focus indicator
    // =========================================================================

    private void positionRing(View cell) {
        RingView rv = ringView; FrameLayout r = root;
        if (rv == null || r == null || !cell.isAttachedToWindow()) return;
        int[] cs = new int[2], rs = new int[2];
        cell.getLocationOnScreen(cs); r.getLocationOnScreen(rs);
        float cx   = (cs[0] - rs[0]) + cell.getWidth()  / 2f;
        float cy   = (cs[1] - rs[1]) + cell.getHeight() / 2f;
        int   rvW  = rv.getWidth();
        if (rvW == 0) rvW = dp(ICON_DP) + dp(RING_STROKE_DP) * 2;
        float half = rvW / 2f;
        rv.setX(cx - half); rv.setY(cy - half); rv.setVisibility(View.VISIBLE);
    }

    // =========================================================================
    // Clock
    // =========================================================================

    private void startClock() {
        if (!clockRunning) {
            clockRunning = true;
            TextView cv = clockView;
            if (cv != null) cv.setText(formatClock(System.currentTimeMillis()), TextView.BufferType.SPANNABLE);
            long now = System.currentTimeMillis();
            clockHandler.postDelayed(clockTick, CLOCK_MS - (now % CLOCK_MS));
        }
    }

    private void stopClock() { clockRunning = false; clockHandler.removeCallbacks(clockTick); }

    // =========================================================================
    // Network / WiFi
    // =========================================================================

    private void checkWifiNow() {
        if (cm == null) return;
        boolean connected = false;
        try {
            Network net = cm.getActiveNetwork();
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            connected = caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ignored) {}
        wifiConnected = connected;
        View wb = wifiBtn; if (wb != null) wb.invalidate();
    }

    private void registerNetworkCallback() {
        if (cm == null) return;
        try {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    try {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                        boolean ok = caps != null
                                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                        if (ok == wifiConnected) return;
                        wifiConnected = ok;
                    } catch (Exception ignored) { return; }
                    clockHandler.post(() -> { View wb = wifiBtn; if (wb != null) wb.invalidate(); });
                }
                @Override public void onLost(Network n) {
                    wifiConnected = false;
                    clockHandler.post(() -> { View wb = wifiBtn; if (wb != null) wb.invalidate(); });
                }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                    boolean ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (ok != wifiConnected) {
                        wifiConnected = ok;
                        clockHandler.post(() -> { View wb = wifiBtn; if (wb != null) wb.invalidate(); });
                    }
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), networkCallback);
        } catch (Exception ignored) { networkCallback = null; }
    }

    private void unregisterNetworkCallback() {
        if (cm != null && networkCallback != null) {
            try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            networkCallback = null;
        }
    }

    // =========================================================================
    // Wallpaper
    // =========================================================================

    private void loadWallpaper() {
        String uri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WP_URI, null);
        if (uri != null) applyWallpaperFromUri(Uri.parse(uri)); else loadSystemWallpaper();
    }

    private void loadSystemWallpaper() {
        if (!systemWpLoading.compareAndSet(false, true)) return;
        wpExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                Drawable d = WallpaperManager.getInstance(this).getDrawable();
                if (d != null) bmp = wpDrawable(d);
            } catch (Exception ignored) {}
            final Bitmap fb = bmp; systemWpLoading.set(false);
            if (!destroyed) runOnUiThread(() -> {
                ImageView wv = wallpaperView;
                if (fb != null && wv != null) {
                    // FIX 5: recycle previous wallpaper bitmap before replacing
                    Drawable prev = wv.getDrawable();
                    if (prev instanceof BitmapDrawable) {
                        Bitmap old = ((BitmapDrawable) prev).getBitmap();
                        wv.setImageDrawable(null);
                        if (old != null && !old.isRecycled()) old.recycle();
                    }
                    wv.setImageBitmap(fb);
                }
            });
        });
    }

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
                if (opts.outWidth <= 0 || opts.outHeight <= 0) { userWpLoading.set(false); return; }
                opts.inSampleSize       = calcSampleSize(opts.outWidth, opts.outHeight);
                opts.inJustDecodeBounds = false;
                opts.inPreferredConfig  = Bitmap.Config.RGB_565;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is != null) bmp = BitmapFactory.decodeStream(is, null, opts);
                }
            } catch (Exception | OutOfMemoryError ignored) { bmp = null; }
            final Bitmap fb = bmp; userWpLoading.set(false);
            if (!destroyed) runOnUiThread(() -> {
                ImageView wv = wallpaperView;
                if (fb != null && wv != null) {
                    Drawable prev = wv.getDrawable();
                    if (prev instanceof BitmapDrawable) {
                        Bitmap old = ((BitmapDrawable) prev).getBitmap();
                        wv.setImageDrawable(null);
                        if (old != null && !old.isRecycled()) old.recycle();
                    }
                    wv.setImageBitmap(fb);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_WP_URI, uri.toString()).apply();
                } else { showToast("Could not load wallpaper"); loadSystemWallpaper(); }
            });
        });
    }

    // FIX 5: use RGB_565 for system wallpaper — no alpha channel in wallpapers,
    // halves memory vs ARGB_8888 (4MB vs 8MB on a 1080p display).
    private Bitmap wpDrawable(Drawable d) {
        int w = d.getIntrinsicWidth()  > 0 ? d.getIntrinsicWidth()  : screenW;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : screenH;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        d.setBounds(0, 0, w, h); d.draw(new Canvas(bmp)); return bmp;
    }

    private int calcSampleSize(int srcW, int srcH) {
        int ss = 1;
        if (srcH > screenH || srcW > screenW) {
            int hH = srcH / 2, hW = srcW / 2;
            while ((hH / ss) > screenH || (hW / ss) > screenW) ss *= 2;
        }
        return ss;
    }

    @SuppressWarnings("deprecation")
    private void openStoragePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_PICK_WP); }
        catch (Exception e) { showToast("No file picker available"); }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_WP && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException e) { showToast("Could not get permission for this image"); return; }
                userWpLoading.set(false); applyWallpaperFromUri(uri);
            }
        }
    }

    // =========================================================================
    // Package receiver
    // =========================================================================

    private void registerPkgReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);    f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);  f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(packageReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(packageReceiver, f);
    }

    private void unregisterPkgReceiver() {
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) {}
    }

    // =========================================================================
    // Init / System UI / Util
    // =========================================================================

    private void initCaches() {
        int memMb    = ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryClass();
        int cacheMb  = Math.min(memMb / 8, 16);
        iconCache = new LruCache<String, Bitmap>(cacheMb * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        };
        int cores = Runtime.getRuntime().availableProcessors();
        iconExecutor = new ThreadPoolExecutor(
                Math.max(1, cores - 1), cores, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.DiscardPolicy());
        wpExecutor  = Executors.newSingleThreadExecutor();
        appExecutor = Executors.newSingleThreadExecutor();
    }

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
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private int dp(int v) { return Math.round(v * density); }

    private void showToast(String msg) {
        if (currentToast != null) currentToast.cancel();
        currentToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        currentToast.show();
    }

    // =========================================================================
    // RingView — Apple TV dual-ring focus indicator
    // =========================================================================
    // Draw order (inner → outer):
    //   1. Dark inner ring  (1.5dp, fully opaque charcoal) — contrasts on white fills
    //   2. 2dp transparent gap — lifts ring off icon visually
    //   3. White outer ring (2.5dp, fully opaque white)    — contrasts on dark icons
    //
    // Both rings share one onDraw call — two drawCircle calls on a hardware layer.
    // Zero extra memory. Always visible regardless of icon background colour.
    // Ring is also scaled with the icon animation in positionRing() so they
    // move together during the focus scale transition.

    static final class RingView extends View {
        private final Paint outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float outerStroke;
        private final float innerStroke;
        private final float gap;        // transparent gap between icon edge and inner ring

        RingView(Context ctx, int strokePx) {
            super(ctx);
            // Outer: white, 60% of total stroke budget
            outerStroke = strokePx * 0.60f;
            // Inner: dark charcoal, 40% of total stroke budget
            innerStroke = strokePx * 0.40f;
            // Gap between icon circle edge and inner ring: half a stroke unit
            gap = strokePx * 0.50f;

            outerPaint.setStyle(Paint.Style.STROKE);
            outerPaint.setColor(0xFFFFFFFF);          // solid white
            outerPaint.setStrokeWidth(outerStroke);

            innerPaint.setStyle(Paint.Style.STROKE);
            innerPaint.setColor(0xFF1A1A1A);          // solid near-black
            innerPaint.setStrokeWidth(innerStroke);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth()  / 2f;
            float cy = getHeight() / 2f;
            // Outer ring radius: from centre to mid-stroke of outer ring
            float outerR = cx - outerStroke / 2f;
            if (outerR <= 0) return;
            canvas.drawCircle(cx, cy, outerR, outerPaint);
            // Inner ring sits inside outer ring, separated by gap + half strokes
            float innerR = outerR - outerStroke / 2f - gap - innerStroke / 2f;
            if (innerR > 0) canvas.drawCircle(cx, cy, innerR, innerPaint);
        }
    }
}
