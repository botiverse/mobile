package build.raft.app.widget

import build.raft.app.core.data.auth.AndroidMMKVKeyValueStore
import ai.slock.compose.data.SlockKeyValueStore
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthSession
import android.content.Context

data class SlockWidgetServerSelection(
    val serverId: String,
    val serverName: String,
    val serverSlug: String?
)

class SlockWidgetServerConfigStore(context: Context) {
    private val store: SlockKeyValueStore =
        AndroidMMKVKeyValueStore(context.applicationContext, PREFERENCES_NAME)

    fun resolveServer(appWidgetId: Int, session: StoredAuthSession): StoredAuthServer? {
        val selectedId = store.getString(serverIdKey(appWidgetId))?.takeIf { it.isNotBlank() }
        return selectedId?.let { id -> session.servers.firstOrNull { it.id == id } }
            ?: session.currentServerId?.let { id -> session.servers.firstOrNull { it.id == id } }
            ?: session.servers.firstOrNull()
    }

    fun readSelection(appWidgetId: Int, session: StoredAuthSession): SlockWidgetServerSelection? =
        resolveServer(appWidgetId, session)?.toSelection()

    fun saveSelection(appWidgetId: Int, server: StoredAuthServer) {
        store.putString(serverIdKey(appWidgetId), server.id)
        store.putString(serverNameKey(appWidgetId), server.name)
        store.putString(serverSlugKey(appWidgetId), server.slug)
    }

    fun clear(appWidgetId: Int) {
        store.putString(serverIdKey(appWidgetId), null)
        store.putString(serverNameKey(appWidgetId), null)
        store.putString(serverSlugKey(appWidgetId), null)
    }

    private fun StoredAuthServer.toSelection(): SlockWidgetServerSelection =
        SlockWidgetServerSelection(
            serverId = id,
            serverName = name.ifBlank { slug ?: id },
            serverSlug = slug
        )

    private companion object {
        private const val PREFERENCES_NAME = "slock_widget_server_config"

        private fun serverIdKey(appWidgetId: Int): String = "widget:$appWidgetId:serverId"
        private fun serverNameKey(appWidgetId: Int): String = "widget:$appWidgetId:serverName"
        private fun serverSlugKey(appWidgetId: Int): String = "widget:$appWidgetId:serverSlug"
    }
}
