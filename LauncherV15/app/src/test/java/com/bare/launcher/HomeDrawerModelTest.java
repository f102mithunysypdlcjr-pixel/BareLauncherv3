package com.bare.launcher;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link HomeDrawerModel} (v1.5.0, COLS = 6). Runs on the JVM
 * (no Android emulator), exercising the "home favourites row + pull-down
 * drawer" geometry, focus navigation, and the Option-A promote / demote
 * Move-mode rules (including full-home-row displacement).
 */
public class HomeDrawerModelTest {

    private static List<String> list(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add("p" + i);
        return l;
    }

    // ── homeCount hygiene ────────────────────────────────────────────────

    @Test public void cols_isSix() { assertEquals(6, HomeDrawerModel.COLS); }

    @Test public void clamp_bounds() {
        assertEquals(0, HomeDrawerModel.clampHomeCount(-3, 20));
        assertEquals(6, HomeDrawerModel.clampHomeCount(100, 20)); // cap = COLS = 6
        assertEquals(5, HomeDrawerModel.clampHomeCount(5, 20));
        assertEquals(3, HomeDrawerModel.clampHomeCount(5, 3));
        assertEquals(0, HomeDrawerModel.clampHomeCount(5, 0));
    }

    @Test public void defaultHomeCount_isFirstSix() {
        assertEquals(6, HomeDrawerModel.defaultHomeCount(20));
        assertEquals(6, HomeDrawerModel.defaultHomeCount(6));
        assertEquals(5, HomeDrawerModel.defaultHomeCount(5));
        assertEquals(0, HomeDrawerModel.defaultHomeCount(0));
    }

    // ── geometry ─────────────────────────────────────────────────────────

    @Test public void geometry_homeFiveThenGrid() {
        // 5 home apps + 20 total → row0=5, then 15 across rows 1..3 (6,6,3).
        int size = 20, hc = 5;
        assertEquals(4, HomeDrawerModel.rowCount(size, hc));
        assertEquals(5, HomeDrawerModel.rowLength(0, size, hc));
        assertEquals(6, HomeDrawerModel.rowLength(1, size, hc));
        assertEquals(6, HomeDrawerModel.rowLength(2, size, hc));
        assertEquals(3, HomeDrawerModel.rowLength(3, size, hc));
        assertEquals(0, HomeDrawerModel.rowOf(4, hc));
        assertEquals(4, HomeDrawerModel.colOf(4, hc));
        assertEquals(1, HomeDrawerModel.rowOf(5, hc));
        assertEquals(0, HomeDrawerModel.colOf(5, hc));
        assertEquals(2, HomeDrawerModel.rowOf(11, hc));
        assertEquals(0, HomeDrawerModel.colOf(11, hc));
        assertEquals(11, HomeDrawerModel.indexAt(2, 0, size, hc));
        assertEquals(-1, HomeDrawerModel.indexAt(3, 3, size, hc)); // last row only 3 wide
        assertEquals(-1, HomeDrawerModel.indexAt(0, 5, size, hc)); // home row only 5 wide
    }

    @Test public void geometry_homeFull() {
        int size = 20, hc = 6;
        assertEquals(4, HomeDrawerModel.rowCount(size, hc)); // 6 + ceil(14/6)=3
        assertEquals(6, HomeDrawerModel.rowLength(0, size, hc));
        assertEquals(6, HomeDrawerModel.rowLength(1, size, hc));
        assertEquals(2, HomeDrawerModel.rowLength(3, size, hc)); // 20-6=14 -> 6,6,2
        assertEquals(0, HomeDrawerModel.rowOf(5, hc));
        assertEquals(1, HomeDrawerModel.rowOf(6, hc));
    }

    @Test public void geometry_homeZero_plainGrid() {
        int size = 10, hc = 0;
        assertEquals(0, HomeDrawerModel.nonHomeBaseRow(hc));
        assertEquals(2, HomeDrawerModel.rowCount(size, hc)); // ceil(10/6) = 2
        assertEquals(0, HomeDrawerModel.rowOf(5, hc));
        assertEquals(1, HomeDrawerModel.rowOf(6, hc));
        assertEquals(6, HomeDrawerModel.indexAt(1, 0, size, hc));
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
        int size = 20, hc = 5; // row1 = indices 5..10 (cols 0..5)
        assertEquals(5, HomeDrawerModel.navLeft(5, size, hc));   // col0 of row1 → stay
        assertEquals(5, HomeDrawerModel.navLeft(6, size, hc));   // col1 → col0
        assertEquals(7, HomeDrawerModel.navRight(6, size, hc));  // col1 → col2 within row1
        assertEquals(10, HomeDrawerModel.navRight(10, size, hc)); // row1 right edge → stay
        assertEquals(4, HomeDrawerModel.navRight(4, size, hc));  // home right edge (hc=5) → stay
        assertEquals(3, HomeDrawerModel.navLeft(4, size, hc));
    }

