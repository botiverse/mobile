package build.raft.app

import build.raft.app.data.ExternalAgentSetupState
import build.raft.app.data.ExternalAgentStatusSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task #1119 PR 2: mobile agent-detail tone for external agents mirrors web
 * `AgentStatusBadge` (slock@2676dab22) tier for tier. Product rule (@artin):
 * not signed in ⇒ gray + "finish setup on the web"; reviewer rule (@LiBai):
 * a status we could not read is never rendered as "waiting for login".
 */
class ExternalAgentDetailIndicatorTest {
    private val now = 1_790_000_000_000L
    // Self-contained UTC ISO-8601 formatter (commonTest has no kotlinx-datetime);
    // civil-from-days per Howard Hinnant's algorithm.
    private fun iso(epochMillis: Long): String {
        val dayMillis = 86_400_000L
        val days = epochMillis.floorDiv(dayMillis)
        val millisOfDay = epochMillis.mod(dayMillis)
        val z = days + 719_468L
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        val hh = millisOfDay / 3_600_000L
        val mm = (millisOfDay % 3_600_000L) / 60_000L
        val ss = (millisOfDay % 60_000L) / 1000L
        val ms = millisOfDay % 1000L
        fun p2(v: Long) = v.toString().padStart(2, '0')
        return "$year-${p2(m)}-${p2(d)}T${p2(hh)}:${p2(mm)}:${p2(ss)}.${ms.toString().padStart(3, '0')}Z"
    }

    @Test
    fun isoHelperRoundTripsThroughTheRepoParser() {
        assertEquals(now, parseExternalAgentTimestampOrNull(iso(now)))
        assertEquals("2026-09-03T06:00:00.000Z".let { parseExternalAgentTimestampOrNull(it) }, parseExternalAgentTimestampOrNull(iso(parseExternalAgentTimestampOrNull("2026-09-03T06:00:00.000Z")!!)))
    }
    private fun status(
        setup: ExternalAgentSetupState,
        lastActivityAt: String? = null
    ) = ExternalAgentStatusSnapshot(setupState = setup, lastActivityAt = lastActivityAt)

    @Test
    fun notProvidedStatusIsPlainExternalCyanNeverGray() {
        val result = externalAgentDetailIndicator(status = null, nowEpochMillis = now)
        assertEquals(SlockColor.AccentCyan, result.state.color)
        assertEquals(ExternalAgentDetailCaption.External, result.caption)
        assertTrue(result.state.external)
        assertFalse(result.state.pulsing)
        assertNull(result.state.status)
    }

    @Test
    fun waitingForLoginWithoutActivityIsGrayAndSetupRequired() {
        val result = externalAgentDetailIndicator(status(ExternalAgentSetupState.WaitingForLogin), now)
        assertEquals(SlockColor.StatusNeutralGray, result.state.color)
        assertEquals(ExternalAgentDetailCaption.SetupRequired, result.caption)
    }

    @Test
    fun recentActivityWithinFiveMinutesIsLimeAndActive() {
        val result =
            externalAgentDetailIndicator(
                status(ExternalAgentSetupState.Connected, lastActivityAt = iso(now - 2 * 60_000L)),
                now
            )
        assertEquals(SlockColor.AccentLime, result.state.color)
        assertEquals(ExternalAgentDetailCaption.Active, result.caption)
    }

    @Test
    fun activeThresholdIsStrictFiveMinutes() {
        val atThreshold =
            externalAgentDetailIndicator(
                status(ExternalAgentSetupState.Connected, lastActivityAt = iso(now - ExternalAgentActiveThresholdMillis)),
                now
            )
        assertEquals(SlockColor.AccentCyan, atThreshold.state.color)
        assertEquals(ExternalAgentDetailCaption.LastActivity(now - ExternalAgentActiveThresholdMillis, iso(now - ExternalAgentActiveThresholdMillis)), atThreshold.caption)
    }

    @Test
    fun activityWithinThirtyMinutesIsCyanWithLastActivity() {
        val at = now - 10 * 60_000L
        val result = externalAgentDetailIndicator(status(ExternalAgentSetupState.Connected, lastActivityAt = iso(at)), now)
        assertEquals(SlockColor.AccentCyan, result.state.color)
        assertEquals(ExternalAgentDetailCaption.LastActivity(at, iso(at)), result.caption)
    }

    @Test
    fun oldActivityIsCyanWithLastActivity() {
        val at = now - 3 * 60 * 60_000L
        val result = externalAgentDetailIndicator(status(ExternalAgentSetupState.Connected, lastActivityAt = iso(at)), now)
        assertEquals(SlockColor.AccentCyan, result.state.color)
        assertEquals(ExternalAgentDetailCaption.LastActivity(at, iso(at)), result.caption)
    }

    // Web order: activity tiers are evaluated before the setup-state gray, so a
    // waiting agent with fresh activity renders lime while its caption still says
    // setup is required. Mirrored deliberately, not accidentally.
    @Test
    fun waitingForLoginWithFreshActivityFollowsWebOrder() {
        val result =
            externalAgentDetailIndicator(
                status(ExternalAgentSetupState.WaitingForLogin, lastActivityAt = iso(now - 60_000L)),
                now
            )
        assertEquals(SlockColor.AccentLime, result.state.color)
        assertEquals(ExternalAgentDetailCaption.SetupRequired, result.caption)
    }

    @Test
    fun connectedWithoutActivityIsCyanNoRaftActivity() {
        assertEquals(
            ExternalAgentDetailCaption.NoRaftActivity,
            externalAgentDetailIndicator(status(ExternalAgentSetupState.CredentialMinted), now).caption
        )
        val connected = externalAgentDetailIndicator(status(ExternalAgentSetupState.Connected), now)
        assertEquals(ExternalAgentDetailCaption.NoRaftActivity, connected.caption)
        assertEquals(SlockColor.AccentCyan, connected.state.color)
    }

    @Test
    fun unknownSetupStateWithoutActivityIsNotConfigured() {
        val result = externalAgentDetailIndicator(status(ExternalAgentSetupState.Unknown), now)
        assertEquals(ExternalAgentDetailCaption.NotConfigured, result.caption)
        assertEquals(SlockColor.AccentCyan, result.state.color)
    }

    @Test
    fun unparseableTimestampCountsAsNoActivity() {
        assertNull(parseExternalAgentTimestampOrNull("not-a-date"))
        assertNull(parseExternalAgentTimestampOrNull(""))
        val result =
            externalAgentDetailIndicator(status(ExternalAgentSetupState.WaitingForLogin, lastActivityAt = "garbage"), now)
        assertEquals(SlockColor.StatusNeutralGray, result.state.color)
        assertEquals(ExternalAgentDetailCaption.SetupRequired, result.caption)
    }

    @Test
    fun setupStateWireValuesMapExactly() {
        assertEquals(ExternalAgentSetupState.WaitingForLogin, ExternalAgentSetupState.fromWireValue("waiting_for_login"))
        assertEquals(ExternalAgentSetupState.CredentialMinted, ExternalAgentSetupState.fromWireValue("credential_minted"))
        assertEquals(ExternalAgentSetupState.Connected, ExternalAgentSetupState.fromWireValue("connected"))
        assertEquals(ExternalAgentSetupState.Unknown, ExternalAgentSetupState.fromWireValue("something_else"))
        assertEquals(ExternalAgentSetupState.Unknown, ExternalAgentSetupState.fromWireValue(null))
    }
}
