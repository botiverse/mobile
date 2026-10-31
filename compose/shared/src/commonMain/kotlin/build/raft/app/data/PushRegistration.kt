package build.raft.app.data

import build.raft.app.raftLogInfo
import build.raft.app.raftLogWarn
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE = "slockPushRegistration"

private const val INSTALLATION_ID_KEY = "installationId"
private const val PENDING_REGISTRATION_KEY = "pendingRegistration"
private const val LAST_REGISTERED_PAYLOAD_KEY = "lastRegisteredPayload"
private const val LAST_BOUND_USER_ID_KEY = "lastBoundUserId"
private const val LAST_BOUND_ENVIRONMENT_KEY = "lastBoundEnvironmentId"
private const val LAST_REGISTERED_AT_KEY = "lastRegisteredAtMillis"
private const val DELIVERY_STATUS_KEY = "deliveryStatus.v1"
internal const val PUSH_LOG_TAG = "SlockPushRegistration"

private val pushRegistrationJson = Json {
    encodeDefaults = true
    // Unset device-state fields are omitted, never sent as null: the server keeps its stored value for
    // an omitted field (slock docs/contracts/notifications.md §3.1).
    explicitNulls = false
    // A payload persisted by an older build still carries previewMode.
    ignoreUnknownKeys = true
}

private val pushDeliveryStatusJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
internal data class RaftPushRegistrationPayload(
    val installationId: String,
    val provider: String = "apns",
    val platform: String = "ios",
    val deviceToken: String,
    val topic: String,
    val env: String,
    val appVersion: String,
    /**
     * Deprecated by the unified contract (§3.1): the preview choice is account-wide
     * (`me/notification-settings`). Never set on a new payload; only read back from a payload an
     * older build persisted, and dropped before that payload is sent again.
     */
    val previewMode: String? = null,
    /**
     * The device's "receive notifications here" switch (`notifications:enabled`). `false`: the server
     * sends this device only `sync` / `retract` pushes, so turning it off really stops alerts.
     */
    val alertsEnabled: Boolean? = null,
    /** The OS notification permission: `granted`, `denied`, `provisional` or `unknown`. */
    val osPermission: String? = null,
    /**
     * The newest preview localization key set this build ships (contract §3.1): 2 = every §1.5 key.
     * APNs then carries `loc-key`s only from that set; any other key arrives as plain English text.
     */
    val previewKeySet: Int? = null
) {
    /** The registration without its device state: what identifies the token binding itself. */
    fun identity(): RaftPushRegistrationPayload =
        copy(previewMode = null, alertsEnabled = null, osPermission = null, previewKeySet = null)
}

/**
 * The preview key set (slock docs/contracts/notifications.md §3.1) the iOS app ships: 2 = every
 * redacted-preview key (`push.preview.*`). Its Localizable.strings has no banner keys yet, and iOS
 * without a service extension shows an unknown `loc-key` verbatim, so it stays at 2.
 */
internal const val RAFT_PUSH_PREVIEW_KEY_SET = 2

/**
 * The preview key set the Android app ships: 3 = set 2 plus every banner key (`push.title.*`,
 * `push.subtitle.*`, `push.body.*`), all in its string resources (AndroidPushCopyKeys,
 * pinned to the slock catalogue by AndroidNotificationCopyVectorTest). FCM then sends the plain
 * title with the subtitle on its own.
 */
const val RAFT_ANDROID_PUSH_PREVIEW_KEY_SET = 3

/**
 * What the device reports with its registration (contract §3.1). [legacyPreviewMode] is set only
 * while the server is not known to run the unified API: an older server reads the preview choice
 * from the registration, as before; the new one copies only a changed value into the account.
 */
data class RaftPushDeviceState(
    val alertsEnabled: Boolean,
    val osPermission: String,
    val legacyPreviewMode: String? = null
)

/** The four `osPermission` wire values. */
internal val RAFT_PUSH_OS_PERMISSIONS: Set<String> = setOf("granted", "denied", "provisional", "unknown")

/**
 * The OS notification permission as the platform host last reported it (Android:
 * `areNotificationsEnabled`, iOS: `authorizationStatus`). A change re-sends the registration so the
 * server stops alerting a device whose notifications are blocked.
 */
object RaftPushDeviceStateHost {
    @Volatile
    var osPermission: String = "unknown"
        private set

