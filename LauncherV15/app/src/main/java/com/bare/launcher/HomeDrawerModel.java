package com.bare.launcher;

import java.util.Collections;
import java.util.List;

/**
 * Pure-Java geometry / navigation / reorder logic for the Apple-TV style
 * "home favourites row + pull-down app drawer" layout (v1.5.0).
 *
 * <p>The launcher keeps a single flat, ordered list of the apps that are
 * eligible to appear on screen — the <em>visible</em> list (the master
 * {@code appList} minus any packages the user hid from the shelf). On top of
 * that flat list sits one extra integer, {@code homeCount}, that records how
 * many of the leading apps are "home" apps:
 *
 * <ul>
 *   <li>The <b>home favourites row</b> (pinned to the bottom, same look as the
 *       legacy shelf) shows the first {@code homeCount} apps of the visible
 *       list, centred when there are fewer than {@link #COLS}.</li>
 *   <li>The <b>app drawer</b> is a vertical recycling grid, {@link #COLS} apps
 *       per row, showing the <em>entire</em> visible list. Its first row is
 *       the home row (the first {@code homeCount} apps, rendered centred to
 *       mirror the bottom row); the remaining apps fill rows 1+ left-aligned,
 *       8 per row, with the last (incomplete) row left-aligned.</li>
 * </ul>
 *
 * <p><b>"Option A" — one continuous space.</b> Home membership is purely
 * positional: home row = "first {@code homeCount} of the drawer order". There
 * is no separate favourites flag. In the drawer's Move mode, pushing an app
 * UP across the home boundary promotes it into the home row
 * ({@code homeCount++}, capped at {@link #COLS}); pushing a home app DOWN out
 * of row 0 demotes it ({@code homeCount--}). Everything else is an ordinary
 * grid swap that leaves {@code homeCount} untouched.
 *
 * <p>This class is deliberately Android-free so the fiddly index math and the
 * promote/demote rules can be exercised by fast JVM unit tests
 * (see {@code HomeDrawerModelTest}). The activity owns the actual
 * {@code List<AppInfo>} and the {@code homeCount} field and drives all view
 * work; it only calls these static helpers to decide <em>where</em> things go.
 */
final class HomeDrawerModel {

    private HomeDrawerModel() { /* no instances */ }

    /** Apps per drawer row, and the hard cap on the home-row size. v1.5.0
     *  uses 6 (down from 8) for larger, TV-friendly rounded-square tiles in
     *  the Apple-TV / Fire-TV idiom. */
    static final int COLS = 6;

    /** Sentinel returned by {@link #navUp(int, int, int)} when UP is pressed
     *  on a cell in the drawer's top row — the caller should close the drawer
     *  rather than move focus. Every other navigation helper only ever
     *  returns a real (>= 0) index. */
    static final int CLOSE_DRAWER = -1;

    // ── homeCount hygiene ────────────────────────────────────────────────

    /** Clamp a {@code homeCount} into the legal range {@code [0, min(COLS,
     *  size)]}. Used everywhere a raw / persisted / mutated value enters the
     *  model so no downstream helper ever sees an out-of-range count. */
    static int clampHomeCount(int homeCount, int size) {
        int cap = Math.min(COLS, Math.max(0, size));
        if (homeCount < 0)   return 0;
        if (homeCount > cap) return cap;
        return homeCount;
    }

    /** Default home-row size for a brand-new install (or the first run after
     *  an upgrade where no {@code home_count} pref exists yet): the first
     *  {@link #COLS} apps, or all of them when fewer than {@link #COLS} are
     *  installed. Mirrors the legacy "first 8 of the stored order" behaviour
     *  so an upgrade never resets the user to alphabetical. */
    static int defaultHomeCount(int size) {
        return Math.min(COLS, Math.max(0, size));
    }

    // ── grid geometry ────────────────────────────────────────────────────

    /** First grid row index occupied by the non-home apps. When there is at
     *  least one home app, row 0 is the home row and the rest start at row 1;
     *  when {@code homeCount == 0} there is no home row and the grid starts at
     *  row 0. */
    static int nonHomeBaseRow(int homeCount) {
        return homeCount > 0 ? 1 : 0;
    }

    /** Total number of grid rows needed to render {@code size} apps with the
     *  given {@code homeCount}. Zero when there are no apps. */
    static int rowCount(int size, int homeCount) {
        homeCount = clampHomeCount(homeCount, size);
        if (size <= 0) return 0;
        int nonHome = size - homeCount;
        int base = nonHomeBaseRow(homeCount); // 0 or 1
        if (nonHome <= 0) return base == 0 ? 0 : 1; // everything fits in the home row
        return base + (nonHome + COLS - 1) / COLS;
    }

    /** Grid row of the app at flat index {@code index}. */
    static int rowOf(int index, int homeCount) {
        if (homeCount > 0 && index < homeCount) return 0;
        int j = index - homeCount;
        return nonHomeBaseRow(homeCount) + j / COLS;
    }

    /** Grid column of the app at flat index {@code index}. */
    static int colOf(int index, int homeCount) {
        if (homeCount > 0 && index < homeCount) return index;
        int j = index - homeCount;
        return j % COLS;
    }

