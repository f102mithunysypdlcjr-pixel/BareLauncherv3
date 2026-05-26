package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link KeymapStore}. Runs on the JVM (no Android emulator
 * required) by exercising the visitor-based parse APIs and the array-based
 * serialise APIs — see the class javadoc on {@link KeymapStore} for why
 * those signatures exist (the launcher's in-memory state is in
 * {@code SparseArray} / {@code ArraySet} which are not available in JVM
 * unit tests).
 */
public class KeymapStoreTest {

    /** Mirror of the launcher's curated keycodes. Real values:
     *  KEYCODE_PROG_RED=183, _GREEN=184, _YELLOW=185, _BLUE=186,
     *  KEYCODE_MENU=82, KEYCODE_CAPTIONS=175. Hard-coded here so the test
     *  has no dependency on the {@code KeyEvent} class (which is Android-
     *  framework and unavailable in JVM tests). */
    private static final int[] CURATED = { 183, 184, 185, 186, 82, 175 };

    // ─── parseKeyMap ─────────────────────────────────────────────────────

    @Test public void parseKeyMap_null_emptyAndNotDropped() {
        Map<Integer, String> out = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap(null, CURATED, out::put);
        assertTrue(out.isEmpty());
        assertFalse(dropped);
    }

    @Test public void parseKeyMap_empty_emptyAndNotDropped() {
        Map<Integer, String> out = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap("", CURATED, out::put);
        assertTrue(out.isEmpty());
        assertFalse(dropped);
    }

    @Test public void parseKeyMap_singleAccepted() {
        Map<Integer, String> out = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap("183=com.app.red", CURATED, out::put);
        assertEquals(1, out.size());
        assertEquals("com.app.red", out.get(183));
        assertFalse(dropped);
    }

    @Test public void parseKeyMap_multipleAccepted() {
        Map<Integer, String> out = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap(
                "183=com.app.red,184=com.app.green,82=com.app.menu",
                CURATED, out::put);
        assertEquals(3, out.size());
        assertEquals("com.app.red", out.get(183));
        assertEquals("com.app.green", out.get(184));
        assertEquals("com.app.menu", out.get(82));
        assertFalse(dropped);
    }

    @Test public void parseKeyMap_uncuratedKeycodeDropped() {
        // Keycode 999 is not in CURATED — a stale shortcut from a previous
        // build where the slot existed and has since been removed. Parser
        // must drop it AND signal the caller to re-save.
        Map<Integer, String> out = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap("999=com.app.x,183=com.app.red", CURATED, out::put);
        assertEquals(1, out.size());
        assertEquals("com.app.red", out.get(183));
        assertNull(out.get(999));
        assertTrue("dropped flag should be set when an uncurated key is filtered", dropped);
    }

    @Test public void parseKeyMap_corruptIntegerDropped() {
        // The "=" position is fine but the keycode is non-numeric.
        Map<Integer, String> out = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap("abc=com.app.x,183=com.app.red", CURATED, out::put);
        assertEquals(1, out.size());
        assertEquals("com.app.red", out.get(183));
        assertTrue(dropped);
    }

    @Test public void parseKeyMap_missingEqualsDropped() {
        // Token without an '=' is a corrupt entry — should drop and flag.
        Map<Integer, String> out = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap("garbage,183=com.app.red", CURATED, out::put);
        assertEquals(1, out.size());
        assertTrue(dropped);
    }

    @Test public void parseKeyMap_emptyPackageDroppedAndFlagged() {
        // 183= is a half-written entry. Parser treats this as corrupt
        // (the kc=pkg shape requires a non-empty pkg) so it must be
        // dropped AND the dropped flag set so the caller re-saves and
        // converges the on-disk format.
        Map<Integer, String> out = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap("183=,184=com.app.green", CURATED, out::put);
        assertEquals(1, out.size());
        assertEquals("com.app.green", out.get(184));
        assertNull(out.get(183));
        assertTrue("empty-package entry should set dropped flag", dropped);
    }

    @Test public void parseKeyMap_trailingCommaTolerated() {
        Map<Integer, String> out = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap("183=com.app.red,", CURATED, out::put);
        assertEquals(1, out.size());
        assertFalse(dropped);
    }

    @Test public void parseKeyMap_doubleCommaTolerated() {
        Map<Integer, String> out = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap("183=com.app.red,,184=com.app.green",
                CURATED, out::put);
        assertEquals(2, out.size());
        assertFalse(dropped);
    }

    // ─── serializeKeyMap ────────────────────────────────────────────────

    @Test public void serializeKeyMap_emptyArrays_emptyString() {
        assertEquals("", KeymapStore.serializeKeyMap(new int[0], new String[0]));
    }

    @Test public void serializeKeyMap_null_emptyString() {
        assertEquals("", KeymapStore.serializeKeyMap(null, null));
        assertEquals("", KeymapStore.serializeKeyMap(new int[0], null));
        assertEquals("", KeymapStore.serializeKeyMap(null, new String[0]));
    }

