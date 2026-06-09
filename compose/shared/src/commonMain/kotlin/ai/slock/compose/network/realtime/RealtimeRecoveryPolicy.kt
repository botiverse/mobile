package ai.slock.compose.network.realtime

import ai.slock.compose.network.model.BackfillScope
import ai.slock.compose.network.model.NetworkStatus
import ai.slock.compose.network.model.RealtimeConnectionPhase
import ai.slock.compose.network.model.RealtimeConnectionState

data class RealtimeRecoveryContext(
    val serverId: String?,
    val visibleThreadId: String?,
    val lastSeq: Long?
)

sealed class RealtimeRecoveryTrigger {
    data class AppForegrounded(val nowMillis: Long) : RealtimeRecoveryTrigger()
    data class NetworkAvailable(val nowMillis: Long) : RealtimeRecoveryTrigger()
    data class ManualResume(val nowMillis: Long, val reason: String) : RealtimeRecoveryTrigger()
}

data class RealtimeRecoveryPolicyResult(
    val state: RealtimeConnectionState,
    val intents: List<RealtimeConnectionIntent>
)

class RealtimeRecoveryPolicy(
    private val reducer: RealtimeConnectionReducer = RealtimeConnectionReducer()
) {
    fun recover(
        current: RealtimeConnectionState,
        trigger: RealtimeRecoveryTrigger,
        context: RealtimeRecoveryContext
    ): RealtimeRecoveryPolicyResult {
        val nowMillis =
            when (trigger) {
                is RealtimeRecoveryTrigger.AppForegrounded -> trigger.nowMillis
                is RealtimeRecoveryTrigger.NetworkAvailable -> trigger.nowMillis
                is RealtimeRecoveryTrigger.ManualResume -> trigger.nowMillis
            }
        val reason =
            when (trigger) {
                is RealtimeRecoveryTrigger.AppForegrounded -> "app_foreground"
                is RealtimeRecoveryTrigger.NetworkAvailable -> "network_available"
                is RealtimeRecoveryTrigger.ManualResume -> trigger.reason
            }
        val seededState =
            context.lastSeq
                ?.takeIf { it > 0L }
                ?.let { current.copy(lastSeq = maxOf(current.lastSeq ?: 0L, it)) }
                ?: current

        val connectionResult =
            when (trigger) {
                is RealtimeRecoveryTrigger.NetworkAvailable ->
                    reducer.reduce(
                        current = seededState,
                        event = RealtimeConnectionEvent.NetworkChanged(NetworkStatus.Online, nowMillis),
                        serverId = context.serverId,
                        visibleThreadId = context.visibleThreadId
                    )
                else ->
                    if (seededState.phase == RealtimeConnectionPhase.Connected) {
                        RealtimeReducerResult(seededState)
                    } else {
                        reducer.reduce(
                            current = seededState,
                            event = RealtimeConnectionEvent.ConnectRequested(nowMillis),
                            serverId = context.serverId,
                            visibleThreadId = context.visibleThreadId
                        )
                    }
            }

        val recoveryIntents = buildList {
            addAll(connectionResult.intents)
            context.serverId?.let {
                add(RealtimeConnectionIntent.BackfillRequired(BackfillScope.Home(it), reason))
            }
            context.visibleThreadId?.let {
                add(RealtimeConnectionIntent.BackfillRequired(BackfillScope.Thread(it), reason))
            }
            if (context.serverId != null) {
                add(RealtimeConnectionIntent.ReplayRooms(reason))
            }
        }.distinct()

        return RealtimeRecoveryPolicyResult(
            state = connectionResult.state,
            intents = recoveryIntents
        )
    }
}
