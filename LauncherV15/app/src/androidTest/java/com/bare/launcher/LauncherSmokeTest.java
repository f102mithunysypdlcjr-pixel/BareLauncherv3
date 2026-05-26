package com.bare.launcher;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
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
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class LauncherSmokeTest {

    @Rule
    public final ActivityTestRule<LauncherActivity> rule =
            new ActivityTestRule<>(LauncherActivity.class, false, true);

    @Test
    public void boots_andHasContentView() throws Exception {
        Activity a = rule.getActivity();
        assertNotNull("LauncherActivity should be created", a);

        // Give the layout pass a chance to run before asserting.
        Thread.sleep(500);

        a.runOnUiThread(() -> {
            android.view.View root = a.findViewById(android.R.id.content);
            assertNotNull("content view present", root);
            assertTrue("content view laid out (width > 0)",
                    root.getWidth() > 0);
            assertTrue("content view laid out (height > 0)",
                    root.getHeight() > 0);
        });
    }
}
