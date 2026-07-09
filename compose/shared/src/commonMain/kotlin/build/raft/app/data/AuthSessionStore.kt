package build.raft.app.data

import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerPermissions
import build.raft.app.model.ServerSummary
import build.raft.app.model.SessionSnapshot
import build.raft.app.model.toGravatarAvatarReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
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
    val servers: List<StoredAuthServer> = emptyList()
) {
    val currentServerId: String?
        get() = selectedServerId ?: servers.firstOrNull()?.id

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
    val gravatarHash: String? = null,
    val emailVerified: Boolean? = null,
    val preferredTimeFormat: String? = null,
    val timeFormat: String? = null
) {
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
            gravatarHash = gravatarHash,
            avatar =
                avatarUrl?.let(AvatarReference::fromWireValue)
                    ?: gravatarHash?.toGravatarAvatarReference(),
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
            permissions =
                ServerPermissions(
                    canManageSettings = role == "owner" || role == "admin"
                )
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
    private val diagnosticsLogger: (String) -> Unit = {}
) : SlockAuthSessionStore, SlockAuthSessionInvalidationSource, SlockAuthSessionChangeSource, SlockAuthServerSelectionMemory {
    private val mutex = Mutex()
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 1)

    override val sessionInvalidations: SharedFlow<Unit> = invalidations.asSharedFlow()
    override val sessionChanges: SharedFlow<String> = changes.asSharedFlow()

    override suspend fun readSession(): StoredAuthSession? =
        mutex.withLock {
            val caller = authSessionDiagnosticsCaller()
            val encoded = store.getString(SLOCK_AUTH_SESSION_RECORD_KEY)
            if (encoded == null) {
                diagnosticsLogger("auth_session_read result=null raw=null caller=$caller")
                return@withLock null
            }
            val session =
                encoded.decodeStoredSessionOrNull(
                    onFailure = { type ->
                        diagnosticsLogger(
                            "auth_session_read result=null rawLen=${encoded.length} " +
                                "decode=failure errorType=$type caller=$caller"
                        )
                    }
                )
            if (session == null) {
                null
            } else {
                diagnosticsLogger(
                    "auth_session_read result=success rawLen=${encoded.length} " +
                        "selectedServer=${!session.selectedServerId.isNullOrBlank()} " +
                        "servers=${session.servers.size} caller=$caller"
                )
                session
            }
        }

    override suspend fun saveSession(session: StoredAuthSession) {
        val fingerprint = session.authSessionClearFingerprint()
        val changed =
            mutex.withLock {
                val previousFingerprint =
                    store.getString(SLOCK_AUTH_SESSION_RECORD_KEY)
                        ?.decodeStoredSessionOrNull()
                        ?.authSessionClearFingerprint()
                store.putString(SLOCK_AUTH_SESSION_RECORD_KEY, authSessionStoreJson.encodeToString(session))
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
        val current = readSession() ?: return
        saveSession(current.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        val current = readSession() ?: return
        saveRememberedServerSelection(
            current.user,
            serverId?.takeIf { it.isNotBlank() }?.let {
                StoredAuthServerSelection(serverId = it, serverSlug = serverSlug)
            }
        )
        saveSession(current.copy(selectedServerId = serverId, selectedServerSlug = serverSlug))
    }

    override suspend fun clearSession(reason: String, source: String) {
        val safeReason = reason.toSafeAuthSessionDiagnosticsToken()
        val safeSource = source.toSafeAuthSessionDiagnosticsToken()
        val previousSession =
            mutex.withLock {
                val current = store.getString(SLOCK_AUTH_SESSION_RECORD_KEY)?.decodeStoredSessionOrNull()
                store.putString(SLOCK_AUTH_SESSION_RECORD_KEY, null)
                current
            }
        diagnosticsLogger("auth_session_clear reason=$safeReason source=$safeSource hadSession=${previousSession != null}")
        invalidations.emit(Unit)
    }

    suspend fun clearSessionFromSessionClearedNotify(
        reason: String,
        previousSessionFingerprint: String
    ): Boolean {
        val shouldInvalidate =
            mutex.withLock {
                val current = store.getString(SLOCK_AUTH_SESSION_RECORD_KEY)?.decodeStoredSessionOrNull()
                when {
                    current == null -> {
                        diagnosticsLogger(
                            "auth_session_clear_notify reason=$reason current=null result=invalidate"
                        )
                        true
                    }
                    current.authSessionClearFingerprint() == previousSessionFingerprint -> {
                        store.putString(SLOCK_AUTH_SESSION_RECORD_KEY, null)
                        diagnosticsLogger(
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
}

private fun Throwable.safeAuthSessionDiagnosticsType(): String =
    this::class.simpleName?.takeIf { it.isNotBlank() } ?: "Throwable"

private fun authSessionDiagnosticsCaller(): String = "unknown"

private fun String.toSafeAuthSessionDiagnosticsToken(): String =
    trim()
        .ifBlank { "unknown" }
        .replace(Regex("""[^A-Za-z0-9_.:/-]"""), "_")
        .take(96)

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
