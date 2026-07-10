package build.raft.app.data

import build.raft.app.slockLogInfo
import build.raft.app.slockLogWarn
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SLOCK_PUSH_REGISTRATION_MMKV_NAMESPACE = "slockPushRegistration"

private const val INSTALLATION_ID_KEY = "installationId"
private const val PENDING_REGISTRATION_KEY = "pendingRegistration"
private const val LAST_REGISTERED_PAYLOAD_KEY = "lastRegisteredPayload"
private const val LAST_BOUND_USER_ID_KEY = "lastBoundUserId"
private const val LAST_BOUND_SERVER_ID_KEY = "lastBoundServerId"
private const val PUSH_LOG_TAG = "SlockPushRegistration"

private val pushRegistrationJson = Json {
    encodeDefaults = true
}

@Serializable
internal data class IosPushRegistrationPayload(
    val installationId: String,
    val provider: String = "apns",
    val platform: String = "ios",
    val deviceToken: String,
    val topic: String,
    val env: String,
    val appVersion: String
)

internal interface SlockPushRegistrationApi {
    suspend fun upsert(payloadJson: String): HomeNetworkResponse
    suspend fun unbind(installationId: String): HomeNetworkResponse
}

internal class SlockPushRegistrationNetworkApi(
    private val networkClient: SlockNetworkClient
) : SlockPushRegistrationApi {
    override suspend fun upsert(payloadJson: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Background,
            path = "push/registrations",
            method = "POST",
            bodyText = payloadJson
        )

    override suspend fun unbind(installationId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "push/registrations/$installationId",
            method = "DELETE"
        )
}

internal interface SlockPushRegistrationLifecycleSink {
    fun onSessionSaved()
    suspend fun unbindBeforeLogout()
}

internal object SlockPushRegistrationLifecycle {
    @Volatile
    private var sink: SlockPushRegistrationLifecycleSink? = null

    fun install(next: SlockPushRegistrationLifecycleSink) {
        sink = next
    }

    fun onSessionSaved() {
        sink?.onSessionSaved()
    }

    suspend fun unbindBeforeLogout() {
        sink?.unbindBeforeLogout()
    }

    internal fun clearForTests() {
        sink = null
    }
}

