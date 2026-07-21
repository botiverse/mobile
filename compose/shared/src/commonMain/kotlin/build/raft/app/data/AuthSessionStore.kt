package build.raft.app.data

import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SessionSnapshot
import build.raft.app.model.serverPermissionsForRole
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

const val SLOCK_AUTH_SESSION_MMKV_NAMESPACE = "slockAuthSession"
const val SLOCK_AUTH_SESSION_RECORD_KEY = "session"
const val SLOCK_AUTH_SESSION_MMKV_STORAGE_KEY = "slockAuthSession:session"

interface SlockAuthSessionStore {
    suspend fun readSession(): StoredAuthSession?
    suspend fun saveSession(session: StoredAuthSession)
    suspend fun updateTokens(accessToken: String, refreshToken: String)
    suspend fun updateSelectedServer(serverId: String?, serverSlug: String?)
    suspend fun clearSession(reason: String = "local", source: String = "unknown")

    /**
     * Atomically clears only the exact captured account/token generation. Auth-loss paths must use
     * this instead of check-then-[clearSession], so a concurrent login or token rotation survives.
     */
    suspend fun clearSessionIfMatches(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String,
    ): Boolean {
        val current = readSession() ?: return false
        if (
            current.user.id != expectedAccountId ||
            current.accessToken != expectedAccessToken ||
            current.refreshToken != expectedRefreshToken
        ) {
            return false
        }
        clearSession(reason, source)
        return true
    }

    // --- Atomic session-write primitives (#466 lost-update fix) ---
    // All session read-modify-writes MUST go through these. A stale captured snapshot must
    // never be able to clobber a concurrently-committed token rotation (the auth_restore
    // metadata write that overwrote the socket coordinator's successor in #466/#489).

    // Default bodies below are a non-atomic fallback (readSession→saveSession) so incidental test
    // fakes need not reimplement them. The REAL stores (KeyValueAuthSessionStore / InMemory…)
    // OVERRIDE these with the shared-lock atomic versions that actually close #466.

    /** Intentional full replace — login/register only; the only verb that sets tokens from scratch. */
    suspend fun replaceAuthenticatedSession(session: StoredAuthSession) = saveSession(session)

    /**
     * Intentional full replace while preserving the latest same-account process selection.
     * Shell-scoped login/register flows use this so a concurrently committed process-level
     * server switch cannot be rolled back by a stale selection captured before the replace.
     * Real stores override this inside the same lock as every other session writer.
     */
    suspend fun replaceAuthenticatedSessionPreservingSelection(session: StoredAuthSession) {
        val current = readSession()
        replaceAuthenticatedSession(session.withPreservedProcessSelection(current))
    }

    /**
     * CAS token rotation (coordinator / token-owner only). Rotates only if the store's LATEST
     * tokens still match [expectedAccessToken]/[expectedRefreshToken]; returns the newly-persisted
     * session (latest metadata + new tokens) if applied, or null if another writer already rotated
     * / no session exists (do not overwrite — read latest to reconcile).
     */
    suspend fun rotateTokensIfMatch(
        expectedAccessToken: String,
        expectedRefreshToken: String,
        newAccessToken: String,
        newRefreshToken: String,
    ): StoredAuthSession? {
        val current = readSession() ?: return null
        if (current.accessToken != expectedAccessToken || current.refreshToken != expectedRefreshToken) {
            return null
        }
        val updated = current.copy(accessToken = newAccessToken, refreshToken = newRefreshToken)
        saveSession(updated)
        return updated
    }

    /**
     * Metadata-only atomic update. [transform] runs (non-suspending) on the store's LATEST
     * session inside the write lock and may change user/servers/selection only — it MUST NOT
     * change tokens (enforced fail-closed). Tokens always come from the latest session, never
     * from a captured snapshot.
     *
     * [expectedAccountId] guards account identity (#466): if non-null and the latest session
     * belongs to a DIFFERENT account (e.g. a login switched accounts between the caller's capture
     * and this write), the update is a no-op — stale-account metadata must never land on the new
     * account. Pass the `user.id` of the session the metadata was computed against.
     */
    suspend fun updateMetadata(
        expectedAccountId: String?,
        transform: (StoredAuthSession) -> StoredAuthSession,
    ) {
        val current = readSession() ?: return
        if (expectedAccountId != null && current.user.id != expectedAccountId) return
        val updated = transform(current)
        require(updated.accessToken == current.accessToken && updated.refreshToken == current.refreshToken) {
            "updateMetadata transform must not change access/refresh tokens"
        }
        saveSession(updated)
    }
}

