package build.raft.app.data

import build.raft.app.optString
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val AUTH_SESSION_CLEARED_EVENT = "raft.authSessionCleared"
private const val KEY_SOURCE = "source"
private const val KEY_REASON = "reason"
private const val KEY_PREVIOUS_SESSION_FINGERPRINT = "previousSessionFingerprint"

interface SlockAuthSessionClearEventSink {
    fun postSessionCleared(reason: String, previousSessionFingerprint: String)
}

internal data class AuthSessionClearedSignal(
    val source: String,
    val reason: String,
    val previousSessionFingerprint: String
)

internal class KuiklyAuthSessionEvents(
    eventBus: SlockEventBus,
    source: String
) : SlockAuthSessionClearEventSink {
    @Volatile
    private var currentEventBus: SlockEventBus = eventBus

    @Volatile
    private var currentSource: String = source

    fun updateEventBus(eventBus: SlockEventBus, source: String) {
        currentEventBus = eventBus
        currentSource = source
    }

    fun addSessionClearedListener(onCleared: (AuthSessionClearedSignal) -> Unit): RealtimeSubscription =
        currentEventBus.observe(AUTH_SESSION_CLEARED_EVENT, crossProcess = true) { data ->
            data?.toAuthSessionClearedSignal()?.let(onCleared)
        }

    fun removeSessionClearedListener(subscription: RealtimeSubscription) {
        subscription.close()
    }

    fun isLocalSource(signal: AuthSessionClearedSignal): Boolean =
        signal.source == currentSource

    override fun postSessionCleared(reason: String, previousSessionFingerprint: String) {
        currentEventBus.post(
            AUTH_SESSION_CLEARED_EVENT,
            buildJsonObject {
                put(KEY_SOURCE, currentSource)
                put(KEY_REASON, reason)
                put(KEY_PREVIOUS_SESSION_FINGERPRINT, previousSessionFingerprint)
            }
        )
    }
}

private fun JsonObject.toAuthSessionClearedSignal(): AuthSessionClearedSignal? {
    val source = optString(KEY_SOURCE).takeIf { it.isNotBlank() } ?: return null
    val previousSessionFingerprint =
        optString(KEY_PREVIOUS_SESSION_FINGERPRINT).takeIf { it.isNotBlank() } ?: return null
    return AuthSessionClearedSignal(
        source = source,
        reason = optString(KEY_REASON).ifBlank { "unknown" },
        previousSessionFingerprint = previousSessionFingerprint
    )
}
