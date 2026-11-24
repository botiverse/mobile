package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SharedMessageBodyFontSizeState(
    val selectedKeysByOwner: Map<String, String> = emptyMap(),
    val loadingOwners: Set<String> = emptySet(),
    val loadedOwners: Set<String> = emptySet(),
) {
    fun selectedKey(ownerKey: String?): String =
        ownerKey?.let(selectedKeysByOwner::get) ?: "md"
}

@RaftCoreInternalApi fun messageBodyFontSizeOwnerKey(environmentId: String, accountId: String): String? {
    val environment = environmentId.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
    val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
    return "$environment|$account"
}

/**
 * The owner key of the last committed auth session in [environmentId], read lock-free from the
 * session store ([KeyValueAuthSessionStore.readCommittedSessionSnapshot]): a conversation page's
 * owner before it has captured its session, so its first frame already finds the account's
 * last-known size. Null when signed out or the store is not installed.
 */
@RaftCoreInternalApi fun messageBodyFontSizeCommittedOwnerKey(environmentId: String): String? {
    val accountId =
        runCatching {
            KeyValueAuthSessionStore(RaftSharedAuthSessionKeyValueStoreProvider.create().store)
                .readCommittedSessionSnapshot()
                ?.user
                ?.id
        }.getOrNull() ?: return null
    return messageBodyFontSizeOwnerKey(environmentId, accountId)
}

/**
 * A conversation page's message body owner: its captured session's account ([accountId]), or
 * before it has one (the cached first frame) the committed session's
 * ([messageBodyFontSizeCommittedOwnerKey]), so messages start at the last-known size, not at md.
 */
@RaftCoreInternalApi fun threadMessageBodyOwnerKey(environmentId: String, accountId: String?): String? =
    accountId?.let { messageBodyFontSizeOwnerKey(environmentId, it) }
        ?: messageBodyFontSizeCommittedOwnerKey(environmentId)

/**
 * Durable last-known copy of each owner's message body size (sm / md / lg), so a cold start draws
 * messages at the account's size from the first frame instead of at md until the appearance
 * settings load returns (then switching, seconds after opening). Keyed by owner (environment +
 * account), like the other last-known copies: kept on sign-out, never read for another account.
 */
class MessageBodyFontSizeLastKnownStore(private val store: SharedPreferenceStore) {
    /** The owner whose size was written last (the account this device last used). */
    fun lastOwnerKey(): String? = store.getString(LAST_OWNER_KEY)?.takeIf { it.isNotBlank() }

    fun read(ownerKey: String): String? =
        store.getString(ownerStorageKey(ownerKey))?.let(::normalizeSettingsMessageBodyFontSize)

    fun write(ownerKey: String, selectedKey: String) {
        store.putString(ownerStorageKey(ownerKey), selectedKey)
        store.putString(LAST_OWNER_KEY, ownerKey)
    }

    private fun ownerStorageKey(ownerKey: String) = "$OWNER_KEY_PREFIX$ownerKey"

    private companion object {
        const val LAST_OWNER_KEY = "settings:persist:message_body_font_size:v1:last_owner"
        const val OWNER_KEY_PREFIX = "settings:persist:message_body_font_size:v1:owner:"
    }
}

/**
 * Process-scoped, owner-partitioned projection of the server-owned message body font preference.
 * Multiple windows/accounts never share one unkeyed preference value.
 *
 * With [lastKnown], the last owner's stored size is in the state from construction and any other
 * owner's is put in when its load starts, so pages draw it before the server answers; every
 * server answer is written back. A seeded owner is not "loaded": the server is still asked.
 */
