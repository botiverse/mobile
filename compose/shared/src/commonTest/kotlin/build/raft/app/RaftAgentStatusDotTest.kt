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

    @Test
    fun anExternalAgentIsCyanOutsideItsOnlineWindowAndLimeInside() {
        val now = 1_792_635_000_000L
        assertEquals(
            RaftAgentStatusDotKind.Cyan,
            raftAgentStatusDotKind("working", external = true, lastSeenAt = null, nowEpochMillis = now),
        )
        assertEquals(
            RaftAgentStatusDotKind.Lime,
            raftAgentStatusDotKind("working", external = true, lastSeenAt = "2026-10-22T02:09:30Z", nowEpochMillis = now),
        )
    }

    @Test
    fun aHeldAgentIsOrangeWhateverItsLastActivity() {
        assertEquals(
            RaftAgentStatusDotKind.Orange,
            raftAgentStatusDotKind("working", external = false, blockedReason = "computer_version_unsupported"),
        )
        assertEquals(
            RaftAgentStatusDotKind.Orange,
            raftAgentStatusDotKind("offline", external = false, blockedReason = "computer_access_revoked"),
        )
        // An unknown reason keeps the ordinary light.
        assertEquals(RaftAgentStatusDotKind.Gray, raftAgentStatusDotKind("offline", external = false, blockedReason = "later"))
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
    fun aHeldAgentSaysWhyAndAnUnknownStatusFallsBack() {
        assertEquals(
            Res.string.agent_status_computer_update_required.desc(),
            widgetText("working", blockedReason = "computer_version_unknown"),
        )
        assertEquals("Codex CLI".desc(), widgetText(null, runtime = "Codex CLI", description = "helper"))
        assertEquals("helper".desc(), widgetText(null, description = "helper"))
        assertEquals(Res.string.member_row_status_offline.desc(), widgetText(null))
    }
}