private fun StoredAuthSession.withPreservedProcessSelection(current: StoredAuthSession?): StoredAuthSession {
    val sameAccountCurrent = current?.takeIf { it.user.id == user.id }
    val incomingSelectedServerId = selectedServerId
    val preservedSelectedServerId =
        (if (sameAccountCurrent != null) sameAccountCurrent.selectedServerId else incomingSelectedServerId)
            ?.takeIf { selected -> servers.any { it.id == selected } }
    return copy(
        selectedServerId = preservedSelectedServerId,
        selectedServerSlug = preservedSelectedServerId?.let { selected -> servers.firstOrNull { it.id == selected }?.slug }
    )
}

interface SlockAuthServerSelectionMemory {
    suspend fun readRememberedServerSelection(user: StoredAuthUser): StoredAuthServerSelection?
    suspend fun saveRememberedServerSelection(user: StoredAuthUser, selection: StoredAuthServerSelection?)
}

interface SlockAuthSessionInvalidationSource {
    val sessionInvalidations: SharedFlow<Unit>
}

interface SlockAuthSessionChangeSource {
    val sessionChanges: SharedFlow<String>
}

/** Reason a session was cleared. Distinguishes active user logout (an authenticated unbind is still
 *  possible) from credential auth-loss/expiry (no valid token — server-side revocation cleans up). */
enum class AuthSessionClearReason { Logout, AuthLoss }

/**
 * Canonical process-wide auth-session mutation event (#466 shared bus). Each mutation carries a
 * monotonic [generation] assigned INSIDE the store lock, so consumers order events by generation
 * regardless of delivery order (max-advance, then re-read latest). Events are WAKE signals, NOT a
 * state source — a late subscriber misses pre-subscription mutations (SharedFlow replay=0) and MUST
 * reconcile the persisted session on subscribe. Carries no token.
 */
sealed interface AuthSessionEvent {
    val generation: Long

    /** A session became authenticated: login / register / full replace. */
    data class Authenticated(override val generation: Long) : AuthSessionEvent

    /** Access/refresh tokens rotated; metadata unchanged. */
    data class TokensRotated(override val generation: Long) : AuthSessionEvent

    /** user/servers/selection changed; tokens unchanged. */
    data class BindingMetadataChanged(override val generation: Long) : AuthSessionEvent

    /** Session cleared; [reason] routes downstream (active logout vs auth-loss). */
    data class Cleared(override val generation: Long, val reason: AuthSessionClearReason) : AuthSessionEvent
}

/** Process-wide auth-session event surface — shared across all store instances backing the same record. */
interface SlockAuthSessionEventSource {
    val authSessionEvents: SharedFlow<AuthSessionEvent>
}

@Serializable
data class StoredAuthServerSelection(
    val serverId: String,
    val serverSlug: String? = null
)

@Serializable
data class StoredAuthSession(
    val user: StoredAuthUser,
    val accessToken: String,
    val refreshToken: String,
    val selectedServerId: String? = null,
    val selectedServerSlug: String? = null,
    val servers: List<StoredAuthServer> = emptyList(),
    @Transient val allowImplicitServerFallback: Boolean = true
) {
    val currentServerId: String?
        get() = selectedServerId ?: if (allowImplicitServerFallback) servers.firstOrNull()?.id else null

    fun toAuthTokens(): StoredAuthTokens? {
        val token = accessToken.takeIf { it.isNotBlank() } ?: return null
        return StoredAuthTokens(
            accessToken = token,
            refreshToken = refreshToken.takeIf { it.isNotBlank() },
            selectedServerId = currentServerId
        )
    }

    fun toSessionSnapshot(): SessionSnapshot? {
        val serverId = currentServerId?.let(::ServerId) ?: return null
        return SessionSnapshot(
            currentServerId = serverId,
            currentMember = user.toMemberProfile(),
            availableServers = servers.map { it.toServerSummary() }
        )
    }
}

@Serializable
data class StoredAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val selectedServerId: String? = null
)

