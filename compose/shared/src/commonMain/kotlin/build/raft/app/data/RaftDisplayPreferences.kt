package build.raft.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The Web's `SHOW_AGENT_MODEL_NAME_STORAGE_KEY` (appearanceStore), kept as the stored key. */
internal const val RaftShowAgentModelNameStorageKey = "slock_show_agent_model_name"

/** The Web's default: on everywhere (`readStoredShowAgentModelName`). */
const val RaftShowAgentModelNameDefault: Boolean = true

/**
 * Settings > Appearance display choices that are saved on this device, like the Web's
 * appearanceStore (localStorage, not the account):
 * - "Show agent model": the model label next to an agent's name in a message row
 *   (Web `MessageItem`).
 * - "Hide empty sidebar sections": the Home list drops sections with nothing in them; the
 *   same value as the Home section menu's "Hide empty sections"
 *   ([RaftSidebarHideEmptySectionsStore]).
 *
 * Compose state, so the Settings switch and every mounted screen stay in step.
 */
object RaftDisplayPreferenceRuntime {
    private var loaded = false

    private var storeOverride: SharedPreferenceStore? = null

    private var showAgentModelNameState by mutableStateOf(RaftShowAgentModelNameDefault)

    private var hideEmptySidebarSectionsState by mutableStateOf(RaftSidebarHideEmptySectionsDefault)

    private fun store(): SharedPreferenceStore = storeOverride ?: raftSharedPreferenceStore()

    private fun ensureLoaded() {
        if (loaded) return
        // An unreadable store (not installed yet, or a screenshot host) keeps the defaults.
        val store = runCatching { store() }.getOrNull() ?: return
        loaded = true
        showAgentModelNameState =
            when (runCatching { store.getString(RaftShowAgentModelNameStorageKey) }.getOrNull()) {
                "true" -> true
                "false" -> false
                else -> RaftShowAgentModelNameDefault
            }
        hideEmptySidebarSectionsState = KeyValueRaftSidebarHideEmptySectionsStore(store).read()
    }

    val showAgentModelName: Boolean
        get() {
            ensureLoaded()
            return showAgentModelNameState
        }

    val hideEmptySidebarSections: Boolean
        get() {
            ensureLoaded()
            return hideEmptySidebarSectionsState
        }

    fun setShowAgentModelName(show: Boolean) {
        ensureLoaded()
        showAgentModelNameState = show
        runCatching { store().putString(RaftShowAgentModelNameStorageKey, show.toString()) }
    }

    fun setHideEmptySidebarSections(hide: Boolean) {
        ensureLoaded()
        hideEmptySidebarSectionsState = hide
        runCatching { KeyValueRaftSidebarHideEmptySectionsStore(store()).write(hide) }
    }

    /** Tests: a fresh store, read again on next access. */
    internal fun resetForTest(store: SharedPreferenceStore?) {
        storeOverride = store
        loaded = false
        showAgentModelNameState = RaftShowAgentModelNameDefault
        hideEmptySidebarSectionsState = RaftSidebarHideEmptySectionsDefault
    }
}
