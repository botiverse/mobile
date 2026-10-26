package build.raft.app

import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.data.ChannelProfileUpdateRequest
import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.model.ChannelId
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class SharedThreadStoreProfileAndMessageStoreTest {
    @Test
    fun receiverReadEventLowersUnloadedReplyBadgeThroughStoreAndRejectsOtherServer() = runTest {
        // No coroutines-test; suspend fakes complete inline on this explicit scope.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository = FakeConversationRepository(threadResult = DataResult.Content(threadSnapshot(
            messages = listOf(message(id = "m1", seq = 10, body = "parent", threadChannelId = "reply-channel",
                replyCount = 8, unreadReplyCount = 4)))))
        val dataSource = testSharedThreadDataSource(
            storeScope = backgroundScope,
            networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId }, conversationRepository = repository)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(4, store.state.value.payload.messages.single().unreadReplyCount)
        val event = build.raft.app.model.PerReceiverReadStateChanged(ServerId("wrong-server"),
            listOf(build.raft.app.model.PerReceiverReadStateScope(ChannelId("reply-channel"), 10, 2, 0)))
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(event))
        assertEquals(4, store.state.value.payload.messages.single().unreadReplyCount)
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(event.copy(serverId = ServerId("server-1"))))
        assertEquals(0, store.state.value.payload.messages.single().unreadReplyCount)
        store.accept(SharedThreadIntent.Refresh)
        assertEquals(0, store.state.value.payload.messages.single().unreadReplyCount)
        fun contentEvent(seq: Long?) = ThreadUpdated(
            parentMessageId = MessageId("m1"), threadChannelId = ChannelId("reply-channel"),
            replyCount = 8, lastReplyAt = "2026-05-19T16:13:00Z", participantIds = emptyList(),
            unreadCount = 4, firstUnreadMessageId = MessageId("reply-old"),
            latestReply = seq?.let { ReplyPreview(messageId = "reply-$it", seq = it, content = "reply",
                senderId = "u2", senderType = "human", senderName = "Peer", createdAt = "2026-05-19T16:13:00Z") })
        for (seq in listOf(null, 9L, 10L)) {
            store.accept(SharedThreadIntent.ApplyRealtimeEvent(contentEvent(seq)))
            assertEquals(0, store.state.value.payload.messages.single().unreadReplyCount)
            assertNull(store.state.value.payload.messages.single().firstUnreadReplyMessageId)
        }
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(contentEvent(11).copy(unreadCount = 1,
            firstUnreadMessageId = MessageId("reply-11"))))
        assertEquals(1, store.state.value.payload.messages.single().unreadReplyCount)
        store.clear()
        scope.cancel()
    }
    @Test
    fun receiverZeroReadEventClearsStaleJumpThroughStore() = runTest {
        // No coroutines-test; suspend fakes complete inline on this explicit scope.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository = FakeConversationRepository(threadResult = DataResult.Content(threadSnapshot(
            messages = listOf(message(id = "m1", seq = 10, body = "parent", threadChannelId = "reply-channel",
                replyCount = 8).copy(firstUnreadReplyMessageId = MessageId("reply-old"))))))
        val dataSource = testSharedThreadDataSource(
            storeScope = backgroundScope,
            networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId }, conversationRepository = repository)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(0, store.state.value.payload.messages.single().unreadReplyCount)
        assertEquals("reply-old", store.state.value.payload.messages.single().firstUnreadReplyMessageId)
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(build.raft.app.model.PerReceiverReadStateChanged(
            ServerId("server-1"), listOf(build.raft.app.model.PerReceiverReadStateScope(ChannelId("reply-channel"), 10, 2, 0)))))
        assertNull(store.state.value.payload.messages.single().firstUnreadReplyMessageId)
        store.clear()
        scope.cancel()
    }
    @Test
    fun readOnlyReplyFallbackClassifiesEveryThreadMutationAsWriteAccess() {
        val threadMutations =
            listOf(
                "followMessageThread",
                "unfollowMessageThread",
                "toggleMessageSaved",
                "commitActionCard",
                "toggleReaction",
                "submitAddMember",
                "removeMember",
                "settingsAction",
                "convertMessageToTask",
                "setTaskStatus",
                "markTaskDone",
                "reopenTask",
                "joinChannel",
                "retryLocalSend",
                "cancelLocalSend",
                "deleteLocalFailedSend"
            )
        val composerMutations =
            listOf(
                "send",
                "inviteMention",
                "notifyMention",
                "attachImage",
                "attachFile",
                "removeAttachment",
                "retryAttachments"
            )

        assertTrue(threadMutations.all(::threadEventRequiresWriteAccess))
        assertTrue(composerMutations.all(::threadComposerEventRequiresWriteAccess))
        var threadNetworkCalls = 0
        var composerNetworkCalls = 0
        threadMutations.forEach { action ->
            if (threadEventAllowed(writeActionsEnabled = false, action)) threadNetworkCalls += 1
        }
        composerMutations.forEach { action ->
            if (threadComposerEventAllowed(writeActionsEnabled = false, action)) composerNetworkCalls += 1
        }
        assertEquals(0, threadNetworkCalls)
        assertEquals(0, composerNetworkCalls)
        assertTrue(threadMutations.all { action -> threadEventAllowed(writeActionsEnabled = true, action) })
        assertTrue(composerMutations.all { action -> threadComposerEventAllowed(writeActionsEnabled = true, action) })
        assertFalse(threadEventRequiresWriteAccess("openMessageThread"))
        assertFalse(threadEventRequiresWriteAccess("copyMessage"))
        assertFalse(threadComposerEventRequiresWriteAccess("change"))

        val gatedPayload =
            basePayload().copy(
                canAddMembers = true,
                members = listOf(RaftThreadMemberItem(id = "u2", displayName = "Member", canRemove = true)),
                settingsActions = listOf(RaftThreadSettingsAction(id = "archive")),
                showSettingsAction = true,
                showMessageSaveAction = true,
                showReactionActions = true,
                showTaskStatusActions = true
            ).withThreadWriteActionsEnabled(false)
        assertFalse(gatedPayload.canAddMembers)
        assertFalse(gatedPayload.members.single().canRemove)
        assertEquals(emptyList(), gatedPayload.settingsActions)
        assertFalse(gatedPayload.showSettingsAction)
        assertFalse(gatedPayload.showMessageSaveAction)
        assertFalse(gatedPayload.showReactionActions)
        assertFalse(gatedPayload.showTaskStatusActions)

        val gatedTimeline =
            basePayload().toThreadTimelineSlice().copy(canConvertMessagesToTasks = true)
                .withThreadWriteActionsEnabled(false)
        assertFalse(gatedTimeline.showMessageSaveAction)
        assertFalse(gatedTimeline.showReactionActions)
        assertFalse(gatedTimeline.showTaskStatusActions)
        assertFalse(gatedTimeline.canConvertMessagesToTasks)
    }
    @Test
    fun mountedAttachmentCommentWritesRecheckCurrentAuthorityBeforeApiCall() = runTest {
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        var writeActionsEnabled = true
        val mountedDialog =
            ThreadAttachmentCommentsDialogState(
                attachment = RaftThreadAttachmentItem(id = "attachment-1", name = "notes.txt"),
                status = "loaded",
                draft = TextFieldValue("comment")
            )

        assertNotNull(mountedDialog.retainForThreadWriteAuthority(writeActionsEnabled))
        writeActionsEnabled = false
        assertNull(mountedDialog.retainForThreadWriteAuthority(writeActionsEnabled))

        val blockedSend =
            runThreadWriteActionIfEnabled(
                writeActionsEnabled = { writeActionsEnabled },
                blockedCode = "thread_attachment_comment_write_disabled"
            ) {
                dataSource.sendAttachmentComment("attachment-1", "comment")
            }
        val blockedResolve =
            runThreadWriteActionIfEnabled(
                writeActionsEnabled = { writeActionsEnabled },
                blockedCode = "thread_attachment_comment_write_disabled"
            ) {
                dataSource.setMessageReaction("comment-1", "✅", selected = true)
            }
        assertTrue(blockedSend is DataResult.Error)
        assertTrue(blockedResolve is DataResult.Error)
        assertEquals(emptyList(), api.sentAttachmentComments)
        assertEquals(emptyList(), api.addedReactions)

        writeActionsEnabled = true
        assertTrue(
            runThreadWriteActionIfEnabled(
                writeActionsEnabled = { writeActionsEnabled },
                blockedCode = "thread_attachment_comment_write_disabled"
            ) {
                dataSource.sendAttachmentComment("attachment-1", "comment")
            } is DataResult.Content
        )
        assertTrue(
            runThreadWriteActionIfEnabled(
                writeActionsEnabled = { writeActionsEnabled },
                blockedCode = "thread_attachment_comment_write_disabled"
            ) {
                dataSource.setMessageReaction("comment-1", "✅", selected = true)
            } is DataResult.Content
        )
        assertEquals(listOf("attachment-1" to "comment"), api.sentAttachmentComments)
        assertEquals(listOf("comment-1" to "✅"), api.addedReactions)
    }
    @Test
    fun channelProfileUpdateAppliesCanonicalResponseAndPublishesHomeProjection() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository =
            FakeChannelRepository(
                homeResult = { DataResult.Empty(EmptyReason.NoChannels) },
                profileUpdater = {
                    DataResult.Content(
                        ChannelProfileUpdateResult(ChannelId("channel-1"), "canonical-name", "Canonical description")
                    )
                }
            )
        val published = mutableListOf<ChannelProfileUpdateResult>()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = repository,
                channelProfileUpdateSink = published::add
            )
        val initial = basePayload().copy(title = "general", subtitle = "Old description")
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, initialPayload = initial)

        store.accept(
            SharedThreadIntent.UpdateChannelProfile(
                channelId = "channel-1",
                originalName = "general",
                originalDescription = "Old description",
                name = " requested-name ",
                description = " Requested description "
            )
        )

        assertEquals(
            listOf(ChannelProfileUpdateRequest(ChannelId("channel-1"), "requested-name", "Requested description")),
            repository.profileUpdateRequests
        )
        assertEquals("canonical-name", store.state.value.payload.title)
        assertEquals("Canonical description", store.state.value.payload.subtitle)
        assertEquals(ChannelProfileUpdateStatus.Succeeded, store.state.value.channelProfileUpdateStatus)
        assertEquals(
            listOf(ChannelProfileUpdateResult(ChannelId("channel-1"), "canonical-name", "Canonical description")),
            published
        )
        scope.cancel()
    }
    @Test
    fun unchangedChannelProfileClosesWithoutRequest() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository = FakeChannelRepository()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = repository
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                initialPayload = basePayload().copy(title = "general", subtitle = "Description")
            )

        store.accept(
            SharedThreadIntent.UpdateChannelProfile(
                channelId = "channel-1",
                originalName = "general",
                originalDescription = "Description",
                name = " general ",
                description = " Description "
            )
        )

        assertEquals(emptyList(), repository.profileUpdateRequests)
        assertEquals(ChannelProfileUpdateStatus.Succeeded, store.state.value.channelProfileUpdateStatus)
        scope.cancel()
    }
    @Test
    fun failedChannelProfileUpdateKeepsCanonicalStateAndExposesError() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository =
            FakeChannelRepository(
                profileUpdater = { DataResult.Error(ScreenError("channel_name_taken", retryable = false)) }
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = repository
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                initialPayload = basePayload().copy(title = "general", subtitle = "Old description")
            )

        store.accept(
            SharedThreadIntent.UpdateChannelProfile(
                channelId = "channel-1",
                originalName = "general",
                originalDescription = "Old description",
                name = "renamed",
                description = "New description"
            )
        )

        assertEquals("general", store.state.value.payload.title)
        assertEquals("Old description", store.state.value.payload.subtitle)
        assertEquals(ChannelProfileUpdateStatus.Failed, store.state.value.channelProfileUpdateStatus)
        assertEquals("channel_name_taken", store.state.value.channelProfileUpdateErrorCode)
        scope.cancel()
    }
    @Test
    fun mismatchedChannelProfileResponseMutatesNeitherThreadNorHomeProjection() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository =
            FakeChannelRepository(
                profileUpdater = {
                    DataResult.Content(ChannelProfileUpdateResult(ChannelId("channel-2"), "wrong-channel", "Wrong"))
                }
            )
        val published = mutableListOf<ChannelProfileUpdateResult>()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = repository,
                channelProfileUpdateSink = published::add
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                initialPayload = basePayload().copy(title = "general", subtitle = "Old")
            )

        store.accept(
            SharedThreadIntent.UpdateChannelProfile(
                channelId = "channel-1",
                originalName = "general",
                originalDescription = "Old",
                name = "renamed",
                description = "New"
            )
        )

        assertEquals("general", store.state.value.payload.title)
        assertEquals("Old", store.state.value.payload.subtitle)
        assertEquals(ChannelProfileUpdateStatus.Failed, store.state.value.channelProfileUpdateStatus)
        assertEquals("channel_update_identity_mismatch", store.state.value.channelProfileUpdateErrorCode)
        assertEquals(emptyList(), published)
        scope.cancel()
    }
    @Test
    fun routeSwitchDuringChannelProfileProjectionCannotCompleteNewRouteStatus() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val sinkStarted = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        val repository =
            FakeChannelRepository(
                homeResult = { DataResult.Empty(EmptyReason.NoChannels) },
                profileUpdater = {
                    DataResult.Content(ChannelProfileUpdateResult(ChannelId("channel-1"), "renamed", "New"))
                }
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = repository,
                channelProfileUpdateSink = {
                    sinkStarted.complete(Unit)
                    releaseSink.await()
                }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, initialPayload = basePayload())
        val updateJob =
            scope.launch {
                store.accept(
                    SharedThreadIntent.UpdateChannelProfile(
                        channelId = "channel-1",
                        originalName = "channel-1",
                        originalDescription = "",
                        name = "renamed",
                        description = "New"
                    )
                )
            }
        sinkStarted.await()
        store.accept(
            SharedThreadIntent.Start(
                basePayload().copy(
                    instanceId = "channel-2",
                    sourceThreadId = "channel-2",
                    streamChannelId = "channel-2",
                    title = "channel-2"
                )
            )
        )
        releaseSink.complete(Unit)
        updateJob.join()

        assertEquals("channel-2", store.state.value.payload.resolveThreadRouteIdentity().timelineChannelId)
        assertFalse(store.state.value.payload.title == "renamed")
        assertEquals(ChannelProfileUpdateStatus.Idle, store.state.value.channelProfileUpdateStatus)
        assertEquals(null, store.state.value.channelProfileUpdateErrorCode)
        scope.cancel()
    }
    @Test
    fun channelProfileProjectionFailureDoesNotLeaveSuccessfulRequestInFlight() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository =
            FakeChannelRepository(
                profileUpdater = {
                    DataResult.Content(ChannelProfileUpdateResult(ChannelId("channel-1"), "renamed", "New"))
                }
            )
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = repository,
                channelProfileUpdateSink = { error("projection unavailable") }
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = logs::add,
                initialPayload = basePayload().copy(title = "general")
            )

        store.accept(
            SharedThreadIntent.UpdateChannelProfile(
                channelId = "channel-1",
                originalName = "general",
                originalDescription = "",
                name = "renamed",
                description = "New"
            )
        )

        assertEquals("renamed", store.state.value.payload.title)
        assertEquals(ChannelProfileUpdateStatus.Succeeded, store.state.value.channelProfileUpdateStatus)
        assertTrue(logs.any { it.startsWith("thread_channel_profile_update_projection_failed channelId=channel-1") })
        scope.cancel()
    }
    @Test
    fun lateChannelProfileResponseCannotMutateAnotherRoute() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository =
            FakeChannelRepository(
                homeResult = { DataResult.Empty(EmptyReason.NoChannels) },
                profileUpdater = {
                    started.complete(Unit)
                    release.await()
                    DataResult.Content(ChannelProfileUpdateResult(ChannelId("channel-1"), "late-name", "Late"))
                }
            )
        val published = mutableListOf<ChannelProfileUpdateResult>()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = repository,
                channelProfileUpdateSink = published::add
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, initialPayload = basePayload())
        val updateJob =
            scope.launch {
                store.accept(
                    SharedThreadIntent.UpdateChannelProfile(
                        channelId = "channel-1",
                        originalName = "channel-1",
                        originalDescription = "",
                        name = "renamed",
                        description = "New"
                    )
                )
            }
        started.await()
        store.accept(
            SharedThreadIntent.Start(
                basePayload().copy(
                    instanceId = "channel-2",
                    sourceThreadId = "channel-2",
                    streamChannelId = "channel-2",
                    title = "channel-2"
                )
            )
        )
        release.complete(Unit)
        updateJob.join()

        assertEquals("channel-2", store.state.value.payload.resolveThreadRouteIdentity().timelineChannelId)
        assertFalse(store.state.value.payload.title == "late-name")
        assertEquals(emptyList(), published)
        scope.cancel()
    }
    @Test
    fun sharedThreadMessageStoreDeduplicatesConcurrentMessageFetches() = runTest {
        val store = SharedThreadMessageStore(scope = backgroundScope)
        val server = ServerId("server-1")
        val parentChannel = ChannelId("channel-1")
        val parentMessageId = MessageId("m1")
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var fetchCount = 0
        val first =
            async {
                store.resolve(server, parentChannel, parentMessageId) {
                    fetchCount += 1
                    firstFetchStarted.complete(Unit)
                    releaseFetch.await()
                    SharedThreadMessageResolution.Loaded(
                        message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
                    )
                }
            }
        firstFetchStarted.await()
        val second =
            async {
                store.resolve(server, parentChannel, parentMessageId) {
                    fetchCount += 1
                    SharedThreadMessageResolution.Loaded(
                        message(threadId = "channel-1", id = "m1", seq = 1, body = "duplicate fetch")
                    )
                }
            }

        releaseFetch.complete(Unit)

        assertEquals("parent body", (first.await() as SharedThreadMessageResolution.Loaded).message.body)
        assertEquals("parent body", (second.await() as SharedThreadMessageResolution.Loaded).message.body)
        assertEquals(1, fetchCount)
        assertEquals("parent body", store.get(server, parentChannel, parentMessageId)?.body)
    }
    @Test
    fun sharedThreadMessageStoreSeparatesSameMessageIdentityAcrossServers() = runTest {
        val store = SharedThreadMessageStore(scope = backgroundScope)
        val channel = ChannelId("channel-1")
        val messageId = MessageId("m1")

        val first =
            store.resolve(ServerId("server-1"), channel, messageId) {
                SharedThreadMessageResolution.Loaded(
                    message(threadId = "channel-1", id = "m1", seq = 1, body = "server one")
                )
            }
        val second =
            store.resolve(ServerId("server-2"), channel, messageId) {
                SharedThreadMessageResolution.Loaded(
                    message(threadId = "channel-1", id = "m1", seq = 1, body = "server two")
                )
            }

        assertEquals("server one", (first as SharedThreadMessageResolution.Loaded).message.body)
        assertEquals("server two", (second as SharedThreadMessageResolution.Loaded).message.body)
        assertEquals("server one", store.get(ServerId("server-1"), channel, messageId)?.body)
        assertEquals("server two", store.get(ServerId("server-2"), channel, messageId)?.body)
    }
    @Test
    fun sharedThreadMessageStoreSeparatesSameMessageIdentityAcrossEnvironmentAndPrincipalScopes() = runTest {
        val store = SharedThreadMessageStore(scope = backgroundScope)
        val channel = ChannelId("channel-1")
        val messageId = MessageId("m1")
        val prodAccountOne = "https://prod.example.test\u001faccount-1\u001fserver-1"
        val prodAccountTwo = "https://prod.example.test\u001faccount-2\u001fserver-1"
        val stagingAccountOne = "https://staging.example.test\u001faccount-1\u001fserver-1"

        store.upsertScoped(
            prodAccountOne,
            channel,
            message(threadId = "channel-1", id = "m1", seq = 1, body = "prod account one")
        )
        store.upsertScoped(
            prodAccountTwo,
            channel,
            message(threadId = "channel-1", id = "m1", seq = 1, body = "prod account two")
        )
        store.upsertScoped(
            stagingAccountOne,
            channel,
            message(threadId = "channel-1", id = "m1", seq = 1, body = "staging account one")
        )

        assertEquals("prod account one", store.getScoped(prodAccountOne, channel, messageId)?.body)
        assertEquals("prod account two", store.getScoped(prodAccountTwo, channel, messageId)?.body)
        assertEquals("staging account one", store.getScoped(stagingAccountOne, channel, messageId)?.body)
    }
    @Test
    fun sharedThreadMessageStoreDoesNotShareInFlightParentFetchAcrossPrincipalScopes() = runTest {
        val store = SharedThreadMessageStore(scope = backgroundScope)
        val channel = ChannelId("channel-1")
        val messageId = MessageId("m1")
        val releaseAccountOne = CompletableDeferred<Unit>()
        var fetchCount = 0
        val accountOne =
            async {
                store.resolveScoped("prod\u001faccount-1\u001fserver-1", channel, messageId) {
                    fetchCount += 1
                    releaseAccountOne.await()
                    SharedThreadMessageResolution.Loaded(
                        message(threadId = "channel-1", id = "m1", seq = 1, body = "account one")
                    )
                }
            }
        val accountTwo =
            store.resolveScoped("prod\u001faccount-2\u001fserver-1", channel, messageId) {
                fetchCount += 1
                SharedThreadMessageResolution.Loaded(
                    message(threadId = "channel-1", id = "m1", seq = 1, body = "account two")
                )
            }
        releaseAccountOne.complete(Unit)

        assertEquals("account one", (accountOne.await() as SharedThreadMessageResolution.Loaded).message.body)
        assertEquals("account two", (accountTwo as SharedThreadMessageResolution.Loaded).message.body)
        assertEquals(2, fetchCount)
    }
    @Test
    fun sharedThreadMessageStoreTreatsForwardBundleOnlyMessageAsHydrated() = runTest {
        val store = SharedThreadMessageStore(scope = backgroundScope)
        val server = ServerId("server-1")
        val channel = ChannelId("channel-1")
        val messageId = MessageId("forward-1")
        var fetchCount = 0
        val forwarded =
            message(
                id = messageId.value,
                seq = 1,
                body = "",
                forwardBundle =
                    ForwardMessageBundle(
                        messages =
                            listOf(
                                ForwardMessageItem(
                                    id = "source-1",
                                    authorId = "u1",
                                    authorName = "Artin",
                                    body = "forwarded body"
                                )
                            )
                    )
            )

        val first =
            store.resolve(server, channel, messageId) {
                fetchCount += 1
                SharedThreadMessageResolution.Loaded(forwarded)
            }
        val second =
            store.resolve(server, channel, messageId) {
                fetchCount += 1
                SharedThreadMessageResolution.Error("duplicate_fetch")
            }

        assertIs<SharedThreadMessageResolution.Loaded>(first)
        assertIs<SharedThreadMessageResolution.Loaded>(second)
        assertEquals(1, fetchCount)
        assertEquals("forwarded body", store.get(server, channel, messageId)?.forwardBundle?.messages?.single()?.body)
    }
    @Test
    fun sharedThreadMessageStoreProducerSurvivesFirstWaiterCancellation() = runTest {
        val producerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadMessageStore(scope = producerScope)
        val server = ServerId("server-1")
        val channel = ChannelId("channel-1")
        val messageId = MessageId("m1")
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var fetchCount = 0
        val first =
            async {
                store.resolve(server, channel, messageId) {
                    fetchCount += 1
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    SharedThreadMessageResolution.Loaded(
                        message(threadId = "channel-1", id = "m1", seq = 1, body = "shared result")
                    )
                }
            }
        fetchStarted.await()
        val second =
            async {
                store.resolve(server, channel, messageId) {
                    fetchCount += 1
                    SharedThreadMessageResolution.Error("duplicate_fetch")
                }
            }

        yield()
        first.cancel()
        releaseFetch.complete(Unit)

        assertEquals("shared result", (second.await() as SharedThreadMessageResolution.Loaded).message.body)
        assertEquals(1, fetchCount)
        assertEquals("shared result", store.get(server, channel, messageId)?.body)
        producerScope.cancel()
    }
    @Test
    fun sharedThreadMessageStoreNeverJoinsACompletedProducerAwaitingCleanup() = runTest {
        // The producer scope's in-flight cleanup is a separate coroutine launched on completion.
        // Hold it back (as a busy Default pool does) and make sure a new request does not join the
        // finished producer and get its stale non-cacheable Missing/Error back.
        val pending = ArrayDeque<Runnable>()
        val manualDispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    pending.addLast(block)
                }
            }
        val producerScope = CoroutineScope(SupervisorJob() + manualDispatcher)
        val store = SharedThreadMessageStore(scope = producerScope)
        val channel = ChannelId("parent-channel-1")
        val messageId = MessageId("parent-1")
        var fetchCount = 0
        val first =
            async(start = CoroutineStart.UNDISPATCHED) {
                store.resolveScoped("scope-1", channel, messageId) {
                    fetchCount += 1
                    SharedThreadMessageResolution.Missing
                }
            }
        // Run only the producer; its completion queues the in-flight cleanup, which stays pending.
        pending.removeFirst().run()
        assertEquals(SharedThreadMessageResolution.Missing, first.await())
        assertEquals(1, pending.size, "the in-flight cleanup should still be queued")

        val second =
            async(start = CoroutineStart.UNDISPATCHED) {
                store.resolveScoped("scope-1", channel, messageId) {
                    fetchCount += 1
                    SharedThreadMessageResolution.Error("thread_parent_timeout")
                }
            }
        while (!second.isCompleted) {
            pending.removeFirstOrNull()?.run() ?: yield()
        }

        assertEquals(SharedThreadMessageResolution.Error("thread_parent_timeout"), second.await())
        assertEquals(2, fetchCount)
        producerScope.cancel()
    }
    @Test
    fun sharedThreadMessageStoreLateFetchCannotOverwriteNewerSeed() = runTest {
        val producerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadMessageStore(scope = producerScope)
        val server = ServerId("server-1")
        val channel = ChannelId("channel-1")
        val messageId = MessageId("m1")
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val resolving =
            async {
                store.resolve(server, channel, messageId) {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    SharedThreadMessageResolution.Loaded(
                        message(threadId = "channel-1", id = "m1", seq = 1, body = "stale fetch")
                    )
                }
            }
        fetchStarted.await()
        store.upsert(
            server,
            channel,
            message(threadId = "channel-1", id = "m1", seq = 2, body = "newer seed")
        )
        releaseFetch.complete(Unit)

        assertEquals("newer seed", (resolving.await() as SharedThreadMessageResolution.Loaded).message.body)
        assertEquals("newer seed", store.get(server, channel, messageId)?.body)
        producerScope.cancel()
    }
}
