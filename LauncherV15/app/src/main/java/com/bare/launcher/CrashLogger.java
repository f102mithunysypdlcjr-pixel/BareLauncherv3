package com.bare.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Tiny zero-dependency crash sink for production builds.
 *
 * <p>Without third-party crash reporting (Firebase Crashlytics, Sentry,
 * etc. — all of which would violate the launcher's "zero external
 * dependencies in the production APK" rule), the only signal a developer
 * gets when a TV ROM kills the launcher is a one-line logcat trace that
 * is nearly impossible for a remote user to capture. This sink:
 *
 * <ol>
 *   <li>Hooks {@link Thread#setDefaultUncaughtExceptionHandler} on
 *       activity create.</li>
 *   <li>On any uncaught exception on any thread, writes a timestamped
 *       full stack trace to {@code <internalFiles>/crash.log} (truncating
 *       to the last 32 KB so the file never grows unbounded).</li>
 *   <li>Logs the same trace to logcat at {@code ERROR}.</li>
 *   <li>Delegates back to the previously-installed handler so Android's
 *       default "process died" path still runs (system shows the home
 *       picker, etc.).</li>
 * </ol>
 *
 * <p>The crash log is plain text. A user reporting an issue can pull the
 * file off the device with {@code adb pull
 * /data/data/com.bare.launcher/files/crash.log} (rooted / dev devices)
 * or share the text via a third-party file-manager app for support.
 *
 * <p>Pure-JDK / Android-framework only. No library dependency, no
 * background thread (the write happens synchronously on the crashing
 * thread before propagation), no IO on a healthy run.
 */
final class CrashLogger {

    private static final String TAG       = "BareLauncher";
    /** Filename inside {@link Context#getFilesDir()}. */
    private static final String FILE_NAME = "crash.log";
    /** Cap the on-disk log so a crash loop cannot fill the data partition.
     *  32 KB easily holds ~50 typical Android stack traces; the log
     *  rotates by truncating from the front in {@link #append}. */
    private static final long   MAX_BYTES = 32L * 1024L;

    /** Pre-built immutable formatter for the per-trace timestamp.
     *  {@link DateTimeFormatter} is thread-safe (unlike
     *  {@link java.text.SimpleDateFormat} which would have to be
     *  thread-locally instantiated or synchronised). Available since
     *  API 26 (our minSdk floor). UTC zone keeps the timestamp
     *  unambiguous in shared crash logs across timezones. */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS'Z'")
            .withZone(ZoneId.of("UTC"));

    private CrashLogger() { /* no instances */ }

    /**
     * Install the default uncaught-exception handler. Idempotent: a second
     * call detects an already-installed BareLauncher handler and is a
     * no-op (so the activity can call this from {@code onCreate} on
     * every config-change rebirth without piling up handlers).
     */
    static void install(final Context appContext) {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof BareLauncherHandler) return;

        // Snapshot the application context so the handler can write files
        // without a reference to a (potentially destroyed) activity.
        final Context ctx = appContext.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler(new BareLauncherHandler(ctx, prev));
    }

    /** Marker class so {@link #install} can recognise its own handler
     *  on subsequent calls and skip re-installation. */
    private static final class BareLauncherHandler implements Thread.UncaughtExceptionHandler {
        private final Context                          ctx;
        private final Thread.UncaughtExceptionHandler  next;
        BareLauncherHandler(Context ctx, Thread.UncaughtExceptionHandler next) {
            this.ctx = ctx; this.next = next;
        }
        @Override public void uncaughtException(Thread t, Throwable e) {
            try {
                Log.e(TAG, "Uncaught on thread " + t.getName(), e);
                append(ctx, t, e);
            } catch (Throwable ignored) {
                // Logging a crash must NEVER itself crash. If anything
                // throws here we swallow and fall through to the previous
                // handler so the system's default "process died" path
                // still runs. The user-visible behaviour matches what
                // they would have seen without this handler installed.
            }
            // Hand off so the platform's "kill the process" path still runs.
            // Without this, Android can hang the activity in a half-dead
            // state on some TV ROMs instead of cleanly handing back to the
            // system home picker.
            if (next != null) next.uncaughtException(t, e);
            else {
                // Last resort: the platform installs a default handler at
                // boot, but if for some reason it's null, crash the JVM
                // ourselves so the system reaps the process.
                Runtime.getRuntime().exit(2);
            }
        }
    }

    /**
     * Append a timestamped trace to the on-disk log, truncating the file
     * from the front if it would otherwise exceed {@link #MAX_BYTES}.
     * Synchronous — runs on the calling thread.
     */
    private static void append(Context ctx, Thread t, Throwable e) {
        File dir = ctx.getFilesDir();
        if (dir == null) return;
        File f = new File(dir, FILE_NAME);
        try {
            // Rotate by simple front-truncation: rename if oversize, then
            // start fresh. 32 KB is small enough that the rare crash
            // doesn't justify a circular-buffer scheme.
            if (f.exists() && f.length() > MAX_BYTES) {
                File bak = new File(dir, FILE_NAME + ".0");
                // Best-effort delete then rename. On rare filesystems
                // both operations can fail; we just continue.
                if (bak.exists() && !bak.delete()) {
                    // not fatal — the new file will overwrite below
                }
                if (!f.renameTo(bak)) {
                    // best-effort
                }
            }
            try (PrintWriter pw = new PrintWriter(new java.io.FileWriter(f, true))) {
                pw.println("──────────────────────────────────────────");
                pw.println("time:    " + TS_FMT.format(Instant.now()));
                pw.println("thread:  " + t.getName());
                pw.println("trace:");
                e.printStackTrace(pw);
                pw.flush();
            }
        } catch (IOException | SecurityException ignored) {
            // Filesystem refused the write — we already logged to logcat
            // in the calling handler. Nothing more we can do.
        }
    }
}
