package build.raft.app.data

import build.raft.app.slockLogInfo
import build.raft.app.slockLogWarn
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SLOCK_PUSH_REGISTRATION_MMKV_NAMESPACE = "slockPushRegistration"

private const val INSTALLATION_ID_KEY = "installationId"
private const val PENDING_REGISTRATION_KEY = "pendingRegistration"
private const val LAST_REGISTERED_PAYLOAD_KEY = "lastRegisteredPayload"
private const val LAST_BOUND_USER_ID_KEY = "lastBoundUserId"
private const val DELIVERY_STATUS_KEY = "deliveryStatus.v1"
internal const val PUSH_LOG_TAG = "SlockPushRegistration"

private val pushRegistrationJson = Json {
    encodeDefaults = true
}

private val pushDeliveryStatusJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
internal data class SlockPushRegistrationPayload(
    val installationId: String,
    val provider: String = "apns",
    val platform: String = "ios",
    val deviceToken: String,
    val topic: String,
    val env: String,
    val appVersion: String
)

/**
 * The provider half of an RFC 045 registration. APNs and FCM share one
 * registration lifecycle (persistence, auth-gated upsert, dedupe, logout
 * unbind); only the token shape and the allowed environments differ.
 */
internal enum class SlockPushProviderSpec(val provider: String, val platform: String) {
    /** iOS: hex APNs device token, sandbox or production. */
    Apns(provider = "apns", platform = "ios"),

    /**
     * Android: the app registers by Firebase Installation ID
     * (`firebase_messaging_installation_id_enabled`), which is case-sensitive.
     * FCM has no sandbox.
     */
    Fcm(provider = "fcm", platform = "android");

    fun normalizeToken(raw: String): String =
        when (this) {
            Apns -> raw.trim().lowercase()
            Fcm -> raw.trim()
        }

    fun accepts(payload: SlockPushRegistrationPayload): Boolean {
        if (payload.provider != provider || payload.platform != platform) return false
        if (payload.installationId.isBlank() || payload.topic.isBlank() || payload.appVersion.isBlank()) return false
        val token = payload.deviceToken
        if (token.isBlank()) return false
        return when (this) {
            Apns ->
                token.length % 2 == 0 &&
                    token.all { it in '0'..'9' || it in 'a'..'f' } &&
                    payload.env in setOf("sandbox", "production")
            Fcm ->
                token.length <= FCM_MAX_TOKEN_LENGTH &&
                    token.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' || it == ':' } &&
                    payload.env == "production"
        }
    }
}

private const val FCM_MAX_TOKEN_LENGTH = 4096

/**
 * The server's last answer to "does server push deliver to this installation", per server the user
 * belongs to, for the (environment, user, installation) that asked. The server folds in its delivery
 * switches, the FCM provider config and whether this installation holds an active FCM registration.
 */
@Serializable
data class SlockPushDeliveryStatus(
    val environmentId: String,
    val userId: String,
    val installationId: String,
    val fcmActiveByServer: Map<String, Boolean>,
)

@Serializable
private data class PushDeliveryStatusServerBody(
    val fcmActive: Boolean? = null,
)

@Serializable
private data class PushDeliveryStatusResponseBody(
    val servers: Map<String, PushDeliveryStatusServerBody>? = null,
)

internal fun normalizedPushEnvironmentId(raw: String?): String? =
    raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

internal interface SlockPushRegistrationApi {
    suspend fun upsert(payloadJson: String): HomeNetworkResponse
    suspend fun unbind(installationId: String): HomeNetworkResponse
    suspend fun deliveryStatus(installationId: String): HomeNetworkResponse
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

    override suspend fun deliveryStatus(installationId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Background,
            path = "push/delivery-status",
            method = "GET",
            query = listOf("installationId" to installationId)
        )
}

internal interface SlockPushRegistrationLifecycleSink {
    fun onSessionSaved()
    fun onSessionInvalidated(reason: AuthSessionClearReason)
    suspend fun unbindBeforeLogout()
}

internal object SlockPushRegistrationLifecycle {
    @Volatile
    private var sink: SlockPushRegistrationLifecycleSink? = null

    @Volatile
    private var sessionEvents: SharedFlow<AuthSessionEvent>? = null

