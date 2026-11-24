package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
@RaftCoreInternalApi const val RAFT_PRIVACY_MODE_READ_TIMEOUT_MILLIS = 10_000L

/**
 * How an environment's client-config read decided privacy mode (owner ruling on #5082):
 * - [Pending]: no answer yet.
 * - [On] / [Off]: a successful response; `privacyMode: true` is on, anything else (no field, an
 *   older server) off.
 * - [Failed]: no successful response (transport error, non-2xx, or no answer within
 *   [RAFT_PRIVACY_MODE_READ_TIMEOUT_MILLIS]).
 *
 * [telemetryMode] is what in-memory trace telemetry follows (#5064, as the web: a failed read is
 * off). [deferrableMode] is what uploads that can wait follow (Hands crash / ANR / native crash /
 * device ping, automatic update checks): a failed read is unknown, so they stay on the device and
 * the next launch decides again.
 */
enum class RaftPrivacyAnswer(val telemetryMode: RaftPrivacyMode, val deferrableMode: RaftPrivacyMode) {
    Pending(RaftPrivacyMode.Unknown, RaftPrivacyMode.Unknown),
    On(RaftPrivacyMode.On, RaftPrivacyMode.On),
    Off(RaftPrivacyMode.Off, RaftPrivacyMode.Off),
    Failed(RaftPrivacyMode.Off, RaftPrivacyMode.Unknown),
}

/**
 * Privacy mode across several servers (owner ruling, observability/privacy): [RaftPrivacyMode.On]
 * when any one is on, [RaftPrivacyMode.Unknown] when any one has not answered (or there is none),
 * [RaftPrivacyMode.Off] only when every one answered off.
 */
fun raftPrivacyModeAcross(modes: Collection<RaftPrivacyMode>): RaftPrivacyMode =
    when {
        modes.isEmpty() -> RaftPrivacyMode.Unknown
        RaftPrivacyMode.On in modes -> RaftPrivacyMode.On
        RaftPrivacyMode.Unknown in modes -> RaftPrivacyMode.Unknown
        else -> RaftPrivacyMode.Off
    }

/**
 * Process-wide privacy mode, published by [SharedClientConfigStore] (one answer per environment;
 * an environment change starts over at [RaftPrivacyMode.Unknown]) and read by every client
 * telemetry sender ([RaftClientTraceEvents]).
 *
 * [state] is the current environment's answer: what the current server's own requests (traces, an
 * agent report, Settings › Feedback) follow; a failed read is off there. [signedInServers] is every
 * API environment this app is signed in to, with its [RaftPrivacyAnswer.deferrableMode] (a failed
 * read is unknown), and [acrossSignedInServers] their [raftPrivacyModeAcross]: what
 * requests that belong to no one server follow (the Hands SDK's crash / ANR / system-kill /
 * WebView-crash uploads, its device ping, automatic update checks; [RaftServicesPrivacyGate]).
 * Today a session lives in one API environment (every server in it shares that deploy's
 * `RAFT_PRIVACY_MODE`, and an environment change replaces it), so the set is the current
 * environment; the aggregate is kept separate so a second signed-in deploy only has to be added
 * here.
 */
object RaftTelemetryPrivacy {
    private val mutableState = MutableStateFlow(RaftPrivacyMode.Unknown)
    val state: StateFlow<RaftPrivacyMode> = mutableState.asStateFlow()

    private val mutableSignedInServers = MutableStateFlow<Map<String, RaftPrivacyMode>>(emptyMap())
    val signedInServers: StateFlow<Map<String, RaftPrivacyMode>> = mutableSignedInServers.asStateFlow()

    private val mutableAcross = MutableStateFlow(RaftPrivacyMode.Unknown)
    val acrossSignedInServers: StateFlow<RaftPrivacyMode> = mutableAcross.asStateFlow()

    /** [environment]'s answer, the current environment's (its session's servers are the signed-in ones). */
    fun publish(environment: String, answer: RaftPrivacyAnswer) {
        val servers = mapOf(environment to answer.deferrableMode)
        mutableSignedInServers.value = servers
        mutableAcross.value = raftPrivacyModeAcross(servers.values)
        mutableState.value = answer.telemetryMode
    }

    /** [environment]'s answer as a successful response said it ([RaftPrivacyMode.Unknown]: none yet). */
    fun publish(environment: String, mode: RaftPrivacyMode) {
        publish(
            environment,
            when (mode) {
                RaftPrivacyMode.Unknown -> RaftPrivacyAnswer.Pending
                RaftPrivacyMode.On -> RaftPrivacyAnswer.On
                RaftPrivacyMode.Off -> RaftPrivacyAnswer.Off
            },
        )
    }

    /** The current environment's answer, its name unknown (tests, a reset). */
    fun publish(mode: RaftPrivacyMode) {
        publish("", mode)
    }
}

/**
 * The owner's rule for Raft's own services (Hands included) under `RAFT_PRIVACY_MODE`: nothing goes
 * to them automatically unless every server this app is signed in to answered privacy mode off.
 * Local recording is unaffected (crash logs, ANR exits and system-kill records stay on the device
 * and are looked at again on the next launch). A person's own "Check for update" is not automatic
 * and is not gated here. Only a successful client-config response counts as an answer here: a
 * failed or timed-out read is unknown ([RaftPrivacyAnswer.Failed]), unlike for trace telemetry.
 */
object RaftServicesPrivacyGate {
    /** How long a launch's uploads wait for every signed-in server's answer before skipping this launch. */
    const val DECISION_WAIT_MILLIS: Long = 60_000L

    /** Whether an automatic request to Raft's services may go now. */
    fun allowsAutomatic(mode: RaftPrivacyMode = RaftTelemetryPrivacy.acrossSignedInServers.value): Boolean =
        mode == RaftPrivacyMode.Off

    /**
     * The decision for this launch's uploads: waits (up to [waitMillis]) until no signed-in server
     * is unknown, then true only when all are off. Still unknown after the wait: false (skipped
     * until the next launch).
     */
    suspend fun awaitLaunchDecision(
        across: StateFlow<RaftPrivacyMode> = RaftTelemetryPrivacy.acrossSignedInServers,
        waitMillis: Long = DECISION_WAIT_MILLIS,
    ): RaftPrivacyMode =
        withTimeoutOrNull(waitMillis) { across.first { it != RaftPrivacyMode.Unknown } } ?: RaftPrivacyMode.Unknown
}
