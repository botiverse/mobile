package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.data.RaftNavDestination
import build.raft.app.data.RaftPageRoute
import build.raft.app.data.RaftProductPageView
import build.raft.app.data.RaftSettingsTab
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * The app's page identities mapped to the registry's `page_viewed` routes and `nav_entry_selected`
 * destinations. Only identity (page name, tab key, surface kind, settings section) is read; no
 * ids or content leave this file.
 */

/** A shell tab (phone bar, wide rail, child tab pages). DMs has no route of its own. */
internal fun appShellTabProductPageView(tab: String): RaftProductPageView? =
    when (tab.trim()) {
        "home" -> RaftProductPageView(RaftPageRoute.Home)
        "activity" -> RaftProductPageView(RaftPageRoute.Activity)
        "tasks" -> RaftProductPageView(RaftPageRoute.Tasks)
        AppShellSearchTabKey -> RaftProductPageView(RaftPageRoute.Search)
        "more" -> RaftProductPageView(RaftPageRoute.More)
        "members" -> RaftProductPageView(RaftPageRoute.Members)
        "settings" -> RaftProductPageView(RaftPageRoute.Settings)
        else -> null
    }

/** The `page_viewed` visit key of a shell tab page: one per shell and tab, across remounts. */
internal fun appShellTabProductPageVisitKey(shellId: String, tab: String): String = "app-shell-tab|$shellId|$tab"

/** The wide rail's inline list panes (Search, Saved). */
internal fun appShellRailInlineProductPageView(key: String?): RaftProductPageView? =
    when (key) {
        AppShellRailSearchKey -> RaftProductPageView(RaftPageRoute.Search)
        AppShellRailSavedKey -> RaftProductPageView(RaftPageRoute.Saved)
        else -> null
    }

/** A shell tab / rail key as a navigation destination: its [RaftAppDestination.navEvent]. */
internal fun appShellNavDestination(key: String): RaftNavDestination? =
    when (val id = key.trim()) {
        // The rail's inline Search (iPhone Duo, no top bar) is the Search destination.
        AppShellRailSearchKey -> RaftNavDestination.Search
        else -> RaftAppDestination.fromId(id)?.navEvent
    }

/** Settings sections the native detail page hosts; developer/diagnostics pages are not product pages. */
internal fun SettingsNativeDetailPage.productSettingsTab(): RaftSettingsTab? =
    when (this) {
        SettingsNativeDetailPage.LanguageRegion -> RaftSettingsTab.LanguageRegion
        SettingsNativeDetailPage.Appearance -> RaftSettingsTab.Appearance
        SettingsNativeDetailPage.Notifications -> RaftSettingsTab.Notifications
        SettingsNativeDetailPage.Mobile -> RaftSettingsTab.Mobile
        SettingsNativeDetailPage.Server -> RaftSettingsTab.Server
        SettingsNativeDetailPage.About -> RaftSettingsTab.About
        // The shared contract has `theme` (SETTINGS_PAGE_TABS); RaftSettingsTab does not mirror it yet,
        // so no page_viewed rather than a wrong tab.
        SettingsNativeDetailPage.Theme,
        SettingsNativeDetailPage.Developer,
        SettingsNativeDetailPage.OpenSourceLicenses,
        SettingsNativeDetailPage.ApiEnvironment,
        SettingsNativeDetailPage.Diagnostics,
        SettingsNativeDetailPage.LocalFeatureOverrides,
        SettingsNativeDetailPage.LocalFeatureOverride -> null
    }

internal fun SettingsWebViewSurface.productPageView(): RaftProductPageView? =
    when (this) {
        SettingsWebViewSurface.Billing -> RaftProductPageView(RaftPageRoute.Settings, RaftSettingsTab.Billing)
        SettingsWebViewSurface.Administration -> RaftProductPageView(RaftPageRoute.Settings, RaftSettingsTab.Administration)
        SettingsWebViewSurface.ImBridges -> RaftProductPageView(RaftPageRoute.Settings, RaftSettingsTab.ImBridges)
        // Settings > Connected apps: the web's Integrations section.
        SettingsWebViewSurface.Marketplace -> RaftProductPageView(RaftPageRoute.Settings, RaftSettingsTab.Integrations)
        SettingsWebViewSurface.Feedback -> RaftProductPageView(RaftPageRoute.Settings, RaftSettingsTab.Feedback)
        SettingsWebViewSurface.Computers -> RaftProductPageView(RaftPageRoute.Computers)
        SettingsWebViewSurface.ReleaseNotes -> RaftProductPageView(RaftPageRoute.ReleaseNotes)
        SettingsWebViewSurface.Onboarding,
        SettingsWebViewSurface.ArtifactViewer -> null
    }

