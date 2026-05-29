package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM unit tests for {@link ClockFormatter#nextMinuteDelay(long)}.
 *
 * <p>The format / span pipeline cannot be exercised in pure JVM tests
 * because it touches Android-framework types ({@code SpannableStringBuilder},
 * {@code TypefaceSpan}, {@code RelativeSizeSpan}). The minute-boundary
 * scheduling math is pure-JDK arithmetic, so it is testable here — and
 * is exactly the function whose invariants ("never schedule before the
 * next minute boundary, never schedule more than 60 s away") govern
 * whether the home-screen clock can fire twice in the same minute on
 * a slightly-fast wall clock.
 */
public class ClockFormatterTest {

    private static final long MIN = 60_000L;
    private static final long CUSHION = 50L;

    @Test public void nextMinuteDelay_atEpoch_isAFullMinutePlusCushion() {
        // 0 ms is exactly a minute boundary — the next boundary is 60 000
        // ms later, plus the 50 ms cushion that lands us safely AFTER the
        // boundary rather than before.
        assertEquals(MIN + CUSHION, ClockFormatter.nextMinuteDelay(0L));
    }

    @Test public void nextMinuteDelay_justBeforeBoundary_isCushionPlusRemainder() {
        // 100 ms before the next minute → 100 ms + 50 ms cushion = 150 ms.
        long now = MIN - 100L;
        assertEquals(150L, ClockFormatter.nextMinuteDelay(now));
    }

    @Test public void nextMinuteDelay_oneMillisecondBeforeBoundary_isOnePlusCushion() {
        // 1 ms before the next minute → 1 + 50 = 51 ms. Tightest case
        // where the +50 cushion meaningfully prevents firing twice in
        // the same minute on a slightly-fast wall clock.
        long now = MIN - 1L;
        assertEquals(1L + CUSHION, ClockFormatter.nextMinuteDelay(now));
    }

    @Test public void nextMinuteDelay_atBoundary_isFullMinutePlusCushion() {
        // At a minute boundary (e.g. exactly :00.000), the next tick is
        // a full minute later — NOT zero, which would re-fire immediately.
        long now = 60 * MIN;
        assertEquals(MIN + CUSHION, ClockFormatter.nextMinuteDelay(now));
    }

    @Test public void nextMinuteDelay_justAfterBoundary_isAlmostAFullMinute() {
        // 1 ms past the boundary → next tick is (60 000 - 1) ms + 50 ms
        // cushion = 60 049 ms.
        long now = 60 * MIN + 1L;
        assertEquals(MIN - 1L + CUSHION, ClockFormatter.nextMinuteDelay(now));
    }

    @Test public void nextMinuteDelay_arbitraryEpochSecond() {
        // Real-world style: 17:23:42.731 = 1 064 622 731 ms past some epoch.
        // The result should land at the next :24:00.050 boundary.
        long now = 17 * 3600_000L + 23 * MIN + 42_731L;
        // Time since previous minute boundary = 42 731 ms.
        // Distance to next boundary = 60 000 - 42 731 = 17 269 ms.
        // Plus 50 ms cushion = 17 319 ms.
        assertEquals(17_319L, ClockFormatter.nextMinuteDelay(now));
    }

    @Test public void nextMinuteDelay_alwaysWithinOneMinuteWindow() {
        // The contract: returned delay is always strictly greater than the
        // 50 ms cushion (we never fire BEFORE the boundary) and strictly
        // less than 60 000 + 50 + 1 (we never schedule more than one full
        // minute out, even at the boundary edge case). Exercise across a
        // dense range of input millis to catch any boundary-handling
        // regression.
        for (long now = 0; now < 5 * MIN; now += 250L) {
            long d = ClockFormatter.nextMinuteDelay(now);
            assertTrue("delay must be > 0 at now=" + now, d > 0);
            assertTrue("delay must be <= MIN+CUSHION at now=" + now,
                    d <= MIN + CUSHION);
        }
    }

    @Test public void nextMinuteDelay_landsAtOrAfterBoundary() {
        // After waiting `delay` ms we must be AT or AFTER the next minute
        // boundary — never before. The 50 ms cushion is what enforces
        // this; without it a slightly-fast wall clock could fire twice
        // in the same minute.
        long now = MIN - 1L;
        long delay = ClockFormatter.nextMinuteDelay(now);
        long arriveAt = now + delay;
        // arriveAt must be in the NEXT minute (>= MIN), not the current.
        assertTrue("arrival " + arriveAt + " must be >= " + MIN, arriveAt >= MIN);
    }
}
