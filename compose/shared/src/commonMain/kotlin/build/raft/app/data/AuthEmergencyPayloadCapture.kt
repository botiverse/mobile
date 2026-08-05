package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val AuthEmergencyPayloadRetentionMillis = 60_000L

internal data class AuthEmergencyPayloadSnapshot(
    val endpoint: String,
    val bodyText: String,
)

internal data class AuthEmergencyPayloadCaptureState(
    val enabled: Boolean = false,
    val generation: Long = 0L,
    val snapshot: AuthEmergencyPayloadSnapshot? = null,
)

/**
 * One-shot, process-memory-only capture of the exact serialized password-login request body.
 *
 * This is intentionally disconnected from diagnostics, console logging, persistence, clipboard,
 * and Hands exports. The capture is available only after an explicit signed-out diagnostics opt-in
 * and is erased on disable, successful login, or the fixed retention timeout.
 */
internal class AuthEmergencyPayloadCaptureStore(
    private val scope: CoroutineScope,
    private val retentionMillis: Long,
) {
    private val mutableState = MutableStateFlow(AuthEmergencyPayloadCaptureState())

    val state: StateFlow<AuthEmergencyPayloadCaptureState> = mutableState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        while (true) {
            val current = mutableState.value
            val next =
                current.copy(
                    enabled = enabled,
                    generation = current.generation + 1L,
                    snapshot = null,
                )
            if (mutableState.compareAndSet(current, next)) return
        }
    }

    fun capture(endpoint: String, bodyText: String) {
        val capturedGeneration: Long
        while (true) {
            val current = mutableState.value
            if (!current.enabled) return
            val next =
                current.copy(
                    enabled = false,
                    generation = current.generation + 1L,
                    snapshot = AuthEmergencyPayloadSnapshot(endpoint = endpoint, bodyText = bodyText),
                )
            if (mutableState.compareAndSet(current, next)) {
                capturedGeneration = next.generation
                break
            }
        }
        scope.launch {
            delay(retentionMillis)
            clearSnapshotIfGeneration(capturedGeneration)
        }
    }

    fun clear() {
        setEnabled(false)
    }

    private fun clearSnapshotIfGeneration(generation: Long) {
        while (true) {
            val current = mutableState.value
            if (current.generation != generation || current.snapshot == null) return
            val next =
                current.copy(
                    enabled = false,
                    generation = current.generation + 1L,
                    snapshot = null,
                )
            if (mutableState.compareAndSet(current, next)) return
        }
    }
}

internal object AuthEmergencyPayloadCapture {
    private val store =
        AuthEmergencyPayloadCaptureStore(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            retentionMillis = AuthEmergencyPayloadRetentionMillis,
        )

    val state: StateFlow<AuthEmergencyPayloadCaptureState> = store.state

    fun setEnabled(enabled: Boolean) = store.setEnabled(enabled)

    fun capture(endpoint: String, bodyText: String) = store.capture(endpoint, bodyText)

    fun clear() = store.clear()
}