    @Volatile
    private var observerScope: CoroutineScope? = null

    private var sessionEventsObserverJob: Job? = null
    private var lastObservedGeneration = 0L

    fun install(next: SlockPushRegistrationLifecycleSink) {
        sink = next
        startObserversIfReady()
    }

    fun observeSessionEvents(source: SharedFlow<AuthSessionEvent>, scope: CoroutineScope) {
        if (sessionEvents !== source) {
            sessionEventsObserverJob?.cancel()
            sessionEventsObserverJob = null
            lastObservedGeneration = 0L
        }
        sessionEvents = source
        observerScope = scope
        startObserversIfReady()
    }

    fun onSessionSaved() {
        sink?.onSessionSaved()
    }

    suspend fun unbindBeforeLogout() {
        sink?.unbindBeforeLogout()
    }

    internal fun clearForTests() {
        sessionEventsObserverJob?.cancel()
        sessionEventsObserverJob = null
        sink = null
        sessionEvents = null
        observerScope = null
        lastObservedGeneration = 0L
    }

    private fun startObserversIfReady() {
        if (sink == null) return
        val scope = observerScope ?: return
        if (sessionEventsObserverJob == null) {
            sessionEvents?.let { source ->
                sessionEventsObserverJob =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        // Events are wake signals, not state. Reconcile persisted auth once at
                        // observer startup so replay=0 cannot strand an already-authenticated
                        // installation. Generation max-advance rejects a late older emission.
                        sink?.onSessionSaved()
                        source.collect { event ->
                            if (event.generation <= lastObservedGeneration) return@collect
                            lastObservedGeneration = event.generation
                            when (event) {
                                is AuthSessionEvent.Cleared -> sink?.onSessionInvalidated(event.reason)
                                is AuthSessionEvent.Authenticated,
                                is AuthSessionEvent.TokensRotated,
                                is AuthSessionEvent.BindingMetadataChanged -> sink?.onSessionSaved()
                            }
                        }
                    }
            }
        }
    }
}

internal interface SlockIosRemoteNotificationsController {
    suspend fun setEnabled(enabled: Boolean)
}

private object PlatformSlockIosRemoteNotificationsController : SlockIosRemoteNotificationsController {
    override suspend fun setEnabled(enabled: Boolean) {
        slockSetIosRemoteNotificationsEnabled(enabled)
    }
}

