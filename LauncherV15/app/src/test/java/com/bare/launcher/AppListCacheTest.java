package com.bare.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * JVM unit tests for {@link AppListCache}'s parse / serialise core.
 *
 * <p>The Android-side file I/O wrappers ({@code readFile}, {@code writeFile})
 * are exercised by the smoke test on a device. These tests cover the
 * pure-Java text format that survives across cold starts: header
 * validation, version mismatch handling, line-based entry round-trip,
 * label sanitisation, and corruption resilience.
 */
public class AppListCacheTest {

    /** Tiny in-memory {@link AppListCache.Entry} for the round-trip tests.
     *  AppInfo cannot be used directly because its constructor accepts an
     *  Android {@code ComponentName}, which is not available in JVM tests. */
    private static final class E implements AppListCache.Entry {
        private final String pkg, label, cls;
        E(String p, String l, String c) { pkg = p; label = l; cls = c; }
        @Override public String pkg()           { return pkg; }
        @Override public String label()         { return label; }
        @Override public String activityClass() { return cls; }
    }

    /** Visitor that captures emitted entries into a {@link LinkedHashMap}
     *  keyed by package — preserves emission order for assertions. */
    private static Map<String, String[]> capture(String contents) {
        LinkedHashMap<String, String[]> out = new LinkedHashMap<>();
        boolean ok = AppListCache.parse(contents,
                (pkg, label, cls) -> out.put(pkg, new String[]{label, cls}));
        // Mirror parse's "all-or-nothing" contract via the return value
        // — if parse() returned false the visitor was never called, so
        // out is empty and the assertion is on whichever side the test
        // is checking.
        if (!ok && !out.isEmpty()) {
            throw new AssertionError("parse returned false but visitor was called");
        }
        return out;
    }

    // ─── parse: rejection paths ──────────────────────────────────────────

    @Test public void parse_null_returnsFalse() {
        Map<String, String[]> out = capture(null);
        assertTrue(out.isEmpty());
    }

    @Test public void parse_empty_returnsFalse() {
        Map<String, String[]> out = capture("");
        assertTrue(out.isEmpty());
    }

    @Test public void parse_badMagic_returnsFalse() {
        // Different first line → file written by something else (or a
        // future BareLauncher format we don't recognise). Reject.
        String contents = "WHAT\n1\n0\n";
        Map<String, String[]> out = capture(contents);
        assertTrue(out.isEmpty());
    }

    @Test public void parse_unsupportedVersion_returnsFalse() {
        // Version 999 — a future on-disk shape we cannot read.
        String contents = AppListCache.MAGIC + "\n999\n0\n";
        Map<String, String[]> out = capture(contents);
        assertTrue(out.isEmpty());
    }

    @Test public void parse_negativeCount_returnsFalse() {
        String contents = AppListCache.MAGIC + "\n" + AppListCache.VERSION + "\n-1\n";
        Map<String, String[]> out = capture(contents);
        assertTrue(out.isEmpty());
    }

    @Test public void parse_truncatedHeader_returnsFalse() {
        // No newline after magic — file truncated mid-header.
        String contents = AppListCache.MAGIC;
        Map<String, String[]> out = capture(contents);
        assertTrue(out.isEmpty());
    }

    @Test public void parse_truncatedEntry_returnsFalseAndEmitsNothing() {
        // count=2 but only 1 entry written — process killed mid-write.
        // Parser must reject the whole file (NOT emit the partial first
        // entry); otherwise the user would see a half-populated shelf.
        String contents = AppListCache.MAGIC + "\n" + AppListCache.VERSION + "\n2\n"
                + "com.app.one\nApp One\ncom.app.one.Main\n";
        Map<String, String[]> out = capture(contents);
        assertTrue("partial cache must produce zero entries", out.isEmpty());
    }

    @Test public void parse_emptyPackage_returnsFalse() {
        // pkg field empty → invalid entry → reject whole file.
        String contents = AppListCache.MAGIC + "\n" + AppListCache.VERSION + "\n1\n"
                + "\nApp One\ncom.app.one.Main\n";
        Map<String, String[]> out = capture(contents);
        assertTrue(out.isEmpty());
    }

    @Test public void parse_emptyClass_returnsFalse() {
        String contents = AppListCache.MAGIC + "\n" + AppListCache.VERSION + "\n1\n"
                + "com.app.one\nApp One\n\n";
        Map<String, String[]> out = capture(contents);
        assertTrue(out.isEmpty());
    }

    @Test public void parse_emptyLabel_returnsFalse() {
        // Empty label is rejected — without a user-visible name the cell
        // is unidentifiable. Better to fall through to a fresh PM scan
        // that may have repaired the label.
        String contents = AppListCache.MAGIC + "\n" + AppListCache.VERSION + "\n1\n"
                + "com.app.one\n\ncom.app.one.Main\n";
        Map<String, String[]> out = capture(contents);
        assertTrue(out.isEmpty());
    }

