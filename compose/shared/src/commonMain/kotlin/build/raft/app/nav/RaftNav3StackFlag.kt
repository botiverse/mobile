package build.raft.app.nav

import build.raft.app.data.MOBILE_NAV3_STACK_FEATURE_FLAG_KEY
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.getBoolean
import build.raft.app.data.putBoolean
import kotlin.concurrent.Volatile

/**
 * The Navigation 3 page stack switch ([MOBILE_NAV3_STACK_FEATURE_FLAG_KEY], default off), decided once
 * per app process like the multi-window setting: the window structure must not change under open
 * pages, so a server or local change takes effect on the next app start.
 *
 * - Server: every evaluation of the key (any server, the last one wins) is written to
 *   [KEY_SERVER_LAST]; a kill switch is the next evaluation writing false.
 * - Local: the developer setting [KEY_LOCAL_OVERRIDE] turns it on for testing whatever the server says.
 * - [launchDecision] reads both once; every later call in the process returns that first answer.
 */
object RaftNav3StackFlag {
    const val KEY_SERVER_LAST: String = "mobile:nav3_stack_v1:server_last"
    const val KEY_LOCAL_OVERRIDE: String = "mobile:nav3_stack_v1:local_override"

    @Volatile private var decided: Boolean? = null

    /** Stores a server evaluation of [key] when it is this flag's key (feature-flag observer). */
    fun recordServerEvaluation(store: SharedPreferenceStore, key: String, enabled: Boolean) {
        if (key != MOBILE_NAV3_STACK_FEATURE_FLAG_KEY) return
        if (store.getString(KEY_SERVER_LAST) != enabled.toString()) store.putBoolean(KEY_SERVER_LAST, enabled)
    }

    fun localOverride(store: SharedPreferenceStore): Boolean = store.getBoolean(KEY_LOCAL_OVERRIDE, false)

    fun setLocalOverride(store: SharedPreferenceStore, enabled: Boolean) = store.putBoolean(KEY_LOCAL_OVERRIDE, enabled)

    /** On when the local test switch or the last server evaluation is on. */
    fun stored(store: SharedPreferenceStore): Boolean =
        localOverride(store) || store.getBoolean(KEY_SERVER_LAST, false)

    /** This process's answer: [stored] at the first call (failures read off), unchanged after. */
    fun launchDecision(store: () -> SharedPreferenceStore): Boolean =
        decided ?: (runCatching { stored(store()) }.getOrDefault(false)).also { decided = it }

    /** Unit tests only: forget the process decision. */
    internal fun resetForTests() {
        decided = null
    }
}
