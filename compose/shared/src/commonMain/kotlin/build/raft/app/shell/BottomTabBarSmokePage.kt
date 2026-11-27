package build.raft.app.shell

import build.raft.app.compat.pager.Page
import build.raft.app.settings.BOTTOM_TAB_BAR_SMOKE_PAGE
import build.raft.app.settings.BottomTabBarSmokeContent

@Page(BOTTOM_TAB_BAR_SMOKE_PAGE, supportInLocal = true)
internal class ComposeBottomTabBarSmokePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { BottomTabBarSmokeContent() }
    }
}
