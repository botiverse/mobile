package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Response of `GET /api/integrations/permission-updates` (botiverse/slock
 * `packages/server/src/routes/integrations.ts` & `useAppPermissionUpdates.ts`):
 * - [count]: installed Apps with added permissions waiting for approval (`approvalRequired`).
 * - [needsAdminReview]: whether anything on the Apps page awaits an owner or admin (a permission
 *   update or prompt text confirmation); lights the Apps attention dot ([build.raft.app.RaftMoreAttention.AppsReview]).
 */
internal data class AppPermissionUpdatesSummary(
    val count: Int,
    val needsAdminReview: Boolean,
)

@Serializable
private data class AppPermissionUpdatesSummaryDto(
    val count: Int? = null,
    val needsAdminReview: Boolean? = null,
)

private val appPermissionUpdatesJson = Json { ignoreUnknownKeys = true }

/**
 * Decodes `GET /integrations/permission-updates`. Fails closed (returns null) on malformed bodies
 * or negative counts. When an older server omits `needsAdminReview`, falls back to `count > 0`.
 */
internal fun parseAppPermissionUpdatesSummary(bodyText: String?): AppPermissionUpdatesSummary? {
    val trimmed = bodyText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val dto =
        runCatching { appPermissionUpdatesJson.decodeFromString(AppPermissionUpdatesSummaryDto.serializer(), trimmed) }
            .getOrNull() ?: return null
    val count = dto.count?.takeIf { it >= 0 } ?: return null
    val needsAdminReview = dto.needsAdminReview ?: (count > 0)
    return AppPermissionUpdatesSummary(count = count, needsAdminReview = needsAdminReview)
}

internal interface AppPermissionUpdatesDataSource {
    suspend fun readSummary(serverId: ServerId): AppPermissionUpdatesSummary?
}

internal class NetworkAppPermissionUpdatesDataSource(
    private val networkClient: RaftNetworkClient,
) : AppPermissionUpdatesDataSource {
    override suspend fun readSummary(serverId: ServerId): AppPermissionUpdatesSummary? {
        val response =
            networkClient.request(
                priority = DataRequestPriority.Background,
                path = "integrations/permission-updates",
                serverIdOverride = serverId,
            )
        if (response.code !in 200..299) return null
        return parseAppPermissionUpdatesSummary(response.bodyText)
    }
}

internal data class SharedAppPermissionUpdatesState(
    /** The server whose review state this holds; anything loaded for another server is dropped. */
    val serverId: String? = null,
    val count: Int = 0,
    val needsAdminReview: Boolean = false,
)

/**
 * Server-scoped store for `GET /integrations/permission-updates` (web `useAppPermissionUpdatesSummary`
 * + `useAppsPageAttention`, #proj-mobile:#345).
 *
 * - Scoped to owners and admins (`manageIntegrations`): non-admins, guests, and missing servers
 *   never issue a request and immediately clear to `needsAdminReview = false`.
 * - Server switch clears the old server's dot immediately and drops any late answer from it.
 * - Single-flight per server; a `permission-updates:changed` ping or refresh arriving while a read
 *   is in flight schedules one follow-up read once it finishes (matching web `again`).
 * - Network / HTTP / decode errors fail closed (`needsAdminReview = false`, never crash).
 */
internal class SharedAppPermissionUpdatesStore(
    private val dataSource: AppPermissionUpdatesDataSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SharedAppPermissionUpdatesState())
    val state: StateFlow<SharedAppPermissionUpdatesState> = mutableState.asStateFlow()

    private var activeServerId: String? = null
    private var activeCanManage: Boolean = false
    private var generation: Long = 0L
    private var loadJob: Job? = null
    private var rereadPending: Boolean = false

    fun load(serverId: String?, canManageIntegrations: Boolean, force: Boolean = false) {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanServerId == null || !canManageIntegrations) {
            activeServerId = cleanServerId
            activeCanManage = false
            generation += 1L
            loadJob?.cancel()
            loadJob = null
            rereadPending = false
            clearState(cleanServerId)
            return
        }
        val serverChanged = activeServerId != cleanServerId
        val newlyAuthorized = !activeCanManage
        activeServerId = cleanServerId
        activeCanManage = true
        if (serverChanged) {
            // Drop the previous server's review dot before the new server answers.
            generation += 1L
            loadJob?.cancel()
            loadJob = null
            rereadPending = false
            clearState(cleanServerId)
        }
        if (loadJob?.isActive == true) {
            if (force || newlyAuthorized) rereadPending = true
            return
        }
        if (!force && !serverChanged && !newlyAuthorized && mutableState.value.serverId == cleanServerId) {
            return
        }
        startRead(cleanServerId)
    }

    /** `permission-updates:changed` for [serverId]: refresh now (or once the in-flight read finishes). */
    fun onChanged(serverId: String) {
        val clean = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        if (!activeCanManage || activeServerId != clean) return
        if (loadJob?.isActive == true) {
            rereadPending = true
            return
        }
        startRead(clean)
    }

    /** Reconnect, foreground, or returning from Apps: re-read for the active server if authorized. */
    fun refresh(serverId: String? = activeServerId) {
        val target = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        onChanged(target)
    }

    fun clear() {
        activeServerId = null
        activeCanManage = false
        generation += 1L
        loadJob?.cancel()
        loadJob = null
        rereadPending = false
        clearState(null)
    }

    private fun clearState(serverId: String?) {
        val current = mutableState.value
        if (current.serverId != serverId || current.count != 0 || current.needsAdminReview) {
            mutableState.value = SharedAppPermissionUpdatesState(serverId = serverId)
        }
    }

    private fun startRead(serverId: String) {
        generation += 1L
        val readGeneration = generation
        rereadPending = false
        loadJob =
            scope.launch {
                val summary =
                    try {
                        dataSource.readSummary(ServerId(serverId))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                if (readGeneration != generation || !activeCanManage || activeServerId != serverId) {
                    return@launch
                }
                loadJob = null
                if (rereadPending) {
                    rereadPending = false
                    startRead(serverId)
                    return@launch
                }
                val next =
                    if (summary != null) {
                        SharedAppPermissionUpdatesState(
                            serverId = serverId,
                            count = summary.count,
                            needsAdminReview = summary.needsAdminReview,
                        )
                    } else {
                        // Fail closed on network/HTTP/decode error, matching web useAppPermissionUpdates.ts.
                        SharedAppPermissionUpdatesState(serverId = serverId)
                    }
                if (mutableState.value != next) {
                    mutableState.value = next
                }
            }
    }
}
