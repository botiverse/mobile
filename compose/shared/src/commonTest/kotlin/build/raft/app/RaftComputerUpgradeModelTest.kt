package build.raft.app

import build.raft.app.data.SettingsComputerAcceptedUpgrade
import build.raft.app.data.SettingsComputerBroadcastPolicySnapshot
import build.raft.app.data.SettingsComputerControlState
import build.raft.app.data.SettingsComputerReleaseNotesSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerUpgradeDiskFullSnapshot
import build.raft.app.data.SettingsComputerUpgradeFailureHintSnapshot
import build.raft.app.data.SettingsComputerUpgradeRefusal
import build.raft.app.data.SettingsComputerUpgradeRequest
import build.raft.app.data.SettingsComputerUpgradeRequestSnapshot
import build.raft.app.data.SettingsComputerVersionGateSnapshot
import build.raft.app.data.settingsComputerUpgradeAvailabilityKey
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(null, raftIsRemoteUpgradeSupported(null))
        assertEquals(false, raftIsRemoteUpgradeSupported("1.0.45"))
        assertEquals(false, raftIsRemoteUpgradeSupported("1.0.52"))
        assertEquals(false, raftIsRemoteUpgradeSupported("1.0.52-rc.1"))
        assertEquals(true, raftIsRemoteUpgradeSupported("1.0.53"))
        assertEquals(true, raftIsRemoteUpgradeSupported("1.0.53-rc.1"))
        assertEquals(true, raftIsRemoteUpgradeSupported("1.0.53+build.5"))
        assertEquals(true, raftIsRemoteUpgradeSupported("1.0.54"))
        assertEquals(false, raftIsRemoteUpgradeSupported("v1.0.53"))
    }

    @Test
    fun versionTierUsesBuiltIn1053FloorEvenWhileOffline() {
        val builtInGate =
            SettingsComputerVersionGateSnapshot(
                reason = "computer_version_unsupported",
                minVersion = RAFT_COMPUTER_VERSION_FLOOR,
                enforcedAt = null,
                enforced = true,
            )
        val offlineTooOld = computer(version = "1.0.52", online = false, gate = builtInGate)
        assertEquals(
            RaftComputerVersionTier.Notice(
                reinstall = true,
                reason = "computer_version_unsupported",
                minVersion = "1.0.53",
                enforced = true,
                enforcedAt = null,
            ),
            raftComputerVersionTier(offlineTooOld),
        )
        assertEquals(RaftComputerCardState.Reinstall, model(offlineTooOld).card)

        val raisedGate =
            SettingsComputerVersionGateSnapshot(
                reason = "computer_version_unsupported",
                minVersion = "1.0.60",
                enforcedAt = "2026-12-01T00:00:00.000Z",
                enforced = false,
            )
        assertEquals(
            RaftComputerVersionTier.Notice(
                reinstall = false,
                reason = "computer_version_unsupported",
                minVersion = "1.0.60",
                enforced = false,
                enforcedAt = "2026-12-01T00:00:00.000Z",
            ),
            raftComputerVersionTier(computer(version = "1.0.53-rc.1", gate = raisedGate)),
        )
        assertEquals(RaftComputerVersionTier.None, raftComputerVersionTier(computer(version = "1.0.52", gate = null)))
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
    fun aComputerRefusingTheUpgradeScopeGetsTheSystemServiceStepsInsteadOfUpgrade() {
        // slock #15243: ready.upgradeScope { ok: false } on the machine row hides the one-click Upgrade.
        val refused = computer(policy = eligible("1.0.50"), upgradeAvailable = true).copy(upgradeScopeRefused = true)
        assertEquals(RaftComputerCardState.SystemService("1.0.50"), model(refused).card)
        // A page loaded before the server knew it: the click's `scope_unavailable` refusal says the same.
        val oneClick = computer(policy = eligible("1.0.50"), upgradeAvailable = true)
        val clicked =
            SettingsComputerControlState(
                upgradeRefusal =
                    SettingsComputerUpgradeRefusal(oneClick.id, settingsComputerUpgradeAvailabilityKey(oneClick), "scope_unavailable")
            )
        assertEquals(RaftComputerUpgradeRefusal.SystemService, model(oneClick, clicked).refusal)
        assertEquals(RaftComputerCardState.SystemService("1.0.50"), model(oneClick, clicked).card)
        // Too old for a web upgrade at all: the plain manual sentence, as before.
        val tooOld = computer(version = "1.0.40", latest = "1.0.50", remoteUpgradeSupported = false).copy(upgradeScopeRefused = true)
        assertEquals(RaftComputerCardState.ManualUpgrade("1.0.50"), model(tooOld).card)
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
    fun theAcceptedRequestFollowsTheServersOwnDeadline() {
        // deadlineAt - requestedAt (server clock both ends) is 40 min: drain wait included.
        val longer =
            request(id = "req-2", state = "pending", requestedAt = "2026-10-21T01:00:00.000Z")
                .copy(deadlineAt = "2026-10-21T01:40:00.000Z")
        assertEquals(40L * 60_000L, raftComputerUpgradeRequestDeadlineMillis(longer))
        val accepted = SettingsComputerAcceptedUpgrade("machine-1", longer, acceptedAtMillis = NOW)
        assertEquals(longer, raftComputerUpgradeInFlight(computer(), accepted, NOW + RAFT_COMPUTER_UPGRADE_REQUEST_DEADLINE_MILLIS))
        assertNull(raftComputerUpgradeInFlight(computer(), accepted, NOW + 40L * 60_000L))
        // No deadlineAt (an older server), or an unreadable one: 30 min.
        assertEquals(RAFT_COMPUTER_UPGRADE_REQUEST_DEADLINE_MILLIS, raftComputerUpgradeRequestDeadlineMillis(request(id = "r", state = "pending")))
        assertEquals(
            RAFT_COMPUTER_UPGRADE_REQUEST_DEADLINE_MILLIS,
            raftComputerUpgradeRequestDeadlineMillis(request(id = "r", state = "pending").copy(deadlineAt = "2026-10-21T00:00:00.000Z"))
        )
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
    fun releaseNotesBelongToTheOfferedVersionAndFollowTheUiLanguage() {
        val notes = SettingsComputerReleaseNotesSnapshot(version = "1.0.50", en = "- English", zhCn = "- 中文")
        assertEquals(notes, model(computer(latest = "1.0.50", notes = notes)).releaseNotes)
        assertNull(model(computer(latest = "1.0.51", notes = notes)).releaseNotes)
        assertEquals("- 中文", raftComputerReleaseNotesText(notes, "zh-Hans-CN"))
        assertEquals("- English", raftComputerReleaseNotesText(notes, "ja"))
        assertEquals("- 中文", raftComputerReleaseNotesText(notes.copy(en = null), "en-US"))
    }

    @Test
    fun diskFullUpgradeFailureSetsUpgradeDiskFullAndClearsFailureHintAndRepairAdvice() {
        val diskFull = SettingsComputerUpgradeDiskFullSnapshot(neededBytes = 812_000_000L, freeBytes = 300_000_000L)
        val failedRow =
            request(id = "req-disk", state = "failed", target = "1.0.54").copy(
                observedVersion = "1.0.53",
                failureHint = SettingsComputerUpgradeFailureHintSnapshot(reason = "earlier_recovery_pending"),
                diskFull = diskFull,
            )
        val active =
            model(
                computer(
                    version = "1.0.53",
                    latest = "1.0.54",
                    policy = eligible("1.0.54"),
                    upgradeAvailable = true,
                    row = failedRow,
                ).copy(installerRepairHint = "unrecovered_operation")
            )
        assertEquals(RaftComputerCardState.OneClick("1.0.54"), active.card)
        assertEquals(diskFull, active.upgradeDiskFull)
        assertNull(active.upgradeFailureHint)
        assertFalse(active.recommendRepair)

        // Clears once the Computer moves off the version it failed on.
        val moved =
            model(
                computer(
                    version = "1.0.53-rc.2",
                    latest = "1.0.54",
                    policy = eligible("1.0.54"),
                    upgradeAvailable = true,
                    row = failedRow,
                )
            )
        assertNull(moved.upgradeDiskFull)

        // Not active when one-click is unavailable.
        val nonOneClick =
            model(
                computer(
                    version = "1.0.53",
                    latest = "1.0.54",
                    policy = noBroadcast("broadcast_disabled"),
                    upgradeAvailable = false,
                    row = failedRow,
                )
            )
        assertNull(nonOneClick.upgradeDiskFull)
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
