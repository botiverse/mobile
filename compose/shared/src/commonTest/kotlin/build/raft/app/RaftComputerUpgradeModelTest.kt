package build.raft.app

import build.raft.app.data.SettingsComputerAcceptedUpgrade
import build.raft.app.data.SettingsComputerBroadcastPolicySnapshot
import build.raft.app.data.SettingsComputerControlState
import build.raft.app.data.SettingsComputerReleaseNotesSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerUpgradeRefusal
import build.raft.app.data.SettingsComputerUpgradeRequest
import build.raft.app.data.SettingsComputerUpgradeRequestSnapshot
import build.raft.app.data.SettingsComputerVersionGateSnapshot
import build.raft.app.data.settingsComputerUpgradeAvailabilityKey
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports of web MachineDetailPanel's Computer card decisions (see RaftComputerUpgradeModel.kt). */
class RaftComputerUpgradeModelTest {
    @Test
    fun semverOrdersPrereleasesBelowTheirReleaseAndIgnoresBuildMetadata() {
        assertEquals(-1, raftCompareComputerVersions("1.0.45-rc.1", "1.0.45"))
        assertEquals(1, raftCompareComputerVersions("1.0.10", "1.0.9"))
        assertEquals(0, raftCompareComputerVersions("1.0.45+build.7", "1.0.45"))
        assertEquals(-1, raftCompareComputerVersions("1.0.41-staging.2", "1.0.41-staging.10"))
        assertEquals(-1, raftCompareComputerVersions("1.0.41-1", "1.0.41-alpha"))
        assertTrue(raftIsComputerSemver("1.0.41-staging.1761000000.sha.abc123"))
        assertFalse(raftIsComputerSemver("1.0.01"))
        assertFalse(raftIsComputerSemver("1.0.4-01"))
        assertFalse(raftIsComputerSemver("v1.0.4"))
        assertEquals(null, raftIsRemoteUpgradeSupported(" "))
        assertEquals(false, raftIsRemoteUpgradeSupported("1.0.45-rc.1"))
        assertEquals(true, raftIsRemoteUpgradeSupported("1.0.45"))
    }

    @Test
    fun versionFactPrefersTheEligiblePolicyTargetThenTheLatestRelease() {
        assertEquals(RaftComputerVersionFact.Unknown, raftComputerVersionFact(computer(version = null)))
        assertEquals(RaftComputerVersionFact.CannotCheck, raftComputerVersionFact(computer(version = "dev")))
        assertEquals(RaftComputerVersionFact.Outdated("1.0.50"), raftComputerVersionFact(computer(policy = eligible("1.0.50"))))
        assertEquals(RaftComputerVersionFact.Current, raftComputerVersionFact(computer(latest = "1.0.46")))
        assertEquals(RaftComputerVersionFact.Outdated("1.0.47"), raftComputerVersionFact(computer(latest = "1.0.47")))
        assertEquals(
            RaftComputerVersionFact.CannotCheck,
            raftComputerVersionFact(computer(latest = "1.0.47", policy = noBroadcast("hands_unavailable")))
        )
        assertEquals(RaftComputerVersionFact.CannotCheck, raftComputerVersionFact(computer(latest = null)))
    }

    @Test
    fun cardOffersTheOneClickUpgradeOnlyWhenThePolicyIsEligibleAndTheGateOpen() {
        val oneClick = computer(policy = eligible("1.0.50"), upgradeAvailable = true)
        assertEquals(RaftComputerCardState.OneClick("1.0.50"), model(oneClick).card)

        val gated = computer(latest = "1.0.50", policy = noBroadcast("broadcast_disabled"))
        assertEquals(RaftComputerCardState.Commands("1.0.50", refusedNotAllowed = false), model(gated).card)
        assertFalse(model(gated).webUpgradeOn)

        val tooOld = computer(version = "1.0.40", latest = "1.0.50", remoteUpgradeSupported = false)
        assertEquals(RaftComputerCardState.ManualUpgrade("1.0.50"), model(tooOld).card)

        assertEquals(RaftComputerCardState.UpToDate, model(computer(latest = "1.0.46")).card)
        assertEquals(RaftComputerCardState.Reading, model(computer(version = null)).card)
    }

