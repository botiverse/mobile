package build.raft.app

import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.core.annotations.Page

@Page(BOTTOM_TAB_BAR_SMOKE_PAGE, supportInLocal = true)
internal class ComposeBottomTabBarSmokePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { BottomTabBarSmokeContent() }
    }
}
