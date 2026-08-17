package build.raft.app

import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.core.annotations.Page

@Page(HOME_SEARCH_PAGE, supportInLocal = true)
internal class ComposeHomeSearchPage : AuthenticatedProductPager(AuthenticatedProductRoute.HomeSearch) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent {
            HomeSecondaryRouterContent(
                HomeDestinationSearch,
                slockPageVisibleResumeTick,
                initialSearchChannelId = pageData.params.optString(HOME_SEARCH_SELECTED_CHANNEL_ID).trim().takeIf { it.isNotBlank() },
                initialSearchOpenFilterId = pageData.params.optString(HOME_SEARCH_INITIAL_OPEN_FILTER_ID).trim().takeIf { it.isNotBlank() },
                initialSearchFocusInput = pageData.params.optBoolean(HOME_SEARCH_FOCUS_INPUT, true)
            )
        }
    }
}

