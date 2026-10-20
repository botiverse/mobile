package build.raft.app.widget

import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.MainActivity
import build.raft.app.R
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.core.data.network.RealRaftNetworkClientFactory
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.model.AgentStatus
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class SlockAgentStatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderWidgetsFromCache(context, appWidgetManager, appWidgetIds)
        refreshWidgets(context.applicationContext, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val cache = RaftAgentWidgetCache(context)
        val config = RaftWidgetServerConfigStore(context)
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
                SlockAgentStatusWidgetProvider::class.java
            )
            renderWidgetsFromCache(appContext, appWidgetManager, appWidgetIds, refreshing = true)
            refreshWidgets(appContext, appWidgetManager, appWidgetIds)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "build.raft.app.widget.ACTION_REFRESH_AGENT_STATUS_WIDGET"
        private const val TAG = "SlockAgentWidget"
        private const val MAX_AGENT_ROWS = 3
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
                    ComponentName(appContext, SlockAgentStatusWidgetProvider::class.java)
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
                val cache = RaftAgentWidgetCache(context)
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
            previous: RaftAgentWidgetState
        ): RaftAgentWidgetState {
            val storedSessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(context))
            val session = storedSessionStore.readSession()
                ?: return RaftAgentWidgetState(statusLabel = "Sign in to see agent activity")
            val selectedServer = RaftWidgetServerConfigStore(context).resolveServer(appWidgetId, session)
                ?: return previous.asStale("Edit widget to choose a server")
            val scopedSession = session.copy(
                selectedServerId = selectedServer.id,
                selectedServerSlug = selectedServer.slug
            )
            val sessionStore = InMemoryRaftAuthSessionStore(scopedSession)
            val networkClient =
                RealRaftNetworkClientFactory.create(sessionStore, baseUrl = AndroidAuthApiFactory.currentBaseUrl(context))
            val response =
                runCatching {
                    networkClient.request(DataRequestPriority.Visible, path = "agents")
                }.getOrElse { throwable ->
                    RaftAppLog.w(TAG, "agent refresh failed: ${throwable.javaClass.simpleName}")
                    return previous.asStale("Could not refresh")
                }
            if (!response.isSuccessful) {
                return previous.asStale("http_${response.code}")
            }
            val agents =
                runCatching { response.bodyText?.toAgentWidgetSummaries().orEmpty() }
                    .getOrElse { return previous.asStale("agents_contract") }
            val rows = agents
                .filter { it.hasSignal }
                .sortedWith(
                    compareByDescending<AgentWidgetSummary> { it.sortKey }
                        .thenBy { it.name.lowercase() }
                )
                .take(MAX_AGENT_ROWS)
                .map { it.toWidgetRow(context) }
            if (rows.isEmpty()) {
                return RaftAgentWidgetState(
                    title = "Live Agents",
                    agentName = "No agents yet",
                    avatarLabel = "A",
                    statusLabel = "No live agent activity",
                    statusTone = RaftAgentWidgetTone.Offline,
                    lastUpdatedAtMillis = System.currentTimeMillis()
                )
            }
            return RaftAgentWidgetState(
                title = "Live Agents",
                agentName = rows.first().name,
                avatarLabel = rows.first().avatarLabel,
                statusLabel = rows.first().detail,
                statusTone = rows.first().statusTone,
                rows = rows,
                lastUpdatedAtMillis = System.currentTimeMillis()
            )
        }

        private data class AgentWidgetSummary(
            val id: String,
            val name: String,
            val detail: String,
            val status: AgentStatus?,
            val avatarWireValue: String?,
            val latestAt: String?,
            val sourceIndex: Int
        ) {
            val hasSignal: Boolean
                get() = detail.isNotBlank() || status != null

            val sortKey: String
                get() =
                    listOf(
                        latestAt.orEmpty(),
                        status.rank().toString().padStart(3, '0'),
                        (Int.MAX_VALUE - sourceIndex).toString().padStart(10, '0')
                    ).joinToString("|")
        }

        private fun String.toAgentWidgetSummaries(): List<AgentWidgetSummary> {
            val array = toAgentWidgetArray() ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val name =
                        item.optString("displayName").takeIf { it.isNotBlank() }
                            ?: item.optString("name").takeIf { it.isNotBlank() }
                            ?: id
                    val activityDetail = item.optString("activityDetail").takeIf { it.isNotBlank() }
                    val activity = item.optString("activity").takeIf { it.isNotBlank() }
                    val statusValue = item.optString("status").takeIf { it.isNotBlank() }
                    val status = AgentStatus.fromWireValue(activity ?: statusValue)
                    val runtime =
                        item.optString("runtime").takeIf { it.isNotBlank() }
                            ?: item.optJSONObject("runtimeProfile")
                                ?.optJSONObject("current")
                                ?.optString("runtime")
                                ?.takeIf { it.isNotBlank() }
                    val detail =
                        activityDetail
                            ?: activity?.toAgentStatusLabel()
                            ?: status?.label
                            ?: runtime
                            ?: item.optString("description").takeIf { it.isNotBlank() }
                            ?: "Online"
                    val latestAt =
                        item.optString("updatedAt").takeIf { it.isNotBlank() }
                            ?: item.optString("activityAt").takeIf { it.isNotBlank() }
                            ?: item.optString("lastActivityAt").takeIf { it.isNotBlank() }
                            ?: item.optString("lastSeenAt").takeIf { it.isNotBlank() }
                            ?: item.optString("createdAt").takeIf { it.isNotBlank() }
                    add(
                        AgentWidgetSummary(
                            id = id,
                            name = name,
                            detail = detail,
                            status = status,
                            avatarWireValue = item.optString("avatarUrl").takeIf { it.isNotBlank() }
                                ?: "pixel:robot",
                            latestAt = latestAt,
                            sourceIndex = index
                        )
                    )
                }
            }
        }

        private fun String.toAgentWidgetArray(): JSONArray? =
            runCatching { JSONArray(this) }.getOrNull()
                ?: runCatching {
                    val root = JSONObject(this)
                    root.optJSONArray("agents")
                        ?: root.optJSONArray("items")
                        ?: root.optJSONArray("results")
                        ?: root.optJSONArray("data")
                        ?: root.optJSONObject("data")?.optJSONArray("agents")
                        ?: root.optJSONObject("data")?.optJSONArray("items")
                        ?: root.optJSONObject("data")?.optJSONArray("results")
                }.getOrNull()

        private fun AgentStatus?.rank(): Int =
            when (this) {
                AgentStatus.Working -> 50
                AgentStatus.Thinking -> 45
                AgentStatus.Online -> 40
                AgentStatus.Idle -> 30
                AgentStatus.Error -> 20
                AgentStatus.Disconnected -> 10
                AgentStatus.Offline, null -> 0
            }

        private fun String.toAgentStatusLabel(): String =
            split('_', '-', ' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { token -> token.replaceFirstChar { char -> char.uppercase() } }
                .ifBlank { this }

        private suspend fun AgentWidgetSummary.toWidgetRow(context: Context): RaftAgentWidgetRow {
            val tone =
                when (status) {
                    AgentStatus.Working, AgentStatus.Thinking, AgentStatus.Online -> RaftAgentWidgetTone.Active
                    AgentStatus.Idle -> RaftAgentWidgetTone.Idle
                    AgentStatus.Error -> RaftAgentWidgetTone.Error
                    AgentStatus.Disconnected, AgentStatus.Offline, null ->
                        if (status == null && detail.isNotBlank()) RaftAgentWidgetTone.Active else RaftAgentWidgetTone.Offline
                }
            return RaftAgentWidgetRow(
                name = name,
                detail = detail,
                avatarLabel = name.take(1).uppercase().ifBlank { "A" },
                statusTone = tone,
                avatarImagePath = avatarWireValue?.let { context.cacheRaftWidgetAvatarPath(it) }
            )
        }

        private fun renderWidgetsFromCache(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            refreshing: Boolean = false
        ) {
            val cache = RaftAgentWidgetCache(context)
            appWidgetIds.forEach { widgetId ->
                appWidgetManager.updateAppWidget(
                    widgetId,
                    buildRemoteViews(context, widgetId, cache.read(widgetId, refreshing = refreshing))
                )
            }
        }

        private fun buildRemoteViews(context: Context, appWidgetId: Int, state: RaftAgentWidgetState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.raft_agent_status_widget)
            views.setOnClickPendingIntent(R.id.raft_agent_widget_root, launchAppIntent(context))
            views.setOnClickPendingIntent(R.id.raft_agent_widget_refresh, refreshIntent(context, appWidgetId))
            views.setTextViewText(R.id.raft_agent_widget_title, state.title)
            views.setTextViewText(R.id.raft_agent_widget_refresh, if (state.refreshing) "Refreshing" else "Refresh")
            views.setTextViewText(R.id.raft_agent_widget_updated, state.updatedLabel(context))
            bindAgentRows(views, state)
            return views
        }

        private fun bindAgentRows(views: RemoteViews, state: RaftAgentWidgetState) {
            val rows = state.rows.ifEmpty {
                listOf(
                    RaftAgentWidgetRow(
                        name = state.agentName,
                        detail = state.updatedDetail,
                        avatarLabel = state.avatarLabel,
                        statusTone = state.statusTone
                    )
                )
            }
            val rowIds =
                intArrayOf(
                    R.id.raft_agent_widget_row_1,
                    R.id.raft_agent_widget_row_2,
                    R.id.raft_agent_widget_row_3
                )
            val avatarTextIds =
                intArrayOf(
                    R.id.raft_agent_widget_row_1_avatar,
                    R.id.raft_agent_widget_row_2_avatar,
                    R.id.raft_agent_widget_row_3_avatar
                )
            val avatarImageIds =
                intArrayOf(
                    R.id.raft_agent_widget_row_1_avatar_image,
                    R.id.raft_agent_widget_row_2_avatar_image,
                    R.id.raft_agent_widget_row_3_avatar_image
                )
            val dotIds =
                intArrayOf(
                    R.id.raft_agent_widget_row_1_status_dot,
                    R.id.raft_agent_widget_row_2_status_dot,
                    R.id.raft_agent_widget_row_3_status_dot
                )
            val detailIds =
                intArrayOf(
                    R.id.raft_agent_widget_row_1_detail,
                    R.id.raft_agent_widget_row_2_detail,
                    R.id.raft_agent_widget_row_3_detail
                )
            rowIds.indices.forEach { index ->
                val row = rows.getOrNull(index)
                views.setViewVisibility(rowIds[index], if (row == null) View.GONE else View.VISIBLE)
                if (row != null) {
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
                    views.setInt(dotIds[index], "setBackgroundResource", row.statusTone.drawableRes)
                    views.setTextViewText(detailIds[index], "${row.name} · ${row.detail}")
                }
            }
        }

        private val RaftAgentWidgetTone.drawableRes: Int
            get() =
                when (this) {
                    RaftAgentWidgetTone.Active -> R.drawable.raft_widget_status_dot_active
                    RaftAgentWidgetTone.Idle -> R.drawable.raft_widget_status_dot_idle
                    RaftAgentWidgetTone.Error -> R.drawable.raft_widget_status_dot_error
                    RaftAgentWidgetTone.Offline -> R.drawable.raft_widget_status_dot_offline
                }

        private fun RaftAgentWidgetState.updatedLabel(context: Context): String {
            if (refreshing) return "Updating..."
            if (lastUpdatedAtMillis <= 0L) return "Open Raft to start"
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastUpdatedAtMillis))
            return if (staleReason != null) "Stale at $time" else context.getString(R.string.slock_status_widget_updated, time)
        }

        private fun launchAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            return PendingIntent.getActivity(
                context,
                10,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun refreshIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, SlockAgentStatusWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            return PendingIntent.getBroadcast(
                context,
                2_000 + appWidgetId,
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
    }
}
