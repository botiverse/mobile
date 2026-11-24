package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * How the message box sends on Compose desktop and hardware keyboards (botiverse/slock
 * `packages/shared/src/composerSendKey.ts` @ 1e18003722ce4e38e599a11836305b7494be0a25,
 * #triage:#210):
 *
 * - [Enter] (wire `"enter"`, stored as `null` on account): Enter sends, Shift+Enter inserts a
 *   newline.
 * - [ModEnter] (wire `"mod_enter"`): ⌘Enter (Apple) / Ctrl+Enter (elsewhere) sends; Enter alone
 *   and Shift+Enter insert a newline.
 */
enum class RaftComposerSendKey(val wireValue: String) {
    Enter("enter"),
    ModEnter("mod_enter");

    /** What `users.composer_send_key` stores on the account: `null` is the default (`"enter"`). */
    fun toStoredAccountWireOrNull(): String? = if (this == ModEnter) ModEnter.wireValue else null

    companion object {
        val Default: RaftComposerSendKey = Enter

        /** What the account or local store holds: only `"mod_enter"` selects [ModEnter]; everything else is [Enter]. */
        fun fromWire(value: String?): RaftComposerSendKey =
            if (value?.trim()?.lowercase() == ModEnter.wireValue) ModEnter else Default
    }
}

@RaftCoreInternalApi const val RaftComposerSendKeyStorageKey = "appearance:composer_send_key"

/**
 * Snapshot-free, thread-safe backing store for [RaftComposerSendKeyRuntime].
 *
 * Uses [MutableStateFlow] rather than Compose `mutableStateOf` so reads/writes from repository
 * and session flows never allocate Compose snapshot state records off the main thread (#264),
 * while keeping `@Composable observeSendKey()` reactive across open conversations.
 */
@RaftCoreInternalApi class RaftComposerSendKeyStore(
    private val defaultStoreProvider: () -> SharedPreferenceStore = ::raftSharedPreferenceStore,
) {
    private val lock = SynchronizedObject()

    @Volatile
    private var loaded = false

    @Volatile
    private var storeOverride: SharedPreferenceStore? = null

    private val sendKeyFlow = MutableStateFlow(RaftComposerSendKey.Default)

    private fun store(): SharedPreferenceStore = storeOverride ?: defaultStoreProvider()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val store = runCatching { store() }.getOrNull() ?: return
            val raw = runCatching { store.getString(RaftComposerSendKeyStorageKey) }.getOrNull()
            sendKeyFlow.value = RaftComposerSendKey.fromWire(raw)
            loaded = true
        }
    }

    val sendKey: RaftComposerSendKey
        get() {
            ensureLoaded()
            return sendKeyFlow.value
        }

    @Composable
    fun observeSendKey(): RaftComposerSendKey {
        ensureLoaded()
        return sendKeyFlow.collectAsState().value
    }

    fun setSendKey(sendKey: RaftComposerSendKey) {
        synchronized(lock) {
            ensureLoaded()
            sendKeyFlow.value = sendKey
            runCatching { store().putString(RaftComposerSendKeyStorageKey, sendKey.wireValue) }
        }
    }

    /** Updates in-memory state immediately (e.g. optimistic Settings tap) without writing until confirmed. */
    fun setInMemory(sendKey: RaftComposerSendKey) {
        synchronized(lock) {
            ensureLoaded()
            sendKeyFlow.value = sendKey
        }
    }

    /** Tests: a fresh store, read again on next access. */
    @RaftCoreInternalApi fun resetForTest(store: SharedPreferenceStore?) {
        synchronized(lock) {
            loaded = false
            storeOverride = store
            sendKeyFlow.value = RaftComposerSendKey.Default
        }
    }
}

/**
 * Process-wide reactive holder for the account's hardware-keyboard send preference
 * (`composerSendKey` from `GET`/`PATCH /api/auth/me`, cached locally in [SharedPreferenceStore] so
 * cold starts apply it on the first frame before the network answers).
 */
object RaftComposerSendKeyRuntime {
    private val backing = RaftComposerSendKeyStore()

    val sendKey: RaftComposerSendKey
        get() = backing.sendKey

    @Composable
    fun observeSendKey(): RaftComposerSendKey = backing.observeSendKey()

    fun setSendKey(sendKey: RaftComposerSendKey) = backing.setSendKey(sendKey)

    @RaftCoreInternalApi fun setInMemory(sendKey: RaftComposerSendKey) = backing.setInMemory(sendKey)

    /** Tests: a fresh store, read again on next access. */
    @RaftCoreInternalApi fun resetForTest(store: SharedPreferenceStore?) = backing.resetForTest(store)
}
