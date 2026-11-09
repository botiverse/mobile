package build.raft.app.nav

import build.raft.app.data.MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.getBoolean
import build.raft.app.data.putBoolean
import build.raft.app.data.raftSharedPreferenceStore
import kotlin.concurrent.Volatile

/**
 * The iOS native navigation switch ([MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY]): the opted-in pages'
 * heads on the real UINavigationBar and the system swipe-back (iOS host, RaftNativeNavProto in
 * ContentView.swift). Off by default everywhere. Decided once per app process like
 * [RaftNav3StackFlag]: the navigation controllers must not change under open pages, so a server or
 * local change takes effect on the next app start.
 *
 * - Host: only an iPhone host offers it ([hostSupported], set by the iOS app before the first
 *   [launchDecision]); iPad, Android and desktop always read off and show no switch.
 * - Server: every evaluation of the key (any server, the last one wins) is written to
 *   [KEY_SERVER_LAST]; a kill switch is the next evaluation writing false.
 * - Local: the Settings > Mobile switch (Beta; in Settings > Developer before 1.23, same key)
 *   [KEY_LOCAL_OVERRIDE]. Unset follows the server; set on or off wins over the server.
 * - Launch override: a Debug build's launch environment (`RAFT_NATIVE_NAV=1|0`, CI captures) wins
 *   over both; release builds never pass one.
 * - [launchDecision] reads them once; every later call in the process returns that first answer.
 */
object RaftIosNativeNavFlag {
    const val KEY_SERVER_LAST: String = "mobile:ios_native_nav_v1:server_last"
    const val KEY_LOCAL_OVERRIDE: String = "mobile:ios_native_nav_v1:local_override"

    /** True on an iPhone host; set by the iOS app at launch. Off everywhere else. */
    @Volatile var hostSupported: Boolean = false

    @Volatile private var decided: Boolean? = null

    /** Stores a server evaluation of [key] when it is this flag's key (feature-flag observer). */
    fun recordServerEvaluation(store: SharedPreferenceStore, key: String, enabled: Boolean) {
        if (key != MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY) return
        if (store.getString(KEY_SERVER_LAST) != enabled.toString()) store.putBoolean(KEY_SERVER_LAST, enabled)
    }

    /** The Settings > Mobile switch as set: null when never touched (the server decides). */
    fun localOverrideSetting(store: SharedPreferenceStore): Boolean? =
        when (store.getString(KEY_LOCAL_OVERRIDE)) {
            "true" -> true
            "false" -> false
            else -> null
        }

    fun setLocalOverride(store: SharedPreferenceStore, enabled: Boolean) = store.putBoolean(KEY_LOCAL_OVERRIDE, enabled)

    /** What the next app start decides from storage: the local setting, else the last server value. */
    fun stored(store: SharedPreferenceStore, supported: Boolean = hostSupported): Boolean {
        if (!supported) return false
        return localOverrideSetting(store) ?: store.getBoolean(KEY_SERVER_LAST, false)
    }

    /** `RAFT_NATIVE_NAV` from a Debug launch environment: "1" on, "0" off, anything else none. */
    fun parseLaunchOverride(value: String?): Boolean? =
        when (value?.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }

    /**
     * This process's answer, fixed at the first call: off without host support; else the launch
     * override, else [stored] (a storage failure reads off).
     */
    fun launchDecision(launchOverride: Boolean? = null, store: () -> SharedPreferenceStore): Boolean =
        decided ?: run {
            when {
                !hostSupported -> false
                launchOverride != null -> launchOverride
                else -> runCatching { stored(store()) }.getOrDefault(false)
            }
        }.also { decided = it }

    /**
     * Pages whose heads are mapped (every head they show publishes to the bar, every button of it is
     * reachable there): the app shell (its Home / Activity / Tasks / Members / More tabs; Search keeps
     * its own search-field head, the bar hides for it) and the pushed pages, plus the login-free
     * demo pages of a visual-fixture build. Every other page keeps its Compose head and the
     * full-page swipe back.
     */
    val mappedPageNames: Set<String> =
        setOf(
            "compose_slock_app_shell",
            "compose_thread",
            "compose_thread_members",
            "compose_home_activity",
            "compose_home_saved",
            "compose_home_drafts",
            "compose_channel_settings",
            "compose_members",
            "compose_member_detail",
            "compose_settings_home",
            "compose_settings_account",
            "compose_settings_native_detail",
            "compose_settings_storage",
            "compose_settings_computers",
            "compose_settings_hands_feedback",
            "compose_native_nav_proto_home",
            "compose_native_nav_proto_thread",
            "compose_channel_members_parity_visual",
            "compose_native_nav_proto_activity",
            "compose_native_nav_proto_tasks",
            "compose_native_nav_proto_more",
            "compose_native_nav_proto_settings_detail",
            "compose_native_nav_proto_saved",
            "compose_native_nav_proto_agent",
            "compose_native_nav_proto_channel_settings",
        )

    fun isMappedPage(pageName: String): Boolean = pageName in mappedPageNames

    /** The iOS host's per-page answer: with the switch off ([enabled] false) no page gets the bar. */
    fun pageUsesNativeNavBar(enabled: Boolean, pageName: String): Boolean = enabled && isMappedPage(pageName)

    /** Unit tests only: forget the process decision. */
    internal fun resetForTests() {
        decided = null
        hostSupported = false
    }
}

/**
 * iOS host entry (Swift: `RaftIosNativeNavFlagKt.raftIosNativeNavLaunchDecision(launchOverride:)`):
 * [RaftIosNativeNavFlag.launchDecision] on the app's shared preference store. [launchOverride] is
 * the raw `RAFT_NATIVE_NAV` launch-environment value (Debug builds only pass it).
 */
fun raftIosNativeNavLaunchDecision(launchOverride: String?): Boolean =
    RaftIosNativeNavFlag.launchDecision(RaftIosNativeNavFlag.parseLaunchOverride(launchOverride)) {
        raftSharedPreferenceStore()
    }