    // ─── parse: success paths ────────────────────────────────────────────

    @Test public void parse_singleEntry_emitted() {
        String contents = AppListCache.MAGIC + "\n" + AppListCache.VERSION + "\n1\n"
                + "com.app.one\nApp One\ncom.app.one.MainActivity\n";
        Map<String, String[]> out = capture(contents);
        assertEquals(1, out.size());
        String[] e = out.get("com.app.one");
        assertEquals("App One", e[0]);
        assertEquals("com.app.one.MainActivity", e[1]);
    }

    @Test public void parse_multipleEntries_emittedInOrder() {
        String contents = AppListCache.MAGIC + "\n" + AppListCache.VERSION + "\n3\n"
                + "a.app\nA App\na.app.Main\n"
                + "b.app\nB App\nb.app.Main\n"
                + "c.app\nC App\nc.app.Main\n";
        Map<String, String[]> out = capture(contents);
        assertEquals(3, out.size());
        // LinkedHashMap preserves emission order; assert the keys come
        // out in the file order. The shelf renders in this order, so it
        // matters.
        String[] keys = out.keySet().toArray(new String[0]);
        assertEquals("a.app", keys[0]);
        assertEquals("b.app", keys[1]);
        assertEquals("c.app", keys[2]);
    }

    @Test public void parse_windowsLineEndings_tolerated() {
        // \r\n line endings produced by some test harnesses or by a
        // future text-editor inspection. The parser strips a trailing
        // \r before consuming the line.
        String contents = AppListCache.MAGIC + "\r\n" + AppListCache.VERSION + "\r\n1\r\n"
                + "com.app\r\nLabel\r\ncom.app.Main\r\n";
        Map<String, String[]> out = capture(contents);
        assertEquals(1, out.size());
        assertEquals("Label", out.get("com.app")[0]);
    }

    @Test public void parse_zeroCount_emitsNothingButReturnsFalse() {
        // count=0 is technically a "valid" structural file, but the
        // reader's contract is "true means we have at least one entry
        // to render"; an empty cache is functionally identical to no
        // cache at all, so it returns false.
        String contents = AppListCache.MAGIC + "\n" + AppListCache.VERSION + "\n0\n";
        boolean[] called = {false};
        boolean ok = AppListCache.parse(contents, (p, l, c) -> called[0] = true);
        assertFalse("zero entries should return false", ok);
        assertFalse("visitor must not be called for zero-count file", called[0]);
    }

    // ─── serialize ────────────────────────────────────────────────────

    @Test public void serialize_null_emptyString() {
        assertEquals("", AppListCache.serialize(null));
    }

    @Test public void serialize_empty_emptyString() {
        assertEquals("", AppListCache.serialize(Collections.emptyList()));
    }

    @Test public void serialize_skipsNullAndIncompleteEntries() {
        ArrayList<AppListCache.Entry> entries = new ArrayList<>();
        entries.add(new E("good", "Good", "good.Main"));
        entries.add(null);
        entries.add(new E(null,    "X",   "x.Main"));
        entries.add(new E("",      "X",   "x.Main"));
        entries.add(new E("p",     null,  "p.Main"));
        entries.add(new E("p2",    "",    "p.Main"));
        entries.add(new E("p3",    "P3",  null));
        entries.add(new E("p4",    "P4",  ""));
        entries.add(new E("ok2",   "Ok2", "ok2.Main"));

        String s = AppListCache.serialize(entries);
        // Round-trip: parse the produced text and verify only the
        // valid entries survived.
        Map<String, String[]> out = capture(s);
        assertEquals(2, out.size());
        assertEquals("Good", out.get("good")[0]);
        assertEquals("Ok2",  out.get("ok2")[0]);
    }

    @Test public void serialize_sanitisesNewlinesInLabels() {
        // Real-world labels never contain newlines, but a malformed
        // <application> manifest can produce one. The parser is line-
        // based; an unescaped \n would desynchronise it. Sanitisation
        // replaces with a space.
        String s = AppListCache.serialize(Collections.singletonList(
                new E("p", "Line1\nLine2", "p.Main")));
        // The serialised text must still parse cleanly, and the
        // sanitised label round-trips with a space substitution.
        Map<String, String[]> out = capture(s);
        assertEquals(1, out.size());
        assertEquals("Line1 Line2", out.get("p")[0]);
    }

    @Test public void serialize_sanitisesCarriageReturnsInLabels() {
        String s = AppListCache.serialize(Collections.singletonList(
                new E("p", "Line1\rLine2", "p.Main")));
        Map<String, String[]> out = capture(s);
        assertEquals("Line1 Line2", out.get("p")[0]);
    }

    @Test public void serialize_sanitisesMixedCRLF() {
        // \r\n in a label → two replacements → "Line1  Line2".
        String s = AppListCache.serialize(Collections.singletonList(
                new E("p", "Line1\r\nLine2", "p.Main")));
        Map<String, String[]> out = capture(s);
        assertEquals("Line1  Line2", out.get("p")[0]);
    }