    /** Number of cells actually present in grid row {@code row}. Row 0 (when
     *  there are home apps) holds {@code homeCount}; the last non-home row may
     *  be a short, left-aligned remainder. */
    static int rowLength(int row, int size, int homeCount) {
        homeCount = clampHomeCount(homeCount, size);
        if (size <= 0 || row < 0 || row >= rowCount(size, homeCount)) return 0;
        if (homeCount > 0 && row == 0) return homeCount;
        int firstIdx = firstIndexOfRow(row, homeCount);
        return Math.min(COLS, size - firstIdx);
    }

    /** Flat index of the first cell in grid row {@code row} (ignoring whether
     *  that row is fully populated — callers gate with {@link #rowLength}). */
    static int firstIndexOfRow(int row, int homeCount) {
        if (homeCount > 0 && row == 0) return 0;
        int base = nonHomeBaseRow(homeCount);
        return homeCount + (row - base) * COLS;
    }

    /** Flat index of the cell at ({@code row}, {@code col}), or -1 if that
     *  slot is empty (e.g. a gap in the last partial row, or a column past
     *  the end of a short home row). */
    static int indexAt(int row, int col, int size, int homeCount) {
        homeCount = clampHomeCount(homeCount, size);
        if (col < 0 || col >= COLS) return -1;
        int len = rowLength(row, size, homeCount);
        if (col >= len) return -1;
        return firstIndexOfRow(row, homeCount) + col;
    }

    // ── focus navigation (drawer, NOT in Move mode) ──────────────────────

    /** LEFT within the drawer. Stops at the row's left edge (no wrap). */
    static int navLeft(int index, int size, int homeCount) {
        homeCount = clampHomeCount(homeCount, size);
        if (index <= 0) return index;
        return colOf(index, homeCount) > 0 ? index - 1 : index;
    }

    /** RIGHT within the drawer. Stops at the row's right edge (no wrap). */
    static int navRight(int index, int size, int homeCount) {
        homeCount = clampHomeCount(homeCount, size);
        if (index < 0 || index >= size - 1) return index < 0 ? 0 : index;
        int row = rowOf(index, homeCount);
        return colOf(index + 1, homeCount) == 0 || rowOf(index + 1, homeCount) != row
                ? index           // next cell is on a new row → stay
                : index + 1;
    }

    /** UP within the drawer. Returns {@link #CLOSE_DRAWER} when the focused
     *  cell is already on the top row (the caller closes the drawer and
     *  returns to the home favourites screen). Otherwise returns the index of
     *  the cell directly above, snapping to the nearest existing cell when the
     *  row above is shorter (e.g. a partial / centred home row). */
    static int navUp(int index, int size, int homeCount) {
        homeCount = clampHomeCount(homeCount, size);
        if (size <= 0) return CLOSE_DRAWER;
        if (index < 0) return 0;
        int row = rowOf(index, homeCount);
        if (row == 0) return CLOSE_DRAWER;          // top row → close
        int col = colOf(index, homeCount);
        int targetRow = row - 1;
        int len = rowLength(targetRow, size, homeCount);
        if (len <= 0) return CLOSE_DRAWER;
        int targetCol = Math.min(col, len - 1);
        return indexAt(targetRow, targetCol, size, homeCount);
    }

    /** DOWN within the drawer. Moves to the cell directly below, snapping to
     *  the nearest existing cell when the row below is shorter. Stays put when
     *  already on the last row. */
    static int navDown(int index, int size, int homeCount) {
        homeCount = clampHomeCount(homeCount, size);
        if (size <= 0 || index < 0) return index < 0 ? 0 : index;
        int row = rowOf(index, homeCount);
        int col = colOf(index, homeCount);
        int targetRow = row + 1;
        int len = rowLength(targetRow, size, homeCount);
        if (len <= 0) return index;                 // already on the last row
        int targetCol = Math.min(col, len - 1);
        return indexAt(targetRow, targetCol, size, homeCount);
    }

    // ── reorder / Move mode (drawer) ─────────────────────────────────────

    /** Result of a Move-mode operation: the (possibly new) home-row size and
     *  the index the dragged app now lives at. The caller mirrors the list
     *  mutation that the {@code moveX} helper already applied in place. */
    static final class MoveResult {
        final int homeCount;
        final int index;
        MoveResult(int homeCount, int index) { this.homeCount = homeCount; this.index = index; }
    }

    /** Swap the dragged app one position LEFT inside its row. No-op at the
     *  row's left edge. {@code homeCount} is unchanged. */
    static <T> MoveResult moveLeft(List<T> order, int index, int homeCount) {
        homeCount = clampHomeCount(homeCount, order.size());
        if (index > 0 && colOf(index, homeCount) > 0) {
            Collections.swap(order, index, index - 1);
            return new MoveResult(homeCount, index - 1);
        }
        return new MoveResult(homeCount, index);
    }

