package build.raft.app.data

import build.raft.app.SlockAdaptiveDetailIdentity
import build.raft.app.SlockAdaptiveDetailSelection
import build.raft.app.SlockAdaptivePaneLayout
import build.raft.app.SlockAdaptivePanePosture
import build.raft.app.SlockAdaptivePaneState
import build.raft.app.SlockAdaptiveWorkspaceState
import build.raft.app.WireThreadLaunchPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task #180 (web parity #9172/#9335): Focus mode on the side reply-thread column.
 *
 * These assert the session contract the UI depends on — when Focus is offered, what toggling does,
 * and that losing the column can never leave the flag behind — rather than the composition.
 */
class AdaptiveSideThreadFocusTest {
    private val owner =
        SlockAdaptiveDetailSelection(
            identity =
                SlockAdaptiveDetailIdentity(
                    serverId = "srv-1",
                    threadId = "ch-1",
                    surfaceKind = "channel",
                    channelId = "ch-1",
                ),
            generation = 1L,
        )

    private fun widePane() =
        SlockAdaptivePaneState(
            widthPx = 2400,
            heightPx = 1600,
            density = 1f,
            posture = SlockAdaptivePanePosture.Flat,
            layout = SlockAdaptivePaneLayout.DualPane,
        )

    private fun narrowPane() =
        SlockAdaptivePaneState(
            widthPx = 900,
            heightPx = 1600,
            density = 1f,
            posture = SlockAdaptivePanePosture.Flat,
            layout = SlockAdaptivePaneLayout.DualPane,
        )

    /** A session whose side thread is actually the visible one for the current selection. */
    private fun sessionWithSideThread(
        pane: SlockAdaptivePaneState,
        detailPaneWidthDp: Float?,
    ): SharedAdaptiveWorkspaceSession {
        val workspace =
            SlockAdaptiveWorkspaceState(
                shellInstanceId = "shell-1",
                serverId = "srv-1",
                selection = owner,
                pane = pane,
            )
        return SharedAdaptiveWorkspaceSession(
            workspace = workspace,
            detailPaneWidthDp = detailPaneWidthDp,
            sideThread =
                AdaptiveSideThread(
                    owner = owner,
                    identity =
                        SlockAdaptiveDetailIdentity(
                            serverId = "srv-1",
                            threadId = "th-1",
                            surfaceKind = "replyThread",
                            parentChannelId = "ch-1",
                        ),
                    payload = WireThreadLaunchPayload(threadId = "th-1"),
                    generation = 1L,
                ),
        )
    }

    @Test
    fun focusIsOffUntilTheUserAsksForIt() {
        val session = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)

