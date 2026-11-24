package build.raft.app.shared

import android.app.Application
import android.graphics.Insets
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.WindowInsets
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftCmpActivityWindowInsetsTest {

    @Before
    @After
    fun resetLastKnownInsets() {
        RaftCmpActivity.lastKnownStatusBarTopPx = 0
        RaftCmpActivity.lastKnownNavigationBarBottomPx = 0
        RaftCmpActivity.lastKnownSafeAreaLeftPx = 0
        RaftCmpActivity.lastKnownSafeAreaRightPx = 0
    }

    @Test
    @Config(sdk = [30, 34])
    fun api30LandscapeCutoutLeftAndNavBarRightAreExtractedAndExcludeImeAndGestures() {
        val insets =
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 72, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 126, 0))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(108, 0, 0, 0))
                // Gesture back-swipe side margins must NOT be treated as horizontal safe area.
                .setInsets(WindowInsets.Type.systemGestures(), Insets.of(60, 0, 60, 48))
                // Open IME must NOT leak into vertical or horizontal safe areas.
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 680))
                .setVisible(WindowInsets.Type.statusBars(), true)
                .build()

        val extracted = extractAndroidRuntimeWindowInsets(insets, sdkInt = 34)
        assertEquals(
            AndroidRuntimeWindowInsets(
                statusBarHeightPx = 72,
                navigationBarHeightPx = 48,
                safeAreaLeftPx = 108,
                safeAreaRightPx = 126,
                statusBarHidden = false,
            ),
            extracted,
        )
    }

    @Test
    @Config(sdk = [30, 34])
    fun api30LandscapeCutoutRightAndNavBarLeftSwapCleanlyOnRotation() {
        val rotated90 =
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 72, 0, 0))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(108, 0, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 126, 0))
                .setVisible(WindowInsets.Type.statusBars(), true)
                .build()
        val rotated270 =
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 72, 0, 0))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, 0, 108, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(126, 0, 0, 0))
                .setVisible(WindowInsets.Type.statusBars(), true)
                .build()
        val portrait =
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 96, 0, 0))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, 96, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 63))
                .setInsets(WindowInsets.Type.systemGestures(), Insets.of(60, 0, 60, 63))
                .setVisible(WindowInsets.Type.statusBars(), true)
                .build()

        val r90 = extractAndroidRuntimeWindowInsets(rotated90, sdkInt = 34)
        val r270 = extractAndroidRuntimeWindowInsets(rotated270, sdkInt = 34)
        val p0 = extractAndroidRuntimeWindowInsets(portrait, sdkInt = 34)

        assertEquals(108, r90.safeAreaLeftPx)
        assertEquals(126, r90.safeAreaRightPx)
        assertEquals(126, r270.safeAreaLeftPx)
        assertEquals(108, r270.safeAreaRightPx)
        assertEquals(0, p0.safeAreaLeftPx)
        assertEquals(0, p0.safeAreaRightPx)
        assertNotEquals(r90.publishKey(), r270.publishKey())
        assertNotEquals(r270.publishKey(), p0.publishKey())

        // Verify pageData updates clear stale horizontal keys across 90 -> 270 -> 0.
        val json = JSONObject()
        applyAndroidRuntimeWindowInsets(json, hostExcludesNavigationBar = false, hostWindowId = "win:1", insets = r90)
        assertEquals("108", json.optString("safeAreaLeft"))
        assertEquals("126", json.optString("safeAreaRight"))

        applyAndroidRuntimeWindowInsets(json, hostExcludesNavigationBar = false, hostWindowId = "win:1", insets = r270)
        assertEquals("126", json.optString("safeAreaLeft"))
        assertEquals("108", json.optString("safeAreaRight"))

        applyAndroidRuntimeWindowInsets(json, hostExcludesNavigationBar = false, hostWindowId = "win:1", insets = p0)
        assertFalse(json.has("safeAreaLeft"))
        assertFalse(json.has("safeAreaRight"))
        assertEquals("96", json.optString("safeAreaTop"))
        assertEquals("63", json.optString("safeAreaBottom"))
    }

    @Test
    @Config(sdk = [30, 34])
    fun api30ImmersiveHiddenStatusBarStillPreservesPhysicalCutout() {
        val insets =
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 0, 0, 0))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(108, 0, 0, 0))
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 0))
                .setVisible(WindowInsets.Type.statusBars(), false)
                .build()

        val extracted = extractAndroidRuntimeWindowInsets(insets, sdkInt = 34)
        assertTrue(extracted.statusBarHidden)
        assertEquals(0, extracted.statusBarHeightPx)
        assertEquals(108, extracted.safeAreaLeftPx)
        assertEquals(0, extracted.safeAreaRightPx)
    }

    @Test
    @Config(sdk = [29])
    fun api29CombinesSystemWindowAndDisplayCutoutAndExcludesImeBottom() {
        val cutout = DisplayCutout(Insets.of(108, 0, 0, 0), Rect(0, 200, 108, 300), null, null, null)
        @Suppress("DEPRECATION")
        val insets =
            WindowInsets.Builder()
                // Landscape with cutout left, 3-button nav bar right, and IME open on bottom.
                .setSystemWindowInsets(Insets.of(0, 72, 126, 620))
                .setStableInsets(Insets.of(0, 72, 126, 0))
                .setDisplayCutout(cutout)
                .build()

        val extracted = extractAndroidRuntimeWindowInsets(insets, sdkInt = 29, legacyStatusBarVisible = true)
        assertEquals(
            AndroidRuntimeWindowInsets(
                statusBarHeightPx = 72,
                navigationBarHeightPx = 0,
                safeAreaLeftPx = 108,
                safeAreaRightPx = 126,
                statusBarHidden = false,
            ),
            extracted,
        )
    }

    @Test
    fun coldStartAllZeroOmitsInsetKeysToPreserveFrameOneFallback() {
        val json = JSONObject()
        applyAndroidRuntimeWindowInsets(
            json = json,
            hostExcludesNavigationBar = false,
            hostWindowId = "win:cold",
            insets = AndroidRuntimeWindowInsets(0, 0, 0, 0, statusBarHidden = false),
        )
        assertFalse(json.has("statusBarHeight"))
        assertFalse(json.has("safeAreaTop"))
        assertFalse(json.has("navigationBarHeight"))
        assertFalse(json.has("safeAreaBottom"))
        assertFalse(json.has("safeAreaLeft"))
        assertFalse(json.has("safeAreaRight"))
        assertEquals("win:cold", json.optString(RaftCmpActivity.KEY_HOST_WINDOW_ID))
    }

    @Test
    fun pushedActivitySeedsFirstFrameFromLastKnownHorizontalSafeAreas() {
        RaftCmpActivity.lastKnownStatusBarTopPx = 72
        RaftCmpActivity.lastKnownNavigationBarBottomPx = 0
        RaftCmpActivity.lastKnownSafeAreaLeftPx = 108
        RaftCmpActivity.lastKnownSafeAreaRightPx = 126

        val seeded = lastKnownAndroidRuntimeWindowInsets(statusBarHidden = false)
        assertEquals(108, seeded.safeAreaLeftPx)
        assertEquals(126, seeded.safeAreaRightPx)

        val json = JSONObject()
        applyAndroidRuntimeWindowInsets(json, hostExcludesNavigationBar = false, hostWindowId = "win:pushed", insets = seeded)
        assertEquals("72", json.optString("safeAreaTop"))
        assertEquals("108", json.optString("safeAreaLeft"))
        assertEquals("126", json.optString("safeAreaRight"))
        assertFalse(json.has("safeAreaBottom"))

        val payload = androidWindowInsetsChangedPayload("win:pushed", seeded)
        assertEquals("108", payload["safeAreaLeftPx"])
        assertEquals("126", payload["safeAreaRightPx"])
        assertEquals("72", payload["statusBarHeightPx"])
        assertEquals("0", payload["navigationBarHeightPx"])
    }
}
