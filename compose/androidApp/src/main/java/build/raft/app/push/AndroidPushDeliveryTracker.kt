package build.raft.app.push

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import build.raft.app.data.RaftNotificationRoute
import build.raft.app.data.RaftPushDeliveryAppState
import build.raft.app.data.RaftPushDeliveryCategory
import build.raft.app.data.RaftPushDeliveryDeviceContext
import build.raft.app.data.RaftPushDeliveryOpenSurface
import build.raft.app.data.RaftPushDeliverySuppressReason
import build.raft.app.data.RaftPushDeliveryTrace
import build.raft.app.data.RaftPushDeliveryTransport
import build.raft.app.data.RaftPushOpenLandingTarget
import build.raft.app.data.normalizePushDeliveryId
import build.raft.app.data.normalizePushDeliveryManufacturer
import build.raft.app.data.parseRaftNotificationRoute
import build.raft.app.data.parseRaftPushSeq
import org.json.JSONObject

internal const val EXTRA_RAFT_PUSH_DELIVERY_ID = "slockPushDeliveryId"
internal const val EXTRA_RAFT_PUSH_DELIVERY_TRANSPORT = "slockPushDeliveryTransport"
internal const val EXTRA_RAFT_PUSH_DELIVERY_SERVER_ID = "slockPushDeliveryServerId"
internal const val EXTRA_RAFT_PUSH_GROUP_KEY = "slockPushDeliveryGroupKey"
internal const val EXTRA_RAFT_PUSH_SEQ = "slockPushDeliverySeq"
internal const val EXTRA_RAFT_PUSH_HAS_SEQ = "slockPushDeliveryHasSeq"
internal const val EXTRA_RAFT_PUSH_OCCURRED_AT_MS = "slockPushDeliveryOccurredAtMs"
internal const val EXTRA_RAFT_PUSH_CONVERSATION_ID = "slockPushDeliveryConversationId"
internal const val EXTRA_RAFT_PUSH_CHANNEL_ID = "slockPushDeliveryChannelId"
internal const val EXTRA_RAFT_PUSH_THREAD_ID = "slockPushDeliveryThreadId"
internal const val EXTRA_RAFT_PUSH_PARENT_MESSAGE_ID = "slockPushDeliveryParentMessageId"
internal const val EXTRA_RAFT_PUSH_TASK_CONVERSATION_ID = "slockPushDeliveryTaskConversationId"
internal const val EXTRA_RAFT_PUSH_IS_ACTIVITY_TARGET = "slockPushDeliveryIsActivityTarget"

internal data class AndroidPosterPresentationOutcome(
    val presented: Boolean,
    val suppressReason: RaftPushDeliverySuppressReason?,
)

internal data class AndroidPushOpenIntentPayload(
    val deliveryId: String,
    val transport: RaftPushDeliveryTransport,
    val serverId: String,
    val groupKey: String? = null,
    val seq: Long? = null,
    val pushOccurredAtMs: Long? = null,
    val conversationId: String? = null,
    val channelId: String? = null,
    val threadId: String? = null,
    val parentMessageId: String? = null,
    val taskConversationId: String? = null,
    val isActivityTarget: Boolean = false,
)

