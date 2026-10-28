package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.data.AddChannelMemberRequest
import build.raft.app.data.ChannelMessageWindow
import build.raft.app.data.AddChannelMembersBatchRequest
import build.raft.app.data.AddChannelMembersBatchResult
import build.raft.app.data.AgentActivitySnapshot
import build.raft.app.data.AgentAvatarUploadRequest
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDmSnapshot
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.AgentMachineAssignmentRequest
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.AgentReminderSnapshot
import build.raft.app.data.AgentResetRequest
import build.raft.app.data.AgentSkillsSnapshot
import build.raft.app.data.AgentWorkspaceChildrenSnapshot
import build.raft.app.data.AgentWorkspaceFilePreviewSnapshot
import build.raft.app.data.AgentWorkspaceSnapshot
import build.raft.app.data.AgentWorkspaceTreeSnapshot
import build.raft.app.data.AttachmentOpenTarget
import build.raft.app.data.AttachmentUploadRequest
import build.raft.app.data.AttachmentUploadResult
import build.raft.app.data.ChannelFilesSnapshot
import build.raft.app.data.ChannelMemberActionResult
import build.raft.app.data.ChannelMemberSnapshot
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxDoneFrontier
import build.raft.app.data.MemberRepository
import build.raft.app.data.MemberSnapshot
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.ScreenError
import build.raft.app.data.SendMessageResult
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadListSnapshot
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalMessageV2EnterBaseline
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadDurableReplyParent
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Fakes shared by the split `SharedThreadStore<Facet>Test` files (task #41 split of SharedThreadStoreTest). */
internal object SharedThreadStoreTestFakes {
    /**
     * Thread-safe append/snapshot log buffer for tests that run stores on
     * multi-threaded dispatchers. commonTest compiles for native targets too, so
     * JVM types like CopyOnWriteArrayList are off-limits, and
     * kotlin.concurrent.atomics is avoided — an unlimited Channel is
     * the portable thread-safe append primitive (#214). `any` drains newly
     * arrived entries into a snapshot list owned by the single polling thread.
     */
    internal class ConcurrentTestLogBuffer {
        private val incoming = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        private val seen = mutableListOf<String>()

        fun add(entry: String) {
            incoming.trySend(entry)
        }

        fun any(predicate: (String) -> Boolean): Boolean {
            while (true) {
                val next = incoming.tryReceive().getOrNull() ?: break
                seen += next
            }
            return seen.any(predicate)
        }
    }
    internal class RecordingDurablePageExtender : ThreadDurablePageExtender {
        data class Call(
            val partition: DurablePartitionKey,
            val channelId: String,
            val direction: ThreadDurablePageDirection,
            val anchorSeq: Long,
            val messageIds: List<String?>,
            val requestedLimit: Int,
            val stillCurrent: Boolean
        )

        val calls = mutableListOf<Call>()
        val replyParents = mutableListOf<ThreadDurableReplyParent?>()

        /** Phase 3b: what [headEpoch] answers (null = not tracked), and the epochs every write was handed. */
        var headEpochProvider: ((DurablePartitionKey) -> Long?)? = null
        val requestHeadEpochs = mutableListOf<Long?>()

        override fun headEpoch(partition: DurablePartitionKey): Long? = headEpochProvider?.invoke(partition)

        override suspend fun extendWithAdjacentPage(
            partition: DurablePartitionKey,
            channelId: String,
            direction: ThreadDurablePageDirection,
            anchorSeq: Long,
            rawMessages: JsonArray,
            requestedLimit: Int,
            replyParent: ThreadDurableReplyParent?,
            requestHeadEpoch: Long?,
            stillCurrent: () -> Boolean
        ) {
            replyParents += replyParent
            requestHeadEpochs += requestHeadEpoch
            calls +=
                Call(
                    partition = partition,
                    channelId = channelId,
                    direction = direction,
                    anchorSeq = anchorSeq,
                    messageIds = rawMessages.map { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content },
                    requestedLimit = requestedLimit,
                    stillCurrent = stillCurrent()
                )
        }

        /** Task #168 step 1: gap catch-up latest pages, as (channel, message ids, limit, stillCurrent). */
        val latestCalls = mutableListOf<Triple<String, List<String?>, Pair<Int, Boolean>>>()

        /** Task #168 step 3: what [beginLatestPageBaseline] hands out (null = refuse, the default). */
        var baselineToRegister: ((DurablePartitionKey, String) -> CanonicalMessageV2EnterBaseline)? = null

        /** Ordered: `begin`, `apply:<baseline handed?>`, `abandon`, plus whatever the test appends. */
        val baselineEvents = mutableListOf<String>()
        var onBegin: (() -> Unit)? = null

        override suspend fun beginLatestPageBaseline(
            partition: DurablePartitionKey,
            channelId: String,
            replyParent: ThreadDurableReplyParent?,
        ): CanonicalMessageV2EnterBaseline? {
            onBegin?.invoke()
            val baseline = baselineToRegister?.invoke(partition, channelId) ?: return null
            baselineEvents += "begin"
            return baseline
        }

        override suspend fun abandonLatestPageBaseline(baseline: CanonicalMessageV2EnterBaseline) {
            baselineEvents += "abandon"
        }

        override suspend fun applyLatestPageAfterBaseline(
            partition: DurablePartitionKey,
            channelId: String,
            rawMessages: JsonArray,
            requestedLimit: Int,
            replyParent: ThreadDurableReplyParent?,
            baseline: CanonicalMessageV2EnterBaseline?,
            messageWindow: ChannelMessageWindow?,
            historyLimited: Boolean,
            requestHeadEpoch: Long?,
            stillCurrent: () -> Boolean,
        ) {
            baselineEvents += "apply:${baseline != null}"
            applyLatestPage(partition, channelId, rawMessages, requestedLimit, replyParent, messageWindow, historyLimited, requestHeadEpoch, stillCurrent)
        }

        override suspend fun applyLatestPage(
            partition: DurablePartitionKey,
            channelId: String,
            rawMessages: JsonArray,
            requestedLimit: Int,
            replyParent: ThreadDurableReplyParent?,
            messageWindow: ChannelMessageWindow?,
            historyLimited: Boolean,
            requestHeadEpoch: Long?,
            stillCurrent: () -> Boolean
        ) {
            replyParents += replyParent
            requestHeadEpochs += requestHeadEpoch
            latestCalls +=
                Triple(
                    channelId,
                    rawMessages.map { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content },
                    requestedLimit to stillCurrent()
                )
        }
    }

    // ---- Task #168 step 1: gap catch-up = ONE latest page, decided by anchor presence -------------
    // These stores run on the test's own runTest scheduler (no Unconfined dispatcher): the store's
    // launched jobs interleave only at suspension points, as on production's Main confinement.
    internal class ParkableReadSessionStore(
        initialSession: StoredAuthSession,
    ) : RaftAuthSessionStore {
        private val delegate = InMemoryRaftAuthSessionStore(initialSession)
        private var parkRead = false
        var nextReadStarted = CompletableDeferred<Unit>()
            private set
        var releaseNextRead = CompletableDeferred<Unit>()
            private set

        fun parkNextRead() {
            parkRead = true
            nextReadStarted = CompletableDeferred()
            releaseNextRead = CompletableDeferred()
        }

        override suspend fun readSession(): StoredAuthSession? {
            if (parkRead) {
                parkRead = false
                nextReadStarted.complete(Unit)
                releaseNextRead.await()
            }
            return delegate.readSession()
        }

        override suspend fun saveSession(session: StoredAuthSession) = delegate.saveSession(session)

        override suspend fun updateTokens(accessToken: String, refreshToken: String) =
            delegate.updateTokens(accessToken, refreshToken)

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) =
            delegate.updateSelectedServer(serverId, serverSlug)

        override suspend fun clearSession(reason: String, source: String) =
            delegate.clearSession(reason, source)
    }
    internal class FakeMemberRepository(
        private val serverMembers: List<MemberProfile> = emptyList(),
        private var channelMembers: List<MemberProfile> = emptyList(),
        /**
         * Number of initial `getChannelMembers` calls that should
         * return a transient Error before subsequent calls succeed.
         * Used to verify the loadMembers retry contract.
         */
        private val failChannelMembersFirstCalls: Int = 0
    ) : MemberRepository {
        val serverMemberRequests: MutableList<ServerId> = mutableListOf()
        val channelMemberRequests: MutableList<ChannelId> = mutableListOf()
        val channelMemberCalls: Int get() = channelMemberRequests.size
        val addRequests: MutableList<AddChannelMemberRequest> = mutableListOf()
        val batchRequests: MutableList<AddChannelMembersBatchRequest> = mutableListOf()
        val removeRequests: MutableList<RemoveChannelMemberRequest> = mutableListOf()

        override suspend fun getMembers(
            priority: build.raft.app.data.DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> {
            serverMemberRequests += serverId
            return DataResult.Content(
                MemberSnapshot(
                    server = build.raft.app.model.ServerSummary(id = serverId, name = "Slock"),
                    members = serverMembers
                )
            )
        }

        override suspend fun getChannelMembers(
            priority: build.raft.app.data.DataRequestPriority,
            channelId: ChannelId
        ): DataResult<ChannelMemberSnapshot> {
            channelMemberRequests += channelId
            val callIndex = channelMemberRequests.size
            if (failChannelMembersFirstCalls > 0 && callIndex <= failChannelMembersFirstCalls) {
                return DataResult.Error(ScreenError(code = "transient_network", retryable = true))
            }
            return DataResult.Content(
                ChannelMemberSnapshot(
                    channelId = channelId,
                    members = channelMembers,
                    canAddMembers = true
                )
            )
        }

        override suspend fun getMemberProfile(
            priority: build.raft.app.data.DataRequestPriority,
            memberId: MemberId,
            serverId: ServerId?
        ): DataResult<MemberProfile> = error("unused")

        override suspend fun updateAgentProfile(
            priority: build.raft.app.data.DataRequestPriority,
            request: AgentProfileUpdateRequest
        ): DataResult<MemberProfile> = error("unused")

        override suspend fun uploadAgentAvatar(
            priority: build.raft.app.data.DataRequestPriority,
            request: AgentAvatarUploadRequest
        ): DataResult<MemberProfile> = error("unused")

        override suspend fun getAgentDetail(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentDetailSnapshot> = error("unused")

        override suspend fun getAgentDirectMessages(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentDmSnapshot> = error("unused")

        override suspend fun getAgentReminders(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentReminderSnapshot> = error("unused")

        override suspend fun getAgentActivity(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentActivitySnapshot> = error("unused")

        override suspend fun getAgentWorkspaces(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentWorkspaceSnapshot> = error("unused")

        override suspend fun getAgentWorkspaceTree(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentWorkspaceTreeSnapshot> = error("unused")

        override suspend fun getAgentWorkspaceChildren(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId,
            dirPath: String
        ): DataResult<AgentWorkspaceChildrenSnapshot> = error("unused")

        override suspend fun readAgentWorkspaceFile(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId,
            path: String
        ): DataResult<AgentWorkspaceFilePreviewSnapshot> = error("unused")

        override suspend fun getAgentSkills(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentSkillsSnapshot> = error("unused")

        override suspend fun startAgent(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun stopAgent(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun resetAgent(
            priority: build.raft.app.data.DataRequestPriority,
            request: AgentResetRequest
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun deleteAgent(
            priority: build.raft.app.data.DataRequestPriority,
            agentId: MemberId,
            workspaceChoice: build.raft.app.data.AgentArchiveWorkspaceChoice
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun assignAgentMachine(
            priority: build.raft.app.data.DataRequestPriority,
            request: AgentMachineAssignmentRequest
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun addChannelMember(
            priority: build.raft.app.data.DataRequestPriority,
            request: AddChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> {
            addRequests += request
            val member =
                serverMembers.firstOrNull { it.id == request.memberId }
                    ?: MemberProfile(
                        id = request.memberId,
                        displayName = request.memberId.value,
                        handle = request.memberId.value,
                        kind = request.kind
                    )
            channelMembers = (channelMembers + member).distinctBy { it.id }
            return DataResult.Content(
                ChannelMemberActionResult(
                    channelId = request.channelId,
                    memberId = request.memberId,
                    member = member,
                    added = true
                )
            )
        }


        override suspend fun addChannelMembersBatch(

            priority: build.raft.app.data.DataRequestPriority,

            request: AddChannelMembersBatchRequest

        ): DataResult<AddChannelMembersBatchResult> {

            batchRequests += request

            val userIds = request.userIds.map { it.value }.distinct()

            val agentIds = request.agentIds.map { it.value }.distinct()

            val addedUsers = userIds.filter { id -> channelMembers.none { it.id.value == id } }

            val addedAgents = agentIds.filter { id -> channelMembers.none { it.id.value == id } }

            return DataResult.Content(

                AddChannelMembersBatchResult(

                    addedUserIds = addedUsers,

                    addedAgentIds = addedAgents,

                    alreadyMemberUserIds = userIds - addedUsers.toSet(),

                    alreadyMemberAgentIds = agentIds - addedAgents.toSet()

                )

            )

        }


        override suspend fun removeChannelMember(
            priority: build.raft.app.data.DataRequestPriority,
            request: RemoveChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> {
            removeRequests += request
            val member = channelMembers.firstOrNull { it.id == request.memberId }
            channelMembers = channelMembers.filterNot { it.id == request.memberId }
            return DataResult.Content(
                ChannelMemberActionResult(
                    channelId = request.channelId,
                    memberId = request.memberId,
                    member = member,
                    added = false
                )
            )
        }
    }
    internal class FakeConversationRepository(
        var threadResult: DataResult<ThreadSnapshot> = DataResult.Content(threadSnapshot()),
        var threadReplySummariesResult: DataResult<ThreadReplySummariesSnapshot> =
            DataResult.Content(ThreadReplySummariesSnapshot(threads = emptyList())),
        var threadResultsById: Map<ThreadId, DataResult<ThreadSnapshot>> = emptyMap(),
        var threadResultProvider: ((ThreadId, Boolean, Int) -> DataResult<ThreadSnapshot>)? = null,
        var threadDelayMillis: Long = 0L,
        var olderResult: DataResult<ThreadSnapshot> = DataResult.Content(threadSnapshot(body = "older repo body", id = "m5", seq = 5)),
        var newerResult: DataResult<ThreadSnapshot> = DataResult.Content(threadSnapshot(body = "newer repo body", id = "m12", seq = 12)),
        var messageContextResult: DataResult<ThreadSnapshot> =
            DataResult.Error(ScreenError(code = "message_context_not_configured", retryable = false)),
        var messageContextResultsById: Map<MessageId, DataResult<ThreadSnapshot>> = emptyMap(),
        var messageContextDelayMillis: Long = 0L,
        var createThreadResult: DataResult<ThreadActionResult> =
            DataResult.Content(
                ThreadActionResult(
                    threadChannelId = ChannelId("thread-m1"),
                    changed = true,
                    parentMessageId = MessageId("m1"),
                    replyCount = 1
                )
            )
    ) : ConversationRepository {
        var getThreadStarted: CompletableDeferred<Unit>? = null
        var releaseGetThread: CompletableDeferred<Unit>? = null
        var syncNewerStarted: CompletableDeferred<Unit>? = null
        var releaseSyncNewer: CompletableDeferred<Unit>? = null
        /** Task #168 step 1: the gap catch-up's latest page; `null` answers with [getThread]'s result. */
        var latestResult: DataResult<ThreadSnapshot>? = null
        var latestStarted: CompletableDeferred<Unit>? = null
        var releaseLatest: CompletableDeferred<Unit>? = null
        val latestRequests: MutableList<ThreadId> = mutableListOf()
        val latestLimits: MutableList<Int> = mutableListOf()
        var threadReplySummariesStarted: CompletableDeferred<Unit>? = null
        var releaseThreadReplySummaries: CompletableDeferred<Unit>? = null
        var ignoreThreadReplySummariesCancellation: Boolean = false
        var getReplyThreadStarted: CompletableDeferred<Unit>? = null
        var releaseGetReplyThread: CompletableDeferred<Unit>? = null
        var createOrOpenStarted: CompletableDeferred<Unit>? = null
        var releaseCreateOrOpen: CompletableDeferred<Unit>? = null
        val messageContextStartedById: MutableMap<MessageId, CompletableDeferred<Unit>> = mutableMapOf()
        val releaseMessageContextById: MutableMap<MessageId, CompletableDeferred<Unit>> = mutableMapOf()
        val getThreadRequests: MutableList<ThreadId> = mutableListOf()
        val getThreadIncludeFollowedSummaries: MutableList<Boolean> = mutableListOf()
        val getThreadLimits: MutableList<Int> = mutableListOf()
        val getThreadReplySummariesRequests: MutableList<Pair<ThreadId, List<MessageId>>> = mutableListOf()
        val olderRequests: MutableList<Pair<ThreadId, Long>> = mutableListOf()
        val olderLimits: MutableList<Int> = mutableListOf()
        val newerRequests: MutableList<Pair<ThreadId, Long>> = mutableListOf()
        val newerLimits: MutableList<Int> = mutableListOf()
        val syncNewerRequests: MutableList<Pair<ThreadId, Long>> = mutableListOf()
        val syncNewerLimits: MutableList<Int> = mutableListOf()
        val getMessageContextRequests: MutableList<MessageId> = mutableListOf()
        val getMessageContextChannelIdRequests: MutableList<ChannelId?> = mutableListOf()
        val createOrOpenRequests: MutableList<Pair<ChannelId, MessageId>> = mutableListOf()
        val getReplyThreadRequests: MutableList<Pair<ChannelId, MessageId>> = mutableListOf()
        val getReplyThreadIncludeFollowedSummaries: MutableList<Boolean> = mutableListOf()
        val getReplyThreadLimits: MutableList<Int> = mutableListOf()
        val getReplyThreadPriorities: MutableList<DataRequestPriority> = mutableListOf()
        val getMessageContextPriorities: MutableList<DataRequestPriority> = mutableListOf()

        override suspend fun getThread(
            threadId: ThreadId,
            includeFollowedSummaries: Boolean,
            limit: Int
        ): DataResult<ThreadSnapshot> {
            getThreadRequests += threadId
            getThreadIncludeFollowedSummaries += includeFollowedSummaries
            getThreadLimits += limit
            getThreadStarted?.complete(Unit)
            releaseGetThread?.await()
            if (threadDelayMillis > 0L) {
                delay(threadDelayMillis)
            }
            return threadResultProvider?.invoke(threadId, includeFollowedSummaries, limit)
                ?: threadResultsById[threadId]
                ?: threadResult
        }

        override suspend fun getThreadReplySummaries(
            threadId: ThreadId,
            parentMessageIds: List<MessageId>
        ): DataResult<ThreadReplySummariesSnapshot> {
            getThreadReplySummariesRequests += threadId to parentMessageIds
            threadReplySummariesStarted?.complete(Unit)
            if (ignoreThreadReplySummariesCancellation) {
                withContext(NonCancellable) { releaseThreadReplySummaries?.await() }
            } else releaseThreadReplySummaries?.await()
            return threadReplySummariesResult
        }

        override suspend fun getReplyThread(
            parentChannelId: ChannelId,
            parentMessageId: MessageId,
            includeFollowedSummaries: Boolean,
            limit: Int
        ): DataResult<ThreadSnapshot> {
            getReplyThreadRequests += parentChannelId to parentMessageId
            getReplyThreadIncludeFollowedSummaries += includeFollowedSummaries
            getReplyThreadLimits += limit
            getReplyThreadStarted?.complete(Unit)
            releaseGetReplyThread?.await()
            val resolvedThreadId = (createThreadResult as? DataResult.Content)?.data?.threadChannelId
            val resolvedResult = resolvedThreadId?.let { threadResultsById[ThreadId(it.value)] } ?: threadResult
            if (resolvedResult is DataResult.Empty &&
                resolvedResult.reason == EmptyReason.NoMessages &&
                resolvedThreadId != null
            ) {
                return DataResult.Content(
                    threadSnapshot(
                        threadId = resolvedThreadId.value,
                        messages = emptyList(),
                        conversation =
                            conversationSummary(resolvedThreadId.value).copy(
                                kind = ConversationKind.Thread,
                                channelId = parentChannelId,
                                thread =
                                    ThreadConversation(
                                        parentChannelId = parentChannelId,
                                        parentMessageId = parentMessageId,
                                        streamChannelId = resolvedThreadId
                                    )
                            )
                    )
                )
            }
            return resolvedResult
        }

        override suspend fun getReplyThread(
            parentChannelId: ChannelId,
            parentMessageId: MessageId,
            includeFollowedSummaries: Boolean,
            limit: Int,
            priority: DataRequestPriority
        ): DataResult<ThreadSnapshot> {
            getReplyThreadPriorities += priority
            return getReplyThread(parentChannelId, parentMessageId, includeFollowedSummaries, limit)
        }

        override suspend fun getMessageContext(messageId: MessageId, channelId: ChannelId?): DataResult<ThreadSnapshot> {
            getMessageContextRequests += messageId
            getMessageContextChannelIdRequests += channelId
            messageContextStartedById[messageId]?.complete(Unit)
            releaseMessageContextById[messageId]?.await()
            if (messageContextDelayMillis > 0L) {
                delay(messageContextDelayMillis)
            }
            return messageContextResultsById[messageId] ?: messageContextResult
        }

        override suspend fun getMessageContext(
            messageId: MessageId,
            channelId: ChannelId?,
            priority: DataRequestPriority
        ): DataResult<ThreadSnapshot> {
            getMessageContextPriorities += priority
            return getMessageContext(messageId, channelId)
        }

        override suspend fun loadOlderMessages(
            threadId: ThreadId,
            beforeSeq: Long,
            limit: Int
        ): DataResult<ThreadSnapshot> {
            olderRequests += threadId to beforeSeq
            olderLimits += limit
            return olderResult
        }

        override suspend fun loadNewerMessages(
            threadId: ThreadId,
            afterSeq: Long,
            limit: Int
        ): DataResult<ThreadSnapshot> {
            newerRequests += threadId to afterSeq
            newerLimits += limit
            return newerResult
        }

        override suspend fun syncNewerMessages(
            threadId: ThreadId,
            sinceSeq: Long,
            limit: Int
        ): DataResult<ThreadSnapshot> {
            syncNewerRequests += threadId to sinceSeq
            syncNewerLimits += limit
            syncNewerStarted?.complete(Unit)
            releaseSyncNewer?.await()
            return newerResult
        }

        override suspend fun loadLatestMessages(
            threadId: ThreadId,
            limit: Int
        ): DataResult<ThreadSnapshot> {
            latestRequests += threadId
            latestLimits += limit
            latestStarted?.complete(Unit)
            releaseLatest?.await()
            return latestResult ?: getThread(threadId, includeFollowedSummaries = false, limit = limit)
        }

        override suspend fun getChannelThreads(channelId: ChannelId, limit: Int): DataResult<ThreadListSnapshot> = error("unused")

        override suspend fun getFollowedThreads(serverId: ServerId, limit: Int): DataResult<ThreadListSnapshot> = error("unused")

        override suspend fun createOrOpenThread(channelId: ChannelId, parentMessageId: MessageId): DataResult<ThreadActionResult> {
            createOrOpenRequests += channelId to parentMessageId
            val result = createThreadResult
            createOrOpenStarted?.complete(Unit)
            releaseCreateOrOpen?.await()
            return result
        }

        override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> = error("unused")

        override suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> = error("unused")

        override suspend fun markThreadDone(threadChannelId: ChannelId, frontier: InboxDoneFrontier): DataResult<ThreadActionResult> = error("unused")

        override suspend fun markThreadUndone(threadChannelId: ChannelId): DataResult<ThreadActionResult> = error("unused")

        override suspend fun uploadAttachments(request: AttachmentUploadRequest): DataResult<AttachmentUploadResult> = error("unused")

        override suspend fun getAttachmentOpenTarget(attachmentId: build.raft.app.model.AttachmentId): DataResult<AttachmentOpenTarget> =
            error("unused")

        override suspend fun getChannelFiles(
            channelId: ChannelId,
            limit: Int,
            cursor: String?
        ): DataResult<ChannelFilesSnapshot> = error("unused")

        fun configureReplyThread(threadChannelId: String, parentMessageId: String) {
            val threadId = ThreadId(threadChannelId)
            createThreadResult =
                DataResult.Content(
                    ThreadActionResult(
                        threadChannelId = ChannelId(threadChannelId),
                        changed = true,
                        parentMessageId = MessageId(parentMessageId),
                        replyCount = 1
                    )
                )
            threadResultsById =
                mapOf(
                    threadId to
                        DataResult.Content(
                            threadSnapshot(
                                threadId = threadChannelId,
                                body = "$threadChannelId body",
                                id = "$threadChannelId-reply",
                                conversation =
                                    conversationSummary(threadChannelId).copy(
                                        kind = ConversationKind.Thread,
                                        channelId = ChannelId(threadChannelId),
                                        thread =
                                            ThreadConversation(
                                                parentChannelId = ChannelId("channel-1"),
                                                parentMessageId = MessageId(parentMessageId),
                                                streamChannelId = ChannelId(threadChannelId)
                                            )
                                    )
                            )
                        )
                )
        }

        override suspend fun sendMessage(
            channelId: ChannelId,
            body: String,
            attachmentIds: List<build.raft.app.model.AttachmentId>,
            asTask: Boolean,
            randomId: String?
        ): DataResult<SendMessageResult> = error("unused")
    }
}
