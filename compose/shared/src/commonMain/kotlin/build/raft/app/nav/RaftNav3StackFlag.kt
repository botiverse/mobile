package build.raft.app.nav

import build.raft.app.data.MOBILE_NAV3_STACK_FEATURE_FLAG_KEY
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.getBoolean
import build.raft.app.data.putBoolean
import kotlin.concurrent.Volatile

/**
 * The Navigation 3 page stack switch ([MOBILE_NAV3_STACK_FEATURE_FLAG_KEY]), decided once per app
 * process like the multi-window setting: the window structure must not change under open pages,
 * so a server or local change takes effect on the next app start.
 *
 * - Build default ([buildDefaultOn]): off, except Alpha builds (the Android host sets it from
 *   BuildConfig.NAV3_STACK_DEFAULT_ON) so testers get the stack without the developer switch.
 * - Server: every evaluation of the key (any server, the last one wins) is written to
 *   [KEY_SERVER_LAST]; a kill switch is the next evaluation writing false.
 * - Local: the developer setting [KEY_LOCAL_OVERRIDE]. Unset follows the build default; set on
 *   turns the stack on whatever the server says; set off turns the build default off (the server
 *   can still turn it on, as before).
 * - [launchDecision] reads them once; every later call in the process returns that first answer.
 */
object RaftNav3StackFlag {
    const val KEY_SERVER_LAST: String = "mobile:nav3_stack_v1:server_last"
    const val KEY_LOCAL_OVERRIDE: String = "mobile:nav3_stack_v1:local_override"

    /** On for Alpha builds only; set by the platform host before the first [launchDecision]. */
    @Volatile var buildDefaultOn: Boolean = false

    @Volatile private var decided: Boolean? = null

    /** Stores a server evaluation of [key] when it is this flag's key (feature-flag observer). */
    fun recordServerEvaluation(store: SharedPreferenceStore, key: String, enabled: Boolean) {
        if (key != MOBILE_NAV3_STACK_FEATURE_FLAG_KEY) return
        if (store.getString(KEY_SERVER_LAST) != enabled.toString()) store.putBoolean(KEY_SERVER_LAST, enabled)
    }

    /** The developer switch as set: null when never touched (the build default applies). */
    fun localOverrideSetting(store: SharedPreferenceStore): Boolean? =
        when (store.getString(KEY_LOCAL_OVERRIDE)) {
            "true" -> true
            "false" -> false
            else -> null
        }

    /** What the developer switch shows: its setting, else the build default. */
    fun localOverride(store: SharedPreferenceStore, buildDefault: Boolean = buildDefaultOn): Boolean =
        localOverrideSetting(store) ?: buildDefault

    fun setLocalOverride(store: SharedPreferenceStore, enabled: Boolean) = store.putBoolean(KEY_LOCAL_OVERRIDE, enabled)

    /**
     * On when the local switch is set on, the last server evaluation is on, or the build default
     * is on and the local switch was not set off.
     */
    fun stored(store: SharedPreferenceStore, buildDefault: Boolean = buildDefaultOn): Boolean {
        val local = localOverrideSetting(store)
        return local == true || store.getBoolean(KEY_SERVER_LAST, false) || (buildDefault && local != false)
    }

    /** This process's answer: [stored] at the first call (failures read off), unchanged after. */
    fun launchDecision(store: () -> SharedPreferenceStore): Boolean =
        decided ?: (runCatching { stored(store()) }.getOrDefault(false)).also { decided = it }

    /** Unit tests only: forget the process decision. */
    internal fun resetForTests() {
        decided = null
        buildDefaultOn = false
    }
}
