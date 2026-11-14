package build.raft.app.data.realtime

import build.raft.app.data.REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.getBoolean
import build.raft.app.data.putBoolean
import build.raft.app.data.raftSharedPreferenceStore
import kotlin.concurrent.Volatile

/**
 * The common-Kotlin Socket.IO client switch ([REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY], realtime
 * unification phase 1). Read every time a host creates a socket, never mid-connection: turning it off
 * (server or local) puts the next connection back on the platform Socket.IO library.
 *
 * - Host: only a host with an adapter offers it ([hostSupported], set by the iOS app at launch); other
 *   hosts always read off and show no switch.
 * - Server: every evaluation of the key (any server, the last one wins) is written to [KEY_SERVER_LAST].
 * - Local: the Settings > Developer switch [KEY_LOCAL_OVERRIDE]. Unset follows the server; on or off wins.
 */
object RaftRealtimeKmpClientFlag {
    const val KEY_SERVER_LAST: String = "mobile:realtime_kmp_client_v1:server_last"
    const val KEY_LOCAL_OVERRIDE: String = "mobile:realtime_kmp_client_v1:local_override"

    /** True where a host adapter exists (iOS); set by that host before its first socket. */
    @Volatile var hostSupported: Boolean = false

    fun recordServerEvaluation(store: SharedPreferenceStore, key: String, enabled: Boolean) {
        if (key != REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY) return
        if (store.getString(KEY_SERVER_LAST) != enabled.toString()) store.putBoolean(KEY_SERVER_LAST, enabled)
    }

    /** The developer switch as set: null when never touched (the server decides). */
    fun localOverrideSetting(store: SharedPreferenceStore): Boolean? =
        when (store.getString(KEY_LOCAL_OVERRIDE)) {
            "true" -> true
            "false" -> false
            else -> null
        }

    fun setLocalOverride(store: SharedPreferenceStore, enabled: Boolean) = store.putBoolean(KEY_LOCAL_OVERRIDE, enabled)

    /** Whether the next socket uses the common client: the local setting, else the last server value. */
    fun enabled(store: SharedPreferenceStore, supported: Boolean = hostSupported): Boolean {
        if (!supported) return false
        return localOverrideSetting(store) ?: store.getBoolean(KEY_SERVER_LAST, false)
    }

    /** Unit tests only. */
    internal fun resetForTests() {
        hostSupported = false
    }
}

/**
 * iOS host entry (Swift: `RaftRealtimeKmpClientFlagKt.raftRealtimeKmpClientEnabled()`), read when a socket
 * is created. A storage failure reads off, so the platform library stays the fallback.
 */
fun raftRealtimeKmpClientEnabled(): Boolean =
    runCatching { RaftRealtimeKmpClientFlag.enabled(raftSharedPreferenceStore()) }.getOrDefault(false)

/** iOS host entry: this host has the adapter (shows the developer switch). */
fun raftRealtimeKmpClientMarkHostSupported() {
    RaftRealtimeKmpClientFlag.hostSupported = true
}
