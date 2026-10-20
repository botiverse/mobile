package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftChildPageLifecycleTest {
    @Test
    fun childIsLifecycleVisibleOnlyWhenTheViewAppearedAndTheTabIsActive() {
        assertTrue(raftChildLifecycleShouldBeVisible(viewAppeared = true, active = true))
        assertFalse(raftChildLifecycleShouldBeVisible(viewAppeared = true, active = false))
        assertFalse(raftChildLifecycleShouldBeVisible(viewAppeared = false, active = true))
        assertFalse(raftChildLifecycleShouldBeVisible(viewAppeared = false, active = false))
    }

    @Test
    fun parentPagerAppearEventsDriveTheChildAppearFlag() {
        assertEquals(true, raftChildLifecyclePagerAppearState(RAFT_CHILD_LIFECYCLE_VIEW_DID_APPEAR))
        assertEquals(false, raftChildLifecyclePagerAppearState(RAFT_CHILD_LIFECYCLE_VIEW_DID_DISAPPEAR))
        assertNull(raftChildLifecyclePagerAppearState(RAFT_CHILD_LIFECYCLE_DID_APPEAR))
        assertNull(raftChildLifecyclePagerAppearState("windowSizeDidChanged"))
    }

    @Test
    fun becomingVisibleSendsBothNativeResumeEvents() {
        assertEquals(
            listOf(RAFT_CHILD_LIFECYCLE_DID_APPEAR, RAFT_CHILD_LIFECYCLE_VIEW_DID_APPEAR),
            raftChildLifecycleEvents(previouslyVisible = false, shouldBeVisible = true)
        )
    }

    @Test
    fun becomingHiddenSendsBothNativePauseEvents() {
        assertEquals(
            listOf(RAFT_CHILD_LIFECYCLE_DID_DISAPPEAR, RAFT_CHILD_LIFECYCLE_VIEW_DID_DISAPPEAR),
            raftChildLifecycleEvents(previouslyVisible = true, shouldBeVisible = false)
        )
    }

    @Test
    fun unchangedVisibilitySendsNoEvents() {
        assertEquals(emptyList(), raftChildLifecycleEvents(previouslyVisible = true, shouldBeVisible = true))
        assertEquals(emptyList(), raftChildLifecycleEvents(previouslyVisible = false, shouldBeVisible = false))
    }

    @Test
    fun firstHomeChildResumesWhenParentPagerAppearsAfterAttach() {
        val attachedWhileParentAppearing =
            raftChildLifecycleShouldBeVisible(viewAppeared = false, active = true)
        assertFalse(attachedWhileParentAppearing)
        assertEquals(
            emptyList(),
            raftChildLifecycleEvents(previouslyVisible = false, shouldBeVisible = attachedWhileParentAppearing)
        )

        val parentAppeared = raftChildLifecyclePagerAppearState(RAFT_CHILD_LIFECYCLE_VIEW_DID_APPEAR)
        assertEquals(true, parentAppeared)
        val shouldBeVisible =
            raftChildLifecycleShouldBeVisible(viewAppeared = parentAppeared == true, active = true)
        assertEquals(
            listOf(RAFT_CHILD_LIFECYCLE_DID_APPEAR, RAFT_CHILD_LIFECYCLE_VIEW_DID_APPEAR),
            raftChildLifecycleEvents(previouslyVisible = false, shouldBeVisible = shouldBeVisible)
        )
    }
}
