package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

@RaftCoreInternalApi data class SharedSeatJoinBlocksState(
    /** The server whose blocks [blocks] holds; rows for another server are never shown. */
    val serverId: String? = null,
    val blocks: List<SeatJoinBlock> = emptyList(),
    val usage: SeatJoinSeatUsage? = null,
    /** Locally dismissed per-notice block IDs (`seat-join-blocked:${block.id}`): a later block has a new ID and shows. */
    val dismissedBlockIds: Set<String> = emptySet(),
) {
    val visibleBlocks: List<SeatJoinBlock>
        get() = blocks.filterNot { it.id in dismissedBlockIds }
}

@RaftCoreInternalApi sealed interface SharedSeatJoinBlocksIntent {
    /**
     * Load seat-join-blocked notices for [serverId]. Only non-guest roles when [proSeatAddEnabled]
     * is true issue network requests; guests, flag-off servers, and null servers clear state with
     * zero requests.
     */
    data class Load(
        val serverId: String?,
        val proSeatAddEnabled: Boolean,
        val role: String?,
        val force: Boolean = false,
    ) : SharedSeatJoinBlocksIntent

    /** `seat-join-blocks:changed` realtime ping for [serverId]: re-read immediately if active. */
    data class Changed(val serverId: String) : SharedSeatJoinBlocksIntent

    /** Explicit refresh (pull-to-refresh, periodic safety poll, or after sending a seat request). */
    data object Refresh : SharedSeatJoinBlocksIntent

    /** Foreground / reconnect / network restore: re-read when last read is stale. */
    data object RefreshIfStale : SharedSeatJoinBlocksIntent

    /** Per-notice local dismissal (matching web `dismissalKey: "seat-join-blocked:${block.id}"`). */
    data class Dismiss(val blockId: String) : SharedSeatJoinBlocksIntent
}

private val SeatJoinBlocksClockOrigin = TimeSource.Monotonic.markNow()

/**
 * Server-scoped store for `GET /api/servers/:id/seat-join-blocks` (`botiverse/slock` `#11576`,
 * web `useSeatJoinBlocks.ts`).
 *
 * - Flag & role gated: fetches only when `serverId != null && proSeatAddEnabled && role != null && role != "guest"`.
 * - Generation guarded: responses from a prior server or superseded load are discarded.
 * - Matches web error policy: a failed refresh preserves the last known list for the current server
 *   so the next realtime ping or safety poll retries cleanly, while switching servers or disabling
 *   the gate clears immediately.
 */
@RaftCoreInternalApi class SharedSeatJoinBlocksStore(
    private val dataSource: SeatJoinBlocksDataSource,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { SeatJoinBlocksClockOrigin.elapsedNow().inWholeMilliseconds },
) {
    private val mutableState = MutableStateFlow(SharedSeatJoinBlocksState())
    val state: StateFlow<SharedSeatJoinBlocksState> = mutableState.asStateFlow()

    private var activeServerId: String? = null
    private var activeCanRead: Boolean = false
    private var serverGeneration: Long = 0L
    private var loadGeneration: Long = 0L
    private var loadJob: Job? = null
    private var lastReadStartedAtMillis: Long? = null

    fun dispatch(intent: SharedSeatJoinBlocksIntent) {
        when (intent) {
            is SharedSeatJoinBlocksIntent.Load -> load(intent)
            is SharedSeatJoinBlocksIntent.Changed -> {
                val cleanServerId = intent.serverId.trim().takeIf { it.isNotEmpty() } ?: return
                if (activeCanRead && activeServerId == cleanServerId) {
                    startRead(cleanServerId, cancelInFlight = true)
                }
            }
            SharedSeatJoinBlocksIntent.Refresh -> {
                val serverId = activeServerId
                if (serverId != null && activeCanRead) {
                    startRead(serverId, cancelInFlight = true)
                }
            }
            SharedSeatJoinBlocksIntent.RefreshIfStale -> {
                val serverId = activeServerId
                if (serverId == null || !activeCanRead) return
                val last = lastReadStartedAtMillis
                if (last == null || nowMillis() - last >= SEAT_JOIN_BLOCKS_MIN_REREAD_MS) {
                    startRead(serverId, cancelInFlight = false)
                }
            }
            is SharedSeatJoinBlocksIntent.Dismiss -> {
                val cleanId = intent.blockId.trim().takeIf { it.isNotEmpty() } ?: return
                val current = mutableState.value
                if (cleanId !in current.dismissedBlockIds) {
                    mutableState.value = current.copy(dismissedBlockIds = current.dismissedBlockIds + cleanId)
                }
            }
        }
    }

    fun clear() {
        activeServerId = null
        activeCanRead = false
        serverGeneration += 1L
        loadGeneration += 1L
        loadJob?.cancel()
        loadJob = null
        lastReadStartedAtMillis = null
        val current = mutableState.value
        val next = SharedSeatJoinBlocksState(serverId = null, dismissedBlockIds = current.dismissedBlockIds)
        if (current != next) mutableState.value = next
    }

    private fun load(intent: SharedSeatJoinBlocksIntent.Load) {
        val cleanServerId = intent.serverId?.trim()?.takeIf { it.isNotEmpty() }
        val canRead = cleanServerId != null && intent.proSeatAddEnabled && serverRoleCanReadSeatJoinBlocks(intent.role)
        if (!canRead || cleanServerId == null) {
            val changed = activeServerId != cleanServerId || activeCanRead
            activeServerId = cleanServerId
            activeCanRead = false
            if (changed || loadJob != null) {
                serverGeneration += 1L
                loadGeneration += 1L
                loadJob?.cancel()
                loadJob = null
                lastReadStartedAtMillis = null
            }
            clearServerRows(cleanServerId)
            return
        }
        val serverChanged = activeServerId != cleanServerId
        val newlyEnabled = !activeCanRead
        activeServerId = cleanServerId
        activeCanRead = true
        if (serverChanged || newlyEnabled) {
            serverGeneration += 1L
            loadGeneration += 1L
            loadJob?.cancel()
            loadJob = null
            lastReadStartedAtMillis = null
            clearServerRows(cleanServerId)
            startRead(cleanServerId, cancelInFlight = true)
            return
        }
        if (intent.force) {
            startRead(cleanServerId, cancelInFlight = true)
        } else if (loadJob?.isActive != true && lastReadStartedAtMillis == null) {
            startRead(cleanServerId, cancelInFlight = false)
        }
    }

    private fun startRead(serverId: String, cancelInFlight: Boolean) {
        if (!cancelInFlight && loadJob?.isActive == true) return
        loadJob?.cancel()
        loadGeneration += 1L
        val mineLoad = loadGeneration
        val mineServer = serverGeneration
        lastReadStartedAtMillis = nowMillis()
        loadJob =
            scope.launch {
                val result =
                    try {
                        dataSource.list(ServerId(serverId))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
                    }
                if (mineLoad != loadGeneration || mineServer != serverGeneration || !activeCanRead || activeServerId != serverId) {
                    return@launch
                }
                loadJob = null
                if (result is DataResult.Content) {
                    val current = mutableState.value
                    val next =
                        current.copy(
                            serverId = serverId,
                            blocks = result.data.blocks,
                            usage = result.data.usage,
                        )
                    if (current != next) mutableState.value = next
                }
            }
    }

    private fun clearServerRows(serverId: String?) {
        val current = mutableState.value
        val next = current.copy(serverId = serverId, blocks = emptyList(), usage = null)
        if (current != next) mutableState.value = next
    }
}
