package build.raft.app

import build.raft.app.compat.pager.Page
import build.raft.app.settings.HOME_SAVED_PAGE

@Page(HOME_SAVED_PAGE, supportInLocal = true)
internal class ComposeHomeSavedPage : AuthenticatedProductPager(AuthenticatedProductRoute.HomeSaved) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { HomeSecondaryRouterContent(HomeDestinationSaved, raftPageVisibleResumeTick) }
    }
}

