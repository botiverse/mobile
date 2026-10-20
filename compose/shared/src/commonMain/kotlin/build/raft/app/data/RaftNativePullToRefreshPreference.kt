package build.raft.app.data

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Settings → Mobile switch "Native pull-to-refresh". Off (the default) keeps Raft's custom
 * pull-to-refresh (the list grows a refresh item at the top while pulling); on uses each
 * platform's system style (Android: Material 3, iOS: UIRefreshControl-style). Stored on the device
 * under [RAFT_NATIVE_PULL_TO_REFRESH_PREFERENCE_KEY] and applied live through
 * [RaftNativePullToRefreshRuntime].
 */
interface RaftNativePullToRefreshPreferenceStore {
    fun readNativePullToRefreshEnabledSync(): Boolean
    suspend fun saveNativePullToRefreshEnabled(enabled: Boolean)
}

class RaftKeyValueNativePullToRefreshPreferenceStore(
    private val store: SharedPreferenceStore
) : RaftNativePullToRefreshPreferenceStore {
    override fun readNativePullToRefreshEnabledSync(): Boolean =
        store.getBoolean(RAFT_NATIVE_PULL_TO_REFRESH_PREFERENCE_KEY, RAFT_NATIVE_PULL_TO_REFRESH_DEFAULT)

    override suspend fun saveNativePullToRefreshEnabled(enabled: Boolean) {
        store.putBoolean(RAFT_NATIVE_PULL_TO_REFRESH_PREFERENCE_KEY, enabled)
        RaftNativePullToRefreshRuntime.publish(enabled)
    }
}

const val RAFT_NATIVE_PULL_TO_REFRESH_PREFERENCE_KEY = "chatLayout:native_pull_to_refresh_enabled"
const val RAFT_NATIVE_PULL_TO_REFRESH_DEFAULT = false

/**
 * Process-wide observable value of the switch. Seeded from the device store on first read; a save
 * through [RaftKeyValueNativePullToRefreshPreferenceStore] publishes the new value, so every
 * mounted pull-to-refresh recomposes into the chosen implementation without a restart.
 */
object RaftNativePullToRefreshRuntime {
    private var state: MutableState<Boolean>? = null

    /** Snapshot-state read: composables reading this recompose when the switch changes. */
    fun enabled(): Boolean = stateOrSeed().value

    fun publish(enabled: Boolean) {
        val current = state
        if (current == null) {
            state = mutableStateOf(enabled)
        } else {
            current.value = enabled
        }
    }

    private fun stateOrSeed(): MutableState<Boolean> =
        state ?: mutableStateOf(readStoredValue()).also { state = it }

    private fun readStoredValue(): Boolean =
        runCatching {
            RaftKeyValueNativePullToRefreshPreferenceStore(raftSharedPreferenceStore())
                .readNativePullToRefreshEnabledSync()
        }.getOrDefault(RAFT_NATIVE_PULL_TO_REFRESH_DEFAULT)
}
