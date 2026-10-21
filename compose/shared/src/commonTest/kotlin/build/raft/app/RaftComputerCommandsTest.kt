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
    fun aLegacyDaemonRowAdoptsItselfAndANonDefaultServerUrlIsPassed() {
        val commands =
            requireNotNull(raftComputerCommands(production("https://api.example.dev"), machineId = "machine-9"))

        assertEquals("raft-computer setup /acme --server-url https://api.example.dev --machine machine-9", commands.setup)
    }

    @Test
    fun stagingIsolatesTheHomeAndBinaryPerServer() {
        val context = SettingsComputerCommandContext(serverSlug = "/acme", deploymentEnv = "staging", serverUrl = "https://ignored")
        val commands = requireNotNull(raftComputerCommands(context))
        val environment = "RAFT_HOME=\"\$HOME/.raft-computer-acme\" RAFT_COMPUTER_INSTALL_DIR=\"\$HOME/.raft-computer-acme/bin\""
        val binary = "\"\$HOME/.raft-computer-acme/bin/raft-computer\""

        assertEquals(
            "$environment sh -c 'curl -fsSL https://slock-cdn-staging.botiverse.dev/computer/staging/install.sh | " +
                "RAFT_COMPUTER_RELEASE_BASE=https://slock-cdn-staging.botiverse.dev/computer/staging RAFT_COMPUTER_INSTALL_CHANNEL=alpha sh'",
            commands.install
        )
        assertEquals("$environment $binary setup /acme --server-url https://api-aws-staging.botiverse.dev", commands.setup)
        assertEquals("$environment $binary restart /acme", commands.restart)
        assertNull(commands.installAndSetup)
    }

    @Test
    fun stagingWindowsCarriesThePowerShellEnvironment() {
        val context = SettingsComputerCommandContext(serverSlug = "acme", deploymentEnv = "staging")
        val commands = requireNotNull(raftComputerCommands(context, RaftComputerCommandPlatform.Windows))
        val environment = "\$env:RAFT_HOME = \"\$env:USERPROFILE\\.raft-computer-acme\"; \$env:RAFT_COMPUTER_INSTALL_DIR = \"\$env:RAFT_HOME\\bin\";"

        assertEquals(
            "$environment \$env:RAFT_COMPUTER_RELEASE_BASE = \"https://slock-cdn-staging.botiverse.dev/computer/staging\"; " +
                "\$env:RAFT_COMPUTER_INSTALL_CHANNEL = \"alpha\"; irm \"\$env:RAFT_COMPUTER_RELEASE_BASE/install.ps1\" | iex",
            commands.install
        )
        assertEquals("$environment & \"\$env:RAFT_COMPUTER_INSTALL_DIR\\raft-computer.exe\" status", commands.status)
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
