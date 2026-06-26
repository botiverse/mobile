package ai.slock.compose.data

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

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
    private val pager: Pager,
    private val source: String
) : SlockAuthSessionClearEventSink {
    private val module: NotifyModule
        get() = pager.acquireModule(NotifyModule.MODULE_NAME)

    fun addSessionClearedListener(onCleared: (AuthSessionClearedSignal) -> Unit): CallbackRef =
        module.addNotify(AUTH_SESSION_CLEARED_EVENT, crossProcess = true) { data ->
            data?.toAuthSessionClearedSignal()?.let(onCleared)
        }

    fun removeSessionClearedListener(callbackRef: CallbackRef) {
        module.removeNotify(AUTH_SESSION_CLEARED_EVENT, callbackRef)
    }

    fun isLocalSource(signal: AuthSessionClearedSignal): Boolean =
        signal.source == source

    override fun postSessionCleared(reason: String, previousSessionFingerprint: String) {
        module.postNotify(
            AUTH_SESSION_CLEARED_EVENT,
            JSONObject()
                .put(KEY_SOURCE, source)
                .put(KEY_REASON, reason)
                .put(KEY_PREVIOUS_SESSION_FINGERPRINT, previousSessionFingerprint)
        )
    }
}

private fun JSONObject.toAuthSessionClearedSignal(): AuthSessionClearedSignal? {
    val source = optString(KEY_SOURCE).takeIf { it.isNotBlank() } ?: return null
    val previousSessionFingerprint =
        optString(KEY_PREVIOUS_SESSION_FINGERPRINT).takeIf { it.isNotBlank() } ?: return null
    return AuthSessionClearedSignal(
        source = source,
        reason = optString(KEY_REASON).ifBlank { "unknown" },
        previousSessionFingerprint = previousSessionFingerprint
    )
}
