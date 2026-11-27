package build.raft.app

import build.raft.app.data.StoredAuthSession
import build.raft.app.thread.key
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class SettingsWebStorageMutation(
    val key: String,
    val value: String?,
)

internal fun settingsWebViewAuthStorageMutations(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding? = null,
): List<SettingsWebStorageMutation> =
    buildList {
        add(SettingsWebStorageMutation(key = "slock_refresh_token", value = null))
        add(
            SettingsWebStorageMutation(
                key = RAFT_HOST_AUTH_BINDING_STORAGE_KEY,
                value = hostBinding?.takeIf { session != null }?.let(::settingsWebViewHostAuthBindingJson),
            )
        )
        add(SettingsWebStorageMutation(key = "slock_access_token", value = session?.accessToken))
        session ?: return@buildList
        val currentServerSlug =
            session.selectedServerSlug
                ?: session.servers.firstOrNull { it.id == session.currentServerId }?.slug
                ?: session.servers.firstOrNull()?.slug
        currentServerSlug?.takeIf { it.isNotBlank() }?.let { slug ->
            add(SettingsWebStorageMutation(key = "slock_last_server_slug", value = slug))
        }
    }

internal fun settingsWebViewAuthStorageJavaScript(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding? = null,
): String =
    settingsWebViewAuthStorageMutations(session, hostBinding).joinToString(separator = ";") { mutation ->
        val encodedKey = settingsWebViewAuthJson.encodeToString(mutation.key)
        mutation.value?.let { value ->
            "window.localStorage.setItem($encodedKey, ${settingsWebViewAuthJson.encodeToString(value)})"
        } ?: "window.localStorage.removeItem($encodedKey)"
    }

internal fun settingsWebViewHostAuthBindingJson(binding: SettingsWebViewHostAuthBinding): String =
    buildString {
        append('{')
        append("\"accountId\":")
        append(settingsWebViewAuthJson.encodeToString(binding.accountId))
        append(",\"serverId\":")
        append(settingsWebViewAuthJson.encodeToString(binding.serverId))
        append(",\"sessionGeneration\":")
        append(binding.sessionGeneration)
        append(",\"webViewGeneration\":")
        append(binding.webViewGeneration)
        append('}')
    }

/**
 * Wraps an auth/session WebView script so it runs only inside a top-level document whose origin
 * matches [webOrigin]. Fails closed (returns empty string) when [webOrigin] has no valid http(s)
 * origin, so third-party redirects and subframes never receive `slock_access_token`.
 */
internal fun settingsWebViewGuardedAuthJavaScript(
    webOrigin: String,
    body: String,
    errorTag: String? = null,
): String {
    val origin = raftWebOriginOf(webOrigin) ?: return ""
    val encodedOrigin = settingsWebViewAuthJson.encodeToString(origin)
    val catchClause =
        if (errorTag != null) {
            val encodedTag = settingsWebViewAuthJson.encodeToString(errorTag)
            "catch (error) { console.error($encodedTag, error && (error.stack || error.message || error)); }"
        } else {
            "catch (_) {}"
        }
    return """
        (function() {
          try {
            if (window.top !== window || window.location.origin !== $encodedOrigin) return;
            $body
          } $catchClause
        })();
    """.trimIndent()
}

internal fun settingsWebViewRuntimeAccessTokenJavaScript(
    accessToken: String,
    accountId: String,
    serverId: String,
    sessionGeneration: Long,
    webViewGeneration: Long,
    sequence: Long,
    webOrigin: String? = null,
): String {
    val normalizedOrigin =
        if (webOrigin != null) {
            raftWebOriginOf(webOrigin) ?: return ""
        } else {
            null
        }
    val encodedAccessToken = settingsWebViewAuthJson.encodeToString(accessToken)
    val encodedAccountId = settingsWebViewAuthJson.encodeToString(accountId)
    val encodedServerId = settingsWebViewAuthJson.encodeToString(serverId)
    val body =
        """
            var key = "slock_access_token";
            var previous = window.localStorage.getItem(key);
            var next = $encodedAccessToken;
            var changed = previous !== next;
            if (changed) window.localStorage.setItem(key, next);
            try {
              window.dispatchEvent(new CustomEvent("raft:host-access-token-updated", {
                detail: {
                  accountId: $encodedAccountId,
                  serverId: $encodedServerId,
                  sessionGeneration: $sessionGeneration,
                  webViewGeneration: $webViewGeneration,
                  sequence: $sequence
                }
              }));
            } catch (_) {}
        """.trimIndent()
    if (normalizedOrigin != null) {
        return settingsWebViewGuardedAuthJavaScript(
            webOrigin = normalizedOrigin,
            body = body,
            errorTag = "slock_mobile_access_token_sync_error",
        )
    }
    return """
        (function() {
          try {
            $body
          } catch (error) {
            console.error("slock_mobile_access_token_sync_error", error && (error.stack || error.message || error));
          }
        })();
    """.trimIndent()
}

private val settingsWebViewAuthJson = Json
internal const val RAFT_HOST_AUTH_BINDING_STORAGE_KEY = "slock_host_auth_binding"