    @Test
    fun aRefusedClickHoldsOnlyWhileTheAvailabilityKeyMatches() {
        val oneClick = computer(policy = eligible("1.0.50"), upgradeAvailable = true)
        fun refused(code: String, key: String = settingsComputerUpgradeAvailabilityKey(oneClick)) =
            SettingsComputerControlState(upgradeRefusal = SettingsComputerUpgradeRefusal(oneClick.id, key, code))

        assertEquals(
            RaftComputerCardState.Commands("1.0.50", refusedNotAllowed = true),
            model(oneClick, refused("computer_broadcast_not_eligible")).card
        )
        assertEquals(
            RaftComputerCardState.Commands("1.0.50", refusedNotAllowed = false),
            model(oneClick, refused("remote_upgrade_disabled")).card
        )
        val unknown = model(oneClick, refused("server_unavailable"))
        assertEquals(RaftComputerCardState.OneClick("1.0.50"), unknown.card)
        assertEquals(RaftComputerUpgradeRefusal.Unknown, unknown.refusal)
        // The policy moved on since the click: the refusal no longer applies.
        assertEquals(RaftComputerCardState.OneClick("1.0.50"), model(oneClick, refused("remote_upgrade_disabled", key = "stale")).card)
    }

    @Test
    fun anUpgradeInFlightComesFromThePendingRowOrTheAcceptedRequest() {
        val pending = request(id = "req-2", state = "pending")
        assertEquals(RaftComputerCardState.Upgrading("1.0.50"), model(computer(policy = eligible("1.0.50"), row = pending)).card)

        val submitting = SettingsComputerControlState(upgradeSubmitting = SettingsComputerUpgradeRequest("machine-1", "1.0.50"))
        assertEquals(RaftComputerCardState.Upgrading("1.0.50"), model(computer(policy = eligible("1.0.50")), submitting).card)

        val accepted = SettingsComputerAcceptedUpgrade("machine-1", request(id = "req-2", state = "pending"), acceptedAtMillis = NOW)
        // A refresh without the pending row is no evidence the request ended.
        assertEquals(accepted.request, raftComputerUpgradeInFlight(computer(), accepted, NOW + 1_000))
        // The row settled that request, the machine reached the target, or the deadline passed.
        assertNull(raftComputerUpgradeInFlight(computer(row = request(id = "req-2", state = "done")), accepted, NOW))
        assertNull(raftComputerUpgradeInFlight(computer(version = "1.0.50"), accepted, NOW))
        assertNull(raftComputerUpgradeInFlight(computer(), accepted, NOW + RAFT_COMPUTER_UPGRADE_REQUEST_DEADLINE_MILLIS))
    }

    @Test
    fun anOfflineComputerUpgradingReadsAsReconnectingUntilTheGraceRunsOut() {
        val pending = request(id = "req-2", state = "pending", requestedAt = "2026-10-21T01:00:00.000Z")
        val offline = computer(online = false, row = pending, lastHeartbeat = "2026-10-21T01:01:00.000Z")
        val since = requireNotNull(parseRaftTimestampEpochMillisOrNull("2026-10-21T01:01:00.000Z"))

        val reconnecting = raftComputerUpgradeReconnect(offline, accepted = null, nowMillis = since + 60_000)
        assertTrue(reconnecting.reconnecting)
        assertFalse(reconnecting.overdue)

        val overdue = raftComputerUpgradeReconnect(offline, accepted = null, nowMillis = since + RAFT_COMPUTER_UPGRADE_RECONNECT_GRACE_MILLIS)
        assertFalse(overdue.reconnecting)
        assertTrue(overdue.overdue)

        assertFalse(raftComputerUpgradeReconnect(computer(online = false), accepted = null, nowMillis = since).reconnecting)
    }

