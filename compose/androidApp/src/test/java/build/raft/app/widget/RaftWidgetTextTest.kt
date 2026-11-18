package build.raft.app.widget

import android.app.Application
import build.raft.app.R
import build.raft.app.model.ConversationKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The widgets' states say which line to show ([RaftWidgetText]); the providers draw it from the app's
 * Android strings. Also: a widget cache written before 1.27 (English lines) still reads as the same state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftWidgetTextTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun res(id: Int, vararg args: Any) = RaftWidgetText.Res(id, args.toList())

    private val row = RaftWidgetHomeRow(title = "general", detail = "hi", avatarLabel = "#", unreadCount = 1)

    private fun content(rows: List<RaftWidgetHomeRow> = emptyList(), staleReason: RaftWidgetStaleReason? = null) =
        RaftWidgetState.Content(
            serverName = "Acme",
            unreadCount = 0,
            activityUnreadCount = 0,
            savedCount = 0,
            rows = rows,
            lastUpdatedAtMillis = 1_000L,
            staleReason = staleReason,
        )

    @Test
    fun activityWidgetStatesNameTheirLines() {
        assertEquals(RaftWidgetText.Plain("Raft"), RaftWidgetState.SignedOut.titleText())
        assertEquals(res(R.string.raft_widget_signed_out), RaftWidgetState.SignedOut.detailText())

        val chooseServer = RaftWidgetState.Error(kind = RaftWidgetErrorKind.ChooseServer)
        assertEquals(res(R.string.raft_widget_choose_server_title), chooseServer.titleText())
        assertEquals(res(R.string.raft_widget_choose_server_detail), chooseServer.detailText())
        // A failed refresh's reason replaces the kind's own line, as before.
        assertEquals(
            res(R.string.raft_widget_could_not_refresh),
            chooseServer.asStale(RaftWidgetStaleReason.CouldNotRefresh).detailText(),
        )

        val openRaft = RaftWidgetState.Error()
        assertEquals(RaftWidgetText.Plain("Raft"), openRaft.titleText())
        assertEquals(res(R.string.raft_widget_open_raft_to_continue), openRaft.detailText())
        // A server error stays its code.
        assertEquals(
            RaftWidgetText.Plain("home_http_500"),
            RaftWidgetState.SignedOut.asStale(RaftWidgetStaleReason.Code("home_http_500")).detailText(),
        )

        assertEquals(RaftWidgetText.Plain("Acme"), content().titleText())
        assertEquals(RaftWidgetText.Plain("Raft"), content().copy(serverName = " ").titleText())
        assertEquals(res(R.string.raft_widget_no_unread_rows), content().detailText())
        assertEquals(res(R.string.raft_widget_rows_with_messages), content(rows = listOf(row)).detailText())
    }

    @Test
    fun theFooterSaysWhenTheWidgetLastUpdated() {
        val time = { "10:30" }
        assertEquals(res(R.string.raft_widget_updating), content().copy(refreshing = true).updatedText(time))
        assertEquals(res(R.string.raft_widget_open_raft_to_start), RaftWidgetState.SignedOut.updatedText(time))
        assertEquals(res(R.string.slock_status_widget_updated, "10:30"), content().updatedText(time))
        assertEquals(
            res(R.string.raft_widget_stale_at, "10:30"),
            content(staleReason = RaftWidgetStaleReason.NoConversations).updatedText(time),
        )
        // An error state's footer is the last time it had content, never "Stale at".
        assertEquals(
            res(R.string.slock_status_widget_updated, "10:30"),
            RaftWidgetState.Error(staleReason = RaftWidgetStaleReason.CouldNotRefresh, lastUpdatedAtMillis = 1_000L).updatedText(time),
        )

        val agents = RaftAgentWidgetState(lastUpdatedAtMillis = 1_000L)
        assertEquals(res(R.string.slock_status_widget_updated, "10:30"), agents.updatedText(time))
        assertEquals(res(R.string.raft_widget_stale_at, "10:30"), agents.asStale(RaftWidgetStaleReason.CouldNotRefresh).updatedText(time))
        assertEquals(res(R.string.raft_widget_updating), agents.copy(refreshing = true).updatedText(time))
        assertEquals(res(R.string.raft_widget_open_raft_to_start), RaftAgentWidgetState().updatedText(time))

        assertEquals(res(R.string.raft_widget_refresh), raftWidgetRefreshText(refreshing = false))
        assertEquals(res(R.string.raft_widget_refreshing), raftWidgetRefreshText(refreshing = true))
    }

    @Test
    fun aConversationRowFallsBackToWhatKindItIs() {
        assertEquals(RaftWidgetText.Plain("general"), raftWidgetRowTitle("general"))
        assertEquals(res(R.string.raft_widget_conversation), raftWidgetRowTitle(""))

        assertEquals(RaftWidgetText.Plain("Ship it"), raftWidgetRowDetail("Ship it", "Ada", "topic", ConversationKind.Channel))
        assertEquals(res(R.string.raft_widget_latest_from, "Ada"), raftWidgetRowDetail(" ", "Ada", "topic", ConversationKind.Channel))
        assertEquals(RaftWidgetText.Plain("topic"), raftWidgetRowDetail(null, "", "topic", ConversationKind.Channel))
        assertEquals(res(R.string.raft_widget_direct_message), raftWidgetRowDetail(null, null, null, ConversationKind.DirectMessage))
        assertEquals(res(R.string.raft_widget_thread), raftWidgetRowDetail(null, null, "", ConversationKind.Thread))
        assertEquals(res(R.string.raft_widget_channel), raftWidgetRowDetail(null, null, null, ConversationKind.Channel))
    }

    @Test
    fun theAgentWidgetPlaceholderRow() {
        val initial = RaftAgentWidgetState()
        assertEquals(res(R.string.raft_agent_widget_no_active_agent), initial.placeholderName())
        assertEquals(res(R.string.raft_agent_widget_offline), initial.placeholderDetail())

        val signedOut = RaftAgentWidgetState(placeholder = RaftAgentWidgetPlaceholder.SignedOut)
        assertEquals(res(R.string.raft_agent_widget_no_active_agent), signedOut.placeholderName())
        assertEquals(res(R.string.raft_agent_widget_signed_out), signedOut.placeholderDetail())

        val none = RaftAgentWidgetState(placeholder = RaftAgentWidgetPlaceholder.NoAgents)
        assertEquals(res(R.string.raft_agent_widget_no_agents), none.placeholderName())
        assertEquals(res(R.string.raft_agent_widget_no_live_activity), none.placeholderDetail())

        assertEquals(
            res(R.string.raft_widget_last_refresh_failed, res(R.string.raft_widget_edit_to_choose_server)),
            none.asStale(RaftWidgetStaleReason.ChooseServer).placeholderDetail(),
        )
        assertEquals(
            res(R.string.raft_widget_last_refresh_failed, RaftWidgetText.Plain("http_502")),
            initial.asStale(RaftWidgetStaleReason.Code("http_502")).placeholderDetail(),
        )
    }

    @Test
    fun staleReasonsRoundTripAndReadTheEnglishOfAnOlderCache() {
        listOf(
            RaftWidgetStaleReason.CouldNotRefresh,
            RaftWidgetStaleReason.ChooseServer,
            RaftWidgetStaleReason.NoConversations,
            RaftWidgetStaleReason.Code("agents_contract"),
        ).forEach { reason -> assertEquals(reason, RaftWidgetStaleReason.fromWire(reason.wire)) }
        assertEquals(RaftWidgetStaleReason.CouldNotRefresh, RaftWidgetStaleReason.fromWire("Could not refresh"))
        assertEquals(RaftWidgetStaleReason.ChooseServer, RaftWidgetStaleReason.fromWire("Edit widget to choose a server"))
        assertEquals(RaftWidgetStaleReason.NoConversations, RaftWidgetStaleReason.fromWire("No conversations yet"))
        assertNull(RaftWidgetStaleReason.fromWire(""))
        assertNull(RaftWidgetStaleReason.fromWire(null))
    }

    @Test
    fun anOlderCachesEnglishLinesReadAsTheSameState() {
        assertEquals(
            RaftWidgetState.Error(kind = RaftWidgetErrorKind.ChooseServer, lastUpdatedAtMillis = 5L),
            JSONObject()
                .put("type", "error")
                .put("title", "Choose a server")
                .put("detail", "Tap and hold the widget, then edit it to select a server.")
                .put("lastUpdatedAtMillis", 5L)
                .toRaftWidgetErrorState(refreshing = false),
        )
        assertEquals(
            RaftWidgetState.Error(kind = RaftWidgetErrorKind.OpenRaft, staleReason = RaftWidgetStaleReason.CouldNotRefresh),
            JSONObject().put("type", "error").put("title", "Raft").put("detail", "Could not refresh").toRaftWidgetErrorState(refreshing = false),
        )
        assertEquals(
            RaftWidgetState.Error(kind = RaftWidgetErrorKind.ChooseServer, staleReason = RaftWidgetStaleReason.Code("home_http_500"), refreshing = true),
            JSONObject()
                .put("type", "error")
                .put("kind", "choose_server")
                .put("staleReason", "home_http_500")
                .toRaftWidgetErrorState(refreshing = true),
        )

        assertEquals(
            RaftAgentWidgetPlaceholder.SignedOut,
            JSONObject().put("agentName", "No active agent").put("statusLabel", "Sign in to see agent activity")
                .toRaftAgentWidgetState(refreshing = false).placeholder,
        )
        val noAgents =
            JSONObject().put("agentName", "No agents yet").put("statusLabel", "No live agent activity")
                .put("staleReason", "Edit widget to choose a server").put("lastUpdatedAtMillis", 7L)
                .toRaftAgentWidgetState(refreshing = false)
        assertEquals(
            RaftAgentWidgetState(
                placeholder = RaftAgentWidgetPlaceholder.NoAgents,
                lastUpdatedAtMillis = 7L,
                staleReason = RaftWidgetStaleReason.ChooseServer,
            ),
            noAgents,
        )
        assertEquals(
            RaftAgentWidgetPlaceholder.NoActiveAgent,
            JSONObject().put("agentName", "Ada").put("statusLabel", "Working…").toRaftAgentWidgetState(refreshing = false).placeholder,
        )
        assertEquals(
            RaftAgentWidgetPlaceholder.NoAgents,
            JSONObject().put("placeholder", "no_agents").toRaftAgentWidgetState(refreshing = false).placeholder,
        )
    }

    @Test
    fun linesResolveWithTheirArguments() {
        assertEquals(
            "Last refresh failed: Could not refresh",
            res(R.string.raft_widget_last_refresh_failed, res(R.string.raft_widget_could_not_refresh)).resolve(context),
        )
        assertEquals("Stale at 10:30", res(R.string.raft_widget_stale_at, "10:30").resolve(context))
        assertEquals("Latest from Ada", res(R.string.raft_widget_latest_from, "Ada").resolve(context))
        assertEquals("Acme", RaftWidgetText.Plain("Acme").resolve(context))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun linesResolveInTheDeviceLanguage() {
        assertEquals(
            "上次刷新失败：刷新失败",
            res(R.string.raft_widget_last_refresh_failed, res(R.string.raft_widget_could_not_refresh)).resolve(context),
        )
        assertEquals("自 10:30 起未更新", res(R.string.raft_widget_stale_at, "10:30").resolve(context))
        assertEquals("选择一个服务器", RaftWidgetState.Error(kind = RaftWidgetErrorKind.ChooseServer).titleText().resolve(context))
        assertEquals("登录后查看工作空间动态。", RaftWidgetState.SignedOut.detailText().resolve(context))
    }
}
