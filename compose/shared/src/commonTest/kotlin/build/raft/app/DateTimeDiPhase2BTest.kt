package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.members.RaftMemberDetailPayload
import build.raft.app.members.RaftMembersPayload
import build.raft.app.members.resolveMembersReminderRelativeTimeLabel
import build.raft.app.members.toCreatedAgentDetailPageData
import build.raft.app.members.toMemberDetailPageData
import build.raft.app.members.toRaftMemberDetailPayload
import build.raft.app.members.toRaftMembersPayload
import build.raft.app.members.toRaftTimeContextOrNull
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftRelativeTimeUnit
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.ServerId
import build.raft.app.model.ServerPermissions
import build.raft.app.model.ServerSummary
import build.raft.app.settings.buildHostMembersPayload
import build.raft.app.thread.toThreadMembersPageRouteOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Date/Time Formatting & DI Phase 2B (`dm:@artin:#2`):
 * - Member and thread-member route payloads must never fabricate silent UTC (`localOffsetMinutes = 0`)
 *   or hardcoded 24h when opened from callers without explicit timezone params (AssistantPage,
 *   SettingsServerArchive, RaftSettingsComputersPage, ThreadMembersPage).
 * - Reminder relative-time calculation uses web JS `Math.round` (`floor(x + 0.5)`) tie-breaking
 *   consistently across JVM and Native/iOS.
 */
class DateTimeDiPhase2BTest {
    @Test
    fun memberPayloadsOmitSilentUtcAndFormatWhenUnsetAndPreserveExplicitValues() {
        val defaultMembers = RaftMembersPayload(instanceId = "assistant-settings")
        assertNull(defaultMembers.localOffsetMinutes)
        assertNull(defaultMembers.timeFormat)

        val detailPageData =
            assertNotNull(
                defaultMembers.toMemberDetailPageData(
                    MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent, ServerId("server-1"))
                )
            )
        assertFalse(detailPageData.has("localOffsetMinutes"), "must not fabricate UTC 0 in pageData")
        assertFalse(detailPageData.has("timeFormat"), "must not fabricate 24h in pageData")

        val decodedDetail = detailPageData.toRaftMemberDetailPayload()
        assertNull(decodedDetail.localOffsetMinutes)
        assertNull(decodedDetail.timeFormat)
        assertNull(decodedDetail.toRaftTimeContextOrNull())

        val createdAgentData =
            assertNotNull(decodedDetail.toCreatedAgentDetailPageData(MemberId("agent-2"), ServerId("server-1")))
        assertFalse(createdAgentData.has("localOffsetMinutes"))
        assertFalse(createdAgentData.has("timeFormat"))

        val decodedMembers = """{"instanceId":"srv-1"}""".toRaftMembersPayload()
        assertNull(decodedMembers.localOffsetMinutes)
        assertNull(decodedMembers.timeFormat)

        val hostPayloadWithoutTime =
            buildHostMembersPayload(
                server =
                    ServerSummary(
                        id = ServerId("server-1"),
                        name = "Raft",
                        role = "owner",
                        permissions = ServerPermissions(canManageSettings = true, canInviteMember = true),
                    ),
                timeContext = null,
            )
        assertNull(hostPayloadWithoutTime.localOffsetMinutes)
        assertNull(hostPayloadWithoutTime.timeFormat)

        val explicitDetail =
            RaftMemberDetailPayload(
                instanceId = "pinned-1",
                localOffsetMinutes = 480,
                todayEpochDay = 20624L,
                nowEpochMillis = 1782240000000L,
                timeFormat = RaftTimeFormat12Hour,
            )
        val explicitContext = assertNotNull(explicitDetail.toRaftTimeContextOrNull())
        assertEquals(480, explicitContext.localOffsetMinutes)
        assertEquals(20624L, explicitContext.todayEpochDay)
        assertEquals(1782240000000L, explicitContext.nowEpochMillis)
        assertEquals(RaftTimeFormat12Hour, explicitContext.timeFormat)
    }

    @Test
    fun threadMembersRouteOmitsSilentUtcWhenAbsentAndPreservesExplicitValues() {
        val bareParams = JSONObject().apply {
            put("serverId", "server-1")
            put("channelId", "channel-1")
        }
        val bareRoute = assertNotNull(bareParams.toThreadMembersPageRouteOrNull())
        assertNull(bareRoute.localOffsetMinutes)
        assertNull(bareRoute.timeFormat)

        val explicitParams = JSONObject().apply {
            put("serverId", "server-1")
            put("channelId", "channel-1")
            put("localOffsetMinutes", -300)
            put("timeFormat", RaftTimeFormat12Hour)
        }
        val explicitRoute = assertNotNull(explicitParams.toThreadMembersPageRouteOrNull())
        assertEquals(-300, explicitRoute.localOffsetMinutes)
        assertEquals(RaftTimeFormat12Hour, explicitRoute.timeFormat)
    }

    @Test
    fun reminderRelativeTimeMatchesWebJsMathRoundHalfTieBreaking() {
        val baseNow = 1_762_353_000_000L // 2025-11-05T14:30:00Z
        val ctx = RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = baseNow, timeFormat = RaftTimeFormat24Hour)

        // -30s (-0.5 min): JS Math.round(-0.5) === 0 ("now"), not -1 (Native llround).
        assertEquals(
            RaftRelativeTimeLabel.Relative(0, RaftRelativeTimeUnit.Minute),
            resolveMembersReminderRelativeTimeLabel("2025-11-05T14:29:30Z", ctx),
        )
        // +30s (+0.5 min): JS Math.round(0.5) === 1 ("in 1 minute").
        assertEquals(
            RaftRelativeTimeLabel.Relative(1, RaftRelativeTimeUnit.Minute),
            resolveMembersReminderRelativeTimeLabel("2025-11-05T14:30:30Z", ctx),
        )
        // -30m (-0.5h is still < 1h so measured in minutes: -30 min); -90m (-1.5h -> JS Math.round(-1.5) === -1h).
        assertEquals(
            RaftRelativeTimeLabel.Relative(-1, RaftRelativeTimeUnit.Hour),
            resolveMembersReminderRelativeTimeLabel("2025-11-05T13:00:00Z", ctx),
        )
        // +3 hours.
        assertEquals(
            RaftRelativeTimeLabel.Relative(3, RaftRelativeTimeUnit.Hour),
            resolveMembersReminderRelativeTimeLabel("2025-11-05T17:30:00Z", ctx),
        )
        // +36 hours (+1.5d -> +2d) and -36 hours (-1.5d -> JS Math.round(-1.5) === -1d).
        assertEquals(
            RaftRelativeTimeLabel.Relative(2, RaftRelativeTimeUnit.Day),
            resolveMembersReminderRelativeTimeLabel("2025-11-07T02:30:00Z", ctx),
        )
        assertEquals(
            RaftRelativeTimeLabel.Relative(-1, RaftRelativeTimeUnit.Day),
            resolveMembersReminderRelativeTimeLabel("2025-11-04T02:30:00Z", ctx),
        )
        // Missing clock or unparseable input returns null so Composable falls back to short date-time or raw text.
        assertNull(resolveMembersReminderRelativeTimeLabel("2025-11-05T17:30:00Z", ctx.copy(nowEpochMillis = null)))
        assertNull(resolveMembersReminderRelativeTimeLabel("not-a-timestamp", ctx))
        assertNull(resolveMembersReminderRelativeTimeLabel("   ", ctx))
    }
}
