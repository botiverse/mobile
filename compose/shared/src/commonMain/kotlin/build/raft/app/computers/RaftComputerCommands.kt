package build.raft.app

import build.raft.app.data.SettingsComputerCommandContext

/*
 * The `raft-computer` terminal commands of the computer detail page: a port of the web's
 * `getComputerCommands` (botiverse/slock packages/web/src/utils/computerSetupCommand.ts, staging
 * 6b367a0cd40adf0532ae0fe226091a8dbd395140). Keep the strings byte-for-byte equal to the web's so a
 * command copied from the app and one copied from the browser behave the same.
 */

internal const val RAFT_COMPUTER_STAGING_SERVER_URL = "https://api-aws-staging.botiverse.dev"
internal const val RAFT_COMPUTER_DEFAULT_SERVER_URL = "https://api.raft.build"
internal const val RAFT_COMPUTER_LEGACY_DEFAULT_SERVER_URL = "https://api.slock.ai"
internal const val RAFT_COMPUTER_CDN_BASE_STAGING = "https://slock-cdn-staging.botiverse.dev/computer/staging"
internal const val RAFT_COMPUTER_CDN_BASE_PROD = "https://cdn.raft.build/computer"

/** Web `statusCommandText` fallback: the status command when the page has no server slug. */
internal const val RAFT_COMPUTER_STATUS_COMMAND = "raft-computer status"

internal enum class RaftComputerCommandPlatform { MacLinux, Windows }

/** Web `ComputerCommands`. */
internal data class RaftComputerCommandSet(
    val install: String,
    val setup: String,
    /** mac/Linux production only: install then setup in one paste; null where they stay two steps. */
    val installAndSetup: String?,
    val status: String,
    val doctor: String,
    /** Restart the whole Computer service and every attached server runner. */
    val restartService: String,
    /** Restart while scoping lifecycle readback to this server. */
    val restart: String,
    val stop: String,
    val start: String,
)

/** A machine whose reported OS starts with `win` gets the PowerShell commands (web `windowsMachine`). */
internal fun raftComputerCommandPlatform(os: String?): RaftComputerCommandPlatform =
    if (os?.trim()?.lowercase()?.startsWith("win") == true) RaftComputerCommandPlatform.Windows else RaftComputerCommandPlatform.MacLinux

private val raftComputerVersionPin = Regex("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")

private fun raftComputerVersionPinOrNull(version: String?): String? =
    version?.trim()?.takeIf { raftComputerVersionPin.matches(it) }

private fun raftComputerCdnBase(deploymentEnv: String): String =
    if (deploymentEnv == "staging") RAFT_COMPUTER_CDN_BASE_STAGING else RAFT_COMPUTER_CDN_BASE_PROD

/** Web `computerInstallCommand`. */
internal fun raftComputerInstallCommand(deploymentEnv: String, version: String? = null): String {
    val base = raftComputerCdnBase(deploymentEnv)
    val pin = raftComputerVersionPinOrNull(version)
    val installEnv = buildList {
        if (deploymentEnv == "staging") {
            add("RAFT_COMPUTER_RELEASE_BASE=$base")
            add("RAFT_COMPUTER_INSTALL_CHANNEL=alpha")
        }
        if (pin != null) add("RAFT_COMPUTER_VERSION=$pin")
    }
    val env = if (installEnv.isEmpty()) "" else installEnv.joinToString(" ") + " "
    return "curl -fsSL $base/install.sh | ${env}sh"
}

/** Web `windowsComputerInstallCommand`. */
internal fun raftComputerWindowsInstallCommand(deploymentEnv: String, version: String? = null): String {
    val base = raftComputerCdnBase(deploymentEnv)
    val pin = raftComputerVersionPinOrNull(version)
    val installEnv = buildList {
        if (deploymentEnv == "staging") {
            add("\$env:RAFT_COMPUTER_RELEASE_BASE = \"$base\"")
            add("\$env:RAFT_COMPUTER_INSTALL_CHANNEL = \"alpha\"")
        }
        if (pin != null) add("\$env:RAFT_COMPUTER_VERSION = \"$pin\"")
    }
    val installUrl = if (deploymentEnv == "staging") "\"\$env:RAFT_COMPUTER_RELEASE_BASE/install.ps1\"" else "$base/install.ps1"
    val env = if (installEnv.isEmpty()) "" else installEnv.joinToString("; ") + "; "
    return "${env}irm $installUrl | iex"
}

