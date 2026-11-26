package build.raft.app.push

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import build.raft.app.data.RaftPushDeliveryAppState
import build.raft.app.data.RaftPushDeliveryCategory
import build.raft.app.data.RaftPushDeliveryDeviceContext
import build.raft.app.data.RaftPushDeliveryOpenSurface
import build.raft.app.data.RaftPushDeliverySuppressReason
import build.raft.app.data.RaftPushDeliveryTrace
import build.raft.app.data.RaftPushDeliveryTransport
import build.raft.app.data.normalizePushDeliveryId
import build.raft.app.data.normalizePushDeliveryManufacturer
import build.raft.app.data.parseRaftNotificationRoute
import org.json.JSONObject

internal const val EXTRA_RAFT_PUSH_DELIVERY_ID = "slockPushDeliveryId"
internal const val EXTRA_RAFT_PUSH_DELIVERY_TRANSPORT = "slockPushDeliveryTransport"
internal const val EXTRA_RAFT_PUSH_DELIVERY_SERVER_ID = "slockPushDeliveryServerId"

internal data class AndroidPosterPresentationOutcome(
    val presented: Boolean,
    val suppressReason: RaftPushDeliverySuppressReason?,
)

internal data class AndroidPushOpenIntentPayload(
    val deliveryId: String,
    val transport: RaftPushDeliveryTransport,
    val serverId: String,
)

/**
 * Pure mapping from [build.raft.app.shared.AndroidUnreadNotificationPoster.postUnreadNotification]
 * result map to `mobile.push.presented` outcome + closed `suppress_reason`.
 */
internal fun androidPosterResultToPresentationOutcome(result: Map<String, Any>): AndroidPosterPresentationOutcome {
    val status = result["status"] as? String
    val code = result["code"] as? String
    val duplicate = result["duplicate"] as? Boolean == true
    return when {
        code == "already_retracted" ->
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.Retracted)
        code == "permission_denied" || code == "notifications_disabled" ->
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.PermissionOff)
        code == "channel_blocked" ->
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.ChannelMuted)
        code == "post_failed" ->
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.PostFailed)
        status == "ok" && duplicate ->
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.Dedup)
        status == "ok" ->
            AndroidPosterPresentationOutcome(presented = true, suppressReason = null)
        else ->
            AndroidPosterPresentationOutcome(presented = false, suppressReason = RaftPushDeliverySuppressReason.Unknown)
    }
}

/**
 * Maps Android `NotificationManager.currentInterruptionFilter` to `dnd_active`: true for
 * `PRIORITY`, `NONE`, and `ALARMS`; false for `ALL` and `UNKNOWN`.
 */
internal fun isAndroidInterruptionFilterDndActive(filter: Int): Boolean =
    filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
        filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN

/**
 * Maps background socket skip gates to the closed `suppress_reason` enum.
 */
internal fun androidSocketBackgroundSkipSuppressReason(
    preferenceEnabled: Boolean,
    isAppInForeground: Boolean,
    remotePushActive: Boolean,
    phoneAlert: Boolean?,
): RaftPushDeliverySuppressReason =
    when {
        phoneAlert == false -> RaftPushDeliverySuppressReason.PhoneAlertFalse
        isAppInForeground -> RaftPushDeliverySuppressReason.ForegroundSuppressed
        !preferenceEnabled -> RaftPushDeliverySuppressReason.AlertsDisabled
        remotePushActive -> RaftPushDeliverySuppressReason.RemotePushActive
        else -> RaftPushDeliverySuppressReason.Unknown
    }

internal fun resolveAndroidPushServerId(serverId: String?, routeJson: String?): String =
    serverId?.trim()?.takeIf { it.isNotEmpty() }
        ?: parseRaftNotificationRoute(routeJson)?.serverId?.trim()?.takeIf { it.isNotEmpty() }
        ?: ""

internal fun extractPushOpenPayload(
    flags: Int,
    rawDeliveryId: String?,
    rawTransport: String?,
    rawServerId: String?,
): AndroidPushOpenIntentPayload? {
    if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    val deliveryId = normalizePushDeliveryId(rawDeliveryId) ?: return null
    val transport = RaftPushDeliveryTransport.fromWire(rawTransport) ?: RaftPushDeliveryTransport.Fcm
    val serverId = rawServerId?.trim().orEmpty()
    return AndroidPushOpenIntentPayload(
        deliveryId = deliveryId,
        transport = transport,
        serverId = serverId,
    )
}

