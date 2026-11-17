package build.raft.app

import build.raft.app.compat.pager.Page

/** The phone's Drafts page (drafts_section_v0), pushed from the Home row like Saved. */
@Page(HOME_DRAFTS_PAGE, supportInLocal = true)
internal class ComposeHomeDraftsPage : AuthenticatedProductPager(AuthenticatedProductRoute.HomeDrafts) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { HomeSecondaryRouterContent(HomeDestinationDrafts, raftPageVisibleResumeTick) }
    }
}
