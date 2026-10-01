package build.raft.app

import build.raft.app.data.SlockAppShellDestinationSnapshots
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.normalizedOrNull
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager

internal const val SLOCK_SHELL_INSTANCE_ID = "slockShellInstanceId"
internal const val SLOCK_SHELL_SERVER_ID = "slockShellServerId"
private const val LEGACY_SLOCK_SHELL_SERVER_ID = "serverId"
internal const val SLOCK_DOCUMENT_SHELL = "slockDocumentShell"
internal const val SLOCK_ROUTE_ENVIRONMENT_ID = "slockRouteEnvironmentId"
internal const val SLOCK_ROUTE_ACCOUNT_ID = "slockRouteAccountId"
internal const val SLOCK_ROUTE_SERVER_ID = "slockRouteServerId"

internal fun appLifecycleEventTargetsShell(
    eventShellInstanceId: String,
    routeShellInstanceId: String
): Boolean =
    if (eventShellInstanceId.isBlank()) {
        routeShellInstanceId == SlockShellStoreScope.Legacy.shellInstanceId
    } else {
        eventShellInstanceId == routeShellInstanceId
    }

/** Identity-only launch contract shared by AppShell and its child routes. */
internal fun JSONObject.slockShellStoreScope(fallbackShellInstanceId: String): SlockShellStoreScope =
    SlockShellStoreScope.create(
        shellInstanceId = optString(SLOCK_SHELL_INSTANCE_ID).ifBlank { fallbackShellInstanceId },
        initialServerId = slockShellServerIdOrNull()
    )

internal fun JSONObject.slockShellStoreScopeOrNull(): SlockShellStoreScope? {
    val shellInstanceId = optString(SLOCK_SHELL_INSTANCE_ID).trim().takeIf { it.isNotBlank() } ?: return null
    return SlockShellStoreScope.create(
        shellInstanceId = shellInstanceId,
        initialServerId = slockShellServerIdOrNull()
    )
}

private fun JSONObject.slockShellServerIdOrNull(): String? =
    if (has(SLOCK_SHELL_SERVER_ID)) {
        // Presence is significant: a serialized null means the live shell has
        // no selected server and must not fall back to a feature route's
        // legacy `serverId` field.
        optString(SLOCK_SHELL_SERVER_ID).trim().takeIf { it.isNotBlank() }
    } else {
        optString(LEGACY_SLOCK_SHELL_SERVER_ID).trim().takeIf { it.isNotBlank() }
    }

internal fun JSONObject.withSlockShellStoreScope(storeScope: SlockShellStoreScope): JSONObject =
    put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        .put(SLOCK_SHELL_SERVER_ID, storeScope.initialServerId)

internal fun JSONObject.authenticatedRouteOwnerOrNull(): AuthenticatedRouteOwner? =
    AuthenticatedRouteOwner(
        environmentId = optString(SLOCK_ROUTE_ENVIRONMENT_ID),
        accountId = optString(SLOCK_ROUTE_ACCOUNT_ID),
        serverId = optString(SLOCK_ROUTE_SERVER_ID),
    ).normalizedOrNull()

internal fun JSONObject.withAuthenticatedRouteOwner(owner: AuthenticatedRouteOwner): JSONObject =
    put(SLOCK_ROUTE_ENVIRONMENT_ID, owner.environmentId)
        .put(SLOCK_ROUTE_ACCOUNT_ID, owner.accountId)
        .put(SLOCK_ROUTE_SERVER_ID, owner.serverId)

/**
 * The only production boundary for opening shell-owned child pages.
 * Feature page data carries target identity/intent; this facade inherits source ownership.
 */
internal fun Pager.openSlockPage(
    pageName: String,
    pageData: JSONObject,
    storeScopeOverride: SlockShellStoreScope? = null,
    authenticatedRouteAuthorityRequired: Boolean = false,
    onMissingScope: () -> Unit = {}
): Boolean {
    val sourceStoreScope = storeScopeOverride ?: sourcePageDataParams().slockShellStoreScopeOrNull()
    if (sourceStoreScope == null) {
        slockLogWarn(
            tag = "SlockScopedNavigation",
            message = "slock_scoped_navigation_rejected reason=missing_source_scope target=$pageName"
        )
        onMissingScope()
        return false
    }
    // The route's server id is only bootstrap metadata. Refresh it from the
    // live shell selection immediately before serializing the child page so a
    // retained route cannot carry an old server across process death.
    val sharedStores = SlockSharedStoreRegistry.currentOrNull()
    val storeScope = sharedStores?.currentNavigationStoreScope(sourceStoreScope) ?: sourceStoreScope
    var launchPageData = pageData.withSlockShellStoreScope(storeScope)
    if (authenticatedRouteAuthorityRequired) {
        val owner = sharedStores?.currentAuthenticatedRouteOwner(storeScope)
        val targetServerId = pageData.optString("serverId").trim()
        if (owner == null || targetServerId.isBlank() || targetServerId != owner.serverId) {
            slockLogWarn(
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
fun releaseSlockShellStoreScope(shellInstanceId: String, serverId: String? = null) {
    val normalizedShellInstanceId = shellInstanceId.trim()
    if (normalizedShellInstanceId.isBlank()) return
    ProcessAttachmentAuthScopes.retireShell(normalizedShellInstanceId)
    val scope =
        SlockShellStoreScope.create(
            shellInstanceId = normalizedShellInstanceId,
            initialServerId = serverId?.trim()?.takeIf { it.isNotBlank() },
        )
    // task #58: a real shell close retires its last-destination snapshot so a
    // later same-shellId open starts fresh. Config-change recreates skip this
    // path (shouldReleaseAndroidShellWindow requires isFinishing && !changing).
    SlockAppShellDestinationSnapshots.clear(scope)
    SlockSharedStoreRegistry.currentOrNull()?.releaseShellStoreScope(scope)
}
