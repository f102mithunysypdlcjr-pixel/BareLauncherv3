package com.bare.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Smoke test that boots {@link LauncherActivity} on an Android device or
 * emulator and verifies the basic UI scaffolding is laid out.
 *
 * <p>Intentionally minimal — this is the safety-net the project lacked, not
 * a full UI test suite. It checks:
 * <ul>
 *     <li>The activity reaches RESUMED without crashing.</li>
 *     <li>The content view tree exists and has a non-zero size after layout.</li>
 *     <li>No leaked window state that would prevent finish() from completing.</li>
 * </ul>
 *
 * <p>This test requires an emulator/device. It is a no-op in pure JVM CI but
 * compiles in every build, so a structural change to the activity that
 * breaks construction fails the build.
 *
 * <h3>Why ActivityScenario, not ActivityTestRule</h3>
 * The previous implementation used {@code ActivityTestRule} (deprecated
 * since androidx.test 1.4) and a {@code Thread.sleep(500)} to hand-wave
 * the layout pass. Both were flake sources on the slow API-29 KVM
 * emulator we run in CI:
 * <ul>
 *     <li>{@code ActivityTestRule} starts the activity before
 *         {@code @Before} hooks complete and tears it down via deprecated
 *         lifecycle paths that occasionally race with the JUnit runner.</li>
 *     <li>{@code Thread.sleep} is a guess. On a cold KVM emulator the
 *         first measure pass can take longer than 500 ms, producing a
 *         flake; on a fast device the same 500 ms is wasted.</li>
 * </ul>
 * {@code ActivityScenario} is the modern recommended primitive and
 * {@code Instrumentation.waitForIdleSync} blocks until the main looper's
 * message queue is actually idle — both deterministic and faster. The
 * test now compiles against {@code androidx.test.core} only, so the
 * {@code androidx.test:rules} dependency is no longer needed.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class LauncherSmokeTest {

    @Test
    public void boots_andHasContentView() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            // Block until the main looper has drained — by the time this
            // returns, onResume has run and the first layout pass has
            // either completed or been queued. A second drain after
            // requesting a layout is not needed because onActivityAction
            // already pumps to RESUMED before returning the scenario.
            getInstrumentation().waitForIdleSync();

            scenario.onActivity(a -> {
                assertNotNull("LauncherActivity should be created", a);
                View root = a.findViewById(android.R.id.content);
                assertNotNull("content view present", root);
                // First measure / layout passes have run by now. If the
                // root still reports zero width / height, something
                // structural has broken — exactly the regression this
                // smoke test exists to catch.
                assertTrue("content view laid out (width > 0)",
                        root.getWidth() > 0);
                assertTrue("content view laid out (height > 0)",
                        root.getHeight() > 0);
            });
        }
    }
}
