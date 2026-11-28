package build.raft.app.push

import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftBadgeSource
import build.raft.app.data.RaftBadgeTelemetry
import build.raft.app.data.RaftNotificationTestAck
import build.raft.app.data.RaftPushDeliveryAppState
import build.raft.app.data.RaftPushDeliverySuppressReason
import build.raft.app.data.RaftPushDeliveryTransport
import build.raft.app.data.RaftPushMessageType
import build.raft.app.data.normalizePushDeliveryCategory
import build.raft.app.data.normalizePushDeliveryId
import build.raft.app.data.parseRaftFcmNotificationRetract
import build.raft.app.data.parseRaftPushBadge
import build.raft.app.network.raftProcessIsForeground
import build.raft.app.thread.raftSystemNotificationTargetsVisibleConversation
import build.raft.app.shared.AndroidUnreadNotificationPoster
import build.raft.app.shared.RaftNotificationAlertsPreference
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/**
 * FCM data messages of the unified notification contract (slock docs/contracts/notifications.md
 * §4.3), split by `type`:
 *
 * - `alert` (or no type: an older server): posted to the tray unless this device's notifications are
 *   switched off or the user is looking at that conversation. Every workspace's alert is posted, not
 *   only the selected one's; its tap switches workspace first.
 * - `retract`: the conversation was read on some device; its notifications up to `throughSeq` close.
 * - `sync`: only the badge changed; Android has no badge count of its own beyond the tray.
 * - anything else: dropped, so a future type never posts a blank alert.
 */
class RaftFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        RaftAppLog.i(TAG, "fcm_registered installationIdPresent=${installationId.isNotBlank()}")
        if (installationId.isNotBlank()) {
            AndroidPushRegistration.onFirebaseInstallationId(applicationContext, installationId)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data.isEmpty()) {
            RaftAppLog.i(TAG, "fcm_message_received type=empty_data")
            return
        }
        when (raftFcmMessageDecision(data, alertsEnabled = { RaftNotificationAlertsPreference.isEnabled(applicationContext) })) {
            RaftFcmMessageDecision.Probe -> {
                val probeId = data["probeId"]?.trim().orEmpty()
                AndroidFcmReachabilityController.onProbeReceived(applicationContext, probeId)
                RaftAppLog.i(TAG, "fcm_message_handled type=probe probeIdPresent=${probeId.isNotEmpty()}")
            }
            RaftFcmMessageDecision.Retract -> {
                AndroidFcmReachabilityController.onRealFcmDelivered(applicationContext, source = "retract")
                val retract = parseRaftFcmNotificationRetract(data)
                val closed = retract?.let { AndroidUnreadNotificationPoster.retract(applicationContext, it) } ?: 0
                applyServerBadge(data)
                parseRaftPushBadge(data["badge"])?.let { badge ->
                    RaftBadgeTelemetry.recordBadgeSet(count = badge, source = RaftBadgeSource.Retract)
                }
                RaftAppLog.i(TAG, "fcm_message_handled type=retract parsed=${retract != null} closed=$closed")
            }
            RaftFcmMessageDecision.Sync -> {
                AndroidFcmReachabilityController.onRealFcmDelivered(applicationContext, source = "sync")
                // The launcher badge follows the tray (setNumber); a per-server sync updates that server's summary.
                applyServerBadge(data)
                parseRaftPushBadge(data["badge"])?.let { badge ->
                    RaftBadgeTelemetry.recordBadgeSet(count = badge, source = RaftBadgeSource.Sync)
                }
                RaftAppLog.i(TAG, "fcm_message_handled type=sync")
            }
            RaftFcmMessageDecision.DropUnknownType ->
                RaftAppLog.i(TAG, "fcm_message_dropped reason=unknown_type")
            RaftFcmMessageDecision.DropAlertsDisabled -> {
                AndroidFcmReachabilityController.onRealFcmDelivered(applicationContext, source = "alert_disabled")
                // The second guard: the server already stops alerting a registration with alertsEnabled=false.
                RaftAppLog.i(TAG, "fcm_message_suppressed reason=alerts_disabled")
                normalizePushDeliveryId(data["deliveryId"])?.let { deliveryId ->
                    val serverId = resolveAndroidPushServerId(data["serverId"], data["route"])
                    val appState =
                        if (raftProcessIsForeground()) RaftPushDeliveryAppState.Foreground else RaftPushDeliveryAppState.Background
                    AndroidPushDeliveryTracker.recordReceived(
                        context = applicationContext,
                        deliveryId = deliveryId,
                        serverId = serverId,
                        transport = RaftPushDeliveryTransport.Fcm,
                        appState = appState,
                        category = normalizePushDeliveryCategory(category = data["category"], kind = data["kind"]),
                    )
                    AndroidPushDeliveryTracker.recordPresented(
                        context = applicationContext,
                        deliveryId = deliveryId,
                        serverId = serverId,
                        transport = RaftPushDeliveryTransport.Fcm,
                        appState = appState,
                        presented = false,
                        suppressReason = RaftPushDeliverySuppressReason.AlertsDisabled,
                    )
                }
                // A Settings test answered at once instead of waiting out the 15 s.
                data["testId"]?.takeIf { it.isNotBlank() }?.let { RaftNotificationTestAck.ack(it, shown = false) }
            }
            RaftFcmMessageDecision.Alert -> {
                AndroidFcmReachabilityController.onRealFcmDelivered(applicationContext, source = "alert")
                val pushSentAtMs = message.sentTime.takeIf { it > 0L }
                postAlert(data, pushSentAtMs = pushSentAtMs)
            }
        }
    }

    /** `serverBadge` (contract §1.4): the server's own term, shown as its summary's number. */
    private fun applyServerBadge(data: Map<String, String>) {
        val serverId = data["serverId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val serverBadge = parseRaftPushBadge(data["serverBadge"]) ?: return
        AndroidUnreadNotificationPoster.applyServerBadge(applicationContext, serverId, serverBadge)
    }

    private fun postAlert(data: Map<String, String>, pushSentAtMs: Long? = null) {
        val foreground = raftProcessIsForeground()
        val deliveryId = normalizePushDeliveryId(data["deliveryId"])
        val serverId = resolveAndroidPushServerId(data["serverId"], data["route"])
        val appState =
            if (foreground) RaftPushDeliveryAppState.Foreground else RaftPushDeliveryAppState.Background
        val receivedAtMs = System.currentTimeMillis()
        val pushOccurredAtMs = pushSentAtMs ?: receivedAtMs
        if (deliveryId != null) {
            AndroidPushDeliveryTracker.recordReceived(
                context = applicationContext,
                deliveryId = deliveryId,
                serverId = serverId,
                transport = RaftPushDeliveryTransport.Fcm,
                appState = appState,
                category = normalizePushDeliveryCategory(category = data["category"], kind = data["kind"]),
                occurredAtMs = receivedAtMs,
            )
            if (pushSentAtMs != null) {
                RaftBadgeTelemetry.recordPushTimestamp(deliveryId = deliveryId, occurredAtMs = pushSentAtMs)
            }
        }
        if (raftFcmAlertSuppressedInForeground(data["category"], foreground = foreground)) {
            // In the foreground only the in-app banner shows (it gets the same notification over the
            // socket), as Slack does: no second, system copy.
            if (deliveryId != null) {
                AndroidPushDeliveryTracker.recordPresented(
                    context = applicationContext,
                    deliveryId = deliveryId,
                    serverId = serverId,
                    transport = RaftPushDeliveryTransport.Fcm,
                    appState = appState,
                    presented = false,
                    suppressReason = RaftPushDeliverySuppressReason.ForegroundSuppressed,
                )
            }
            RaftAppLog.i(TAG, "fcm_message_suppressed reason=app_foreground")
            return
        }
        if (
            raftSystemNotificationTargetsVisibleConversation(
                serverId = data["serverId"],
                kind = data["kind"],
                channelId = data["channelId"],
                threadId = data["threadId"],
                parentChannelId = data["parentChannelId"],
                parentMessageId = data["parentMessageId"],
                messageId = data["messageId"],
                category = data["category"]
            )
        ) {
            // The user is already looking at this conversation: stay silent, same as the in-app banner.
            if (deliveryId != null) {
                AndroidPushDeliveryTracker.recordPresented(
                    context = applicationContext,
                    deliveryId = deliveryId,
                    serverId = serverId,
                    transport = RaftPushDeliveryTransport.Fcm,
                    appState = appState,
                    presented = false,
                    suppressReason = RaftPushDeliverySuppressReason.VisibleConversation,
                )
            }
            RaftAppLog.i(TAG, "fcm_message_suppressed reason=visible_conversation")
            return
        }
        val request = raftFcmUnreadNotificationRequest(data, pushOccurredAtMs = pushOccurredAtMs)
        val result = AndroidUnreadNotificationPoster.postUnreadNotification(applicationContext, request)
        if (result["status"] == "ok" && result["code"] != "already_retracted") {
            parseRaftPushBadge(data["badge"])?.let { badge ->
                RaftBadgeTelemetry.recordBadgeSet(count = badge, source = RaftBadgeSource.Alert)
            }
        }
        if (deliveryId != null) {
            AndroidPushDeliveryTracker.recordPosterOutcome(
                context = applicationContext,
                deliveryId = deliveryId,
                serverId = serverId,
                transport = RaftPushDeliveryTransport.Fcm,
                appState = appState,
                result = result,
            )
        }
        // A test from Settings (contract §6): confirm whether it reached the tray.
        data["testId"]?.takeIf { it.isNotBlank() }?.let { testId ->
            RaftNotificationTestAck.ack(testId, shown = result["status"] == "ok")
        }
        RaftAppLog.i(
            TAG,
            "fcm_message_handled type=alert dataKeys=${data.keys.sorted().joinToString(",")} status=${result["status"]} code=${result["code"]}"
        )
    }

    private companion object {
        const val TAG = "SlockFCM"
    }
}

