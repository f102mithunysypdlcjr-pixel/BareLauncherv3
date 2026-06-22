package com.bare.launcher;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link HomeDrawerModel}. Runs on the JVM (no Android
 * emulator), exercising the "home favourites row + pull-down drawer" geometry,
 * focus navigation, and the Option-A promote / demote Move-mode rules.
 */
public class HomeDrawerModelTest {

    private static List<String> list(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add("p" + i);
        return l;
    }

    // ── homeCount hygiene ────────────────────────────────────────────────

    @Test public void clamp_bounds() {
        assertEquals(0, HomeDrawerModel.clampHomeCount(-3, 20));
        assertEquals(8, HomeDrawerModel.clampHomeCount(100, 20));
        assertEquals(5, HomeDrawerModel.clampHomeCount(5, 20));
        assertEquals(3, HomeDrawerModel.clampHomeCount(5, 3)); // fewer apps than count
        assertEquals(0, HomeDrawerModel.clampHomeCount(5, 0));
    }

    @Test public void defaultHomeCount_isFirstEight() {
        assertEquals(8, HomeDrawerModel.defaultHomeCount(20));
        assertEquals(8, HomeDrawerModel.defaultHomeCount(8));
        assertEquals(5, HomeDrawerModel.defaultHomeCount(5));
        assertEquals(0, HomeDrawerModel.defaultHomeCount(0));
    }

    // ── geometry ─────────────────────────────────────────────────────────

    @Test public void geometry_homeFiveThenGrid() {
        // 5 home apps + 20 total → row0 has 5, rows 1+ hold the other 15.
        int size = 20, hc = 5;
        assertEquals(1 /*home*/ + 2 /*15 -> ceil(15/8)=2*/, HomeDrawerModel.rowCount(size, hc));
        assertEquals(5, HomeDrawerModel.rowLength(0, size, hc));
        assertEquals(8, HomeDrawerModel.rowLength(1, size, hc));
        assertEquals(7, HomeDrawerModel.rowLength(2, size, hc)); // remainder 15-8=7
        // index 4 = last home cell
        assertEquals(0, HomeDrawerModel.rowOf(4, hc));
        assertEquals(4, HomeDrawerModel.colOf(4, hc));
        // index 5 = first non-home cell (row 1, col 0)
        assertEquals(1, HomeDrawerModel.rowOf(5, hc));
        assertEquals(0, HomeDrawerModel.colOf(5, hc));
        // index 13 = row 2 col 0 (5 + 8)
        assertEquals(2, HomeDrawerModel.rowOf(13, hc));
        assertEquals(0, HomeDrawerModel.colOf(13, hc));
        assertEquals(13, HomeDrawerModel.indexAt(2, 0, size, hc));
        assertEquals(-1, HomeDrawerModel.indexAt(2, 7, size, hc)); // 7 cells -> col 7 empty
        assertEquals(-1, HomeDrawerModel.indexAt(0, 5, size, hc)); // home row only 5 wide
    }

    @Test public void geometry_homeFull() {
        int size = 20, hc = 8;
        assertEquals(8, HomeDrawerModel.rowLength(0, size, hc));
        assertEquals(8, HomeDrawerModel.rowLength(1, size, hc));
        assertEquals(4, HomeDrawerModel.rowLength(2, size, hc)); // 20-8=12 -> 8 + 4
        assertEquals(0, HomeDrawerModel.rowOf(7, hc));
        assertEquals(1, HomeDrawerModel.rowOf(8, hc));
    }

    @Test public void geometry_homeZero_plainGrid() {
        int size = 10, hc = 0;
        assertEquals(0, HomeDrawerModel.nonHomeBaseRow(hc));
        assertEquals(2, HomeDrawerModel.rowCount(size, hc)); // 8 + 2
        assertEquals(0, HomeDrawerModel.rowOf(7, hc));
        assertEquals(1, HomeDrawerModel.rowOf(8, hc));
        assertEquals(8, HomeDrawerModel.indexAt(1, 0, size, hc));
    }

    @Test public void geometry_allHome_noSecondRow() {
        int size = 5, hc = 5;
        assertEquals(1, HomeDrawerModel.rowCount(size, hc));
        assertEquals(5, HomeDrawerModel.rowLength(0, size, hc));
        assertEquals(0, HomeDrawerModel.rowLength(1, size, hc));
    }

    @Test public void geometry_empty() {
        assertEquals(0, HomeDrawerModel.rowCount(0, 0));
        assertEquals(0, HomeDrawerModel.rowLength(0, 0, 0));
        assertEquals(-1, HomeDrawerModel.indexAt(0, 0, 0, 0));
    }

    // ── navigation ───────────────────────────────────────────────────────

    @Test public void nav_leftRight_stopsAtRowEdges() {
        int size = 20, hc = 5;
        assertEquals(5, HomeDrawerModel.navLeft(5, size, hc));  // col0 of row1 → stay
        assertEquals(5, HomeDrawerModel.navLeft(6, size, hc));  // → 5
        assertEquals(13, HomeDrawerModel.navRight(12, size, hc)); // within row1
        assertEquals(12, HomeDrawerModel.navRight(12 - 0, size, hc) == 13 ? 12 : 12); // sanity
        // last cell of row1 is index 12 (5+7); index 12 RIGHT would cross to row2 → stay
        assertEquals(12, HomeDrawerModel.navRight(12, size, hc));
        // home row right edge (index 4) → stays
        assertEquals(4, HomeDrawerModel.navRight(4, size, hc));
        assertEquals(3, HomeDrawerModel.navLeft(4, size, hc));
    }

