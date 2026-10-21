package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import build.raft.app.base.Utils
import build.raft.app.data.RaftProductPageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.ui.LocalRaftPageActive

@Page(RAFT_APP_SHELL_CHILD_TAB_PAGE, supportInLocal = true)
internal class ComposeRaftAppShellChildTabPage : build.raft.app.base.BasePager() {
    // Each shell tab is its own child page: it appears when its tab is selected on a visible shell.
    override fun productPageView(): RaftProductPageView? =
        appShellTabProductPageView(retainedAppShellSelectedTab(pageData.params.optString("tab")))

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
        PageErrorContent(
            title = "App Shell",
            subtitle = "Child tab host",
            iconName = RaftLucideIconName.House,
            message = "Could not attach child tab",
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
    LaunchedEffect(tab, active, entry.selectedTab) {
        if (active) {
            bridge.log(
                "app_shell_child_tab_render shellId=$shellId pageDataTab=$tab " +
                    "pageTab=$tab selectedTab=${entry.selectedTab} active=$active entryId=${entry.debugId}"
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
