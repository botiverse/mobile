package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.SharedForegroundLoadGate
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.toHomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Fixtures shared by the split `SharedThreadStore<Facet>Test` files (task #41 split of SharedThreadStoreTest). */
internal object SharedThreadStoreTestFixtures {
    /** The foreground gate on the test scheduler's clock (task #41): lease ages and "opened since" are virtual. */
    internal fun TestScope.virtualForegroundLoadGate(): build.raft.app.data.SharedForegroundLoadGate =
        build.raft.app.data.SharedForegroundLoadGate(nowMillis = { testScheduler.currentTime })
    internal fun SharedThreadStore.startLocalSendForCurrentOwner(
        dataSource: SharedThreadDataSource,
        message: RaftThreadMessageItem,
        composerSeed: ThreadLocalSendComposerSeed? = null
    ) {
        val provenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        val admitted =
            admitLocalSend(
                message = message,
                expectedProvenance = provenance,
                expectedRoute = state.value.payload.resolveThreadRouteIdentity(),
                composerSeed = composerSeed
            )
        assertTrue(
            admitted,
            "local send not admitted: writeActionsEnabled=${state.value.writeActionsEnabled} " +
                "access=${state.value.accessResolution} status=${state.value.payload.status}"
        )
    }
    internal fun focusedReplyRoute(): RaftThreadScreenPayload =
        basePayload().copy(
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-parent-1",
            streamChannelId = "thread-parent-1",
            parentChannelId = "parent-channel-1",
            parentMessageId = "parent-1",
            originParentMessageId = "parent-1",
            originStatus = "loading",
            focusedMessageId = "reply-focus-1"
        )
    internal fun directMessagePayload(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-dm-1",
            title = "artin",
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            sourceThreadId = "dm-1",
            streamChannelId = "dm-1",
            status = "loading",
            canSend = true,
            dmPeerName = "artin",
            dmPeerAvatarUrl = "pixel:crown",
            dmPeerAvatarLabel = "AR",
        )
    internal fun replyPayload(parentMessageId: String, threadId: String): RaftThreadScreenPayload =
        basePayload().copy(
            instanceId = "shared-shell-thread-$threadId",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = threadId,
            streamChannelId = threadId,
            parentChannelId = "channel-1",
            parentMessageId = parentMessageId,
            originParentMessageId = parentMessageId,
            title = ThreadReplyPageTitle,
            subtitle = "#general",
            showModeTabs = false
        )
    internal fun activityReplyPayload(
        parentKind: ConversationKind,
        parentChannelId: String,
        parentMessageId: String,
        threadId: String
    ): RaftThreadScreenPayload {
        val request =
            InboxItem(
                key = "thread:$parentMessageId",
                kind = InboxItemKind.Thread,
                channelId = ChannelId(threadId),
                channelName = "reply-thread",
                threadChannelId = ChannelId(threadId),
                parentChannelId = ChannelId(parentChannelId),
                parentChannelName = if (parentKind == ConversationKind.DirectMessage) "Cindy" else "general",
                parentChannelKind = parentKind,
                parentMessageId = MessageId(parentMessageId),
                parentMessagePreview = "parent body",
                latestActivityMessageId = MessageId("reply-1")
            ).toHomeMessageOpenRequestForTest()
        val launch =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = request.conversationIdentity,
                timeContext = null,
                anchorMessageId = request.anchorMessageId,
                originFallback = request.originFallback
            )
        return parseThreadScreenPayload(
            payload = Json.encodeToString(launch),
            instanceId = "activity-reply-$threadId"
        )
    }
    internal fun replyThreadConversation(
        threadId: String = "thread-m1",
        parentChannelId: String = "channel-1",
        parentMessageId: String = "m1",
        joined: Boolean,
        canSend: Boolean
    ): ConversationSummary =
        conversationSummary(threadId).copy(
            kind = ConversationKind.Thread,
            joined = joined,
            permissions = ConversationPermissions(canSend = canSend),
            channelId = ChannelId(threadId),
            thread =
                ThreadConversation(
                    parentChannelId = ChannelId(parentChannelId),
                    parentMessageId = MessageId(parentMessageId),
                    streamChannelId = ChannelId(threadId),
                    parentSurfaceKind = null
                )
        )
    internal fun unresolvedReplyPayload(parentMessageId: String): RaftThreadScreenPayload =
        basePayload().copy(
            instanceId = "shared-shell-thread-pending-$parentMessageId",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = null,
            streamChannelId = null,
            parentChannelId = "channel-1",
            parentMessageId = parentMessageId,
            originParentMessageId = parentMessageId,
            title = ThreadReplyPageTitle,
            subtitle = "#general",
            showModeTabs = false
        )
    internal fun contentPayload(body: String, id: String = "m1"): RaftThreadScreenPayload =
        contentPayloadForRoute(basePayload(), body, id = id)
    internal fun contentPayloadForRoute(
        base: RaftThreadScreenPayload,
        body: String,
        id: String = "m1",
        channelId: String = base.sourceThreadId ?: "channel-1"
    ): RaftThreadScreenPayload =
        buildSharedThreadPayloadFromMessagesPage(
            basePayload = base,
            page =
                ThreadMessagesPage(
                    messages =
                        listOf(
                            WireMessage(
                                id = id,
                                channelId = channelId,
                                senderType = "user",
                                senderId = "u1",
                                senderName = "Artin",
                                messageType = "chat",
                                content = body,
                            ),
                        ),
                    canLoadMoreBefore = false,
                    canLoadMoreAfter = null
                ),
            session = sampleSession()
        )
    internal fun canonicalThreadDataSource(storeScope: CoroutineScope): SharedThreadDataSource =
        testSharedThreadDataSource(
            storeScope = storeScope,
            networkApi = FakeThreadApi(failChannelMessages = true),
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
        )
    internal fun emptyHomeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels = emptyList(),
            directMessages = emptyList(),
            unreadTotal = 0
        )
    internal fun unjoinedHomeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    conversationSummary().copy(
                        joined = false,
                        permissions = ConversationPermissions(canSend = false)
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )
    internal fun joinedHomeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    conversationSummary().copy(
                        joined = true,
                        permissions = ConversationPermissions(canSend = true)
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )
    internal fun localPendingMessage(
        id: String,
        body: String,
        asTask: Boolean = false,
        attachmentIds: List<String> = emptyList(),
        streamChannelId: String = "channel-1",
        randomId: String? = null
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "u1",
            authorName = "Artin",
            isOwn = true,
            body = body,
            threadChannelId = streamChannelId,
            attachments =
                attachmentIds.map { attachmentId ->
                    RaftThreadAttachmentItem(
                        id = attachmentId,
                        name = "$attachmentId.txt",
                        mimeType = "text/plain",
                        sizeLabel = "1 KB"
                    )
                },
            deliveryState = ThreadMessageDeliverySending,
            pendingSendContext =
                RaftThreadPendingSendContext(
                    channelId = "channel-1",
                    body = body,
                    asTask = asTask,
                    attachmentIds = attachmentIds,
                    attachments =
                        attachmentIds.map { attachmentId ->
                            RaftPendingAttachmentItem(
                                id = attachmentId,
                                fileName = "$attachmentId.txt",
                                sizeLabel = "1 KB",
                                mimeType = "text/plain",
                                status = "ready"
                            )
                        },
                    mentionChannelId = "channel-1",
                    streamChannelId = streamChannelId,
                    memberScopedMentions = false,
                    randomId = randomId
                )
        )
    internal fun wrongReplyThreadSnapshot(): ThreadSnapshot =
        threadSnapshot(
            threadId = "wrong-thread",
            body = "wrong cached body",
            id = "wrong-reply",
            messages = listOf(message(threadId = "wrong-thread", id = "wrong-reply", seq = 2, body = "wrong cached body")),
            parentMessage = message(threadId = "other-channel", id = "other-message", seq = 1, body = "wrong parent"),
            conversation =
                conversationSummary("wrong-thread").copy(
                    kind = ConversationKind.Thread,
                    channelId = ChannelId("wrong-thread"),
                    name = "wrong",
                    subtitle = "#wrong",
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("other-channel"),
                            parentMessageId = MessageId("other-message"),
                            streamChannelId = ChannelId("wrong-thread")
                        )
                )
        )
    internal fun canonicalReplyThreadSnapshot(): ThreadSnapshot =
        threadSnapshot(
            threadId = "thread-m1",
            body = "reply body",
            id = "reply-1",
            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")),
            parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body"),
            conversation =
                conversationSummary("thread-m1").copy(
                    kind = ConversationKind.Thread,
                    channelId = ChannelId("thread-m1"),
                    name = "general",
                    subtitle = "#general",
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId("m1"),
                            streamChannelId = ChannelId("thread-m1")
                        )
                )
        )
    internal fun replyHintRepository(
        threadResult: DataResult<ThreadSnapshot> = DataResult.Content(canonicalReplyThreadSnapshot()),
        createThreadResult: DataResult<ThreadActionResult> =
            DataResult.Content(
                ThreadActionResult(
                    threadChannelId = ChannelId("thread-m1"),
                    changed = false,
                    parentMessageId = MessageId("m1"),
                    replyCount = 2
                )
            )
    ): FakeConversationRepository =
        FakeConversationRepository(
            threadResult = threadResult,
            threadResultsById =
                mapOf(
                    ThreadId("wrong-thread") to DataResult.Content(wrongReplyThreadSnapshot()),
                    ThreadId("thread-m1") to DataResult.Content(canonicalReplyThreadSnapshot())
                ),
            createThreadResult = createThreadResult
        )
    internal fun SharedThreadDataSource.cachePayloadForTest(
        payload: RaftThreadScreenPayload
    ): Boolean {
        runBlocking { readSession() }
        return cachePayload(payload, serverId = "server-1")
    }
    internal suspend fun SharedThreadDataSource.threadPayloadOwnerScopeIdForTest(): String =
        requireNotNull(captureReplyCacheProducerContext()?.provenance?.threadPayloadOwnerScopeId)
    internal fun replyResolutionSession(userId: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@example.test", displayName = userId),
            accessToken = "access-$userId",
            refreshToken = "refresh-$userId",
            selectedServerId = "server-a",
            servers =
                listOf(
                    StoredAuthServer(id = "server-a", name = "Server A", slug = "server-a"),
                    StoredAuthServer(id = "server-b", name = "Server B", slug = "server-b")
                )
        )
    internal fun testReplyCacheIdentityAuthority(
        sessionProvider: suspend () -> StoredAuthSession?,
        environmentProvider: () -> String = { "https://prod.example.test" },
        serverIdProvider: (StoredAuthSession) -> String? = { it.currentServerId },
        // Like RealtimeAuthorityRegistry: a session always carries a generation >= 1; 0 is "no session".
        authGenerationProvider: () -> Long = { 1L },
    ): suspend () -> ThreadReplyCacheIdentitySnapshot? = {
        sessionProvider()?.let { session ->
            val environmentId = environmentProvider().trim().trimEnd('/')
            val principalId = session.user.id.trim()
            val serverId = serverIdProvider(session)?.trim().orEmpty()
            ThreadReplyCacheIdentitySnapshot(
                session = session.copy(selectedServerId = serverId),
                environmentId = environmentId,
                principalId = principalId,
                serverId = serverId,
                authGeneration = authGenerationProvider(),
                selectionGeneration = 0L,
            )
        }
    }
    internal fun member(id: String, name: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = name.lowercase(),
            kind = MemberKind.Human,
            presence = Presence.Active
        )
}
