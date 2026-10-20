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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class RaftShellStoreScope private constructor(
    val shellInstanceId: String,
    val initialServerId: String?
) {
    init {
        require(shellInstanceId.isNotBlank()) { "shellInstanceId must not be blank" }
    }

    companion object {
        val Legacy = create(shellInstanceId = "legacy-app-shell", initialServerId = null)

        fun create(shellInstanceId: String, initialServerId: String? = null): RaftShellStoreScope {
            val normalizedShellInstanceId = shellInstanceId.trim().takeIf { it.isNotBlank() }
                ?: error("shellInstanceId must not be blank")
            return RaftShellStoreScope(
                shellInstanceId = normalizedShellInstanceId,
                initialServerId = initialServerId?.trim()?.takeIf { it.isNotBlank() }
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RaftShellStoreScope && shellInstanceId == other.shellInstanceId

    override fun hashCode(): Int = shellInstanceId.hashCode()

    override fun toString(): String =
        "RaftShellStoreScope(shellInstanceId=$shellInstanceId, initialServerId=$initialServerId)"
}

internal class RaftShellScopedStoreCache<Value>(
    private val onGetOrPutEntered: (() -> Unit)? = null
) {
    private val lock = SynchronizedObject()
    private val values = linkedMapOf<RaftShellStoreScope, Value>()

    fun get(scope: RaftShellStoreScope): Value? = synchronized(lock) { values[scope] }

    fun getOrPut(scope: RaftShellStoreScope, create: () -> Value): Value {
        // The test seam lets the concurrency regression prove every contender reached
        // this operation before the creator is allowed to complete.
        onGetOrPutEntered?.invoke()
        return synchronized(lock) { values.getOrPut(scope, create) }
    }

    fun remove(scope: RaftShellStoreScope): Value? = synchronized(lock) { values.remove(scope) }

    fun clear(): List<Value> =
        synchronized(lock) { values.values.toList().also { values.clear() } }

    fun entries(): List<Pair<RaftShellStoreScope, Value>> =
        synchronized(lock) { values.map { (scope, value) -> scope to value } }

    val size: Int
        get() = synchronized(lock) { values.size }
}

/**
 * Selects the shell scope that should be serialized for a child route.
 *
 * [RaftShellStoreScope.initialServerId] is only bootstrap data. Once a live
 * selected-server store exists, its value is the current shell authority,
 * including `null` (the shell currently has no selected server). In
 * particular, do not fall back to an older initial value after an in-process
 * server switch or an explicit selection clear.
 */
internal fun childNavigationStoreScope(
    sourceScope: RaftShellStoreScope,
    selectedServerStore: SelectedServerStore?
): RaftShellStoreScope =
    selectedServerStore?.let { selected ->
        RaftShellStoreScope.create(
            shellInstanceId = sourceScope.shellInstanceId,
            initialServerId = selected.selectedServerId.value
        )
    } ?: sourceScope

internal data class SelectedServerSelection(
    val serverId: String?,
    val serverSlug: String?
)

internal data class SelectedServerSelectionUpdate(
    val selection: SelectedServerSelection,
    val changed: Boolean,
)

internal data class SelectedServerSelectionCommit(
    val processSession: StoredAuthSession,
    val update: SelectedServerSelectionUpdate,
)

internal data class SelectedServerAuthoritySnapshot(
    val ownerUserId: String,
    val selection: SelectedServerSelection,
    val generation: Long,
    /** Restart-stable epoch used by durable identity-bound projections. */
    val selectionEpoch: Long = generation,
)

@Serializable
internal data class SelectedServerSelectionEpochRecord(
    val epoch: Long,
    val serverId: String?,
    /** Latest process-auth revision observed for this principal. */
    val authGeneration: Long = 0L,
)

internal interface SelectedServerSelectionEpochStore {
    fun read(
        environmentId: String,
        shellInstanceId: String,
        principalId: String,
    ): SelectedServerSelectionEpochRecord?

    fun write(
        environmentId: String,
        shellInstanceId: String,
        principalId: String,
        epoch: Long,
        serverId: String?,
        authGeneration: Long,
    )
}

internal class KeyValueSelectedServerSelectionEpochStore(
    private val store: RaftKeyValueStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SelectedServerSelectionEpochStore {
    override fun read(
        environmentId: String,
        shellInstanceId: String,
        principalId: String,
    ): SelectedServerSelectionEpochRecord? =
        store.getString(key(environmentId, shellInstanceId, principalId))
            ?.let { encoded ->
                runCatching {
                    json.decodeFromString<SelectedServerSelectionEpochRecord>(encoded)
                }.getOrNull()?.let { record ->
                    record.copy(
                        epoch = record.epoch.coerceAtLeast(0L),
                        authGeneration = record.authGeneration.coerceAtLeast(0L),
                    )
                }
            }

    override fun write(
        environmentId: String,
        shellInstanceId: String,
        principalId: String,
        epoch: Long,
        serverId: String?,
        authGeneration: Long,
    ) {
        store.putString(
            key(environmentId, shellInstanceId, principalId),
            json.encodeToString(
                SelectedServerSelectionEpochRecord(
                    epoch = epoch.coerceAtLeast(0L),
                    serverId = serverId?.trim()?.takeIf { it.isNotBlank() },
                    authGeneration = authGeneration.coerceAtLeast(0L),
                )
            )
        )
    }

    private fun key(environmentId: String, shellInstanceId: String, principalId: String): String =
        listOf(environmentId, shellInstanceId, principalId)
            .joinToString("|") { value -> value.trim().let { normalized -> "${normalized.length}:$normalized" } }
            .let { "slock:thread-selection-epoch:v1:$it" }
}

internal data class ThreadReplyCacheIdentitySnapshot(
    val session: StoredAuthSession,
    val environmentId: String,
    val principalId: String,
    val serverId: String,
    val authGeneration: Long,
    val selectionGeneration: Long,
    /**
     * Stable process-auth binding generation. Token rotation keeps this value;
     * logout, account, or environment replacement advances it.
     */
    val bindingGeneration: Long = 0L,
)

internal data class ThreadReplyCacheIdentityTransitionAnchor(
    val environmentId: String,
    val principalId: String,
    val authGeneration: Long,
    val selectionGeneration: Long,
)

internal class SelectedServerStore internal constructor(
    val scope: RaftShellStoreScope,
    private val mutex: Mutex = Mutex(),
    private val onSelectionChanged: (String?) -> Unit = {},
    private val selectionEpochStore: SelectedServerSelectionEpochStore? = null,
    private val environmentIdProvider: () -> String = { "" },
) {
    private var mutationRevision: Long = 0L
    private val requestedInitialServerId = scope.initialServerId
    private var ownerUserId: String? = null
    private var selectedServerSlug: String? = null
    private var selectionInitialized: Boolean = requestedInitialServerId != null
    private var authorityGeneration: Long = 0L
    private var durableSelectionEpoch: Long = 0L
    private var durableSelectionPrincipal: String? = null
    private var durableSelectionEnvironment: String? = null
    private var durableSelectionAuthGeneration: Long? = null
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
            mutationRevision += 1L
            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
            selectionInitialized = true
            setSelectedServerId(normalizedServerId)
            selectedServerSlug = serverSlug?.trim()?.takeIf { it.isNotBlank() }
            advanceAuthorityIfChanged(before)
            syncDurableSelectionEpochAfterMutationLocked()
            selection()
        }

    internal suspend fun commitSelection(
        serverId: String?,
        serverSlug: String?,
        commitProcessSelection: suspend () -> Unit,
    ): SelectedServerSelection =
        mutex.withLock {
            // Publish the process next-launch default before the shell value while holding the
            // shell authority lock. Concurrent readSession().reconcile() therefore sees either
            // the complete old pair or the complete new pair, never a shell-only intermediate.
            commitProcessSelection()
            val before = authorityState()
            mutationRevision += 1L
            selectionInitialized = true
            setSelectedServerId(serverId?.trim()?.takeIf { it.isNotBlank() })
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
            mutationRevision += 1L
            ownerUserId = expectedAccountId
            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
            selectionInitialized = true
            setSelectedServerId(normalizedServerId)
            selectedServerSlug = serverSlug?.trim()?.takeIf { it.isNotBlank() }
            advanceAuthorityIfChanged(before)
            syncDurableSelectionEpochAfterMutationLocked()
            val updated = selection()
            SelectedServerSelectionUpdate(
                selection = updated,
                changed = updated != previous,
            )
        }

    internal suspend fun selectIfAccountAndSourceMatch(
        expectedAccountId: String,
        expectedServerId: String?,
        serverId: String,
        commitProcessSelection: suspend () -> StoredAuthSession?,
    ): SelectedServerSelectionCommit? =
        mutex.withLock {
            // Keep the source comparison and process/shell commit under this shell's one
            // canonical selection lock. A check before entering this block would leave an A→C
            // switch window where a stale A→B intent could overwrite C.
            if (_disposed.value) return@withLock null
            if (ownerUserId != null && ownerUserId != expectedAccountId) return@withLock null
            if (expectedServerId != null && selectedServerId.value != expectedServerId) return@withLock null
            val processSession = commitProcessSelection() ?: return@withLock null
            val target = processSession.servers.firstOrNull { it.id == serverId } ?: return@withLock null
            val before = authorityState()
            val previous = selection()
            mutationRevision += 1L
            ownerUserId = expectedAccountId
            selectionInitialized = true
            setSelectedServerId(target.id)
            selectedServerSlug = target.slug
            advanceAuthorityIfChanged(before)
            SelectedServerSelectionCommit(
                processSession = processSession,
                update = SelectedServerSelectionUpdate(
                    selection = selection(),
                    changed = selection() != previous,
                ),
            )
        }

    internal suspend fun reconcile(session: StoredAuthSession): SelectedServerSelection =
        mutex.withLock {
            mutationRevision += 1L
            reconcileLocked(session).also {
                // Reconciliation may clear a server that disappeared from the same principal's
                // membership.  Once an authenticated durable authority has been captured, that
                // is a real selection boundary and must survive process death immediately.
                syncDurableSelectionEpochAfterMutationLocked()
            }
        }

    /**
     * Converges only a provider-proven account transition. A stable hybrid where the current
     * principal still equals [expectedPreviousOwner] is rejected by the caller; this atomic owner
     * check prevents an unrelated route owner from being overwritten between readiness and repair.
     */
    internal suspend fun convergeReplyCacheAuthorityTransition(
        expectedPreviousOwner: String,
        session: StoredAuthSession,
        authGeneration: Long,
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
            mutationRevision += 1L
            reconcileLocked(session).also {
                // Account-transition convergence can change the selected server after the
                // previous principal's producer has been retired. Persist that boundary before
                // the waiter allows a new route capture; otherwise a process death in the
                // transition window could reuse the prior restart-stable selection epoch.
                syncDurableSelectionEpochLocked(
                    principalId = session.user.id,
                    authGeneration = authGeneration,
                )
            }
        }

    /**
     * Captures the shell route only when it is owned by [session]'s principal. A null owner may be
     * initialized from this exact process-session snapshot, but a different existing owner is never
     * reconciled here: reply-cache authority must fail closed on an A-session/B-route hybrid.
     */
    internal suspend fun captureReplyCacheAuthority(
        session: StoredAuthSession,
        authGeneration: Long,
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
                advanceAuthorityAndMutationIfChanged(before)
                return@withLock null
            }
            if (session.servers.isNotEmpty() && session.servers.none { it.id == serverId }) {
                advanceAuthorityAndMutationIfChanged(before)
                return@withLock null
            }
            if (selectedServerSlug == null) {
                selectedServerSlug = session.servers.firstOrNull { it.id == serverId }?.slug
            }
            advanceAuthorityAndMutationIfChanged(before)
            syncDurableSelectionEpochLocked(
                principalId = principalId,
                authGeneration = authGeneration,
            )
            SelectedServerAuthoritySnapshot(
                ownerUserId = principalId,
                selection = selection(),
                generation = authorityGeneration,
                selectionEpoch = durableSelectionEpoch,
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
            val durablePrincipal = durableSelectionPrincipal
            val durableAuthGeneration = durableSelectionAuthGeneration
            val before = authorityState()
            mutationRevision += 1L
            ownerUserId = null
            setSelectedServerId(requestedInitialServerId)
            selectedServerSlug = null
            selectionInitialized = requestedInitialServerId != null
            advanceAuthorityIfChanged(before)
            if (durablePrincipal != null && durableAuthGeneration != null) {
                // `requestedInitialServerId` is only a route bootstrap hint.  It may still be
                // present after logout, but must never be written back as the retired principal's
                // durable selection.  Persist an explicit null boundary and advance the epoch so
                // a process death between session clear and the next auth revision cannot revive
                // pending rows under the old server fence.
                advanceDurableSelectionEpochLocked(
                    principalId = durablePrincipal,
                    authGeneration = durableAuthGeneration,
                )
            }
        }
    }

    internal suspend fun clearActiveSelection() {
        mutex.withLock {
            val durablePrincipal = durableSelectionPrincipal
            val durableAuthGeneration = durableSelectionAuthGeneration
            val before = authorityState()
            mutationRevision += 1L
            ownerUserId = null
            setSelectedServerId(null)
            selectedServerSlug = null
            selectionInitialized = false
            advanceAuthorityIfChanged(before)
            if (durablePrincipal != null && durableAuthGeneration != null) {
                syncDurableSelectionEpochLocked(durablePrincipal, durableAuthGeneration)
            }
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

    private fun advanceAuthorityAndMutationIfChanged(before: SelectionAuthorityState) {
        if (authorityState() == before) return
        authorityGeneration += 1L
        mutationRevision += 1L
    }

    internal suspend fun captureMutationRevision(): Long = mutex.withLock { mutationRevision }

    internal suspend fun clearActiveSelectionIfRevision(expectedRevision: Long): Boolean =
        mutex.withLock {
            if (mutationRevision != expectedRevision) return@withLock false
            val durablePrincipal = durableSelectionPrincipal
            val durableAuthGeneration = durableSelectionAuthGeneration
            val before = authorityState()
            mutationRevision += 1L
            ownerUserId = null
            setSelectedServerId(null)
            selectedServerSlug = null
            selectionInitialized = false
            advanceAuthorityIfChanged(before)
            if (durablePrincipal != null && durableAuthGeneration != null) {
                syncDurableSelectionEpochLocked(durablePrincipal, durableAuthGeneration)
            }
            true
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

    /**
     * Restores the selection epoch from durable app-scope storage.  The
     * in-memory authority generation remains a process fence; only an actual
     * selected-server change advances the restart-stable epoch.
     */
    private fun syncDurableSelectionEpochLocked(
        principalId: String,
        authGeneration: Long,
    ) {
        val normalizedPrincipal = principalId.trim().takeIf { it.isNotBlank() } ?: return
        val store = selectionEpochStore
        if (store == null) {
            durableSelectionPrincipal = normalizedPrincipal
            durableSelectionEpoch = authorityGeneration
            durableSelectionEnvironment = environmentIdProvider().trim().trimEnd('/')
            durableSelectionAuthGeneration = authGeneration
            return
        }
        val environmentId = environmentIdProvider().trim().trimEnd('/')
        val selectedServerId = selectedServerId.value?.trim()?.takeIf { it.isNotBlank() }
        val record = store.read(environmentId, scope.shellInstanceId, normalizedPrincipal)
        if (
            durableSelectionPrincipal != normalizedPrincipal ||
                durableSelectionEnvironment != environmentId
        ) {
            durableSelectionPrincipal = normalizedPrincipal
            durableSelectionEpoch = record?.epoch ?: 0L
            durableSelectionEnvironment = environmentId
            durableSelectionAuthGeneration = null
        }
        when {
            record == null -> {
                durableSelectionAuthGeneration = authGeneration
                store.write(
                    environmentId,
                    scope.shellInstanceId,
                    normalizedPrincipal,
                    durableSelectionEpoch,
                    selectedServerId,
                    authGeneration,
                )
            }
            authGeneration < record.authGeneration -> {
                // A delayed capture from a retired auth revision cannot mutate the current
                // durable selection authority or regress the in-memory revision used by a
                // subsequent legitimate server switch.  The caller will fail its final
                // auth-context revalidation; keep the latest epoch and prior revision intact.
                durableSelectionEpoch = record.epoch
            }
            record.serverId != selectedServerId -> {
                durableSelectionAuthGeneration = authGeneration
                durableSelectionEpoch = nextSelectionEpoch(record.epoch)
                store.write(
                    environmentId,
                    scope.shellInstanceId,
                    normalizedPrincipal,
                    durableSelectionEpoch,
                    selectedServerId,
                    authGeneration,
                )
            }
            else -> {
                durableSelectionAuthGeneration = authGeneration
                durableSelectionEpoch = record.epoch
                if (authGeneration > record.authGeneration) {
                    store.write(
                        environmentId,
                        scope.shellInstanceId,
                        normalizedPrincipal,
                        durableSelectionEpoch,
                        selectedServerId,
                        authGeneration,
                    )
                }
            }
        }
    }

    /**
     * A server switch is itself a durable identity boundary.  Once this store has captured an
     * authenticated authority, persist the new selection immediately instead of waiting for a
     * later route capture; otherwise a process death in the small switch→capture window could
     * resurrect a pending send under the previous server/epoch.
     */
    private fun syncDurableSelectionEpochAfterMutationLocked() {
        val principal = durableSelectionPrincipal ?: return
        // A logout/account transition may leave the previous durable principal cached until the
        // next verified capture.  Never attribute a new owner's selection to that old key.
        if (ownerUserId != principal) return
        val authGeneration = durableSelectionAuthGeneration ?: return
        syncDurableSelectionEpochLocked(principal, authGeneration)
    }

    /**
     * Retires the currently captured principal's selection without consulting the route's
     * bootstrap server.  Logout/clear is a semantic boundary even when the bootstrap value happens
     * to equal the last selected server, so the restart-stable epoch is always advanced.
     */
    private fun advanceDurableSelectionEpochLocked(
        principalId: String,
        authGeneration: Long,
    ) {
        val normalizedPrincipal = principalId.trim().takeIf { it.isNotBlank() } ?: return
        val store = selectionEpochStore
        val environmentId = environmentIdProvider().trim().trimEnd('/')
        if (store == null) {
            durableSelectionPrincipal = normalizedPrincipal
            durableSelectionEnvironment = environmentId
            durableSelectionAuthGeneration = authGeneration
            durableSelectionEpoch = (durableSelectionEpoch + 1L).coerceAtLeast(0L)
            return
        }
        val record = store.read(environmentId, scope.shellInstanceId, normalizedPrincipal)
        val baseEpoch = maxOf(durableSelectionEpoch, record?.epoch ?: 0L)
        val nextEpoch = nextSelectionEpoch(baseEpoch)
        durableSelectionPrincipal = normalizedPrincipal
        durableSelectionEnvironment = environmentId
        durableSelectionAuthGeneration = authGeneration
        durableSelectionEpoch = nextEpoch
        store.write(
            environmentId = environmentId,
            shellInstanceId = scope.shellInstanceId,
            principalId = normalizedPrincipal,
            epoch = nextEpoch,
            serverId = null,
            authGeneration = authGeneration,
        )
    }

    private fun nextSelectionEpoch(epoch: Long): Long =
        if (epoch == Long.MAX_VALUE) Long.MAX_VALUE else (epoch + 1L).coerceAtLeast(0L)

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
    private val sessionStore: RaftAuthSessionStore,
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
        val selection =
            selectedServerStore.captureReplyCacheAuthority(
                session = session,
                authGeneration = authContext.authorityGeneration,
            ) ?: return null
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
            selectionGeneration = selection.selectionEpoch,
            bindingGeneration = authContext.writeGeneration,
        )
    }
}

internal suspend fun RaftShellScopedStoreCache<SelectedServerStore>.clearActiveServerSelections() {
    entries().forEach { (_, runtime) -> runtime.clearActiveSelection() }
}

internal data class SelectedServerMutationReceipt(
    val store: SelectedServerStore,
    val revision: Long,
)

internal suspend fun RaftShellScopedStoreCache<SelectedServerStore>.captureActiveServerSelectionReceipts():
    List<SelectedServerMutationReceipt> =
    entries().map { (_, store) ->
        SelectedServerMutationReceipt(store, store.captureMutationRevision())
    }

internal suspend fun clearActiveServerSelectionsIfUnchanged(
    receipts: List<SelectedServerMutationReceipt>,
) {
    receipts.forEach { receipt ->
        receipt.store.clearActiveSelectionIfRevision(receipt.revision)
    }
}

/**
 * Per-shell view over the process-owned authenticated session.
 *
 * Tokens, user identity and the server list remain process shared. The current
 * server is overlaid in memory for this shell, while an explicit switch still
 * updates the process store as the next-launch/last-used default.
 */
internal class RaftShellAuthSessionStore(
    private val delegate: RaftAuthSessionStore,
    private val selectedServerStore: SelectedServerStore,
    eventScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val onProcessSessionCleared: suspend () -> Unit = {}
) : RaftAuthSessionStore, RaftAuthSessionEventSource {
    private val eventMutex = Mutex()
    private val selectionMutationMutex = Mutex()
    private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 64)
    private var eventGeneration = 0L

    override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

    internal fun processTerminalSourceOrNull(): RaftAuthSessionTerminalSource? =
        delegate.authSessionTerminalSourceOrNull()

    init {
        (delegate as? RaftAuthSessionEventSource)?.let { source ->
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
        return selectionMutationMutex.withLock { readSessionLocked() }
    }

    private suspend fun readSessionLocked(): StoredAuthSession? =
        delegate.readSession()?.withShellSelection()

    override suspend fun readOrCreateAuthInstallationId(candidate: String): String =
        delegate.readOrCreateAuthInstallationId(candidate)

    override suspend fun saveSession(session: StoredAuthSession) {
        // Keep the current main atomic full-replace primitive. Restoring the old
        // delegate.saveSession path here would reintroduce stale whole-session
        // writes that can overwrite a concurrent token rotation.
        replaceAuthenticatedSession(session)
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        selectionMutationMutex.withLock {
            val current = delegate.readSession() ?: return@withLock
            delegate.rotateTokensIfMatch(
                expectedAccessToken = current.accessToken,
                expectedRefreshToken = current.refreshToken,
                newAccessToken = accessToken,
                newRefreshToken = refreshToken
            )
        }
    }

    override suspend fun replaceAuthenticatedSession(session: StoredAuthSession) {
        selectionMutationMutex.withLock {
            selectedServerStore.reconcile(session)
            delegate.replaceAuthenticatedSessionPreservingSelection(session)
        }
    }

    override suspend fun rotateTokensIfMatch(
        expectedAccessToken: String,
        expectedRefreshToken: String,
        newAccessToken: String,
        newRefreshToken: String
    ): StoredAuthSession? =
        selectionMutationMutex.withLock {
            delegate.rotateTokensIfMatch(
                expectedAccessToken = expectedAccessToken,
                expectedRefreshToken = expectedRefreshToken,
                newAccessToken = newAccessToken,
                newRefreshToken = newRefreshToken
            )?.withShellSelection()
        }

    override suspend fun prepareRefreshAttemptIfMatch(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        candidate: StoredAuthRefreshAttempt,
    ): StoredAuthRefreshAttempt? =
        delegate.prepareRefreshAttemptIfMatch(
            expectedAccountId = expectedAccountId,
            expectedAccessToken = expectedAccessToken,
            expectedRefreshToken = expectedRefreshToken,
            candidate = candidate,
        )

    override suspend fun rotateTokensAndClearRefreshAttemptIfMatch(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        expectedAttemptId: String,
        newAccessToken: String,
        newRefreshToken: String,
    ): StoredAuthSession? =
        selectionMutationMutex.withLock {
            delegate.rotateTokensAndClearRefreshAttemptIfMatch(
                expectedAccountId = expectedAccountId,
                expectedAccessToken = expectedAccessToken,
                expectedRefreshToken = expectedRefreshToken,
                expectedAttemptId = expectedAttemptId,
                newAccessToken = newAccessToken,
                newRefreshToken = newRefreshToken,
            )?.withShellSelection()
        }

    override suspend fun updateMetadata(
        expectedAccountId: String?,
        transform: (StoredAuthSession) -> StoredAuthSession
    ) {
        selectionMutationMutex.withLock {
            val shellSession = readSessionLocked() ?: return@withLock
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
    }

    override suspend fun selectServerIfSessionMatches(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        expectedSelectedServerId: String?,
        serverId: String,
    ): StoredAuthSession? =
        selectionMutationMutex.withLock {
            val committed =
                selectedServerStore.selectIfAccountAndSourceMatch(
                    expectedAccountId = expectedAccountId,
                    expectedServerId = expectedSelectedServerId,
                    serverId = serverId,
                ) {
                    delegate.selectServerIfSessionMatches(
                        expectedAccountId = expectedAccountId,
                        expectedAccessToken = expectedAccessToken,
                        expectedRefreshToken = expectedRefreshToken,
                        serverId = serverId,
                    )
                } ?: return@withLock null
            val processSession = committed.processSession
            val update = committed.update
            val target = processSession.servers.firstOrNull { it.id == serverId } ?: return@withLock null
            (delegate as? RaftAuthServerSelectionMemory)?.saveRememberedServerSelection(
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
        selectionMutationMutex.withLock {
            val selection =
                selectedServerStore.commitSelection(serverId, serverSlug) {
                    // The process-level value is intentionally only the next-launch default.
                    delegate.updateSelectedServer(serverId, serverSlug)
                }
            emitAuthSessionEvent { generation -> AuthSessionEvent.BindingMetadataChanged(generation) }
        }
    }

    override suspend fun clearSession(reason: String, source: String) {
        selectionMutationMutex.withLock {
            selectedServerStore.clearSelection()
            delegate.clearSession(reason, source)
        }
        onProcessSessionCleared()
    }

    override suspend fun clearSessionIfMatches(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String,
    ): Boolean {
        val cleared = selectionMutationMutex.withLock {
            delegate.clearSessionIfMatches(
                expectedAccountId = expectedAccountId,
                expectedAccessToken = expectedAccessToken,
                expectedRefreshToken = expectedRefreshToken,
                reason = reason,
                source = source,
            ).also { if (it) selectedServerStore.clearSelection() }
        }
        if (cleared) onProcessSessionCleared()
        return cleared
    }

    override suspend fun clearSessionIfMatchesOutcome(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String,
    ): AuthSessionClearOutcome {
        val outcome = selectionMutationMutex.withLock {
            delegate.clearSessionIfMatchesOutcome(
                expectedAccountId = expectedAccountId,
                expectedAccessToken = expectedAccessToken,
                expectedRefreshToken = expectedRefreshToken,
                reason = reason,
                source = source,
            ).also { if (it == AuthSessionClearOutcome.Cleared) selectedServerStore.clearSelection() }
        }
        if (outcome == AuthSessionClearOutcome.Cleared) onProcessSessionCleared()
        return outcome
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

internal fun RaftAuthSessionStore.authSessionTerminalSourceOrNull(): RaftAuthSessionTerminalSource? =
    when (this) {
        is RaftAuthSessionTerminalSource -> this
        is RaftShellAuthSessionStore -> processTerminalSourceOrNull()
        else -> null
    }