/**
 * The `page_viewed` identity of an independently mounted authenticated page, from its page data.
 * [consumeTaskThread] answers whether a thread page instance was opened from a task.
 */
internal fun authenticatedProductPageView(
    route: AuthenticatedProductRoute,
    params: JSONObject,
    consumeTaskThread: (instanceId: String) -> Boolean,
): RaftProductPageView? =
    when (route) {
        AuthenticatedProductRoute.Thread -> threadProductPageView(params, consumeTaskThread)
        AuthenticatedProductRoute.HomeSearch ->
            if (params.optString(HOME_SEARCH_SELECTED_CHANNEL_ID).isNotBlank()) {
                RaftProductPageView(RaftPageRoute.ConversationSearch)
            } else {
                RaftProductPageView(RaftPageRoute.Search)
            }
        AuthenticatedProductRoute.HomeSaved -> RaftProductPageView(RaftPageRoute.Saved)
        AuthenticatedProductRoute.HomeActivity -> RaftProductPageView(RaftPageRoute.Activity)
        AuthenticatedProductRoute.Members -> RaftProductPageView(RaftPageRoute.Members)
        AuthenticatedProductRoute.MemberDetail ->
            when (memberDetailKindWire(params)) {
                "agent" -> RaftProductPageView(RaftPageRoute.Agent)
                "human", "user" -> RaftProductPageView(RaftPageRoute.Human)
                else -> null
            }
        AuthenticatedProductRoute.SettingsAccount -> RaftProductPageView(RaftPageRoute.Settings, RaftSettingsTab.Account)
        AuthenticatedProductRoute.SettingsHandsFeedback -> RaftProductPageView(RaftPageRoute.Settings, RaftSettingsTab.Feedback)
        AuthenticatedProductRoute.SettingsStorage -> RaftProductPageView(RaftPageRoute.Settings, RaftSettingsTab.Storage)
        AuthenticatedProductRoute.SettingsNativeDetail ->
            settingsNativeDetailRoute(params)?.page?.productSettingsTab()?.let { RaftProductPageView(RaftPageRoute.Settings, it) }
        AuthenticatedProductRoute.SettingsWebView -> settingsWebViewSurfaceFromPageData(params)?.productPageView()
        AuthenticatedProductRoute.SettingsComputers ->
            if (settingsComputerRouteIdentityFromPageData(params) != null) {
                RaftProductPageView(RaftPageRoute.Computer)
            } else {
                RaftProductPageView(RaftPageRoute.Computers)
            }
        AuthenticatedProductRoute.ReleaseNotes -> RaftProductPageView(RaftPageRoute.ReleaseNotes)
        AuthenticatedProductRoute.ThreadMembers,
        AuthenticatedProductRoute.ChannelSettings,
        AuthenticatedProductRoute.JointChannelConnections,
        AuthenticatedProductRoute.ThreadForwardBundle,
        AuthenticatedProductRoute.FilePreview,
        AuthenticatedProductRoute.ArtifactViewer -> null
    }

private val productPageRouteJson = Json { ignoreUnknownKeys = true }

private fun JSONObject.nestedPayloadObject(): JsonObject? =
    optString("payload").takeIf { it.isNotBlank() }?.let { text ->
        runCatching { productPageRouteJson.parseToJsonElement(text).jsonObject }.getOrNull()
    }

private fun JsonObject.stringField(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun threadProductPageView(
    params: JSONObject,
    consumeTaskThread: (String) -> Boolean,
): RaftProductPageView? {
    val payload = params.nestedPayloadObject() ?: return null
    val instanceId = params.optString("instanceId").ifBlank { payload.stringField("instanceId").orEmpty() }
    // A task opens as its thread (or its anchored channel before a thread exists): the opener marks it.
    if (consumeTaskThread(instanceId)) return RaftProductPageView(RaftPageRoute.Task)
    // WireThreadLaunchPayload.surfaceKind defaults to a channel; joints render on the channel surface.
    return when (payload.stringField("surfaceKind") ?: RaftThreadSurfaceKind.Channel) {
        RaftThreadSurfaceKind.Channel -> RaftProductPageView(RaftPageRoute.Channel)
        RaftThreadSurfaceKind.DirectMessage -> RaftProductPageView(RaftPageRoute.Dm)
        RaftThreadSurfaceKind.ReplyThread -> RaftProductPageView(RaftPageRoute.Thread)
        else -> null
    }
}

private fun memberDetailKindWire(params: JSONObject): String? =
    params.optString("memberKind").trim().lowercase().takeIf { it.isNotEmpty() }
        ?: params.nestedPayloadObject()?.stringField("memberKind")?.trim()?.lowercase()