@Serializable
data class StoredAuthUser(
    val id: String,
    val email: String,
    val name: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val emailVerified: Boolean? = null,
    val profileSetupCompletedAt: String? = "legacy_complete",
    val profileSetupSuggestedHandle: String? = null,
    val profileSetupProvider: String? = null,
    val preferredTimeFormat: String? = null,
    val timeFormat: String? = null
) {
    val requiresProfileSetup: Boolean
        get() = profileSetupCompletedAt.isNullOrBlank()

    val bestDisplayName: String
        get() =
            displayName?.takeIf { it.isNotBlank() }
                ?: name?.takeIf { it.isNotBlank() }
                ?: email.substringBefore("@").ifBlank { "Raft user" }

    fun toMemberProfile(): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = bestDisplayName,
            handle = email,
            kind = MemberKind.Human,
            description = description,
            avatarUrl = avatarUrl,
            avatar = avatarUrl?.let(AvatarReference::fromWireValue),
            avatarLabel = bestDisplayName.take(1).uppercase(),
            presence = Presence.Active
        )
}

@Serializable
data class StoredAuthServer(
    val id: String,
    val name: String,
    val slug: String? = null,
    val ownerId: String? = null,
    val onboardingAgentId: String? = null,
    val plan: String? = null,
    val planDowngradedAt: String? = null,
    val hideHumansFromMembers: Boolean = false,
    val role: String? = null,
    val avatarUrl: String? = null,
    val createdAt: String? = null
) {
    fun toServerSummary(): ServerSummary =
        ServerSummary(
            id = ServerId(id),
            name = name,
            slug = slug,
            role = role,
            plan = plan,
            onboardingAgentId = onboardingAgentId,
            description = slug?.let { "/$it" },
            avatarUrl = avatarUrl,
            permissions = serverPermissionsForRole(role)
        )
}

