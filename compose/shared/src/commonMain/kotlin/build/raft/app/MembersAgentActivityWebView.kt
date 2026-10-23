package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSkeletonRow
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.StoredAuthSession
import build.raft.designsystem.theme.RaftTheme
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * Agent profile Activity tab as an embedded web page (xxchan, 2026-10-23): the activity view
 * keeps changing on web, so the app shows web's activity-only page
 * (`/s/<slug>/agent/<agentId>/activity`, slock #10937) in a WebView while the profile header and
 * the other tabs stay native. The page is the host-shell Settings embed (`embed=raft-settings-v1`,
 * `shell=host`, `lang=`), authenticated with the same host access token bootstrap and runtime token
 * sync as the Settings WebViews (web maps this path to its `agent` host-token surface).
 */

internal const val AgentActivityWebViewDiagnosticsName = "agent-activity"

/**
 * Everything the Activity tab WebView needs from the profile route. Identity only: the web page
 * loads its own activity data. [webBaseUrl] is the active Developer environment web URL (AGENTS.md
 * Environment Web Link URL Rule) and is reused for the document URL and for recognising
 * same-environment message links.
 */
internal class MembersAgentActivityWebBinding(
    val authStore: RaftAuthSessionStore,
    val processAuthEvents: RaftAuthSessionEventSource,
    val webBaseUrl: String,
    val serverSlug: String?,
    /** Opens a same-environment message permalink natively (thread page). */
    val onOpenMessagePermalink: (String) -> Unit,
    /** Opens an off-site link through the app's link preference (in-app or system browser). */
    val onOpenUrl: (String) -> Unit,
    val log: (String) -> Unit = {},
)

internal fun agentActivityWebViewUrl(
    webBaseUrl: String?,
    serverSlug: String,
    agentId: String,
    localeTag: String,
): String =
    sharedSettingsHostShellWebUrl(
        webUrl = webBaseUrl,
        path = "s/${serverSlug.trim()}/agent/${agentId.trim()}/activity",
        extraQueryParameters = listOf("lang" to localeTag),
    )

/** What the host does with a URL the embedded page moved to (or tried to load). */
internal sealed interface AgentActivityWebNavigation {
    /** Still the activity document (or the auth bootstrap document). */
    data object Stay : AgentActivityWebNavigation

    /** A same-environment message link: open the conversation natively. */
    data class OpenMessage(val permalink: String) : AgentActivityWebNavigation

    /** An off-site http(s) link: hand it to the app's link opener. */
    data class OpenExternal(val url: String) : AgentActivityWebNavigation

    /** Anything else that would leave the activity view (channel, DM, computer...): no native target. */
    data object Ignore : AgentActivityWebNavigation
}

internal fun agentActivityWebNavigation(
    documentUrl: String,
    targetUrl: String,
    configuredWebUrl: String?,
): AgentActivityWebNavigation {
    val target = targetUrl.trim()
    if (target.isEmpty() || target == "about:blank") return AgentActivityWebNavigation.Stay
    val document = agentActivityWebUrlParts(documentUrl) ?: return AgentActivityWebNavigation.Ignore
    val parts = agentActivityWebUrlParts(target) ?: return AgentActivityWebNavigation.Ignore
    if (parts.origin != document.origin) {
        return if (parts.scheme == "http" || parts.scheme == "https") {
            AgentActivityWebNavigation.OpenExternal(target)
        } else {
            AgentActivityWebNavigation.Ignore
        }
    }
    if (parts.path == AgentActivityWebBootstrapPath) return AgentActivityWebNavigation.Stay
    if (parts.path == document.path && parts.query == document.query) return AgentActivityWebNavigation.Stay
    if (target.toRaftMessagePermalinkTarget(configuredWebUrl) != null) {
        return AgentActivityWebNavigation.OpenMessage(target)
    }
    return AgentActivityWebNavigation.Ignore
}

private const val AgentActivityWebBootstrapPath = "/__raft_mobile_bootstrap__"

private data class AgentActivityWebUrlParts(
    val scheme: String,
    val origin: String,
    val path: String,
    val query: String,
)

private fun agentActivityWebUrlParts(url: String): AgentActivityWebUrlParts? {
    val raw = url.trim().substringBefore('#')
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = raw.substring(0, schemeEnd).lowercase()
    val rest = raw.substring(schemeEnd + 3)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd).lowercase()
    if (authority.isEmpty()) return null
    val pathAndQuery = rest.substring(authorityEnd)
    val path = pathAndQuery.substringBefore('?').trimEnd('/').ifEmpty { "/" }
    val query = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
    return AgentActivityWebUrlParts(scheme = scheme, origin = "$scheme://$authority", path = path, query = query)
}

