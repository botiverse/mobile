package build.raft.app

import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.core.annotations.Page

@Page(HOME_ACTIVITY_PAGE, supportInLocal = true)
internal class ComposeHomeActivityPage : AuthenticatedProductPager(AuthenticatedProductRoute.HomeActivity) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { HomeSecondaryRouterContent(HomeDestinationActivity, slockPageVisibleResumeTick) }
    }
}

