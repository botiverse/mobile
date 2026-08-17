package build.raft.app

import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.core.annotations.Page

@Page(HOME_SAVED_PAGE, supportInLocal = true)
internal class ComposeHomeSavedPage : AuthenticatedProductPager(AuthenticatedProductRoute.HomeSaved) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { HomeSecondaryRouterContent(HomeDestinationSaved, slockPageVisibleResumeTick) }
    }
}