    fun updateOsPermission(value: String) {
        val clean = value.trim().lowercase().takeIf { it in RAFT_PUSH_OS_PERMISSIONS } ?: "unknown"
        if (clean == osPermission) return
        osPermission = clean
        RaftPushRegistrationLifecycle.onDeviceStateChanged()
    }
}

/** The registration's device state on both platforms: the shared notifications switch and the host's permission. */
internal fun raftPushDeviceStateProvider(): () -> RaftPushDeviceState? {
    val store by lazy { KeyValueNotificationPreferenceStore(raftSharedPreferenceStore()) }
    val previewStore by lazy { KeyValuePushPreviewModePreferenceStore(raftSharedPreferenceStore()) }
    return {
        RaftPushDeviceState(
            alertsEnabled = store.readNotificationEnabledSync(),
            osPermission = RaftPushDeviceStateHost.osPermission,
            legacyPreviewMode =
                previewStore.readPushPreviewModeSync().takeIf { RaftNotificationCapability.accountSupported() != true }
        )
    }
}

/**
 * The provider half of an RFC 045 registration. APNs and FCM share one
 * registration lifecycle (persistence, auth-gated upsert, dedupe, logout
 * unbind); only the token shape and the allowed environments differ.
 */
internal enum class RaftPushProviderSpec(val provider: String, val platform: String) {
    /** iOS: hex APNs device token, sandbox or production. */
    Apns(provider = "apns", platform = "ios"),

    /**
     * Android: the app registers by Firebase Installation ID
     * (`firebase_messaging_installation_id_enabled`), which is case-sensitive.
     * FCM has no sandbox.
     */
    Fcm(provider = "fcm", platform = "android");

    /** The newest localization key set this platform's app ships (contract §3.1). */
    val previewKeySet: Int
        get() =
            when (this) {
                Apns -> RAFT_PUSH_PREVIEW_KEY_SET
                Fcm -> RAFT_ANDROID_PUSH_PREVIEW_KEY_SET
            }

    fun normalizeToken(raw: String): String =
        when (this) {
            Apns -> raw.trim().lowercase()
            Fcm -> raw.trim()
        }

