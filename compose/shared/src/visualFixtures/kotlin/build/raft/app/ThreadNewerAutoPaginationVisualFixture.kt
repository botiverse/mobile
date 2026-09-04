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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred

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
    private val lock = SynchronizedObject()
    private var activeRunNonce: String = ""
    private var initialRequests: Int = 0
    private var sentRequests: Int = 0
    private val afterRequests = mutableListOf<ThreadNewerAutoAfterRequest>()
    private val afterResponseGates = mutableMapOf<Int, CompletableDeferred<ThreadNewerAutoFixtureResponse>>()
    private val returnedAfterCalls = mutableSetOf<Int>()

    internal fun beginRun(runNonce: String) {
        synchronized(lock) {
            activeRunNonce = runNonce
            initialRequests = 0
            sentRequests = 0
            afterRequests.clear()
            afterResponseGates.values.forEach { gate ->
                gate.cancel()
            }
            afterResponseGates.clear()
            returnedAfterCalls.clear()
        }
    }

    internal fun recordInitialRequest(runNonce: String) {
        synchronized(lock) {
            check(activeRunNonce == runNonce) { "stale initial request for $runNonce" }
            initialRequests += 1
        }
    }

    internal fun recordSentRequest(runNonce: String) {
        synchronized(lock) {
            check(runNonce == activeRunNonce) { "stale thread newer auto send run $runNonce" }
            sentRequests += 1
        }
    }

    public fun sentRequestCount(runNonce: String): Int =
        synchronized(lock) {
            if (runNonce == activeRunNonce) sentRequests else 0
        }

    internal suspend fun awaitAfterResponse(
        runNonce: String,
        afterSeq: Long,
        limit: Int,
    ): ThreadNewerAutoFixtureResponse {
        val (callNumber, gate) = synchronized(lock) {
            check(activeRunNonce == runNonce) { "stale newer request for $runNonce" }
            val callNumber = afterRequests.size + 1
            check(callNumber !in afterResponseGates) { "duplicate newer request number $callNumber" }
            afterRequests +=
                ThreadNewerAutoAfterRequest(
                    callNumber = callNumber,
                    afterSeq = afterSeq,
                    limit = limit,
                )
            val gate = CompletableDeferred<ThreadNewerAutoFixtureResponse>()
            afterResponseGates[callNumber] = gate
            callNumber to gate
        }
        val response = gate.await()
        synchronized(lock) {
            if (activeRunNonce == runNonce) {
                returnedAfterCalls += callNumber
            }
        }
        return response
    }

    public fun snapshot(runNonce: String): ThreadNewerAutoPaginationFixtureSnapshot =
        synchronized(lock) {
            check(activeRunNonce == runNonce) {
                "fixture run mismatch: expected=$activeRunNonce requested=$runNonce"
            }
            ThreadNewerAutoPaginationFixtureSnapshot(
                runNonce = activeRunNonce,
                initialRequestCount = initialRequests,
                afterRequests = afterRequests.toList(),
                returnedAfterCalls = returnedAfterCalls.sorted(),
            )
        }

    public fun releaseSuccess(runNonce: String, callNumber: Int, messageCount: Int): Boolean =
        release(
            runNonce = runNonce,
            callNumber = callNumber,
            response = ThreadNewerAutoFixtureResponse.Success(messageCount),
        )

    public fun releaseError(runNonce: String, callNumber: Int): Boolean =
        release(
            runNonce = runNonce,
            callNumber = callNumber,
            response = ThreadNewerAutoFixtureResponse.Error,
        )

    private fun release(
        runNonce: String,
        callNumber: Int,
        response: ThreadNewerAutoFixtureResponse,
    ): Boolean {
        val gate =
            synchronized(lock) {
                if (activeRunNonce != runNonce) return@synchronized null
                afterResponseGates[callNumber]
            } ?: return false
        return gate.complete(response)
    }
}

public data class ThreadNewerAutoPaginationFixtureSnapshot(
    val runNonce: String,
    val initialRequestCount: Int,
    val afterRequests: List<ThreadNewerAutoAfterRequest>,
    val returnedAfterCalls: List<Int>,
)

public data class ThreadNewerAutoAfterRequest(
    val callNumber: Int,
    val afterSeq: Long,
    val limit: Int,
)

internal sealed interface ThreadNewerAutoFixtureResponse {
    data class Success(val messageCount: Int) : ThreadNewerAutoFixtureResponse

    data object Error : ThreadNewerAutoFixtureResponse
}

@Page(ThreadNewerAutoPageName, supportInLocal = true)
internal class ThreadNewerAutoPaginationVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val runNonce = pageData.params.optString("runNonce").trim()
        require(runNonce.isNotBlank()) { "thread newer auto-pagination runNonce is required" }
        ThreadNewerAutoPaginationVisualFixtureProbe.beginRun(runNonce)
        val showComposer = pageData.params.optBoolean("showComposer", false)
        setContent { ThreadNewerAutoPaginationVisualContent(runNonce, showComposer) }
    }
}

