package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.raftLogInfo
import build.raft.app.raftLogWarn
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android-facing owner for the FCM registration lifecycle, the same shared
 * coordinator as iOS with the FCM provider spec.
 *
 * The host calls [install] at process start and [updateRegistration] with the
 * Firebase Installation ID from `FirebaseMessagingService.onRegistered`. Logout
 * unbind and account switches go through [RaftPushRegistrationLifecycle].
 * Android has no OS-level remote-notification switch to toggle (the android
 * actual of that hook is a no-op); the server-side unbind is what stops delivery.
 * It also keeps the server's delivery-status answer, which tells the background
 * realtime path whether FCM already delivers a server's tray notifications.
 */
object RaftAndroidPushRegistrationBridge {
    private val scope = CoroutineScope(SupervisorJob())
    private val bridgeLock = Any()

    @Volatile
    private var coordinator: RaftPushRegistrationCoordinator? = null

    private var pendingDeliveryStatusRefreshReason: String? = null

    private data class InstallResult(
        val runtime: RaftPushRegistrationCoordinator,
        val installationId: String,
        val newlyInstalled: Boolean,
        val pendingRefreshReason: String?
    )

    /**
     * [fcmUnavailableReason] non-null (no Google Play services, so no FCM transport on this device) turns
     * registration off for the process before the startup reconcile runs: nothing is upserted, a payload
     * left by an earlier process is dropped, and no delivery-status answer is kept, so the background
     * realtime path keeps posting this device's notifications itself.
     */
    fun install(candidateInstallationId: String, fcmUnavailableReason: String? = null): String {
        val result =
            synchronized(bridgeLock) {
                val existing = coordinator
                val target = existing ?: createCoordinator()
                if (fcmUnavailableReason != null) {
                    // Before the coordinator is published or handed to the lifecycle: once installed there, a
                    // session observer could already start a flush of a payload persisted by an earlier process.
                    target.reportProviderUnavailable(fcmUnavailableReason)
                }
                // Persist or resolve the installation id before publishing the coordinator so concurrent
                // observers/refreshes/registration updates never observe currentInstallationId() == null.
                val installationId = target.installationId(candidateInstallationId)
                if (existing == null) {
                    coordinator = target
                }
                val pending =
                    if (fcmUnavailableReason == null) {
                        pendingDeliveryStatusRefreshReason.also { pendingDeliveryStatusRefreshReason = null }
                    } else {
                        pendingDeliveryStatusRefreshReason = null
                        null
                    }
                InstallResult(
                    runtime = target,
                    installationId = installationId,
                    newlyInstalled = existing == null,
                    pendingRefreshReason = pending
                )
            }
        if (result.newlyInstalled) {
            RaftPushRegistrationLifecycle.install(result.runtime)
        }
        if (fcmUnavailableReason != null) {
            scope.launch { result.runtime.markProviderUnavailable(fcmUnavailableReason) }
        }
        result.runtime.onSessionSaved()
        if (result.pendingRefreshReason != null) {
            refreshDeliveryStatus(result.pendingRefreshReason)
        }
        return result.installationId
    }

    fun updateRegistration(
        firebaseInstallationId: String,
        applicationId: String,
        appVersion: String
    ) {
        val runtime = coordinator
        if (runtime == null) {
            raftLogWarn(PUSH_LOG_TAG, "registration_rejected reason=bridge_not_installed provider=fcm")
            return
        }
        scope.launch {
            runtime.updateRegistration(
                deviceToken = firebaseInstallationId,
                topic = applicationId,
                env = "production",
                appVersion = appVersion
            )
        }
    }

    /**
     * Re-asks the server whether push delivers to this installation (app foreground, realtime connect and
     * its periodic tick). The coordinator spaces the requests; this never throws into the caller.
     */
    fun refreshDeliveryStatus(reason: String) {
        val runtime =
            synchronized(bridgeLock) {
                coordinator ?: run {
                    pendingDeliveryStatusRefreshReason = reason
                    return
                }
            }
        scope.launch {
            try {
                runtime.refreshDeliveryStatus(reason = reason)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                raftLogWarn(
                    PUSH_LOG_TAG,
                    "delivery_status_result result=error trigger=$reason " +
                        "errorType=${error::class.simpleName ?: "unknown"} provider=fcm"
                )
            }
        }
    }

    /**
     * The stored delivery-status answer and this installation's id, for the background realtime path to
     * match against the current environment and user. Null when [install] has not run in this process.
     */
    fun storedDeliveryStatusOrNull(): RaftPushDeliveryStatus? =
        runCatching { coordinator?.takeUnless { it.isProviderUnavailable() }?.storedDeliveryStatus() }.getOrNull()

    fun installationIdOrNull(): String? =
        runCatching { coordinator?.currentInstallationId() }.getOrNull()

    fun hasSettledRegistrationSnapshot(): Boolean =
        runCatching { coordinator?.hasSettledRegistrationSnapshot() == true }.getOrDefault(false)

    /** Lock-free read of `mobile_fcm_probe_v0` for the active selected server (default OFF). */
    fun isFcmProbeFeatureFlagEnabled(): Boolean =
        runCatching {
            val provider = RaftDataStoreRegistry.currentOrNull() ?: return@runCatching false
            val serverId =
                provider.authStore.readCommittedSessionSnapshot()
                    ?.currentServerId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::ServerId)
                    ?: return@runCatching false
            provider.serverFeatureFlags.isEnabled(serverId, MOBILE_FCM_PROBE_FEATURE_FLAG_KEY)
        }.getOrDefault(false)

    /** Lock-free `(environment|serverId)` key scoping HTTP 404 probe-unsupported state. */
    fun currentProbeServerKeyOrNull(): String? =
        runCatching {
            val provider = RaftDataStoreRegistry.currentOrNull() ?: return@runCatching null
            val env = provider.apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
            val serverId =
                provider.authStore.readCommittedSessionSnapshot()
                    ?.currentServerId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@runCatching null
            "$env|$serverId"
        }.getOrNull()

    suspend fun sendReachabilityProbe(probeId: String): RaftPushProbeResult {
        val runtime = coordinator ?: return RaftPushProbeResult.Inconclusive("bridge_not_installed")
        return runtime.sendReachabilityProbe(probeId)
    }

    private fun createCoordinator(): RaftPushRegistrationCoordinator {
        val store =
            RaftSharedKeyValueStoreProvider.create(
                namespace = RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE,
                storageName = "push registration"
            ).store
        return RaftPushRegistrationCoordinator(
            store = store,
            spec = RaftPushProviderSpec.Fcm,
            currentSession = { RaftDataStoreRegistry.currentOrNull()?.authStore?.readSession() },
            authRuntimeReady = { RaftDataStoreRegistry.currentOrNull() != null },
            apiProvider = {
                RaftDataStoreRegistry.currentOrNull()?.networkClient?.let(::RaftPushRegistrationNetworkApi)
            },
            scope = scope,
            currentEnvironmentId = {
                RaftDataStoreRegistry.currentOrNull()?.apiEnvironmentStore?.current()?.apiBaseUrl
            },
            deviceStateProvider = raftPushDeviceStateProvider(),
            infoLogger = { message -> raftLogInfo(PUSH_LOG_TAG, "$message provider=fcm") },
            warningLogger = { message -> raftLogWarn(PUSH_LOG_TAG, "$message provider=fcm") },
            onRegistrationConfirmed = { RaftFcmProbeHost.onPushRegistrationReady() },
        )
    }
}
