package build.raft.app

import build.raft.app.data.StoredAuthSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class SettingsWebStorageMutation(
    val key: String,
    val value: String?,
)

internal fun settingsWebViewAuthStorageMutations(
    session: StoredAuthSession?,
): List<SettingsWebStorageMutation> =
    buildList {
        add(SettingsWebStorageMutation(key = "slock_refresh_token", value = null))
        session ?: return@buildList
        add(SettingsWebStorageMutation(key = "slock_access_token", value = session.accessToken))
        val currentServerSlug =
            session.selectedServerSlug
                ?: session.servers.firstOrNull { it.id == session.currentServerId }?.slug
                ?: session.servers.firstOrNull()?.slug
        currentServerSlug?.takeIf { it.isNotBlank() }?.let { slug ->
            add(SettingsWebStorageMutation(key = "slock_last_server_slug", value = slug))
        }
    }

internal fun settingsWebViewAuthStorageJavaScript(session: StoredAuthSession?): String =
    settingsWebViewAuthStorageMutations(session).joinToString(separator = ";") { mutation ->
        val encodedKey = settingsWebViewAuthJson.encodeToString(mutation.key)
        mutation.value?.let { value ->
            "window.localStorage.setItem($encodedKey, ${settingsWebViewAuthJson.encodeToString(value)})"
        } ?: "window.localStorage.removeItem($encodedKey)"
    }

private val settingsWebViewAuthJson = Json
