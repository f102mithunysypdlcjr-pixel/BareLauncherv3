package com.bare.launcher;

/**
 * Pure-Java parse / serialize helpers for the launcher's two persisted
 * sets stored in {@code SharedPreferences}:
 *
 * <ol>
 *   <li><b>Remote-key shortcut map</b> — {@code "kc=pkg,kc=pkg,..."}.
 *       Keys are raw Android keycodes ({@code KEYCODE_PROG_RED} = 183
 *       etc.); values are launcher-able package names. Bindings whose
 *       keycode is no longer in the curated allow-list are dropped on
 *       parse so removed slots auto-clean across versions.</li>
 *   <li><b>Hidden apps</b> — {@code "pkg,pkg,..."} comma-separated set
 *       of packages the user has hidden from the home shelf.</li>
 * </ol>
 *
 * <p>Both formats:
 * <ul>
 *   <li>Tolerate empty / null input → empty result.</li>
 *   <li>Skip blank tokens silently (trailing commas, double commas).</li>
 *   <li>Are forward-compatible: a future version could append extra
 *       fields after the package name and a current build still parses
 *       the leading {@code keycode=package} (the parser stops at the
 *       first comma after the value).</li>
 * </ul>
 *
 * <h3>Why visitor / array signatures?</h3>
 * The activity stores its in-memory state in {@link android.util.SparseArray}
 * and {@link android.util.ArraySet} (zero-autoboxing for the hot keypress
 * lookup path). Those types are Android-framework only — they cannot be
 * used in JVM unit tests without Robolectric, which the project
 * intentionally avoids ("zero external dependencies in the production APK").
 *
 * <p>By exposing parsing through a {@link KeymapVisitor} callback and
 * accepting a {@link Consumer Consumer&lt;String&gt;} for hidden apps, this
 * helper is fully JVM-testable. Serialisation accepts plain {@code int[]}
 * / {@code String[]} pairs and {@link Iterable Iterable&lt;String&gt;} for
 * the same reason. The conversion cost in the activity is two arrays per
 * keymap save (rare; only when the user changes a binding) — never on a
 * hot path.
 *
 * <p>This class is package-private and final because no consumer outside
 * the launcher needs it. It used to live as four private methods inside
 * {@code LauncherActivity} ({@code loadKeyMap} / {@code saveKeyMap} /
 * {@code loadHiddenApps} / {@code saveHiddenApps}); pulling the pure
 * string handling out drops ~80 lines from the activity and lets the
 * round-trip be exercised by fast JVM tests (see {@code KeymapStoreTest}).
 */
final class KeymapStore {

    private KeymapStore() { /* no instances */ }

    /**
     * Callback invoked by {@link #parseKeyMap} for every accepted
     * {@code keycode → package} entry. Implementations typically just
     * forward to {@code SparseArray.put(int, String)} via a method
     * reference: {@code keyMap::put}.
     */
    interface KeymapVisitor { void put(int keycode, String pkg); }

    /**
     * Single-method consumer of strings. Wider {@code java.util.function}
     * interfaces are not used so this class compiles against the lowest
     * Android API levels without bringing in the {@code java.util.function}
     * dependency on every call site (it is available, but having a tiny
     * domain-specific interface keeps the testable surface explicit).
     */
    interface Consumer<T> { void accept(T value); }

