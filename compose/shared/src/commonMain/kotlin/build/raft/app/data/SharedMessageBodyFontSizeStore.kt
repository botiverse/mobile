package build.raft.app.data

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

internal fun messageBodyFontSizeOwnerKey(environmentId: String, accountId: String): String? {
    val environment = environmentId.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
    val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
    return "$environment|$account"
}

/**
 * Process-scoped, owner-partitioned projection of the server-owned message body font preference.
 * Multiple windows/accounts never share one unkeyed preference value.
 */
class SharedMessageBodyFontSizeStore(
    private val scope: CoroutineScope,
    private val diagnosticsLogger: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _state = slockStoreState(SharedMessageBodyFontSizeState(), "SharedMessageBodyFontSizeStore")
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
                _state.value =
                    current.copy(
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
