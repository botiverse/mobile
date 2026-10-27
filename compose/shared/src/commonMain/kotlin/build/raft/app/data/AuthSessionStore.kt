package build.raft.app.data

import build.raft.app.toRaftAvatarLabel
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SessionSnapshot
import build.raft.app.model.serverPermissionsForRole
import build.raft.app.model.RaftServerThemeLook
import build.raft.app.model.raftServerThemeLook
import build.raft.app.model.raftThemeChoiceFromWire
import build.raft.designsystem.tokens.RaftThemeId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

const val RAFT_AUTH_SESSION_MMKV_NAMESPACE = "slockAuthSession"
const val RAFT_AUTH_SESSION_RECORD_KEY = "session"
const val RAFT_AUTH_SESSION_MMKV_STORAGE_KEY = "slockAuthSession:session"
const val RAFT_AUTH_INSTALLATION_ID_RECORD_KEY = "authInstallationId"

enum class AuthSessionClearOutcome {
    Cleared,
    AlreadyAbsent,
    AuthorityChanged,
}

interface RaftAuthSessionStore {
    suspend fun readSession(): StoredAuthSession?
    suspend fun saveSession(session: StoredAuthSession)
    suspend fun updateTokens(accessToken: String, refreshToken: String)
    suspend fun updateSelectedServer(serverId: String?, serverSlug: String?)
    suspend fun clearSession(reason: String = "local", source: String = "unknown")

    /** Stable, non-secret per-install identity used only to bind refresh replay receipts. */
    suspend fun readOrCreateAuthInstallationId(candidate: String): String = candidate

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

