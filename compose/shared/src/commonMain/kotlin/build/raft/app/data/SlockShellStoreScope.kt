package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

internal data class SelectedServerSelectionUpdate(
    val selection: SelectedServerSelection,
    val changed: Boolean,
)

internal data class SelectedServerAuthoritySnapshot(
    val ownerUserId: String,
    val selection: SelectedServerSelection,
    val generation: Long,
)

internal data class ThreadReplyCacheIdentitySnapshot(
    val session: StoredAuthSession,
    val environmentId: String,
    val principalId: String,
    val serverId: String,
    val authGeneration: Long,
    val selectionGeneration: Long,
)

internal data class ThreadReplyCacheIdentityTransitionAnchor(
    val environmentId: String,
    val principalId: String,
    val authGeneration: Long,
    val selectionGeneration: Long,
)

internal class SelectedServerStore internal constructor(
    val scope: SlockShellStoreScope,
    private val mutex: Mutex = Mutex(),
    private val onSelectionChanged: (String?) -> Unit = {}
) {
    private val requestedInitialServerId = scope.initialServerId
    private var ownerUserId: String? = null
    private var selectedServerSlug: String? = null
    private var selectionInitialized: Boolean = requestedInitialServerId != null
    private var authorityGeneration: Long = 0L
    private val _selectedServerId = MutableStateFlow(requestedInitialServerId)
    val selectedServerId: StateFlow<String?> = _selectedServerId.asStateFlow()

    // Compatibility alias while call sites migrate to the explicit selection store.
    val currentServerId: StateFlow<String?> = selectedServerId

    private val _disposed = MutableStateFlow(false)
    val disposed: StateFlow<Boolean> = _disposed.asStateFlow()

    init {
        onSelectionChanged(_selectedServerId.value)
    }

    fun isSelected(serverId: String?): Boolean {
        val normalized = serverId?.trim()?.takeIf { it.isNotBlank() }
        return normalized != null && selectedServerId.value == normalized
    }

    internal suspend fun select(serverId: String?, serverSlug: String?): SelectedServerSelection =
        mutex.withLock {
            val before = authorityState()
            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
            selectionInitialized = true
            setSelectedServerId(normalizedServerId)
            selectedServerSlug = serverSlug?.trim()?.takeIf { it.isNotBlank() }
            advanceAuthorityIfChanged(before)
            selection()
        }

    internal suspend fun selectIfAccountMatches(
        expectedAccountId: String,
        serverId: String?,
        serverSlug: String?,
    ): SelectedServerSelectionUpdate? =
        mutex.withLock {
            if (ownerUserId != null && ownerUserId != expectedAccountId) {
                return@withLock null
            }
            val before = authorityState()
            val previous = selection()
            ownerUserId = expectedAccountId
            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
            selectionInitialized = true
            setSelectedServerId(normalizedServerId)
            selectedServerSlug = serverSlug?.trim()?.takeIf { it.isNotBlank() }
            advanceAuthorityIfChanged(before)
            val updated = selection()
            SelectedServerSelectionUpdate(
                selection = updated,
                changed = updated != previous,
            )
        }

    internal suspend fun reconcile(session: StoredAuthSession): SelectedServerSelection =
        mutex.withLock {
            reconcileLocked(session)
        }

    /**
     * Converges only a provider-proven account transition. A stable hybrid where the current
     * principal still equals [expectedPreviousOwner] is rejected by the caller; this atomic owner
     * check prevents an unrelated route owner from being overwritten between readiness and repair.
     */
    internal suspend fun convergeReplyCacheAuthorityTransition(
        expectedPreviousOwner: String,
        session: StoredAuthSession,
    ): SelectedServerSelection? =
        mutex.withLock {
            if (_disposed.value) return@withLock null
            val nextOwner = session.user.id.trim().takeIf { it.isNotBlank() } ?: return@withLock null
            if (
                ownerUserId != null &&
                ownerUserId != expectedPreviousOwner &&
                ownerUserId != nextOwner
            ) {
                return@withLock null
            }
            reconcileLocked(session)
        }

    /**
     * Captures the shell route only when it is owned by [session]'s principal. A null owner may be
     * initialized from this exact process-session snapshot, but a different existing owner is never
     * reconciled here: reply-cache authority must fail closed on an A-session/B-route hybrid.
     */
    internal suspend fun captureReplyCacheAuthority(
        session: StoredAuthSession,
    ): SelectedServerAuthoritySnapshot? =
        mutex.withLock {
            if (_disposed.value) return@withLock null
            val principalId = session.user.id.trim().takeIf { it.isNotBlank() } ?: return@withLock null
            if (ownerUserId != null && ownerUserId != principalId) return@withLock null
            val before = authorityState()
            if (ownerUserId == null) ownerUserId = principalId
            if (!selectionInitialized) {
                setSelectedServerId(session.selectedServerId)
                selectedServerSlug = session.selectedServerSlug
                selectionInitialized = true
            }
            val serverId = selectedServerId.value?.trim()?.takeIf { it.isNotBlank() }
            if (serverId == null) {
                advanceAuthorityIfChanged(before)
                return@withLock null
            }
            if (session.servers.isNotEmpty() && session.servers.none { it.id == serverId }) {
                advanceAuthorityIfChanged(before)
                return@withLock null
            }
            if (selectedServerSlug == null) {
                selectedServerSlug = session.servers.firstOrNull { it.id == serverId }?.slug
            }
            advanceAuthorityIfChanged(before)
            SelectedServerAuthoritySnapshot(
                ownerUserId = principalId,
                selection = selection(),
                generation = authorityGeneration,
            )
        }

    internal suspend fun isReplyCacheAuthorityCurrent(
        expected: SelectedServerAuthoritySnapshot,
    ): Boolean =
        mutex.withLock {
            !_disposed.value &&
                ownerUserId == expected.ownerUserId &&
                selection() == expected.selection &&
                authorityGeneration == expected.generation
        }

    internal suspend fun clearSelection() {
        mutex.withLock {
            val before = authorityState()
            ownerUserId = null
            setSelectedServerId(requestedInitialServerId)
            selectedServerSlug = null
            selectionInitialized = requestedInitialServerId != null
            advanceAuthorityIfChanged(before)
        }
    }

    internal suspend fun clearActiveSelection() {
        mutex.withLock {
            val before = authorityState()
            ownerUserId = null
            setSelectedServerId(null)
            selectedServerSlug = null
            selectionInitialized = false
            advanceAuthorityIfChanged(before)
        }
    }

    private data class SelectionAuthorityState(
        val ownerUserId: String?,
        val serverId: String?,
        val serverSlug: String?,
        val initialized: Boolean,
    )

    private fun authorityState(): SelectionAuthorityState =
        SelectionAuthorityState(
            ownerUserId = ownerUserId,
            serverId = selectedServerId.value,
            serverSlug = selectedServerSlug,
            initialized = selectionInitialized,
        )

    private fun advanceAuthorityIfChanged(before: SelectionAuthorityState) {
        if (authorityState() != before) authorityGeneration += 1L
    }

    private fun selection(): SelectedServerSelection =
        SelectedServerSelection(
            serverId = selectedServerId.value,
            serverSlug = selectedServerSlug
        )

    private fun reconcileLocked(session: StoredAuthSession): SelectedServerSelection {
        val before = authorityState()
        if (ownerUserId != session.user.id) {
            ownerUserId = session.user.id
            val initialSelection =
                requestedInitialServerId
                    ?.takeIf { requested -> session.servers.any { it.id == requested } }
            setSelectedServerId(initialSelection ?: session.selectedServerId)
            selectedServerSlug =
                session.servers.firstOrNull { it.id == selectedServerId.value }?.slug
                    ?: session.selectedServerSlug
            selectionInitialized = true
        } else if (!selectionInitialized) {
            setSelectedServerId(session.selectedServerId)
            selectedServerSlug = session.selectedServerSlug
            selectionInitialized = true
        } else if (selectedServerId.value != null && session.servers.none { it.id == selectedServerId.value }) {
            // Losing the selected membership does not authorize inheriting another
            // shell's process last-used server.
            setSelectedServerId(null)
            selectedServerSlug = null
        } else if (selectedServerSlug == null) {
            selectedServerSlug = session.servers.firstOrNull { it.id == selectedServerId.value }?.slug
        }
        advanceAuthorityIfChanged(before)
        return selection()
    }

    private fun setSelectedServerId(serverId: String?) {
        val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
        if (_selectedServerId.value == normalizedServerId) return
        onSelectionChanged(normalizedServerId)
        _selectedServerId.value = normalizedServerId
    }

    internal fun dispose() {
        onSelectionChanged(null)
        _disposed.value = true
    }
}

