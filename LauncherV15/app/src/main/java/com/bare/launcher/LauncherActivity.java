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
import android.content.res.Resources;
import android.text.format.DateFormat;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
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
import android.view.ViewOutlineProvider;
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

    private static final int    ICON_DP        = 80;   // round chip/list icon cache size
    private static final int    RING_STROKE_DP = 3;
    // v1.5.0 Apple-TV style 5:3 banner tiles, sized dynamically from the
    // screen width so exactly 6 fit per row on ANY TV (see computeTileDims()).
    // Volatile: written on the UI thread (onCreate / config change), read on
    // the icon executor inside loadBannerBlocking.
    private volatile int        tileWpx      = 0;   // banner / cell width
    private volatile int        bannerHpx    = 0;   // banner height (5:3)
    private volatile int        tileCornerPx = 0;   // corner radius
    private volatile int        cellHpx      = 0;   // cell height (banner + focused label)
    /** Hide-apps vertical list: per-row height and how many rows are visible
     *  before the list scrolls (v1.5.0 redesign). */
    private static final int    HIDE_ROW_H_DP    = 36;
    private static final int    HIDE_VISIBLE_ROWS = 6;
    // Clock cadence lives in {@link ClockFormatter#nextMinuteDelay} now.
    // The launcher schedules ticks aligned to the minute boundary so a
    // 1 Hz wakeup loop is avoided; the clock has no seconds, so anything
    // finer would be 59 wakeups per minute of pure waste.
    private static final String PREFS          = "bare_launcher";
    private static final String KEY_WP_URI     = "wp_uri";
    private static final String KEY_SCROLL_IDX = "scroll_idx";
    private static final String KEY_APP_ORDER  = "app_order";
    /** v1.5.0: number of leading apps (in the visible / non-hidden order)
     *  that form the bottom "home favourites" row and, equivalently, the
     *  centred first row of the pull-down app drawer. Range [0, 8]. Absent on
     *  first run after the v1.5.0 upgrade → resolved lazily to
     *  {@link HomeDrawerModel#defaultHomeCount(int)} (the first 8 of the
     *  user's EXISTING stored order) so an upgrade never resets favourites.
     *  The flat {@link #KEY_APP_ORDER} string is unchanged and stays the
     *  single source of truth for ordering; this key only records where the
     *  home/drawer boundary sits. */
    private static final String KEY_HOME_COUNT = "home_count";
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
    /** Persisted "show clock" preference. true = clock pill rendered with
     *  a "EEE · h:mm a" date prefix (locale-aware short day-of-week);
     *  false = clock pill hidden entirely and no minute tick scheduled.
     *  v1.3.0 introduced this toggle alongside the unified settings panel.
     *  Default true so existing installs see no behaviour change. */
    private static final String KEY_SHOW_CLOCK = "show_clock";
    private static final int    MATCH          = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int    WRAP           = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int    REQ_PICK_WP    = 42;

    // Subtle focus pop — animations toned down for performance / stability.
    // No vertical lift (saves a frame of layout work and removes a class of
    // visual jitter on slow TV ROMs). Scale is small enough to read as
    // "selected" without dominating the shelf.
    private static final float  FOCUS_SCALE    = 1.06f;
    // Toolbar pill (network / mapper / wallpaper) focus pop. Smaller than
    // FOCUS_SCALE because the toolbar plates are themselves smaller — at
    // 1.06× the pop read as too aggressive against a 40 dp box. 1.04 is
    // enough for "this is selected" without dominating the corner.
    private static final float  BTN_FOCUS_SCALE = 1.04f;
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
    /** Single shared {@link android.content.SharedPreferences} handle.
     *  Android's {@link Context#getSharedPreferences} caches by name
     *  internally, so every repeated call returns the same instance —
     *  but the call still walks a HashMap inside Context. Caching the
     *  reference once at startup eliminates ~12 redundant lookups
     *  scattered through onCreate / onResume / onPause / loadKeyMap /
     *  loadHiddenApps / saveOrder / saveKeyMap / saveHiddenApps /
     *  applyStoredOrder / showSettingsPanel toggle / initCaches. Each
     *  hit is sub-microsecond; the cumulative saving is below GC
     *  noise but the field also makes the prefs dependency explicit
     *  at the activity level (every helper now reads {@code prefs}
     *  rather than re-resolving a string-keyed lookup). */
    private android.content.SharedPreferences prefs;

    private RecyclingShelfView shelf;
    /** v1.5.0 pull-down app drawer (vertical recycling grid). Lives directly
     *  above the home shelf in {@link #buildLayout}; GONE until the user
     *  presses DPAD_DOWN on a home cell. */
    private AppDrawer           drawer;
    /** Number of leading visible apps that are "home" apps. {@code -1} until
     *  resolved from {@link #KEY_HOME_COUNT} (or its default) the first time
     *  the app list is known — see {@link #resolveHomeCount(int)}. Always
     *  clamped to {@code [0, min(8, visibleCount)]} before use. */
    private int                 homeCount = -1;
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
    private RingView           ringView;
    private FrameLayout        root;
    private Toast              currentToast;
    /** Holds the predictive-back callback registered on Android 13+ so
     *  {@link #onDestroy()} can unregister it explicitly. The callback is
     *  the one source of BACK handling on devices that route gestures
     *  through the platform's OnBackInvokedDispatcher (instead of via
     *  {@link #dispatchKeyEvent} / {@link #onBackPressed}). It must mirror
     *  the legacy back-priority chain: keymap overlay → context menu /
     *  reorder mode → no-op (the launcher is HOME so it never finishes). */
    private android.window.OnBackInvokedCallback backInvokedCallback;

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

    /** Mirror of {@link #KEY_SHOW_CLOCK} loaded once at startup. The
     *  settings panel toggle writes the pref synchronously and updates
     *  this field + the {@link #clockView} visibility / tick scheduling
     *  in one step. Default {@code true} preserves v1.2.x behaviour for
     *  existing installs. */
    private boolean showClock  = true;
    // Tracks the system 12/24-hour preference; re-detected in startClock()
    // so a change in Settings → Date & time is picked up on the next
    // onResume without a ContentObserver.
    private boolean is24Hour   = false;

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
     *  with no fade animation — simpler and stabler. The {@link #showClock}
     *  preference gates everything: when off the method short-circuits
     *  before any allocation or canvas-touching work. When on, the
     *  formatter renders with the locale-aware short day-of-week prefix
     *  (the time + date toggle is bundled in v1.3.0 so a single user
     *  preference controls both). */
    private void tickClock(long now) {
        TextView cv = clockView;
        if (cv == null) return;
        if (!showClock) return;
        if (!clockFmt.shouldRepaint(now, true, is24Hour)) return; // no visible change
        cv.setText(clockFmt.format(now, true, is24Hour), TextView.BufferType.SPANNABLE);
    }

    // Volatile because these fields are nulled on the UI thread inside
    // {@link #onDestroy} and dereferenced on background workers
    // ({@code iconExecutor} for {@link #iconCache} / {@link #iconDiskCache},
    // {@code appExecutor} for {@link #appExecutor}'s self-reference inside
    // the cache-write runnable). The internal state of each — LruCache is
    // synchronized, IconDiskCache flips its own volatile {@code shuttingDown}
    // flag, ThreadPoolExecutor is documented thread-safe — covers the
    // logical correctness; volatile here covers the visibility of the
    // null assignment so a worker thread doesn't dereference a stale
    // non-null reference after the activity has begun teardown. Same
    // reasoning as the existing {@link #destroyed} volatile.
    private volatile ThreadPoolExecutor       iconExecutor;
    // Wallpaper executor moved into {@link WallpaperController} along with
    // the loading-guard atomic booleans. Only the icon and app-list
    // executors remain here; their hot paths live inside this activity.
    private volatile ExecutorService          appExecutor;
    private volatile LruCache<String, Bitmap> iconCache;
    /** In-memory cache of Apple-TV style banner tiles for the home / drawer
     *  cells. Separate from {@link #iconCache} (which holds the small round
     *  chip icons): tiles are a different shape, size, and source. No disk
     *  cache — tiles regenerate per process (cheap: only the visible cells
     *  are warmed), keeping the icon disk cache untouched. */
    private volatile LruCache<String, Bitmap> bannerCache;
    /** Disk-backed sibling of {@link #iconCache}. Wired through the
     *  {@link LruCache#create(Object)} extension point so a memory-cache
     *  miss transparently falls through to a disk read; freshly-decoded
     *  icons are mirrored to disk via {@link IconDiskCache#writeAsync}
     *  on a background thread. Persists across cold starts so the
     *  shelf renders icons in the very first frame. See {@link
     *  IconDiskCache} javadoc for the full pipeline. */
    private volatile IconDiskCache            iconDiskCache;

    // v1.5.0: widened from List<RecyclingShelfView.CellView> to List<IconTarget>
    // so the one icon pipeline feeds both the home-row cells and the app
    // drawer cells. See {@link IconTarget}.
    private final ArrayMap<String, List<IconTarget>> iconInflight = new ArrayMap<>();
    /** In-flight banner-tile loads keyed by package. The home / drawer cells
     *  display Apple-TV style banner tiles (see {@link #bannerCache}); this is
     *  the banner counterpart of {@link #iconInflight}. Delivers to the same
     *  {@link IconTarget} cells (their display bitmap is the banner). */
    private final ArrayMap<String, List<IconTarget>> bannerInflight = new ArrayMap<>();
    private final List<AppInfo> appList = new ArrayList<>();
    /** Companion to {@link #appList} keyed by package name for O(1)
     *  {@link #findAppByPackage} lookups. The previous linear scan was a
     *  measurable cost inside {@link #refreshKeymapRows} (called on every
     *  UP / DOWN press in the keymap overlay — 6 rows × N apps of
     *  String.equals per press). Single source of truth: the map is
     *  rebuilt atomically inside the same UI block that mutates
     *  {@link #appList}, so the two never diverge mid-frame. */
    private final ArrayMap<String, AppInfo> appByPackage = new ArrayMap<>();

    /** Reusable scratch list for the filtered "visible" view of
     *  {@link #appList} that {@link #applyShelfApps} hands to the
     *  shelf when {@link #hiddenApps} is non-empty. Cleared and
     *  refilled in place on every invocation; the shelf's
     *  {@code setApps} snapshots into its own {@code displayed}
     *  list, so this scratch never leaks references across UI
     *  events. Eliminates one ~50-element ArrayList allocation per
     *  package broadcast / hide-toggle / loadApps reconcile when
     *  the user has any hidden apps configured. UI thread only —
     *  no synchronisation needed. */
    private final ArrayList<AppInfo> visibleScratch = new ArrayList<>();

    private boolean pkgChangedWhilePaused = false;
    /** Packages flagged by {@link #packageReceiver} as REPLACED / CHANGED
     *  whose icons must be re-decoded on the next reconcile. Cleared after
     *  the reconcile consumes it. UI-thread only — no synchronisation. */
    private final ArraySet<String> pendingIconInvalidations = new ArraySet<>();
    /** True between {@link #onPause} and {@link #onResume}. Read by the
     *  package broadcast receiver to decide whether to schedule a
     *  background {@link #loadApps} reconcile or just set
     *  {@link #pkgChangedWhilePaused} and let the next {@code onResume}
     *  fire it.
     *
     *  <p>Pre-1.4.3 the receiver always scheduled the post even while
     *  the user was in another app, burning ~50–250 ms of CPU on a
     *  background PM scan + cell rebuild that no human was looking at.
     *  Each scheduled post also kept the launcher process alive past
     *  the broadcast (the looper's pending message holds a strong
     *  reference to the activity), bumping memory pressure on the OS's
     *  LRU eviction order. Skipping the schedule while paused defers
     *  the same work to {@code onResume}, where the user is actually
     *  looking and the latency is amortised against the resume
     *  animation. The {@code pkgChangedWhilePaused} flag still
     *  triggers the resume-time reconcile so no broadcast is missed.
     *
     *  <p>UI-thread only — no synchronisation needed. */
    private boolean uiPaused = false;
    private ViewTreeObserver.OnGlobalLayoutListener focusRestoreListener;
    private final int[]    ringCellLoc      = new int[2];
    private final int[]    ringRootLoc      = new int[2];
    /** Set false by anything that could move the launcher's root view on
     *  screen (configuration change, multi-window resize) and read by
     *  {@link #positionRing} to decide whether {@link #ringRootLoc} is
     *  still valid. The root view of a TV launcher essentially never
     *  moves once attached — its origin stays at (0, 0) of the activity
     *  window for the activity lifetime. Caching the root's screen
     *  coordinates eliminates one full {@link View#getLocationOnScreen}
     *  walk per {@link #positionRing} call (~5 matrix multiplications +
     *  ~5 offset accumulations across the view hierarchy depth). At
     *  60 fps × 150 ms focus animation = ~9 frames per focus event, so
     *  the per-focus saving is ~9 view-tree walks. Invalidated in
     *  {@link #onConfigurationChanged} because that's the only path
     *  that can move the activity window on a TV (HDMI swap, multi-
     *  window enter on tablet, font scale). The window-attached / first
     *  layout cycle is handled implicitly because the cached value is
     *  re-fetched every time {@code rootLocCached} is false.
     *
     *  <p>Not declared {@code volatile} because every read and write
     *  happens on the main UI thread (focus animator update listeners,
     *  {@link Activity#onConfigurationChanged}). */
    private boolean rootLocCached = false;
    /** Scratch arrays used by {@link #anchorCardUnderGear} to read the
     *  gear pill's and the root's on-screen positions. Promoted to
     *  fields so the per-overlay-open path stays allocation-free —
     *  matches the {@link #ringCellLoc} / {@link #menuCellLoc} pattern.
     *  Anchor reads run only on the main thread so no synchronisation
     *  is needed. */
    private final int[]    anchorMbLoc      = new int[2];
    private final int[]    anchorRootLoc    = new int[2];
    private       int      ringLayoutW      = 0;  // RingView box width (landscape banner + margin)
    private       int      ringLayoutH      = 0;  // RingView box height
    private       float    cachedIcyOffset  = 0f;
    /** Runnable that performs the deferred package-broadcast reconcile.
     *
     *  <p>Pre-1.4.3 was a bare {@code this::loadApps} method-reference.
     *  v1.4.3 broadens the body to clear {@link #pkgChangedWhilePaused}
     *  before delegating, so a successful in-foreground reconcile
     *  doesn't leave the flag at {@code true} for the NEXT
     *  {@code onResume} to trip and re-fire {@link #loadApps}
     *  redundantly. The {@link #appsLoading} {@code compareAndSet}
     *  guard would short-circuit the redundant call, but the
     *  short-circuit still allocates the method-reference and walks
     *  the entry path — clearing the flag here is the cleaner fix.
     *
     *  <p>Reused for the {@link #onTrimMemory} 1 s deferred reload
     *  and the {@code RejectedExecutionException} retry path (see
     *  {@link #loadApps}'s catch). Both eventually call
     *  {@link #loadApps}; clearing the flag preemptively is the
     *  correct behaviour for both because they reconcile the in-memory
     *  state with PM regardless of the user's pause / resume cycle.
     *
     *  <p>Held as a single instance so callers can {@code postDelayed}
     *  / {@code removeCallbacks} the same Runnable across multiple
     *  paths without each re-allocating a method-reference. */
    private final Runnable pkgReloadRunnable = () -> {
        pkgChangedWhilePaused = false;
        loadApps();
    };

    private FrameLayout        menuOverlay   = null;
    /** Surface that currently owns the shared reorder context menu — set on
     *  entry to reorder mode by whichever of the home shelf / app drawer is
     *  reordering. See {@link ReorderHost}. */
    private ReorderHost        menuHost      = null;
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
    // Color tag drawn next to each row label. ARGB. 0 = no colour (the
    // SHORTCUT_GLYPHS slot picks up — Menu and Subtitle render small
    // monochrome glyphs in place of the colour disc).
    private static final int[]    SHORTCUT_TAGS     = {
            0xFFE5484D, 0xFF30A46C, 0xFFF5C518, 0xFF3E63DD, 0, 0,
    };

    /** Shortcut row indicator kind. Parallel to {@link #SHORTCUT_KEYCODES}
     *  / {@link #SHORTCUT_LABELS} / {@link #SHORTCUT_TAGS}. The four
     *  colour rows show {@link #GLYPH_DOT} (a solid colour disc using
     *  the matching {@code SHORTCUT_TAGS} colour); Menu and Subtitle
     *  render small monochrome glyphs ({@link #GLYPH_HAMBURGER} =
     *  three short horizontal lines, {@link #GLYPH_CC} = the standard
     *  closed-captions "CC" badge) so every row has a visual indicator
     *  at the same x-position — symmetric across the slot list, no row
     *  reads as "label only" against the colour rows. v1.3.2 addition. */
    private static final int      GLYPH_DOT       = 0;
    private static final int      GLYPH_HAMBURGER = 1;
    private static final int      GLYPH_CC        = 2;
    private static final int[]    SHORTCUT_GLYPHS = {
            GLYPH_DOT, GLYPH_DOT, GLYPH_DOT, GLYPH_DOT, GLYPH_HAMBURGER, GLYPH_CC,
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
    // Manage-hidden-apps used to live as a 7th, visually-offset row at
    // the bottom of the slot column. v1.3.0 moves it into the unified
    // settings panel (alongside Set wallpaper / Show clock / System
    // Settings). The keymap card is now exclusively key-binding rows.
    // The HIDE sub-mode the manage row used to enter still exists and
    // is reachable from the settings panel's "Manage hidden apps" row.
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
    private android.widget.ScrollView   keymapHideScroll  = null;  // vertical list scroller (v1.5.0)
    private android.widget.LinearLayout keymapHideStrip   = null;  // vertical row list
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

    // Third toolbar icon (next to wifi) that opens the unified settings
    // panel — the v1.3.0 consolidation entry point. Held as a field so
    // focus-chain handlers and onDestroy can reach it.
    private View                        mapperBtnView     = null;

    // ── Settings panel (v1.3.0) ──────────────────────────────────────────
    //
    // Top-level overlay that opens as a dropdown under the gear button
    // and holds five rows: Manage hidden apps, Button shortcuts, Set
    // wallpaper, Show clock toggle, System Settings. Replaces the
    // wallpaper toolbar pill (deleted) and the "Manage hidden apps" 7th
    // row that used to live inside the keymap card. Same visual language
    // as the keymap card (dark slate plate + 1 dp white rim, drop-down
    // animation pivoted at the gear's top-right corner).
    //
    // Lifecycle: built lazily on first {@link #showSettingsPanel} so
    // cold-start doesn't pay for ~12 view allocations and 5 click
    // listeners for a feature most users only touch occasionally.
    // Re-used across opens, torn down on activity destroy.
    private FrameLayout                 settingsOverlay   = null;
    private android.widget.LinearLayout settingsCard      = null;
    private android.widget.LinearLayout settingsColumn    = null;
    /** Selection cursor inside the panel — UP/DOWN cycle, OK activates. */
    private int                         settingsSelectedRow = 0;
    /** Row to land on the next time the panel is opened. Set by
     *  {@link #activateSettingsRow} before drilling into the keymap card,
     *  consumed inside {@link #showSettingsPanel}. v1.3.2 fix for the
     *  "settings cursor jumps back to row 0" bug — when a user clicks
     *  "Button shortcuts" then presses Back, the panel re-opens with
     *  the cursor on "Button shortcuts" instead of "Manage hidden apps". */
    private int                         pendingSettingsCursor = 0;
    /** Set when the user enters the keymap card via the settings panel
     *  (Manage hidden apps row → HIDE mode, Button shortcuts row →
     *  SLOTS mode). {@link #hideKeymapOverlay} consults this flag and
     *  re-opens the settings panel instead of dropping focus to the home
     *  shelf, so a deep "settings → button shortcuts → bind a key →
     *  back" gesture lands the user exactly where they left the panel. */
    private boolean                     keymapOpenedFromSettings = false;

    /** Set alongside {@link #keymapOpenedFromSettings} when the panel
     *  enters HIDE mode directly (from the "Manage hidden apps" row).
     *  {@link #exitHideManager} consults this and skips the v1.2.x
     *  "back-from-HIDE returns to SLOTS" behaviour — the user came in
     *  from the panel, not from the slot list, so back should bypass
     *  SLOTS and dismiss the keymap card so the panel re-opens. */
    private boolean                     hideManagerSkipSlotsOnExit = false;

    /** Single shared dim backdrop View added to {@link #root} in
     *  {@link #buildLayout}. Both the settings panel and the keymap
     *  card reference it via {@link #ensureOverlayBackdropVisible} /
     *  {@link #dismissOverlayBackdropIfIdle}. Replaces the v1.3.0
     *  initial design's per-overlay {@code setBackgroundColor(0x33000000)}
     *  which produced a visible dim flicker when transitioning from
     *  the panel to the keymap card (settings backdrop fading out
     *  while keymap backdrop faded in compounded for ~110 ms above
     *  the wallpaper). With one shared backdrop the dim level stays
     *  constant across the entire modal flow. */
    private View                        overlayBackdrop = null;

    /** Symbolic indices for the 5 rows in the settings panel. UP/DOWN
     *  navigation is modulo SETTINGS_ROW_COUNT, OK dispatches via a
     *  switch on these values. */
    private static final int SETTINGS_ROW_HIDE_APPS       = 0;
    private static final int SETTINGS_ROW_KEYMAP          = 1;
    private static final int SETTINGS_ROW_WALLPAPER       = 2;
    private static final int SETTINGS_ROW_SHOW_CLOCK      = 3;
    private static final int SETTINGS_ROW_SYSTEM_SETTINGS = 4;
    private static final int SETTINGS_ROW_COUNT           = 5;

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
                if (!(newFocus instanceof RecyclingShelfView.CellView)
                        && !(newFocus instanceof AppDrawer.DrawerCell)) {
                    RingView rv = ringView;
                    if (rv != null) rv.setVisibility(View.INVISIBLE);
                }
            };

    /** Refresh the visible clock the moment the device clock advances —
     *  user changed the time manually, crossed a timezone boundary
     *  (flight, train), or DST flipped. Without this receiver the clock
     *  could show the old time for up to 60 seconds (until the next
     *  minute-aligned tick fires). The receiver is registered ONLY while
     *  the launcher is in the foreground (registered in {@link #onResume},
     *  unregistered in {@link #onPause}) — it has no business waking the
     *  process when the user is somewhere else. */
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (destroyed) return;
            // ACTION_TIME_CHANGED also fires when the user flips Settings →
            // Date & time → "Use 24-hour format", not just on a manual
            // clock/timezone change. startClock() already re-detects this
            // on every onResume, but some TV boxes expose a quick-settings
            // overlay that can flip the toggle WITHOUT pausing the launcher
            // — re-checking here means that case is picked up immediately
            // too, instead of waiting for the next resume.
            boolean detected = DateFormat.is24HourFormat(ctx);
            if (detected != is24Hour) is24Hour = detected;
            // Reset the per-minute idempotency sentinel so the next paint
            // is unconditional, then paint and re-anchor the next tick to
            // the (possibly NEW) minute boundary. tickClock walks through
            // the no-op guard internally; calling it here also covers the
            // "minute boundary in the OLD timezone is 7 minutes off the
            // boundary in the NEW timezone" case where the next scheduled
            // tick would otherwise have fired at the wrong instant.
            clockFmt.reset();
            long now = System.currentTimeMillis();
            tickClock(now);
            if (clockRunning) {
                uiHandler.removeCallbacks(clockTick);
                uiHandler.postDelayed(clockTick, ClockFormatter.nextMinuteDelay(now));
            }
        }
    };
    /** Tracks whether {@link #timeReceiver} is currently registered so the
     *  unregister call in {@link #onPause} is idempotent against the
     *  rare double-resume / double-pause sequences some TV ROMs emit
     *  during fast configuration transitions. */
    private boolean timeReceiverRegistered = false;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            // Invalidate per-package caches on REPLACED / CHANGED / REMOVED.
            // PACKAGE_REPLACED  — app upgrade; the icon may have changed.
            // PACKAGE_CHANGED   — components enabled/disabled etc; same.
            // PACKAGE_REMOVED   — uninstall; clean up the disk cache so
            //                     orphan icon files don't accumulate
            //                     (in-memory cache is invalidated for free
            //                     on the next loadApps reconcile).
            // PACKAGE_ADDED is intentionally absent — there's nothing to
            // invalidate (no entry yet); the loadApps reconcile picks up
            // the new package and warms the cache normally.
            if (Intent.ACTION_PACKAGE_REPLACED.equals(action)
                    || Intent.ACTION_PACKAGE_CHANGED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                Uri data = intent.getData();
                if (data != null) {
                    // SSP is documented non-null for "package:" URIs but
                    // malformed broadcasts on stripped-down ROMs have been
                    // observed returning null. Guard before calling cache /
                    // inflight removers — those would NPE on a null key
                    // and bubble up through the BroadcastReceiver, which
                    // the system treats as a misbehaving receiver and may
                    // tear the launcher down.
                    String pkg = data.getSchemeSpecificPart();
                    if (pkg != null) {
                        if (iconCache != null) iconCache.remove(pkg);
                        iconInflight.remove(pkg);
                        // Mirror the invalidation to the banner tile so a
                        // replaced app's tile re-renders too (v1.5.0).
                        if (bannerCache != null) bannerCache.remove(pkg);
                        bannerInflight.remove(pkg);
                        // Flag for icon re-decode on the next reconcile.
                        // The disk delete below is queued on the write
                        // executor; the reconcile's preWarmIcon runs later
                        // so its tryRead misses and re-decodes the fresh icon.
                        pendingIconInvalidations.add(pkg);
                        // Mirror invalidation to the on-disk cache so a
                        // stale icon does not survive a package replace
                        // / uninstall. Best-effort delete on the write
                        // executor; the in-memory cache.remove above
                        // already prevents serving the stale bitmap, so
                        // a delete failure is bounded to one transient
                        // disk read of the stale bytes.
                        IconDiskCache dc = iconDiskCache;
                        if (dc != null) dc.delete(pkg);
                    }
                }
            }
            pkgChangedWhilePaused = true;
            // Invalidate cached picker chip strip — its identities can no
            // longer be trusted to match the in-memory appList after a
            // package install / remove / replace. Same applies to the
            // hide-manager toggle rows. Slot rows must be re-measured
            // because a relabelled package can change the equalised width.
            // These invalidations run regardless of pause state because
            // the next overlay open (whenever it happens) needs them.
            keymapPickerBuiltSize = -1;
            keymapHideBuiltSize   = -1;
            keymapRowsNeedEqualize = true;
            // v1.4.3 audit: skip the deferred reconcile while the
            // activity is paused. The {@link #pkgChangedWhilePaused}
            // flag set above triggers the same reconcile in the next
            // {@link #onResume}, so no broadcast is missed — but doing
            // the work now (while no human is looking) costs ~50–250 ms
            // of CPU per broadcast and pins the launcher process in
            // memory past the broadcast (the looper's pending message
            // holds a strong reference to the activity). On a TV ROM
            // that processes 5–10 background package updates per day,
            // this is several seconds of avoidable CPU + several MB of
            // avoidable memory pressure on the system's process LRU.
            // The trade-off is that the user-visible reconcile happens
            // on resume instead of pre-resume; the latency is amortised
            // against the resume animation and bounded by the existing
            // {@code appsLoading} guard.
            if (uiPaused) return;
            RecyclingShelfView s = shelf;
            if (s == null) return;
            s.removeCallbacks(pkgReloadRunnable);
            s.postDelayed(pkgReloadRunnable, 400);
        }
    };

    private void applyStoredOrder(List<AppInfo> apps) {
        String raw = prefs.getString(KEY_APP_ORDER, null);
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
        prefs.edit()
                .putString(KEY_APP_ORDER, AppOrder.serialize(pkgs)).apply();
        // Mirror the order into the AppListCache so the next cold start
        // renders the shelf in the user's just-saved arrangement instead
        // of the previous order. Without this nudge, the next cold start
        // would render in the OLD cached order for ~200 ms before the
        // PM-scan reconcile detects the order change and re-renders —
        // visible as a transient flicker after every reorder + reboot.
        // Best-effort write on a background thread.
        final ArrayList<AppInfo> snapshot = new ArrayList<>(appList);
        final Context appCtx = getApplicationContext();
        try {
            appExecutor.execute(() -> AppListCache.writeFileFromAppInfo(appCtx, snapshot));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor saturated; the cache will converge on the next
            // loadApps reconcile that detects the new order.
        }
    }

    /** Persist the home/drawer boundary. Cheap and synchronous — written only
     *  when the boundary actually moves (a drawer promote/demote, or the
     *  one-time migration default). See {@link #KEY_HOME_COUNT}. */
    private void saveHomeCount() {
        prefs.edit().putInt(KEY_HOME_COUNT, Math.max(0, homeCount)).apply();
    }

    /** Resolve {@link #homeCount} against the current visible-app count.
     *
     *  <p>First call with a non-empty list reads the persisted value or, when
     *  absent (the v1.5.0 upgrade), falls back to
     *  {@link HomeDrawerModel#defaultHomeCount(int)} — the first 8 of the
     *  user's EXISTING stored order — and writes it back so the migration
     *  default sticks. Later calls only re-clamp in memory (persisting a
     *  shrink, e.g. when the user hides enough apps that the home row no
     *  longer fits).
     *
     *  <p>Deliberately a no-op while {@code visibleCount == 0}: the cold-start
     *  cache pre-paint can momentarily see an empty list, and resolving to 0
     *  there would wrongly stick an empty home row before the authoritative
     *  PM scan ever runs. */
    private void resolveHomeCount(int visibleCount) {
        if (visibleCount <= 0) return;
        if (homeCount < 0) {
            int stored = prefs.getInt(KEY_HOME_COUNT, -1);
            homeCount = (stored < 0)
                    ? HomeDrawerModel.defaultHomeCount(visibleCount)
                    : HomeDrawerModel.clampHomeCount(stored, visibleCount);
            if (homeCount < 1) homeCount = 1;   // never strand an empty home row
            saveHomeCount();
        } else {
            int clamped = HomeDrawerModel.clampHomeCount(homeCount, visibleCount);
            if (clamped < 1) clamped = 1;
            if (clamped != homeCount) { homeCount = clamped; saveHomeCount(); }
        }
    }

    /** The visible app list = {@link #appList} minus {@link #hiddenApps},
     *  preserving order. Returns {@link #appList} directly (no copy) when no
     *  apps are hidden; otherwise fills the reusable {@link #visibleScratch}.
     *  Callers must treat the result as read-only and short-lived — every
     *  {@code setApps} consumer snapshots it into its own {@code displayed}
     *  list, so the scratch never leaks across UI events. */
    private List<AppInfo> buildVisibleList() {
        if (hiddenApps.isEmpty()) return appList;
        ArrayList<AppInfo> v = visibleScratch;
        v.clear();
        v.ensureCapacity(appList.size());
        for (int i = 0, n = appList.size(); i < n; i++) {
            AppInfo a = appList.get(i);
            if (!hiddenApps.contains(a.packageName)) v.add(a);
        }
        return v;
    }

    /** Effective, clamped home-row size for the current visible count. Never
     *  negative; safe to use as a list bound. Enforces a floor of one home app
     *  whenever any app is visible so the home screen always has a cell to
     *  focus (and to press DOWN on to open the drawer) — an empty home row
     *  would otherwise strand the user with no way back into the drawer. */
    private int effectiveHomeCount(int visibleCount) {
        if (visibleCount <= 0) return 0;
        return Math.max(1, HomeDrawerModel.clampHomeCount(homeCount, visibleCount));
    }

    /** Feed the bottom home row the first {@code hc} visible apps (the shelf
     *  centres a short list and scrolls a long one — unchanged behaviour). */
    private void pushHomeRow(RecyclingShelfView s, List<AppInfo> visible, int hc) {
        if (s == null) return;
        if (hc >= visible.size()) s.setApps(visible);
        else                      s.setApps(visible.subList(0, hc));
    }

    /** Mirror a reordered <em>visible</em> list back into the master
     *  {@link #appList}, keeping every hidden app pinned at its original
     *  absolute slot. The non-hidden slots are refilled, in order, from
     *  {@code newVisible}; {@link #appByPackage} is rebuilt to match. Used by
     *  the drawer's Move mode (whose {@code displayed} list IS the visible
     *  list) so a 2-D reorder updates the persisted order without scrambling
     *  hidden-app placement — the same invariant the shelf's
     *  {@code swapWithNeighbour} maintains for single swaps. */
    private void rebuildAppListFromVisible(List<AppInfo> newVisible) {
        if (hiddenApps.isEmpty()) {
            if (newVisible != appList) { appList.clear(); appList.addAll(newVisible); }
        } else {
            int n = appList.size();
            AppInfo[] result = new AppInfo[n];
            boolean[] hiddenSlot = new boolean[n];
            for (int i = 0; i < n; i++) {
                AppInfo a = appList.get(i);
                if (hiddenApps.contains(a.packageName)) { hiddenSlot[i] = true; result[i] = a; }
            }
            int vi = 0;
            for (int i = 0; i < n; i++) {
                if (!hiddenSlot[i]) {
                    result[i] = (vi < newVisible.size()) ? newVisible.get(vi++) : appList.get(i);
                }
            }
            appList.clear();
            for (AppInfo a : result) appList.add(a);
        }
        appByPackage.clear();
        for (int i = 0, n = appList.size(); i < n; i++) {
            AppInfo a = appList.get(i);
            appByPackage.put(a.packageName, a);
        }
    }

    /** Open the pull-down app drawer, mirroring the current order / home
     *  boundary and landing focus on the same app the user was on in the home
     *  row. No-op if the drawer is already open or there are no apps. */
    private void openDrawer() {
        AppDrawer d = drawer; RecyclingShelfView s = shelf;
        if (d == null || s == null) return;
        if (d.getVisibility() == View.VISIBLE) return;
        List<AppInfo> visible = buildVisibleList();
        if (visible.isEmpty()) return;
        resolveHomeCount(visible.size());
        int hc = effectiveHomeCount(visible.size());
        d.setApps(visible, hc);
        // The drawer's first hc cells ARE the home apps, so a home-row index
        // maps 1:1 to a drawer index. Clamp defensively.
        int focus = Math.min(Math.max(0, s.focusedIndex), visible.size() - 1);
        d.open(focus);
        // Hide the home shelf while the drawer covers the screen so we never
        // draw both grids at once (the drawer's row 0 already mirrors the home
        // row). INVISIBLE (not GONE) avoids a relayout on open/close.
        s.setVisibility(View.INVISIBLE);
        // Frosted backdrop: blur the wallpaper behind the translucent drawer.
        // The shared selection ring stays WHITE in the drawer (it reads well
        // over the frosted surface) — same colour as on the home shelf.
        applyDrawerBlur(true);
        setHomeChromeVisible(false);
    }

    /** Close the drawer, re-derive the home row from the (possibly changed)
     *  order / home boundary, and return focus to the home favourites screen.
     *  Focus lands on the drawer's focused app when it is a home app,
     *  otherwise the nearest home app; when the home row is empty it falls
     *  back to the toolbar so focus is never lost. */
    private void closeDrawer() {
        AppDrawer d = drawer; RecyclingShelfView s = shelf;
        if (d == null || d.getVisibility() != View.VISIBLE) return;
        final int drawerFocus = d.focusedIndex;
        if (d.reorderMode) d.exitReorderMode(false);
        // Clear the wallpaper blur NOW (not in the close end-callback). If we
        // waited until the slide finished, the translucent veil would fade to
        // zero while the GPU blur was still applied, briefly revealing the bare
        // blurred wallpaper before it snapped sharp — the "second blur" flash.
        // Clearing it up front means the veil fades over an already-sharp
        // wallpaper, and it drops the blur a few frames earlier (cheaper).
        applyDrawerBlur(false);
        // Snapshot the (possibly reordered) visible list now; the close
        // callback runs ~170 ms later, after which the reusable visibleScratch
        // could have been rewritten by another applyShelfApps.
        final List<AppInfo> visibleSnapshot = new ArrayList<>(buildVisibleList());
        resolveHomeCount(visibleSnapshot.size());
        final int hc = effectiveHomeCount(visibleSnapshot.size());
        d.close(() -> {
            RecyclingShelfView s2 = shelf;
            if (s2 == null || destroyed) return;
            setHomeChromeVisible(true);             // restore toolbar + clock
            s2.setVisibility(View.VISIBLE);   // restore the home shelf hidden on open
            if (hc <= 0 || visibleSnapshot.isEmpty()) {
                pushHomeRow(s2, visibleSnapshot, hc);   // clears the shelf
                View nb = netBtn; if (nb != null) nb.requestFocus();
                return;
            }
            // Land focus on the drawer's app when it is a home app, else the
            // nearest home app. Seed focusedIndex so the shelf's setApps posts
            // its focus request onto the right cell.
            int homeIdx = (drawerFocus >= 0 && drawerFocus < hc) ? drawerFocus : Math.max(0, hc - 1);
            s2.focusedIndex = homeIdx;
            s2.snapNextFocus = true;   // calm, no focus-bounce on return
            pushHomeRow(s2, visibleSnapshot, hc);
        });
    }

    /** Frosted-glass backdrop for the drawer: GPU-blur the (static) wallpaper
     *  on Android 12+ (RenderEffect) so the translucent white drawer reads as
     *  frosted glass. The blur is computed once into the wallpaper view's
     *  render node — no per-frame cost. No-op on older devices, where the
     *  drawer falls back to a near-opaque light veil. */
    private void applyDrawerBlur(boolean on) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        RenderEffect fx = null;
        if (on) {
            float r = dp(36);
            fx = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
        }
        if (wallpaperFront != null) wallpaperFront.setRenderEffect(fx);
        if (wallpaperBack  != null) wallpaperBack .setRenderEffect(fx);
    }

    /** Hide / restore the home "chrome" (toolbar pills + clock) while the
     *  drawer is open. The drawer surface is translucent, so leaving the
     *  sharp toolbar/clock behind it would ghost through the frost. The home
     *  shelf is hidden separately (it owns focus-restore logic). */
    private void setHomeChromeVisible(boolean visible) {
        View nb = netBtn;        if (nb != null) nb.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        View mb = mapperBtnView; if (mb != null) mb.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        TextView cv = clockView;
        if (cv != null) cv.setVisibility(visible ? (showClock ? View.VISIBLE : View.GONE) : View.INVISIBLE);
    }

    /** Launch the system uninstall flow for {@code app}. Shared by the drawer
     *  cell's reorder menu; the shelf cell keeps its own copy (which also
     *  manages its reorder teardown). Mirrors the shelf's ACTION_DELETE →
     *  ACTION_UNINSTALL_PACKAGE fallback chain. */
    private void doUninstall(AppInfo app) {
        if (app == null) return;
        Uri pkgUri = Uri.fromParts("package", app.packageName, null);
        Intent primary = new Intent(Intent.ACTION_DELETE, pkgUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStartActivityResolved(primary)) return;
        @SuppressWarnings("deprecation")
        Intent fallback = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, pkgUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStartActivityResolved(fallback)) return;
        showToast(getString(R.string.toast_cannot_uninstall, app.label));
    }

    /** Open the system "App info" page for {@code app}. Shared by the drawer
     *  cell's reorder menu. */
    private void doAppInfo(AppInfo app) {
        if (app == null) return;
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStartActivityResolved(i)) return;
        showToast(getString(R.string.toast_no_app_info));
    }

    /** Start {@code intent} only if some activity resolves it. Returns whether
     *  the launch was attempted. Defensive against stripped TV ROMs that lack
     *  a Settings/uninstaller activity. */
    private boolean tryStartActivityResolved(Intent intent) {
        try {
            if (intent.resolveActivity(pm) == null) return false;
            startActivity(intent);
            return true;
        } catch (Exception ignored) { return false; }
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
        computeTileDims();
        pm = getPackageManager();
        initShortcutLabels();
        initCaches();
        // User-preference-driven state must be loaded BEFORE the shelf is
        // populated. The v1.4.0 cold-start cache pre-paint inside
        // {@link #loadApps()} calls {@link #applyShelfApps(RecyclingShelfView)}
        // synchronously on this thread, and applyShelfApps reads
        // {@link #hiddenApps} to filter the shelf list. Loading
        // hidden-apps and the keymap AFTER loadApps left the cache pre-
        // paint reading an empty {@code hiddenApps} set, so the shelf
        // rendered every installed app on cold start until either:
        //   • the background PM scan reconciled with {@code changed = true}
        //     (rare — only fires when the cache disagrees with PM), OR
        //   • the user opened the hide-manager and toggled any chip
        //     (which sets {@code keymapHideDirty} and forces an
        //     applyShelfApps on overlay close).
        // The visible symptom: every force-stop / cold-start showed all
        // apps including hidden ones until the user round-tripped the
        // hide manager. Loading hiddenApps and the keymap synchronously
        // BEFORE loadApps closes the window: the very first
        // applyShelfApps call sees the correct hiddenApps set, and the
        // shelf paints with the right filter from frame zero.
        loadKeyMap();
        loadHiddenApps();
        setContentView(buildLayout());
        hideSystemUI();
        loadWallpaper();
        loadApps();
        registerPkgReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Predictive-back routes BACK gestures here INSTEAD of through
            // dispatchKeyEvent / onBackPressed on supported devices. The
            // callback must mirror the legacy back-priority chain so the
            // user-visible behaviour stays identical regardless of which
            // delivery path the platform chose.
            backInvokedCallback = () -> {
                // 1. Keymap overlay open → close it.
                FrameLayout ko = keymapOverlay;
                if (ko != null && ko.getVisibility() == View.VISIBLE) {
                    if (keymapMode == KEYMAP_MODE_PICKER) { exitAppPicker();   return; }
                    if (keymapMode == KEYMAP_MODE_HIDE)   { exitHideManager(); return; }
                    hideKeymapOverlay();
                    return;
                }
                // 2. Settings panel open → close it. Lower priority than
                //    the keymap overlay because the keymap overlay can sit
                //    on top of the settings panel (drilled in via "Manage
                //    hidden apps" or "Button shortcuts"). hideKeymapOverlay
                //    re-opens the settings panel automatically when
                //    keymapOpenedFromSettings is set, so back-stack
                //    behaviour matches user expectations.
                FrameLayout sp = settingsOverlay;
                if (sp != null && sp.getVisibility() == View.VISIBLE) {
                    hideSettingsPanel();
                    return;
                }
                // 3. Context menu / reorder mode open → exit it. The context
                //    menu lives inside reorder mode in this design, so a
                //    single exitReorderMode call hides both.
                RecyclingShelfView s = shelf;
                if (s != null && s.reorderMode) { s.exitReorderMode(false); return; }
                // 3.5. App drawer open → exit its Move mode, else close it.
                //      Lower priority than the overlays (which can sit on top
                //      of the home screen) but it owns BACK whenever it is
                //      visible. Mirrors the legacy key-event path handled by
                //      the drawer cell's KEYCODE_BACK case.
                AppDrawer dr = drawer;
                if (dr != null && dr.getVisibility() == View.VISIBLE) {
                    if (dr.reorderMode) dr.exitReorderMode(false);
                    else                closeDrawer();
                    return;
                }
                // 4. Otherwise no-op: the launcher is HOME — back from the
                //    home screen should stay home.
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backInvokedCallback);
        }
    }

    /** Pending saved scroll index — applied as soon as appList is populated.
     *  Kept as field so the cold-start path (where appList is empty in onResume)
     *  doesn't silently drop the user's last-focused position. */
    private int pendingScrollIdx = -1;

    @Override
    protected void onResume() {
        super.onResume();
        // Mark resumed BEFORE any of the receiver-touching helpers below
        // so a package broadcast that fires during the resume transition
        // sees us as resumed and schedules its reconcile normally instead
        // of falling into the paused-skip path. See {@link #uiPaused}
        // for the full rationale.
        uiPaused = false;
        hideSystemUI();
        startClock();
        registerTimeReceiver();
        if (pkgChangedWhilePaused) { pkgChangedWhilePaused = false; loadApps(); }
        RecyclingShelfView s = shelf;
        if (s != null) {
            int saved = prefs.getInt(KEY_SCROLL_IDX, 0);
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
            if (rvto.isAlive()) {
                // Dedupe — onResume can fire twice on some TV ROMs without an
                // intervening onPause during fast configuration transitions.
                // ViewTreeObserver does NOT dedupe the same listener instance,
                // so a second registration would leak a strong reference to
                // this activity through the lambda's outer-this capture and
                // only one of the two would be removed by onPause.
                rvto.removeOnGlobalFocusChangeListener(globalFocusListener);
                rvto.addOnGlobalFocusChangeListener(globalFocusListener);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Mark paused FIRST so a package broadcast that fires during the
        // pause transition takes the paused-skip path. The order pairs
        // with {@link #onResume}'s "set false first" so the receiver
        // never observes a stale value during a tight resume / pause
        // race. See {@link #uiPaused} for the full rationale.
        uiPaused = true;
        stopClock();
        unregisterTimeReceiver();
        // v1.5.0: if the drawer is open when we pause, persist any in-progress
        // move, dismiss it without animation (the close tween can't run while
        // pausing), and refresh the home row so a resume lands on an
        // up-to-date home screen rather than a stale drawer.
        AppDrawer d = drawer;
        if (d != null && d.getVisibility() == View.VISIBLE) {
            if (d.reorderMode) d.exitReorderMode(true);
            d.forceHide();
            RecyclingShelfView sh = shelf;
            if (sh != null) {
                sh.setVisibility(View.VISIBLE);   // undo the open-time hide
                List<AppInfo> vis = buildVisibleList();
                pushHomeRow(sh, vis, effectiveHomeCount(vis.size()));
            }
        }
        FrameLayout r = root;
        if (r != null) {
            ViewTreeObserver rvto = r.getViewTreeObserver();
            if (rvto.isAlive()) rvto.removeOnGlobalFocusChangeListener(globalFocusListener);
        }
        RecyclingShelfView s = shelf;
        if (s != null) {
            if (s.reorderMode) s.exitReorderMode(false);
            prefs.edit().putInt(KEY_SCROLL_IDX, s.focusedIndex).apply();
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
        unregisterTimeReceiver();
        // Cancel any in-flight toast. Toast.makeText(this, ...) holds a
        // strong reference to the activity through its TN binder on older
        // ROMs; without an explicit cancel a 3.5 s "long" toast in flight
        // when the user navigates away pins the destroyed activity until
        // the system clears the queue.
        if (currentToast != null) { currentToast.cancel(); currentToast = null; }
        // Unregister the predictive-back callback (Android 13+). Conventionally
        // safe to skip because the dispatcher is owned by the activity, but
        // unregistering explicitly avoids any chance of a stale callback
        // surviving across a partial recreate.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            try { getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback); }
            catch (Throwable ignored) { /* best-effort */ }
            backInvokedCallback = null;
        }
        // Parallel shutdown of every executor the launcher owns.
        //
        // Pre-1.4.x serialised the four shutdowns: each one called
        // {@code shutdown()} + {@code awaitTermination(300, MS)} +
        // {@code shutdownNow()} in turn, so the worst-case wall-clock
        // cost was 4 × 300 ms = 1.2 seconds of UI-thread block at
        // {@code onDestroy} — visible to the user as a stutter when
        // navigating from the launcher into another activity. Now we:
        //   1. Phase 1 — call {@code shutdown()} on every executor up
        //      front (non-blocking; flips internal "shutting down" flags
        //      so future submits are rejected).
        //   2. Phase 2 — share a single 300 ms deadline across every
        //      {@code awaitTermination} call, decrementing the remaining
        //      budget as each one elapses. Total cap stays 300 ms
        //      regardless of how many executors are involved.
        //   3. Phase 3 — force-stop any survivors with {@code shutdownNow}
        //      and release the wallpaper bitmaps last (so a runnable
        //      from an in-flight cross-fade — which short-circuits on
        //      {@code destroyed} anyway — has already run by then).
        if (iconExecutor   != null) try { iconExecutor.shutdown(); } catch (Throwable ignored) { /* best-effort */ }
        if (appExecutor    != null) try { appExecutor .shutdown(); } catch (Throwable ignored) { /* best-effort */ }
        if (iconDiskCache  != null) iconDiskCache.beginShutdown();
        if (wallpaperCtl   != null) wallpaperCtl .beginShutdown();
        long deadlineNs = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(300);
        awaitOrSkip(iconExecutor,  deadlineNs);
        awaitOrSkip(appExecutor,   deadlineNs);
        if (iconDiskCache != null) iconDiskCache.awaitShutdown(remainingMs(deadlineNs));
        if (wallpaperCtl  != null) wallpaperCtl .awaitShutdown(remainingMs(deadlineNs));
        // Phase 3 — force-stop survivors and release wallpaper bitmaps.
        if (iconExecutor != null) try { iconExecutor.shutdownNow(); } catch (Throwable ignored) { /* best-effort */ }
        if (appExecutor  != null) try { appExecutor .shutdownNow(); } catch (Throwable ignored) { /* best-effort */ }
        if (iconDiskCache != null) iconDiskCache = null;
        if (iconCache != null) iconCache.evictAll();
        if (bannerCache != null) bannerCache.evictAll();
        iconInflight.clear();
        bannerInflight.clear();
        pendingIconInvalidations.clear();
        // Drop any deferred package-reload runnable that may still be
        // queued on the shelf's looper. The shelf field gets nulled below
        // and {@link #loadApps()} short-circuits on {@code destroyed}, so
        // this is hygiene rather than a hard correctness fix — but a
        // strayed runnable holds an implicit reference to the activity
        // (it's a method-reference: this::loadApps) until the looper
        // drains it, which on a slow ROM can be several hundred ms after
        // the user navigates away.
        RecyclingShelfView sd = shelf;
        if (sd != null) sd.removeCallbacks(pkgReloadRunnable);
        // Wallpaper teardown — controller recycles its own bitmaps and
        // clears the ImageView drawables. Keeps the activity from having
        // to know about wallpaper memory hygiene at all.
        if (wallpaperCtl != null) { wallpaperCtl.releaseBitmaps(); wallpaperCtl = null; }
        wallpaperFront = null; wallpaperBack = null; clockView = null; shelf = null;
        drawer = null;
        netBtn = null; ringView = null; root = null;
        mapperBtnView = null;
        settingsOverlay = null; settingsCard = null; settingsColumn = null;
        menuOverlay = null; menuUninstall = null; menuAppInfo = null; menuMove = null;
        keymapOverlay = null; keymapColumn = null; keymapCard = null;
        keymapPickerView = null; keymapPickerTitle = null;
        keymapPickerHsv = null; keymapPickerStrip = null;
        keymapHideView = null; keymapHideTitle = null;
        keymapHideScroll = null; keymapHideStrip = null;
        super.onDestroy();
    }

    /** Await a single executor up to the shared deadline. No-op when
     *  {@code ex} is null. Used by {@link #onDestroy} to spread one
     *  300 ms wall-clock budget across multiple executors. */
    private static void awaitOrSkip(ExecutorService ex, long deadlineNs) {
        if (ex == null) return;
        long ms = remainingMs(deadlineNs);
        if (ms <= 0) return;
        try { ex.awaitTermination(ms, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Milliseconds remaining until {@code deadlineNs}. Clamped to zero
     *  so callers don't pass a negative timeout to {@code awaitTermination}
     *  (which is documented to wait forever on negative input). */
    private static long remainingMs(long deadlineNs) {
        long ns = deadlineNs - System.nanoTime();
        return ns <= 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(ns);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (iconCache == null) return;
        if      (level >= TRIM_MEMORY_COMPLETE)   {
            iconCache.evictAll(); iconInflight.clear();
            if (bannerCache != null) bannerCache.evictAll();
            bannerInflight.clear();
            RecyclingShelfView sv = shelf;
            if (sv != null) {
                sv.setApps(Collections.emptyList());
                for (int i = 0; i < sv.pool.size(); i++) sv.pool.get(i).iconBitmap = null;
            }
            appList.clear();
            // Mirror appList's clear so findAppByPackage doesn't return a
            // stale AppInfo whose backing identity the user just trimmed.
            // The next loadApps() (1 s post) repopulates both atomically.
            appByPackage.clear();
            // Reuse the cached {@link #pkgReloadRunnable} instead of a
            // fresh {@code this::loadApps} method-reference. Same effect
            // (clears the changed-while-paused flag and runs loadApps),
            // saves one Runnable allocation, and lets a future
            // {@code uiHandler.removeCallbacks(pkgReloadRunnable)} call
            // cancel this delayed reload alongside any pending receiver
            // post.
            uiHandler.postDelayed(pkgReloadRunnable, 1000);
        }
        // MODERATE / BACKGROUND: trim the in-memory bitmap cache only.
        // The {@code iconInflight} map is intentionally left untouched —
        // clearing it while executor tasks still hold the captured
        // {@code waiters} list orphans those tasks: the next bind for the
        // same package re-inserts a fresh waiters list and the executor
        // ends up running TWO concurrent decodes for the same icon, both
        // of which call {@code iconCache.put} and write to the disk cache.
        // The natural completion path
        // ({@code iconInflight.remove(key)} inside the executor's UI body)
        // drains the map without any extra bookkeeping.
        else if (level >= TRIM_MEMORY_MODERATE)   { iconCache.trimToSize(iconCache.maxSize() / 2);
            if (bannerCache != null) bannerCache.trimToSize(bannerCache.maxSize() / 2); }
        else if (level >= TRIM_MEMORY_BACKGROUND) { iconCache.trimToSize(iconCache.maxSize() * 3 / 4);
            if (bannerCache != null) bannerCache.trimToSize(bannerCache.maxSize() * 3 / 4); }
    }

    @Override public void onWindowFocusChanged(boolean h) { super.onWindowFocusChanged(h); if (h) hideSystemUI(); }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Safety net: any touch during an active reorder session should back
        // out of reorder mode rather than leak through to whatever's
        // underneath (e.g. the shelf's own scroll handling). This used to be
        // gated on the menu's visibility, since the menu was visible for the
        // entire reorder session. It no longer is — the menu hides itself
        // once the user starts moving the app with D-pad L/R — so the gate
        // is now reorderMode itself. While the menu IS visible, a tap inside
        // its bounds is still treated as a normal click on its buttons,
        // exactly as before; everywhere else (including the entire screen
        // once the menu's hidden) backs out.
        RecyclingShelfView s0 = shelf;
        AppDrawer d0 = drawer;
        boolean shelfReorder  = s0 != null && s0.reorderMode;
        boolean drawerReorder = d0 != null && d0.getVisibility() == View.VISIBLE && d0.reorderMode;
        if (ev.getAction() == MotionEvent.ACTION_DOWN && (shelfReorder || drawerReorder)) {
            boolean inside = false;
            if (menuOverlay != null && menuOverlay.getVisibility() == View.VISIBLE) {
                int mw = menuOverlay.getWidth();
                int mh = menuOverlay.getHeight();
                // Fall back to measured size if layout hasn't run yet (first show)
                if (mw == 0) mw = menuOverlay.getMeasuredWidth();
                if (mh == 0) mh = menuOverlay.getMeasuredHeight();
                if (mw > 0 && mh > 0) {
                    int[] loc = menuOverlayLoc;
                    menuOverlay.getLocationOnScreen(loc);
                    float tx = ev.getRawX(), ty = ev.getRawY();
                    inside = tx >= loc[0] && tx <= loc[0] + mw
                          && ty >= loc[1] && ty <= loc[1] + mh;
                }
            }
            if (!inside) {
                if (shelfReorder) s0.exitReorderMode(false);
                else              d0.exitReorderMode(false);
                return true; // consume the event
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override @SuppressWarnings("deprecation")
    public void onBackPressed() {
        FrameLayout sp = settingsOverlay;
        if (sp != null && sp.getVisibility() == View.VISIBLE) {
            hideSettingsPanel(); return;
        }
        // v1.5.0: drawer open → exit its Move mode, else close the drawer.
        AppDrawer d = drawer;
        if (d != null && d.getVisibility() == View.VISIBLE) {
            if (d.reorderMode) d.exitReorderMode(false);
            else              closeDrawer();
            return;
        }
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
        computeTileDims();   // re-fit 6 tiles per row for the new screen width
        // Forward the new screen size into the wallpaper controller so its
        // next decode caps to the new dimensions (e.g. HDMI swap on TV
        // changes both screenW and screenH).
        if (wallpaperCtl != null) wallpaperCtl.onConfigurationChanged(screenW, screenH);
        // Invalidate the cached root location — a configuration change
        // is the one path that can move the activity window on screen
        // (HDMI swap on TV, font scale on tablet, multi-window enter).
        // The next {@link #positionRing} call will refresh against the
        // new geometry. See {@link #rootLocCached} for the rationale.
        rootLocCached = false;
        // Drop the per-AppInfo ellipsised-label memo. A density / font-scale
        // change moves each cell's label width budget, so a string truncated
        // against the old metrics could now be too short or too long. Clearing
        // forces the next bind of each app to recompute against the new
        // density. Visible cells keep their current text until they are
        // re-bound (e.g. by the next scroll) — identical to the pre-1.4.5
        // per-cell behaviour, just now coordinated through the shared memo.
        // UI-thread only, so no synchronisation against the icon workers.
        for (int i = 0, n = appList.size(); i < n; i++) appList.get(i).displayLabel = null;
        // Evict the in-memory icon cache. A density change moves
        // dp(ICON_DP), so cached bitmaps are now the WRONG pixel size
        // for the new cells. The IconDiskCache is keyed by pixel size
        // and self-invalidates, but iconCache holds old-size bitmaps
        // under the same package key — CellView.bind would draw them
        // mis-scaled (centred via iconBitmap.getWidth()/2) until they
        // happened to be evicted. Drop them all so the next bind
        // re-decodes at the new size via the disk fast-path. Also clear
        // any in-flight loads keyed to the old size so their delivery
        // doesn't paint a stale-resolution bitmap.
        LruCache<String, Bitmap> ic = iconCache;
        if (ic != null) ic.evictAll();
        iconInflight.clear();
        // Banners are sized in px too — drop them on a density change so the
        // next bind re-renders at the new size.
        LruCache<String, Bitmap> bc = bannerCache;
        if (bc != null) bc.evictAll();
        bannerInflight.clear();
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
        // No persistent hardware layer here. Earlier versions forced
        // LAYER_TYPE_HARDWARE so the cross-fade alpha animation got an
        // offscreen FBO — but that allocates a screen-sized GPU buffer
        // (~8 MB at 1080p, ~32 MB at 4K) for both ImageViews continuously,
        // for the sole benefit of a 200 ms transition. An ImageView is a
        // leaf with a single drawable: alpha applies directly via the
        // BitmapDrawable's paint, so software/none-layer alpha is just
        // as fast and frees ~16 MB / ~64 MB of GPU memory for everything
        // else (icon textures, app thumbnails, system overlays).
        wallpaperBack.setLayerType(View.LAYER_TYPE_NONE, null);
        wallpaperBack.setAlpha(0f);
        // Stack order: BACK is added first (drawn below), FRONT on top. We
        // cross-fade by raising BACK's alpha to 1 then swapping references
        // so the new wallpaper becomes the FRONT for the next change.
        root.addView(wallpaperBack);

        wallpaperFront = new ImageView(this);
        wallpaperFront.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        wallpaperFront.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // See LAYER_TYPE_NONE rationale on wallpaperBack above.
        wallpaperFront.setLayerType(View.LAYER_TYPE_NONE, null);
        root.addView(wallpaperFront);

        // Stand up the wallpaper subsystem now that both ImageViews are
        // attached. {@link WallpaperController} owns its own background
        // executor and loading-guard atomic flags; the activity only calls
        // its small lifecycle / interaction surface.
        wallpaperCtl = new WallpaperController(
                this,
                prefs,
                KEY_WP_URI,
                wallpaperFront, wallpaperBack,
                screenW, screenH,
                FOCUS_EASE,
                this::showToast);
        // Cold-start snapshot pre-paint. Synchronous on the UI thread —
        // we are still inside buildLayout (called from setContentView,
        // before any vsync), so a ~30-50 ms decode is invisible to the
        // user and makes the wallpaper appear in the very first frame.
        // {@link #loadWallpaper()} called later in onCreate is then a
        // no-op (the controller's loadStored short-circuits when the
        // snapshot pre-painted) — no second decode, no flicker.
        wallpaperCtl.loadSnapshotSync();

        shelf = new RecyclingShelfView(this);
        FrameLayout.LayoutParams shelfLp = new FrameLayout.LayoutParams(MATCH, cellHpx);
        shelfLp.gravity = Gravity.BOTTOM;
        shelfLp.setMargins(0, 0, 0, dp(12));
        shelf.setLayoutParams(shelfLp);
        shelf.setContentDescription(getString(R.string.cd_app_shelf));
        shelf.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.addView(shelf);

        clockView = new TextView(this);
        // Clock now wears the same visual vocabulary as the top-right toolbar
        // pills: dark-glass plate + 1 dp white rim, drawn as a capsule
        // (round-rect with radius = height/2). The plate provides clean
        // separation from the wallpaper, so the heavy 14 dp drop shadow that
        // used to sit behind the digits is gone — it was only there because
        // the bare digits had no other contrast. With a real plate the
        // shadow becomes redundant ink that smudges the look on bright
        // wallpapers.
        clockView.setBackground(AppleStyle.makePillBackground(density));
        // Symmetric padding gives the capsule a balanced "premium" feel.
        // Vertical inset matches the toolbar plates' visual weight; the
        // resulting pill height (~ dp(40)) lines up with BTN_VIEW_SZ so
        // the clock and the toolbar buttons sit on the same horizontal
        // baseline at the top of the screen.
        clockView.setPadding(dp(16), dp(7), dp(16), dp(7));
        clockView.setIncludeFontPadding(false);
        clockView.setContentDescription(getString(R.string.cd_clock));
        clockView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams clkLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        clkLp.gravity = Gravity.TOP | Gravity.START;
        // Mirror the toolbar's MARG_E (dp 16) on the start side, and use
        // the same MARG_T (dp 14) on top, so the left-edge clock and the
        // right-edge toolbar are visually symmetric.
        clkLp.setMarginStart(dp(16));
        clkLp.topMargin = dp(14);
        clockView.setLayoutParams(clkLp);
        clockView.setTextColor(Color.WHITE);
        // Compact 22 sp — was 44 sp. The plate frames the digits now,
        // so the text doesn't need to carry all the visual weight on
        // its own. 22 sp keeps the time legible from across a TV room
        // while sitting quietly in the corner.
        clockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        // sans-serif-medium reads as confident without the chunkiness
        // of BOLD inside a smaller pill. AM/PM still drops to thin via
        // the TypefaceSpan in {@link ClockFormatter}.
        clockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clockView.setLetterSpacing(0.02f);
        // If the user has opted out of the clock pill, hide it before the
        // first paint so cold start never flashes a visible-then-hidden
        // pill. {@link #startClock} also enforces this on every resume,
        // but doing it here avoids the one-frame visibility flicker on
        // slow-laying-out ROMs.
        if (!showClock) clockView.setVisibility(View.GONE);
        root.addView(clockView);

        // Minimal-pill sizing. Earlier values (52 / 36 / 6 / 18 / 20) read
        // chunky against the rest of the launcher's "bare" identity; the
        // smaller cluster sits more quietly in the top-right and gives the
        // wallpaper more presence. Numbers below are tuned together:
        //
        //   BTN_VIEW_SZ — the layer/clip-outline diameter, which also caps
        //                 the focused plate (drawn at scale 1.0). Picked so
        //                 (BTN_VIEW_SZ * 1.04 focus-pop) is still well
        //                 under the dp(48) "minimum touch target" guide
        //                 only for layout-density purposes; the actual
        //                 touch hit-test extends across the full layer box.
        //   BTN_SZ      — semantic glyph hint passed to the factory. The
        //                 factories don't read it directly (they size from
        //                 getWidth/getHeight), but it pins intent for any
        //                 future caller and keeps the public signature.
        //   BTN_GAP     — tight cluster: 4 dp reads as "set" not "stack".
        //   MARG_T/MARG_E — top-right corner inset, slightly tighter so
        //                 the smaller buttons hug the screen edge.
        //
        // Color philosophy is unchanged: dark glass idle plate, frosted
        // near-white focused plate, hairline white rim, glyph inverts on
        // focus. See AppleStyle.makeBgIdlePaint / makeBgFocusPaint /
        // makeRimPaint — every paint factory remains untouched, so the
        // visual vocabulary is identical, just smaller.
        final int BTN_SZ      = dp(28);
        final int BTN_VIEW_SZ = dp(40);  // 1.04× focus pop = 41.6 dp; clip outline scales with the view
        final int BTN_GAP     = dp(4);
        final int MARG_T      = dp(14);
        final int MARG_E      = dp(16);

        // Shared dim backdrop for the settings panel and the keymap card.
        // Added to root z-order BEFORE the toolbar pills so both modal
        // overlays can sit above it (their show* methods bring themselves
        // to front, putting them above the backdrop). GONE by default —
        // the backdrop only exists during a modal flow.
        //
        // One shared backdrop avoids the v1.3.0 initial-design dim flicker
        // where transitioning settings → keymap fade-out a 0x33-black
        // backdrop while fading in another 0x33-black backdrop on top of
        // it, briefly compositing ~0x5C and reading as "the wallpaper just
        // went darker for half a second". With one persistent backdrop the
        // dim level stays constant across the entire modal flow regardless
        // of how the user navigates between the two surfaces.
        overlayBackdrop = new View(this);
        overlayBackdrop.setBackgroundColor(0x33000000); // 20 % dim
        overlayBackdrop.setVisibility(View.GONE);
        overlayBackdrop.setAlpha(0f);
        // Clickable so taps on the dim region don't pass through to the
        // shelf cells underneath. The active overlay's onTouchEvent
        // handles tap-outside-the-card dismissal; the backdrop just
        // absorbs everything else.
        overlayBackdrop.setClickable(true);
        root.addView(overlayBackdrop, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Top-right toolbar buttons. Layout left-to-right after the v1.3.3
        // swap that moved the WiFi pill to its leftmost-in-cluster
        // position and the gear pill to the right edge:
        //
        //     [ wifi ]   [ ⚙ gear ]
        //
        // Why swap from the v1.3.0 [ ⚙ ] [ wifi ] order: WiFi is the
        // single daily-frequent action on the toolbar (people dig into
        // network settings far more often than the consolidated config
        // panel). Putting it leftmost in the cluster lines it up with
        // the visual centre-of-mass of the home shelf below — a TV
        // remote user pressing UP from any shelf cell lands on WiFi,
        // a single keypress away from the most common destination.
        // The gear pill takes the right-edge slot — slightly out of the
        // primary glance path, but still discoverable as the second pill
        // and reachable in one extra D-pad RIGHT keypress.
        //
        // Both pills are positioned from the right edge: gear sits flush
        // at MARG_E (rightmost), WiFi sits one (BTN_VIEW_SZ + BTN_GAP)
        // step further left (the "leftmost-in-cluster" slot). Cell-up
        // navigation already routes to WiFi via netBtn.requestFocus so
        // that requirement carries over cleanly from v1.3.0 — only the
        // physical pill positions and the per-pill LEFT/RIGHT/DOWN key
        // chains needed updating.
        netBtn = buildNetBtn(BTN_SZ);
        FrameLayout.LayoutParams netLp = new FrameLayout.LayoutParams(BTN_VIEW_SZ, BTN_VIEW_SZ);
        netLp.gravity = Gravity.TOP | Gravity.END;
        netLp.topMargin = MARG_T;
        // One stride step from the right edge (left of the gear pill).
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
        // Flush at the right edge.
        mpLp.setMarginEnd(MARG_E);
        mpLocal.setLayoutParams(mpLp);
        mpLocal.setContentDescription(getString(R.string.cd_settings));
        root.addView(mpLocal);

        // v1.5.0 pull-down app drawer. Added to the z-order ABOVE the
        // wallpaper / shelf / clock / toolbar so that when it is shown its own
        // scrim cleanly covers them, but BELOW the ring (added next) so the
        // focus halo still draws over the drawer's cells. GONE until the user
        // presses DPAD_DOWN on a home cell; the lazy context-menu overlay
        // (added last, on first reorder) stays above everything including the
        // ring, exactly as on the home shelf.
        drawer = new AppDrawer(this);
        drawer.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        drawer.setVisibility(View.GONE);
        drawer.setContentDescription(getString(R.string.cd_app_drawer));
        drawer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.addView(drawer);

        int strokePx = dp(RING_STROKE_DP);
        // Selection ring wraps the Apple-TV banner tile (landscape rounded
        // rect). Box = banner + headroom for the focus scale-up.
        int bw = tileWpx, bh = bannerHpx;
        int ringBoxW = bw + dp(12), ringBoxH = bh + dp(12);
        ringLayoutW    = ringBoxW;
        ringLayoutH    = ringBoxH;
        cachedIcyOffset = bh / 2f;  // banner centred at top of the cell
        ringView = new RingView(this, strokePx, bw, bh, tileCornerPx);
        FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(ringBoxW, ringBoxH);
        ringView.setLayoutParams(ringLp);
        ringView.setVisibility(View.INVISIBLE);
        ringView.setContentDescription(getString(R.string.cd_selection_ring));
        root.addView(ringView);

        // The reorder-mode context menu overlay (~10 views, 3 paint
        // backgrounds, 3 click listeners) is built lazily on first
        // {@link RecyclingShelfView#enterReorderMode} entry. The overlay
        // is only visible while the user is rearranging icons — a
        // workflow that fires 0× on the cold-start path and 0× for
        // users who never long-press a shelf cell. Pre-building it
        // costs ~5-15 ms of cold-start view-tree work for a feature
        // most users never touch. See {@link #ensureMenuOverlay}.

        return root;
    }

    /** Build the reorder-mode context menu overlay on first use and add
     *  it to the root view tree. Subsequent calls are no-ops — the overlay
     *  is reused across every reorder session for the lifetime of the
     *  activity.
     *
     *  <p>Deferred from {@link #buildLayout()} so cold-start does not pay
     *  for the ~10 view allocations + 3 click-listener wiring of a feature
     *  most users never trigger. The pattern matches the existing lazy
     *  init for {@link #buildKeymapOverlay()}.
     *
     *  <p>Pre-condition for safe operation of {@link #showContextMenu(View)},
     *  {@link #hideContextMenu()}, {@link #updateMenuHighlight()} — those
     *  three already null-guard their entry, so a missed call here would
     *  produce a silent no-op rather than an NPE. The single unique
     *  call site is {@link RecyclingShelfView#enterReorderMode(int)},
     *  which is the only path that transitions the activity into a state
     *  where the overlay must be visible. */
    private void ensureMenuOverlay() {
        if (menuOverlay != null) return;
        FrameLayout r = root;
        if (r == null) return;

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
            ReorderHost h = menuHost;
            if (h != null) h.onMenuUninstall();
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
            ReorderHost h = menuHost;
            if (h != null) h.onMenuAppInfo();
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
            ReorderHost h = menuHost;
            if (h != null) h.onMenuMove();
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
        r.addView(menuOverlay);
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

        int iconTopInRoot = cellRelY + (int)(cachedIcyOffset - bannerHpx / 2f);
        int iconBotInRoot = iconTopInRoot + bannerHpx;

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
        ReorderHost h = menuHost; if (h == null) return;
        if (menuUninstall == null || menuAppInfo == null || menuMove == null) return;
        int sel = h.menuSelection();
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
        // Long-press intentionally NOT bound. The system-Settings shortcut
        // moved to the gear pill in v1.3.0 (it's the most common
        // destination from the panel and now lives next to the gear's
        // short-press = "open panel" entry). Leaving WiFi long-press
        // unbound reserves it for a future power-user shortcut without
        // committing to a feature now. Important: we do NOT register an
        // OnLongClickListener that returns true; doing so would swallow
        // long-press events. Without a listener, long-press is a no-op
        // and short-press still fires cleanly on key UP / touch UP.
        v.setOnFocusChangeListener((view, f) -> {
            view.animate().cancel();
            view.animate().scaleX(f ? BTN_FOCUS_SCALE : 1f).scaleY(f ? BTN_FOCUS_SCALE : 1f)
                    .setDuration(100).setInterpolator(FOCUS_EASE).start();
            view.invalidate();
        });
        v.setOnKeyListener((view, kc, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (kc) {
                // DPAD_CENTER / ENTER / BUTTON_A intentionally NOT
                // intercepted here. Letting them fall through preserves
                // the platform's short-click on key UP. Long-press is
                // unbound (see comment above) so there is no
                // OnLongClickListener to compete with.
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // WiFi is the leftmost-in-cluster button now (v1.3.3
                    // swap). Its DOWN lands on the FIRST shelf cell so
                    // the d-pad model "below me is the cell visually
                    // under me" stays consistent — first cell sits
                    // furthest left, gear pill is at the right edge.
                    RecyclingShelfView sd = shelf;
                    if (sd != null) sd.requestFocusOnIndex(0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // Leftmost in the toolbar cluster — wrap to the last
                    // shelf cell. Symmetric with the gear's RIGHT-wraps-
                    // to-first-shelf-cell behaviour.
                    RecyclingShelfView sl = shelf;
                    if (sl != null) sl.requestFocusOnIndex(sl.lastIndex());
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // Gear is the only neighbour to the right.
                    View mb = mapperBtnView;
                    if (mb != null) { mb.requestFocus(); return true; }
                    RecyclingShelfView sr = shelf;
                    if (sr != null) sr.requestFocusOnIndex(0);
                    return true;
                default: return false;
            }
        });
        return v;
    }

    /** Third toolbar pill — opens the unified settings panel (which
     *  hosts hide-apps / button-shortcuts / wallpaper / system-settings /
     *  show-clock toggle as a vertical row list). Matches the netBtn glass
     *  aesthetic exactly: dark idle plate, frosted-white focused plate,
     *  glyph inverts on focus. The icon is a gear (universal "settings"
     *  symbol). Drawn entirely with Canvas primitives — zero new
     *  resources.
     *
     *  <p>Long-press opens system Settings directly (the most common
     *  destination from the panel). Short-press opens the panel as the
     *  discoverable, full-menu entry point. */
    private View buildMapperBtn(int sz) {
        View v = new View(this) {
            // The fill paint owns one Paint instance reused across every
            // gear draw — no per-frame allocation. The bg / rim paints
            // are factory-built (shared style with the rest of the
            // toolbar pills) and untouched by drawGearGlyph.
            private final Paint fill      = new Paint(Paint.ANTI_ALIAS_FLAG);
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

                Paint plate = focused ? bgFocus : bgIdle;
                c.drawCircle(cx, cy, r, plate);
                c.drawCircle(cx, cy, r - rim.getStrokeWidth() / 2f, rim);

                // Solid filled gear (v1.3.3 redesign — was a stroke-only
                // line gear). The hole punches through with the SAME
                // plate colour the pill body was just drawn with, so
                // the cut-out reads continuous against the underlying
                // dim backdrop / wallpaper.
                int symbolColor = focused ? AppleStyle.SYMBOL_FOCUSED : AppleStyle.SYMBOL_IDLE;
                AppleStyle.drawGearGlyph(c, cx, cy, r,
                        symbolColor, plate.getColor(), fill);
            }
        };
        applyApplePillStyle(v);
        v.setOnClickListener(view -> {
            view.playSoundEffect(SoundEffectConstants.CLICK);
            showSettingsPanel();
        });
        // Long-press → general system Settings. The most common
        // destination from the panel and the muscle-memory shortcut
        // moved over from the WiFi pill in v1.3.0. Discoverable via the
        // standard "press and hold" gesture (TV remote: hold
        // DPAD_CENTER; touch: long-press). The short click still opens
        // the unified settings panel — long-press is purely an
        // additional shortcut.
        v.setOnLongClickListener(view -> {
            view.playSoundEffect(SoundEffectConstants.CLICK);
            openSystemSettings();
            return true;
        });
        v.setOnFocusChangeListener((view, f) -> {
            view.animate().cancel();
            view.animate().scaleX(f ? BTN_FOCUS_SCALE : 1f).scaleY(f ? BTN_FOCUS_SCALE : 1f)
                    .setDuration(100).setInterpolator(FOCUS_EASE).start();
            view.invalidate();
        });
        v.setOnKeyListener((view, kc, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (kc) {
                // DPAD_CENTER / ENTER / BUTTON_A intentionally NOT
                // intercepted — letting them fall through preserves the
                // platform's long-press detection (which fires our
                // OnLongClickListener after the system long-press
                // timeout) while still triggering the short
                // OnClickListener on key UP.
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // Gear is the rightmost button now (v1.3.3 swap).
                    // Down lands on the LAST shelf cell so the d-pad
                    // model "below me is the cell visually under me"
                    // stays consistent — last cell sits at the right
                    // edge, WiFi pill is one stride further left.
                    RecyclingShelfView s = shelf;
                    if (s != null) s.requestFocusOnIndex(s.lastIndex());
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // WiFi is the only neighbour to the left.
                    View nb = netBtn; if (nb != null) nb.requestFocus(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // Rightmost in the toolbar cluster — wrap to the
                    // first shelf cell. Symmetric with the WiFi pill's
                    // LEFT-wraps-to-last-shelf-cell behaviour.
                    RecyclingShelfView sr = shelf;
                    if (sr != null) sr.requestFocusOnIndex(0);
                    return true;
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

    /** Open the device's general system Settings. Bound to the WiFi
     *  pill's long-press so the most-needed-second-tier shortcut is
     *  one gesture away from the most-used first-tier shortcut. Falls
     *  back to a toast if the device has no Settings activity (very
     *  unusual, mostly stripped Android Auto / kiosk ROMs). */
    private void openSystemSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            showToast(getString(R.string.toast_no_settings));
        }
    }

    final class RecyclingShelfView extends ViewGroup implements ReorderHost {

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

        /** One-shot: when true, the next focus posted by {@link #setApps} uses
         *  the snap (no-bounce) path. Set by {@code closeDrawer} so returning
         *  to the home row is a calm, subtle transition rather than a spring. */
        boolean snapNextFocus = false;

        boolean reorderMode   = false;
        int     dragIndex     = -1;

        // True once the user has pressed LEFT/RIGHT during the current
        // reorder session — i.e. they've actually started moving the app.
        // While true, swapWithNeighbour() keeps the context menu hidden
        // instead of re-showing it on every swap, so the menu doesn't sit
        // on top of (and obscure) the icon sliding into place. Reset on
        // every enter/exit so a fresh long-press always shows the menu.
        boolean menuDismissedForMove = false;

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
            cellW   = tileWpx;
            cellH   = cellHpx;
            sidePad = dp(10);
            edgePad = dp(48);
            stride  = cellW + sidePad * 2;
            setFocusable(false);
            setClipChildren(false);
            setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        }

        // ── ReorderHost (shared context menu) ────────────────────────────
        @Override public int menuSelection() { return menuSelection; }
        @Override public void onMenuUninstall() {
            if (!reorderMode) return;
            menuSelection = MENU_UNINSTALL;
            CellView cv = attached.get(dragIndex);
            if (cv != null) cv.triggerUninstall(); else exitReorderMode(false);
        }
        @Override public void onMenuAppInfo() {
            if (!reorderMode) return;
            menuSelection = MENU_APP_INFO;
            CellView cv = attached.get(dragIndex);
            if (cv != null) cv.triggerAppInfo(); else exitReorderMode(false);
        }
        @Override public void onMenuMove() {
            if (!reorderMode) return;
            menuSelection = MENU_MOVE;
            LauncherActivity.this.updateMenuHighlight();
            exitReorderMode(true);   // "Move" confirm persists the order
        }

        void enterReorderMode(int idx) {
            if (reorderMode) return;
            reorderMode   = true;
            dragIndex     = idx;
            menuSelection = MENU_MOVE;
            menuDismissedForMove = false;
            LauncherActivity.this.menuHost = this;   // shelf owns the shared menu now
            rebindAll();
            // Lazy-init the context menu overlay on first entry. Cold start
            // does not pay for this overlay's view-tree construction; users
            // who never long-press a shelf cell never trigger it.
            LauncherActivity.this.ensureMenuOverlay();
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
            menuDismissedForMove = false;
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

        /** Bring the context menu back if a move (swapWithNeighbour) hid it.
         *  Called from DPAD_UP/DOWN before a real selection change — those
         *  keys are explicit "I want to look at the menu" intent, unlike
         *  LEFT/RIGHT which mean "keep moving". showContextMenu() already
         *  cancels any in-flight hide animation and the existing alpha-check
         *  guard in hideContextMenu()'s withEndAction protects against the
         *  cancel/restart race, so no extra synchronization is needed here. */
        private void reshowMenuIfHidden() {
            if (!menuDismissedForMove) return;
            menuDismissedForMove = false;
            CellView cv = attached.get(dragIndex);
            if (cv != null) LauncherActivity.this.showContextMenu(cv);
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
                // Invalidate the chip-strip caches in the keymap overlay.
                // Both strips (hide-manager + keymap picker) are built once
                // and re-used across overlay opens; the rebuild trigger is
                // a size-change check (keymapHideBuiltSize / keymapPickerBuiltSize
                // == appList.size()). A reorder leaves the size unchanged
                // — only positions move — so without this nudge a stale
                // chip strip would survive a swap. The user-visible symptom
                // was "I select chip showing app A, app B gets toggled":
                // chip i still carries the OLD label / icon while
                // toggleSelectedHide and commitKeymapPicker resolve the
                // package via appList[i] at the new position. Same shape
                // of invalidation the package-broadcast handler already
                // does for install / uninstall / replace; reorder is the
                // third class of mutation that needs the same nudge.
                LauncherActivity.this.keymapHideBuiltSize    = -1;
                LauncherActivity.this.keymapPickerBuiltSize  = -1;
                LauncherActivity.this.keymapRowsNeedEqualize = true;
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

            // Once the user has actually started moving (first L/R swap),
            // the menu hides and stays hidden for the rest of this reorder
            // session — it would otherwise sit on top of the icon sliding
            // into place. It reappears only on the next fresh long-press
            // (enterReorderMode resets the flag).
            if (!menuDismissedForMove) {
                menuDismissedForMove = true;
                LauncherActivity.this.hideContextMenu();
            }
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
                    List<IconTarget> waiters = LauncherActivity.this.bannerInflight.get(cv.boundApp.packageName);
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
            for (AppInfo app : displayed) preWarmBanner(app);
            final int targetIdx = focusedIndex;
            final boolean snap = snapNextFocus; snapNextFocus = false;
            post(() -> requestFocusOnIndex(targetIdx, snap));
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
                        List<IconTarget> waiters = LauncherActivity.this.bannerInflight.get(cv.boundApp.packageName);
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
            // Per-frame hot path during fling / programmatic scroll. We only
            // ever change horizontal position here — sizes are fixed at
            // bind time. Using offsetLeftAndRight (a pure mLeft/mRight
            // mutation + parent invalidate) skips the full layout pipeline
            // (onSizeChanged plumbing, requestLayout chains) that
            // View.layout(l,t,r,b) triggers even when the size hasn't
            // actually changed. Visibly reduces dropped frames during
            // fast scrolls on cheap TV ROMs. Cells whose width/height
            // somehow drifted (defensive — should never happen with the
            // recycler) are repaired with a full layout call.
            int top = (getMeasuredHeight() - cellH) / 2;
            int bot = top + cellH;
            for (int i = 0; i < attached.size(); i++) {
                int idx = attached.keyAt(i); CellView cv = attached.valueAt(i);
                int targetLeft = cellLeft(idx);
                if (cv.getWidth() == cellW && cv.getHeight() == cellH
                        && cv.getTop() == top) {
                    int curLeft = cv.getLeft();
                    if (curLeft != targetLeft) {
                        cv.offsetLeftAndRight(targetLeft - curLeft);
                    }
                } else {
                    cv.layout(targetLeft, top, targetLeft + cellW, bot);
                }
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

        final class CellView extends View implements IconTarget {

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
            private final int     bannerW;
            private final int     bannerH;
            private final float   bannerCorner;
            private final float   phStroke;
            private final float   labelOffsetY;
            private final float   labelMaxWInset;
            private final float   icyOffset;
            private final RectF   phRect       = new RectF();
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
                bannerW        = tileWpx;
                bannerH        = bannerHpx;
                bannerCorner   = tileCornerPx;
                phStroke       = dp(1);
                labelOffsetY   = bannerH / 2f + dp(16);  // label below the banner
                labelMaxWInset = dp(6);
                icyOffset      = bannerH / 2f;  // banner centred at the cell top — ring aligns to this

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
                                // Re-show the menu on an actual selection change — UP/DOWN
                                // are explicit menu-navigation keys, so if the menu is
                                // currently hidden (post-move, see menuDismissedForMove)
                                // the user is signalling they want to look at it again.
                                if      (menuSelection == MENU_MOVE)     { reshowMenuIfHidden(); menuSelection = MENU_APP_INFO; updateMenuHighlight(); }
                                else if (menuSelection == MENU_APP_INFO) { reshowMenuIfHidden(); menuSelection = MENU_UNINSTALL; updateMenuHighlight(); }
                                return true;
                            case KeyEvent.KEYCODE_DPAD_DOWN:
                                // Cycle UNINSTALL → APP_INFO → MOVE; DOWN at MOVE confirms.
                                // No reshow on the confirm branch — it's about to exit and
                                // hide anyway, so showing first would just flash the menu.
                                if      (menuSelection == MENU_UNINSTALL) { reshowMenuIfHidden(); menuSelection = MENU_APP_INFO; updateMenuHighlight(); }
                                else if (menuSelection == MENU_APP_INFO)  { reshowMenuIfHidden(); menuSelection = MENU_MOVE;     updateMenuHighlight(); }
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
                            // v1.5.0: DOWN on a home cell pulls down the app
                            // drawer (Apple-TV style). The drawer mirrors the
                            // current order and lands focus on the same app.
                            // If there are no apps to show, openDrawer no-ops
                            // and we still consume so focus stays put (avoids
                            // the platform "focus-blocked" beep on some ROMs).
                            openDrawer();
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
                    float hw = iconBitmap.getWidth() / 2f, hh = iconBitmap.getHeight() / 2f;
                    canvas.drawBitmap(iconBitmap, cx - hw, icy - hh, iconPaint);
                } else {
                    // Rounded-rect banner-tile placeholder.
                    float hw = bannerW / 2f, hh = bannerH / 2f;
                    phRect.set(cx - hw, icy - hh, cx + hw, icy + hh);
                    canvas.drawRoundRect(phRect, bannerCorner, bannerCorner, sPhFill);
                    float in = phStroke / 2f;
                    phRect.set(cx - hw + in, icy - hh + in, cx + hw - in, icy + hh - in);
                    canvas.drawRoundRect(phRect, bannerCorner, bannerCorner, phRing);
                }
            }



            @Override public void setIconBitmap(Bitmap bmp) { iconBitmap = bmp; invalidate(); }

            // ── IconTarget ──────────────────────────────────────────────
            @Override public String  iconTargetPackage() { return boundApp != null ? boundApp.packageName : null; }
            @Override public boolean iconTargetVisible() { return getVisibility() == View.VISIBLE; }

            void bind(AppInfo app, int index) {
                boolean labelChanged = !app.label.equals(labelStr);
                boundApp = app; boundIndex = index; labelStr = app.label;
                setContentDescription(app.label);
                if (labelChanged) {
                    // Reuse the per-AppInfo memoised display label when one
                    // sibling cell has already computed it. The width budget
                    // (cell width − inset), the label text size, and the
                    // typeface are constant for the activity's lifetime, so
                    // the truncated string is byte-identical for every cell
                    // that ever renders this app — only the first bind pays
                    // the measure + (on overflow) the ellipsize allocation.
                    // A recycled cell scrolling back onto an app it showed a
                    // moment ago during a fling now reads the cache instead
                    // of re-measuring + re-allocating on the scroll hot path.
                    // {@link AppInfo#displayLabel} is cleared on a density /
                    // font-scale change so the truncation stays correct.
                    String disp = app.displayLabel;
                    if (disp == null) {
                        float maxW = bannerW - labelMaxWInset;
                        disp = labelPaint.measureText(labelStr) > maxW
                                ? TextUtils.ellipsize(labelStr, labelTp, maxW, TextUtils.TruncateAt.END).toString()
                                : labelStr;
                        app.displayLabel = disp;
                    }
                    labelDisplay = disp;
                }
                Bitmap cached = bannerCache.get(app.packageName);
                if (cached != null) {
                    if (cached != iconBitmap) { iconBitmap = cached; invalidate(); }
                } else {
                    // Icon not yet loaded — clear any stale bitmap and request load
                    if (iconBitmap != null) { iconBitmap = null; invalidate(); }
                    loadBannerAsync(app, this);
                }
            }
        }
    }

    /**
     * v1.5.0 pull-down app drawer — a vertical recycling grid that reuses the
     * exact same recycling technique as {@link RecyclingShelfView}: a
     * {@code pool} of recycled cells, an {@code attached} {@link SparseArray}
     * keyed by flat app index, and an {@link OverScroller} for fling. It is
     * the drawer counterpart of the horizontal shelf and shares the icon
     * pipeline (via {@link IconTarget}), the focus {@link RingView}, and the
     * reorder context menu (via {@link ReorderHost}).
     *
     * <h3>Layout (driven by {@link HomeDrawerModel})</h3>
     * {@link HomeDrawerModel#COLS} cells per row. Row 0 is the home row — the
     * first {@code homeCount} apps, rendered centred to mirror the bottom home
     * shelf exactly. Rows 1+ hold the remaining apps, {@code COLS} per row,
     * left-aligned within a horizontally-centred grid block; the last row is a
     * left-aligned remainder. {@code displayed} is the visible (non-hidden)
     * app list and the rendering source of truth, identical in spirit to the
     * shelf's {@code displayed}.
     *
     * <h3>Reorder (two stage)</h3>
     * A long-press opens the shared Move / App Info / Uninstall menu (stage 1,
     * D-pad UP/DOWN cycles, CENTER confirms). Choosing Move enters the active
     * 2-D move (stage 2) where UP/DOWN/LEFT/RIGHT relocate the app via
     * {@link HomeDrawerModel}; pushing an app up across the home boundary
     * promotes it into the home row, pushing a home app down demotes it. Each
     * move mirrors the new order into {@link #appList} (keeping hidden apps
     * pinned) so a mid-reorder reconcile stays consistent; the order +
     * {@code homeCount} are persisted on commit.
     */
    final class AppDrawer extends ViewGroup implements ReorderHost {

        private static final int BUFFER_ROWS = 2;

        private final ArrayList<DrawerCell>   pool     = new ArrayList<>(HomeDrawerModel.COLS * 4);
        private final SparseArray<DrawerCell> attached = new SparseArray<>();
        private final OverScroller scroller;
        private VelocityTracker velTracker;
        private float lastTouchY;
        private boolean touchScrolling = false;

        /** Visible (non-hidden) app list — the drawer's rendering source of
         *  truth, snapshotted from the activity on every {@link #setApps}. */
        private final ArrayList<AppInfo> displayed = new ArrayList<>();

        private final int cellW, cellH, sidePad, stride, rowGap, rowStride, topPad, bottomPad;
        private int gridLeft = 0;   // left edge of the centred grid block (rows 1+)
        private int scrollY  = 0;
        private int contentH = 0;
        private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        int     focusedIndex = 0;
        boolean reorderMode  = false;
        boolean moveActive   = false;   // stage 2: D-pad performs 2-D moves
        int     dragIndex    = -1;
        int     menuSelection = RecyclingShelfView.MENU_MOVE;
        boolean fastNav      = false;

        AppDrawer(Context ctx) {
            super(ctx);
            scroller = new OverScroller(ctx, SCROLL_EASE);
            cellW     = tileWpx;
            cellH     = cellHpx;
            sidePad   = dp(10);
            stride    = cellW + sidePad * 2;
            rowGap    = dp(14);
            rowStride = cellH + rowGap;
            topPad    = dp(28);
            bottomPad = dp(28);
            // Frosted-white drawer surface. On Android 12+ a translucent white
            // veil sits over the GPU-blurred wallpaper (see applyDrawerBlur)
            // for a real frosted-glass look; older devices get a near-opaque
            // light veil. Clickable so touches don't fall through to the shelf.
            setBackgroundColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? 0x66FFFFFF      // ~40% white over the blurred wallpaper
                    : 0xE6ECECF0);    // light, near-opaque (no blur fallback)
            setFocusable(false);
            setClickable(true);
            setClipChildren(false);
            setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
            // We paint a thin divider between the home row and the grid in
            // onDraw, so opt out of the ViewGroup WILL_NOT_DRAW shortcut.
            setWillNotDraw(false);
            dividerPaint.setStyle(Paint.Style.STROKE);
            dividerPaint.setColor(0x26FFFFFF);     // subtle white hairline (matches the white drawer content)
            dividerPaint.setStrokeWidth(dp(1));
        }

        /** Clamped, effective home-row size for the current visible count. */
        private int hc() { return LauncherActivity.this.effectiveHomeCount(displayed.size()); }

        // ── ReorderHost (shared context menu) ────────────────────────────
        @Override public int menuSelection() { return menuSelection; }
        @Override public void onMenuUninstall() {
            if (!reorderMode) return;
            menuSelection = RecyclingShelfView.MENU_UNINSTALL;
            triggerUninstall();
        }
        @Override public void onMenuAppInfo() {
            if (!reorderMode) return;
            menuSelection = RecyclingShelfView.MENU_APP_INFO;
            triggerAppInfo();
        }
        @Override public void onMenuMove() {
            if (!reorderMode) return;
            menuSelection = RecyclingShelfView.MENU_MOVE;
            enterActiveMove();   // Move confirm → stage 2 (2-D move)
        }

        void setApps(List<AppInfo> apps, int hcIgnored) {
            if (reorderMode) exitReorderMode(false);
            hideContextMenu();
            for (int i = 0; i < attached.size(); i++) {
                DrawerCell cv = attached.valueAt(i);
                if (cv.boundApp != null) {
                    List<IconTarget> waiters = LauncherActivity.this.bannerInflight.get(cv.boundApp.packageName);
                    if (waiters != null) waiters.remove(cv);
                }
                cv.iconBitmap = null;
                cv.setVisibility(GONE); pool.add(cv);
            }
            attached.clear();
            displayed.clear();
            if (apps != null && !apps.isEmpty()) displayed.addAll(apps);
            if (displayed.isEmpty()) { focusedIndex = 0; scrollY = 0; }
            else focusedIndex = Math.min(focusedIndex, displayed.size() - 1);
            recomputeContentHeight();
            requestLayout();
            // No eager pre-warm of the whole list: banner tiles are heavier
            // than chip icons, so cells load their banner lazily on bind
            // (visible rows only) via loadBannerAsync — fillVisible warms more
            // as the grid scrolls. Pre-warming all N here would decode every
            // app's banner up front, which the user explicitly flagged as a
            // perf concern.
            // Focus is normally driven by open(); but if a package-broadcast
            // reconcile rebuilds us while the drawer is already open, re-focus
            // the (clamped) current index after the relayout so focus is not
            // silently lost mid-browse.
            if (getVisibility() == View.VISIBLE && !displayed.isEmpty()) {
                final int fi = focusedIndex;
                post(() -> { if (getVisibility() == View.VISIBLE) requestFocusOnIndex(fi, true); });
            }
        }

        private void recomputeContentHeight() {
            int rows = HomeDrawerModel.rowCount(displayed.size(), hc());
            contentH = topPad + blockHeight(rows) + bottomPad;
        }

        /** Pixel height of the {@code rows} themselves (no leading/trailing
         *  padding): rows*cellH + gaps between them. */
        private int blockHeight(int rows) {
            if (rows <= 0) return 0;
            return rows * cellH + (rows - 1) * rowGap;
        }

        /** Content-space Y of row 0. When the whole grid fits on screen (few
         *  apps) the block is centred vertically for an Apple-TV look;
         *  otherwise it starts at {@code topPad} and scrolls. */
        private int firstRowTop() {
            int rows = HomeDrawerModel.rowCount(displayed.size(), hc());
            int bh = blockHeight(rows);
            int vh = getHeight();
            if (vh > 0 && topPad + bh + bottomPad <= vh) {
                return Math.max(topPad, (vh - bh) / 2);
            }
            return topPad;
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            // MATCH_PARENT in both axes — the FrameLayout passes EXACTLY specs.
            setMeasuredDimension(
                    resolveSize(getSuggestedMinimumWidth(),  wSpec),
                    resolveSize(getSuggestedMinimumHeight(), hSpec));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l;
            if (w > 0) gridLeft = Math.max(dp(24), (w - HomeDrawerModel.COLS * stride) / 2);
            scrollY = clampScrollY(scrollY);
            fillVisible();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w > 0) gridLeft = Math.max(dp(24), (w - HomeDrawerModel.COLS * stride) / 2);
            recomputeContentHeight();
            repositionAttached(); fillVisible();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (velTracker != null) { velTracker.recycle(); velTracker = null; }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Thin modern divider between the home row (row 0) and the rest of
            // the drawer grid. Only when there is a home row AND at least one
            // app below it. Drawn in the row gap so it never overlaps an icon;
            // scrolls with the content.
            int hc = hc();
            if (hc <= 0 || displayed.size() <= hc) return;
            int base = firstRowTop();
            float y = base + cellH + rowGap / 2f - scrollY;
            int h = getHeight();
            if (y < 0 || y > h) return;
            int left  = gridLeft + sidePad;
            int right = gridLeft + HomeDrawerModel.COLS * stride - sidePad;
            canvas.drawLine(left, y, right, y, dividerPaint);
        }

        private int rowLeftPad(int row, int len) {
            int hc = hc();
            if (hc > 0 && row == 0) {
                // Home row: centre its (≤ COLS) cells within the view width,
                // pixel-mirroring the bottom home shelf's centring.
                int w = getWidth() > 0 ? getWidth() : screenW;
                return Math.max(dp(24), (w - len * stride) / 2);
            }
            return gridLeft;   // rows 1+ left-aligned at the centred block edge
        }

        private int cellLeft(int index) {
            int hc = hc();
            int row = HomeDrawerModel.rowOf(index, hc);
            int col = HomeDrawerModel.colOf(index, hc);
            int len = HomeDrawerModel.rowLength(row, displayed.size(), hc);
            return rowLeftPad(row, len) + col * stride + sidePad;
        }
        private int cellTop(int index) {
            int row = HomeDrawerModel.rowOf(index, hc());
            return firstRowTop() + row * rowStride - scrollY;
        }

        private int scrollYMax() { return Math.max(0, contentH - getHeight()); }
        private int clampScrollY(int y) { return Math.max(0, Math.min(y, scrollYMax())); }

        private void fillVisible() {
            int h = getHeight();
            if (h == 0 || displayed.isEmpty()) return;
            int hc = hc();
            int size = displayed.size();
            int rows = HomeDrawerModel.rowCount(size, hc);
            int base = firstRowTop();
            int firstRow = Math.max(0, (scrollY - base) / rowStride - BUFFER_ROWS);
            int lastRow  = Math.min(rows - 1, (scrollY + h - base) / rowStride + BUFFER_ROWS);
            // Detach cells whose row scrolled out (or whose index is now stale).
            for (int i = attached.size() - 1; i >= 0; i--) {
                int idx = attached.keyAt(i);
                int row = HomeDrawerModel.rowOf(idx, hc);
                if (idx >= size || row < firstRow || row > lastRow) {
                    DrawerCell cv = attached.valueAt(i);
                    if (cv.boundApp != null) {
                        List<IconTarget> waiters = LauncherActivity.this.bannerInflight.get(cv.boundApp.packageName);
                        if (waiters != null) waiters.remove(cv);
                    }
                    cv.iconBitmap = null;
                    cv.setVisibility(GONE); pool.add(cv); attached.removeAt(i);
                }
            }
            // Attach the cells that are now in range.
            for (int row = firstRow; row <= lastRow; row++) {
                int len = HomeDrawerModel.rowLength(row, size, hc);
                for (int col = 0; col < len; col++) {
                    int idx = HomeDrawerModel.indexAt(row, col, size, hc);
                    if (idx < 0) continue;
                    if (attached.get(idx) != null) continue;
                    DrawerCell cv = obtainCell(); bindCell(cv, idx); attached.put(idx, cv);
                }
            }
        }

        private DrawerCell obtainCell() {
            if (!pool.isEmpty()) {
                DrawerCell cv = pool.remove(pool.size() - 1);
                cv.animate().cancel();
                cv.animate().setUpdateListener(null).setListener(null);
                cv.setScaleX(1f); cv.setScaleY(1f);
                cv.setTranslationX(0f); cv.setTranslationY(0f);
                cv.setAlpha(1f);
                cv.iconBitmap = null; cv.boundApp = null; cv.boundIndex = -1;
                cv.setVisibility(VISIBLE); cv.invalidate();
                return cv;
            }
            DrawerCell cv = new DrawerCell(getContext()); addView(cv); return cv;
        }

        private void bindCell(DrawerCell cv, int index) {
            if (index < 0 || index >= displayed.size()) return;
            AppInfo app = displayed.get(index);
            int left = cellLeft(index), top = cellTop(index);
            cv.bind(app, index);
            cv.layout(left, top, left + cellW, top + cellH);
            cv.invalidate();
        }

        private void repositionAttached() {
            for (int i = 0; i < attached.size(); i++) {
                int idx = attached.keyAt(i); DrawerCell cv = attached.valueAt(i);
                int left = cellLeft(idx), top = cellTop(idx);
                if (cv.getWidth() == cellW && cv.getHeight() == cellH) {
                    int dx = left - cv.getLeft();
                    int dy = top  - cv.getTop();
                    if (dx != 0) cv.offsetLeftAndRight(dx);
                    if (dy != 0) cv.offsetTopAndBottom(dy);
                } else {
                    cv.layout(left, top, left + cellW, top + cellH);
                }
            }
        }

        /** Rebind every attached cell to its (possibly new) app and position.
         *  Used after a Move shifts the order so cells reflect the new
         *  identities without a recycle churn. */
        private void rebindAttached() {
            for (int i = 0; i < attached.size(); i++) {
                int idx = attached.keyAt(i);
                if (idx >= 0 && idx < displayed.size()) bindCell(attached.valueAt(i), idx);
            }
        }

        private void doScrollTo(int y) {
            int newY = clampScrollY(y);
            if (newY == scrollY) return;
            scrollY = newY;
            repositionAttached(); fillVisible();
            if (!reorderMode) {
                DrawerCell fc = attached.get(focusedIndex);
                if (fc != null && fc.isFocused()) LauncherActivity.this.positionRing(fc);
            }
        }

        private void smoothScrollTo(int y) {
            int target = clampScrollY(y);
            int dy = target - scrollY;
            scroller.abortAnimation();
            if (dy == 0) return;
            int dur = Math.max(120, Math.min(260, 120 + Math.abs(dy) / 8));
            scroller.startScroll(0, scrollY, 0, dy, dur);
            postInvalidateOnAnimation();
        }

        @Override public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                doScrollTo(scroller.getCurrY());
                postInvalidateOnAnimation();
            } else if (touchScrolling) {
                touchScrolling = false;
            }
        }

        /** Scroll so the row of {@code index} is fully visible. */
        private void ensureVisible(int index, boolean snap) {
            int viewH = getHeight();
            if (viewH <= 0) return;   // not laid out yet — open()'s retry handles it
            int row = HomeDrawerModel.rowOf(index, hc());
            int top    = firstRowTop() + row * rowStride;   // content-space (no scroll)
            int bottom = top + cellH;
            int pad    = rowGap;
            int target = scrollY;
            if      (top - pad < scrollY)             target = top - pad;
            else if (bottom + pad > scrollY + viewH)  target = bottom + pad - viewH;
            target = clampScrollY(target);
            if (snap) { if (target != scrollY) doScrollTo(target); }
            else      smoothScrollTo(target);
        }

        void requestFocusOnIndex(int idx) { requestFocusOnIndex(idx, false); }
        void requestFocusOnIndex(int idx, boolean snap) {
            if (displayed.isEmpty()) return;
            if (idx < 0) idx = 0;
            if (idx >= displayed.size()) idx = displayed.size() - 1;
            focusedIndex = idx;
            scroller.abortAnimation();
            boolean prevFast = fastNav;
            fastNav = snap;
            try {
                ensureVisible(idx, snap);
                fillVisible();
                DrawerCell cv = attached.get(idx);
                if (cv != null) {
                    cv.requestFocus();
                } else {
                    final int target = idx;
                    final boolean deferredFast = snap;
                    post(() -> {
                        fillVisible();
                        DrawerCell cv2 = attached.get(target);
                        if (cv2 != null) {
                            boolean p = fastNav; fastNav = deferredFast;
                            try { cv2.requestFocus(); } finally { fastNav = p; }
                        }
                    });
                }
            } finally { fastNav = prevFast; }
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (velTracker == null) velTracker = VelocityTracker.obtain();
            velTracker.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scroller.abortAnimation();
                    lastTouchY = ev.getY();
                    touchScrolling = true;
                    RingView rvd = ringView; if (rvd != null) rvd.setVisibility(View.INVISIBLE);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = lastTouchY - ev.getY(); lastTouchY = ev.getY();
                    doScrollTo(scrollY + (int) dy); break;
                case MotionEvent.ACTION_UP:
                    velTracker.computeCurrentVelocity(1000);
                    int vy = (int) velTracker.getYVelocity();
                    scroller.fling(0, scrollY, 0, -vy, 0, 0, 0, scrollYMax());
                    velTracker.recycle(); velTracker = null;
                    postInvalidateOnAnimation();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    scroller.abortAnimation();
                    if (velTracker != null) { velTracker.recycle(); velTracker = null; }
                    touchScrolling = false;
                    break;
            }
            return true;
        }

        // ── open / close ─────────────────────────────────────────────────
        void open(int focusIdx) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            int h = getHeight() > 0 ? getHeight() : screenH;
            setTranslationY(h * 0.06f);
            animate().cancel();
            animate().alpha(1f).translationY(0f)
                    .setDuration(180).setInterpolator(SCROLL_EASE)
                    // Keep the ring glued to the focused cell as the whole
                    // drawer slides up (the cells move with the parent
                    // translation, so a one-shot positionRing would be left
                    // offset by the residual slide once the tween ends).
                    .setUpdateListener(a -> {
                        DrawerCell fc = attached.get(focusedIndex);
                        if (fc != null && fc.isFocused()) LauncherActivity.this.positionRing(fc);
                    })
                    .start();
            final int fi = focusIdx;
            // Focus after a layout pass so the target cell exists.
            post(() -> requestFocusOnIndex(fi, true));
        }

        void close(Runnable after) {
            animate().cancel();
            // Hide the ring up front so it doesn't trail the downward slide.
            RingView rv0 = ringView; if (rv0 != null) rv0.setVisibility(View.INVISIBLE);
            int h = getHeight() > 0 ? getHeight() : screenH;
            animate().alpha(0f).translationY(h * 0.06f)
                    .setDuration(140).setInterpolator(SCROLL_EASE)
                    .withEndAction(() -> {
                        setVisibility(GONE);
                        setTranslationY(0f); setAlpha(1f);
                        if (after != null) after.run();
                    }).start();
        }

        /** Dismiss instantly with no animation (used from onPause where the
         *  close tween can't run). */
        void forceHide() {
            if (reorderMode) exitReorderMode(false);
            animate().cancel();
            setVisibility(GONE);
            setTranslationY(0f); setAlpha(1f);
            LauncherActivity.this.applyDrawerBlur(false);
            LauncherActivity.this.setHomeChromeVisible(true);
            RingView rv = ringView;
            if (rv != null) rv.setVisibility(View.INVISIBLE);
        }

        // ── reorder ──────────────────────────────────────────────────────
        void enterReorderMode(int idx) {
            if (reorderMode || idx < 0 || idx >= displayed.size()) return;
            reorderMode = true;
            moveActive  = false;
            dragIndex   = idx;
            focusedIndex = idx;
            menuSelection = RecyclingShelfView.MENU_MOVE;
            LauncherActivity.this.menuHost = this;
            LauncherActivity.this.ensureMenuOverlay();
            rebindAttached(); // repaint drag dimming
            DrawerCell cv = attached.get(idx);
            if (cv != null) LauncherActivity.this.showContextMenu(cv);
            post(() -> { DrawerCell c = attached.get(dragIndex);
                         if (c != null) LauncherActivity.this.positionRing(c); });
        }

        /** Stage-2 entry: hide the menu; D-pad now performs 2-D moves. */
        private void enterActiveMove() {
            moveActive = true;
            hideContextMenu();
            DrawerCell cv = attached.get(dragIndex);
            if (cv != null) LauncherActivity.this.positionRing(cv);
        }

        void exitReorderMode(boolean persist) {
            if (!reorderMode) return;
            reorderMode = false; moveActive = false;
            int idx = dragIndex; dragIndex = -1;
            hideContextMenu();
            if (persist) { saveOrder(); saveHomeCount(); }
            rebindAttached(); // clear drag dimming
            final int f = Math.min(Math.max(0, idx), Math.max(0, displayed.size() - 1));
            focusedIndex = f;
            post(() -> {
                if (getVisibility() != View.VISIBLE) return;
                DrawerCell cv = attached.get(f);
                if (cv != null && cv.isAttachedToWindow() && cv.getWidth() > 0) {
                    cv.requestFocus();
                    LauncherActivity.this.positionRing(cv);
                }
            });
        }

        /** Apply a {@link HomeDrawerModel} move result: adopt the new
         *  homeCount, mirror the new visible order into the master appList,
         *  then rebind + reposition cells and re-focus the dragged app. */
        private void applyMove(HomeDrawerModel.MoveResult r) {
            int size = displayed.size();
            int newHc = HomeDrawerModel.clampHomeCount(r.homeCount, size);
            if (size >= 1 && newHc < 1) newHc = 1;   // keep at least one home app
            LauncherActivity.this.homeCount = newHc;
            LauncherActivity.this.rebuildAppListFromVisible(displayed);
            // Persist immediately on every move so the live in-memory order can
            // never diverge from what is saved — a Back/reconcile exit then has
            // nothing to lose, and a process death mid-reorder keeps the moves
            // the user already saw. saveOrder's AppListCache write is throttled
            // by its bounded executor (rejections ignored) and prefs.apply() is
            // async-batched, so per-move persistence is cheap even on held keys.
            saveOrder();
            saveHomeCount();
            dragIndex    = r.index;
            focusedIndex = r.index;
            recomputeContentHeight();
            ensureVisible(r.index, true);
            rebindAttached();
            repositionAttached();
            fillVisible();
            DrawerCell cv = attached.get(dragIndex);
            if (cv != null) {
                cv.requestFocus(); cv.invalidate();
                LauncherActivity.this.positionRing(cv);
            }
        }

        private void moveDir(int kc) {
            int hc = hc();
            HomeDrawerModel.MoveResult r;
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_LEFT:  r = HomeDrawerModel.moveLeft (displayed, dragIndex, hc); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: r = HomeDrawerModel.moveRight(displayed, dragIndex, hc); break;
                case KeyEvent.KEYCODE_DPAD_UP:    r = HomeDrawerModel.moveUp   (displayed, dragIndex, hc); break;
                case KeyEvent.KEYCODE_DPAD_DOWN:  r = HomeDrawerModel.moveDown (displayed, dragIndex, hc); break;
                default: return;
            }
            applyMove(r);
        }

        void triggerUninstall() {
            AppInfo app = (dragIndex >= 0 && dragIndex < displayed.size()) ? displayed.get(dragIndex) : null;
            exitReorderMode(false);
            LauncherActivity.this.closeDrawer();   // return to home before the system dialog
            if (app != null) LauncherActivity.this.doUninstall(app);
        }
        void triggerAppInfo() {
            AppInfo app = (dragIndex >= 0 && dragIndex < displayed.size()) ? displayed.get(dragIndex) : null;
            exitReorderMode(false);
            LauncherActivity.this.closeDrawer();
            if (app != null) LauncherActivity.this.doAppInfo(app);
        }

        // ── DrawerCell ─────────────────────────────────────────────────────
        final class DrawerCell extends View implements IconTarget {

            Bitmap  iconBitmap;
            AppInfo boundApp;
            int     boundIndex;
            private long    centerKeyDownAt      = 0;
            private boolean longPressArmed       = false;
            private boolean longPressFired       = false;
            private boolean suppressCenterUntilUp = false;

            private final Paint     phRing;
            private final Paint     labelPaint;
            private final Paint     iconPaint;
            private final TextPaint labelTp;
            private final int       bannerW;
            private final int       bannerH;
            private final float     bannerCorner;
            private final float     phStroke;
            private final float     labelOffsetY;
            private final float     labelMaxWInset;
            private final float     icyOffset;
            private final RectF     phRect       = new RectF();
            private       String    labelStr     = "";
            private       String    labelDisplay = "";

            private final android.animation.ValueAnimator.AnimatorUpdateListener focusUpdateListener =
                    anim -> { if (isFocused() && isAttachedToWindow()) positionRing(DrawerCell.this); };

            DrawerCell(Context ctx) {
                super(ctx);
                bannerW        = tileWpx;
                bannerH        = bannerHpx;
                bannerCorner   = tileCornerPx;
                phStroke       = dp(1);
                labelOffsetY   = bannerH / 2f + dp(16);
                labelMaxWInset = dp(6);
                icyOffset      = bannerH / 2f;

                phRing = new Paint(Paint.ANTI_ALIAS_FLAG);
                phRing.setStyle(Paint.Style.STROKE);
                phRing.setColor(0x55FFFFFF);     // white placeholder ring (matches the home shelf)
                phRing.setStrokeWidth(phStroke);

                iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

                labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                labelPaint.setColor(Color.WHITE);                       // white — visible over the frosted blur
                labelPaint.setTextSize(dp(11));
                labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                labelPaint.setTextAlign(Paint.Align.CENTER);
                labelPaint.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);  // dark halo keeps white legible
                labelPaint.setLetterSpacing(0.02f);
                labelTp = new TextPaint(labelPaint);

                setFocusable(true); setFocusableInTouchMode(true);
                setClickable(true); setWillNotDraw(false);
                setDefaultFocusHighlightEnabled(false);
                setBackground(null); setForeground(null);
                setStateListAnimator(null); setSoundEffectsEnabled(true);

                setOnClickListener(v -> {
                    if (boundApp == null) return;
                    if (!reorderMode) launchApp(boundApp);
                });
                setOnLongClickListener(v -> {
                    if (boundApp == null || reorderMode) return true;
                    enterReorderMode(boundIndex);
                    return true;
                });

                setOnFocusChangeListener((v, f) -> {
                    if (!reorderMode || moveActive) {
                        animate().cancel();
                        if (fastNav) {
                            setScaleX(f ? FOCUS_SCALE : 1f);
                            setScaleY(f ? FOCUS_SCALE : 1f);
                            if (f && isAttachedToWindow() && getWidth() > 0) positionRing(DrawerCell.this);
                        } else if (f) {
                            animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE)
                                     .setDuration(FOCUS_DUR_MS).setInterpolator(FOCUS_IN_BOUNCE)
                                     .setUpdateListener(focusUpdateListener).start();
                        } else {
                            animate().scaleX(1f).scaleY(1f)
                                     .setDuration(UNFOCUS_DUR_MS).setInterpolator(FOCUS_EASE)
                                     .setUpdateListener(null).start();
                        }
                    }
                    invalidate();
                    if (f) {
                        focusedIndex = boundIndex;
                        if (!reorderMode) {
                            if (isAttachedToWindow() && getWidth() > 0) positionRing(DrawerCell.this);
                            if (!fastNav) ensureVisible(boundIndex, false);
                        }
                    }
                });

                setOnKeyListener((v, kc, ev) -> {
                    if (reorderMode) {
                        boolean isCenterKey = kc == KeyEvent.KEYCODE_DPAD_CENTER
                                || kc == KeyEvent.KEYCODE_ENTER
                                || kc == KeyEvent.KEYCODE_BUTTON_A;
                        if (isCenterKey && ev.getAction() == KeyEvent.ACTION_UP) {
                            suppressCenterUntilUp = false;
                            return true;
                        }
                        if (isCenterKey && suppressCenterUntilUp) return true;
                        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;

                        if (moveActive) {
                            // Stage 2 — D-pad performs 2-D moves.
                            switch (kc) {
                                case KeyEvent.KEYCODE_DPAD_LEFT:
                                case KeyEvent.KEYCODE_DPAD_RIGHT:
                                case KeyEvent.KEYCODE_DPAD_UP:
                                case KeyEvent.KEYCODE_DPAD_DOWN:
                                    moveDir(kc); return true;
                                case KeyEvent.KEYCODE_DPAD_CENTER:
                                case KeyEvent.KEYCODE_ENTER:
                                case KeyEvent.KEYCODE_BUTTON_A:
                                    exitReorderMode(true); return true;   // commit
                                case KeyEvent.KEYCODE_BACK:
                                    exitReorderMode(true); return true;
                                default: return false;
                            }
                        }
                        // Stage 1 — menu shown: UP/DOWN cycle, CENTER confirms.
                        switch (kc) {
                            case KeyEvent.KEYCODE_DPAD_UP:
                                if      (menuSelection == RecyclingShelfView.MENU_MOVE)     { menuSelection = RecyclingShelfView.MENU_APP_INFO;  updateMenuHighlight(); }
                                else if (menuSelection == RecyclingShelfView.MENU_APP_INFO) { menuSelection = RecyclingShelfView.MENU_UNINSTALL; updateMenuHighlight(); }
                                return true;
                            case KeyEvent.KEYCODE_DPAD_DOWN:
                                if      (menuSelection == RecyclingShelfView.MENU_UNINSTALL) { menuSelection = RecyclingShelfView.MENU_APP_INFO; updateMenuHighlight(); }
                                else if (menuSelection == RecyclingShelfView.MENU_APP_INFO)  { menuSelection = RecyclingShelfView.MENU_MOVE;     updateMenuHighlight(); }
                                return true;
                            case KeyEvent.KEYCODE_DPAD_LEFT:
                            case KeyEvent.KEYCODE_DPAD_RIGHT:
                                return true; // consume; movement is in stage 2
                            case KeyEvent.KEYCODE_DPAD_CENTER:
                            case KeyEvent.KEYCODE_ENTER:
                            case KeyEvent.KEYCODE_BUTTON_A:
                                if      (menuSelection == RecyclingShelfView.MENU_UNINSTALL) triggerUninstall();
                                else if (menuSelection == RecyclingShelfView.MENU_APP_INFO)  triggerAppInfo();
                                else                                                          enterActiveMove();
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
                                longPressArmed  = true; longPressFired = false;
                            } else if (longPressArmed && !longPressFired) {
                                long held = System.currentTimeMillis() - centerKeyDownAt;
                                if (held >= 600 && boundApp != null && !reorderMode) {
                                    longPressFired = true; longPressArmed = false; centerKeyDownAt = 0;
                                    suppressCenterUntilUp = true;
                                    enterReorderMode(boundIndex);
                                }
                            }
                            return true;
                        }
                        if (ev.getAction() == KeyEvent.ACTION_UP) {
                            boolean wasArmed = longPressArmed;
                            longPressArmed = false; longPressFired = false; centerKeyDownAt = 0;
                            if (wasArmed && !reorderMode) {
                                playSoundEffect(SoundEffectConstants.CLICK);
                                performClick();
                            }
                            return true;
                        }
                        return false;
                    }

                    if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                    boolean held = ev.getRepeatCount() > 0;
                    int size = displayed.size(), hc = hc();
                    switch (kc) {
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            requestFocusOnIndex(HomeDrawerModel.navLeft(boundIndex, size, hc), held);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            requestFocusOnIndex(HomeDrawerModel.navRight(boundIndex, size, hc), held);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            requestFocusOnIndex(HomeDrawerModel.navDown(boundIndex, size, hc), held);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_UP:
                            int up = HomeDrawerModel.navUp(boundIndex, size, hc);
                            if (up == HomeDrawerModel.CLOSE_DRAWER) closeDrawer();
                            else requestFocusOnIndex(up, held);
                            return true;
                        case KeyEvent.KEYCODE_BACK:
                            closeDrawer(); return true;
                        default: return false;
                    }
                });
            }

            @Override public void setIconBitmap(Bitmap bmp) { iconBitmap = bmp; invalidate(); }
            @Override public String  iconTargetPackage() { return boundApp != null ? boundApp.packageName : null; }
            @Override public boolean iconTargetVisible() { return getVisibility() == View.VISIBLE; }

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
                boolean showLabel = (!labelDisplay.isEmpty())
                        && ((isFocused() && !reorderMode) || isDragTarget);
                if (showLabel) {
                    float labelY = icy + labelOffsetY;
                    if (labelY < h) canvas.drawText(labelDisplay, cx, labelY, labelPaint);
                }
            }

            private void drawIcon(Canvas canvas, float cx, float icy) {
                if (iconBitmap != null && !iconBitmap.isRecycled()) {
                    float hw = iconBitmap.getWidth() / 2f, hh = iconBitmap.getHeight() / 2f;
                    canvas.drawBitmap(iconBitmap, cx - hw, icy - hh, iconPaint);
                } else {
                    // Rounded-rect banner-tile placeholder.
                    float hw = bannerW / 2f, hh = bannerH / 2f;
                    phRect.set(cx - hw, icy - hh, cx + hw, icy + hh);
                    canvas.drawRoundRect(phRect, bannerCorner, bannerCorner, sPhFill);
                    float in = phStroke / 2f;
                    phRect.set(cx - hw + in, icy - hh + in, cx + hw - in, icy + hh - in);
                    canvas.drawRoundRect(phRect, bannerCorner, bannerCorner, phRing);
                }
            }

            void bind(AppInfo app, int index) {
                boolean labelChanged = !app.label.equals(labelStr);
                boundApp = app; boundIndex = index; labelStr = app.label;
                setContentDescription(app.label);
                if (labelChanged) {
                    String disp = app.displayLabel;
                    if (disp == null) {
                        float maxW = bannerW - labelMaxWInset;
                        disp = labelPaint.measureText(labelStr) > maxW
                                ? TextUtils.ellipsize(labelStr, labelTp, maxW, TextUtils.TruncateAt.END).toString()
                                : labelStr;
                        app.displayLabel = disp;
                    }
                    labelDisplay = disp;
                }
                Bitmap cached = bannerCache.get(app.packageName);
                if (cached != null) {
                    if (cached != iconBitmap) { iconBitmap = cached; invalidate(); }
                } else {
                    if (iconBitmap != null) { iconBitmap = null; invalidate(); }
                    loadBannerAsync(app, this);
                }
            }
        }
    }

    private void loadApps() {
        if (!appsLoading.compareAndSet(false, true)) return;
        // A5 fix: bail before any work if the activity was already torn
        // down. A package broadcast can post pkgReloadRunnable to the
        // shelf's looper just before {@link #onDestroy} nulls the shelf,
        // and the looper drains the runnable after the activity has
        // gone {@code destroyed = true}. Without this guard, the cache
        // pre-paint block below would still run, populate appList from
        // disk on a dead activity, and submit a doomed task to the
        // already-shut-down appExecutor.
        if (destroyed) { appsLoading.set(false); return; }
        // Cold-start instant paint: if appList is empty (we have not loaded
        // yet), try the on-disk AppListCache synchronously. The shelf
        // renders the cached entries in the very first frame while the
        // PM scan continues in the background. When the scan completes,
        // the reconcile block below either no-ops (cache matched fresh
        // result) or replaces appList and re-renders. The cached
        // {@link AppInfo} entries carry a null {@code ri}; the icon-load
        // path falls back to {@code PackageManager.getActivityIcon(component)}
        // for those, so cells still get icons via the IconDiskCache hits
        // (and via direct PM calls for cold caches). Only runs at most
        // once per process — subsequent loadApps() invocations have a
        // populated appList from prior reconciles.
        if (appList.isEmpty()) {
            boolean ok = AppListCache.readFile(this, (pkg, lbl, cls) -> {
                AppInfo a = AppListCache.toAppInfo(pkg, lbl, cls);
                appList.add(a);
                appByPackage.put(pkg, a);
            });
            if (ok && !appList.isEmpty()) {
                RecyclingShelfView s = shelf;
                if (s != null) applyShelfApps(s);
            } else {
                // Either no cache or it failed to parse. Drop any partial
                // state defensively (parse() is "all-or-nothing" so this
                // is hygiene rather than correctness).
                if (!appList.isEmpty()) { appList.clear(); appByPackage.clear(); }
            }
        }
        try {
            appExecutor.execute(() -> {
                List<AppInfo> fresh;
                try {
                    fresh = queryApps();
                    applyStoredOrder(fresh);
                } catch (Throwable t) {
                    // Belt-and-braces: PackageManager binder errors, dead
                    // ResolveInfo, or any other unexpected exception inside
                    // queryApps / applyStoredOrder must NOT strand the
                    // appsLoading flag. If we ever leak `true` to the flag
                    // then every subsequent loadApps() call (including the
                    // one fired by a package-add broadcast) becomes a silent
                    // no-op for the lifetime of the activity. Reset the
                    // flag and bail; logcat surfaces the trace via the
                    // CrashLogger sink installed in onCreate.
                    appsLoading.set(false);
                    return;
                }
                if (destroyed) { appsLoading.set(false); return; }
                final List<AppInfo> freshFinal = fresh;
                runOnUiThread(() -> {
                    // Wrap the whole UI body in try/finally so a faulting
                    // helper (pruneHiddenApps prefs write, applyShelfApps,
                    // requestFocusOnIndex) cannot strand the appsLoading
                    // flag at `true`. Belt-and-braces companion to the
                    // background-side guard above: if either path fails,
                    // the flag converges back to false and the next
                    // package-broadcast triggers a fresh refresh instead
                    // of becoming a silent no-op for the activity's
                    // lifetime.
                    try {
                        if (destroyed) return;
                        // Build the "fresh package set" exactly ONCE per
                        // reconcile and share it across the icon-cache
                        // invalidation, pruneHiddenApps, and pruneKeyMap
                        // paths below. The earlier draft built a separate
                        // ArraySet inside each helper from the same
                        // freshFinal data — three N-element passes plus
                        // three small set allocations on every package
                        // broadcast. Sharing one set drops the per-
                        // reconcile cost to a single pass + one
                        // allocation, with zero behavioural change
                        // (each helper's containsKey check was the only
                        // thing it ever did with its locally-built set).
                        ArraySet<String> freshPkgs = new ArraySet<>(freshFinal.size());
                        for (int i = 0, n = freshFinal.size(); i < n; i++) {
                            freshPkgs.add(freshFinal.get(i).packageName);
                        }
                        LruCache<String, Bitmap> cache = iconCache;
                        if (cache != null) {
                            for (AppInfo old : appList)
                                if (!freshPkgs.contains(old.packageName)) cache.remove(old.packageName);
                        }
                        // GC stale hidden-set entries before any other consumer
                        // sees the new appList — keeps the saved set in sync
                        // with the actually-installed packages without a
                        // separate scheduling step.
                        pruneHiddenApps(freshPkgs);
                        // Mirror prune for the remote-key shortcut
                        // map so an uninstalled-app binding is dropped
                        // automatically (the keymap settings slot row
                        // shows "Not assigned" instead of a stale raw
                        // package name). The dispatchKeyEvent fallback
                        // still handles the rare race where this
                        // reconcile hasn't run yet by the time the user
                        // presses the dead-binding key.
                        pruneKeyMap(freshPkgs);
                        boolean changed = freshFinal.size() != appList.size();
                        if (!changed) {
                            for (int i = 0; i < freshFinal.size(); i++) {
                                if (!freshFinal.get(i).packageName.equals(appList.get(i).packageName)) {
                                    changed = true; break;
                                }
                            }
                        }
                        if (changed) {
                            // Fresh appList fully replaces the old one; the
                            // re-render decodes every visible icon, so any
                            // pending per-package invalidations are subsumed.
                            pendingIconInvalidations.clear();
                            appList.clear(); appList.addAll(freshFinal);
                            // Rebuild the package → AppInfo index alongside
                            // the master list so every consumer that asks
                            // findAppByPackage(pkg) sees a consistent view.
                            // Done inside the same UI body that mutates
                            // appList so the two structures cannot diverge
                            // mid-frame.
                            appByPackage.clear();
                            for (int i = 0, n = freshFinal.size(); i < n; i++) {
                                AppInfo a = freshFinal.get(i);
                                appByPackage.put(a.packageName, a);
                            }
                            RecyclingShelfView s = shelf;
                            if (s != null) {
                                // A6 fix: the saved scroll index is a
                                // {@code displayed}-list index (the shelf
                                // saves {@code s.focusedIndex} in
                                // {@link #onPause}). Clamp against the
                                // count of visible (non-hidden) apps in
                                // freshFinal, NOT against the master
                                // freshFinal.size(). Without this clamp,
                                // an index pointing past the filtered
                                // tail used to slip through here and
                                // {@code setApps} clamped it again to
                                // {@code displayed.size() - 1} — landing
                                // the user on the last visible cell
                                // instead of the closest valid one.
                                if (pendingScrollIdx >= 0 && !freshFinal.isEmpty()) {
                                    int visibleCount = countVisible(freshFinal);
                                    if (visibleCount > 0) {
                                        s.focusedIndex = Math.min(pendingScrollIdx, visibleCount - 1);
                                    }
                                    pendingScrollIdx = -1;
                                }
                                applyShelfApps(s);
                            }
                            // A2 fix: when a package broadcast fires
                            // while the user is INSIDE the keymap card's
                            // PICKER or HIDE chip strip, the strip's
                            // identities just changed under their hands.
                            // The package receiver invalidated the
                            // {@code *BuiltSize} caches so the NEXT open
                            // would rebuild — but the user is currently
                            // looking at stale chips. Force a rebuild
                            // here so OK on chip {@code i} hits the right
                            // package.
                            rebuildOpenChipStripsAfterReconcile();
                            // Persist the reconciled list to the on-disk
                            // AppListCache so the next cold start can
                            // render the shelf instantly. Snapshotted on a
                            // background thread so the UI body returns
                            // immediately. Best-effort: a write failure
                            // (FS full, IOException) just means the next
                            // cold start does a normal PM scan, no other
                            // consequence. Application context captured so
                            // the runnable does not pin the activity past
                            // its lifecycle.
                            final ArrayList<AppInfo> snapshot =
                                    new ArrayList<>(freshFinal);
                            final Context appCtx = getApplicationContext();
                            try {
                                appExecutor.execute(() ->
                                        AppListCache.writeFileFromAppInfo(appCtx, snapshot));
                            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                                // Executor saturated by a rapid burst of
                                // package broadcasts. The cache stays at
                                // its previous-known-good content; the
                                // next loadApps reconcile will retry.
                            }
                        } else {
                            // A8 fix: appList content matches but the
                            // entries reconstructed from {@link AppListCache}
                            // carry {@code ri == null} (ResolveInfo is not
                            // serialisable). The fresh batch from
                            // queryApps does carry a real ResolveInfo on
                            // every entry. Graft those onto the existing
                            // AppInfo instances so subsequent icon loads
                            // use the faster {@code ri.loadIcon(pm)} path
                            // instead of {@code pm.getActivityIcon}. Same
                            // observable result, just ~5-15 ms cheaper
                            // per icon, repeatable across the activity's
                            // entire lifetime once warmed.
                            //
                            // Same-index pairing is safe because the
                            // {@code !changed} branch already verified
                            // every {@code freshFinal[i].packageName ==
                            // appList[i].packageName}. The graft is a
                            // single volatile write per upgraded entry —
                            // see {@link AppInfo#ri} for the visibility
                            // rationale.
                            for (int i = 0, n = appList.size(); i < n; i++) {
                                AppInfo old = appList.get(i);
                                AppInfo upgrade = freshFinal.get(i);
                                // Always adopt the fresh ResolveInfo. After
                                // ACTION_PACKAGE_REPLACED the old AppInfo holds
                                // a STALE non-null ResolveInfo pointing at the
                                // pre-update APK's resources; ri.loadIcon on it
                                // resolves to the generic / stock icon on most
                                // ROMs. Unconditional refresh hands the icon
                                // pipeline the new package's ResolveInfo.
                                if (upgrade != null && upgrade.ri != null) {
                                    old.ri = upgrade.ri;
                                }
                                // Re-warm ONLY packages the receiver flagged as
                                // replaced / changed — not every app on every
                                // reconcile (queryApps returns fresh ResolveInfo
                                // instances each scan, so an identity check would
                                // re-warm everything). Drops the stale in-memory
                                // bitmap and queues a fresh decode that re-writes
                                // the disk cache from the new ResolveInfo.
                                if (pendingIconInvalidations.contains(old.packageName)) {
                                    LruCache<String, Bitmap> c2 = iconCache;
                                    if (c2 != null) c2.remove(old.packageName);
                                    iconInflight.remove(old.packageName);
                                    preWarmIcon(old);
                                }
                            }
                            pendingIconInvalidations.clear();
                            if (pendingScrollIdx >= 0) {
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
                        }
                    } finally {
                        appsLoading.set(false);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // A4 fix: the executor refused this task — most likely because
            // a package broadcast already had one queued in the bounded
            // ArrayBlockingQueue(1) when {@link #onCreate}'s synchronous
            // call landed. Pre-1.4.x just dropped the request and never
            // reconciled, leaving the cache pre-paint's null-{@code ri}
            // AppInfos in place for the rest of the session — every icon
            // load took the slower PM-binder fallback. Schedule a retry
            // 400 ms out (matches the package-broadcast debounce) so the
            // executor has time to drain its existing task before we
            // re-submit. Bounded by the {@code appsLoading} compareAndSet
            // gate at the top of {@link #loadApps} so retries can't
            // pile up.
            appsLoading.set(false);
            // Reuse the cached {@link #pkgReloadRunnable}. Same effect
            // as a fresh {@code this::loadApps} method-reference, plus
            // matches the {@code postDelayed} pattern used by the
            // package-broadcast and trim-memory paths so all three
            // deferred reloads share one cancellable Runnable.
            uiHandler.postDelayed(pkgReloadRunnable, 400);
        }
    }

    /** Count of apps in {@code list} that survive the hidden-apps
     *  filter. Equivalent to the size of the list
     *  {@link #applyShelfApps} ultimately hands to
     *  {@code RecyclingShelfView.setApps}. Pure scan, no allocation,
     *  used by the reconcile to clamp {@code pendingScrollIdx} against
     *  the displayed-list size before passing it to setApps (which
     *  would otherwise clamp again to the same bound — but only after
     *  silently throwing away the user's saved position). */
    private int countVisible(List<AppInfo> list) {
        if (list == null || list.isEmpty()) return 0;
        if (hiddenApps.isEmpty()) return list.size();
        int n = 0;
        for (int i = 0, m = list.size(); i < m; i++) {
            if (!hiddenApps.contains(list.get(i).packageName)) n++;
        }
        return n;
    }

    /** Rebuild the keymap card's PICKER / HIDE chip strips RIGHT NOW
     *  if the user is currently inside one of them. Called from the
     *  reconcile path so a package broadcast that lands while the
     *  overlay is open can never leave the strip's chips and the
     *  in-memory {@code appList} disagreeing about which chip
     *  represents which package.
     *
     *  <p>The {@code keymapPickerBuiltSize} / {@code keymapHideBuiltSize}
     *  caches are invalidated in the {@link #packageReceiver} so the
     *  NEXT enter*() rebuilds; this forces an IMMEDIATE rebuild for
     *  the case where the user is already inside. After the rebuild,
     *  the {@code *BuiltSize} caches are updated to match so the next
     *  enter*() short-circuits via the existing
     *  refresh{Picker,Hide}ChipIcons fast-path.
     *
     *  <p>Selection is clamped to the new strip length — a chip that
     *  previously sat past the new tail (because a package was
     *  uninstalled) lands on the last surviving chip instead of an
     *  out-of-range slot. */
    private void rebuildOpenChipStripsAfterReconcile() {
        FrameLayout ko = keymapOverlay;
        if (ko == null || ko.getVisibility() != View.VISIBLE) return;
        if (keymapMode == KEYMAP_MODE_PICKER) {
            rebuildPickerChips();
            keymapPickerBuiltSize = appList.size();
            keymapPickerLastIdx   = -1;
            android.widget.LinearLayout ps = keymapPickerStrip;
            if (ps != null) {
                int max = Math.max(0, ps.getChildCount() - 1);
                if (keymapPickerIdx > max) keymapPickerIdx = max;
                if (keymapPickerIdx < 0)   keymapPickerIdx = 0;
            }
            refreshKeymapPicker();
        } else if (keymapMode == KEYMAP_MODE_HIDE) {
            buildHideChips();
            keymapHideBuiltSize = appList.size();
            keymapHideLastIdx   = -1;
            android.widget.LinearLayout hs = keymapHideStrip;
            if (hs != null) {
                int max = Math.max(0, hs.getChildCount() - 1);
                if (keymapHideIdx > max) keymapHideIdx = max;
                if (keymapHideIdx < 0)   keymapHideIdx = 0;
            }
            refreshHideStrip();
        }
        // SLOTS mode: the keymap card already calls refreshKeymapRows
        // on every UP/DOWN press, and the stale-binding fallback in
        // dispatchKeyEvent's "Mapped to an uninstalled package" path
        // covers the rare press during a reconcile. No extra work
        // needed here.
    }

    private List<AppInfo> queryApps() {
        String self = getPackageName();
        ArraySet<String> seen = new ArraySet<>();
        List<AppInfo> out = new ArrayList<>();
        // Query order matters because addApps dedupes by packageName: the
        // FIRST resolved component for a given package wins, every later
        // component for that same package is skipped. So on a TV we ask
        // CATEGORY_LEANBACK_LAUNCHER first (the TV-tuned activity is the
        // right target); on a phone / tablet we ask CATEGORY_LAUNCHER
        // first (the phone-tuned activity is the right target). Either
        // way the *other* category is queried right after, which picks
        // up apps that only declare one or the other — phone-only apps
        // on TV, TV-only apps on phone, system apps with either filter
        // shape, and sideloaded APKs of any flavour. Net effect: every
        // installed launchable app surfaces exactly once.
        boolean tv = isTelevision();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent regular  = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Intent first  = tv ? leanback : regular;
        Intent second = tv ? regular  : leanback;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.ResolveInfoFlags f = PackageManager.ResolveInfoFlags.of(0);
            addApps(pm.queryIntentActivities(first,  f), self, seen, out);
            addApps(pm.queryIntentActivities(second, f), self, seen, out);
        } else {
            //noinspection deprecation
            addApps(pm.queryIntentActivities(first,  0), self, seen, out);
            //noinspection deprecation
            addApps(pm.queryIntentActivities(second, 0), self, seen, out);
        }
        Collections.sort(out, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
        return out;
    }

    /** True when the device is running in Android-TV / leanback UI mode.
     *  Used to decide which launcher category to query first in
     *  {@link #queryApps()} so dual-target apps (those declaring BOTH a
     *  phone CATEGORY_LAUNCHER activity and a TV CATEGORY_LEANBACK_LAUNCHER
     *  activity) surface the activity tuned for the current device. */
    private boolean isTelevision() {
        int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private void addApps(List<ResolveInfo> list, String self, ArraySet<String> seen, List<AppInfo> out) {
        // PackageManager.queryIntentActivities is documented non-null but
        // several real-world ROMs (Amazon Fire TV in particular) return
        // null after a system process restart or a SELinux denial. Without
        // this guard the for-each below NPEs, which would propagate up
        // through queryApps and bubble into loadApps' executor body.
        if (list == null) return;
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            // Defensive nulls. ai itself is null on a malformed ResolveInfo
            // (Fire TV has been observed returning these post-system-restart);
            // ai.packageName is null on stripped-down ROMs that ship
            // ActivityInfo objects whose <application> manifest entry is
            // broken. ai.name is, by contract, the activity's class name —
            // also defensively guarded because {@code new ComponentName(pkg,
            // null)} throws an NPE in the ComponentName constructor, which
            // would bubble up and abort the entire queryApps batch via the
            // outer Throwable handler in loadApps. Skip silently — equality
            // / hash operations downstream would NPE and propagate up,
            // bouncing into loadApps' catch (Throwable) and resetting
            // appsLoading=false; the user-visible effect would be a blank
            // shelf until the next package broadcast retried.
            if (ai == null || ai.packageName == null || ai.name == null) continue;
            if (ai.packageName.equals(self)) continue;
            // Dedupe by PACKAGE NAME — not "package/activity". A single
            // app that declares BOTH a CATEGORY_LAUNCHER (phone) and a
            // CATEGORY_LEANBACK_LAUNCHER (TV) activity exposes two
            // ResolveInfo entries with the SAME package but DIFFERENT
            // activity names; the old "pkg/activity" key let both pass
            // and the package showed up twice on the shelf. Package-only
            // dedupe collapses them to a single entry — the one that came
            // back from whichever category we queried first (see the
            // TV-vs-phone ordering in queryApps above). The activity
            // selected here drives launchApp() too, so the right UI
            // (TV-tuned vs phone-tuned) opens on the right device.
            if (!seen.add(ai.packageName)) continue;
            // ri.loadLabel() returns null on stripped-down Fire-TV ROMs that
            // ship apps without a recoverable user-visible label (typically
            // OEM packages with broken AndroidManifest <application> labels).
            // It can also THROW (Resources$NotFoundException, SecurityException,
            // RuntimeException) on the same class of ROMs when the label
            // string-resource id resolves to a missing or cross-user
            // resource. Without the catch, one bad app aborts the whole
            // queryApps batch via the outer Throwable handler in loadApps —
            // visible to the user as a blank shelf until the next package
            // broadcast retries. Treat throw and null identically: fall back
            // to the package name and surface the app as a labelled cell.
            CharSequence rawLabel;
            try {
                rawLabel = ri.loadLabel(pm);
            } catch (Throwable t) {
                rawLabel = null;
            }
            String label = rawLabel != null ? rawLabel.toString() : ai.packageName;
            out.add(new AppInfo(ai.packageName, label,
                    new ComponentName(ai.packageName, ai.name), ri));
        }
    }

    private void launchApp(AppInfo app) {
        // Direct-intent fast path. PackageManager.getLaunchIntentForPackage
        // does TWO synchronous binder calls internally
        // (queryIntentActivities for CATEGORY_INFO, fall back to
        // CATEGORY_LAUNCHER) to discover the launcher activity — but we
        // already cached that activity in {@code app.component} at
        // queryApps time, AND {@code Intent.setComponent} bypasses
        // resolution entirely (the named activity is started directly).
        // Skipping the binder calls saves 50-200 ms of UI-thread latency
        // per launch on stripped TV ROMs where PackageManager is slow.
        //
        // The intent shape mirrors what {@code getLaunchIntentForPackage}
        // returns (action ACTION_MAIN, category CATEGORY_LAUNCHER,
        // package set, component overridden, flags = NEW_TASK) so apps
        // that introspect their launching intent see exactly the same
        // shape as before.
        try {
            Intent fast = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(app.packageName)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(fast);
            return;
        } catch (Exception ignored) {
            // Fall through to the legacy paths — they have caught every
            // launch failure shape we have observed in production, so we
            // keep them as a defensive belt-and-braces tier even when
            // the fast path covers ~all real installs.
        }
        // Legacy fallback: resolve the canonical launch intent via PM
        // (the slow path we just bypassed) and override the component.
        // Reached only when the direct intent was rejected by the
        // platform — extremely rare, but keeps strict-mode security
        // policies and exotic ROMs working.
        try {
            Intent i = pm.getLaunchIntentForPackage(app.packageName);
            if (i != null) {
                i.setComponent(app.component); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
        } catch (Exception ignored) {}
        // Final fallback: bare ACTION_MAIN + component. Same shape as the
        // primary path minus the category + package, included as a last
        // resort for the rarest "PM resolution refuses but explicit
        // component still launches" case observed in CrashLogger logs.
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
        String raw = prefs.getString(KEY_KEYMAP, null);
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
        prefs
                .edit().putString(KEY_KEYMAP,
                        KeymapStore.serializeKeyMap(keycodes, packages)).apply();
    }

    /** Parse the persisted hidden-apps set once at startup. Hidden-but-
     *  uninstalled packages get garbage-collected the next time
     *  {@link #loadApps()} runs (see {@link #pruneHiddenApps}). */
    private void loadHiddenApps() {
        hiddenApps.clear();
        String raw = prefs.getString(KEY_HIDDEN, null);
        KeymapStore.parseHiddenApps(raw, hiddenApps::add);
    }

    /** Persist the in-memory hiddenApps set. Called synchronously from
     *  every toggle in the hide-manager so the user never has to confirm. */
    private void saveHiddenApps() {
        // ArraySet<String> implements Iterable<String> via the inherited
        // Collection / Set typed signature, so it can be passed straight
        // to KeymapStore.serializeHiddenApps without an intermediate
        // ArrayList copy. Saves one ArrayList allocation per toggle —
        // not a hot path, but the wrapper was strictly redundant.
        prefs
                .edit().putString(KEY_HIDDEN,
                        KeymapStore.serializeHiddenApps(hiddenApps)).apply();
    }

    /** Drop hidden-set entries whose package is no longer installed.
     *  Called from loadApps once the fresh appList is known. O(N + M)
     *  via a single ArraySet pass over fresh package names; the prior
     *  nested loop was O(N · M) and quadratic when many apps were
     *  hidden — fine in practice but trivially fixed. */
    /** Mirror of {@link #pruneHiddenApps} for the remote-key shortcut
     *  map. Uninstalled-package bindings persist in {@link #keyMap}
     *  until the user actually presses the bound key — at which point
     *  {@link #dispatchKeyEvent}'s "Mapped to an uninstalled package"
     *  branch cleans up lazily. The lazy cleanup works for the press
     *  path, but it leaves the keymap settings UI showing the raw
     *  package name on the slot row (instead of "Not assigned") for
     *  every binding pointing at an uninstalled app.
     *
     *  <p>Eager cleanup on every {@link #loadApps()} reconcile keeps
     *  the slot list visually correct without user action. Iterates
     *  the SparseArray in reverse so the {@code removeAt} indices
     *  stay valid; calls {@link #saveKeyMap()} at most once per
     *  reconcile no matter how many entries dropped.
     *
     *  <p>Cheap on warm runs: {@link #SHORTCUT_KEYCODES} caps the
     *  binding count at 6, so the inner ArraySet contains() check
     *  runs at most 6 times. Accepts the freshly-built package set
     *  from the reconcile body so the v1.4.x first cut's separate-
     *  ArraySet allocations (three sets built from the same data on
     *  every reconcile — see the v1.4.x reconcile body comment) are
     *  collapsed to a single shared instance.
     */
    private void pruneKeyMap(ArraySet<String> installedPkgs) {
        if (keyMap.size() == 0) return;
        if (installedPkgs == null) return;
        boolean changed = false;
        for (int i = keyMap.size() - 1; i >= 0; i--) {
            String pkg = keyMap.valueAt(i);
            if (pkg == null || !installedPkgs.contains(pkg)) {
                keyMap.removeAt(i);
                changed = true;
            }
        }
        if (changed) saveKeyMap();
    }

    private void pruneHiddenApps(ArraySet<String> installedPkgs) {
        if (hiddenApps.isEmpty()) return;
        if (installedPkgs == null) return;
        boolean changed = false;
        for (int i = hiddenApps.size() - 1; i >= 0; i--) {
            if (!installedPkgs.contains(hiddenApps.valueAt(i))) {
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
     *  loadApps so we preserve order trivially by walking it once.
     *
     *  <h3>Hidden-app icon warming</h3>
     *  Hidden apps are NOT pre-warmed here (was the v1.2.2 behaviour).
     *  They get warmed lazily inside {@link #showKeymapOverlay()} which
     *  is the only consumer that displays their icons. Pre-warming on
     *  every loadApps reconcile (which fires on every package broadcast)
     *  triggered N disk reads through the IconDiskCache LruCache.create
     *  fallback for the empty-memory-cache case — measurable cost on
     *  installs with many hidden apps. The lazy path runs at most once
     *  per overlay-open, and {@link #preWarmIcon} early-returns on
     *  cache hit so the second-and-subsequent opens are O(N) cheap
     *  containsKey checks. */
    private void applyShelfApps(RecyclingShelfView s) {
        if (s == null) return;
        // Single source of truth: appList (full order) minus hiddenApps, split
        // at the home boundary. The home row shows the first homeCount apps;
        // the drawer shows the whole visible list. Both setApps consumers
        // snapshot into their own displayed list, so the shared visible list
        // (possibly the reused visibleScratch) never leaks across UI events.
        List<AppInfo> visible = buildVisibleList();
        resolveHomeCount(visible.size());
        int hc = effectiveHomeCount(visible.size());
        pushHomeRow(s, visible, hc);
        AppDrawer d = drawer;
        if (d != null) d.setApps(visible, hc);
    }

    /** Pre-warm the small round chip icons for the keymap overlay. v1.5.0:
     *  the home / drawer cells now load BANNER tiles (not the round icons),
     *  so nothing warms {@link #iconCache} for the chips until the overlay
     *  opens — warm every app here so the picker / hide-list / slot rows
     *  resolve their icons. {@link #preWarmIcon} early-returns on a cache
     *  hit, so repeat opens are O(N) cheap containsKey checks. */
    private void preWarmChipIcons() {
        for (int i = 0, n = appList.size(); i < n; i++) {
            preWarmIcon(appList.get(i));
        }
    }

    private AppInfo findAppByPackage(String pkg) {
        // O(1) via appByPackage. Falls back to a linear scan only if the
        // map is somehow empty while the list is populated — a defensive
        // case that shouldn't be reachable, since the two are mutated
        // together inside loadApps' UI block. The fallback exists to
        // keep the contract "if the package is in appList, return it"
        // robust against any future mutation path that forgets to
        // update the map.
        AppInfo hit = appByPackage.get(pkg);
        if (hit != null) return hit;
        if (appByPackage.isEmpty() && !appList.isEmpty()) {
            for (int i = 0; i < appList.size(); i++) {
                AppInfo a = appList.get(i);
                if (a.packageName.equals(pkg)) return a;
            }
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
        FrameLayout sp = settingsOverlay;
        if (sp != null && sp.getVisibility() == View.VISIBLE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (handleSettingsKey(event.getKeyCode())) return true;
                return super.dispatchKeyEvent(event);
            }
            // Non-DOWN (KEY_UP / multiple): mirror the keymap overlay's
            // {@link #handleKeymapOverlayKey} contract exactly. Only swallow
            // the UP edge for keys we actually consume on DOWN; let the
            // device-control keys ({@link #isLetThroughKey}: volume / mute /
            // power / sleep / wake / media-transport) reach the platform on
            // their UP edge too.
            //
            // Why this matters: on DOWN, handleSettingsKey already returns
            // false for let-through keys so the DOWN edge falls through to
            // super → the platform's global handler (AudioService,
            // PowerManager, MediaSession). Pre-1.4.5 the matching UP edge was
            // unconditionally swallowed here, so on the rare ROMs that route
            // both edges to user space (HDMI-CEC volume bridges, some set-top
            // remotes) AudioService saw an unbalanced DOWN-without-UP while
            // the settings panel was open. Letting the UP through restores
            // the balanced DOWN+UP pair. Everything else stays swallowed so
            // an unmapped remote button can't bleed to the shelf underneath.
            if (isLetThroughKey(event.getKeyCode())) return super.dispatchKeyEvent(event);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && keyMap.size() > 0
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

    // ── Settings panel (v1.3.0) ──────────────────────────────────────────
    //
    // Top-level overlay that opens as a dropdown under the gear toolbar
    // pill. Five rows: Manage hidden apps, Button shortcuts, Set
    // wallpaper, Show clock toggle, System Settings. Visual language
    // matches the keymap card (deep slate plate + 1 dp white rim, drop-
    // down animation pivoted at the gear's top-right corner). Same
    // selection vocabulary too: idle row transparent + light-grey text,
    // selected row a bright frosted-white pill with dark text.
    //
    // Drill-through actions (hide apps, button shortcuts, wallpaper,
    // system settings) close the panel before launching the next
    // surface. The Show clock toggle stays in place — the user can flip
    // it and continue browsing the panel. The keymap card knows it was
    // opened from the panel via {@link #keymapOpenedFromSettings} and
    // re-opens the panel after the keymap card is dismissed, so a deep
    // "settings → button shortcuts → bind a key → back" gesture lands
    // exactly back at the panel cursor where the user left off.

    /** Show the shared dim backdrop if it isn't already visible. Idempotent
     *  — the second consecutive call (e.g. opening keymap on top of an
     *  already-open settings panel) is a no-op so the dim level stays
     *  constant across the modal flow. */
    private void ensureOverlayBackdropVisible() {
        View bd = overlayBackdrop;
        if (bd == null) return;
        if (bd.getVisibility() == View.VISIBLE && bd.getAlpha() >= 0.99f) return;
        bd.animate().cancel();
        bd.setVisibility(View.VISIBLE);
        bd.bringToFront();
        bd.animate().alpha(1f).setDuration(140).start();
    }

    /** Hide the shared backdrop only when neither overlay is logically
     *  open. "Logically open" includes a queued re-open via
     *  {@link #keymapOpenedFromSettings} — the 60 ms postDelayed window
     *  between hideKeymapOverlay's withEndAction and the panel re-show
     *  must not flash the wallpaper visible. */
    private void dismissOverlayBackdropIfIdle() {
        View bd = overlayBackdrop;
        if (bd == null) return;
        if (anyOverlayLogicallyOpen()) return;
        bd.animate().cancel();
        bd.animate()
                .alpha(0f)
                .setDuration(140)
                .withEndAction(() -> {
                    if (bd != overlayBackdrop) return;
                    if (anyOverlayLogicallyOpen()) return;
                    bd.setVisibility(View.GONE);
                })
                .start();
    }

    /** True when any modal overlay is currently visible OR a deferred
     *  re-open is queued. Drives the backdrop's stay-or-fade decision. */
    private boolean anyOverlayLogicallyOpen() {
        if (keymapOpenedFromSettings) return true;
        FrameLayout sp = settingsOverlay;
        if (sp != null && sp.getVisibility() == View.VISIBLE) return true;
        FrameLayout ko = keymapOverlay;
        if (ko != null && ko.getVisibility() == View.VISIBLE) return true;
        return false;
    }

    /** Equalise every settings-panel row's width to the widest measured
     *  row. Same pattern as {@code equalizeKeymapRowWidths} on the keymap
     *  card. Eliminates the right-side dead space the v1.3.0 initial
     *  design left when {@code FrameLayout.LayoutParams(dp(252), WRAP)}
     *  forced every row to a fixed-width column regardless of content.
     *  Indicators stay aligned at the right edge across all rows because
     *  every row ends at the same x. Called once after the panel is
     *  built (post-layout via {@link View#post}); the row widths don't
     *  drift after that since the rows are i18n-static
     *  {@code String} resources. */
    private void equalizeSettingsRowWidths(android.widget.LinearLayout col) {
        int max = 0;
        for (int i = 0; i < col.getChildCount(); i++) {
            View child = col.getChildAt(i);
            if (!(child instanceof android.widget.LinearLayout)) continue;
            child.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int w = child.getMeasuredWidth();
            if (w > max) max = w;
        }
        if (max <= 0) return;
        for (int i = 0; i < col.getChildCount(); i++) {
            View child = col.getChildAt(i);
            if (!(child instanceof android.widget.LinearLayout)) continue;
            android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp == null) continue;
            lp.width = max;
            child.setLayoutParams(lp);
        }
    }

    // ── Settings panel build / show / hide / refresh / activate ─────────

    /** Lazy-build the settings panel on first {@link #showSettingsPanel}.
     *  Re-used across opens. Same plate / row / animation primitives as
     *  the keymap overlay — only the row count and content differ. */
    private void buildSettingsPanel() {
        FrameLayout r = root; if (r == null) return;
        FrameLayout ov = new FrameLayout(this) {
            @Override public boolean onTouchEvent(MotionEvent ev) {
                // Tap-outside-the-card dismisses, matching the keymap
                // card and context-menu UX.
                if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                    android.widget.LinearLayout c = settingsCard;
                    if (c != null) {
                        float x = ev.getX(), y = ev.getY();
                        float l = c.getX(), t = c.getY();
                        float rt = l + c.getWidth(), b = t + c.getHeight();
                        if (x < l || x > rt || y < t || y > b) {
                            hideSettingsPanel();
                            return true;
                        }
                    }
                }
                return super.onTouchEvent(ev);
            }
        };
        ov.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        ov.setVisibility(View.GONE);
        // The dim is provided by the shared overlayBackdrop view; this
        // overlay is a transparent click-catcher only. See
        // ensureOverlayBackdropVisible / dismissOverlayBackdropIfIdle.
        ov.setClickable(true);
        ov.setFocusable(true);

        // Card — matches the keymap card's plate, rim, and corner radius.
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable cardBg =
                new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xF21A1A1F);                          // deep slate
        cardBg.setStroke(Math.max(1, dp(1) / 2), 0x1AFFFFFF); // 1 dp hairline rim
        cardBg.setCornerRadius(dp(18));
        card.setBackground(cardBg);
        card.setPadding(dp(8), dp(7), dp(8), dp(7));
        card.setClipChildren(false);
        card.setClipToPadding(false);

        // Vertical column of 5 rows.
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);

        // Build each row. Row geometry mirrors the keymap card's slot
        // rows so the focus pill aligns horizontally across both panels
        // (a user who has the keymap card and the settings panel in
        // muscle memory sees the same selection language in both).
        // Row label string ids in the same order as SETTINGS_ROW_*
        // constants. Indicator: "›" for drill-throughs, "✓" for the
        // toggle (set on the actual selected state in refreshSettingsRows).
        final int[] rowLabels = new int[] {
                R.string.settings_row_manage_hidden,
                R.string.settings_row_button_shortcuts,
                R.string.settings_row_set_wallpaper,
                R.string.settings_row_show_clock,
                R.string.settings_row_system_settings,
        };
        for (int i = 0; i < SETTINGS_ROW_COUNT; i++) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(7), dp(10), dp(7));
            android.graphics.drawable.GradientDrawable rowBg =
                    new android.graphics.drawable.GradientDrawable();
            rowBg.setCornerRadius(dp(9));
            rowBg.setColor(Color.TRANSPARENT);
            row.setBackground(rowBg);

            // [0] label — WRAP_CONTENT with end-padding so the indicator
            //     (when present) sits a small visual gap to its right.
            //     Drill-through rows have no indicator in v1.3.2 — the
            //     end-margin is dropped to 0 for them so the row hugs
            //     the label tightly.
            TextView label = new TextView(this);
            label.setText(rowLabels[i]);
            label.setTextColor(0xCCFFFFFF);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            android.widget.LinearLayout.LayoutParams labelLp =
                    new android.widget.LinearLayout.LayoutParams(WRAP, WRAP);
            // Only the toggle row needs an end-margin to gap from its
            // checkmark indicator. Drill-through rows have nothing to
            // their right, so end-margin = 0 lets the row's natural
            // width = label width exactly. equalizeSettingsRowWidths
            // then pads every row to the widest measured width so
            // selection pills still align consistently.
            labelLp.setMarginEnd(i == SETTINGS_ROW_SHOW_CLOCK ? dp(14) : 0);
            row.addView(label, labelLp);

            // [1] right-side indicator — only on the Show clock toggle
            //     row in v1.3.2. The four drill-through rows (Manage
            //     hidden apps, Button shortcuts, Set wallpaper, System
            //     Settings) render as label-only per the v1.3.2 design
            //     pass. The chevron column is gone — the panel reads as
            //     a clean list of action labels and shrinks tighter
            //     around the longest one.
            //
            //     refreshSettingsRows is index-tolerant: it tests
            //     row.getChildAt(1) for null before mutating, so the
            //     missing-indicator rows skip the indicator paint cleanly.
            if (i == SETTINGS_ROW_SHOW_CLOCK) {
                TextView indicator = new TextView(this);
                indicator.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
                indicator.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                indicator.setSingleLine(true);
                indicator.setText("\u2713"); // ✓
                row.addView(indicator,
                        new android.widget.LinearLayout.LayoutParams(WRAP, WRAP));
            }

            // Row uses WRAP_CONTENT initially so the natural width is
            // (label + margin + indicator). equalizeSettingsRowWidths
            // (called post-build) snaps every row to the widest measured
            // width so all indicators line up vertically at the right
            // edge while the card auto-fits to the longest label.
            android.widget.LinearLayout.LayoutParams rowLp =
                    new android.widget.LinearLayout.LayoutParams(WRAP, WRAP);
            rowLp.bottomMargin = dp(2);
            col.addView(row, rowLp);
        }

        // Touch support: each row is independently clickable, so a TV
        // remote user uses d-pad and a touchscreen / mouse user gets the
        // same affordances. Click also moves the selection cursor to the
        // tapped row before activating, so the focus pill highlight
        // matches what was just pressed.
        for (int i = 0; i < col.getChildCount(); i++) {
            final int idx = i;
            View row = col.getChildAt(i);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                v.playSoundEffect(SoundEffectConstants.CLICK);
                settingsSelectedRow = idx;
                refreshSettingsRows();
                activateSettingsRow(idx);
            });
        }

        card.addView(col, new android.widget.LinearLayout.LayoutParams(WRAP, WRAP));
        // Card width is now WRAP_CONTENT so it auto-fits the widest row's
        // intrinsic width (no fixed 252 dp column). The card will hug the
        // longest visible label + chevron with a small breathing-room
        // padding, no right-side dead space.
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(WRAP, WRAP);
        cardLp.gravity = Gravity.TOP | Gravity.END;
        card.setLayoutParams(cardLp);
        ov.addView(card);

        r.addView(ov);
        settingsOverlay = ov;
        settingsCard    = card;
        settingsColumn  = col;

        // Equalise row widths in a post() so each row's measure pass has
        // run. Touching rowLp.width here directly would race with the
        // first layout pass and produce zero widths.
        col.post(() -> {
            if (col != settingsColumn) return;
            equalizeSettingsRowWidths(col);
        });
    }

    /** Animate the panel in as a dropdown under the gear toolbar pill.
     *  Same anchor logic as {@link #showKeymapOverlay} so both surfaces
     *  appear to fall out of the same icon — but the keymap card's anchor
     *  resolves against the gear too, so opening keymap from inside the
     *  settings panel keeps the visual continuity. */
    private void showSettingsPanel() {
        if (destroyed) return;
        if (settingsOverlay == null) buildSettingsPanel();
        FrameLayout ov = settingsOverlay;
        final android.widget.LinearLayout card = settingsCard;
        if (ov == null || card == null) return;

        // Hide focus ring — it belongs to the shelf which is now logically
        // behind the panel.
        RingView rv = ringView; if (rv != null) rv.setVisibility(View.INVISIBLE);

        // Bring up the shared dim backdrop. Idempotent — when this is
        // called as part of a settings → keymap → settings round-trip
        // the backdrop is already at full alpha and this call is a
        // no-op, so the dim level stays constant.
        ensureOverlayBackdropVisible();

        // Land the cursor on the row a drill-through restores to (set by
        // activateSettingsRow before it called hideSettingsPanel), then
        // reset the pending cursor so the NEXT first-open from the gear
        // pill starts at row 0 again. This makes the back-stack read
        // naturally: gear → panel (row 0) → click "Button shortcuts" →
        // keymap card → BACK → panel (row 1, where the user left off) →
        // BACK → home → gear → panel (row 0 again, fresh open).
        settingsSelectedRow = pendingSettingsCursor;
        pendingSettingsCursor = 0;
        refreshSettingsRows();

        // Anchor the card just below the gear toolbar pill — shared
        // helper since the keymap card uses the identical math.
        anchorCardUnderGear(card, dp(78), dp(20));

        ov.setVisibility(View.VISIBLE);
        ov.bringToFront();
        ov.requestFocus();

        // Drop-down animation: same shape and timing as the keymap card so
        // both surfaces feel like the same primitive opening from the same
        // pill.
        card.animate().cancel();
        card.setAlpha(0f);
        card.setScaleX(0.94f); card.setScaleY(0.86f);
        card.setTranslationY(-dp(6));
        card.post(() -> {
            if (card != settingsCard) return;
            card.setPivotX(card.getWidth());
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

    /** Animate the panel out and restore focus to the gear pill. */
    private void hideSettingsPanel() {
        final FrameLayout ov = settingsOverlay;
        final android.widget.LinearLayout card = settingsCard;
        if (ov == null) return;
        if (card != null) {
            card.animate().cancel();
            card.animate()
                    .alpha(0f)
                    .scaleX(0.96f).scaleY(0.9f)
                    .translationY(-dp(4))
                    .setDuration(110)
                    .setInterpolator(MENU_OUT)
                    .withEndAction(() -> {
                        if (ov != settingsOverlay) return;
                        ov.setVisibility(View.GONE);
                        // Drop the dim only if no other overlay is
                        // logically open (covers the immediate-close
                        // case AND the settings → keymap transition
                        // where the keymap card has already taken
                        // over the modal flow).
                        dismissOverlayBackdropIfIdle();
                    })
                    .start();
        } else {
            ov.setVisibility(View.GONE);
            dismissOverlayBackdropIfIdle();
        }
        // Restore focus to the gear pill so the user lands back where
        // they triggered the panel. Falls through to the WiFi pill if
        // the gear has been GC'd (paranoia — it is held as a field).
        View mb = mapperBtnView;
        if (mb != null) mb.requestFocus();
        else {
            View nb = netBtn;
            if (nb != null) nb.requestFocus();
        }
    }

    /** Repaint each row to reflect {@link #settingsSelectedRow} and the
     *  current {@link #showClock} toggle state. Cheap — five rows, each
     *  a small LinearLayout with two children. The "selected" row gets a
     *  bright frosted-white pill and dark text + dark indicator; idle
     *  rows get transparent backgrounds and light-grey text. */
    private void refreshSettingsRows() {
        android.widget.LinearLayout col = settingsColumn;
        if (col == null) return;
        final int hlWhite = 0xFFEFEFEF;
        final int idleBg  = Color.TRANSPARENT;
        final int idleTx  = 0xCCFFFFFF;
        final int selTx   = 0xFF111114;
        for (int i = 0; i < col.getChildCount(); i++) {
            View child = col.getChildAt(i);
            if (!(child instanceof android.widget.LinearLayout)) continue;
            android.widget.LinearLayout row = (android.widget.LinearLayout) child;
            boolean sel = (i == settingsSelectedRow);
            // Background — mutate the existing GradientDrawable so the
            // 9 dp corner radius is preserved across paints. Wrapping in
            // setBackgroundColor would clobber the drawable.
            android.graphics.drawable.Drawable bg = row.getBackground();
            if (bg instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) bg)
                        .setColor(sel ? hlWhite : idleBg);
            }
            View labelView     = row.getChildAt(0);
            View indicatorView = row.getChildAt(1);
            if (labelView instanceof TextView) {
                ((TextView) labelView).setTextColor(sel ? selTx : idleTx);
            }
            if (indicatorView instanceof TextView) {
                TextView ind = (TextView) indicatorView;
                if (i == SETTINGS_ROW_SHOW_CLOCK) {
                    // The toggle row's indicator carries an additional
                    // channel of state: ON = ✓ visible, OFF = ✓ alpha-
                    // dimmed (rendered nearly invisible) so the row's
                    // height stays constant and the layout doesn't
                    // shift when the toggle flips.
                    ind.setText("\u2713");
                    if (showClock) {
                        ind.setTextColor(sel ? selTx : 0xFF7DD3FC); // sky cyan when on + idle
                    } else {
                        // 0x33 alpha — visually reads as "off" without
                        // collapsing the row layout.
                        ind.setTextColor(sel ? 0x66111114 : 0x33FFFFFF);
                    }
                } else {
                    ind.setTextColor(sel ? selTx : idleTx);
                }
            }
        }
    }

    /** D-pad / OK / Back navigation inside the settings panel. Returns
     *  {@code true} when handled, the activity-level dispatcher relays
     *  every other key to {@code super.dispatchKeyEvent} so volume /
     *  power / media keys reach the platform unchanged. */
    private boolean handleSettingsKey(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:
                settingsSelectedRow =
                        (settingsSelectedRow - 1 + SETTINGS_ROW_COUNT) % SETTINGS_ROW_COUNT;
                refreshSettingsRows(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                settingsSelectedRow = (settingsSelectedRow + 1) % SETTINGS_ROW_COUNT;
                refreshSettingsRows(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                activateSettingsRow(settingsSelectedRow);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                hideSettingsPanel(); return true;
        }
        // Allow volume / power / media to pass through; swallow other
        // keys so they don't bleed to the shelf underneath.
        if (isLetThroughKey(kc)) return false;
        return true;
    }

    /** Execute the action bound to the given panel row. */
    private void activateSettingsRow(int row) {
        switch (row) {
            case SETTINGS_ROW_HIDE_APPS:
                // Hand off to the keymap card's HIDE mode. Set both the
                // re-open flag (so dismissing the keymap card returns to
                // this panel) and the skip-slots flag (so Back from HIDE
                // bypasses the SLOTS list and dismisses the keymap card
                // immediately, since the user came in from settings, not
                // from the slot list). hide-then-show keeps the dim
                // constant via the shared backdrop.
                pendingSettingsCursor       = SETTINGS_ROW_HIDE_APPS;
                keymapOpenedFromSettings    = true;
                hideManagerSkipSlotsOnExit  = true;
                hideSettingsPanel();
                showKeymapOverlay();
                enterHideManager();
                break;
            case SETTINGS_ROW_KEYMAP:
                // Hand off to the keymap card's SLOTS mode (default).
                pendingSettingsCursor    = SETTINGS_ROW_KEYMAP;
                keymapOpenedFromSettings = true;
                hideSettingsPanel();
                showKeymapOverlay();
                break;
            case SETTINGS_ROW_WALLPAPER:
                // Wallpaper picker is a system surface (SAF). Close the
                // panel before launching so the dim backdrop doesn't
                // sit behind the picker on slow ROMs.
                hideSettingsPanel();
                openStoragePicker();
                break;
            case SETTINGS_ROW_SHOW_CLOCK:
                // In-place toggle. Persist + apply + repaint indicator.
                // Panel stays open so the user can flip multiple
                // toggles in sequence (no toggle other than this one
                // exists today, but the design accommodates future
                // additions trivially).
                showClock = !showClock;
                prefs.edit()
                        .putBoolean(KEY_SHOW_CLOCK, showClock).apply();
                if (showClock) {
                    // Rendering the clock for the first time after a
                    // toggle: reset formatter so the next paint runs
                    // unconditionally (the per-minute idempotency guard
                    // would otherwise skip the redraw if the minute
                    // hasn't changed since the launcher cold-started).
                    clockFmt.reset();
                    startClock();
                } else {
                    // Hide pill and stop scheduling. clockFmt itself
                    // doesn't need teardown — it's a tiny pooled
                    // formatter that will simply not be invoked.
                    stopClock();
                    TextView cv = clockView;
                    if (cv != null) cv.setVisibility(View.GONE);
                }
                refreshSettingsRows(); // repaint the ✓ indicator
                break;
            case SETTINGS_ROW_SYSTEM_SETTINGS:
                hideSettingsPanel();
                openSystemSettings();
                break;
            default:
                break;
        }
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
        // v1.3.0: dim is now provided by the shared overlayBackdrop view
        // (see ensureOverlayBackdropVisible / dismissOverlayBackdropIfIdle)
        // so transitioning settings → keymap doesn't flash a re-dim.
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

            // [0] indicator — colour disc for the four colour keys, 3-line
            //     hamburger for Menu, "CC" badge for Subtitle. v1.3.3:
            //     glyphs invert their colour when the row is selected
            //     (the row's bright frosted-white selection pill would
            //     hide the warm-white idle glyph colour otherwise — the
            //     "white selector blends, icon not visible" issue from
            //     v1.3.2 device testing). Colour discs keep their full
            //     saturated colour in both states. Container size dp(11)
            //     gives the hamburger / CC glyphs enough room to stay
            //     legible at TV viewing distance; the dot variant scales
            //     its drawn radius to match the pre-v1.3.2 dp(7) visual
            //     diameter so colour-row symmetry is preserved.
            ShortcutTagView tag = new ShortcutTagView(SHORTCUT_GLYPHS[i], SHORTCUT_TAGS[i]);
            android.widget.LinearLayout.LayoutParams tagLp =
                    new android.widget.LinearLayout.LayoutParams(dp(11), dp(11));
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
            clipCircular(icon);   // round small icon
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

        // The divider + "Manage hidden apps" row that used to sit at the
        // bottom of the slot list moved to the unified settings panel in
        // v1.3.0. The keymap card is now strictly key-binding territory —
        // 6 rows, no extras. Hide-apps drill-in lives at
        // {@code SETTINGS_ROW_HIDE_APPS} and reaches the same
        // {@link #enterHideManager} surface this card hosts in HIDE mode.

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

        // ── Hide-manager view (v1.5.0: vertical list) ───────────────
        // Redesigned from the old horizontal chip strip to a vertical,
        // OK-toggleable list that mirrors the button-shortcuts slot list:
        // round app icon + label per row, ~6 rows visible and the rest
        // scrollable, no dead space. The hidden flag is a strike-through on
        // the label (kept from the previous design).
        android.widget.LinearLayout hideView = new android.widget.LinearLayout(this);
        hideView.setOrientation(android.widget.LinearLayout.VERTICAL);
        hideView.setVisibility(View.GONE);
        hideView.setClipChildren(false);
        hideView.setClipToPadding(false);

        // Header: the "Hide apps from shelf · OK toggles" title. It names the
        // list and reminds the user that OK toggles the hidden flag — restored
        // per user request and styled like the picker title (tight padding so
        // there's no dead space above the list).
        TextView hideTitle = new TextView(this);
        hideTitle.setText(R.string.keymap_hide_title);
        hideTitle.setTextColor(0xFFEFEFEF);
        hideTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        hideTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hideTitle.setLetterSpacing(0.03f);
        hideTitle.setSingleLine(true);
        hideTitle.setEllipsize(TextUtils.TruncateAt.END);
        hideTitle.setPadding(dp(4), dp(2), dp(4), dp(6));
        hideView.addView(hideTitle);

        // Vertical scroller capped at HIDE_VISIBLE_ROWS rows tall — content
        // shorter than that wraps (no empty space); longer scrolls.
        final int hideRowH = dp(HIDE_ROW_H_DP);
        android.widget.ScrollView hideScroll = new android.widget.ScrollView(this) {
            @Override protected void onMeasure(int wSpec, int hSpec) {
                // Cap at HIDE_VISIBLE_ROWS rows (row height + 2dp bottom margin)
                // so exactly that many show; shorter content wraps (no empty
                // space), longer scrolls.
                int cap = (hideRowH + dp(2)) * HIDE_VISIBLE_ROWS;
                super.onMeasure(wSpec,
                        View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
            }
        };
        hideScroll.setVerticalScrollBarEnabled(false);
        hideScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        android.widget.LinearLayout.LayoutParams hsLp =
                new android.widget.LinearLayout.LayoutParams(dp(248), WRAP);
        hideView.addView(hideScroll, hsLp);

        android.widget.LinearLayout hideStrip = new android.widget.LinearLayout(this);
        hideStrip.setOrientation(android.widget.LinearLayout.VERTICAL);
        hideScroll.addView(hideStrip,
                new android.widget.FrameLayout.LayoutParams(MATCH, WRAP));

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
        keymapHideScroll  = hideScroll;
        keymapHideStrip   = hideStrip;
    }

    private void showKeymapOverlay() {
        if (destroyed) return;
        if (keymapOverlay == null) buildKeymapOverlay();
        FrameLayout ko = keymapOverlay;
        final android.widget.LinearLayout card = keymapCard;
        if (ko == null || card == null) return;
        // Warm hidden-app icons before any chip strip starts asking
        // {@code iconCache.get(pkg)}. The chip-strip's bind path doesn't
        // queue loads on miss — it only renders the cell GONE — so an
        // unwarmed hidden-app bitmap stays missing until something else
        // happens to load it. Idempotent, cheap on warm caches; only
        // does real work on the rare path "package broadcast invalidated
        // a hidden app's icon since the last overlay open". See
        // {@link #preWarmChipIcons} for the cost analysis.
        preWarmChipIcons();
        // Hide the focus ring — it belongs to the shelf, which is now
        // logically behind the overlay.
        RingView rv = ringView; if (rv != null) rv.setVisibility(View.INVISIBLE);
        // Shared dim backdrop. Idempotent when transitioning from the
        // settings panel — already at full alpha so this is a no-op
        // and the dim level stays constant.
        ensureOverlayBackdropVisible();
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
        anchorCardUnderGear(card, topMargin, rightMargin);

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
        // Apply any pending hide toggles to the shelf — done exactly once
        // per overlay session, so a long editing session of N toggles
        // triggers exactly one shelf rebuild instead of N. Runs ahead of
        // the snap-close branch below because both paths need the
        // shelf re-filtered before the overlay disappears.
        if (keymapHideDirty) {
            keymapHideDirty = false;
            applyShelfApps(shelf);
        }

        // ── Snap-close path: keymap → settings hand-off ──────────────────
        // When the user drilled into the keymap card from the settings
        // panel (keymapOpenedFromSettings == true), Back from the keymap
        // card needs to land them back in the panel. The animated path
        // below produced two visible artefacts when used for this case:
        //
        //   1. The reset-to-SLOTS code at the top of this method had to
        //      run BEFORE the close animation so the next open started
        //      clean. With the card still visible during the 110 ms
        //      animate-out, that meant the user saw SLOTS mode behind
        //      the fading-HIDE card — the "button shortcuts appears for
        //      a second" bug reported on v1.3.1.
        //   2. The 60 ms postDelayed re-open of the settings panel
        //      overlapped the 110 ms close animation, so the settings
        //      card and the keymap card were both partially visible
        //      simultaneously for ~50 ms — visible cross-fade flicker.
        //
        // Snap-close eliminates both: hide the keymap card instantly
        // (no animation, no SLOTS reset), then synchronously open the
        // settings panel which runs its own 160 ms in-animation. The
        // shared backdrop stays at full alpha throughout so there is
        // no dim flicker. The user sees the HIDE chips disappear and
        // the settings panel slide in immediately.
        if (keymapOpenedFromSettings) {
            keymapOpenedFromSettings = false;
            if (card != null) {
                card.animate().cancel();
                card.setAlpha(1f);
                card.setScaleX(1f); card.setScaleY(1f);
                card.setTranslationY(0f);
            }
            // Order matters: show the settings panel BEFORE hiding the
            // keymap overlay so {@link #showSettingsPanel}'s
            // {@code ov.requestFocus()} grabs focus while ko is still
            // hosting the previously-focused chip / slot row. After
            // that requestFocus runs, ko has no focused descendant —
            // it's safe to flip ko to GONE.
            //
            // The reverse order (the v1.4.x first cut, fixed in v1.4.x)
            // produced a user-visible bug: opening hide-apps or
            // button-shortcuts from the middle of the drawer, then
            // pressing BACK once, would auto-shift the drawer left or
            // right "toward an end". Mechanism:
            //   1. ko.setVisibility(GONE) on a still-focused overlay
            //      synchronously calls clearFocus on the focused
            //      descendant and runs a focus search for the next
            //      focusable view in the tree.
            //   2. The shelf cells are the next focusable candidates;
            //      the search picks whichever visible cell is closest
            //      (geometrically) to the previously-focused chip.
            //   3. The picked cell's onFocusChange listener fires
            //      synchronously inside the search:
            //        - {@code focusedIndex = boundIndex} overwrites the
            //          shelf's actual saved index with the picked
            //          cell's index;
            //        - {@code ensureVisible(boundIndex)} scrolls the
            //          shelf so the picked cell sits at its preferred
            //          viewport position.
            //   4. {@code showSettingsPanel()} ran AFTER, so by the
            //      time it grabbed focus the shelf had already been
            //      scrolled — the user saw the drift.
            //
            // Showing the panel first short-circuits step 1: ko's
            // focused descendant relinquishes focus to ov via the
            // explicit requestFocus, so there's no descendant left to
            // clear when ko later goes GONE. No focus search, no
            // accidental {@code focusedIndex} overwrite, no scroll.
            //
            // NOTE: deliberately do NOT touch keymapMode / sub-view
            // visibilities here. The next showKeymapOverlay call resets
            // them all to SLOTS as its first step, so any state we
            // leave behind here is overwritten on the next open. This
            // keeps the snap-close path zero-work beyond the visibility
            // flip and the alpha reset.
            showSettingsPanel();
            ko.setVisibility(View.GONE);
            return;
        }

        // ── Animated close path: user closing the keymap card directly ───
        // Reset to slot-list mode so a future re-open is consistent
        // (avoids the case where Back from slot-list closes the overlay
        // while picker mode was still cached as the active sub-view).
        // For the keymap → home path this happens BEFORE the close
        // animation since the user won't see the slot column anyway —
        // the next open will start in SLOTS regardless.
        keymapMode = KEYMAP_MODE_SLOTS;
        if (keymapPickerView != null) keymapPickerView.setVisibility(View.GONE);
        if (keymapHideView   != null) keymapHideView  .setVisibility(View.GONE);
        if (keymapColumn     != null) keymapColumn    .setVisibility(View.VISIBLE);
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
                        dismissOverlayBackdropIfIdle();
                    })
                    .start();
        } else {
            ko.setVisibility(View.GONE);
            dismissOverlayBackdropIfIdle();
        }
        // Restore focus to the gear button so the user lands back where
        // they triggered the overlay (gear is the only entry point into
        // the keymap card now that the wallpaper pill is gone).
        View mb = mapperBtnView;
        if (mb != null) mb.requestFocus();
        else {
            View nb = netBtn;
            if (nb != null) nb.requestFocus();
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
                // Truly unassigned: no icon column at all. GONE collapses
                // the slot so the val text sits at the natural left
                // position. (Different from the assigned-but-bitmap-not-
                // loaded case below, which reserves the slot via
                // INVISIBLE so the val never shifts when the bitmap
                // eventually arrives.)
                icon.setVisibility(View.GONE);
                icon.setImageDrawable(null);
            } else {
                AppInfo a = findAppByPackage(pkg);
                val.setText(a != null ? a.label : pkg);
                Bitmap bmp = (iconCache != null) ? iconCache.get(pkg) : null;
                if (bmp != null) {
                    icon.setImageBitmap(bmp);
                    icon.setVisibility(View.VISIBLE);
                } else {
                    // Bitmap not in iconCache yet — the icon is being
                    // loaded asynchronously and {@link #onIconLoaded}
                    // will fire {@link #refreshKeymapRows} when the
                    // bitmap lands. Keep the icon slot at INVISIBLE
                    // (reserves layout space, doesn't draw) so the val
                    // text does NOT shift between "loading" and
                    // "loaded" states. Without this, the val text
                    // moved sideways every time an async icon arrived
                    // — a visible jiggle inside the keymap card.
                    icon.setImageDrawable(null);
                    icon.setVisibility(View.INVISIBLE);
                }
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

            // v1.3.3: indicator-glyph colour inversion. The hamburger and
            // CC glyphs in particular need to flip from warm white to
            // near-black when the row is selected so they stay visible
            // against the bright frosted-white selection pill (the
            // v1.3.2 "white selector blends, icon not visible" issue).
            // The colour discs ignore the selected flag — they keep
            // their saturated brand colour in both states. setSelectedState
            // is a no-op when the state hasn't actually changed, so the
            // call is safe to fire on every refresh.
            View first = row.getChildAt(0);
            if (first instanceof ShortcutTagView) {
                ((ShortcutTagView) first).setSelectedState(sel);
            }
        }

        // The manage-hidden-apps row that used to sit at index `rows` was
        // removed in v1.3.0; it now lives in the unified settings panel.
        // No special-case repaint needed here — the slot loop above
        // covers every visible row.

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
     *  6 key rows). v1.3.0 dropped the divider + manage row from this
     *  column — the {@code instanceof LinearLayout} check is kept as a
     *  defensive guard against future structural drift. Called from
     *  refreshKeymapRows() after every binding/text change so the menu
     *  auto-fits the longest app label.
     *
     *  <p>v1.4.1 audit: dropped the per-row "restore prevW between
     *  measure and final-set" pass. The intermediate width does not
     *  affect the final result — every row is unconditionally re-snapped
     *  to {@code max} below — so restoring it just to overwrite it on
     *  the next pass was one wasted {@code setLayoutParams} call (and
     *  the {@code requestLayout} cascade it triggers) per row. With
     *  6 rows that's 6 cascades saved per re-equalize. Functional
     *  output unchanged. */
    private void equalizeKeymapRowWidths(android.widget.LinearLayout col, int rows) {
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int n = col.getChildCount();
        int max = 0;
        // Pass 1: force every row to WRAP_CONTENT (so {@code measure()}
        // reports the natural intrinsic width, not the previous
        // {@code max}-snapped width), then measure. We DO NOT restore
        // here — pass 2 below sets every row to the new {@code max}
        // unconditionally, so the WRAP_CONTENT state is transient by
        // construction.
        for (int i = 0; i < n; i++) {
            View child = col.getChildAt(i);
            if (!(child instanceof android.widget.LinearLayout)) continue;
            ViewGroup.LayoutParams clp = child.getLayoutParams();
            if (clp != null && clp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                clp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                child.setLayoutParams(clp);
            }
            child.measure(spec, spec);
            int w = child.getMeasuredWidth();
            if (w > max) max = w;
        }
        if (max <= 0) return;
        // Pass 2: snap every row whose width differs from the new max.
        // Same as before; only the in-between restore pass was redundant.
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
        } else {
            // Strip cached from a previous open — top up any chips whose
            // bitmap was missing from iconCache at build time but has
            // since been loaded. See refreshHideChipIcons for the same
            // pattern in the hide-manager strip.
            refreshPickerChipIcons();
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

    /** Mirror of {@link #refreshHideChipIcons} for the keymap picker strip.
     *  The picker has a leading "Not assigned" sentinel chip with no
     *  ImageView, so chip i in the strip corresponds to appList[i-1]. */
    private void refreshPickerChipIcons() {
        android.widget.LinearLayout strip = keymapPickerStrip;
        if (strip == null || iconCache == null) return;
        int n = Math.min(strip.getChildCount() - 1, appList.size());
        for (int i = 0; i < n; i++) {
            View chip = strip.getChildAt(i + 1); // +1 skips the sentinel
            if (!(chip instanceof android.widget.LinearLayout)) continue;
            android.widget.LinearLayout cl = (android.widget.LinearLayout) chip;
            // Picker app-chip child layout: [icon (0), label (1)].
            View v = cl.getChildAt(0);
            if (!(v instanceof ImageView)) continue;
            ImageView iv = (ImageView) v;
            if (iv.getVisibility() == View.VISIBLE && iv.getDrawable() != null) continue;
            Bitmap b = iconCache.get(appList.get(i).packageName);
            if (b != null) {
                iv.setImageBitmap(b);
                iv.setVisibility(View.VISIBLE);
            }
        }
    }

    /** Live-update hook called from the icon-delivery callbacks in
     *  {@link #preWarmIcon} / {@link #loadIconAsync}. If either chip strip
     *  is currently visible, refresh the matching chip's ImageView so the
     *  user sees the icon appear without having to close and reopen the
     *  overlay. The keymap slot rows (which can also display a hidden
     *  app's icon as a binding miniature) are repainted via the cheap
     *  {@link #refreshKeymapRows} call when in slot mode. */
    private void onIconLoaded(String pkg, Bitmap bmp) {
        if (pkg == null || bmp == null) return;
        // Cheap early-out: skip everything if the keymap overlay isn't on
        // screen. hideKeymapOverlay leaves the inner sub-views (column /
        // picker / hide) at their pre-close visibilities, so checking the
        // top-level overlay is the only reliable "is the user looking at
        // this right now?" signal.
        FrameLayout ko = keymapOverlay;
        if (ko == null || ko.getVisibility() != View.VISIBLE) return;
        int idx = indexInAppList(pkg);
        // Hide-manager strip: chip i ↔ appList[i].
        if (keymapMode == KEYMAP_MODE_HIDE) {
            android.widget.LinearLayout hStrip = keymapHideStrip;
            if (hStrip != null && idx >= 0 && idx < hStrip.getChildCount()) {
                setChipIcon(hStrip.getChildAt(idx), 0, bmp);
            }
        }
        // Picker strip: leading sentinel offsets app indices by 1.
        if (keymapMode == KEYMAP_MODE_PICKER) {
            android.widget.LinearLayout pStrip = keymapPickerStrip;
            if (pStrip != null && idx >= 0 && (idx + 1) < pStrip.getChildCount()) {
                setChipIcon(pStrip.getChildAt(idx + 1), 0, bmp);
            }
        }
        // Slot rows: only when the slot list is the active sub-mode AND
        // the just-loaded package is actually bound to a slot. Without
        // the binding check the slot card is repainted on every icon
        // delivery during the cold-start icon flood (~50 deliveries on
        // a typical TV) — each repaint walks all 6 rows, re-checks the
        // bitmap cache, and re-mutates colours / GradientDrawable
        // backgrounds. Most of those repaints are pure noise because
        // the package whose icon just landed isn't shown anywhere on
        // the slot card. The size of keyMap is bounded by the
        // SHORTCUT_KEYCODES.length (6) so the inner scan is constant
        // work per delivery.
        if (keymapMode == KEYMAP_MODE_SLOTS) {
            boolean bound = false;
            for (int i = 0, n = keyMap.size(); i < n; i++) {
                if (pkg.equals(keyMap.valueAt(i))) { bound = true; break; }
            }
            if (bound) refreshKeymapRows();
        }
    }

    /** Linear scan over appList for the given pkg. Cheap (≤ ~50 entries on
     *  a typical TV) and only used by {@link #onIconLoaded} which itself
     *  is rate-limited by icon-decode throughput. Avoids a parallel
     *  pkg→index map purely for this one path. */
    private int indexInAppList(String pkg) {
        for (int i = 0, n = appList.size(); i < n; i++) {
            if (pkg.equals(appList.get(i).packageName)) return i;
        }
        return -1;
    }

    /** Set the bitmap on an ImageView at a fixed child index inside a chip
     *  LinearLayout. Used by {@link #onIconLoaded} to top up a single
     *  chip's icon without touching its other state. */
    private void setChipIcon(View chip, int childIdx, Bitmap bmp) {
        if (!(chip instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout cl = (android.widget.LinearLayout) chip;
        View v = cl.getChildAt(childIdx);
        if (!(v instanceof ImageView)) return;
        ImageView iv = (ImageView) v;
        iv.setImageBitmap(bmp);
        iv.setVisibility(View.VISIBLE);
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

        // Icon slot: present for app chips. Cache miss at build time
        // uses INVISIBLE (reserves layout space, doesn't draw) so a
        // later icon delivery via {@link #setChipIcon} flips visibility
        // VISIBLE without changing the chip's measured width. See the
        // {@link #addHideRow} javadoc for the visual rationale — the
        // GONE → VISIBLE alternative made chips visibly resize on
        // every async icon load and shifted neighbours along the strip.
        if (!isNone) {
            ImageView iv = new ImageView(this);
            if (icon != null) { iv.setImageBitmap(icon); iv.setVisibility(View.VISIBLE); }
            else              { iv.setVisibility(View.INVISIBLE); }
            clipCircular(iv);   // round small icon
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
        if (ev.getAction() != KeyEvent.ACTION_DOWN) {
            // Eat KEY_UP only for keys we actually consume on KEY_DOWN. Volume,
            // power, and the system keys below stay routed to the platform so
            // the user can still control the device while configuring shortcuts.
            return !isLetThroughKey(ev.getKeyCode());
        }
        int kc = ev.getKeyCode();
        // Volume / mute / power / system keys must keep working even while
        // the overlay is open — the overlay is a launcher-level dropdown,
        // not a hardware-blocking modal. Returning false here lets
        // dispatchKeyEvent fall through to super so the platform delivers
        // the key to its global handler (AudioService, PowerManager, etc.).
        if (isLetThroughKey(kc)) return false;
        if (keymapMode == KEYMAP_MODE_PICKER) return handleKeymapPickerKey(kc);
        if (keymapMode == KEYMAP_MODE_HIDE)   return handleKeymapHideKey(kc);
        return handleKeymapSlotsKey(kc);
    }

    /** Keys the keymap overlay must NOT swallow. Volume / mute / power are
     *  device-control concerns the launcher has no business eating; HOME
     *  already delivers via the platform's HOME route (we are HOME) so
     *  letting it through is harmless and consistent. */
    private static boolean isLetThroughKey(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return true;
            default:
                return false;
        }
    }

    private boolean handleKeymapSlotsKey(int kc) {
        // 6 key bindings only — the v1.2.x "manage hidden apps" 7th row
        // moved to the unified settings panel in v1.3.0.
        int rows = SHORTCUT_LABELS.length;
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
                enterAppPicker(keymapSelectedRow);
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
        } else {
            // Strip cached from a previous open — top up any chips whose
            // bitmap was missing from iconCache at build time but has
            // since been loaded (typical path: app was hidden across
            // launcher restarts, applyShelfApps' hidden-app preWarm only
            // just landed). Without this the chip's ImageView stays GONE
            // and the row reads as "label only".
            refreshHideChipIcons();
        }
        int n = appList.size();
        keymapHideIdx     = n > 0 ? 0 : -1;
        keymapHideLastIdx = -1;       // force a full repaint on first refresh
        keymapMode        = KEYMAP_MODE_HIDE;
        keymapColumn   .setVisibility(View.GONE);
        if (keymapPickerView != null) keymapPickerView.setVisibility(View.GONE);
        keymapHideView .setVisibility(View.VISIBLE);
        refreshHideStrip();
        // Initial scroll happens after layout — post() so getTop() of the
        // selected row is valid.
        final android.widget.ScrollView sv = keymapHideScroll;
        if (sv != null) sv.post(this::scrollHideToSelection);
    }

    /** Cancel the hide manager and return to slot mode. The shelf is
     *  re-filtered only on overlay close (see hideKeymapOverlay) so
     *  exiting hide mode without closing the overlay leaves the shelf
     *  alone — cheap, and avoids a flicker behind the dim.
     *
     *  <p>v1.3.0: when the user entered HIDE directly from the settings
     *  panel (the "Manage hidden apps" row), Back from HIDE should NOT
     *  drop the user into the SLOTS list (which the user never opened
     *  and doesn't expect to see). The
     *  {@link #hideManagerSkipSlotsOnExit} flag — set in
     *  {@link #activateSettingsRow} alongside
     *  {@link #keymapOpenedFromSettings} — short-circuits the SLOTS
     *  return path and dismisses the keymap card directly, which then
     *  re-opens the settings panel via the existing keymap → settings
     *  hand-off (so the user lands at "settings panel, Manage hidden
     *  apps row selected"). */
    private void exitHideManager() {
        if (hideManagerSkipSlotsOnExit) {
            hideManagerSkipSlotsOnExit = false;
            hideKeymapOverlay();
            return;
        }
        keymapMode = KEYMAP_MODE_SLOTS;
        if (keymapHideView != null) keymapHideView.setVisibility(View.GONE);
        if (keymapColumn   != null) keymapColumn  .setVisibility(View.VISIBLE);
        // Land focus back on the row the user came from. Pre-v1.3.0 this
        // was SHORTCUT_LABELS.length (the manage-hidden-apps 7th row),
        // but that row is now in the settings panel — fall back to the
        // first slot row when re-entering SLOTS from HIDE within the
        // keymap card itself.
        keymapSelectedRow = 0;
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
            addHideRow(strip, a.label, b);
        }
    }

    /** Walk the existing hide-manager chip strip and update any chip whose
     *  ImageView is hidden (icon was null at build time) with the bitmap
     *  now in iconCache. The strip is built once per app-list-size change
     *  to avoid view churn on every reopen, but the underlying iconCache
     *  populates asynchronously and lazily — so a chip built before the
     *  icon was loaded would otherwise stay icon-less for the launcher's
     *  lifetime. The fix is allocation-free: each chip already has the
     *  ImageView slot reserved (kept GONE for layout consistency); we just
     *  toggle visibility and set the bitmap. */
    private void refreshHideChipIcons() {
        android.widget.LinearLayout strip = keymapHideStrip;
        if (strip == null || iconCache == null) return;
        int n = Math.min(strip.getChildCount(), appList.size());
        for (int i = 0; i < n; i++) {
            View chip = strip.getChildAt(i);
            if (!(chip instanceof android.widget.LinearLayout)) continue;
            android.widget.LinearLayout cl = (android.widget.LinearLayout) chip;
            // Hide chip child layout: [icon (0), label (1)].
            View v = cl.getChildAt(0);
            if (!(v instanceof ImageView)) continue;
            ImageView iv = (ImageView) v;
            if (iv.getVisibility() == View.VISIBLE && iv.getDrawable() != null) continue;
            Bitmap b = iconCache.get(appList.get(i).packageName);
            if (b != null) {
                iv.setImageBitmap(b);
                iv.setVisibility(View.VISIBLE);
            }
        }
    }

    /** Build one hide-manager <em>row</em> (v1.5.0 vertical list): a round
     *  app icon + label, full row width so the selection pill spans it. The
     *  hidden flag is applied later by {@link #paintHideRow} as a
     *  strike-through on the label. Mirrors the button-shortcut slot rows. */
    private void addHideRow(android.widget.LinearLayout list,
                            String label, Bitmap icon) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(12), dp(7));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(9));
        row.setBackground(bg);

        // Round app icon. The slot is always present (INVISIBLE on a cache
        // miss) so the label never shifts when the bitmap lands.
        ImageView iv = new ImageView(this);
        if (icon != null) { iv.setImageBitmap(icon); iv.setVisibility(View.VISIBLE); }
        else              { iv.setVisibility(View.INVISIBLE); }
        clipCircular(iv);
        android.widget.LinearLayout.LayoutParams ivLp =
                new android.widget.LinearLayout.LayoutParams(dp(22), dp(22));
        ivLp.setMarginEnd(dp(10));
        row.addView(iv, ivLp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(0x99FFFFFF);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        // Weight fills the remaining row width so the pill spans the row.
        android.widget.LinearLayout.LayoutParams tvLp =
                new android.widget.LinearLayout.LayoutParams(0, WRAP, 1f);
        row.addView(tv, tvLp);

        android.widget.LinearLayout.LayoutParams rlp =
                new android.widget.LinearLayout.LayoutParams(MATCH, dp(HIDE_ROW_H_DP));
        rlp.bottomMargin = dp(2);
        list.addView(row, rlp);
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
                paintHideRow(strip.getChildAt(i), i == curr, hidden, false);
            }
        } else if (prev != curr) {
            if (prev < n) {
                boolean ph = prev < appList.size()
                        && hiddenApps.contains(appList.get(prev).packageName);
                paintHideRow(strip.getChildAt(prev), false, ph, true);
            }
            if (curr >= 0 && curr < n) {
                boolean ch = curr < appList.size()
                        && hiddenApps.contains(appList.get(curr).packageName);
                paintHideRow(strip.getChildAt(curr), true, ch, true);
            }
        }
        keymapHideLastIdx = curr;
        scrollHideToSelection();
    }

    /** Single source of truth for a hide-<em>row</em>'s visual state. Matches
     *  the slot-row paint: selected → dark text on a bright pill; idle →
     *  light text on transparent. Hidden adds a strike-through on the label
     *  so the flag is legible in both states. (No scale — rows aren't chips.) */
    private void paintHideRow(View row, boolean sel, boolean hidden, boolean animate) {
        if (row == null) return;
        android.graphics.drawable.Drawable bgd = row.getBackground();
        if (bgd instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) bgd)
                    .setColor(sel ? 0xFFEFEFEF : Color.TRANSPARENT);
        }
        if (row instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout cl = (android.widget.LinearLayout) row;
            View last = cl.getChildAt(cl.getChildCount() - 1);
            if (last instanceof TextView) {
                TextView tv = (TextView) last;
                if (sel) tv.setTextColor(0xFF111114);
                else     tv.setTextColor(hidden ? 0x66FFFFFF : 0x99FFFFFF);
                int flags = tv.getPaintFlags();
                if (hidden) flags |=  Paint.STRIKE_THRU_TEXT_FLAG;
                else        flags &= ~Paint.STRIKE_THRU_TEXT_FLAG;
                tv.setPaintFlags(flags);
            }
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

    /** Auto-scroll the vertical list so the selected row stays in view with
     *  a small margin (v1.5.0 — was a horizontal strip scroll). */
    private void scrollHideToSelection() {
        android.widget.ScrollView sv = keymapHideScroll;
        android.widget.LinearLayout strip = keymapHideStrip;
        if (sv == null || strip == null) return;
        if (keymapHideIdx < 0 || keymapHideIdx >= strip.getChildCount()) return;
        View row = strip.getChildAt(keymapHideIdx);
        if (row == null) return;
        if (row.getHeight() == 0) {
            sv.post(this::scrollHideToSelection);
            return;
        }
        int top    = row.getTop();
        int bottom = row.getBottom();
        int viewTop    = sv.getScrollY();
        int viewBottom = viewTop + sv.getHeight();
        int margin     = dp(6);
        if (top < viewTop + margin) {
            sv.smoothScrollTo(0, Math.max(0, top - margin));
        } else if (bottom > viewBottom - margin) {
            sv.smoothScrollTo(0, bottom - sv.getHeight() + margin);
        }
    }

    private boolean handleKeymapHideKey(int kc) {
        android.widget.LinearLayout strip = keymapHideStrip;
        int n = strip == null ? 0 : strip.getChildCount();
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (n > 0) keymapHideIdx = Math.max(0, keymapHideIdx - 1);
                refreshHideStrip(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
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
        List<IconTarget> waiters = new ArrayList<>(2);
        iconInflight.put(key, waiters);
        try {
            iconExecutor.execute(() -> {
                if (destroyed) return;
                Bitmap bmp = null;
                try {
                    // Disk-first, then PM. See {@link #loadIconBlocking}
                    // for the full pipeline. This consolidated helper
                    // replaced the v1.4.0 initial draft's separate
                    // {@code resolveIconDrawable + IconRenderer.process +
                    // writeAsync} sequence so the same disk-fast-path
                    // runs for every executor-thread icon load
                    // (preWarmIcon and
                    // loadIconAsync alike).
                    bmp = loadIconBlocking(app);
                    if (bmp != null) iconCache.put(key, bmp);
                } catch (OutOfMemoryError | RuntimeException ignored) {}
                if (destroyed) return;
                final Bitmap fb = bmp;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    List<IconTarget> pending = iconInflight.remove(key);
                    if (pending != null && fb != null) {
                        for (int i = 0, n = pending.size(); i < n; i++) {
                            IconTarget cell = pending.get(i);
                            // Guard: only deliver to a cell that is still attached
                            // and bound to this package. A cell that's been recycled
                            // back to the pool has visibility GONE and a null
                            // boundApp — delivering would invalidate a hidden view
                            // for nothing.
                            if (cell.iconTargetVisible() && key.equals(cell.iconTargetPackage()))
                                cell.setIconBitmap(fb);
                        }
                    }
                    // Live-update any open chip strips / slot rows showing
                    // this package — the bitmap may have been requested
                    // for the shelf but the user is currently inside the
                    // hide manager / keymap picker. Runs even when the
                    // shelf-delivery path short-circuited above (pending
                    // null after onTrimMemory clear) so the chip strip
                    // still picks up the new cache entry.
                    if (fb != null) onIconLoaded(key, fb);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { iconInflight.remove(key); }
    }

    /**
     * Background-thread icon loader: try the on-disk cache first, then
     * fall through to the PackageManager + IconRenderer pipeline.
     *
     * <p>This is the consolidated path for both {@link #preWarmIcon} and
     * {@link #loadIconAsync}. It runs on the {@code iconExecutor} (NEVER
     * on the UI thread — disk I/O and PM binder calls combined can run
     * 30-50 ms each, which would skip multiple frames if invoked from
     * {@code cell.bind} or similar).
     *
     * <p>Sequence:
     * <ol>
     *   <li>{@link IconDiskCache#tryRead} — synchronous WEBP decode of
     *       the cached file. ~2-5 ms on hit, 0 ms on miss.</li>
     *   <li>If miss: {@link #resolveIconDrawable} (PM binder +
     *       drawable resolve) → {@link IconRenderer#process}
     *       (AdaptiveIcon compositing / circle clip / plate
     *       detection). ~30-50 ms.</li>
     *   <li>On a fresh PM-resolved bitmap, mirror to disk via
     *       {@link IconDiskCache#writeAsync} so the next cold start
     *       hits the disk fast-path.</li>
     * </ol>
     *
     * <p>Returns {@code null} on any failure — callers handle the null
     * (cell stays at placeholder until a future load succeeds).
     */
    private Bitmap loadIconBlocking(AppInfo app) {
        if (app == null) return null;
        IconDiskCache dc = iconDiskCache;
        // Snapshot the current target pixel size up front. {@link #density}
        // is volatile so the read is coherent on every CPU; capturing
        // once also guarantees the disk-read, the IconRenderer.process
        // output dimensions, AND the disk-write key all agree even if
        // density changes mid-call (HDMI swap, multi-window resize) —
        // without the snapshot, an internal {@code dp(ICON_DP)}
        // re-read could produce a B-sized bitmap that we then store
        // under an A-keyed filename, causing a future read at A to
        // return a wrong-sized bitmap. We call
        // {@link IconRenderer#process} directly with the captured size
        // so every leg of this method shares one resolution.
        final int iconPx = dp(ICON_DP);
        if (dc != null) {
            Bitmap fromDisk = dc.tryRead(app.packageName, iconPx);
            if (fromDisk != null) return fromDisk;
        }
        Drawable d = resolveIconDrawable(app);
        Bitmap fresh = (d != null) ? IconRenderer.process(d, iconPx) : null;
        if (fresh != null && dc != null) dc.writeAsync(app.packageName, iconPx, fresh);
        return fresh;
    }

    /**
     * Resolve the launcher icon Drawable for an {@link AppInfo}.
     *
     * <p>Two paths:
     * <ol>
     *   <li>{@code app.ri != null} — the AppInfo came from a fresh
     *       {@link android.content.pm.PackageManager#queryIntentActivities}
     *       call. {@link android.content.pm.ResolveInfo#loadIcon} returns
     *       the activity's launcher drawable directly.</li>
     *   <li>{@code app.ri == null} — the AppInfo was reconstructed from
     *       the on-disk {@link AppListCache} (which cannot serialise
     *       ResolveInfo). Fall back to
     *       {@link android.content.pm.PackageManager#getActivityIcon(android.content.ComponentName)}
     *       which gives the same drawable via the activity's
     *       ComponentName — same binder cost, same result. Throws
     *       {@link android.content.pm.PackageManager.NameNotFoundException}
     *       when the package vanished between the cache write and the
     *       read; the next package broadcast invalidates the cache so
     *       the stale entry doesn't survive long.</li>
     * </ol>
     *
     * <p>Returns {@code null} on any exception — the icon-load callers
     * already handle null from the previous {@code ri.loadIcon} path
     * (placeholder cell stays until a future cache refresh succeeds).
     */
    private Drawable resolveIconDrawable(AppInfo app) {
        if (app == null) return null;
        // Request icons at 2× the device's actual screen density (the
        // standard "give the downscaler headroom" ratio — same idea as
        // 2x/3x web assets), capped at DENSITY_XXXHIGH (640 dpi).
        //
        // Why not just hardcode XXXHIGH for every device? On a TV box
        // that already reports a high density (xhdpi+, the common case
        // on modern TVs), 2× naturally saturates at the XXXHIGH cap, so
        // behaviour there is unchanged. But on the boxes that actually
        // have the bug — ones reporting a LOW density (e.g. 160 dpi =
        // mdpi), where ri.loadIcon(pm) fetches the mdpi asset and
        // upscales it to iconPx, looking blurry — unconditionally
        // requesting XXXHIGH forces decoding a ~640 dpi asset (e.g. a
        // 432×432 px adaptive-icon foreground layer) when the screen
        // only needed roughly 320 dpi worth of sharpness. That's a 4×
        // larger decode (and proportionally more transient ARGB_8888
        // memory) for zero additional visible benefit, multiplied across
        // every app on the shelf during the initial icon-load flood —
        // exactly the moment low-RAM devices are already under the most
        // memory pressure. Scaling the request to 2× what the device
        // actually needs fixes the same blurry-icon bug with a request
        // proportional to what will actually be visible.
        int deviceDensityDpi = Math.round(density * DisplayMetrics.DENSITY_DEFAULT);
        final int targetDensity = Math.min(deviceDensityDpi * 2, DisplayMetrics.DENSITY_XXXHIGH);
        if (app.ri != null) {
            // Fast path: ri.activityInfo already carries the icon resource ID
            // in memory. getResourcesForApplication() does cost a binder hop
            // into system_server the first time it's called for a given
            // package, but PackageManager caches the returned Resources
            // object internally, so every subsequent icon load for that
            // same package is just a HashMap lookup.
            ActivityInfo ai = app.ri.activityInfo;
            if (ai != null) {
                int iconRes = ai.getIconResource();
                if (iconRes != 0) {
                    try {
                        Resources res = pm.getResourcesForApplication(ai.packageName);
                        Drawable d = res.getDrawableForDensity(iconRes, targetDensity, null);
                        if (d != null) return d;
                    } catch (Exception ignored) { /* fall through */ }
                }
            }
            try {
                return app.ri.loadIcon(pm);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (app.component != null) {
            try {
                return pm.getActivityIcon(app.component);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    // ── Banner-tile pipeline (v1.5.0) ────────────────────────────────────
    // Mirrors the icon pipeline (preWarmIcon / loadIconAsync) but produces
    // the Apple-TV style banner tiles the home / drawer cells display, into
    // {@link #bannerCache} / {@link #bannerInflight}. Delivers to the same
    // {@link IconTarget} cells (their display bitmap is the banner). Does NOT
    // fire {@link #onIconLoaded} — that hook is for the round chip icons.

    /** Resolve the app's TV banner ({@code android:banner}) drawable, or
     *  {@code null} if it ships none (the common case for phone-style apps —
     *  those fall back to a generated tile). */
    private Drawable resolveBannerDrawable(AppInfo app) {
        if (app == null) return null;
        try {
            if (app.ri != null && app.ri.activityInfo != null) {
                Drawable b = app.ri.activityInfo.loadBanner(pm);
                if (b != null) return b;
            }
        } catch (RuntimeException ignored) { /* fall through */ }
        try {
            Drawable b = pm.getApplicationBanner(app.packageName);
            if (b != null) return b;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { /* none */ }
        return null;
    }

    /** Background-thread banner loader: real banner art (cover-fit) when the
     *  app provides one, else a generated tile (icon on a tinted rounded
     *  rect). Always returns a {@code BANNER_W × BANNER_H} bitmap (or null). */
    private Bitmap loadBannerBlocking(AppInfo app) {
        if (app == null) return null;
        final int w = tileWpx, h = bannerHpx, corner = tileCornerPx;
        Drawable banner = resolveBannerDrawable(app);
        if (banner != null) {
            Bitmap b = IconRenderer.processBannerArt(banner, w, h, corner);
            if (b != null) return b;
        }
        Drawable icon = resolveIconDrawable(app);
        return IconRenderer.generateBannerTile(icon, w, h, corner);
    }

    private void preWarmBanner(AppInfo app) {
        if (bannerCache == null) return;
        String key = app.packageName;
        if (bannerCache.get(key) != null || bannerInflight.containsKey(key)) return;
        List<IconTarget> waiters = new ArrayList<>(2);
        bannerInflight.put(key, waiters);
        try {
            iconExecutor.execute(() -> {
                if (destroyed) return;
                Bitmap bmp = null;
                try { bmp = loadBannerBlocking(app); if (bmp != null) bannerCache.put(key, bmp); }
                catch (OutOfMemoryError | RuntimeException ignored) {}
                if (destroyed) return;
                final Bitmap fb = bmp;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    List<IconTarget> pending = bannerInflight.remove(key);
                    if (pending != null && fb != null) {
                        for (int i = 0, n = pending.size(); i < n; i++) {
                            IconTarget cell = pending.get(i);
                            if (cell.iconTargetVisible() && key.equals(cell.iconTargetPackage()))
                                cell.setIconBitmap(fb);
                        }
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { bannerInflight.remove(key); }
    }

    private void loadBannerAsync(AppInfo app, IconTarget target) {
        if (bannerCache == null) return;
        String key = app.packageName;
        Bitmap cached = bannerCache.get(key);
        if (cached != null) { target.setIconBitmap(cached); return; }
        List<IconTarget> waiters = bannerInflight.get(key);
        if (waiters != null) {
            if (!waiters.contains(target)) waiters.add(target);
            return;
        }
        waiters = new ArrayList<>(2); waiters.add(target);
        bannerInflight.put(key, waiters);
        final List<IconTarget> fw = waiters;
        try {
            iconExecutor.execute(() -> {
                if (destroyed) return;
                Bitmap bmp = null;
                try { bmp = loadBannerBlocking(app); if (bmp != null) bannerCache.put(key, bmp); }
                catch (OutOfMemoryError | RuntimeException ignored) {}
                if (destroyed) return;
                final Bitmap fb = bmp;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    bannerInflight.remove(key);
                    if (fb == null) return;
                    for (IconTarget cell : fw) {
                        if (cell.iconTargetVisible() && key.equals(cell.iconTargetPackage()))
                            cell.setIconBitmap(fb);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { bannerInflight.remove(key); }
    }

    private void positionRing(View cell) {
        RingView rv = ringView; FrameLayout r = root;
        if (rv == null || r == null || !cell.isAttachedToWindow()) return;
        if (cell.getWidth() == 0) return;
        cell.getLocationOnScreen(ringCellLoc);
        // Root location is stable for the activity's lifetime on a TV
        // launcher — the activity window doesn't move until a
        // configuration change resets us. Cache the first read and
        // reuse it across every subsequent {@code positionRing} call.
        // Saves one full {@link View#getLocationOnScreen} walk
        // (~5 matrix multiplications + offset accumulations) per call.
        // {@link #onConfigurationChanged} clears the flag so the next
        // call refreshes against the new geometry.
        if (!rootLocCached) {
            r.getLocationOnScreen(ringRootLoc);
            rootLocCached = true;
        }

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
        rv.setX(cx - ringLayoutW / 2f); rv.setY(cy - ringLayoutH / 2f);
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
        // Re-detect system 12/24-hour preference on every resume.  The user
        // may have changed Settings → Date & time while away; we pick up the
        // new value here without requiring a ContentObserver.
        boolean detected = DateFormat.is24HourFormat(this);
        if (detected != is24Hour) {
            is24Hour = detected;
            clockFmt.reset(); // force repaint with new hour format
        }
        if (!showClock) {
            // Toggle is off: ensure the pill is hidden and no tick is
            // scheduled. tickClock would short-circuit anyway, but dropping
            // the postDelayed entirely means zero CPU per minute on installs
            // that opt out of the clock — the configured "0 cost when off"
            // contract from the v1.3.0 design discussion.
            clockRunning = false;
            uiHandler.removeCallbacks(clockTick);
            TextView cv = clockView;
            if (cv != null) cv.setVisibility(View.GONE);
            return;
        }
        TextView cv = clockView;
        if (cv != null) cv.setVisibility(View.VISIBLE);
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
        // Best-effort registration. Hardened TV ROMs (and rare cases after
        // a system_server restart) have been observed throwing
        // SecurityException out of registerReceiver even though the
        // launcher is the active home and the receiver is RECEIVER_NOT_EXPORTED.
        // Without this catch the throwable bubbles up through onCreate
        // and the activity dies before setContentView's view tree is
        // visible — which on TV ROMs flashes the system home picker.
        // Catching here lets the launcher come up; the user-visible
        // consequence is that package-add / -remove won't auto-refresh
        // the shelf until the next onResume (which retries the listener
        // wiring via the standard lifecycle).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(packageReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else
                registerReceiver(packageReceiver, f);
        } catch (SecurityException | IllegalStateException ignored) {
            // logged via CrashLogger if it fires anywhere above this frame
        }
    }

    private void unregisterPkgReceiver() {
        // Catch IllegalArgumentException for "receiver not registered"
        // (the normal case if registration above failed) AND catch
        // SecurityException for parity with the register path on the
        // same hardened ROMs that throw on the receive side.
        try { unregisterReceiver(packageReceiver); }
        catch (IllegalArgumentException | SecurityException ignored) {}
    }

    /** Register {@link #timeReceiver} for the system's clock-change
     *  broadcasts. Idempotent — the registered flag prevents double
     *  registration on rapid {@code onResume → onResume} paths some TV
     *  ROMs emit during fast configuration transitions (the same shape
     *  of bug the {@code globalFocusListener} dedupe defends against). */
    private void registerTimeReceiver() {
        if (timeReceiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        // No data scheme — these are broadcast as plain action intents.
        // RECEIVER_NOT_EXPORTED is appropriate on Tiramisu+ since these
        // are system-only broadcasts; locking the receiver keeps any
        // future third-party app from spoofing a fake clock change.
        //
        // Best-effort registration — same defensive pattern as
        // registerPkgReceiver. If the system refuses (very rare; some
        // hardened ROMs gate even system-only broadcasts on a SELinux
        // domain) the launcher still runs; the user-visible consequence
        // is a clock that lags a flight / DST transition by up to 60 s
        // (the natural one-minute tick still fires).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(timeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else
                registerReceiver(timeReceiver, f);
            timeReceiverRegistered = true;
        } catch (SecurityException | IllegalStateException ignored) {
            timeReceiverRegistered = false;
        }
    }

    private void unregisterTimeReceiver() {
        if (!timeReceiverRegistered) return;
        try { unregisterReceiver(timeReceiver); }
        catch (IllegalArgumentException | SecurityException ignored) {}
        timeReceiverRegistered = false;
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
        // Trigger the SharedPreferences async-load thread early so the
        // first synchronous {@code .getString()} / {@code .getInt()}
        // call later in onCreate (loadKeyMap, loadHiddenApps, the
        // KEY_SCROLL_IDX read in onResume) does not block on disk I/O.
        // SharedPreferencesImpl spawns a "SharedPreferencesImpl-load"
        // background thread inside its constructor; this single call
        // returns immediately, but the file-parse runs in parallel
        // with the slowest cold-start step ({@link #buildLayout()}).
        // By the time the first reader hits, the parsed Map is in
        // memory and the synchronous wait inside getString completes
        // in a single CountDownLatch await. Net effect: ~5-30 ms
        // less UI-thread blocking on slow ROMs at cold start, with
        // zero behavioural change (the load was always going to
        // happen — just now in parallel).
        //
        // Stash the returned handle as {@link #prefs} so every
        // {@code getString} / {@code edit} site reuses one instance —
        // see the field's javadoc for the cumulative-savings rationale.
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // {@link Context#getSystemService(String)} is documented to
        // return {@code null} when the named service does not exist.
        // {@code ACTIVITY_SERVICE} is a core Android service that
        // should always be present, but stripped TV firmware (some
        // Wear OS / IoT-derived ROMs that have ended up running on
        // cheap TV boxes) have been observed missing it. Without this
        // guard the launcher NPEs inside onCreate and dies on launch
        // — user gets a black home screen with no recovery short of
        // a factory reset. Fall back to a 64 MB heap-class default
        // so the LruCache cap math below still produces a sensible
        // value (~8 MB cache).
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int memMb   = (am != null) ? am.getMemoryClass() : 64;
        int cacheMb = Math.min(memMb / 8, 16);
        // The on-disk icon cache. Constructed BEFORE iconCache so the
        // icon-load executor tasks can read from / write to it. Owns
        // its own write executor; shut down in onDestroy().
        iconDiskCache = new IconDiskCache(this);
        // The in-memory icon cache. Pure LRU, no create() fallback —
        // the disk lookup is performed inside {@link #loadIconBlocking}
        // on the iconExecutor's worker thread. Earlier drafts wired the
        // disk cache as a {@code LruCache.create()} override; that path
        // ran synchronously on the calling thread, which on cold start
        // meant {@code preWarmIcon}'s early-return check
        // ({@code iconCache.get(key)}) blocked on a disk read for every
        // missing app — ~5 ms × 50 apps = 250 ms of UI-thread blocking
        // inside {@code setApps}. Doing the disk read in the executor
        // task keeps every UI-thread {@code iconCache.get} memory-only
        // (instant) and lets the disk reads run in parallel across the
        // pool's cores-1 workers.
        iconCache = new LruCache<String, Bitmap>(cacheMb * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        };
        // Banner-tile cache (v1.5.0). Tiles are larger than chip icons but
        // far fewer are alive at once (only on-screen home/drawer cells), so
        // a third of the icon budget is ample. In-memory only — no disk tier.
        bannerCache = new LruCache<String, Bitmap>(Math.max(2, cacheMb / 3) * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        };
        int cores = Runtime.getRuntime().availableProcessors();
        // Icon executor: {@code cores} worker threads handle the cold-start
        // icon flood (typically 50 apps × ~20 ms decode each ≈ 1 s of work
        // distributed across the pool). After the flood the queue stays
        // empty for the rest of the session — package broadcasts and
        // onTrimMemory are the only events that re-fire icon work, and
        // they're rare.
        //
        // 1.4.2 audit: pool size raised from {@code Math.max(2, cores - 1)}
        // to {@code Math.max(2, cores)}. The previous "cores − 1" tuning
        // assumed leaving a core free for the UI thread would smooth
        // cold-start frame paints. In practice Android's CFS scheduler
        // rotates threads at sub-millisecond granularity and the UI
        // thread's foreground priority class gets its fair share
        // regardless of how many CPU-bound workers are running. On a
        // 4-core TV the change lifts the cold-start icon-decode wall
        // clock from ~333 ms (3 workers) to ~250 ms (4 workers) — a
        // ~25 % reduction in time-to-fully-rendered-shelf, with no
        // measurable UI-thread regression in profiling. The
        // {@code Math.max(2, ...)} floor also avoids the latent
        // IllegalArgumentException that {@code cores - 1 = 0 < max = cores}
        // would have triggered on a hypothetical single-core device
        // (Android always reports ≥ 2 logical cores in practice, but
        // the contract is now defensively safe by construction).
        //
        // allowCoreThreadTimeOut(true) lets the core threads exit after
        // the 30 s keepAlive elapses. Without this they sit in WAITING
        // forever, holding ~0.5–1 MB of stack each and showing up in
        // StrictMode / heap dumps as live launcher state. The platform
        // re-creates them on the next executor.execute() call, so the
        // observable behaviour for the next icon flood is identical
        // (other than a one-time thread-creation cost in the µs range).
        //
        // Queue capacity: RecyclingShelfView.setApps() submits ONE
        // preWarmIcon() task per app, synchronously, in a tight loop —
        // every cold-start app count lands on this queue essentially at
        // once. A 128-deep bound was tight enough that TV boxes shipping
        // with large pre-installed app catalogues (150-300+ streaming
        // apps is common on cheap Android TV hardware) could overflow it.
        // DiscardOldestPolicy silently drops the OLDEST *queued* task to
        // make room for the new one — it does not throw, so the
        // RejectedExecutionException cleanup below never runs for the
        // discarded task, and that task's iconInflight entry is never
        // removed. The affected package's icon then stays stuck on the
        // placeholder for the rest of the session (nothing re-queues it
        // until a package broadcast for that exact app, or a
        // TRIM_MEMORY_COMPLETE clear, frees the orphaned entry). Bumped
        // to 1024 — comfortably above any realistic installed-app count
        // (a queued task is just a tiny Runnable closure, so the memory
        // cost of the higher bound is negligible and transient) — so the
        // discard path is no longer reachable in practice while still
        // keeping the queue bounded against a genuinely runaway producer.
        int iconWorkers = Math.max(2, cores);
        iconExecutor = new ThreadPoolExecutor(iconWorkers, iconWorkers, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1024), new ThreadPoolExecutor.DiscardOldestPolicy());
        iconExecutor.allowCoreThreadTimeOut(true);
        // Wallpaper executor is now owned by {@link WallpaperController}
        // (constructed later inside {@link #buildLayout()}). The activity
        // no longer manages the wallpaper-thread lifecycle directly.
        //
        // App-list executor: single thread, exists only to run the
        // PackageManager scan off the UI. The previous keepAlive of 0
        // meant the thread, once spawned, lived forever even though
        // it's idle for 99.99 % of the session. 30 s + core-thread-
        // timeout matches the icon executor's discipline.
        ThreadPoolExecutor app = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardPolicy());
        app.allowCoreThreadTimeOut(true);
        appExecutor = app;

        // Show-clock preference (v1.3.0). Cheap synchronous read after the
        // pre-warm above guarantees the parsed map is already in memory.
        // Default true so existing v1.2.x installs continue to render the
        // clock pill on first launch without any opt-in step. The "show
        // clock" toggle is a single bundled control: when true the formatter
        // renders day-of-week + time, when false the pill is hidden and no
        // minute tick is scheduled (zero CPU per minute).
        showClock = prefs.getBoolean(KEY_SHOW_CLOCK, true);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUI() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern WindowInsetsController path (API 30+, R). Hides system
            // bars, lets the user swipe them in transiently. The launcher
            // needs immersive sticky because the user spends most of their
            // foreground time on it; the bars would otherwise eat ~80 dp
            // of vertical space on phones, and on TV would draw a visible
            // overlay strip on top of the wallpaper during input events.
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Legacy SystemUiVisibility path (API 26-29). Same effect as
            // the modern branch above but routed through the deprecated
            // flag-based API. The flags are deprecated since API 30 but
            // the platform team kept the constants working forever for
            // exactly this shape of cross-version code — the
            // @SuppressWarnings("deprecation") on the method covers the
            // unavoidable build-time warning. android:windowFullscreen
            // in styles.xml hides the status bar via FLAG_FULLSCREEN
            // independently; the visibility flags here are what hide the
            // navigation bar AND keep both bars hidden when the user
            // touches the screen (IMMERSIVE_STICKY behaviour). The
            // LAYOUT_* flags pre-allocate the layout under the bars so
            // the launcher's content doesn't reflow when the bars
            // appear / disappear during transient swipes.
            View dv = w.getDecorView();
            dv.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    /** Compute and apply drop-down anchor margins on a card so it sits
     *  immediately below the gear toolbar pill, right-edges aligned. The
     *  settings panel and the keymap card both anchor the same way; this
     *  helper used to be inlined twice with identical logic, audited
     *  out in v1.3.3.
     *
     *  <p>Falls back to a sensible top/right pair when the gear pill
     *  hasn't been laid out yet (cold start, configuration change). The
     *  fallback positions the card at roughly the same place the gear
     *  would normally sit, so a card that's shown before measure passes
     *  finish (rare but possible) doesn't land off-screen.
     *
     *  <p>Allocations: zero. The two scratch arrays needed for
     *  {@code getLocationOnScreen} are held as instance fields
     *  ({@link #anchorMbLoc} / {@link #anchorRootLoc}) and reused
     *  across every invocation. Called only on the main thread (one
     *  call per overlay open).
     *
     *  @param card                  the card whose LayoutParams will be mutated
     *  @param defaultTopMarginPx    fallback top margin in px
     *  @param defaultRightMarginPx  fallback right margin in px */
    private void anchorCardUnderGear(View card,
                                     int defaultTopMarginPx,
                                     int defaultRightMarginPx) {
        int topMargin   = defaultTopMarginPx;
        int rightMargin = defaultRightMarginPx;
        View mb = mapperBtnView;
        FrameLayout r = root;
        if (mb != null && r != null && mb.getWidth() > 0) {
            int[] mbLoc = anchorMbLoc;
            int[] rLoc  = anchorRootLoc;
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
    }

    private int dp(int v) { return Math.round(v * density); }

    /** Compute the Apple-TV banner-tile pixel dimensions from the current
     *  screen width so exactly 6 tiles fit per row on any TV. Called at
     *  startup (before {@link #buildLayout}) and on configuration change.
     *  The tile is 5:3 (like tvOS 400x240); the corner is ~20% of the height
     *  (slightly more rounded per user request), capped so tiles don't get
     *  huge on very wide panels. */
    private void computeTileDims() {
        int sidePad = dp(10);
        int avail   = Math.max(0, screenW - dp(24) * 2);
        int stride  = avail > 0 ? avail / 6 : dp(150);
        int cw = stride - sidePad * 2;
        int capW = dp(156), minW = dp(64);
        if (cw > capW) cw = capW;
        if (cw < minW) cw = minW;
        tileWpx      = cw;
        bannerHpx    = Math.round(cw * 3f / 5f);        // 5:3
        tileCornerPx = Math.round(bannerHpx * 0.20f);   // slightly more rounded
        cellHpx      = bannerHpx + dp(28);              // banner + focused-label area
    }

    /** Clip a small list/chip {@link ImageView} to a circle so the shared
     *  (rounded-square) cached icon bitmap renders ROUND in the keymap slot
     *  rows, the app picker, and the hide-apps list — without touching the
     *  shared {@link #iconCache} (a pure view-level clip, no extra bitmap).
     *  Used so the chip icons stay the familiar small round app icons while
     *  the home / drawer tiles use their own larger artwork. */
    private static void clipCircular(View v) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                int w = view.getWidth(), h = view.getHeight();
                int d = Math.min(w, h);
                int l = (w - d) / 2, t = (h - d) / 2;
                outline.setOval(l, t, l + d, t + d);
            }
        });
        v.setClipToOutline(true);
    }

    /** Per-row indicator view for the keymap card slot list. Renders one
     *  of three glyphs ({@link #GLYPH_DOT} colour disc, {@link
     *  #GLYPH_HAMBURGER} 3-line menu glyph, {@link #GLYPH_CC} closed-
     *  captions badge) at a tiny dp(11) container size, centred via
     *  cached {@link Rect} bounds for the text variant. The {@code
     *  selectedState} field flips the hamburger / CC colour from warm
     *  white to near-black when the row is selected so the glyphs stay
     *  visible against the bright frosted-white selection pill — the
     *  v1.3.2 issue where they invisibly blended into the white pill.
     *  Colour discs (the four colour-key rows) keep their saturated
     *  colour in both states because red / green / yellow / blue are
     *  visible on white anyway. v1.3.3 introduction. */
    private final class ShortcutTagView extends View {
        final int kind;
        final int color;
        private boolean selectedState = false;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect ccBounds = new Rect();

        ShortcutTagView(int kind, int color) {
            super(LauncherActivity.this);
            this.kind = kind;
            this.color = color;
        }

        /** Update the selection state and request a redraw if it
         *  actually changed. Cheap no-op when the row's selection state
         *  hasn't moved (refreshKeymapRows fires on every UP/DOWN press
         *  but the state delta is one row in / one row out — most rows
         *  are stable). */
        void setSelectedState(boolean s) {
            if (s != selectedState) {
                selectedState = s;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas c) {
            drawShortcutGlyph(c, getWidth(), getHeight(), kind, color,
                    selectedState, paint, ccBounds);
        }
    }

    /** Draw a single keymap-card row indicator. Three rendering modes
     *  packed behind a {@code kind} switch so all six rows share one
     *  allocation-free {@link Paint} owned by the calling
     *  {@link ShortcutTagView}.
     *
     *  <ul>
     *    <li>{@link #GLYPH_DOT}: solid colour disc (~64 % of the
     *        container's half-width). Stays its saturated colour in
     *        both idle and selected states — visible on either backdrop.</li>
     *    <li>{@link #GLYPH_HAMBURGER}: three short horizontal lines,
     *        symmetric within ~64 % of the container so the visual
     *        footprint matches the colour disc. Idle warm white,
     *        selected near-black.</li>
     *    <li>{@link #GLYPH_CC}: bold "CC" text, centred via cached
     *        {@link Rect} text bounds for accurate visual alignment.
     *        Same idle / selected colour rules as the hamburger.</li>
     *  </ul>
     *
     *  The {@link Rect} parameter is owned by the calling View
     *  (per-instance) and reused for {@code Paint.getTextBounds} on
     *  the CC variant — getTextBounds allocates internally if no Rect
     *  is supplied, so passing a cached one keeps the draw call
     *  zero-alloc per frame. */
    // Resolved once per process instead of inside drawShortcutGlyph's
    // GLYPH_CC branch — Typeface.create(String, int) is not guaranteed
    // to be a cache hit on every Android version, so re-resolving it on
    // every redraw of the CC row (every UP/DOWN that crosses it while
    // the keymap overlay is open) was a small avoidable allocation on
    // an otherwise zero-alloc draw path.
    private static final Typeface CC_GLYPH_TYPEFACE =
            Typeface.create("sans-serif-condensed", Typeface.BOLD);

    private static void drawShortcutGlyph(Canvas c, int w, int h,
                                          int kind, int color, boolean selected,
                                          Paint p, Rect ccBounds) {
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f, cy = h / 2f;
        // Glyph colour for the monochrome variants: idle warm white
        // (matches row label idle colour), selected near-black (matches
        // row label selected colour). Colour discs keep their full
        // colour regardless — saturated brand colours read on either
        // backdrop, and inverting them would be a different visual
        // language (the dots would lose their identity).
        final int glyphColor = selected ? 0xFF111114 : 0xCCFFFFFF;

        if (kind == GLYPH_DOT) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            // 0.64 fraction of the half-width keeps the visible disc
            // at ~dp(7) inside the dp(11) container, matching the
            // pre-v1.3.2 dot diameter.
            c.drawCircle(cx, cy, Math.min(cx, cy) * 0.64f, p);
        } else if (kind == GLYPH_HAMBURGER) {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(glyphColor);
            // Stroke + line geometry tuned so the hamburger occupies
            // ~64 % of the container (matching the dot's visual
            // footprint per the user's "small as symmetric to other
            // colour icon" feedback).
            p.setStrokeWidth(Math.max(1f, w * 0.13f));
            p.setStrokeCap(Paint.Cap.ROUND);
            float inset   = w * 0.18f;            // 64 % horizontal span
            float spacing = h * 0.22f;            // ~44 % vertical span
            c.drawLine(inset, cy - spacing, w - inset, cy - spacing, p);
            c.drawLine(inset, cy           , w - inset, cy           , p);
            c.drawLine(inset, cy + spacing, w - inset, cy + spacing, p);
        } else { // GLYPH_CC
            p.setStyle(Paint.Style.FILL);
            p.setColor(glyphColor);
            p.setTypeface(CC_GLYPH_TYPEFACE);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(h * 0.62f);
            // Vertically centre via the actual rendered glyph bounds
            // (Paint.FontMetrics centres on the typographic median,
            // which sits below the visual median of "CC" — the user
            // reported "not aligned in centre" against this rendering).
            // getTextBounds writes into the caller-owned Rect, so this
            // is allocation-free per draw.
            p.getTextBounds("CC", 0, 2, ccBounds);
            float baseline = cy + ccBounds.height() / 2f - ccBounds.bottom;
            c.drawText("CC", cx, baseline, p);
        }
    }

    private void showToast(String msg) {
        if (currentToast != null) currentToast.cancel();
        currentToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        currentToast.show();
    }
}
