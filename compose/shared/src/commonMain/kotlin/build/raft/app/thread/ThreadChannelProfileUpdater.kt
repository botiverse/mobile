package build.raft.app.thread

import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ThreadRouteIdentity
import build.raft.app.data.DataResult
import build.raft.app.data.MviStaleToken
import build.raft.app.model.ChannelId
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.thread.resolveThreadRouteIdentity
import kotlinx.coroutines.CancellationException

/**
 * Channel profile (name / description) updates made from the thread page, and applying a profile
 * update to the visible channel payload, for [SharedThreadStore].
 *
 * Holds no state, scope or Job: the store dispatches into it on its own scope, and every read and
 * write of store state goes through the accessors (the store's `_state`, its route stale token,
 * `reduceState` and `reducePayloadPatch`), so stale checks run at the same points as before.
 */
internal class ThreadChannelProfileUpdater(
    private val dataSource: SharedThreadDataSource,
    private val diagnosticsLogger: ((String) -> Unit)?,
    private val currentState: () -> SharedThreadState,
    private val currentRouteStaleToken: () -> MviStaleToken,
    private val reduceState: ((SharedThreadState) -> SharedThreadState) -> Boolean,
    private val reducePayloadPatch: (
        cachePayload: Boolean,
        patch: (RaftThreadScreenPayload) -> RaftThreadScreenPayload
    ) -> Boolean,
) {
    fun applyChannelProfile(intent: SharedThreadIntent.ApplyChannelProfile) {
        val routeIdentity = currentState().payload.resolveThreadRouteIdentity()
        if (routeIdentity.surfaceKind != RaftThreadSurfaceKind.Channel || routeIdentity.timelineChannelId != intent.channelId) return
        reducePayloadPatch(true) { payload ->
            payload.copy(title = intent.name, subtitle = intent.description)
        }
    }

    suspend fun updateChannelProfile(intent: SharedThreadIntent.UpdateChannelProfile) {
        val current = currentState()
        val routeIdentity = current.payload.resolveThreadRouteIdentity()
        val staleToken = currentRouteStaleToken()
        if (
            routeIdentity.surfaceKind != RaftThreadSurfaceKind.Channel ||
            routeIdentity.timelineChannelId != intent.channelId ||
            current.channelProfileUpdateStatus == ChannelProfileUpdateStatus.InFlight
        ) {
            return
        }
        val name = intent.name.trim()
        val description = intent.description.trim()
        val originalName = intent.originalName.trim()
        val originalDescription = intent.originalDescription.trim()
        if (name == originalName && description == originalDescription) {
            diagnosticsLogger?.invoke(
                "thread_channel_profile_update_skip channelId=${intent.channelId} reason=unchanged"
            )
            reduceState { latest ->
                latest.copy(
                    channelProfileUpdateStatus = ChannelProfileUpdateStatus.Succeeded,
                    channelProfileUpdateErrorCode = null
                )
            }
            return
        }
        diagnosticsLogger?.invoke(
            "thread_channel_profile_update_start channelId=${intent.channelId} " +
                "nameChanged=${name != originalName} descriptionChanged=${description != originalDescription}"
        )
        reduceState { latest ->
            latest.copy(
                channelProfileUpdateStatus = ChannelProfileUpdateStatus.InFlight,
                channelProfileUpdateErrorCode = null
            )
        }
        val result = dataSource.updateChannelProfile(ChannelId(intent.channelId), name, description)
        if (!channelProfileUpdateStillCurrent(staleToken, routeIdentity, intent.channelId, "after_network")) {
            return
        }
        when (result) {
            is DataResult.Content -> {
                val canonical = result.data
                if (canonical.channelId != ChannelId(intent.channelId)) {
                    diagnosticsLogger?.invoke(
                        "thread_channel_profile_update_identity_mismatch requested=${intent.channelId} " +
                            "response=${canonical.channelId.value}"
                    )
                    failChannelProfileUpdate("channel_update_identity_mismatch")
                    return
                }
                reducePayloadPatch(true) { payload ->
                    payload.copy(title = canonical.name, subtitle = canonical.description)
                }
                try {
                    dataSource.publishChannelProfileUpdate(canonical)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    diagnosticsLogger?.invoke(
                        "thread_channel_profile_update_projection_failed channelId=${canonical.channelId.value} " +
                            "exception=${exception::class.simpleName}"
                    )
                }
                if (!channelProfileUpdateStillCurrent(staleToken, routeIdentity, intent.channelId, "after_projection")) {
                    return
                }
                diagnosticsLogger?.invoke(
                    "thread_channel_profile_update_success channelId=${canonical.channelId.value}"
                )
                reduceState { latest ->
                    latest.copy(
                        channelProfileUpdateStatus = ChannelProfileUpdateStatus.Succeeded,
                        channelProfileUpdateErrorCode = null
                    )
                }
            }
            is DataResult.Error -> failChannelProfileUpdate(result.error.code)
            is DataResult.NoPermission -> failChannelProfileUpdate(result.blocker.code)
            is DataResult.Empty -> failChannelProfileUpdate("channel_update_empty")
        }
    }

    private fun channelProfileUpdateStillCurrent(
        staleToken: MviStaleToken,
        routeIdentity: ThreadRouteIdentity,
        channelId: String,
        stage: String
    ): Boolean {
        val currentPayload = currentState().payload
        if (
            staleToken.isCurrent(currentRouteStaleToken()) &&
            currentPayload.resolveThreadRouteIdentity() == routeIdentity
        ) {
            return true
        }
        diagnosticsLogger?.invoke(
            "thread_channel_profile_update_stale_drop channelId=$channelId stage=$stage " +
                "token=${staleToken.value} currentToken=${currentRouteStaleToken().value} " +
                "currentRoute=${currentPayload.resolveThreadRouteIdentity().instanceKey}"
        )
        return false
    }

    private fun failChannelProfileUpdate(errorCode: String) {
        diagnosticsLogger?.invoke("thread_channel_profile_update_failed errorCode=$errorCode")
        reduceState { latest ->
            latest.copy(
                channelProfileUpdateStatus = ChannelProfileUpdateStatus.Failed,
                channelProfileUpdateErrorCode = errorCode
            )
        }
    }

    fun clearChannelProfileUpdateStatus() {
        reduceState { latest ->
            latest.copy(
                channelProfileUpdateStatus = ChannelProfileUpdateStatus.Idle,
                channelProfileUpdateErrorCode = null
            )
        }
    }
}