internal class SlockIosPushRegistrationCoordinator(
    private val store: SlockKeyValueStore,
    private val currentSession: suspend () -> StoredAuthSession?,
    private val apiProvider: () -> SlockPushRegistrationApi?,
    private val scope: CoroutineScope,
    private val infoLogger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : SlockPushRegistrationLifecycleSink {
    private val mutex = Mutex()

    @Volatile
    private var unbindInProgress = false

    @Volatile
    private var registrationSuspended = false

    fun installationId(candidateInstallationId: String): String {
        val existing = store.getString(INSTALLATION_ID_KEY)?.takeIf { it.isNotBlank() }
        if (existing != null) return existing

        val candidate = candidateInstallationId.trim().lowercase()
        require(candidate.isNotBlank()) { "A generated installation ID is required." }
        store.putString(INSTALLATION_ID_KEY, candidate)
        infoLogger("installation_id_created")
        return candidate
    }

    suspend fun updateRegistration(
        deviceToken: String,
        topic: String,
        env: String,
        appVersion: String
    ): Boolean {
        val installationId = store.getString(INSTALLATION_ID_KEY)?.takeIf { it.isNotBlank() }
        if (installationId == null) {
            warningLogger("registration_deferred reason=missing_installation_id")
            return false
        }
        val payload =
            IosPushRegistrationPayload(
                installationId = installationId,
                deviceToken = deviceToken.trim().lowercase(),
                topic = topic.trim(),
                env = env.trim().lowercase(),
                appVersion = appVersion.trim()
            )
        if (!payload.isValid()) {
            warningLogger("registration_rejected reason=invalid_atomic_identity")
            return false
        }
        store.putString(PENDING_REGISTRATION_KEY, pushRegistrationJson.encodeToString(payload))
        return flushPending(reason = "token_update")
    }

    suspend fun retryPendingRegistration(reason: String): Boolean =
        flushPending(reason)

    override fun onSessionSaved() {
        if (unbindInProgress) return
        scope.launch {
            handleSessionSaved()
        }
    }

    internal suspend fun handleSessionSaved(): Boolean {
        if (unbindInProgress) return false
        registrationSuspended = false
        return flushPending(reason = "session_saved")
    }

    override suspend fun unbindBeforeLogout() {
        registrationSuspended = true
        unbindInProgress = true
        try {
            mutex.withLock {
                val installationId = store.getString(INSTALLATION_ID_KEY)?.takeIf { it.isNotBlank() }
                    ?: return@withLock
                val session = currentSession() ?: return@withLock
                val api = apiProvider()
                if (api == null) {
                    warningLogger("unbind_deferred reason=shared_runtime_unavailable")
                    return@withLock
                }
                val response = api.unbind(installationId)
                if (response.isSuccessful || response.code == 404) {
                    store.putString(LAST_BOUND_USER_ID_KEY, null)
                    store.putString(LAST_BOUND_SERVER_ID_KEY, null)
                    store.putString(LAST_REGISTERED_PAYLOAD_KEY, null)
                    infoLogger("unbind_result result=success userPresent=${session.user.id.isNotBlank()}")
                } else {
                    warningLogger("unbind_result result=failure code=${response.code}")
                }
            }
        } finally {
            unbindInProgress = false
        }
    }

    private suspend fun flushPending(reason: String): Boolean =
        mutex.withLock {
            if (registrationSuspended) {
                infoLogger("registration_deferred reason=logout_suspended trigger=$reason")
                return@withLock false
            }
            val payloadJson = store.getString(PENDING_REGISTRATION_KEY)?.takeIf { it.isNotBlank() }
                ?: return@withLock false
            val session = currentSession()
            if (session == null || session.user.id.isBlank()) {
                infoLogger("registration_deferred reason=no_authenticated_session trigger=$reason")
                return@withLock false
            }
            val serverId = session.currentServerId?.takeIf { it.isNotBlank() }
            if (serverId == null) {
                infoLogger("registration_deferred reason=no_selected_server trigger=$reason")
                return@withLock false
            }
            val alreadyRegistered =
                store.getString(LAST_REGISTERED_PAYLOAD_KEY) == payloadJson &&
                    store.getString(LAST_BOUND_USER_ID_KEY) == session.user.id &&
                    store.getString(LAST_BOUND_SERVER_ID_KEY) == serverId
            if (alreadyRegistered) {
                infoLogger("registration_skip reason=unchanged trigger=$reason")
                return@withLock true
            }
            val api = apiProvider()
            if (api == null) {
                infoLogger("registration_deferred reason=shared_runtime_unavailable trigger=$reason")
                return@withLock false
            }
            val response = api.upsert(payloadJson)
            if (!response.isSuccessful) {
                warningLogger("registration_result result=failure code=${response.code} trigger=$reason")
                return@withLock false
            }
            store.putString(LAST_REGISTERED_PAYLOAD_KEY, payloadJson)
            store.putString(LAST_BOUND_USER_ID_KEY, session.user.id)
            store.putString(LAST_BOUND_SERVER_ID_KEY, serverId)
            infoLogger("registration_result result=success trigger=$reason")
            true
        }
}

private fun IosPushRegistrationPayload.isValid(): Boolean =
    installationId.isNotBlank() &&
        provider == "apns" &&
        platform == "ios" &&
        deviceToken.length % 2 == 0 &&
        deviceToken.isNotBlank() &&
        deviceToken.all { it in '0'..'9' || it in 'a'..'f' } &&
        topic.isNotBlank() &&
        env in setOf("sandbox", "production") &&
        appVersion.isNotBlank()

/**
 * Swift-facing owner for the APNs registration lifecycle.
 *
 * The native host supplies only signing/runtime facts. Persistence, authenticated
 * upsert dedupe and logout unbind stay in shared code so account changes cannot
 * create a second iOS-only registration model.
 */
object SlockIosPushRegistrationBridge {
    private val scope = CoroutineScope(SupervisorJob())

    @Volatile
    private var coordinator: SlockIosPushRegistrationCoordinator? = null

    fun install(candidateInstallationId: String): String {
        val runtime = coordinator ?: createCoordinator().also {
            coordinator = it
            SlockPushRegistrationLifecycle.install(it)
        }
        val installationId = runtime.installationId(candidateInstallationId)
        runtime.onSessionSaved()
        return installationId
    }

    fun updateRegistration(
        deviceToken: String,
        topic: String,
        env: String,
        appVersion: String
    ) {
        val runtime = coordinator
        if (runtime == null) {
            slockLogWarn(PUSH_LOG_TAG, "registration_rejected reason=bridge_not_installed")
            return
        }
        scope.launch {
            runtime.updateRegistration(
                deviceToken = deviceToken,
                topic = topic,
                env = env,
                appVersion = appVersion
            )
        }
    }

    fun retryPendingRegistration() {
        val runtime = coordinator ?: return
        scope.launch {
            runtime.retryPendingRegistration(reason = "host_retry")
        }
    }

    private fun createCoordinator(): SlockIosPushRegistrationCoordinator {
        val store =
            SlockSharedKeyValueStoreProvider.create(
                namespace = SLOCK_PUSH_REGISTRATION_MMKV_NAMESPACE,
                storageName = "push registration"
            ).store
        return SlockIosPushRegistrationCoordinator(
            store = store,
            currentSession = { SlockSharedStoreRegistry.currentOrNull()?.authStore?.readSession() },
            apiProvider = {
                SlockSharedStoreRegistry.currentOrNull()?.networkClient?.let(::SlockPushRegistrationNetworkApi)
            },
            scope = scope,
            infoLogger = { message -> slockLogInfo(PUSH_LOG_TAG, message) },
            warningLogger = { message -> slockLogWarn(PUSH_LOG_TAG, message) }
        )
    }
}
