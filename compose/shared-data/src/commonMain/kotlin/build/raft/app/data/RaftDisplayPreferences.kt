package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow

/** The Web's `SHOW_AGENT_MODEL_NAME_STORAGE_KEY` (appearanceStore), kept as the stored key. */
@RaftCoreInternalApi const val RaftShowAgentModelNameStorageKey = "slock_show_agent_model_name"

/** The Web's default: on everywhere (`readStoredShowAgentModelName`). */
const val RaftShowAgentModelNameDefault: Boolean = true

/** The Web's `SHOW_SENDER_DESCRIPTION_STORAGE_KEY` (appearanceStore), kept as the stored key. */
@RaftCoreInternalApi const val RaftShowSenderDescriptionStorageKey = "slock_show_sender_description"

/** The Web's default: on everywhere (`readStoredShowSenderDescription`). */
const val RaftShowSenderDescriptionDefault: Boolean = true

/** The Web's `SHOW_LIVE_AGENT_ACTIVITY_BAR_STORAGE_KEY` (appearanceStore), kept as the stored key. */
@RaftCoreInternalApi const val RaftShowLiveAgentActivityStorageKey = "slock_show_live_agent_activity_bar"

/** The Web's default: on unless the stored value is "false" (`readStoredShowLiveAgentActivityBar`). */
const val RaftShowLiveAgentActivityDefault: Boolean = true

/**
 * Snapshot-free, thread-safe backing store for [RaftDisplayPreferenceRuntime].
 *
 * Uses per-preference [MutableStateFlow]s rather than Compose `mutableStateOf` so first
 * initialization or reads from background flows never allocate Compose snapshot state records off
 * the main thread (`SnapshotKt.readError`, Hands `25b91174` follow-up `#proj-mobile:#264`), while
 * keeping `@Composable observe*()` allocation-free in hot message lists.
 */
@RaftCoreInternalApi class RaftDisplayPreferenceStore(
    private val defaultStoreProvider: () -> SharedPreferenceStore = ::raftSharedPreferenceStore,
) {
    private val lock = SynchronizedObject()

    @Volatile
    private var loaded = false

    @Volatile
    private var storeOverride: SharedPreferenceStore? = null

    private val showLiveAgentActivityFlow = MutableStateFlow(RaftShowLiveAgentActivityDefault)
    private val showAgentModelNameFlow = MutableStateFlow(RaftShowAgentModelNameDefault)
    private val showSenderDescriptionFlow = MutableStateFlow(RaftShowSenderDescriptionDefault)
    private val hideEmptySidebarSectionsFlow = MutableStateFlow(RaftSidebarHideEmptySectionsDefault)

    private fun store(): SharedPreferenceStore = storeOverride ?: defaultStoreProvider()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            // An unreadable store (not installed yet, or a screenshot host) keeps the defaults.
            val store = runCatching { store() }.getOrNull() ?: return
            // Web: anything but "false" is on.
            showLiveAgentActivityFlow.value =
                runCatching { store.getString(RaftShowLiveAgentActivityStorageKey) }.getOrNull() != "false"
            showAgentModelNameFlow.value =
                when (runCatching { store.getString(RaftShowAgentModelNameStorageKey) }.getOrNull()) {
                    "true" -> true
                    "false" -> false
                    else -> RaftShowAgentModelNameDefault
                }
            showSenderDescriptionFlow.value =
                when (runCatching { store.getString(RaftShowSenderDescriptionStorageKey) }.getOrNull()) {
                    "true" -> true
                    "false" -> false
                    else -> RaftShowSenderDescriptionDefault
                }
            hideEmptySidebarSectionsFlow.value = KeyValueRaftSidebarHideEmptySectionsStore(store).read()
            loaded = true
        }
    }

    val showLiveAgentActivity: Boolean
        get() {
            ensureLoaded()
            return showLiveAgentActivityFlow.value
        }

    val showAgentModelName: Boolean
        get() {
            ensureLoaded()
            return showAgentModelNameFlow.value
        }

    val showSenderDescription: Boolean
        get() {
            ensureLoaded()
            return showSenderDescriptionFlow.value
        }

    val hideEmptySidebarSections: Boolean
        get() {
            ensureLoaded()
            return hideEmptySidebarSectionsFlow.value
        }

    @Composable
    fun observeShowLiveAgentActivity(): Boolean {
        ensureLoaded()
        return showLiveAgentActivityFlow.collectAsState().value
    }

    @Composable
    fun observeShowAgentModelName(): Boolean {
        ensureLoaded()
        return showAgentModelNameFlow.collectAsState().value
    }

    @Composable
    fun observeShowSenderDescription(): Boolean {
        ensureLoaded()
        return showSenderDescriptionFlow.collectAsState().value
    }

    @Composable
    fun observeHideEmptySidebarSections(): Boolean {
        ensureLoaded()
        return hideEmptySidebarSectionsFlow.collectAsState().value
    }

    fun setShowLiveAgentActivity(show: Boolean) {
        synchronized(lock) {
            ensureLoaded()
            showLiveAgentActivityFlow.value = show
            runCatching { store().putString(RaftShowLiveAgentActivityStorageKey, show.toString()) }
        }
    }

    fun setShowAgentModelName(show: Boolean) {
        synchronized(lock) {
            ensureLoaded()
            showAgentModelNameFlow.value = show
            runCatching { store().putString(RaftShowAgentModelNameStorageKey, show.toString()) }
        }
    }

    fun setShowSenderDescription(show: Boolean) {
        synchronized(lock) {
            ensureLoaded()
            showSenderDescriptionFlow.value = show
            runCatching { store().putString(RaftShowSenderDescriptionStorageKey, show.toString()) }
        }
    }

    fun setHideEmptySidebarSections(hide: Boolean) {
        synchronized(lock) {
            ensureLoaded()
            hideEmptySidebarSectionsFlow.value = hide
            runCatching { KeyValueRaftSidebarHideEmptySectionsStore(store()).write(hide) }
        }
    }

    /** Tests: a fresh store, read again on next access. */
    @RaftCoreInternalApi fun resetForTest(store: SharedPreferenceStore?) {
        synchronized(lock) {
            loaded = false
            storeOverride = store
            showLiveAgentActivityFlow.value = RaftShowLiveAgentActivityDefault
            showAgentModelNameFlow.value = RaftShowAgentModelNameDefault
            showSenderDescriptionFlow.value = RaftShowSenderDescriptionDefault
            hideEmptySidebarSectionsFlow.value = RaftSidebarHideEmptySectionsDefault
        }
    }
}

