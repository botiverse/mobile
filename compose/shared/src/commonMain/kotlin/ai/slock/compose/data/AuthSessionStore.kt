package ai.slock.compose.data

import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerPermissions
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.SessionSnapshot
import ai.slock.compose.model.toGravatarAvatarReference
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
    suspend fun clearSession()
}

interface SlockAuthSessionInvalidationSource {
    val sessionInvalidations: SharedFlow<Unit>
}

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
            permissions =
                ServerPermissions(
                    canManageSettings = role == "owner" || role == "admin"
                )
        )
}

class InMemorySlockAuthSessionStore(
    initialSession: StoredAuthSession? = null
) : SlockAuthSessionStore, SlockAuthSessionInvalidationSource {
    private val mutex = Mutex()
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var session: StoredAuthSession? = initialSession

    override val sessionInvalidations: SharedFlow<Unit> = invalidations.asSharedFlow()

    override suspend fun readSession(): StoredAuthSession? =
        mutex.withLock { session }

    override suspend fun saveSession(session: StoredAuthSession) {
        mutex.withLock { this.session = session }
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        mutex.withLock {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        mutex.withLock {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }
    }

    override suspend fun clearSession() {
        mutex.withLock { session = null }
        invalidations.emit(Unit)
    }
}

class KuiklyMMKVAuthSessionStore(
    private val store: SlockKeyValueStore
) : SlockAuthSessionStore, SlockAuthSessionInvalidationSource {
    private val mutex = Mutex()
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val sessionInvalidations: SharedFlow<Unit> = invalidations.asSharedFlow()

    override suspend fun readSession(): StoredAuthSession? =
        mutex.withLock {
            store.getString(SLOCK_AUTH_SESSION_RECORD_KEY)?.decodeStoredSessionOrNull()
        }

    override suspend fun saveSession(session: StoredAuthSession) {
        mutex.withLock {
            store.putString(SLOCK_AUTH_SESSION_RECORD_KEY, authSessionStoreJson.encodeToString(session))
        }
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val current = readSession() ?: return
        saveSession(current.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        val current = readSession() ?: return
        saveSession(current.copy(selectedServerId = serverId, selectedServerSlug = serverSlug))
    }

    override suspend fun clearSession() {
        mutex.withLock {
            store.putString(SLOCK_AUTH_SESSION_RECORD_KEY, null)
        }
        invalidations.emit(Unit)
    }

    private fun String.decodeStoredSessionOrNull(): StoredAuthSession? =
        runCatching {
            authSessionStoreJson.decodeFromString(StoredAuthSession.serializer(), this)
        }.getOrNull()
}

private val authSessionStoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