    /**
     * Parse the persisted keymap string and forward accepted entries to
     * {@code visitor}. Bindings whose keycode is not in
     * {@code curatedKeycodes} are silently dropped — that's how removed
     * shortcut slots (Guide / Search in older builds) auto-clean on
     * cold start.
     *
     * @param raw                stored string; may be {@code null} or empty.
     * @param curatedKeycodes    keycodes the launcher accepts as shortcut
     *                           slots in the current build.
     * @param visitor            invoked once per accepted entry.
     * @return {@code true} if at least one entry was dropped (caller
     *         should re-save so prefs converge to the new shape).
     */
    static boolean parseKeyMap(String raw, int[] curatedKeycodes, KeymapVisitor visitor) {
        if (raw == null || raw.isEmpty()) return false;
        boolean dropped = false;
        int n = raw.length(), start = 0;
        while (start < n) {
            int comma = raw.indexOf(',', start);
            int end   = comma < 0 ? n : comma;
            int eq    = raw.indexOf('=', start);
            if (eq > start && eq < end - 1) {
                try {
                    // Use String.substring + Integer.parseInt(String). The
                    // CharSequence overload Integer.parseInt(CharSequence,
                    // int, int, int) was only added in API 33 — minSdk=30
                    // would crash with NoSuchMethodError on Android 11/12.
                    // The substring allocation runs at most ~6 times per
                    // launcher cold-start (one per stored shortcut), so
                    // the cost is negligible against the safety win.
                    int kc = Integer.parseInt(raw.substring(start, eq));
                    String pkg = raw.substring(eq + 1, end);
                    if (!pkg.isEmpty()) {
                        if (isCurated(kc, curatedKeycodes)) visitor.put(kc, pkg);
                        else                                dropped = true;
                    }
                } catch (NumberFormatException ignored) {
                    dropped = true;
                }
            } else if (end > start) {
                // Non-empty token without '=' is a corrupt entry — drop it
                // so the caller's re-save converges to a clean shape.
                dropped = true;
            }
            start = end + 1;
        }
        return dropped;
    }

    /**
     * Serialize parallel {@code keycodes} / {@code packages} arrays into
     * the persisted {@code "kc=pkg,kc=pkg"} string.
     *
     * <p>Both arrays must be the same length. {@code packages[i]} entries
     * that are {@code null} or empty are skipped (the underlying
     * {@code SparseArray} is never expected to hold those, but the
     * defensive skip keeps the on-disk format clean if a future caller
     * relaxes that contract).
     */
    static String serializeKeyMap(int[] keycodes, String[] packages) {
        if (keycodes == null || packages == null) return "";
        if (keycodes.length != packages.length) {
            throw new IllegalArgumentException(
                    "keycodes/packages length mismatch: "
                            + keycodes.length + " vs " + packages.length);
        }
        if (keycodes.length == 0) return "";
        StringBuilder sb = new StringBuilder(keycodes.length * 12);
        for (int i = 0, n = keycodes.length; i < n; i++) {
            String pkg = packages[i];
            if (pkg == null || pkg.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(keycodes[i]).append('=').append(pkg);
        }
        return sb.toString();
    }

    /**
     * Parse the persisted hidden-apps string and forward each non-empty
     * package name to {@code sink}. The set is cleared by the caller, not
     * here — keeps this helper allocation-free.
     */
    static void parseHiddenApps(String raw, Consumer<String> sink) {
        if (raw == null || raw.isEmpty()) return;
        int n = raw.length(), start = 0;
        while (start < n) {
            int comma = raw.indexOf(',', start);
            int end   = comma < 0 ? n : comma;
            if (end > start) sink.accept(raw.substring(start, end));
            start = end + 1;
        }
    }

    /**
     * Serialize an iterable of package names into the persisted
     * comma-separated string. Null / empty entries are skipped. The
     * launcher only ever does {@code contains()} checks against the
     * parsed set so the iteration order does not matter for correctness.
     */
    static String serializeHiddenApps(Iterable<String> packages) {
        if (packages == null) return "";
        StringBuilder sb = new StringBuilder(64);
        for (String p : packages) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        return sb.toString();
    }

    /** Is {@code kc} present in the curated keycodes array? Linear scan
     *  on a tiny (~6-element) array is fastest in practice. Public
     *  because the activity also needs the same predicate when validating
     *  inbound key events. */
    static boolean isCurated(int kc, int[] curatedKeycodes) {
        if (curatedKeycodes == null) return false;
        for (int k : curatedKeycodes) if (k == kc) return true;
        return false;
    }
}
