package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.base.Utils
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.resources.MR
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

/** Executable inventory of independently mounted authenticated production routes. */
internal enum class AuthenticatedProductRoute(val pageName: String) {
    Thread(THREAD_PAGE),
    ThreadMembers(THREAD_MEMBERS_PAGE),
    ChannelSettings(CHANNEL_SETTINGS_PAGE),
    ThreadForwardBundle(THREAD_FORWARD_BUNDLE_PAGE),
    HomeSearch(HOME_SEARCH_PAGE),
    HomeSaved(HOME_SAVED_PAGE),
    HomeActivity(HOME_ACTIVITY_PAGE),
    Members(MEMBERS_PAGE),
    MemberDetail(MEMBER_DETAIL_PAGE),
    FilePreview(RAFT_FILE_PREVIEW_PAGE),
    SettingsAccount(SETTINGS_ACCOUNT_PAGE),
    SettingsHandsFeedback(SETTINGS_HANDS_FEEDBACK_PAGE),
    SettingsNativeDetail(SETTINGS_NATIVE_DETAIL_PAGE),
    SettingsWebView(SETTINGS_WEB_VIEW_PAGE),
    SettingsStorage(SETTINGS_STORAGE_PAGE),
    SettingsComputers(SETTINGS_COMPUTERS_PAGE),
    ReleaseNotes(RELEASE_NOTES_PAGE),
}

internal val authenticatedProductRoutePageNames: Set<String> =
    AuthenticatedProductRoute.entries.mapTo(linkedSetOf()) { it.pageName }

internal enum class AuthenticatedRouteExemption(val pageName: String, val authorityOwner: String) {
    AppShell(RAFT_APP_SHELL_PAGE, "app-shell-auth-gate"),
    AppShellChild(RAFT_APP_SHELL_CHILD_TAB_PAGE, "parent-app-shell-auth-gate"),
    HomeChild(RAFT_HOME_PAGE, "parent-app-shell-auth-gate"),
    SettingsChild(SETTINGS_HOME_PAGE, "parent-app-shell-auth-gate"),
}

internal abstract class AuthenticatedProductPager(
    private val authenticatedRoute: AuthenticatedProductRoute,
) : build.raft.app.base.BasePager() {
    protected fun setAuthenticatedContent(content: @Composable () -> Unit) {
        setContent {
            AuthenticatedProductRouteEffect(this@AuthenticatedProductPager, authenticatedRoute)
            RaftTimestampDisplayScope(
                state = retainedRaftTimestampDisplayState(this@AuthenticatedProductPager.pagerId),
                content = content
            )
        }
    }
}

@Composable
internal fun AuthenticatedProductRouteEffect(
    pager: Pager,
    route: AuthenticatedProductRoute,
) {
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val params = pager.pageData.params
    val storeScope = remember(params.toString(), pager.pagerId) {
        params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val hasExplicitShellScope = remember(params.toString()) {
        params.raftShellStoreScopeOrNull() != null
    }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val sessionExpiredMessage = stringResource(MR.strings.auth_error_session_expired)
    AuthSessionTerminalRouteEffect(
        enabled = hasExplicitShellScope,
        authStore = authStore,
    ) { signal ->
        bridge.logWarn(
            "authenticated_route_terminal page=${route.pageName} " +
                "generation=${signal.generation ?: 0L} " +
                "reason=${signal.reason.name.lowercase()} action=replace_login"
        )
        pager.openLoginReplacingCurrentPage(sessionExpiredMessage)
    }
}
