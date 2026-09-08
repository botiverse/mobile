package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockKuiklyChildPageLifecycleTest {
    @Test
    fun childIsLifecycleVisibleOnlyWhenTheViewAppearedAndTheTabIsActive() {
        assertTrue(slockChildLifecycleShouldBeVisible(viewAppeared = true, active = true))
        assertFalse(slockChildLifecycleShouldBeVisible(viewAppeared = true, active = false))
        assertFalse(slockChildLifecycleShouldBeVisible(viewAppeared = false, active = true))
        assertFalse(slockChildLifecycleShouldBeVisible(viewAppeared = false, active = false))
    }

    @Test
    fun parentPagerAppearEventsDriveTheChildAppearFlag() {
        assertEquals(true, slockChildLifecyclePagerAppearState(SLOCK_CHILD_LIFECYCLE_VIEW_DID_APPEAR))
        assertEquals(false, slockChildLifecyclePagerAppearState(SLOCK_CHILD_LIFECYCLE_VIEW_DID_DISAPPEAR))
        assertNull(slockChildLifecyclePagerAppearState(SLOCK_CHILD_LIFECYCLE_DID_APPEAR))
        assertNull(slockChildLifecyclePagerAppearState("windowSizeDidChanged"))
    }

    @Test
    fun becomingVisibleSendsBothNativeResumeEvents() {
        assertEquals(
            listOf(SLOCK_CHILD_LIFECYCLE_DID_APPEAR, SLOCK_CHILD_LIFECYCLE_VIEW_DID_APPEAR),
            slockChildLifecycleEvents(previouslyVisible = false, shouldBeVisible = true)
        )
    }

    @Test
    fun becomingHiddenSendsBothNativePauseEvents() {
        assertEquals(
            listOf(SLOCK_CHILD_LIFECYCLE_DID_DISAPPEAR, SLOCK_CHILD_LIFECYCLE_VIEW_DID_DISAPPEAR),
            slockChildLifecycleEvents(previouslyVisible = true, shouldBeVisible = false)
        )
    }

    @Test
    fun unchangedVisibilitySendsNoEvents() {
        assertEquals(emptyList(), slockChildLifecycleEvents(previouslyVisible = true, shouldBeVisible = true))
        assertEquals(emptyList(), slockChildLifecycleEvents(previouslyVisible = false, shouldBeVisible = false))
    }

    @Test
    fun firstHomeChildResumesWhenParentPagerAppearsAfterAttach() {
        val attachedWhileParentAppearing =
            slockChildLifecycleShouldBeVisible(viewAppeared = false, active = true)
        assertFalse(attachedWhileParentAppearing)
        assertEquals(
            emptyList(),
            slockChildLifecycleEvents(previouslyVisible = false, shouldBeVisible = attachedWhileParentAppearing)
        )

        val parentAppeared = slockChildLifecyclePagerAppearState(SLOCK_CHILD_LIFECYCLE_VIEW_DID_APPEAR)
        assertEquals(true, parentAppeared)
        val shouldBeVisible =
            slockChildLifecycleShouldBeVisible(viewAppeared = parentAppeared == true, active = true)
        assertEquals(
            listOf(SLOCK_CHILD_LIFECYCLE_DID_APPEAR, SLOCK_CHILD_LIFECYCLE_VIEW_DID_APPEAR),
            slockChildLifecycleEvents(previouslyVisible = false, shouldBeVisible = shouldBeVisible)
        )
    }
}
