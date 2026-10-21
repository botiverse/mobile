package build.raft.app.shared

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.net.Uri
import build.raft.app.R
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.KeyValuePushPreviewModePreferenceStore
import build.raft.app.data.PUSH_PREVIEW_MODE_UNREADABLE
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.SystemUnreadNotificationRequest
import build.raft.app.data.conversationKindWireValue
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.systemUnreadNotificationServerGate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

internal object AndroidUnreadNotificationPoster {
    const val SHARED_NOTIFICATION_CHANNEL_ID = "shared-system-notifications-v1"

    private const val UNREAD_GENERAL_NOTIFICATION_CHANNEL_ID = "slock-unread-general-v1"
    private const val UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID = "slock-unread-channel-v1"
    private const val UNREAD_DM_NOTIFICATION_CHANNEL_ID = "slock-unread-dm-v1"
    // Also used by the Settings test notification so the test exercises the
    // same HIGH-importance channel real mentions ride on.
    const val UNREAD_MENTION_NOTIFICATION_CHANNEL_ID = "slock-unread-mention-v1"

    fun postUnreadNotification(context: Context, request: SystemUnreadNotificationRequest): Map<String, Any> =
        postUnreadNotification(context, request.toJson())

    fun postUnreadNotification(context: Context, request: JSONObject): Map<String, Any> {
        val appContext = context.applicationContext
        val serverGate =
            systemUnreadNotificationServerGate(
                notificationServerId = request.optString("serverId"),
                currentServerId = currentSelectedServerId(appContext)
            )
        if (!serverGate.shouldPost) {
            return mapOf("status" to "error", "code" to serverGate.code)
        }
        if (!hasNotificationPermission(appContext)) {
            return mapOf("status" to "error", "code" to "permission_denied")
        }
        ensureSharedNotificationChannels(appContext)
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return mapOf("status" to "error", "code" to "notifications_disabled")
        }
        val category = request.optString("category")
        val channelId = notificationChannelIdForCategory(category)
        if (isNotificationChannelBlocked(appContext, channelId)) {
            return mapOf("status" to "error", "code" to "channel_blocked", "channelId" to channelId)
        }
        val notificationKey =
            request.optString("notificationKey").takeIf { it.isNotBlank() }
                ?: "${request.optString("serverId")}:${request.optString("threadId")}:${request.optString("messageId")}"
        val notificationId = stableNotificationId(notificationKey)
        val contentIntent = unreadNotificationContentIntent(appContext, request, notificationId)
        return runCatching {
            NotificationManagerCompat.from(appContext)
                .notify(
                    notificationId,
                    buildUnreadNotification(
                        context = appContext,
                        channelId = channelId,
                        request = request,
                        previewMode = currentPushPreviewMode(),
                        contentIntent = contentIntent
                    )
                )
            mapOf(
                "status" to "ok",
                "code" to "posted",
                "notificationId" to notificationId,
                "channelId" to channelId
            )
        }.getOrElse { error ->
            mapOf(
                "status" to "error",
                "code" to "post_failed",
                "message" to (error.message ?: error.javaClass.simpleName)
            )
        }
    }

    /**
     * The tray notification for one unread request. Both entry points build it here: FCM
     * (`RaftFirebaseMessagingService`, possibly already redacted by the server) and the local realtime
     * path (`AndroidAppScopeRealtimeHostContext`, raw content, the only path on devices without GMS).
     * [previewMode] is the device's choice from [KeyValuePushPreviewModePreferenceStore]; the title,
     * the collapsed text and the expanded big text all use the same resolved text, and the lock
     * screen gets a content-free public version.
     */
    internal fun buildUnreadNotification(
        context: Context,
        channelId: String,
        request: JSONObject,
        previewMode: PushPreviewMode,
        contentIntent: PendingIntent?,
        strings: PushPreviewStrings = AndroidResourcePushPreviewStrings(context),
        whenMillis: Long = System.currentTimeMillis()
    ): Notification {
        val appContext = context.applicationContext
        val text =
            resolveUnreadNotificationText(
                request = request,
                devicePreviewMode = previewMode,
                strings = strings,
                defaultTitle = appContext.getString(R.string.app_name),
                defaultBody = appContext.getString(R.string.notification_unread_default_text)
            )
        val publicVersion =
            NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(text.publicTitle)
                .setContentText(text.publicBody)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setWhen(whenMillis)
                .setShowWhen(true)
                .build()
        return NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setWhen(whenMillis)
            .setShowWhen(true)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }

    /**
     * The store already maps a missing choice to full and a corrupt one to hidden; a store that cannot
     * be opened at all is unreadable too and fails closed the same way.
     */
    private fun currentPushPreviewMode(): PushPreviewMode =
        runCatching {
            PushPreviewMode.fromWire(
                KeyValuePushPreviewModePreferenceStore(raftSharedPreferenceStore()).readPushPreviewModeSync()
            )
        }.getOrNull() ?: PushPreviewMode.fromWire(PUSH_PREVIEW_MODE_UNREADABLE) ?: PushPreviewMode.Hidden

    private fun currentSelectedServerId(context: Context): String? =
        runCatching {
            runBlocking {
                KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(context.applicationContext))
                    .readSession()
                    ?.currentServerId
            }
        }.getOrNull()

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context.applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ensureSharedNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    SHARED_NOTIFICATION_CHANNEL_ID,
                    appContext.getString(R.string.settings_notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = appContext.getString(R.string.settings_notification_channel_description)
                    enableVibration(false)
                },
                NotificationChannel(
                    UNREAD_GENERAL_NOTIFICATION_CHANNEL_ID,
                    appContext.getString(R.string.notification_unread_general_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = appContext.getString(R.string.notification_unread_general_channel_description)
                },
                NotificationChannel(
                    UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID,
                    appContext.getString(R.string.notification_unread_channel_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = appContext.getString(R.string.notification_unread_channel_channel_description)
                },
                NotificationChannel(
                    UNREAD_DM_NOTIFICATION_CHANNEL_ID,
                    appContext.getString(R.string.notification_unread_dm_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = appContext.getString(R.string.notification_unread_dm_channel_description)
                },
                NotificationChannel(
                    UNREAD_MENTION_NOTIFICATION_CHANNEL_ID,
                    appContext.getString(R.string.notification_unread_mention_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = appContext.getString(R.string.notification_unread_mention_channel_description)
                }
            )
        )
    }

    fun isNotificationChannelBlocked(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val appContext = context.applicationContext
        val channel =
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .getNotificationChannel(channelId)
        return channel?.importance == NotificationManager.IMPORTANCE_NONE
    }

    private fun unreadNotificationContentIntent(context: Context, request: JSONObject, notificationId: Int): PendingIntent? {
        val appContext = context.applicationContext
        val intent = unreadNotificationLaunchIntent(appContext, request)
            ?: appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ?: return null
        intent.putExtra(EXTRA_RAFT_APP_OPEN_SOURCE, RAFT_APP_OPEN_SOURCE_PUSH_NOTIFICATION)
        return PendingIntent.getActivity(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun unreadNotificationLaunchIntent(context: Context, request: JSONObject): Intent? {
        val appContext = context.applicationContext
        val basePageData =
            RaftCmpActivity.buildUnreadNotificationAppShellPageData(request) ?: return null
        val multiShellHostEnabled =
            runCatching {
                KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())
                    .readMultiShellHostEnabledSync()
            }.getOrDefault(false)
        val pageData =
            basePageData.apply {
                put(ANDROID_DOCUMENT_SHELL_KEY, true)
                currentAndroidShellRouteOwner(appContext)?.let { withAndroidShellRouteOwner(it) }
            }
        val target = resolveAndroidShellLaunchTarget(pageData, multiShellHostEnabled)
        val resolvedPageData = pageData.withAndroidShellLaunchTarget(target)
        return Intent(appContext, raftAppShellHostActivityClass(target))
            .putExtra(RaftCmpActivity.KEY_PAGE_NAME, RaftCmpActivity.PAGE_APP_SHELL)
            .putExtra(RaftCmpActivity.KEY_PAGE_DATA, RaftPageData.fromJson(resolvedPageData).text)
            .apply {
                if (target.documentShell) {
                    data = Uri.parse(target.shellInstanceId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
                } else {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            }
    }

    private fun notificationChannelIdForCategory(category: String): String =
        when (category) {
            "channel_unread" -> UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID
            "direct_message" -> UNREAD_DM_NOTIFICATION_CHANNEL_ID
            "mention" -> UNREAD_MENTION_NOTIFICATION_CHANNEL_ID
            else -> UNREAD_GENERAL_NOTIFICATION_CHANNEL_ID
        }

    private fun stableNotificationId(key: String): Int {
        val hash = key.hashCode()
        return if (hash == Int.MIN_VALUE) {
            Int.MAX_VALUE
        } else {
            kotlin.math.abs(hash).coerceAtLeast(1)
        }
    }

    internal fun SystemUnreadNotificationRequest.toJson(): JSONObject =
        JSONObject()
            .put("serverId", serverId)
            .put("messageId", messageId)
            .put("threadId", threadId)
            .put("channelId", channelId.orEmpty())
            .put("parentChannelId", parentChannelId.orEmpty())
            .put("parentMessageId", parentMessageId.orEmpty())
            .put("kind", conversationKindWireValue().orEmpty())
            .put("category", category.wireValue)
            .put("title", title)
            .put("body", body)
            .put("notificationKey", notificationKey)
            .put("senderName", senderName.orEmpty())
            .put("mentioned", mentioned)
}
