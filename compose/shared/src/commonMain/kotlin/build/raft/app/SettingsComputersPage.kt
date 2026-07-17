package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.data.StoredAuthSession
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Computers is owned by the Web settings surface. Mobile supplies only the
 * selected server identity and auth bootstrap, while the Web MVI/data layer
 * loads and mutates the canonical computer state.
 */
@Composable
internal fun SettingsComputersWebViewContent(
    session: StoredAuthSession?,
    webBaseUrl: String?,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
    onUrlChange: (String) -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    controller: SlockWebViewController = rememberSlockWebViewController(),
    onNavigationStateChange: (SlockWebViewNavigationState) -> Unit = {}
) {
    val webUrl = remember(session, webBaseUrl) {
        settingsComputersWebViewUrl(webBaseUrl, session)
    }
    val injectedJavaScript = remember(session) {
        settingsComputersWebViewInjectedJavaScript(session)
    }
    if (session == null) {
        SlockCenteredLoading(stringResource(MR.strings.common_loading))
        return
    }
    SlockWebView(
        url = webUrl,
        modifier =
            modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .padding(bottom = bottomContentPadding),
        allowNavigation = true,
        allowedNavigationSchemes = setOf("http", "https"),
        domStorageEnabled = true,
        cacheEnabled = true,
        ephemeral = false,
        injectedJavaScript = injectedJavaScript,
        controller = controller,
        onUrlChange = onUrlChange,
        onLoadFailure = onLoadFailure,
        onNavigationStateChange = onNavigationStateChange
    )
}

internal fun settingsComputersWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?
): String {
    val serverSlug =
        session?.selectedServerSlug
            ?: session?.servers?.firstOrNull { it.id == session.currentServerId }?.slug
            ?: session?.servers?.firstOrNull()?.slug
    val path =
        serverSlug
            ?.takeIf { it.isNotBlank() }
            ?.let { "s/$it/computers" }
            ?: "computers"
    return sharedSettingsHostShellWebUrl(webBaseUrl, path)
}

internal fun settingsComputersWebViewInjectedJavaScript(session: StoredAuthSession?): String {
    if (session == null) return ""
    val currentServerSlug =
        session.selectedServerSlug
            ?: session.servers.firstOrNull { it.id == session.currentServerId }?.slug
            ?: session.servers.firstOrNull()?.slug
    val writes =
        buildList {
            add(settingsComputersLocalStorageWrite("slock_access_token", session.accessToken))
            add(settingsComputersLocalStorageWrite("slock_refresh_token", session.refreshToken))
            currentServerSlug?.takeIf { it.isNotBlank() }?.let {
                add(settingsComputersLocalStorageWrite("slock_last_server_slug", it))
            }
        }.joinToString(separator = ";")
    return """
        (function() {
          try {
            $writes;
          } catch (error) {
            console.error("slock_mobile_computers_injected_js_error", error && (error.stack || error.message || error));
          }
        })();
    """.trimIndent()
}

private val settingsComputersWebViewJson = Json

private fun settingsComputersLocalStorageWrite(key: String, value: String): String =
    "window.localStorage.setItem(${settingsComputersWebViewJson.encodeToString(key)}, ${settingsComputersWebViewJson.encodeToString(value)})"