/**
 * One verifiable authority for reply-cache ownership. Environment/principal come from the immutable
 * process auth-revision binding; the route comes from the shell store's principal-bound snapshot.
 * Every live owner is revalidated before the snapshot escapes.
 */
internal class ThreadReplyCacheIdentityAuthority(
    private val sessionStore: SlockAuthSessionStore,
    private val selectedServerStore: SelectedServerStore,
    private val authContextProvider: () -> ProcessPresenceAuthContext?,
    private val environmentIdProvider: () -> String,
) {
    suspend fun capture(): ThreadReplyCacheIdentitySnapshot? {
        val authContext = authContextProvider() ?: return null
        val environmentId = environmentIdProvider().trim().trimEnd('/')
        if (environmentId.isBlank() || environmentId != authContext.environmentId) return null
        val session = sessionStore.readSession() ?: return null
        val principalId = session.user.id.trim()
        if (principalId.isBlank() || principalId != authContext.accountId) return null
        val selection = selectedServerStore.captureReplyCacheAuthority(session) ?: return null
        val serverId = selection.selection.serverId?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val confirmedContext = authContextProvider()
        val confirmedEnvironmentId = environmentIdProvider().trim().trimEnd('/')
        val confirmedSession = sessionStore.readSession()
        if (
            confirmedContext != authContext ||
            confirmedEnvironmentId != environmentId ||
            confirmedSession != session ||
            !selectedServerStore.isReplyCacheAuthorityCurrent(selection)
        ) {
            return null
        }
        return ThreadReplyCacheIdentitySnapshot(
            session = session.copy(
                selectedServerId = serverId,
                selectedServerSlug = selection.selection.serverSlug,
                allowImplicitServerFallback = false,
            ),
            environmentId = environmentId,
            principalId = principalId,
            serverId = serverId,
            authGeneration = authContext.authorityGeneration,
            selectionGeneration = selection.generation,
        )
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
    eventScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val onProcessSessionCleared: suspend () -> Unit = {}
) : SlockAuthSessionStore, SlockAuthSessionEventSource {
    private val eventMutex = Mutex()
    private val selectionMutationMutex = Mutex()
    private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 64)
    private var eventGeneration = 0L

    override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

    init {
        (delegate as? SlockAuthSessionEventSource)?.let { source ->
            eventScope.launch {
                source.authSessionEvents.collect { event ->
                    selectionMutationMutex.withLock {
                        emitAuthSessionEvent { generation -> event.withGeneration(generation) }
                    }
                }
            }
        }
    }

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

    override suspend fun selectServerIfSessionMatches(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        serverId: String,
    ): StoredAuthSession? =
        selectionMutationMutex.withLock {
            val processSession =
                delegate.selectServerIfSessionMatches(
                    expectedAccountId = expectedAccountId,
                    expectedAccessToken = expectedAccessToken,
                    expectedRefreshToken = expectedRefreshToken,
                    serverId = serverId,
                ) ?: return@withLock null
            val target = processSession.servers.firstOrNull { it.id == serverId } ?: return@withLock null
            val update =
                selectedServerStore.selectIfAccountMatches(
                    expectedAccountId = expectedAccountId,
                    serverId = target.id,
                    serverSlug = target.slug,
                ) ?: return@withLock null
            (delegate as? SlockAuthServerSelectionMemory)?.saveRememberedServerSelection(
                processSession.user,
                StoredAuthServerSelection(serverId = target.id, serverSlug = target.slug),
            )
            if (update.changed) {
                emitAuthSessionEvent { generation -> AuthSessionEvent.BindingMetadataChanged(generation) }
            }
            processSession.copy(
                selectedServerId = update.selection.serverId,
                selectedServerSlug = update.selection.serverSlug,
                allowImplicitServerFallback = false,
            )
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
        emitAuthSessionEvent { generation -> AuthSessionEvent.BindingMetadataChanged(generation) }
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

    private suspend fun emitAuthSessionEvent(
        create: (Long) -> AuthSessionEvent
    ) {
        val event = eventMutex.withLock { create(++eventGeneration) }
        events.emit(event)
    }

    private fun AuthSessionEvent.withGeneration(generation: Long): AuthSessionEvent =
        when (this) {
            is AuthSessionEvent.Authenticated -> AuthSessionEvent.Authenticated(generation)
            is AuthSessionEvent.TokensRotated -> AuthSessionEvent.TokensRotated(generation)
            is AuthSessionEvent.BindingMetadataChanged -> AuthSessionEvent.BindingMetadataChanged(generation)
            is AuthSessionEvent.Cleared -> AuthSessionEvent.Cleared(generation, reason)
        }
}
