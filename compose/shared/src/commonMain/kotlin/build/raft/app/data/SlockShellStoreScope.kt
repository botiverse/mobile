package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SlockShellStoreScope private constructor(
    val shellInstanceId: String,
    val initialServerId: String?
) {
    init {
        require(shellInstanceId.isNotBlank()) { "shellInstanceId must not be blank" }
    }

    companion object {
        val Legacy = create(shellInstanceId = "legacy-app-shell", initialServerId = null)

        fun create(shellInstanceId: String, initialServerId: String? = null): SlockShellStoreScope {
            val normalizedShellInstanceId = shellInstanceId.trim().takeIf { it.isNotBlank() }
                ?: error("shellInstanceId must not be blank")
            return SlockShellStoreScope(
                shellInstanceId = normalizedShellInstanceId,
                initialServerId = initialServerId?.trim()?.takeIf { it.isNotBlank() }
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SlockShellStoreScope && shellInstanceId == other.shellInstanceId

    override fun hashCode(): Int = shellInstanceId.hashCode()

    override fun toString(): String =
        "SlockShellStoreScope(shellInstanceId=$shellInstanceId, initialServerId=$initialServerId)"
}

internal class SlockShellScopedStoreCache<Value>(
    private val onGetOrPutEntered: (() -> Unit)? = null
) {
    private val lock = SynchronizedObject()
    private val values = linkedMapOf<SlockShellStoreScope, Value>()

    fun get(scope: SlockShellStoreScope): Value? = synchronized(lock) { values[scope] }

    fun getOrPut(scope: SlockShellStoreScope, create: () -> Value): Value {
        // The test seam lets the concurrency regression prove every contender reached
        // this operation before the creator is allowed to complete.
        onGetOrPutEntered?.invoke()
        return synchronized(lock) { values.getOrPut(scope, create) }
    }

    fun remove(scope: SlockShellStoreScope): Value? = synchronized(lock) { values.remove(scope) }

    fun clear(): List<Value> =
        synchronized(lock) { values.values.toList().also { values.clear() } }

    fun entries(): List<Pair<SlockShellStoreScope, Value>> =
        synchronized(lock) { values.map { (scope, value) -> scope to value } }

    val size: Int
        get() = synchronized(lock) { values.size }
}

internal data class SelectedServerSelection(
    val serverId: String?,
    val serverSlug: String?
)

internal class SelectedServerStore internal constructor(
    val scope: SlockShellStoreScope,
    private val mutex: Mutex = Mutex()
) {
    private val requestedInitialServerId = scope.initialServerId
    private var ownerUserId: String? = null
    private var selectedServerSlug: String? = null
    private var selectionInitialized: Boolean = requestedInitialServerId != null
    private val _selectedServerId = MutableStateFlow(requestedInitialServerId)
    val selectedServerId: StateFlow<String?> = _selectedServerId.asStateFlow()

    // Compatibility alias while call sites migrate to the explicit selection store.
    val currentServerId: StateFlow<String?> = selectedServerId

    private val _disposed = MutableStateFlow(false)
    val disposed: StateFlow<Boolean> = _disposed.asStateFlow()

    fun isSelected(serverId: String?): Boolean {
        val normalized = serverId?.trim()?.takeIf { it.isNotBlank() }
        return normalized != null && selectedServerId.value == normalized
    }

    internal suspend fun select(serverId: String?, serverSlug: String?): SelectedServerSelection =
        mutex.withLock {
            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
            selectionInitialized = true
            _selectedServerId.value = normalizedServerId
            selectedServerSlug = serverSlug?.trim()?.takeIf { it.isNotBlank() }
            selection()
        }

    internal suspend fun reconcile(session: StoredAuthSession): SelectedServerSelection =
        mutex.withLock {
            if (ownerUserId != session.user.id) {
                ownerUserId = session.user.id
                val initialSelection =
                    requestedInitialServerId
                        ?.takeIf { requested -> session.servers.any { it.id == requested } }
                _selectedServerId.value = initialSelection ?: session.selectedServerId
                selectedServerSlug =
                    session.servers.firstOrNull { it.id == selectedServerId.value }?.slug
                        ?: session.selectedServerSlug
                selectionInitialized = true
            } else if (!selectionInitialized) {
                _selectedServerId.value = session.selectedServerId
                selectedServerSlug = session.selectedServerSlug
                selectionInitialized = true
            } else if (selectedServerId.value != null && session.servers.none { it.id == selectedServerId.value }) {
                // Losing the selected membership does not authorize inheriting another
                // shell's process last-used server.
                _selectedServerId.value = null
                selectedServerSlug = null
            } else if (selectedServerSlug == null) {
                selectedServerSlug = session.servers.firstOrNull { it.id == selectedServerId.value }?.slug
            }
            selection()
        }

    internal suspend fun clearSelection() {
        mutex.withLock {
            ownerUserId = null
            _selectedServerId.value = requestedInitialServerId
            selectedServerSlug = null
            selectionInitialized = requestedInitialServerId != null
        }
    }

    internal suspend fun clearActiveSelection() {
        mutex.withLock {
            ownerUserId = null
            _selectedServerId.value = null
            selectedServerSlug = null
            selectionInitialized = false
        }
    }

    internal suspend fun clearIfOwnedBy(expectedUserId: String): Boolean =
        mutex.withLock {
            if (ownerUserId != expectedUserId) return@withLock false
            _selectedServerId.value = null
            selectedServerSlug = null
            selectionInitialized = true
            true
        }

    private fun selection(): SelectedServerSelection =
        SelectedServerSelection(
            serverId = selectedServerId.value,
            serverSlug = selectedServerSlug
        )

    internal fun dispose() {
        _disposed.value = true
    }
}

internal suspend fun SlockShellScopedStoreCache<SelectedServerStore>.clearActiveServerSelections() {
    entries().forEach { (_, runtime) -> runtime.clearActiveSelection() }
}

/**
 * Per-shell view over the process-owned authenticated session.
 *
 * Tokens, user identity and the server list remain process shared. The current
 * server is overlaid in memory for this shell, while an explicit switch still
 * updates the process store as the next-launch/last-used default.
 */
internal class SlockShellAuthSessionStore(
    private val delegate: SlockAuthSessionStore,
    private val selectedServerStore: SelectedServerStore,
    private val onProcessSessionCleared: suspend () -> Unit = {}
) : SlockAuthSessionStore {
    override suspend fun readSession(): StoredAuthSession? {
        val session = delegate.readSession() ?: return null
        val selection = selectedServerStore.reconcile(session)
        return session.copy(
            selectedServerId = selection.serverId,
            selectedServerSlug = selection.serverSlug,
            allowImplicitServerFallback = false
        )
    }

    override suspend fun saveSession(session: StoredAuthSession) {
        // Keep the current main atomic full-replace primitive. Restoring the old
        // delegate.saveSession path here would reintroduce stale whole-session
        // writes that can overwrite a concurrent token rotation.
        replaceAuthenticatedSession(session)
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val current = delegate.readSession() ?: return
        delegate.rotateTokensIfMatch(
            expectedAccessToken = current.accessToken,
            expectedRefreshToken = current.refreshToken,
            newAccessToken = accessToken,
            newRefreshToken = refreshToken
        )
    }

    override suspend fun replaceAuthenticatedSession(session: StoredAuthSession) {
        selectedServerStore.reconcile(session)
        delegate.replaceAuthenticatedSessionPreservingSelection(session)
    }

    override suspend fun rotateTokensIfMatch(
        expectedAccessToken: String,
        expectedRefreshToken: String,
        newAccessToken: String,
        newRefreshToken: String
    ): StoredAuthSession? =
        delegate.rotateTokensIfMatch(
            expectedAccessToken = expectedAccessToken,
            expectedRefreshToken = expectedRefreshToken,
            newAccessToken = newAccessToken,
            newRefreshToken = newRefreshToken
        )?.withShellSelection()

    override suspend fun updateMetadata(
        expectedAccountId: String?,
        transform: (StoredAuthSession) -> StoredAuthSession
    ) {
        val shellSession = readSession() ?: return
        delegate.updateMetadata(expectedAccountId) { processSession ->
            val transformed =
                transform(
                    processSession.copy(
                        selectedServerId = shellSession.selectedServerId,
                        selectedServerSlug = shellSession.selectedServerSlug
                    )
                )
            transformed.copy(
                selectedServerId = processSession.selectedServerId,
                selectedServerSlug = processSession.selectedServerSlug
            )
        }
        delegate.readSession()?.let { selectedServerStore.reconcile(it) }
    }

    private suspend fun StoredAuthSession.withShellSelection(): StoredAuthSession {
        val selection = selectedServerStore.reconcile(this)
        return copy(
            selectedServerId = selection.serverId,
            selectedServerSlug = selection.serverSlug,
            allowImplicitServerFallback = false
        )
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        val selection = selectedServerStore.select(serverId, serverSlug)
        // The process-level value is intentionally only the next-launch default.
        delegate.updateSelectedServer(selection.serverId, selection.serverSlug)
    }

    override suspend fun clearSession(reason: String, source: String) {
        selectedServerStore.clearSelection()
        delegate.clearSession(reason, source)
        onProcessSessionCleared()
    }

    override suspend fun clearSessionIfMatches(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String,
    ): Boolean {
        val cleared =
            delegate.clearSessionIfMatches(
                expectedAccountId = expectedAccountId,
                expectedAccessToken = expectedAccessToken,
                expectedRefreshToken = expectedRefreshToken,
                reason = reason,
                source = source,
            )
        if (cleared) {
            selectedServerStore.clearSelection()
            onProcessSessionCleared()
        }
        return cleared
    }
}
