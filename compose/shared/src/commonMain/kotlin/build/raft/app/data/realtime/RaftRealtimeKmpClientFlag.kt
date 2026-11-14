package build.raft.app.data.realtime

import build.raft.app.data.REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.raftSharedPreferenceStore
import kotlin.concurrent.Volatile

/**
 * The common-Kotlin Socket.IO client switch ([REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY]). Read every time a
 * host creates a socket, never mid-connection: turning it off (server or local) puts the next connection
 * back on the platform Socket.IO library (socket.io-client-java on Android, Socket.IO-Client-Swift on iOS).
 *
 * - Host: only a host with an adapter offers it ([hostSupported], set by the Android and iOS apps at
 *   launch); other hosts (desktop) always read off and show no switch.
 * - Build default ([buildDefaultOn]): set by the host from its build configuration. On for QA builds
 *   (Android `debug` / `alpha` via BuildConfig.REALTIME_KMP_CLIENT_DEFAULT_ON, iOS `Debug` via the
 *   RAFT_REALTIME_KMP_CLIENT_DEFAULT_ON build setting), off for release builds.
 * - Server: an explicit evaluation of the key (a rule, a kill switch, or any `true`) is written to
 *   [KEY_SERVER_LAST] (any server, the last one wins). A "no rule" evaluation (reason `default`,
 *   `missing_flag`, `missing_server_unit` evaluating false) clears it, so it never overrides the build
 *   default: a QA build stays on until the server explicitly turns it off; a release build stays off until
 *   the server turns it on.
 * - Local: the Settings > Developer switch [KEY_LOCAL_OVERRIDE]. Unset follows server, then build default;
 *   on or off wins.
 */
object RaftRealtimeKmpClientFlag {
    const val KEY_SERVER_LAST: String = "mobile:realtime_kmp_client_v1:server_last"
    const val KEY_LOCAL_OVERRIDE: String = "mobile:realtime_kmp_client_v1:local_override"

    /** Evaluation reasons that carry no decision for this key (nothing configured for this client). */
    val NoDecisionReasons: Set<String> = setOf("default", "missing_flag", "missing_server_unit")

    /** True where a host adapter exists (Android, iOS); set by that host before its first socket. */
    @Volatile var hostSupported: Boolean = false

    /** The host's build default (QA builds on); set by the host before its first socket. */
    @Volatile var buildDefaultOn: Boolean = false

    /** Which input decided [enabled]: `local`, `server`, `build_default` or `unsupported`. */
    enum class Source(val token: String) { Local("local"), Server("server"), BuildDefault("build_default"), Unsupported("unsupported") }

    data class Decision(val enabled: Boolean, val source: Source)

    /** Stores a server evaluation of [key] when it is this flag's key (feature-flag observer). */
    fun recordServerEvaluation(store: SharedPreferenceStore, key: String, enabled: Boolean, reason: String? = null) {
        if (key != REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY) return
        val value = if (!enabled && reason != null && reason in NoDecisionReasons) null else enabled.toString()
        if (store.getString(KEY_SERVER_LAST) != value) store.putString(KEY_SERVER_LAST, value)
    }

    /** The last explicit server decision, null when the server has none. */
    fun serverDecision(store: SharedPreferenceStore): Boolean? =
        when (store.getString(KEY_SERVER_LAST)) {
            "true" -> true
            "false" -> false
            else -> null
        }

    /** The developer switch as set: null when never touched (the server, then the build default, decides). */
    fun localOverrideSetting(store: SharedPreferenceStore): Boolean? =
        when (store.getString(KEY_LOCAL_OVERRIDE)) {
            "true" -> true
            "false" -> false
            else -> null
        }

    fun setLocalOverride(store: SharedPreferenceStore, enabled: Boolean) = store.putString(KEY_LOCAL_OVERRIDE, enabled.toString())

    /** Whether the next socket uses the common client, and why. */
    fun decision(
        store: SharedPreferenceStore,
        supported: Boolean = hostSupported,
        buildDefault: Boolean = buildDefaultOn,
    ): Decision {
        if (!supported) return Decision(false, Source.Unsupported)
        localOverrideSetting(store)?.let { return Decision(it, Source.Local) }
        serverDecision(store)?.let { return Decision(it, Source.Server) }
        return Decision(buildDefault, Source.BuildDefault)
    }

    /** Whether the next socket uses the common client: the local setting, else the server, else the build default. */
    fun enabled(
        store: SharedPreferenceStore,
        supported: Boolean = hostSupported,
        buildDefault: Boolean = buildDefaultOn,
    ): Boolean = decision(store, supported, buildDefault).enabled

    /** Unit tests only. */
    internal fun resetForTests() {
        hostSupported = false
        buildDefaultOn = false
    }
}

/** [RaftRealtimeKmpClientFlag.decision] on the app store; a storage failure reads off (the platform library). */
fun raftRealtimeKmpClientDecision(): RaftRealtimeKmpClientFlag.Decision =
    runCatching { RaftRealtimeKmpClientFlag.decision(raftSharedPreferenceStore()) }
        .getOrDefault(RaftRealtimeKmpClientFlag.Decision(false, RaftRealtimeKmpClientFlag.Source.Unsupported))

/**
 * iOS host entry (Swift: `RaftRealtimeKmpClientFlagKt.raftRealtimeKmpClientEnabled()`), read when a socket
 * is created. A storage failure reads off, so the platform library stays the fallback.
 */
fun raftRealtimeKmpClientEnabled(): Boolean = raftRealtimeKmpClientDecision().enabled

/** Host entry: this host has the adapter (shows the developer switch). */
fun raftRealtimeKmpClientMarkHostSupported() {
    RaftRealtimeKmpClientFlag.hostSupported = true
}

/** Host entry: the build configuration's default (QA builds on, release off). */
fun raftRealtimeKmpClientSetBuildDefault(on: Boolean) {
    RaftRealtimeKmpClientFlag.buildDefaultOn = on
}
