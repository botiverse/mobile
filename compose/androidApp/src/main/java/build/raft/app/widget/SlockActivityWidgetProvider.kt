package build.raft.app.widget

import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.MainActivity
import build.raft.app.R
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.auth.androidMMKVActivityUnreadKeyValueStore
import build.raft.app.core.data.auth.androidMMKVApiEnvironmentKeyValueStore
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.core.data.network.RealSlockNetworkClientFactory
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxFilter
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.ProcessActivityUnreadProjectionCache
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealInboxRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealSavedRepository
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.toConversationSummary
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForRenderedAvatar
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class SlockActivityWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderWidgetsFromCache(context, appWidgetManager, appWidgetIds)
        refreshWidgets(context.applicationContext, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val cache = SlockWidgetCache(context)
        val config = SlockWidgetServerConfigStore(context)
        appWidgetIds.forEach { appWidgetId ->
            cache.clear(appWidgetId)
            config.clear(appWidgetId)
        }
        super.onDeleted(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val appWidgetIds = intent.requestedWidgetIds(
                appContext,
                appWidgetManager,
                SlockActivityWidgetProvider::class.java
            )
            renderWidgetsFromCache(appContext, appWidgetManager, appWidgetIds, refreshing = true)
            refreshWidgets(appContext, appWidgetManager, appWidgetIds)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "build.raft.app.widget.ACTION_REFRESH_STATUS_WIDGET"
        private const val TAG = "SlockStatusWidget"
        private const val MAX_HOME_ROWS = 3
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refreshWidget(context: Context, appWidgetId: Int) {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val appWidgetIds = intArrayOf(appWidgetId)
            renderWidgetsFromCache(appContext, appWidgetManager, appWidgetIds, refreshing = true)
            refreshWidgets(appContext, appWidgetManager, appWidgetIds)
        }

        fun refreshAllWidgets(context: Context) {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val appWidgetIds =
                appWidgetManager.getAppWidgetIds(
                    ComponentName(appContext, SlockActivityWidgetProvider::class.java)
                )
            renderWidgetsFromCache(appContext, appWidgetManager, appWidgetIds, refreshing = true)
            refreshWidgets(appContext, appWidgetManager, appWidgetIds)
        }

        private fun refreshWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            if (appWidgetIds.isEmpty()) return
            scope.launch {
                val cache = SlockWidgetCache(context)
                appWidgetIds.forEach { appWidgetId ->
                    val state = loadWidgetState(context, appWidgetId, cache.read(appWidgetId))
                    cache.write(appWidgetId, state)
                    withContext(Dispatchers.Main) {
                        appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, appWidgetId, state))
                    }
                }
            }
        }

        private suspend fun loadWidgetState(
            context: Context,
            appWidgetId: Int,
            previous: SlockWidgetState
        ): SlockWidgetState {
            val storedSessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(context))
            val session = storedSessionStore.readSession()
                ?: return SlockWidgetState.SignedOut
            val selectedServer = SlockWidgetServerConfigStore(context).resolveServer(appWidgetId, session)
                ?: return SlockWidgetState.Error(
                    title = "Choose a server",
                    detail = "Tap and hold the widget, then edit it to select a server.",
                    lastUpdatedAtMillis = previous.lastUpdatedAtMillis
                )
            val scopedSession = session.copy(
                selectedServerId = selectedServer.id,
                selectedServerSlug = selectedServer.slug
            )
            val persistedActivityUnread =
                ProcessActivityUnreadProjectionCache(androidMMKVActivityUnreadKeyValueStore(context)).read(
                    environmentId =
                        SharedApiEnvironmentStore(androidMMKVApiEnvironmentKeyValueStore(context))
                            .current()
                            .apiBaseUrl,
                    accountId = session.user.id,
                    serverId = selectedServer.id
                )
            val sessionStore = InMemorySlockAuthSessionStore(scopedSession)
            val serverId = ServerId(selectedServer.id)
            val networkClient =
                RealSlockNetworkClientFactory.create(sessionStore, baseUrl = AndroidAuthApiFactory.currentBaseUrl(context))
            suspend fun fetchHome(): DataResult<HomeSnapshot>? =
                runCatching { RealChannelRepository(sessionStore, networkClient).getHome(serverId) }
                    .onFailure { throwable -> SlockAppLog.w(TAG, "home refresh failed: ${throwable.javaClass.simpleName}") }
                    .getOrNull()
            var homeResult = fetchHome() ?: return previous.asStale("Could not refresh")
            var attempt = 1
            while (attempt < WIDGET_HOME_ATTEMPTS) {
                val errorCode = (homeResult as? DataResult.Error)?.error?.code ?: break
                if (!isWidgetTransientCancel(errorCode)) break
                // The first refresh after the configure screen closes races the app's background edge, which
                // retires in-flight reads (status 0, "lifecycle_retired"); a read is safe to repeat.
                SlockAppLog.d(TAG, "home refresh cancelled code=$errorCode attempt=$attempt; retrying")
                delay(WIDGET_HOME_RETRY_DELAY_MS)
                attempt++
                homeResult = fetchHome() ?: return previous.asStale("Could not refresh")
            }
            val home = when (homeResult) {
                is DataResult.Content -> homeResult.data
                is DataResult.Empty -> return previous.asStale("No conversations yet")
                is DataResult.Error -> return previous.asStale(homeResult.error.code)
                is DataResult.NoPermission -> return previous.asStale(homeResult.blocker.code)
            }
            val inboxUnread =
                when (val result = runCatching {
                    RealInboxRepository(sessionStore, networkClient).loadInitial(serverId, InboxFilter.All, limit = 1)
                }.getOrNull()) {
                    is DataResult.Content -> result.data.totalUnreadCount
                    is DataResult.Empty -> 0
                    else -> persistedActivityUnread?.totalUnreadCount ?: previous.activityUnreadCount
                }
            val savedCount =
                when (val result = runCatching {
                    RealSavedRepository(sessionStore, networkClient).loadInitial(serverId, limit = 1)
                }.getOrNull()) {
                    is DataResult.Content -> result.data.totalCount
                    is DataResult.Empty -> 0
                    else -> previous.savedCount
                }
            val memberProfiles =
                when (val result = runCatching {
                    RealMemberRepository(sessionStore, networkClient).getMembers(DataRequestPriority.Visible, serverId)
                }.getOrNull()) {
                    is DataResult.Content -> result.data.members
                    else -> emptyList()
                }
            val membersById = memberProfiles.associateBy { it.id.value }
            return SlockWidgetState.Content(
                serverName = home.server.name,
                unreadCount = home.unreadTotal,
                activityUnreadCount = inboxUnread,
                savedCount = savedCount,
                rows = home.toWidgetRows(context, membersById),
                lastUpdatedAtMillis = System.currentTimeMillis(),
                staleReason = null
            )
        }

        private fun renderWidgetsFromCache(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            refreshing: Boolean = false
        ) {
            val cache = SlockWidgetCache(context)
            appWidgetIds.forEach { widgetId ->
                appWidgetManager.updateAppWidget(
                    widgetId,
                    buildRemoteViews(context, widgetId, cache.read(widgetId, refreshing = refreshing))
                )
            }
        }

        private fun buildRemoteViews(context: Context, appWidgetId: Int, state: SlockWidgetState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.slock_activity_widget)
            views.setOnClickPendingIntent(R.id.slock_widget_root, launchAppIntent(context))
            views.setOnClickPendingIntent(R.id.slock_widget_refresh, refreshIntent(context, appWidgetId))
            views.setTextViewText(R.id.slock_widget_title, state.title)
            views.setTextViewText(R.id.slock_widget_detail, state.detail)
            views.setTextViewText(R.id.slock_widget_updated, state.updatedLabel(context))
            views.setTextViewText(R.id.slock_widget_refresh, if (state.refreshing) "Refreshing" else "Refresh")
            val showStats = state is SlockWidgetState.Content
            views.setViewVisibility(R.id.slock_widget_stats, if (showStats) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.slock_widget_rows, if (showStats) View.VISIBLE else View.GONE)
            if (state is SlockWidgetState.Content) {
                views.setTextViewText(R.id.slock_widget_activity_value, state.activityUnreadCount.displayCount())
                views.setTextViewText(R.id.slock_widget_saved_value, state.savedCount.displayCount())
                views.setTextViewText(
                    R.id.slock_widget_detail,
                    if (state.rows.isEmpty()) "No unread rows" else "Rows with messages"
                )
                bindHomeRows(views, state.rows)
            }
            return views
        }

        private fun bindHomeRows(views: RemoteViews, rows: List<SlockWidgetHomeRow>) {
            val rowIds =
                intArrayOf(
                    R.id.slock_widget_row_1,
                    R.id.slock_widget_row_2,
                    R.id.slock_widget_row_3
                )
            val avatarTextIds =
                intArrayOf(
                    R.id.slock_widget_row_1_avatar,
                    R.id.slock_widget_row_2_avatar,
                    R.id.slock_widget_row_3_avatar
                )
            val avatarImageIds =
                intArrayOf(
                    R.id.slock_widget_row_1_avatar_image,
                    R.id.slock_widget_row_2_avatar_image,
                    R.id.slock_widget_row_3_avatar_image
                )
            val titleIds =
                intArrayOf(
                    R.id.slock_widget_row_1_title,
                    R.id.slock_widget_row_2_title,
                    R.id.slock_widget_row_3_title
                )
            val detailIds =
                intArrayOf(
                    R.id.slock_widget_row_1_detail,
                    R.id.slock_widget_row_2_detail,
                    R.id.slock_widget_row_3_detail
                )
            val countIds =
                intArrayOf(
                    R.id.slock_widget_row_1_count,
                    R.id.slock_widget_row_2_count,
                    R.id.slock_widget_row_3_count
                )
            rowIds.indices.forEach { index ->
                val row = rows.getOrNull(index)
                views.setViewVisibility(rowIds[index], if (row == null) View.GONE else View.VISIBLE)
                if (row != null) {
                    views.setTextViewText(titleIds[index], row.title)
                    views.setTextViewText(detailIds[index], row.detail)
                    views.setTextViewText(countIds[index], row.unreadCount.displayCount())
                    val bitmap = row.avatarImagePath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
                    if (bitmap != null) {
                        views.setViewVisibility(avatarImageIds[index], View.VISIBLE)
                        views.setViewVisibility(avatarTextIds[index], View.GONE)
                        views.setImageViewBitmap(avatarImageIds[index], bitmap)
                    } else {
                        views.setViewVisibility(avatarImageIds[index], View.GONE)
                        views.setViewVisibility(avatarTextIds[index], View.VISIBLE)
                        views.setTextViewText(avatarTextIds[index], row.avatarLabel.take(2).uppercase())
                    }
                }
            }
        }

        private fun launchAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun refreshIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, SlockActivityWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            return PendingIntent.getBroadcast(
                context,
                1_000 + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun Intent.requestedWidgetIds(
            context: Context,
            appWidgetManager: AppWidgetManager,
            providerClass: Class<out AppWidgetProvider>
        ): IntArray {
            val requestedId = getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            return if (requestedId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                intArrayOf(requestedId)
            } else {
                appWidgetManager.getAppWidgetIds(ComponentName(context, providerClass))
            }
        }

        private fun Int.displayCount(): String =
            if (this > 99) "99+" else this.coerceAtLeast(0).toString()

        private suspend fun build.raft.app.data.HomeSnapshot.toWidgetRows(
            context: Context,
            membersById: Map<String, MemberProfile>
        ): List<SlockWidgetHomeRow> =
            (channels + directMessages)
                .filter { it.unreadCount > 0 || it.mentionCount > 0 }
                .sortedWith(
                    compareByDescending<HomeConversationRow> { it.mentionCount }
                        .thenByDescending { it.unreadCount }
                        .thenBy { it.name.lowercase() }
                )
                .take(MAX_HOME_ROWS)
                .map { conversation ->
                    conversation.toConversationSummary().toWidgetRow(
                        context = context,
                        member = conversation.presenceMemberId?.value?.let(membersById::get)
                    )
                }

        private suspend fun ConversationSummary.toWidgetRow(context: Context, member: MemberProfile?): SlockWidgetHomeRow {
            val title = name.ifBlank { "Conversation" }
            val detail =
                lastMessagePreview?.takeIf { it.isNotBlank() }
                    ?: lastMessageSenderName?.takeIf { it.isNotBlank() }?.let { "Latest from $it" }
                    ?: subtitle?.takeIf { it.isNotBlank() }
                    ?: when (kind) {
                        ConversationKind.DirectMessage -> "Direct message"
                        ConversationKind.Thread -> "Thread"
                        else -> "Channel"
                    }
            val avatarWireValue = member?.avatarWireValueForRenderedAvatar()
            val imagePath = avatarWireValue?.let { context.cacheSlockWidgetAvatarPath(it) }
            return SlockWidgetHomeRow(
                title = title,
                detail = detail,
                avatarLabel =
                    member?.avatarLabel?.takeIf { it.isNotBlank() }
                        ?: if (kind == ConversationKind.DirectMessage) title.take(1).uppercase() else "#",
                unreadCount = maxOf(unreadCount, mentionCount),
                avatarImagePath = imagePath
            )
        }

        private fun SlockWidgetState.updatedLabel(context: Context): String {
            if (refreshing) return "Updating..."
            if (lastUpdatedAtMillis <= 0L) return "Open Raft to start"
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastUpdatedAtMillis))
            return if (this is SlockWidgetState.Content && staleReason != null) {
                "Stale at $time"
            } else {
                context.getString(R.string.slock_status_widget_updated, time)
            }
        }
    }
}

private const val WIDGET_HOME_ATTEMPTS = 3
private const val WIDGET_HOME_RETRY_DELAY_MS = 1_000L

/** A transport cancellation (HTTP status 0, e.g. "home_http_0") rather than a server answer. */
internal fun isWidgetTransientCancel(errorCode: String): Boolean = errorCode.endsWith("_http_0")
