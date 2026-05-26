package com.bare.launcher;

import android.app.Activity;
import android.app.ActivityManager;
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextPaint;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.OverScroller;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LauncherActivity extends Activity {

    private static final int    ICON_DP        = 68;
    private static final int    CELL_W_DP      = 90;
    private static final int    CELL_H_DP      = 100;
    private static final int    RING_STROKE_DP = 3;
    // Clock cadence lives in {@link ClockFormatter#nextMinuteDelay} now.
    // The launcher schedules ticks aligned to the minute boundary so a
    // 1 Hz wakeup loop is avoided; the clock has no seconds, so anything
    // finer would be 59 wakeups per minute of pure waste.
    private static final String PREFS          = "bare_launcher";
    private static final String KEY_WP_URI     = "wp_uri";
    private static final String KEY_SCROLL_IDX = "scroll_idx";
    private static final String KEY_APP_ORDER  = "app_order";
    // Persisted remote-key→app shortcut map. Format: "kc=pkg,kc=pkg,...".
    // Keys are raw Android keycode integers (e.g. 183 = KEYCODE_PROG_RED).
    // Loaded once at startup into the in-memory keyMap SparseArray; every
    // mapped key press anywhere on the home screen launches its app via
    // dispatchKeyEvent. Saved synchronously on every config change so the
    // user never has to confirm.
    private static final String KEY_KEYMAP     = "key_map";
    // Persisted set of packages hidden from the home shelf. Comma-separated
    // package names. The list is "hide from shelf only" — hidden packages
    // are still visible in the keymap picker so they can be bound to a
    // remote-key shortcut. Loaded once at startup into hiddenApps; saved
    // synchronously on every toggle.
    private static final String KEY_HIDDEN     = "hidden_apps";
    private static final int    MATCH          = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int    WRAP           = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int    REQ_PICK_WP    = 42;

    // Subtle focus pop — animations toned down for performance / stability.
    // No vertical lift (saves a frame of layout work and removes a class of
    // visual jitter on slow TV ROMs). Scale is small enough to read as
    // "selected" without dominating the shelf.
    private static final float  FOCUS_SCALE    = 1.06f;
    // Bumped 130 -> 150 ms so the bounce has enough frames to be perceived
    // (at 130 ms the spring barely registered on 60 Hz panels). Still well
    // under the 200 ms threshold where animations start to feel sluggish.
    private static final int    FOCUS_DUR_MS   = 150;
    private static final int    UNFOCUS_DUR_MS = 100;

    // Easing curves. Defined once, reused everywhere — no per-animation alloc.
    //   FOCUS_EASE      — decelerate-out, the canonical "press / lift" curve
    //   FOCUS_IN_BOUNCE — subtle overshoot for focus-IN only. Tension 2.8
    //                     gives a slightly springier "pop" than the previous
    //                     2.0 — the cell ticks ~7% past FOCUS_SCALE before
    //                     settling. Higher tension is a math-only change
    //                     with zero CPU / GPU cost. Peak visible width
    //                     (cellW * 1.073) still fits inside the cell stride,
    //                     so neighbours never overlap during the bounce.
    //   SCROLL_EASE     — Material standard ease-in-out for shelf scrolling
    //   REORDER_EASE    — quick decelerate for the swap slide
    //   MENU_IN         — gentle overshoot-free pop-in for the context menu
    private static final Interpolator FOCUS_EASE      = new DecelerateInterpolator(1.6f);
    private static final Interpolator FOCUS_IN_BOUNCE = new OvershootInterpolator(2.8f);
    private static final Interpolator SCROLL_EASE     = new PathInterpolator(0.33f, 0f, 0.2f, 1f);
    private static final Interpolator REORDER_EASE    = new PathInterpolator(0.25f, 0.1f, 0.2f, 1f);
    private static final Interpolator MENU_IN         = new PathInterpolator(0.18f, 0.7f, 0.25f, 1f);
    private static final Interpolator MENU_OUT        = new PathInterpolator(0.4f, 0f, 0.7f, 0.3f);

    // Static icon-pipeline scratch buffers (sMatrixTL, sPixelBuf, sFillPts)
    // and paints (sMaskPaint, sSrcInPaint, sDrawPaint, sWhiteFill) used to
    // live here. They moved to {@link IconRenderer}, which is the only
    // consumer — pulling them out drops ~25 lines from the activity and
    // gives the icon pipeline a single home.
    //
    // sPhFill stays here because the only caller is {@code CellView.drawIcon}
    // (the placeholder rendered before the real icon bitmap arrives). It is
    // shared across every cell; one paint per process keeps GC pressure
    // zero on the cold-start icon-flood frames.
    private static final Paint sPhFill = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        sPhFill.setStyle(Paint.Style.FILL);
        sPhFill.setColor(0x33FFFFFF);
    }

    private volatile float      density;
    // Volatile: read from background threads (wallpaper executor) inside
    // wpDrawable() and calcSampleSize(). Without volatile, weak-memory-model
    // CPUs could observe stale zeros and produce a 1px-tall wallpaper bitmap.
    private volatile int        screenW, screenH;
    private volatile boolean    destroyed       = false;
    // Wallpaper-loading guards live inside {@link WallpaperController} now.
    // The icon and app-list executors stay in the activity because their
    // hot-path consumers (CellView icon delivery, package broadcast
    // refresh) live here.
    private final AtomicBoolean appsLoading     = new AtomicBoolean(false);

    private PackageManager      pm;

    private RecyclingShelfView shelf;
    // Wallpaper rendering uses two stacked ImageViews. wallpaperFront is on
    // top (always visible to the user); wallpaperBack sits below and is the
    // staging slot used during a fade. Roles do NOT swap — the
    // {@link WallpaperController} that drives both views enforces the
    // "front is always front" invariant. The activity holds these as
    // fields only so {@code onDestroy} can clear the references; all
    // bitmap mutation goes through the controller.
    private ImageView          wallpaperFront;
    private ImageView          wallpaperBack;
    private WallpaperController wallpaperCtl;
    private TextView           clockView;
    private View               netBtn;
    private View               wpBtnView;
    private RingView           ringView;
    private FrameLayout        root;
    private Toast              currentToast;

    // Single Handler on the main looper — was previously two (uiHandler +
    // clockHandler). The clock runnable is the only "named" callback; we
    // use clockTick as the token for removeCallbacks.
    private final Handler uiHandler    = new Handler(Looper.getMainLooper());
    private       boolean clockRunning = false;

    // Clock formatter encapsulates all the per-tick allocation hygiene
    // (Calendar reuse, char[8] digit buffer, SpannableStringBuilder, AM/PM
    // spans). Pulled out of the activity into {@link ClockFormatter} —
    // the activity now only deals with scheduling / TextView wiring.
    private final ClockFormatter clockFmt = new ClockFormatter();

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (destroyed || !clockRunning) return;
            long now = System.currentTimeMillis();
            tickClock(now);
            // Schedule the next tick for the next minute boundary, with a
            // tiny 50 ms cushion so we land just AFTER :00 rather than just
            // before (avoids a tick firing twice in the same minute on a
            // slightly-fast wall-clock). See {@link ClockFormatter#nextMinuteDelay}.
            uiHandler.postDelayed(this, ClockFormatter.nextMinuteDelay(now));
        }
    };

    /** Refresh the clock TextView. Updates the digits on the minute boundary
     *  with no fade animation — simpler and stabler. */
    private void tickClock(long now) {
        TextView cv = clockView;
        if (cv == null) return;
        if (!clockFmt.shouldRepaint(now)) return; // no visible change
        cv.setText(clockFmt.format(now), TextView.BufferType.SPANNABLE);
    }

    private ThreadPoolExecutor       iconExecutor;
    // Wallpaper executor moved into {@link WallpaperController} along with
    // the loading-guard atomic booleans. Only the icon and app-list
    // executors remain here; their hot paths live inside this activity.
    private ExecutorService          appExecutor;
    private LruCache<String, Bitmap> iconCache;

    private final ArrayMap<String, List<RecyclingShelfView.CellView>> iconInflight = new ArrayMap<>();
    private final List<AppInfo> appList = new ArrayList<>();

    private boolean pkgChangedWhilePaused = false;
    private ViewTreeObserver.OnGlobalLayoutListener focusRestoreListener;
    private final int[]    ringCellLoc      = new int[2];
    private final int[]    ringRootLoc      = new int[2];
    private       int      ringLayoutSize   = 0;  // full RingView box size (large enough at 1.12x focus scale)
    private       float    cachedIcyOffset  = 0f;
    private final Runnable pkgReloadRunnable = this::loadApps;

    private FrameLayout        menuOverlay   = null;
    private       TextView    menuUninstall = null;
    private       TextView    menuAppInfo   = null;
    private       TextView    menuMove      = null;
    private final int[]    menuCellLoc      = new int[2];
    private final int[]    menuRootLoc      = new int[2];
    private final int[]    menuOverlayLoc   = new int[2];

    // ── Remote-key → app shortcut state ──────────────────────────────────
    // Curated list of TV-remote keycodes that are safe to remap. Order = row
    // order in the config overlay. The four colour keys cover every TV remote
    // we ship for; KEYCODE_MENU is included even though some remotes also
    // surface it as a launcher-affordance — we trade that affordance for
    // user-controlled remapping (the launcher itself does not consume MENU).
    // KEYCODE_CAPTIONS is the standard subtitle / CC button on Android TV.
    // GUIDE and SEARCH were removed at user request — neither is widely
    // useful on the TV side and removing them keeps the overlay compact.
    private static final int[]    SHORTCUT_KEYCODES = {
            KeyEvent.KEYCODE_PROG_RED,
            KeyEvent.KEYCODE_PROG_GREEN,
            KeyEvent.KEYCODE_PROG_YELLOW,
            KeyEvent.KEYCODE_PROG_BLUE,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_CAPTIONS,
    };
    // Populated in onCreate() from string resources (see initShortcutLabels).
    // Kept as an instance String[] so existing index-based accesses
    // (SHORTCUT_LABELS.length, SHORTCUT_LABELS[i]) keep working without
    // touching every callsite. Length and index order MUST stay locked to
    // SHORTCUT_KEYCODES — they're parallel arrays.
    private final String[] SHORTCUT_LABELS = new String[SHORTCUT_KEYCODES.length];
    // Color tag drawn next to each row label. ARGB. 0 = no tag (Menu/Subtitle).
    private static final int[]    SHORTCUT_TAGS     = {
            0xFFE5484D, 0xFF30A46C, 0xFFF5C518, 0xFF3E63DD, 0, 0,
    };
    // Keyed by raw keycode → package name. SparseArray fits the small,
    // dense-int-key access pattern with zero autoboxing on every key press.
    private final SparseArray<String> keyMap            = new SparseArray<>();
    private FrameLayout               keymapOverlay     = null;
    // The animated card lives inside keymapOverlay; we hold a separate
    // reference because the dropdown animation (scale + fade + Y-translate)
    // is applied to the card, not to the full-screen backdrop overlay.
    private android.widget.LinearLayout keymapCard      = null;
    private android.widget.LinearLayout keymapColumn    = null;
    private int                       keymapSelectedRow = 0;

    // ── Keymap overlay app-picker state ──────────────────────────────────
    // The overlay has two visual modes that swap visibility inside the card:
    //   • SLOTS  — vertical list of remappable keys (default)
    //   • PICKER — horizontal scrollable list of installed apps for the
    //              slot the user just opened
    // Mode-switching is driven by handleKeymapOverlayKey: OK on a slot
    // enters PICKER, OK on an app commits the binding and returns to SLOTS,
    // BACK in PICKER cancels back to SLOTS, BACK in SLOTS closes the overlay.
    private static final int            KEYMAP_MODE_SLOTS  = 0;
    private static final int            KEYMAP_MODE_PICKER = 1;
    // Hide-manager mode: the scale-up card swaps from the slot list to a
    // vertical, OK-toggleable list of every installed app. UP/DOWN navigate,
    // OK toggles the hidden flag, BACK returns to slot mode.
    private static final int            KEYMAP_MODE_HIDE   = 2;
    private int                         keymapMode          = KEYMAP_MODE_SLOTS;
    private android.widget.LinearLayout keymapPickerView    = null;  // vertical wrapper for picker
    private TextView                    keymapPickerTitle   = null;  // "Pick app for Red"
    private android.widget.HorizontalScrollView keymapPickerHsv = null;
    private android.widget.LinearLayout keymapPickerStrip   = null;  // horizontal app chips
    private int                         keymapPickerIdx     = 0;     // 0 = "None" sentinel, 1..N = appList[i-1]
    private int                         keymapPickerLastIdx = -1;    // tracks last-painted selection so refresh only animates the two chips that changed
    private int                         keymapPickerSlotRow = 0;     // which slot row triggered the picker
    // Picker chip strip is rebuilt only when the underlying app list
    // changes — avoids re-allocating ~N TextViews on every overlay open.
    private int                         keymapPickerBuiltSize = -1;

    // ── Hide-manager state ───────────────────────────────────────────────
    // Set of packages the user has hidden from the home shelf. Read on
    // every loadApps() to filter the shelf list; written on every toggle
    // inside the hide-manager mode. Iteration order doesn't matter — we
    // never list this set directly; we only do contains() checks.
    private final ArraySet<String>      hiddenApps        = new ArraySet<>();
    // Manage-hidden-apps slot row: a 7th, visually-offset row at the bottom
    // of the slot column that transitions the card to HIDE mode. Held as a
    // field so refreshKeymapRows can paint its selection state without
    // hunting through child indices (key rows occupy 0..5, divider 6,
    // manage row 7).
    private android.widget.LinearLayout keymapManageRow   = null;
    // Hide-manager sub-view (third child of the card, sibling of the slot
    // list and picker — visibility is swapped between the three). The hide
    // manager intentionally mirrors the keymap PICKER's UX exactly: a
    // horizontal chip strip with the same selection language (bright pill
    // + dark text + 1.05x scale + auto-scroll). The only delta is that
    // hidden chips render their label with a strike-through, so the user
    // can read the hidden flag at a glance in either selected or idle
    // state without breaking the picker's visual vocabulary.
    private android.widget.LinearLayout keymapHideView    = null;
    private TextView                    keymapHideTitle   = null;
    private android.widget.HorizontalScrollView keymapHideHsv = null;
    private android.widget.LinearLayout keymapHideStrip   = null;  // horizontal chip strip
    private int                         keymapHideIdx     = 0;
    private int                         keymapHideLastIdx = -1;
    // Built-row count cached so we only rebuild the toggle rows when the
    // app list size actually changes between opens.
    private int                         keymapHideBuiltSize = -1;
    // Set true whenever a slot row's text content (binding/label) might
    // have changed. equalizeKeymapRowWidths is expensive (7 view measures)
    // and was previously called on every UP/DOWN press — a no-op since
    // text didn't change between presses. With this flag we only re-
    // measure on the first refresh after the overlay opens, after a
    // commit from the picker, or after a package broadcast invalidates
    // the chip caches.
    private boolean keymapRowsNeedEqualize = true;
    // Set true on every toggle; on overlay close we re-apply the filtered
    // list to the shelf only if anything actually changed during the
    // session (avoids a full shelf rebuild for read-only opens).
    private boolean                     keymapHideDirty   = false;

    // Third toolbar icon (next to wifi + wallpaper) that opens the keymap
    // overlay. Held as a field so focus-chain handlers and onDestroy can
    // reach it.
    private View                        mapperBtnView     = null;

    /** Hides the selection ring whenever focus moves OUT of any shelf cell.
     *  Single source of truth for "ring should not be visible right now".
     *
     *  Transient-null tolerance: during a cyclic-wrap navigation the
     *  previously-focused cell is recycled (setVisibility(GONE)), which
     *  synchronously clears focus to null and re-fires this listener with
     *  newFocus == null. Hiding the ring on that intermediate event leaves
     *  a one-frame INVISIBLE window before the destination cell's focus
     *  event repositions and re-shows it — visible to the user as a "ring
     *  flickers off when wrapping at the end" glitch. We now skip the null
     *  transition entirely; the next real focus event (either back to a
     *  CellView, or out to a toolbar button) makes the correct decision. */
    private final ViewTreeObserver.OnGlobalFocusChangeListener globalFocusListener =
            (oldFocus, newFocus) -> {
                if (destroyed) return;
                if (newFocus == null) return; // transient — let the next focus event decide
                if (!(newFocus instanceof RecyclingShelfView.CellView)) {
                    RingView rv = ringView;
                    if (rv != null) rv.setVisibility(View.INVISIBLE);
                }
            };

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
            // Invalidate cached picker chip strip — its identities can no
            // longer be trusted to match the in-memory appList after a
            // package install / remove / replace. Same applies to the
            // hide-manager toggle rows. Slot rows must be re-measured
            // because a relabelled package can change the equalised width.
            keymapPickerBuiltSize = -1;
            keymapHideBuiltSize   = -1;
            keymapRowsNeedEqualize = true;
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
        Map<String, Integer> rank = AppOrder.parse(raw);
        if (rank.isEmpty()) return;
        Collections.sort(apps, (a, b) -> {
            Integer ra = rank.get(a.packageName), rb = rank.get(b.packageName);
            if (ra != null && rb != null) return ra - rb;
            if (ra != null) return -1;
            if (rb != null) return  1;
            // Use the JDK's locale-independent case-insensitive comparator.
            // String.compareToIgnoreCase uses the default Locale and famously
            // mis-orders Turkish "I"/"i" vs ASCII letters — we don't want that
            // for an app launcher whose order should be deterministic across
            // locales.
            return String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
        });
    }

    private void saveOrder() {
        if (appList.isEmpty()) return;
        ArrayList<String> pkgs = new ArrayList<>(appList.size());
        for (int i = 0; i < appList.size(); i++) pkgs.add(appList.get(i).packageName);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_APP_ORDER, AppOrder.serialize(pkgs)).apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Install the zero-dependency crash sink BEFORE we do anything
        // else that could fault. From this point on any uncaught throwable
        // on any thread gets timestamped and appended to
        // <internalFiles>/crash.log so a remote user can pull a real
        // trace (no Crashlytics / Sentry / etc. — see CrashLogger javadoc).
        CrashLogger.install(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density; screenW = dm.widthPixels; screenH = dm.heightPixels;
        pm = getPackageManager();
        initShortcutLabels();
        initCaches();
        setContentView(buildLayout());
        hideSystemUI();
        loadWallpaper();
        loadApps();
        loadKeyMap();
        loadHiddenApps();
        registerPkgReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                        RecyclingShelfView s = shelf;
                        if (s != null && s.reorderMode) s.exitReorderMode(false);
                    });
        }
    }

    /** Pending saved scroll index — applied as soon as appList is populated.
     *  Kept as field so the cold-start path (where appList is empty in onResume)
     *  doesn't silently drop the user's last-focused position. */
    private int pendingScrollIdx = -1;

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        startClock();
        if (pkgChangedWhilePaused) { pkgChangedWhilePaused = false; loadApps(); }
        RecyclingShelfView s = shelf;
        if (s != null) {
            int saved = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_SCROLL_IDX, 0);
            if (!appList.isEmpty()) {
                // Clamp against the shelf's currently-displayed size so a
                // saved index that points past the end of the filtered
                // (hide-apps) view doesn't get misread as a wrap target by
                // requestFocusOnIndex during the upcoming restore.
                s.focusedIndex = Math.min(saved, s.lastIndex());
            } else {
                // Cold start: apps haven't loaded yet. Stash the index;
                // loadApps's UI callback will apply it once the shelf is populated.
                pendingScrollIdx = saved;
            }
            // Dedupe — onResume can fire twice without an intervening onPause
            // during fast configuration transitions on some TV ROMs.
            if (focusRestoreListener != null) {
                ViewTreeObserver vto0 = s.getViewTreeObserver();
                if (vto0.isAlive()) vto0.removeOnGlobalLayoutListener(focusRestoreListener);
                focusRestoreListener = null;
            }
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
        FrameLayout r = root;
        if (r != null) {
            ViewTreeObserver rvto = r.getViewTreeObserver();
            if (rvto.isAlive()) rvto.addOnGlobalFocusChangeListener(globalFocusListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopClock();
        FrameLayout r = root;
        if (r != null) {
            ViewTreeObserver rvto = r.getViewTreeObserver();
            if (rvto.isAlive()) rvto.removeOnGlobalFocusChangeListener(globalFocusListener);
        }
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
        uiHandler.removeCallbacksAndMessages(null);
        unregisterPkgReceiver();
        shutdown(iconExecutor); shutdown(appExecutor);
        if (iconCache != null) iconCache.evictAll();
        iconInflight.clear();
        // Wallpaper teardown — controller recycles its own bitmaps and
        // clears the ImageView drawables. Keeps the activity from having
        // to know about wallpaper memory hygiene at all.
        if (wallpaperCtl != null) { wallpaperCtl.onDestroy(); wallpaperCtl = null; }
        wallpaperFront = null; wallpaperBack = null; clockView = null; shelf = null;
        wpBtnView = null; netBtn = null; ringView = null; root = null;
        mapperBtnView = null;
        menuOverlay = null; menuUninstall = null; menuAppInfo = null; menuMove = null;
        keymapOverlay = null; keymapColumn = null; keymapCard = null;
        keymapPickerView = null; keymapPickerTitle = null;
        keymapPickerHsv = null; keymapPickerStrip = null;
        keymapManageRow = null;
        keymapHideView = null; keymapHideTitle = null;
        keymapHideHsv  = null; keymapHideStrip = null;
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
                sv.setApps(Collections.emptyList());
                for (int i = 0; i < sv.pool.size(); i++) sv.pool.get(i).iconBitmap = null;
            }
            appList.clear();
            uiHandler.postDelayed(this::loadApps, 1000);
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
                int[] loc = menuOverlayLoc;
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
        // No-op for HOME launcher: back from the home screen should stay home.
        // Calling super would let the platform finish() the activity which on
        // some TV ROMs flashes the system home picker.
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Refresh display metrics. The activity declares configChanges so it
        // survives DPI/screen-size changes (HDMI swap on TV, system font scale
        // change, multi-window enter on tablet). Without this refresh, dp(...)
        // and the background wallpaper sizing would silently keep stale values.
        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density;
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        // Forward the new screen size into the wallpaper controller so its
        // next decode caps to the new dimensions (e.g. HDMI swap on TV
        // changes both screenW and screenH).
        if (wallpaperCtl != null) wallpaperCtl.onConfigurationChanged(screenW, screenH);
        TextView cv = clockView;
        if (cv != null) {
            // Force-refresh: ClockFormatter's "last shown minute" sentinel
            // is reset so tickClock paints unconditionally (the per-minute
            // idempotency guard would otherwise skip the redraw).
            clockFmt.reset();
            cv.setAlpha(1f);
            tickClock(System.currentTimeMillis());
        }
    }

    private View buildLayout() {
        root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);

        wallpaperBack = new ImageView(this);
        wallpaperBack.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperBack.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperBack.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        wallpaperBack.setAlpha(0f);
        // Stack order: BACK is added first (drawn below), FRONT on top. We
        // cross-fade by raising BACK's alpha to 1 then swapping references
        // so the new wallpaper becomes the FRONT for the next change.
        root.addView(wallpaperBack);

        wallpaperFront = new ImageView(this);
        wallpaperFront.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperFront.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperFront.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(wallpaperFront);

        // Stand up the wallpaper subsystem now that both ImageViews are
        // attached. {@link WallpaperController} owns its own background
        // executor and loading-guard atomic flags; the activity only calls
        // its small lifecycle / interaction surface.
        wallpaperCtl = new WallpaperController(
                this,
                getSharedPreferences(PREFS, MODE_PRIVATE),
                KEY_WP_URI,
                wallpaperFront, wallpaperBack,
                screenW, screenH,
                FOCUS_EASE,
                this::showToast);

        shelf = new RecyclingShelfView(this);
        FrameLayout.LayoutParams shelfLp = new FrameLayout.LayoutParams(MATCH, dp(CELL_H_DP));
        shelfLp.gravity = Gravity.BOTTOM;
        shelfLp.setMargins(0, 0, 0, dp(12));
        shelf.setLayoutParams(shelfLp);
        shelf.setContentDescription(getString(R.string.cd_app_shelf));
        shelf.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.addView(shelf);

        clockView = new TextView(this);
        clockView.setShadowLayer(dp(14), 0, dp(3), 0xCC000000);
        clockView.setPadding(dp(22), dp(11), dp(22), dp(11));
        clockView.setIncludeFontPadding(false);
        clockView.setContentDescription(getString(R.string.cd_clock));
        clockView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams clkLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        clkLp.gravity = Gravity.TOP | Gravity.START;
        clkLp.setMarginStart(dp(32));
        clkLp.topMargin = dp(24);
        clockView.setLayoutParams(clkLp);
        clockView.setTextColor(Color.WHITE);
        clockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 44);
        // Heavy base typeface — time digits read thick. AM/PM is overridden
        // back to sans-serif-thin via a TypefaceSpan in {@link ClockFormatter}.
        clockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        clockView.setLetterSpacing(0.02f);
        root.addView(clockView);

        final int BTN_SZ  = dp(36);
        final int BTN_VIEW_SZ = dp(52);  // larger to accommodate glow without clipping
        final int BTN_GAP = dp(6);
        final int MARG_T  = dp(18);
        final int MARG_E  = dp(20);

        // Top-right toolbar buttons. Layout left-to-right is:
        //   [mapper] [wifi] [wallpaper]
        // Margins are computed from the right edge — wallpaper sits flush
        // against MARG_E, each preceding button is one (BTN_VIEW_SZ + BTN_GAP)
        // step further left. mapperBtn was added as a third icon so the
        // remote-key remapping config gets a dedicated, discoverable entry
        // (no long-press gesture, no nested menu).
        netBtn = buildNetBtn(BTN_SZ);
        FrameLayout.LayoutParams netLp = new FrameLayout.LayoutParams(BTN_VIEW_SZ, BTN_VIEW_SZ);
        netLp.gravity = Gravity.TOP | Gravity.END;
        netLp.topMargin = MARG_T;
        netLp.setMarginEnd(MARG_E + BTN_VIEW_SZ + BTN_GAP);
        netBtn.setLayoutParams(netLp);
        netBtn.setClipBounds(null);
        netBtn.setContentDescription(getString(R.string.cd_network_settings));
        root.addView(netBtn);

        View mpLocal = buildMapperBtn(BTN_SZ);
        mapperBtnView = mpLocal;
        FrameLayout.LayoutParams mpLp = new FrameLayout.LayoutParams(BTN_VIEW_SZ, BTN_VIEW_SZ);
        mpLp.gravity = Gravity.TOP | Gravity.END;
        mpLp.topMargin = MARG_T;
        // Leftmost of the three: 2 stride steps from the right edge.
        mpLp.setMarginEnd(MARG_E + 2 * (BTN_VIEW_SZ + BTN_GAP));
        mpLocal.setLayoutParams(mpLp);
        mpLocal.setContentDescription(getString(R.string.cd_remap_remote));
        root.addView(mpLocal);

        View wpLocal = buildWpBtn(BTN_SZ);
        wpBtnView = wpLocal;
        FrameLayout.LayoutParams wpLp = new FrameLayout.LayoutParams(BTN_VIEW_SZ, BTN_VIEW_SZ);
        wpLp.gravity = Gravity.TOP | Gravity.END;
        wpLp.topMargin = MARG_T;
        wpLp.setMarginEnd(MARG_E);
        wpLocal.setLayoutParams(wpLp);
        wpLocal.setContentDescription(getString(R.string.cd_change_wallpaper));
        root.addView(wpLocal);

        int iconPx = dp(ICON_DP), strokePx = dp(RING_STROKE_DP);
        // Ring view diameter = icon + headroom for the focus scale-up.
        // Ring sits with ZERO gap on the icon edge now, so headroom is just
        // enough to fit the scaled-up ring (icon + stroke) at focus scale.
        int ringSize = iconPx + dp(12);
        ringLayoutSize  = ringSize;
        cachedIcyOffset = iconPx / 2f;  // icon centred in cell, no extra offset
        ringView = new RingView(this, strokePx, iconPx);
        FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(ringSize, ringSize);
        ringView.setLayoutParams(ringLp);
        ringView.setVisibility(View.INVISIBLE);
        ringView.setContentDescription(getString(R.string.cd_selection_ring));
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
        // Apple-TV plate matches the keymap card exactly: deep slate with a
        // hairline rim. Dropping the previous opaque-black plate gives the
        // app context menu the same visual vocabulary as the rest of the UI.
        android.graphics.drawable.GradientDrawable menuBg =
                new android.graphics.drawable.GradientDrawable();
        menuBg.setColor(0xF21A1A1F);
        menuBg.setCornerRadius(dp(12));
        menuBg.setStroke(1, 0x1AFFFFFF);
        menuCol.setBackground(menuBg);
        // Small inner padding so each rounded item-pill is inset from the
        // card edge — otherwise a square selection would visually clash
        // with the card's rounded outer corner.
        menuCol.setPadding(dp(4), dp(4), dp(4), dp(4));
        menuCol.setElevation(dp(8));

        // Each menu item gets its OWN GradientDrawable as background so the
        // selected highlight is a rounded pill (not a flat rectangle, which
        // is what the previous setBackgroundColor call produced — and what
        // looked clipped against the card's rounded outer corner).
        // updateMenuHighlight just mutates the colour on these existing
        // drawables; the rounded shape is fixed at construction time.
        final int itemRadius = dp(8);

        menuUninstall = new TextView(this);
        menuUninstall.setText(R.string.menu_uninstall);
        menuUninstall.setTextColor(0xFFFF6B6B);
        menuUninstall.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        menuUninstall.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        menuUninstall.setGravity(Gravity.CENTER);
        menuUninstall.setPadding(dp(20), dp(11), dp(20), dp(11));
        menuUninstall.setClickable(true);
        menuUninstall.setFocusable(false);
        menuUninstall.setContentDescription(getString(R.string.cd_uninstall_app));
        android.graphics.drawable.GradientDrawable uBg =
                new android.graphics.drawable.GradientDrawable();
        uBg.setCornerRadius(itemRadius);
        uBg.setColor(Color.TRANSPARENT);
        menuUninstall.setBackground(uBg);
        menuUninstall.setOnClickListener(v -> {
            RecyclingShelfView s = shelf;
            if (s != null && s.reorderMode) {
                s.menuSelection = RecyclingShelfView.MENU_UNINSTALL;
                RecyclingShelfView.CellView cv = s.attached.get(s.dragIndex);
                if (cv != null) cv.triggerUninstall();
                else s.exitReorderMode(false);
            }
        });

        menuAppInfo = new TextView(this);
        menuAppInfo.setText(R.string.menu_app_info);
        menuAppInfo.setTextColor(Color.WHITE);
        menuAppInfo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        menuAppInfo.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        menuAppInfo.setGravity(Gravity.CENTER);
        menuAppInfo.setPadding(dp(20), dp(11), dp(20), dp(11));
        menuAppInfo.setClickable(true);
        menuAppInfo.setFocusable(false);
        menuAppInfo.setContentDescription(getString(R.string.cd_open_app_info));
        android.graphics.drawable.GradientDrawable iBg =
                new android.graphics.drawable.GradientDrawable();
        iBg.setCornerRadius(itemRadius);
        iBg.setColor(Color.TRANSPARENT);
        menuAppInfo.setBackground(iBg);
        menuAppInfo.setOnClickListener(v -> {
            RecyclingShelfView s = shelf;
            if (s != null && s.reorderMode) {
                s.menuSelection = RecyclingShelfView.MENU_APP_INFO;
                RecyclingShelfView.CellView cv = s.attached.get(s.dragIndex);
                if (cv != null) cv.triggerAppInfo();
                else s.exitReorderMode(false);
            }
        });

        menuMove = new TextView(this);
        menuMove.setText(R.string.menu_move);
        menuMove.setTextColor(Color.WHITE);
        menuMove.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        menuMove.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        menuMove.setGravity(Gravity.CENTER);
        menuMove.setPadding(dp(20), dp(11), dp(20), dp(11));
        menuMove.setClickable(true);
        menuMove.setFocusable(false);
        menuMove.setContentDescription(getString(R.string.cd_move_app_position));
        android.graphics.drawable.GradientDrawable mBg =
                new android.graphics.drawable.GradientDrawable();
        mBg.setCornerRadius(itemRadius);
        mBg.setColor(Color.TRANSPARENT);
        menuMove.setBackground(mBg);
        menuMove.setOnClickListener(v -> {
            RecyclingShelfView s = shelf;
            if (s != null && s.reorderMode) {
                s.menuSelection = RecyclingShelfView.MENU_MOVE;
                updateMenuHighlight();
                // "Move" confirm: exit reorder saving order
                s.exitReorderMode(true);
            }
        });

        // Dividers removed — the rounded-pill selection state is enough to
        // separate items visually, and removing them gives a cleaner
        // Apple-TV-style menu with no horizontal noise.
        android.widget.LinearLayout.LayoutParams itemLp =
                new android.widget.LinearLayout.LayoutParams(dp(140), WRAP);
        itemLp.bottomMargin = dp(2);
        menuCol.addView(menuUninstall, itemLp);
        android.widget.LinearLayout.LayoutParams itemLp2 =
                new android.widget.LinearLayout.LayoutParams(dp(140), WRAP);
        itemLp2.bottomMargin = dp(2);
        menuCol.addView(menuAppInfo,   itemLp2);
        menuCol.addView(menuMove,      new android.widget.LinearLayout.LayoutParams(dp(140), WRAP));

        menuOverlay.addView(menuCol, new FrameLayout.LayoutParams(WRAP, WRAP));
        root.addView(menuOverlay);

        return root;
    }

    void showContextMenu(View cell) {
        if (menuOverlay == null || menuUninstall == null || menuAppInfo == null || menuMove == null) return;
        cell.getLocationOnScreen(menuCellLoc);
        FrameLayout r = root; if (r == null) return;
        r.getLocationOnScreen(menuRootLoc);
        // Anchor to the cell's LAYOUT centre (where it ends up after the
        // reorder-swap slide), not its current visual centre. The slide sets
        // translationX = slidePx and animates back to 0 — getLocationOnScreen
        // returns the post-translation visual position. If we anchored there,
        // the menu would stick at the OLD cell position while the cell slides
        // into the NEW one, ending up offset to one side of the moving icon.
        // By subtracting translationX we anchor to the destination so the
        // cell glides INTO the menu and they remain aligned.
        // Scale is also applied around the centre pivot, so the layout centre
        // = visual_top_left + width * scaleX / 2 (positionRing uses the same
        // formula for the focus halo).
        float sx = cell.getScaleX();
        float tx = cell.getTranslationX();
        float ty = cell.getTranslationY();
        int cellCx    = (menuCellLoc[0] - menuRootLoc[0])
                      + Math.round(cell.getWidth() * sx / 2f)
                      - Math.round(tx);
        int cellRelY  = (menuCellLoc[1] - menuRootLoc[1])
                      - Math.round(ty);   // cell visual top in root coords, un-translated

        int rW = r.getWidth()  > 0 ? r.getWidth()  : screenW;
        int rH = r.getHeight() > 0 ? r.getHeight() : screenH;
        menuOverlay.measure(
                View.MeasureSpec.makeMeasureSpec(rW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(rH, View.MeasureSpec.AT_MOST));
        int mw = menuOverlay.getMeasuredWidth();
        int mh = menuOverlay.getMeasuredHeight();

        int iconPx        = dp(ICON_DP);
        int iconTopInRoot = cellRelY + (int)(cachedIcyOffset - iconPx / 2f);
        int iconBotInRoot = iconTopInRoot + iconPx;

        // Prefer above the icon; fall back to below if it would clip the top
        int menuY = iconTopInRoot - dp(6) - mh;
        boolean menuAbove = true;
        if (menuY < dp(8)) {
            menuY = iconBotInRoot + dp(6);
            menuAbove = false;
        }
        // Clamp so it never escapes the bottom either
        menuY = Math.min(menuY, r.getHeight() - mh - dp(8));
        menuY = Math.max(menuY, dp(8));

        int menuX = cellCx - mw / 2;
        menuX = Math.max(dp(8), Math.min(menuX, r.getWidth() - mw - dp(8)));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuOverlay.getLayoutParams();
        lp.leftMargin = menuX; lp.topMargin = menuY;
        lp.gravity = Gravity.TOP | Gravity.START;
        menuOverlay.setLayoutParams(lp);

        // Pivot: anchor the scale-up at the edge facing the icon so the menu
        // appears to "pop out" of the focused cell rather than inflate from
        // its own centre. Pivot X tracks the icon's horizontal centre relative
        // to the overlay so off-centre menus (clamped to screen edge) still
        // grow toward the right place.
        float pivotX = (cellCx - menuX);
        pivotX = Math.max(0f, Math.min(pivotX, mw));
        menuOverlay.setPivotX(pivotX);
        menuOverlay.setPivotY(menuAbove ? mh : 0f);

        boolean wasVisible = menuOverlay.getVisibility() == View.VISIBLE;
        menuOverlay.animate().cancel();
        menuOverlay.setVisibility(View.VISIBLE);
        if (!wasVisible) {
            menuOverlay.setAlpha(0f);
            menuOverlay.setScaleX(0.9f);
            menuOverlay.setScaleY(0.9f);
            menuOverlay.animate()
                    .alpha(1f)
                    .scaleX(1f).scaleY(1f)
                    .setDuration(130)
                    .setInterpolator(MENU_IN)
                    .start();
        } else {
            // Already visible (e.g. re-anchored after a reorder swap) — just
            // make sure the transform is at rest.
            menuOverlay.setAlpha(1f);
            menuOverlay.setScaleX(1f);
            menuOverlay.setScaleY(1f);
        }
        updateMenuHighlight();
    }

    void hideContextMenu() {
        if (menuOverlay == null) return;
        if (menuOverlay.getVisibility() != View.VISIBLE) return;
        final FrameLayout fm = menuOverlay;
        fm.animate().cancel();
        fm.animate()
                .alpha(0f)
                .scaleX(0.9f).scaleY(0.9f)
                .setDuration(90)
                .setInterpolator(MENU_OUT)
                .withEndAction(() -> {
                    if (fm != menuOverlay) return;
                    // Guard against the cancellation race: showContextMenu
                    // cancels the in-flight fade-out, which on some Android
                    // versions still runs withEndAction. Only commit GONE if
                    // the fade actually reached its near-zero target.
                    if (fm.getAlpha() > 0.05f) return;
                    fm.setVisibility(View.GONE);
                    // Reset transform so the next show() starts from a known state.
                    fm.setAlpha(1f);
                    fm.setScaleX(1f);
                    fm.setScaleY(1f);
                })
                .start();
    }

    void updateMenuHighlight() {
        RecyclingShelfView s = shelf; if (s == null) return;
        if (menuUninstall == null || menuAppInfo == null || menuMove == null) return;
        int sel = s.menuSelection;
        // Bright frosted-white pill for the selected item, mirroring the
        // toolbar buttons & keymap rows. The selected item's text inverts
        // to dark for contrast; non-destructive items invert to near-black,
        // Uninstall keeps its red identity but darkens to read on white.
        final int hlWhite = 0xFFEFEFEF;
        // Each item's background was constructed as a GradientDrawable with
        // a fixed corner radius (see buildLayout) — we mutate the colour on
        // those existing drawables so the rounded shape never changes.
        setMenuItemBg(menuUninstall, sel == RecyclingShelfView.MENU_UNINSTALL ? hlWhite : Color.TRANSPARENT);
        setMenuItemBg(menuAppInfo,   sel == RecyclingShelfView.MENU_APP_INFO  ? hlWhite : Color.TRANSPARENT);
        setMenuItemBg(menuMove,      sel == RecyclingShelfView.MENU_MOVE      ? hlWhite : Color.TRANSPARENT);
        menuUninstall.setTextColor(sel == RecyclingShelfView.MENU_UNINSTALL ? 0xFFC0202A : 0xCCFF6B6B);
        menuAppInfo  .setTextColor(sel == RecyclingShelfView.MENU_APP_INFO  ? 0xFF111114 : 0xCCFFFFFF);
        menuMove     .setTextColor(sel == RecyclingShelfView.MENU_MOVE      ? 0xFF111114 : 0xCCFFFFFF);
    }

    /** Updates the colour of an item's existing rounded GradientDrawable
     *  background WITHOUT replacing it (which is what setBackgroundColor
     *  would do, losing the corner radius). Falls back to a fresh rounded
     *  drawable if the item somehow lost its background. */
    private void setMenuItemBg(TextView tv, int color) {
        android.graphics.drawable.Drawable d = tv.getBackground();
        if (d instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) d).setColor(color);
            return;
        }
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(color);
        tv.setBackground(g);
    }

    private View buildNetBtn(int sz) {
        View v = new View(this) {
            // Pure shortcut button: opens WiFi settings. No status indicator —
            // the system already surfaces connectivity in its own UI; mirroring
            // it here creates two sources of truth that can disagree.
            //
            // Glyph: bold "3 thick bar" WiFi fan matching the reference image.
            // Three concentric arcs with ROUND caps so the band ends read as
            // smooth pill tips rather than chiselled edges. A small solid
            // wedge sits at the apex as the fan's source.
            //
            // Colour rule unchanged: dark-glass plate idle, frosted-white plate
            // on focus, glyph inverts (white→dark) so the symbol always reads.
            private final Paint stroke    = makeBtnStrokePaint();   // ROUND caps
            private final Paint dot       = makeBtnPaint(true);
            private final Paint bgIdle    = makeBgIdlePaint();
            private final Paint bgFocus   = makeBgFocusPaint();
            private final Paint rim       = makeRimPaint();
            private final RectF oval      = new RectF();
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                boolean focused = isFocused();
                float scale = focused ? 1f : 0.86f;
                float cx = w / 2f, cy = h / 2f;
                float r = Math.min(cx, cy) * scale;
                // Background plate — frosted white when focused, dark glass when idle
                c.drawCircle(cx, cy, r, focused ? bgFocus : bgIdle);
                // Subtle 1dp inner rim — gives the glass plate a defined edge
                c.drawCircle(cx, cy, r - rim.getStrokeWidth() / 2f, rim);

                int symbolColor = focused ? 0xFF0F0F12 : 0xFFFFFFFF;
                stroke.setColor(symbolColor);
                dot.setColor(symbolColor);

                // Geometry tuned to match the reference image:
                //   ic    icon "container" radius
                //   sw    band thickness — 22% of ic gives a chunky bar feel
                //         without crowding the arcs together
                //   ay    arc anchor — sits below cell centre so the fan
                //         radiates upward from a low source point
                //   radii 0.40 / 0.66 / 0.92 — slightly wider outer ring than
                //         before so the largest bar reads as a confident band
                //   sweep 110° centred on top (215°→325°) — symmetric around
                //         270° (straight up) so the fan reads as upright,
                //         not tilted to one side
                //   apex  small solid wedge (filled circle) anchored just above
                //         the inner-most band's endpoints
                float ic         = r * 0.96f;
                float sw         = ic * 0.22f;
                float ay         = cy + ic * 0.36f;
                float dotR       = sw * 0.55f;
                float dotY       = ay - sw * 0.05f;
                float startAngle = 215f, sweep = 110f;

                stroke.setStrokeWidth(sw);
                stroke.setStrokeJoin(Paint.Join.ROUND);
                stroke.setStrokeCap(Paint.Cap.ROUND);   // ROUND caps for the soft pill-tip look

                c.drawCircle(cx, dotY, dotR, dot);
                float[] radii = { ic * 0.40f, ic * 0.66f, ic * 0.92f };
                for (float rr : radii) {
                    oval.set(cx - rr, ay - rr, cx + rr, ay + rr);
                    c.drawArc(oval, startAngle, sweep, false, stroke);
                }
            }
        };
        applyApplePillStyle(v);
        v.setOnClickListener(view -> openNetSettings());
        v.setOnFocusChangeListener((view, f) -> {
            view.animate().cancel();
            view.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f)
                    .setDuration(100).setInterpolator(FOCUS_EASE).start();
            view.invalidate();
        });
        v.setOnKeyListener((view, kc, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    view.playSoundEffect(SoundEffectConstants.CLICK);
                    view.performClick(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    RecyclingShelfView sd = shelf; if (sd != null) sd.requestFocusOnIndex(0); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // Wifi is the middle button: LEFT focuses the mapper
                    // (leftmost) when present, else falls through to the
                    // last shelf cell to preserve pre-mapper behaviour.
                    View mb = mapperBtnView;
                    if (mb != null) { mb.requestFocus(); return true; }
                    RecyclingShelfView sl = shelf;
                    if (sl != null) sl.requestFocusOnIndex(sl.lastIndex());
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
            // Same Apple-TV glass aesthetic as the WiFi button. Glyph is a
            // landscape (sun + mountain) drawn as crisp white strokes that
            // invert to dark on focus for the frosted-plate effect.
            private final Paint stroke    = makeBtnStrokePaint();
            private final Paint bgIdle    = makeBgIdlePaint();
            private final Paint bgFocus   = makeBgFocusPaint();
            private final Paint rim       = makeRimPaint();
            private final android.graphics.Path mt  = new android.graphics.Path();
            private int   lw = 0, lh = 0;
            private float ls = -1f;
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                boolean focused = isFocused();
                float scale = focused ? 1f : 0.86f;
                float cx = w / 2f, cy = h / 2f;
                float r = Math.min(cx, cy) * scale;
                c.drawCircle(cx, cy, r, focused ? bgFocus : bgIdle);
                c.drawCircle(cx, cy, r - rim.getStrokeWidth() / 2f, rim);

                int symbolColor = focused ? 0xFF0F0F12 : 0xFFFFFFFF;
                stroke.setColor(symbolColor);
                float s = r * 0.92f;
                stroke.setStrokeWidth(s * 0.13f);
                if (w != lw || h != lh || scale != ls) {
                    lw = w; lh = h; ls = scale;
                    float l = cx - s/2f, rt = cx + s/2f, t = cy - s/2f, b = cy + s/2f;
                    mt.rewind();
                    mt.moveTo(l, b); mt.lineTo(l + s*0.38f, t + s*0.48f);
                    mt.lineTo(l + s*0.62f, t + s*0.66f); mt.lineTo(rt, b);
                }
                // Landscape icon: outer frame, sun dot, mountain path — full bright.
                c.drawCircle(cx, cy, s * 0.46f, stroke);
                c.drawCircle(cx + s*0.17f, cy - s*0.18f, s*0.10f, stroke);
                c.drawPath(mt, stroke);
            }
        };
        applyApplePillStyle(v);
        v.setOnClickListener(view -> openStoragePicker());
        v.setOnFocusChangeListener((view, f) -> {
            view.animate().cancel();
            view.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f)
                    .setDuration(100).setInterpolator(FOCUS_EASE).start();
            view.invalidate();
        });
        v.setOnKeyListener((view, kc, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    view.playSoundEffect(SoundEffectConstants.CLICK);
                    view.performClick(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    RecyclingShelfView s = shelf;
                    if (s != null) s.requestFocusOnIndex(s.lastIndex());
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

    /** Third toolbar pill — opens the remote-key remap overlay.
     *  Matches the netBtn / wpBtn glass aesthetic exactly: dark idle plate,
     *  frosted-white focused plate, glyph inverts on focus. The icon is a
     *  3-bar "sliders" / settings glyph (universal "configure" symbol).
     *  Drawn entirely with Canvas primitives — zero new resources. */
    private View buildMapperBtn(int sz) {
        View v = new View(this) {
            private final Paint stroke    = makeBtnStrokePaint();
            private final Paint dot       = makeBtnPaint(true);
            private final Paint bgIdle    = makeBgIdlePaint();
            private final Paint bgFocus   = makeBgFocusPaint();
            private final Paint rim       = makeRimPaint();
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                boolean focused = isFocused();
                float scale = focused ? 1f : 0.86f;
                float cx = w / 2f, cy = h / 2f;
                float r = Math.min(cx, cy) * scale;
                c.drawCircle(cx, cy, r, focused ? bgFocus : bgIdle);
                c.drawCircle(cx, cy, r - rim.getStrokeWidth() / 2f, rim);

                int symbolColor = focused ? 0xFF0F0F12 : 0xFFFFFFFF;
                stroke.setColor(symbolColor);
                dot.setColor(symbolColor);

                // 3 horizontal bars with circular knobs at varied positions —
                // reads as "sliders" / "configure" at a glance. Knob ordering
                // (25%, 70%, 50%) gives an asymmetric, hand-tuned look that
                // never collides with the bar end-caps.
                float ic       = r * 0.96f;
                float lineW    = ic * 1.30f;
                float lineL    = cx - lineW / 2f;
                float lineR    = cx + lineW / 2f;
                float strokeW  = ic * 0.18f;
                float spacing  = ic * 0.55f;
                float knobR    = strokeW * 0.95f;
                stroke.setStrokeWidth(strokeW);
                stroke.setStrokeCap(Paint.Cap.ROUND);
                stroke.setStrokeJoin(Paint.Join.ROUND);

                final float[] knobFrac = { 0.28f, 0.70f, 0.46f };
                for (int i = 0; i < 3; i++) {
                    float by = cy + (i - 1) * spacing;
                    c.drawLine(lineL, by, lineR, by, stroke);
                    float kx = lineL + lineW * knobFrac[i];
                    // Outer knob disc (matches plate colour) acts as a "cut-out"
                    // around the inner knob to visually separate it from the bar.
                    c.drawCircle(kx, by, knobR + strokeW * 0.45f,
                            focused ? bgFocus : bgIdle);
                    c.drawCircle(kx, by, knobR, dot);
                }
            }
        };
        applyApplePillStyle(v);
        v.setOnClickListener(view -> {
            view.playSoundEffect(SoundEffectConstants.CLICK);
            showKeymapOverlay();
        });
        v.setOnFocusChangeListener((view, f) -> {
            view.animate().cancel();
            view.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f)
                    .setDuration(100).setInterpolator(FOCUS_EASE).start();
            view.invalidate();
        });
        v.setOnKeyListener((view, kc, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    view.playSoundEffect(SoundEffectConstants.CLICK);
                    view.performClick(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // Down lands on the first shelf cell — natural since the
                    // mapper is the leftmost icon and the leftmost shelf cell
                    // sits below it.
                    RecyclingShelfView s = shelf;
                    if (s != null) s.requestFocusOnIndex(0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // Wrap to last shelf cell — taking over what netBtn
                    // previously did when it was leftmost.
                    RecyclingShelfView sl = shelf;
                    if (sl != null) sl.requestFocusOnIndex(sl.lastIndex());
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    View nb = netBtn; if (nb != null) nb.requestFocus(); return true;
                default: return false;
            }
        });
        return v;
    }

    /** Common Apple-TV pill setup is now centralised in {@link AppleStyle}.
     *  This wrapper exists only so unchanged button-construction call sites
     *  (which call {@code applyApplePillStyle(v)} unqualified) keep
     *  compiling. The body is a one-liner forwarding to the shared helper. */
    private void applyApplePillStyle(View v) {
        AppleStyle.applyApplePillStyle(v);
    }

    private Paint makeBtnPaint(boolean fill) {
        return AppleStyle.makeBtnPaint(fill);
    }

    private Paint makeBtnStrokePaint() {
        return AppleStyle.makeBtnStrokePaint();
    }

    /** Idle button background — dark glass that reads on any wallpaper. */
    private Paint makeBgIdlePaint() {
        return AppleStyle.makeBgIdlePaint();
    }

    /** Focused button background — frosted near-white that lifts the symbol
     *  via inversion. This is the Apple-TV "selected pill" effect. */
    private Paint makeBgFocusPaint() {
        return AppleStyle.makeBgFocusPaint();
    }

    /** Hairline inner rim that defines the glass plate edge in any state.
     *  Stroke width is 1 dp scaled by the activity's cached density. */
    private Paint makeRimPaint() {
        return AppleStyle.makeRimPaint(density);
    }

    private void openNetSettings() {
        String[] actions = { Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS };
        for (String a : actions) {
            try { startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
            catch (Exception ignored) {}
        }
        showToast(getString(R.string.toast_no_network_settings));
    }

    final class RecyclingShelfView extends ViewGroup {

        private static final int BUFFER = 4;

        private final ArrayList<CellView>   pool     = new ArrayList<>(8);
        private final SparseArray<CellView> attached = new SparseArray<>();
        private final OverScroller scroller;
        private VelocityTracker velTracker;
        private float lastTouchX;
        private int   scrollX     = 0;
        private int   totalW      = 0;
        private int   centerX     = 0;
        private boolean needsRefill = false;
        // sidePad — half-stride gutter on each side of every cell (cellW + 2*sidePad = stride)
        // edgePad — buffer kept between the focused cell and the viewport edge
        //           when ensureVisible scrolls. Pre-computed once per shelf
        //           instead of dp(10) / dp(48) every scroll frame & focus event.
        private final int cellW, cellH, stride, sidePad, edgePad;

        // Source of truth for what the shelf is rendering RIGHT NOW. The
        // outer appList is the master inventory of every installed
        // launchable app; the shelf may show a filtered subset (hide-apps
        // feature). bindCell, fillVisible, requestFocusOnIndex, etc. all
        // read from this list — never from the outer appList directly.
        // Mismatching the two was the cause of the "hide app function not
        // working" regression: setApps used to update the bookkeeping
        // (totalW, focusedIndex) from the filtered list while bindCell
        // still rendered apps from the unfiltered appList, so cells got
        // counted but rendered the wrong identities.
        private final ArrayList<AppInfo>   displayed = new ArrayList<>();

        int focusedIndex = 0;

        boolean reorderMode   = false;
        int     dragIndex     = -1;

        // True while a programmatic D-pad-held navigation is being processed.
        // Triggers two short-circuits in CellView.onFocusChange:
        //   • scale snaps to its target (no animator) — avoids the ~50 ms
        //     thrash where each held-key event cancels the previous bounce
        //     and leaves cells stuck at intermediate scales.
        //   • the redundant ensureVisible() is skipped — requestFocusOnIndex
        //     already ran ensureVisibleSync().
        // Set/cleared synchronously around requestFocus(), so it accurately
        // tags the focus callback that fires inside requestFocus().
        boolean fastNav = false;

        // MENU_UNINSTALL=0 (top), MENU_APP_INFO=1 (middle), MENU_MOVE=2 (bottom)
        private static final int MENU_UNINSTALL = 0;
        private static final int MENU_APP_INFO  = 1;
        private static final int MENU_MOVE      = 2;
        int menuSelection = MENU_MOVE;

        RecyclingShelfView(Context ctx) {
            super(ctx);
            // Pass SCROLL_EASE so startScroll() honours our Material-style
            // ease-in-out curve. Fling deceleration uses the framework's
            // own SplineOverScroller and is unaffected by this interpolator —
            // exactly the right split: programmatic d-pad scrolls feel
            // premium, touch-fling keeps native physics.
            scroller = new OverScroller(ctx, SCROLL_EASE);
            cellW   = dp(CELL_W_DP);
            cellH   = dp(CELL_H_DP);
            sidePad = dp(10);
            edgePad = dp(48);
            stride  = cellW + sidePad * 2;
            setFocusable(false);
            setClipChildren(false);
            setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        }

        void enterReorderMode(int idx) {
            if (reorderMode) return;
            reorderMode   = true;
            dragIndex     = idx;
            menuSelection = MENU_MOVE;
            rebindAll();
            CellView cv = attached.get(idx); if (cv != null) LauncherActivity.this.showContextMenu(cv);
            // rebindAll() calls cv.layout() directly — no requestLayout in flight.
            // post() fires after the current message finishes, which is exactly when
            // the cell's screen coordinates are stable. No global layout listener needed.
            post(LauncherActivity.this::updateRingAfterMove);
        }

        void exitReorderMode(boolean persist) {
            if (!reorderMode) return;
            reorderMode = false;
            dragIndex   = -1;
            hideContextMenu();
            if (persist) saveOrder();
            rebindAll();
            // rebindAll() calls requestFocus() on focusedIndex, which triggers the focus
            // listener. Because reorderMode is already false at that point, the focus-loss
            // branch on the OLD drag cell would hide the ring, and the focus-gain branch on
            // the new cell would post(positionRing). To avoid the 1-frame invisible flicker,
            // we post an explicit reposition that runs in the same message as the focus event.
            final int idx = focusedIndex;
            post(() -> {
                CellView cv = attached.get(idx);
                if (cv != null && cv.isAttachedToWindow() && cv.getWidth() > 0)
                    LauncherActivity.this.positionRing(cv);
            });
        }

        void swapWithNeighbour(int targetIdx) {
            if (targetIdx < 0 || targetIdx >= displayed.size() || targetIdx == dragIndex) return;
            // Capture the from/to so the post-swap slide animation knows the
            // visual delta between each cell's old and new screen positions.
            int oldDragIdx = dragIndex;
            // Swap in displayed (the rendered ordering). We then mirror the
            // swap into the master appList — but ONLY for the two AppInfo
            // identities involved, leaving any hidden apps that sit between
            // them in their original positions. This keeps the persisted
            // order in sync with what the user actually rearranged without
            // scrambling hidden-app placement.
            AppInfo movedApp     = displayed.get(oldDragIdx);
            AppInfo neighbourApp = displayed.get(targetIdx);
            Collections.swap(displayed, oldDragIdx, targetIdx);
            int aMaster = -1, bMaster = -1;
            for (int i = 0, n = appList.size(); i < n; i++) {
                AppInfo a = appList.get(i);
                if      (a == movedApp)     aMaster = i;
                else if (a == neighbourApp) bMaster = i;
                if (aMaster >= 0 && bMaster >= 0) break;
            }
            if (aMaster >= 0 && bMaster >= 0 && aMaster != bMaster) {
                Collections.swap(appList, aMaster, bMaster);
            }
            dragIndex    = targetIdx;
            focusedIndex = dragIndex;
            ensureVisibleSync(dragIndex);   // sync scroll — swap-slide animates cleanly off final layout
            rebindAll();                    // bindCell → layout() — cell positions final now

            // Slide animation: the cell now occupying targetIdx (showing the
            // dragged app) appears to glide FROM its old visual position to
            // the new one. The displaced neighbour, now at oldDragIdx, glides
            // the opposite way. Implementation trick: rebindAll() has already
            // placed both cells at their FINAL layout positions, so we offset
            // them via translationX (which doesn't affect layout) and animate
            // that offset back to zero.
            int slidePx = (oldDragIdx - targetIdx) * stride;
            CellView movedCell     = attached.get(targetIdx);
            CellView neighbourCell = attached.get(oldDragIdx);
            if (movedCell != null) {
                movedCell.animate().cancel();
                movedCell.setTranslationX(slidePx);
                movedCell.animate()
                        .translationX(0f)
                        .setDuration(140)
                        .setInterpolator(REORDER_EASE)
                        // Per-frame ring track: the dragged cell carries the
                        // selection ring, so the halo follows the slide.
                        .setUpdateListener(anim -> {
                            if (movedCell.isAttachedToWindow())
                                LauncherActivity.this.positionRing(movedCell);
                        })
                        .start();
            }
            if (neighbourCell != null) {
                neighbourCell.animate().cancel();
                neighbourCell.setTranslationX(-slidePx);
                neighbourCell.animate()
                        .translationX(0f)
                        .setDuration(140)
                        .setInterpolator(REORDER_EASE)
                        .setUpdateListener(null)
                        .start();
            }

            CellView cv = attached.get(dragIndex);
            if (cv != null) LauncherActivity.this.showContextMenu(cv);
            // Direct call — cell.mLeft is already updated by repositionAttached() above,
            // so getLocationOnScreen() returns the correct coordinate immediately.
            // The animation's update listener keeps it tracking through the slide.
            LauncherActivity.this.updateRingAfterMove();
        }

        private void rebindAll() {
            for (int i = 0; i < attached.size(); i++) {
                int idx = attached.keyAt(i);
                if (idx >= 0 && idx < displayed.size()) bindCell(attached.valueAt(i), idx);
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
                CellView cv = attached.valueAt(i);
                // Detach from any pending icon loads
                if (cv.boundApp != null) {
                    List<CellView> waiters = LauncherActivity.this.iconInflight.get(cv.boundApp.packageName);
                    if (waiters != null) waiters.remove(cv);
                }
                cv.iconBitmap = null;
                cv.setVisibility(GONE); pool.add(cv);
            }
            attached.clear();
            // Snapshot the caller's list into our own so subsequent
            // mutations from the activity don't reach inside the shelf
            // (the activity may rebuild appList during a package broadcast
            // without re-calling setApps; we want stable rendering until
            // applyShelfApps is invoked again).
            displayed.clear();
            if (apps != null && !apps.isEmpty()) displayed.addAll(apps);
            if (displayed.isEmpty()) { focusedIndex = 0; scrollX = 0; }
            else                     focusedIndex = Math.min(focusedIndex, displayed.size() - 1);
            totalW = displayed.size() * stride; centerX = 0; needsRefill = true;
            requestLayout();
            for (AppInfo app : displayed) preWarmIcon(app);
            final int targetIdx = focusedIndex;
            post(() -> requestFocusOnIndex(targetIdx));
        }

        void requestFocusOnIndex(int idx) { requestFocusOnIndex(idx, false); }

        /** Last visible-cell index, or 0 if the shelf is empty. Callers
         *  that want to jump to "the rightmost shelf cell" should use this
         *  instead of {@code appList.size() - 1} so the hide-apps filter
         *  is respected (otherwise an UP-from-toolbar can land focus on a
         *  hidden index past the end of the rendered cells, which the
         *  shelf then has to clamp — visible as a brief mis-positioned
         *  ring before snap-back). */
        int lastIndex() { return displayed.isEmpty() ? 0 : displayed.size() - 1; }

        /** Programmatic focus jump.
         *  @param snap  true → no smooth-scroll animation. Used for held
         *               D-pad navigation (key-repeat) so fast-scroll feels
         *               actually fast — the smooth path was queueing
         *               120-240 ms tweens that each cancelled the previous.
         *
         *  Boundary behaviour:
         *    • snap = false (single press) → CYCLIC. Stepping past the last
         *      cell wraps to the first and vice versa.
         *    • snap = true (held key) → CLAMP at first / last. Cyclic wrap
         *      mid-key-repeat would teleport the shelf under the user's
         *      fingers, which reads as "fast scroll is broken". Clamping
         *      gives a stable edge for fast nav. Releasing and pressing
         *      again gets the cyclic single-press behaviour back.
         *
         *  Wrap-around mechanics (the part that used to land focus on the
         *  wrong cell — "third app from left" / "second from right"):
         *    A wrap is a giant scroll jump. ensureVisibleSync runs
         *    doScrollTo, which inside fillVisible recycles the currently-
         *    focused cell via setVisibility(GONE). That synchronously
         *    transfers focus to a still-attached intermediate cell, whose
         *    onFocusChange listener kicks off its own ensureVisible →
         *    smoothScrollTo back toward where it sits. By the time we
         *    finally cv.requestFocus() on the wrap target, the destination
         *    smoothScrollTo(0) short-circuits with dx==0 and never aborts
         *    the competing animation — so the shelf glides past the target
         *    and focus settles 2-3 cells in.
         *
         *    Fix: keep fastNav=true through the entire wrap path so every
         *    intermediate focus event short-circuits the listener's
         *    ensureVisible. The destination cell is then focused cleanly
         *    and we run a manual focus-bounce so wrap navigation still has
         *    its visual cue (the bounce that previously rode on the focus
         *    listener path). */
        void requestFocusOnIndex(int idx, boolean snap) {
            if (displayed.isEmpty()) return;
            int sz = displayed.size();
            boolean wrapped = false;
            if (snap) {
                // Held D-pad → clamp.
                if (idx < 0)   idx = 0;
                if (idx >= sz) idx = sz - 1;
            } else {
                // Single press → cyclic wrap.
                if      (idx < 0)   { idx = sz - 1; wrapped = true; }
                else if (idx >= sz) { idx = 0;      wrapped = true; }
            }
            focusedIndex = idx;
            // Cancel any in-flight fling to prevent scroll fighting
            scroller.abortAnimation();

            // bigJump = any path where the destination is far enough that
            // fillVisible will recycle the currently-focused cell. Both
            // wrap and held-key paths qualify; only the smooth single-step
            // press is safe to leave the focus listener unguarded.
            boolean bigJump = snap || wrapped;

            boolean prevFast = fastNav;
            if (bigJump) fastNav = true;
            try {
                if (bigJump) ensureVisibleSync(idx);
                else         ensureVisible(idx);
                // Force fillVisible after scroll to ensure the cell exists
                fillVisible();
                CellView cv = attached.get(idx);
                if (cv != null) {
                    cv.requestFocus();
                } else {
                    // Cell not yet attached — post a retry after layout.
                    final int target = idx;
                    final boolean fastDeferred = bigJump;
                    post(() -> {
                        fillVisible();
                        CellView cv2 = attached.get(target);
                        if (cv2 != null) {
                            boolean p = fastNav;
                            fastNav = fastDeferred;
                            try { cv2.requestFocus(); }
                            finally { fastNav = p; }
                        }
                    });
                }
            } finally {
                fastNav = prevFast;
            }

            // Wrap deserves the focus bounce so the user clearly perceives
            // they jumped to the other end. Held-key (snap) skips it — fast
            // nav wants a calm visual. Bounce is run manually because the
            // bigJump path suppressed the focus-listener animator.
            if (wrapped) {
                CellView cvBounce = attached.get(idx);
                if (cvBounce != null && cvBounce.isFocused()) {
                    cvBounce.animate().cancel();
                    cvBounce.setScaleX(1f); cvBounce.setScaleY(1f);
                    // Re-anchor the ring to the now-scale-1 cell BEFORE the
                    // animation starts. Without this, the previous fastNav
                    // positionRing call used scale=FOCUS_SCALE, so for one
                    // frame the ring sat at the larger radius around a
                    // shrunk cell — read as "ring jumps off" at wrap. The
                    // animator's per-frame update listener takes over after
                    // this first sync.
                    LauncherActivity.this.positionRing(cvBounce);
                    cvBounce.animate()
                            .scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE)
                            .setDuration(FOCUS_DUR_MS)
                            .setInterpolator(FOCUS_IN_BOUNCE)
                            .setUpdateListener(cvBounce.focusUpdateListener)
                            .start();
                }
            }
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w > 0) centerX = (totalW < w) ? (w - totalW) / 2 : dp(24);
            repositionAttached(); fillVisible();
        }

        private int cellLeft(int i) { return centerX + i * stride + sidePad - scrollX; }

        /** Maximum legal scrollX. Includes symmetric end-padding equal to
         *  centerX (= dp(24) when content overflows) so the LAST cell on the
         *  right is always rendered with the same gutter as the first cell
         *  on the left. The previous formula `totalW - getWidth()` ignored
         *  the right gutter and clipped the trailing cell by ~dp(14) — the
         *  "last app gets cropped on the right side" bug.
         *
         *  Math (overflow case, centerX = dp(24), sidePad = dp(10)):
         *    last_right_in_world = centerX + (n-1)*stride + sidePad + cellW
         *                        = centerX + n*stride - sidePad
         *                        = centerX + totalW - sidePad
         *    we want: scrollXMax + getWidth() ≥ last_right_in_world + centerX
         *    →        scrollXMax = totalW + 2*centerX - sidePad - getWidth()
         *
         *  Fits-case (centerX = (w - totalW)/2): the formula evaluates to
         *  -sidePad which clamps to 0 — no scroll allowed when content fits. */
        private int scrollXMax() {
            return Math.max(0, totalW + 2 * centerX - sidePad - getWidth());
        }

        private void fillVisible() {
            int w = getWidth();
            if (w == 0 || displayed.isEmpty()) return;
            if (centerX == 0) centerX = (totalW < w) ? (w - totalW) / 2 : dp(24);
            int first = Math.max(0, (scrollX - centerX) / stride - BUFFER);
            int last  = Math.min(displayed.size() - 1, (scrollX + w - centerX) / stride + BUFFER);
            for (int i = attached.size() - 1; i >= 0; i--) {
                int idx = attached.keyAt(i);
                if (idx < first || idx > last) {
                    CellView cv = attached.valueAt(i);
                    // Detach from any pending icon load so stale bitmap isn't delivered
                    if (cv.boundApp != null) {
                        List<CellView> waiters = LauncherActivity.this.iconInflight.get(cv.boundApp.packageName);
                        if (waiters != null) waiters.remove(cv);
                    }
                    cv.iconBitmap = null;
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
                cv.animate().setUpdateListener(null).setListener(null); // drop captured lambdas before reuse
                cv.setScaleX(1f); cv.setScaleY(1f); // reset scale before reuse
                cv.setTranslationX(0f);         // reorder slide leftover
                cv.setTranslationY(0f);         // safety reset (no current Y animation)
                cv.setAlpha(1f);                // reset alpha
                cv.iconBitmap = null;           // clear stale bitmap — prevents ghost icons
                cv.boundApp   = null;           // clear stale binding
                cv.boundIndex = -1;             // clear stale index
                cv.setVisibility(VISIBLE);
                cv.invalidate();                // force redraw with clean state
                return cv;
            }
            CellView cv = new CellView(getContext()); addView(cv); return cv;
        }

        private void bindCell(CellView cv, int index) {
            if (index < 0 || index >= displayed.size()) {
                // Defensive: skip stale binds from a recycle path that
                // raced an applyShelfApps() shrink. The cell's content
                // will be re-bound on the next fillVisible.
                return;
            }
            AppInfo app = displayed.get(index);
            int left = cellLeft(index), top = (getMeasuredHeight() - cellH) / 2;
            cv.bind(app, index);
            cv.layout(left, top, left + cellW, top + cellH);
            cv.invalidate();
        }

        private void repositionAttached() {
            for (int i = 0; i < attached.size(); i++) {
                int idx = attached.keyAt(i); CellView cv = attached.valueAt(i);
                int left = cellLeft(idx), top = (getMeasuredHeight() - cellH) / 2;
                cv.layout(left, top, left + cellW, top + cellH);
            }
        }

        private void doScrollTo(int x) {
            int max = scrollXMax();
            int newX = Math.max(0, Math.min(x, max));
            if (newX == scrollX) return; // no-op avoids redundant work
            scrollX = newX;
            repositionAttached(); fillVisible();
            // Keep ring tracking the focused cell during programmatic scrolls
            if (!reorderMode) {
                CellView fc = attached.get(focusedIndex);
                if (fc != null && fc.isFocused()) LauncherActivity.this.positionRing(fc);
            }
        }

        /** Smooth animated scroll. Used for d-pad navigation so the shelf
         *  glides between positions instead of snapping. The OverScroller's
         *  computeScrollOffset path delivers per-frame updates which we route
         *  through doScrollTo, so the ring naturally tracks the moving cells. */
        private void smoothScrollTo(int x) {
            int max = scrollXMax();
            int target = Math.max(0, Math.min(x, max));
            int dx = target - scrollX;
            // Always abort any in-flight scroll first, even on a no-op call.
            // Otherwise a stale animation from earlier could keep gliding
            // under us — the focus-listener path used to call this with
            // dx==0 right after a wrap and the early-return left a leftover
            // scroller alive, which is exactly how the cyclic-wrap focus
            // landed several cells past the edge.
            scroller.abortAnimation();
            if (dx == 0) return;
            // Duration scales gently with distance — short hops feel snappy
            // (90 ms) while long jumps still complete in under ~190 ms so
            // they never feel sluggish. Held D-pad bypasses this path
            // entirely (snap mode) for true fast scroll.
            int dist = Math.abs(dx);
            int dur  = Math.max(90, Math.min(190, 90 + dist / 8));
            scroller.startScroll(scrollX, 0, dx, 0, dur);
            postInvalidateOnAnimation();
        }

        private void ensureVisible(int idx) {
            int left = centerX + idx * stride + sidePad, right = left + cellW;
            // Use animated scroll for d-pad navigation so the shelf glides
            // smoothly. Touch fling continues to use the scroller's own path
            // via onTouchEvent, and cell-attachment scrolls during reorder
            // use doScrollTo synchronously to avoid mid-swap visual races.
            if      (left  - edgePad < scrollX)               smoothScrollTo(Math.max(0, left - edgePad));
            else if (right + edgePad > scrollX + getWidth())  smoothScrollTo(right + edgePad - getWidth());
        }

        /** Synchronous variant used by reorder flow — there we need the cells
         *  laid out at their final positions BEFORE running the swap-slide
         *  animation, so we can't tolerate an in-flight scroll animation. */
        private void ensureVisibleSync(int idx) {
            int left = centerX + idx * stride + sidePad, right = left + cellW;
            if      (left  - edgePad < scrollX)               doScrollTo(Math.max(0, left - edgePad));
            else if (right + edgePad > scrollX + getWidth())  doScrollTo(right + edgePad - getWidth());
        }

        // True from ACTION_DOWN until the fling settles. While set, the ring is
        // hidden and focus changes are suppressed. Snapping to the centermost
        // visible cell happens once the scroller fully stops, which restores
        // the ring on the destination cell in a single frame.
        private boolean touchScrolling = false;

        @Override public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                doScrollTo(scroller.getCurrX());
                postInvalidateOnAnimation();
            } else if (touchScrolling) {
                // Fling has settled. Snap focus to the cell whose centre is
                // closest to the viewport centre — that's the natural target
                // for touch-scroll on a horizontal carousel.
                touchScrolling = false;
                snapFocusToVisibleCenter();
            }
        }

        /** Finds the attached cell whose centre is closest to the viewport
         *  centre and gives it focus. Called only after a touch fling settles. */
        private void snapFocusToVisibleCenter() {
            int w = getWidth();
            if (w <= 0 || displayed.isEmpty()) return;
            int viewportCenterX = scrollX + w / 2;
            int bestIdx = focusedIndex;
            int bestDist = Integer.MAX_VALUE;
            // Use the precomputed sidePad (== dp(10) at construction) instead
            // of calling dp(10) per iteration — same value, no per-loop math.
            int halfCellW = cellW / 2;
            for (int i = 0; i < attached.size(); i++) {
                int idx = attached.keyAt(i);
                int cellCenter = centerX + idx * stride + sidePad + halfCellW;
                int dist = Math.abs(cellCenter - viewportCenterX);
                if (dist < bestDist) { bestDist = dist; bestIdx = idx; }
            }
            if (bestIdx != focusedIndex) {
                requestFocusOnIndex(bestIdx);
                return;
            }
            // Same cell — make sure focus and ring are restored.
            CellView cv = attached.get(bestIdx);
            if (cv == null || !cv.isAttachedToWindow() || cv.getWidth() <= 0) {
                requestFocusOnIndex(bestIdx);
                return;
            }
            if (!cv.isFocused()) cv.requestFocus();
            LauncherActivity.this.positionRing(cv);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (velTracker == null) velTracker = VelocityTracker.obtain();
            velTracker.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scroller.abortAnimation();
                    lastTouchX = ev.getX();
                    touchScrolling = true;
                    // Hide the ring immediately — during a touch drag the
                    // ring shouldn't track the originally-focused cell as it
                    // scrolls off; that produced a "ghost ring slides off the
                    // edge" artefact and made the icons appear to overlap as
                    // their selection halo dragged across them.
                    RingView rvDown = ringView;
                    if (rvDown != null) rvDown.setVisibility(View.INVISIBLE);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = lastTouchX - ev.getX(); lastTouchX = ev.getX();
                    doScrollTo(scrollX + (int) dx); break;
                case MotionEvent.ACTION_UP:
                    velTracker.computeCurrentVelocity(1000);
                    int vx = (int) velTracker.getXVelocity();
                    scroller.fling(scrollX, 0, -vx, 0,
                            0, scrollXMax(), 0, 0);
                    velTracker.recycle(); velTracker = null;
                    if (scroller.isFinished()) {
                        // Zero-velocity release: settle handler in computeScroll
                        // won't fire because no fling animation was queued.
                        // Snap immediately so the ring reappears.
                        touchScrolling = false;
                        snapFocusToVisibleCenter();
                    } else {
                        postInvalidateOnAnimation();
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    scroller.abortAnimation();
                    velTracker.recycle(); velTracker = null;
                    touchScrolling = false;
                    snapFocusToVisibleCenter();
                    break;
            }
            return true;
        }

        // ── CellView ──────────────────────────────────────────────────────────

        final class CellView extends View {

            Bitmap  iconBitmap;
            AppInfo boundApp;
            int     boundIndex;
            private long    centerKeyDownAt      = 0;
            private boolean longPressArmed       = false;
            private boolean longPressFired       = false;
            // Set true when reorderMode is entered via key long-press so that the
            // continued key-repeat (and KEY_UP) don't immediately confirm/exit.
            private boolean suppressCenterUntilUp = false;

            private final Paint   phRing;
            private final Paint   labelPaint;
            private final Paint   iconPaint;
            private final TextPaint labelTp;
            private final int     iconPx;
            private final float   phR;
            private final float   phStroke;
            private final float   labelOffsetY;
            private final float   labelMaxWInset;
            private final float   icyOffset;
            private       String  labelStr     = "";
            private       String  labelDisplay = "";

            // Pre-allocated focus-tween update listener — reused across every
            // focus animation so we don't churn a fresh lambda (with its
            // captured CellView.this) per key press during fast nav.
            private final android.animation.ValueAnimator.AnimatorUpdateListener focusUpdateListener =
                    anim -> {
                        if (isFocused() && isAttachedToWindow())
                            positionRing(CellView.this);
                    };

            CellView(Context ctx) {
                super(ctx);
                iconPx         = dp(ICON_DP);
                phR            = iconPx / 2f - dp(2);
                phStroke       = dp(1);
                labelOffsetY   = iconPx / 2f + dp(17);
                labelMaxWInset = dp(6);
                icyOffset      = iconPx / 2f;  // centred in cell — ring aligns to this

                phRing = new Paint(Paint.ANTI_ALIAS_FLAG);
                phRing.setStyle(Paint.Style.STROKE);
                phRing.setColor(0x55FFFFFF);
                phRing.setStrokeWidth(phStroke);

                iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

                labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                labelPaint.setColor(Color.WHITE);
                labelPaint.setTextSize(dp(11));
                labelPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                labelPaint.setTextAlign(Paint.Align.CENTER);
                labelPaint.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
                labelPaint.setLetterSpacing(0.02f);

                labelTp = new TextPaint(labelPaint);

                setFocusable(true); setFocusableInTouchMode(true);
                setClickable(true); setWillNotDraw(false);
                // Suppress platform default rectangular focus selector — our
                // RingView handles focus indication exclusively.
                setDefaultFocusHighlightEnabled(false);
                setBackground(null);
                setForeground(null);
                setStateListAnimator(null);
                setSoundEffectsEnabled(true);

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
                    if (!reorderMode) {
                        animate().cancel();
                        if (fastNav) {
                            // Held D-pad nav — snap scale, skip animator entirely.
                            // Without this, every key-repeat press fires a focus
                            // animation that's cancelled ~50 ms later by the next
                            // press, which both thrashes ViewPropertyAnimator and
                            // leaves cells stuck at intermediate scales when the
                            // hold ends. Snap-scale gives a clean, instant fast-
                            // scroll feel that matches the synced shelf scroll.
                            setScaleX(f ? FOCUS_SCALE : 1f);
                            setScaleY(f ? FOCUS_SCALE : 1f);
                            if (f && isAttachedToWindow() && getWidth() > 0)
                                positionRing(CellView.this);
                        } else if (f) {
                            // Subtle bouncy focus-IN: OvershootInterpolator(2.0)
                            // ticks the cell ~7-8 % past FOCUS_SCALE then settles.
                            // Reads as a tiny "tap" of life on selection without
                            // dominating the shelf or stressing slow GPUs.
                            // The reused focusUpdateListener keeps the RingView
                            // in lockstep with the cell every frame — and being
                            // pre-allocated, avoids per-focus lambda churn.
                            animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE)
                                     .setDuration(FOCUS_DUR_MS)
                                     .setInterpolator(FOCUS_IN_BOUNCE)
                                     .setUpdateListener(focusUpdateListener)
                                     .start();
                        } else {
                            // Plain decelerate shrink on focus-out — no bounce,
                            // matches Material spec for de-selection. Clear the
                            // update listener so the lambda doesn't fire pointlessly
                            // during the unfocus tween (it's a no-op anyway since
                            // !isFocused, but the dispatch cost is real).
                            animate().scaleX(1f).scaleY(1f)
                                     .setDuration(UNFOCUS_DUR_MS)
                                     .setInterpolator(FOCUS_EASE)
                                     .setUpdateListener(null)
                                     .start();
                        }
                    }
                    invalidate();
                    if (f) {
                        focusedIndex = boundIndex;
                        if (!reorderMode) {
                            // Position the ring SYNCHRONOUSLY here. By the time we get
                            // a focus-gain callback, requestFocusOnIndex has already
                            // run ensureVisible+fillVisible+bindCell, which means
                            // cv.layout() has been called and getLocationOnScreen()
                            // returns the final stable coordinates. Posting the call
                            // produced a 1-frame ring lag during fast d-pad presses
                            // (each press hid the ring on the prior cell, so the user
                            // saw the ring "disappear" between consecutive cells).
                            if (isAttachedToWindow() && getWidth() > 0)
                                positionRing(CellView.this);
                            // Skip the smooth-scroll path during snap navigation —
                            // requestFocusOnIndex already called ensureVisibleSync().
                            if (!fastNav) ensureVisible(boundIndex);
                        }
                    }
                    // Don't hide the ring on focus loss — the next cell to gain
                    // focus will reposition it in the SAME frame. Hiding here
                    // produced the "ring stutters across only a handful of apps
                    // during fast scroll" artefact, because the brief INVISIBLE
                    // state was visible to the user between every key press.
                    // The ring is hidden explicitly when:
                    //   • focus leaves the shelf entirely (handled by the
                    //     globalFocusListener on the root)
                    //   • the activity exits or the shelf is re-populated
                    //   • a touch interaction begins on the shelf
                });

                setOnKeyListener((v, kc, ev) -> {
                    if (reorderMode) {
                        boolean isCenterKey = kc == KeyEvent.KEYCODE_DPAD_CENTER
                                || kc == KeyEvent.KEYCODE_ENTER
                                || kc == KeyEvent.KEYCODE_BUTTON_A;

                        // Clear the suppression latch on KEY_UP so the next press works normally.
                        if (isCenterKey && ev.getAction() == KeyEvent.ACTION_UP) {
                            suppressCenterUntilUp = false;
                            return true; // consume — don't treat KEY_UP as a confirm
                        }
                        // Suppress repeating center-key presses until the key is fully released.
                        if (isCenterKey && suppressCenterUntilUp) return true;

                        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                        switch (kc) {
                            case KeyEvent.KEYCODE_DPAD_LEFT:
                                if (menuSelection == MENU_MOVE) swapWithNeighbour(dragIndex - 1);
                                return true;
                            case KeyEvent.KEYCODE_DPAD_RIGHT:
                                if (menuSelection == MENU_MOVE) swapWithNeighbour(dragIndex + 1);
                                return true;
                            case KeyEvent.KEYCODE_DPAD_UP:
                                // Cycle MOVE → APP_INFO → UNINSTALL (top); stop at top.
                                if      (menuSelection == MENU_MOVE)     { menuSelection = MENU_APP_INFO; updateMenuHighlight(); }
                                else if (menuSelection == MENU_APP_INFO) { menuSelection = MENU_UNINSTALL; updateMenuHighlight(); }
                                return true;
                            case KeyEvent.KEYCODE_DPAD_DOWN:
                                // Cycle UNINSTALL → APP_INFO → MOVE; DOWN at MOVE confirms.
                                if      (menuSelection == MENU_UNINSTALL) { menuSelection = MENU_APP_INFO; updateMenuHighlight(); }
                                else if (menuSelection == MENU_APP_INFO)  { menuSelection = MENU_MOVE;     updateMenuHighlight(); }
                                else exitReorderMode(true);
                                return true;
                            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                            case KeyEvent.KEYCODE_BUTTON_A:
                                if      (menuSelection == MENU_UNINSTALL) triggerUninstall();
                                else if (menuSelection == MENU_APP_INFO)  triggerAppInfo();
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
                                    suppressCenterUntilUp = true; // block repeat/UP from immediately exiting
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
                            if (wasArmed && !reorderMode) {
                                // Play TV-style click sound on confirm. No haptics.
                                playSoundEffect(SoundEffectConstants.CLICK);
                                performClick();
                            }
                            return true;
                        }
                        return false;
                    }

                    if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                    switch (kc) {
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            // Snap (instant) when the key is being held down so
                            // the user gets the fast-scroll they pressed for.
                            // First press (repeatCount==0) still gets the smooth
                            // glide for a polished single-step feel.
                            requestFocusOnIndex(boundIndex - 1, ev.getRepeatCount() > 0);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            requestFocusOnIndex(boundIndex + 1, ev.getRepeatCount() > 0);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_UP:
                            View nb = netBtn; if (nb != null) nb.requestFocus(); return true;
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            // Consume — there's nothing below the shelf. Without this,
                            // the platform's focus-search may jump to an unrelated
                            // descendant or trigger an audible "focus-blocked" beep on
                            // some TV ROMs. Returning true keeps focus on the cell.
                            return true;
                        default: return false;
                    }
                });
            }

            void triggerUninstall() {
                if (boundApp == null) return;
                final AppInfo appToUninstall = boundApp;
                final Uri pkgUri = Uri.fromParts("package", appToUninstall.packageName, null);

                // Always exit reorder mode FIRST so the dialog opens cleanly
                // and the menu doesn't linger if the user dismisses the system
                // confirmation. The package broadcast receiver will refresh
                // the app list automatically when the uninstall completes.
                exitReorderMode(false);

                // ACTION_DELETE is the modern, non-deprecated path and is
                // wired up by every PackageInstaller variant (including TV
                // ROMs running Android 14+). EXTRA_RETURN_RESULT is removed
                // because (a) it's only honoured by the deprecated
                // ACTION_UNINSTALL_PACKAGE entry point and (b) it caused the
                // result code to come back as RESULT_CANCELED on successful
                // uninstall on several TV ROMs, which masked the success.
                Intent primary = new Intent(Intent.ACTION_DELETE, pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (tryUninstall(primary)) return;

                @SuppressWarnings("deprecation")
                Intent fallback = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (tryUninstall(fallback)) return;

                showToast(getString(R.string.toast_cannot_uninstall, appToUninstall.label));
            }

            /** Open the system "App info" page for the focused app.
             *  Always exits reorder mode first so the menu doesn't linger
             *  behind the settings activity (and so the user comes back to
             *  a clean shelf). Falls back to a toast if no Settings app on
             *  the device handles ACTION_APPLICATION_DETAILS_SETTINGS — that
             *  path is well-supported but cheap-TV ROMs occasionally strip it. */
            void triggerAppInfo() {
                if (boundApp == null) return;
                final String pkg = boundApp.packageName;
                exitReorderMode(false);
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    if (i.resolveActivity(pm) != null) { startActivity(i); return; }
                } catch (Exception ignored) {}
                showToast(getString(R.string.toast_no_app_info));
            }

            private boolean tryUninstall(Intent intent) {
                try {
                    if (intent.resolveActivity(pm) == null) return false;
                    // Plain startActivity — we don't need a result. The
                    // PACKAGE_REMOVED broadcast triggers loadApps() reliably
                    // across every Android version we support.
                    startActivity(intent);
                    return true;
                } catch (Exception ignored) { return false; }
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

                // Show label: always for focused+normal, always for drag target in reorder
                boolean showLabel = (!labelDisplay.isEmpty()) &&
                        ((isFocused() && !reorderMode) || isDragTarget);
                if (showLabel) {
                    float labelY = icy + labelOffsetY;
                    if (labelY < h) canvas.drawText(labelDisplay, cx, labelY, labelPaint);
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
                boolean labelChanged = !app.label.equals(labelStr);
                boundApp = app; boundIndex = index; labelStr = app.label;
                setContentDescription(app.label);
                if (labelChanged) {
                    float maxW = dp(CELL_W_DP) - labelMaxWInset;
                    labelDisplay = labelPaint.measureText(labelStr) > maxW
                            ? TextUtils.ellipsize(labelStr, labelTp, maxW, TextUtils.TruncateAt.END).toString()
                            : labelStr;
                }
                Bitmap cached = iconCache.get(app.packageName);
                if (cached != null) {
                    if (cached != iconBitmap) { iconBitmap = cached; invalidate(); }
                } else {
                    // Icon not yet loaded — clear any stale bitmap and request load
                    if (iconBitmap != null) { iconBitmap = null; invalidate(); }
                    loadIconAsync(app, this);
                }
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
                        // GC stale hidden-set entries before any other consumer
                        // sees the new appList — keeps the saved set in sync
                        // with the actually-installed packages without a
                        // separate scheduling step.
                        pruneHiddenApps(fresh);
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
                            if (s != null) {
                                // Apply any pending scroll-index restore (cold start path).
                                // onResume stashes it when appList was empty.
                                if (pendingScrollIdx >= 0 && !fresh.isEmpty()) {
                                    s.focusedIndex = Math.min(pendingScrollIdx, fresh.size() - 1);
                                    pendingScrollIdx = -1;
                                }
                                applyShelfApps(s);
                            }
                        } else if (pendingScrollIdx >= 0) {
                            // App list unchanged but a pending index is waiting —
                            // honour it. setApps wasn't called, so manually request focus.
                            // Clamp against the shelf's currently-rendered size (not
                            // appList.size()) — when hide-apps is filtering, the saved
                            // index could exceed the visible list and requestFocusOnIndex
                            // would otherwise interpret it as an out-of-bounds wrap.
                            RecyclingShelfView s = shelf;
                            if (s != null && !appList.isEmpty()) {
                                s.requestFocusOnIndex(Math.min(pendingScrollIdx, s.lastIndex()));
                            }
                            pendingScrollIdx = -1;
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
        Collections.sort(out, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
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
        } catch (Exception e) { showToast(getString(R.string.toast_app_unavailable)); }
    }

    // ── Remote-key → app shortcut routing ────────────────────────────────

    /** Parse the persisted keymap once at startup. The pure-Java parsing
     *  logic — including the "drop bindings whose keycode is no longer in
     *  {@link #SHORTCUT_KEYCODES}" rule — lives in {@link KeymapStore},
     *  which is JVM-testable. The activity owns the {@link SparseArray}
     *  destination and the {@code SharedPreferences} read; the parser
     *  pushes accepted entries through the {@code keyMap::put} method
     *  reference (zero-autoboxing for {@code int → SparseArray.put(int)}).
     *
     *  If anything was filtered, we rewrite prefs immediately so the
     *  on-disk format converges to the new shape. */
    private void loadKeyMap() {
        keyMap.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_KEYMAP, null);
        boolean dropped = KeymapStore.parseKeyMap(raw, SHORTCUT_KEYCODES, keyMap::put);
        if (dropped) saveKeyMap();
    }

    /** Write the keymap back to SharedPreferences. Called on every
     *  configuration change (left/right cycle in the overlay) — the user
     *  spec requires assignments to be saved instantly with no confirm.
     *
     *  Two array allocations per save (one int[], one String[]) feed the
     *  testable {@link KeymapStore#serializeKeyMap} signature. The save
     *  path is rare (only when the user changes a binding) — never on a
     *  hot path — so the cost is irrelevant against the testability gain. */
    private void saveKeyMap() {
        int n = keyMap.size();
        int[]    keycodes = new int[n];
        String[] packages = new String[n];
        for (int i = 0; i < n; i++) {
            keycodes[i] = keyMap.keyAt(i);
            packages[i] = keyMap.valueAt(i);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_KEYMAP,
                        KeymapStore.serializeKeyMap(keycodes, packages)).apply();
    }

    /** Parse the persisted hidden-apps set once at startup. Hidden-but-
     *  uninstalled packages get garbage-collected the next time
     *  {@link #loadApps()} runs (see {@link #pruneHiddenApps}). */
    private void loadHiddenApps() {
        hiddenApps.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HIDDEN, null);
        KeymapStore.parseHiddenApps(raw, hiddenApps::add);
    }

    /** Persist the in-memory hiddenApps set. Called synchronously from
     *  every toggle in the hide-manager so the user never has to confirm. */
    private void saveHiddenApps() {
        // ArraySet doesn't implement Iterable<String> via the typed
        // signature KeymapStore expects — wrap with a tiny ArrayList.
        // The wrapper is created at most once per toggle (rare event).
        ArrayList<String> snapshot = new ArrayList<>(hiddenApps.size());
        for (int i = 0, m = hiddenApps.size(); i < m; i++) {
            snapshot.add(hiddenApps.valueAt(i));
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_HIDDEN,
                        KeymapStore.serializeHiddenApps(snapshot)).apply();
    }

    /** Drop hidden-set entries whose package is no longer installed.
     *  Called from loadApps once the fresh appList is known. O(N + M)
     *  via a single ArraySet pass over fresh package names; the prior
     *  nested loop was O(N · M) and quadratic when many apps were
     *  hidden — fine in practice but trivially fixed. */
    private void pruneHiddenApps(List<AppInfo> fresh) {
        if (hiddenApps.isEmpty()) return;
        ArraySet<String> installed = new ArraySet<>(fresh.size());
        for (int j = 0, m = fresh.size(); j < m; j++) installed.add(fresh.get(j).packageName);
        boolean changed = false;
        for (int i = hiddenApps.size() - 1; i >= 0; i--) {
            if (!installed.contains(hiddenApps.valueAt(i))) {
                hiddenApps.removeAt(i);
                changed = true;
            }
        }
        if (changed) saveHiddenApps();
    }

    /** Push the (filtered) shelf list to the RecyclingShelfView.
     *  Single point of policy: the shelf shows appList minus hiddenApps;
     *  every other consumer (keymap picker, hide manager) iterates the
     *  master appList directly so hidden apps remain bindable to remote
     *  keys and toggleable in the hide manager.
     *
     *  Fast-paths the empty-hidden-set case to a direct reference pass —
     *  no allocation, no scan. The list is already sorted/ordered by
     *  loadApps so we preserve order trivially by walking it once. */
    private void applyShelfApps(RecyclingShelfView s) {
        if (s == null) return;
        if (hiddenApps.isEmpty()) { s.setApps(appList); return; }
        List<AppInfo> visible = new ArrayList<>(appList.size());
        for (int i = 0, n = appList.size(); i < n; i++) {
            AppInfo a = appList.get(i);
            if (!hiddenApps.contains(a.packageName)) visible.add(a);
        }
        s.setApps(visible);
    }

    private AppInfo findAppByPackage(String pkg) {
        for (int i = 0; i < appList.size(); i++) {
            AppInfo a = appList.get(i);
            if (a.packageName.equals(pkg)) return a;
        }
        return null;
    }

    /** Keys the launcher must always handle itself — d-pad, confirm,
     *  back/home, volume, power. Mapping any of these is silently
     *  ignored so a misconfiguration can never lock the user out of
     *  navigation.
     *  KEYCODE_MENU is deliberately ABSENT — it's exposed as a mappable
     *  slot in the config overlay, and the launcher never consumes it
     *  itself, so users can repurpose it freely. */
    private static boolean isCoreNavKey(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
                return true;
            default: return false;
        }
    }

    /** Activity-level key dispatch. Two responsibilities:
     *    1. While the keymap overlay is visible, swallow every key into
     *       the overlay's own d-pad navigator. Stops mapped shortcuts
     *       from firing while the user is configuring them, and stops
     *       stray keys from bleeding through to the shelf underneath.
     *    2. Otherwise, look up the keycode in the in-memory keyMap.
     *       Match → launch the assigned app and consume. Lookup runs only
     *       on the first ACTION_DOWN (repeatCount == 0) to avoid relaunch
     *       storms on a held key. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        FrameLayout ko = keymapOverlay;
        if (ko != null && ko.getVisibility() == View.VISIBLE) {
            if (handleKeymapOverlayKey(event)) return true;
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && !isCoreNavKey(event.getKeyCode())) {
            String pkg = keyMap.get(event.getKeyCode());
            if (pkg != null) {
                AppInfo app = findAppByPackage(pkg);
                if (app != null) { launchApp(app); return true; }
                // Mapped to an uninstalled package — clean up so the slot
                // becomes free again on next config open.
                keyMap.delete(event.getKeyCode());
                saveKeyMap();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ── Keymap configuration overlay ─────────────────────────────────────
    //
    // The overlay is a full-screen FrameLayout sitting at the top of the
    // launcher's existing root z-order. Inside the overlay sits a centred
    // "card" LinearLayout that holds two sibling sub-views which alternate
    // visibility based on keymapMode:
    //
    //   keymapColumn    — vertical list of slot rows (default mode)
    //   keymapPickerView — horizontal scrollable app chips (picker mode)
    //
    // No new Activity, no new Fragment, no new resources. Everything is
    // built programmatically and reused across opens.

    /** Build the overlay lazily on first open. Reused for every subsequent
     *  open — keeping it inflated is cheap (one FrameLayout + ~20 child views)
     *  and avoids the inflate cost on every reopen.
     *
     *  Visual style: compact dropdown anchored just below the mapper button
     *  (top-right toolbar). Apple-TV-inspired palette: deep slate plate with
     *  a hairline rim, idle rows transparent + light-grey text, focused row
     *  becomes a bright frosted-white pill with dark text — exactly the same
     *  language as the toolbar buttons (idle dark / focused white-frosted),
     *  so the launcher's visual vocabulary stays consistent.
     *
     *  Compactness: rows flow content-tight (tag → name col → icon → app
     *  label) with NO flex spacer pushing label and value to opposite
     *  edges. The previous design had ~180 dp of dead space in every row;
     *  the new layout uses only what the content needs. */
    private void buildKeymapOverlay() {
        FrameLayout r = root; if (r == null) return;
        FrameLayout ov = new FrameLayout(this) {
            @Override public boolean onTouchEvent(MotionEvent ev) {
                // Tap-outside-the-card dismisses (matches the context-menu UX).
                if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                    android.widget.LinearLayout c = keymapCard;
                    if (c != null) {
                        float x = ev.getX(), y = ev.getY();
                        float l = c.getX(), t = c.getY();
                        float rt = l + c.getWidth(), b = t + c.getHeight();
                        if (x < l || x > rt || y < t || y > b) {
                            hideKeymapOverlay();
                            return true;
                        }
                    }
                }
                return true;
            }
        };
        ov.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        // Light contextual dim — this is a dropdown, not a full-screen modal.
        // Keeps the home shelf faintly visible behind so the action feels
        // anchored to the page rather than blocking it.
        ov.setBackgroundColor(0x33000000);
        ov.setClickable(true);
        ov.setFocusable(true);
        ov.setFocusableInTouchMode(true);
        ov.setVisibility(View.GONE);

        // Apple-TV style card: deep slate plate, soft 14 dp corners, 1 px
        // hairline rim, subtle elevation. Slot list and picker swap
        // visibility inside the same card — no second card needed.
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable cardBg =
                new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xF21A1A1F);          // deep slate, ~95% opacity
        cardBg.setCornerRadius(dp(14));
        cardBg.setStroke(1, 0x1AFFFFFF);      // ~10% white hairline rim
        card.setBackground(cardBg);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setElevation(dp(10));
        // Highlight pills are clipped to the card's rounded outline by
        // hardware regardless of these flags; setting them avoids any
        // accidental scale-overflow clipping for the picker chips.
        card.setClipChildren(false);
        card.setClipToPadding(false);

        // ── Slot list view (default) ──────────────────────────────
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);

        // Each row: [tag dot][name (fixed col)][icon][app label] — left-flow,
        // no flex spacer. Rows use WRAP_CONTENT and are equalised to the
        // widest row's measured width in equalizeKeymapRowWidths() after
        // every binding update — that way the menu shrinks to fit the
        // longest visible app name (no dead space on the right) but the
        // selection pill still aligns across all rows.
        final int nameColW = dp(56);
        for (int i = 0; i < SHORTCUT_LABELS.length; i++) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(7), dp(10), dp(7));
            android.graphics.drawable.GradientDrawable rowBg =
                    new android.graphics.drawable.GradientDrawable();
            rowBg.setCornerRadius(dp(9));
            rowBg.setColor(Color.TRANSPARENT);
            row.setBackground(rowBg);

            // [0] colour tag (small dot for the four colour keys, transparent
            //     placeholder for Menu/Subtitle so the name column still aligns).
            View tag = new View(this);
            android.graphics.drawable.GradientDrawable tagBg =
                    new android.graphics.drawable.GradientDrawable();
            tagBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            tagBg.setColor(SHORTCUT_TAGS[i] == 0 ? Color.TRANSPARENT : SHORTCUT_TAGS[i]);
            tag.setBackground(tagBg);
            android.widget.LinearLayout.LayoutParams tagLp =
                    new android.widget.LinearLayout.LayoutParams(dp(7), dp(7));
            tagLp.setMarginEnd(dp(8));
            row.addView(tag, tagLp);

            // [1] button name (fixed-width column for vertical alignment)
            TextView name = new TextView(this);
            name.setText(SHORTCUT_LABELS[i]);
            name.setTextColor(0xCCFFFFFF);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine(true);
            android.widget.LinearLayout.LayoutParams nameLp =
                    new android.widget.LinearLayout.LayoutParams(nameColW, WRAP);
            nameLp.setMarginEnd(dp(12));
            row.addView(name, nameLp);

            // [2] app icon (visible only when assigned and cached)
            ImageView icon = new ImageView(this);
            icon.setVisibility(View.GONE);
            android.widget.LinearLayout.LayoutParams iconLp =
                    new android.widget.LinearLayout.LayoutParams(dp(18), dp(18));
            iconLp.setMarginEnd(dp(8));
            row.addView(icon, iconLp);

            // [3] app label — flows left-aligned next to the icon. No
            //     flex spacer: this is the whole point of the redesign.
            TextView val = new TextView(this);
            val.setTextColor(0x88FFFFFF);
            val.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            val.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            val.setSingleLine(true);
            val.setEllipsize(TextUtils.TruncateAt.END);
            val.setMaxWidth(dp(130));
            row.addView(val, new android.widget.LinearLayout.LayoutParams(WRAP, WRAP));

            // Row width starts as WRAP_CONTENT; equalizeKeymapRowWidths()
            // resizes every row to the widest one after refreshKeymapRows()
            // re-binds the value text. This eliminates the ~70 dp of right-
            // side dead space the old fixed dp(252) layout had for short
            // app names while keeping selection pills perfectly aligned.
            android.widget.LinearLayout.LayoutParams rlp =
                    new android.widget.LinearLayout.LayoutParams(WRAP, WRAP);
            rlp.bottomMargin = dp(2);
            col.addView(row, rlp);
        }

        // ── Divider + manage-hidden-apps row ─────────────────────────────
        // Hairline separator that visually groups the 6 mappable keys above
        // and the navigation-style "manage hidden apps" entry below as two
        // distinct categories. 1 dp tall, low-opacity white, with breathing
        // room above/below so the slot pills don't touch it.
        View kmDivider = new View(this);
        kmDivider.setBackgroundColor(0x1AFFFFFF);
        android.widget.LinearLayout.LayoutParams kmDivLp =
                new android.widget.LinearLayout.LayoutParams(MATCH, Math.max(1, dp(1) / 2));
        kmDivLp.topMargin    = dp(4);
        kmDivLp.bottomMargin = dp(4);
        kmDivLp.leftMargin   = dp(6);
        kmDivLp.rightMargin  = dp(6);
        col.addView(kmDivider, kmDivLp);

        // Manage row mirrors the slot-row geometry (so the focus pill aligns
        // perfectly when the manage entry is selected) but only carries a
        // single label TextView — it's an action, not a binding.
        android.widget.LinearLayout manage = new android.widget.LinearLayout(this);
        manage.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        manage.setGravity(Gravity.CENTER_VERTICAL);
        manage.setPadding(dp(10), dp(7), dp(10), dp(7));
        android.graphics.drawable.GradientDrawable manageBg =
                new android.graphics.drawable.GradientDrawable();
        manageBg.setCornerRadius(dp(9));
        manageBg.setColor(Color.TRANSPARENT);
        manage.setBackground(manageBg);
        TextView manageLabel = new TextView(this);
        manageLabel.setText(R.string.keymap_manage_hidden);
        // Idle colour is a bit dimmer than slot-row labels (0xCCFFFFFF →
        // 0x99FFFFFF) so the row visibly reads as a different category
        // even before the user notices the divider. Selected colour
        // matches the slot rows for a consistent inverted-pill highlight.
        manageLabel.setTextColor(0x99FFFFFF);
        manageLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        manageLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        manageLabel.setSingleLine(true);
        manage.addView(manageLabel,
                new android.widget.LinearLayout.LayoutParams(WRAP, WRAP));
        android.widget.LinearLayout.LayoutParams manageLp =
                new android.widget.LinearLayout.LayoutParams(WRAP, WRAP);
        manageLp.bottomMargin = dp(2);
        col.addView(manage, manageLp);
        keymapManageRow = manage;

        // ── App picker view ─────────────────────────────────────
        android.widget.LinearLayout picker = new android.widget.LinearLayout(this);
        picker.setOrientation(android.widget.LinearLayout.VERTICAL);
        picker.setVisibility(View.GONE);
        picker.setClipChildren(false);
        picker.setClipToPadding(false);

        TextView pickerTitle = new TextView(this);
        pickerTitle.setTextColor(0xFFEFEFEF);
        pickerTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        pickerTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pickerTitle.setLetterSpacing(0.04f);
        pickerTitle.setPadding(dp(4), dp(2), dp(4), dp(8));
        picker.addView(pickerTitle);

        // Horizontal scroller, capped at ~52% of the screen so the picker
        // never balloons across the display on big TVs.
        android.widget.HorizontalScrollView hsv =
                new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int hsvW = Math.min(dp(540), Math.round(screenW * 0.52f));
        if (hsvW < dp(300)) hsvW = dp(300);
        android.widget.LinearLayout.LayoutParams hsvLp =
                new android.widget.LinearLayout.LayoutParams(hsvW, WRAP);
        picker.addView(hsv, hsvLp);

        android.widget.LinearLayout strip = new android.widget.LinearLayout(this);
        strip.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        strip.setPadding(dp(2), dp(4), dp(2), dp(4));
        // Allow chip scale-up to draw outside the strip's logical bounds.
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        hsv.addView(strip, new android.widget.FrameLayout.LayoutParams(WRAP, WRAP));

        // ── Hide-manager view ───────────────────────────────────────
        // Multi-select sibling of the picker: same horizontal chip strip,
        // same selection language (bright frosted-white pill + dark text
        // + 1.05x scale + auto-scroll). The only delta is that hidden
        // chips render their label with a strike-through line so the
        // hidden flag is legible in either selected or idle state — this
        // way the launcher uses ONE chip-picker idiom across both
        // single-select (button mapper) and multi-select (hide manager)
        // flows, instead of the previous vertical-list special case.
        android.widget.LinearLayout hideView = new android.widget.LinearLayout(this);
        hideView.setOrientation(android.widget.LinearLayout.VERTICAL);
        hideView.setVisibility(View.GONE);
        hideView.setClipChildren(false);
        hideView.setClipToPadding(false);

        TextView hideTitle = new TextView(this);
        hideTitle.setText(R.string.keymap_hide_title);
        hideTitle.setTextColor(0xFFEFEFEF);
        hideTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        hideTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hideTitle.setLetterSpacing(0.04f);
        hideTitle.setPadding(dp(4), dp(2), dp(4), dp(8));
        hideView.addView(hideTitle);

        // Horizontal scroller, capped at the same width as the picker so
        // both card sub-modes have a consistent footprint and the user's
        // muscle memory carries between them.
        android.widget.HorizontalScrollView hsv2 =
                new android.widget.HorizontalScrollView(this);
        hsv2.setHorizontalScrollBarEnabled(false);
        hsv2.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int hideW = Math.min(dp(540), Math.round(screenW * 0.52f));
        if (hideW < dp(300)) hideW = dp(300);
        android.widget.LinearLayout.LayoutParams hsv2Lp =
                new android.widget.LinearLayout.LayoutParams(hideW, WRAP);
        hideView.addView(hsv2, hsv2Lp);

        android.widget.LinearLayout hideStrip = new android.widget.LinearLayout(this);
        hideStrip.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        hideStrip.setPadding(dp(2), dp(4), dp(2), dp(4));
        // Allow chip scale-up to draw outside the strip's logical bounds
        // (matches the picker strip).
        hideStrip.setClipChildren(false);
        hideStrip.setClipToPadding(false);
        hsv2.setClipChildren(false);
        hsv2.setClipToPadding(false);
        hsv2.addView(hideStrip,
                new android.widget.FrameLayout.LayoutParams(WRAP, WRAP));

        card.addView(col);
        card.addView(picker);
        card.addView(hideView);

        // Top-right anchored. Exact margins are computed in showKeymapOverlay
        // so the card sits immediately below the mapper toolbar button and
        // visually "drops out" of it.
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        cardLp.gravity = Gravity.TOP | Gravity.END;
        ov.addView(card, cardLp);

        r.addView(ov);
        keymapOverlay     = ov;
        keymapCard        = card;
        keymapColumn      = col;
        keymapPickerView  = picker;
        keymapPickerTitle = pickerTitle;
        keymapPickerHsv   = hsv;
        keymapPickerStrip = strip;
        keymapHideView    = hideView;
        keymapHideTitle   = hideTitle;
        keymapHideHsv     = hsv2;
        keymapHideStrip   = hideStrip;
    }

    private void showKeymapOverlay() {
        if (keymapOverlay == null) buildKeymapOverlay();
        FrameLayout ko = keymapOverlay;
        final android.widget.LinearLayout card = keymapCard;
        if (ko == null || card == null) return;
        // Hide the focus ring — it belongs to the shelf, which is now
        // logically behind the overlay.
        RingView rv = ringView; if (rv != null) rv.setVisibility(View.INVISIBLE);
        // Always open in slot-list mode.
        keymapMode        = KEYMAP_MODE_SLOTS;
        keymapSelectedRow = 0;
        if (keymapPickerView != null) keymapPickerView.setVisibility(View.GONE);
        if (keymapHideView   != null) keymapHideView  .setVisibility(View.GONE);
        if (keymapColumn     != null) keymapColumn    .setVisibility(View.VISIBLE);
        refreshKeymapRows();

        // Anchor the card just below the mapper toolbar button so it reads
        // as a dropdown coming out of that icon. Right edge aligns with the
        // mapper button's right edge, top edge sits 4 dp below it.
        int topMargin   = dp(78);   // fallback if mapper button isn't laid out yet
        int rightMargin = dp(20);
        View mb = mapperBtnView;
        FrameLayout r = root;
        if (mb != null && r != null && mb.getWidth() > 0) {
            int[] mbLoc = new int[2];
            int[] rLoc  = new int[2];
            mb.getLocationOnScreen(mbLoc);
            r .getLocationOnScreen(rLoc);
            int mbBottomInRoot = mbLoc[1] - rLoc[1] + mb.getHeight();
            int mbRightInRoot  = mbLoc[0] - rLoc[0] + mb.getWidth();
            int rW = r.getWidth() > 0 ? r.getWidth() : screenW;
            topMargin   = mbBottomInRoot + dp(4);
            rightMargin = rW - mbRightInRoot;
            if (rightMargin < dp(8)) rightMargin = dp(8);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        lp.gravity     = Gravity.TOP | Gravity.END;
        lp.topMargin   = topMargin;
        lp.rightMargin = rightMargin;
        card.setLayoutParams(lp);

        ko.setVisibility(View.VISIBLE);
        ko.bringToFront();
        ko.requestFocus();

        // Drop-down animation: scale-up from the top-right corner with a
        // small downward translate so the card appears to "fall out" of the
        // mapper button. Pivot must be set after measure(), which happens
        // on the next layout pass — post() guarantees getWidth() is valid.
        card.animate().cancel();
        card.setAlpha(0f);
        card.setScaleX(0.94f); card.setScaleY(0.86f);
        card.setTranslationY(-dp(6));
        card.post(() -> {
            if (card != keymapCard) return;
            card.setPivotX(card.getWidth());     // top-right corner
            card.setPivotY(0f);
            card.animate()
                    .alpha(1f)
                    .scaleX(1f).scaleY(1f)
                    .translationY(0f)
                    .setDuration(160)
                    .setInterpolator(MENU_IN)
                    .start();
        });
    }

    private void hideKeymapOverlay() {
        final FrameLayout ko = keymapOverlay;
        final android.widget.LinearLayout card = keymapCard;
        if (ko == null) return;
        // Reset to slot-list mode so a future re-open is consistent
        // (avoids the case where Back from slot-list closes the overlay
        // while picker mode was still cached as the active sub-view).
        keymapMode = KEYMAP_MODE_SLOTS;
        if (keymapPickerView != null) keymapPickerView.setVisibility(View.GONE);
        if (keymapHideView   != null) keymapHideView  .setVisibility(View.GONE);
        if (keymapColumn     != null) keymapColumn    .setVisibility(View.VISIBLE);
        // Apply any pending hide toggles to the shelf — done exactly once
        // per overlay session, so a long editing session of N toggles
        // triggers exactly one shelf rebuild instead of N.
        if (keymapHideDirty) {
            keymapHideDirty = false;
            applyShelfApps(shelf);
        }
        if (card != null) {
            card.animate().cancel();
            card.animate()
                    .alpha(0f)
                    .scaleX(0.96f).scaleY(0.9f)
                    .translationY(-dp(4))
                    .setDuration(110)
                    .setInterpolator(MENU_OUT)
                    .withEndAction(() -> {
                        if (ko != keymapOverlay) return;
                        // Cancellation race guard — see hideContextMenu.
                        if (card.getAlpha() > 0.05f) return;
                        ko.setVisibility(View.GONE);
                        card.setAlpha(1f);
                        card.setScaleX(1f); card.setScaleY(1f);
                        card.setTranslationY(0f);
                    })
                    .start();
        } else {
            ko.setVisibility(View.GONE);
        }
        // Restore focus to the mapper button so the user lands back
        // where they triggered the overlay.
        View mb = mapperBtnView;
        if (mb != null) mb.requestFocus();
        else {
            View wb = wpBtnView;
            if (wb != null) wb.requestFocus();
        }
    }

    /** Repaint every slot row to reflect the current keyMap state and
     *  selection. Called on every navigation event in slot mode and on
     *  every commit from picker mode. Cheap — each row is a tiny
     *  LinearLayout with at most 4 children, all looked up by index.
     *
     *  Selection language: bright frosted-white pill + dark text, mirroring
     *  the toolbar buttons' "idle dark / focused white-frosted" pattern.
     *  This keeps a single visual vocabulary across the whole launcher.
     *
     *  The manage-hidden-apps row is treated as the (rows)th selectable
     *  entry — it lives below the key rows and a hairline divider, and
     *  shares the same selection pill/text-inversion treatment so the
     *  highlight reads consistently across both categories. */
    private void refreshKeymapRows() {
        android.widget.LinearLayout col = keymapColumn;
        if (col == null) return;
        int rows = SHORTCUT_LABELS.length;
        for (int i = 0; i < rows; i++) {
            View child = col.getChildAt(i);
            if (!(child instanceof android.widget.LinearLayout)) continue;
            android.widget.LinearLayout row = (android.widget.LinearLayout) child;
            boolean sel = (i == keymapSelectedRow);
            int kc = SHORTCUT_KEYCODES[i];
            String pkg = keyMap.get(kc);

            // Children inside row: tag(0), name(1), icon(2), val(3)
            TextView name  = (TextView)  row.getChildAt(1);
            ImageView icon = (ImageView) row.getChildAt(2);
            TextView val   = (TextView)  row.getChildAt(3);

            if (pkg == null) {
                val.setText(R.string.keymap_not_assigned);
                icon.setVisibility(View.GONE);
                icon.setImageDrawable(null);
            } else {
                AppInfo a = findAppByPackage(pkg);
                val.setText(a != null ? a.label : pkg);
                Bitmap bmp = (iconCache != null) ? iconCache.get(pkg) : null;
                if (bmp != null) { icon.setImageBitmap(bmp); icon.setVisibility(View.VISIBLE); }
                else             { icon.setImageDrawable(null); icon.setVisibility(View.GONE); }
            }

            // Apple-TV inversion: selected row becomes a bright plate with
            // dark text; idle rows are transparent with light text. The
            // same colour ramp as the toolbar buttons (idle 0xCCFFFFFF,
            // focused 0xFF111114).
            if (sel) {
                name.setTextColor(0xFF111114);
                val .setTextColor(pkg == null ? 0xAA111114 : 0xFF111114);
            } else {
                name.setTextColor(0xCCFFFFFF);
                val .setTextColor(pkg == null ? 0x66FFFFFF : 0xC0FFFFFF);
            }

            android.graphics.drawable.Drawable rbg = row.getBackground();
            if (rbg instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) rbg)
                        .setColor(sel ? 0xFFEFEFEF : Color.TRANSPARENT);
            }
        }

        // Paint the manage row separately — it's a different category
        // (an action, not a key binding) so the styling pipeline above
        // doesn't apply, but the selection language must match.
        android.widget.LinearLayout mr = keymapManageRow;
        if (mr != null) {
            boolean sel = (keymapSelectedRow == rows);
            View first = mr.getChildAt(0);
            if (first instanceof TextView) {
                ((TextView) first).setTextColor(sel ? 0xFF111114 : 0x99FFFFFF);
            }
            android.graphics.drawable.Drawable mrBg = mr.getBackground();
            if (mrBg instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) mrBg)
                        .setColor(sel ? 0xFFEFEFEF : Color.TRANSPARENT);
            }
        }

        // Equalise row widths to the widest row so the menu is exactly as
        // wide as it needs to be (no dead space) AND the selection pill
        // aligns across all rows. Only re-measure when bindings/labels
        // might have changed — selection-only repaints (every UP/DOWN
        // press) skip this entirely, saving 7 view-measure passes per
        // navigation event.
        if (keymapRowsNeedEqualize) {
            equalizeKeymapRowWidths(col, rows);
            keymapRowsNeedEqualize = false;
        }
    }

    /** Measure every selectable row in the slot column and snap them all to
     *  the widest measured width. Iterates every LinearLayout child (the
     *  6 key rows + the manage row) and skips non-LinearLayout children
     *  like the hairline divider — so the divider is never counted as a
     *  measurable row. Called from refreshKeymapRows() after every
     *  binding/text change so the menu auto-fits the longest app label. */
    private void equalizeKeymapRowWidths(android.widget.LinearLayout col, int rows) {
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int n = col.getChildCount();
        int max = 0;
        for (int i = 0; i < n; i++) {
            View child = col.getChildAt(i);
            if (!(child instanceof android.widget.LinearLayout)) continue;
            // Force measurement against current content. We pass UNSPECIFIED
            // on width so each row reports its natural intrinsic width.
            ViewGroup.LayoutParams clp = child.getLayoutParams();
            int prevW = clp != null ? clp.width : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (clp != null && clp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                clp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                child.setLayoutParams(clp);
            }
            child.measure(spec, spec);
            int w = child.getMeasuredWidth();
            if (w > max) max = w;
            if (clp != null && prevW != ViewGroup.LayoutParams.WRAP_CONTENT) {
                clp.width = prevW;
                child.setLayoutParams(clp);
            }
        }
        if (max <= 0) return;
        for (int i = 0; i < n; i++) {
            View child = col.getChildAt(i);
            if (!(child instanceof android.widget.LinearLayout)) continue;
            ViewGroup.LayoutParams clp = child.getLayoutParams();
            if (clp == null || clp.width == max) continue;
            clp.width = max;
            child.setLayoutParams(clp);
        }
    }

    // ── App picker mode ──────────────────────────────────────────

    /** Enter picker mode for the slot at rowIdx. Hides the slot list,
     *  rebuilds the chip strip from the current appList (only when its
     *  size has changed since the last build — chips are cheap but
     *  rebuilding ~50 of them on every reopen is needless GC pressure),
     *  and selects the chip matching the current binding (or "None" if
     *  unassigned). */
    private void enterAppPicker(int rowIdx) {
        if (keymapColumn == null || keymapPickerView == null) return;
        keymapPickerSlotRow = rowIdx;
        TextView pt = keymapPickerTitle;
        if (pt != null) {
            pt.setText(getString(R.string.keymap_pick_app_for, SHORTCUT_LABELS[rowIdx]));
        }
        if (keymapPickerBuiltSize != appList.size()) {
            rebuildPickerChips();
            keymapPickerBuiltSize = appList.size();
        }
        // Pre-select the chip matching the current binding so left/right
        // navigates from where the user is, not always from the start.
        int kc = SHORTCUT_KEYCODES[rowIdx];
        String pkg = keyMap.get(kc);
        int idx = 0; // 0 = "None" sentinel
        if (pkg != null) {
            for (int i = 0; i < appList.size(); i++) {
                if (appList.get(i).packageName.equals(pkg)) { idx = i + 1; break; }
            }
        }
        keymapPickerIdx     = idx;
        keymapPickerLastIdx = -1;   // force a full repaint on first refresh
        keymapMode          = KEYMAP_MODE_PICKER;
        keymapColumn.setVisibility(View.GONE);
        keymapPickerView.setVisibility(View.VISIBLE);
        refreshKeymapPicker();
        // Initial scroll happens after layout — post() so getLeft() of the
        // selected chip is non-zero.
        final android.widget.HorizontalScrollView hsv = keymapPickerHsv;
        if (hsv != null) hsv.post(this::scrollPickerToSelection);
    }

    /** Cancel the picker without writing anything — return to slot list. */
    private void exitAppPicker() {
        keymapMode = KEYMAP_MODE_SLOTS;
        if (keymapPickerView != null) keymapPickerView.setVisibility(View.GONE);
        if (keymapColumn     != null) keymapColumn    .setVisibility(View.VISIBLE);
    }

    /** Commit the highlighted chip as the binding for the current slot row,
     *  save to SharedPreferences, refresh the slot-list display, and return
     *  to slot mode. Idx 0 = "None" → delete the binding. */
    private void commitAppPickerSelection() {
        int kc = SHORTCUT_KEYCODES[keymapPickerSlotRow];
        if (keymapPickerIdx <= 0) {
            keyMap.delete(kc);
        } else {
            int appIdx = keymapPickerIdx - 1;
            if (appIdx >= 0 && appIdx < appList.size()) {
                keyMap.put(kc, appList.get(appIdx).packageName);
            }
        }
        saveKeyMap();
        // Slot-row text just changed — schedule a re-measure on the next
        // refresh so equalised widths reflect the new app label.
        keymapRowsNeedEqualize = true;
        refreshKeymapRows();
        exitAppPicker();
    }

    /** Rebuild the chip strip from the current appList. Called only when
     *  the app list size has changed since the last build (see enterAppPicker),
     *  not on every reopen. Each chip is a small horizontal LinearLayout
     *  with an optional icon and a label. */
    private void rebuildPickerChips() {
        android.widget.LinearLayout strip = keymapPickerStrip;
        if (strip == null) return;
        strip.removeAllViews();
        // First chip is the "Not assigned" sentinel — always present so the
        // user can clear a binding from the picker without a separate gesture.
        addPickerChip(strip, getString(R.string.keymap_not_assigned), null, true);
        for (int i = 0; i < appList.size(); i++) {
            AppInfo a = appList.get(i);
            Bitmap b = (iconCache != null) ? iconCache.get(a.packageName) : null;
            addPickerChip(strip, a.label, b, false);
        }
    }

    private void addPickerChip(android.widget.LinearLayout strip,
                               String label, Bitmap icon, boolean isNone) {
        android.widget.LinearLayout chip = new android.widget.LinearLayout(this);
        chip.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), dp(7), dp(12), dp(7));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(10));
        chip.setBackground(bg);

        // Icon slot: present for app chips (even if the bitmap isn't cached
        // yet, keeping a hidden ImageView keeps chip widths consistent).
        if (!isNone) {
            ImageView iv = new ImageView(this);
            if (icon != null) { iv.setImageBitmap(icon); iv.setVisibility(View.VISIBLE); }
            else              { iv.setVisibility(View.GONE); }
            android.widget.LinearLayout.LayoutParams ivLp =
                    new android.widget.LinearLayout.LayoutParams(dp(20), dp(20));
            ivLp.setMarginEnd(dp(7));
            chip.addView(iv, ivLp);
        }

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.create(isNone ? "sans-serif" : "sans-serif-medium",
                Typeface.NORMAL));
        tv.setTextColor(0x99FFFFFF);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(150));
        chip.addView(tv);

        android.widget.LinearLayout.LayoutParams clp =
                new android.widget.LinearLayout.LayoutParams(WRAP, WRAP);
        clp.setMarginEnd(dp(7));
        strip.addView(chip, clp);
    }

    /** Repaint chip styles to reflect the current selection and scroll the
     *  selected chip into the viewport.
     *
     *  Two paint modes:
     *    • prev < 0 → first paint after entering picker mode. We must do a
     *      FULL sweep here, instantly resetting every chip's pill colour /
     *      text colour / scale to the idle state and then highlighting only
     *      the current one. This is the fix for the "2 or 3 selectors at
     *      once" bug: previous picker sessions left their selected chip
     *      scaled-up + bright-pilled, and the cheap two-chip diff path
     *      below could not undo those stale highlights.
     *    • prev ≥ 0 → in-session navigation. Only the two chips that
     *      changed are touched (animated), keeping per-keypress work O(1). */
    private void refreshKeymapPicker() {
        android.widget.LinearLayout strip = keymapPickerStrip;
        if (strip == null) return;
        int n = strip.getChildCount();
        if (n == 0) return;
        int prev = keymapPickerLastIdx;
        int curr = keymapPickerIdx;
        if (prev < 0) {
            // Full reset — instant (no animation) so we don't trigger N
            // simultaneous spring animations across the whole strip.
            for (int i = 0; i < n; i++) {
                if (i == curr) continue;
                paintPickerChip(strip.getChildAt(i), false, false);
            }
            if (curr >= 0 && curr < n) {
                // Animate just the new selection in for a subtle pop.
                paintPickerChip(strip.getChildAt(curr), true, true);
            }
        } else if (prev != curr) {
            if (prev < n) paintPickerChip(strip.getChildAt(prev), false, true);
            if (curr >= 0 && curr < n) paintPickerChip(strip.getChildAt(curr), true, true);
        }
        // prev == curr (and ≥ 0): nothing visual changed — skip work.
        keymapPickerLastIdx = curr;
        scrollPickerToSelection();
    }

    private void paintPickerChip(View chip, boolean sel, boolean animate) {
        if (chip == null) return;
        // Apple-TV inverted pill: selected chip is bright frosted-white with
        // dark text; idle chips are transparent with light text. Same
        // language as the slot rows and the toolbar buttons.
        android.graphics.drawable.Drawable bgd = chip.getBackground();
        if (bgd instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) bgd)
                    .setColor(sel ? 0xFFEFEFEF : Color.TRANSPARENT);
        }
        if (chip instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout cl = (android.widget.LinearLayout) chip;
            View last = cl.getChildAt(cl.getChildCount() - 1);
            if (last instanceof TextView) {
                ((TextView) last).setTextColor(sel ? 0xFF111114 : 0x99FFFFFF);
            }
        }
        chip.animate().cancel();
        float targetScale = sel ? 1.05f : 1f;
        if (animate) {
            chip.animate()
                    .scaleX(targetScale).scaleY(targetScale)
                    .setDuration(140)
                    .setInterpolator(FOCUS_EASE)
                    .start();
        } else {
            chip.setScaleX(targetScale);
            chip.setScaleY(targetScale);
        }
    }

    /** Auto-scroll the horizontal strip so the selected chip is visible
     *  with a small margin. Smooth-scroll keeps the focus motion premium. */
    private void scrollPickerToSelection() {
        android.widget.HorizontalScrollView hsv = keymapPickerHsv;
        android.widget.LinearLayout strip = keymapPickerStrip;
        if (hsv == null || strip == null) return;
        if (keymapPickerIdx < 0 || keymapPickerIdx >= strip.getChildCount()) return;
        View chip = strip.getChildAt(keymapPickerIdx);
        if (chip == null) return;
        if (chip.getWidth() == 0) {
            // Layout hasn't run yet — try again next frame.
            hsv.post(this::scrollPickerToSelection);
            return;
        }
        int chipLeft  = chip.getLeft();
        int chipRight = chip.getRight();
        int viewLeft  = hsv.getScrollX();
        int viewRight = viewLeft + hsv.getWidth();
        int margin    = dp(40);
        if (chipLeft < viewLeft + margin) {
            hsv.smoothScrollTo(Math.max(0, chipLeft - margin), 0);
        } else if (chipRight > viewRight - margin) {
            hsv.smoothScrollTo(chipRight - hsv.getWidth() + margin, 0);
        }
    }

    /** D-pad navigation inside the overlay. Routes by current mode:
     *
     *  SLOT mode:
     *    UP/DOWN — move slot selection
     *    OK      — enter picker for the selected slot
     *    BACK    — close the overlay
     *
     *  PICKER mode:
     *    LEFT/RIGHT — move chip selection (auto-scrolls)
     *    OK         — commit the chip and return to slot mode
     *    BACK       — cancel and return to slot mode
     *
     *  All other keys are swallowed so unmapped remote buttons don't
     *  bleed through and trigger their (potentially mapped) shortcut
     *  while the user is configuring shortcuts. */
    private boolean handleKeymapOverlayKey(KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;  // eat KEY_UP for handled keys
        int kc = ev.getKeyCode();
        if (keymapMode == KEYMAP_MODE_PICKER) return handleKeymapPickerKey(kc);
        if (keymapMode == KEYMAP_MODE_HIDE)   return handleKeymapHideKey(kc);
        return handleKeymapSlotsKey(kc);
    }

    private boolean handleKeymapSlotsKey(int kc) {
        // 6 key bindings + 1 manage-hidden-apps action row.
        int rows = SHORTCUT_LABELS.length + 1;
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:
                keymapSelectedRow = (keymapSelectedRow - 1 + rows) % rows;
                refreshKeymapRows(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                keymapSelectedRow = (keymapSelectedRow + 1) % rows;
                refreshKeymapRows(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (keymapSelectedRow == SHORTCUT_LABELS.length) enterHideManager();
                else                                            enterAppPicker(keymapSelectedRow);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                hideKeymapOverlay(); return true;
        }
        // Swallow everything else — see method javadoc.
        return true;
    }

    private boolean handleKeymapPickerKey(int kc) {
        android.widget.LinearLayout strip = keymapPickerStrip;
        int n = strip == null ? 0 : strip.getChildCount();
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (n > 0) keymapPickerIdx = Math.max(0, keymapPickerIdx - 1);
                refreshKeymapPicker(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (n > 0) keymapPickerIdx = Math.min(n - 1, keymapPickerIdx + 1);
                refreshKeymapPicker(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                commitAppPickerSelection(); return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                exitAppPicker(); return true;
        }
        // Swallow everything else — see handleKeymapOverlayKey javadoc.
        return true;
    }

    // ── Hide-manager mode ────────────────────────────────────────────────
    //
    // Vertical, OK-toggleable list of every installed app inside the same
    // card that hosts the slot list and picker. Rows are built once per
    // appList-size change (see keymapHideBuiltSize) and reused across
    // opens. Toggling repaints only the check mark of the affected row;
    // the shelf is re-filtered exactly once when the overlay is closed
    // (see hideKeymapOverlay) so a heavy session of multiple toggles
    // doesn't trigger N shelf rebuilds.

    /** Enter hide mode — swap the slot list for the hide chip strip,
     *  rebuild chips on first open / after a package change, and pre-select
     *  the first chip. Mirrors enterAppPicker exactly so the UX feels
     *  identical between the two card sub-modes. */
    private void enterHideManager() {
        if (keymapColumn == null || keymapHideView == null) return;
        if (keymapHideBuiltSize != appList.size()) {
            buildHideChips();
            keymapHideBuiltSize = appList.size();
        }
        int n = appList.size();
        keymapHideIdx     = n > 0 ? 0 : -1;
        keymapHideLastIdx = -1;       // force a full repaint on first refresh
        keymapMode        = KEYMAP_MODE_HIDE;
        keymapColumn   .setVisibility(View.GONE);
        if (keymapPickerView != null) keymapPickerView.setVisibility(View.GONE);
        keymapHideView .setVisibility(View.VISIBLE);
        refreshHideStrip();
        // Initial scroll happens after layout — post() so getLeft() of the
        // selected chip is non-zero.
        final android.widget.HorizontalScrollView hsv = keymapHideHsv;
        if (hsv != null) hsv.post(this::scrollHideToSelection);
    }

    /** Cancel the hide manager and return to slot mode. The shelf is
     *  re-filtered only on overlay close (see hideKeymapOverlay) so
     *  exiting hide mode without closing the overlay leaves the shelf
     *  alone — cheap, and avoids a flicker behind the dim. */
    private void exitHideManager() {
        keymapMode = KEYMAP_MODE_SLOTS;
        if (keymapHideView != null) keymapHideView.setVisibility(View.GONE);
        if (keymapColumn   != null) keymapColumn  .setVisibility(View.VISIBLE);
        // Land focus back on the manage-row entry the user came from so
        // a reopen of the manager (or another action) is one keypress away.
        keymapSelectedRow = SHORTCUT_LABELS.length;
        refreshKeymapRows();
    }

    /** Build the hide-manager chip strip from the current appList. Called
     *  only when the app list size has changed since the last build, or
     *  when a package broadcast invalidates the cache. Each chip is the
     *  same horizontal LinearLayout shape used by the keymap picker:
     *  [icon (20dp)] [label]. The hidden flag is encoded entirely as a
     *  paint flag on the label TextView (Paint.STRIKE_THRU_TEXT_FLAG),
     *  so toggling state is a single setPaintFlags() call — no view
     *  insert/remove churn, no visibility flip, no allocation. */
    private void buildHideChips() {
        android.widget.LinearLayout strip = keymapHideStrip;
        if (strip == null) return;
        strip.removeAllViews();
        for (int i = 0; i < appList.size(); i++) {
            AppInfo a = appList.get(i);
            Bitmap b = (iconCache != null) ? iconCache.get(a.packageName) : null;
            addHideChip(strip, a.label, b);
        }
    }

    /** Mirror of {@link #addPickerChip} for the hide-manager strip. The
     *  geometry, paddings, idle colours and pill background match exactly
     *  so the two strips are visually indistinguishable in idle state.
     *  Hidden state is applied later by {@link #paintHideChip} as a
     *  strike-through flag on the label paint. */
    private void addHideChip(android.widget.LinearLayout strip,
                             String label, Bitmap icon) {
        android.widget.LinearLayout chip = new android.widget.LinearLayout(this);
        chip.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), dp(7), dp(12), dp(7));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(10));
        chip.setBackground(bg);

        // Icon slot — always present so chip widths stay consistent
        // whether or not the bitmap has been cached yet.
        ImageView iv = new ImageView(this);
        if (icon != null) { iv.setImageBitmap(icon); iv.setVisibility(View.VISIBLE); }
        else              { iv.setVisibility(View.GONE); }
        android.widget.LinearLayout.LayoutParams ivLp =
                new android.widget.LinearLayout.LayoutParams(dp(20), dp(20));
        ivLp.setMarginEnd(dp(7));
        chip.addView(iv, ivLp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(0x99FFFFFF);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(150));
        chip.addView(tv);

        android.widget.LinearLayout.LayoutParams clp =
                new android.widget.LinearLayout.LayoutParams(WRAP, WRAP);
        clp.setMarginEnd(dp(7));
        strip.addView(chip, clp);
    }

    /** Repaint chip styles to reflect current selection AND hidden state.
     *  Two paint modes (same dispatch pattern as refreshKeymapPicker):
     *    • prev < 0 → first paint after entering hide mode. Full sweep:
     *      every chip's pill colour / text colour / strike-through / scale.
     *    • prev ≥ 0 → in-session navigation. Only the two chips that
     *      changed selection are repainted. The toggled chip's strike-
     *      through is updated separately by {@link #toggleSelectedHide}. */
    private void refreshHideStrip() {
        android.widget.LinearLayout strip = keymapHideStrip;
        if (strip == null) return;
        int n = strip.getChildCount();
        if (n == 0) return;
        int prev = keymapHideLastIdx;
        int curr = keymapHideIdx;
        if (prev < 0) {
            // Full sweep — paint every chip's idle state, then highlight
            // the current one. We have to walk every chip here because the
            // strike-through paint flag may have stuck from a prior open.
            for (int i = 0; i < n; i++) {
                boolean hidden = i < appList.size()
                        && hiddenApps.contains(appList.get(i).packageName);
                paintHideChip(strip.getChildAt(i), i == curr, hidden, false);
            }
        } else if (prev != curr) {
            if (prev < n) {
                boolean ph = prev < appList.size()
                        && hiddenApps.contains(appList.get(prev).packageName);
                paintHideChip(strip.getChildAt(prev), false, ph, true);
            }
            if (curr >= 0 && curr < n) {
                boolean ch = curr < appList.size()
                        && hiddenApps.contains(appList.get(curr).packageName);
                paintHideChip(strip.getChildAt(curr), true, ch, true);
            }
        }
        keymapHideLastIdx = curr;
        scrollHideToSelection();
    }

    /** Single source of truth for a hide-chip's visual state. Matches the
     *  keymap-picker chip paint exactly for selection / scale / pill
     *  background; adds strike-through on the label when hidden so the
     *  flag is legible in both selected and idle pills. */
    private void paintHideChip(View chip, boolean sel, boolean hidden, boolean animate) {
        if (chip == null) return;
        android.graphics.drawable.Drawable bgd = chip.getBackground();
        if (bgd instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) bgd)
                    .setColor(sel ? 0xFFEFEFEF : Color.TRANSPARENT);
        }
        if (chip instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout cl = (android.widget.LinearLayout) chip;
            View last = cl.getChildAt(cl.getChildCount() - 1);
            if (last instanceof TextView) {
                TextView tv = (TextView) last;
                // Selected → dark text on bright pill; idle → light text.
                // Hidden idle is dimmer than visible idle (0x66FFFFFF vs
                // 0x99FFFFFF) so the strike-through reads as "muted /
                // hidden" at a glance. Selected hidden stays at full dark
                // text — the strike-through still distinguishes it.
                if (sel) {
                    tv.setTextColor(hidden ? 0xFF111114 : 0xFF111114);
                } else {
                    tv.setTextColor(hidden ? 0x66FFFFFF : 0x99FFFFFF);
                }
                int flags = tv.getPaintFlags();
                if (hidden) flags |=  Paint.STRIKE_THRU_TEXT_FLAG;
                else        flags &= ~Paint.STRIKE_THRU_TEXT_FLAG;
                tv.setPaintFlags(flags);
            }
        }
        chip.animate().cancel();
        float targetScale = sel ? 1.05f : 1f;
        if (animate) {
            chip.animate()
                    .scaleX(targetScale).scaleY(targetScale)
                    .setDuration(140)
                    .setInterpolator(FOCUS_EASE)
                    .start();
        } else {
            chip.setScaleX(targetScale);
            chip.setScaleY(targetScale);
        }
    }

    /** Toggle the hidden flag for the currently-selected chip. Saves the
     *  pref synchronously, repaints just that chip's strike-through (no
     *  full sweep), and marks the shelf as dirty so it gets re-filtered
     *  when the overlay closes. */
    private void toggleSelectedHide() {
        android.widget.LinearLayout strip = keymapHideStrip;
        if (strip == null) return;
        int idx = keymapHideIdx;
        if (idx < 0 || idx >= appList.size()) return;
        String pkg = appList.get(idx).packageName;
        boolean nowHidden;
        if (hiddenApps.contains(pkg)) { hiddenApps.remove(pkg); nowHidden = false; }
        else                          { hiddenApps.add(pkg);    nowHidden = true;  }
        saveHiddenApps();
        keymapHideDirty = true;
        // Cheap repaint: the selected chip retains its bright pill, only
        // the strike-through paint flag changes.
        if (idx < strip.getChildCount()) {
            View chip = strip.getChildAt(idx);
            if (chip instanceof android.widget.LinearLayout) {
                android.widget.LinearLayout cl = (android.widget.LinearLayout) chip;
                View last = cl.getChildAt(cl.getChildCount() - 1);
                if (last instanceof TextView) {
                    TextView tv = (TextView) last;
                    int flags = tv.getPaintFlags();
                    if (nowHidden) flags |=  Paint.STRIKE_THRU_TEXT_FLAG;
                    else           flags &= ~Paint.STRIKE_THRU_TEXT_FLAG;
                    tv.setPaintFlags(flags);
                }
            }
        }
    }

    /** Auto-scroll the horizontal strip so the selected chip stays in
     *  view with a small margin. Identical to scrollPickerToSelection. */
    private void scrollHideToSelection() {
        android.widget.HorizontalScrollView hsv = keymapHideHsv;
        android.widget.LinearLayout strip = keymapHideStrip;
        if (hsv == null || strip == null) return;
        if (keymapHideIdx < 0 || keymapHideIdx >= strip.getChildCount()) return;
        View chip = strip.getChildAt(keymapHideIdx);
        if (chip == null) return;
        if (chip.getWidth() == 0) {
            hsv.post(this::scrollHideToSelection);
            return;
        }
        int chipLeft  = chip.getLeft();
        int chipRight = chip.getRight();
        int viewLeft  = hsv.getScrollX();
        int viewRight = viewLeft + hsv.getWidth();
        int margin    = dp(40);
        if (chipLeft < viewLeft + margin) {
            hsv.smoothScrollTo(Math.max(0, chipLeft - margin), 0);
        } else if (chipRight > viewRight - margin) {
            hsv.smoothScrollTo(chipRight - hsv.getWidth() + margin, 0);
        }
    }

    private boolean handleKeymapHideKey(int kc) {
        android.widget.LinearLayout strip = keymapHideStrip;
        int n = strip == null ? 0 : strip.getChildCount();
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (n > 0) keymapHideIdx = Math.max(0, keymapHideIdx - 1);
                refreshHideStrip(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (n > 0) keymapHideIdx = Math.min(n - 1, keymapHideIdx + 1);
                refreshHideStrip(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                toggleSelectedHide(); return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                exitHideManager(); return true;
        }
        // Swallow everything else — see handleKeymapOverlayKey javadoc.
        return true;
    }

    private void preWarmIcon(AppInfo app) {
        String key = app.packageName;
        if (iconCache.get(key) != null || iconInflight.containsKey(key)) return;
        // Register an empty waiter list — visible cells self-register via loadIconAsync/bind().
        // The redundant attached-cell scan was removed: it caused double setIconBitmap delivery
        // when a cell was already in the waiters list AND matched the attached scan.
        List<RecyclingShelfView.CellView> waiters = new ArrayList<>(2);
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
                    List<RecyclingShelfView.CellView> pending = iconInflight.remove(key);
                    if (pending == null || fb == null) return;
                    for (int i = 0, n = pending.size(); i < n; i++) {
                        RecyclingShelfView.CellView cell = pending.get(i);
                        // Guard: only deliver to a cell that is still attached
                        // and bound to this package. A cell that's been recycled
                        // back to the pool has visibility GONE and a null
                        // boundApp — delivering would invalidate a hidden view
                        // for nothing.
                        if (cell.getVisibility() == View.VISIBLE
                                && key.equals(cell.boundApp != null ? cell.boundApp.packageName : null))
                            cell.setIconBitmap(fb);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { iconInflight.remove(key); }
    }

    private void loadIconAsync(AppInfo app, RecyclingShelfView.CellView target) {
        String key = app.packageName;
        Bitmap cached = iconCache.get(key);
        if (cached != null) { target.setIconBitmap(cached); return; }
        List<RecyclingShelfView.CellView> waiters = iconInflight.get(key);
        if (waiters != null) {
            if (!waiters.contains(target)) waiters.add(target); // avoid duplicate registration
            return;
        }
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
                    for (RecyclingShelfView.CellView cell : fw) {
                        // Same guard as preWarmIcon: only deliver if the cell
                        // is still on screen and still bound to this package.
                        if (cell.getVisibility() == View.VISIBLE
                                && key.equals(cell.boundApp != null ? cell.boundApp.packageName : null))
                            cell.setIconBitmap(fb);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { iconInflight.remove(key); }
    }

    /** Single-line entry point — the entire icon-bitmap pipeline lives in
     *  {@link IconRenderer}. The wrapper exists so the activity's call sites
     *  (preWarmIcon / loadIconAsync) keep their {@code processIcon(d)}
     *  signature unchanged: dp(ICON_DP) is computed here so {@link IconRenderer}
     *  itself stays Activity-free. */
    private Bitmap processIcon(Drawable d) {
        return IconRenderer.process(d, dp(ICON_DP));
    }

    private void positionRing(View cell) {
        RingView rv = ringView; FrameLayout r = root;
        if (rv == null || r == null || !cell.isAttachedToWindow()) return;
        if (cell.getWidth() == 0) return;
        cell.getLocationOnScreen(ringCellLoc); r.getLocationOnScreen(ringRootLoc);

        // Cells animate to scaleX/Y = FOCUS_SCALE on focus around the centre
        // pivot. getLocationOnScreen returns the post-transform VISUAL
        // top-left, which already includes the scale-induced offset. We
        // project the icon centre via the cell's scale to find the visual
        // icon centre:
        //   visualIconCx = visualTopLeftX + cell.getWidth() * scaleX / 2
        //   visualIconCy = visualTopLeftY + cachedIcyOffset * scaleY
        float sx = cell.getScaleX();
        float sy = cell.getScaleY();
        float cx = (ringCellLoc[0] - ringRootLoc[0]) + cell.getWidth() * sx / 2f;
        float cy = (ringCellLoc[1] - ringRootLoc[1]) + cachedIcyOffset * sy;
        // Keep the ring's own scale in lockstep with the cell so its radius
        // hugs the focused icon — a fixed-size ring sat INSIDE the focused
        // icon by ~2.5dp, which read as misalignment.
        rv.setScaleX(sx);
        rv.setScaleY(sy);
        float half = ringLayoutSize / 2f;
        rv.setX(cx - half); rv.setY(cy - half);
        if (rv.getVisibility() != View.VISIBLE) rv.setVisibility(View.VISIBLE);
        // No invalidate() — setX/setY/setScale already mark the view dirty.
    }

    /** Synchronously repositions the ring over the drag-target cell.
     *  Must be called AFTER layout/scroll changes have been applied. */
    private void updateRingAfterMove() {
        RecyclingShelfView s = shelf;
        if (s == null || !s.reorderMode) return;
        RecyclingShelfView.CellView cv = s.attached.get(s.dragIndex);
        if (cv != null && cv.isAttachedToWindow() && cv.getWidth() > 0) positionRing(cv);
    }

    private void startClock() {
        if (!clockRunning) {
            clockRunning = true;
            // Fresh start — reset the minute tracker so the very first paint
            // uses the no-fade cold-render path (no spurious pulse on entry).
            clockFmt.reset();
            long now = System.currentTimeMillis();
            tickClock(now);
            uiHandler.postDelayed(clockTick, ClockFormatter.nextMinuteDelay(now));
        }
    }

    private void stopClock() { clockRunning = false; uiHandler.removeCallbacks(clockTick); }

    // Wallpaper subsystem — the entire bitmap-loading / cross-fade / decode
    // state machine lives in {@link WallpaperController}. The controller is
    // built in {@link #buildLayout()} after the two stacked ImageViews are
    // attached, and torn down in {@link #onDestroy()}. Activity now only
    // forwards the four lifecycle / interaction calls below.

    private void loadWallpaper() {
        if (wallpaperCtl != null) wallpaperCtl.loadStored();
    }

    private void loadSystemWallpaper() {
        if (wallpaperCtl != null) wallpaperCtl.loadSystem();
    }

    private void applyWallpaperFromUri(Uri uri) {
        if (wallpaperCtl != null) wallpaperCtl.applyFromUri(uri);
    }

    @SuppressWarnings("deprecation")
    private void openStoragePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_PICK_WP); }
        catch (Exception e) { showToast(getString(R.string.toast_no_file_picker)); }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_WP && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException e) { showToast(getString(R.string.toast_wallpaper_no_permission)); return; }
                if (wallpaperCtl != null) {
                    wallpaperCtl.resetUserLoadingGuard();
                    wallpaperCtl.applyFromUri(uri);
                }
            }
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

    /** Resolve the user-visible labels for every remappable remote key from
     *  string resources. Called once from onCreate; the resulting array is
     *  read on every keymap-overlay open and on every picker-title rebuild. */
    private void initShortcutLabels() {
        SHORTCUT_LABELS[0] = getString(R.string.key_red);
        SHORTCUT_LABELS[1] = getString(R.string.key_green);
        SHORTCUT_LABELS[2] = getString(R.string.key_yellow);
        SHORTCUT_LABELS[3] = getString(R.string.key_blue);
        SHORTCUT_LABELS[4] = getString(R.string.key_menu);
        SHORTCUT_LABELS[5] = getString(R.string.key_subtitle);
    }

    private void initCaches() {
        int memMb   = ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryClass();
        int cacheMb = Math.min(memMb / 8, 16);
        iconCache = new LruCache<String, Bitmap>(cacheMb * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        };
        int cores = Runtime.getRuntime().availableProcessors();
        iconExecutor = new ThreadPoolExecutor(Math.max(2, cores - 1), cores, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.DiscardOldestPolicy());
        // Wallpaper executor is now owned by {@link WallpaperController}
        // (constructed later inside {@link #buildLayout()}). The activity
        // no longer manages the wallpaper-thread lifecycle directly.
        appExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardPolicy());
    }

    private void hideSystemUI() {
        Window w = getWindow();
        // Modern WindowInsetsController path. minSdk is 30 (R), so the
        // legacy SystemUiVisibility branch is unreachable and removed.
        w.setDecorFitsSystemWindows(false);
        WindowInsetsController c = w.getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private int dp(int v) { return Math.round(v * density); }

    private void showToast(String msg) {
        if (currentToast != null) currentToast.cancel();
        currentToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        currentToast.show();
    }
}
