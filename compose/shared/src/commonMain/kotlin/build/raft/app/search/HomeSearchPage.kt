package build.raft.app

import build.raft.app.compat.pager.Page
import build.raft.app.settings.HOME_SEARCH_FOCUS_INPUT
import build.raft.app.settings.HOME_SEARCH_INITIAL_OPEN_FILTER_ID
import build.raft.app.settings.HOME_SEARCH_PAGE
import build.raft.app.settings.HOME_SEARCH_SELECTED_CHANNEL_ID

@Page(HOME_SEARCH_PAGE, supportInLocal = true)
internal class ComposeHomeSearchPage : AuthenticatedProductPager(AuthenticatedProductRoute.HomeSearch) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent {
            HomeSecondaryRouterContent(
                HomeDestinationSearch,
                raftPageVisibleResumeTick,
                initialSearchChannelId = pageData.params.optString(HOME_SEARCH_SELECTED_CHANNEL_ID).trim().takeIf { it.isNotBlank() },
                initialSearchOpenFilterId = pageData.params.optString(HOME_SEARCH_INITIAL_OPEN_FILTER_ID).trim().takeIf { it.isNotBlank() },
                initialSearchFocusInput = pageData.params.optBoolean(HOME_SEARCH_FOCUS_INPUT, true),
                searchInputActive = raftPageVisible
            )
        }
    }
}

