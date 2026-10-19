package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.compat.pager.Page
import build.raft.app.compat.json.JSONObject

@Page(SLOCK_APP_SHELL_PAGE, supportInLocal = true)
internal class ComposeSlockAppShellPage : build.raft.app.base.BasePager() {
    private var initialAuthRestoreTick by mutableStateOf(0)
    private var adaptivePane by mutableStateOf(SlockAdaptivePaneState())
    private var conversationForwardRequestTick by mutableStateOf(0)

    override fun willInit() {
        super.willInit()
        setContent {
            val timestampDisplayState = retainedSlockTimestampDisplayState(pagerId)
            SlockTimestampDisplayScope(timestampDisplayState) {
                SlockAppShellContent(
                    pageVisibleTick = slockPageVisibleResumeTick,
                    initialAuthRestoreTick = initialAuthRestoreTick,
                    pageVisible = slockPageVisible,
                    adaptivePane = adaptivePane,
                    conversationForwardRequestTick = conversationForwardRequestTick
                )
            }
        }
    }

    override fun onSlockPageDidAppear(isInitialAppearance: Boolean) {
        if (isInitialAppearance) {
            initialAuthRestoreTick += 1
        }
    }

    override fun onSlockAdaptivePaneChanged(eventData: JSONObject) {
        val event = eventData.toSlockAdaptivePaneEventOrNull() ?: return
        adaptivePane = reduceSlockAdaptivePane(adaptivePane, event)
    }

    override fun onReceivePagerEvent(pagerEvent: String, eventData: JSONObject) {
        super.onReceivePagerEvent(pagerEvent, eventData)
        if (pagerEvent == SLOCK_CONVERSATION_FORWARD_PAGER_EVENT) {
            conversationForwardRequestTick += 1
        }
    }
}
