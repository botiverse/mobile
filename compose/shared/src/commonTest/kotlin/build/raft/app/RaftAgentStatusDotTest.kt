package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftAgentStatusDotTest {
    @Test
    fun theWidgetDotFollowsTheAppDot() {
        assertEquals(RaftAgentStatusDotKind.Yellow, raftAgentStatusDotKind("working", external = false))
        assertEquals(RaftAgentStatusDotKind.Lime, raftAgentStatusDotKind("online", external = false))
        assertEquals(RaftAgentStatusDotKind.Lime, raftAgentStatusDotKind("idle", external = false))
        assertEquals(RaftAgentStatusDotKind.Orange, raftAgentStatusDotKind("error", external = false))
        assertEquals(RaftAgentStatusDotKind.Gray, raftAgentStatusDotKind("offline", external = false))
    }

    @Test
    fun anUnknownStatusIsGrayNeverGreen() {
        assertEquals(RaftAgentStatusDotKind.Gray, raftAgentStatusDotKind(null, external = false))
        assertEquals(RaftAgentStatusDotKind.Gray, raftAgentStatusDotKind("something-new", external = false))
    }

    private fun widgetText(
        status: String?,
        detail: String? = null,
        blockedReason: String? = null,
        runtime: String? = null,
        description: String? = null,
    ): StringDesc =
        raftAgentWidgetStatusText(
            statusWireValue = status,
            activityDetail = detail,
            blockedReason = blockedReason,
            external = false,
            runtime = runtime,
            description = description,
        )

    @Test
    fun theWidgetSaysTheAppsStatusWords() {
        // raft-design patterns/status.md: "Working…" and "Thinking…" with their ellipsis, never the
        // wire value title-cased.
        assertEquals(Res.string.member_row_status_working.desc(), widgetText("working"))
        assertEquals(Res.string.member_row_status_working.desc(), widgetText("working", detail = "Working"))
        assertEquals(Res.string.member_row_status_thinking.desc(), widgetText("thinking"))
        assertEquals(Res.string.member_row_status_online.desc(), widgetText("online", detail = "Tool finished"))
        assertEquals(Res.string.member_row_status_offline.desc(), widgetText("offline"))
        assertEquals(Res.string.member_row_status_error.desc(), widgetText("error"))
        // What it is doing, in the daemon's words, while it works.
        assertEquals("Running npm test".desc(), widgetText("working", detail = "Running npm test"))
    }

    @Test
    fun retiredRuntimeBlockedAgentShowsOrangeDotAndChangeRuntimeUntilSwitched() {
        assertEquals(
            RaftAgentStatusDotKind.Orange,
            raftAgentStatusDotKind("offline", external = false, blockedReason = "runtime_retired", runtime = "kimi"),
        )
        assertEquals(
            RaftAgentStatusDotKind.Lime,
            raftAgentStatusDotKind("online", external = false, blockedReason = "runtime_retired", runtime = "kimi-sdk"),
        )
        assertEquals(
            Res.string.agent_status_runtime_retired_change_runtime.desc(),
            widgetText("offline", blockedReason = "runtime_retired", runtime = "kimi"),
        )
        assertEquals(
            Res.string.member_row_status_online.desc(),
            widgetText("online", blockedReason = "runtime_retired", runtime = "kimi-sdk"),
        )
    }
}
