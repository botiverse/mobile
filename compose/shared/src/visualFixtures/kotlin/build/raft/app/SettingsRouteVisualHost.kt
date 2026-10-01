package build.raft.app

import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.core.annotations.Page

@Page(SETTINGS_ROUTE_VISUAL_PAGE, supportInLocal = true)
internal class ComposeSettingsRouteVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsRouteVisualScreen() }
    }
}
