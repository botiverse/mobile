package build.raft.app

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

/** Wide pane roles (RaftWidePanes.kt, plan option C): routing, the pane stack and Back order. */
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
    fun channelSettingsAndItsSubViewsAreSupportingPanes() {
        val supporting = RaftPageRegistry.pageNames.filter { RaftPageRegistry.paneRole(it) == RaftPaneRole.Supporting }.toSet()
        assertEquals(setOf(CHANNEL_SETTINGS_PAGE, THREAD_MEMBERS_PAGE, JOINT_CHANNEL_CONNECTIONS_PAGE), supporting)
        assertEquals(RaftPaneRole.Window, RaftPageRegistry.paneRole(MEMBER_DETAIL_PAGE))
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
    fun backPopsTheSupportingPaneBeforeAnythingElse() {
        val open = RaftPaneStack().replace("settings", data).push("members", data)
        assertEquals(RaftWideBackStep.PopSupportingPane, raftWideBackStep(open, shown = true))
        assertEquals(RaftWideBackStep.PopSupportingPane, raftWideBackStep(open.pop(), shown = true))
        assertEquals(RaftWideBackStep.Existing, raftWideBackStep(open.pop().pop(), shown = true))
        assertEquals(RaftWideBackStep.Existing, raftWideBackStep(RaftPaneStack(), shown = true))
        // Pages kept in the stack while the pane isn't drawn (e.g. under the task overlay): Back
        // goes straight to the existing step instead of closing an invisible pane.
        assertEquals(RaftWideBackStep.Existing, raftWideBackStep(open, shown = false))
    }

    @Test
    fun paneSitsBesideWhenTheConversationKeepsItsMinimum() {
        // 1280 window: about 960 of detail beside the rail and the 240 sidebar.
        assertEquals(RaftSupportingPaneSize(420f, beside = true), raftSupportingPaneSize(960f))
        assertEquals(RaftSupportingPaneSize(352f, beside = true), raftSupportingPaneSize(712f))
        assertEquals(RaftSupportingPaneSize(320f, beside = true), raftSupportingPaneSize(680f))
        // Narrower: a drawer over the conversation's right edge, as the web's.
        assertEquals(RaftSupportingPaneSize(420f, beside = false), raftSupportingPaneSize(528f))
        assertEquals(RaftSupportingPaneSize(400f, beside = false), raftSupportingPaneSize(400f))
    }
}
