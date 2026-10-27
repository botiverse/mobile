package build.raft.app.data

import build.raft.app.model.ServerNotificationPreferencesChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-level consumer of the viewer's server notification preference push (`notification_prefs:updated`
 * carrying a server mode, mapped to [ServerNotificationPreferencesChanged]). Whether or not a Settings page is
 * open, the event refreshes the durable last-known copy ([SettingsServerNotificationLastKnownStore]) by
 * refetching the preference through [refresh] (the repository writes the copy on success). The payload is
 * not trusted as the value: a refetch is cheap and always answers for the signed-in account.
 *
 * Single-flight per (account, server): a burst coalesces into the in-flight refetch plus at most one
 * follow-up. Events for different servers refresh independently.
 */
internal class ServerNotificationPreferencesRealtimeRefresher(
    private val scope: CoroutineScope,
    private val refresh: suspend (principalId: String, serverId: String) -> Unit,
    private val logger: (String) -> Unit = {},
) {
    private data class Key(val principalId: String, val serverId: String)

    private val mutex = Mutex()
    private val inFlight = mutableSetOf<Key>()
    private val pending = mutableSetOf<Key>()

    /** Process realtime sink entry: [eventServerId] is the server the frame was accepted for. */
    suspend fun onEvent(principalId: String, eventServerId: String, event: ServerNotificationPreferencesChanged) {
        val serverId = event.serverId.value.trim()
        if (serverId.isBlank() || serverId != eventServerId.trim()) return
        request(principalId, serverId)
    }

    suspend fun request(principalId: String, serverId: String) {
        val cleanPrincipal = principalId.trim().takeIf { it.isNotBlank() } ?: return
        val cleanServer = serverId.trim().takeIf { it.isNotBlank() } ?: return
        val key = Key(cleanPrincipal, cleanServer)
        val start =
            mutex.withLock {
                if (key in inFlight) {
                    pending += key
                    false
                } else {
                    inFlight += key
                    true
                }
            }
        if (!start) {
            logger("server_notification_realtime_refresh_coalesce serverId=$cleanServer")
            return
        }
        scope.launch { drain(key) }
    }

    private suspend fun drain(key: Key) {
        var released = false
        try {
            while (true) {
                // Events that landed before this GET starts are answered by it: only a change that arrives
                // while it is in flight needs a follow-up.
                mutex.withLock { pending.remove(key) }
                try {
                    refresh(key.principalId, key.serverId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    logger(
                        "server_notification_realtime_refresh_failed serverId=${key.serverId} " +
                            "error=${error::class.simpleName}"
                    )
                }
                // Checking for a follow-up and leaving the in-flight set is one step, so an event that
                // lands in between is never lost.
                val again =
                    mutex.withLock {
                        if (pending.remove(key)) {
                            true
                        } else {
                            inFlight -= key
                            released = true
                            false
                        }
                    }
                if (!again) break
            }
        } finally {
            if (!released) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        inFlight -= key
                        pending -= key
                    }
                }
            }
        }
    }
}