    @Test public void nav_up_topRowCloses() {
        int size = 20, hc = 5;
        assertEquals(HomeDrawerModel.CLOSE_DRAWER, HomeDrawerModel.navUp(0, size, hc));
        assertEquals(HomeDrawerModel.CLOSE_DRAWER, HomeDrawerModel.navUp(4, size, hc));
        assertEquals(HomeDrawerModel.CLOSE_DRAWER, HomeDrawerModel.navUp(0, size, 0));
    }

    @Test public void nav_up_snapsToShorterHomeRow() {
        int size = 20, hc = 5;
        // row1 col5 (index 10) UP → home only 5 wide → snap to last home cell (4)
        assertEquals(4, HomeDrawerModel.navUp(10, size, hc));
        // row1 col2 (index 7) UP → home col2 (index 2)
        assertEquals(2, HomeDrawerModel.navUp(7, size, hc));
    }

    @Test public void nav_down_snapsToShorterLastRow() {
        int size = 20, hc = 5; // rows: 5, 6, 6, 3 ; last row cols 0..2
        // home col4 (index 4) DOWN → row1 col4 (index 9)
        assertEquals(9, HomeDrawerModel.navDown(4, size, hc));
        // row2 col5 (index 16) DOWN → last row only 3 wide → snap to col2 (index 19)
        assertEquals(19, HomeDrawerModel.navDown(16, size, hc));
        // last row → stay
        assertEquals(19, HomeDrawerModel.navDown(19, size, hc));
    }

    // ── Move mode: promote / demote ──────────────────────────────────────

    @Test public void move_promote_appendsToHomeAndIncrements() {
        List<String> order = list(20);          // p0..p19
        int hc = 5;                              // not full → append-grow
        // index 7 is row1 col2 (p7). UP → promote, appended at end of home (dest = 5).
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 7, hc);
        assertEquals(6, r.homeCount);
        assertEquals(5, r.index);
        assertEquals("p7", order.get(5));
        assertEquals("p5", order.get(6));        // p5, p6 shifted right by one
        assertEquals("p6", order.get(7));
    }

    @Test public void move_promote_fullRow_swapsWithCellAbove() {
        List<String> order = list(20);
        int hc = 6;                              // home full
        // index 8 = row1 col2. UP → straight vertical swap with the home cell
        // directly above (index 2): the moved app takes the home slot, the
        // replaced home app drops into the slot the moved app vacated.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 8, hc);
        assertEquals(6, r.homeCount);            // home stays full
        assertEquals(2, r.index);                // moved app now at home col 2
        assertEquals("p8", order.get(2));        // moved app in the home slot
        assertEquals("p2", order.get(8));        // replaced home app → vacated slot
        assertEquals(Arrays.asList("p0", "p1", "p8", "p3", "p4", "p5"), order.subList(0, 6));
    }

    @Test public void move_demote_decrementsAndLeavesFirstNonHome() {
        List<String> order = list(20);
        int hc = 5;
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveDown(order, 1, hc);
        assertEquals(4, r.homeCount);
        assertEquals(4, r.index);
        assertEquals("p1", order.get(4));        // now first non-home app
        assertEquals(Arrays.asList("p0", "p2", "p3", "p4"), order.subList(0, 4));
    }

    @Test public void move_demote_lastHomeStaysInPlaceConceptually() {
        List<String> order = list(20);
        int hc = 5;
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
        assertEquals(1, u.homeCount);
        assertEquals(0, u.index);
        // Any row-0 app (home empty) promotes into a new 1-app home row.
        HomeDrawerModel.MoveResult u2 = HomeDrawerModel.moveUp(list(10), 5, 0);
        assertEquals(1, u2.homeCount);
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
        // index 4 is last home cell (hc=5) → RIGHT must not bleed into row 1.
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveRight(order, 4, hc);
        assertEquals(4, r.index);
        assertEquals(list(20), order);
        // index 10 is last cell of row 1 → RIGHT must not cross to row 2.
        HomeDrawerModel.MoveResult r2 = HomeDrawerModel.moveRight(order, 10, hc);
        assertEquals(10, r2.index);
        assertEquals(list(20), order);
    }

    @Test public void move_verticalSwap_lowerRows() {
        List<String> order = list(30);
        int hc = 5;
        // index 12 (row2 col1) UP → swap with row1 col1 (index 6).
        HomeDrawerModel.MoveResult r = HomeDrawerModel.moveUp(order, 12, hc);
        assertEquals(6, r.index);
        assertEquals(5, r.homeCount);
        assertEquals("p12", order.get(6));
        assertEquals("p6", order.get(12));
    }
}
