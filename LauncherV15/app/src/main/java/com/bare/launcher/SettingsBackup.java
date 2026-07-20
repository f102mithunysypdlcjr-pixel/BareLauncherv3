package com.bare.launcher;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure-Java serialize / parse for the launcher's settings backup file.
 *
 * <p>The backup is a tiny, human-readable, line-based text file:
 * <pre>
 *   BLBK\t1
 *   app_order\tcom.a,com.b,com.c
 *   home_count\t6
 *   key_map\t183=com.a,184=com.b
 *   hidden_apps\tcom.x,com.y
 *   clock_mode\t0
 * </pre>
 * Line 1 is a magic + format-version header; every following line is a
 * {@code key\tvalue} pair. The wallpaper is deliberately NOT included — it is
 * stored as a device-scoped {@code content://} permission grant that cannot
 * be transferred to another install.
 *
 * <p>Design goals mirror the rest of the launcher's persisted formats
 * ({@link AppOrder}, {@link KeymapStore}, {@code AppListCache}):
 * <ul>
 *   <li><b>Atomic-friendly:</b> {@link #parse} validates the header up front
 *       and returns {@code null} for anything that is not a recognised
 *       backup, so the caller can refuse to apply a single byte of a bad
 *       file (no half-applied restore).</li>
 *   <li><b>Forward-compatible:</b> unknown keys are ignored on parse, and a
 *       newer major version is rejected rather than mis-read.</li>
 *   <li><b>Robust values:</b> the values we store (comma-separated package
 *       lists, {@code kc=pkg} maps, integers) never contain a TAB or newline;
 *       {@link #serialize} defensively strips any anyway so the line format
 *       can never be corrupted.</li>
 *   <li><b>Android-free:</b> exercised by fast JVM unit tests; the activity
 *       only does the SAF file I/O around it.</li>
 * </ul>
 */
final class SettingsBackup {

    private SettingsBackup() { /* no instances */ }

    static final String MAGIC   = "BLBK";
    static final int    VERSION = 1;

    // Keys — intentionally the same strings as the SharedPreferences keys so
    // the file reads clearly and the activity maps them 1:1.
    static final String K_APP_ORDER  = "app_order";
    static final String K_HOME_COUNT = "home_count";
    static final String K_KEY_MAP    = "key_map";
    static final String K_HIDDEN     = "hidden_apps";
    static final String K_CLOCK_MODE = "clock_mode";

    /** Build the backup file text. {@code null} string values are written as
     *  empty so every key is always present in a well-formed file. */
    static String serialize(String appOrder, int homeCount, String keyMap,
                            String hiddenApps, int clockMode) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(MAGIC).append('\t').append(VERSION).append('\n');
        line(sb, K_APP_ORDER,  appOrder);
        line(sb, K_HOME_COUNT, Integer.toString(homeCount));
        line(sb, K_KEY_MAP,    keyMap);
        line(sb, K_HIDDEN,     hiddenApps);
        line(sb, K_CLOCK_MODE, Integer.toString(clockMode));
        return sb.toString();
    }

    private static void line(StringBuilder sb, String key, String value) {
        if (value == null) value = "";
        // Defensive: TAB / newline can never appear in our values, but strip
        // them so a hand-edited or odd input can't break the line structure.
        value = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        sb.append(key).append('\t').append(value).append('\n');
    }

    /**
     * Parse a backup file. Returns {@code null} when the magic / version
     * header is missing or unsupported — the caller treats that as "not a
     * valid backup" and changes nothing. Blank lines and unknown keys are
     * skipped (forward-compatible).
     */
    static Parsed parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] lines = raw.split("\\r?\\n", -1);
        if (lines.length == 0) return null;

        String[] header = lines[0].split("\t", -1);
        if (header.length < 2 || !MAGIC.equals(header[0])) return null;
        int ver;
        try { ver = Integer.parseInt(header[1].trim()); }
        catch (NumberFormatException e) { return null; }
        if (ver < 1 || ver > VERSION) return null;   // unknown future format → refuse

        Map<String, String> map = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.isEmpty()) continue;
            int tab = ln.indexOf('\t');
            if (tab <= 0) continue;                   // no key, or empty key → skip
            map.put(ln.substring(0, tab), ln.substring(tab + 1));
        }
        return new Parsed(map);
    }

    /** Typed accessor over the parsed key/value pairs. */
    static final class Parsed {
        private final Map<String, String> m;
        Parsed(Map<String, String> m) { this.m = m; }

        /** {@code true} if the key was present in the file (even if empty). */
        boolean has(String key) { return m.containsKey(key); }

        /** Raw string value, or {@code null} if absent. */
        String str(String key) { return m.get(key); }

        /** Integer value, or {@code def} if absent / not an integer. */
        int intVal(String key, int def) {
            String v = m.get(key);
            if (v == null) return def;
            try { return Integer.parseInt(v.trim()); }
            catch (NumberFormatException e) { return def; }
        }
    }
}
