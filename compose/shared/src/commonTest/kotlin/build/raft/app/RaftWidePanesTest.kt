package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.compat.pager.RaftPaneRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Wide pane roles (RaftWidePanes.kt, plan option C): routing, the pane stack, the profile column's cells and Back order. */
class RaftWidePanesTest {
    private val data = JsonObject(mapOf("serverId" to JsonPrimitive("s1"), "channelId" to JsonPrimitive("c1")))

    private class RecordingNavigator : RaftCmpNavigator {
        val opened = mutableListOf<Pair<String, JsonObject>>()
        var closes = 0

        override fun openPage(pageName: String, pageData: JsonObject) {
            opened += pageName to pageData
        }

        override fun closePage() {
            closes++
        }
    }

    @Test
    fun compactNeverReadsRoles() {
        val route = raftPaneRoute(wide = false, pageName = "compose_any") { fail("roles read off the wide layout") }
        assertEquals(RaftPaneRole.Window, route)
    }

    @Test
    fun wideFollowsTheRole() {
        assertEquals(RaftPaneRole.Window, raftPaneRoute(wide = true, pageName = "a") { RaftPaneRole.Window })
        assertEquals(RaftPaneRole.Supporting, raftPaneRoute(wide = true, pageName = "a") { RaftPaneRole.Supporting })
    }

    @Test
    fun channelSettingsItsSubViewsAndTheProfileAreSupportingPanes() {
        val supporting = RaftPageRegistry.pageNames.filter { RaftPageRegistry.paneRole(it) == RaftPaneRole.Supporting }.toSet()
        assertEquals(
            setOf(CHANNEL_SETTINGS_PAGE, THREAD_MEMBERS_PAGE, MEMBER_DETAIL_PAGE),
            supporting,
        )
        // The registry never sends a page to the main detail: only the pane does (below).
        assertTrue(RaftPageRegistry.pageNames.none { RaftPageRegistry.paneRole(it) == RaftPaneRole.Detail })
        // The Members rail root keeps its own inline detail; the list page itself opens as before.
        assertEquals(RaftPaneRole.Window, RaftPageRegistry.paneRole(MEMBERS_PAGE))
    }

    @Test
    fun pagesOpenedFromThePaneGoByTheWeb() {
        // The profile's Message: the conversation becomes the main detail (web closeProfile + navigate).
        assertEquals(RaftPaneRole.Detail, raftProfileColumnRole(THREAD_PAGE))
        // Channel members -> a profile, a profile -> a created agent's profile: pushed in the pane.
        assertEquals(RaftPaneRole.Supporting, raftProfileColumnRole(MEMBER_DETAIL_PAGE))
        assertEquals(RaftPaneRole.Supporting, raftProfileColumnRole(THREAD_MEMBERS_PAGE))
        // Everything else (the profile's Computers link) opens as it did.
        assertEquals(RaftPaneRole.Window, raftProfileColumnRole(SETTINGS_COMPUTERS_PAGE))
    }

    @Test
    fun paneNavigatorHandsDetailRolesToThePane() {
        val platform = RecordingNavigator()
        val panes = mutableListOf<Pair<RaftPaneRole, String>>()
        val navigator =
            RaftPaneNavigator(
                platform = platform,
                wide = { true },
                openInPane = { role, name, _ -> panes += role to name },
                roleOf = ::raftProfileColumnRole,
            )
        navigator.openPage(THREAD_PAGE, data)
        navigator.openPage(MEMBER_DETAIL_PAGE, data)
        navigator.openPage(SETTINGS_COMPUTERS_PAGE, data)
        assertEquals(listOf(RaftPaneRole.Detail to THREAD_PAGE, RaftPaneRole.Supporting to MEMBER_DETAIL_PAGE), panes)
        assertEquals(listOf(SETTINGS_COMPUTERS_PAGE to data), platform.opened)
    }