class InMemorySlockAuthSessionStore(
    initialSession: StoredAuthSession? = null
) : SlockAuthSessionStore, SlockAuthSessionInvalidationSource, SlockAuthSessionChangeSource, SlockAuthServerSelectionMemory {
    private val mutex = Mutex()
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var session: StoredAuthSession? = initialSession
    private val rememberedSelections = mutableMapOf<String, StoredAuthServerSelection>()

    override val sessionInvalidations: SharedFlow<Unit> = invalidations.asSharedFlow()
    override val sessionChanges: SharedFlow<String> = changes.asSharedFlow()

    override suspend fun readSession(): StoredAuthSession? =
        mutex.withLock { session }

    override suspend fun saveSession(session: StoredAuthSession) {
        val changed =
            mutex.withLock {
                val previousFingerprint = this.session?.authSessionClearFingerprint()
                this.session = session
                previousFingerprint != session.authSessionClearFingerprint()
            }
        if (changed) {
            changes.emit(session.authSessionClearFingerprint())
        }
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val updatedFingerprint =
            mutex.withLock {
                val previousFingerprint = session?.authSessionClearFingerprint()
                val updated =
                    session?.copy(accessToken = accessToken, refreshToken = refreshToken)
                        ?.also { session = it }
                updated
                    ?.authSessionClearFingerprint()
                    ?.takeIf { it != previousFingerprint }
            }
        updatedFingerprint?.let { changes.emit(it) }
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        val updatedFingerprint =
            mutex.withLock {
                val previousFingerprint = session?.authSessionClearFingerprint()
                session?.let { current ->
                    current.user.accountServerSelectionKey()?.let { accountKey ->
                        val remembered =
                            serverId?.takeIf { it.isNotBlank() }?.let {
                                StoredAuthServerSelection(serverId = it, serverSlug = serverSlug)
                            }
                        if (remembered == null) {
                            rememberedSelections.remove(accountKey)
                        } else {
                            rememberedSelections[accountKey] = remembered
                        }
                    }
                    current.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
                        .also { session = it }
                        .authSessionClearFingerprint()
                        .takeIf { it != previousFingerprint }
                }
            }
        updatedFingerprint?.let { changes.emit(it) }
    }

    override suspend fun clearSession(reason: String, source: String) {
        mutex.withLock { session = null }
        invalidations.emit(Unit)
    }

    override suspend fun clearSessionIfMatches(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String,
    ): Boolean {
        val cleared =
            mutex.withLock {
                val current = session ?: return@withLock false
                if (
                    current.user.id != expectedAccountId ||
                    current.accessToken != expectedAccessToken ||
                    current.refreshToken != expectedRefreshToken
                ) {
                    return@withLock false
                }
                session = null
                true
            }
        if (cleared) invalidations.emit(Unit)
        return cleared
    }

    override suspend fun replaceAuthenticatedSession(session: StoredAuthSession) {
        val changed =
            mutex.withLock {
                val previous = this.session?.authSessionClearFingerprint()
                this.session = session
                previous != session.authSessionClearFingerprint()
            }
        if (changed) {
            changes.emit(session.authSessionClearFingerprint())
        }
    }

    override suspend fun replaceAuthenticatedSessionPreservingSelection(session: StoredAuthSession) {
        var persisted: StoredAuthSession? = null
        val changed =
            mutex.withLock {
                val previous = this.session
                val updated = session.withPreservedProcessSelection(previous)
                this.session = updated
                persisted = updated
                previous?.authSessionClearFingerprint() != updated.authSessionClearFingerprint()
            }
        if (changed) {
            changes.emit(requireNotNull(persisted).authSessionClearFingerprint())
        }
    }

    override suspend fun rotateTokensIfMatch(
        expectedAccessToken: String,
        expectedRefreshToken: String,
        newAccessToken: String,
        newRefreshToken: String,
    ): StoredAuthSession? {
        var newFingerprint: String? = null
        val rotated =
            mutex.withLock {
                val current = session ?: return@withLock null
                if (current.accessToken != expectedAccessToken || current.refreshToken != expectedRefreshToken) {
                    return@withLock null
                }
                val updated = current.copy(accessToken = newAccessToken, refreshToken = newRefreshToken)
                session = updated
                newFingerprint =
                    updated.authSessionClearFingerprint().takeIf { it != current.authSessionClearFingerprint() }
                updated
            }
        newFingerprint?.let { changes.emit(it) }
        return rotated
    }

    override suspend fun updateMetadata(
        expectedAccountId: String?,
        transform: (StoredAuthSession) -> StoredAuthSession,
    ) {
        var newFingerprint: String? = null
        mutex.withLock {
            val current = session ?: return@withLock
            if (expectedAccountId != null && current.user.id != expectedAccountId) return@withLock
            val updated = transform(current)
            require(
                updated.accessToken == current.accessToken && updated.refreshToken == current.refreshToken
            ) { "updateMetadata transform must not change access/refresh tokens" }
            if (updated != current) {
                session = updated
                newFingerprint =
                    updated.authSessionClearFingerprint().takeIf { it != current.authSessionClearFingerprint() }
            }
        }
        newFingerprint?.let { changes.emit(it) }
    }

    override suspend fun readRememberedServerSelection(user: StoredAuthUser): StoredAuthServerSelection? =
        mutex.withLock {
            user.accountServerSelectionKey()?.let(rememberedSelections::get)
        }

    override suspend fun saveRememberedServerSelection(
        user: StoredAuthUser,
        selection: StoredAuthServerSelection?
    ) {
        mutex.withLock {
            val accountKey = user.accountServerSelectionKey() ?: return
            if (selection == null || selection.serverId.isBlank()) {
                rememberedSelections.remove(accountKey)
            } else {
                rememberedSelections[accountKey] = selection
            }
        }
    }
}

