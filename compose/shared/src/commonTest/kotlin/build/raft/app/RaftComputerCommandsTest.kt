package build.raft.app

import build.raft.app.data.SettingsComputerCommandContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Expected strings are the web's `getComputerCommands` output (botiverse/slock staging
 * 6b367a0cd40adf0532ae0fe226091a8dbd395140, packages/web/src/utils/computerSetupCommand.ts).
 */
class RaftComputerCommandsTest {
    @Test
    fun productionMacLinuxUsesTheInstalledBinaryAndNoServerUrl() {
        val commands = requireNotNull(raftComputerCommands(production("https://api.raft.build/")))

        assertEquals("curl -fsSL https://cdn.raft.build/computer/install.sh | sh", commands.install)
        assertEquals("raft-computer setup /acme", commands.setup)
        assertEquals("curl -fsSL https://cdn.raft.build/computer/install.sh | sh -s -- --setup /acme", commands.installAndSetup)
        assertEquals("raft-computer status", commands.status)
        assertEquals("raft-computer doctor", commands.doctor)
        assertEquals("raft-computer restart", commands.restartService)
        assertEquals("raft-computer restart /acme", commands.restart)
    }

    @Test
    fun productionWindowsUsesThePowerShellInstallerInTwoSteps() {
        val commands = requireNotNull(raftComputerCommands(production(null), RaftComputerCommandPlatform.Windows))

        assertEquals("irm https://cdn.raft.build/computer/install.ps1 | iex", commands.install)
        assertNull(commands.installAndSetup)
        assertEquals("raft-computer setup /acme", commands.setup)
    }

    @Test
    fun noServerSlugMeansNoCommands() {
        assertNull(raftComputerCommands(SettingsComputerCommandContext(serverSlug = " / ", deploymentEnv = "production")))
        assertNull(raftComputerCommands(null))
    }

    @Test
    fun windowsMachinesAreTheOnesReportingAWindowsOs() {
        assertEquals(RaftComputerCommandPlatform.Windows, raftComputerCommandPlatform("Windows_NT"))
        assertEquals(RaftComputerCommandPlatform.Windows, raftComputerCommandPlatform("win32"))
        assertEquals(RaftComputerCommandPlatform.MacLinux, raftComputerCommandPlatform("darwin"))
        assertEquals(RaftComputerCommandPlatform.MacLinux, raftComputerCommandPlatform(null))
    }

    private fun production(serverUrl: String?) =
        SettingsComputerCommandContext(serverSlug = "acme", deploymentEnv = "production", serverUrl = serverUrl)
}