    /** Atomically distinguishes a consumed authority from a concurrent successor generation. */
    suspend fun clearSessionIfMatchesOutcome(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String,
    ): AuthSessionClearOutcome {
        val cleared =
            clearSessionIfMatches(
                expectedAccountId,
                expectedAccessToken,
                expectedRefreshToken,
                reason,
                source,
            )
        if (cleared) return AuthSessionClearOutcome.Cleared
        return if (readSession() == null) {
            AuthSessionClearOutcome.AlreadyAbsent
        } else {
            AuthSessionClearOutcome.AuthorityChanged
        }
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

    /** Login admission rechecked after acquiring the concrete store's write lock. */
    suspend fun replaceAuthenticatedSessionIfCurrent(session: StoredAuthSession, isCurrent: () -> Boolean): Boolean {
        if (!isCurrent()) return false
        replaceAuthenticatedSession(session)
        return true
    }

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
     * Durably admits one refresh attempt before the request can consume the predecessor token.
     * Implementations must compare and write inside the same session mutation lock. An already
     * persisted attempt for this exact authority is returned so a cold process reuses its id.
     */
    suspend fun prepareRefreshAttemptIfMatch(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        candidate: StoredAuthRefreshAttempt,
    ): StoredAuthRefreshAttempt? {
        val current = readSession() ?: return null
        if (
            current.user.id != expectedAccountId ||
            current.accessToken != expectedAccessToken ||
            current.refreshToken != expectedRefreshToken
        ) {
            return null
        }
        current.refreshAttempt
            ?.takeIf { it.matchesAuthority(current, candidate.installationId) }
            ?.let { return it }
        require(candidate.matchesAuthority(current, candidate.installationId)) {
            "refresh attempt authority mismatch"
        }
        saveSession(current.copy(refreshAttempt = candidate))
        return candidate
    }

    /**
     * Commits the successor tokens and retires their attempt in one durable session-record write.
     * Returning null means the authority or attempt changed; callers must reconcile latest state.
     */
    suspend fun rotateTokensAndClearRefreshAttemptIfMatch(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        expectedAttemptId: String,
        newAccessToken: String,
        newRefreshToken: String,
    ): StoredAuthSession? {
        val current = readSession() ?: return null
        if (
            current.user.id != expectedAccountId ||
            current.accessToken != expectedAccessToken ||
            current.refreshToken != expectedRefreshToken ||
            current.refreshAttempt?.attemptId != expectedAttemptId
        ) {
            return null
        }
        val updated =
            current.copy(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                refreshAttempt = null,
            )
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

    /**
     * Atomically selects [serverId] only while the latest session still matches the
     * complete captured account/token generation and, when supplied, the captured selected
     * Server. The generation/source comparison and target
     * resolution from the latest server list both run inside the real store's metadata
     * write lock, so a removed server, account switch, or same-account login/token
     * successor or intervening selection fails closed instead of publishing a stale selection.
     *
     * Shell-scoped stores override this primitive so the same explicit action updates
     * both the shell selection authority and the process next-launch default.
     */
    suspend fun selectServerIfSessionMatches(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        expectedSelectedServerId: String? = null,
        serverId: String,
    ): StoredAuthSession? {
        var selected: StoredAuthSession? = null
        updateMetadata(expectedAccountId = expectedAccountId) { latest ->
            if (
                latest.accessToken != expectedAccessToken ||
                latest.refreshToken != expectedRefreshToken ||
                (expectedSelectedServerId != null && latest.selectedServerId != expectedSelectedServerId)
            ) {
                return@updateMetadata latest
            }
            val target = latest.servers.firstOrNull { it.id == serverId } ?: return@updateMetadata latest
            latest.copy(
                selectedServerId = target.id,
                selectedServerSlug = target.slug,
            ).also { selected = it }
        }
        return selected
    }

    /**
     * Applies a QA-bootstrap session mutation only while the complete immutable binding captured
     * by the caller is still current. The binding comparison runs inside [updateMetadata]'s store
     * write lock, so a same-account successor attempt cannot receive an older attempt's receipt,
     * runtime envelope, or launch authorization. Token rotation remains allowed because [transform]
     * receives the latest session and metadata writers are forbidden from changing its tokens.
     */
    suspend fun updateQaBootstrapIfBindingMatches(
        expected: StoredQaBootstrapMetadata,
        transform: (StoredAuthSession) -> StoredAuthSession,
    ): StoredAuthSession? {
        var applied: StoredAuthSession? = null
        updateMetadata(expectedAccountId = expected.principalId) { latest ->
            val current = latest.qaBootstrap
            if (current == null || !current.hasSameImmutableBinding(expected)) {
                latest
            } else {
                transform(latest).also { updated ->
                    require(updated.qaBootstrap?.hasSameImmutableBinding(expected) == true) {
                        "QA bootstrap update must preserve the immutable binding"
                    }
                    applied = updated
                }
            }
        }
        return applied
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

interface RaftAuthServerSelectionMemory {
    suspend fun readRememberedServerSelection(user: StoredAuthUser): StoredAuthServerSelection?
    suspend fun saveRememberedServerSelection(user: StoredAuthUser, selection: StoredAuthServerSelection?)
}

interface RaftAuthSessionInvalidationSource {
    val sessionInvalidations: SharedFlow<Unit>
}

interface RaftAuthSessionChangeSource {
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
interface RaftAuthSessionEventSource {
    val authSessionEvents: SharedFlow<AuthSessionEvent>
}

data class AuthSessionTerminalSnapshot(
    val generation: Long,
    val session: StoredAuthSession?,
)

/**
 * Process-auth revision authority used by terminal route/cleanup consumers.
 * The conditional commit executes while the canonical session mutation lock is held, so a
 * successor login cannot land between the final absent-session check and the terminal action.
 */
internal interface RaftAuthSessionTerminalSource {
    val authSessionTerminalEvents: SharedFlow<AuthSessionEvent>

    suspend fun readAuthSessionTerminalSnapshot(): AuthSessionTerminalSnapshot

    suspend fun runIfAuthSessionTerminalRevision(
        expectedGeneration: Long,
        action: () -> Unit,
    ): Boolean

    /**
     * Commits terminal auth loss when the revision is still absent or still owns the exact
     * captured authority. Real stores override this under the canonical mutation lock and clear
     * the captured authority before [action], so a prior best-effort clear failure cannot leave an
     * expired session Ready while a concurrent successor remains protected. Returns the terminal
     * generation committed while still under that lock; callers must not infer it from a later
     * snapshot that may already belong to a successor.
     */
    suspend fun runIfAuthSessionTerminalOrAuthorityRevision(
        expectedGeneration: Long,
        expectedSession: StoredAuthSession?,
        action: () -> Unit,
    ): Long? =
        expectedGeneration.takeIf {
            runIfAuthSessionTerminalRevision(expectedGeneration, action)
        }
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
    val qaBootstrap: StoredQaBootstrapMetadata? = null,
    val refreshAttempt: StoredAuthRefreshAttempt? = null,
    @Transient val qaBootstrapLaunchAuthorized: Boolean = false,
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
data class StoredAuthRefreshAttempt(
    val attemptId: String,
    val installationId: String,
    val accountId: String,
    val predecessorAccessToken: String,
    val predecessorRefreshToken: String,
    val createdAtMillis: Long,
)

internal fun StoredAuthRefreshAttempt.matchesAuthority(
    session: StoredAuthSession,
    expectedInstallationId: String,
): Boolean =
    attemptId.isValidAuthRefreshAttemptId() &&
        installationId.isValidAuthInstallationId() &&
        installationId == expectedInstallationId &&
        accountId == session.user.id &&
        predecessorAccessToken == session.accessToken &&
        predecessorRefreshToken == session.refreshToken

private val AuthInstallationIdRegex = Regex("^ari_[0-9a-f]{32}$")

internal fun String.isValidAuthInstallationId(): Boolean = AuthInstallationIdRegex.matches(this)

private fun String.validatedAuthInstallationId(): String {
    require(isValidAuthInstallationId()) { "invalid auth installation id" }
    return this
}

@Serializable
data class StoredQaBootstrapMetadata(
    val ticketDigest: String,
    val runCaseId: String,
    val attempt: Int,
    val ciJobRef: String,
    val principalId: String,
    val serverId: String,
    val sessionGeneration: String,
    val targetRoute: String,
    val applicationId: String,
    val signingCertSha256: String,
    val deviceBootGeneration: String,
    val protocol: String = "server_ticket_v1",
    val state: String = "pending",
    val readyReceipt: StoredQaBootstrapReadyReceipt? = null,
    val runtimeEnvelope: StoredQaBootstrapRuntimeEnvelope? = null
)

internal fun StoredQaBootstrapMetadata.hasSameImmutableBinding(other: StoredQaBootstrapMetadata): Boolean =
    ticketDigest == other.ticketDigest &&
        runCaseId == other.runCaseId &&
        attempt == other.attempt &&
        ciJobRef == other.ciJobRef &&
        principalId == other.principalId &&
        serverId == other.serverId &&
        sessionGeneration == other.sessionGeneration &&
        targetRoute == other.targetRoute &&
        applicationId == other.applicationId &&
        signingCertSha256 == other.signingCertSha256 &&
        deviceBootGeneration == other.deviceBootGeneration &&
        protocol == other.protocol

@Serializable
data class StoredQaBootstrapReadyReceipt(
    val receiptId: String,
    val receiptDigest: String,
    val version: String,
    val readyAtServer: String
)

@Serializable
data class StoredQaBootstrapRuntimeEnvelope(
    val envelopeDigest: String,
    val readyReceiptDigest: String,
    val pid: Int,
    val processInstanceId: String,
    val processGeneration: String,
    val deviceBootGeneration: String,
    val deviceReadyElapsedRealtimeNanos: String,
    val routeReadyProofDigest: String,
    val recoveryOf: String? = null
)

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
            avatarLabel = bestDisplayName.toRaftAvatarLabel(),
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
    val createdAt: String? = null,
    /**
     * Whether this server's public page is live: the owner made it public AND the
     * `public_server_v0` gate is on for it (evaluated as the anonymous public route does).
     * Sent per server by `GET /api/servers` (botiverse/slock@9df0d1b3f
     * packages/server/src/routes/servers.ts:748-770); the owner-only `publiclyVisible`
     * itself is never sent. Absent on other server payloads (create, join), so it
     * defaults to false. There is no socket event for it; members see changes on the
     * next server-list refresh.
     */
    val publicPageLive: Boolean = false,
    /**
     * The member's own theme on this server, as its wire id (design/foundations/themes.md: one
     * choice per member per server, stored on the membership). Sent per server by
     * `GET /api/servers` (botiverse/slock@aed2ba714 services/serverService.ts:305-307,
     * routes/servers.ts:785) and kept up to date by `GET`/`PATCH .../appearance-settings` and
     * the `appearance:updated` event. `null` is the default, Raft Yellow, and is what older
     * servers and other server payloads (create) give. Kept as the raw wire id so a theme this
     * build does not know survives on disk; [raftTheme] reads it ([RaftThemeId.Raft] for unknown).
     * Persisted with the session, so a cold start knows the theme before the network answers.
     */
    val theme: String? = null,
    /** Whether the theme's decorations are on (slock `server_members.theme_decor`, default true). */
    val themeDecor: Boolean = true
) {
    /** The stored theme choice; null for the default (Raft Yellow) or an id this build does not know. */
    val raftTheme: RaftThemeId?
        get() = raftThemeChoiceFromWire(theme)

    /** What the member sees on this server: Pro themes show as Raft Yellow on a Free plan. */
    fun themeLook(): RaftServerThemeLook = raftServerThemeLook(raftTheme, themeDecor, plan)

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
            hideHumansFromMembers = hideHumansFromMembers,
            permissions = serverPermissionsForRole(role),
            publicPageLive = publicPageLive,
            theme = raftTheme,
            themeDecor = themeDecor
        )
}

class InMemoryRaftAuthSessionStore(
    initialSession: StoredAuthSession? = null
) : RaftAuthSessionStore, RaftAuthSessionInvalidationSource, RaftAuthSessionChangeSource, RaftAuthServerSelectionMemory {
    private val mutex = Mutex()
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var session: StoredAuthSession? = initialSession
    private var authInstallationId: String? = null
    private val rememberedSelections = mutableMapOf<String, StoredAuthServerSelection>()

    override val sessionInvalidations: SharedFlow<Unit> = invalidations.asSharedFlow()
    override val sessionChanges: SharedFlow<String> = changes.asSharedFlow()

    override suspend fun readSession(): StoredAuthSession? =
        mutex.withLock { session }

    override suspend fun readOrCreateAuthInstallationId(candidate: String): String =
        mutex.withLock {
            authInstallationId ?: candidate.validatedAuthInstallationId().also { authInstallationId = it }
        }

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

    override suspend fun clearSessionIfMatchesOutcome(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String,
    ): AuthSessionClearOutcome {
        val outcome =
            mutex.withLock {
                val current = session ?: return@withLock AuthSessionClearOutcome.AlreadyAbsent
                if (
                    current.user.id != expectedAccountId ||
                    current.accessToken != expectedAccessToken ||
                    current.refreshToken != expectedRefreshToken
                ) {
                    return@withLock AuthSessionClearOutcome.AuthorityChanged
                }
                session = null
                AuthSessionClearOutcome.Cleared
            }
        if (outcome == AuthSessionClearOutcome.Cleared) invalidations.emit(Unit)
        return outcome
    }

    override suspend fun replaceAuthenticatedSession(session: StoredAuthSession) {
        replaceAuthenticatedSessionIfCurrent(session) { true }
    }

    override suspend fun replaceAuthenticatedSessionIfCurrent(session: StoredAuthSession, isCurrent: () -> Boolean): Boolean {
        val changed =
            mutex.withLock {
                if (!isCurrent()) return false
                val previous = this.session?.authSessionClearFingerprint()
                this.session = session
                previous != session.authSessionClearFingerprint()
            }
        if (changed) {
            changes.emit(session.authSessionClearFingerprint())
        }
        return true
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

    override suspend fun prepareRefreshAttemptIfMatch(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        candidate: StoredAuthRefreshAttempt,
    ): StoredAuthRefreshAttempt? =
        mutex.withLock {
            val current = session ?: return@withLock null
            if (
                current.user.id != expectedAccountId ||
                current.accessToken != expectedAccessToken ||
                current.refreshToken != expectedRefreshToken
            ) {
                return@withLock null
            }
            current.refreshAttempt
                ?.takeIf { it.matchesAuthority(current, candidate.installationId) }
                ?.let { return@withLock it }
            require(candidate.matchesAuthority(current, candidate.installationId)) {
                "refresh attempt authority mismatch"
            }
            session = current.copy(refreshAttempt = candidate)
            candidate
        }

    override suspend fun rotateTokensAndClearRefreshAttemptIfMatch(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        expectedAttemptId: String,
        newAccessToken: String,
        newRefreshToken: String,
    ): StoredAuthSession? {
        var newFingerprint: String? = null
        val rotated =
            mutex.withLock {
                val current = session ?: return@withLock null
                if (
                    current.user.id != expectedAccountId ||
                    current.accessToken != expectedAccessToken ||
                    current.refreshToken != expectedRefreshToken ||
                    current.refreshAttempt?.attemptId != expectedAttemptId
                ) {
                    return@withLock null
                }
                val updated =
                    current.copy(
                        accessToken = newAccessToken,
                        refreshToken = newRefreshToken,
                        refreshAttempt = null,
                    )
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
    private val store: RaftKeyValueStore,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val criticalDiagnosticsLogger: (String) -> Unit = diagnosticsLogger
) : RaftAuthSessionStore, RaftAuthSessionInvalidationSource, RaftAuthSessionChangeSource,
    RaftAuthSessionEventSource, RaftAuthSessionTerminalSource, RaftAuthServerSelectionMemory {
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
    override val authSessionTerminalEvents: SharedFlow<AuthSessionEvent> = authSessionEvents

    // Backing read/write of the session record — NOT locked; callers MUST hold [mutex]/[storeMutex].
    // The durable JSON is loaded once per app-scope store. Every repository/network consumer then
    // reads the process-owned decoded snapshot; mutations update MMKV and this cache atomically.
    private fun readSessionRecordStateLocked(): Pair<CachedAuthSessionRecord, Boolean> {
        cachedSessionRecords[store]?.let { return it to true }
        val encoded = store.getString(RAFT_AUTH_SESSION_RECORD_KEY)
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
        publishSessionRecordLocked(record)
        return record to false
    }

    private fun readSessionRecordLocked(): StoredAuthSession? =
        readSessionRecordStateLocked().first.session

    private fun writeSessionRecordLocked(session: StoredAuthSession) {
        val encoded = authSessionStoreJson.encodeToString(session)
        store.putString(RAFT_AUTH_SESSION_RECORD_KEY, encoded)
        publishSessionRecordLocked(
            CachedAuthSessionRecord(
                session = session,
                encodedLength = encoded.length,
                decodeFailure = null
            )
        )
    }

    private fun clearSessionRecordLocked() {
        store.putString(RAFT_AUTH_SESSION_RECORD_KEY, null)
        publishSessionRecordLocked(
            CachedAuthSessionRecord(
                session = null,
                encodedLength = null,
                decodeFailure = null
            )
        )
    }

    // Copy-on-write so [readCommittedSessionSnapshot] can read the map without the lock.
    private fun publishSessionRecordLocked(record: CachedAuthSessionRecord) {
        cachedSessionRecords = cachedSessionRecords + (store to record)
    }

    /**
     * The last committed session, read WITHOUT the process-wide session lock.
     *
     * For synchronous host callers that may run on the main thread (Activity/BroadcastReceiver/
     * Service callbacks, settings bridge, notification posting). `runBlocking { readSession() }`
     * there can deadlock: kotlinx `Mutex` hands the lock to the next queued waiter and resumes it
     * on that waiter's dispatcher, so when the waiter is a Main coroutine the blocked main thread
     * never runs it and the lock is never released (#3823). This never suspends or waits: it
     * returns the session as of the last completed write (a write in flight is not waited for).
     * Anything that read-modify-writes the session must keep using the locked [readSession].
     */
    fun readCommittedSessionSnapshot(): StoredAuthSession? {
        cachedSessionRecords[store]?.let { return it.session }
        // Not loaded in this process yet: the store holds the last committed record.
        return store.getString(RAFT_AUTH_SESSION_RECORD_KEY)?.decodeStoredSessionOrNull()
    }

    override suspend fun readAuthSessionTerminalSnapshot(): AuthSessionTerminalSnapshot =
        mutex.withLock {
            AuthSessionTerminalSnapshot(
                generation = storeGeneration,
                session = readSessionRecordLocked(),
            )
        }

    override suspend fun runIfAuthSessionTerminalRevision(
        expectedGeneration: Long,
        action: () -> Unit,
    ): Boolean =
        mutex.withLock {
            if (storeGeneration != expectedGeneration || readSessionRecordLocked() != null) {
                return@withLock false
            }
            action()
            true
        }

    override suspend fun runIfAuthSessionTerminalOrAuthorityRevision(
        expectedGeneration: Long,
        expectedSession: StoredAuthSession?,
        action: () -> Unit,
    ): Long? {
        var removedGeneration: Long? = null
        val committedGeneration =
            mutex.withLock {
                val current = readSessionRecordLocked()
                when {
                    current == null -> {
                        if (storeGeneration != expectedGeneration) return@withLock null
                        action()
                        storeGeneration
                    }
                    expectedSession == null ||
                        current.user.id != expectedSession.user.id ||
                        current.accessToken != expectedSession.accessToken ||
                        current.refreshToken != expectedSession.refreshToken -> null
                    else -> {
                        clearSessionRecordLocked()
                        nextGenerationLocked().also { generation ->
                            removedGeneration = generation
                            action()
                        }
                    }
                }
            }
        removedGeneration?.let { generation ->
            storeEvents.emit(
                AuthSessionEvent.Cleared(
                    generation = generation,
                    reason = AuthSessionClearReason.AuthLoss,
                )
            )
            invalidations.emit(Unit)
        }
        return committedGeneration
    }

    override suspend fun replaceAuthenticatedSession(session: StoredAuthSession) {
        replaceAuthenticatedSessionIfCurrent(session) { true }
    }

    override suspend fun replaceAuthenticatedSessionIfCurrent(session: StoredAuthSession, isCurrent: () -> Boolean): Boolean {
        val fingerprint = session.authSessionClearFingerprint()
        var generation = 0L
        val changed =
            mutex.withLock {
                if (!isCurrent()) return false
                val previous = readSessionRecordLocked()?.authSessionClearFingerprint()
                writeSessionRecordLocked(session)
                generation = nextGenerationLocked()
                previous != fingerprint
            }
        storeEvents.emit(AuthSessionEvent.Authenticated(generation))
        qaCredentialIdentityDiagnostic(session, generation)?.let { diagnosticsLogger(it) }
        if (changed) {
            changes.emit(fingerprint)
        }
        diagnosticsLogger("auth_session_replace result=success changed=$changed servers=${session.servers.size}")
        return true
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
        qaCredentialIdentityDiagnostic(updated, generation)?.let { diagnosticsLogger(it) }
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

    override suspend fun prepareRefreshAttemptIfMatch(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        candidate: StoredAuthRefreshAttempt,
    ): StoredAuthRefreshAttempt? {
        var reusedAttemptAgeMillis: Long? = null
        val attempt =
            mutex.withLock {
                val current = readSessionRecordLocked() ?: return@withLock null
                if (
                    current.user.id != expectedAccountId ||
                    current.accessToken != expectedAccessToken ||
                    current.refreshToken != expectedRefreshToken
                ) {
                    return@withLock null
                }
                current.refreshAttempt
                    ?.takeIf { it.matchesAuthority(current, candidate.installationId) }
                    ?.let { existing ->
                        reusedAttemptAgeMillis = existing.refreshAttemptAgeMillis(candidate.createdAtMillis)
                        return@withLock existing
                    }
                require(candidate.matchesAuthority(current, candidate.installationId)) {
                    "refresh attempt authority mismatch"
                }
                writeSessionRecordLocked(current.copy(refreshAttempt = candidate))
                candidate
            }
        // A reused attempt is replayed under its original id. Its age says whether the server's
        // replay receipt can still be alive; fingerprints only, never token values.
        val reuseFields =
            reusedAttemptAgeMillis?.let { ageMillis ->
                " reused=true ${refreshReplayAgeDiagnosticsFields(ageMillis)}"
            } ?: " reused=false"
        diagnosticsLogger(
            "auth_refresh_attempt_prepare applied=${attempt != null} " +
                "attempt=${attempt?.attemptId.authTokenDiagnosticsFingerprint()}" +
                if (attempt != null) reuseFields else ""
        )
        return attempt
    }

    override suspend fun readOrCreateAuthInstallationId(candidate: String): String =
        mutex.withLock {
            store.getString(RAFT_AUTH_INSTALLATION_ID_RECORD_KEY)?.takeIf { it.isValidAuthInstallationId() }
                ?: candidate.validatedAuthInstallationId().also { created ->
                    store.putString(RAFT_AUTH_INSTALLATION_ID_RECORD_KEY, created)
                }
        }

    /**
     * Startup guard against cloned installs. An auth installation id with no session record next to it
     * did not come from this install's own sign-in (for example a backup or device-transfer restore of an
     * older build that still copied the auth store). Dropping it makes the next refresh mint a fresh id,
     * so two devices never present the same installation identity to the server.
     *
     * Atomic under the store lock, so a sign-in racing with this call either lands first (the id is
     * kept) or after (the id is minted fresh on its first refresh).
     *
     * @return true when an orphaned id was dropped.
     */
    suspend fun discardAuthInstallationIdWithoutSession(): Boolean {
        val discarded =
            mutex.withLock {
                if (readSessionRecordLocked() != null) return@withLock false
                if (store.getString(RAFT_AUTH_INSTALLATION_ID_RECORD_KEY) == null) return@withLock false
                store.putString(RAFT_AUTH_INSTALLATION_ID_RECORD_KEY, null)
                true
            }
        if (discarded) diagnosticsLogger("auth_installation_id_discarded reason=no_session")
        return discarded
    }

    override suspend fun rotateTokensAndClearRefreshAttemptIfMatch(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        expectedAttemptId: String,
        newAccessToken: String,
        newRefreshToken: String,
    ): StoredAuthSession? {
        var generation = 0L
        var newFingerprint: String? = null
        val rotated =
            mutex.withLock {
                val current = readSessionRecordLocked() ?: return@withLock null
                if (
                    current.user.id != expectedAccountId ||
                    current.accessToken != expectedAccessToken ||
                    current.refreshToken != expectedRefreshToken ||
                    current.refreshAttempt?.attemptId != expectedAttemptId
                ) {
                    return@withLock null
                }
                val updated =
                    current.copy(
                        accessToken = newAccessToken,
                        refreshToken = newRefreshToken,
                        refreshAttempt = null,
                    )
                writeSessionRecordLocked(updated)
                generation = nextGenerationLocked()
                newFingerprint =
                    updated.authSessionClearFingerprint().takeIf { it != current.authSessionClearFingerprint() }
                updated
            }
        if (rotated != null) storeEvents.emit(AuthSessionEvent.TokensRotated(generation))
        newFingerprint?.let { changes.emit(it) }
        diagnosticsLogger("auth_refresh_successor_commit applied=${rotated != null}")
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

    override suspend fun clearSessionIfMatchesOutcome(
        expectedAccountId: String,
        expectedAccessToken: String,
        expectedRefreshToken: String,
        reason: String,
        source: String,
    ): AuthSessionClearOutcome {
        val safeReason = reason.toSafeAuthSessionDiagnosticsToken()
        val safeSource = source.toSafeAuthSessionDiagnosticsToken()
        var removedGeneration: Long? = null
        val outcome =
            mutex.withLock {
                val current = readSessionRecordLocked()
                    ?: return@withLock AuthSessionClearOutcome.AlreadyAbsent
                if (
                    current.user.id != expectedAccountId ||
                    current.accessToken != expectedAccessToken ||
                    current.refreshToken != expectedRefreshToken
                ) {
                    return@withLock AuthSessionClearOutcome.AuthorityChanged
                }
                clearSessionRecordLocked()
                removedGeneration = nextGenerationLocked()
                AuthSessionClearOutcome.Cleared
            }
        removedGeneration?.let {
            storeEvents.emit(AuthSessionEvent.Cleared(it, classifyAuthSessionClearReason(reason)))
        }
        diagnosticsLogger(
            "auth_session_clear_if_matches_outcome reason=$safeReason source=$safeSource outcome=$outcome"
        )
        if (outcome == AuthSessionClearOutcome.Cleared) invalidations.emit(Unit)
        return outcome
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
        // Replaced (never mutated) under storeMutex and published @Volatile, so
        // readCommittedSessionSnapshot can read it lock-free.
        @Volatile
        private var cachedSessionRecords: Map<RaftKeyValueStore, CachedAuthSessionRecord> = emptyMap()

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

internal fun qaCredentialIdentityDiagnostic(session: StoredAuthSession, authGeneration: Long): String? {
    val principalId = session.user.id
    if (
        authGeneration <= 0L ||
        !principalId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,255}"))
    ) {
        return null
    }
    return "QA_CREDENTIAL_IDENTITY version=1 principalId=$principalId authGeneration=$authGeneration"
}

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