@Composable
private fun ThreadNewerAutoPaginationVisualContent(runNonce: String, showComposer: Boolean) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val provider = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val storeScope =
        remember(runNonce) {
            SlockShellStoreScope.create(
                shellInstanceId = "thread-newer-auto-$runNonce",
                initialServerId = ThreadNewerAutoServerId,
            )
        }
    val fixtureSession = remember(runNonce) { threadNewerAutoSession(runNonce) }
    val sessionStore =
        remember(runNonce) {
            InMemorySlockAuthSessionStore(fixtureSession)
        }
    // Task #1122: the data source has no private identity resolver any more; this fixture is its
    // own realm (own session store, own environment id), so it names its own authority — the
    // production authority class, fed a fixed auth context that matches the fixture session.
    val replyCacheIdentityAuthority =
        remember(runNonce, sessionStore, storeScope) {
            ThreadReplyCacheIdentityAuthority(
                sessionStore = sessionStore,
                selectedServerStore = SelectedServerStore(storeScope),
                authContextProvider = {
                    ProcessPresenceAuthContext(
                        environmentId = ThreadNewerAutoEnvironmentId,
                        accountId = fixtureSession.user.id,
                        authorityGeneration = 1L,
                        writeGeneration = 1L,
                    )
                },
                environmentIdProvider = { ThreadNewerAutoEnvironmentId },
            )
        }
    val networkApi = remember(runNonce) { ThreadNewerAutoPaginationNetworkApi(runNonce) }
    val dataSource =
        remember(runNonce, sessionStore, networkApi) {
            SharedThreadDataSource(
                networkApi = networkApi,
                sessionStore = sessionStore,
                // This fixture is its own realm: name it instead of relying on a blank default.
                cacheEnvironmentIdProvider = { ThreadNewerAutoEnvironmentId },
                replyCacheIdentityAuthority = { replyCacheIdentityAuthority.capture() },
                threadPageSizeProvider = { ThreadNewerAutoPageSize },
                diagnosticsLogger = { message -> bridge.log("thread_newer_auto_fixture $message") },
            )
        }
    val tasksDataSource = remember { SharedTasksDataSource(ThreadNewerAutoTasksApi) }
    val initialPayload =
        remember(runNonce) {
            KuiklyThreadScreenPayload(
                instanceId = "thread-newer-auto-$runNonce",
                title = "Newer pagination fixture",
                sourceThreadId = ThreadNewerAutoChannelId,
                streamChannelId = ThreadNewerAutoChannelId,
                status = "loading",
                focusedMessageId = threadNewerAutoMessageId(ThreadNewerAutoFocusedMessageSeq),
                focusedMessageRequestNonce = 1,
                canLoadMoreAfter = true,
                showModeTabs = false,
                showComposer = showComposer,
                canSend = showComposer,
                showMembersAction = false,
                showSettingsAction = false,
            )
        }

    DisposableEffect(provider, storeScope) {
        onDispose {
            provider.releaseShellStoreScope(storeScope)
        }
    }
    val timeContextProvider = rememberLiveSlockTimeContext(initialPayload.toSlockTimeCapabilities())

    SharedThreadRouteContent(
        initialPayload = initialPayload,
        timeContextProvider = timeContextProvider,
        bridge = bridge,
        dataSource = dataSource,
        threadSendScope = provider.threadSendScope,
        authStore = sessionStore,
        tasksDataSource = tasksDataSource,
        tasksDataStore = provider.tasksDataStore,
        storeScope = storeScope,
        composerDraftPersistSlice = provider.composerDraftPersistSlice,
        eventBus = provider.eventBus,
        onBack = {},
        onAuthRequired = { code -> bridge.log("thread_newer_auto_fixture auth_required=$code") },
    )
}

