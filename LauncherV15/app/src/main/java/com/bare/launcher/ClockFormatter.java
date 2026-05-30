package com.bare.launcher;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.Calendar;
import java.util.Locale;

/**
 * Encapsulates the home-screen clock's text formatting.
 *
 * <p>Visual brief: 12-hour wall clock with a small "AM/PM" suffix rendered
 * at ~42 % size in {@code sans-serif-thin} — Apple-TV / iOS lock-screen
 * style. The hour digits inherit the {@link android.widget.TextView}'s
 * heavy base typeface; only the AM/PM suffix overrides via spans.
 *
 * <h3>Allocation discipline</h3>
 * The launcher's clock fires once per minute (not per second — see
 * {@code CLOCK_MS} in {@code LauncherActivity}). Even at that rate, the
 * naïve "build a fresh String + a fresh SpannableStringBuilder per tick"
 * pattern produces measurable GC pressure across a multi-day uptime. This
 * class amortises every reusable allocation:
 *
 * <ul>
 *   <li>One {@link Calendar} re-used across ticks (set the time-in-millis
 *       per call, never construct a new instance).</li>
 *   <li>One {@code char[8]} backing the digit composition.</li>
 *   <li>One {@link SpannableStringBuilder} cleared and re-filled per tick.</li>
 *   <li>Three pre-built spans ({@link RelativeSizeSpan}, {@link TypefaceSpan},
 *       {@link StyleSpan}) re-applied per tick.</li>
 * </ul>
 *
 * <p>The only per-tick allocation is the tiny {@code String} produced by
 * {@link String#valueOf(char[], int, int)} when appending to the
 * {@link SpannableStringBuilder}. {@code SpannableStringBuilder} only
 * accepts {@link CharSequence} on append, so the only fully zero-alloc
 * path would be a wrapper class implementing {@link CharSequence} around
 * the digit buffer — not worth the complexity at one tick per minute.
 *
 * <p>This class is package-private and final because no consumer outside
 * the launcher needs it. Pulling it out drops ~50 lines from
 * {@code LauncherActivity} and makes the formatting logic independently
 * understandable (and trivially mockable for a future test pass).
 */
final class ClockFormatter {

    private final Calendar               cal   = Calendar.getInstance();
    private final char[]                 chars = new char[8];
    private final SpannableStringBuilder ssb   = new SpannableStringBuilder();
    private final RelativeSizeSpan       amPmSize  = new RelativeSizeSpan(0.42f);
    private final TypefaceSpan           amPmFace  = new TypefaceSpan("sans-serif-thin");
    private final StyleSpan              amPmStyle = new StyleSpan(Typeface.NORMAL);

    /** -1 sentinel = "no minute paint yet". {@link #shouldRepaint} returns
     *  {@code true} on the first call after construction or a reset. */
    private int lastShownMinute = -1;

    /** Last day-of-year shown. Tracked so a midnight rollover with the
     *  {@code showDate} prefix on triggers a repaint even though the
     *  minute number cycles back to 0. */
    private int lastShownDayOfYear = -1;

    /** Whether the most recent {@link #format} call was rendered with
     *  the date prefix. A toggle of the user's "Show clock" preference
     *  flips this — {@link #shouldRepaint} compares the requested mode
     *  to the rendered mode so the next paint runs unconditionally. */
    private boolean lastShownWithDate = false;

    /**
     * Whether {@link #format(long, boolean)} would produce a visibly
     * different string than the one currently shown. Activity uses this
     * to skip the {@link android.widget.TextView#setText} when the
     * minute has not advanced (the minute-aligned tick can fire slightly
     * off and we want to avoid a redundant invalidate).
     *
     * <p>Three repaint triggers:
     * <ul>
     *   <li>The minute number changed since the last paint — the time
     *       digits need a redraw.</li>
     *   <li>The day-of-year rolled over — only relevant when the date
     *       prefix is being rendered, but we always check it so a wall-
     *       clock correction that lands on a different day still
     *       repaints.</li>
     *   <li>The {@code showDate} mode flipped — the user just toggled
     *       "Show clock" and we need to re-render with or without the
     *       prefix.</li>
     * </ul>
     */
    boolean shouldRepaint(long ms, boolean showDate) {
        cal.setTimeInMillis(ms);
        if (showDate != lastShownWithDate) return true;
        if (cal.get(Calendar.MINUTE) != lastShownMinute) return true;
        return cal.get(Calendar.DAY_OF_YEAR) != lastShownDayOfYear;
    }