internal class SlockPushRegistrationCoordinator(
    private val store: SlockKeyValueStore,
    private val spec: SlockPushProviderSpec = SlockPushProviderSpec.Apns,
    private val currentSession: suspend () -> StoredAuthSession?,
    private val authRuntimeReady: () -> Boolean = { true },
    private val apiProvider: () -> SlockPushRegistrationApi?,
    private val scope: CoroutineScope,
    private val remoteNotificationsController: SlockIosRemoteNotificationsController =
        PlatformSlockIosRemoteNotificationsController,
    /**
     * Non-null turns on the delivery-status cache (Android). The API base URL the shared network client
     * talks to; the stored answer is bound to it. iOS passes null and never asks.
     */
    private val currentEnvironmentId: (() -> String?)? = null,
    private val infoLogger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : SlockPushRegistrationLifecycleSink {
    private val mutex = Mutex()

    @Volatile
    private var unbindInProgress = false

    @Volatile
    private var registrationSuspended = false

    private var remoteNotificationsEnabled: Boolean? = null

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
            SlockPushRegistrationPayload(
                installationId = installationId,
                provider = spec.provider,
                platform = spec.platform,
                deviceToken = spec.normalizeToken(deviceToken),
                topic = topic.trim(),
                env = env.trim().lowercase(),
                appVersion = appVersion.trim()
            )
        if (!spec.accepts(payload)) {
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

    override fun onSessionInvalidated(reason: AuthSessionClearReason) {
        scope.launch {
            handleSessionInvalidated(reason = "session_${reason.name.lowercase()}")
        }
    }

    internal suspend fun handleSessionSaved(): Boolean {
        if (unbindInProgress) return false
        if (!authRuntimeReady()) {
            infoLogger("registration_deferred reason=auth_runtime_unavailable trigger=session_saved")
            return false
        }
        val session = currentSession()
        if (session == null || session.user.id.isBlank()) {
            handleSessionInvalidated(reason = "startup_without_session")
            return false
        }
        val registered = mutex.withLock {
            registrationSuspended = false
            setRemoteNotificationsEnabledLocked(true, reason = "authenticated_session")
            flushPendingLocked(session = session, reason = "session_saved")
        }
        // Login, account switch, or a cold start with no answer for this user yet. Token rotations and
        // selection changes keep a matching answer: it already covers every server of the user.
        if (currentEnvironmentId != null && storedDeliveryStatusFor(session.user.id) == null) {
            scope.launch { refreshDeliveryStatus(reason = "session_saved") }
        }
        return registered
    }

    /** The stored delivery-status answer, whoever it belongs to; callers match it against the current state. */
    fun storedDeliveryStatus(): SlockPushDeliveryStatus? =
        store.getString(DELIVERY_STATUS_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded ->
                try {
                    pushDeliveryStatusJson.decodeFromString<SlockPushDeliveryStatus>(encoded)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

    fun currentInstallationId(): String? =
        store.getString(INSTALLATION_ID_KEY)?.takeIf { it.isNotBlank() }

    private fun storedDeliveryStatusFor(userId: String): SlockPushDeliveryStatus? =
        storedDeliveryStatus()?.takeIf { status ->
            status.userId == userId &&
                status.installationId == currentInstallationId() &&
                status.environmentId == normalizedPushEnvironmentId(currentEnvironmentId?.invoke())
        }

    /**
     * Asks the server whether push delivers to this installation, per server, and stores the answer for
     * the (environment, user, installation) that asked. A failed request (including 404 from a server
     * without the route) or a malformed body keeps the previous answer; logout and auth loss clear it.
     */
    suspend fun refreshDeliveryStatus(reason: String): Boolean {
        val environmentProvider = currentEnvironmentId ?: return false
        if (registrationSuspended || unbindInProgress) {
            infoLogger("delivery_status_deferred reason=logout_suspended trigger=$reason")
            return false
        }
        val installationId = currentInstallationId()
        val session = currentSession()
        val userId = session?.user?.id?.takeIf { it.isNotBlank() }
        val environmentId = normalizedPushEnvironmentId(environmentProvider())
        if (session == null || installationId == null || userId == null || environmentId == null) {
            infoLogger("delivery_status_deferred reason=missing_identity trigger=$reason")
            return false
        }
        if (session.currentServerId.isNullOrBlank()) {
            infoLogger("delivery_status_deferred reason=no_selected_server trigger=$reason")
            return false
        }
        val api = apiProvider()
        if (api == null) {
            infoLogger("delivery_status_deferred reason=shared_runtime_unavailable trigger=$reason")
            return false
        }
        val response = api.deliveryStatus(installationId)
        val body = response.bodyText
        if (!response.isSuccessful || body == null) {
            warningLogger("delivery_status_result result=failure code=${response.code} trigger=$reason")
            return false
        }
        val servers =
            try {
                pushDeliveryStatusJson.decodeFromString<PushDeliveryStatusResponseBody>(body).servers
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        if (servers == null) {
            warningLogger("delivery_status_result result=malformed trigger=$reason")
            return false
        }
        val status =
            SlockPushDeliveryStatus(
                environmentId = environmentId,
                userId = userId,
                installationId = installationId,
                fcmActiveByServer =
                    servers.mapNotNull { (serverId, server) ->
                        val id = serverId.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        server.fcmActive?.let { id to it }
                    }.toMap()
            )
        return mutex.withLock {
            // Store only for the binding that asked: a logout, account switch or environment change while
            // the request was in flight drops the answer.
            val current = currentSession()?.user?.id
            if (
                registrationSuspended ||
                current != userId ||
                currentInstallationId() != installationId ||
                normalizedPushEnvironmentId(environmentProvider()) != environmentId
            ) {
                infoLogger("delivery_status_result result=stale_ignored trigger=$reason")
                return@withLock false
            }
            store.putString(DELIVERY_STATUS_KEY, pushRegistrationJson.encodeToString(status))
            infoLogger(
                "delivery_status_result result=success trigger=$reason " +
                    "servers=${status.fcmActiveByServer.size} " +
                    "fcmActive=${status.fcmActiveByServer.count { it.value }}"
            )
            true
        }
    }

    override suspend fun unbindBeforeLogout() {
        registrationSuspended = true
        unbindInProgress = true
        try {
            mutex.withLock {
                setRemoteNotificationsEnabledLocked(false, reason = "logout")
                try {
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
                        infoLogger("unbind_result result=success userPresent=${session.user.id.isNotBlank()}")
                    } else {
                        warningLogger("unbind_result result=failure code=${response.code}")
                    }
                } finally {
                    // A later login belongs to a new auth family even when the
                    // previous cleanup request failed or never reached server.
                    clearBoundRegistrationLocked()
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
            setRemoteNotificationsEnabledLocked(true, reason = "pending_registration")
            flushPendingLocked(session = session, payloadJson = payloadJson, reason = reason)
        }

    private suspend fun flushPendingLocked(
        session: StoredAuthSession,
        reason: String,
        payloadJson: String? = store.getString(PENDING_REGISTRATION_KEY)?.takeIf { it.isNotBlank() }
    ): Boolean {
        if (registrationSuspended) {
            infoLogger("registration_deferred reason=logout_suspended trigger=$reason")
            return false
        }
        val pendingPayload = payloadJson ?: return false
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }
        if (serverId == null) {
            infoLogger("registration_deferred reason=no_selected_server trigger=$reason")
            return false
        }
        val alreadyRegistered =
            store.getString(LAST_REGISTERED_PAYLOAD_KEY) == pendingPayload &&
                store.getString(LAST_BOUND_USER_ID_KEY) == session.user.id
        return if (alreadyRegistered) {
            infoLogger("registration_skip reason=unchanged trigger=$reason")
            true
        } else {
            val api = apiProvider()
            if (api == null) {
                infoLogger("registration_deferred reason=shared_runtime_unavailable trigger=$reason")
                return false
            }
            val response = api.upsert(pendingPayload)
            if (!response.isSuccessful) {
                warningLogger("registration_result result=failure code=${response.code} trigger=$reason")
                return false
            }
            store.putString(LAST_REGISTERED_PAYLOAD_KEY, pendingPayload)
            store.putString(LAST_BOUND_USER_ID_KEY, session.user.id)
            infoLogger("registration_result result=success trigger=$reason")
            if (currentEnvironmentId != null) {
                scope.launch { refreshDeliveryStatus(reason = "registration_upsert") }
            }
            true
        }
    }

    internal suspend fun handleSessionInvalidated(reason: String) {
        mutex.withLock {
            registrationSuspended = true
            setRemoteNotificationsEnabledLocked(false, reason = reason)
            clearBoundRegistrationLocked()
        }
    }

    private suspend fun setRemoteNotificationsEnabledLocked(enabled: Boolean, reason: String) {
        if (remoteNotificationsEnabled == enabled) return
        remoteNotificationsController.setEnabled(enabled)
        remoteNotificationsEnabled = enabled
        infoLogger("remote_notifications_state enabled=$enabled reason=$reason")
    }

    private fun clearBoundRegistrationLocked() {
        store.putString(LAST_BOUND_USER_ID_KEY, null)
        store.putString(LAST_REGISTERED_PAYLOAD_KEY, null)
        store.putString(DELIVERY_STATUS_KEY, null)
    }
}

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
    private var coordinator: SlockPushRegistrationCoordinator? = null

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

    private fun createCoordinator(): SlockPushRegistrationCoordinator {
        val store =
            SlockSharedKeyValueStoreProvider.create(
                namespace = SLOCK_PUSH_REGISTRATION_MMKV_NAMESPACE,
                storageName = "push registration"
            ).store
        return SlockPushRegistrationCoordinator(
            store = store,
            currentSession = { SlockSharedStoreRegistry.currentOrNull()?.authStore?.readSession() },
            authRuntimeReady = { SlockSharedStoreRegistry.currentOrNull() != null },
            apiProvider = {
                SlockSharedStoreRegistry.currentOrNull()?.networkClient?.let(::SlockPushRegistrationNetworkApi)
            },
            scope = scope,
            infoLogger = { message -> slockLogInfo(PUSH_LOG_TAG, message) },
            warningLogger = { message -> slockLogWarn(PUSH_LOG_TAG, message) }
        )
    }
}
