package ai.slock.android.widget

import ai.slock.android.MainActivity
import ai.slock.android.R
import ai.slock.android.core.data.auth.androidMMKVAuthKeyValueStore
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import ai.slock.android.core.data.network.RealSlockNetworkClientFactory
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.InboxFilter
import ai.slock.compose.data.InMemorySlockAuthSessionStore
import ai.slock.compose.data.KuiklyMMKVAuthSessionStore
import ai.slock.compose.data.RealChannelRepository
import ai.slock.compose.data.RealInboxRepository
import ai.slock.compose.data.RealSavedRepository
import ai.slock.compose.model.ServerId
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        private const val ACTION_REFRESH = "ai.slock.android.widget.ACTION_REFRESH_STATUS_WIDGET"
        private const val TAG = "SlockStatusWidget"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refreshWidget(context: Context, appWidgetId: Int) {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val appWidgetIds = intArrayOf(appWidgetId)
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
            val storedSessionStore = KuiklyMMKVAuthSessionStore(androidMMKVAuthKeyValueStore(context))
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
            val sessionStore = InMemorySlockAuthSessionStore(scopedSession)
            val serverId = ServerId(selectedServer.id)
            val networkClient = RealSlockNetworkClientFactory.create(sessionStore)
            val homeResult =
                runCatching {
                    RealChannelRepository(sessionStore, networkClient).getHome(serverId)
                }.getOrElse { throwable ->
                    SlockDiagnosticsLogger.w(TAG, "home refresh failed: ${throwable.javaClass.simpleName}")
                    return previous.asStale("Could not refresh")
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
                    else -> previous.activityUnreadCount.coerceAtLeast(home.unreadTotal)
                }
            val savedCount =
                when (val result = runCatching {
                    RealSavedRepository(sessionStore, networkClient).loadInitial(serverId, limit = 1)
                }.getOrNull()) {
                    is DataResult.Content -> result.data.totalCount
                    is DataResult.Empty -> 0
                    else -> previous.savedCount
                }
            return SlockWidgetState.Content(
                serverName = home.server.name,
                unreadCount = home.unreadTotal,
                activityUnreadCount = maxOf(home.unreadTotal, inboxUnread),
                savedCount = savedCount,
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
            if (state is SlockWidgetState.Content) {
                views.setTextViewText(R.id.slock_widget_unread_value, state.unreadCount.displayCount())
                views.setTextViewText(R.id.slock_widget_activity_value, state.activityUnreadCount.displayCount())
                views.setTextViewText(R.id.slock_widget_saved_value, state.savedCount.displayCount())
            }
            return views
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