    @Test public void serializeKeyMap_single() {
        assertEquals("183=com.app.red",
                KeymapStore.serializeKeyMap(new int[]{183}, new String[]{"com.app.red"}));
    }

    @Test public void serializeKeyMap_multiple() {
        assertEquals("183=com.app.red,184=com.app.green",
                KeymapStore.serializeKeyMap(new int[]{183, 184},
                        new String[]{"com.app.red", "com.app.green"}));
    }

    @Test public void serializeKeyMap_skipsNullAndEmptyPackages() {
        assertEquals("183=com.app.red,184=com.app.green",
                KeymapStore.serializeKeyMap(
                        new int[]{183, 999, 1000, 184},
                        new String[]{"com.app.red", null, "", "com.app.green"}));
    }

    @Test(expected = IllegalArgumentException.class)
    public void serializeKeyMap_lengthMismatchThrows() {
        KeymapStore.serializeKeyMap(new int[]{183, 184}, new String[]{"com.app.red"});
    }

    // ─── parseHiddenApps ────────────────────────────────────────────────

    @Test public void parseHiddenApps_null_empty() {
        Set<String> out = new LinkedHashSet<>();
        KeymapStore.parseHiddenApps(null, out::add);
        assertTrue(out.isEmpty());
    }

    @Test public void parseHiddenApps_empty_empty() {
        Set<String> out = new LinkedHashSet<>();
        KeymapStore.parseHiddenApps("", out::add);
        assertTrue(out.isEmpty());
    }

    @Test public void parseHiddenApps_basic() {
        Set<String> out = new LinkedHashSet<>();
        KeymapStore.parseHiddenApps("a,b,c", out::add);
        assertEquals(3, out.size());
        assertTrue(out.contains("a"));
        assertTrue(out.contains("b"));
        assertTrue(out.contains("c"));
    }

    @Test public void parseHiddenApps_skipsBlankTokens() {
        Set<String> out = new LinkedHashSet<>();
        KeymapStore.parseHiddenApps(",a,,b,", out::add);
        assertEquals(2, out.size());
        assertTrue(out.contains("a"));
        assertTrue(out.contains("b"));
    }

    // ─── serializeHiddenApps ───────────────────────────────────────────

    @Test public void serializeHiddenApps_null_emptyString() {
        assertEquals("", KeymapStore.serializeHiddenApps(null));
    }

    @Test public void serializeHiddenApps_empty_emptyString() {
        assertEquals("", KeymapStore.serializeHiddenApps(Collections.emptyList()));
    }

    @Test public void serializeHiddenApps_basic() {
        assertEquals("a,b,c",
                KeymapStore.serializeHiddenApps(Arrays.asList("a", "b", "c")));
    }

    @Test public void serializeHiddenApps_skipsNullAndEmpty() {
        assertEquals("a,b",
                KeymapStore.serializeHiddenApps(Arrays.asList("a", null, "", "b")));
    }

    // ─── round-trips ─────────────────────────────────────────────────────

    @Test public void hiddenApps_roundTrip_preservesAllPackages() {
        // Order is implementation-dependent for sets, so test by parsing
        // back into a set and asserting equality of contents.
        String stored = KeymapStore.serializeHiddenApps(Arrays.asList("z.app", "a.app", "m.app"));
        Set<String> parsed = new LinkedHashSet<>();
        KeymapStore.parseHiddenApps(stored, parsed::add);
        assertEquals(3, parsed.size());
        assertTrue(parsed.contains("z.app"));
        assertTrue(parsed.contains("a.app"));
        assertTrue(parsed.contains("m.app"));
    }

    @Test public void keyMap_roundTrip_preservesEntries() {
        String stored = KeymapStore.serializeKeyMap(
                new int[]{183, 82, 184},
                new String[]{"com.app.red", "com.app.menu", "com.app.green"});
        Map<Integer, String> parsed = new HashMap<>();
        boolean dropped = KeymapStore.parseKeyMap(stored, CURATED, parsed::put);
        assertFalse(dropped);
        assertEquals(3, parsed.size());
        assertEquals("com.app.red",   parsed.get(183));
        assertEquals("com.app.menu",  parsed.get(82));
        assertEquals("com.app.green", parsed.get(184));
    }

    // ─── isCurated ────────────────────────────────────────────────────

    @Test public void isCurated_presentAndAbsent() {
        assertTrue (KeymapStore.isCurated(183, CURATED));
        assertTrue (KeymapStore.isCurated(82,  CURATED));
        assertFalse(KeymapStore.isCurated(999, CURATED));
        assertFalse(KeymapStore.isCurated(0,   CURATED));
    }

    @Test public void isCurated_nullArrayFalse() {
        assertFalse(KeymapStore.isCurated(183, null));
    }

    @Test public void isCurated_emptyArrayFalse() {
        assertFalse(KeymapStore.isCurated(183, new int[0]));
    }
}
