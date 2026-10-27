package build.raft.app.shared

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.Bundle
import android.service.notification.StatusBarNotification
import build.raft.app.R
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.KeyValuePushPreviewModePreferenceStore
import build.raft.app.data.PUSH_PREVIEW_MODE_UNREADABLE
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftConversationReadHost
import build.raft.app.data.RaftConversationReadListener
import build.raft.app.data.SystemUnreadNotificationRequest
import build.raft.app.data.conversationKindWireValue
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.systemUnreadNotificationServerGate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

internal object AndroidUnreadNotificationPoster {
    const val SHARED_NOTIFICATION_CHANNEL_ID = "shared-system-notifications-v1"

    internal const val UNREAD_GENERAL_NOTIFICATION_CHANNEL_ID = "slock-unread-general-v1"
    internal const val UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID = "slock-unread-channel-v1"
    internal const val UNREAD_DM_NOTIFICATION_CHANNEL_ID = "slock-unread-dm-v1"
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
        ensureSharedNotificationChannels(appContext)
        if (!hasNotificationPermission(appContext)) {
            return mapOf("status" to "error", "code" to "permission_denied")
        }
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return mapOf("status" to "error", "code" to "notifications_disabled")
        }
        val category = request.optString("category")
        val channelId = notificationChannelIdForCategory(category)
        if (isNotificationChannelBlocked(appContext, channelId)) {
            return mapOf("status" to "error", "code" to "channel_blocked", "channelId" to channelId)
        }
        return runCatching {
            postGroupedUnreadNotification(
                context = appContext,
                channelId = channelId,
                request = request,
                previewMode = currentPushPreviewMode()
            )
        }.getOrElse { error ->
            mapOf(
                "status" to "error",
                "code" to "post_failed",
                "message" to (error.message ?: error.javaClass.simpleName)
            )
        }
    }

    private val groupingLock = Any()

    /** Monotonic time of the grouping state; tests replace it with a clock they advance. */
    @Volatile
    internal var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    private val recentPosts = UnreadRecentPosts(clock = { clock() })

    /** Orders posts against reads: every conversation post takes the next value. */
    private val postCounter = AtomicLong()

    /** The [postCounter] value of each conversation's last post; must hold [groupingLock]. */
    private val lastPostMarks = mutableMapOf<Int, Long>()

    /** The conversations each server's posted summary counted; must hold [groupingLock]. */
    private val postedSummaryConversations = mutableMapOf<String, Set<Int>>()

    /** Servers whose summary update is deferred ([SUMMARY_COALESCE_MILLIS]); must hold [groupingLock]. */
    private val pendingSummaryServers = mutableSetOf<String>()

    /**
     * How long a summary update that changes no conversation membership waits, so a burst of messages
     * updates it once instead of once per message.
     */
    internal const val SUMMARY_COALESCE_MILLIS = 1_000L

    private val backgroundExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "raft-unread-notifications").apply { isDaemon = true }
        }
    }

    /** Runs a deferred summary update; tests replace it to run the update when they choose. */
    @Volatile
    internal var summaryScheduler: (delayMillis: Long, task: () -> Unit) -> Unit = { delayMillis, task ->
        backgroundExecutor.schedule({ runCatching(task) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * Clears a conversation's notification when the user reads it in the app (shared
     * [RaftConversationReadHost]): reading #general in the app must not leave its pushes in the tray,
     * nor count them again with the next one. Off the caller's (often main) thread: it takes the
     * grouping lock a burst of pushes may hold and talks to the notification service.
     */
    fun installConversationReadListener(context: Context) {
        val appContext = context.applicationContext
        RaftConversationReadHost.listener =
            RaftConversationReadListener { serverId, channelId ->
                // Taken now, on the reader's thread: a message posted after this read, while the clear
                // waits for the background thread and the lock, stays.
                val readMark = postCounter.get()
                backgroundRunner { clearConversation(appContext, serverId, channelId, readMark) }
            }
    }

    /** Runs the clear of a read conversation off the reader's thread; tests run it when they choose. */
    @Volatile
    internal var backgroundRunner: (task: () -> Unit) -> Unit = { task ->
        backgroundExecutor.execute { runCatching(task) }
    }

    /**
     * Cancels the notification of conversation [channelId] of [serverId] and forgets its messages, so
     * the next message there starts from one; its server's summary is recounted, or removed with the
     * last conversation. A conversation without a notification costs only a tray read.
     *
     * [readMark] is [postCounter] when the user read it: a conversation posted to after that has a
     * message the read did not cover, and is left alone. The read position itself (a seq) cannot be
     * compared: neither the FCM data nor the realtime notification event carries the message's seq.
     */
    @SuppressLint("MissingPermission") // Only cancels, or re-posts a summary over posted conversations.
    fun clearConversation(context: Context, serverId: String, channelId: String, readMark: Long = Long.MAX_VALUE) {
        val appContext = context.applicationContext
        val conversationKey =
            AndroidUnreadNotificationGrouping.conversationKey(
                JSONObject().put("serverId", serverId).put("channelId", channelId)
            )
        val notificationId = AndroidUnreadNotificationGrouping.conversationNotificationId(conversationKey)
        runCatching {
            synchronized(groupingLock) {
                if (recentPosts.isCleared(notificationId)) return@synchronized
                if ((lastPostMarks[notificationId] ?: 0L) > readMark) return@synchronized
                val shown =
                    recentPosts.has(notificationId) ||
                        activeUnreadNotifications(appContext).any {
                            it.tag == AndroidUnreadNotificationGrouping.CONVERSATION_TAG && it.id == notificationId
                        }
                if (!shown) return@synchronized
                recentPosts.clear(notificationId)
                NotificationManagerCompat.from(appContext)
                    .cancel(AndroidUnreadNotificationGrouping.CONVERSATION_TAG, notificationId)
                refreshServerSummary(appContext, serverId.trim(), SummaryRefresh.Now)
            }
        }
    }

    /**
     * The user swiped conversation [notificationId] away (null: an older delete intent without the id):
     * forget it, then recount or remove the summaries.
     */
    fun onConversationDismissed(context: Context, notificationId: Int?) {
        if (notificationId != null) {
            synchronized(groupingLock) { recentPosts.clear(notificationId) }
        }
        refreshUnreadGroupSummaries(context)
    }

    /** Forgets every recent post and summary state (tests). */
    internal fun resetGroupingState() {
        synchronized(groupingLock) {
            recentPosts.reset()
            lastPostMarks.clear()
            postedSummaryConversations.clear()
            pendingSummaryServers.clear()
        }
    }

    private enum class SummaryRefresh {
        /** After a conversation post: immediately when the summary's conversations changed, else coalesced. */
        AfterPost,

        /** Re-post now (or cancel when no conversation is left). */
        Now,

        /** Like [Now], but leaves a posted summary that already has the right count alone. */
        IfChanged
    }

    /**
     * Posts [request] into its conversation notification and refreshes its server's group summary
     * (see [AndroidUnreadNotificationGrouping] for the shape). The gates (server, permission, channel)
     * are the caller's. Serialized: FCM and the local realtime path post from different threads, and
     * each post reads the conversation's history before it replaces it.
     */
    @SuppressLint("MissingPermission") // hasNotificationPermission is checked by the caller.
    internal fun postGroupedUnreadNotification(
        context: Context,
        channelId: String,
        request: JSONObject,
        previewMode: PushPreviewMode,
        whenMillis: Long = System.currentTimeMillis(),
        contentIntent: (notificationId: Int) -> PendingIntent? = { id ->
            unreadNotificationContentIntent(context.applicationContext, request, id)
        }
    ): Map<String, Any> = synchronized(groupingLock) {
        val appContext = context.applicationContext
        val serverId = request.optString("serverId").trim()
        val conversationKey = AndroidUnreadNotificationGrouping.conversationKey(request)
        val notificationId = AndroidUnreadNotificationGrouping.conversationNotificationId(conversationKey)
        val previous =
            recentPosts.history(notificationId)
                ?: activeUnreadNotifications(appContext)
                    .firstOrNull { it.tag == AndroidUnreadNotificationGrouping.CONVERSATION_TAG && it.id == notificationId }
                    ?.let { UnreadConversationHistory.readFrom(it.notification.extras) }
                ?: UnreadConversationHistory.EMPTY
        val notification =
            buildUnreadNotification(
                context = appContext,
                channelId = channelId,
                request = request,
                previewMode = previewMode,
                contentIntent = contentIntent(notificationId),
                whenMillis = whenMillis,
                previous = previous,
                deleteIntent = unreadNotificationDismissIntent(appContext, notificationId)
            )
        NotificationManagerCompat.from(appContext)
            .notify(AndroidUnreadNotificationGrouping.CONVERSATION_TAG, notificationId, notification)
        lastPostMarks[notificationId] = postCounter.incrementAndGet()
        val history = UnreadConversationHistory.readFrom(notification.extras)
        recentPosts.record(
            notificationId = notificationId,
            serverId = serverId,
            history = history,
            entry =
                UnreadSummaryEntry(
                    notificationId = notificationId,
                    channelId = channelId,
                    title = notification.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_TITLE).orEmpty(),
                    latestLine = history.lines.lastOrNull().orEmpty(),
                    count = history.count,
                    postedAtMillis = whenMillis
                )
        )
        refreshServerSummary(appContext, serverId, SummaryRefresh.AfterPost)
        mapOf(
            "status" to "ok",
            "code" to "posted",
            "notificationId" to notificationId,
            "channelId" to channelId,
            "count" to history.count
        )
    }

    /**
     * Cancels every group summary left without a conversation and recounts the others. A summary is
     * only removed with its children when the user swipes the whole group; a conversation tapped open
     * (auto-cancel) or swiped on its own leaves the summary behind, which Android then shows as a
     * stale standalone notification. Called when a conversation notification is dismissed
     * ([RaftUnreadNotificationDismissReceiver]) and when the app comes to the foreground, which is
     * where a tap on a conversation notification lands.
     */
    fun refreshUnreadGroupSummaries(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            synchronized(groupingLock) {
                val active = activeUnreadNotifications(appContext)
                // Whatever is not in the tray by now was dismissed, unless it was posted a moment ago.
                val keptByGrace = recentPosts.retainOnly(trayConversationIds(active))
                val serverIds =
                    active
                        .mapNotNull { it.notification.extras?.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_ID) }
                        .toSet() + postedSummaryConversations.keys
                for (serverId in serverIds) refreshServerSummary(appContext, serverId, SummaryRefresh.IfChanged)
                // A conversation the tray does not list only because it was posted a moment ago, or
                // because it was tapped open right after: look again once the grace is over.
                for (serverId in keptByGrace) deferSummary(appContext, serverId)
            }
        }
    }

    /**
     * Must hold [groupingLock]. Only re-posts a summary that has conversations, which were themselves
     * posted with the permission; without it Android drops the post.
     *
     * Android rate-limits a package's notification updates (a few per second) and sheds the excess, so
     * a burst must not cost two notify calls per message. After a post ([SummaryRefresh.AfterPost]) the
     * summary is posted at once only when its conversations changed (the first one, a new one), which
     * keeps the group bundled; a new message in a conversation it already counts only marks the summary
     * pending, and one deferred update ([SUMMARY_COALESCE_MILLIS]) recounts it for the whole burst.
     */
    @SuppressLint("MissingPermission")
    private fun refreshServerSummary(context: Context, serverId: String, refresh: SummaryRefresh) {
        val summaryId = AndroidUnreadNotificationGrouping.summaryNotificationId(serverId)
        val entries = unreadSummaryEntries(context, serverId)
        val manager = NotificationManagerCompat.from(context)
        if (entries.isEmpty()) {
            manager.cancel(AndroidUnreadNotificationGrouping.SUMMARY_TAG, summaryId)
            postedSummaryConversations.remove(serverId)
            pendingSummaryServers.remove(serverId)
            return
        }
        val conversations = entries.map { it.notificationId }.toSet()
        when (refresh) {
            SummaryRefresh.AfterPost ->
                if (postedSummaryConversations[serverId] == conversations) {
                    deferSummary(context, serverId)
                    return
                }
            SummaryRefresh.IfChanged -> {
                val posted =
                    activeUnreadNotifications(context)
                        .firstOrNull { it.tag == AndroidUnreadNotificationGrouping.SUMMARY_TAG && it.id == summaryId }
                if (posted != null && posted.notification.number == entries.sumOf { it.count }) {
                    postedSummaryConversations[serverId] = conversations
                    return
                }
            }
            SummaryRefresh.Now -> Unit
        }
        manager.notify(
            AndroidUnreadNotificationGrouping.SUMMARY_TAG,
            summaryId,
            buildUnreadGroupSummary(
                context = context,
                serverId = serverId,
                channelId = summaryChannelId(context, entries),
                entries = entries,
                contentIntent = unreadSummaryContentIntent(context, summaryId)
            )
        )
        postedSummaryConversations[serverId] = conversations
        pendingSummaryServers.remove(serverId)
    }

    /** Must hold [groupingLock]. One deferred update per server, however many ask for it. */
    private fun deferSummary(context: Context, serverId: String) {
        if (pendingSummaryServers.add(serverId)) {
            summaryScheduler(SUMMARY_COALESCE_MILLIS) { flushPendingSummary(context, serverId) }
        }
    }

    /**
     * The deferred update. Every post it covers is at least a moment old by now, so the tray has it:
     * a conversation the tray does not list was tapped open or dismissed, and the post records must not
     * bring it back into a summary without children.
     */
    internal fun flushPendingSummary(context: Context, serverId: String) {
        runCatching {
            synchronized(groupingLock) {
                // Already posted by a later membership change, or cancelled with the last conversation.
                if (serverId !in pendingSummaryServers) return@synchronized
                pendingSummaryServers.remove(serverId)
                val conversationsInTray = trayConversationIds(activeUnreadNotifications(context))
                val keptByGrace = recentPosts.retainOnly(conversationsInTray, graceMillis = FLUSH_RETAIN_GRACE_MILLIS)
                refreshServerSummary(context, serverId, SummaryRefresh.Now)
                // A post too young to judge yet (or shed by the rate limit): one more look later.
                for (pendingServerId in keptByGrace) deferSummary(context, pendingServerId)
            }
        }
    }

    /**
     * How young a post absent from the tray may be and still count in a deferred update. A deferred
     * update covers posts already in the tray (same conversations), and a new conversation posts its
     * summary at once, so only a post that has not reached the tray in that short time is spared.
     */
    private const val FLUSH_RETAIN_GRACE_MILLIS = 250L

    private fun trayConversationIds(active: List<StatusBarNotification>): Set<Int> =
        active.filter { it.tag == AndroidUnreadNotificationGrouping.CONVERSATION_TAG }.map { it.id }.toSet()

    /**
     * The channel a server's summary is posted on: the channel of its newest conversation that is not
     * blocked. A summary on a channel the user turned off (only "mentions" left on, say) is dropped by
     * Android, and the group falls apart; its conversations' channels were enabled when they posted.
     */
    internal fun summaryChannelId(context: Context, entries: List<UnreadSummaryEntry>): String =
        entries
            .map { it.channelId }
            .filter { it.isNotBlank() }
            .distinct()
            .firstOrNull { !isNotificationChannelBlocked(context, it) }
            ?: UNREAD_GENERAL_NOTIFICATION_CHANNEL_ID

    /**
     * The conversations of [serverId] still in the tray, newest first, plus the ones this process
     * posted a moment ago that `activeNotifications` may not list yet, minus the ones it cleared a moment
     * ago that it may still list ([UnreadRecentPosts]).
     */
    private fun unreadSummaryEntries(context: Context, serverId: String): List<UnreadSummaryEntry> {
        val fromTray =
            activeUnreadNotifications(context)
                .filter {
                    it.tag == AndroidUnreadNotificationGrouping.CONVERSATION_TAG &&
                        it.notification.extras?.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_ID) == serverId &&
                        // Cancelled a moment ago: the tray may not have caught up.
                        !recentPosts.isCleared(it.id)
                }
                .associate { posted ->
                    val extras = posted.notification.extras
                    val history = UnreadConversationHistory.readFrom(extras)
                    posted.id to
                        UnreadSummaryEntry(
                            notificationId = posted.id,
                            channelId = NotificationCompat.getChannelId(posted.notification).orEmpty(),
                            title = extras.getString(AndroidUnreadNotificationGrouping.EXTRA_TITLE).orEmpty(),
                            latestLine = history.lines.lastOrNull().orEmpty(),
                            count = history.count.coerceAtLeast(1),
                            postedAtMillis = posted.notification.`when`
                        )
                }
        return (fromTray + recentPosts.entries(serverId)).values.sortedByDescending { it.postedAtMillis }
    }

    private fun activeUnreadNotifications(context: Context): List<StatusBarNotification> =
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .activeNotifications
                .filter {
                    it.tag == AndroidUnreadNotificationGrouping.CONVERSATION_TAG ||
                        it.tag == AndroidUnreadNotificationGrouping.SUMMARY_TAG
                }
        }.getOrDefault(emptyList())

    /**
     * The tray notification of one conversation, with [request] as its newest message. Both entry
     * points build it here: FCM (`RaftFirebaseMessagingService`, possibly already redacted by the
     * server) and the local realtime path (`AndroidAppScopeRealtimeHostContext`, raw content, the only
     * path on devices without GMS). [previewMode] is the device's choice from
     * [KeyValuePushPreviewModePreferenceStore]; the title, the collapsed text and every listed message
     * use resolved text, and the lock screen gets a content-free public version.
     *
     * [previous] is what the conversation's notification already shows. With one message the
     * expanded view is that message's big text; with more it lists the recent ones (InboxStyle), the
     * collapsed text stays the newest, and the count is the notification number and the summary line.
     * The history rides in the extras for the next message ([UnreadConversationHistory]).
     */
    internal fun buildUnreadNotification(
        context: Context,
        channelId: String,
        request: JSONObject,
        previewMode: PushPreviewMode,
        contentIntent: PendingIntent?,
        strings: PushPreviewStrings = AndroidResourcePushPreviewStrings(context),
        whenMillis: Long = System.currentTimeMillis(),
        previous: UnreadConversationHistory = UnreadConversationHistory.EMPTY,
        deleteIntent: PendingIntent? = null
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
        val history = previous.appended(request.optString("messageId"), text.body)
        val countText =
            appContext.resources.getQuantityString(R.plurals.notification_unread_message_count, history.count, history.count)
        val serverId = request.optString("serverId").trim()
        val publicVersion =
            NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(text.publicTitle)
                .setContentText(if (history.count > 1) countText else text.publicBody)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setWhen(whenMillis)
                .setShowWhen(true)
                .build()
        val style =
            if (history.count > 1) {
                NotificationCompat.InboxStyle()
                    .setSummaryText(countText)
                    .also { inbox -> history.lines.forEach { inbox.addLine(it) } }
            } else {
                NotificationCompat.BigTextStyle().bigText(text.body)
            }
        val extras =
            Bundle().apply {
                putString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_ID, serverId)
                putString(AndroidUnreadNotificationGrouping.EXTRA_CONVERSATION_KEY, AndroidUnreadNotificationGrouping.conversationKey(request))
                putString(AndroidUnreadNotificationGrouping.EXTRA_TITLE, text.title)
                history.writeTo(this)
            }
        return NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(style)
            .setNumber(history.count)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setGroup(AndroidUnreadNotificationGrouping.groupKey(serverId))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setWhen(whenMillis)
            .setShowWhen(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setAutoCancel(true)
            .addExtras(extras)
            .build()
    }

    /**
     * The group summary of one server: what Android shows for the bundle where it renders one (the
     * collapsed group on older launchers, wearables), and what keeps the server's conversations
     * together. It lists each conversation with its newest message, newest first, and counts every
     * unread message across them. It never alerts (`GROUP_ALERT_CHILDREN`): the conversation that
     * changed already did. [channelId] is one of its conversations' channels ([summaryChannelId]).
     */
    internal fun buildUnreadGroupSummary(
        context: Context,
        serverId: String,
        channelId: String,
        entries: List<UnreadSummaryEntry>,
        contentIntent: PendingIntent?
    ): Notification {
        val appContext = context.applicationContext
        val total = entries.sumOf { it.count }
        val countText =
            appContext.resources.getQuantityString(R.plurals.notification_unread_message_count, total, total)
        val appName = appContext.getString(R.string.app_name)
        val inbox =
            NotificationCompat.InboxStyle()
                .setBigContentTitle(appName)
                .setSummaryText(countText)
        entries.take(AndroidUnreadNotificationGrouping.MAX_LINES).forEach { entry ->
            inbox.addLine(listOf(entry.title, entry.latestLine).filter { it.isNotBlank() }.joinToString(": "))
        }
        val publicVersion =
            NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(appName)
                .setContentText(countText)
                .build()
        return NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(appName)
            .setContentText(countText)
            .setStyle(inbox)
            .setNumber(total)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setGroup(AndroidUnreadNotificationGrouping.groupKey(serverId))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setWhen(entries.maxOfOrNull { it.postedAtMillis } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .addExtras(Bundle().apply { putString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_ID, serverId) })
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

    // Lock-free: posting can run on the main thread, where waiting on the session lock can
    // deadlock (#3823).
    private fun currentSelectedServerId(context: Context): String? =
        runCatching {
            KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(context.applicationContext))
                .readCommittedSessionSnapshot()
                ?.currentServerId
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

    /** A tap on a server's summary opens the app where it was: the summary has no single conversation. */
    private fun unreadSummaryContentIntent(context: Context, summaryId: Int): PendingIntent? {
        val intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_RAFT_APP_OPEN_SOURCE, RAFT_APP_OPEN_SOURCE_PUSH_NOTIFICATION)
            } ?: return null
        return PendingIntent.getActivity(
            context,
            summaryId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Fired when the user dismisses conversation [notificationId], so it is forgotten and its server's
     * summary is recounted or removed. One per conversation (request code = its id).
     */
    private fun unreadNotificationDismissIntent(context: Context, notificationId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, RaftUnreadNotificationDismissReceiver::class.java)
                .putExtra(RaftUnreadNotificationDismissReceiver.EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
