package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.settings.AppShellRailSearchKey
import build.raft.designsystem.theme.RaftScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LargeScreenShellPolishTest {
    @Test
    fun mainTabHeaderUsesCanvasBackgroundOnWideOrUnderTopBarOnly() {
        assertFalse(raftMainTabHeaderUsesCanvasBackground(RaftScale.Compact, underAppShellTopBar = false))
        assertTrue(raftMainTabHeaderUsesCanvasBackground(RaftScale.Wide, underAppShellTopBar = false))
        assertTrue(raftMainTabHeaderUsesCanvasBackground(RaftScale.Wide, underAppShellTopBar = true))
        assertTrue(raftMainTabHeaderUsesCanvasBackground(RaftScale.Compact, underAppShellTopBar = true))
    }

    @Test
    fun membersDetailHidesBackArrowInWideSplitAndRestoresWhenNarrow() {
        assertFalse(raftMembersDetailShowsBack(sidePaneShowsBack = true, inWideSplit = true))
        assertFalse(raftMembersDetailShowsBack(sidePaneShowsBack = false, inWideSplit = true))
        assertTrue(raftMembersDetailShowsBack(sidePaneShowsBack = true, inWideSplit = false))
        assertFalse(raftMembersDetailShowsBack(sidePaneShowsBack = false, inWideSplit = false))
    }

    @Test
    fun artifactsHomeTitleIsInlineOnlyOnWideScale() {
        assertTrue(artifactsHomeTitleIsInline(RaftScale.Wide))
        assertFalse(artifactsHomeTitleIsInline(RaftScale.Compact))
    }

    @Test
    fun channelDetailsMembersSummaryCapProtectsLocalizedTitleInNarrowSidePane() {
        // 308dp wide Details side pane -> ~276dp card width -> 114dp value cap leaves ~92dp for "Members" / "Участники".
        assertEquals(114.dp, channelSettingsMembersSummaryMaxWidth(276.dp))
        // 320dp wide Details side pane -> ~288dp card width -> 126dp value cap.
        assertEquals(126.dp, channelSettingsMembersSummaryMaxWidth(288.dp))
        // Phone 390dp -> ~358dp card width -> 196dp (full summary fits).
        assertEquals(196.dp, channelSettingsMembersSummaryMaxWidth(358.dp))
        // Wide card -> clamped to 200dp.
        assertEquals(200.dp, channelSettingsMembersSummaryMaxWidth(420.dp))
        // Extreme narrow card -> floor at 72dp.
        assertEquals(72.dp, channelSettingsMembersSummaryMaxWidth(200.dp))
    }

    @Test
    fun railAndTabSwitchesDismissKeyboardExceptForSearchAndNoOpRetaps() {
        assertTrue(shouldDismissKeyboardOnAppShellTabSwitch("tasks"))
        assertTrue(shouldDismissKeyboardOnAppShellTabSwitch("home"))
        assertFalse(shouldDismissKeyboardOnAppShellTabSwitch(AppShellSearchTabKey))

        assertTrue(shouldDismissKeyboardOnRailTabSelect("tasks", inlineSecondaryDestination = null, selectedTab = "home"))
        assertTrue(shouldDismissKeyboardOnRailTabSelect("settings", inlineSecondaryDestination = null, selectedTab = "home"))
        // Dismissing an inline secondary page (e.g. Computers/Search) back onto the underlying tab dismisses keyboard.
        assertTrue(shouldDismissKeyboardOnRailTabSelect("home", inlineSecondaryDestination = "computers", selectedTab = "home"))
        // Re-tapping the already-active tab with no inline overlay does not yank keyboard focus.
        assertFalse(shouldDismissKeyboardOnRailTabSelect("home", inlineSecondaryDestination = null, selectedTab = "home"))
        // Inline Search rail button keeps keyboard available for typing.
        assertFalse(shouldDismissKeyboardOnRailTabSelect(AppShellRailSearchKey, inlineSecondaryDestination = null, selectedTab = "home"))

        // Opening a different inline rail page (Computers/Apps/Artifacts/Assistant) dismisses keyboard,
        // while re-tapping the already-active inline page or an unrouted page leaves focus intact.
        assertTrue(shouldDismissKeyboardOnRailInlinePageSelect("computers", currentInlineSecondaryDestination = null))
        assertTrue(shouldDismissKeyboardOnRailInlinePageSelect("artifacts", currentInlineSecondaryDestination = "computers"))
        assertFalse(shouldDismissKeyboardOnRailInlinePageSelect("assistant", currentInlineSecondaryDestination = "assistant"))
        assertFalse(shouldDismissKeyboardOnRailInlinePageSelect(null, currentInlineSecondaryDestination = null))
    }
}
