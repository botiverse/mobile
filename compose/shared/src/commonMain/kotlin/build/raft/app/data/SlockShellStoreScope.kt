package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

internal class SlockShellScopedStoreCache<Value> {
    private val values = linkedMapOf<SlockShellStoreScope, Value>()

    fun get(scope: SlockShellStoreScope): Value? = values[scope]

    fun getOrPut(scope: SlockShellStoreScope, create: () -> Value): Value =
        values.getOrPut(scope, create)

    fun remove(scope: SlockShellStoreScope): Value? = values.remove(scope)

    fun clear(): List<Value> = values.values.toList().also { values.clear() }

    fun entries(): List<Pair<SlockShellStoreScope, Value>> =
        values.map { (scope, value) -> scope to value }

    val size: Int
        get() = values.size
}

internal class SlockShellStoreRuntime internal constructor(
    val scope: SlockShellStoreScope
) {
    private val _currentServerId = MutableStateFlow(scope.initialServerId)
    val currentServerId: StateFlow<String?> = _currentServerId.asStateFlow()

    private val _disposed = MutableStateFlow(false)
    val disposed: StateFlow<Boolean> = _disposed.asStateFlow()

    internal fun updateServer(serverId: String?) {
        _currentServerId.value = serverId?.trim()?.takeIf { it.isNotBlank() }
    }

    internal fun dispose() {
        _disposed.value = true
    }
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
    private val runtime: SlockShellStoreRuntime
) : SlockAuthSessionStore {
    private val mutex = Mutex()
    private val requestedInitialServerId = runtime.scope.initialServerId
    private var ownerUserId: String? = null
    private var selectedServerId: String? = requestedInitialServerId
    private var selectedServerSlug: String? = null
    private var selectionInitialized: Boolean = requestedInitialServerId != null

    override suspend fun readSession(): StoredAuthSession? {
        val session = delegate.readSession() ?: return null
        val selection = resolveSelection(session)
        return session.copy(
            selectedServerId = selection.serverId,
            selectedServerSlug = selection.serverSlug
        )
    }

    override suspend fun saveSession(session: StoredAuthSession) {
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
        resolveSelection(session)
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
        delegate.readSession()?.let { resolveSelection(it) }
    }

    private suspend fun StoredAuthSession.withShellSelection(): StoredAuthSession {
        val selection = resolveSelection(this)
        return copy(selectedServerId = selection.serverId, selectedServerSlug = selection.serverSlug)
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
        mutex.withLock {
            selectionInitialized = true
            selectedServerId = normalizedServerId
            selectedServerSlug = serverSlug?.trim()?.takeIf { it.isNotBlank() }
        }
        runtime.updateServer(normalizedServerId)
        // The process-level value is intentionally only the next-launch default.
        delegate.updateSelectedServer(normalizedServerId, serverSlug)
    }

    override suspend fun clearSession(reason: String, source: String) {
        mutex.withLock {
            ownerUserId = null
            selectedServerId = requestedInitialServerId
            selectedServerSlug = null
            selectionInitialized = requestedInitialServerId != null
        }
        runtime.updateServer(null)
        delegate.clearSession(reason, source)
    }

    private suspend fun resolveSelection(session: StoredAuthSession): ShellServerSelection =
        mutex.withLock {
            if (ownerUserId != session.user.id) {
                ownerUserId = session.user.id
                val initialSelection =
                    requestedInitialServerId
                        ?.takeIf { requested -> session.servers.any { it.id == requested } }
                selectedServerId = initialSelection ?: session.selectedServerId
                selectedServerSlug =
                    session.servers.firstOrNull { it.id == selectedServerId }?.slug
                        ?: session.selectedServerSlug
                selectionInitialized = true
            } else if (!selectionInitialized) {
                selectedServerId = session.selectedServerId
                selectedServerSlug = session.selectedServerSlug
                selectionInitialized = true
            } else if (selectedServerId != null && session.servers.none { it.id == selectedServerId }) {
                selectedServerId = session.selectedServerId?.takeIf { selected -> session.servers.any { it.id == selected } }
                selectedServerSlug = session.selectedServerSlug
            } else if (selectedServerSlug == null) {
                selectedServerSlug = session.servers.firstOrNull { it.id == selectedServerId }?.slug
            }
            runtime.updateServer(selectedServerId)
            ShellServerSelection(
                serverId = selectedServerId,
                serverSlug = selectedServerSlug
            )
        }

    private data class ShellServerSelection(
        val serverId: String?,
        val serverSlug: String?
    )
}