    /** Swap the dragged app one position RIGHT inside its row. No-op at the
     *  row's right edge. {@code homeCount} is unchanged. */
    static <T> MoveResult moveRight(List<T> order, int index, int homeCount) {
        int size = order.size();
        homeCount = clampHomeCount(homeCount, size);
        if (index >= 0 && index < size - 1
                && rowOf(index + 1, homeCount) == rowOf(index, homeCount)) {
            Collections.swap(order, index, index + 1);
            return new MoveResult(homeCount, index + 1);
        }
        return new MoveResult(homeCount, index);
    }

    /**
     * Move the dragged app UP one row.
     *
     * <ul>
     *   <li><b>Top row with an empty home row ({@code homeCount == 0})</b>:
     *       PROMOTE. There is no home row yet, so pushing the top-row app up
     *       turns it into the first home favourite — it moves to index 0 and
     *       {@code homeCount} becomes 1. This is the symmetric inverse of
     *       demoting the last home app down to an empty home row, so the user
     *       can always rebuild the home row through Move alone.</li>
     *   <li><b>Home row</b> (row 0, {@code homeCount > 0}): no-op — a home app
     *       is already at the top, nothing above it.</li>
     *   <li><b>First non-home row, home not full</b>: PROMOTE. The app is
     *       lifted out of its slot and appended to the end of the home segment;
     *       {@code homeCount++} (capped at {@link #COLS}).</li>
     *   <li><b>First non-home row, home already full ({@code COLS})</b>: a
     *       straight vertical swap — the moved app takes the home slot directly
     *       above it (same column) and the home app it replaces drops into the
     *       slot the moved app just vacated. {@code homeCount} stays at
     *       {@link #COLS}.</li>
     *   <li><b>Any lower row</b>: ordinary vertical swap with the cell one row
     *       up (8 positions earlier).</li>
     * </ul>
     */
    static <T> MoveResult moveUp(List<T> order, int index, int homeCount) {
        int size = order.size();
        homeCount = clampHomeCount(homeCount, size);
        if (index < 0 || size == 0) return new MoveResult(homeCount, Math.max(0, index));
        int row = rowOf(index, homeCount);
        if (homeCount == 0 && row == 0) {
            // No home row yet — pushing the top-row app up turns it into the
            // first home favourite (inverse of demoting the last home app).
            T app = order.remove(index);
            order.add(0, app);
            return new MoveResult(1, 0);
        }
        if (row == 0) return new MoveResult(homeCount, index);   // home app at top → nothing above

        int base = nonHomeBaseRow(homeCount);
        if (row == base && homeCount < COLS) {
            // PROMOTE: pull out and append to the end of the home segment.
            T app = order.remove(index);
            int dest = homeCount;            // first slot just past the current home segment
            order.add(dest, app);
            return new MoveResult(homeCount + 1, dest);
        }
        // Either the home row is full (row == base, homeCount == COLS) or this
        // is a lower row: a straight vertical swap with the cell directly above
        // (same column). For the full-home-row case this means the moved app
        // takes the home slot above it and the replaced home app drops into the
        // slot the moved app vacated.
        // Ordinary vertical swap with the cell directly above (same column).
        int col = colOf(index, homeCount);
        int aboveLen = rowLength(row - 1, size, homeCount);
        int targetCol = Math.min(col, aboveLen - 1);
        int target = indexAt(row - 1, targetCol, size, homeCount);
        if (target >= 0 && target != index) {
            Collections.swap(order, index, target);
            return new MoveResult(homeCount, target);
        }
        return new MoveResult(homeCount, index);
    }

    /**
     * Move the dragged app DOWN one row.
     *
     * <ul>
     *   <li><b>Home row</b> (row 0, {@code homeCount > 0}): DEMOTE. The app
     *       leaves the home segment and becomes the first non-home app;
     *       {@code homeCount--}. (It stays in the drawer — only its home
     *       membership changes.)</li>
     *   <li><b>Any other row</b>: ordinary vertical swap with the cell one row
     *       down (8 positions later), snapping to the nearest existing cell
     *       when the row below is shorter. No-op on the last row.</li>
     * </ul>
     */
    static <T> MoveResult moveDown(List<T> order, int index, int homeCount) {
        int size = order.size();
        homeCount = clampHomeCount(homeCount, size);
        if (index < 0 || size == 0) return new MoveResult(homeCount, Math.max(0, index));
        int row = rowOf(index, homeCount);

        if (homeCount > 0 && row == 0) {
            // DEMOTE: move just past the (shrinking) home segment so it becomes
            // the first non-home app. Removing index then inserting at
            // homeCount-1 lands it exactly at the head of the non-home run.
            T app = order.remove(index);
            int dest = homeCount - 1;
            order.add(dest, app);
            return new MoveResult(homeCount - 1, dest);
        }
        // Ordinary vertical swap with the cell directly below (same column).
        int col = colOf(index, homeCount);
        int belowLen = rowLength(row + 1, size, homeCount);
        if (belowLen <= 0) return new MoveResult(homeCount, index); // last row
        int targetCol = Math.min(col, belowLen - 1);
        int target = indexAt(row + 1, targetCol, size, homeCount);
        if (target >= 0 && target != index) {
            Collections.swap(order, index, target);
            return new MoveResult(homeCount, target);
        }
        return new MoveResult(homeCount, index);
    }
}