    @Test
    fun aFailedRequestIsReportedOnlyWhileTheComputerIsStillBelowItsTarget() {
        val failed = request(id = "req-1", state = "failed", target = "1.0.49")
        assertEquals(failed, model(computer(latest = "1.0.50", row = failed)).requestOutcome)
        assertNull(model(computer(version = "1.0.49", latest = "1.0.50", row = failed)).requestOutcome)
        assertNull(model(computer(latest = "1.0.50", row = request(id = "req-1", state = "done"))).requestOutcome)
    }

    @Test
    fun versionTierFollowsTheServerGate() {
        val gate = SettingsComputerVersionGateSnapshot("computer_version_unsupported", "1.0.48", enforcedAt = null, enforced = false)
        val upgrade = assertIs<RaftComputerVersionTier.Notice>(raftComputerVersionTier(computer(gate = gate)))
        assertFalse(upgrade.reinstall)
        val reinstall = assertIs<RaftComputerVersionTier.Notice>(raftComputerVersionTier(computer(version = "1.0.40", gate = gate)))
        assertTrue(reinstall.reinstall)
        assertEquals(RaftComputerCardState.Reinstall, model(computer(version = "1.0.40", gate = gate)).card)
        assertEquals(RaftComputerVersionTier.None, raftComputerVersionTier(computer()))
        assertEquals(RaftComputerVersionTier.None, raftComputerVersionTier(computer(version = null, gate = gate)))
    }

    @Test
    fun releaseNotesBelongToTheOfferedVersionAndFollowTheUiLanguage() {
        val notes = SettingsComputerReleaseNotesSnapshot(version = "1.0.50", en = "- English", zhCn = "- 中文")
        assertEquals(notes, model(computer(latest = "1.0.50", notes = notes)).releaseNotes)
        assertNull(model(computer(latest = "1.0.51", notes = notes)).releaseNotes)
        assertEquals("- 中文", raftComputerReleaseNotesText(notes, "zh-Hans-CN"))
        assertEquals("- English", raftComputerReleaseNotesText(notes, "ja"))
        assertEquals("- 中文", raftComputerReleaseNotesText(notes.copy(en = null), "en-US"))
    }

    private fun model(computer: SettingsComputerSnapshot, control: SettingsComputerControlState = SettingsComputerControlState()) =
        raftComputerUpgradeModel(computer, control, NOW)

    private fun eligible(target: String) =
        SettingsComputerBroadcastPolicySnapshot(eligibility = "eligible", targetVersion = target, policyRevision = "rev-1", reasonCode = "eligible")

    private fun noBroadcast(reason: String) =
        SettingsComputerBroadcastPolicySnapshot(eligibility = "no_broadcast", targetVersion = null, policyRevision = "rev-1", reasonCode = reason)

    private fun request(
        id: String,
        state: String,
        target: String = "1.0.50",
        requestedAt: String = "2026-10-21T01:00:00.000Z",
    ) = SettingsComputerUpgradeRequestSnapshot(id = id, targetVersion = target, requestedAt = requestedAt, state = state)

    private fun computer(
        version: String? = "1.0.46",
        latest: String? = "1.0.46",
        online: Boolean = true,
        policy: SettingsComputerBroadcastPolicySnapshot? = null,
        upgradeAvailable: Boolean = false,
        remoteUpgradeSupported: Boolean? = true,
        row: SettingsComputerUpgradeRequestSnapshot? = null,
        gate: SettingsComputerVersionGateSnapshot? = null,
        notes: SettingsComputerReleaseNotesSnapshot? = null,
        lastHeartbeat: String? = null,
    ) = SettingsComputerSnapshot(
        id = "machine-1",
        name = "Studio",
        daemonVersion = "",
        computerVersion = version,
        isComputer = true,
        online = online,
        lastHeartbeat = lastHeartbeat,
        upgradeAvailable = upgradeAvailable,
        broadcastPolicy = policy,
        upgradeRequest = row,
        remoteUpgradeSupported = remoteUpgradeSupported,
        versionGate = gate,
        latestComputerVersion = latest,
        latestComputerReleaseNotes = notes,
    )

    private companion object {
        const val NOW = 1_761_008_400_000L
    }
}
