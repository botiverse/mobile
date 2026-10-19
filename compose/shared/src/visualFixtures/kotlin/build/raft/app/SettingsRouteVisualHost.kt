package build.raft.app

import build.raft.app.compat.pager.Page

@Page(SETTINGS_ROUTE_VISUAL_PAGE, supportInLocal = true)
internal class ComposeSettingsRouteVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsRouteVisualScreen() }
    }
}
