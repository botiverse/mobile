package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import build.raft.app.base.BasePager
import build.raft.app.base.Utils
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.ProcessPresenceAuthContext
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.ThreadReplyCacheIdentityAuthority
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.SlockTasksApi
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager

private const val ThreadNewerAutoPageName = "compose_thread_newer_auto_pagination_visual"
private const val ThreadNewerAutoServerId = "thread-newer-auto-server"
private const val ThreadNewerAutoChannelId = "thread-newer-auto-channel"
private const val ThreadNewerAutoInitialMessageCount = 16
private const val ThreadNewerAutoPageSize = 30
private const val ThreadNewerAutoEnvironmentId = "https://thread-newer-auto.fixture.example.test"
private const val ThreadNewerAutoFocusedMessageSeq = 8L

/**
 * Cross-process-safe-enough probe for the Android connected fixture.
 *
 * Android instrumentation and the target activity share the target process in
 * this lane, so the test can park an actual [SharedThreadNetworkApi] request,
 * inspect the real LazyColumn through accessibility, and then release the
 * response. No production/store callback is replaced: the only controlled seam
 * is the typed network boundary already owned by [SharedThreadDataSource].
 */
public object ThreadNewerAutoPaginationVisualFixtureProbe {
    private val ledger = ThreadNewerAutoFixtureLedger()

    internal fun beginRun(runNonce: String) = ledger.beginRun(runNonce)

    internal fun recordInitialRequest(runNonce: String, requestTrace: String? = null): Int =
        ledger.recordInitialRequest(runNonce, requestTrace)

    internal fun recordSyncRequest(runNonce: String, channelId: String, sinceSeq: Long, limit: Int, requestTrace: String? = null): Int =
        ledger.recordSyncRequest(runNonce, channelId, sinceSeq, limit, requestTrace)

    internal fun recordOlderRequest(runNonce: String, beforeSeq: Long, limit: Int, requestTrace: String? = null) =
        ledger.recordOlderRequest(runNonce, beforeSeq, limit, requestTrace)

    internal fun recordOtherRequest(runNonce: String, description: String) =
        ledger.recordOtherRequest(runNonce, description)

    internal fun recordSentRequest(runNonce: String) = ledger.recordSentRequest(runNonce)

    public fun sentRequestCount(runNonce: String): Int = ledger.sentRequestCount(runNonce)

    internal suspend fun awaitAfterResponse(
        runNonce: String,
        afterSeq: Long,
        limit: Int,
        requestTrace: String? = null,
    ): ThreadNewerAutoFixtureResponse =
        ledger.awaitAfterResponse(runNonce, afterSeq, limit, requestTrace)

    public fun snapshot(runNonce: String): ThreadNewerAutoPaginationFixtureSnapshot =
        ledger.snapshot(runNonce)

    public fun releaseSuccess(runNonce: String, callNumber: Int, messageCount: Int): Boolean =
        ledger.release(runNonce, callNumber, ThreadNewerAutoFixtureResponse.Success(messageCount))

    public fun releaseError(runNonce: String, callNumber: Int): Boolean =
        ledger.release(runNonce, callNumber, ThreadNewerAutoFixtureResponse.Error)
}

