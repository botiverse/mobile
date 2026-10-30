package build.raft.app

import build.raft.app.data.SettingsComputerBroadcastPolicySnapshot
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsComputerControlState
import build.raft.app.data.SettingsComputerRestartProgress
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerUpgradeRequest
import build.raft.app.data.SettingsComputerUpgradeRequestSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A rack card's one next step (web botiverse/slock rack/ComputersRack.tsx `RackRowAction`). */
class RaftComputersRackActionTest {
    private val now = 1_792_635_000_000L
    // Staging: its commands differ per platform (PowerShell on Windows), so the platform shows.
    private val context = SettingsComputerCommandContext(serverSlug = "acme", deploymentEnv = "staging")

    private fun computer(
        online: Boolean = true,
        canManage: Boolean = true,
        isComputer: Boolean = true,
        version: String = "1.0.49",
        platform: String = "linux x64",
        upgradeRequest: SettingsComputerUpgradeRequestSnapshot? = null,
        lastHeartbeat: String? = null,
    ) = SettingsComputerSnapshot(
        id = "m1",
        name = "build-box",
        daemonVersion = "",
        computerVersion = version,
        isComputer = isComputer,
        online = online,
        platform = platform,
        upgradeAvailable = true,
        remoteUpgradeSupported = true,
        broadcastPolicy = SettingsComputerBroadcastPolicySnapshot(eligibility = "eligible", targetVersion = "1.0.53"),
        latestComputerVersion = "1.0.53",
        upgradeRequest = upgradeRequest,
        lastHeartbeat = lastHeartbeat,
        canManage = canManage,
    )

    private fun action(
        computer: SettingsComputerSnapshot,
        control: SettingsComputerControlState = SettingsComputerControlState(),
        commandContext: SettingsComputerCommandContext? = context,
    ) = raftComputerRackAction(
        computer = computer,
        upgrade = raftComputerUpgradeModel(computer, control, now),
        restart = control.restart,
        commandContext = commandContext,
    )

    @Test
    fun onlineWithAnUpgradeOffersItsVersion() {
        assertEquals(RaftComputerRackAction.Upgrade("1.0.53"), action(computer()))
    }

    @Test
    fun upgradeAskedForOrPendingReadsUpgrading() {
        val submitting = SettingsComputerControlState(upgradeSubmitting = SettingsComputerUpgradeRequest(machineId = "m1", targetVersion = "1.0.53"))
        assertEquals(RaftComputerRackAction.Upgrading, action(computer(), submitting))

        val pending = SettingsComputerUpgradeRequestSnapshot(id = "u1", targetVersion = "1.0.53", requestedAt = "2026-10-22T09:00:00Z", state = "pending")
        assertEquals(RaftComputerRackAction.Upgrading, action(computer(upgradeRequest = pending)))
    }

    @Test
    fun aRestartOfThisComputerShowsItsProgressBeforeTheUpgrade() {
        val running = SettingsComputerRestartProgress(machineId = "m1")
        assertEquals(RaftComputerRackAction.Restarting(running), action(computer(), SettingsComputerControlState(restart = running)))

        // Another computer's restart leaves this card's step alone.
        val other = SettingsComputerControlState(restart = SettingsComputerRestartProgress(machineId = "m2"))
        assertEquals(RaftComputerRackAction.Upgrade("1.0.53"), action(computer(), other))
    }

    @Test
    fun offlineShowsTheRestartCommandForItsPlatform() {
        val unix = assertIs<RaftComputerRackAction.OfflineRestart>(action(computer(online = false)))
        val windows = assertIs<RaftComputerRackAction.OfflineRestart>(action(computer(online = false, platform = "win32 x64")))
        assertEquals(raftComputerCommands(context)?.restart, unix.command)
        assertEquals(raftComputerCommands(context, RaftComputerCommandPlatform.Windows)?.restart, windows.command)
        assertTrue(unix.command != windows.command, "the Windows command differs: ${unix.command}")

        // No server slug yet: no command to show.
        assertNull(action(computer(online = false), commandContext = null))
    }

    @Test
    fun offlineWhileRestartingForItsUpgradeShowsNothing() {
        val pending = SettingsComputerUpgradeRequestSnapshot(id = "u1", targetVersion = "1.0.53", requestedAt = "2026-10-22T02:08:00Z", state = "pending")
        assertNull(action(computer(online = false, upgradeRequest = pending, lastHeartbeat = "2026-10-22T02:09:30Z")))
    }

    @Test
    fun nothingForViewersWithoutPermissionDaemonsOrUpToDateComputers() {
        assertNull(action(computer(canManage = false)))
        assertNull(action(computer(online = false, canManage = false)))
        assertNull(action(computer(isComputer = false)))
        assertNull(action(computer(version = "1.0.53")))
    }
}