    /**
     * Reset the "last shown" sentinels so the next call to
     * {@link #shouldRepaint(long, boolean)} returns {@code true}
     * unconditionally and {@link #format(long, boolean)} will re-emit
     * the spans even if nothing actually changed. Used after
     * configuration changes (RTL flip, font scale change, locale
     * change) so the next paint redraws against the new environment.
     */
    void reset() {
        lastShownMinute    = -1;
        lastShownDayOfYear = -1;
        // lastShownWithDate intentionally left as-is: a reset() call
        // doesn't change the user's "Show clock" preference, only the
        // dirty-paint flags. shouldRepaint will still trigger a repaint
        // because lastShownMinute = -1 forces it.
    }

    /**
     * Build the visible clock {@link CharSequence} for the given absolute
     * time, optionally prefixed with the locale-aware short day-of-week
     * (e.g. {@code "Sat · 12:34 PM"} when {@code showDate=true}).
     *
     * <p>The day prefix uses {@link Calendar#getDisplayName} with a
     * {@link Locale#getDefault()} lookup so the rendering is correct in
     * every system language without changes to the launcher's
     * English-only resource bundle. The {@code · } separator is locale-
     * neutral.
     *
     * <p>The returned object is the internally-pooled
     * {@link SpannableStringBuilder} — callers must not mutate it and
     * should pass {@link android.widget.TextView.BufferType#SPANNABLE}
     * so the platform copies the spans. Subsequent calls will reuse
     * the same builder and overwrite its contents.
     */
    CharSequence format(long ms, boolean showDate) {
        cal.setTimeInMillis(ms);
        lastShownMinute    = cal.get(Calendar.MINUTE);
        lastShownDayOfYear = cal.get(Calendar.DAY_OF_YEAR);
        lastShownWithDate  = showDate;

        int hour = cal.get(Calendar.HOUR);
        if (hour == 0) hour = 12;
        int min  = cal.get(Calendar.MINUTE);
        int ampm = cal.get(Calendar.AM_PM);
        int pos  = 0;
        if (hour >= 10) chars[pos++] = (char) ('0' + hour / 10);
        chars[pos++] = (char) ('0' + hour % 10);
        chars[pos++] = ':';
        chars[pos++] = (char) ('0' + min / 10);
        chars[pos++] = (char) ('0' + min % 10);
        chars[pos++] = ' ';
        int amStart = pos;
        chars[pos++] = ampm == Calendar.AM ? 'A' : 'P';
        chars[pos++] = 'M';

        ssb.clear();
        ssb.clearSpans();
        // Optional date prefix. Calendar.getDisplayName allocates a small
        // String per call (locale lookups don't expose a zero-alloc API)
        // — at one tick per minute this is a single tiny String per
        // minute, far below GC pressure. Falls back gracefully if the
        // locale ever returns null (some stripped-down ROMs have been
        // observed shipping with broken DateFormatSymbols).
        if (showDate) {
            String day = cal.getDisplayName(Calendar.DAY_OF_WEEK,
                    Calendar.SHORT, Locale.getDefault());
            if (day != null && !day.isEmpty()) {
                ssb.append(day);
                ssb.append(" \u00B7 ");
            }
        }

        // String.valueOf builds a tiny throwaway String, but format() now
        // fires once per minute (not per second), so this is 1 small alloc
        // per minute — far below GC pressure.
        int timeStart = ssb.length();
        ssb.append(String.valueOf(chars, 0, pos));
        // Span offsets are relative to ssb, not to the chars buffer, so
        // shift by timeStart when the date prefix pushed the time down.
        ssb.setSpan(amPmSize,  timeStart + amStart, timeStart + pos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(amPmFace,  timeStart + amStart, timeStart + pos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(amPmStyle, timeStart + amStart, timeStart + pos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ssb;
    }

    /**
     * Compute the delay in ms from {@code now} until the next minute
     * boundary, plus a small 50 ms cushion so the tick lands just AFTER
     * {@code :00} rather than just before. The 50 ms guard avoids a tick
     * firing twice in the same minute on a slightly-fast wall clock —
     * the minute-aligned scheduling pattern that powers the launcher's
     * once-per-minute clock loop.
     */
    static long nextMinuteDelay(long now) {
        return 60_000L - (now % 60_000L) + 50L;
    }
}