/**
 * Document-start script: the Settings auth bootstrap (host access token + binding), the app's
 * resolved light/dark mode as web's device theme preference, and page CSS that turns off text
 * selection and the iOS touch callout (long-press menu) for this read-only embed.
 */
internal fun agentActivityWebViewInjectedJavaScript(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding?,
    dark: Boolean,
): String {
    val authWrites = settingsWebViewAuthStorageJavaScript(session, hostBinding)
    val themeKey = Json.encodeToString(AgentActivityWebThemeStorageKey)
    val themeValue = Json.encodeToString(agentActivityWebThemePreferenceJson(dark))
    return """
        (function() {
          try {
            $authWrites;
            window.localStorage.setItem($themeKey, $themeValue);
          } catch (error) {
            console.error("slock_mobile_${AgentActivityWebViewDiagnosticsName}_injected_js_error", error && (error.stack || error.message || error));
          }
          try {
            var styleId = "raft-host-no-select";
            var css = "html,body,body *{-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important;}" +
              "input,textarea,[contenteditable],[contenteditable] *{-webkit-user-select:text!important;user-select:text!important;}";
            var apply = function() {
              if (document.getElementById(styleId)) return true;
              var parent = document.head || document.documentElement;
              if (!parent) return false;
              var style = document.createElement("style");
              style.id = styleId;
              style.textContent = css;
              parent.appendChild(style);
              return true;
            };
            if (!apply()) {
              var observer = new MutationObserver(function() { if (apply()) observer.disconnect(); });
              observer.observe(document, { childList: true, subtree: true });
            }
            document.addEventListener("DOMContentLoaded", apply);
          } catch (_) {}
        })();
    """.trimIndent()
}

/** Web `APP_THEME_PREFERENCES_STORAGE_KEY` (packages/web/src/theme/appTheme.ts). */
internal const val AgentActivityWebThemeStorageKey = "slock-theme-preferences-v2"

internal fun agentActivityWebThemePreferenceJson(dark: Boolean): String =
    if (dark) "{\"mode\":\"dark\"}" else "{\"mode\":\"light\"}"

/**
 * The Activity tab body. Composed as soon as the profile's tab pager composes its pages (the pager
 * keeps every page alive), so the page loads while another tab is showing and survives tab
 * switches; it is released only with the profile. A native skeleton covers it until the document
 * commits, over the app canvas colour, so there is no white flash.
 */