/**
 * Settings > Appearance display choices that are saved on this device, like the Web's
 * appearanceStore (localStorage, not the account):
 * - "Live agent activity": who is working, at the top of the window (the wide top bar) and on
 *   the Home bar (Web `WorkingNowSummary`).
 * - "Show agent model": the model label next to an agent's name in a message row
 *   (Web `MessageItem`).
 * - "Show member descriptions": the sender's description on a message's author row, people
 *   and agents alike (Web `MessageItem`); the profile card keeps it.
 * - "Hide empty sidebar sections": the Home list drops sections with nothing in them; the
 *   same value as the Home section menu's "Hide empty sections"
 *   ([RaftSidebarHideEmptySectionsStore]).
 *
 * Backed by [MutableStateFlow] so the Settings switch and every mounted screen stay in step
 * via `@Composable observe*()` without creating process-wide Compose snapshot state.
 */
object RaftDisplayPreferenceRuntime {
    private val backing = RaftDisplayPreferenceStore()

    val showLiveAgentActivity: Boolean
        get() = backing.showLiveAgentActivity

    val showAgentModelName: Boolean
        get() = backing.showAgentModelName

    val showSenderDescription: Boolean
        get() = backing.showSenderDescription

    val hideEmptySidebarSections: Boolean
        get() = backing.hideEmptySidebarSections

    @Composable
    fun observeShowLiveAgentActivity(): Boolean = backing.observeShowLiveAgentActivity()

    @Composable
    fun observeShowAgentModelName(): Boolean = backing.observeShowAgentModelName()

    @Composable
    fun observeShowSenderDescription(): Boolean = backing.observeShowSenderDescription()

    @Composable
    fun observeHideEmptySidebarSections(): Boolean = backing.observeHideEmptySidebarSections()

    fun setShowLiveAgentActivity(show: Boolean) = backing.setShowLiveAgentActivity(show)

    fun setShowAgentModelName(show: Boolean) = backing.setShowAgentModelName(show)

    fun setShowSenderDescription(show: Boolean) = backing.setShowSenderDescription(show)

    fun setHideEmptySidebarSections(hide: Boolean) = backing.setHideEmptySidebarSections(hide)

    /** Tests: a fresh store, read again on next access. */
    @RaftCoreInternalApi fun resetForTest(store: SharedPreferenceStore?) = backing.resetForTest(store)
}