class KeyValueAuthSessionStore(
    private val store: SlockKeyValueStore,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val criticalDiagnosticsLogger: (String) -> Unit = diagnosticsLogger
) : SlockAuthSessionStore, SlockAuthSessionInvalidationSource, SlockAuthSessionChangeSource, SlockAuthSessionEventSource, SlockAuthServerSelectionMemory {
    // #466: the serialization lock is SHARED across every KeyValueAuthSessionStore instance
    // (they all back the same MMKV namespace), so cross-instance / cross-subsystem session
    // read-modify-writes can never lose an update. Delegating the existing `mutex` to the
    // companion keeps all current call sites in the single serialization domain.
    private val mutex get() = storeMutex
    // Also companion-shared (like the mutex) so existing observers of sessionChanges/invalidations
    // get cross-instance events too — not just the new typed bus (#466).
    private val invalidations get() = storeInvalidations
    private val changes get() = storeChanges

    override val sessionInvalidations: SharedFlow<Unit> = invalidations.asSharedFlow()
    override val sessionChanges: SharedFlow<String> = changes.asSharedFlow()
    override val authSessionEvents: SharedFlow<AuthSessionEvent> = storeEvents.asSharedFlow()

    // Backing read/write of the session record — NOT locked; callers MUST hold [mutex]/[storeMutex].
    // The durable JSON is loaded once per app-scope store. Every repository/network consumer then
    // reads the process-owned decoded snapshot; mutations update MMKV and this cache atomically.
    private fun readSessionRecordStateLocked(): Pair<CachedAuthSessionRecord, Boolean> {
        cachedSessionRecords[store]?.let { return it to true }
        val encoded = store.getString(SLOCK_AUTH_SESSION_RECORD_KEY)
        var decodeFailure: String? = null
        val session =
            encoded?.decodeStoredSessionOrNull(
                onFailure = { type -> decodeFailure = type }
            )
        val record =
            CachedAuthSessionRecord(
                session = session,
                encodedLength = encoded?.length,
                decodeFailure = decodeFailure
            )
        cachedSessionRecords[store] = record
        return record to false
    }

    private fun readSessionRecordLocked(): StoredAuthSession? =
        readSessionRecordStateLocked().first.session

    private fun writeSessionRecordLocked(session: StoredAuthSession) {
        val encoded = authSessionStoreJson.encodeToString(session)
        store.putString(SLOCK_AUTH_SESSION_RECORD_KEY, encoded)
        cachedSessionRecords[store] =
            CachedAuthSessionRecord(
                session = session,
                encodedLength = encoded.length,
                decodeFailure = null
            )
    }

    private fun clearSessionRecordLocked() {
        store.putString(SLOCK_AUTH_SESSION_RECORD_KEY, null)
        cachedSessionRecords[store] =
            CachedAuthSessionRecord(
                session = null,
                encodedLength = null,
                decodeFailure = null
            )
    }

    override suspend fun replaceAuthenticatedSession(session: StoredAuthSession) {
        val fingerprint = session.authSessionClearFingerprint()
        var generation = 0L
        val changed =
            mutex.withLock {
                val previous = readSessionRecordLocked()?.authSessionClearFingerprint()
                writeSessionRecordLocked(session)
                generation = nextGenerationLocked()
                previous != fingerprint
            }
        storeEvents.emit(AuthSessionEvent.Authenticated(generation))
        if (changed) {
            changes.emit(fingerprint)
        }
        diagnosticsLogger("auth_session_replace result=success changed=$changed servers=${session.servers.size}")
    }

    override suspend fun replaceAuthenticatedSessionPreservingSelection(session: StoredAuthSession) {
        var persisted: StoredAuthSession? = null
        var generation = 0L
        val changed =
            mutex.withLock {
                val previous = readSessionRecordLocked()
                val updated = session.withPreservedProcessSelection(previous)
                writeSessionRecordLocked(updated)
                persisted = updated
                generation = nextGenerationLocked()
                previous?.authSessionClearFingerprint() != updated.authSessionClearFingerprint()
            }
        storeEvents.emit(AuthSessionEvent.Authenticated(generation))
        val updated = requireNotNull(persisted)
        if (changed) {
            changes.emit(updated.authSessionClearFingerprint())
        }
        diagnosticsLogger(
            "auth_session_replace_preserving_selection result=success changed=$changed servers=${updated.servers.size}"
        )
    }

    override suspend fun rotateTokensIfMatch(
        expectedAccessToken: String,
        expectedRefreshToken: String,
        newAccessToken: String,
        newRefreshToken: String,
    ): StoredAuthSession? {
        var newFingerprint: String? = null
        var generation = 0L
        val rotated =
            mutex.withLock {
                val current = readSessionRecordLocked() ?: return@withLock null
                if (current.accessToken != expectedAccessToken || current.refreshToken != expectedRefreshToken) {
                    // Another writer (e.g. the socket coordinator) already rotated — do NOT overwrite.
                    return@withLock null
                }
                val updated = current.copy(accessToken = newAccessToken, refreshToken = newRefreshToken)
                writeSessionRecordLocked(updated)
                generation = nextGenerationLocked()
                newFingerprint =
                    updated.authSessionClearFingerprint().takeIf { it != current.authSessionClearFingerprint() }
                updated
            }
        if (rotated != null) storeEvents.emit(AuthSessionEvent.TokensRotated(generation))
        newFingerprint?.let { changes.emit(it) }
        diagnosticsLogger("auth_token_rotate applied=${rotated != null}")
        return rotated
    }

    override suspend fun updateMetadata(
        expectedAccountId: String?,
        transform: (StoredAuthSession) -> StoredAuthSession,
    ) {
        var newFingerprint: String? = null
        var generation: Long? = null
        mutex.withLock {
            val current = readSessionRecordLocked() ?: return@withLock
            // Account-generation guard (#466): never apply stale-account metadata onto a session
            // that was switched to a different account since the caller captured it.
            if (expectedAccountId != null && current.user.id != expectedAccountId) return@withLock
            val updated = transform(current)
            // Fail-closed: metadata transform MUST NOT change tokens. Token rotation is
            // rotateTokensIfMatch's job ONLY; this is what stops the #466 lost-update overwrite.
            require(
                updated.accessToken == current.accessToken && updated.refreshToken == current.refreshToken
            ) { "updateMetadata transform must not change access/refresh tokens" }
            if (updated != current) {
                writeSessionRecordLocked(updated)
                generation = nextGenerationLocked()
                newFingerprint =
                    updated.authSessionClearFingerprint().takeIf { it != current.authSessionClearFingerprint() }
            }
        }
        generation?.let { storeEvents.emit(AuthSessionEvent.BindingMetadataChanged(it)) }
        newFingerprint?.let { changes.emit(it) }
    }

    override suspend fun readSession(): StoredAuthSession? =
        mutex.withLock {
            val (record, cacheHit) = readSessionRecordStateLocked()
            if (record.decodeFailure != null) {
                diagnosticsLogger(
                    "auth_session_read result=null rawLen=${record.encodedLength ?: 0} " +
                        "decode=failure errorType=${record.decodeFailure} caller=${authSessionDiagnosticsCaller()}"
                )
                return@withLock null
            }
            val session = record.session
            if (session == null) {
                diagnosticsLogger(
                    "auth_session_read result=null raw=null cacheHit=$cacheHit " +
                        "caller=${authSessionDiagnosticsCaller()}"
                )
                return@withLock null
            }
            if (!cacheHit) {
                diagnosticsLogger(
                    "auth_session_read result=success rawLen=${record.encodedLength ?: 0} " +
                        "selectedServer=${!session.selectedServerId.isNullOrBlank()} " +
                        "servers=${session.servers.size} cacheHit=false"
                )
            }
            session
        }

    override suspend fun saveSession(session: StoredAuthSession) {
        val fingerprint = session.authSessionClearFingerprint()
        val changed =
            mutex.withLock {
                val previousFingerprint = readSessionRecordLocked()?.authSessionClearFingerprint()
                writeSessionRecordLocked(session)
                previousFingerprint != fingerprint
            }
        if (changed) {
            changes.emit(fingerprint)
        }
        diagnosticsLogger(
            "auth_session_save result=success changed=$changed " +
                "selectedServer=${!session.selectedServerId.isNullOrBlank()} servers=${session.servers.size}"
        )
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        // #466: read-modify-write under a SINGLE lock so this can never clobber a concurrent rotation
        // (the old readSession()+saveSession() had a TOCTOU gap between the two lock acquisitions).
        var newFingerprint: String? = null
        var generation: Long? = null
        mutex.withLock {
            val current = readSessionRecordLocked() ?: return@withLock
            val updated = current.copy(accessToken = accessToken, refreshToken = refreshToken)
            if (updated != current) {
                writeSessionRecordLocked(updated)
                generation = nextGenerationLocked()
                newFingerprint =
                    updated.authSessionClearFingerprint().takeIf { it != current.authSessionClearFingerprint() }
            }
        }
        generation?.let { storeEvents.emit(AuthSessionEvent.TokensRotated(it)) }
        newFingerprint?.let { changes.emit(it) }
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        // #466: selection is metadata — apply atomically onto the latest session (keep latest tokens).
        var selectedUser: StoredAuthUser? = null
        updateMetadata(expectedAccountId = null) { latest ->
            selectedUser = latest.user
            latest.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }
        selectedUser?.let { user ->
            saveRememberedServerSelection(
                user,
                serverId?.takeIf { it.isNotBlank() }?.let {
                    StoredAuthServerSelection(serverId = it, serverSlug = serverSlug)
                }
            )
        }
    }

    override suspend fun clearSession(reason: String, source: String) {
        val safeReason = reason.toSafeAuthSessionDiagnosticsToken()
        val safeSource = source.toSafeAuthSessionDiagnosticsToken()
        var generation = 0L
        val previousSession =
            mutex.withLock {
                val current = readSessionRecordLocked()
                clearSessionRecordLocked()
                generation = nextGenerationLocked()
                current
            }
        storeEvents.emit(AuthSessionEvent.Cleared(generation, classifyAuthSessionClearReason(reason)))
        criticalDiagnosticsLogger(
            "auth_session_clear reason=$safeReason source=$safeSource hadSession=${previousSession != null}"
        )
        invalidations.emit(Unit)
    }

    override suspend fun clearSessionIfMatches(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String,
    ): Boolean {
        val safeReason = reason.toSafeAuthSessionDiagnosticsToken()
        val safeSource = source.toSafeAuthSessionDiagnosticsToken()
        var removedGeneration: Long? = null
        val cleared =
            mutex.withLock {
                val current = readSessionRecordLocked() ?: return@withLock false
                if (
                    current.user.id != expectedAccountId ||
                    current.accessToken != expectedAccessToken ||
                    current.refreshToken != expectedRefreshToken
                ) {
                    return@withLock false
                }
                clearSessionRecordLocked()
                removedGeneration = nextGenerationLocked()
                true
            }
        removedGeneration?.let {
            storeEvents.emit(AuthSessionEvent.Cleared(it, classifyAuthSessionClearReason(reason)))
        }
        val log = if (cleared) criticalDiagnosticsLogger else diagnosticsLogger
        log("auth_session_clear_if_matches reason=$safeReason source=$safeSource cleared=$cleared")
        if (cleared) invalidations.emit(Unit)
        return cleared
    }

    suspend fun clearSessionFromSessionClearedNotify(
        reason: String,
        previousSessionFingerprint: String
    ): Boolean {
        var removedGeneration: Long? = null
        val shouldInvalidate =
            mutex.withLock {
                val current = readSessionRecordLocked()
                when {
                    current == null -> {
                        diagnosticsLogger(
                            "auth_session_clear_notify reason=$reason current=null result=invalidate"
                        )
                        true
                    }
                    current.authSessionClearFingerprint() == previousSessionFingerprint -> {
                        clearSessionRecordLocked()
                        removedGeneration = nextGenerationLocked()
                        criticalDiagnosticsLogger(
                            "auth_session_clear_notify reason=$reason current=matched result=removed"
                        )
                        true
                    }
                    else -> {
                        diagnosticsLogger(
                            "auth_session_clear_notify reason=$reason current=changed result=ignored"
                        )
                        false
                    }
                }
            }
        // #466: a MATCHED remove is a real session mutation → emit the typed process-wide event so
        // #560 and other typed-bus observers see remote clears too, not only the legacy invalidation.
        // A duplicate notify (current=null, nothing removed) must NOT fabricate a new mutation.
        removedGeneration?.let { storeEvents.emit(AuthSessionEvent.Cleared(it, AuthSessionClearReason.AuthLoss)) }
        if (shouldInvalidate) {
            invalidations.emit(Unit)
        }
        return shouldInvalidate
    }

    override suspend fun readRememberedServerSelection(user: StoredAuthUser): StoredAuthServerSelection? =
        mutex.withLock {
            val key = user.rememberedServerSelectionStoreKey() ?: return@withLock null
            store.getString(key)?.decodeRememberedServerSelectionOrNull()
        }

    override suspend fun saveRememberedServerSelection(
        user: StoredAuthUser,
        selection: StoredAuthServerSelection?
    ) {
        mutex.withLock {
            val key = user.rememberedServerSelectionStoreKey() ?: return
            val value =
                selection
                    ?.takeIf { it.serverId.isNotBlank() }
                    ?.let { authSessionStoreJson.encodeToString(it) }
            store.putString(key, value)
        }
    }

    private fun String.decodeStoredSessionOrNull(onFailure: (String) -> Unit = {}): StoredAuthSession? =
        runCatching {
            authSessionStoreJson.decodeFromString(StoredAuthSession.serializer(), this)
        }.onFailure { throwable ->
            onFailure(throwable.safeAuthSessionDiagnosticsType())
        }.getOrNull()

    private fun String.decodeRememberedServerSelectionOrNull(): StoredAuthServerSelection? =
        runCatching {
            authSessionStoreJson.decodeFromString(StoredAuthServerSelection.serializer(), this)
        }.getOrNull()

    private companion object {
        private data class CachedAuthSessionRecord(
            val session: StoredAuthSession?,
            val encodedLength: Int?,
            val decodeFailure: String?
        )

        // Process-wide, shared across ALL KeyValueAuthSessionStore instances (every instance
        // backs the same MMKV session record). This is the single serialization domain for all
        // session read-modify-writes — the fix for the #466 cross-subsystem lost-update.
        private val storeMutex = Mutex()
        private val cachedSessionRecords = mutableMapOf<SlockKeyValueStore, CachedAuthSessionRecord>()

        // Process-wide shared event bus + generation counter (#466 gate: cross-instance
        // notification). generation is assigned INSIDE storeMutex; events emit AFTER the lock,
        // ordered by generation so consumers max-advance regardless of delivery order.
        private val storeEvents = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 64)
        private var storeGeneration = 0L

        // Legacy per-observer flows, also companion-shared so existing sessionChanges/invalidations
        // observers see cross-instance events (not only the new typed bus). Larger buffer than the
        // old per-instance capacity=1 since all instances now fan in here.
        private val storeChanges = MutableSharedFlow<String>(extraBufferCapacity = 16)
        private val storeInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

        /** Allocate the next monotonic generation. MUST be called while holding [storeMutex]. */
        private fun nextGenerationLocked(): Long = ++storeGeneration
    }
}

