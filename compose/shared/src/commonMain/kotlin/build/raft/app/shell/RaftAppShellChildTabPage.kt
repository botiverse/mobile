package build.raft.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import build.raft.app.base.Utils
import build.raft.app.data.RaftProductPageView
import build.raft.app.iconName
import build.raft.app.members.iconName
import build.raft.app.optString
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftTimestampDisplayScope
import build.raft.app.RaftUnifiedDialogHostProvider
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.resources.*
import build.raft.app.settings.AppShellChildTabRegistry
import build.raft.app.settings.PageErrorContent
import build.raft.app.settings.RAFT_APP_SHELL_CHILD_TAB_PAGE
import build.raft.app.settings.appShellChildPageRendersContent
import build.raft.app.settings.appShellChildTabContentActive
import build.raft.app.settings.iconName
import build.raft.app.settings.retainedAppShellSelectedTab

@Page(RAFT_APP_SHELL_CHILD_TAB_PAGE, supportInLocal = true)
internal class ComposeRaftAppShellChildTabPage : build.raft.app.base.BasePager() {
    // Each shell tab is its own child page: it appears when its tab is selected on a visible shell.
    override fun productPageView(): RaftProductPageView? =
        appShellTabProductPageView(retainedAppShellSelectedTab(pageData.params.optString("tab")))

    // The shell's tab, not this pager: when the shell rebuilds its tab pages (the signed-in shell
    // settling on a cold start), the new Home page continues the Home visit.
    override fun productPageVisitKey(): String =
        appShellTabProductPageVisitKey(
            shellId = pageData.params.optString("shellId"),
            tab = retainedAppShellSelectedTab(pageData.params.optString("tab")),
        )

    override fun willInit() {
        super.willInit()
        setContent { RaftAppShellChildTabPageContent() }
    }
}

@Composable
private fun RaftAppShellChildTabPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val shellId = pager.pageData.params.optString("shellId")
    val tab = retainedAppShellSelectedTab(pager.pageData.params.optString("tab"))
    val entry = AppShellChildTabRegistry.entry(shellId)
    if (entry == null) {
        LaunchedEffect(shellId, tab) {
            bridge.log("app_shell_child_tab_registry_entry shellId=$shellId pageDataTab=$tab result=missing")
        }
        // Only an internal failure lands here (the shell's entry is gone): the app's generic error copy.
        PageErrorContent(
            title = stringResource(Res.string.common_error),
            subtitle = null,
            iconName = RaftLucideIconName.House,
            message = stringResource(Res.string.error_generic),
            code = "child_tab_registry_missing"
        )
        return
    }
    // The tab pager places this child in the same frame the selection changes and provides that
    // placement through LocalRaftPageActive. The registry entry is only refreshed in the shell's
    // SideEffect (after composition), so reading the selection from it lagged one frame: the
    // newly placed tab composed as inactive (or, on its first visit, as an empty placeholder
    // because the retained set lagged too) and the shell flashed a blank / half-rendered frame.
    val active =
        appShellChildTabContentActive(
            parentVisible = entry.parentVisible,
            placedActive = LocalRaftPageActive.current
        )
    val retained = tab in entry.retainedTabs
    LaunchedEffect(shellId, tab, entry.debugId) {
        bridge.log(
            "app_shell_child_tab_registry_entry shellId=$shellId pageDataTab=$tab " +
                "result=hit entryId=${entry.debugId} selectedTab=${entry.selectedTab}"
        )
    }
    if (active) {
        val selectedTab = entry.selectedTab
        LaunchedEffect(tab, selectedTab) {
            bridge.log(
                "app_shell_child_tab_render shellId=$shellId pageDataTab=$tab " +
                    "pageTab=$tab selectedTab=$selectedTab active=true entryId=${entry.debugId}"
            )
        }
    }
    if (appShellChildPageRendersContent(active = active, retained = retained)) {
        val renderer = entry.rendererFor(tab)
        val modalHostState = entry.modalHostState
        RaftTimestampDisplayScope(entry.timestampDisplayState) {
            if (modalHostState != null) {
                RaftUnifiedDialogHostProvider(modalHostState) {
                    renderer(tab, active)
                }
            } else {
                renderer(tab, active)
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize())
    }
}
