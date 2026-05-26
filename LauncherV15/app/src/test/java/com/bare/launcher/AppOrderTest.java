package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link AppOrder}. Runs on the JVM (no Android emulator
 * required), exercising the persisted-order parse / serialize round-trip
 * the launcher uses to remember app order across reboots.
 */
public class AppOrderTest {

    @Test public void parse_null_isEmpty() {
        Map<String, Integer> r = AppOrder.parse(null);
        assertTrue(r.isEmpty());
    }

    @Test public void parse_empty_isEmpty() {
        Map<String, Integer> r = AppOrder.parse("");
        assertTrue(r.isEmpty());
    }

    @Test public void parse_single() {
        Map<String, Integer> r = AppOrder.parse("com.app.one");
        assertEquals(1, r.size());
        assertEquals(Integer.valueOf(0), r.get("com.app.one"));
    }

    @Test public void parse_multipleAssignsAscendingRanks() {
        Map<String, Integer> r = AppOrder.parse("a,b,c");
        assertEquals(Integer.valueOf(0), r.get("a"));
        assertEquals(Integer.valueOf(1), r.get("b"));
        assertEquals(Integer.valueOf(2), r.get("c"));
    }

    @Test public void parse_skipsEmptyTokens() {
        // A manual edit could leave dangling commas. We must not bind
        // rank 0 to the empty string; that would sort an unknown package
        // ahead of every known one.
        Map<String, Integer> r = AppOrder.parse(",a,,b,");
        assertEquals(2, r.size());
        assertEquals(Integer.valueOf(0), r.get("a"));
        assertEquals(Integer.valueOf(1), r.get("b"));
        assertNull(r.get(""));
    }

    @Test public void parse_duplicatesUseFirstOccurrence() {
        Map<String, Integer> r = AppOrder.parse("a,b,a,c");
        // First "a" wins at rank 0; the second "a" is collapsed.
        assertEquals(Integer.valueOf(0), r.get("a"));
        assertEquals(Integer.valueOf(1), r.get("b"));
        assertEquals(Integer.valueOf(2), r.get("c"));
    }

    @Test public void serialize_null_isEmpty() {
        assertEquals("", AppOrder.serialize(null));
    }

    @Test public void serialize_empty_isEmpty() {
        assertEquals("", AppOrder.serialize(Collections.emptyList()));
    }

    @Test public void serialize_single() {
        assertEquals("com.app.one",
                AppOrder.serialize(Collections.singletonList("com.app.one")));
    }

    @Test public void serialize_multipleJoinedWithComma() {
        assertEquals("a,b,c",
                AppOrder.serialize(Arrays.asList("a", "b", "c")));
    }

    @Test public void serialize_skipsNullAndEmptyEntries() {
        // Defensive: if a caller hands us a list with holes, we keep the
        // delimiter clean rather than emit ",,".
        assertEquals("a,b",
                AppOrder.serialize(Arrays.asList("a", null, "", "b")));
    }

    @Test public void roundTrip_preservesOrder() {
        String stored = AppOrder.serialize(Arrays.asList("z", "a", "m", "k"));
        Map<String, Integer> parsed = AppOrder.parse(stored);
        assertEquals(Integer.valueOf(0), parsed.get("z"));
        assertEquals(Integer.valueOf(1), parsed.get("a"));
        assertEquals(Integer.valueOf(2), parsed.get("m"));
        assertEquals(Integer.valueOf(3), parsed.get("k"));
    }
}