    fun accepts(payload: RaftPushRegistrationPayload): Boolean {
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
 * A confirmed upsert is trusted for this long; after it the same payload is upserted again (idempotent
 * on the server). This heals a registration the server dropped without telling the app (revoked after a
 * provider rejection, unbound, rebound to an expired session family) on servers that do not report
 * `fcmRegistered` in the delivery status, and it re-confirms installs upgraded from builds that never
 * stored a confirmation time.
 */
const val PUSH_REGISTRATION_REFRESH_INTERVAL_MILLIS: Long = 24L * 60L * 60L * 1000L

/** Backoff between retries of a failed re-upsert after the device state (switch, permission) changed. */
val PUSH_DEVICE_STATE_RETRY_DELAYS_MILLIS: List<Long> = listOf(5_000L, 30_000L, 2L * 60_000L, 10L * 60_000L)

/** A delivery-status answer older than this is unknown: the background path posts locally again. */
const val PUSH_DELIVERY_STATUS_MAX_AGE_MILLIS: Long = 30L * 60L * 1000L

/** How often a live app-scope realtime connection re-asks for the delivery status. */
const val PUSH_DELIVERY_STATUS_REFRESH_INTERVAL_MILLIS: Long = 15L * 60L * 1000L

/** Minimum spacing between delivery-status requests, unless no answer exists for the current binding. */
internal const val PUSH_DELIVERY_STATUS_MIN_REQUEST_INTERVAL_MILLIS: Long = 5L * 60L * 1000L

/**
 * The server's last answer to "does server push deliver to this installation", per server the user
 * belongs to, for the (environment, user, installation) that asked, received at [fetchedAtMillis]. The
 * server folds in its delivery switches, the FCM provider config and whether this installation holds
 * an active FCM registration.
 */
@Serializable
data class RaftPushDeliveryStatus(
    val environmentId: String,
    val userId: String,
    val installationId: String,
    val fcmActiveByServer: Map<String, Boolean>,
    val fetchedAtMillis: Long,
)

@Serializable
private data class PushDeliveryStatusServerBody(
    val fcmActive: Boolean? = null,
)

@Serializable
private data class PushDeliveryStatusResponseBody(
    val servers: Map<String, PushDeliveryStatusServerBody>? = null,
    /**
     * Whether the server holds a live registration of this installation for the asking user, independent
     * of the rollout switches that gate `fcmActive`. Absent on servers that predate the field.
     */
    val fcmRegistered: Boolean? = null,
)

@Serializable
private data class PushRegistrationUpsertRegistrationBody(
    val installationId: String? = null,
    val provider: String? = null,
)

@Serializable
private data class PushRegistrationUpsertResponseBody(
    val registration: PushRegistrationUpsertRegistrationBody? = null,
)

/**
 * The environment key of a delivery-status answer: the API base URL without surrounding blanks or a
 * trailing slash. Both the writer and the background reader go through this.
 */
fun normalizedPushEnvironmentId(raw: String?): String? =
    raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

internal interface RaftPushRegistrationApi {
    suspend fun upsert(payloadJson: String): HomeNetworkResponse
    suspend fun unbind(installationId: String): HomeNetworkResponse
    suspend fun deliveryStatus(installationId: String): HomeNetworkResponse
}

internal class RaftPushRegistrationNetworkApi(
    private val networkClient: RaftNetworkClient
) : RaftPushRegistrationApi {
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

internal interface RaftPushRegistrationLifecycleSink {
    fun onSessionSaved()

    /**
     * A login, registration or full session replace: the server may have started a new session family,
     * so the registration must be re-bound even when token and user are unchanged.
     */
    fun onSessionAuthenticated() = onSessionSaved()

    fun onSessionInvalidated(reason: AuthSessionClearReason)
    suspend fun unbindBeforeLogout()

    /** The device state changed (notifications switch, OS permission): re-send the registration. */
    fun onDeviceStateChanged() {}

    /** This device's installation id, the one its push registration carries; null before install. */
    fun installationIdOrNull(): String? = null
}

internal object RaftPushRegistrationLifecycle {
    @Volatile
    private var sink: RaftPushRegistrationLifecycleSink? = null

    @Volatile
    private var sessionEvents: SharedFlow<AuthSessionEvent>? = null

    @Volatile
    private var observerScope: CoroutineScope? = null

    private var sessionEventsObserverJob: Job? = null
    private var lastObservedGeneration = 0L

    fun install(next: RaftPushRegistrationLifecycleSink) {
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

    fun onDeviceStateChanged() {
        sink?.onDeviceStateChanged()
    }

    fun installationIdOrNull(): String? = runCatching { sink?.installationIdOrNull() }.getOrNull()

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
                                is AuthSessionEvent.Authenticated -> sink?.onSessionAuthenticated()
                                is AuthSessionEvent.TokensRotated,
                                is AuthSessionEvent.BindingMetadataChanged -> sink?.onSessionSaved()
                            }
                        }
                    }
            }
        }
    }
}

internal interface RaftIosRemoteNotificationsController {
    suspend fun setEnabled(enabled: Boolean)
}

private object PlatformRaftIosRemoteNotificationsController : RaftIosRemoteNotificationsController {
    override suspend fun setEnabled(enabled: Boolean) {
        raftSetIosRemoteNotificationsEnabled(enabled)
    }
}