private fun Throwable.safeAuthSessionDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"

private fun authSessionDiagnosticsCaller(): String = "unknown"

private fun String.toSafeAuthSessionDiagnosticsToken(): String =
    trim()
        .ifBlank { "unknown" }
        .replace(Regex("""[^A-Za-z0-9_.:/-]"""), "_")
        .take(96)

private fun classifyAuthSessionClearReason(reason: String): AuthSessionClearReason =
    // Explicit user logout / account switch → an authenticated unbind is still possible (the #560
    // upper-layer coordinator does the DELETE). Everything else — 401 / expiry / missing-or-invalid
    // token / auth_restore — is credential auth-loss where the client cannot authenticate a DELETE;
    // server-side revocation cleans up. Conservative default = AuthLoss so a downstream never attempts
    // an unauthenticated unbind. (Exact logout call-site tagging refined with #560.)
    when {
        reason.contains("logout", ignoreCase = true) ||
            reason.contains("sign_out", ignoreCase = true) ||
            reason.contains("signout", ignoreCase = true) ||
            reason.contains("switch_account", ignoreCase = true) -> AuthSessionClearReason.Logout
        else -> AuthSessionClearReason.AuthLoss
    }

private val authSessionStoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun StoredAuthUser.rememberedServerSelectionStoreKey(): String? =
    accountServerSelectionKey()?.let { "rememberedServerSelection:$it" }

internal fun StoredAuthSession.authSessionClearFingerprint(): String =
    listOf(
        user.id,
        user.email,
        accessToken,
        refreshToken
    ).joinToString("|").stableAuthSessionFingerprint()

private fun String.stableAuthSessionFingerprint(): String {
    var hash = 2166136261L
    for (character in this) {
        hash = hash xor character.code.toLong()
        hash = (hash * 16777619L) and 0xffffffffL
    }
    return hash.toString(16)
}

private fun StoredAuthUser.accountServerSelectionKey(): String? {
    val raw =
        id.trim().takeIf { it.isNotBlank() }?.let { "id:$it" }
            ?: email.trim().lowercase().takeIf { it.isNotBlank() }?.let { "email:$it" }
            ?: return null
    return raw.map { character ->
        when {
            character.isLetterOrDigit() -> character
            character == ':' || character == '-' || character == '_' || character == '.' -> character
            else -> '_'
        }
    }.joinToString(separator = "")
}
