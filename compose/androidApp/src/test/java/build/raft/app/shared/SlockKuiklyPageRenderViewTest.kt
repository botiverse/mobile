package build.raft.app.shared

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockKuiklyPageRenderViewTest {

    @Test
    fun childRootSizeChangeIsIgnoredUntilChildIsAttached() {
        assertFalse(
            shouldNotifySlockKuiklyChildRootSizeChange(
                childAttached = false,
                previousWidthPx = null,
                previousHeightPx = null,
                nextWidthPx = 1080,
                nextHeightPx = 2215
            )
        )
    }

    @Test
    fun childRootSizeChangeRequiresPositiveSize() {
        assertFalse(
            shouldNotifySlockKuiklyChildRootSizeChange(
                childAttached = true,
                previousWidthPx = 1080,
                previousHeightPx = 2215,
                nextWidthPx = 1080,
                nextHeightPx = 0
            )
        )
    }

    @Test
    fun childRootSizeChangeIsDeduplicated() {
        assertFalse(
            shouldNotifySlockKuiklyChildRootSizeChange(
                childAttached = true,
                previousWidthPx = 1080,
                previousHeightPx = 2215,
                nextWidthPx = 1080,
                nextHeightPx = 2215
            )
        )
    }

    @Test
    fun attachedChildRootSizeChangeNotifiesWhenHostSizeDiffers() {
        assertTrue(
            shouldNotifySlockKuiklyChildRootSizeChange(
                childAttached = true,
                previousWidthPx = 1080,
                previousHeightPx = 2215,
                nextWidthPx = 1080,
                nextHeightPx = 2460
            )
        )
    }

    @Test
    fun rootViewSizeEventDataUsesDpValues() {
        assertEquals(
            mapOf("width" to 360f, "height" to 820f),
            slockKuiklyRootViewSizeDidChangedData(
                widthPx = 1080,
                heightPx = 2460,
                density = 3f
            )
        )
    }

    @Test
    fun childRenderVisibilityHidesInactiveNativeLayer() {
        assertEquals(View.VISIBLE, slockKuiklyChildRenderVisibility(visible = true))
        assertEquals(View.INVISIBLE, slockKuiklyChildRenderVisibility(visible = false))
    }

    @Test
    fun childRenderVisibilityFollowsActiveAttachedState() {
        assertTrue(slockKuiklyChildShouldRender(childActive = true, childAttached = true))
        assertFalse(slockKuiklyChildShouldRender(childActive = false, childAttached = true))
        assertFalse(slockKuiklyChildShouldRender(childActive = true, childAttached = false))
    }

    @Test
    fun startupHoldOnlyCoversAnActiveUnresolvedChild() {
        assertTrue(
            slockKuiklyChildStartupHoldVisible(
                childActive = true,
                childLoadFinished = false,
                pageName = "compose_app_shell_child_tab"
            )
        )
        assertFalse(
            slockKuiklyChildStartupHoldVisible(
                childActive = false,
                childLoadFinished = false,
                pageName = "compose_app_shell_child_tab"
            )
        )
        assertFalse(
            slockKuiklyChildStartupHoldVisible(
                childActive = true,
                childLoadFinished = true,
                pageName = "compose_app_shell_child_tab"
            )
        )
        assertFalse(
            slockKuiklyChildStartupHoldVisible(
                childActive = true,
                childLoadFinished = false,
                pageName = ""
            )
        )
    }

    @Test
    fun childInitCanBeScheduledAfterDetachedResetWithoutWaitingForAnotherLayout() {
        assertTrue(
            shouldScheduleSlockKuiklyChildInit(
                destroyed = false,
                childAttached = false,
                childInitPosted = false,
                pageName = "compose_app_shell_child_tab",
                widthPx = 1170,
                heightPx = 2215
            )
        )
        assertFalse(
            shouldScheduleSlockKuiklyChildInit(
                destroyed = false,
                childAttached = true,
                childInitPosted = false,
                pageName = "compose_app_shell_child_tab",
                widthPx = 1170,
                heightPx = 2215
            )
        )
        assertFalse(
            shouldScheduleSlockKuiklyChildInit(
                destroyed = false,
                childAttached = false,
                childInitPosted = false,
                pageName = "compose_app_shell_child_tab",
                widthPx = 0,
                heightPx = 2215
            )
        )
    }

    @Test
    fun childLifecycleVisibilityRequiresRenderedAndAppearedState() {
        assertTrue(
            slockKuiklyChildLifecycleVisible(
                childActive = true,
                childAttached = true,
                viewDidAppear = true,
                pageDidAppear = true
            )
        )
        assertFalse(
            slockKuiklyChildLifecycleVisible(
                childActive = false,
                childAttached = true,
                viewDidAppear = true,
                pageDidAppear = true
            )
        )
        assertFalse(
            slockKuiklyChildLifecycleVisible(
                childActive = true,
                childAttached = true,
                viewDidAppear = false,
                pageDidAppear = true
            )
        )
        assertFalse(
            slockKuiklyChildLifecycleVisible(
                childActive = true,
                childAttached = true,
                viewDidAppear = true,
                pageDidAppear = false
            )
        )
    }
}