@Composable
internal fun MembersAgentActivityWebView(
    binding: MembersAgentActivityWebBinding,
    agentId: String,
    modifier: Modifier = Modifier,
) {
    val authSnapshot = rememberSettingsWebViewAuthSnapshot(binding.authStore, binding.processAuthEvents)
    val session = authSnapshot.session
    val localeTag = rememberStandaloneSettingsLocaleTag(enabled = true)
    val serverSlug = binding.serverSlug?.takeIf { it.isNotBlank() } ?: standaloneSettingsServerSlug(session)
    val webUrl =
        remember(binding.webBaseUrl, serverSlug, agentId, localeTag) {
            serverSlug?.let { agentActivityWebViewUrl(binding.webBaseUrl, it, agentId, localeTag) }
        }
    val controller = rememberRaftWebViewController()
    val hostAuthBinding = rememberSettingsWebViewHostAuthBinding(authSnapshot, controller)
    val dark = RaftAppThemeState.isDark
    val injectedJavaScript =
        remember(session, hostAuthBinding, dark) {
            agentActivityWebViewInjectedJavaScript(session, hostAuthBinding, dark)
        }
    // The theme is written at document start, so a theme change needs a new document (KB:
    // webview-document-start-injection-pitfalls §1); it is part of the WebView generation.
    val generationKey = hostAuthBinding?.webViewGeneration to dark
    var documentReady by remember(webUrl, generationKey) { mutableStateOf(false) }
    var handledLeaveUrl by remember(webUrl, generationKey) { mutableStateOf<String?>(null) }
    val canvas = RaftTheme.colors.layerCanvas

    if (session == null || webUrl == null) {
        AgentActivityWebViewSkeleton(modifier)
        return
    }

    fun dispatch(navigation: AgentActivityWebNavigation) {
        when (navigation) {
            is AgentActivityWebNavigation.OpenMessage -> binding.onOpenMessagePermalink(navigation.permalink)
            is AgentActivityWebNavigation.OpenExternal -> binding.onOpenUrl(navigation.url)
            AgentActivityWebNavigation.Ignore,
            AgentActivityWebNavigation.Stay -> Unit
        }
    }

    SettingsWebViewContent(
        url = webUrl,
        modifier = modifier,
        // Only the activity document itself may load; other documents are blocked and routed below.
        allowNavigation = false,
        injectedJavaScript = injectedJavaScript,
        controller = controller,
        generationKey = generationKey,
        onLoadFailure = { reason -> binding.log("agent_activity_webview_load_failure reason=$reason") },
        onNavigationBlocked = { url ->
            val navigation = agentActivityWebNavigation(webUrl, url, binding.webBaseUrl)
            binding.log("agent_activity_webview_navigation_blocked kind=${navigation.logKind()}")
            dispatch(navigation)
        },
        onNavigationStateChange = { update ->
            // In-page (history API) navigation cannot be blocked by the native fence: when the
            // page routes away from the activity view, return it to the activity view and open
            // the target natively when there is a native destination.
            val url = update.state.url
            val navigation = agentActivityWebNavigation(webUrl, url, binding.webBaseUrl)
            if (navigation == AgentActivityWebNavigation.Stay) {
                handledLeaveUrl = null
            } else if (handledLeaveUrl != url) {
                handledLeaveUrl = url
                binding.log(
                    "agent_activity_webview_left_activity kind=${navigation.logKind()} canGoBack=${update.state.canGoBack}"
                )
                if (update.state.canGoBack) controller.goBack()
                dispatch(navigation)
            }
        },
        onDocumentReadyChange = { documentReady = it },
        backgroundColor = canvas,
        paintNativeBackground = true,
        suppressLongPress = true,
        loadingCover = { AgentActivityWebViewSkeleton() },
    )

    SettingsWebViewAccessTokenSyncEffect(
        snapshot = authSnapshot,
        documentReady = documentReady,
        controller = controller,
        binding = hostAuthBinding,
        surface = null,
        trustedDocumentUrl = webUrl,
        logger = binding.log,
        diagnosticsName = AgentActivityWebViewDiagnosticsName,
    )
}

private fun AgentActivityWebNavigation.logKind(): String =
    when (this) {
        AgentActivityWebNavigation.Stay -> "stay"
        is AgentActivityWebNavigation.OpenMessage -> "message"
        is AgentActivityWebNavigation.OpenExternal -> "external"
        AgentActivityWebNavigation.Ignore -> "ignored"
    }

/** Activity-list placeholder in raft-ui skeleton blocks, on the app canvas. */
@Composable
internal fun AgentActivityWebViewSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RaftTheme.colors.layerCanvas)
                .padding(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Lg),
    ) {
        AgentActivityWebViewSkeletonLineWidths.forEach { widths ->
            RaftSkeletonRow(lineWidths = widths, modifier = Modifier.width(320.dp))
        }
    }
}

private val AgentActivityWebViewSkeletonLineWidths =
    listOf(
        listOf(72.dp, 220.dp),
        listOf(72.dp, 180.dp),
        listOf(72.dp, 240.dp),
        listOf(72.dp, 150.dp),
        listOf(72.dp, 200.dp),
        listOf(72.dp, 168.dp),
    )
