package build.raft.app.data

import build.raft.app.slockLogInfo
import build.raft.app.slockLogWarn
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
 * unbind and account switches go through [SlockPushRegistrationLifecycle].
 * Android has no OS-level remote-notification switch to toggle (the android
 * actual of that hook is a no-op); the server-side unbind is what stops delivery.
 * It also keeps the server's delivery-status answer, which tells the background
 * realtime path whether FCM already delivers a server's tray notifications.
 */
object SlockAndroidPushRegistrationBridge {
    private val scope = CoroutineScope(SupervisorJob())

    @Volatile
    private var coordinator: SlockPushRegistrationCoordinator? = null

    /**
     * [fcmUnavailableReason] non-null (no Google Play services, so no FCM transport on this device) turns
     * registration off for the process before the startup reconcile runs: nothing is upserted, a payload
     * left by an earlier process is dropped, and no delivery-status answer is kept, so the background
     * realtime path keeps posting this device's notifications itself.
     */
    fun install(candidateInstallationId: String, fcmUnavailableReason: String? = null): String {
        val existing = coordinator
        val runtime = existing ?: createCoordinator()
        if (fcmUnavailableReason != null) {
            // Before the coordinator is published or handed to the lifecycle: once installed there, a
            // session observer could already start a flush of a payload persisted by an earlier process.
            runtime.reportProviderUnavailable(fcmUnavailableReason)
        }
        if (existing == null) {
            coordinator = runtime
            SlockPushRegistrationLifecycle.install(runtime)
        }
        if (fcmUnavailableReason != null) {
            scope.launch { runtime.markProviderUnavailable(fcmUnavailableReason) }
        }
        val installationId = runtime.installationId(candidateInstallationId)
        runtime.onSessionSaved()
        return installationId
    }

    fun updateRegistration(
        firebaseInstallationId: String,
        applicationId: String,
        appVersion: String
    ) {
        val runtime = coordinator
        if (runtime == null) {
            slockLogWarn(PUSH_LOG_TAG, "registration_rejected reason=bridge_not_installed provider=fcm")
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
        val runtime = coordinator ?: return
        scope.launch {
            try {
                runtime.refreshDeliveryStatus(reason = reason)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                slockLogWarn(
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
    fun storedDeliveryStatusOrNull(): SlockPushDeliveryStatus? =
        runCatching { coordinator?.takeUnless { it.isProviderUnavailable() }?.storedDeliveryStatus() }.getOrNull()

    fun installationIdOrNull(): String? =
        runCatching { coordinator?.currentInstallationId() }.getOrNull()

    private fun createCoordinator(): SlockPushRegistrationCoordinator {
        val store =
            SlockSharedKeyValueStoreProvider.create(
                namespace = SLOCK_PUSH_REGISTRATION_MMKV_NAMESPACE,
                storageName = "push registration"
            ).store
        return SlockPushRegistrationCoordinator(
            store = store,
            spec = SlockPushProviderSpec.Fcm,
            currentSession = { SlockSharedStoreRegistry.currentOrNull()?.authStore?.readSession() },
            authRuntimeReady = { SlockSharedStoreRegistry.currentOrNull() != null },
            apiProvider = {
                SlockSharedStoreRegistry.currentOrNull()?.networkClient?.let(::SlockPushRegistrationNetworkApi)
            },
            scope = scope,
            currentEnvironmentId = {
                SlockSharedStoreRegistry.currentOrNull()?.apiEnvironmentStore?.current()?.apiBaseUrl
            },
            infoLogger = { message -> slockLogInfo(PUSH_LOG_TAG, "$message provider=fcm") },
            warningLogger = { message -> slockLogWarn(PUSH_LOG_TAG, "$message provider=fcm") }
        )
    }
}
