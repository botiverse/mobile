package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `RAFT_PRIVACY_MODE` as this app knows it for the current API environment (botiverse/slock
 * docs/observability/privacy-mode.md, web `store/privacyMode.ts` + `utils/webTelemetryPrivacyHold.ts`).
 *
 * - [Unknown]: the environment's client config has not answered yet. Telemetry is held in memory,
 *   nothing is sent.
 * - [On]: the answer carried `privacyMode: true`. Held telemetry is dropped and nothing is sent.
 * - [Off]: any other answer (no field, an older server), or a failed read (no response, non-2xx,
 *   an unreadable body, or no answer within [RAFT_PRIVACY_MODE_READ_TIMEOUT_MILLIS]). What was held
 *   is sent, then everything is as before.
 */
enum class RaftPrivacyMode { Unknown, On, Off }

/** The web's client-config read gives up after 10 s and counts that as off; so does the app. */
internal const val RAFT_PRIVACY_MODE_READ_TIMEOUT_MILLIS = 10_000L

/**
 * Process-wide privacy mode, published by [SharedClientConfigStore] (one answer per environment;
 * an environment change starts over at [RaftPrivacyMode.Unknown]) and read by every client
 * telemetry sender ([RaftClientTraceEvents]).
 */
object RaftTelemetryPrivacy {
    private val mutableState = MutableStateFlow(RaftPrivacyMode.Unknown)
    val state: StateFlow<RaftPrivacyMode> = mutableState.asStateFlow()

    fun publish(mode: RaftPrivacyMode) {
        mutableState.value = mode
    }
}
