package ai.slock.android.widget

import ai.slock.android.MainActivity
import ai.slock.android.R
import ai.slock.android.core.data.auth.androidMMKVAuthKeyValueStore
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import ai.slock.android.core.data.network.RealSlockNetworkClientFactory
import ai.slock.compose.data.DataRequestPriority
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.KuiklyMMKVAuthSessionStore
import ai.slock.compose.data.RealMemberRepository
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class SlockAgentStatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderWidgets(context, appWidgetManager, appWidgetIds, SlockAgentWidgetCache(context).read())
        refreshWidgets(context.applicationContext, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val appWidgetIds =
                appWidgetManager.getAppWidgetIds(ComponentName(appContext, SlockAgentStatusWidgetProvider::class.java))
            renderWidgets(appContext, appWidgetManager, appWidgetIds, SlockAgentWidgetCache(appContext).read(refreshing = true))
            refreshWidgets(appContext, appWidgetManager, appWidgetIds)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "ai.slock.android.widget.ACTION_REFRESH_AGENT_STATUS_WIDGET"
        private const val TAG = "SlockAgentWidget"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun refreshWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            if (appWidgetIds.isEmpty()) return
            scope.launch {
                val cache = SlockAgentWidgetCache(context)
                val state = loadWidgetState(context, cache.read())
                cache.write(state)
                withContext(Dispatchers.Main) {
                    renderWidgets(context, appWidgetManager, appWidgetIds, state)
                }
            }
        }

        private suspend fun loadWidgetState(context: Context, previous: SlockAgentWidgetState): SlockAgentWidgetState {
            val sessionStore = KuiklyMMKVAuthSessionStore(androidMMKVAuthKeyValueStore(context))
            val session = sessionStore.readSession()
                ?: return SlockAgentWidgetState(statusLabel = "Sign in to see agent activity")
            val serverId = session.currentServerId?.let(::ServerId)
                ?: return previous.asStale("Choose a server in Raft")
            val networkClient = RealSlockNetworkClientFactory.create(sessionStore)
            val result =
                runCatching {
                    RealMemberRepository(sessionStore, networkClient).getMembers(DataRequestPriority.Visible, serverId)
                }.getOrElse { throwable ->
                    SlockDiagnosticsLogger.w(TAG, "agent refresh failed: ${throwable.javaClass.simpleName}")
                    return previous.asStale("Could not refresh")
                }
            val members = when (result) {
                is DataResult.Content -> result.data.members
                is DataResult.Empty -> emptyList()
                is DataResult.Error -> return previous.asStale(result.error.code)
                is DataResult.NoPermission -> return previous.asStale(result.blocker.code)
            }
            val selected = members.filter { it.kind == MemberKind.Agent }.maxByOrNull { it.agentWidgetRank() }
                ?: return SlockAgentWidgetState(
                    title = "Live Agents",
                    agentName = "No agents yet",
                    avatarLabel = "A",
                    statusLabel = "Create an agent in Raft",
                    statusTone = SlockAgentWidgetTone.Offline,
                    lastUpdatedAtMillis = System.currentTimeMillis()
                )
            return selected.toWidgetState()
        }

        private fun MemberProfile.agentWidgetRank(): Int =
            when (agentStatus) {
                AgentStatus.Working -> 50
                AgentStatus.Thinking -> 45
                AgentStatus.Online -> 40
                AgentStatus.Idle -> 30
                AgentStatus.Error -> 20
                AgentStatus.Disconnected -> 10
                AgentStatus.Offline, null -> if (presence == Presence.Active) 35 else 0
            }

        private fun MemberProfile.toWidgetState(): SlockAgentWidgetState {
            val status = agentStatus
            val label = status?.label ?: roleDescription ?: presence.name
            val tone =
                when (status) {
                    AgentStatus.Working, AgentStatus.Thinking, AgentStatus.Online -> SlockAgentWidgetTone.Active
                    AgentStatus.Idle -> SlockAgentWidgetTone.Idle
                    AgentStatus.Error -> SlockAgentWidgetTone.Error
                    AgentStatus.Disconnected, AgentStatus.Offline, null ->
                        if (presence == Presence.Active) SlockAgentWidgetTone.Active else SlockAgentWidgetTone.Offline
                }
            return SlockAgentWidgetState(
                title = "Live Agents",
                agentName = displayName.ifBlank { handle },
                avatarLabel = avatarLabel.ifBlank { displayName.take(1).uppercase().ifBlank { "A" } },
                statusLabel = label,
                statusTone = tone,
                lastUpdatedAtMillis = System.currentTimeMillis()
            )
        }

        private fun renderWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            state: SlockAgentWidgetState
        ) {
            appWidgetIds.forEach { widgetId ->
                appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context, state))
            }
        }

        private fun buildRemoteViews(context: Context, state: SlockAgentWidgetState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.slock_agent_status_widget)
            views.setOnClickPendingIntent(R.id.slock_agent_widget_root, launchAppIntent(context))
            views.setOnClickPendingIntent(R.id.slock_agent_widget_refresh, refreshIntent(context))
            views.setTextViewText(R.id.slock_agent_widget_title, state.title)
            views.setTextViewText(R.id.slock_agent_widget_refresh, if (state.refreshing) "Refreshing" else "Refresh")
            views.setTextViewText(R.id.slock_agent_widget_avatar, state.avatarLabel.take(2).uppercase())
            views.setTextViewText(R.id.slock_agent_widget_name, state.agentName)
            views.setTextViewText(R.id.slock_agent_widget_status, state.updatedDetail)
            views.setTextViewText(R.id.slock_agent_widget_updated, state.updatedLabel(context))
            views.setInt(R.id.slock_agent_widget_status_dot, "setBackgroundResource", state.statusTone.drawableRes)
            return views
        }

        private val SlockAgentWidgetTone.drawableRes: Int
            get() =
                when (this) {
                    SlockAgentWidgetTone.Active -> R.drawable.slock_widget_status_dot_active
                    SlockAgentWidgetTone.Idle -> R.drawable.slock_widget_status_dot_idle
                    SlockAgentWidgetTone.Error -> R.drawable.slock_widget_status_dot_error
                    SlockAgentWidgetTone.Offline -> R.drawable.slock_widget_status_dot_offline
                }

        private fun SlockAgentWidgetState.updatedLabel(context: Context): String {
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

        private fun refreshIntent(context: Context): PendingIntent {
            val intent = Intent(context, SlockAgentStatusWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(
                context,
                11,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