    @Test
    fun paneBridgeClosesThePanePageAndPassesTheRest() {
        val calls = mutableListOf<String>()
        var closes = 0
        val platform =
            object : HostBridgeTransport {
                override fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?) {
                    calls += method
                }

                override fun callSync(method: String, paramsJson: String): String? = "sync:$method"
            }
        val transport = RaftPaneBridgeTransport(platform) { closes++ }
        transport.call(BridgeModule.CLOSE_PAGE, "", null)
        transport.call("toast", "{}", null)
        assertEquals(1, closes)
        assertEquals(listOf("toast"), calls)
        assertEquals("sync:log", transport.callSync("log", "{}"))
    }

    @Test
    fun threadPageDataCarriesItsLaunchPayloadBack() {
        val launch = WireThreadLaunchPayload(instanceId = "dm-1", threadId = "t1", surfaceKind = "dm")
        val pageData = threadLaunchPageData(launch).toJsonObject()
        assertEquals(launch, threadLaunchPayloadOrNull(pageData))
        assertEquals(null, threadLaunchPayloadOrNull(data))
    }

    @Test
    fun unlistedPagesOpenAsWindows() {
        assertEquals(RaftPaneRole.Window, RaftPageRegistry.paneRole("compose_not_a_page"))
        assertEquals(RaftPaneRole.Window, RaftPageRegistry.paneRole("compose_slock_app_shell"))
    }

    @Test
    fun compactNavigatorPassesEveryCallThroughUnchanged() {
        val platform = RecordingNavigator()
        val navigator = RaftPaneNavigator(platform, wide = { false }, openInPane = { _, _, _ -> fail("pane on compact") })
        RaftPageRegistry.pageNames.forEach { navigator.openPage(it, data) }
        navigator.closePage()
        assertEquals(RaftPageRegistry.pageNames.map { it to data }, platform.opened)
        assertEquals(1, platform.closes)
    }

    @Test
    fun wideNavigatorSendsRoledPagesToThePaneAndTheRestToThePlatform() {
        val platform = RecordingNavigator()
        val panes = mutableListOf<Triple<RaftPaneRole, String, JsonObject>>()
        var paneCloses = 0
        val navigator =
            RaftPaneNavigator(
                platform = platform,
                wide = { true },
                openInPane = { role, name, pageData -> panes += Triple(role, name, pageData) },
                close = { paneCloses++ },
                roleOf = { if (it == "settings") RaftPaneRole.Supporting else RaftPaneRole.Window },
            )
        navigator.openPage("settings", data)
        navigator.openPage("profile", data)
        navigator.closePage()
        assertEquals(listOf(Triple(RaftPaneRole.Supporting, "settings", data)), panes)
        assertEquals(listOf("profile" to data), platform.opened)
        assertEquals(1, paneCloses)
        assertEquals(0, platform.closes)
    }

    @Test
    fun wideNavigatorWithoutItsOwnCloseClosesThePlatformPage() {
        val platform = RecordingNavigator()
        RaftPaneNavigator(platform, wide = { true }, openInPane = { _, _, _ -> }).closePage()
        assertEquals(1, platform.closes)
    }

    @Test
    fun paneStackPushesReplacesAndCloses() {
        val settings = RaftPaneStack().replace("settings", data)
        val members = settings.push("members", data)
        assertEquals(listOf("settings", "members"), members.entries.map { it.pageName })
        assertEquals(listOf("settings"), members.pop().entries.map { it.pageName })
        // Opening from the conversation starts the pane over.
        assertEquals(listOf("members"), members.replace("members", data).entries.map { it.pageName })
        // An entry closing itself takes what it opened with it.
        assertEquals(emptyList(), members.close(settings.entries.single().id).entries)
        assertEquals(listOf("settings"), members.close(members.entries.last().id).entries.map { it.pageName })
        // Ids stay unique after a pop, so a re-pushed page gets a fresh composition.
        val again = members.pop().push("members", data)
        assertEquals(2, again.entries.map { it.id }.toSet().size)
        assertFalse(members.cleared().isOpen)
    }

    @Test
    fun backClosesTheProfileColumnsLastPageBeforeAnythingElse() {
        val open = RaftProfileColumnPanes().open(CHANNEL_SETTINGS_PAGE, data).openFrom(RaftProfileColumnCell.Details, THREAD_MEMBERS_PAGE, data)
        assertEquals(RaftWideBackStep.PopProfileColumn, raftWideBackStep(open, shown = true))
        assertEquals(RaftWideBackStep.PopProfileColumn, raftWideBackStep(open.back(), shown = true))
        assertEquals(RaftWideBackStep.Existing, raftWideBackStep(open.back().back(), shown = true))
        assertEquals(RaftWideBackStep.Existing, raftWideBackStep(RaftProfileColumnPanes(), shown = true))
        // Pages kept in the column while it isn't declared (e.g. under the task overlay): Back
        // goes straight to the existing step instead of closing an invisible column.
        assertEquals(RaftWideBackStep.Existing, raftWideBackStep(open, shown = false))
        assertEquals(RaftWideBackStep.Existing, raftWideBackStep(open, paneShown = true, overlayOpen = true))
    }

    // #189 step 3 (web channelSideLayout): the profile column's cells, profile over details, as pages open and close.
    @Test
    fun aPageOpensInItsCellProfileOverDetails() {
        assertEquals(RaftProfileColumnCell.Profile, raftProfileColumnCellOf(MEMBER_DETAIL_PAGE))
        assertEquals(RaftProfileColumnCell.Details, raftProfileColumnCellOf(CHANNEL_SETTINGS_PAGE))
        assertEquals(RaftProfileColumnCell.Details, raftProfileColumnCellOf(THREAD_MEMBERS_PAGE))
        val none = RaftProfileColumnPanes()
        assertFalse(none.isOpen)
        assertEquals(emptyList(), none.cells, "nothing open: no column")
        val details = none.open(CHANNEL_SETTINGS_PAGE, data)
        assertEquals(listOf(RaftProfileColumnCell.Details), details.cells)
        val profile = none.open(MEMBER_DETAIL_PAGE, data)
        assertEquals(listOf(RaftProfileColumnCell.Profile), profile.cells)
        // Both open: one column, the profile on top (web: the details follow the profile), whichever opened first.
        val both = details.open(MEMBER_DETAIL_PAGE, data)
        assertEquals(listOf(RaftProfileColumnCell.Profile, RaftProfileColumnCell.Details), both.cells)
        assertEquals(both.cells, profile.open(CHANNEL_SETTINGS_PAGE, data).cells)
        // Opened from the conversation a page starts its cell over; the other cell stays.
        val again = both.open(MEMBER_DETAIL_PAGE, data)
        assertEquals(1, again.profile.entries.size)
        assertEquals(both.details, again.details)
    }

    @Test
    fun aPageOpenedInTheColumnPushesInItsCellOrOpensTheOther() {
        val details = RaftProfileColumnPanes().open(CHANNEL_SETTINGS_PAGE, data)
        // The details' member list: pushed over the details, in their cell.
        val members = details.openFrom(RaftProfileColumnCell.Details, THREAD_MEMBERS_PAGE, data)
        assertEquals(listOf(CHANNEL_SETTINGS_PAGE, THREAD_MEMBERS_PAGE), members.details.entries.map { it.pageName })
        assertEquals(listOf(RaftProfileColumnCell.Details), members.cells)
        // A member picked there: their profile above the list, which stays as it was.
        val picked = members.openFrom(RaftProfileColumnCell.Details, MEMBER_DETAIL_PAGE, data)
        assertEquals(listOf(RaftProfileColumnCell.Profile, RaftProfileColumnCell.Details), picked.cells)
        assertEquals(members.details, picked.details)
        assertEquals(listOf(MEMBER_DETAIL_PAGE), picked.profile.entries.map { it.pageName })
        // Another profile from the profile: pushed in the profile.
        val next = picked.openFrom(RaftProfileColumnCell.Profile, MEMBER_DETAIL_PAGE, data)
        assertEquals(2, next.profile.entries.size)
        // Every page, the details' first, for reopening on the platform stack when the window narrows.
        assertEquals(listOf(CHANNEL_SETTINGS_PAGE, THREAD_MEMBERS_PAGE, MEMBER_DETAIL_PAGE, MEMBER_DETAIL_PAGE), next.pages.map { it.pageName })
        // The details opened last: they go on top, so Back on the phone goes through them first, as in the column.
        val detailsLast = RaftProfileColumnPanes().open(MEMBER_DETAIL_PAGE, data).open(CHANNEL_SETTINGS_PAGE, data)
        assertEquals(listOf(MEMBER_DETAIL_PAGE, CHANNEL_SETTINGS_PAGE), detailsLast.pages.map { it.pageName })
    }

    // PR #4664 review: a page opened into the column leaves the thread's Focus mode; closing one doesn't.
    @Test
    fun openedSinceIsAPageOpenedNotOneClosed() {
        val none = RaftProfileColumnPanes()
        val details = none.open(CHANNEL_SETTINGS_PAGE, data)
        assertTrue(details.openedSince(none))
        val members = details.openFrom(RaftProfileColumnCell.Details, THREAD_MEMBERS_PAGE, data)
        assertTrue(members.openedSince(details), "a page pushed")
        val profile = members.openFrom(RaftProfileColumnCell.Details, MEMBER_DETAIL_PAGE, data)
        assertTrue(profile.openedSince(members), "the other cell")
        // A profile replaced by another (the same cell starting over) is a page opened too.
        assertTrue(profile.open(MEMBER_DETAIL_PAGE, data).openedSince(profile))
        assertFalse(profile.back().openedSince(profile), "Back")
        assertFalse(profile.closeCell(RaftProfileColumnCell.Details).openedSince(profile), "a cell closed")
        assertFalse(profile.cleared().openedSince(profile))
        assertFalse(profile.openedSince(profile), "nothing changed")
    }

    @Test
    fun backClosesTheLastOpenedPageFirstThenTheOtherCell() {
        val picked =
            RaftProfileColumnPanes()
                .open(CHANNEL_SETTINGS_PAGE, data)
                .openFrom(RaftProfileColumnCell.Details, THREAD_MEMBERS_PAGE, data)
                .openFrom(RaftProfileColumnCell.Details, MEMBER_DETAIL_PAGE, data)
        assertEquals(RaftProfileColumnCell.Profile, picked.backCell)
        // The profile goes first; then the member list, then the details.
        val one = picked.back()
        assertEquals(listOf(RaftProfileColumnCell.Details), one.cells)
        assertEquals(RaftProfileColumnCell.Details, one.backCell)
        val two = one.back()
        assertEquals(listOf(CHANNEL_SETTINGS_PAGE), two.details.entries.map { it.pageName })
        val three = two.back()
        assertFalse(three.isOpen)
        assertEquals(null, three.backCell)
        assertEquals(three, three.back(), "Back with nothing open changes nothing")
        // The details opened after a profile: Back closes the details first.
        val detailsLast = RaftProfileColumnPanes().open(MEMBER_DETAIL_PAGE, data).open(CHANNEL_SETTINGS_PAGE, data)
        assertEquals(RaftProfileColumnCell.Details, detailsLast.backCell)
        assertEquals(listOf(RaftProfileColumnCell.Profile), detailsLast.back().cells)
    }

    @Test
    fun closeTakesItsCellOrItsPageAndWhatWasOpenedOverIt() {
        val picked =
            RaftProfileColumnPanes()
                .open(CHANNEL_SETTINGS_PAGE, data)
                .openFrom(RaftProfileColumnCell.Details, THREAD_MEMBERS_PAGE, data)
                .open(MEMBER_DETAIL_PAGE, data)
        // The details' Close (their first page's): the member list goes with them; the profile stays, and Back is its.
        val root = picked.details.entries.first().id
        val closed = picked.close(RaftProfileColumnCell.Details, root)
        assertEquals(listOf(RaftProfileColumnCell.Profile), closed.cells)
        assertEquals(RaftProfileColumnCell.Profile, closed.backCell)
        // The member list's own Close: back to the details.
        val list = picked.details.entries.last().id
        assertEquals(listOf(CHANNEL_SETTINGS_PAGE), picked.close(RaftProfileColumnCell.Details, list).details.entries.map { it.pageName })
        // The Settings toggle closes the details cell whole.
        assertEquals(listOf(RaftProfileColumnCell.Profile), picked.closeCell(RaftProfileColumnCell.Details).cells)
        // The profile's Close with the profile opened last: Back moves to the details.
        val profileClosed = picked.close(RaftProfileColumnCell.Profile, picked.profile.entries.first().id)
        assertEquals(RaftProfileColumnCell.Details, profileClosed.backCell)
        assertFalse(picked.cleared().isOpen)
        // Ids stay unique after clearing, so a page opened again gets a fresh composition.
        val reopened = picked.cleared().open(CHANNEL_SETTINGS_PAGE, data)
        assertTrue(reopened.details.entries.first().id !in picked.details.entries.map { it.id })
    }

    // raft-design channel.md: the conversation's Settings toggle is pressed while its own details are the column's.
    @Test
    fun theSettingsToggleReadsTheChannelDetailsCell() {
        val details = RaftPaneStack().replace(CHANNEL_SETTINGS_PAGE, data)
        assertTrue(RaftProfileColumnState(details.entries.first()) {}.showsChannelDetails("c1"))
        // Their member list pushed over the details: still open.
        val members = details.push(THREAD_MEMBERS_PAGE, data)
        assertTrue(RaftProfileColumnState(members.entries.first()) {}.showsChannelDetails("c1"))
        assertFalse(RaftProfileColumnState(details.entries.first()) {}.showsChannelDetails("c2"))
        assertFalse(RaftProfileColumnState(details.entries.first()) {}.showsChannelDetails(null))
        // A member list opened from the conversation alone is not the details.
        val list = RaftPaneStack().replace(THREAD_MEMBERS_PAGE, data)
        assertFalse(RaftProfileColumnState(list.entries.first()) {}.showsChannelDetails("c1"))
        assertFalse(RaftProfileColumnState(null) {}.showsChannelDetails("c1"))
    }

    @Test
    fun theProfileColumnIsTheWebsProfileColumn() {
        assertEquals(RaftPaneGridColumnBounds(id = "profile", defaultWidthDp = 400f, minWidthDp = 320f), RaftPaneGridColumns.Profile)
    }
}
