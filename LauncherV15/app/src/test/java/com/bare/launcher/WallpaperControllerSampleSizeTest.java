package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for {@link WallpaperController#computeSampleSize}.
 *
 * <p>The 1.1.4 release fixed a panorama-OOM bug where the loop used
 * {@code &&} (halve while BOTH dimensions exceed the screen) instead of
 * {@code ||} (halve while EITHER does). On a wide-aspect source — say a
 * 4000 × 500 photo on a 1920 × 1080 panel — the old code exited at
 * {@code inSampleSize = 1} on the first iteration because
 * {@code srcH (500) > screenH (1080)} was already false. {@code CENTER_CROP}
 * scaled the resulting bitmap down anyway, so the bug was invisible —
 * but on a 4K panel with a high-resolution wallpaper it could push the
 * launcher process over its memory ceiling and trigger an OOM that the
 * surrounding {@code catch (OutOfMemoryError)} swallowed silently.
 *
 * <p>These tests pin the corrected behaviour: every input that does NOT
 * fit on at least one axis halves until both axes fit, and pathological
 * inputs (zero or near-zero screen dim) terminate at the {@code 0x8000}
 * cap rather than infinite-looping.
 *
 * <p>Pure JVM — no Android framework usage. Static helper extracted from
 * the original {@code WallpaperController.calcSampleSize(int,int)} for
 * exactly this purpose.
 */
public class WallpaperControllerSampleSizeTest {

    // ── Trivial / fits-already cases ─────────────────────────────────────

    @Test public void sourceSmallerThanScreen_returnsOne() {
        assertEquals(1, WallpaperController.computeSampleSize(800, 600, 1920, 1080));
    }

    @Test public void sourceEqualToScreen_returnsOne() {
        assertEquals(1, WallpaperController.computeSampleSize(1920, 1080, 1920, 1080));
    }

    @Test public void zeroSizedSource_returnsOne() {
        // Defensive — should never happen because BitmapFactory's bounds
        // pass would have rejected the source, but the helper must not
        // misbehave if it does.
        assertEquals(1, WallpaperController.computeSampleSize(0, 0, 1920, 1080));
    }

    // ── Square / proportional sources ────────────────────────────────────

    @Test public void doubleScreenSquare_halvesOnce() {
        assertEquals(2, WallpaperController.computeSampleSize(3840, 2160, 1920, 1080));
    }

    @Test public void quadrupleScreenSquare_halvesTwice() {
        assertEquals(4, WallpaperController.computeSampleSize(7680, 4320, 1920, 1080));
    }

    @Test public void slightlyOverScreen_halvesOnceToFit() {
        // 2000 × 1100 on a 1920 × 1080 panel — just barely doesn't fit
        // on either axis, so we halve once to 1000 × 550 (fits) and stop.
        assertEquals(2, WallpaperController.computeSampleSize(2000, 1100, 1920, 1080));
    }

    // ── Panorama (the 1.1.4 bug) ─────────────────────────────────────────

    @Test public void panorama_4000x500_on1080p_subsamplesUntilBothAxesFit() {
        // The exact case from the 1.1.4 changelog. Width 4000 vs screen
        // 1920 → must halve. Height 500 already fits — but the corrected
        // loop keeps halving while EITHER axis exceeds the screen:
        //   ss=1 → 4000 > 1920 → halve → ss=2
        //   ss=2 → 2000 > 1920 → halve → ss=4
        //   ss=4 → 1000 ≤ 1920 AND 125 ≤ 1080 → stop
        // → inSampleSize = 4. The buggy `&&` version returned 1 here
        // (because srcH=500 already fit on its first iteration), which
        // burned ~8 MB of bitmap memory CENTER_CROP scaled away.
        assertEquals(4, WallpaperController.computeSampleSize(4000, 500, 1920, 1080));
    }

    @Test public void extremePanorama_8000x400_on1080p_subsamplesEnoughForWidth() {
        // 8000 / 4 = 2000 (still > 1920); 8000 / 8 = 1000 ≤ 1920. Three halvings.
        assertEquals(8, WallpaperController.computeSampleSize(8000, 400, 1920, 1080));
    }

    @Test public void tallPortrait_500x4000_on1080p_subsamplesForHeight() {
        // Symmetric to the panorama case but on the H axis. 4000 / 4 =
        // 1000 ≤ 1080. Width 500 fits trivially. Two halvings.
        assertEquals(4, WallpaperController.computeSampleSize(500, 4000, 1920, 1080));
    }

    // ── 4K panel (where the OOM actually fired in production) ───────────

    @Test public void panorama_8000x500_on4kPanel_halvesEnough() {
        // Same shape on a 4K (3840 × 2160) panel: 8000 / 4 = 2000 ≤ 3840.
        // Two halvings.
        assertEquals(4, WallpaperController.computeSampleSize(8000, 500, 3840, 2160));
    }

    @Test public void hugeSource_on4kPanel_halvesEnough() {
        // 16 K × 16 K source rendered to 4 K → 16 384 / 8 = 2048 (≤ 3840).
        // Three halvings.
        assertEquals(8, WallpaperController.computeSampleSize(16_384, 16_384, 3840, 2160));
    }

    // ── Power-of-two halving stops at the right value ────────────────────

    @Test public void resultIsAlwaysAPowerOfTwo() {
        // BitmapFactory.Options.inSampleSize is documented to be rounded
        // down to the nearest power of two anyway, but our helper produces
        // exact powers of two — sanity-check across a range of inputs.
        int[] sizes = {1000, 1500, 2048, 3001, 5555, 8192, 12345, 19999};
        for (int srcW : sizes) {
            int s = WallpaperController.computeSampleSize(srcW, 1080, 1920, 1080);
            assertTrue("not power of two: src=" + srcW + " ss=" + s,
                    s > 0 && (s & (s - 1)) == 0);
        }
    }

    // ── Pathological inputs: must terminate ─────────────────────────────

    @Test public void zeroScreen_capsAtSafetyLimit() {
        // A zero screen dim is the kind of input that historically caused
        // a divide-style infinite loop. The helper short-circuits via the
        // 0x8000 cap so the call always returns. We only assert that it
        // returns a value at or below the cap — the exact value isn't a
        // user-facing contract.
        int s = WallpaperController.computeSampleSize(4000, 4000, 0, 0);
        assertTrue("must terminate at or below 0x8000, was " + s, s <= 0x8000);
        assertTrue("must be a positive power of two", s > 0 && (s & (s - 1)) == 0);
    }

    @Test public void tinyScreen_capsAtSafetyLimit() {
        // 1 × 1 screen with a normal source: same termination contract.
        int s = WallpaperController.computeSampleSize(4000, 4000, 1, 1);
        assertTrue("must terminate at or below 0x8000, was " + s, s <= 0x8000);
        assertTrue("must be a positive power of two", s > 0 && (s & (s - 1)) == 0);
    }
}