private class ThreadNewerAutoPaginationNetworkApi(
    private val runNonce: String,
) : SharedThreadNetworkApi {
    private var sentMessageSequence = 100L

    override suspend fun channelMessages(
        threadId: String,
        limit: Int,
        beforeSeq: Long?,
        afterSeq: Long?,
    ): HomeNetworkResponse {
        check(threadId == ThreadNewerAutoChannelId) { "unexpected fixture thread $threadId" }
        if (beforeSeq != null) {
            return threadNewerAutoMessagesResponse(startSeq = 1L, messageCount = 0)
        }
        if (afterSeq == null) {
            ThreadNewerAutoPaginationVisualFixtureProbe.recordInitialRequest(runNonce)
            return threadNewerAutoMessagesResponse(
                startSeq = 1L,
                messageCount = ThreadNewerAutoInitialMessageCount,
            )
        }
        return when (
            val response =
                ThreadNewerAutoPaginationVisualFixtureProbe.awaitAfterResponse(
                    runNonce = runNonce,
                    afterSeq = afterSeq,
                    limit = limit,
                )
        ) {
            is ThreadNewerAutoFixtureResponse.Success ->
                threadNewerAutoMessagesResponse(
                    startSeq = afterSeq + 1L,
                    messageCount = response.messageCount,
                )
            ThreadNewerAutoFixtureResponse.Error ->
                HomeNetworkResponse(
                    code = 503,
                    bodyText = null,
                    errorBodyText = "thread_newer_auto_fixture_failure",
                )
        }
    }

    override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "[]")

    override suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean,
        attachmentIds: List<String>,
        randomId: String?,
    ): HomeNetworkResponse {
        check(channelId == ThreadNewerAutoChannelId) { "unexpected fixture send channel $channelId" }
        ThreadNewerAutoPaginationVisualFixtureProbe.recordSentRequest(runNonce)
        sentMessageSequence += 1L
        val seq = sentMessageSequence
        return HomeNetworkResponse(
            code = 200,
            bodyText =
                """
                {
                  "id":"${threadNewerAutoMessageId(seq)}",
                  "seq":$seq,
                  "channelId":"$ThreadNewerAutoChannelId",
                  "content":"$body",
                  "createdAt":"2026-07-27T06:00:00Z",
                  "senderId":"thread-newer-auto-user",
                  "senderName":"Thread Pagination Fixture",
                  "senderType":"user",
                  "messageType":"chat",
                  "randomId":"${randomId.orEmpty()}"
                }
                """.trimIndent(),
        )
    }

    override suspend fun executePendingMentionActions(
        action: String,
        resolutionIds: List<String>,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = "{\"results\":[]}")

    override suspend fun attachmentUrl(attachmentId: String, disposition: String?): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    override suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    override suspend fun sendAttachmentComment(attachmentId: String, body: String): HomeNetworkResponse = okResponse()

    override suspend fun markChannelReadToSeq(channelId: String, seq: Long): HomeNetworkResponse = okResponse()

    override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse = okResponse()

    override suspend fun saveMessage(messageId: String): HomeNetworkResponse = okResponse()

    override suspend fun unsaveMessage(messageId: String): HomeNetworkResponse = okResponse()

    override suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse = okResponse()

    override suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse = okResponse()

    override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                "{\"threadChannelId\":\"$ThreadNewerAutoChannelId\",\"parentMessageId\":\"$parentMessageId\",\"created\":false}",
        )

    override suspend fun messageContext(messageId: String, channelId: String?): HomeNetworkResponse = okResponse()

    private fun okResponse(): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = "{}")
}

private object ThreadNewerAutoTasksApi : SlockTasksApi {
    override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "{\"tasks\":[]}")

    override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "{}")
}

private fun threadNewerAutoSession(runNonce: String): StoredAuthSession =
    StoredAuthSession(
        user =
            StoredAuthUser(
                id = "thread-newer-auto-user",
                email = "thread-newer-auto-$runNonce@example.invalid",
                name = "Thread Pagination Fixture",
            ),
        accessToken = "thread-newer-auto-access-$runNonce",
        refreshToken = "thread-newer-auto-refresh-$runNonce",
        selectedServerId = ThreadNewerAutoServerId,
        selectedServerSlug = "thread-newer-auto",
        servers =
            listOf(
                StoredAuthServer(
                    id = ThreadNewerAutoServerId,
                    name = "Thread Pagination Fixture",
                    slug = "thread-newer-auto",
                    role = "owner",
                ),
            ),
        allowImplicitServerFallback = false,
    )

private fun threadNewerAutoMessagesResponse(startSeq: Long, messageCount: Int): HomeNetworkResponse {
    val messages =
        (startSeq until startSeq + messageCount)
            .joinToString(separator = ",") { seq ->
                val authorSuffix = if (seq % 2L == 0L) "a" else "b"
                """
                {
                  "id":"${threadNewerAutoMessageId(seq)}",
                  "seq":$seq,
                  "channelId":"$ThreadNewerAutoChannelId",
                  "content":"${threadNewerAutoMessageLabel(seq)} — deterministic real Thread row with enough content to wrap on a phone viewport.",
                  "createdAt":"2026-07-20T08:${(seq % 60L).toString().padStart(2, '0')}:00Z",
                  "senderId":"fixture-user-$authorSuffix",
                  "senderName":"Fixture ${authorSuffix.uppercase()}",
                  "senderType":"user",
                  "messageType":"chat"
                }
                """.trimIndent()
            }
    return HomeNetworkResponse(
        code = 200,
        bodyText = "{\"historyLimited\":false,\"messages\":[$messages]}",
    )
}

private fun threadNewerAutoMessageId(seq: Long): String = "thread-newer-auto-m$seq"

public fun threadNewerAutoMessageLabel(seq: Long): String =
    "AUTO-MSG-${seq.toString().padStart(4, '0')}"
