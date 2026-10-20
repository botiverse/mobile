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

    // task #410: lastSeenAt presence tiers (web EXTERNAL_AGENT_ONLINE_WINDOW_MS)

    @Test
    fun lastSeenAtWithinTwoMinutesRendersOnlineBeforeActivityTiers() {
        // Web AgentStatusBadge: the presence tier wins over the 5-minute
        // activity tier — an agent seen 30s ago is "Online" even when
        // lastActivityAt is absent.
        val result = externalAgentDetailIndicator(
            status = status(ExternalAgentSetupState.Connected),
            nowEpochMillis = now,
            lastSeenAt = iso(now - 30_000L)
        )
        assertEquals(RaftColor.AccentLime, result.state.color)
        assertEquals(ExternalAgentDetailCaption.Online, result.caption)
    }

    @Test
    fun lastSeenAtOutsideTwoMinutesRendersLastSeen() {
        val result = externalAgentDetailIndicator(
            status = status(ExternalAgentSetupState.Connected),
            nowEpochMillis = now,
            lastSeenAt = iso(now - 200_000L)
        )
        assertEquals(RaftColor.AccentCyan, result.state.color)
        assertEquals(
            ExternalAgentDetailCaption.LastSeen(now - 200_000L, iso(now - 200_000L)),
            result.caption
        )
    }

    @Test
    fun waitingForLoginStillWinsOverAnOnlineLastSeenAt() {
        // Existing mobile-only divergence: not signed in ⇒ gray + setup copy
        // even when the credential was just seen. The dot never disagrees
        // with the text beside it.
        val result = externalAgentDetailIndicator(
            status = status(ExternalAgentSetupState.WaitingForLogin),
            nowEpochMillis = now,
            lastSeenAt = iso(now - 10_000L)
        )
        assertEquals(RaftColor.StatusNeutralGray, result.state.color)
        assertEquals(ExternalAgentDetailCaption.SetupRequired, result.caption)
    }

    @Test
    fun missingLastSeenAtFallsBackToActivityTiers() {
        val result = externalAgentDetailIndicator(
            status = status(ExternalAgentSetupState.Connected, iso(now - 60_000L)),
            nowEpochMillis = now,
            lastSeenAt = null
        )
        assertEquals(RaftColor.AccentLime, result.state.color)
        assertEquals(ExternalAgentDetailCaption.Active, result.caption)
    }

    @Test
    fun noStatusStillReadsLastSeenAtFromTheSummary() {
        // externalStatus is permission-gated (null on non-managers). The
        // summary's lastSeenAt still carries the presence tiers — the row
        // should never stay a bare cyan "External" when the agent was seen.
        val result = externalAgentDetailIndicator(
            status = null,
            nowEpochMillis = now,
            lastSeenAt = iso(now - 5_000L)
        )
        assertEquals(RaftColor.AccentLime, result.state.color)
        assertEquals(ExternalAgentDetailCaption.Online, result.caption)

        val stale = externalAgentDetailIndicator(
            status = null,
            nowEpochMillis = now,
            lastSeenAt = iso(now - 300_000L)
        )
        assertEquals(RaftColor.AccentCyan, stale.state.color)
        assertEquals(
            ExternalAgentDetailCaption.LastSeen(now - 300_000L, iso(now - 300_000L)),
            stale.caption
        )
    }

    @Test
    fun onlineWindowBoundaryIsExclusiveAtTwoMinutes() {
        // now - lastSeenAt == 120_000 is OFFLINE (web `<`, not `<=`).
        val result = externalAgentDetailIndicator(
            status = status(ExternalAgentSetupState.Connected),
            nowEpochMillis = now,
            lastSeenAt = iso(now - ExternalAgentOnlineWindowMillis)
        )
        assertEquals(RaftColor.AccentCyan, result.state.color)
        assertEquals(
            ExternalAgentDetailCaption.LastSeen(now - ExternalAgentOnlineWindowMillis, iso(now - ExternalAgentOnlineWindowMillis)),
            result.caption
        )
    }

    @Test
    fun notProvidedStatusIsPlainExternalCyanNeverGray() {
        val result = externalAgentDetailIndicator(status = null, nowEpochMillis = now)
        assertEquals(RaftColor.AccentCyan, result.state.color)
        assertEquals(ExternalAgentDetailCaption.External, result.caption)
        assertTrue(result.state.external)
        assertFalse(result.state.pulsing)
        assertNull(result.state.status)
    }

    @Test
    fun waitingForLoginWithoutActivityIsGrayAndSetupRequired() {
        val result = externalAgentDetailIndicator(status(ExternalAgentSetupState.WaitingForLogin), now)
        assertEquals(RaftColor.StatusNeutralGray, result.state.color)
        assertEquals(ExternalAgentDetailCaption.SetupRequired, result.caption)
    }

    @Test
    fun recentActivityWithinFiveMinutesIsLimeAndActive() {
        val result =
            externalAgentDetailIndicator(
                status(ExternalAgentSetupState.Connected, lastActivityAt = iso(now - 2 * 60_000L)),
                now
            )
        assertEquals(RaftColor.AccentLime, result.state.color)
        assertEquals(ExternalAgentDetailCaption.Active, result.caption)
    }

    @Test
    fun activeThresholdIsStrictFiveMinutes() {
        val atThreshold =
            externalAgentDetailIndicator(
                status(ExternalAgentSetupState.Connected, lastActivityAt = iso(now - ExternalAgentActiveThresholdMillis)),
                now
            )
        assertEquals(RaftColor.AccentCyan, atThreshold.state.color)
        assertEquals(ExternalAgentDetailCaption.LastActivity(now - ExternalAgentActiveThresholdMillis, iso(now - ExternalAgentActiveThresholdMillis)), atThreshold.caption)
    }

    @Test
    fun activityOlderThanFiveMinutesIsCyanWithLastActivity() {
        val at = now - 10 * 60_000L
        val result = externalAgentDetailIndicator(status(ExternalAgentSetupState.Connected, lastActivityAt = iso(at)), now)
        assertEquals(RaftColor.AccentCyan, result.state.color)
        assertEquals(ExternalAgentDetailCaption.LastActivity(at, iso(at)), result.caption)
    }

    @Test
    fun oldActivityIsCyanWithLastActivity() {
        val at = now - 3 * 60 * 60_000L
        val result = externalAgentDetailIndicator(status(ExternalAgentSetupState.Connected, lastActivityAt = iso(at)), now)
        assertEquals(RaftColor.AccentCyan, result.state.color)
        assertEquals(ExternalAgentDetailCaption.LastActivity(at, iso(at)), result.caption)
    }

    // Deliberate divergence from web: web evaluates activity tiers before the
    // setup state and can show a lime dot beside a "setup required" caption
    // (agent ran, then lost its credential). Product rule: not signed in ⇒ gray,
    // and the dot must never disagree with the text beside it.
    @Test
    fun waitingForLoginWithFreshActivityStaysGrayAndSetupRequired() {
        val result =
            externalAgentDetailIndicator(
                status(ExternalAgentSetupState.WaitingForLogin, lastActivityAt = iso(now - 60_000L)),
                now
            )
        assertEquals(RaftColor.StatusNeutralGray, result.state.color)
        assertEquals(ExternalAgentDetailCaption.SetupRequired, result.caption)
    }

    // The input that makes a 30-minute tier unreachable on this platform, and the
    // guard against re-adding one. Web is activity-first, so 20-minute-old activity
    // on an unlogged-in agent lands in its 30-minute tier and paints cyan. Here
    // `waiting` is evaluated first and consumes the case, so no age comparison ever
    // runs. If someone reintroduces a `< 30 min` arm below `waiting`, this test does
    // NOT go red — the arm would simply be dead again, which is why the removal was
    // safe. What this pins is the ordering: move `waiting` below the age tiers to
    // "match web" and this goes gray → cyan, exposing it as a product change.
    @Test
    fun waitingForLoginOutranksTheAgeTiersWebWouldUseHere() {
        val result =
            externalAgentDetailIndicator(
                status(ExternalAgentSetupState.WaitingForLogin, lastActivityAt = iso(now - 20 * 60_000L)),
                now
            )
        assertEquals(RaftColor.StatusNeutralGray, result.state.color)
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
        assertEquals(RaftColor.AccentCyan, connected.state.color)
    }

    @Test
    fun unknownSetupStateWithoutActivityIsNotConfigured() {
        val result = externalAgentDetailIndicator(status(ExternalAgentSetupState.Unknown), now)
        assertEquals(ExternalAgentDetailCaption.NotConfigured, result.caption)
        assertEquals(RaftColor.AccentCyan, result.state.color)
    }

    @Test
    fun unparseableTimestampCountsAsNoActivity() {
        assertNull(parseExternalAgentTimestampOrNull("not-a-date"))
        assertNull(parseExternalAgentTimestampOrNull(""))
        val result =
            externalAgentDetailIndicator(status(ExternalAgentSetupState.WaitingForLogin, lastActivityAt = "garbage"), now)
        assertEquals(RaftColor.StatusNeutralGray, result.state.color)
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