private fun raftComputerIsDefaultServerUrl(serverUrl: String?): Boolean {
    val normalized = serverUrl?.trim()?.trimEnd('/')
    return normalized == RAFT_COMPUTER_DEFAULT_SERVER_URL || normalized == RAFT_COMPUTER_LEGACY_DEFAULT_SERVER_URL
}

/** staging / slockdev are internal test surfaces: every command carries a per-server home and binary. */
private val raftComputerIsolatedDeploymentEnvs = setOf("staging", "slockdev")

/**
 * Web `getComputerCommands(serverSlug, deploymentEnv, serverUrl, { machineId, platform, version })`;
 * null without a server slug. [machineId] is only passed for rows still run by the retired daemon
 * (`setup --machine <id>` adopts that row).
 */
internal fun raftComputerCommands(
    context: SettingsComputerCommandContext?,
    platform: RaftComputerCommandPlatform = RaftComputerCommandPlatform.MacLinux,
    machineId: String? = null,
    version: String? = null,
): RaftComputerCommandSet? {
    val slug = context?.serverSlug?.trim()?.trimStart('/')?.takeIf { it.isNotBlank() } ?: return null
    val deploymentEnv = context.deploymentEnv
    val serverUrl = context.serverUrl
    val commandServerUrl =
        when (deploymentEnv) {
            "production" -> serverUrl.takeUnless { raftComputerIsDefaultServerUrl(it) }
            "staging" -> RAFT_COMPUTER_STAGING_SERVER_URL
            "slockdev" -> serverUrl
            else -> null
        }?.takeIf { it.isNotBlank() }
    val serverUrlArg = commandServerUrl?.let { " --server-url $it" }.orEmpty()
    val machineArg = machineId?.trim()?.takeIf { it.isNotBlank() }?.let { " --machine $it" }.orEmpty()
    val setupArgs = "$serverUrlArg$machineArg"
    if (deploymentEnv in raftComputerIsolatedDeploymentEnvs) {
        if (platform == RaftComputerCommandPlatform.Windows) {
            val home = "\$env:USERPROFILE\\.raft-computer-$slug"
            val environment = "\$env:RAFT_HOME = \"$home\"; \$env:RAFT_COMPUTER_INSTALL_DIR = \"\$env:RAFT_HOME\\bin\";"
            val binary = "& \"\$env:RAFT_COMPUTER_INSTALL_DIR\\raft-computer.exe\""
            return RaftComputerCommandSet(
                install = "$environment ${raftComputerWindowsInstallCommand(deploymentEnv, version)}",
                setup = "$environment $binary setup /$slug$setupArgs",
                installAndSetup = null,
                status = "$environment $binary status",
                doctor = "$environment $binary doctor",
                restartService = "$environment $binary restart",
                restart = "$environment $binary restart /$slug",
                stop = "$environment $binary stop",
                start = "$environment $binary start",
            )
        }
        val home = "\$HOME/.raft-computer-$slug"
        val environment = "RAFT_HOME=\"$home\" RAFT_COMPUTER_INSTALL_DIR=\"$home/bin\""
        val binary = "\"$home/bin/raft-computer\""
        return RaftComputerCommandSet(
            install = "$environment sh -c '${raftComputerInstallCommand(deploymentEnv, version)}'",
            setup = "$environment $binary setup /$slug$setupArgs",
            installAndSetup = null,
            status = "$environment $binary status",
            doctor = "$environment $binary doctor",
            restartService = "$environment $binary restart",
            restart = "$environment $binary restart /$slug",
            stop = "$environment $binary stop",
            start = "$environment $binary start",
        )
    }
    val windows = platform == RaftComputerCommandPlatform.Windows
    return RaftComputerCommandSet(
        install = if (windows) raftComputerWindowsInstallCommand(deploymentEnv, version) else raftComputerInstallCommand(deploymentEnv, version),
        setup = "raft-computer setup /$slug$setupArgs",
        installAndSetup =
            if (windows) null else "${raftComputerInstallCommand(deploymentEnv, version)} -s -- --setup /$slug$setupArgs",
        status = "raft-computer status",
        doctor = "raft-computer doctor",
        restartService = "raft-computer restart",
        restart = "raft-computer restart /$slug",
        stop = "raft-computer stop",
        start = "raft-computer start",
    )
}