/**
 * Android wiring for end-to-end push delivery observability (`#proj-mobile:#405`).
 * Never throws; any missing or non-UUID `deliveryId` is a strict no-op.
 */
internal object AndroidPushDeliveryTracker {
    fun install(context: Context) {
        val appContext = context.applicationContext
        RaftPushDeliveryTrace.installDeviceContextProvider {
            deviceContext(appContext)
        }
    }

    fun isDndActive(context: Context): Boolean =
        runCatching {
            val manager =
                context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return false
            isAndroidInterruptionFilterDndActive(manager.currentInterruptionFilter)
        }.getOrDefault(false)

    fun deviceContext(context: Context): RaftPushDeliveryDeviceContext {
        val appContext = context.applicationContext
        return RaftPushDeliveryDeviceContext(
            manufacturer = normalizePushDeliveryManufacturer(Build.MANUFACTURER, Build.BRAND),
            osVersion = Build.VERSION.SDK_INT.toString(),
            gmsAvailable = runCatching { !RaftPlayServicesAvailability.isFcmUnavailable(appContext) }.getOrDefault(false),
            dndActive = isDndActive(appContext),
        )
    }

    fun recordReceived(
        context: Context,
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        category: RaftPushDeliveryCategory,
        accountId: String? = null,
    ) {
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return
        RaftPushDeliveryTrace.recordReceived(
            deliveryId = normalizedId,
            serverId = serverId,
            transport = transport,
            appState = appState,
            category = category,
            accountId = accountId,
            deviceContext = deviceContext(context),
        )
    }

    fun recordPresented(
        context: Context,
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        presented: Boolean,
        suppressReason: RaftPushDeliverySuppressReason? = null,
        accountId: String? = null,
    ) {
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return
        RaftPushDeliveryTrace.recordPresented(
            deliveryId = normalizedId,
            serverId = serverId,
            transport = transport,
            appState = appState,
            presented = presented,
            suppressReason = suppressReason,
            accountId = accountId,
            deviceContext = deviceContext(context),
        )
    }

    fun recordPosterOutcome(
        context: Context,
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        result: Map<String, Any>,
        accountId: String? = null,
    ) {
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return
        val outcome = androidPosterResultToPresentationOutcome(result)
        recordPresented(
            context = context,
            deliveryId = normalizedId,
            serverId = serverId,
            transport = transport,
            appState = appState,
            presented = outcome.presented,
            suppressReason = outcome.suppressReason,
            accountId = accountId,
        )
    }

    fun putDeliveryOpenExtras(intent: Intent, request: JSONObject) {
        val deliveryId = normalizePushDeliveryId(request.optString("deliveryId")) ?: return
        val transport =
            RaftPushDeliveryTransport.fromWire(request.optString("deliveryTransport"))
                ?: RaftPushDeliveryTransport.Fcm
        val serverId = resolveAndroidPushServerId(request.optString("serverId"), request.optString("route"))
        intent.putExtra(EXTRA_RAFT_PUSH_DELIVERY_ID, deliveryId)
        intent.putExtra(EXTRA_RAFT_PUSH_DELIVERY_TRANSPORT, transport.wire)
        if (serverId.isNotEmpty()) {
            intent.putExtra(EXTRA_RAFT_PUSH_DELIVERY_SERVER_ID, serverId)
        }
    }

    fun recordOpenedFromIntent(
        context: Context,
        intent: Intent?,
        appState: RaftPushDeliveryAppState,
    ) {
        intent ?: return
        val payload =
            runCatching {
                extractPushOpenPayload(
                    flags = intent.flags,
                    rawDeliveryId = intent.getStringExtra(EXTRA_RAFT_PUSH_DELIVERY_ID),
                    rawTransport = intent.getStringExtra(EXTRA_RAFT_PUSH_DELIVERY_TRANSPORT),
                    rawServerId = intent.getStringExtra(EXTRA_RAFT_PUSH_DELIVERY_SERVER_ID),
                )
            }.getOrNull() ?: return
        runCatching { intent.removeExtra(EXTRA_RAFT_PUSH_DELIVERY_ID) }
        RaftPushDeliveryTrace.recordOpened(
            deliveryId = payload.deliveryId,
            serverId = payload.serverId,
            transport = payload.transport,
            appState = appState,
            openSurface = RaftPushDeliveryOpenSurface.SystemTray,
            deviceContext = deviceContext(context),
        )
    }
}