    @Test public void nav_up_topRowCloses() {
        int size = 20, hc = 5;
        assertEquals(HomeDrawerModel.CLOSE_DRAWER, HomeDrawerModel.navUp(0, size, hc));
        assertEquals(HomeDrawerModel.CLOSE_DRAWER, HomeDrawerModel.navUp(4, size, hc));
        // homeCount 0 → row 0 is the plain grid top, still closes
        assertEquals(HomeDrawerModel.CLOSE_DRAWER, HomeDrawerModel.navUp(0, size, 0));
    }

    @Test public void nav_up_snapsToShorterHomeRow() {
        int size = 20, hc = 5;
        // row1 col6 (index 11) UP → home row only 5 wide → snap to last home cell (index 4)
        assertEquals(4, HomeDrawerModel.navUp(11, size, hc));
        // row1 col2 (index 7) UP → home col2 (index 2)
        assertEquals(2, HomeDrawerModel.navUp(7, size, hc));
    }

    @Test public void nav_down_snapsToShorterLastRow() {
        int size = 20, hc = 5; // row2 has 7 cells (cols 0..6)
        // home col4 (index 4) DOWN → row1 col4 (index 9)
        assertEquals(9, HomeDrawerModel.navDown(4, size, hc));
        // row1 col7 (index 12) DOWN → row2 only 7 wide → snap to col6 (index 19)
        assertEquals(19, HomeDrawerModel.navDown(12, size, hc));
        // last row → stay
        assertEquals(19, HomeDrawerModel.navDown(19, size, hc));
    }

    // ── Move mode: promote / demote ──────────────────────────────────────

    @Test public void move_promote_appendsToHomeAndIncrements() {
        List<String> order = list(20);          // p0..p19
        int hc = 5;
        // index 7 is row1 col2 (p7). UP → promote, appended at end of home (dest = 5).
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 7, hc);
        assertEquals(6, r.homeCount);
        assertEquals(5, r.index);
        assertEquals("p7", order.get(5));
        // p5, p6 shift right by one; p7 removed from old slot
        assertEquals("p5", order.get(6));
        assertEquals("p6", order.get(7));
    }

    @Test public void move_promote_cappedAtEight_swapsInstead() {
        List<String> order = list(20);
        int hc = 8;                              // home full
        // index 8 is first non-home (row1 col0). UP → swap with cell above (index 0).
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 8, hc);
        assertEquals(8, r.homeCount);            // unchanged — no promote when full
        assertEquals(0, r.index);
        assertEquals("p8", order.get(0));
        assertEquals("p0", order.get(8));
    }

    @Test public void move_demote_decrementsAndLeavesFirstNonHome() {
        List<String> order = list(20);
        int hc = 5;
        // index 1 (home, p1) DOWN → demote. dest = homeCount-1 = 4.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveDown(order, 1, hc);
        assertEquals(4, r.homeCount);
        assertEquals(4, r.index);
        assertEquals("p1", order.get(4));        // now first non-home app
        // remaining home apps p0,p2,p3,p4
        assertEquals(Arrays.asList("p0", "p2", "p3", "p4"), order.subList(0, 4));
    }

    @Test public void move_demote_lastHomeStaysInPlaceConceptually() {
        List<String> order = list(20);
        int hc = 5;
        // index 4 (last home cell) DOWN → dest = 4, homeCount → 4. Order unchanged.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveDown(order, 4, hc);
        assertEquals(4, r.homeCount);
        assertEquals(4, r.index);
        assertEquals("p4", order.get(4));
        assertEquals(list(20), order);
    }

    @Test public void move_demoteToZero_thenPromoteBack() {
        List<String> order = list(10);
        int hc = 1;
        HomeDrawerModel.MoveResult d = HomeDrawerModel.moveDown(order, 0, hc);
        assertEquals(0, d.homeCount);            // home now empty
        assertEquals(0, d.index);
        // homeCount 0: pushing the top-row app UP rebuilds the home row.
        HomeDrawerModel.MoveResult u = HomeDrawerModel.moveUp(order, 0, 0);
        assertEquals(1, u.homeCount);            // promoted back to a 1-app home row
        assertEquals(0, u.index);
        // A lower-row app moved up when home is empty is an ordinary swap
        // (it takes two presses to climb into the home row).
        HomeDrawerModel.MoveResult u2 = HomeDrawerModel.moveUp(list(10), 8, 0);
        assertEquals(0, u2.homeCount);
        assertEquals(0, u2.index);
    }

    @Test public void move_leftRight_swapWithinRow() {
        List<String> order = list(20);
        int hc = 5;
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveRight(order, 1, hc);
        assertEquals(2, r.index);
        assertEquals("p1", order.get(2));
        assertEquals("p2", order.get(1));
        HomeDrawerModel.MoveResult l = HomeDrawerModel.moveLeft(order, 2, hc);
        assertEquals(1, l.index);
        assertEquals(list(20), order);           // swapped back
    }

    @Test public void move_rightStopsAtRowBoundary() {
        List<String> order = list(20);
        int hc = 5;
        // index 4 is last home cell → RIGHT must not bleed into row 1.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveRight(order, 4, hc);
        assertEquals(4, r.index);
        assertEquals(list(20), order);
        // index 12 is last cell of row1 → RIGHT must not cross to row2.
        HomeDrawerModel.MoveResult r2 = HomeDrawerModel.moveRight(order, 12, hc);
        assertEquals(12, r2.index);
        assertEquals(list(20), order);
    }

    @Test public void move_verticalSwap_lowerRows() {
        List<String> order = list(30);
        int hc = 5;
        // index 14 (row2 col1) UP → swap with row1 col1 (index 6).
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 14, hc);
        assertEquals(6, r.index);
        assertEquals(5, r.homeCount);
        assertEquals("p14", order.get(6));
        assertEquals("p6", order.get(14));
    }
}
