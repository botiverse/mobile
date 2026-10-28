package build.raft.app

import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.normalizedOrNull
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager

internal const val RAFT_SHELL_INSTANCE_ID = "slockShellInstanceId"
internal const val RAFT_SHELL_SERVER_ID = "slockShellServerId"
private const val LEGACY_RAFT_SHELL_SERVER_ID = "serverId"
internal const val RAFT_DOCUMENT_SHELL = "slockDocumentShell"
const val RAFT_ROUTE_ENVIRONMENT_ID = "slockRouteEnvironmentId"
const val RAFT_ROUTE_ACCOUNT_ID = "slockRouteAccountId"
const val RAFT_ROUTE_SERVER_ID = "slockRouteServerId"

/**
 * Page data flag: the host shows the page without its push transition (Android
 * `FLAG_ACTIVITY_NO_ANIMATION`, iOS `animated:NO`), for a page that takes the place of what the
 * source page shows over itself (a task's first send opens its conversation where the pending task
 * page was).
 */
const val RAFT_OPEN_WITHOUT_ANIMATION = "slockOpenWithoutAnimation"

/** [RAFT_OPEN_WITHOUT_ANIMATION] when [animated] is false; the data unchanged otherwise. */
internal fun JSONObject.withRaftOpenAnimation(animated: Boolean): JSONObject =
    if (animated) this else put(RAFT_OPEN_WITHOUT_ANIMATION, true)

internal fun appLifecycleEventTargetsShell(
    eventShellInstanceId: String,
    routeShellInstanceId: String
): Boolean =
    if (eventShellInstanceId.isBlank()) {
        routeShellInstanceId == RaftShellStoreScope.Legacy.shellInstanceId
    } else {
        eventShellInstanceId == routeShellInstanceId
    }

/** Identity-only launch contract shared by AppShell and its child routes. */
internal fun JSONObject.raftShellStoreScope(fallbackShellInstanceId: String): RaftShellStoreScope =
    RaftShellStoreScope.create(
        shellInstanceId = optString(RAFT_SHELL_INSTANCE_ID).ifBlank { fallbackShellInstanceId },
        initialServerId = raftShellServerIdOrNull()
    )

internal fun JSONObject.raftShellStoreScopeOrNull(): RaftShellStoreScope? {
    val shellInstanceId = optString(RAFT_SHELL_INSTANCE_ID).trim().takeIf { it.isNotBlank() } ?: return null
    return RaftShellStoreScope.create(
        shellInstanceId = shellInstanceId,
        initialServerId = raftShellServerIdOrNull()
    )
}

private fun JSONObject.raftShellServerIdOrNull(): String? =
    if (has(RAFT_SHELL_SERVER_ID)) {
        // Presence is significant: a serialized null means the live shell has
        // no selected server and must not fall back to a feature route's
        // legacy `serverId` field.
        optString(RAFT_SHELL_SERVER_ID).trim().takeIf { it.isNotBlank() }
    } else {
        optString(LEGACY_RAFT_SHELL_SERVER_ID).trim().takeIf { it.isNotBlank() }
    }

internal fun JSONObject.withRaftShellStoreScope(storeScope: RaftShellStoreScope): JSONObject =
    put(RAFT_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        .put(RAFT_SHELL_SERVER_ID, storeScope.initialServerId)

internal fun JSONObject.authenticatedRouteOwnerOrNull(): AuthenticatedRouteOwner? =
    AuthenticatedRouteOwner(
        environmentId = optString(RAFT_ROUTE_ENVIRONMENT_ID),
        accountId = optString(RAFT_ROUTE_ACCOUNT_ID),
        serverId = optString(RAFT_ROUTE_SERVER_ID),
    ).normalizedOrNull()

internal fun JSONObject.withAuthenticatedRouteOwner(owner: AuthenticatedRouteOwner): JSONObject =
    put(RAFT_ROUTE_ENVIRONMENT_ID, owner.environmentId)
        .put(RAFT_ROUTE_ACCOUNT_ID, owner.accountId)
        .put(RAFT_ROUTE_SERVER_ID, owner.serverId)

/**
 * The only production boundary for opening shell-owned child pages.
 * Feature page data carries target identity/intent; this facade inherits source ownership.
 */
internal fun Pager.openRaftPage(
    pageName: String,
    pageData: JSONObject,
    storeScopeOverride: RaftShellStoreScope? = null,
    authenticatedRouteAuthorityRequired: Boolean = false,
    onMissingScope: () -> Unit = {}
): Boolean {
    val sourceStoreScope = storeScopeOverride ?: sourcePageDataParams().raftShellStoreScopeOrNull()
    if (sourceStoreScope == null) {
        raftLogWarn(
            tag = "SlockScopedNavigation",
            message = "slock_scoped_navigation_rejected reason=missing_source_scope target=$pageName"
        )
        onMissingScope()
        return false
    }
    // The route's server id is only bootstrap metadata. Refresh it from the
    // live shell selection immediately before serializing the child page so a
    // retained route cannot carry an old server across process death.
    val sharedStores = RaftSharedStoreRegistry.currentOrNull()
    val storeScope = sharedStores?.currentNavigationStoreScope(sourceStoreScope) ?: sourceStoreScope
    var launchPageData = pageData.withRaftShellStoreScope(storeScope)
    if (authenticatedRouteAuthorityRequired) {
        val owner = sharedStores?.currentAuthenticatedRouteOwner(storeScope)
        val targetServerId = pageData.optString("serverId").trim()
        if (owner == null || targetServerId.isBlank() || targetServerId != owner.serverId) {
            raftLogWarn(
                tag = "SlockScopedNavigation",
                message =
                    "slock_scoped_navigation_rejected reason=authenticated_route_authority " +
                        "target=$pageName"
            )
            onMissingScope()
            return false
        }
        launchPageData = launchPageData.withAuthenticatedRouteOwner(owner)
    }
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(pageName, launchPageData)
    return true
}

private fun Pager.sourcePageDataParams(): JSONObject = pageData.params

/** Android lifecycle bridge; identity-only so platform code never reaches provider internals. */
fun releaseRaftShellStoreScope(shellInstanceId: String, serverId: String? = null) {
    val normalizedShellInstanceId = shellInstanceId.trim()
    if (normalizedShellInstanceId.isBlank()) return
    ProcessAttachmentAuthScopes.retireShell(normalizedShellInstanceId)
    RaftSharedStoreRegistry.currentOrNull()?.releaseShellStoreScope(
        RaftShellStoreScope.create(
            shellInstanceId = normalizedShellInstanceId,
            initialServerId = serverId?.trim()?.takeIf { it.isNotBlank() },
        )
    )
}