class SharedMessageBodyFontSizeStore(
    private val scope: CoroutineScope,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val lastKnown: MessageBodyFontSizeLastKnownStore? = null,
) {
    private val mutex = Mutex()
    private val _state = raftStoreState(initialState(), "SharedMessageBodyFontSizeStore")

    private fun initialState(): SharedMessageBodyFontSizeState {
        val store = lastKnown ?: return SharedMessageBodyFontSizeState()
        val seeded =
            runCatching {
                store.lastOwnerKey()?.let { owner -> store.read(owner)?.let { selected -> owner to selected } }
            }.getOrNull()
        return SharedMessageBodyFontSizeState(selectedKeysByOwner = listOfNotNull(seeded).toMap())
    }

    private fun lastKnownFor(ownerKey: String): String? = runCatching { lastKnown?.read(ownerKey) }.getOrNull()

    private fun rememberLastKnown(ownerKey: String, selectedKey: String) {
        runCatching { lastKnown?.write(ownerKey, selectedKey) }
    }
    val state: StateFlow<SharedMessageBodyFontSizeState> = _state.asStateFlow()

    private val loads = mutableMapOf<String, Deferred<DataResult<SettingsAppearanceSnapshot>>>()
    private val generations = mutableMapOf<String, Long>()

    suspend fun ensureLoaded(
        ownerKey: String,
        force: Boolean = false,
        loadAppearance: suspend () -> DataResult<SettingsAppearanceSnapshot>,
    ) {
        val normalizedOwnerKey = ownerKey.trim().takeIf { it.isNotBlank() } ?: return
        // Conversation pages call this from a LaunchedEffect (main thread); the loading-owner write
        // belongs on the store dispatcher like every other write of this store.
        scope.raftWithStoreScope { ensureLoadedOnStoreScope(normalizedOwnerKey, force, loadAppearance) }
    }

    private suspend fun ensureLoadedOnStoreScope(
        normalizedOwnerKey: String,
        force: Boolean,
        loadAppearance: suspend () -> DataResult<SettingsAppearanceSnapshot>,
    ) {
        val deferred =
            mutex.withLock {
                val current = _state.value
                if (!force && normalizedOwnerKey in current.loadedOwners) return@withLock null
                loads[normalizedOwnerKey]?.let { active ->
                    if (!force) return@withLock active
                }
                val requestGeneration = (generations[normalizedOwnerKey] ?: 0L) + 1L
                generations[normalizedOwnerKey] = requestGeneration
                val seeded =
                    if (normalizedOwnerKey in current.selectedKeysByOwner) {
                        current.selectedKeysByOwner
                    } else {
                        lastKnownFor(normalizedOwnerKey)
                            ?.let { selected -> current.selectedKeysByOwner + (normalizedOwnerKey to selected) }
                            ?: current.selectedKeysByOwner
                    }
                _state.value =
                    current.copy(
                        selectedKeysByOwner = seeded,
                        loadingOwners = current.loadingOwners + normalizedOwnerKey,
                        loadedOwners = current.loadedOwners - normalizedOwnerKey,
                    )
                scope.async(start = CoroutineStart.LAZY) {
                    val result =
                        runCatchingPreservingCancellation { loadAppearance() }
                            .getOrElse { throwable ->
                                DataResult.Error(
                                    ScreenError(
                                        code = "message_body_font_size_load_failed",
                                        retryable = true,
                                        serverMessage = throwable.message?.takeIf(String::isNotBlank),
                                    )
                                )
                            }
                    result.also {
                        publishLoadResult(normalizedOwnerKey, requestGeneration, result)
                    }
                }.also { loads[normalizedOwnerKey] = it }
            }
        deferred?.start()
        deferred?.await()
    }

    private suspend fun publishLoadResult(
        ownerKey: String,
        requestGeneration: Long,
        result: DataResult<SettingsAppearanceSnapshot>,
    ) {
        mutex.withLock {
            if (generations[ownerKey] != requestGeneration) {
                diagnosticsLogger("message_body_font_size source=load result=stale owner=$ownerKey")
                return@withLock
            }
            loads.remove(ownerKey)
            val current = _state.value
            when (result) {
                is DataResult.Content -> {
                    val selectedKey = normalizeSettingsMessageBodyFontSize(result.data.messageBodyFontSize.selectedKey)
                    _state.value =
                        current.copy(
                            selectedKeysByOwner = current.selectedKeysByOwner + (ownerKey to selectedKey),
                            loadingOwners = current.loadingOwners - ownerKey,
                            loadedOwners = current.loadedOwners + ownerKey,
                        )
                    rememberLastKnown(ownerKey, selectedKey)
                    diagnosticsLogger("message_body_font_size source=load owner=$ownerKey selected=$selectedKey")
                }
                else -> {
                    _state.value = current.copy(loadingOwners = current.loadingOwners - ownerKey)
                    diagnosticsLogger(
                        "message_body_font_size source=load result=failure owner=$ownerKey " +
                            "detail=${result.messageBodyFontSizeDiagnostics()}"
                    )
                }
            }
        }
    }

    suspend fun acceptAuthoritative(ownerKey: String, snapshot: SettingsAppearanceSnapshot) {
        val normalizedOwnerKey = ownerKey.trim().takeIf { it.isNotBlank() } ?: return
        scope.raftWithStoreScope { acceptAuthoritativeOnStoreScope(normalizedOwnerKey, snapshot) }
    }

    private suspend fun acceptAuthoritativeOnStoreScope(normalizedOwnerKey: String, snapshot: SettingsAppearanceSnapshot) {
        mutex.withLock {
            generations[normalizedOwnerKey] = (generations[normalizedOwnerKey] ?: 0L) + 1L
            loads.remove(normalizedOwnerKey)
            val selectedKey = normalizeSettingsMessageBodyFontSize(snapshot.messageBodyFontSize.selectedKey)
            val current = _state.value
            _state.value =
                current.copy(
                    selectedKeysByOwner = current.selectedKeysByOwner + (normalizedOwnerKey to selectedKey),
                    loadingOwners = current.loadingOwners - normalizedOwnerKey,
                    loadedOwners = current.loadedOwners + normalizedOwnerKey,
                )
            rememberLastKnown(normalizedOwnerKey, selectedKey)
            diagnosticsLogger("message_body_font_size source=authoritative owner=$normalizedOwnerKey selected=$selectedKey")
        }
    }
}

private fun DataResult<*>.messageBodyFontSizeDiagnostics(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:${reason.name}"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }
