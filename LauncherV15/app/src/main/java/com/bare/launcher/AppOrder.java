package com.bare.launcher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java helpers for the persisted app-shelf order.
 *
 * The order is stored in {@code SharedPreferences} as a single comma-separated
 * string of package names, e.g. {@code "com.app.a,com.app.b,com.app.c"}.
 * These two helpers cover the round trip:
 * <ul>
 *   <li>{@link #parse(String)} turns the stored string into a rank map
 *       (package → index) used to sort the freshly-queried app list.</li>
 *   <li>{@link #serialize(List)} produces the stored string from the
 *       current ordered package list.</li>
 * </ul>
 *
 * Both functions are deliberately Android-free so they can be exercised by
 * fast JVM unit tests (see {@code AppOrderTest}). The activity uses these
 * helpers indirectly via {@code applyStoredOrder} / {@code saveOrder}.
 */
final class AppOrder {

    private AppOrder() { /* no instances */ }

    /**
     * Parse a stored order string into a package-name → rank map. A {@code null}
     * or empty input yields an empty map. Trailing/internal empty tokens
     * (which can appear if the user manually edited prefs) are skipped so a
     * malformed entry does not bind rank 0 to the empty string.
     */
    static Map<String, Integer> parse(String raw) {
        Map<String, Integer> rank = new HashMap<>();
        if (raw == null || raw.isEmpty()) return rank;
        // -1 limit preserves trailing empties; we filter them out below so
        // the behaviour is "skip empties" rather than "fail on empties".
        String[] parts = raw.split(",", -1);
        int idx = 0;
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            // First occurrence wins — duplicates in the persisted string
            // (which a manual edit could produce) collapse to the earliest
            // rank, matching the natural reading-order intuition.
            if (!rank.containsKey(p)) {
                rank.put(p, idx++);
            }
        }
        return rank;
    }

    /**
     * Build the persisted order string from a list of package names. A
     * {@code null} or empty input yields an empty string. Null elements
     * inside the list are skipped (defensive — should never happen but
     * the activity has historically been tolerant).
     */
    static String serialize(List<String> packageNames) {
        if (packageNames == null || packageNames.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(packageNames.size() * 24);
        boolean first = true;
        for (String p : packageNames) {
            if (p == null || p.isEmpty()) continue;
            if (!first) sb.append(',');
            sb.append(p);
            first = false;
        }
        return sb.toString();
    }
}