internal data class AndroidPushLandingSpec(
    val groupKey: String?,
    val seq: Long?,
    val pushOccurredAtMs: Long?,
    val conversationId: String?,
    val channelId: String?,
    val threadId: String?,
    val parentMessageId: String?,
    val taskConversationId: String?,
    val isActivityTarget: Boolean,
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

internal fun resolveAndroidPushLandingSpec(request: JSONObject): AndroidPushLandingSpec {
    fun optNonBlank(key: String): String? = request.optString(key).trim().takeIf { it.isNotEmpty() }
    val serverId = resolveAndroidPushServerId(request.optString("serverId"), request.optString("route"))
    val route = parseRaftNotificationRoute(request.optString("route"))
    val conversationId = optNonBlank("conversationId")
    val taskConversationId = optNonBlank("taskConversationChannelId")
    val channelId =
        optNonBlank("channelId")
            ?: when (route) {
                is RaftNotificationRoute.Channel -> route.channelId
                is RaftNotificationRoute.DirectMessage -> route.channelId
                else -> null
            }
    val threadId = optNonBlank("threadId")
    val parentMessageId =
        optNonBlank("parentMessageId")
            ?: (route as? RaftNotificationRoute.Thread)?.parentMessageId
    val groupKey =
        optNonBlank("groupKey")
            ?: if (serverId.isNotEmpty() && !conversationId.isNullOrEmpty()) "$serverId:$conversationId" else null
    val seq = parseRaftPushSeq(request.optString("seq"))
    val pushOccurredAtMs = request.optLong("deliveryOccurredAtMs", 0L).takeIf { it > 0L }
    val hasConversationTarget =
        conversationId != null ||
            taskConversationId != null ||
            channelId != null ||
            threadId != null ||
            parentMessageId != null
    val isActivityTarget =
        when (route) {
            is RaftNotificationRoute.App -> false
            is RaftNotificationRoute.Agent -> !hasConversationTarget
            null -> !hasConversationTarget
            else -> false
        }
    return AndroidPushLandingSpec(
        groupKey = groupKey,
        seq = seq,
        pushOccurredAtMs = pushOccurredAtMs,
        conversationId = conversationId,
        channelId = channelId,
        threadId = threadId,
        parentMessageId = parentMessageId,
        taskConversationId = taskConversationId,
        isActivityTarget = isActivityTarget,
    )
}

internal fun extractPushOpenPayload(
    flags: Int,
    rawDeliveryId: String?,
    rawTransport: String?,
    rawServerId: String?,
    rawGroupKey: String? = null,
    seq: Long? = null,
    pushOccurredAtMs: Long? = null,
    rawConversationId: String? = null,
    rawChannelId: String? = null,
    rawThreadId: String? = null,
    rawParentMessageId: String? = null,
    rawTaskConversationId: String? = null,
    isActivityTarget: Boolean = false,
): AndroidPushOpenIntentPayload? {
    if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    val deliveryId = normalizePushDeliveryId(rawDeliveryId) ?: return null
    val transport = RaftPushDeliveryTransport.fromWire(rawTransport) ?: RaftPushDeliveryTransport.Fcm
    val serverId = rawServerId?.trim().orEmpty()
    return AndroidPushOpenIntentPayload(
        deliveryId = deliveryId,
        transport = transport,
        serverId = serverId,
        groupKey = rawGroupKey?.trim()?.takeIf { it.isNotEmpty() },
        seq = seq,
        pushOccurredAtMs = pushOccurredAtMs?.takeIf { it > 0L },
        conversationId = rawConversationId?.trim()?.takeIf { it.isNotEmpty() },
        channelId = rawChannelId?.trim()?.takeIf { it.isNotEmpty() },
        threadId = rawThreadId?.trim()?.takeIf { it.isNotEmpty() },
        parentMessageId = rawParentMessageId?.trim()?.takeIf { it.isNotEmpty() },
        taskConversationId = rawTaskConversationId?.trim()?.takeIf { it.isNotEmpty() },
        isActivityTarget = isActivityTarget,
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
        occurredAtMs: Long? = null,
    ) {
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return
        RaftPushDeliveryTrace.recordReceived(
            deliveryId = normalizedId,
            serverId = serverId,
            transport = transport,
            appState = appState,
            category = category,
            occurredAtMs = occurredAtMs?.takeIf { it > 0L } ?: System.currentTimeMillis(),
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
        val landing = resolveAndroidPushLandingSpec(request)
        intent.putExtra(EXTRA_RAFT_PUSH_DELIVERY_ID, deliveryId)
        intent.putExtra(EXTRA_RAFT_PUSH_DELIVERY_TRANSPORT, transport.wire)
        if (serverId.isNotEmpty()) {
            intent.putExtra(EXTRA_RAFT_PUSH_DELIVERY_SERVER_ID, serverId)
        }
        landing.groupKey?.let { intent.putExtra(EXTRA_RAFT_PUSH_GROUP_KEY, it) }
        landing.seq?.let {
            intent.putExtra(EXTRA_RAFT_PUSH_HAS_SEQ, true)
            intent.putExtra(EXTRA_RAFT_PUSH_SEQ, it)
        }
        landing.pushOccurredAtMs?.let { intent.putExtra(EXTRA_RAFT_PUSH_OCCURRED_AT_MS, it) }
        landing.conversationId?.let { intent.putExtra(EXTRA_RAFT_PUSH_CONVERSATION_ID, it) }
        landing.channelId?.let { intent.putExtra(EXTRA_RAFT_PUSH_CHANNEL_ID, it) }
        landing.threadId?.let { intent.putExtra(EXTRA_RAFT_PUSH_THREAD_ID, it) }
        landing.parentMessageId?.let { intent.putExtra(EXTRA_RAFT_PUSH_PARENT_MESSAGE_ID, it) }
        landing.taskConversationId?.let { intent.putExtra(EXTRA_RAFT_PUSH_TASK_CONVERSATION_ID, it) }
        if (landing.isActivityTarget) {
            intent.putExtra(EXTRA_RAFT_PUSH_IS_ACTIVITY_TARGET, true)
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
                    rawGroupKey = intent.getStringExtra(EXTRA_RAFT_PUSH_GROUP_KEY),
                    seq = if (intent.getBooleanExtra(EXTRA_RAFT_PUSH_HAS_SEQ, false)) intent.getLongExtra(EXTRA_RAFT_PUSH_SEQ, 0L) else null,
                    pushOccurredAtMs = intent.getLongExtra(EXTRA_RAFT_PUSH_OCCURRED_AT_MS, 0L).takeIf { it > 0L },
                    rawConversationId = intent.getStringExtra(EXTRA_RAFT_PUSH_CONVERSATION_ID),
                    rawChannelId = intent.getStringExtra(EXTRA_RAFT_PUSH_CHANNEL_ID),
                    rawThreadId = intent.getStringExtra(EXTRA_RAFT_PUSH_THREAD_ID),
                    rawParentMessageId = intent.getStringExtra(EXTRA_RAFT_PUSH_PARENT_MESSAGE_ID),
                    rawTaskConversationId = intent.getStringExtra(EXTRA_RAFT_PUSH_TASK_CONVERSATION_ID),
                    isActivityTarget = intent.getBooleanExtra(EXTRA_RAFT_PUSH_IS_ACTIVITY_TARGET, false),
                )
            }.getOrNull() ?: return
        runCatching { intent.removeExtra(EXTRA_RAFT_PUSH_DELIVERY_ID) }
        val candidateIds =
            listOfNotNull(
                payload.conversationId,
                payload.channelId,
                payload.threadId,
                payload.taskConversationId,
            ).toSet()
        RaftPushDeliveryTrace.beginDeferredOpen(
            deliveryId = payload.deliveryId,
            serverId = payload.serverId,
            transport = payload.transport,
            appState = appState,
            openSurface = RaftPushDeliveryOpenSurface.SystemTray,
            target =
                RaftPushOpenLandingTarget(
                    serverId = payload.serverId,
                    conversationIds = candidateIds,
                    parentMessageId = payload.parentMessageId,
                    isActivityTarget = payload.isActivityTarget,
                ),
            pushOccurredAtMs = payload.pushOccurredAtMs,
            groupKey = payload.groupKey,
            seq = payload.seq,
            deviceContext = deviceContext(context),
        )
    }
}