/**
 * A message notification while the app is in the foreground: the in-app banner shows it, so the
 * system copy is not posted. Reminders, notices and tests have no in-app banner and still post.
 */
internal fun raftFcmAlertSuppressedInForeground(category: String?, foreground: Boolean): Boolean =
    foreground && category !in setOf("reminder", "system_notice", "test")

internal enum class RaftFcmMessageDecision {
    Alert,
    Retract,
    Sync,
    Probe,
    DropUnknownType,
    DropAlertsDisabled
}

/**
 * What one FCM data message does. [alertsEnabled] (the device's notifications switch) is read only
 * for an alert: a retraction, sync or silent reachability probe still applies when alerts are off,
 * so a read conversation does not linger, the badge stays right, and silent reachability probes
 * confirm transport health without ever posting a notification.
 */
internal fun raftFcmMessageDecision(data: Map<String, String>, alertsEnabled: () -> Boolean): RaftFcmMessageDecision =
    when (RaftPushMessageType.fromWire(data["type"])) {
        null -> RaftFcmMessageDecision.DropUnknownType
        RaftPushMessageType.Probe -> RaftFcmMessageDecision.Probe
        RaftPushMessageType.Retract -> RaftFcmMessageDecision.Retract
        RaftPushMessageType.Sync -> RaftFcmMessageDecision.Sync
        RaftPushMessageType.Alert ->
            if (alertsEnabled()) RaftFcmMessageDecision.Alert else RaftFcmMessageDecision.DropAlertsDisabled
    }

/**
 * The poster request for one FCM data message. `previewMode` / `titleKey` / `bodyKey` / `bodyArgs`
 * are present when the server redacted the push for the account's preview choice; for a registration
 * of key set 3 the title / subtitle / body keys of the banner copy too, and `subtitle` is the context
 * line (the plain `title` then carries no ` · subtitle`).
 */
internal fun raftFcmUnreadNotificationRequest(
    data: Map<String, String>,
    pushOccurredAtMs: Long? = null,
): JSONObject =
    JSONObject().apply {
        for (key in FCM_UNREAD_NOTIFICATION_KEYS) {
            put(key, data[key].orEmpty())
        }
        if (!data["deliveryId"].isNullOrBlank()) {
            put("deliveryTransport", RaftPushDeliveryTransport.Fcm.wire)
            pushOccurredAtMs?.takeIf { it > 0L }?.let { put("deliveryOccurredAtMs", it) }
        }
    }

private val FCM_UNREAD_NOTIFICATION_KEYS =
    listOf(
        "serverId",
        "serverName",
        "messageId",
        "threadId",
        "channelId",
        "parentChannelId",
        "parentMessageId",
        "kind",
        "category",
        "title",
        "body",
        "notificationKey",
        "routeUrl",
        "serverSeq",
        "previewMode",
        "titleKey",
        "bodyKey",
        "bodyArgs",
        // Banner copy (contract §1.5, key set 3): the context line and the other parts' keys.
        "subtitle",
        "titleArgs",
        "subtitleKey",
        "subtitleArgs",
        // Task v2 task-reply pushes (slock #11445): open the task conversation on tap.
        "taskId",
        "taskConversationChannelId",
        "taskMessageId",
        // Unified notification contract (§4.3): identity for retraction, the click target, the
        // account sound setting and the personal / normal priority.
        "type",
        "id",
        "groupKey",
        "conversationId",
        "seq",
        "route",
        "priority",
        "silent",
        "badge",
        "serverBadge",
        "testId",
        "deliveryId"
    )
