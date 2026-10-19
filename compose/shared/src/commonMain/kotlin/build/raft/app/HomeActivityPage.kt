package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.compat.pager.Page
import build.raft.app.compat.json.JSONObject

@Page(HOME_ACTIVITY_PAGE, supportInLocal = true)
internal class ComposeHomeActivityPage : AuthenticatedProductPager(AuthenticatedProductRoute.HomeActivity) {
    // task #13: this pushed page owns a responsive surface (Activity dual-pane), so it
    // consumes host window facts like ComposeSlockAppShellPage/MembersPage do. Without
    // this override the shellScope stays on the default compact pane and the Activity
    // surface can never split.
    private var adaptivePane by mutableStateOf(SlockAdaptivePaneState())

    override fun willInit() {
        super.willInit()
        setAuthenticatedContent {
            HomeSecondaryRouterContent(
                HomeDestinationActivity,
                slockPageVisibleResumeTick,
                adaptivePane = adaptivePane,
            )
        }
    }

    override fun onSlockAdaptivePaneChanged(eventData: JSONObject) {
        val event = eventData.toSlockAdaptivePaneEventOrNull() ?: return
        adaptivePane = reduceSlockAdaptivePane(adaptivePane, event)
    }
}
