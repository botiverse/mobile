package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftRatingPrompt
import build.raft.app.data.RaftRatingPromptAccount
import build.raft.app.data.RaftRatingPromptShellContext
import build.raft.app.data.SharedComputersStore

/**
 * Lets the rating prompt ([RaftRatingPrompt]) see the signed-in shell's account and the current
 * server's Computers while this shell is shown. Composed once by the app shell; owns nothing else.
 */
@Composable
internal fun RaftRatingPromptShellBinding(
    authStore: RaftAuthSessionStore,
    computersStore: SharedComputersStore,
) {
    DisposableEffect(authStore, computersStore) {
        val bound = RaftRatingPrompt.coordinator.bindShell(RaftShellRatingPromptContext(authStore, computersStore))
        onDispose { RaftRatingPrompt.coordinator.unbindShell(bound) }
    }
}

/** The shell's session (user, current server, every joined server) and its Computers store. */
internal class RaftShellRatingPromptContext(
    private val authStore: RaftAuthSessionStore,
    private val computersStore: SharedComputersStore,
) : RaftRatingPromptShellContext {
    override suspend fun account(): RaftRatingPromptAccount? {
        val session = authStore.readSession() ?: return null
        val userId = session.user.id.trim().takeIf { it.isNotEmpty() } ?: return null
        return RaftRatingPromptAccount(
            userId = userId,
            currentServerId = session.currentServerId?.trim()?.takeIf { it.isNotEmpty() },
            joinedServerIds = session.servers.map { it.id },
        )
    }

    /**
     * Any machine in the server's machine list: the list held in memory or on disk, else one
     * background load of it (only reached when every other rule already allows the prompt).
     */
    override suspend fun serverHasComputer(serverId: String): Boolean? {
        val known =
            (computersStore.cachedSettingsComputersForServer(serverId) as? DataResult.Content)?.data
                ?: run {
                    computersStore.ensureComputerPickerForServer(serverId, priority = DataRequestPriority.Background)
                    computersStore.currentSettingsComputers(serverId)
                }
                ?: return null
        return known.computers.isNotEmpty()
    }
}
