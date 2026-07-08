package com.bare.launcher;

import android.content.ComponentName;
import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Cold-start cache for the launcher's app shelf.
 *
 * <p>The launcher's first-paint critical path used to be: query
 * {@link android.content.pm.PackageManager#queryIntentActivities} twice
 * (one binder call each), then call {@link android.content.pm.ResolveInfo#loadLabel}
 * on every result (one binder call per app), then sort by label, then
 * hand to the shelf. On a stripped-down TV ROM with 30-50 apps installed,
 * this can take 200-500 ms before the shelf has anything to render.
 *
 * <p>{@link AppListCache} persists the shelf-relevant fields of every
 * known app to a small text file in {@link Context#getFilesDir()} so
 * the activity's next cold start can paint the shelf instantly from
 * disk while the slow PM scan continues in the background. When the
 * scan completes, the activity reconciles: identical results → no
 * re-render, package set differs → full re-render with the fresh data.
 *
 * <h3>What is and isn't cached</h3>
 * Cached: package name, user-visible label, activity class name. These
 * are sufficient to:
 * <ul>
 *   <li>Render a {@code CellView} (label + icon — icon comes from the
 *       sibling {@link IconDiskCache}).</li>
 *   <li>Launch the app via the direct-intent fast path (uses
 *       {@code ComponentName} = packageName/activityClass).</li>
 *   <li>Look up by {@code findAppByPackage} for keymap routing.</li>
 * </ul>
 * NOT cached: {@link android.content.pm.ResolveInfo}. It's not
 * serializable, and the consumers that need it ({@code preWarmIcon},
 * {@code loadIconAsync}) fall back to
 * {@link android.content.pm.PackageManager#getActivityIcon} when ri
 * is null — same binder cost as {@code ri.loadIcon}, no observable
 * difference for the user.
 *
 * <h3>File format</h3>
 * Plain text, UTF-8, line-based:
 * <pre>
 *   BLAC                     ← magic ("BareLauncher AppList Cache")
 *   1                        ← format version (single byte)
 *   {N}                      ← entry count
 *   {pkg_1}\n{label_1}\n{cls_1}
 *   {pkg_2}\n{label_2}\n{cls_2}
 *   ...
 *   {pkg_N}\n{label_N}\n{cls_N}
 * </pre>
 * Labels containing {@code \n} or {@code \r} are sanitised on write
 * (the offending characters become spaces); the parser does not need
 * any escape handling. Real-world app labels never contain newlines —
 * the sanitisation is purely defensive against malformed manifests.
 *
 * <h3>Atomic writes</h3>
 * Like {@link WallpaperController}'s snapshot, writes go to a
 * {@code .tmp} sibling and rename-publish on success. A process kill
 * mid-write leaves the previous-known-good cache intact instead of a
 * truncated file the parser would reject on next cold start.
 *
 * <h3>JVM testability</h3>
 * The pure parse / serialise core is exposed as static methods that
 * accept {@code String} / {@link Iterable} and an
 * {@link Entry} interface — no Android types. Android file I/O lives
 * behind {@link #readFile} / {@link #writeFile} which the activity
 * calls. JVM unit tests exercise the core directly (see
 * {@code AppListCacheTest}).
 */
final class AppListCache {

    private AppListCache() { /* no instances */ }

    /** On-disk file basename. Lives in {@link Context#getFilesDir()}
     *  alongside {@code wallpaper.snap} — both private app storage,
     *  both survive across reboots. */
    static final String FILE_NAME = "applist.cache";

    /** Temp-file basename for atomic publish via rename. */
    static final String TMP_NAME  = "applist.cache.tmp";

    /** Magic header. Lets {@link #parse} bail early on a file from a
     *  different writer (rare but possible if a future build switched
     *  formats and the user downgraded). The four bytes also act as a
     *  truncation detector — anything short of 4 bytes can't possibly
     *  be a valid cache. */
    static final String MAGIC   = "BLAC";

    /** Format version. Bumped only when the on-disk shape changes in a
     *  way the current parser cannot transparently consume. A version
     *  bump invalidates every existing cache file (parser returns
     *  false; activity falls through to PM scan). */
    static final int    VERSION = 1;

    /** Soft cap on parsed entry count. Defends against a corrupt count
     *  line (e.g. parsed as MAX_INT) trying to allocate a giant list.
     *  10 000 apps is well above any realistic install. */
    private static final int MAX_ENTRIES = 10_000;

    /** Same synthetic-packageName prefix {@link AppInfo#tvInput} uses.
     *  Duplicated here (rather than exposing AppInfo's private literal)
     *  since {@link #toAppInfo} needs it purely as a string prefix check —
     *  no coupling to AppInfo's internals beyond the format both already
     *  agree on. */
    private static final String TVINPUT_PKG_PREFIX = "tvinput://";

    /** Placeholder written to the on-disk "activity class" field for a
     *  TV-input entry, which has no real activity class. Any non-empty
     *  string works — {@link #parse}/{@link #serialize} only require the
     *  field to be non-empty, and {@link #toAppInfo} never reads it back
     *  for a tvinput:// entry (it branches to {@link AppInfo#tvInput}
     *  before the class field is used). Chosen to be self-documenting in
     *  a hex dump / manual inspection of the cache file. */
    private static final String TVINPUT_CLASS_PLACEHOLDER = "tvinput";

    // ── Pure-Java core (JVM testable) ─────────────────────────────────────

    /** Read-only view of one cache entry. The shipped {@link AppInfo}
     *  satisfies this contract via {@link #from(AppInfo)} below. */
    interface Entry {
        String pkg();
        String label();
        String activityClass();
    }

    /** Visitor invoked once per parsed entry. Implementations typically
     *  forward to a list-add or a SparseArray put — the activity binds
     *  this to its {@code AppInfo}-constructing helper. */
    interface Visitor { void accept(String pkg, String label, String activityClass); }

    /**
     * Parse the on-disk cache contents. Tolerates trailing whitespace,
     * Windows line endings, and missing trailing newline. Returns
     * {@code true} if at least one entry was emitted; {@code false}
     * on any structural problem (bad magic, bad version, truncated
     * entries, count mismatch). The visitor is NOT called on the
     * false return — partial state is suppressed.
     *
     * <p>The implementation is allocation-light: a single
     * {@link BufferedReader}-style line walk, no regex, no split.
     * Suitable for synchronous UI-thread invocation from
     * {@code onCreate} (the file is small and the parse is O(lines)).
     *
     * @param contents the entire UTF-8 text content of the cache file.
     * @param visitor  callback for each accepted entry.
     * @return         {@code true} on success, {@code false} on any
     *                 structural rejection (visitor not invoked).
     */
    static boolean parse(String contents, Visitor visitor) {
        if (contents == null || contents.length() < MAGIC.length()) return false;
        // Two-pass via a temporary list so we can validate before
        // calling the visitor — the contract is "all-or-nothing on
        // success, never deliver a partial cache".
        ArrayList<String[]> staged = new ArrayList<>();
        int n = contents.length();
        int pos = 0;
        // Helper: read one line, return null on truncation.
        // Inline rather than a separate method to keep hot-path call
        // count low and avoid a lambda allocation.
        // Magic
        int eol = indexOfLineEnd(contents, pos, n);
        if (eol < 0) return false;
        String magic = stripCR(contents, pos, eol);
        if (!MAGIC.equals(magic)) return false;
        pos = eol + 1;
        // Version
        eol = indexOfLineEnd(contents, pos, n);
        if (eol < 0) return false;
        int version;
        try {
            version = Integer.parseInt(stripCR(contents, pos, eol));
        } catch (NumberFormatException e) { return false; }
        if (version != VERSION) return false;
        pos = eol + 1;
        // Count
        eol = indexOfLineEnd(contents, pos, n);
        if (eol < 0) return false;
        int count;
        try {
            count = Integer.parseInt(stripCR(contents, pos, eol));
        } catch (NumberFormatException e) { return false; }
        if (count < 0 || count > MAX_ENTRIES) return false;
        pos = eol + 1;
        // Body — three lines per entry
        for (int i = 0; i < count; i++) {
            int eolPkg = indexOfLineEnd(contents, pos, n);
            if (eolPkg < 0) return false;
            String pkg = stripCR(contents, pos, eolPkg);
            pos = eolPkg + 1;

            int eolLbl = indexOfLineEnd(contents, pos, n);
            if (eolLbl < 0) return false;
            String label = stripCR(contents, pos, eolLbl);
            pos = eolLbl + 1;

            int eolCls = indexOfLineEnd(contents, pos, n);
            if (eolCls < 0) return false;
            String cls = stripCR(contents, pos, eolCls);
            pos = eolCls + 1;

            // Package and class are required; label is allowed to be
            // empty (very rare, but addApps's loadLabel fallback can
            // produce one — drop the entry rather than render a
            // labelless cell that the user can't identify).
            if (pkg.isEmpty() || cls.isEmpty() || label.isEmpty()) return false;
            staged.add(new String[]{pkg, label, cls});
        }
        // Validation passed — emit.
        for (int i = 0, m = staged.size(); i < m; i++) {
            String[] e = staged.get(i);
            visitor.accept(e[0], e[1], e[2]);
        }
        return !staged.isEmpty();
    }

    /**
     * Serialise the given entries to the cache string. Sanitises labels
     * by replacing any {@code \r} or {@code \n} with a single space —
     * the format is line-based and a literal newline in a label would
     * desynchronise the parser. Real labels never contain newlines;
     * the sanitisation defends against malformed manifests only.
     *
     * <p>{@code Iterable} signature so the activity can pass its
     * {@code List<AppInfo>} directly without an intermediate copy.
     *
     * @param entries one entry per shelf app, in display order.
     * @return        the serialised cache string, or an empty string
     *                if {@code entries} is null or contains no
     *                emit-able items.
     */
    static String serialize(Iterable<? extends Entry> entries) {
        if (entries == null) return "";
        // Two-pass: first collect valid entries (skip null / empty
        // pkg / empty cls), then emit. Without the pre-count we can't
        // write a correct count line.
        ArrayList<Entry> valid = new ArrayList<>();
        for (Entry e : entries) {
            if (e == null) continue;
            String pkg = e.pkg();
            String cls = e.activityClass();
            String lbl = e.label();
            if (pkg == null || pkg.isEmpty()) continue;
            if (cls == null || cls.isEmpty()) continue;
            if (lbl == null || lbl.isEmpty()) continue;
            valid.add(e);
        }
        if (valid.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(64 + valid.size() * 64);
        sb.append(MAGIC).append('\n');
        sb.append(VERSION).append('\n');
        sb.append(valid.size()).append('\n');
        for (int i = 0, m = valid.size(); i < m; i++) {
            Entry e = valid.get(i);
            sb.append(e.pkg()).append('\n');
            sb.append(sanitiseLine(e.label())).append('\n');
            sb.append(e.activityClass()).append('\n');
        }
        return sb.toString();
    }

    /** Returns the index of the next {@code '\n'} in [{@code from}, {@code to}),
     *  or -1 if none. Inlines the search so we don't allocate a String
     *  per line via {@code split}. */
    private static int indexOfLineEnd(String s, int from, int to) {
        for (int i = from; i < to; i++) if (s.charAt(i) == '\n') return i;
        return -1;
    }

    /** Substring [{@code from}, {@code to}) with a trailing {@code '\r'}
     *  trimmed if present (Windows line endings). */
    private static String stripCR(String s, int from, int to) {
        if (to > from && s.charAt(to - 1) == '\r') to--;
        return s.substring(from, to);
    }

    /** Replace {@code \r} and {@code \n} in a label with a space. The
     *  parser is line-based; a literal newline in a label would split
     *  the entry across multiple file lines and resynchronise the
     *  parser on the wrong content. */
    static String sanitiseLine(String s) {
        if (s == null) return "";
        // Fast-path: no offending characters → return original.
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                // Slow path — rebuild with replacements.
                StringBuilder sb = new StringBuilder(n);
                sb.append(s, 0, i);
                for (int j = i; j < n; j++) {
                    char cj = s.charAt(j);
                    sb.append((cj == '\n' || cj == '\r') ? ' ' : cj);
                }
                return sb.toString();
            }
        }
        return s;
    }

    /** Adapter from {@link AppInfo} to the {@link Entry} interface used
     *  by {@link #serialize}. The activity's app list is
     *  {@code List<AppInfo>}; this lets it round-trip via the same
     *  serialisation path the JVM tests exercise. */
    static Entry from(final AppInfo a) {
        if (a == null) return null;
        // TV-input entries used to be skipped here — "re-enumerated fresh
        // from the TV Input Framework on every loadApps scan" — on the
        // theory that KEY_APP_ORDER alone would put them back in place.
        // In practice that meant the input's shelf slot didn't exist for
        // the instant cold-start paint (which reads only this cache), so
        // the slot had to be spliced in a moment later once the background
        // PM scan's TvInputs.enumerate() call completed — visible on every
        // restart as the input tile popping into its home-row position and
        // shoving/overlapping the app next to it. Persisting the input here
        // too (its synthetic "tvinput://" packageName is already a stable,
        // self-sufficient key — see AppInfo.tvInput) lets it render in its
        // correct slot on the very first frame, same as any real app. The
        // 3-line format requires a non-empty class field; a TV input has no
        // real activity class, so TVINPUT_CLASS_PLACEHOLDER stands in — it
        // is never read back as a real ComponentName (see toAppInfo).
        final String cls = a.tvInputId != null
                ? TVINPUT_CLASS_PLACEHOLDER
                : (a.component != null ? a.component.getClassName() : null);
        return new Entry() {
            @Override public String pkg()           { return a.packageName; }
            @Override public String label()         { return a.label; }
            @Override public String activityClass() { return cls; }
        };
    }

    // ── Android file I/O wrappers ─────────────────────────────────────────

    /**
     * Read the cache file synchronously and forward each entry to
     * {@code visitor}. Returns {@code true} if at least one entry was
     * emitted (caller can use this signal to render the shelf
     * immediately and skip waiting for the PM scan).
     *
     * <p>Synchronous and on the calling thread — the file is small
     * (~5-10 KB even for 50 apps) and the parse runs in a few ms. Safe
     * to call from {@code onCreate} on the UI thread; that's the
     * point.
     */
    static boolean readFile(Context ctx, Visitor visitor) {
        if (ctx == null || visitor == null) return false;
        File dir = ctx.getFilesDir();
        if (dir == null) return false;
        File f = new File(dir, FILE_NAME);
        if (!f.exists() || f.length() == 0) return false;
        // Single-shot read into a byte array, then UTF-8 decode. Faster
        // than the BufferedReader + char[] + StringBuilder loop the
        // pre-1.4.2 implementation used: one syscall + one alloc instead
        // of N reads each appending into an incrementally growing
        // StringBuilder. Files.readAllBytes is API 26+ (our minSdk
        // floor) so no compatibility shim. The cache file is tiny
        // (~5–10 KB for 50 apps) so memory peak is bounded by 2× the
        // file size during the byte-array → String decode.
        String contents;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            contents = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | OutOfMemoryError ignored) {
            return false;
        }
        boolean ok = parse(contents, visitor);
        if (!ok) {
            // The cache is corrupt or version-mismatched. Delete it so
            // the next write doesn't need to compete with stale bytes.
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        return ok;
    }

    /**
     * Write the given app entries to the cache file atomically.
     * Synchronous on the calling thread — the activity invokes this
     * from its {@code appExecutor} (background) right after a
     * reconciled PM scan, so the UI thread is never blocked on cache
     * writes.
     *
     * <p>Atomic publish: write to {@code applist.cache.tmp}, rename to
     * {@code applist.cache}. Failure paths leave the previous-known-
     * good cache intact (or no cache, if this is the first write).
     */
    static void writeFile(Context ctx, Iterable<? extends Entry> entries) {
        if (ctx == null) return;
        File dir = ctx.getFilesDir();
        if (dir == null) return;
        String text = serialize(entries);
        if (text.isEmpty()) {
            // No valid entries — purge any existing cache so a stale
            // file doesn't outlive an empty install.
            File existing = new File(dir, FILE_NAME);
            //noinspection ResultOfMethodCallIgnored
            existing.delete();
            return;
        }
        File tmp  = new File(dir, TMP_NAME);
        File dest = new File(dir, FILE_NAME);
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            w.write(text);
            w.flush();
        } catch (IOException ignored) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Convenience overload accepting the activity's
     *  {@code List<AppInfo>} directly. Wraps each AppInfo via
     *  {@link #from} so the serialiser sees the {@link Entry} adapter. */
    static void writeFileFromAppInfo(Context ctx, List<AppInfo> apps) {
        if (apps == null || apps.isEmpty()) {
            writeFile(ctx, null);
            return;
        }
        ArrayList<Entry> wrapped = new ArrayList<>(apps.size());
        for (int i = 0, n = apps.size(); i < n; i++) {
            Entry e = from(apps.get(i));
            if (e != null) wrapped.add(e);
        }
        writeFile(ctx, wrapped);
    }

    /**
     * Construct an {@link AppInfo} from a cached entry. The
     * {@link android.content.pm.ResolveInfo} field is left {@code null}
     * because ResolveInfo is not serialisable — consumers that need
     * it (the icon-load path) fall back to
     * {@link android.content.pm.PackageManager#getActivityIcon} for
     * {@code null}-ri AppInfo instances.
     */
    static AppInfo toAppInfo(String pkg, String label, String activityClass) {
        // Mirror of the tvinput:// packageName convention AppInfo.tvInput()
        // establishes — a cached TV-input entry round-trips back through
        // that factory instead of the real-app path below, which would
        // otherwise build a nonsense ComponentName from the placeholder
        // class field written in from().
        if (pkg != null && pkg.startsWith(TVINPUT_PKG_PREFIX)) {
            return AppInfo.tvInput(pkg.substring(TVINPUT_PKG_PREFIX.length()), label);
        }
        ComponentName cn = new ComponentName(pkg, activityClass);
        return new AppInfo(pkg, label, cn, null);
    }

    /** Delete the cache file (e.g. after the user clears app data
     *  externally). Best-effort; absence of the file after this call
     *  is not guaranteed if the FS is in an unusual state. */
    static void delete(Context ctx) {
        if (ctx == null) return;
        File dir = ctx.getFilesDir();
        if (dir == null) return;
        //noinspection ResultOfMethodCallIgnored
        new File(dir, FILE_NAME).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, TMP_NAME).delete();
    }
}
