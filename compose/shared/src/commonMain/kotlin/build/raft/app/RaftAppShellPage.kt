package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.compat.pager.Page
import build.raft.app.compat.json.JSONObject

@Page(RAFT_APP_SHELL_PAGE, supportInLocal = true)
internal class ComposeRaftAppShellPage : build.raft.app.base.BasePager() {
    private var initialAuthRestoreTick by mutableStateOf(0)
    private var adaptivePane by mutableStateOf(RaftAdaptivePaneState())
    private var conversationForwardRequestTick by mutableStateOf(0)
    private var wideHistoryRequest by mutableStateOf<RaftWideNavRequest?>(null)
    private var searchHandoff by mutableStateOf<RaftSearchTabQueryRequest?>(null)

    override val raftSearchTabHandoffRole: RaftSearchTabHandoffRole
        get() = RaftSearchTabHandoffRole.Shell

    override val raftOwnsDatabaseLaunch: Boolean
        get() = true

    override fun onRaftSearchTabHandoff(request: RaftSearchTabQueryRequest) {
        searchHandoff = request
    }

    override fun willInit() {
        super.willInit()
        setContent {
            val timestampDisplayState = retainedRaftTimestampDisplayState(pagerId)
            RaftTimestampDisplayScope(timestampDisplayState) {
                RaftAppShellContent(
                    pageVisibleTick = raftPageVisibleResumeTick,
                    initialAuthRestoreTick = initialAuthRestoreTick,
                    pageVisible = raftPageVisible,
                    adaptivePane = adaptivePane,
                    conversationForwardRequestTick = conversationForwardRequestTick,
                    wideHistoryRequest = wideHistoryRequest,
                    searchHandoff = searchHandoff,
                    onSearchHandoffApplied = { searchHandoff = null }
                )
            }
        }
    }

    override fun onRaftPageDidAppear(isInitialAppearance: Boolean) {
        if (isInitialAppearance) {
            initialAuthRestoreTick += 1
        }
    }

    override fun onRaftAdaptivePaneChanged(eventData: JSONObject) {
        val event = eventData.toRaftAdaptivePaneEventOrNull() ?: return
        adaptivePane = reduceRaftAdaptivePane(adaptivePane, event)
    }

    override fun onReceivePagerEvent(pagerEvent: String, eventData: JSONObject) {
        super.onReceivePagerEvent(pagerEvent, eventData)
        if (pagerEvent == RAFT_CONVERSATION_FORWARD_PAGER_EVENT) {
            conversationForwardRequestTick += 1
        }
        if (pagerEvent == RAFT_WIDE_HISTORY_NAVIGATE_EVENT) {
            val direction = RaftWideNavDirection.fromWireName(eventData.optString(RAFT_WIDE_HISTORY_DIRECTION)) ?: return
            wideHistoryRequest = RaftWideNavRequest(direction, nonce = (wideHistoryRequest?.nonce ?: 0) + 1)
        }
    }
}
