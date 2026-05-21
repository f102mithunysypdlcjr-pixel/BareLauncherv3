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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.style.RelativeSizeSpan;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.Gravity;
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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LauncherActivity extends Activity {

    private static final int    ICON_DP        = 68;
    private static final int    CELL_W_DP      = 90;
    private static final int    CELL_H_DP      = 100;
    private static final int    RING_STROKE_DP = 3;
    private static final long   CLOCK_MS       = 1_000L;
    private static final String PREFS          = "bare_launcher";
    private static final String KEY_WP_URI     = "wp_uri";
    private static final String KEY_SCROLL_IDX = "scroll_idx";
    private static final String KEY_APP_ORDER  = "app_order";
    private static final int    MATCH          = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int    WRAP           = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int    REQ_PICK_WP    = 42;
    private static final int    REQ_UNINSTALL  = 43;

    private static final ThreadLocal<Matrix> sMatrixTL = new ThreadLocal<Matrix>() {
        @Override protected Matrix initialValue() { return new Matrix(); }
    };
    private static final ThreadLocal<byte[]> sPixelBuf = new ThreadLocal<>();

    private static final Paint sMaskPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sSrcInPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint sDrawPaint  = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
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

    private volatile float      density;
    private          int        screenW, screenH;
    private volatile boolean    destroyed       = false;
    private final AtomicBoolean systemWpLoading = new AtomicBoolean(false);
    private final AtomicBoolean userWpLoading   = new AtomicBoolean(false);
    private final AtomicBoolean appsLoading     = new AtomicBoolean(false);
    private volatile boolean    netConnected    = false;

    private PackageManager      pm;
    private ConnectivityManager cm;

    private RecyclingShelfView shelf;
    private ImageView          wallpaperView;
    private TextView           clockView;
    private View               netBtn;
    private View               wpBtnView;
    private RingView           ringView;
    private FrameLayout        root;
    private Toast              currentToast;

    private final Handler uiHandler    = new Handler(Looper.getMainLooper());
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private       boolean clockRunning = false;

    private final java.util.Calendar       clockCal   = java.util.Calendar.getInstance();
    private final char[]                   clockChars = new char[8];
    private final SpannableStringBuilder   clockSsb   = new SpannableStringBuilder();
    private final RelativeSizeSpan         clockSpan  = new RelativeSizeSpan(0.55f);

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (destroyed || !clockRunning) return;
            TextView cv = clockView;
            if (cv != null) cv.setText(buildClock(System.currentTimeMillis()), TextView.BufferType.SPANNABLE);
            long now = System.currentTimeMillis();
            clockHandler.postDelayed(this, CLOCK_MS - (now % CLOCK_MS));
        }
    };

    private CharSequence buildClock(long ms) {
        clockCal.setTimeInMillis(ms);
        int hour = clockCal.get(java.util.Calendar.HOUR);
        if (hour == 0) hour = 12;
        int min  = clockCal.get(java.util.Calendar.MINUTE);
        int ampm = clockCal.get(java.util.Calendar.AM_PM);
        int pos  = 0;
        if (hour >= 10) clockChars[pos++] = (char)('0' + hour / 10);
        clockChars[pos++] = (char)('0' + hour % 10);
        clockChars[pos++] = ':';
        clockChars[pos++] = (char)('0' + min / 10);
        clockChars[pos++] = (char)('0' + min % 10);
        clockChars[pos++] = ' ';
        int amStart = pos;
        clockChars[pos++] = ampm == java.util.Calendar.AM ? 'A' : 'P';
        clockChars[pos++] = 'M';
        clockSsb.clear(); clockSsb.clearSpans();
        clockSsb.append(String.valueOf(clockChars, 0, pos));  // valueOf reuses buf, no new char[]
        clockSsb.setSpan(clockSpan, amStart, pos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return clockSsb;
    }

    private ThreadPoolExecutor       iconExecutor;
    private ExecutorService          wpExecutor;
    private ExecutorService          appExecutor;
    private LruCache<String, Bitmap> iconCache;

    private final ArrayMap<String, List<RecyclingShelfView.CellView>> iconInflight = new ArrayMap<>();
    private final List<AppInfo> appList = new ArrayList<>();

    private boolean pkgChangedWhilePaused = false;
    private ViewTreeObserver.OnGlobalLayoutListener focusRestoreListener;
    private final int[]    ringCellLoc      = new int[2];
    private final int[]    ringRootLoc      = new int[2];
    private       int      cachedRingSize   = 0;
    private final Runnable pkgReloadRunnable = this::loadApps;
    private       FrameLayout menuOverlay   = null;
    private       TextView    menuUninstall = null;
    private       TextView    menuMove      = null;
    private final int[]    menuCellLoc      = new int[2];
    private final int[]    menuRootLoc      = new int[2];

    private ConnectivityManager.NetworkCallback networkCallback;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_REPLACED.equals(action) || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                Uri data = intent.getData();
                if (data != null) {
                    String pkg = data.getSchemeSpecificPart();
                    if (iconCache != null) iconCache.remove(pkg);
                    iconInflight.remove(pkg);
                }
            }
            pkgChangedWhilePaused = true;
            RecyclingShelfView s = shelf;
            if (s == null) return;
            s.removeCallbacks(pkgReloadRunnable);
            s.postDelayed(pkgReloadRunnable, 400);
        }
    };

    static final class AppInfo {
        final String        packageName;
        final String        label;
        final ComponentName component;
        final ResolveInfo   ri;
        AppInfo(String pkg, String lbl, ComponentName cmp, ResolveInfo r) {
            packageName = pkg; label = lbl; component = cmp; ri = r;
        }
    }

    private void applyStoredOrder(List<AppInfo> apps) {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_APP_ORDER, null);
        if (raw == null || raw.isEmpty()) return;
        String[] order = raw.split(",", -1);
        ArrayMap<String, Integer> rank = new ArrayMap<>(order.length);
        for (int i = 0; i < order.length; i++) rank.put(order[i], i);
        Collections.sort(apps, (a, b) -> {
            Integer ra = rank.get(a.packageName), rb = rank.get(b.packageName);
            if (ra != null && rb != null) return ra - rb;
            if (ra != null) return -1;
            if (rb != null) return  1;
            return a.label.compareToIgnoreCase(b.label);
        });
    }

    private void saveOrder() {
        if (appList.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < appList.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(appList.get(i).packageName);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_APP_ORDER, sb.toString()).apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density; screenW = dm.widthPixels; screenH = dm.heightPixels;
        pm = getPackageManager();
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        initCaches();
        setContentView(buildLayout());
        hideSystemUI();
        loadWallpaper();
        loadApps();
        registerPkgReceiver();
        registerNetworkCallback();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                        RecyclingShelfView s = shelf;
                        if (s != null && s.reorderMode) s.exitReorderMode(false);
                    });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        startClock();
        checkNetNow();
        if (pkgChangedWhilePaused) { pkgChangedWhilePaused = false; loadApps(); }
        RecyclingShelfView s = shelf;
        if (s != null) {
            int saved = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_SCROLL_IDX, 0);
            if (!appList.isEmpty()) s.focusedIndex = Math.min(saved, appList.size() - 1);
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
        RecyclingShelfView s = shelf;
        if (s != null) {
            if (s.reorderMode) s.exitReorderMode(false);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_SCROLL_IDX, s.focusedIndex).apply();
            if (focusRestoreListener != null) {
                ViewTreeObserver vto = s.getViewTreeObserver();
                if (vto.isAlive()) vto.removeOnGlobalLayoutListener(focusRestoreListener);
                focusRestoreListener = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopClock();
        clockHandler.removeCallbacksAndMessages(null);
        uiHandler.removeCallbacksAndMessages(null);
        unregisterPkgReceiver();
        unregisterNetworkCallback();
        shutdown(iconExecutor); shutdown(wpExecutor); shutdown(appExecutor);
        if (iconCache != null) iconCache.evictAll();
        iconInflight.clear();
        wallpaperView = null; clockView = null; shelf = null;
        wpBtnView = null; netBtn = null; ringView = null; root = null;
        super.onDestroy();
    }

    private void shutdown(ExecutorService ex) {
        if (ex == null) return;
        ex.shutdown();
        try { ex.awaitTermination(300, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { ex.shutdownNow(); }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (iconCache == null) return;
        if      (level >= TRIM_MEMORY_COMPLETE)   {
            iconCache.evictAll(); iconInflight.clear();
            RecyclingShelfView sv = shelf;
            if (sv != null) {
                sv.setApps(Collections.emptyList()); // moves attached → pool
                // Now null every pooled bitmap so the evicted bitmaps can be GC'd
                for (int i = 0; i < sv.pool.size(); i++) sv.pool.get(i).iconBitmap = null;
            }
            appList.clear();
        }
        else if (level >= TRIM_MEMORY_MODERATE)   { iconCache.trimToSize(iconCache.maxSize() / 2); iconInflight.clear(); }
        else if (level >= TRIM_MEMORY_BACKGROUND) { iconCache.trimToSize(iconCache.maxSize() * 3 / 4); iconInflight.clear(); }
    }

    @Override public void onWindowFocusChanged(boolean h) { super.onWindowFocusChanged(h); if (h) hideSystemUI(); }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Dismiss the context menu when the user taps outside its bounds.
        if (ev.getAction() == MotionEvent.ACTION_DOWN && menuOverlay != null
                && menuOverlay.getVisibility() == View.VISIBLE) {
            int mw = menuOverlay.getWidth();
            int mh = menuOverlay.getHeight();
            // Fall back to measured size if layout hasn't run yet (first show)
            if (mw == 0) mw = menuOverlay.getMeasuredWidth();
            if (mh == 0) mh = menuOverlay.getMeasuredHeight();
            if (mw > 0 && mh > 0) {
                int[] loc = menuRootLoc; // reuse pre-allocated array
                menuOverlay.getLocationOnScreen(loc);
                float tx = ev.getRawX(), ty = ev.getRawY();
                boolean inside = tx >= loc[0] && tx <= loc[0] + mw
                              && ty >= loc[1] && ty <= loc[1] + mh;
                if (!inside) {
                    RecyclingShelfView s = shelf;
                    if (s != null && s.reorderMode) s.exitReorderMode(false);
                    return true; // consume the event
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override @SuppressWarnings("deprecation")
    public void onBackPressed() {
        RecyclingShelfView s = shelf;
        if (s != null && s.reorderMode) { s.exitReorderMode(false); return; }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        TextView cv = clockView;
        if (cv != null) cv.setText(buildClock(System.currentTimeMillis()), TextView.BufferType.SPANNABLE);
    }

    private View buildLayout() {
        root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        wallpaperView = new ImageView(this);
        wallpaperView.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(wallpaperView);

        shelf = new RecyclingShelfView(this);
        FrameLayout.LayoutParams shelfLp = new FrameLayout.LayoutParams(MATCH, dp(CELL_H_DP));
        shelfLp.gravity = Gravity.BOTTOM;
        shelfLp.setMargins(0, 0, 0, dp(28));
        shelf.setLayoutParams(shelfLp);
        root.addView(shelf);

        clockView = new TextView(this);
        clockView.setShadowLayer(dp(8), 0, dp(3), 0xDD000000);
        clockView.setPadding(dp(22), dp(11), dp(22), dp(11));
        clockView.setIncludeFontPadding(false);
        FrameLayout.LayoutParams clkLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        clkLp.gravity = Gravity.TOP | Gravity.START;
        clkLp.setMargins(dp(32), dp(24), 0, 0);
        clockView.setLayoutParams(clkLp);
        clockView.setTextColor(Color.WHITE);
        clockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 42);
        clockView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        clockView.setLetterSpacing(0.01f);
        root.addView(clockView);

        final int BTN_SZ  = dp(36);
        final int BTN_GAP = dp(10);
        final int MARG_T  = dp(26);
        final int MARG_E  = dp(28);

        netBtn = buildNetBtn(BTN_SZ);
        netBtn.setAlpha(0.55f);
        FrameLayout.LayoutParams netLp = new FrameLayout.LayoutParams(BTN_SZ, BTN_SZ);
        netLp.gravity = Gravity.TOP | Gravity.END;
        netLp.setMargins(0, MARG_T, MARG_E + BTN_SZ + BTN_GAP, 0);
        netBtn.setLayoutParams(netLp);
        root.addView(netBtn);

        View wpLocal = buildWpBtn(BTN_SZ);
        wpLocal.setAlpha(0.55f);
        wpBtnView = wpLocal;
        FrameLayout.LayoutParams wpLp = new FrameLayout.LayoutParams(BTN_SZ, BTN_SZ);
        wpLp.gravity = Gravity.TOP | Gravity.END;
        wpLp.setMargins(0, MARG_T, MARG_E, 0);
        wpLocal.setLayoutParams(wpLp);
        root.addView(wpLocal);

        int iconPx = dp(ICON_DP), strokePx = dp(RING_STROKE_DP);
        // Add extra padding for the outer shadow bleed (≈ strokePx on each side)
        int ringShadowBleed = strokePx * 2;
        int ringSize = iconPx + strokePx * 2 + dp(4) + ringShadowBleed * 2;
        cachedRingSize = iconPx + strokePx * 2 + dp(4); // logical ring diameter (no bleed)
        ringView = new RingView(this, strokePx, ringShadowBleed);
        ringView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ringView.setLayoutParams(new FrameLayout.LayoutParams(ringSize, ringSize));
        ringView.setVisibility(View.INVISIBLE);
        root.addView(ringView);

        menuOverlay = new FrameLayout(this) {
            @Override public boolean onTouchEvent(MotionEvent ev) {
                // Consume — prevents tap-through to shelf. Dismiss handled by dispatchTouchEvent.
                return true;
            }
        };
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        menuLp.gravity = Gravity.TOP | Gravity.START;
        menuOverlay.setLayoutParams(menuLp);
        menuOverlay.setVisibility(View.GONE);
        menuOverlay.setClipChildren(false);
        menuOverlay.setClipToPadding(false);

        android.widget.LinearLayout menuCol = new android.widget.LinearLayout(this);
        menuCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        menuCol.setGravity(Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable menuBg = new android.graphics.drawable.GradientDrawable();
        menuBg.setColor(0xEE111111);
        menuBg.setCornerRadius(dp(10));
        menuCol.setBackground(menuBg);

        menuUninstall = new TextView(this);
        menuUninstall.setText("✕  Uninstall");
        menuUninstall.setTextColor(0xFFFF6B6B);
        menuUninstall.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        menuUninstall.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        menuUninstall.setGravity(Gravity.CENTER);
        menuUninstall.setPadding(dp(20), dp(12), dp(20), dp(12));
        menuUninstall.setClickable(true);
        menuUninstall.setFocusable(false);
        menuUninstall.setOnClickListener(v -> {
            RecyclingShelfView s = shelf;
            if (s != null && s.reorderMode) {
                s.menuSelection = RecyclingShelfView.MENU_UNINSTALL;
                RecyclingShelfView.CellView cv = s.attached.get(s.dragIndex);
                if (cv != null) cv.triggerUninstall();
                else s.exitReorderMode(false);
            }
        });

        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);

        menuMove = new TextView(this);
        menuMove.setText("⇔  Move");
        menuMove.setTextColor(Color.WHITE);
        menuMove.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        menuMove.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        menuMove.setGravity(Gravity.CENTER);
        menuMove.setPadding(dp(20), dp(12), dp(20), dp(12));
        menuMove.setClickable(true);
        menuMove.setFocusable(false);
        menuMove.setOnClickListener(v -> {
            RecyclingShelfView s = shelf;
            if (s != null && s.reorderMode) {
                s.menuSelection = RecyclingShelfView.MENU_MOVE;
                updateMenuHighlight();
                // "Move" confirm: exit reorder saving order
                s.exitReorderMode(true);
            }
        });

        menuCol.addView(menuUninstall, new android.widget.LinearLayout.LayoutParams(dp(130), WRAP));
        menuCol.addView(divider, new android.widget.LinearLayout.LayoutParams(MATCH, 1));
        menuCol.addView(menuMove, new android.widget.LinearLayout.LayoutParams(dp(130), WRAP));

        menuOverlay.addView(menuCol, new FrameLayout.LayoutParams(WRAP, WRAP));
        root.addView(menuOverlay);

        return root;
    }

    void showContextMenu(View cell) {
        if (menuOverlay == null || menuUninstall == null || menuMove == null) return;
        cell.getLocationOnScreen(menuCellLoc);
        FrameLayout r = root; if (r == null) return;
        r.getLocationOnScreen(menuRootLoc);
        int cellCx    = (menuCellLoc[0] - menuRootLoc[0]) + cell.getWidth() / 2;
        int cellRelY  = (menuCellLoc[1] - menuRootLoc[1]);   // cell top in root coords

        menuOverlay.measure(
                View.MeasureSpec.makeMeasureSpec(r.getWidth(),  View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(r.getHeight(), View.MeasureSpec.AT_MOST));
        int mw = menuOverlay.getMeasuredWidth();
        int mh = menuOverlay.getMeasuredHeight();

        int iconPx        = dp(ICON_DP);
        // icyOffset is iconPx/2 + 4dp — same formula used in CellView.onDraw
        int icyInCell     = iconPx / 2 + dp(4);
        int iconTopInRoot = cellRelY + (icyInCell - iconPx / 2);   // top of icon in root
        int iconBotInRoot = iconTopInRoot + iconPx;

        // Prefer above the icon; fall back to below if it would clip the top
        int menuY = iconTopInRoot - dp(6) - mh;
        if (menuY < dp(8)) menuY = iconBotInRoot + dp(6);
        // Clamp so it never escapes the bottom either
        menuY = Math.min(menuY, r.getHeight() - mh - dp(8));
        menuY = Math.max(menuY, dp(8));

        int menuX = cellCx - mw / 2;
        menuX = Math.max(dp(8), Math.min(menuX, r.getWidth() - mw - dp(8)));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuOverlay.getLayoutParams();
        lp.leftMargin = menuX; lp.topMargin = menuY;
        lp.gravity = Gravity.TOP | Gravity.START;
        menuOverlay.setLayoutParams(lp);
        menuOverlay.setVisibility(View.VISIBLE);
        updateMenuHighlight();
    }

    void hideContextMenu() {
        if (menuOverlay != null) menuOverlay.setVisibility(View.GONE);
    }

    void updateMenuHighlight() {
        RecyclingShelfView s = shelf; if (s == null) return;
        if (menuUninstall == null || menuMove == null) return;
        boolean uninstSel = s.menuSelection == RecyclingShelfView.MENU_UNINSTALL;
        menuUninstall.setBackgroundColor(uninstSel ? 0x552C2C2C : Color.TRANSPARENT);
        menuMove.setBackgroundColor(uninstSel ? Color.TRANSPARENT : 0x552C2C2C);
        menuUninstall.setTextColor(uninstSel ? 0xFFFF6B6B : 0xAAFF6B6B);
        menuMove.setTextColor(uninstSel ? 0xAAFFFFFF : Color.WHITE);
    }

    private View buildNetBtn(int sz) {
        View v = new View(this) {
            private final Paint arcP  = makeBtnPaint(false);
            private final Paint dotP  = makeBtnPaint(true);
            private final Paint hlP   = makeHlPaint();
            private final Paint bgP   = makeBgCirclePaint();
            private final Paint dimP  = makeBtnPaint(false);
            private final RectF oval  = new RectF();
            {
                dimP.setAlpha(70);
            }
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                float cx = w / 2f, cy = h / 2f, r = Math.min(cx, cy);
                c.drawCircle(cx, cy, r, bgP);
                if (isFocused()) c.drawCircle(cx, cy, r, hlP);
                boolean conn = netConnected;
                float ic = r * 1.28f;
                float sw = ic * 0.115f;
                float dotR = sw * 0.9f;
                float dotY = cy + ic * 0.24f;
                float startAngle = 202.5f, sweep = 135f;
                arcP.setStrokeWidth(sw);
                dimP.setStrokeWidth(sw);
                Paint ap = conn ? arcP : dimP;
                c.drawCircle(cx, dotY, dotR, conn ? dotP : dimP);
                float r1 = ic * 0.30f;
                oval.set(cx - r1, dotY - r1, cx + r1, dotY + r1);
                c.drawArc(oval, startAngle, sweep, false, ap);
                float r2 = ic * 0.56f;
                oval.set(cx - r2, dotY - r2, cx + r2, dotY + r2);
                c.drawArc(oval, startAngle, sweep, false, ap);
                if (!conn) {
                    float xs = sw * 0.85f;
                    float x1 = cx - xs, y1 = dotY - xs, x2 = cx + xs, y2 = dotY + xs;
                    c.drawLine(x1, y1, x2, y2, arcP);
                    c.drawLine(x2, y1, x1, y2, arcP);
                }
            }
        };
        v.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        v.setFocusable(true); v.setFocusableInTouchMode(true); v.setClickable(true);
        v.setOnClickListener(view -> openNetSettings());
        v.setOnFocusChangeListener((view, f) -> { view.setAlpha(f ? 1f : 0.55f); view.invalidate(); });
        v.setOnKeyListener((view, kc, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A: view.performClick(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    RecyclingShelfView sd = shelf; if (sd != null) sd.requestFocusOnIndex(0); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    RecyclingShelfView sl = shelf;
                    if (sl != null) sl.requestFocusOnIndex(appList.isEmpty() ? 0 : appList.size() - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    View wb = wpBtnView; if (wb != null) wb.requestFocus(); return true;
                default: return false;
            }
        });
        return v;
    }

    private View buildWpBtn(int sz) {
        View v = new View(this) {
            private final Paint p   = makeBtnStrokePaint();
            private final Paint hlP = makeHlPaint();
            private final Paint bgP = makeBgCirclePaint();
            private final android.graphics.Path mt = new android.graphics.Path();
            private int lw = 0, lh = 0;
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                float cx = w / 2f, cy = h / 2f, r = Math.min(cx, cy);
                c.drawCircle(cx, cy, r, bgP);
                if (isFocused()) c.drawCircle(cx, cy, r, hlP);
                float s = r * 1.30f;
                p.setStrokeWidth(s * 0.10f);
                if (w != lw || h != lh) {
                    lw = w; lh = h;
                    float l = cx - s/2f, rt = cx + s/2f, t = cy - s/2f, b = cy + s/2f;
                    mt.rewind();
                    mt.moveTo(l, b); mt.lineTo(l + s*0.38f, t + s*0.48f);
                    mt.lineTo(l + s*0.62f, t + s*0.66f); mt.lineTo(rt, b);
                }
                c.drawRoundRect(cx - s/2f, cy - s/2f, cx + s/2f, cy + s/2f, s*0.10f, s*0.10f, p);
                c.drawCircle(cx + s*0.17f, cy - s/2f + s*0.26f, s*0.10f, p);
                c.drawPath(mt, p);
            }
        };
        v.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        v.setFocusable(true); v.setFocusableInTouchMode(true); v.setClickable(true);
        v.setOnClickListener(view -> openStoragePicker());
        v.setOnFocusChangeListener((view, f) -> { view.setAlpha(f ? 1f : 0.55f); view.invalidate(); });
        v.setOnKeyListener((view, kc, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A: view.performClick(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    RecyclingShelfView s = shelf;
                    if (s != null) s.requestFocusOnIndex(appList.isEmpty() ? 0 : appList.size() - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    View nb = netBtn; if (nb != null) nb.requestFocus(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    RecyclingShelfView sr = shelf; if (sr != null) sr.requestFocusOnIndex(0); return true;
                default: return false;
            }
        });
        return v;
    }

    private Paint makeBtnPaint(boolean fill) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    private Paint makeBtnStrokePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private Paint makeHlPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55FFFFFF);
        return p;
    }

    private Paint makeBgCirclePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        return p;
    }

    private void openNetSettings() {
        String[] actions = { Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS };
        for (String a : actions) {
            try { startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
            catch (Exception ignored) {}
        }
        showToast("Cannot open network settings");
    }

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

        boolean reorderMode   = false;
        int     dragIndex     = -1;

        // MENU_UNINSTALL=0 (top row), MENU_MOVE=1 (bottom row)
        private static final int MENU_UNINSTALL = 0;
        private static final int MENU_MOVE      = 1;
        int menuSelection = MENU_MOVE;

        RecyclingShelfView(Context ctx) {
            super(ctx);
            scroller = new OverScroller(ctx);
            cellW  = dp(CELL_W_DP);
            cellH  = dp(CELL_H_DP);
            stride = cellW + dp(10) * 2;
            setFocusable(false);
            setClipChildren(false);
        }

        void enterReorderMode(int idx) {
            if (reorderMode) return;
            reorderMode   = true;
            dragIndex     = idx;
            menuSelection = MENU_MOVE;
            rebindAll();
            CellView cv = attached.get(idx); if (cv != null) LauncherActivity.this.showContextMenu(cv);
        }

        void exitReorderMode(boolean persist) {
            if (!reorderMode) return;
            reorderMode = false;
            dragIndex   = -1;
            hideContextMenu();
            if (persist) saveOrder();
            rebindAll();
        }

        void swapWithNeighbour(int targetIdx) {
            if (targetIdx < 0 || targetIdx >= appList.size() || targetIdx == dragIndex) return;
            Collections.swap(appList, dragIndex, targetIdx);
            dragIndex    = targetIdx;
            focusedIndex = dragIndex;
            ensureVisible(dragIndex);
            rebindAll();
            CellView cv = attached.get(dragIndex); if (cv != null) LauncherActivity.this.showContextMenu(cv);
        }

        private void rebindAll() {
            for (int i = 0; i < attached.size(); i++) {
                int idx = attached.keyAt(i);
                if (idx >= 0 && idx < appList.size()) bindCell(attached.valueAt(i), idx);
            }
            int targetIdx = reorderMode ? dragIndex : focusedIndex;
            CellView focused = attached.get(targetIdx);
            if (focused != null) {
                focused.requestFocus();
                focused.invalidate();
            }
            if (reorderMode) updateMenuHighlight();
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
            if (reorderMode) exitReorderMode(false); // guard: don't corrupt dragIndex on list refresh
            hideContextMenu();
            for (int i = 0; i < attached.size(); i++) {
                CellView cv = attached.valueAt(i); cv.setVisibility(GONE); pool.add(cv);
            }
            attached.clear();
            if (apps.isEmpty()) { focusedIndex = 0; scrollX = 0; }
            if (!apps.isEmpty()) focusedIndex = Math.min(focusedIndex, apps.size() - 1);
            totalW = apps.size() * stride; centerX = 0; needsRefill = true;
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
            repositionAttached(); fillVisible();
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
            if (!pool.isEmpty()) {
                CellView cv = pool.remove(pool.size() - 1);
                cv.animate().cancel();          // cancel any in-flight scale animation
                cv.setScaleX(1f); cv.setScaleY(1f); // reset scale before reuse
                cv.setVisibility(VISIBLE);
                return cv;
            }
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
                case MotionEvent.ACTION_UP:
                    velTracker.computeCurrentVelocity(1000);
                    scroller.fling(scrollX, 0, (int) -velTracker.getXVelocity(), 0,
                            0, Math.max(0, totalW - getWidth()), 0, 0);
                    velTracker.recycle(); velTracker = null;
                    postInvalidateOnAnimation(); break;
                case MotionEvent.ACTION_CANCEL:
                    scroller.abortAnimation();
                    velTracker.recycle(); velTracker = null; break;
            }
            return true;
        }

        // ── CellView ──────────────────────────────────────────────────────────

        final class CellView extends View {

            Bitmap  iconBitmap;
            AppInfo boundApp;
            int     boundIndex;
            boolean focused = false;
            private long    centerKeyDownAt  = 0;
            private boolean longPressArmed   = false;
            private boolean longPressFired   = false;

            private final Paint   phRing;
            private final Paint   labelPaint;
            private final Paint   iconPaint;
            private final Paint   dragRingPaint;
            private final TextPaint labelTp;
            private final int     iconPx;
            private final float   phR;
            private final float   phStroke;
            private final float   dragRingExtra;
            private final float   labelOffsetY;
            private final float   labelMaxWInset;
            private final float   icyOffset;
            private       String  labelStr = "";

            CellView(Context ctx) {
                super(ctx);
                iconPx         = dp(ICON_DP);
                phR            = iconPx / 2f - dp(2);
                phStroke       = dp(1);
                dragRingExtra  = dp(3);
                labelOffsetY   = iconPx / 2f + dp(12);
                labelMaxWInset = dp(6);
                icyOffset      = iconPx / 2f + dp(4);

                phRing = new Paint(Paint.ANTI_ALIAS_FLAG);
                phRing.setStyle(Paint.Style.STROKE);
                phRing.setColor(0x55FFFFFF);
                phRing.setStrokeWidth(phStroke);

                iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

                labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                labelPaint.setColor(Color.WHITE);
                labelPaint.setTextSize(dp(11));
                labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                labelPaint.setTextAlign(Paint.Align.CENTER);
                labelPaint.setShadowLayer(dp(4), 0, dp(2), 0xDD000000);

                labelTp = new TextPaint(labelPaint);

                dragRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                dragRingPaint.setStyle(Paint.Style.STROKE);
                dragRingPaint.setColor(0xCCFFFFFF);
                dragRingPaint.setStrokeWidth(dp(2));

                setFocusable(true); setFocusableInTouchMode(true);
                setClickable(true); setLongClickable(true); setWillNotDraw(false);

                setOnClickListener(v -> {
                    if (boundApp == null) return;
                    if (!reorderMode) launchApp(boundApp);
                    // In reorder mode clicks are consumed but do nothing — menu buttons handle confirm/cancel
                });

                setOnLongClickListener(v -> {
                    if (boundApp == null || reorderMode) return true;
                    enterReorderMode(boundIndex);
                    return true;
                });

                setOnFocusChangeListener((v, f) -> {
                    focused = f;
                    if (!reorderMode) {
                        animate().cancel();
                        animate().scaleX(f ? 1.10f : 1f).scaleY(f ? 1.10f : 1f).setDuration(120).start();
                    }
                    invalidate();
                    if (f) {
                        focusedIndex = boundIndex;
                        post(() -> { if (focused) positionRing(this); });
                        if (!reorderMode) ensureVisible(boundIndex);
                    } else {
                        if (!reorderMode) {
                            RingView rv = ringView; if (rv != null) rv.setVisibility(View.INVISIBLE);
                        }
                    }
                });

                setOnKeyListener((v, kc, ev) -> {
                    if (reorderMode) {
                        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                        switch (kc) {
                            case KeyEvent.KEYCODE_DPAD_LEFT:
                                if (menuSelection == MENU_MOVE) swapWithNeighbour(dragIndex - 1);
                                return true;
                            case KeyEvent.KEYCODE_DPAD_RIGHT:
                                if (menuSelection == MENU_MOVE) swapWithNeighbour(dragIndex + 1);
                                return true;
                            case KeyEvent.KEYCODE_DPAD_UP:
                                if (menuSelection != MENU_UNINSTALL) { menuSelection = MENU_UNINSTALL; updateMenuHighlight(); }
                                return true;
                            case KeyEvent.KEYCODE_DPAD_DOWN:
                                if (menuSelection == MENU_UNINSTALL) { menuSelection = MENU_MOVE; updateMenuHighlight(); }
                                else exitReorderMode(true);
                                return true;
                            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                            case KeyEvent.KEYCODE_BUTTON_A:
                                if (menuSelection == MENU_UNINSTALL) triggerUninstall();
                                else exitReorderMode(true);
                                return true;
                            case KeyEvent.KEYCODE_BACK:
                                exitReorderMode(false); return true;
                            default: return false;
                        }
                    }

                    boolean isCenterKey = kc == KeyEvent.KEYCODE_DPAD_CENTER
                            || kc == KeyEvent.KEYCODE_ENTER
                            || kc == KeyEvent.KEYCODE_BUTTON_A;

                    if (isCenterKey) {
                        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                            if (ev.getRepeatCount() == 0) {
                                centerKeyDownAt = System.currentTimeMillis();
                                longPressArmed  = true;
                                longPressFired  = false;
                            } else if (longPressArmed && !longPressFired) {
                                long held = System.currentTimeMillis() - centerKeyDownAt;
                                if (held >= 600 && boundApp != null && !reorderMode) {
                                    longPressFired = true;
                                    longPressArmed = false;
                                    centerKeyDownAt = 0;
                                    enterReorderMode(boundIndex);
                                }
                            }
                            return true;
                        }
                        if (ev.getAction() == KeyEvent.ACTION_UP) {
                            boolean wasArmed = longPressArmed;
                            longPressArmed  = false;
                            longPressFired  = false;
                            centerKeyDownAt = 0;
                            if (wasArmed && !reorderMode) performClick();
                            return true;
                        }
                        return false;
                    }

                    if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                    switch (kc) {
                        case KeyEvent.KEYCODE_DPAD_LEFT:  requestFocusOnIndex(boundIndex - 1); return true;
                        case KeyEvent.KEYCODE_DPAD_RIGHT: requestFocusOnIndex(boundIndex + 1); return true;
                        case KeyEvent.KEYCODE_DPAD_UP:
                            View nb = netBtn; if (nb != null) nb.requestFocus(); return true;
                        default: return false;
                    }
                });
            }

            void triggerUninstall() {
                if (boundApp == null) return;
                AppInfo appToUninstall = boundApp;
                exitReorderMode(false);
                // ACTION_DELETE works universally on Android TV without needing
                // REQUEST_DELETE_PACKAGES. Falls back to deprecated intent.
                try {
                    Intent i = new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + appToUninstall.packageName));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e1) {
                    try {
                        Intent i2 = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                                Uri.parse("package:" + appToUninstall.packageName));
                        i2.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                        i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivityForResult(i2, REQ_UNINSTALL);
                    } catch (Exception e2) {
                        showToast("Cannot uninstall " + appToUninstall.label);
                    }
                }
            }

            @Override protected void onDraw(Canvas canvas) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;

                float cx  = w / 2f;
                float icy = icyOffset;

                boolean isDragTarget = reorderMode && boundIndex == dragIndex;

                if (reorderMode && !isDragTarget) {
                    iconPaint.setAlpha(102);
                    drawIcon(canvas, cx, icy);
                    iconPaint.setAlpha(255);
                } else {
                    drawIcon(canvas, cx, icy);
                }

                if (isDragTarget) {
                    canvas.drawCircle(cx, icy, iconPx / 2f + dragRingExtra, dragRingPaint);
                }

                if ((focused && !reorderMode) && !labelStr.isEmpty()) {
                    float labelY = icy + labelOffsetY;
                    if (labelY < h) {
                        float maxW = w - labelMaxWInset;
                        String display = labelPaint.measureText(labelStr) > maxW
                                ? TextUtils.ellipsize(labelStr, labelTp, maxW, TextUtils.TruncateAt.END).toString()
                                : labelStr;
                        canvas.drawText(display, cx, labelY, labelPaint);
                    }
                }


            }

            private void drawIcon(Canvas canvas, float cx, float icy) {
                if (iconBitmap != null && !iconBitmap.isRecycled()) {
                    float half = iconBitmap.getWidth() / 2f;
                    canvas.drawBitmap(iconBitmap, cx - half, icy - half, iconPaint);
                } else {
                    canvas.drawCircle(cx, icy, phR, sPhFill);
                    canvas.drawCircle(cx, icy, phR - phStroke / 2f, phRing);
                }
            }



            void setIconBitmap(Bitmap bmp) { iconBitmap = bmp; invalidate(); }

            void bind(AppInfo app, int index) {
                boundApp = app; boundIndex = index; labelStr = app.label;
                // Don't reset focused here — the View system owns focus state.
                // Only reload icon data.
                Bitmap cached = iconCache.get(app.packageName);
                if (cached != null && cached != iconBitmap) { iconBitmap = cached; invalidate(); }
                else if (cached == null) { iconBitmap = null; invalidate(); loadIconAsync(app, this); }
            }
        }
    }
    private void loadApps() {
        if (!appsLoading.compareAndSet(false, true)) return;
        try {
            appExecutor.execute(() -> {
                List<AppInfo> fresh = queryApps();
                applyStoredOrder(fresh);
                if (!destroyed) {
                    runOnUiThread(() -> {
                        appsLoading.set(false);
                        LruCache<String, Bitmap> cache = iconCache;
                        if (cache != null) {
                            ArraySet<String> pkgs = new ArraySet<>(fresh.size());
                            for (AppInfo a : fresh) pkgs.add(a.packageName);
                            for (AppInfo old : appList)
                                if (!pkgs.contains(old.packageName)) cache.remove(old.packageName);
                        }
                        boolean changed = fresh.size() != appList.size();
                        if (!changed) {
                            for (int i = 0; i < fresh.size(); i++) {
                                if (!fresh.get(i).packageName.equals(appList.get(i).packageName)) {
                                    changed = true; break;
                                }
                            }
                        }
                        if (changed) {
                            appList.clear(); appList.addAll(fresh);
                            RecyclingShelfView s = shelf;
                            if (s != null) s.setApps(appList);
                        }
                    });
                } else { appsLoading.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { appsLoading.set(false); }
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

    private void preWarmIcon(AppInfo app) {
        String key = app.packageName;
        if (iconCache.get(key) != null || iconInflight.containsKey(key)) return;
        List<RecyclingShelfView.CellView> waiters = new ArrayList<>(0);
        iconInflight.put(key, waiters);
        try {
            iconExecutor.execute(() -> {
                if (destroyed) return;
                Bitmap bmp = null;
                try { bmp = processIcon(app.ri.loadIcon(pm)); if (bmp != null) iconCache.put(key, bmp); }
                catch (OutOfMemoryError | RuntimeException ignored) {}
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
                try { bmp = processIcon(app.ri.loadIcon(pm)); if (bmp != null) iconCache.put(key, bmp); }
                catch (OutOfMemoryError | RuntimeException ignored) {}
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

    private Bitmap processIcon(Drawable d) {
        if (d == null) return null;
        int sz = dp(ICON_DP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) d;
            int bleed = Math.round(sz * 18f / 108f);
            int full  = sz + bleed * 2;
            Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            if (aid.getBackground() != null) { aid.getBackground().setBounds(-bleed, -bleed, full - bleed, full - bleed); aid.getBackground().draw(c); }
            if (aid.getForeground() != null) { aid.getForeground().setBounds(-bleed, -bleed, full - bleed, full - bleed); aid.getForeground().draw(c); }
            return clipToCircle(out, sz);
        }
        Bitmap raw = renderDrawable(d, sz);
        if (raw == null) return null;
        boolean fill = needsFill(raw, sz);
        int  csz  = Math.round(sz * (fill ? 0.80f : 1.08f));
        int  inset = (sz - csz) / 2;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (fill) canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, sWhiteFill);
        Matrix mx = sMatrixTL.get();
        mx.setScale((float) csz / sz, (float) csz / sz);
        mx.postTranslate(inset, inset);
        canvas.drawBitmap(raw, mx, sDrawPaint);
        raw.recycle();
        return clipToCircle(out, sz);
    }

    private boolean needsFill(Bitmap src, int sz) {
        int q1 = sz / 4, q3 = sz * 3 / 4;
        if (src.getConfig() == Bitmap.Config.ARGB_8888) {
            int needed = src.getByteCount();
            byte[] px = sPixelBuf.get();
            if (px == null || px.length < needed) { px = new byte[needed]; sPixelBuf.set(px); }
            ByteBuffer buf = ByteBuffer.wrap(px); buf.rewind();
            src.copyPixelsToBuffer(buf);
            int step = Math.max(1, (q3 - q1) / 10), total = 0, trans = 0;
            for (int y = q1; y < q3; y += step)
                for (int x = q1; x < q3; x += step) {
                    if ((px[(y * sz + x) * 4] & 0xFF) < 20) trans++;  // ARGB_8888: byte[0]=A
                    total++;
                }
            return total > 0 && (float) trans / total >= 0.50f;
        }
        int step = Math.max(1, (q3 - q1) / 10), total = 0, trans = 0;
        for (int y = q1; y < q3; y += step)
            for (int x = q1; x < q3; x += step) {
                if (Color.alpha(src.getPixel(x, y)) < 20) trans++;
                total++;
            }
        return total > 0 && (float) trans / total >= 0.50f;
    }

    private Bitmap renderDrawable(Drawable d, int sz) {
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null && !src.isRecycled() && src.getWidth() > 0 && src.getHeight() > 0) {
                Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
                Matrix mx = sMatrixTL.get();
                mx.setScale((float) sz / src.getWidth(), (float) sz / src.getHeight());
                new Canvas(out).drawBitmap(src, mx, sDrawPaint);
                return out;
            }
        }
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : sz;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : sz;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h); d.draw(new Canvas(bmp));
        if (w == sz && h == sz) return bmp;
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Matrix mx = sMatrixTL.get();
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

    private void positionRing(View cell) {
        RingView rv = ringView; FrameLayout r = root;
        if (rv == null || r == null || !cell.isAttachedToWindow()) return;
        if (cell.getWidth() == 0) return;
        cell.getLocationOnScreen(ringCellLoc); r.getLocationOnScreen(ringRootLoc);
        float cx   = (ringCellLoc[0] - ringRootLoc[0]) + cell.getWidth() / 2f;
        // Icon is drawn at icyOffset from the cell's top edge (iconPx/2 + 4dp).
        int iconPx = dp(ICON_DP);
        float icyOffset = iconPx / 2f + dp(4);
        float cy   = (ringCellLoc[1] - ringRootLoc[1]) + icyOffset;
        // rv is larger than cachedRingSize by shadowBleed on each side
        float half = rv.getWidth() / 2f;
        rv.setX(cx - half); rv.setY(cy - half); rv.setVisibility(View.VISIBLE);
    }

    private void startClock() {
        if (!clockRunning) {
            clockRunning = true;
            TextView cv = clockView;
            if (cv != null) cv.setText(buildClock(System.currentTimeMillis()), TextView.BufferType.SPANNABLE);
            long now = System.currentTimeMillis();
            clockHandler.postDelayed(clockTick, CLOCK_MS - (now % CLOCK_MS));
        }
    }

    private void stopClock() { clockRunning = false; clockHandler.removeCallbacks(clockTick); }

    private void checkNetNow() {
        if (cm == null) return;
        boolean c = false;
        try {
            Network net = cm.getActiveNetwork();
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            c = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        } catch (Exception ignored) {}
        netConnected = c;
        View nb = netBtn; if (nb != null) nb.invalidate();
    }

    private void registerNetworkCallback() {
        if (cm == null) return;
        try {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    if (netConnected) return;
                    netConnected = true;
                    uiHandler.post(() -> { View nb = netBtn; if (nb != null) nb.invalidate(); });
                }
                @Override public void onLost(Network n) {
                    boolean still = false;
                    try {
                        Network active = cm.getActiveNetwork();
                        if (active != null) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                            still = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                                    || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                        }
                    } catch (Exception ignored) {}
                    if (still == netConnected) return;
                    netConnected = still;
                    uiHandler.post(() -> { View nb = netBtn; if (nb != null) nb.invalidate(); });
                }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                    boolean ok = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                            || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    if (ok == netConnected) return;
                    netConnected = ok;
                    uiHandler.post(() -> { View nb = netBtn; if (nb != null) nb.invalidate(); });
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

    private void loadWallpaper() {
        String uri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WP_URI, null);
        if (uri != null) applyWallpaperFromUri(Uri.parse(uri)); else loadSystemWallpaper();
    }

    private void loadSystemWallpaper() {
        if (!systemWpLoading.compareAndSet(false, true)) return;
        wpExecutor.execute(() -> {
            Bitmap bmp = null;
            try { Drawable d = WallpaperManager.getInstance(this).getDrawable(); if (d != null) bmp = wpDrawable(d); }
            catch (Exception ignored) {}
            final Bitmap fb = bmp; systemWpLoading.set(false);
            if (!destroyed) runOnUiThread(() -> {
                ImageView wv = wallpaperView;
                if (fb != null && wv != null) { recyclePrev(wv); wv.setImageBitmap(fb); }
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
                try (InputStream is = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(is, null, opts); }
                if (opts.outWidth <= 0 || opts.outHeight <= 0) { userWpLoading.set(false); return; }
                opts.inSampleSize = calcSampleSize(opts.outWidth, opts.outHeight);
                opts.inJustDecodeBounds = false;
                opts.inPreferredConfig  = Bitmap.Config.RGB_565;
                try (InputStream is = getContentResolver().openInputStream(uri)) { if (is != null) bmp = BitmapFactory.decodeStream(is, null, opts); }
            } catch (Exception | OutOfMemoryError ignored) { bmp = null; }
            final Bitmap fb = bmp; userWpLoading.set(false);
            if (!destroyed) runOnUiThread(() -> {
                ImageView wv = wallpaperView;
                if (fb != null && wv != null) {
                    recyclePrev(wv); wv.setImageBitmap(fb);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_WP_URI, uri.toString()).apply();
                } else { showToast("Could not load wallpaper"); loadSystemWallpaper(); }
            });
        });
    }

    private void recyclePrev(ImageView iv) {
        Drawable prev = iv.getDrawable();
        if (prev instanceof BitmapDrawable) {
            Bitmap old = ((BitmapDrawable) prev).getBitmap();
            iv.setImageDrawable(null);
            if (old != null && !old.isRecycled()) old.recycle();
        }
    }

    private Bitmap wpDrawable(Drawable d) {
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : screenW;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : screenH;
        int ss = calcSampleSize(w, h);
        int sw = Math.max(1, w / ss), sh = Math.max(1, h / ss);
        Bitmap bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.RGB_565);
        d.setBounds(0, 0, sw, sh); d.draw(new Canvas(bmp));
        return bmp;
    }

    private int calcSampleSize(int srcW, int srcH) {
        int ss = 1;
        while (srcH / ss > screenH || srcW / ss > screenW) ss *= 2;
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
                userWpLoading.set(false);
                applyWallpaperFromUri(uri);
            }
        } else if (req == REQ_UNINSTALL) {
            // Result may be RESULT_CANCELED even on successful uninstall (ACTION_DELETE doesn't
            // always return RESULT_OK). Always refresh — the package receiver is unreliable
            // while we're in the foreground on some ROMs.
            uiHandler.postDelayed(this::loadApps, 400);
        }
    }

    private void registerPkgReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED); f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED); f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(packageReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(packageReceiver, f);
    }

    private void unregisterPkgReceiver() {
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) {}
    }

    private void initCaches() {
        int memMb   = ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryClass();
        int cacheMb = Math.min(memMb / 8, 16);
        iconCache = new LruCache<String, Bitmap>(cacheMb * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        };
        int cores = Runtime.getRuntime().availableProcessors();
        iconExecutor = new ThreadPoolExecutor(Math.max(1, cores - 1), cores, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.DiscardPolicy());
        wpExecutor  = Executors.newSingleThreadExecutor();
        appExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.DiscardOldestPolicy());
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

    static final class RingView extends View {
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint white  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float bleed; // extra inset on each side for shadow room
        private float cxf, cyf, wR, sRo, sRi;

        RingView(Context ctx, int strokePx, int bleedPx) {
            super(ctx);
            this.bleed = bleedPx;
            float ws = strokePx * 0.9f;
            float ds = strokePx * 0.5f;
            shadow.setStyle(Paint.Style.STROKE); shadow.setColor(0xBB000000); shadow.setStrokeWidth(ds);
            white.setStyle(Paint.Style.STROKE);  white.setColor(0xFFFFFFFF);  white.setStrokeWidth(ws);
            inner.setStyle(Paint.Style.STROKE);  inner.setColor(0x88000000);  inner.setStrokeWidth(ds);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            cxf = w / 2f; cyf = h / 2f;
            float ws = white.getStrokeWidth(), ds = shadow.getStrokeWidth();
            // Logical ring radius starts at (w - 2*bleed)/2, inset by half stroke
            float logR = (w - 2f * bleed) / 2f;
            wR  = logR - ws / 2f;
            sRo = wR + ws / 2f + ds / 2f;
            sRi = wR - ws / 2f - ds / 2f;
        }

        @Override protected void onDraw(Canvas c) {
            if (wR <= 0) return;
            c.drawCircle(cxf, cyf, sRo, shadow);
            c.drawCircle(cxf, cyf, wR,  white);
            c.drawCircle(cxf, cyf, sRi, inner);
        }
    }
}