        assertFalse(session.sideThreadFocusActive(), "a freshly opened column is not maximised")
        assertTrue(session.sideThreadFocusAvailable(desktopModeOnLargeScreen = true))
    }

    @Test
    fun togglingEntersAndLeavesFocusMode() {
        val session = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)

        val focused = session.toggleSideThreadFocus(desktopModeOnLargeScreen = true)
        assertTrue(focused.sideThreadFocusActive())

        val restored = focused.toggleSideThreadFocus(desktopModeOnLargeScreen = true)
        assertFalse(restored.sideThreadFocusActive(), "toggling again returns to the two columns")
    }

    @Test
    fun focusIsNotOfferedWithoutAColumnEvenIfTheFlagIsSet() {
        // A window that cannot host the column must not expose Focus: the mode relaxes layout, it
        // never invents a column.
        val narrow = sessionWithSideThread(narrowPane(), detailPaneWidthDp = 500f)
        assertFalse(narrow.sideThreadFocusAvailable(desktopModeOnLargeScreen = true))

        val toggled = narrow.toggleSideThreadFocus(desktopModeOnLargeScreen = true)
        assertFalse(toggled.sideThreadFocusActive(), "toggling without an available column is a no-op")
        assertEquals(narrow, toggled)
    }

    @Test
    fun focusIsNeverActiveWithoutASideThread() {
        val empty = SharedAdaptiveWorkspaceSession()
        assertFalse(empty.sideThreadFocusActive())
        assertFalse(empty.toggleSideThreadFocus(desktopModeOnLargeScreen = true).sideThreadFocusActive())
    }

    @Test
    fun aStaleFlagIsClearedWhenTheColumnCanNoLongerBeHosted() {
        // Shrink below the breakpoint: fold-into-detail and clear Focus in the SAME update, so the
        // session never ends up "maximised with no column to maximise".
        val focused = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)
            .copy(sideThreadFocused = true)

        val folded =
            focused
                .foldSideThreadIntoDetail()
                .copy(sideThreadFocused = false)

        assertFalse(folded.sideThreadFocusActive())
        assertFalse(folded.sideThreadFocused, "the flag must not survive the fold")
    }

    @Test
    fun normalizeDropsTheFlagWhenItCanNoLongerApply() {
        val focused = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)
            .copy(sideThreadFocused = true)

        // Same session, but now the measured detail width no longer fits the column.
        val shrunk = focused.copy(detailPaneWidthDp = 500f)
        assertFalse(shrunk.normalizeSideThreadFocus(desktopModeOnLargeScreen = true).sideThreadFocused)
    }

    @Test
    fun normalizeKeepsFocusWhileItStillApplies() {
        val focused = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)
            .copy(sideThreadFocused = true)

        assertTrue(focused.normalizeSideThreadFocus(desktopModeOnLargeScreen = true).sideThreadFocused)
    }

    @Test
    fun focusIsNotPersistedSoAWidenedWindowDoesNotAutoRestoreIt() {
        // Focus is view state, not a preference: a new session starts unfocused regardless of what
        // the previous one had, which is also why widening back does not re-enter the mode.
        val focused = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)
            .copy(sideThreadFocused = true)
        assertTrue(focused.sideThreadFocusActive())

        val freshSession = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)
        assertFalse(freshSession.sideThreadFocusActive())
    }

    @Test
    fun hostsWithoutTheSideColumnDoNotRenderTheEntry() {
        // Argus's required case: a page that was NOT adopted by the column must not offer Focus at
        // all -- not merely have it disabled. This is the predicate ThreadPage uses to decide
        // whether the menu button exists.
        val session = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)

        assertFalse(
            session.showsSideThreadFocusEntry(isSideColumnOwner = false),
            "a non-owner page (standalone thread / main detail) must not show the entry"
        )
        // ...and toggling from such a page changes nothing.
        assertEquals(session, session.toggleSideThreadFocusFromPanel(isSideColumnOwner = false))
    }

    @Test
    fun onlyTheSideColumnOwnerShowsTheEntry() {
        val session = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)

        assertTrue(
            session.showsSideThreadFocusEntry(isSideColumnOwner = true),
            "the page that owns the column shows the entry"
        )
    }

    @Test
    fun anOwnerPageWithNoVisibleThreadShowsNoEntry() {
        // Ownership alone is not enough: if the session no longer has a thread in the column there
        // is nothing to maximise.
        val noThread = SharedAdaptiveWorkspaceSession(workspace = sessionWithSideThread(widePane(), 1240f).workspace)

        assertFalse(noThread.showsSideThreadFocusEntry(isSideColumnOwner = true))
    }

    @Test
    fun togglingFromTheOwnerPageEntersAndLeavesFocus() {
        val session = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)

        val focused = session.toggleSideThreadFocusFromPanel(isSideColumnOwner = true)
        assertTrue(focused.sideThreadFocusActive())

        val restored = focused.toggleSideThreadFocusFromPanel(isSideColumnOwner = true)
        assertFalse(restored.sideThreadFocusActive())
    }

    @Test
    fun togglingFocusKeepsTheThreadsPageIdentitySoReadingPositionSurvives() {
        // Reading position is preserved because the side page is re-laid-out, not recreated: the
        // child page's remember key is (identity.key, generation), and neither may change across a
        // Focus toggle. If either moved, the page would be rebuilt and the scroll position lost --
        // which is exactly what "don't lose reading position" means for this feature.
        val session = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)
        val before = session.visibleSideThread()

        val focused = session.toggleSideThreadFocusFromPanel(isSideColumnOwner = true)
        val afterFocus = focused.visibleSideThread()

        assertEquals(before?.identity?.key, afterFocus?.identity?.key, "identity must not change on entering Focus")
        assertEquals(before?.generation, afterFocus?.generation, "generation must not change on entering Focus")

        val restored = focused.toggleSideThreadFocusFromPanel(isSideColumnOwner = true)
        val afterRestore = restored.visibleSideThread()

        assertEquals(before?.identity?.key, afterRestore?.identity?.key, "identity must not change on leaving Focus")
        assertEquals(before?.generation, afterRestore?.generation, "generation must not change on leaving Focus")
    }

    @Test
    fun togglingFocusNeverDropsOrReplacesTheThread() {
        // The thread payload itself must be the same object identity-wise: a new payload would mean
        // the child page reloads, so any scroll offset the user had is gone.
        val session = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)
        val before = session.visibleSideThread()

        val focused = session.toggleSideThreadFocusFromPanel(isSideColumnOwner = true)

        assertEquals(before?.payload, focused.visibleSideThread()?.payload)
        assertEquals(before?.owner, focused.visibleSideThread()?.owner)
    }

    @Test
    fun aFocusedPaneIsCollapsedByWidthAndTakesNoWeight() {
        // P0 regression guard (Argus's point): a session-state test cannot catch `weight(0f)` coming
        // back -- only an assertion on the layout the pane is given can. Compose rejects a zero
        // weight with IllegalArgumentException("invalid weight 0.0"), which crashed the app the
        // moment Focus was entered. The production call site routes through this function, so this
        // assertion covers the shipping path.
        val focused = build.raft.app.slockParentPaneLayout(focused = true)
        assertEquals(0f, focused.widthDp, "a focused pane collapses to zero width")
        assertNull(focused.weight, "a focused pane must not take a weight at all")
    }

    @Test
    fun theWeightUsedForTheParentPaneIsNeverZero() {
        // The invariant Compose demands: any weight that IS passed must be > 0.
        val normal = build.raft.app.slockParentPaneLayout(focused = false)
        assertNotNull(normal.weight)
        assertTrue(normal.weight!! > 0f, "Compose throws for weight 0; the pane must never take one")
        assertNull(normal.widthDp, "the unfocused pane is sized by weight, not width")
        assertTrue(build.raft.app.SLOCK_PARENT_PANE_WEIGHT > 0f)
    }

    @Test
    fun enteringFocusKeepsTheSessionRenderable() {
        val session = sessionWithSideThread(widePane(), detailPaneWidthDp = 1240f)

        val focused = session.toggleSideThreadFocusFromPanel(isSideColumnOwner = true)
        assertTrue(focused.sideThreadFocusActive())
        assertTrue(focused.visibleSideThread() != null, "Focus must keep the thread renderable")
        assertTrue(focused.workspace?.selection != null, "Focus must keep a selection to restore")

        val restored = focused.toggleSideThreadFocusFromPanel(isSideColumnOwner = true)
        assertFalse(restored.sideThreadFocusActive())
        assertTrue(restored.visibleSideThread() != null, "leaving Focus must restore the two columns")
    }
}