    // ─── round-trips ────────────────────────────────────────────────────

    @Test public void roundTrip_typicalAppList() {
        ArrayList<AppListCache.Entry> entries = new ArrayList<>();
        entries.add(new E("com.android.tv.settings", "Settings", "com.android.tv.settings.MainActivity"));
        entries.add(new E("com.netflix.ninja",       "Netflix",  "com.netflix.ninja.MainActivity"));
        entries.add(new E("com.spotify.tv.android",  "Spotify",  "com.spotify.tv.android.SpotifyTVActivity"));
        entries.add(new E("org.videolan.vlc",        "VLC",      "org.videolan.vlc.gui.video.VideoPlayerActivity"));

        String s = AppListCache.serialize(entries);
        Map<String, String[]> out = capture(s);
        assertEquals(4, out.size());
        // Verify content fidelity for each entry.
        assertEquals("Netflix", out.get("com.netflix.ninja")[0]);
        assertEquals("com.netflix.ninja.MainActivity", out.get("com.netflix.ninja")[1]);
        assertEquals("VLC", out.get("org.videolan.vlc")[0]);
    }

    @Test public void roundTrip_unicodeLabels() {
        // Real apps ship CJK / RTL labels. The parser uses the platform's
        // String semantics for indexOf('\n') which is char-based — every
        // BMP code point is one char so comparisons are correct. Multi-
        // BMP code points (e.g. emoji surrogates) split across two chars
        // but neither half is \n, so they survive intact.
        ArrayList<AppListCache.Entry> entries = new ArrayList<>();
        entries.add(new E("jp.app",   "アプリ",       "jp.app.Main"));
        entries.add(new E("zh.app",   "应用",         "zh.app.Main"));
        entries.add(new E("ar.app",   "تطبيق",       "ar.app.Main"));
        entries.add(new E("emoji",    "🚀 Launcher", "emoji.Main"));

        String s = AppListCache.serialize(entries);
        Map<String, String[]> out = capture(s);
        assertEquals(4, out.size());
        assertEquals("アプリ",       out.get("jp.app")[0]);
        assertEquals("应用",         out.get("zh.app")[0]);
        assertEquals("تطبيق",       out.get("ar.app")[0]);
        assertEquals("🚀 Launcher", out.get("emoji")[0]);
    }

    @Test public void roundTrip_pipeDelimiterCharsInLabels() {
        // Earlier draft formats used pipe as a field separator, which
        // would have required escaping any pipe in a label. The shipped
        // line-based format has no field separator so labels containing
        // pipes survive without any escaping.
        String s = AppListCache.serialize(Collections.singletonList(
                new E("p", "Foo|Bar|Baz", "p.Main")));
        Map<String, String[]> out = capture(s);
        assertEquals("Foo|Bar|Baz", out.get("p")[0]);
    }

    @Test public void roundTrip_largeAppList() {
        // Synthesise 200 entries — well above any real install but
        // exercises the StringBuilder growth path and confirms no
        // off-by-one in the count parser at sizes > 100.
        ArrayList<AppListCache.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            entries.add(new E("com.app." + i, "App " + i, "com.app." + i + ".Main"));
        }
        String s = AppListCache.serialize(entries);
        Map<String, String[]> out = capture(s);
        assertEquals(200, out.size());
        assertEquals("App 0",   out.get("com.app.0")[0]);
        assertEquals("App 199", out.get("com.app.199")[0]);
    }

    // ─── sanitiseLine direct tests ───────────────────────────────────────

    @Test public void sanitiseLine_null_emptyString() {
        assertEquals("", AppListCache.sanitiseLine(null));
    }

    @Test public void sanitiseLine_empty_unchanged() {
        assertEquals("", AppListCache.sanitiseLine(""));
    }

    @Test public void sanitiseLine_noWhitespace_returnsSameInstance() {
        // Fast-path: when the input contains no \r or \n, we return the
        // original String reference without allocating. Subtle but
        // matters for cold-start GC pressure (every label is sanitised).
        String input = "Hello World";
        String result = AppListCache.sanitiseLine(input);
        // == not equals — fast-path returns the SAME instance.
        assertTrue("fast-path should return identical instance",
                result == input);
    }

    @Test public void sanitiseLine_internalNewline_replacedWithSpace() {
        assertEquals("Foo Bar", AppListCache.sanitiseLine("Foo\nBar"));
    }

    @Test public void sanitiseLine_leadingNewline_replacedWithSpace() {
        assertEquals(" Foo", AppListCache.sanitiseLine("\nFoo"));
    }

    @Test public void sanitiseLine_trailingNewline_replacedWithSpace() {
        assertEquals("Foo ", AppListCache.sanitiseLine("Foo\n"));
    }
}