internal class RaftPushRegistrationCoordinator(
    private val store: RaftKeyValueStore,
    private val spec: RaftPushProviderSpec = RaftPushProviderSpec.Apns,
    private val currentSession: suspend () -> StoredAuthSession?,
    private val authRuntimeReady: () -> Boolean = { true },
    private val apiProvider: () -> RaftPushRegistrationApi?,
    private val scope: CoroutineScope,
    private val remoteNotificationsController: RaftIosRemoteNotificationsController =
        PlatformRaftIosRemoteNotificationsController,
    /**
     * Non-null turns on the delivery-status cache (Android). The API base URL the shared network client
     * talks to; the stored answer is bound to it. iOS passes null and never asks.
     */
    private val currentEnvironmentId: (() -> String?)? = null,
    private val clockMillis: () -> Long = ::defaultNowMillis,
    /**
     * The device state ([RaftPushDeviceState]) read on every upsert, so each registration carries the
     * current switch and permission. Null (or a failed read) omits both fields: the server keeps what
     * it stored.
     */
    private val deviceStateProvider: () -> RaftPushDeviceState? = { null },
    /**
     * Waits before each retry of a failed re-upsert after a device-state change. Without them the
     * server would keep alerting a switched-off device until the next trigger (login, token, 24h).
     */
    private val deviceStateRetryDelaysMillis: List<Long> = PUSH_DEVICE_STATE_RETRY_DELAYS_MILLIS,
    private val infoLogger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : RaftPushRegistrationLifecycleSink {
    private val mutex = Mutex()

    @Volatile
    private var unbindInProgress = false

    @Volatile
    private var registrationSuspended = false

    private var remoteNotificationsEnabled: Boolean? = null

    // Delivery-status request spacing, guarded by [mutex]. In memory: a new process asks once.
    private var lastDeliveryStatusRequestAtMillis: Long? = null

    // Set under [mutex]; cleared without it so a cancelled request cannot leave it stuck.
    @Volatile
    private var deliveryStatusRequestInFlight = false

    /** Non-null once the host reported that this device cannot receive this provider's pushes at all. */
    @Volatile
    private var providerUnavailableReason: String? = null

    /** The next upsert ignores the dedupe once (new session family). Guarded by [mutex]. */
    private var forceNextUpsert = false

    /**
     * Payloads already re-upserted in this process because the server said it holds no registration.
     * In memory and once per payload, so a server that keeps dropping the registration (for example a
     * provider that rejects the token) cannot drive a re-registration loop. Guarded by [mutex].
     */
    private val serverMissingRepairedPayloads = mutableSetOf<String>()

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
        providerUnavailableReason?.let { unavailable ->
            infoLogger("registration_rejected reason=provider_unavailable detail=$unavailable")
            return false
        }
        val installationId = store.getString(INSTALLATION_ID_KEY)?.takeIf { it.isNotBlank() }
        if (installationId == null) {
            warningLogger("registration_deferred reason=missing_installation_id")
            return false
        }
        val payload =
            RaftPushRegistrationPayload(
                installationId = installationId,
                provider = spec.provider,
                platform = spec.platform,
                deviceToken = spec.normalizeToken(deviceToken),
                topic = topic.trim(),
                env = env.trim().lowercase(),
                appVersion = appVersion.trim(),
                alertsEnabled = currentDeviceState()?.alertsEnabled,
                osPermission = currentDeviceState()?.osPermission,
                previewMode = currentDeviceState()?.legacyPreviewMode,
                previewKeySet = spec.previewKeySet
            )
        if (!spec.accepts(payload)) {
            warningLogger("registration_rejected reason=invalid_atomic_identity")
            return false
        }
        val payloadJson = pushRegistrationJson.encodeToString(payload)
        store.putString(PENDING_REGISTRATION_KEY, payloadJson)
        if (!sameRegistrationIdentity(payloadJson, store.getString(LAST_REGISTERED_PAYLOAD_KEY))) {
            // The answer described the previous registration; none applies until the new one is upserted.
            clearDeliveryStatus(reason = "registration_changed")
        }
        return flushPending(reason = "token_update")
    }

    suspend fun retryPendingRegistration(reason: String): Boolean =
        flushPending(reason)

    /**
     * The host found that this device cannot receive this provider's pushes (Android without Google
     * Play services, so no FCM transport). Nothing is registered or claimed: the pending payload, the
     * local binding and any delivery-status answer are dropped, so every server reads as not delivering
     * and the background realtime path keeps posting its own notifications. Sticky for the process.
     */
    suspend fun markProviderUnavailable(reason: String) {
        reportProviderUnavailable(reason)
        mutex.withLock {
            store.putString(PENDING_REGISTRATION_KEY, null)
            clearBoundRegistrationLocked()
        }
    }

    /**
     * Non-suspending half of [markProviderUnavailable]: from this call on no trigger upserts or asks for
     * the delivery status, even one already launched (a payload persisted by an earlier process included).
     */
    fun reportProviderUnavailable(reason: String) {
        val firstReport = providerUnavailableReason == null
        providerUnavailableReason = reason
        if (firstReport) infoLogger("provider_unavailable reason=$reason")
    }

    fun isProviderUnavailable(): Boolean = providerUnavailableReason != null

    override fun onSessionSaved() {
        if (unbindInProgress) return
        scope.launch {
            handleSessionSaved()
        }
    }

    override fun onSessionAuthenticated() {
        scope.launch {
            handleSessionSaved(forceUpsert = true)
        }
    }

    override fun onSessionInvalidated(reason: AuthSessionClearReason) {
        scope.launch {
            handleSessionInvalidated(reason = "session_${reason.name.lowercase()}")
        }
    }

    // The retry loop of the latest device-state change; a newer change replaces it.
    private var deviceStateChangeJob: Job? = null

    override fun onDeviceStateChanged() {
        if (unbindInProgress) return
        deviceStateChangeJob?.cancel()
        deviceStateChangeJob = scope.launch { syncDeviceStateWithRetry() }
    }

    /**
     * [handleDeviceStateChanged], retried after [deviceStateRetryDelaysMillis] while it fails with a
     * registration still waiting to be sent (network or server failure). It stops once nothing could
     * be sent anyway: no pending registration, logged out, or no usable provider on this device.
     */
    internal suspend fun syncDeviceStateWithRetry(): Boolean {
        if (handleDeviceStateChanged()) return true
        for ((attempt, waitMillis) in deviceStateRetryDelaysMillis.withIndex()) {
            if (!deviceStateSyncRetryable()) return false
            infoLogger("registration_retry trigger=device_state_changed attempt=${attempt + 1} delayMs=$waitMillis")
            delay(waitMillis)
            if (handleDeviceStateChanged()) return true
        }
        warningLogger("registration_retry_exhausted trigger=device_state_changed")
        return false
    }

    private fun deviceStateSyncRetryable(): Boolean =
        !registrationSuspended &&
            !unbindInProgress &&
            providerUnavailableReason == null &&
            !store.getString(PENDING_REGISTRATION_KEY).isNullOrBlank()

    /**
     * Re-upserts the pending registration with the current device state. The flush re-reads
     * [deviceStateProvider], so the dedupe sees a changed payload and the server is updated now
     * instead of at the next 24h refresh.
     */
    internal suspend fun handleDeviceStateChanged(): Boolean =
        flushPending(reason = "device_state_changed")

    private fun currentDeviceState(): RaftPushDeviceState? =
        try {
            deviceStateProvider()?.let { state ->
                state.copy(
                    osPermission = state.osPermission.takeIf { it in RAFT_PUSH_OS_PERMISSIONS } ?: "unknown",
                    legacyPreviewMode = state.legacyPreviewMode?.let { PushPreviewMode.fromWire(it)?.wireName ?: PUSH_PREVIEW_MODE_UNREADABLE }
                )
            }
        } catch (_: Throwable) {
            warningLogger("device_state_read_failed fallback=omit")
            null
        }

    private fun decodePayloadOrNull(payloadJson: String?): RaftPushRegistrationPayload? =
        payloadJson?.takeIf { it.isNotBlank() }?.let { encoded ->
            try {
                pushRegistrationJson.decodeFromString<RaftPushRegistrationPayload>(encoded)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    /**
     * The stored payload with the current device state and without a legacy previewMode; unchanged
     * when it already matches.
     */
    private fun withCurrentDeviceState(payloadJson: String): String {
        val state = currentDeviceState()
        val payload = decodePayloadOrNull(payloadJson) ?: return payloadJson
        val next =
            payload.copy(
                previewMode = state?.legacyPreviewMode,
                alertsEnabled = state?.alertsEnabled,
                osPermission = state?.osPermission,
                previewKeySet = spec.previewKeySet
            )
        if (next == payload) return payloadJson
        return pushRegistrationJson.encodeToString(next)
    }

    /** Same token binding (installation, provider, token, topic, env, version), whatever the device state. */
    private fun sameRegistrationIdentity(first: String?, second: String?): Boolean {
        if (first == second) return true
        val a = decodePayloadOrNull(first) ?: return false
        val b = decodePayloadOrNull(second) ?: return false
        return a.identity() == b.identity()
    }

    internal suspend fun handleSessionSaved(forceUpsert: Boolean = false): Boolean {
        if (forceUpsert) {
            // Recorded before any early return (logout in progress, auth runtime not ready, no session yet),
            // so the new session family is re-upserted by the next flush instead of after the 24h expiry.
            mutex.withLock { forceNextUpsert = true }
        }
        if (unbindInProgress) return false
        if (providerUnavailableReason != null) return false
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
        // Login and account switch have no answer for this user yet and skip the throttle; a token rotation
        // or selection change with a matching answer is throttled like any other refresh.
        if (currentEnvironmentId != null) {
            scope.launch { refreshDeliveryStatus(reason = "session_saved", force = true) }
        }
        return registered
    }

    /** The stored delivery-status answer, whoever it belongs to; callers match it against the current state. */
    fun storedDeliveryStatus(): RaftPushDeliveryStatus? =
        store.getString(DELIVERY_STATUS_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded ->
                try {
                    pushDeliveryStatusJson.decodeFromString<RaftPushDeliveryStatus>(encoded)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

    fun currentInstallationId(): String? =
        store.getString(INSTALLATION_ID_KEY)?.takeIf { it.isNotBlank() }

    override fun installationIdOrNull(): String? = currentInstallationId()

    private fun storedDeliveryStatusFor(userId: String, environmentId: String?): RaftPushDeliveryStatus? =
        storedDeliveryStatus()?.takeIf { status ->
            status.userId == userId &&
                status.installationId == currentInstallationId() &&
                status.environmentId == environmentId
        }

    /** The last upsert bound this installation to [userId] and no different payload is waiting. */
    private fun registrationSettledFor(userId: String): Boolean {
        val registered = store.getString(LAST_REGISTERED_PAYLOAD_KEY)?.takeIf { it.isNotBlank() } ?: return false
        val pending = store.getString(PENDING_REGISTRATION_KEY)?.takeIf { it.isNotBlank() }
        return store.getString(LAST_BOUND_USER_ID_KEY) == userId && (pending == null || pending == registered)
    }

    private fun clearDeliveryStatus(reason: String) {
        if (store.getString(DELIVERY_STATUS_KEY) == null) return
        store.putString(DELIVERY_STATUS_KEY, null)
        infoLogger("delivery_status_cleared reason=$reason")
    }

    /**
     * Asks the server whether push delivers to this installation, per server, and stores the answer for
     * the (environment, user, installation) that asked. Asks only once the registration for the signed-in
     * user is in place. A failed request (including 404 from a server without the route) or a malformed
     * body keeps the previous answer, which expires after [PUSH_DELIVERY_STATUS_MAX_AGE_MILLIS]; logout,
     * auth loss and a registration change clear it.
     *
     * Requests are spaced by [PUSH_DELIVERY_STATUS_MIN_REQUEST_INTERVAL_MILLIS] and never overlap. [force]
     * (upsert, login, account switch) skips the spacing only while no answer exists for the current
     * binding, so the triggers that fire together on login or cold start cost one request.
     */
    suspend fun refreshDeliveryStatus(reason: String, force: Boolean = false): Boolean {
        val environmentProvider = currentEnvironmentId ?: return false
        if (providerUnavailableReason != null) return false
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
        var admittedRegistration: String? = null
        val admitted = mutex.withLock {
            if (!registrationSettledFor(userId)) {
                infoLogger("delivery_status_deferred reason=registration_not_settled trigger=$reason")
                return@withLock false
            }
            if (deliveryStatusRequestInFlight) return@withLock false
            val now = clockMillis()
            val last = lastDeliveryStatusRequestAtMillis
            val bypass = force && storedDeliveryStatusFor(userId, environmentId) == null
            if (!bypass && last != null && now - last in 0 until PUSH_DELIVERY_STATUS_MIN_REQUEST_INTERVAL_MILLIS) {
                return@withLock false
            }
            lastDeliveryStatusRequestAtMillis = now
            deliveryStatusRequestInFlight = true
            admittedRegistration = store.getString(LAST_REGISTERED_PAYLOAD_KEY)
            true
        }
        if (!admitted) return false
        try {
            return requestDeliveryStatus(api, reason, environmentProvider, environmentId, userId, installationId, admittedRegistration)
        } finally {
            deliveryStatusRequestInFlight = false
        }
    }

    private suspend fun requestDeliveryStatus(
        api: RaftPushRegistrationApi,
        reason: String,
        environmentProvider: () -> String?,
        environmentId: String,
        userId: String,
        installationId: String,
        admittedRegistration: String?
    ): Boolean {
        val response = api.deliveryStatus(installationId)
        val body = response.bodyText
        if (!response.isSuccessful || body == null) {
            warningLogger("delivery_status_result result=failure code=${response.code} trigger=$reason")
            return false
        }
        val decoded =
            try {
                pushDeliveryStatusJson.decodeFromString<PushDeliveryStatusResponseBody>(body)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        val servers = decoded?.servers
        if (servers == null) {
            warningLogger("delivery_status_result result=malformed trigger=$reason")
            return false
        }
        val status =
            RaftPushDeliveryStatus(
                environmentId = environmentId,
                userId = userId,
                installationId = installationId,
                fcmActiveByServer =
                    servers.mapNotNull { (serverId, server) ->
                        val id = serverId.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        server.fcmActive?.let { id to it }
                    }.toMap(),
                fetchedAtMillis = clockMillis()
            )
        return mutex.withLock {
            // Store only for the binding that asked: a logout, account switch, environment change or
            // registration change while the request was in flight drops the answer.
            val current = currentSession()?.user?.id
            if (
                registrationSuspended ||
                current != userId ||
                !registrationSettledFor(userId) ||
                // A new registration (FID, topic, version) settled meanwhile: this answer predates it.
                // A device-state-only change keeps the binding, and with it the answer.
                !sameRegistrationIdentity(store.getString(LAST_REGISTERED_PAYLOAD_KEY), admittedRegistration) ||
                currentInstallationId() != installationId ||
                normalizedPushEnvironmentId(environmentProvider()) != environmentId
            ) {
                infoLogger("delivery_status_result result=stale_ignored trigger=$reason")
                return@withLock false
            }
            if (decoded?.fcmRegistered == false) {
                repairServerMissingRegistrationLocked(reason)
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

    /**
     * The server says it holds no live registration although the app has one settled. Drop the local
     * binding so the next flush upserts again, once per payload per process; afterwards the answer is
     * only logged (the background path reads no stored answer and keeps posting locally).
     */
    private fun repairServerMissingRegistrationLocked(reason: String) {
        val registered = store.getString(LAST_REGISTERED_PAYLOAD_KEY)?.takeIf { it.isNotBlank() }
        store.putString(DELIVERY_STATUS_KEY, null)
        if (registered == null) return
        if (!serverMissingRepairedPayloads.add(registered)) {
            warningLogger("registration_server_missing action=none reason=already_repaired trigger=$reason")
            return
        }
        warningLogger("registration_server_missing action=reupsert trigger=$reason")
        store.putString(PENDING_REGISTRATION_KEY, store.getString(PENDING_REGISTRATION_KEY) ?: registered)
        clearBoundRegistrationLocked()
        scope.launch { flushPending(reason = "server_missing_registration") }
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
            if (providerUnavailableReason != null) return@withLock false
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
        if (providerUnavailableReason != null) return false
        val storedPending = payloadJson ?: return false
        // Every upsert carries the current device state; persisting it keeps pending == registered
        // once it is confirmed, which the delivery-status gate relies on.
        val pendingPayload = withCurrentDeviceState(storedPending)
        if (pendingPayload != storedPending) store.putString(PENDING_REGISTRATION_KEY, pendingPayload)
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }
        if (serverId == null) {
            infoLogger("registration_deferred reason=no_selected_server trigger=$reason")
            return false
        }
        val environmentId = currentEnvironmentId?.let { provider -> normalizedPushEnvironmentId(provider()) }
        val sameBinding =
            store.getString(LAST_REGISTERED_PAYLOAD_KEY) == pendingPayload &&
                store.getString(LAST_BOUND_USER_ID_KEY) == session.user.id &&
                store.getString(LAST_BOUND_ENVIRONMENT_KEY) == environmentId
        val confirmedAt = store.getString(LAST_REGISTERED_AT_KEY)?.toLongOrNull()
        val confirmationFresh =
            confirmedAt != null && clockMillis() - confirmedAt in 0 until PUSH_REGISTRATION_REFRESH_INTERVAL_MILLIS
        return if (sameBinding && confirmationFresh && !forceNextUpsert) {
            infoLogger("registration_skip reason=unchanged trigger=$reason")
            true
        } else {
            if (sameBinding) {
                val refreshReason = if (forceNextUpsert) "new_session" else "confirmation_expired"
                infoLogger("registration_refresh reason=$refreshReason trigger=$reason")
            }
            val api = apiProvider()
            if (api == null) {
                infoLogger("registration_deferred reason=shared_runtime_unavailable trigger=$reason")
                return false
            }
            val deviceStateOnlyChange =
                !sameBinding &&
                    sameRegistrationIdentity(pendingPayload, store.getString(LAST_REGISTERED_PAYLOAD_KEY)) &&
                    store.getString(LAST_BOUND_USER_ID_KEY) == session.user.id &&
                    store.getString(LAST_BOUND_ENVIRONMENT_KEY) == environmentId
            if (!sameBinding && !deviceStateOnlyChange) {
                // New token, topic, user or environment: the stored answer described the previous
                // registration. A same-binding refresh (confirmation expiry, new session family) or a
                // device-state-only change keeps it: FCM keeps delivering meanwhile, and dropping the answer
                // would make the realtime path post the same notification locally as well.
                clearDeliveryStatus(reason = "registration_changed")
            }
            val response = api.upsert(pendingPayload)
            if (!response.isSuccessful) {
                warningLogger("registration_result result=failure code=${response.code} trigger=$reason")
                return false
            }
            if (!upsertConfirmed(response, pendingPayload)) {
                // A 2xx without the server's registration echo (proxy, captive portal, wrong host) is not
                // a registration: keep it pending so the next trigger tries again.
                warningLogger("registration_result result=unconfirmed code=${response.code} trigger=$reason")
                return false
            }
            store.putString(LAST_REGISTERED_PAYLOAD_KEY, pendingPayload)
            store.putString(LAST_BOUND_USER_ID_KEY, session.user.id)
            store.putString(LAST_BOUND_ENVIRONMENT_KEY, environmentId)
            store.putString(LAST_REGISTERED_AT_KEY, clockMillis().toString())
            forceNextUpsert = false
            infoLogger("registration_result result=success trigger=$reason")
            if (currentEnvironmentId != null) {
                scope.launch { refreshDeliveryStatus(reason = "registration_upsert", force = true) }
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
        store.putString(LAST_BOUND_ENVIRONMENT_KEY, null)
        store.putString(LAST_REGISTERED_AT_KEY, null)
        store.putString(LAST_REGISTERED_PAYLOAD_KEY, null)
        store.putString(DELIVERY_STATUS_KEY, null)
    }

    /** The server echoed the registration it stored for this installation and provider. */
    private fun upsertConfirmed(response: HomeNetworkResponse, payloadJson: String): Boolean {
        val body = response.bodyText?.takeIf { it.isNotBlank() } ?: return false
        val echoed =
            try {
                pushDeliveryStatusJson.decodeFromString<PushRegistrationUpsertResponseBody>(body).registration
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return false
        val sent =
            try {
                pushRegistrationJson.decodeFromString<RaftPushRegistrationPayload>(payloadJson)
            } catch (_: SerializationException) {
                return false
            } catch (_: IllegalArgumentException) {
                return false
            }
        return echoed.installationId == sent.installationId && echoed.provider == sent.provider
    }
}

/**
 * Swift-facing owner for the APNs registration lifecycle.
 *
 * The native host supplies only signing/runtime facts. Persistence, authenticated
 * upsert dedupe and logout unbind stay in shared code so account changes cannot
 * create a second iOS-only registration model.
 */
object RaftIosPushRegistrationBridge {
    private val scope = CoroutineScope(SupervisorJob())

    @Volatile
    private var coordinator: RaftPushRegistrationCoordinator? = null

    fun install(candidateInstallationId: String): String {
        val runtime = coordinator ?: createCoordinator().also {
            coordinator = it
            RaftPushRegistrationLifecycle.install(it)
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
            raftLogWarn(PUSH_LOG_TAG, "registration_rejected reason=bridge_not_installed")
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

    private fun createCoordinator(): RaftPushRegistrationCoordinator {
        val store =
            RaftSharedKeyValueStoreProvider.create(
                namespace = RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE,
                storageName = "push registration"
            ).store
        return RaftPushRegistrationCoordinator(
            store = store,
            currentSession = { RaftSharedStoreRegistry.currentOrNull()?.authStore?.readSession() },
            authRuntimeReady = { RaftSharedStoreRegistry.currentOrNull() != null },
            apiProvider = {
                RaftSharedStoreRegistry.currentOrNull()?.networkClient?.let(::RaftPushRegistrationNetworkApi)
            },
            scope = scope,
            deviceStateProvider = raftPushDeviceStateProvider(),
            infoLogger = { message -> raftLogInfo(PUSH_LOG_TAG, message) },
            warningLogger = { message -> raftLogWarn(PUSH_LOG_TAG, message) }
        )
    }
}
