package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.app.data.AuthenticatedRouteOwner

/*
 * "Open workspaces in separate app windows" (#4484): how a shell page that targets its own window
 * (`slockDocumentShell` + route owner, built by appShellPageDataForServerSelection) reaches the
 * platform.
 *
 * Android opens windows through its page router: RouterModule.openPage starts (or brings to front)
 * the owner's document task, so the page data is the whole request. iOS cannot: its page router
 * pushes into the current window's navigation stack, so the new shell would boot inside the window
 * the user is leaving. There the page data becomes a scene request the host answers once (a scene
 * shows the server, or the request was refused); refused falls back to switching in place, so a
 * pick never leaves the user on the old server.
 */

/** How a server switch that opens another window reaches the platform. */
internal enum class AppShellShellWindowPath {
    /** The host's page router opens the shell page in its own window (Android document task). */
    PageRouter,

    /** The host requests a window-system scene for the route owner and answers the outcome (iOS). */
    HostSceneRequest,
}

internal fun appShellShellWindowPath(hostRequestsScene: Boolean): AppShellShellWindowPath =
    if (hostRequestsScene) AppShellShellWindowPath.HostSceneRequest else AppShellShellWindowPath.PageRouter

/**
 * A scene request: identity (the route owner the window hosts) and intent (the tab it opens on,
 * whether it skips the restored landing). Never product content (AGENTS.md route/pageData rule);
 * the new window's shell loads its server through its own stores.
 */
internal data class AppShellShellWindowRequest(
    val routeOwner: AuthenticatedRouteOwner,
    val initialTab: String?,
    val suppressRestoreLanding: Boolean,
    val source: String,
) {
    fun toBridgeParams(): JSONObject =
        JSONObject()
            .withAuthenticatedRouteOwner(routeOwner)
            .apply {
                initialTab?.let { put(AppShellInitialTabKey, it) }
                put(APP_SHELL_SUPPRESS_RESTORE_LANDING, suppressRestoreLanding)
                put("source", source)
            }
}

/**
 * The scene request for [pageData], or null when it is not a document-shell page with a complete
 * route owner (then there is no window to ask for: switch in place).
 */
internal fun appShellShellWindowRequestOrNull(pageData: JSONObject, source: String): AppShellShellWindowRequest? {
    if (!pageData.optBoolean(RAFT_DOCUMENT_SHELL, false)) return null
    val owner = pageData.authenticatedRouteOwnerOrNull() ?: return null
    val tab =
        pageData.optString(AppShellInitialTabKey).trim()
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let(::retainedAppShellSelectedTab)
            ?.takeIf { it != "home" }
    return AppShellShellWindowRequest(
        routeOwner = owner,
        initialTab = tab,
        suppressRestoreLanding = pageData.optBoolean(APP_SHELL_SUPPRESS_RESTORE_LANDING, false),
        source = source.trim().ifBlank { "unknown" },
    )
}

/** The host's one answer to a scene request. */
internal enum class AppShellShellWindowOutcome {
    /** A new scene connected for the owner. */
    Opened,

    /** The owner's existing scene was brought to the front. */
    Reused,

    /** Refused, failed, timed out, unknown, or the host could not be asked. */
    Refused;

    /** Only a refusal switches the source window in place; a window that shows the server leaves it alone. */
    val switchesInPlace: Boolean
        get() = this == Refused
}

/**
 * Fails closed: anything but an explicit "opened" / "reused" (a missing bridge method answers
 * `{"code":-1}`, a host without the bridge answers nothing) is [AppShellShellWindowOutcome.Refused].
 */
internal fun appShellShellWindowOutcome(result: JSONObject?): AppShellShellWindowOutcome =
    when (result?.optString("status")?.trim()?.lowercase()) {
        "opened" -> AppShellShellWindowOutcome.Opened
        "reused" -> AppShellShellWindowOutcome.Reused
        else -> AppShellShellWindowOutcome.Refused
    }

/**
 * Opens [pageData], a shell page that targets its own window. On [AppShellShellWindowPath.PageRouter]
 * hosts that is RouterModule.openPage and [onShownInWindow] runs at once. On
 * [AppShellShellWindowPath.HostSceneRequest] hosts the page data becomes a scene request:
 * [onShownInWindow] runs once a window shows the owner, [onSwitchInPlace] when the host refused (or
 * when the page data names no owner, synchronously). Exactly one of the two runs, on the main thread.
 */
internal fun Pager.openAppShellInOwnWindow(
    bridge: BridgeModule,
    pageData: JSONObject,
    source: String,
    path: AppShellShellWindowPath = appShellShellWindowPath(raftPlatformRequestsShellWindowScene()),
    onShownInWindow: () -> Unit = {},
    onSwitchInPlace: () -> Unit,
) {
    when (path) {
        AppShellShellWindowPath.PageRouter -> {
            openAppShellReplacingCurrentPage(pageData)
            onShownInWindow()
        }
        AppShellShellWindowPath.HostSceneRequest -> {
            val request = appShellShellWindowRequestOrNull(pageData, source)
            if (request == null) {
                bridge.logWarn("app_shell_shell_window_request_skipped source=$source reason=no_route_owner")
                onSwitchInPlace()
                return
            }
            bridge.requestShellWindow(request.toBridgeParams()) { result ->
                val outcome = appShellShellWindowOutcome(result)
                bridge.log(
                    "app_shell_shell_window_result source=$source to=${request.routeOwner.serverId} " +
                        "outcome=${outcome.name} reason=${result?.optString("reason").orEmpty()}"
                )
                if (outcome.switchesInPlace) onSwitchInPlace() else onShownInWindow()
            }
        }
    }
}
