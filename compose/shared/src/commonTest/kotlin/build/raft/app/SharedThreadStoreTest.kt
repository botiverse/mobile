package build.raft.app

import build.raft.app.data.AddChannelMemberRequest
import build.raft.app.data.AddChannelMembersBatchRequest
import build.raft.app.data.AddChannelMembersBatchResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.ComposeLocalDataStore
import build.raft.app.data.AgentActivitySnapshot
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDmSnapshot
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.AgentMachineAssignmentRequest
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.AgentAvatarUploadRequest
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
import build.raft.app.data.ChannelMemberActionResult
import build.raft.app.data.ChannelProfileUpdateRequest
import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.ChannelMemberSnapshot
import build.raft.app.data.ChannelArchiveResult
import build.raft.app.data.ChannelActivityMuteSettings
import build.raft.app.data.ChannelFilesSnapshot
import build.raft.app.data.ChannelJoinResult
import build.raft.app.data.ChannelLeaveResult
import build.raft.app.data.ChannelDeleteResult
import build.raft.app.data.ChannelRepository
import build.raft.app.data.ChannelUpdateResult
import build.raft.app.data.ChannelVisibility
import build.raft.app.data.ChannelSnapshot
import build.raft.app.data.ConversationRepository
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.CreateChannelResult
import build.raft.app.data.DataResult
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.ActivityReplyThreadPrewarmKey
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.DirectMessageSnapshot
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.MemberRepository
import build.raft.app.data.MemberSnapshot
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.MessageReactionSummary
import build.raft.app.data.MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.SendMessageResult
import build.raft.app.data.SavedMessageStatusSnapshot
import build.raft.app.data.SavedStatusRepository
import build.raft.app.data.SharedCreateAgentCoordinator
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.SlockAgentsApi
import build.raft.app.data.SlockAgentsApiClient
import build.raft.app.data.SlockComputersApi
import build.raft.app.data.SlockComputersApiClient
import build.raft.app.data.SlockIntegrationsActionsPushApi
import build.raft.app.data.SlockIntegrationsActionsPushApiClient
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardMetadataItem
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.ThreadListSnapshot
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageType
import build.raft.app.model.MessageUpdated
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.ThreadRepliesSyncHost
import build.raft.app.model.ThreadId
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Thread-safe append/snapshot log buffer for tests that run stores on
 * multi-threaded dispatchers. commonTest compiles for native targets too, so
 * JVM types like CopyOnWriteArrayList are off-limits, and the OHOS toolchain
 * (Kotlin 2.0.x) predates kotlin.concurrent.atomics — an unlimited Channel is
 * the portable thread-safe append primitive (#214). `any` drains newly
 * arrived entries into a snapshot list owned by the single polling thread.
 */
private class ConcurrentTestLogBuffer {
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

class SharedThreadStoreTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun receiverReadEventLowersUnloadedReplyBadgeThroughStoreAndRejectsOtherServer() = kotlinx.coroutines.test.runTest {
        val repository = FakeConversationRepository(threadResult = DataResult.Content(threadSnapshot(
            messages = listOf(message(id = "m1", seq = 10, body = "parent", threadChannelId = "reply-channel",
                replyCount = 8, unreadReplyCount = 4)))))
        val dataSource = testSharedThreadDataSource(
            networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
            sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId }, conversationRepository = repository)
        val store = SharedThreadStore(dataSource = dataSource, scope = backgroundScope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        testScheduler.runCurrent()
        assertEquals(4, store.state.value.payload.messages.single().unreadReplyCount)
        val event = build.raft.app.model.PerReceiverReadStateChanged(ServerId("wrong-server"),
            listOf(build.raft.app.model.PerReceiverReadStateScope(ChannelId("reply-channel"), 10, 2, 0)))
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(event))
        assertEquals(4, store.state.value.payload.messages.single().unreadReplyCount)
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(event.copy(serverId = ServerId("server-1"))))
        assertEquals(0, store.state.value.payload.messages.single().unreadReplyCount)
        store.accept(SharedThreadIntent.Refresh)
        testScheduler.runCurrent()
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
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun receiverZeroReadEventClearsStaleJumpThroughStore() = kotlinx.coroutines.test.runTest {
        val repository = FakeConversationRepository(threadResult = DataResult.Content(threadSnapshot(
            messages = listOf(message(id = "m1", seq = 10, body = "parent", threadChannelId = "reply-channel",
                replyCount = 8).copy(firstUnreadReplyMessageId = MessageId("reply-old"))))))
        val dataSource = testSharedThreadDataSource(
            networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
            sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId }, conversationRepository = repository)
        val store = SharedThreadStore(dataSource = dataSource, scope = backgroundScope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        testScheduler.runCurrent()
        assertEquals(0, store.state.value.payload.messages.single().unreadReplyCount)
        assertEquals("reply-old", store.state.value.payload.messages.single().firstUnreadReplyMessageId)
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(build.raft.app.model.PerReceiverReadStateChanged(
            ServerId("server-1"), listOf(build.raft.app.model.PerReceiverReadStateScope(ChannelId("reply-channel"), 10, 2, 0)))))
        assertNull(store.state.value.payload.messages.single().firstUnreadReplyMessageId)
        store.clear()
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
                members = listOf(KuiklyThreadMemberItem(id = "u2", displayName = "Member", canRemove = true)),
                settingsActions = listOf(KuiklyThreadSettingsAction(id = "archive")),
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
    fun mountedAttachmentCommentWritesRecheckCurrentAuthorityBeforeApiCall() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        var writeActionsEnabled = true
        val mountedDialog =
            ThreadAttachmentCommentsDialogState(
                attachment = KuiklyThreadAttachmentItem(id = "attachment-1", name = "notes.txt"),
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
    fun channelProfileUpdateAppliesCanonicalResponseAndPublishesHomeProjection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
    fun unchangedChannelProfileClosesWithoutRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeChannelRepository()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
    fun failedChannelProfileUpdateKeepsCanonicalStateAndExposesError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeChannelRepository(
                profileUpdater = { DataResult.Error(ScreenError("channel_name_taken", retryable = false)) }
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
    fun mismatchedChannelProfileResponseMutatesNeitherThreadNorHomeProjection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeChannelRepository(
                profileUpdater = {
                    DataResult.Content(ChannelProfileUpdateResult(ChannelId("channel-2"), "wrong-channel", "Wrong"))
                }
            )
        val published = mutableListOf<ChannelProfileUpdateResult>()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
    fun routeSwitchDuringChannelProfileProjectionCannotCompleteNewRouteStatus() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
    fun channelProfileProjectionFailureDoesNotLeaveSuccessfulRequestInFlight() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeChannelRepository(
                profileUpdater = {
                    DataResult.Content(ChannelProfileUpdateResult(ChannelId("channel-1"), "renamed", "New"))
                }
            )
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
    fun lateChannelProfileResponseCannotMutateAnotherRoute() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
    fun sharedThreadMessageStoreDeduplicatesConcurrentMessageFetches() = runBlocking {
        val store = SharedThreadMessageStore()
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
    fun sharedThreadMessageStoreSeparatesSameMessageIdentityAcrossServers() = runBlocking {
        val store = SharedThreadMessageStore()
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
    fun sharedThreadMessageStoreSeparatesSameMessageIdentityAcrossEnvironmentAndPrincipalScopes() = runBlocking {
        val store = SharedThreadMessageStore()
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
    fun sharedThreadMessageStoreDoesNotShareInFlightParentFetchAcrossPrincipalScopes() = runBlocking {
        val store = SharedThreadMessageStore()
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
    fun sharedThreadMessageStoreTreatsForwardBundleOnlyMessageAsHydrated() = runBlocking {
        val store = SharedThreadMessageStore()
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
    fun sharedThreadMessageStoreProducerSurvivesFirstWaiterCancellation() = runBlocking {
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    fun sharedThreadMessageStoreLateFetchCannotOverwriteNewerSeed() = runBlocking {
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    @Test
    fun threadRouteIdentitySeparatesUnresolvedReplyParentFromResolvedTimeline() {
        val unresolved = unresolvedReplyPayload(parentMessageId = "m1").resolveThreadRouteIdentity()
        val resolved = replyPayload(parentMessageId = "m1", threadId = "thread-m1").resolveThreadRouteIdentity()

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, unresolved.surfaceKind)
        assertEquals("reply-parent:channel-1:m1", unresolved.instanceKey)
        assertNull(unresolved.timelineKey)
        assertFalse(unresolved.canRenderTimelineCandidates)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, resolved.surfaceKind)
        assertEquals("reply-stream:thread-m1", resolved.instanceKey)
        assertEquals("thread-m1", resolved.timelineKey)
        assertTrue(resolved.canRenderTimelineCandidates)
    }

    @Test
    fun threadRouteIdentityRetainsReplyPageAcrossParentToStreamResolution() {
        val unresolved = unresolvedReplyPayload(parentMessageId = "m1").resolveThreadRouteIdentity()
        val resolved = replyPayload(parentMessageId = "m1", threadId = "thread-m1").resolveThreadRouteIdentity()
        val otherParent = replyPayload(parentMessageId = "m2", threadId = "thread-m2").resolveThreadRouteIdentity()

        assertTrue(unresolved.canRetainThreadPageFor(resolved))
        assertTrue(resolved.canRetainThreadPageFor(unresolved))
        assertFalse(unresolved.canRetainThreadPageFor(otherParent))
    }

    @Test
    fun threadRouteIdentityKeepsChannelAndDirectMessageSurfacesTyped() {
        val channel =
            basePayload().copy(
                sourceThreadId = "same-stream",
                streamChannelId = "same-stream",
                surfaceKind = KuiklyThreadSurfaceKind.Channel
            ).resolveThreadRouteIdentity()
        val dm =
            basePayload().copy(
                sourceThreadId = "same-stream",
                streamChannelId = "same-stream",
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage
            ).resolveThreadRouteIdentity()

        assertEquals(KuiklyThreadSurfaceKind.Channel, channel.surfaceKind)
        assertEquals("channel:same-stream", channel.instanceKey)
        assertEquals("same-stream", channel.timelineKey)
        assertEquals("channel", channel.channelKind)
        assertEquals("channel", channel.routeKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, dm.surfaceKind)
        assertEquals("dm:same-stream", dm.instanceKey)
        assertEquals("same-stream", dm.timelineKey)
        assertEquals(null, dm.channelKind)
        assertEquals("dm", dm.routeKind)
    }

    @Test
    fun conversationSummaryThreadRouteIdentityCarriesJointChannelKind() {
        val identity =
            ConversationSummary(
                threadId = ThreadId("joint-1"),
                channelId = ChannelId("joint-1"),
                channelType = "joint",
                kind = ConversationKind.Joint,
                name = "partner"
            ).resolveThreadRouteIdentity()

        assertEquals(KuiklyThreadSurfaceKind.Channel, identity.surfaceKind)
        assertEquals("joint", identity.channelKind)
        assertEquals("joint", identity.routeKind)
        assertEquals("joint:joint-1", identity.instanceKey)
        assertEquals("joint-1", identity.timelineKey)
    }

    @Test
    fun conversationSummaryThreadRouteIdentityLetsJointKindOverrideGenericChannelType() {
        val identity =
            ConversationSummary(
                threadId = ThreadId("joint-1"),
                channelId = ChannelId("joint-1"),
                channelType = "channel",
                kind = ConversationKind.Joint,
                name = "partner"
            ).resolveThreadRouteIdentity()

        assertEquals(KuiklyThreadSurfaceKind.Channel, identity.surfaceKind)
        assertEquals("joint", identity.channelKind)
        assertEquals("joint", identity.routeKind)
        assertEquals("joint:joint-1", identity.instanceKey)
    }

    @Test
    fun conversationSummaryThreadRouteIdentityIgnoresParentChannelEchoAsTimeline() {
        val identity =
            ConversationSummary(
                threadId = ThreadId("reply-thread-1"),
                channelId = ChannelId("parent-channel-1"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("parent-channel-1"),
                        parentMessageId = MessageId("message-1"),
                        streamChannelId = ChannelId("reply-thread-1")
                    ),
                kind = ConversationKind.Thread,
                name = "Thread"
            ).resolveThreadRouteIdentity()

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, identity.surfaceKind)
        assertEquals("reply-stream:reply-thread-1", identity.instanceKey)
        assertEquals("reply-thread-1", identity.timelineKey)
        assertTrue(identity.canRenderTimelineCandidates)
    }

    @Test
    fun threadRouteTimelineChannelIdOwnsMemoryCacheKey() {
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val unresolved =
            contentPayloadForRoute(
                base =
                    unresolvedReplyPayload(parentMessageId = "m1").copy(
                        sourceThreadId = "channel-1",
                        streamChannelId = null
                    ),
                body = "must not cache under parent",
                id = "reply-unresolved"
            )
        val resolved =
            contentPayloadForRoute(
                base = replyPayload(parentMessageId = "m1", threadId = "thread-m1"),
                body = "resolved reply body",
                id = "reply-resolved",
                channelId = "thread-m1"
            )

        dataSource.cachePayloadForTest(unresolved)
        assertNull(dataSource.cachedPayload("channel-1"))

        dataSource.cachePayloadForTest(resolved)
        assertEquals("resolved reply body", dataSource.cachedPayload("thread-m1")?.messages?.single()?.body)
    }

    @Test
    fun sharedThreadPayloadStoreNamespacesSameThreadIdByServer() {
        val store = SharedThreadPayloadStore()
        val serverOne = basePayload().copy(sourceThreadId = "same-thread", title = "server-one")
        val serverTwo = basePayload().copy(sourceThreadId = "same-thread", title = "server-two")

        store.put("server-1", "same-thread", serverOne, TestThreadOwnerScopeId)
        store.put("server-2", "same-thread", serverTwo, TestThreadOwnerScopeId)

        assertEquals("server-one", store.get("server-1", "same-thread", TestThreadEnvironmentId, TestThreadPrincipalId, TestThreadOwnerScopeId)?.title)
        assertEquals("server-two", store.get("server-2", "same-thread", TestThreadEnvironmentId, TestThreadPrincipalId, TestThreadOwnerScopeId)?.title)
    }

    @Test
    fun sharedThreadPayloadStoreNamespacesSameServerThreadByOwnerScope() {
        val store = SharedThreadPayloadStore()
        val ownerA = "https://prod.example.test\u001fu-a\u001fserver-1\u001f1\u001f1"
        val ownerB = "https://prod.example.test\u001fu-b\u001fserver-1\u001f2\u001f1"
        val payloadA = basePayload().copy(sourceThreadId = "channel-1", title = "owner-a")
        val payloadB = basePayload().copy(sourceThreadId = "channel-1", title = "owner-b")

        store.put("server-1", "channel-1", payloadA, ownerA)

        assertEquals("owner-a", store.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerA)?.title)
        assertNull(store.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerB))
        assertEquals(listOf("owner-a"), store.values("server-1", ownerA).map { it.title })
        assertEquals(emptyList(), store.values("server-1", ownerB))

        store.put("server-1", "channel-1", payloadB, ownerB)

        assertEquals("owner-a", store.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerA)?.title)
        assertEquals("owner-b", store.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerB)?.title)
    }

    @Test
    fun ownerScopedFreshnessAdvanceDoesNotRetireAnotherOwnersTimeline() {
        val store = SharedThreadPayloadStore()
        val ownerA = "https://prod.example.test\u001fu-a\u001fserver-1\u001f1\u001f1"
        val ownerB = "https://prod.example.test\u001fu-b\u001fserver-1\u001f2\u001f1"
        store.put(
            "server-1",
            "channel-1",
            basePayload().copy(sourceThreadId = "channel-1", title = "owner-a"),
            ownerA
        )
        store.put(
            "server-1",
            "channel-1",
            basePayload().copy(sourceThreadId = "channel-1", title = "owner-b"),
            ownerB
        )

        store.freshnessLedger.advance("server-1", "channel-1", ownerA)

        assertNull(store.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerA))
        assertEquals("owner-b", store.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerB)?.title)
    }

    @Test
    fun replyCacheOwnerScopeIdIsNullWhenAnyIdentitySegmentIsBlank() {
        val full =
            ReplyCacheProvenance(
                environmentId = "https://prod.example.test",
                principalId = "user-1",
                serverId = "server-1",
                generation = 3L,
                authGeneration = 1L,
                selectionGeneration = 2L
            )

        // Positive control: a complete identity yields one owner key shared by every reply cache.
        assertEquals(
            "https://prod.example.test\u001fuser-1\u001fserver-1\u001f1\u001f2",
            full.threadPayloadOwnerScopeId
        )
        assertEquals(full.threadPayloadOwnerScopeId, full.threadPendingSendOwnerScopeId)

        // A blank segment no longer collapses into a shared "legacy" bucket: the owner is absent.
        assertNull(full.copy(environmentId = "").threadPayloadOwnerScopeId)
        assertNull(full.copy(environmentId = " ").threadPendingSendOwnerScopeId)
        assertNull(full.copy(principalId = "").threadPayloadOwnerScopeId)
        assertNull(full.copy(serverId = "").threadPayloadOwnerScopeId)
        // Auth generation 0 is RealtimeAuthorityRegistry's "no session" revision (every advance
        // starts at 1), so it is a sentinel like a blank segment, not a first epoch.
        assertNull(full.copy(authGeneration = 0L).threadPayloadOwnerScopeId)
        assertNull(full.copy(authGeneration = 0L).threadPendingSendOwnerScopeId)
        assertNull(full.copy(authGeneration = -1L).threadPayloadOwnerScopeId)
        // Selection generation 0 is the first durable selection epoch, not a sentinel.
        assertEquals(
            "https://prod.example.test\u001fuser-1\u001fserver-1\u001f1\u001f0",
            full.copy(selectionGeneration = 0L).threadPayloadOwnerScopeId
        )
    }

    @Test
    fun sharedThreadPayloadStoreRejectsBlankOwnerInsteadOfLegacyBucket() {
        val store = SharedThreadPayloadStore()
        val payload = basePayload().copy(sourceThreadId = "channel-1", title = "owned")
        val cacheKey = SharedThreadPayloadCacheKey("server-1", "channel-1")

        // Positive control: a named owner round-trips.
        store.put("server-1", "channel-1", payload, TestThreadOwnerScopeId)
        assertEquals(
            "owned",
            store.get("server-1", "channel-1", TestThreadEnvironmentId, TestThreadPrincipalId, TestThreadOwnerScopeId)?.title
        )

        // A blank owner is rejected at every entry instead of being routed to a constant bucket.
        assertFailsWith<IllegalArgumentException> { SharedThreadOwnedPayloadCacheKey("", cacheKey) }
        assertFailsWith<IllegalArgumentException> { SharedThreadFreshnessGeneration(cacheKey, " ") }
        assertFailsWith<IllegalArgumentException> { store.put("server-1", "channel-1", payload, "") }
        assertFailsWith<IllegalArgumentException> {
            store.get("server-1", "channel-1", TestThreadEnvironmentId, TestThreadPrincipalId, "")
        }
        assertFailsWith<IllegalArgumentException> { store.values("server-1", "") }
        assertFailsWith<IllegalArgumentException> { store.remove("server-1", "channel-1", " ") }
        assertFailsWith<IllegalArgumentException> { store.freshnessLedger.capture("server-1", "channel-1", "") }
        assertFailsWith<IllegalArgumentException> { store.freshnessLedger.advance("server-1", "channel-1", "") }
        assertFailsWith<IllegalArgumentException> { store.freshnessLedger.retireOwner("") }

        // The rejected calls left the named owner's entry untouched.
        assertEquals(
            "owned",
            store.get("server-1", "channel-1", TestThreadEnvironmentId, TestThreadPrincipalId, TestThreadOwnerScopeId)?.title
        )
    }

    @Test
    fun contentGenerationAdvancesEveryOwnerWithoutNamingOne() {
        val ledger = SharedThreadFreshnessLedger()
        val ownerA = "https://prod.example.test\u001fu-a\u001fserver-1\u001f1\u001f1"
        val ownerB = "https://prod.example.test\u001fu-b\u001fserver-1\u001f2\u001f1"
        val freshnessA = ledger.capture("server-1", "channel-1", ownerA)
        val freshnessB = ledger.capture("server-1", "channel-1", ownerB)
        val content = ledger.captureContent("server-1", "channel-1")
        val otherServerContent = ledger.captureContent("server-2", "channel-1")
        assertSame(content, ledger.captureContent("server-1", "channel-1"))

        // Owner-scoped advance retires exactly that owner and leaves the owner-less token alone.
        ledger.advance("server-1", "channel-1", ownerA)
        assertNotSame(freshnessA, ledger.capture("server-1", "channel-1", ownerA))
        assertSame(freshnessB, ledger.capture("server-1", "channel-1", ownerB))
        assertSame(content, ledger.captureContent("server-1", "channel-1"))

        // A canonical content change (activity facts carry no owner) retires every owner and
        // rotates the owner-less token once, without pretending to be any owner.
        val advanced = ledger.advanceAllOwners("server-1", "channel-1")
        assertNotSame(content, advanced)
        assertSame(advanced, ledger.captureContent("server-1", "channel-1"))
        assertNotSame(freshnessB, ledger.capture("server-1", "channel-1", ownerB))
        assertSame(otherServerContent, ledger.captureContent("server-2", "channel-1"))

        // Server invalidation rotates only that server's token.
        ledger.invalidateServer("server-1")
        assertNotSame(advanced, ledger.captureContent("server-1", "channel-1"))
        assertSame(otherServerContent, ledger.captureContent("server-2", "channel-1"))
    }

    @Test
    fun replyCountFactsAreScopedByEnvironmentAndDropBlankIdentity() {
        val store = SharedThreadPayloadStore()
        val canonical =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent",
                            threadChannelId = null,
                            replyCount = 0
                        )
                    )
            )
        val facts = listOf("m1" to SharedThreadReplyCountFact("thread-m1", 7, TimeSource.Monotonic.markNow()))
        fun replyCount(environmentId: String, principalId: String): Int =
            store.applyReplyCountFacts("server-1", "channel-1", environmentId, principalId, canonical)
                .messages.single().replyCount

        // Positive control: same environment + principal sees the recorded fact.
        store.recordReplyCountFacts("server-1", "channel-1", "https://prod.example.test", "user-1", facts)
        assertEquals(7, replyCount("https://prod.example.test", "user-1"))

        // The same principal on another environment is a different identity: no sharing.
        assertEquals(0, replyCount("https://staging.example.test", "user-1"))
        assertEquals(0, replyCount("https://prod.example.test", "user-2"))

        // A blank environment or principal can neither record nor read facts (fail-closed).
        store.recordReplyCountFacts("server-1", "channel-1", "", "user-1", facts)
        store.recordReplyCountFacts("server-1", "channel-1", "https://staging.example.test", "", facts)
        assertEquals(0, replyCount("", "user-1"))
        assertEquals(0, replyCount("https://staging.example.test", ""))
        assertEquals(0, replyCount("https://staging.example.test", "user-1"))
        assertEquals(7, replyCount("https://prod.example.test", "user-1"))
    }

    @Test
    fun replyCountFactsOverrideCanonicalValue() {
        val store = SharedThreadPayloadStore()
        fun canonical(replyCount: Int) =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent",
                            threadChannelId = null,
                            replyCount = replyCount
                        )
                    )
            )
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 7, TimeSource.Monotonic.markNow()))
        )
        // The recorded fact is the freshest observation for this identity: it
        // overrides the canonical value in both directions. A canonical payload
        // reporting more replies (9) does not win, because facts are accepted by
        // recency at record time — the older canonical projection is stale.
        assertEquals(
            7,
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-1", canonical(9))
                .messages.single().replyCount
        )
        // A canonical value that missed replies is likewise corrected upward.
        assertEquals(
            7,
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-1", canonical(3))
                .messages.single().replyCount
        )
    }

    @Test
    fun replyCountFactsRejectObservationsOlderThanTheRecordedFact() {
        val store = SharedThreadPayloadStore()
        fun canonical(replyCount: Int) =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent",
                            threadChannelId = null,
                            replyCount = replyCount
                        )
                    )
            )
        fun apply(replyCount: Int) =
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-1", canonical(replyCount))
                .messages.single().replyCount

        val now = TimeSource.Monotonic.markNow()
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 5, now))
        )

        // A replayed projection observed before the recorded fact (e.g. a
        // reprojection scheduled before a reply was deleted) must not win.
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 4, now - 1.seconds))
        )
        assertEquals(5, apply(4))

        // A genuinely newer observation (the post-delete projection) is accepted
        // and overrides both the prior fact and the canonical value.
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 4, now + 1.seconds))
        )
        assertEquals(4, apply(4))
    }

    @Test
    fun replyCountFactsRejectCanonicalResponseMarkedAtRequestTime() {
        val store = SharedThreadPayloadStore()
        fun canonical(replyCount: Int) =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent",
                            threadChannelId = null,
                            replyCount = replyCount
                        )
                    )
            )
        fun apply(replyCount: Int) =
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-1", canonical(replyCount))
                .messages.single().replyCount

        // Interleaving: the canonical summaries request is issued while the
        // server-side snapshot still says 5; while it is in flight a realtime
        // event records 6; the response then lands carrying the stale 5. The
        // response is observed at request-issue time, so the realtime fact must
        // stay ahead.
        val requestMark = TimeSource.Monotonic.markNow()
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 6, requestMark + 1.seconds))
        )
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 5, requestMark))
        )
        assertEquals(6, apply(5))

        // Converse: a canonical response issued after the realtime event (e.g.
        // a fresh projection after a reply was deleted) is genuinely newer and
        // still wins.
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 4, requestMark + 2.seconds))
        )
        assertEquals(4, apply(4))
    }

    @Test
    fun sameServerOwnerCannotJoinOrConsumeAnotherOwnersPrewarm() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        val ownerA = "https://prod.example.test\u001fu-a\u001fserver-1\u001f1\u001f1"
        val ownerB = "https://prod.example.test\u001fu-b\u001fserver-1\u001f2\u001f1"
        val route = basePayload()
        val payloadA = contentPayloadForRoute(route, "owner-a body", "owner-a")
        val payloadB = contentPayloadForRoute(route, "owner-b body", "owner-b")
        val ownerAStarted = CompletableDeferred<Unit>()
        val releaseOwnerA = CompletableDeferred<Unit>()
        var ownerBFetches = 0

        val ownerAPrewarm =
            async {
                store.prewarm("server-1", "channel-1", ownerA) {
                    ownerAStarted.complete(Unit)
                    releaseOwnerA.await()
                    DataResult.Content(payloadA)
                }
            }
        ownerAStarted.await()

        assertNull(store.joinOrConsumePrewarm("server-1", "channel-1", ownerB))
        val ownerBResult =
            store.prewarm("server-1", "channel-1", ownerB) {
                ownerBFetches += 1
                DataResult.Content(payloadB)
            }

        assertEquals(1, ownerBFetches)
        assertEquals(
            "owner-b body",
            assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(ownerBResult).data.messages.single().body
        )
        releaseOwnerA.complete(Unit)
        assertEquals(
            "owner-a body",
            assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(ownerAPrewarm.await()).data.messages.single().body
        )
        appScope.cancel()
    }

    @Test
    fun replyCountFactsSurviveTimelineGenerationButRemainServerScoped() {
        val store = SharedThreadPayloadStore()
        val enriched =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent",
                            threadChannelId = "thread-m1",
                            replyCount = 7
                        )
                    )
            )
        val canonicalWithoutSummaries =
            enriched.copy(
                messages =
                    enriched.messages.map { message ->
                        message.copy(threadChannelId = null, replyCount = 0)
                    }
            )

        store.put("server-1", "channel-1", enriched, TestThreadOwnerScopeId)
        val firstMark = TimeSource.Monotonic.markNow()
        store.recordReplyCountFacts(
            serverId = "server-1",
            threadId = "channel-1",
            environmentId = TestThreadEnvironmentId,
            principalId = "user-1",
            facts = listOf("m1" to SharedThreadReplyCountFact("thread-m1", 7, firstMark))
        )
        store.freshnessLedger.advance("server-1", "channel-1", TestThreadOwnerScopeId)

        assertNull(store.get("server-1", "channel-1", TestThreadEnvironmentId, TestThreadPrincipalId, TestThreadOwnerScopeId))
        assertEquals(
            7,
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-1", canonicalWithoutSummaries)
                .messages.single().replyCount
        )
        assertEquals(
            0,
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-2", canonicalWithoutSummaries)
                .messages.single().replyCount
        )
        assertEquals(
            0,
            store.applyReplyCountFacts("server-2", "channel-1", TestThreadEnvironmentId, "user-1", canonicalWithoutSummaries)
                .messages.single().replyCount
        )

        store.recordReplyCountFacts(
            serverId = "server-1",
            threadId = "channel-1",
            environmentId = TestThreadEnvironmentId,
            principalId = "user-1",
            facts = listOf("m1" to SharedThreadReplyCountFact("thread-m1", 3, firstMark + 1.seconds))
        )
        assertEquals(
            3,
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-1", canonicalWithoutSummaries)
                .messages.single().replyCount
        )

        store.freshnessLedger.invalidateServer("server-1")

        assertEquals(
            0,
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-1", canonicalWithoutSummaries)
                .messages.single().replyCount
        )
    }

    @Test
    fun sharedThreadPayloadStorePoolsPrewarmRequestsGlobally() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedThreadPayloadStore(
                scope = appScope,
                maxConcurrentPrewarms = 2,
                prewarmStartIntervalMillis = 0
            )
        val release = CompletableDeferred<Unit>()
        val firstTwoStarted = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        var starts = 0
        val jobs =
            (1..3).map { index ->
                async {
                    store.prewarm("server-1", "thread-$index", TestThreadOwnerScopeId) {
                        active += 1
                        starts += 1
                        maxActive = maxOf(maxActive, active)
                        if (starts == 2) firstTwoStarted.complete(Unit)
                        release.await()
                        active -= 1
                        val payload = basePayload().copy(sourceThreadId = "thread-$index")
                        store.put("server-1", "thread-$index", payload, TestThreadOwnerScopeId)
                        DataResult.Content(payload)
                    }
                }
            }

        firstTwoStarted.await()
        assertEquals(2, active)
        assertEquals(2, starts)
        assertEquals(2, maxActive)

        release.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(3, starts)
        assertEquals(2, maxActive)
        appScope.cancel()
    }

    @Test
    fun sharedThreadPayloadStoreRateLimitsPrewarmStarts() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedThreadPayloadStore(
                scope = appScope,
                maxConcurrentPrewarms = 2,
                prewarmStartIntervalMillis = 40
            )
        val origin = kotlin.time.TimeSource.Monotonic.markNow()
        val starts = mutableListOf<Long>()
        val jobs =
            (1..2).map { index ->
                async {
                    store.prewarm("server-1", "thread-$index", TestThreadOwnerScopeId) {
                        starts += origin.elapsedNow().inWholeMilliseconds
                        val payload = basePayload().copy(sourceThreadId = "thread-$index")
                        store.put("server-1", "thread-$index", payload, TestThreadOwnerScopeId)
                        DataResult.Content(payload)
                    }
                }
            }

        jobs.forEach { it.await() }

        assertEquals(2, starts.size)
        assertTrue(starts[1] - starts[0] >= 30L)
        appScope.cancel()
    }

    @Test
    fun initialThreadFetchJoinsCanonicalPrewarmInFlightWithoutRepositoryRequest() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("channel-1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(SlockShellStoreScope.create("thread-prewarm-test", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val warmed =
            contentPayloadForRoute(
                base = route,
                body = "prewarmed reply body",
                id = "reply-warm",
                channelId = "thread-m1"
            )
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        val prewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    dataSource.cachePayloadForTest(warmed)
                    DataResult.Content(warmed)
                }
            }
        fetchStarted.await()

        val opened =
            async {
                dataSource.fetchThreadPayload(
                    basePayload = route,
                    reusePrewarm = true
                )
            }

        assertEquals(emptyList(), repository.getReplyThreadRequests)
        releaseFetch.complete(Unit)
        assertEquals("prewarmed reply body", (opened.await() as DataResult.Content).data.messages.single().body)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
        prewarm.await()
        appScope.cancel()
    }

    @Test
    fun initialThreadFetchConsumesCompletedPrewarmCacheWithoutRepositoryRequest() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope)
        val repository = FakeConversationRepository()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(SlockShellStoreScope.create("thread-prewarm-success", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val warmed =
            contentPayloadForRoute(
                base = route,
                body = "completed prewarm body",
                id = "reply-warm",
                channelId = "thread-m1"
            )
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
            dataSource.cachePayloadForTest(warmed)
            DataResult.Content(warmed)
        }

        val result = dataSource.fetchThreadPayload(basePayload = route, reusePrewarm = true)

        assertEquals("completed prewarm body", (result as DataResult.Content).data.messages.single().body)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
        appScope.cancel()
    }

    @Test
    fun supersededPrewarmCompletionCannotReplaceTheNewestActivityGeneration() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val stale =
            contentPayloadForRoute(
                base = route,
                body = "stale generation",
                id = "reply-stale",
                channelId = "thread-m1"
            )
        val fresh =
            contentPayloadForRoute(
                base = route,
                body = "fresh generation",
                id = "reply-fresh",
                channelId = "thread-m1"
            )
        val staleStarted = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val freshStarted = CompletableDeferred<Unit>()
        val releaseFresh = CompletableDeferred<Unit>()
        val stalePrewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    staleStarted.complete(Unit)
                    releaseStale.await()
                    DataResult.Content(stale)
                }
            }
        staleStarted.await()
        payloadStore.freshnessLedger.advance("server-1", "thread-m1", TestThreadOwnerScopeId)
        val freshPrewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    freshStarted.complete(Unit)
                    releaseFresh.await()
                    DataResult.Content(fresh)
                }
            }
        freshStarted.await()

        releaseFresh.complete(Unit)
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(freshPrewarm.await())
        releaseStale.complete(Unit)
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(stalePrewarm.await())

        val consumed =
            assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(
                payloadStore.joinOrConsumePrewarm("server-1", "thread-m1", TestThreadOwnerScopeId)
            )
        assertEquals("fresh generation", consumed.data.messages.single().body)
        appScope.cancel()
    }

    @Test
    fun lateCompletionFromSupersededInFlightGenerationPublishesNoReadyCandidate() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val stale =
            contentPayloadForRoute(
                base = route,
                body = "late stale body",
                id = "reply-stale",
                channelId = "thread-m1"
            )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val prewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    started.complete(Unit)
                    release.await()
                    DataResult.Content(stale)
                }
            }
        started.await()

        payloadStore.freshnessLedger.advance("server-1", "thread-m1", TestThreadOwnerScopeId)
        release.complete(Unit)
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(prewarm.await())

        assertEquals(null, payloadStore.joinOrConsumePrewarm("server-1", "thread-m1", TestThreadOwnerScopeId))
        assertEquals(null, payloadStore.get("server-1", "thread-m1", TestThreadEnvironmentId, TestThreadPrincipalId, TestThreadOwnerScopeId))
        appScope.cancel()
    }

    @Test
    fun serverInvalidationMakesReadyAndOrdinaryCachedCandidatesUnusable() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val cached =
            contentPayloadForRoute(
                base = route,
                body = "old candidate",
                id = "reply-old",
                channelId = "thread-m1"
            )
        payloadStore.put("server-1", "thread-m1", cached, TestThreadOwnerScopeId)
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(
            payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) { DataResult.Content(cached) }
        )

        payloadStore.freshnessLedger.invalidateServer("server-1")

        assertEquals(null, payloadStore.get("server-1", "thread-m1", TestThreadEnvironmentId, TestThreadPrincipalId, TestThreadOwnerScopeId))
        assertEquals(null, payloadStore.joinOrConsumePrewarm("server-1", "thread-m1", TestThreadOwnerScopeId))
        appScope.cancel()
    }

    @Test
    fun ordinaryCanonicalFetchRetriesCurrentGenerationBeforeReturningOrCaching() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        var responseIndex = 0
        val repository = FakeConversationRepository()
        repository.threadResultProvider = { _, _, _ ->
            if (responseIndex++ == 0) {
                DataResult.Content(threadSnapshot(body = "request-start stale", id = "stale"))
            } else {
                DataResult.Content(threadSnapshot(body = "current fresh", id = "fresh"))
            }
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.getThreadStarted = started
        repository.releaseGetThread = release
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(SlockShellStoreScope.create("canonical-generation", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        val request =
            async {
                dataSource.fetchThreadPayload(
                    basePayload = basePayload(),
                    persistResult = true,
                    reusePrewarm = false
                )
            }
        started.await()

        payloadStore.freshnessLedger.advance("server-1", "channel-1", ownerScopeId)
        release.complete(Unit)
        val result = assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(request.await())

        assertEquals("current fresh", result.data.messages.single().body)
        assertEquals(
            "current fresh",
            payloadStore.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerScopeId)?.messages?.single()?.body
        )
        assertEquals(2, repository.getThreadRequests.size)
        appScope.cancel()
    }

    @Test
    fun visibleJoinOfSupersededPrewarmRefetchesCurrentGeneration() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "current after join", id = "fresh"))
            )
        suspend fun dataSource(shellId: String): SharedThreadDataSource {
            val result = testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(SlockShellStoreScope.create(shellId, "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
            result.readSession()
            return result
        }
        val firstDataSource = dataSource("join-current-first")
        val secondDataSource = dataSource("join-current-second")
        val ownerScopeId = firstDataSource.threadPayloadOwnerScopeIdForTest()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stalePrewarm =
            async {
                payloadStore.prewarm("server-1", "channel-1", ownerScopeId) {
                    started.complete(Unit)
                    release.await()
                    DataResult.Content(contentPayload("stale joined", id = "stale"))
                }
            }
        started.await()
        val firstVisible = async { firstDataSource.fetchThreadPayload(basePayload(), reusePrewarm = true) }
        val secondVisible = async { secondDataSource.fetchThreadPayload(basePayload(), reusePrewarm = true) }
        yield()

        payloadStore.freshnessLedger.advance("server-1", "channel-1", ownerScopeId)
        release.complete(Unit)
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(stalePrewarm.await())
        val firstResult = assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(firstVisible.await())
        val secondResult = assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(secondVisible.await())

        assertEquals("current after join", firstResult.data.messages.single().body)
        assertEquals("current after join", secondResult.data.messages.single().body)
        assertEquals(1, repository.getThreadRequests.size)
        appScope.cancel()
    }

    @Test
    fun supersededFailedTakeoverRetriesCurrentGenerationBeforeVisibleSuccess() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        var responseIndex = 0
        val repository = FakeConversationRepository()
        repository.threadResultProvider = { _, _, _ ->
            if (responseIndex++ == 0) {
                DataResult.Content(threadSnapshot(body = "stale takeover", id = "stale"))
            } else {
                DataResult.Content(threadSnapshot(body = "current takeover", id = "fresh"))
            }
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.getThreadStarted = started
        repository.releaseGetThread = release
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(SlockShellStoreScope.create("takeover-current", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        payloadStore.prewarm("server-1", "channel-1", ownerScopeId) {
            DataResult.Error(ScreenError(code = "prewarm_offline", retryable = true))
        }
        val visible = async { dataSource.fetchThreadPayload(basePayload(), reusePrewarm = true) }
        started.await()

        payloadStore.freshnessLedger.advance("server-1", "channel-1", ownerScopeId)
        release.complete(Unit)
        val result = assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(visible.await())

        assertEquals("current takeover", result.data.messages.single().body)
        assertEquals(2, repository.getThreadRequests.size)
        appScope.cancel()
    }

    @Test
    fun retiredOwnerFailedPrewarmTakeoverDoesNotRetryOrWriteEitherOwnerCache() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "retired owner", id = "retired"))
            )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.getThreadStarted = started
        repository.releaseGetThread = release
        val authStore = InMemorySlockAuthSessionStore(sampleSession())
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = authStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(SlockShellStoreScope.create("retired-takeover", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val ownerA = dataSource.threadPayloadOwnerScopeIdForTest()
        payloadStore.prewarm("server-1", "channel-1", ownerA) {
            DataResult.Error(ScreenError(code = "prewarm_offline", retryable = true))
        }
        val visible = async { dataSource.fetchThreadPayload(basePayload(), reusePrewarm = true) }
        started.await()

        authStore.saveSession(
            sampleSession().copy(
                user = StoredAuthUser(id = "u2", email = "second@example.test", displayName = "Second"),
                accessToken = "access-u2",
                refreshToken = "refresh-u2"
            )
        )
        val ownerB = dataSource.threadPayloadOwnerScopeIdForTest()
        release.complete(Unit)

        val result = assertIs<DataResult.Error>(visible.await())
        assertEquals("thread_reply_cache_provenance_stale", result.error.code)
        assertEquals(false, result.error.retryable)
        assertEquals(1, repository.getThreadRequests.size)
        assertNull(payloadStore.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerA))
        assertNull(payloadStore.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerB))
        appScope.cancel()
    }

    @Test
    fun removeAfterReadyPrewarmSelfHealsToOneFreshFlight() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val stale =
            contentPayloadForRoute(
                base = route,
                body = "removed ready",
                id = "removed-ready",
                channelId = "thread-m1"
            )
        val fresh =
            contentPayloadForRoute(
                base = route,
                body = "fresh after remove",
                id = "fresh-after-remove",
                channelId = "thread-m1"
            )
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(
            payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) { DataResult.Content(stale) }
        )
        payloadStore.remove("server-1", "thread-m1", TestThreadOwnerScopeId)
        var freshFetches = 0

        val result =
            assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    freshFetches += 1
                    DataResult.Content(fresh)
                }
            )

        assertEquals(1, freshFetches)
        assertEquals("fresh after remove", result.data.messages.single().body)
        appScope.cancel()
    }

    @Test
    fun failedPrewarmTakeoverIsSingleFlightAcrossVisibleStores() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("channel-1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val takeoverStarted = CompletableDeferred<Unit>()
        val releaseTakeover = CompletableDeferred<Unit>()
        repository.getReplyThreadStarted = takeoverStarted
        repository.releaseGetReplyThread = releaseTakeover
        suspend fun dataSource(shellId: String): SharedThreadDataSource {
            val dataSource = testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(SlockShellStoreScope.create(shellId, "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
            dataSource.readSession()
            return dataSource
        }
        val firstStore = dataSource("failed-prewarm-first")
        val secondStore = dataSource("failed-prewarm-second")
        val ownerScopeId = firstStore.threadPayloadOwnerScopeIdForTest()
        payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
            DataResult.Error(ScreenError(code = "prewarm_offline", retryable = true))
        }

        val first = async { firstStore.fetchThreadPayload(route, reusePrewarm = true) }
        takeoverStarted.await()
        val second = async { secondStore.fetchThreadPayload(route, reusePrewarm = true) }

        assertEquals(1, repository.getReplyThreadRequests.size)
        releaseTakeover.complete(Unit)
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(first.await())
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(second.await())
        assertEquals(1, repository.getReplyThreadRequests.size)
        appScope.cancel()
    }

    @Test
    fun failedNewGenerationCannotReturnThePreviousReadyPayloadDuringVisibleTakeover() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val stale =
            contentPayloadForRoute(
                base = route,
                body = "stale v1",
                id = "reply-v1",
                channelId = "thread-m1"
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(
                                        threadId = "thread-m1",
                                        id = "reply-fresh",
                                        seq = 3,
                                        body = "fresh takeover"
                                    )
                                ),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val takeoverStarted = CompletableDeferred<Unit>()
        val releaseTakeover = CompletableDeferred<Unit>()
        repository.getReplyThreadStarted = takeoverStarted
        repository.releaseGetReplyThread = releaseTakeover
        suspend fun dataSource(shellId: String): SharedThreadDataSource {
            val dataSource =
                testSharedThreadDataSource(
                    networkApi = FakeThreadApi(),
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    selectedServerStore = SelectedServerStore(SlockShellStoreScope.create(shellId, "server-1")),
                    conversationRepository = repository,
                    payloadStore = payloadStore
                )
            dataSource.readSession()
            return dataSource
        }
        val firstDataSource = dataSource("v2-takeover-first")
        val secondDataSource = dataSource("v2-takeover-second")
        val ownerScopeId = firstDataSource.threadPayloadOwnerScopeIdForTest()
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(
            payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
                DataResult.Content(stale)
            }
        )
        payloadStore.freshnessLedger.advance("server-1", "thread-m1", ownerScopeId)
        assertIs<DataResult.Error>(
            payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
                DataResult.Error(ScreenError(code = "prewarm_v2_offline", retryable = true))
            }
        )
        val first = async { firstDataSource.fetchThreadPayload(route, reusePrewarm = true) }
        takeoverStarted.await()
        val second = async { secondDataSource.fetchThreadPayload(route, reusePrewarm = true) }

        assertEquals(1, repository.getReplyThreadRequests.size)
        releaseTakeover.complete(Unit)
        val firstPayload = assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(first.await()).data
        val secondPayload = assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(second.await()).data
        assertEquals("fresh takeover", firstPayload.messages.single().body)
        assertEquals("fresh takeover", secondPayload.messages.single().body)
        assertEquals(1, repository.getReplyThreadRequests.size)
        appScope.cancel()
    }

    @Test
    fun manualRetrySuccessClearsTerminalFailedPrewarmGeneration() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope)
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Error(ScreenError(code = "takeover_offline", retryable = true))
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        suspend fun dataSource(shellId: String): SharedThreadDataSource {
            val dataSource = testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(SlockShellStoreScope.create(shellId, "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
            dataSource.readSession()
            return dataSource
        }

        val terminalDataSource = dataSource("failed-prewarm-terminal")
        val ownerScopeId = terminalDataSource.threadPayloadOwnerScopeIdForTest()
        payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
            DataResult.Error(ScreenError(code = "prewarm_offline", retryable = true))
        }

        assertIs<DataResult.Error>(
            terminalDataSource.fetchThreadPayload(
                basePayload = route,
                persistResult = false,
                reusePrewarm = true
            )
        )
        assertEquals(1, repository.getReplyThreadRequests.size)

        repository.threadResult =
            DataResult.Content(
                threadSnapshot(
                    threadId = "thread-m1",
                    conversation =
                        conversationSummary("thread-m1").copy(
                            kind = ConversationKind.Thread,
                            channelId = ChannelId("channel-1"),
                            thread =
                                ThreadConversation(
                                    parentChannelId = ChannelId("channel-1"),
                                    parentMessageId = MessageId("m1"),
                                    streamChannelId = ChannelId("thread-m1")
                                )
                        )
                )
            )
        // Under a named environment the failed access flight is remembered per identity, so a
        // manual retry must say so (the Store passes explicitRetry here); a blank environment
        // used to skip that memory entirely and made this test pass without it.
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(
            dataSource("failed-prewarm-manual-retry").fetchThreadPayload(
                basePayload = route,
                persistResult = false,
                reusePrewarm = false,
                replaceReplyAccessFailure = true
            )
        )
        assertEquals(2, repository.getReplyThreadRequests.size)

        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(
            dataSource("failed-prewarm-reopen").fetchThreadPayload(
                basePayload = route,
                persistResult = false,
                reusePrewarm = true
            )
        )
        assertEquals(3, repository.getReplyThreadRequests.size)
        appScope.cancel()
    }

    @Test
    fun failedPrewarmTakeoverJoinsNewPrewarmStartedDuringHandoff() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(appScope)
        payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
            DataResult.Error(ScreenError(code = "prewarm_offline", retryable = true))
        }
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val replacementPayload =
            contentPayloadForRoute(
                base = replyPayload(parentMessageId = "m1", threadId = "thread-m1"),
                body = "replacement prewarm body",
                id = "replacement-prewarm",
                channelId = "thread-m1"
            )
        val replacement =
            async {
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    replacementStarted.complete(Unit)
                    releaseReplacement.await()
                    DataResult.Content(replacementPayload)
                }
            }
        replacementStarted.await()
        var directFetches = 0
        val takeover =
            async {
                payloadStore.takeOverFailedPrewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    directFetches += 1
                    DataResult.Error(ScreenError(code = "duplicate_fresh", retryable = true))
                }
            }

        yield()
        assertEquals(0, directFetches)
        releaseReplacement.complete(Unit)
        assertEquals(
            "replacement prewarm body",
            (takeover.await() as DataResult.Content).data.messages.single().body
        )
        replacement.await()
        assertEquals(0, directFetches)
        appScope.cancel()
    }

    @Test
    fun activityReplyPrewarmUsesBackgroundRepositoryPriorityAndCanonicalCache() = runBlocking {
        val payloadStore = SharedThreadPayloadStore()
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("channel-1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(SlockShellStoreScope.create("thread-prewarm-marker", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val marker =
            ActivityReplyThreadPrewarmMarker(
                key =
                    ActivityReplyThreadPrewarmKey(
                        serverId = "server-1",
                        parentChannelId = "channel-1",
                        parentMessageId = "m1",
                        threadChannelId = "thread-m1"
                )
            )

        val result = dataSource.prewarmReplyThread(marker)

        assertTrue(result is DataResult.Content)
        assertEquals(listOf(DataRequestPriority.Background), repository.getReplyThreadPriorities)
        assertEquals(listOf(DataRequestPriority.Background), repository.getMessageContextPriorities)
        assertEquals("thread-m1", dataSource.cachedPayload("thread-m1")?.sourceThreadId)
    }

    @Test
    fun ownerTransitionDropsLateReplyPrewarmWithoutWritingEitherOwnerCache() = runBlocking {
        val payloadStore = SharedThreadPayloadStore(prewarmStartIntervalMillis = 0L)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("channel-1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            ).also {
                it.getReplyThreadStarted = CompletableDeferred()
                it.releaseGetReplyThread = CompletableDeferred()
            }
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        val ownerBSession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-b", email = "b@example.test", displayName = "Owner B"),
                accessToken = "access-b",
                refreshToken = "refresh-b"
            )
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(SlockShellStoreScope.create("thread-prewarm-owner-transition", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore,
                replyCacheIdentityAuthority = { identity }
            )
        val ownerA = dataSource.threadPayloadOwnerScopeIdForTest()
        val marker =
            ActivityReplyThreadPrewarmMarker(
                key =
                    ActivityReplyThreadPrewarmKey(
                        serverId = "server-1",
                        parentChannelId = "channel-1",
                        parentMessageId = "m1",
                        threadChannelId = "thread-m1"
                    )
            )
        val prewarm = async { dataSource.prewarmReplyThread(marker) }
        repository.getReplyThreadStarted?.await()

        identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "u-b",
                serverId = "server-1",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        val ownerB = dataSource.threadPayloadOwnerScopeIdForTest()
        repository.releaseGetReplyThread?.complete(Unit)

        val result = assertIs<DataResult.Error>(prewarm.await())
        assertEquals("thread_reply_access_authority_changed", result.error.code)
        assertNull(payloadStore.get("server-1", "thread-m1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerA))
        assertNull(payloadStore.get("server-1", "thread-m1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerB))
    }

    @Test
    fun newerActivityVersionReplacesCompletedPrewarmBeforeVisibleOpen() = runBlocking {
        val payloadStore = SharedThreadPayloadStore(prewarmStartIntervalMillis = 0L)
        fun snapshot(messages: List<Message>) =
            DataResult.Content(
                threadSnapshot(
                    threadId = "thread-m1",
                    messages = messages,
                    parentMessage =
                        message(
                            threadId = "channel-1",
                            id = "m1",
                            seq = 1,
                            body = "parent"
                        ),
                    conversation =
                        conversationSummary("thread-m1").copy(
                            kind = ConversationKind.Thread,
                            channelId = ChannelId("thread-m1"),
                            thread =
                                ThreadConversation(
                                    parentChannelId = ChannelId("channel-1"),
                                    parentMessageId = MessageId("m1"),
                                    streamChannelId = ChannelId("thread-m1")
                                )
                        )
                )
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    snapshot(
                        listOf(
                            message(
                                threadId = "thread-m1",
                                id = "reply-1",
                                seq = 2,
                                body = "reply one"
                            )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(SlockShellStoreScope.create("thread-prewarm-version", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val key =
            ActivityReplyThreadPrewarmKey(
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                threadChannelId = "thread-m1"
            )

        assertIs<DataResult.Content<Unit>>(
            dataSource.prewarmReplyThread(
                ActivityReplyThreadPrewarmMarker(key = key)
            )
        )
        repository.threadResult =
            snapshot(
                listOf(
                    message(
                        threadId = "thread-m1",
                        id = "reply-1",
                        seq = 2,
                        body = "reply one"
                    ),
                    message(
                        threadId = "thread-m1",
                        id = "reply-2",
                        seq = 3,
                        body = "reply two"
                    )
                )
            )
        payloadStore.freshnessLedger.advance(
            "server-1",
            "thread-m1",
            dataSource.threadPayloadOwnerScopeIdForTest()
        )

        assertIs<DataResult.Content<Unit>>(
            dataSource.prewarmReplyThread(
                ActivityReplyThreadPrewarmMarker(key = key)
            )
        )
        val visible =
            assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(
                dataSource.fetchThreadPayload(
                    basePayload = replyPayload(parentMessageId = "m1", threadId = "thread-m1"),
                    reusePrewarm = true
                )
            )

        assertEquals(2, repository.getReplyThreadRequests.size)
        assertEquals(listOf("reply-1", "reply-2"), visible.data.messages.map { it.id })
        assertEquals(listOf(DataRequestPriority.Background, DataRequestPriority.Background), repository.getReplyThreadPriorities)
    }

    @Test
    fun storeInitialPayloadKeepsRouteIdentityBeforeStartDispatch() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val routePayload =
            basePayload().copy(
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1"
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                initialPayload = routePayload
            )

        val payload = store.state.value.payload
        val identity = payload.resolveThreadRouteIdentity()
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals("channel:channel-1", identity.instanceKey)
        assertEquals("channel-1", store.state.value.timelineSlice.sourceThreadId)
        scope.cancel()
    }

    @Test
    fun unresolvedReplyStartDoesNotRenderPreviousResolvedReplyTimeline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val resolvedRoute = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(
                base = resolvedRoute,
                body = "old resolved reply body",
                id = "reply-old",
                channelId = "thread-m1"
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(resolvedRoute))
        assertEquals("old resolved reply body", store.state.value.payload.messages.single().body)

        store.accept(SharedThreadIntent.Start(unresolvedReplyPayload(parentMessageId = "m1")))

        val payload = store.state.value.payload
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertTrue(payload.messages.isEmpty())
        scope.cancel()
    }

    @Test
    fun cachedThreadPayloadSurvivesVisibleFetchFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("cached body", payload.messages.single().body)
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun sameServerAccountSwitchShowsLoadingShellInsteadOfPreviousOwnersCachedTimeline() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        val ownerBSession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-b", email = "b@example.test", displayName = "Owner B"),
                accessToken = "access-b",
                refreshToken = "refresh-b"
            )
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L
            )
        val loadingStateApplied = CompletableDeferred<KuiklyThreadScreenPayload>()
        val releaseLoadingState = CompletableDeferred<Unit>()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                replyCacheIdentityAuthority = { identity },
                payloadStore = payloadStore
            )
        assertNotNull(dataSource.readSession())
        assertTrue(dataSource.cachePayload(contentPayload("owner-a cached body"), serverId = "server-1"))
        identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "u-b",
                serverId = "server-1",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                afterRefreshLoadingStateApplied = { payload ->
                    loadingStateApplied.complete(payload)
                    releaseLoadingState.await()
                }
            )

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            val loadingPayload = loadingStateApplied.await()
            val firstState = store.state.value

            assertEquals("loading", loadingPayload.status)
            assertTrue(loadingPayload.messages.isEmpty())
            assertEquals(ThreadAccessResolution.Loading, firstState.accessResolution)
            assertEquals("loading", firstState.timelineSlice.status)
            assertTrue(firstState.timelineSlice.messages.isEmpty())
            assertEquals(
                ThreadRouteMountPlan.LoadingShell,
                firstState.payload.threadRouteMountPlan(
                    timelineSlice = firstState.timelineSlice,
                    accessResolution = firstState.accessResolution
                )
            )
            assertFalse(
                firstState.payload.hasRenderableContentForComposer(
                    timelineSlice = firstState.timelineSlice,
                    accessResolution = firstState.accessResolution
                )
            )
        } finally {
            releaseLoadingState.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun ownerSwitchDuringFirstFrameAdmissionRetainsOldCacheWithoutPublishingIt() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        val ownerBSession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-b", email = "b@example.test", displayName = "Owner B"),
                accessToken = "access-b",
                refreshToken = "refresh-b"
            )
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L
            )
        val admissionStarted = CompletableDeferred<Unit>()
        val releaseAdmission = CompletableDeferred<Unit>()
        val loadingApplied = CompletableDeferred<Unit>()
        val releaseLoading = CompletableDeferred<Unit>()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                replyCacheIdentityAuthority = { identity },
                payloadStore = payloadStore
            )
        val ownerA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        assertTrue(
            dataSource.cachePayload(
                contentPayload("owner-a cached body"),
                serverId = "server-1",
                ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty(),
                expectedProvenance = ownerA
            )
        )
        dataSource.onReplyCacheFirstFrameAdmissionForTest = {
            admissionStarted.complete(Unit)
            releaseAdmission.await()
        }
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                afterRefreshLoadingStateApplied = {
                    loadingApplied.complete(Unit)
                    releaseLoading.await()
                }
            )

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            withTimeout(5_000) { admissionStarted.await() }
            identity =
                ThreadReplyCacheIdentitySnapshot(
                    session = ownerBSession,
                    environmentId = "https://prod.example.test",
                    principalId = "u-b",
                    serverId = "server-1",
                    authGeneration = 2L,
                    selectionGeneration = 1L,
                    bindingGeneration = 2L
                )
            val ownerB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
            releaseAdmission.complete(Unit)
            withTimeout(5_000) { loadingApplied.await() }

            val firstFrame = store.state.value
            assertEquals(ThreadAccessResolution.Loading, firstFrame.accessResolution)
            assertEquals("loading", firstFrame.timelineSlice.status)
            assertTrue(firstFrame.timelineSlice.messages.isEmpty())
            assertFalse(firstFrame.writeActionsEnabled)
            assertEquals(
                "owner-a cached body",
                payloadStore
                    .get("server-1", "channel-1", environmentId = ownerA.environmentId, principalId = ownerA.principalId, ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty())
                    ?.messages
                    ?.single()
                    ?.body
            )
            assertNull(
                payloadStore.get(
                    "server-1",
                    "channel-1",
                    environmentId = ownerB.environmentId,
                    principalId = ownerB.principalId,
                    ownerScopeId = ownerB.threadPayloadOwnerScopeId.orEmpty()
                )
            )
        } finally {
            releaseAdmission.complete(Unit)
            releaseLoading.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun authGenerationAdvanceDuringFetchStillDeliversContent() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L,
                bindingGeneration = 1L
            )
        val api = FakeThreadApi()
        api.channelMessagesStarted = CompletableDeferred()
        api.releaseChannelMessages = CompletableDeferred()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                replyCacheIdentityAuthority = { identity },
                payloadStore = payloadStore
            )
        val ownerA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            withTimeout(5_000) { api.channelMessagesStarted!!.await() }
            // Same principal/server, only the token rotated -> authGeneration advanced.
            identity =
                ThreadReplyCacheIdentitySnapshot(
                    session =
                        ownerASession.copy(
                            accessToken = "access-a2",
                            refreshToken = "refresh-a2"
                        ),
                    environmentId = "https://prod.example.test",
                    principalId = "u-a",
                    serverId = "server-1",
                    authGeneration = 2L,
                    selectionGeneration = 1L,
                    bindingGeneration = 1L
                )
            val ownerARotated =
                requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
            api.releaseChannelMessages!!.complete(Unit)

            withTimeout(5_000) {
                while (store.state.value.payload.status != "content") {
                    delay(10)
                }
            }
            assertEquals(
                "network body",
                store.state.value.payload.messages.single().body
            )
            val cached =
                listOfNotNull(
                    payloadStore.get(
                        "server-1",
                        "channel-1",
                        environmentId = ownerA.environmentId,
                        principalId = ownerA.principalId,
                        ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty()
                    ),
                    payloadStore.get(
                        "server-1",
                        "channel-1",
                        environmentId = ownerARotated.environmentId,
                        principalId = ownerARotated.principalId,
                        ownerScopeId = ownerARotated.threadPayloadOwnerScopeId.orEmpty()
                    )
                )
            assertEquals("network body", cached.single().messages.single().body)
        } finally {
            api.releaseChannelMessages?.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun ownerSwitchDuringFetchDropsResult() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        val ownerBSession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-b", email = "b@example.test", displayName = "Owner B"),
                accessToken = "access-b",
                refreshToken = "refresh-b"
            )
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L,
                bindingGeneration = 1L
            )
        val api = FakeThreadApi()
        api.channelMessagesStarted = CompletableDeferred()
        api.releaseChannelMessages = CompletableDeferred()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                replyCacheIdentityAuthority = { identity },
                payloadStore = payloadStore
            )
        val ownerA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val diagnostics = ConcurrentTestLogBuffer()
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = diagnostics::add
            )

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            withTimeout(5_000) { api.channelMessagesStarted!!.await() }
            identity =
                ThreadReplyCacheIdentitySnapshot(
                    session = ownerBSession,
                    environmentId = "https://prod.example.test",
                    principalId = "u-b",
                    serverId = "server-1",
                    authGeneration = 2L,
                    selectionGeneration = 1L,
                    bindingGeneration = 2L
                )
            val ownerB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
            api.releaseChannelMessages!!.complete(Unit)

            // The fetch result must be dropped post-transport: payload stays on the
            // loading shell and nothing is persisted for either owner scope.
            // Deterministic completion signal: dropForOwnerChange emits the
            // `reason=owner_changed` diagnostic synchronously inside the refresh
            // coroutine, so observing that line proves adjudication has run.
            withTimeout(5_000) {
                while (!diagnostics.any { it.contains("reason=owner_changed") }) {
                    delay(5)
                }
            }
            assertEquals("loading", store.state.value.payload.status)
            assertTrue(store.state.value.payload.messages.isEmpty())
            assertNull(
                payloadStore.get(
                    "server-1",
                    "channel-1",
                    environmentId = ownerA.environmentId,
                    principalId = ownerA.principalId,
                    ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty()
                )
            )
            assertNull(
                payloadStore.get(
                    "server-1",
                    "channel-1",
                    environmentId = ownerB.environmentId,
                    principalId = ownerB.principalId,
                    ownerScopeId = ownerB.threadPayloadOwnerScopeId.orEmpty()
                )
            )
        } finally {
            api.releaseChannelMessages?.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun startRendersChannelShellWhenRealtimeInvalidatedFirstFrameCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val route = basePayload()
        dataSource.cachePayloadForTest(contentPayload("stale cached body"))
        dataSource.invalidateCachedPayload(route)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))

        val firstFrame = store.state.value.payload
        assertEquals("loading", firstFrame.status)
        assertEquals("channel-1", firstFrame.sourceThreadId)
        assertTrue(firstFrame.messages.isEmpty())
        assertFalse(firstFrame.shouldRenderThreadEarlyLoadingShell())
        assertEquals(0, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun startRendersCachedReplyParentContentBeforeReplyNetworkFetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val messageStore = SharedThreadMessageStore()
        val api = FakeThreadApi(bodyText = messagesResponse("reply body"))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        dataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-1",
            parentChannelId = "channel-1",
            parentMessage = contentPayload("stored parent body").messages.single(),
            threadChannelId = "thread-m1"
        )
        val route = unresolvedReplyPayload(parentMessageId = "m1")
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))

        val firstFrame = store.state.value.payload
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, firstFrame.surfaceKind)
        assertEquals("loading", firstFrame.status)
        assertEquals("loaded", firstFrame.originStatus)
        assertEquals("stored parent body", firstFrame.originMessage?.body)
        assertEquals("m1", firstFrame.originParentMessageId)
        assertTrue(firstFrame.messages.isEmpty())
        assertFalse(firstFrame.shouldRenderThreadEarlyLoadingShell())
        assertEquals(0, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun activityReplyStartKeepsStoreParentWhenTimelineCacheStillMarksItMissing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val messageStore = SharedThreadMessageStore()
        val api = FakeThreadApi(bodyText = messagesResponse("network reply"))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        dataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-1",
            parentChannelId = "channel-1",
            parentMessage = contentPayload("stored parent body").messages.single(),
            threadChannelId = "thread-m1"
        )
        val route =
            replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                .copy(focusedMessageId = "reply-1")
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(
                base = route,
                body = "cached focused reply",
                id = "reply-1",
                channelId = "thread-m1"
            ).copy(
                originMessage = null,
                originPreview = null,
                originStatus = "missing",
                originErrorCode = null
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))

        val firstState = store.state.value
        val firstFrame = firstState.payload
        assertEquals("cached focused reply", firstFrame.messages.single().body)
        assertEquals("loaded", firstFrame.originStatus)
        assertEquals("m1", firstFrame.originMessage?.id)
        assertEquals("stored parent body", firstFrame.originMessage?.body)
        assertEquals(ThreadAccessResolution.Loading, firstState.accessResolution)
        assertEquals(
            ThreadRouteMountPlan.FeatureSurfaces,
            firstFrame.threadRouteMountPlan(
                timelineSlice = firstState.timelineSlice,
                accessResolution = firstState.accessResolution
            )
        )
        assertFalse(
            firstFrame.hasRenderableContentForComposer(
                timelineSlice = firstState.timelineSlice,
                accessResolution = firstState.accessResolution
            )
        )
        assertEquals(0, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun startDoesNotRenderOrCacheContentFromRoutePayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val routePayload =
            contentPayload("stale route body").copy(
                status = "content",
                members = listOf(KuiklyThreadMemberItem(id = "route-member", displayName = "Route member")),
                settingsActions = listOf(KuiklyThreadSettingsAction(id = "route-action"))
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val observedPayloads = mutableListOf<KuiklyThreadScreenPayload>()
        val observer = scope.launch {
            store.state.collect { state -> observedPayloads += state.payload }
        }

        store.accept(SharedThreadIntent.Start(routePayload))

        val payload = store.state.value.payload
        assertFalse(observedPayloads.any { state -> state.messages.any { it.body == "stale route body" } })
        assertFalse(observedPayloads.any { state -> state.members.any { it.id == "route-member" } })
        assertFalse(observedPayloads.any { state -> state.settingsActions.any { it.id == "route-action" } })
        assertEquals("network body", payload.messages.single().body)
        api.failChannelMessages = true
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals("network body", store.state.value.payload.messages.single().body)
        observer.cancel()
        scope.cancel()
    }

    @Test
    fun sameRouteIntentDoesNotRestartOrImportOriginCandidateContent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val originCommitStarted = CompletableDeferred<Unit>()
        val releaseOriginCommit = CompletableDeferred<Unit>()
        val originCommitFinished = CompletableDeferred<Unit>()
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = { message ->
                    if (message.startsWith("thread_focused_origin_result")) {
                        originCommitFinished.complete(Unit)
                    }
                },
                beforeFocusedOriginCommit = {
                    originCommitStarted.complete(Unit)
                    releaseOriginCommit.await()
                }
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")

        try {
            store.accept(SharedThreadIntent.Start(route))
            // Freeze the independent origin writer before applying the same-route intent. The
            // hook is test-only; production does not wait here. A failed origin lookup is allowed
            // to advance loading -> error/missing in production, so loading is not itself the
            // invariant. Holding this boundary lets the test inspect the route-intent projection
            // before that legitimate transition, then verify the transition below as well.
            withTimeout(5_000) { originCommitStarted.await() }
            val afterStart = store.state.value.payload
            assertEquals(1, api.channelMessageCalls)
            assertEquals("loading", afterStart.originStatus)

            val originCandidate =
                KuiklyThreadMessageItem(
                    id = "m1",
                    authorId = "route-author",
                    authorName = "Route",
                    body = "late route origin should stay out of state"
                )
            store.accept(
                SharedThreadIntent.ApplyRouteIntent(
                    route.copy(
                        selectedTab = "files",
                        focusedMessageId = "reply-1",
                        focusedMessageRequestNonce = 7,
                        originMessage = originCandidate,
                        originStatus = "loaded",
                        messages =
                            listOf(
                                KuiklyThreadMessageItem(
                                    id = "route-message",
                                    authorId = "route-author",
                                    authorName = "Route",
                                    body = "late route body should stay out of state"
                                )
                            )
                    )
                )
            )

            val payloadBeforeOriginCommit = store.state.value.payload
            assertEquals(1, api.channelMessageCalls)
            assertEquals("files", payloadBeforeOriginCommit.selectedTab)
            assertEquals("reply-1", payloadBeforeOriginCommit.focusedMessageId)
            assertEquals(7, payloadBeforeOriginCommit.focusedMessageRequestNonce)
            assertEquals(afterStart.messages, payloadBeforeOriginCommit.messages)
            assertNull(payloadBeforeOriginCommit.originMessage)
            assertEquals(afterStart.originStatus, payloadBeforeOriginCommit.originStatus)

            releaseOriginCommit.complete(Unit)
            withTimeout(5_000) { originCommitFinished.await() }
            val payloadAfterOriginCommit = store.state.value.payload
            assertTrue(payloadAfterOriginCommit.originStatus != "loading")
            assertFalse(payloadAfterOriginCommit.originMessage?.body == originCandidate.body)
            assertEquals("files", payloadAfterOriginCommit.selectedTab)
            assertEquals("reply-1", payloadAfterOriginCommit.focusedMessageId)
        } finally {
            releaseOriginCommit.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun repeatedActivityReplyOpenUpdatesFocusedMessageForSameThread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("reply body", id = "reply-2", channelId = "thread-m1")
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val firstRoute = replyPayload(parentMessageId = "m1", threadId = "thread-m1").copy(focusedMessageId = "reply-1")
        val secondRoute = firstRoute.copy(focusedMessageId = "reply-2")

        store.accept(SharedThreadIntent.Start(firstRoute))
        store.accept(SharedThreadIntent.Start(secondRoute))

        assertEquals("reply-2", store.state.value.payload.focusedMessageId)
        assertEquals(listOf("thread-m1", "thread-m1"), api.channelIds)
        scope.cancel()
    }

    @Test
    fun staleFocusedOriginWriterCannotOverwriteRepeatedActivityReplyFocus() = runBlocking {
        val externalJob = SupervisorJob()
        val scope = CoroutineScope(externalJob + Dispatchers.Default)
        val staleCommitStarted = CompletableDeferred<Unit>()
        val freshCommitStarted = CompletableDeferred<Unit>()
        val releaseStaleCommit = CompletableDeferred<Unit>()
        val freshCommitFinished = CompletableDeferred<Unit>()
        val staleCommitFinished = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("reply body", id = "reply-2", channelId = "thread-m1")
            )
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        networkApi = api,
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
                    ),
                scope = scope,
                diagnosticsLogger = { message ->
                    if (message.contains("thread_focused_origin_result")) {
                        if (!freshCommitFinished.complete(Unit)) {
                            staleCommitFinished.complete(Unit)
                        }
                    }
                },
                beforeFocusedOriginCommit = { payloadBeforeCommit ->
                    if (staleCommitStarted.complete(Unit)) {
                        assertEquals("reply-1", payloadBeforeCommit.focusedMessageId)
                        try {
                            releaseStaleCommit.await()
                        } catch (_: CancellationException) {
                            withContext(NonCancellable) { releaseStaleCommit.await() }
                        }
                    } else {
                        freshCommitStarted.complete(Unit)
                    }
                }
            )
        val firstRoute =
            replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                .copy(focusedMessageId = "reply-1")
        val secondRoute = firstRoute.copy(focusedMessageId = "reply-2")

        try {
            store.accept(SharedThreadIntent.Start(firstRoute))
            withTimeout(5_000) { staleCommitStarted.await() }

            withTimeout(5_000) { store.accept(SharedThreadIntent.Start(secondRoute)) }
            withTimeout(5_000) { freshCommitStarted.await() }
            withTimeout(5_000) { freshCommitFinished.await() }
            assertEquals("reply-2", store.state.value.payload.focusedMessageId)

            releaseStaleCommit.complete(Unit)
            withTimeout(5_000) { staleCommitFinished.await() }
            assertEquals("reply-2", store.state.value.payload.focusedMessageId)
        } finally {
            releaseStaleCommit.complete(Unit)
            externalJob.cancelAndJoin()
        }
    }

    @Test
    fun routeContextDoesNotOverrideCanonicalCacheMembershipState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        dataSource.cachePayloadForTest(
            contentPayload("cached body").copy(
                joined = false,
                canSend = false,
                membersCount = 7
            )
        )
        val routePayload =
            basePayload().copy(
                joined = true,
                canSend = true,
                membersCount = 99
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(routePayload))

        val payload = store.state.value.payload
        assertEquals("cached body", payload.messages.single().body)
        assertEquals(false, payload.joined)
        assertEquals(false, payload.canSend)
        assertEquals(7, payload.membersCount)
        scope.cancel()
    }

    @Test
    fun successfulFetchWritesPayloadCacheForNextStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals("network body", store.state.value.payload.messages.single().body)
        api.failChannelMessages = true
        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("network body", payload.messages.single().body)
        assertEquals(2, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun emptyDirectMessageFetchRendersEmptyStateInsteadOfError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = emptyMessagesResponse())
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(directMessagePayload()))

        val payload = store.state.value.payload
        assertEquals("empty", payload.status)
        assertEquals(null, payload.errorCode)
        assertEquals(emptyList(), payload.messages)
        assertEquals("No messages yet", payload.emptyTitle)
        assertEquals("This conversation has no messages yet.", payload.emptyDetail)
        assertEquals("dm-1", payload.sourceThreadId)
        assertEquals("dm-1", payload.streamChannelId)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
        assertTrue(store.state.value.canonicalAccessResolved)
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun freshDirectMessageEmptyResolvesBeforeDelayedHomeProjection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val homeRequested = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                homeRequested.complete(Unit)
                releaseHome.await()
                DataResult.Content(emptyHomeSnapshot())
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Empty(EmptyReason.NoMessages)
                    ),
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val accessHistory = mutableListOf<ThreadAccessResolution>()
        val accessCollector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                store.state
                    .map { it.accessResolution }
                    .distinctUntilChanged()
                    .collect(accessHistory::add)
            }

        store.dispatch(SharedThreadIntent.Start(directMessagePayload()))
        homeRequested.await()
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedBeforeHome = store.state.value
        assertEquals("empty", resolvedBeforeHome.payload.status)
        assertEquals(null, resolvedBeforeHome.payload.errorCode)
        assertEquals(ThreadAccessResolution.Resolved, resolvedBeforeHome.accessResolution)
        assertTrue(resolvedBeforeHome.canonicalAccessResolved)
        assertFalse(resolvedBeforeHome.initialHomeAccessLookupComplete)
        assertEquals(null, resolvedBeforeHome.initialHomeAccessCandidate)

        releaseHome.complete(Unit)
        withTimeout(1_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
        yield()
        assertTrue(accessHistory.isNotEmpty())
        assertEquals(ThreadAccessResolution.Loading, accessHistory.first())
        assertEquals(ThreadAccessResolution.Resolved, accessHistory.last())
        assertFalse(accessHistory.contains(ThreadAccessResolution.Error))
        assertFalse(accessHistory.contains(ThreadAccessResolution.NoPermission))
        accessCollector.cancel()
        scope.cancel()
    }

    @Test
    fun freshDirectMessageEmptyStaysLoadingWhenHomeCompletesFirstWithoutCandidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val conversationRepository =
            FakeConversationRepository(
                threadResult = DataResult.Empty(EmptyReason.NoMessages)
            ).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = conversationRepository,
                channelRepository = FakeChannelRepository { DataResult.Content(emptyHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val accessHistory = mutableListOf<ThreadAccessResolution>()
        val accessCollector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                store.state
                    .map { it.accessResolution }
                    .distinctUntilChanged()
                    .collect(accessHistory::add)
            }

        store.dispatch(SharedThreadIntent.Start(directMessagePayload()))
        conversationRepository.getThreadStarted?.await()
        withTimeout(1_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }

        val homeFirst = store.state.value
        assertTrue(homeFirst.initialHomeAccessLookupComplete)
        assertEquals(null, homeFirst.initialHomeAccessCandidate)
        assertFalse(homeFirst.canonicalAccessResolved)
        assertEquals(ThreadAccessResolution.Loading, homeFirst.accessResolution)
        yield()
        assertTrue(accessHistory.isNotEmpty())
        assertTrue(accessHistory.all { it == ThreadAccessResolution.Loading })

        conversationRepository.releaseGetThread?.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolved = store.state.value
        assertEquals("empty", resolved.payload.status)
        assertTrue(resolved.canonicalAccessResolved)
        assertTrue(resolved.initialHomeAccessLookupComplete)
        assertEquals(null, resolved.initialHomeAccessCandidate)
        assertEquals(ThreadAccessResolution.Resolved, resolved.accessResolution)
        yield()
        assertEquals(ThreadAccessResolution.Loading, accessHistory.first())
        assertEquals(ThreadAccessResolution.Resolved, accessHistory.last())
        assertFalse(accessHistory.contains(ThreadAccessResolution.Error))
        assertFalse(accessHistory.contains(ThreadAccessResolution.NoPermission))
        accessCollector.cancel()
        scope.cancel()
    }

    @Test
    fun existingChannelEmptyResolvesWithCanonicalEmptyAndHomeCandidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Empty(EmptyReason.NoMessages)
                    ),
                channelRepository = FakeChannelRepository { DataResult.Content(joinedHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolved = store.state.value
        assertEquals("empty", resolved.payload.status)
        assertEquals(ThreadAccessResolution.Resolved, resolved.accessResolution)
        assertTrue(resolved.canonicalAccessResolved)
        assertTrue(resolved.initialHomeAccessLookupComplete)
        assertTrue(resolved.initialHomeAccessCandidate?.ids.orEmpty().contains("channel-1"))
        scope.cancel()
    }

    @Test
    fun emptyChannelFromLaunchPlaceholderShowsNoMessagesNotLoading() = runBlocking {
        // task #326: a channel that resolves to Content with zero messages (in the
        // joined home directory) must not keep the route placeholder's "Loading..."
        // as its empty-state title — otherwise the empty channel shows 「加载中」 forever.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult =
                            DataResult.Content(
                                threadSnapshot(messages = emptyList(), conversation = conversationSummary())
                            )
                    ),
                channelRepository = FakeChannelRepository { DataResult.Content(joinedHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(
            SharedThreadIntent.Start(
                basePayload().copy(
                    status = "loading",
                    emptyTitle = ThreadRouteLoadingPlaceholderTitle,
                    emptyDetail = ""
                )
            )
        )
        withTimeout(1_000) {
            while (store.state.value.payload.status == "loading") {
                delay(10)
            }
        }

        val resolved = store.state.value.payload
        assertEquals("empty", resolved.status)
        assertEquals("No messages yet", resolved.emptyTitle)
        assertEquals("This conversation has no messages yet.", resolved.emptyDetail)
        scope.cancel()
    }

    @Test
    fun emptyReplyThreadStillFailsClosedWithoutParentAccessCandidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route =
            activityReplyPayload(
                parentKind = ConversationKind.Channel,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                threadId = "thread-m1"
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Empty(EmptyReason.NoMessages),
                        createThreadResult = DataResult.Error(ScreenError("missing_thread_identity"))
                    ),
                channelRepository = FakeChannelRepository { DataResult.Content(emptyHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Error) {
                delay(10)
            }
        }

        val failed = store.state.value
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, failed.payload.surfaceKind)
        assertEquals("empty", failed.payload.status)
        assertTrue(failed.canonicalAccessResolved)
        assertTrue(failed.initialHomeAccessLookupComplete)
        assertEquals(null, failed.initialHomeAccessCandidate)
        assertFalse(failed.writeActionsEnabled)
        assertFalse(failed.payload.joined)
        assertFalse(failed.payload.canSend)
        assertFalse(failed.payload.composer.enabled)
        scope.cancel()
    }

    @Test
    fun noPermissionAndErrorRemainFailClosedForDirectMessageAccess() = runBlocking {
        suspend fun resolve(result: DataResult<ThreadSnapshot>): SharedThreadState {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val dataSource =
                testSharedThreadDataSource(
                    networkApi = FakeThreadApi(failChannelMessages = true),
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    conversationRepository = FakeConversationRepository(threadResult = result),
                    channelRepository = FakeChannelRepository { DataResult.Content(emptyHomeSnapshot()) }
                )
            val store = SharedThreadStore(dataSource = dataSource, scope = scope)
            store.dispatch(SharedThreadIntent.Start(directMessagePayload()))
            withTimeout(1_000) {
                while (
                    store.state.value.accessResolution != ThreadAccessResolution.NoPermission &&
                    store.state.value.accessResolution != ThreadAccessResolution.Error
                ) {
                    delay(10)
                }
            }
            return store.state.value.also { scope.cancel() }
        }

        val noPermission =
            resolve(DataResult.NoPermission(PermissionBlocker(code = "thread_forbidden")))
        assertEquals(ThreadAccessResolution.NoPermission, noPermission.accessResolution)
        assertFalse(noPermission.canonicalAccessResolved)
        assertEquals("no_permission", noPermission.payload.status)
        assertEquals(null, noPermission.payload.errorServerMessage)
        assertFalse(noPermission.writeActionsEnabled)
        assertFalse(noPermission.payload.joined)
        assertFalse(noPermission.payload.canSend)
        assertFalse(noPermission.payload.composer.enabled)

        val edgeBlocked =
            resolve(
                DataResult.NoPermission(
                    PermissionBlocker(code = "thread_forbidden", serverMessage = "edge_blocked")
                )
            )
        assertEquals(ThreadAccessResolution.NoPermission, edgeBlocked.accessResolution)
        assertEquals("no_permission", edgeBlocked.payload.status)
        assertEquals("edge_blocked", edgeBlocked.payload.errorServerMessage)
        assertFalse(edgeBlocked.writeActionsEnabled)

        val error = resolve(DataResult.Error(ScreenError(code = "offline", retryable = true)))
        assertEquals(ThreadAccessResolution.Error, error.accessResolution)
        assertFalse(error.canonicalAccessResolved)
        assertEquals("error", error.payload.status)
        assertFalse(error.writeActionsEnabled)
        assertFalse(error.payload.joined)
        assertFalse(error.payload.canSend)
        assertFalse(error.payload.composer.enabled)
    }

    @Test
    fun cachedThreadRefreshExposesBackgroundRefreshingUntilNetworkCompletes() = runPortableCoroutineTest {
        repeat(25) { iteration ->
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
            val loadingStateApplied = CompletableDeferred<KuiklyThreadScreenPayload>()
            val releaseLoadingState = CompletableDeferred<Unit>()
            val networkStarted = CompletableDeferred<Unit>()
            val releaseNetwork = CompletableDeferred<Unit>()
            val api =
                FakeThreadApi(bodyText = messagesResponse("network body $iteration")).apply {
                    channelMessagesStarted = networkStarted
                    releaseChannelMessages = releaseNetwork
                }
            val dataSource =
                testSharedThreadDataSource(
                    networkApi = api,
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
                )
            dataSource.cachePayloadForTest(contentPayload("cached body $iteration"))
            val store =
                SharedThreadStore(
                    dataSource = dataSource,
                    scope = scope,
                    afterRefreshLoadingStateApplied = { payload ->
                        assertTrue(
                            loadingStateApplied.complete(payload),
                            "iteration=$iteration loading state must be applied exactly once"
                        )
                        releaseLoadingState.await()
                    }
                )

            try {
                store.dispatch(SharedThreadIntent.Start(basePayload()))

                val committed = loadingStateApplied.await()
                assertEquals(0, api.channelMessageCalls, "iteration=$iteration must gate before fetch")
                assertEquals("content", committed.status, "iteration=$iteration")
                assertEquals("cached body $iteration", committed.messages.single().body, "iteration=$iteration")
                assertTrue(committed.backgroundRefreshing, "iteration=$iteration")

                releaseLoadingState.complete(Unit)
                networkStarted.await()
                val inFlight = store.state.value.payload
                assertEquals(1, api.channelMessageCalls, "iteration=$iteration")
                assertEquals("cached body $iteration", inFlight.messages.single().body, "iteration=$iteration")
                assertTrue(inFlight.backgroundRefreshing, "iteration=$iteration")

                releaseNetwork.complete(Unit)
                val completed =
                    store.state
                        .map { state -> state.payload }
                        .first { payload ->
                            !payload.backgroundRefreshing &&
                                payload.messages.singleOrNull()?.body == "network body $iteration"
                        }
                assertFalse(completed.backgroundRefreshing, "iteration=$iteration")
                assertEquals("network body $iteration", completed.messages.single().body, "iteration=$iteration")
                assertEquals(1, api.channelMessageCalls, "iteration=$iteration must fetch exactly once")
            } finally {
                releaseLoadingState.complete(Unit)
                releaseNetwork.complete(Unit)
                scopeJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun warmChannelCacheResolvesFooterFromHomeCandidateBeforeCanonicalFetch() = runBlocking {
        // Task #1141: on a Channel route with renderable cached content, the Home directory
        // candidate (joined + canSend) is the write authority; the footer must resolve when the
        // directory answers, not after the first network refresh. Home releases first, the
        // canonical fetch stays held.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "network body",
                            conversation = conversationSummary().copy(joined = true)
                        )
                    )
            ).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
            }
        val homeRequested = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                homeRequested.complete(Unit)
                releaseHome.await()
                DataResult.Content(joinedHomeSnapshot())
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
        )
        // A real memory-cached payload was written from a canonical snapshot, which already
        // carried the composer enabled for a joined + canSend conversation; model that here.
        val cached = contentPayload("cached body")
        dataSource.cachePayloadForTest(
            cached.copy(joined = true, canSend = true, composer = cached.composer.copy(enabled = true))
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        repository.getThreadStarted?.await()
        homeRequested.await()

        val cachedFrame = store.state.value
        assertEquals("content", cachedFrame.payload.status)
        assertEquals(ThreadAccessResolution.Loading, cachedFrame.accessResolution)
        assertFalse(cachedFrame.writeActionsEnabled)

        releaseHome.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }
        val homeResolved = store.state.value
        assertFalse(homeResolved.canonicalAccessResolved)
        assertEquals(ThreadAccessResolution.Resolved, homeResolved.accessResolution)
        assertTrue(homeResolved.writeActionsEnabled)
        assertTrue(homeResolved.payload.joined)
        assertTrue(homeResolved.payload.canSend)
        assertTrue(homeResolved.payload.composer.enabled)
        assertTrue(
            homeResolved.payload.isThreadPrimaryFooterReady(
                timelineSlice = homeResolved.timelineSlice,
                accessResolution = homeResolved.accessResolution
            )
        )

        repository.releaseGetThread?.complete(Unit)
        // The canonical body and the canonicalAccessResolved flag land in separate reduces;
        // wait for the flag, then read one frame.
        withTimeout(1_000) {
            while (!store.state.value.canonicalAccessResolved) {
                delay(10)
            }
        }
        val canonicalFrame = store.state.value
        assertEquals("network body", canonicalFrame.payload.messages.singleOrNull()?.body)
        assertEquals(ThreadAccessResolution.Resolved, canonicalFrame.accessResolution)
        assertTrue(canonicalFrame.writeActionsEnabled)
        scope.cancel()
    }

    @Test
    fun warmChannelCacheKeepsFooterUnresolvedUntilInitialLookupCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "network body",
                            conversation = conversationSummary().copy(joined = true)
                        )
                    )
            ).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
            }
        val homeRequested = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                homeRequested.complete(Unit)
                releaseHome.await()
                DataResult.Content(unjoinedHomeSnapshot())
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
        )
        dataSource.cachePayloadForTest(contentPayload("cached body").copy(joined = true, canSend = true))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        repository.getThreadStarted?.await()
        homeRequested.await()
        store.accept(
            SharedThreadIntent.ApplyRouteIntent(
                basePayload().copy(
                    selectedTab = "files",
                    focusedMessageId = "m1",
                    focusedMessageRequestNonce = 7
                )
            )
        )

        val cachedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Loading, cachedFrame.accessResolution)
        assertEquals("content", cachedFrame.payload.status)
        assertTrue(cachedFrame.payload.joined)
        assertFalse(
            cachedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = cachedFrame.timelineSlice,
                accessResolution = cachedFrame.accessResolution
            )
        )

        repository.releaseGetThread?.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.payload.messages.singleOrNull()?.body != "network body") {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        releaseHome.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, resolvedFrame.accessResolution)
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertTrue(resolvedFrame.writeActionsEnabled)
        assertEquals("files", resolvedFrame.payload.selectedTab)
        assertEquals("m1", resolvedFrame.payload.focusedMessageId)
        assertEquals(7, resolvedFrame.payload.focusedMessageRequestNonce)
        assertEquals(1, channelRepository.homeCalls)
        assertTrue(
            resolvedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = resolvedFrame.timelineSlice,
                accessResolution = resolvedFrame.accessResolution
            )
        )
        scope.cancel()
    }

    @Test
    fun warmReplyThreadCacheUsesJoinedParentAccessWhenCanonicalCompletesFirst() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val canonicalConversation = replyThreadConversation(joined = false, canSend = false)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            conversation = canonicalConversation
                        )
                    )
            ).apply {
                getReplyThreadStarted = CompletableDeferred()
                releaseGetReplyThread = CompletableDeferred()
            }
        val homeRequested = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                homeRequested.complete(Unit)
                releaseHome.await()
                DataResult.Content(joinedHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached reply").copy(
                joined = false,
                canSend = false,
                composer = route.composer.copy(enabled = false)
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val renderEvents = mutableListOf<SharedThreadRenderSlice>()
        val renderJob = launch {
            store.renderSliceFlow().collect { slice -> renderEvents += slice }
        }

        store.dispatch(SharedThreadIntent.Start(route))
        repository.getReplyThreadStarted?.await()
        homeRequested.await()

        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        assertFalse(
            store.state.value.payload.isThreadPrimaryFooterReady(
                timelineSlice = store.state.value.timelineSlice,
                accessResolution = store.state.value.accessResolution
            )
        )

        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(1_000) {
            while (!store.state.value.canonicalAccessResolved) {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        releaseHome.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, resolvedFrame.accessResolution)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, resolvedFrame.payload.surfaceKind)
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertTrue(resolvedFrame.writeActionsEnabled)
        assertEquals(1, channelRepository.channelsCalls)
        assertFalse(
            renderEvents.any { slice ->
                slice.accessResolution == ThreadAccessResolution.Resolved && !slice.payload.joined
            }
        )
        assertTrue(
            resolvedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = resolvedFrame.timelineSlice,
                accessResolution = resolvedFrame.accessResolution
            )
        )
        renderJob.cancel()
        scope.cancel()
    }

    @Test
    fun warmReplyThreadCacheUsesJoinedParentAccessWhenHomeCompletesFirst() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val canonicalConversation = replyThreadConversation(joined = false, canSend = false)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            conversation = canonicalConversation
                        )
                    )
            ).apply {
                getReplyThreadStarted = CompletableDeferred()
                releaseGetReplyThread = CompletableDeferred()
            }
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(joinedHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached reply").copy(
                joined = false,
                canSend = false,
                composer = route.composer.copy(enabled = false)
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val renderEvents = mutableListOf<SharedThreadRenderSlice>()
        val renderJob = launch {
            store.renderSliceFlow().collect { slice -> renderEvents += slice }
        }

        store.dispatch(SharedThreadIntent.Start(route))
        repository.getReplyThreadStarted?.await()
        withTimeout(1_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }

        // Hands cd41d823: a positive parent candidate (joined + canSend) is the only
        // write authority the reply surface ever consults — the canonical reply stream
        // does not attest it. With renderable cached content the footer must therefore
        // resolve when the directory answers, not after the held canonical fetch.
        val homeFirstFrame = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, homeFirstFrame.accessResolution)
        assertTrue(homeFirstFrame.payload.joined)
        assertTrue(homeFirstFrame.payload.canSend)
        assertTrue(homeFirstFrame.payload.composer.enabled)
        assertTrue(homeFirstFrame.writeActionsEnabled)
        assertTrue(
            homeFirstFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = homeFirstFrame.timelineSlice,
                accessResolution = homeFirstFrame.accessResolution
            )
        )

        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertEquals(1, channelRepository.channelsCalls)
        assertFalse(
            renderEvents.any { slice ->
                slice.accessResolution == ThreadAccessResolution.Resolved && !slice.payload.joined
            }
        )
        assertTrue(
            resolvedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = resolvedFrame.timelineSlice,
                accessResolution = resolvedFrame.accessResolution
            )
        )
        renderJob.cancel()
        scope.cancel()
    }

    @Test
    fun warmReplyThreadCacheUsesUnjoinedParentAccessOverCanonicalThreadAccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val canonicalConversation = replyThreadConversation(joined = true, canSend = true)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            conversation = canonicalConversation
                        )
                    )
            )
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                releaseHome.await()
                DataResult.Content(unjoinedHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached reply").copy(joined = true, canSend = true)
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val renderEvents = mutableListOf<SharedThreadRenderSlice>()
        val renderJob = launch {
            store.renderSliceFlow().collect { slice -> renderEvents += slice }
        }

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(1_000) {
            while (!store.state.value.canonicalAccessResolved) {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        releaseHome.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, resolvedFrame.payload.surfaceKind)
        assertFalse(resolvedFrame.payload.joined)
        assertFalse(resolvedFrame.payload.canSend)
        assertFalse(resolvedFrame.payload.composer.enabled)
        assertTrue(resolvedFrame.writeActionsEnabled)
        assertEquals(1, channelRepository.channelsCalls)
        assertFalse(
            renderEvents.any { slice ->
                slice.accessResolution == ThreadAccessResolution.Resolved && slice.payload.joined
            }
        )
        assertTrue(
            resolvedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = resolvedFrame.timelineSlice,
                accessResolution = resolvedFrame.accessResolution
            )
        )
        renderJob.cancel()
        scope.cancel()
    }

    @Test
    fun warmReplyThreadCacheUsesUnjoinedParentAccessWhenHomeCompletesFirst() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            conversation = replyThreadConversation(joined = true, canSend = true)
                        )
                    )
            ).apply {
                getReplyThreadStarted = CompletableDeferred()
                releaseGetReplyThread = CompletableDeferred()
            }
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(unjoinedHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached reply").copy(joined = true, canSend = true)
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val renderEvents = mutableListOf<SharedThreadRenderSlice>()
        val renderJob = launch {
            store.renderSliceFlow().collect { slice -> renderEvents += slice }
        }

        store.dispatch(SharedThreadIntent.Start(route))
        repository.getReplyThreadStarted?.await()
        withTimeout(1_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        assertFalse(store.state.value.payload.joined)
        assertFalse(
            store.state.value.payload.isThreadPrimaryFooterReady(
                timelineSlice = store.state.value.timelineSlice,
                accessResolution = store.state.value.accessResolution
            )
        )

        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertFalse(resolvedFrame.payload.joined)
        assertFalse(resolvedFrame.payload.canSend)
        assertFalse(resolvedFrame.payload.composer.enabled)
        assertTrue(resolvedFrame.writeActionsEnabled)
        assertFalse(
            renderEvents.any { slice ->
                slice.accessResolution == ThreadAccessResolution.Resolved && slice.payload.joined
            }
        )
        renderJob.cancel()
        scope.cancel()
    }

    @Test
    fun activityReplyThreadUsesJoinedDirectMessageParentAccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = activityReplyPayload(
            parentKind = ConversationKind.DirectMessage,
            parentChannelId = "dm-1",
            parentMessageId = "dm-parent-message",
            threadId = "dm-thread-1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "dm-thread-1",
                            body = "network dm reply",
                            conversation =
                                replyThreadConversation(
                                    threadId = "dm-thread-1",
                                    parentChannelId = "dm-1",
                                    parentMessageId = "dm-parent-message",
                                    joined = false,
                                    canSend = false
                                )
                        )
                    )
            )
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(joinedDmHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached dm reply", channelId = "dm-thread-1").copy(
                joined = false,
                canSend = false,
                composer = route.composer.copy(enabled = false)
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, resolvedFrame.payload.parentSurfaceKind)
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertEquals("dm-1", resolvedFrame.payload.parentChannelId)
        assertEquals(1, channelRepository.directMessageCalls)
        scope.cancel()
    }

    @Test
    fun activityReplyThreadUsesCanonicalHomeStoreSnapshotWithoutDuplicateHomeRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    )
            )
        val channelRepository = FakeChannelRepository { DataResult.Error(ScreenError("unexpected_home_request")) }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository,
                homeSnapshotProvider = { joinedHomeSnapshot() }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        assertEquals(0, channelRepository.homeCalls)
        assertEquals(1, repository.getReplyThreadRequests.size)
        assertEquals(KuiklyThreadSurfaceKind.Channel, store.state.value.payload.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, store.state.value.payload.parentChannelKind)
        assertTrue(store.state.value.payload.joined)
        assertTrue(store.state.value.payload.composer.enabled)
        scope.cancel()
    }

    @Test
    fun activityReplyThreadUsesJoinedJointParentAccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = activityReplyPayload(
            parentKind = ConversationKind.Joint,
            parentChannelId = "joint-1",
            parentMessageId = "joint-parent-message",
            threadId = "joint-thread-1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "joint-thread-1",
                            body = "network joint reply",
                            conversation =
                                replyThreadConversation(
                                    threadId = "joint-thread-1",
                                    parentChannelId = "joint-1",
                                    parentMessageId = "joint-parent-message",
                                    joined = false,
                                    canSend = false
                                )
                        )
                    )
            )
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(joinedJointHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(KuiklyThreadSurfaceKind.Channel, resolvedFrame.payload.parentSurfaceKind)
        assertEquals(JointChannelKindWireValue, resolvedFrame.payload.parentChannelKind)
        assertEquals(ConversationKind.Joint, resolvedFrame.payload.resolveThreadRouteIdentity().parentConversationKind)
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertEquals("joint-1", resolvedFrame.payload.parentChannelId)
        assertEquals(1, channelRepository.channelsCalls)
        scope.cancel()
    }

    @Test
    fun activityReplyThreadKeepsCanonicalRepliesReadableWhenParentAccessCandidateIsMissing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            id = "reply-1",
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    )
            )
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(replyChildOnlyHomeSnapshot(joined = true, canSend = true))
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(1_000) {
            while (
                store.state.value.accessResolution != ThreadAccessResolution.Resolved ||
                store.state.value.writeActionsEnabled
            ) {
                delay(10)
            }
        }

        val degradedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, degradedFrame.accessResolution)
        assertFalse(degradedFrame.accessResolution.shouldRenderThreadAccessRetry())
        assertEquals(listOf("network reply"), degradedFrame.payload.messages.map { it.body })
        assertFalse(degradedFrame.writeActionsEnabled)
        assertFalse(degradedFrame.payload.joined)
        assertFalse(degradedFrame.payload.canSend)
        assertFalse(degradedFrame.payload.composer.enabled)
        assertTrue(
            degradedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = degradedFrame.timelineSlice,
                accessResolution = degradedFrame.accessResolution
            )
        )
        assertFalse(
            shouldShowThreadComposerForReadyContent(
                joined = degradedFrame.payload.joined,
                showComposer = degradedFrame.payload.showComposer,
                selectedTab = degradedFrame.payload.selectedTab
            )
        )
        assertEquals(
            null,
            degradedFrame.payload.resolvedTaskLookupChannelId(
                writeActionsEnabled = degradedFrame.writeActionsEnabled
            )
        )
        scope.cancel()
    }

    @Test
    fun readOnlyReplyBecomingEmptyStaysWriteClosedInBothParentRefreshCompletionOrders() = runBlocking {
        suspend fun verify(homeCompletesFirst: Boolean) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val route =
                activityReplyPayload(
                    parentKind = ConversationKind.Channel,
                    parentChannelId = "channel-1",
                    parentMessageId = "m1",
                    threadId = "thread-m1"
                )
            val retryHomeStarted = CompletableDeferred<Unit>()
            val retryHomeFinished = CompletableDeferred<Unit>()
            val releaseRetryHome = CompletableDeferred<Unit>()
            val retryHomeMarker = "retry-home-${if (homeCompletesFirst) "first" else "last"}"
            var homeAttempt = 0
            val channelRepository =
                FakeChannelRepository {
                    homeAttempt += 1
                    if (homeAttempt == 2) {
                        retryHomeStarted.complete(Unit)
                        if (!homeCompletesFirst) {
                            releaseRetryHome.await()
                        }
                        retryHomeFinished.complete(Unit)
                    }
                    val home = replyChildOnlyHomeSnapshot(joined = true, canSend = true)
                    DataResult.Content(
                        if (homeAttempt == 2) {
                            home.copy(
                                channels =
                                    home.channels +
                                        ConversationSummary(
                                            threadId = ThreadId(retryHomeMarker),
                                            channelId = ChannelId(retryHomeMarker),
                                            kind = ConversationKind.Channel,
                                            name = retryHomeMarker
                                        ).toHomeChannelRow()
                            )
                        } else {
                            home
                        }
                    )
                }
            val repository =
                FakeConversationRepository(
                    threadResult =
                        DataResult.Content(
                            threadSnapshot(
                                threadId = "thread-m1",
                                body = "network reply",
                                id = "reply-1",
                                conversation = replyThreadConversation(joined = false, canSend = false)
                            )
                        )
                )
            val store =
                SharedThreadStore(
                    dataSource =
                        testSharedThreadDataSource(
                            networkApi = FakeThreadApi(failChannelMessages = true),
                            sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                            conversationRepository = repository,
                            channelRepository = channelRepository
                        ),
                    scope = scope
                )

            store.dispatch(SharedThreadIntent.Start(route))
            withTimeout(1_000) {
                while (
                    store.state.value.accessResolution != ThreadAccessResolution.Resolved ||
                    store.state.value.writeActionsEnabled
                ) {
                    delay(10)
                }
            }
            repository.threadResult = DataResult.Empty(EmptyReason.NoMessages)
            if (homeCompletesFirst) {
                // Start already resolved and cached the reply stream under this named environment,
                // so Refresh re-reads the thread by id (getThread) instead of by parent pair. The
                // parent-pair hook only fired here while a blank environment disabled that cache.
                repository.getThreadStarted = CompletableDeferred()
                repository.releaseGetThread = CompletableDeferred()
            }
            store.dispatch(SharedThreadIntent.Refresh)
            withTimeout(5_000) { retryHomeStarted.await() }
            if (homeCompletesFirst) {
                withTimeout(5_000) { repository.getThreadStarted?.await() }
                retryHomeFinished.await()
                withTimeout(1_000) {
                    while (retryHomeMarker !in store.state.value.payload.knownMarkdownChannelNames) {
                        delay(10)
                    }
                }
                assertEquals("content", store.state.value.payload.status)
                assertFalse(store.state.value.writeActionsEnabled)
                repository.releaseGetThread?.complete(Unit)
            } else {
                withTimeout(1_000) {
                    while (store.state.value.payload.status != "empty") {
                        delay(10)
                    }
                }
                assertFalse(store.state.value.writeActionsEnabled)
                releaseRetryHome.complete(Unit)
                retryHomeFinished.await()
            }
            withTimeout(1_000) {
                while (
                    store.state.value.payload.status != "empty" ||
                    retryHomeMarker !in store.state.value.payload.knownMarkdownChannelNames
                ) {
                    delay(10)
                }
            }

            val finalState = store.state.value
            assertEquals(ThreadAccessResolution.Resolved, finalState.accessResolution)
            assertFalse(finalState.writeActionsEnabled)
            assertFalse(finalState.payload.joined)
            assertFalse(finalState.payload.canSend)
            assertFalse(finalState.payload.composer.enabled)
            assertEquals(
                null,
                finalState.payload.resolvedTaskLookupChannelId(
                    writeActionsEnabled = finalState.writeActionsEnabled
                )
            )
            scope.cancel()
        }

        verify(homeCompletesFirst = true)
        verify(homeCompletesFirst = false)
    }

    @Test
    fun activityReplyThreadUsesLoadedParentContextWhenParentAccessLookupTimesOut() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val neverCompleteHome = CompletableDeferred<Unit>()
        var homeAttempt = 0
        val channelRepository =
            FakeChannelRepository {
                homeAttempt += 1
                if (homeAttempt == 1) {
                    neverCompleteHome.await()
                }
                DataResult.Content(joinedHomeSnapshot())
            }
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            id = "reply-1",
                            parentMessage =
                                message(
                                    threadId = "channel-1",
                                    id = "m1",
                                    seq = 9,
                                    body = "parent body"
                                ),
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(1_500) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertTrue(resolvedFrame.canonicalAccessResolved)
        assertTrue(resolvedFrame.initialHomeAccessLookupComplete)
        assertEquals(null, resolvedFrame.initialHomeAccessCandidate)
        assertEquals("loaded", resolvedFrame.payload.originStatus)
        assertEquals("m1", resolvedFrame.payload.originMessage?.id)
        assertEquals("parent body", resolvedFrame.payload.originMessage?.body)
        assertEquals("reply-1", resolvedFrame.payload.messages.single().id)
        assertEquals("network reply", resolvedFrame.payload.messages.single().body)
        assertEquals(ThreadAccessResolution.Resolved, resolvedFrame.accessResolution)
        assertFalse(resolvedFrame.accessResolution.shouldRenderThreadAccessRetry())
        assertFalse(resolvedFrame.payload.joined)
        assertFalse(resolvedFrame.payload.canSend)
        assertFalse(resolvedFrame.payload.composer.enabled)
        assertFalse(resolvedFrame.writeActionsEnabled)
        assertEquals(1, repository.getReplyThreadRequests.size)
        assertEquals(1, channelRepository.channelsCalls)

        store.dispatch(SharedThreadIntent.Refresh)
        withTimeout(1_000) {
            while (!store.state.value.writeActionsEnabled) {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
        assertTrue(store.state.value.payload.joined)
        assertTrue(store.state.value.payload.canSend)
        assertTrue(store.state.value.payload.composer.enabled)
        assertEquals(2, channelRepository.channelsCalls)
        scope.cancel()
    }

    @Test
    fun activityReplyThreadUsesLoadedParentContextWhenHomeLookupCompletesFirstWithoutCandidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            id = "reply-1",
                            parentMessage =
                                message(
                                    threadId = "channel-1",
                                    id = "m1",
                                    seq = 9,
                                    body = "parent body"
                                ),
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    )
            ).apply {
                getReplyThreadStarted = CompletableDeferred()
                releaseGetReplyThread = CompletableDeferred()
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = FakeChannelRepository { DataResult.Content(emptyHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        repository.getReplyThreadStarted?.await()
        withTimeout(1_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }
        assertFalse(store.state.value.canonicalAccessResolved)
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)

        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertTrue(resolvedFrame.canonicalAccessResolved)
        assertEquals("loaded", resolvedFrame.payload.originStatus)
        assertEquals("m1", resolvedFrame.payload.originMessage?.id)
        assertEquals("reply-1", resolvedFrame.payload.messages.single().id)
        assertFalse(resolvedFrame.payload.joined)
        assertFalse(resolvedFrame.payload.canSend)
        assertFalse(resolvedFrame.payload.composer.enabled)
        assertFalse(resolvedFrame.writeActionsEnabled)
        scope.cancel()
    }

    @Test
    fun refreshCanonicalAccessWinsInEitherInitialLookupCompletionOrder() = runBlocking {
        for (homeCompletesFirst in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val homeCalls = Channel<Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
            val canonicalCalls = Channel<Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
            val channelRepository =
                FakeChannelRepository {
                    val release = CompletableDeferred<Unit>()
                    val finished = CompletableDeferred<Unit>()
                    homeCalls.send(release to finished)
                    try {
                        release.await()
                        DataResult.Content(unjoinedHomeSnapshot())
                    } finally {
                        finished.complete(Unit)
                    }
                }
            var canonicalCallCount = 0
            val repository =
                object : ConversationRepository by FakeConversationRepository() {
                    override suspend fun getThread(
                        threadId: ThreadId,
                        includeFollowedSummaries: Boolean,
                        limit: Int,
                    ): DataResult<ThreadSnapshot> {
                        val callIndex = ++canonicalCallCount
                        val release = CompletableDeferred<Unit>()
                        val finished = CompletableDeferred<Unit>()
                        canonicalCalls.send(release to finished)
                        return try {
                            release.await()
                            DataResult.Content(threadSnapshot(body = "canonical-$callIndex"))
                        } finally {
                            finished.complete(Unit)
                        }
                    }
                }
            val dataSource =
                testSharedThreadDataSource(
                    networkApi = FakeThreadApi(failChannelMessages = true),
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    conversationRepository = repository,
                    channelRepository = channelRepository
                )
            val store = SharedThreadStore(dataSource = dataSource, scope = scope)

            store.dispatch(SharedThreadIntent.Start(basePayload()))
            val (releaseFirstHome, firstHomeFinished) = withTimeout(1_000) { homeCalls.receive() }
            val (releaseFirstCanonical, firstCanonicalFinished) = withTimeout(1_000) { canonicalCalls.receive() }
            releaseFirstCanonical.complete(Unit)
            withTimeout(1_000) { firstCanonicalFinished.await() }
            withTimeout(1_000) {
                store.state.first { it.payload.messages.singleOrNull()?.body == "canonical-1" }
            }
            assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)

            store.dispatch(SharedThreadIntent.Refresh)
            withTimeout(500) { firstHomeFinished.await() }
            val (releaseSecondHome, secondHomeFinished) = withTimeout(1_000) { homeCalls.receive() }
            val (releaseSecondCanonical, secondCanonicalFinished) = withTimeout(1_000) { canonicalCalls.receive() }
            if (homeCompletesFirst) {
                releaseSecondHome.complete(Unit)
                withTimeout(1_000) { secondHomeFinished.await() }
                withTimeout(1_000) {
                    store.state.first { it.initialHomeAccessLookupComplete }
                }
                releaseSecondCanonical.complete(Unit)
                withTimeout(1_000) { secondCanonicalFinished.await() }
                withTimeout(1_000) {
                    store.state.first { it.payload.messages.singleOrNull()?.body == "canonical-2" }
                }
            } else {
                releaseSecondCanonical.complete(Unit)
                withTimeout(1_000) { secondCanonicalFinished.await() }
                withTimeout(1_000) {
                    store.state.first { it.payload.messages.singleOrNull()?.body == "canonical-2" }
                }
                releaseSecondHome.complete(Unit)
                withTimeout(1_000) { secondHomeFinished.await() }
                withTimeout(1_000) {
                    store.state.first { it.initialHomeAccessLookupComplete }
                }
            }
            releaseFirstHome.complete(Unit)

            val resolved =
                withTimeout(1_000) {
                    store.state.first {
                        it.initialHomeAccessLookupComplete &&
                            it.accessResolution == ThreadAccessResolution.Resolved &&
                            it.payload.messages.singleOrNull()?.body == "canonical-2" &&
                            it.payload.joined &&
                            it.payload.canSend &&
                            it.payload.composer.enabled &&
                            it.writeActionsEnabled
                    }
                }
            assertTrue(resolved.payload.joined)
            assertTrue(resolved.payload.canSend)
            assertTrue(resolved.payload.composer.enabled)
            assertTrue(resolved.writeActionsEnabled)
            scope.cancel()
        }
    }

    @Test
    fun warmCacheRefreshFailureDoesNotReenableStaleFooterAccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                releaseHome.await()
                DataResult.Content(unjoinedHomeSnapshot())
            }
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Error(ScreenError(code = "offline", retryable = true))
            ).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(contentPayload("cached body").copy(joined = true, canSend = true))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        repository.getThreadStarted?.await()
        repository.releaseGetThread?.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Error) {
                delay(10)
            }
        }
        releaseHome.complete(Unit)
        delay(50)

        val failedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Error, failedFrame.accessResolution)
        assertEquals("content", failedFrame.payload.status)
        assertFalse(
            failedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = failedFrame.timelineSlice,
                accessResolution = failedFrame.accessResolution
            )
        )
        scope.cancel()
    }

    @Test
    fun retryResetsTimedOutHomeVerdictWithoutOverridingCanonicalJoinedAccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstHomeNeverCompletes = CompletableDeferred<Unit>()
        val releaseRetryHome = CompletableDeferred<Unit>()
        var homeAttempt = 0
        val channelRepository =
            FakeChannelRepository {
                homeAttempt += 1
                if (homeAttempt == 1) {
                    firstHomeNeverCompletes.await()
                } else {
                    releaseRetryHome.await()
                }
                DataResult.Content(unjoinedHomeSnapshot())
            }
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Error(ScreenError(code = "offline", retryable = true))
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(contentPayload("cached body").copy(joined = true, canSend = true))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val renderEvents = mutableListOf<SharedThreadRenderSlice>()
        val renderJob = launch {
            store.renderSliceFlow().collect { slice -> renderEvents += slice }
        }

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        withTimeout(1_500) {
            while (
                store.state.value.accessResolution != ThreadAccessResolution.Error ||
                !store.state.value.initialHomeAccessLookupComplete
            ) {
                delay(10)
            }
        }

        repository.threadResult = DataResult.Content(threadSnapshot(conversation = conversationSummary().copy(joined = true)))
        renderEvents.clear()
        store.dispatch(SharedThreadIntent.Refresh)
        withTimeout(1_000) {
            while (homeAttempt < 2 || store.state.value.payload.messages.singleOrNull()?.body != "repository body") {
                delay(10)
            }
        }

        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        assertFalse(
            renderEvents.any { slice ->
                slice.accessResolution == ThreadAccessResolution.Resolved && slice.payload.joined
            }
        )

        releaseRetryHome.complete(Unit)
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }
        assertTrue(store.state.value.payload.joined)
        assertTrue(store.state.value.payload.canSend)
        assertTrue(store.state.value.payload.composer.enabled)
        assertTrue(store.state.value.writeActionsEnabled)
        renderJob.cancel()
        scope.cancel()
    }

    @Test
    fun resolvedAccessStaysReadyDuringOrdinaryBackgroundRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = FakeConversationRepository()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        withTimeout(1_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        repository.getThreadStarted = CompletableDeferred()
        repository.releaseGetThread = CompletableDeferred()
        store.dispatch(SharedThreadIntent.Refresh)
        repository.getThreadStarted?.await()

        val refreshingFrame = store.state.value
        assertTrue(refreshingFrame.payload.backgroundRefreshing)
        assertEquals(ThreadAccessResolution.Resolved, refreshingFrame.accessResolution)
        assertTrue(
            refreshingFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = refreshingFrame.timelineSlice,
                accessResolution = refreshingFrame.accessResolution
            )
        )

        repository.releaseGetThread?.complete(Unit)
        scope.cancel()
    }

    @Test
    fun initialHomeAccessTimeoutFallsBackToCanonicalThreadAccessOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val neverReleaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                neverReleaseHome.await()
                DataResult.Content(emptyHomeSnapshot())
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = FakeConversationRepository(),
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        withTimeout(2_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        assertTrue(store.state.value.canonicalAccessResolved)
        assertTrue(store.state.value.payload.joined)
        assertEquals(1, channelRepository.homeCalls)
        scope.cancel()
    }

    @Test
    fun contentSlicePreservesReplyThreadChannelSubtitleWhenNetworkPayloadOmitsIt() {
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val current = contentPayloadForRoute(route, "cached body").copy(subtitle = "#general")
        val networkPatch = contentPayloadForRoute(route, "network body").copy(subtitle = null)

        val payload = current.withContentSliceFrom(networkPatch)

        assertEquals("#general", payload.subtitle)
        assertEquals("network body", payload.messages.single().body)
    }

    @Test
    fun contentSliceDoesNotOwnNonReplyHeaderSubtitle() {
        val current = contentPayload("cached body").copy(subtitle = "General updates")
        val networkPatch = contentPayload("network body").copy(subtitle = null)

        val payload = current.withContentSliceFrom(networkPatch)

        assertEquals("General updates", payload.subtitle)
        assertEquals("network body", payload.messages.single().body)
    }

    @Test
    fun contentSliceDoesNotOwnRouteIdentityWhenNetworkPayloadChangesIt() {
        val current =
            contentPayload("cached body").copy(
                instanceId = "shared-shell-thread-channel-1",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                parentChannelId = null,
                parentMessageId = null,
                memberScopedMentions = true
            )
        val networkPatch =
            contentPayload("network body").copy(
                instanceId = "shared-shell-thread-channel-2",
                sourceThreadId = "channel-2",
                streamChannelId = "channel-2",
                parentChannelId = "parent-2",
                parentMessageId = "message-2",
                memberScopedMentions = false
            )

        val payload = current.withContentSliceFrom(networkPatch)

        assertEquals("shared-shell-thread-channel-1", payload.instanceId)
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals(true, payload.memberScopedMentions)
        assertEquals("network body", payload.messages.single().body)
    }

    @Test
    fun threadContentProjectionCanResolveReplyThreadIdentityOutsideContentSlice() {
        val current =
            unresolvedReplyPayload(parentMessageId = "m1")
                .copy(instanceId = "shared-shell-thread-pending-m1")
        val networkPatch =
            replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                .copy(
                    instanceId = "shared-shell-thread-thread-m1",
                    messages =
                        listOf(
                            KuiklyThreadMessageItem(
                                id = "reply-1",
                                authorId = "u1",
                                authorName = "Artin",
                                body = "network body"
                            )
                        )
                )

        val contentOnly = current.withContentSliceFrom(networkPatch)
        val projected = current.withThreadContentProjectionFrom(networkPatch)

        assertEquals("shared-shell-thread-pending-m1", contentOnly.instanceId)
        assertEquals(null, contentOnly.sourceThreadId)
        assertEquals(null, contentOnly.streamChannelId)
        assertEquals("channel-1", contentOnly.parentChannelId)
        assertEquals("m1", contentOnly.parentMessageId)
        assertEquals("shared-shell-thread-thread-m1", projected.instanceId)
        assertEquals("thread-m1", projected.sourceThreadId)
        assertEquals("thread-m1", projected.streamChannelId)
        assertEquals("channel-1", projected.parentChannelId)
        assertEquals("m1", projected.parentMessageId)
        assertEquals("network body", projected.messages.single().body)
    }

    @Test
    fun contentSliceDoesNotOwnDirectMessageHeaderMetadata() {
        val current =
            directMessagePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "cached-1",
                            authorId = "u2",
                            authorName = "Stdrc",
                            body = "cached body"
                        )
                    )
            )
        val networkPatch =
            contentPayload("network body").copy(
                title = "Direct Message",
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                sourceThreadId = "dm-1",
                streamChannelId = "dm-1",
                dmPeerName = null,
                dmPeerAvatarUrl = null,
                dmPeerAvatarLabel = null,
            )

        val payload = current.withContentSliceFrom(networkPatch)
        val headerDisplay =
            payload.threadHeaderDisplayModelFromDirectory(
                ConversationSummary(
                    threadId = ThreadId("dm-1"),
                    channelId = ChannelId("dm-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "artin"
                ).toConversationHeaderProjection()
            )

        assertEquals("artin", payload.title)
        assertEquals("artin", payload.dmPeerName)
        assertEquals("pixel:crown", payload.dmPeerAvatarUrl)
        assertEquals("AR", payload.dmPeerAvatarLabel)
        assertEquals("artin", headerDisplay.title)
        assertEquals(null, headerDisplay.dmPeerAvatarWireValue)
        assertEquals("AR", headerDisplay.dmPeerAvatarLabel)
        assertEquals("network body", payload.messages.single().body)
    }

    @Test
    fun memoryCachedDirectMessageSnapshotCanRemainSparseWhenSnapshotOmitsDisplayMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(directMessagePayload(), "cached body")
                .copy(
                    title = "Direct Message",
                    dmPeerName = null,
                    dmPeerAvatarUrl = null,
                    dmPeerAvatarLabel = null,
                )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(directMessagePayload()))

        val payload = store.state.value.payload
        val headerDisplay = payload.threadHeaderFallbackDisplayModel()
        assertEquals("content", payload.status)
        assertEquals("Direct Message", payload.title)
        assertEquals("Direct Message", headerDisplay.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals(null, payload.dmPeerAvatarLabel)
        assertEquals("cached body", payload.messages.single().body)
        scope.cancel()
    }

    @Test
    fun canonicalContentSliceOverridesAgentDirectMessageHeaderMetadata() {
        val current =
            directMessagePayload().copy(
                title = "Product-UX-Designer",
                sourceThreadId = "dm-agent-1",
                streamChannelId = "dm-agent-1",
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "cached-1",
                            authorId = "agent-1",
                            authorName = "Product UX",
                            authorKind = "agent",
                            body = "cached body"
                        )
                    ),
                dmPeerName = "Product-UX-Designer",
                dmPeerAvatarUrl = "pixel:tree",
                dmPeerAvatarLabel = "PU",
            )
        val networkPatch =
            contentPayload("network body").copy(
                title = "Product-UX-Designer",
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                sourceThreadId = "dm-agent-1",
                streamChannelId = "dm-agent-1",
                dmPeerName = "Product-UX-Designer",
                dmPeerAvatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                dmPeerAvatarLabel = "PU",
            )

        val payload = current.withContentSliceFrom(networkPatch)

        assertEquals("Product-UX-Designer", payload.title)
        assertEquals("Product-UX-Designer", payload.dmPeerName)
        assertEquals("https://www.gravatar.com/avatar/agent-hash?s=52&d=404", payload.dmPeerAvatarUrl)
        assertEquals("PU", payload.dmPeerAvatarLabel)
        assertEquals("network body", payload.messages.single().body)
    }

    @Test
    fun staleRefreshResultDoesNotOverwriteCurrentThreadRoute() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val channelStarted = CompletableDeferred<Unit>()
        val channelRelease = CompletableDeferred<Unit>()
        val replyStarted = CompletableDeferred<Unit>()
        val replyRelease = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi().apply {
                bodyTextByThreadId["channel-1"] =
                    messagesResponse("channel body", id = "channel-message", channelId = "channel-1")
                bodyTextByThreadId["thread-a"] =
                    messagesResponse("reply network", id = "reply-network", channelId = "thread-a")
                channelMessagesStartedByThreadId["channel-1"] = channelStarted
                releaseChannelMessagesByThreadId["channel-1"] = channelRelease
                channelMessagesStartedByThreadId["thread-a"] = replyStarted
                releaseChannelMessagesByThreadId["thread-a"] = replyRelease
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val replyRoute = replyPayload(parentMessageId = "parent-a", threadId = "thread-a")
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(
                base = replyRoute,
                body = "reply cached",
                id = "reply-cached",
                channelId = "thread-a"
            )
        )
        val logs = ConcurrentTestLogBuffer()
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        val channelJob = scope.launch { store.accept(SharedThreadIntent.Start(basePayload())) }
        channelStarted.await()
        val replyJob = scope.launch { store.accept(SharedThreadIntent.Start(replyRoute)) }
        replyStarted.await()

        withTimeout(500) {
            while (true) {
                val payload = store.state.value.payload
                if (payload.sourceThreadId == "thread-a" && payload.messages.singleOrNull()?.body == "reply cached") {
                    return@withTimeout
                }
                delay(10)
            }
        }
        assertEquals("thread-a", store.state.value.payload.sourceThreadId)
        assertEquals("reply cached", store.state.value.payload.messages.single().body)

        channelRelease.complete(Unit)
        withTimeout(500) {
            while (true) {
                if (logs.any { it.startsWith("thread_refresh_stale_drop") && it.contains("threadId=channel-1") }) {
                    return@withTimeout
                }
                delay(10)
            }
        }
        assertTrue(logs.any { it.startsWith("thread_refresh_stale_drop") && it.contains("threadId=channel-1") })
        assertEquals("thread-a", store.state.value.payload.sourceThreadId)
        assertEquals("reply cached", store.state.value.payload.messages.single().body)

        replyRelease.complete(Unit)
        withTimeout(500) {
            while (true) {
                if (store.state.value.payload.messages.singleOrNull()?.body == "reply network") {
                    return@withTimeout
                }
                delay(10)
            }
        }
        assertEquals("thread-a", store.state.value.payload.sourceThreadId)
        assertEquals("reply network", store.state.value.payload.messages.single().body)
        channelJob.join()
        replyJob.join()
        scope.cancel()
    }

    @Test
    fun contentSliceKeepsLoadedMembersWhenNetworkPayloadOmitsMembers() {
        val loadedMember = KuiklyThreadMemberItem(id = "u1", displayName = "Artin")
        val loadedCandidate = KuiklyThreadMemberItem(id = "agent-1", displayName = "Cindy", kind = "agent")
        val current =
            contentPayload("cached body").copy(
                membersCount = 2,
                members = listOf(loadedMember),
                memberCandidates = listOf(loadedCandidate),
                canAddMembers = false,
                addMemberStatus = "submitting",
                removeMemberStatus = "removing",
                removingMemberId = "u1"
            )
        val networkPatch = contentPayload("network body")

        val payload = current.withContentSliceFrom(networkPatch)

        assertEquals("network body", payload.messages.single().body)
        assertEquals(2, payload.membersCount)
        assertEquals(listOf(loadedMember), payload.members)
        assertEquals(listOf(loadedCandidate), payload.memberCandidates)
        assertFalse(payload.canAddMembers)
        assertEquals("submitting", payload.addMemberStatus)
        assertEquals("removing", payload.removeMemberStatus)
        assertEquals("u1", payload.removingMemberId)
    }

    @Test
    fun contentSliceDoesNotOwnMembersWhenNetworkPayloadIncludesMembers() {
        val loadedMember = KuiklyThreadMemberItem(id = "u1", displayName = "Artin")
        val loadedCandidate = KuiklyThreadMemberItem(id = "agent-1", displayName = "Cindy", kind = "agent")
        val networkMember = KuiklyThreadMemberItem(id = "u2", displayName = "Network User")
        val networkCandidate = KuiklyThreadMemberItem(id = "agent-2", displayName = "Network Agent", kind = "agent")
        val current =
            contentPayload("cached body").copy(
                membersCount = 2,
                members = listOf(loadedMember),
                memberCandidates = listOf(loadedCandidate),
                canAddMembers = false,
                addMemberStatus = "submitting",
                addMemberErrorCode = "member_exists",
                removeMemberStatus = "removing",
                removingMemberId = "u1",
                removeMemberErrorCode = "remove_failed"
            )
        val networkPatch =
            contentPayload("network body").copy(
                membersCount = 99,
                members = listOf(networkMember),
                memberCandidates = listOf(networkCandidate),
                canAddMembers = true,
                addMemberStatus = "idle",
                addMemberErrorCode = null,
                removeMemberStatus = "idle",
                removingMemberId = null,
                removeMemberErrorCode = null
            )

        val payload = current.withContentSliceFrom(networkPatch)

        assertEquals("network body", payload.messages.single().body)
        assertEquals(2, payload.membersCount)
        assertEquals(listOf(loadedMember), payload.members)
        assertEquals(listOf(loadedCandidate), payload.memberCandidates)
        assertFalse(payload.canAddMembers)
        assertEquals("submitting", payload.addMemberStatus)
        assertEquals("member_exists", payload.addMemberErrorCode)
        assertEquals("removing", payload.removeMemberStatus)
        assertEquals("u1", payload.removingMemberId)
        assertEquals("remove_failed", payload.removeMemberErrorCode)
    }

    @Test
    fun contentSliceDoesNotOwnActionChromeWhenNetworkPayloadChangesIt() {
        val current =
            contentPayload("cached body").copy(
                selectedTab = "tasks",
                showModeTabs = false,
                showReplyActions = false,
                settingsActions = listOf(KuiklyThreadSettingsAction(id = "current-action")),
                showMembersAction = false,
                showSettingsAction = false,
                showOpenInChannelAction = false,
                showMessageSaveAction = false,
                showTaskOpenActions = false,
                showTaskStatusActions = false,
                joinLabel = "JOIN CURRENT"
            )
        val networkPatch =
            contentPayload("network body").copy(
                selectedTab = "chat",
                showModeTabs = true,
                showReplyActions = true,
                settingsActions = listOf(KuiklyThreadSettingsAction(id = "network-action")),
                showMembersAction = true,
                showSettingsAction = true,
                showOpenInChannelAction = true,
                showMessageSaveAction = true,
                showTaskOpenActions = true,
                showTaskStatusActions = true,
                joinLabel = "JOIN NETWORK"
            )

        val payload = current.withContentSliceFrom(networkPatch)

        assertEquals("network body", payload.messages.single().body)
        assertEquals("tasks", payload.selectedTab)
        assertEquals(false, payload.showModeTabs)
        assertEquals(false, payload.showReplyActions)
        assertEquals(listOf("current-action"), payload.settingsActions.map { it.id })
        assertEquals(false, payload.showMembersAction)
        assertEquals(false, payload.showSettingsAction)
        assertEquals(false, payload.showOpenInChannelAction)
        assertEquals(false, payload.showMessageSaveAction)
        assertEquals(false, payload.showTaskOpenActions)
        assertEquals(false, payload.showTaskStatusActions)
        assertEquals("JOIN CURRENT", payload.joinLabel)
    }

    @Test
    fun contentSliceDoesNotOwnAccessOrComposerState() {
        val current =
            contentPayload("cached body").copy(
                showComposer = false,
                joined = false,
                canSend = false,
                composer =
                    KuiklyThreadComposerPayload(
                        enabled = false,
                        placeholder = "Current composer",
                        seedText = "draft"
                    )
            )
        val networkPatch =
            contentPayload("network body").copy(
                showComposer = true,
                joined = true,
                canSend = true,
                composer =
                    KuiklyThreadComposerPayload(
                        enabled = true,
                        placeholder = "Network composer",
                        seedText = ""
                    )
            )

        val payload = current.withContentSliceFrom(networkPatch)

        assertEquals("network body", payload.messages.single().body)
        assertEquals(false, payload.showComposer)
        assertEquals(false, payload.joined)
        assertEquals(false, payload.canSend)
        assertEquals(false, payload.composer.enabled)
        assertEquals("Current composer", payload.composer.placeholder)
        assertEquals("draft", payload.composer.seedText)
    }

    @Test
    fun threadContentProjectionAppliesTasksOutsideContentSlice() {
        val currentTask =
            KuiklyThreadTaskItem(
                id = "current-task",
                title = "Current task",
                status = "Todo",
                taskNumber = 1
            )
        val networkTask =
            KuiklyThreadTaskItem(
                id = "network-task",
                title = "Network task",
                status = "InReview",
                taskNumber = 2
            )
        val current = contentPayload("cached body").copy(tasks = listOf(currentTask))
        val networkPatch = contentPayload("network body").copy(tasks = listOf(networkTask))

        val contentOnly = current.withContentSliceFrom(networkPatch)
        val projected = current.withThreadContentProjectionFrom(networkPatch)

        assertEquals(listOf("current-task"), contentOnly.tasks.map { it.id })
        assertEquals(listOf("network-task"), projected.tasks.map { it.id })
        assertEquals("network body", projected.messages.single().body)
    }

    @Test
    fun startKeepsPayloadAccessDefaultsWithoutHomeSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                currentMember = member("u1", "Artin"),
                channels =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            channelType = "public",
                            kind = ConversationKind.Channel,
                            name = "all",
                            subtitle = "General updates",
                            joined = false,
                            memberCount = 4,
                            permissions = ConversationPermissions(canSend = false)
                        ).toHomeChannelRow()
                    ),
                directMessages = emptyList(),
                unreadTotal = 0
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload().copy(title = "channel-1")))

        val payload = store.state.value.payload
        assertEquals("loading", payload.status)
        assertEquals("", payload.title)
        assertEquals("Channel", threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals(null, payload.subtitle)
        assertEquals("channel", payload.channelKind)
        // fb845a19: with the durable home snapshot removed there is no local
        // access fallback to rewrite joined/canSend — the payload keeps its
        // route defaults.
        assertEquals(true, payload.joined)
        assertEquals(true, payload.canSend)
        scope.cancel()
    }

    @Test
    fun startAndInitialContentDoNotBlockOnHomeSnapshotFallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val homeRequested = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository { _ ->
                homeRequested.complete(Unit)
                releaseHome.await()
                DataResult.Content(emptyHomeSnapshot())
            }
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))

        withTimeout(1_000) {
            while (store.state.value.payload.messages.firstOrNull()?.body != "network body") {
                delay(1)
            }
        }
        withTimeout(1_000) {
            homeRequested.await()
        }
        releaseHome.complete(Unit)
        scope.cancel()
    }

    @Test
    fun startAndPagingUseConfiguredThreadPageSize() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))
        store.accept(SharedThreadIntent.LoadOlder)
        store.accept(SharedThreadIntent.LoadNewer)

        assertEquals(listOf(30, 30, 30), api.channelMessageLimits)
        assertEquals(emptyList(), api.syncLimits)
        scope.cancel()
    }

    @Test
    fun messageReactionIntentPatchesOnlyPayloadSliceAndNoopsWhenEquivalent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        val before = store.state.value
        store.accept(SharedThreadIntent.ApplyMessageReactionSelection("m1", "🔥", selected = true))

        val patched = store.state.value
        assertTrue(before !== patched)
        assertEquals(before.highlightedMentions, patched.highlightedMentions)
        assertEquals(listOf(KuiklyThreadReactionItem("🔥", count = 1, selected = true)), patched.payload.messages.single().reactions)

        store.accept(SharedThreadIntent.ApplyMessageReactionSelection("m1", "🔥", selected = true))
        assertTrue(patched === store.state.value)
        scope.cancel()
    }

    @Test
    fun reactionToggleTargetPrefersSelectedDuplicateForRemoval() {
        val selectedReactions = listOf(KuiklyThreadReactionItem("🔥", count = 1, selected = true))
        val staleReactions = listOf(KuiklyThreadReactionItem("🔥", count = 1, selected = false))
        val baseMessage = contentPayload("reactable body").messages.single()
        val payload =
            contentPayload("reactable body").copy(
                messages = listOf(baseMessage.copy(reactions = staleReactions)),
                originMessage = baseMessage.copy(reactions = selectedReactions)
            )

        val toggleTarget = payload.threadReactionToggleTarget("m1", "🔥")

        assertEquals(false, toggleTarget?.nextSelected)
        assertEquals(selectedReactions, toggleTarget?.message?.reactions)
    }

    @Test
    fun stableTimelinePreservesSelectedReactionFromCurrentPayload() {
        val baseMessage = contentPayload("reactable body").messages.single()
        val current =
            contentPayload("reactable body").copy(
                messages =
                    listOf(
                        baseMessage.copy(
                            reactions = listOf(KuiklyThreadReactionItem("🎉", count = 1, selected = true))
                        )
                    )
            )
        val staleRefresh =
            contentPayload("reactable body").copy(
                messages = listOf(baseMessage.copy(reactions = emptyList()))
            )

        val preserved = staleRefresh.preserveStableTimelineFrom(current)

        assertEquals(
            listOf(KuiklyThreadReactionItem("🎉", count = 1, selected = true)),
            preserved.messages.single().reactions
        )
    }

    @Test
    fun stableTimelineCanDisableSelectedReactionPreservationForRealtimeRemoval() {
        val baseMessage = contentPayload("reactable body").messages.single()
        val current =
            contentPayload("reactable body").copy(
                messages =
                    listOf(
                        baseMessage.copy(
                            reactions = listOf(KuiklyThreadReactionItem("🎉", count = 1, selected = true))
                        )
                    )
            )
        val realtimeRemoval =
            contentPayload("reactable body").copy(
                messages = listOf(baseMessage.copy(reactions = emptyList()))
            )

        val removed =
            realtimeRemoval.preserveStableTimelineFrom(
                previous = current,
                preserveSelectedReactions = false
            )

        assertEquals(emptyList(), removed.messages.single().reactions)
    }

    @Test
    fun stableTimelineKeepsTransientLocalSendingReplyWhenCacheProjectionIsEmpty() {
        val parentMessageId = "parent-1"
        val threadChannelId = "thread-1"
        val pendingReply =
            KuiklyThreadMessageItem(
                id = "local-send-thread-1-1",
                authorId = "user-1",
                authorName = "Artin",
                body = "pending reply",
                deliveryState = ThreadMessageDeliverySending,
                pendingSendContext =
                    KuiklyThreadPendingSendContext(
                        channelId = "channel-1",
                        body = "pending reply",
                        asTask = false,
                        attachmentIds = emptyList(),
                        streamChannelId = threadChannelId,
                        parentMessageId = parentMessageId
                    )
            )
        val current =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-pending-$parentMessageId",
                sourceThreadId = threadChannelId,
                streamChannelId = threadChannelId,
                parentChannelId = "channel-1",
                parentMessageId = parentMessageId,
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                status = "content",
                messages = listOf(pendingReply),
                originMessage =
                    KuiklyThreadMessageItem(
                        id = parentMessageId,
                        authorId = "user-1",
                        authorName = "Artin",
                        body = "origin"
                    ),
                originParentMessageId = parentMessageId,
                originStatus = "loaded"
            )
        val emptyCacheProjection =
            current.copy(
                status = "empty",
                messages = emptyList()
            )

        val preserved = emptyCacheProjection.preserveStableTimelineFrom(current)

        assertEquals("content", preserved.status)
        assertEquals(listOf(pendingReply), preserved.messages)
    }

    @Test
    fun stableTimelineKeepsPendingSendAcrossNonEmptyStaleRefreshUntilExactEcho() {
        val pending = localPendingMessage("local-send-channel-1-1", "pending", randomId = "rid-1")
        val current = contentPayload("old").copy(messages = contentPayload("old").messages + pending)
        val staleRefresh = contentPayload("old")

        val preserved =
            staleRefresh.preserveStableTimelineFrom(
                previous = current,
                sendEchoMatchPolicy = ThreadSendEchoMatchPolicy.ExactRandomId
            )

        assertEquals(listOf("m1", "local-send-channel-1-1"), preserved.messages.map { it.id })

        val canonicalEcho =
            contentPayload("pending", id = "server-1").messages.single().copy(
                isOwn = true,
                randomId = "rid-1"
            )
        val echoed =
            staleRefresh
                .copy(messages = staleRefresh.messages + canonicalEcho)
                .preserveStableTimelineFrom(current)

        assertEquals(listOf("m1", "server-1"), echoed.messages.map { it.id })
        assertFalse(echoed.messages.any { it.id == "local-send-channel-1-1" })
    }

    @Test
    fun stableTimelineDoesNotAbsorbPendingSendForWrongRandomId() {
        val pending = localPendingMessage("local-send-channel-1-1", "same body", randomId = "rid-1")
        val current = contentPayload("old").copy(messages = contentPayload("old").messages + pending)
        val wrongCanonical =
            contentPayload("same body", id = "server-other").messages.single().copy(
                isOwn = true,
                randomId = "rid-other"
            )

        val preserved =
            contentPayload("old")
                .copy(messages = contentPayload("old").messages + wrongCanonical)
                .preserveStableTimelineFrom(current)

        assertEquals(
            setOf("m1", "local-send-channel-1-1", "server-other"),
            preserved.messages.map { it.id }.toSet()
        )
    }

    @Test
    fun stableTimelineDoesNotAbsorbPendingSendFromSameBodyCanonicalWithoutRandomId() {
        val pending = localPendingMessage("local-send-channel-1-1", "same body", randomId = "rid-1")
        val current = contentPayload("old").copy(messages = contentPayload("old").messages + pending)
        val missingRandomIdCanonical =
            contentPayload("same body", id = "server-missing-random").messages.single().copy(isOwn = true)
        val staleRefresh =
            contentPayload("old").copy(
                messages = contentPayload("old").messages + missingRandomIdCanonical
            )

        val preserved =
            staleRefresh.preserveStableTimelineFrom(
                previous = current,
                sendEchoMatchPolicy = ThreadSendEchoMatchPolicy.ExactRandomId
            )

        assertEquals(
            setOf("m1", "local-send-channel-1-1", "server-missing-random"),
            preserved.messages.map { it.id }.toSet()
        )

        val exactEcho =
            contentPayload("same body", id = "server-exact").messages.single().copy(
                isOwn = true,
                randomId = "rid-1"
            )
        val converged =
            contentPayload("old")
                .copy(messages = contentPayload("old").messages + exactEcho)
                .preserveStableTimelineFrom(
                    previous = preserved,
                    sendEchoMatchPolicy = ThreadSendEchoMatchPolicy.ExactRandomId
                )

        assertEquals(listOf("m1", "server-exact"), converged.messages.map { it.id })
        assertFalse(converged.messages.any { it.id == "local-send-channel-1-1" })
    }

    @Test
    fun refreshFenceKeepsCanonicalOwnSendAcknowledgedAfterRefreshStarted() {
        val acknowledged =
            contentPayload("sent", id = "server-1").messages.single().copy(
                isOwn = true,
                randomId = "rid-1"
            )
        val current = contentPayload("old").copy(messages = contentPayload("old").messages + acknowledged)
        val staleRefresh = contentPayload("old")

        val preserved =
            staleRefresh.preserveStableTimelineFrom(
                previous = current,
                refreshObservedMessageIds = setOf("m1")
            )

        assertEquals(listOf("m1", "server-1"), preserved.messages.map { it.id })
    }

    @Test
    fun refreshFenceDropsStalePendingShadowWhenCurrentStateAlreadyHasExactCanonicalAck() {
        val stalePending = localPendingMessage("local-send-channel-1-1", "sent", randomId = "rid-1")
        val acknowledged =
            contentPayload("sent", id = "server-1").messages.single().copy(
                isOwn = true,
                randomId = "rid-1"
            )
        val staleRefreshProjection =
            contentPayload("old").copy(messages = contentPayload("old").messages + stalePending)
        val current = contentPayload("old").copy(messages = contentPayload("old").messages + acknowledged)

        val preserved =
            staleRefreshProjection.preserveStableTimelineFrom(
                previous = current,
                refreshObservedMessageIds = setOf("m1")
            )

        assertEquals(listOf("m1", "server-1"), preserved.messages.map { it.id })
        assertFalse(preserved.messages.any { it.id == "local-send-channel-1-1" })
    }

    @Test
    fun appendSentPagePatchesTimelineAndPreservesStoreHeaderIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload().copy(title = "Readable channel")))
        store.accept(
            SharedThreadIntent.AppendSentPage(
                ThreadMessagesPage(
                    messages =
                        listOf(
                            WireMessage(
                                id = "sent-1",
                                channelId = "channel-1",
                                senderType = "user",
                                senderId = "u1",
                                senderName = "Artin",
                                messageType = "chat",
                                content = "sent body"
                            )
                        ),
                    canLoadMoreBefore = null,
                    canLoadMoreAfter = null
                )
            )
        )

        val payload = store.state.value.payload
        assertEquals("general", payload.title)
        assertEquals(listOf("cached body", "sent body"), payload.messages.map { it.body })
        assertNull(localStore.readThread(ThreadId("channel-1")))
        assertEquals("sent body", dataSource.cachedPayload("channel-1")?.messages?.last()?.body)
        scope.cancel()
    }

    @Test
    fun localSendSuccessReplacesTempBubbleWithCanonicalMessage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage("local-1", "sent body"))
        store.replaceLocalSendForCurrentOwner(dataSource, "local-1", sentPage("server-1", "sent body"))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        assertEquals(ThreadMessageDeliverySent, messages.first { it.id == "server-1" }.deliveryState)
        scope.cancel()
    }

    @Test
    fun concurrentSendCompletionPreservesSiblingSendAndInterleavedAgentReply() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = ParkableReadSessionStore(sampleSession())
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage("local-1", "first"))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage("local-2", "second"))

        sessionStore.parkNextRead()
        val firstCompletion =
            async {
                store.replaceLocalSendForCurrentOwner(dataSource, "local-1", sentPage("server-1", "first"))
            }
        sessionStore.nextReadStarted.await()

        store.replaceLocalSendForCurrentOwner(dataSource, "local-2", sentPage("server-2", "second"))
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("agent-1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("agent-1", "Agent"),
                        timestampLabel = "2026-07-19T00:47:00Z",
                        body = "agent reply",
                        seq = 12,
                    )
                )
            )
        )

        sessionStore.releaseNextRead.complete(Unit)
        firstCompletion.await()

        val visibleIds = store.state.value.payload.messages.map { it.id }.toSet()
        assertEquals(setOf("m1", "server-1", "server-2", "agent-1"), visibleIds)
        assertFalse(store.state.value.payload.messages.any { it.id.startsWith("local-") })
        val cachedIds = dataSource.cachedPayload("channel-1")?.messages.orEmpty().map { it.id }.toSet()
        assertEquals(setOf("m1", "server-1", "server-2", "agent-1"), cachedIds)
        scope.cancel()
    }

    @Test
    fun staleRefreshCannotDeletePendingOrAcknowledgedSendsStartedAfterItsObservationFence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        api.channelMessagesStarted = CompletableDeferred()
        api.releaseChannelMessages = CompletableDeferred()
        val refresh = async { store.accept(SharedThreadIntent.Refresh) }
        api.channelMessagesStarted?.await()

        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-send-a", "first", randomId = "rid-a")
        )
        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-send-b", "second", randomId = "rid-b")
        )
        store.replaceLocalSendForCurrentOwner(
            dataSource,
            "local-send-a",
            sentPage("server-a", "first", randomId = "rid-a")
        )

        api.releaseChannelMessages?.complete(Unit)
        refresh.await()

        assertEquals(
            setOf("m1", "server-a", "local-send-b"),
            store.state.value.payload.messages.map { it.id }.toSet()
        )
        assertFalse(store.state.value.payload.messages.any { it.id == "local-send-a" })

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("server-b"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u1", "Artin"),
                        timestampLabel = "2026-05-19T16:02:00Z",
                        body = "second",
                        seq = 12,
                        randomId = "rid-b"
                    )
                )
            )
        )

        assertEquals(
            setOf("m1", "server-a", "server-b"),
            store.state.value.payload.messages.map { it.id }.toSet()
        )
        assertFalse(store.state.value.payload.messages.any { it.id.startsWith("local-send-") })
        scope.cancel()
    }

    @Test
    fun realtimeOwnMessageEchoReplacesTransientLocalSendingBubble() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult =
                            DataResult.Content(
                                threadSnapshot(
                                    body = "cached body",
                                    seq = 10,
                                    conversation = conversationSummary().copy(joined = true)
                                )
                            )
                    )
            )
        val cachedPayload =
            contentPayload("cached body").let { content ->
                content.copy(messages = content.messages.map { message -> message.copy(seq = 10L) })
            }
        dataSource.cachePayloadForTest(cachedPayload)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-send-channel-1-1", "sent body")
        )
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("server-1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u1", "Artin"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "sent body",
                        seq = 11
                    )
                )
            )
        )

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-send-channel-1-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        assertEquals(listOf("cached body", "sent body"), messages.map { it.body })
        // task #562: the cache snapshot follows the live store — canonical
        // echo present, transient local-send bubble already replaced.
        val cachedMessages = dataSource.cachedPayload("channel-1")?.messages.orEmpty()
        assertFalse(cachedMessages.any { it.id == "local-send-channel-1-1" })
        assertEquals(1, cachedMessages.count { it.id == "server-1" })
        scope.cancel()
    }

    @Test
    fun appendSentPageDropsMatchingTransientLocalSendingBubble() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-send-channel-1-1", "sent body")
        )
        store.accept(SharedThreadIntent.AppendSentPage(sentPage("server-1", "sent body")))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-send-channel-1-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        scope.cancel()
    }

    @Test
    fun localSendSuccessCachesCanonicalMessageWithoutTempSendingBubble() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage("local-1", "sent body"))
        store.replaceLocalSendForCurrentOwner(dataSource, "local-1", sentPage("server-1", "sent body"))

        assertNull(localStore.readThread(ThreadId("channel-1")))
        val cachedMessages = dataSource.cachedPayload("channel-1")?.messages.orEmpty()
        assertFalse(cachedMessages.any { it.id == "local-1" })
        assertFalse(cachedMessages.any { it.deliveryState == ThreadMessageDeliverySending })
        assertEquals(ThreadMessageDeliverySent, cachedMessages.first { it.id == "server-1" }.deliveryState)
        scope.cancel()
    }

    @Test
    fun retiredOwnerLocalSendSuccessAndFailureCannotMutateCurrentOwnerStateOrCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        val ownerBSession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-b", email = "b@example.test", displayName = "Owner B"),
                accessToken = "access-b",
                refreshToken = "refresh-b"
            )
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                replyCacheIdentityAuthority = { identity },
                payloadStore = payloadStore
            )
        val ownerA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val route = contentPayload("owner-b body")
        val routeIdentity = route.resolveThreadRouteIdentity()
        identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "u-b",
                serverId = "server-1",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        val ownerB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        assertTrue(
            dataSource.cachePayload(
                payload = route,
                serverId = "server-1",
                ownerScopeId = ownerB.threadPayloadOwnerScopeId.orEmpty(),
                expectedProvenance = ownerB
            )
        )
        val pending = localPendingMessage("local-shared", "owner-b pending")
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                initialPayload = route.copy(messages = route.messages + pending)
            )

        store.accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = pending.id,
                page = sentPage("owner-a-server", "owner-a completion"),
                expectedProvenance = ownerA,
                expectedRoute = routeIdentity
            )
        )
        store.accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = pending.id,
                errorCode = "owner_a_failure",
                expectedProvenance = ownerA,
                expectedRoute = routeIdentity
            )
        )

        val visible = store.state.value.payload
        assertFalse(visible.messages.any { it.id == "owner-a-server" })
        assertEquals(ThreadMessageDeliverySending, visible.messages.first { it.id == pending.id }.deliveryState)
        assertNull(
            payloadStore.get(
                "server-1",
                "channel-1",
                environmentId = ownerA.environmentId,
                principalId = ownerA.principalId,
                ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty()
            )
        )
        val ownerBCache =
            payloadStore.get(
                "server-1",
                "channel-1",
                environmentId = ownerB.environmentId,
                principalId = ownerB.principalId,
                ownerScopeId = ownerB.threadPayloadOwnerScopeId.orEmpty()
            )
        assertEquals(listOf("owner-b body"), ownerBCache?.messages?.map { it.body })
        scope.cancel()
    }

    @Test
    fun retiredOwnerNullProvenanceGapRejectsLocalSendBeforeApiOrStateMutation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val api = FakeThreadApi()
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        val ownerBSession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-b", email = "b@example.test", displayName = "Owner B"),
                accessToken = "access-b",
                refreshToken = "refresh-b"
            )
        val ownerAIdentity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L
            )
        val ownerBIdentity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "u-b",
                serverId = "server-1",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        var identity: ThreadReplyCacheIdentitySnapshot? = ownerAIdentity
        val transitionWaiterEntered = CompletableDeferred<Unit>()
        val releaseTransitionWaiter = CompletableDeferred<Unit>()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = FakeConversationRepository(),
                replyCacheIdentityAuthority = { identity },
                replyCacheIdentityTransitionWaiter = {
                    transitionWaiterEntered.complete(Unit)
                    releaseTransitionWaiter.await()
                    true
                },
                payloadStore = payloadStore
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        withTimeout(1_000) {
            while (!store.state.value.writeActionsEnabled) {
                delay(10)
            }
        }
        val ownerA = requireNotNull(dataSource.currentReplyCacheProvenance())
        val ownerAAuthority =
            requireNotNull(
                dataSource.captureThreadLocalSendAuthority(
                    state = store.state.value,
                    mountedOwnerProvenance = ownerA
                )
            )
        val beforeGapState = store.state.value
        val beforeGapOwnerACache =
            payloadStore.get(
                serverId = "server-1",
                threadId = "channel-1",
                environmentId = ownerA.environmentId,
                principalId = ownerA.principalId,
                ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty()
            )

        identity = null
        val ownerTransition = async(Dispatchers.Default) { dataSource.captureReplyCacheProducerContext() }
        transitionWaiterEntered.await()

        assertTrue(store.state.value.writeActionsEnabled)
        assertNull(dataSource.currentReplyCacheProvenance())
        assertNull(
            dataSource.captureThreadLocalSendAuthority(
                state = store.state.value,
                mountedOwnerProvenance = ownerA
            )
        )
        val admittedDuringGap =
            store.admitLocalSend(
                message = localPendingMessage("local-gap", "must not send"),
                expectedProvenance = ownerAAuthority.provenance,
                expectedRoute = ownerAAuthority.route
            )
        if (admittedDuringGap) {
            dataSource.sendMessage(
                channelId = "channel-1",
                body = "must not send",
                asTask = false,
                attachmentIds = emptyList(),
                randomId = "rid-gap"
            )
        }

        assertFalse(admittedDuringGap)
        assertEquals(0, api.sendMessageCalls)
        assertEquals(beforeGapState, store.state.value)

        identity = ownerBIdentity
        releaseTransitionWaiter.complete(Unit)
        val ownerB = requireNotNull(ownerTransition.await()).provenance
        assertEquals("u-b", ownerB.principalId)
        assertNull(
            dataSource.captureThreadLocalSendAuthority(
                state = store.state.value,
                mountedOwnerProvenance = ownerA
            )
        )
        assertFalse(
            store.admitLocalSend(
                message = localPendingMessage("local-gap", "must not send"),
                expectedProvenance = ownerAAuthority.provenance,
                expectedRoute = ownerAAuthority.route
            )
        )
        store.accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = "local-gap",
                page = sentPage("server-gap", "must not send"),
                expectedProvenance = ownerAAuthority.provenance,
                expectedRoute = ownerAAuthority.route
            )
        )
        store.accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = "local-gap",
                errorCode = "owner_a_failure",
                expectedProvenance = ownerAAuthority.provenance,
                expectedRoute = ownerAAuthority.route
            )
        )
        assertEquals(beforeGapState, store.state.value)
        assertEquals(
            beforeGapOwnerACache,
            payloadStore.get(
                serverId = "server-1",
                threadId = "channel-1",
                environmentId = ownerA.environmentId,
                principalId = ownerA.principalId,
                ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty()
            )
        )
        assertNull(
            payloadStore.get(
                serverId = "server-1",
                threadId = "channel-1",
                environmentId = ownerB.environmentId,
                principalId = ownerB.principalId,
                ownerScopeId = ownerB.threadPayloadOwnerScopeId.orEmpty()
            )
        )
        assertFalse(store.state.value.payload.messages.any { it.id == "local-gap" || it.id == "server-gap" })
        scope.cancel()
    }

    @Test
    fun replyThreadLocalSendSuccessKeepsCanonicalSentMessageWhenEchoUsesParentChannel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult =
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "thread-m1",
                                    messages = emptyList(),
                                    parentMessage =
                                        message(
                                            threadId = "channel-1",
                                            id = "m1",
                                            seq = 10,
                                            body = "parent body"
                                        ),
                                    conversation = replyThreadConversation(joined = true, canSend = true)
                                )
                            )
                    ),
                channelRepository = FakeChannelRepository { DataResult.Content(joinedHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val base =
            replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                .copy(
                    status = "empty",
                    parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
                    parentChannelKind = "channel",
                    originStatus = "loaded",
                    originMessage =
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u2",
                            authorName = "Cindy",
                            body = "parent body",
                            threadChannelId = "channel-1"
                        )
                )

        store.accept(SharedThreadIntent.Start(base))
        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-1", "sent body", streamChannelId = "thread-m1")
        )
        store.replaceLocalSendForCurrentOwner(
            dataSource,
            "local-1",
            sentPage("server-1", "sent body", channelId = "channel-1", threadId = null)
        )

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals(listOf("server-1"), payload.messages.map { it.id })
        assertFalse(payload.messages.any { it.id == "local-1" })
        assertEquals(ThreadMessageDeliverySent, payload.messages.single().deliveryState)
        assertNull(localStore.readThread(ThreadId("thread-m1")))
        assertEquals("sent body", dataSource.cachedPayload("thread-m1")?.messages?.single()?.body)
        scope.cancel()
    }

    @Test
    fun cachePayloadDropsTransientLocalSendingBubble() = runBlocking {
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val payload =
            contentPayload("sent body", id = "server-1").copy(
                messages =
                    listOf(
                        localPendingMessage("local-send-channel-1-1", "sent body"),
                        contentPayload("sent body", id = "server-1").messages.single()
                    )
            )

        dataSource.cachePayloadForTest(payload)
        val cachedPayload = dataSource.cachedPayload("channel-1")

        assertNotNull(cachedPayload)
        assertFalse(cachedPayload.messages.any { it.id == "local-send-channel-1-1" })
        assertFalse(cachedPayload.messages.any { it.deliveryState == ThreadMessageDeliverySending })
        assertEquals(listOf("server-1"), cachedPayload.messages.map { it.id })
    }

    @Test
    fun cachedPayloadDoesNotPersistHeaderFallbackTitleAsConversationName() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val route =
            basePayload().copy(
                title = threadHeaderFallbackTitle(KuiklyThreadSurfaceKind.Channel),
                sourceThreadId = "joint-1",
                streamChannelId = "joint-1",
                channelKind = "joint"
            )
        val payload = contentPayloadForRoute(route, "body", channelId = "joint-1")

        dataSource.cachePayloadForTest(payload)

        val cached = localStore.readThread(ThreadId("joint-1"))
        assertNull(cached)
        assertEquals(listOf("body"), dataSource.cachedPayload("joint-1")?.messages?.map { it.body })
    }

    @Test
    fun canonicalPayloadCacheKeepsThreadTimelineInMemoryWithoutPersistentRewrite() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(threadSnapshot(body = "stale db body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val freshPayload = contentPayload("fresh memory body")

        val selected =
            dataSource.cacheCanonicalPayload(freshPayload)

        assertEquals(listOf("fresh memory body"), selected.messages.map { it.body })
        assertEquals(listOf("fresh memory body"), dataSource.cachedPayload("channel-1")?.messages?.map { it.body })
        assertEquals(listOf("stale db body"), localStore.readThread(ThreadId("channel-1"))?.messages?.map { it.body })
    }

    @Test
    fun shorterInitialWindowDoesNotShrinkExpandedVisibleTimeline() {
        val expandedMessages =
            (1..72).map { index ->
                KuiklyThreadMessageItem(
                    id = "m$index",
                    authorId = "u1",
                    authorName = "Artin",
                    body = "body $index",
                    seq = index.toLong()
                )
            }
        val previous =
            basePayload().copy(
                status = "content",
                messages = expandedMessages,
                canLoadMoreBefore = true
            )
        val latestWindow =
            previous.copy(
                messages =
                    expandedMessages
                        .takeLast(50)
                        .map { message ->
                            if (message.id == "m72") {
                                message.copy(body = "updated tail")
                            } else {
                                message.copy()
                            }
                        },
                canLoadMoreBefore = true
            )

        val stable = latestWindow.preserveStableTimelineFrom(previous)

        assertEquals(72, stable.messages.size)
        assertEquals("m1", stable.messages.first().id)
        assertEquals("updated tail", stable.messages.last().body)
    }

    @Test
    fun cachedReplyPayloadDoesNotPersistParentDisplaySubtitle() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val payload =
            replyPayload(parentMessageId = "parent-1", threadId = "thread-parent-1")
                .copy(
                    status = "content",
                    subtitle = "#general",
                    originMessage =
                        KuiklyThreadMessageItem(
                            id = "parent-1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "Parent message",
                            threadChannelId = "thread-parent-1"
                        ),
                    messages =
                        listOf(
                            KuiklyThreadMessageItem(
                                id = "reply-1",
                                authorId = "u2",
                                authorName = "Cindy",
                                body = "Reply body"
                            )
                        )
                )

        dataSource.cachePayloadForTest(payload)

        assertNull(localStore.readThread(ThreadId("thread-parent-1")))
        val cached = dataSource.cachedPayload("thread-parent-1")
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, cached?.surfaceKind)
        assertEquals("#general", cached?.subtitle)
    }

    @Test
    fun localSendFailurePersistsFailedBubbleWithRetryContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage(
                id = localMessageId,
                body = "retry body",
                asTask = true,
                attachmentIds = listOf("att-1")
            )
        )
        store.failLocalSendForCurrentOwner(dataSource, localMessageId, "network_error")

        assertNull(localStore.readThread(ThreadId("channel-1")))
        val cachedPayload = dataSource.cachedPayload("channel-1")
        assertNotNull(cachedPayload)
        val cachedFailed = cachedPayload.messages.first { it.id == localMessageId }
        assertEquals(ThreadMessageDeliveryFailed, cachedFailed.deliveryState)
        assertEquals("network_error", cachedFailed.pendingSendErrorCode)
        assertEquals(true, cachedFailed.pendingSendContext?.asTask)
        assertEquals(listOf("att-1"), cachedFailed.pendingSendContext?.attachmentIds)
        scope.cancel()
    }

    @Test
    fun localSendFailurePersistsReadableErrorMessage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage(localMessageId, "retry body"))

        store.failLocalSendForCurrentOwner(
            dataSource = dataSource,
            messageId = localMessageId,
            errorCode = "body_too_large",
            errorMessage = "Body is too large"
        )

        val failed = store.state.value.payload.messages.first { it.id == localMessageId }
        assertEquals("body_too_large", failed.pendingSendErrorCode)
        assertEquals("Body is too large", failed.pendingSendErrorMessage)
        val cachedPayload = dataSource.cachedPayload("channel-1")
        assertNotNull(cachedPayload)
        val cachedFailed = cachedPayload.messages.first { it.id == localMessageId }
        assertEquals("body_too_large", cachedFailed.pendingSendErrorCode)
        assertEquals("Body is too large", cachedFailed.pendingSendErrorMessage)
        scope.cancel()
    }

    @Test
    fun dataSourceCachesLocalSendFailureAfterPageScopeEnds() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val basePayload =
            basePayload().copy(
                status = "content",
                messages = listOf(KuiklyThreadMessageItem(id = "m1", authorId = "u1", authorName = "Artin", body = "existing"))
            )
        val localMessage = localPendingMessage("local-send-channel-1-1", "retry body")

        dataSource.cacheLocalSendFailure(
            basePayload = basePayload,
            localMessage = localMessage,
            errorCode = "thread_send_network",
            errorMessage = "offline"
        )

        val cached = dataSource.cachedPayload("channel-1")
        assertNotNull(cached)
        val failed = cached.messages.first { it.id == localMessage.id }
        assertEquals(ThreadMessageDeliveryFailed, failed.deliveryState)
        assertEquals("thread_send_network", failed.pendingSendErrorCode)
        assertEquals("offline", failed.pendingSendErrorMessage)
    }

    @Test
    fun dataSourceCachesLocalSendSuccessAfterPageScopeEnds() = runBlocking {
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val basePayload =
            basePayload().copy(
                status = "content",
                messages = listOf(KuiklyThreadMessageItem(id = "m1", authorId = "u1", authorName = "Artin", body = "existing"))
            )
        val localMessage = localPendingMessage("local-send-channel-1-1", "sent body")

        dataSource.cacheLocalSendSuccess(
            basePayload = basePayload,
            localMessage = localMessage,
            sentPage = sentPage("server-1", "sent body")
        )

        val cached = dataSource.cachedPayload("channel-1")
        assertNotNull(cached)
        assertFalse(cached.messages.any { it.id == localMessage.id })
        assertEquals(1, cached.messages.count { it.id == "server-1" })
    }

    @Test
    fun dismissLocalFailedSendRemovesFailedBubbleFromStateAndLocalCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage(localMessageId, "delete me"))
        store.failLocalSendForCurrentOwner(dataSource, localMessageId, "network_error")

        store.accept(SharedThreadIntent.DismissLocalFailedSend(localMessageId))

        assertEquals(null, store.state.value.payload.messages.firstOrNull { it.id == localMessageId })
        assertNull(localStore.readThread(ThreadId("channel-1")))
        val cachedPayload = dataSource.cachedPayload("channel-1")
        assertEquals(null, cachedPayload?.messages?.firstOrNull { it.id == localMessageId })
        scope.cancel()
    }

    @Test
    fun cancelLocalSendRemovesSendingBubbleFromStateAndLocalCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage(localMessageId, "cancel me"))

        store.accept(SharedThreadIntent.CancelLocalSend(localMessageId))

        assertEquals(null, store.state.value.payload.messages.firstOrNull { it.id == localMessageId })
        assertNull(localStore.readThread(ThreadId("channel-1")))
        val cachedPayload = dataSource.cachedPayload("channel-1")
        assertEquals(null, cachedPayload?.messages?.firstOrNull { it.id == localMessageId })
        scope.cancel()
    }

    @Test
    fun refreshRetainsLocalFailedBubbleMissingFromServerPage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage(localMessageId, "retry body"))
        store.failLocalSendForCurrentOwner(dataSource, localMessageId, "network_error")

        store.accept(SharedThreadIntent.Refresh)

        val messages = store.state.value.payload.messages
        assertEquals(ThreadMessageDeliveryFailed, messages.first { it.id == localMessageId }.deliveryState)
        assertEquals("retry body", messages.first { it.id == localMessageId }.body)
        assertEquals("network body", messages.first { it.id == "m1" }.body)
        assertEquals(1, messages.count { it.id == localMessageId })
        scope.cancel()
    }

    @Test
    fun localSendFailureKeepsFailedBubbleWithRetryContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage(
                id = "local-1",
                body = "retry body",
                asTask = true,
                attachmentIds = listOf("att-1")
            )
        )

        store.failLocalSendForCurrentOwner(dataSource, "local-1", "network_error")

        val failed = store.state.value.payload.messages.first { it.id == "local-1" }
        assertEquals(ThreadMessageDeliveryFailed, failed.deliveryState)
        assertEquals("retry body", failed.body)
        assertEquals("network_error", failed.pendingSendErrorCode)
        assertEquals(true, failed.pendingSendContext?.asTask)
        assertEquals(listOf("att-1"), failed.pendingSendContext?.attachmentIds)
        scope.cancel()
    }

    @Test
    fun retryFailureReusesSameTempBubbleAndRetrySuccessReplacesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val local = localPendingMessage("local-1", "retry body")
        store.startLocalSendForCurrentOwner(dataSource, local)
        store.failLocalSendForCurrentOwner(dataSource, "local-1", "offline")

        store.startLocalSendForCurrentOwner(
            dataSource,
            local.copy(deliveryState = ThreadMessageDeliverySending)
        )
        store.failLocalSendForCurrentOwner(dataSource, "local-1", "still_offline")

        val failedMessages = store.state.value.payload.messages.filter { it.id == "local-1" }
        assertEquals(1, failedMessages.size)
        assertEquals(ThreadMessageDeliveryFailed, failedMessages.single().deliveryState)
        assertEquals("still_offline", failedMessages.single().pendingSendErrorCode)

        store.startLocalSendForCurrentOwner(
            dataSource,
            failedMessages.single().copy(deliveryState = ThreadMessageDeliverySending)
        )
        store.replaceLocalSendForCurrentOwner(dataSource, "local-1", sentPage("server-1", "retry body"))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        scope.cancel()
    }

    @Test
    fun localSendReplacementDoesNotDuplicateServerMessageAlreadyInFlow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage("local-1", "sent body"))
        store.accept(SharedThreadIntent.AppendSentPage(sentPage("server-1", "sent body")))

        store.replaceLocalSendForCurrentOwner(dataSource, "local-1", sentPage("server-1", "sent body"))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        scope.cancel()
    }

    @Test
    fun retryPreservesAttachmentIdsAcrossFailureAndRetryCycle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val local =
            localPendingMessage(
                id = "local-1",
                body = "with attachments",
                attachmentIds = listOf("att-1", "att-2")
            )
        store.startLocalSendForCurrentOwner(dataSource, local)
        store.failLocalSendForCurrentOwner(dataSource, "local-1", "network_error")

        val failed = store.state.value.payload.messages.first { it.id == "local-1" }
        assertEquals(ThreadMessageDeliveryFailed, failed.deliveryState)
        assertEquals(listOf("att-1", "att-2"), failed.pendingSendContext?.attachmentIds)
        assertEquals(2, failed.attachments.size)

        store.startLocalSendForCurrentOwner(
            dataSource,
            failed.copy(deliveryState = ThreadMessageDeliverySending)
        )

        val retried = store.state.value.payload.messages.first { it.id == "local-1" }
        assertEquals(ThreadMessageDeliverySending, retried.deliveryState)
        assertNull(retried.pendingSendErrorCode)
        assertEquals(listOf("att-1", "att-2"), retried.pendingSendContext?.attachmentIds)
        assertEquals(2, retried.attachments.size)
        scope.cancel()
    }

    @Test
    fun loadMembersEnrichesThreadMembersFromRepository() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val serverMembers =
            listOf(
                member("u1", "Artin"),
                MemberProfile(
                    id = MemberId("agent-2"),
                    displayName = "Cindy",
                    handle = "cindy",
                    kind = MemberKind.Agent,
                    presence = Presence.Active
                )
            )
        val memberRepository =
            FakeMemberRepository(
                serverMembers = serverMembers,
                channelMembers =
                    listOf(
                        member("u1", "Artin"),
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Android Bot",
                            handle = "android-bot",
                            kind = MemberKind.Agent,
                            presence = Presence.Active
                        )
                    )
            )
        val serverMembersStore = SharedServerMembersStore(memberRepository)
        serverMembersStore.seed(
            MemberSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                members = serverMembers
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        val beforeMessages = store.state.value.payload.messages
        assertTrue(memberRepository.channelMemberRequests.isEmpty())
        assertTrue(store.state.value.payload.members.isEmpty())

        store.accept(SharedThreadIntent.FirstContentReady)
        assertEquals(2, store.state.value.payload.membersCount)
        assertEquals(listOf(ChannelId("channel-1")), memberRepository.channelMemberRequests)
        assertEquals(listOf("u1", "agent-1"), store.state.value.payload.members.map { it.id })
        assertEquals(listOf("agent-2"), store.state.value.payload.memberCandidates.map { it.id })

        store.accept(SharedThreadIntent.LoadMembers)
        assertEquals(
            listOf(ChannelId("channel-1")),
            memberRepository.channelMemberRequests
        )

        val payload = store.state.value.payload
        assertTrue(beforeMessages === payload.messages)
        assertEquals(2, payload.membersCount)
        assertEquals(listOf("u1", "agent-1"), payload.members.map { it.id })
        assertEquals(listOf("agent-2"), payload.memberCandidates.map { it.id })
        store.accept(SharedThreadIntent.LoadMembers)
        assertEquals(
            listOf(ChannelId("channel-1")),
            memberRepository.channelMemberRequests
        )
        scope.cancel()
    }

    @Test
    fun loadMembersRetriesAfterTransientFailure() = runBlocking {
        // Verifies the loadMembers idempotency contract: a transient
        // failure on the first call must NOT set status=Loaded, so the
        // next modal open / explicit LoadMembers re-fetches.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val flakyMemberRepository =
            FakeMemberRepository(
                serverMembers = listOf(member("u1", "Artin")),
                channelMembers = listOf(member("u1", "Artin")),
                failChannelMembersFirstCalls = 1
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = flakyMemberRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(0, flakyMemberRepository.channelMemberCalls, "members must wait for first content readiness")

        store.accept(SharedThreadIntent.FirstContentReady)
        assertEquals(1, flakyMemberRepository.channelMemberCalls, "first content readiness starts background members")

        store.accept(SharedThreadIntent.LoadMembers)
        val callsAfterFirstLoad = flakyMemberRepository.channelMemberCalls
        assertEquals(2, callsAfterFirstLoad, "First explicit LoadMembers should retry after background failure")

        store.accept(SharedThreadIntent.LoadMembers)
        val callsAfterRetry = flakyMemberRepository.channelMemberCalls
        assertEquals(
            2,
            callsAfterRetry,
            "After successful retry, the next LoadMembers should not fetch again"
        )
        // And on the second attempt the request succeeded, so the
        // payload should now have the channel member.
        assertEquals(listOf("u1"), store.state.value.payload.members.map { it.id })
        // A third LoadMembers should now be a no-op (Loaded).
        store.accept(SharedThreadIntent.LoadMembers)
        assertEquals(
            2,
            flakyMemberRepository.channelMemberCalls,
            "After successful retry, status should be Loaded and no further fetch should happen"
        )
        scope.cancel()
    }

    @Test
    fun loadMembersDoesNotBackfillAvatarsFromServerProfile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val uploadedAvatar = "https://cdn.slock.ai/avatars/users/stdrc.webp"
        val memberRepository =
            FakeMemberRepository(
                serverMembers = listOf(member("u1", "Artin").copy(avatarUrl = uploadedAvatar)),
                channelMembers = listOf(member("u1", "Artin"))
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.LoadMembers)

        assertNull(store.state.value.payload.members.single().avatarUrl)
        scope.cancel()
    }

    @Test
    fun threadDataSourceWritesFullAndRealtimeAuthorSnapshotsIntoMemberStore() {
        val memberRepository = FakeMemberRepository()
        val memberStore = SharedServerMembersStore(memberRepository)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                serverMembersStore = memberStore
            )
        val uploadedAvatar =
            "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
        val fullHistoryRow =
            KuiklyThreadMessageItem(
                id = "history-message",
                authorId = "agent-1",
                authorName = "Argus",
                authorKind = "agent",
                avatarWireValue = uploadedAvatar
            )

        dataSource.seedThreadAuthorProfiles(
            serverId = "server-1",
            payload = basePayload().copy(status = "content", messages = listOf(fullHistoryRow)),
            source = "initial"
        )
        assertEquals(
            uploadedAvatar,
            memberStore.state.value.memberProfile(ServerId("server-1"), "agent-1")?.avatar?.wireValue
        )

        dataSource.seedRealtimeThreadAuthorProfiles(
            serverId = "server-1",
            event =
                MessageNew(
                    Message(
                        id = MessageId("realtime-message"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author =
                            member("agent-1", "Argus").copy(
                                kind = MemberKind.Agent,
                                avatar = AvatarReference.fromWireValue(uploadedAvatar),
                                avatarUrl = uploadedAvatar
                            ),
                        timestampLabel = "2026-08-02T00:00:00Z",
                        body = "realtime"
                    )
                ),
            currentUserId = "u1"
        )
        assertEquals(
            uploadedAvatar,
            memberStore.state.value.memberProfile(ServerId("server-1"), "agent-1")?.avatar?.wireValue
        )
    }

    @Test
    fun acceptedCachedPayloadSeedsAvatarResolverBeforeFailedRefresh() = runBlocking {
        val payloadStore = SharedThreadPayloadStore()
        val uploadedAvatar =
            "https://cdn.raft.build/avatars/users/0123456789abcdef0123456789abcdef.webp"
        val cached =
            contentPayload("cached body").let { payload ->
                payload.copy(
                    messages =
                        listOf(
                            payload.messages.single().copy(
                                authorId = "cached-user",
                                authorName = "Cached User",
                                authorKind = "human",
                                avatarWireValue = uploadedAvatar
                            )
                        )
                )
            }
        val memberStore = SharedServerMembersStore(FakeMemberRepository())
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                serverMembersStore = memberStore,
                payloadStore = payloadStore
            )
        dataSource.cachePayloadForTest(cached)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals("cached body", store.state.value.payload.messages.single().body)
        assertEquals(
            uploadedAvatar,
            memberStore.state.value.memberProfile(ServerId("server-1"), "cached-user")?.avatar?.wireValue
        )
        scope.cancel()
    }

    @Test
    fun serverSwitchCacheHitsSeedOnlyTheirSelectedServerInEitherDirection() = runBlocking {
        for (serverOrder in listOf(listOf("server-a", "server-b"), listOf("server-b", "server-a"))) {
            val payloadStore = SharedThreadPayloadStore()
            val memberStore = SharedServerMembersStore(FakeMemberRepository())
            val avatars =
                mapOf(
                    "server-a" to "https://cdn.raft.build/avatars/users/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.webp",
                    "server-b" to "https://cdn.raft.build/avatars/users/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.webp"
                )
            serverOrder.forEach { serverId ->
                val cached =
                    contentPayload("cached $serverId").let { payload ->
                        payload.copy(
                            messages =
                                listOf(
                                    payload.messages.single().copy(
                                        authorId = "shared-user",
                                        authorName = "Shared User",
                                        authorKind = "human",
                                        avatarWireValue = avatars.getValue(serverId)
                                    )
                                )
                        )
                    }
                val session =
                    sampleSession().copy(
                        selectedServerId = serverId,
                        servers =
                            listOf(
                                StoredAuthServer(id = "server-a", name = "Server A", slug = "server-a"),
                                StoredAuthServer(id = "server-b", name = "Server B", slug = "server-b")
                            )
                    )
                val dataSource =
                    testSharedThreadDataSource(
                        networkApi = FakeThreadApi(failChannelMessages = true),
                        sessionStore = InMemorySlockAuthSessionStore(session),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        serverMembersStore = memberStore,
                        payloadStore = payloadStore
                    )
                assertNotNull(dataSource.readSession())
                assertTrue(dataSource.cachePayload(cached, serverId = serverId))
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val store = SharedThreadStore(dataSource = dataSource, scope = scope)

                store.accept(SharedThreadIntent.Start(basePayload()))

                assertEquals("cached $serverId", store.state.value.payload.messages.single().body)
                assertEquals(
                    avatars.getValue(serverId),
                    memberStore.state.value.memberProfile(ServerId(serverId), "shared-user")?.avatar?.wireValue
                )
                val otherServer = if (serverId == "server-a") "server-b" else "server-a"
                val otherAvatar =
                    memberStore.state.value.memberProfile(ServerId(otherServer), "shared-user")?.avatar?.wireValue
                if (otherAvatar != null) {
                    assertEquals(avatars.getValue(otherServer), otherAvatar)
                }
                scope.cancel()
            }
        }
    }

    @Test
    fun acceptedRealtimeInlineReplySeedsResolverWhileRejectedScopeWritesNothing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val memberRepository = FakeMemberRepository()
        val memberStore = SharedServerMembersStore(memberRepository)
        val sessionStore = ParkableReadSessionStore(sampleSession())
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                serverMembersStore = memberStore
            )
        dataSource.cachePayloadForTest(contentPayload("reply parent"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val acceptedAvatar =
            "https://cdn.raft.build/avatars/agents/77777777777777777777777777777777.webp"
        val rejectedAvatar =
            "https://cdn.raft.build/avatars/agents/88888888888888888888888888888888.webp"
        val staleAvatar =
            "https://cdn.raft.build/avatars/agents/99999999999999999999999999999999.webp"
        fun replyEvent(
            senderId: String,
            avatarUrl: String,
            seq: Long,
            envelopeServerId: String
        ) =
            ThreadUpdated(
                parentMessageId = MessageId("m1"),
                threadChannelId = ChannelId("reply-thread"),
                replyCount = seq.toInt(),
                lastReplyAt = "2026-08-02T00:00:00Z",
                participantIds = listOf(MemberId(senderId)),
                unreadCount = 1,
                firstUnreadMessageId = MessageId("reply-$seq"),
                latestReply =
                    ReplyPreview(
                        messageId = "reply-$seq",
                        seq = seq,
                        content = "live reply $seq",
                        senderId = senderId,
                        senderType = "agent",
                        senderName = senderId,
                        senderAvatarUrl = avatarUrl,
                        createdAt = "2026-08-02T00:00:00Z"
                    ),
                syncCoreReplyWindow =
                    build.raft.app.network.sync.ThreadRepliesSyncWindowEnvelope(
                        producer = build.raft.app.network.sync.ThreadRepliesSyncWindowProducer,
                        rootKind = "message",
                        rootServerId = envelopeServerId,
                        rootMessageId = "m1",
                        relationKind = "replies",
                        parentScopeServerId = envelopeServerId,
                        parentScopeKind = "channel",
                        parentScopeId = "channel-1",
                        backing = "sync-scope",
                        windowKind = "sync-scope-window"
                    )
            )

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                replyEvent(
                    senderId = "agent-live",
                    avatarUrl = acceptedAvatar,
                    seq = 1L,
                    envelopeServerId = "server-1"
                )
            )
        )
        val resolver = slockMemberAvatarResolver(memberStore, "server-1")
        assertEquals(acceptedAvatar, resolver.current("agent-live"))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                replyEvent(
                    senderId = "agent-rejected",
                    avatarUrl = rejectedAvatar,
                    seq = 2L,
                    envelopeServerId = "other-server"
                )
            )
        )
        assertNull(resolver.current("agent-rejected"))
        assertNull(memberStore.state.value.memberProfile(ServerId("server-1"), "agent-rejected"))

        sessionStore.parkNextRead()
        val staleEvent =
            launch {
                store.accept(
                    SharedThreadIntent.ApplyRealtimeEvent(
                        replyEvent(
                            senderId = "agent-stale",
                            avatarUrl = staleAvatar,
                            seq = 3L,
                            envelopeServerId = "server-1"
                        )
                    )
                )
            }
        sessionStore.nextReadStarted.await()
        store.accept(
            SharedThreadIntent.Start(
                basePayload().copy(
                    sourceThreadId = "channel-2",
                    streamChannelId = "channel-2",
                    title = "Other channel"
                )
            )
        )
        sessionStore.releaseNextRead.complete(Unit)
        staleEvent.join()
        assertNull(resolver.current("agent-stale"))
        assertNull(memberStore.state.value.memberProfile(ServerId("server-1"), "agent-stale"))
        scope.cancel()
    }

    @Test
    fun addChannelMemberUsesTypedRepositoryAndRefreshesMembers() = runBlocking {
        val memberRepository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        member("u1", "Artin"),
                        MemberProfile(
                            id = MemberId("agent-2"),
                            displayName = "Cindy",
                            handle = "cindy",
                            kind = MemberKind.Agent,
                            presence = Presence.Active
                        )
                    ),
                channelMembers = listOf(member("u1", "Artin"))
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository
            )

        val result = dataSource.addChannelMember(basePayload(), "agent-2", "agent")
        val payload = (result as DataResult.Content).data

        assertEquals(
            listOf(AddChannelMemberRequest(ChannelId("channel-1"), MemberId("agent-2"), MemberKind.Agent)),
            memberRepository.addRequests
        )
        assertEquals(listOf("u1", "agent-2"), payload.members.map { it.id })
        assertEquals(emptyList(), payload.memberCandidates.map { it.id })
    }

    @Test
    fun channelCreateActionCardUsesTypedRepositoryAndMarksExecuted() = runBlocking {
        val channelRepository = FakeChannelRepository()
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = channelRepository,
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "channel:create",
                name = "design-review",
                description = "Coordinate visual review.",
                visibility = "private",
                initialHumans = listOf("u2", "u2"),
                initialAgents = listOf("agent-2")
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-card", card)
        val executedCard = (result as DataResult.Content).data

        assertEquals(
            listOf(
                CreateChannelRequest(
                    name = "design-review",
                    description = "Coordinate visual review.",
                    visibility = "private",
                    agentMemberIds = listOf("agent-2"),
                    userMemberIds = listOf("u2")
                )
            ),
            channelRepository.createRequests
        )
        assertEquals("executed", executedCard.state)
        assertEquals("channel", executedCard.resultKind)
        assertEquals("channel-created", executedCard.resultId)
        assertEquals("design-review", executedCard.resultName)
        assertEquals(
            listOf(
                """POST actions/m-card/mark-executed body={"result":{"kind":"channel","id":"channel-created","name":"design-review","channelId":null,"channelName":null,"addedHumanIds":null,"addedAgentIds":null}}"""
            ),
            actionCardsApi.calls
        )
    }

    @Test
    fun agentCreateActionCardRequiresDialogInsteadOfDirectCommit() = runBlocking {
        val agentsApi = RecordingAgentsApi()
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                agentsApiClient = SlockAgentsApiClient(agentsApi),
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "agent:create",
                name = "@Noel",
                description = "Helps with release review.",
                suggestedComputer = "machine-2",
                draftHint = "Use the release machine."
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-card", card)

        assertTrue(result is DataResult.Error)
        assertEquals("action_card_agent_create_dialog_required", result.error.code)
        assertTrue(agentsApi.createBodies.isEmpty())
        assertTrue(actionCardsApi.calls.isEmpty())
    }

    @Test
    fun threadDataSourceRetainsCreateAgentCoordinatorForRouteInjection() {
        val coordinator =
            SharedCreateAgentCoordinator(
                agentsApiClient = SlockAgentsApiClient(RecordingAgentsApi()),
                computersApiClient = SlockComputersApiClient(RecordingComputersApi())
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                createAgentCoordinator = coordinator
            )

        assertTrue(dataSource.createAgentCoordinatorOrNull === coordinator)
    }

    @Test
    fun agentCreateActionCardDialogSubmitUsesTypedApiAndMarksExecuted() = runBlocking {
        val agentsApi = RecordingAgentsApi()
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                agentsApiClient = SlockAgentsApiClient(agentsApi),
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "agent:create",
                name = "@Noel",
                description = "Helps with release review.",
                requiredComputer = "machine-2",
                draftHint = "Use the release machine."
            )

        val result = dataSource.commitAgentCreateActionCard("m-card", card)
        val executedCard = (result as DataResult.Content).data

        assertEquals(1, agentsApi.createBodies.size)
        assertTrue(agentsApi.createBodies.single().contains(""""name":"Noel""""))
        assertTrue(agentsApi.createBodies.single().contains(""""description":"Helps with release review.""""))
        assertTrue(agentsApi.createBodies.single().contains(""""machineId":"machine-2""""))
        assertFalse(agentsApi.createBodies.single().contains("@Noel"))
        assertEquals("executed", executedCard.state)
        assertEquals("agent", executedCard.resultKind)
        assertEquals("agent-created", executedCard.resultId)
        assertEquals("Noel", executedCard.resultName)
        assertEquals(
            listOf(
                """POST actions/m-card/mark-executed body={"result":{"kind":"agent","id":"agent-created","name":"Noel","channelId":null,"channelName":null,"addedHumanIds":null,"addedAgentIds":null}}"""
            ),
            actionCardsApi.calls
        )
    }

    @Test
    fun actionCardCreateDoesNotReturnExecutedCardWhenMarkExecutedFails() = runBlocking {
        val agentsApi = RecordingAgentsApi()
        val actionCardsApi = RecordingActionCardsApi(
            markResponse = HomeNetworkResponse(code = 500, bodyText = """{"error":"server_unavailable"}""")
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                agentsApiClient = SlockAgentsApiClient(agentsApi),
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card = ThreadActionCard(type = "agent:create", name = "Noel")

        val result = dataSource.commitAgentCreateActionCard("m-card", card)

        assertEquals(1, agentsApi.createBodies.size)
        assertEquals(1, actionCardsApi.calls.size)
        assertTrue(result is DataResult.Error)
        assertEquals("action_mark_executed_contract_mismatch", result.error.code)
    }

    @Test
    fun channelAddMembersActionCardUsesTypedRepositoryAndMarksExecuted() = runBlocking {
        val memberRepository = FakeMemberRepository()
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "channel:add_member",
                channel = "channel-2",
                humans = listOf("u3", "u3"),
                agents = listOf("agent-3"),
                draftHint = "Bring owners in."
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-card", card)
        val executedCard = (result as DataResult.Content).data

        // task #352: ONE atomic batch for the whole card (mixed humans + agents),
        // not a per-member loop.
        assertEquals(
            listOf(
                AddChannelMembersBatchRequest(
                    channelId = ChannelId("channel-2"),
                    userIds = listOf(MemberId("u3")),
                    agentIds = listOf(MemberId("agent-3"))
                )
            ),
            memberRepository.batchRequests
        )
        assertEquals(emptyList(), memberRepository.addRequests)
        assertEquals("executed", executedCard.state)
        assertEquals("channel-members", executedCard.resultKind)
        assertEquals("channel-2", executedCard.resultId)
        assertEquals(listOf("u3"), executedCard.resultAddedHumanIds)
        assertEquals(listOf("agent-3"), executedCard.resultAddedAgentIds)
        assertEquals(
            listOf(
                """POST actions/m-card/mark-executed body={"result":{"kind":"channel-members","id":null,"name":null,"channelId":"channel-2","channelName":"channel-2","addedHumanIds":["u3"],"addedAgentIds":["agent-3"]}}"""
            ),
            actionCardsApi.calls
        )
    }

    @Test
    fun channelAddMembersActionCardAlreadyMembersOnlyIsIdempotentSuccess() = runBlocking {
        // task #352 / #361 blocker: the server defines an all-already-members batch
        // as an idempotent SUCCESS (added:[], ids under alreadyMembers). The action
        // card must therefore advance to executed, not fail — so a re-send never
        // blocks card completion.
        val memberRepository =
            FakeMemberRepository(
                channelMembers = listOf(member("u3", "Existing Human"), member("agent-3", "Existing Agent"))
            )
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "channel:add_member",
                channel = "channel-2",
                humans = listOf("u3"),
                agents = listOf("agent-3"),
                draftHint = "Bring owners in."
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-card", card)
        val executedCard = (result as DataResult.Content).data
        assertEquals(
            listOf(
                AddChannelMembersBatchRequest(
                    channelId = ChannelId("channel-2"),
                    userIds = listOf(MemberId("u3")),
                    agentIds = listOf(MemberId("agent-3"))
                )
            ),
            memberRepository.batchRequests
        )
        assertEquals("executed", executedCard.state)
        // Nothing new was added; the card still completes with empty added-ids.
        assertEquals(listOf(), executedCard.resultAddedHumanIds)
        assertEquals(listOf(), executedCard.resultAddedAgentIds)
    }

    @Test
    fun integrationApproveLoginActionCardExecutesInlineAction() = runBlocking {
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "integration:approve_agent_login",
                requestId = "request-1",
                agentId = "agent-1",
                agentName = "KMP-专家",
                clientId = "client-1",
                clientKey = "hands",
                clientName = "Hands",
                scopes = listOf("identity", "profile")
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-card", card)
        val executedCard = (result as DataResult.Content).data

        assertEquals("executed", executedCard.state)
        assertEquals("agent-integration-login", executedCard.resultKind)
        assertEquals("grant-1", executedCard.resultId)
        assertEquals("Hands", executedCard.resultName)
        assertEquals("artin", executedCard.executedByUserName)
        assertEquals(
            listOf("""POST actions/m-card/execute body={"expectedState":"prepared"}"""),
            actionCardsApi.calls
        )
    }

    @Test
    fun genericRegisterAppActionCardExecutesThroughServerAuthority() = runBlocking {
        val actionCardsApi =
            RecordingActionCardsApi(
                executeResponse =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """
                            {
                              "messageId":"m-register",
                              "metadata":{
                                "kind":"action-card",
                                "state":"executed",
                                "executedByUserName":"artin",
                                "action":{
                                  "type":"integration:register_app",
                                  "name":"Build Monitor",
                                  "returnUrl":"https://build.example/oauth/callback",
                                  "scopes":["identity","profile"]
                                },
                                "result":{
                                  "kind":"integration-app-registration",
                                  "mode":"register",
                                  "clientId":"client-1",
                                  "clientKey":"build-monitor",
                                  "clientName":"Build Monitor",
                                  "returnUrl":"https://build.example/oauth/callback",
                                  "scopes":["identity","profile"]
                                }
                              }
                            }
                            """.trimIndent()
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "integration:register_app",
                name = "Build Monitor",
                metadataItems =
                    listOf(
                        ThreadActionCardMetadataItem(
                            key = "returnUrl",
                            value = "https://build.example/oauth/callback"
                        )
                    )
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-register", card)
        val executedCard = (result as DataResult.Content).data

        assertEquals("executed", executedCard.state)
        assertEquals("integration-app-registration", executedCard.resultKind)
        assertEquals("client-1", executedCard.resultId)
        assertEquals("Build Monitor", executedCard.resultName)
        assertEquals("artin", executedCard.executedByUserName)
        assertEquals(
            listOf("""POST actions/m-register/execute body={"expectedState":"prepared"}"""),
            actionCardsApi.calls
        )
    }

    @Test
    fun retiredUpdateRegistrationCardDoesNotCallGenericExecute() = runBlocking {
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(actionCardsApi)
            )

        val result =
            dataSource.commitThreadActionCard(
                basePayload(),
                "m-update",
                ThreadActionCard(type = "integration:update_app_registration")
            )

        assertTrue(result is DataResult.Error)
        assertEquals("action_card_unsupported", result.error.code)
        assertTrue(actionCardsApi.calls.isEmpty())
    }

    @Test
    fun removeChannelMemberUsesTypedRepositoryAndRefreshesMembers() = runBlocking {
        val serverMembers = listOf(member("u1", "Artin"), member("u2", "Stdrc"))
        val memberRepository =
            FakeMemberRepository(
                serverMembers = serverMembers,
                channelMembers = listOf(member("u1", "Artin"), member("u2", "Stdrc"))
            )
        val serverMembersStore = SharedServerMembersStore(memberRepository)
        serverMembersStore.seed(
            MemberSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                members = serverMembers
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore
            )

        val result = dataSource.removeChannelMember(basePayload(), "u2", "human")
        val payload = (result as DataResult.Content).data

        assertEquals(
            listOf(RemoveChannelMemberRequest(ChannelId("channel-1"), MemberId("u2"), MemberKind.Human)),
            memberRepository.removeRequests
        )
        assertEquals(listOf("u1"), payload.members.map { it.id })
        assertEquals(listOf("u2"), payload.memberCandidates.map { it.id })
    }

    @Test
    fun startHydratesThreadMembersInBackgroundAfterRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val serverMembers = listOf(member("u1", "Artin"), member("u2", "Stdrc"))
        val memberRepository =
            FakeMemberRepository(
                serverMembers = serverMembers,
                channelMembers = listOf(member("u1", "Artin"))
            )
        val serverMembersStore = SharedServerMembersStore(memberRepository)
        serverMembersStore.seed(
            MemberSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                members = serverMembers
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals("content", store.state.value.payload.status)
        assertTrue(memberRepository.channelMemberRequests.isEmpty())
        store.accept(SharedThreadIntent.FirstContentReady)
        assertEquals(listOf(ChannelId("channel-1")), memberRepository.channelMemberRequests)
        assertEquals(listOf("u1"), store.state.value.payload.members.map { it.id })
        assertEquals(listOf("u2"), store.state.value.payload.memberCandidates.map { it.id })
        scope.cancel()
    }

    @Test
    fun threadMembersPreserveRemoteSourceServerAndDisableRemoteRemove() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remoteMember =
            MemberProfile(
                id = MemberId("remote-agent"),
                displayName = "Remote Agent",
                handle = "remote-agent",
                kind = MemberKind.Agent,
                serverId = ServerId("server-2"),
                serverName = "Remote Lab",
                serverSlug = "remote-lab",
                avatarUrl = "https://example.com/remote.png"
            )
        val memberRepository =
            FakeMemberRepository(
                serverMembers = listOf(member("u1", "Artin")),
                channelMembers = listOf(member("u1", "Artin"), remoteMember)
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.LoadMembers)

        val remote = store.state.value.payload.members.single { it.id == "remote-agent" }
        assertEquals("server-2", remote.sourceServerId)
        assertEquals("Remote Lab", remote.sourceServerLabel)
        assertEquals(false, remote.canRemove)
        assertEquals("https://example.com/remote.png", remote.avatarUrl)
        scope.cancel()
    }

    @Test
    fun persistentCachedThreadSnapshotIsIgnoredOnVisibleFetchFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("cached-1"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            author = member("u2", "Stdrc"),
                            timestampLabel = "2026-05-19T15:00:00Z",
                            body = "persistent cached body"
                        )
                    )
            )
        )
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("error", payload.status)
        assertEquals(emptyList(), payload.messages.map { it.body })
        assertEquals("thread_network", payload.errorCode)
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun channelPersistentCachedSnapshotIsNotReadBeforeLoadingFrame() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("cached-1"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            author = member("u2", "Stdrc"),
                            timestampLabel = "2026-05-19T15:00:00Z",
                            body = "first visible cached body"
                        )
                    )
            )
        )
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertFalse(logs.any { it.contains("thread_persistent_cache_result") })
        assertTrue(logs.any { it.contains("thread_visible_payload") && it.contains("status=loading") })
        assertEquals(emptyList(), store.state.value.payload.messages.map { it.body })
        scope.cancel()
    }

    @Test
    fun directMessagePersistentSnapshotIsIgnored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("dm-1"),
                        channelId = ChannelId("dm-1"),
                        kind = ConversationKind.DirectMessage,
                        name = "b2cddd71-75a9-4cfc-b3e0-02485b50900d"
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("cached-1"),
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            author = member("u2", "Stdrc"),
                            timestampLabel = "2026-06-21T15:32:00Z",
                            body = "cached dm body"
                        )
                    )
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(directMessagePayload()))

        val payload = store.state.value.payload
        val headerDisplay = payload.threadHeaderFallbackDisplayModel()
        assertEquals("error", payload.status)
        assertEquals("artin", payload.title)
        assertEquals("Direct Message", headerDisplay.title)
        assertEquals("artin", payload.dmPeerName)
        assertEquals("pixel:crown", payload.dmPeerAvatarUrl)
        assertEquals("AR", payload.dmPeerAvatarLabel)
        assertEquals(emptyList(), payload.messages.map { it.body })
        scope.cancel()
    }

    @Test
    fun agentDirectMessagePersistentSnapshotIsIgnored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("dm-agent-1"),
                        channelId = ChannelId("dm-agent-1"),
                        kind = ConversationKind.DirectMessage,
                        name = "Product-UX-Designer",
                        directMessagePeerType = "agent",
                        presenceMemberId = MemberId("agent-1")
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("cached-1"),
                            threadId = ThreadId("dm-agent-1"),
                            channelId = ChannelId("dm-agent-1"),
                            author =
                                MemberProfile(
                                    id = MemberId("agent-1"),
                                    displayName = "Product UX",
                                    handle = "product-ux",
                                    kind = MemberKind.Agent,
                                    avatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                                ),
                            timestampLabel = "2026-06-21T15:32:00Z",
                            body = "cached agent dm body"
                        )
                    )
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gravatarSeed =
            directMessagePayload().copy(
                sourceThreadId = "dm-agent-1",
                streamChannelId = "dm-agent-1",
                title = "Product-UX-Designer",
                dmPeerName = "Product-UX-Designer",
                dmPeerAvatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                dmPeerAvatarLabel = "PU",
            )

        store.accept(SharedThreadIntent.Start(gravatarSeed))

        val payload = store.state.value.payload
        assertEquals("error", payload.status)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("Product-UX-Designer", payload.dmPeerName)
        assertEquals("https://www.gravatar.com/avatar/agent-hash?s=52&d=404", payload.dmPeerAvatarUrl)
        assertEquals(emptyList(), payload.messages.map { it.body })
        scope.cancel()
    }

    @Test
    fun persistentCachedThreadSnapshotWindowIsIgnored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation = conversationSummary(),
                messages = (1..35).map { index -> message(id = "cached-$index", seq = index.toLong(), body = "cached $index") }
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals(emptyList(), payload.messages.map { it.body })
        assertEquals(false, payload.canLoadMoreBefore)
        assertEquals("thread_network", payload.errorCode)
        scope.cancel()
    }

    @Test
    fun successfulFetchCachesThreadTimelineInMemoryWithoutPersistentSnapshot() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )

        dataSource.fetchThreadPayload(basePayload())

        val cached = localStore.readThread(ThreadId("channel-1"))
        assertNull(cached)
        assertEquals("network body", dataSource.cachedPayload("channel-1")?.messages?.single()?.body)
        assertEquals("Artin", dataSource.cachedPayload("channel-1")?.messages?.single()?.authorName)
    }

    @Test
    fun focusedReplyContextRestoresOriginFromCachedParentMessage() = runBlocking {
        val repository =
            replyHintRepository().also { fake ->
                fake.messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = emptyList(),
                            parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
                        )
                    )
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
        )
        val firstOpen = dataSource.fetchThreadPayload(unresolvedReplyPayload(parentMessageId = "m1"))
        val firstPayload = (firstOpen as? DataResult.Content)?.data

        assertNotNull(firstPayload)
        assertEquals("parent body", firstPayload.originMessage?.body)

        repository.messageContextResult =
            DataResult.Content(
                threadSnapshot(
                    threadId = "thread-m1",
                    messages =
                        listOf(
                            message(
                                threadId = "thread-m1",
                                channelId = "thread-m1",
                                id = "reply-1",
                                seq = 2,
                                body = "focused reply"
                            )
                        ),
                    parentMessage = null,
                    conversation =
                        conversationSummary("thread-m1").copy(
                            kind = ConversationKind.Thread,
                            channelId = ChannelId("thread-m1"),
                            thread =
                                ThreadConversation(
                                    parentChannelId = ChannelId("channel-1"),
                                    parentMessageId = MessageId("m1"),
                                    streamChannelId = ChannelId("thread-m1")
                                )
                        )
                )
            )

        val secondOpen =
            dataSource.fetchThreadPayload(
                replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                    .copy(focusedMessageId = "reply-1")
            )
        val secondPayload = (secondOpen as? DataResult.Content)?.data

        assertNotNull(secondPayload)
        assertEquals("loaded", secondPayload.originStatus)
        assertEquals("m1", secondPayload.originMessage?.id)
        assertEquals("parent body", secondPayload.originMessage?.body)
        assertEquals(listOf("focused reply"), secondPayload.messages.map { it.body })
    }

    @Test
    fun fetchedThreadPayloadUsesHomeDirectMessageAvatarBeforeMemoryCacheWrite() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                currentMember = member("u1", "Artin"),
                channels = emptyList(),
                directMessages =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("dm-agent-1"),
                            channelId = ChannelId("dm-agent-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "Product-UX-Designer",
                            presenceMemberId = MemberId("agent-1")
                        ).toHomeDirectMessageRow()
                    ),
                unreadTotal = 0
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals(null, payload.messages.single().avatarWireValue)
        val cachedAuthor = localStore.readThread(ThreadId("channel-1"))?.messages?.single()?.author
        assertNull(cachedAuthor)
        val cachedMessage = dataSource.cachedPayload("channel-1")?.messages?.single()
        assertEquals(null, cachedMessage?.avatarWireValue)
    }

    @Test
    fun fetchedThreadPayloadDoesNotBlockOnMemberProfileAvatarBeforeFirstRender() = runBlocking {
        val memberRepository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Product UX",
                            handle = "product-ux",
                            kind = MemberKind.Agent,
                            avatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                            avatar = AvatarReference.Pixel("tree")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals(emptyList(), memberRepository.serverMemberRequests)
        val message = payload.messages.single()
        assertEquals("Product-UX-Designer", message.authorName)
        assertEquals(null, message.avatarWireValue)
    }

    @Test
    fun secondaryRefreshDoesNotFetchOrPatchSenderProfileAvatars() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val memberRepository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Product UX",
                            handle = "product-ux",
                            kind = MemberKind.Agent,
                            avatar = AvatarReference.Pixel("tree")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals(emptyList(), memberRepository.serverMemberRequests)
        val message = store.state.value.payload.messages.single()
        assertEquals("Product-UX-Designer", message.authorName)
        assertEquals(null, message.avatarWireValue)
        assertNull(localStore.readThread(ThreadId("channel-1")))
        assertEquals(null, dataSource.cachedPayload("channel-1")?.messages?.single()?.avatarWireValue)
        scope.cancel()
    }

    @Test
    fun taskReplyThreadPayloadDoesNotBlockOnMemberProfileAvatarForOriginBeforeFirstRender() = runBlocking {
        val agentParent =
            message(threadId = "channel-1", id = "m1", seq = 1, body = "agent task parent")
                .copy(
                    author =
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Product UX",
                            handle = "product-ux",
                            kind = MemberKind.Agent,
                            avatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                        )
                )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")
                                ),
                            parentMessage = agentParent,
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val memberRepository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Product UX",
                            handle = "product-ux",
                            kind = MemberKind.Agent,
                            avatar = AvatarReference.Pixel("tree")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                memberRepository = memberRepository
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(emptyList(), memberRepository.serverMemberRequests)
        assertEquals("agent task parent", payload.originMessage?.body)
        assertEquals("pixel:robot", payload.originMessage?.avatarWireValue)
    }

    @Test
    fun taskReplyThreadPayloadFallsBackToStreamChannelNameWithoutHomeSnapshot() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                currentMember = member("u1", "Artin"),
                channels =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            kind = ConversationKind.Channel,
                            name = "general",
                            subtitle = "#general"
                        ).toHomeChannelRow()
                    ),
                directMessages = emptyList(),
                unreadTotal = 0
            )
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")),
                            parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body"),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    name = "thread-m1",
                                    subtitle = null,
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(ThreadReplyPageTitle, threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        // fb845a19 removed the durable HomeSnapshot blob cache, so the parent
        // channel name can no longer be resolved from the local store here —
        // the subtitle falls back to the thread's own channel name; live
        // parent resolution happens through the runtime conversation
        // identity provider instead.
        assertEquals("#thread-m1", payload.subtitle)
    }

    @Test
    fun fetchedThreadPayloadNormalizesAgentAvatarBeforeFirstRender() = runBlocking {
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        val message = payload.messages.single()
        assertEquals(null, message.avatarWireValue)
    }

    @Test
    fun fetchThreadPayloadWithoutSessionReturnsAuthRequiredNoPermission() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        val result = dataSource.fetchThreadPayload(basePayload())
        val blocker = (result as DataResult.NoPermission).blocker

        assertEquals("auth_required", blocker.code)
        assertEquals("auth_session", blocker.requiredCapability)
        assertEquals(0, api.channelMessageCalls)
    }

    @Test
    fun openMessageContextWithoutSessionReturnsAuthRequiredNoPermission() = runBlocking {
        val repository =
            FakeConversationRepository(
                messageContextResult = DataResult.Content(threadSnapshot())
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val result = dataSource.openMessageContext("message-1", basePayload())
        val blocker = (result as DataResult.NoPermission).blocker

        assertEquals("auth_required", blocker.code)
        assertEquals("auth_session", blocker.requiredCapability)
        assertEquals(0, repository.getMessageContextRequests.size)
    }

    @Test
    fun openMessageContextKeepsExplicitDirectMessageParentSurfaceWhenSnapshotLooksThread() = runBlocking {
        val timelineMessage =
            message(
                threadId = "dm-1",
                id = "parent-1",
                seq = 12,
                body = "Parent DM message"
            )
        val parentMessage =
            timelineMessage.copy(
                threadChannelId = ChannelId("reply-thread-1"),
                replyCount = 3,
                unreadReplyCount = 1
            )
        val repository =
            FakeConversationRepository(
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "reply-thread-1",
                            messages = listOf(timelineMessage),
                            parentMessage = parentMessage,
                            conversation =
                                ConversationSummary(
                                    threadId = ThreadId("reply-thread-1"),
                                    channelId = ChannelId("dm-1"),
                                    kind = ConversationKind.Thread,
                                    name = "Thread",
                                    subtitle = "#Cindy",
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("dm-1"),
                                            parentMessageId = MessageId("parent-1"),
                                            streamChannelId = ChannelId("reply-thread-1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val parentRoute =
            directMessagePayload().copy(
                title = "Cindy",
                dmPeerName = "Cindy",
                dmPeerAvatarUrl = "pixel:cindy",
                dmPeerAvatarLabel = "CI",
                focusedMessageId = "parent-1"
            )

        val payload = (dataSource.openMessageContext("parent-1", parentRoute) as DataResult.Content).data

        assertEquals(listOf(MessageId("parent-1")), repository.getMessageContextRequests)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("dm-1", payload.sourceThreadId)
        assertEquals("dm-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        val headerDisplay =
            payload.threadHeaderDisplayModelFromDirectory(
                ConversationSummary(
                    threadId = ThreadId("dm-1"),
                    channelId = ChannelId("dm-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "Cindy"
                ).toConversationHeaderProjection()
            )

        assertEquals("Thread", payload.title)
        assertEquals("Cindy", headerDisplay.title)
        assertEquals(null, headerDisplay.dmPeerAvatarWireValue)
        assertEquals(null, payload.subtitle)
        assertEquals("Thread", payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals("TH", payload.dmPeerAvatarLabel)
        assertEquals("reply-thread-1", payload.messages.single().threadChannelId)
        assertEquals(3, payload.messages.single().replyCount)
        assertEquals(1, payload.messages.single().unreadReplyCount)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.resolveThreadComposer().surfaceKind)
    }

    @Test
    fun fetchThreadPayloadPrefersConversationRepositorySnapshotWhenAvailable() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "repository body"))
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals(listOf(ThreadId("channel-1")), repository.getThreadRequests)
        assertEquals(listOf(false), repository.getThreadIncludeFollowedSummaries)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("repository body", payload.messages.single().body)
    }

    @Test
    fun initialMessagePageSummaryAdmitsInlinePreviewBeforeFirstContentReady() = runBlocking {
        val firstFrameSummary =
            ThreadReplySummariesSnapshot(
                threads =
                    listOf(
                        InboxItem(
                            key = "thread:m1",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("thread-m1"),
                            channelName = "reply thread",
                            threadChannelId = ChannelId("thread-m1"),
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId("m1"),
                            replyCount = 5,
                            unreadCount = 1,
                            firstUnreadMessageId = MessageId("reply-5"),
                            latestReplies =
                                listOf(
                                    ReplyPreview(
                                        messageId = "reply-5",
                                        seq = 5,
                                        content = "first-frame preview",
                                        senderId = "agent-1",
                                        senderType = "agent",
                                        senderName = "MingQi",
                                        senderDisplayName = "明启",
                                        senderAvatarUrl = null,
                                        createdAt = "2026-07-25T14:01:00Z",
                                    )
                                ),
                        )
                    )
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m1",
                                        seq = 10,
                                        body = "initial body",
                                    )
                                ),
                            replySummaries = firstFrameSummary,
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val firstVisibleMessage = store.state.value.payload.messages.single()
        assertEquals("thread-m1", firstVisibleMessage.threadChannelId)
        assertEquals(5, firstVisibleMessage.replyCount)
        assertEquals(1, firstVisibleMessage.unreadReplyCount)
        assertEquals("reply-5", firstVisibleMessage.firstUnreadReplyMessageId)
        assertEquals(listOf("reply-5"), firstVisibleMessage.inlineReplies?.replies?.map { it.messageId })
        assertEquals("明启", firstVisibleMessage.inlineReplies?.replies?.single()?.senderDisplayName)
        delay(1L)
        assertEquals(
            listOf(ThreadId("channel-1") to listOf(MessageId("m1"))),
            repository.getThreadReplySummariesRequests
        )
        scope.cancel()
    }

    @Test
    fun secondaryRefreshBackfillsFollowedSummariesWithoutBlockingInitialFetch() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "initial body",
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m1",
                                        seq = 10,
                                        body = "initial body",
                                        threadChannelId = "thread-m1",
                                        replyCount = 0
                                    )
                                )
                        )
                    ),
                threadReplySummariesResult =
                    DataResult.Content(
                        ThreadReplySummariesSnapshot(
                            threads =
                                listOf(
                                    InboxItem(
                                        key = "thread:m1",
                                        kind = InboxItemKind.Thread,
                                        channelId = ChannelId("thread-m1"),
                                        channelName = "reply thread",
                                        threadChannelId = ChannelId("thread-m1"),
                                        parentChannelId = ChannelId("channel-1"),
                                        parentMessageId = MessageId("m1"),
                                        replyCount = 7,
                                        unreadCount = 2,
                                        firstUnreadMessageId = MessageId("reply-6"),
                                        latestReplies =
                                            listOf(
                                                ReplyPreview(
                                                    messageId = "reply-6",
                                                    seq = 6,
                                                    content = "first unread",
                                                    senderId = "u2",
                                                    senderType = "human",
                                                    senderName = "cindy",
                                                    senderAvatarUrl = null,
                                                    createdAt = "2026-05-19T16:06:00Z",
                                                    senderDisplayName = "Cindy",
                                                )
                                            )
                                    )
                                ),
                            followedThreadKeys = setOf("thread:m1")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        delay(1L)

        assertEquals(listOf(false), repository.getThreadIncludeFollowedSummaries)
        assertEquals(
            listOf(ThreadId("channel-1") to listOf(MessageId("m1"))),
            repository.getThreadReplySummariesRequests
        )
        val message = store.state.value.payload.messages.single()
        assertEquals("initial body", message.body)
        assertEquals(7, message.replyCount)
        assertEquals(2, message.unreadReplyCount)
        assertEquals("reply-6", message.firstUnreadReplyMessageId)
        assertEquals(listOf("reply-6"), message.inlineReplies?.replies?.map { it.messageId })
        assertEquals("Cindy", message.inlineReplies?.replies?.single()?.senderDisplayName)
        assertTrue(message.isThreadFollowed)
        assertEquals(0, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun delayedSecondaryProjectionDoesNotReplaceCommittedNewerPage() = runBlocking {
        val initialMessages =
            (1L..16L).map { seq ->
                message(
                    id = "m$seq",
                    seq = seq,
                    body = "message $seq",
                    threadChannelId = if (seq == 1L) "thread-m1" else null,
                )
            }
        val newerMessages =
            (17L..46L).map { seq ->
                message(id = "m$seq", seq = seq, body = "message $seq")
            }
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(messages = initialMessages)),
                threadReplySummariesResult = replySummaries(replyCount = 7),
                newerResult = DataResult.Content(threadSnapshot(messages = newerMessages)),
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var store: SharedThreadStore
        var projectionCommitCount = 0
        var maxSeqAfterNewerCommit: Long? = null
        val repliesSyncHost =
            ThreadRepliesSyncHost(
                beforeProjectionCommit = {
                    projectionCommitCount += 1
                    if (projectionCommitCount == 2) {
                        runBlocking {
                            assertTrue(store.loadNewer() is ThreadNewerPageLoadOutcome.Applied)
                            maxSeqAfterNewerCommit = store.state.value.payload.messages.mapNotNull { it.seq }.maxOrNull()
                            store.accept(SharedThreadIntent.ApplyMessageSaved("m1", saved = true))
                        }
                    }
                },
            )
        store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
                threadRepliesSyncHost = repliesSyncHost,
            )

        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))
        store.accept(
            SharedThreadIntent.SetPagingAvailability(
                canLoadMoreBefore = false,
                canLoadMoreAfter = true,
            ),
        )
        store.accept(SharedThreadIntent.FirstContentReady)

        assertEquals(46L, maxSeqAfterNewerCommit)
        assertEquals(2, projectionCommitCount)
        assertEquals(46L, store.state.value.payload.messages.mapNotNull { it.seq }.maxOrNull())
        assertEquals((1L..46L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals(listOf(ThreadId("channel-1") to 16L), repository.newerRequests)
        assertEquals(7, store.state.value.payload.messages.first { it.id == "m1" }.replyCount)
        assertTrue(store.state.value.payload.messages.first { it.id == "m1" }.isSaved)
        scope.cancel()
    }

    @Test
    fun lateSummaryFromPreviousRouteCannotMutateCurrentCanonicalMessages() = runBlocking {
        val repository =
            FakeConversationRepository(
                threadResultsById =
                    mapOf(
                        ThreadId("channel-1") to
                            DataResult.Content(threadSnapshot(threadId = "channel-1", body = "route one")),
                        ThreadId("channel-2") to
                            DataResult.Content(threadSnapshot(threadId = "channel-2", body = "route two")),
                    ),
                threadReplySummariesResult = replySummaries(replyCount = 7),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        repository.threadReplySummariesStarted?.await()
        store.accept(
            SharedThreadIntent.Start(
                basePayload().copy(
                    instanceId = "shared-shell-thread-channel-2",
                    sourceThreadId = "channel-2",
                    streamChannelId = "channel-2",
                ),
            ),
        )
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()

        val current = store.state.value.payload
        assertEquals("channel-2", current.sourceThreadId)
        assertEquals(listOf("route two"), current.messages.map { it.body })
        assertEquals(0, current.messages.single().replyCount)
        scope.cancel()
    }

    @Test
    fun channelSummaryBackfillWithNoChangesDoesNotRefetchTimelineOrEmitState() = runBlocking {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "stable body")),
                threadReplySummariesResult =
                    DataResult.Content(ThreadReplySummariesSnapshot(threads = emptyList()))
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val logs = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        delay(1L)

        assertEquals(listOf(false), repository.getThreadIncludeFollowedSummaries)
        assertEquals(
            listOf(ThreadId("channel-1") to listOf(MessageId("m1"))),
            repository.getThreadReplySummariesRequests
        )
        assertEquals("stable body", store.state.value.payload.messages.single().body)
        assertTrue(
            logs.any {
                it.startsWith("thread_secondary_refresh_result") &&
                    it.contains("emitted=false")
            }
        )
        scope.cancel()
    }

    @Test
    fun earlySummaryResultStashesUntilFirstContentReady() = runBlocking {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "stable body")),
                threadReplySummariesResult = replySummaries(replyCount = 7),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(basePayload()))
        repository.threadReplySummariesStarted?.await()
        assertEquals(
            listOf(ThreadId("channel-1") to listOf(MessageId("m1"))),
            repository.getThreadReplySummariesRequests
        )
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()
        assertEquals(0, store.state.value.payload.messages.single().replyCount)

        store.accept(SharedThreadIntent.FirstContentReady)
        yield()
        delay(1L)
        assertEquals(7, store.state.value.payload.messages.single().replyCount)
        scope.cancel()
    }

    @Test
    fun lateSummaryResultAppliesImmediatelyAfterFirstContentReady() = runBlocking {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "stable body")),
                threadReplySummariesResult = replySummaries(replyCount = 7),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        repository.threadReplySummariesStarted?.await()
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()
        delay(1L)
        assertEquals(7, store.state.value.payload.messages.single().replyCount)
        scope.cancel()
    }

    @Test
    fun summarySnapshotDoesNotOverwriteNewerRealtimeFact() = runBlocking {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "stable body")),
                threadReplySummariesResult = replySummaries(replyCount = 15),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        repository.threadReplySummariesStarted?.await()
        // A realtime reply-count fact lands while the summaries request is in
        // flight; its newer observedAt must survive the older server snapshot.
        dataSource.recordReplyCountRealtimeFact(
            threadId = "channel-1",
            parentMessageId = "m1",
            fact =
                SharedThreadReplyCountFact(
                    threadChannelId = "thread-m1",
                    replyCount = 17,
                    observedAt = TimeSource.Monotonic.markNow(),
                ),
        )
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()
        delay(1L)
        assertEquals(17, store.state.value.payload.messages.single().replyCount)
        scope.cancel()
    }

    @Test
    fun replyThreadStartPromotesQueuedActivityPrewarmToForeground() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val payloadStore =
            SharedThreadPayloadStore(
                scope = appScope,
                maxConcurrentPrewarms = 1,
                prewarmStartIntervalMillis = 0L
            )
        val blockerStarted = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val blocker =
            async {
                payloadStore.prewarm("server-1", "thread-blocker", TestThreadOwnerScopeId) {
                    blockerStarted.complete(Unit)
                    releaseBlocker.await()
                    DataResult.Content(
                        contentPayloadForRoute(
                            base = replyPayload(parentMessageId = "blocker", threadId = "thread-blocker"),
                            body = "blocker body",
                            id = "blocker-reply",
                            channelId = "thread-blocker"
                        )
                    )
                }
            }
        blockerStarted.await()

        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")),
                            parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body"),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(SlockShellStoreScope.create("thread-prewarm-promote", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val marker =
            ActivityReplyThreadPrewarmMarker(
                key =
                    ActivityReplyThreadPrewarmKey(
                        serverId = "server-1",
                        parentChannelId = "channel-1",
                        parentMessageId = "m1",
                        threadChannelId = "thread-m1"
                    )
            )
        val queuedPrewarm =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    dataSource.prewarmReplyThread(marker)
                } catch (_: CancellationException) {
                    // A visible open owns the replacement foreground request.
                }
            }
        assertEquals(emptyList(), repository.getReplyThreadRequests)

        val visibleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = visibleScope
            )
        withTimeout(1_000L) {
            store.accept(
                SharedThreadIntent.Start(replyPayload(parentMessageId = "m1", threadId = "thread-m1"))
            )
        }

        assertEquals(
            listOf(ChannelId("channel-1") to MessageId("m1")),
            repository.getReplyThreadRequests
        )
        assertEquals(listOf(DataRequestPriority.Foreground), repository.getReplyThreadPriorities)
        assertEquals("reply body", store.state.value.payload.messages.single().body)
        queuedPrewarm.join()
        releaseBlocker.complete(Unit)
        blocker.await()
        visibleScope.cancel()
        appScope.cancel()
    }

    @Test
    fun replyThreadStartJoinsActivityPrewarmAndHydratesParentAfterGraceTimeout() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
        val parentMessageId = MessageId("m1")
        val releaseParent = CompletableDeferred<Unit>()
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        focusedReplySnapshot(
                            threadId = "thread-m1",
                            parentChannelId = "channel-1",
                            parentMessageId = parentMessageId.value,
                            focusedMessageId = "reply-1"
                        )
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            id = parentMessageId.value,
                            seq = 1,
                            body = "late parent"
                        )
                    )
            )
        repository.releaseMessageContextById[parentMessageId] = releaseParent
        val replyFetchStarted = CompletableDeferred<Unit>()
        val releaseReplyFetch = CompletableDeferred<Unit>()
        repository.getReplyThreadStarted = replyFetchStarted
        repository.releaseGetReplyThread = releaseReplyFetch
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(SlockShellStoreScope.create("thread-prewarm-visible-start", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore,
                replyOriginHydrationGraceMillis = 0L
            )
        val marker =
            ActivityReplyThreadPrewarmMarker(
                ActivityReplyThreadPrewarmKey(
                    serverId = "server-1",
                    parentChannelId = "channel-1",
                    parentMessageId = parentMessageId.value,
                    threadChannelId = "thread-m1"
                )
            )
        val prewarm = async { dataSource.prewarmReplyThread(marker) }
        replyFetchStarted.await()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val logs = ConcurrentTestLogBuffer()
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        val visibleStart = async {
            store.accept(
                SharedThreadIntent.Start(replyPayload(parentMessageId.value, "thread-m1").copy(originStatus = "loading"))
            )
        }
        delay(20L)
        assertEquals(
            listOf(ChannelId("channel-1") to parentMessageId),
            repository.getReplyThreadRequests,
            "the visible Start must join the in-flight Activity prewarm instead of issuing another request"
        )
        releaseReplyFetch.complete(Unit)
        assertTrue(prewarm.await() is DataResult.Content)
        visibleStart.await()

        val beforeParent = store.state.value.payload
        assertEquals("loading", beforeParent.originStatus)
        assertEquals(listOf("focused reply"), beforeParent.messages.map { it.body })
        assertEquals("loading", dataSource.cachedPayload("thread-m1")?.originStatus)
        releaseParent.complete(Unit)
        val loaded =
            withTimeout(1_000L) {
                store.state.map { it.payload }.first { it.originStatus == "loaded" }
            }
        assertEquals("late parent", loaded.originMessage?.body)
        assertEquals(1, repository.getMessageContextRequests.count { it == parentMessageId })
        store.accept(SharedThreadIntent.FirstContentReady)
        withTimeout(1_000L) {
            while (!logs.any { it.startsWith("thread_secondary_refresh_result") }) {
                delay(10L)
            }
        }

        assertEquals(
            listOf(ChannelId("channel-1") to parentMessageId),
            repository.getReplyThreadRequests
        )
        assertEquals(listOf(false), repository.getReplyThreadIncludeFollowedSummaries)
        assertEquals(listOf(DataRequestPriority.Background), repository.getReplyThreadPriorities)
        assertEquals("focused reply", store.state.value.payload.messages.single().body)
        assertTrue(
            logs.any {
                it.startsWith("thread_secondary_refresh_result") &&
                    it.contains("emitted=false")
            },
            "an unchanged secondary payload must not emit a second state or trigger recomposition"
        )
        scope.cancel()
        appScope.cancel()
    }

    @Test
    fun fetchThreadPayloadCarriesRepositoryJointChannelKind() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "joint-1",
                            body = "repository body",
                            conversation =
                                ConversationSummary(
                                    threadId = ThreadId("joint-1"),
                                    channelId = ChannelId("joint-1"),
                                    channelType = "joint",
                                    kind = ConversationKind.Joint,
                                    name = "partner"
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val base =
            basePayload().copy(
                sourceThreadId = "joint-1",
                streamChannelId = "joint-1",
                channelKind = "channel"
            )

        val payload = (dataSource.fetchThreadPayload(base) as DataResult.Content).data

        assertEquals(listOf(ThreadId("joint-1")), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("joint", payload.channelKind)
        assertEquals(SlockLucideIconName.GitBranch, payload.channelKind.threadChannelHeaderIcon())
        assertEquals(false, payload.showTaskOpenActions)
        assertEquals(false, payload.showTaskStatusActions)
        assertEquals(false, payload.composer.showAsTaskToggle)
        assertEquals(false, payload.supportsChannelTasks())
        assertEquals("repository body", payload.messages.single().body)
    }

    @Test
    fun fetchThreadPayloadKeepsRouteJointChannelKindOverGenericRepositoryKind() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "joint-1",
                            body = "repository body",
                            conversation =
                                ConversationSummary(
                                    threadId = ThreadId("joint-1"),
                                    channelId = ChannelId("joint-1"),
                                    channelType = "channel",
                                    kind = ConversationKind.Channel,
                                    name = "partner"
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val base =
            basePayload().copy(
                sourceThreadId = "joint-1",
                streamChannelId = "joint-1",
                channelKind = "joint"
            )

        val payload = (dataSource.fetchThreadPayload(base) as DataResult.Content).data

        assertEquals(listOf(ThreadId("joint-1")), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("joint", payload.channelKind)
        assertEquals(SlockLucideIconName.GitBranch, payload.channelKind.threadChannelHeaderIcon())
        assertEquals("repository body", payload.messages.single().body)
    }

    @Test
    fun repositoryThreadPayloadDoesNotBlockOnMemberProfileAvatarBeforeFirstRender() = runBlocking {
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            messages =
                                listOf(
                                    message(id = "m1", seq = 1, body = "agent body", authorKind = MemberKind.Agent)
                                        .copy(
                                            author =
                                                MemberProfile(
                                                    id = MemberId("agent-1"),
                                                    displayName = "Product UX",
                                                    handle = "product-ux",
                                                    kind = MemberKind.Agent,
                                                    avatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                                                )
                                        )
                                )
                        )
                    )
            )
        val memberRepository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Product UX",
                            handle = "product-ux",
                            kind = MemberKind.Agent,
                            avatar = AvatarReference.Pixel("tree")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                memberRepository = memberRepository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        val message = payload.messages.single()
        assertEquals("pixel:robot", message.avatarWireValue)
        assertEquals(emptyList(), memberRepository.serverMemberRequests)
    }

    @Test
    fun fetchThreadPayloadUsesFocusedMessageContextWhenRouteCarriesFocusedMessage() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "latest body", id = "latest", seq = 30)),
            messageContextResult =
                DataResult.Content(
                    threadSnapshot(
                        messages =
                            listOf(
                                message(threadId = "channel-1", id = "older", seq = 18, body = "older body"),
                                message(threadId = "channel-1", id = "target", seq = 19, body = "target body"),
                                message(threadId = "channel-1", id = "newer", seq = 20, body = "newer body")
                            )
                    ).copy(
                        canLoadMoreBefore = true,
                        canLoadMoreAfter = true,
                        highlightedMessageId = MessageId("target")
                    )
                )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload =
            (dataSource.fetchThreadPayload(basePayload().copy(focusedMessageId = "target")) as DataResult.Content).data

        assertEquals(listOf(MessageId("target")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(null), repository.getMessageContextChannelIdRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals(listOf("older", "target", "newer"), payload.messages.map { it.id })
        assertEquals("target", payload.focusedMessageId)
        assertTrue(payload.canLoadMoreBefore)
        assertTrue(payload.canLoadMoreAfter)
    }

    @Test
    fun fetchReplyThreadPayloadScopesFocusedMessageContextToLocalThreadChannel() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "fallback body")),
            messageContextResult =
                DataResult.Content(
                    threadSnapshot(
                        threadId = "joint-thread-local",
                        messages =
                            listOf(
                                message(
                                    threadId = "joint-thread-local",
                                    channelId = "joint-thread-local",
                                    id = "joint-reply-1",
                                    seq = 44,
                                    body = "joint reply body"
                                )
                            ),
                        conversation =
                            conversationSummary("joint-thread-local").copy(
                                kind = ConversationKind.Thread,
                                channelId = ChannelId("joint-parent-local"),
                                thread =
                                    ThreadConversation(
                                        parentChannelId = ChannelId("joint-parent-local"),
                                        parentMessageId = MessageId("parent-1"),
                                        streamChannelId = ChannelId("joint-thread-local")
                                    )
                            )
                    ).copy(highlightedMessageId = MessageId("joint-reply-1"))
                )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val route =
            basePayload().copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "joint-thread-local",
                streamChannelId = "joint-thread-local",
                parentChannelId = "joint-parent-local",
                parentMessageId = "parent-1",
                originParentMessageId = "parent-1",
                focusedMessageId = "joint-reply-1"
            )

        val payload = (dataSource.fetchThreadPayload(route) as DataResult.Content).data

        assertEquals(listOf(MessageId("joint-reply-1")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(ChannelId("joint-thread-local")), repository.getMessageContextChannelIdRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf("joint reply body"), payload.messages.map { it.body })
        assertEquals("joint-reply-1", payload.focusedMessageId)
        assertEquals("joint-thread-local", payload.sourceThreadId)
    }

    @Test
    fun focusedReplyContextKeepsRouteParentAsOrigin() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "fallback body")),
            messageContextResult =
                DataResult.Content(
                    threadSnapshot(
                        threadId = "thread-parent-1",
                        messages =
                            listOf(
                                message(
                                    threadId = "parent-channel-1",
                                    channelId = "parent-channel-1",
                                    id = "parent-1",
                                    seq = 1,
                                    body = "route parent body"
                                ),
                                message(
                                    threadId = "thread-parent-1",
                                    channelId = "thread-parent-1",
                                    id = "reply-focus-1",
                                    seq = 2,
                                    body = "focused reply body"
                                )
                            ),
                        parentMessage =
                            message(
                                threadId = "thread-parent-1",
                                channelId = "thread-parent-1",
                                id = "reply-focus-1",
                                seq = 2,
                                body = "focused reply body"
                            ),
                        conversation =
                            conversationSummary("thread-parent-1").copy(
                                kind = ConversationKind.Thread,
                                channelId = ChannelId("thread-parent-1"),
                                thread =
                                    ThreadConversation(
                                        parentChannelId = ChannelId("parent-channel-1"),
                                        parentMessageId = MessageId("parent-1"),
                                        streamChannelId = ChannelId("thread-parent-1")
                                    )
                            )
                    ).copy(highlightedMessageId = MessageId("reply-focus-1"))
                )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val route =
            basePayload().copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-parent-1",
                streamChannelId = "thread-parent-1",
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-1",
                originParentMessageId = "parent-1",
                focusedMessageId = "reply-focus-1"
            )

        val payload = (dataSource.fetchThreadPayload(route) as DataResult.Content).data

        assertEquals(listOf(MessageId("reply-focus-1")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(ChannelId("thread-parent-1")), repository.getMessageContextChannelIdRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals("parent-1", payload.parentMessageId)
        assertEquals("parent-1", payload.originParentMessageId)
        assertEquals("parent-1", payload.originMessage?.id)
        assertEquals("reply-focus-1", payload.focusedMessageId)
        assertEquals(listOf("reply-focus-1"), payload.messages.map { it.id })
    }

    @Test
    fun focusedReplyCommitsTimelineBeforeParentAndCachesLateOrigin() = runBlocking {
        val parentMessageId = MessageId("parent-1")
        val focusedMessageId = MessageId("reply-focus-1")
        val releaseParent = CompletableDeferred<Unit>()
        val parentStarted = CompletableDeferred<Unit>()
        val repository =
            FakeConversationRepository(
                messageContextResultsById =
                    mapOf(
                        focusedMessageId to
                            DataResult.Content(
                                focusedReplySnapshot(
                                    threadId = "thread-parent-1",
                                    parentChannelId = "parent-channel-1",
                                    parentMessageId = parentMessageId.value,
                                    focusedMessageId = focusedMessageId.value
                                )
                            ),
                        parentMessageId to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "parent-channel-1",
                                    messages =
                                        listOf(
                                            message(
                                                threadId = "parent-channel-1",
                                                channelId = "parent-channel-1",
                                                id = parentMessageId.value,
                                                seq = 1,
                                                body = "late parent"
                                            )
                                        )
                                )
                            )
                    )
            ).also { fake ->
                fake.messageContextStartedById[parentMessageId] = parentStarted
                fake.releaseMessageContextById[parentMessageId] = releaseParent
            }
        val messageStore = SharedThreadMessageStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        val route = focusedReplyRoute()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(route))
        withTimeout(1_000L) { parentStarted.await() }

        val beforeParent = store.state.value.payload
        assertEquals("content", beforeParent.status)
        assertEquals(listOf("reply-focus-1"), beforeParent.messages.map { it.id })
        assertEquals("loading", beforeParent.originStatus)
        assertNull(beforeParent.originMessage)

        releaseParent.complete(Unit)
        val afterParent =
            withTimeout(1_000L) {
                store.state.map { it.payload }.first { it.originStatus == "loaded" }
            }
        assertEquals(listOf("reply-focus-1"), afterParent.messages.map { it.id })
        assertEquals("late parent", afterParent.originMessage?.body)
        assertEquals(null, afterParent.errorCode)

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val secondStore = SharedThreadStore(dataSource = dataSource, scope = secondScope)
        secondStore.accept(SharedThreadIntent.Start(route.copy(focusedMessageRequestNonce = 2)))
        assertEquals("loaded", secondStore.state.value.payload.originStatus)
        assertEquals("late parent", secondStore.state.value.payload.originMessage?.body)
        assertEquals(1, repository.getMessageContextRequests.count { it == parentMessageId })
        scope.cancel()
        secondScope.cancel()
    }

    @Test
    fun focusedReplyParentAbsenceAndFailureStayTypedWithoutReplacingTimeline() = runBlocking {
        val parentMessageId = MessageId("parent-1")
        val focusedMessageId = MessageId("reply-focus-1")
        val repository =
            FakeConversationRepository(
                messageContextResult = DataResult.Empty(EmptyReason.NoMessages),
                messageContextResultsById =
                    mapOf(
                        focusedMessageId to
                            DataResult.Content(
                                focusedReplySnapshot(
                                    threadId = "thread-parent-1",
                                    parentChannelId = "parent-channel-1",
                                    parentMessageId = parentMessageId.value,
                                    focusedMessageId = focusedMessageId.value
                                )
                            )
                    )
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedThreadIntent.Start(focusedReplyRoute()))
        val missing = withTimeout(1_000L) {
            store.state.map { it.payload }.first { it.originStatus == "missing" }
        }
        assertEquals("content", missing.status)
        assertEquals(listOf("reply-focus-1"), missing.messages.map { it.id })
        assertNull(missing.originErrorCode)

        repository.messageContextResult = DataResult.Error(ScreenError("thread_parent_timeout", retryable = true))
        store.accept(SharedThreadIntent.Start(focusedReplyRoute().copy(focusedMessageRequestNonce = 2)))
        val error = withTimeout(1_000L) {
            store.state.map { it.payload }.first { it.originStatus == "error" }
        }
        assertEquals("thread_parent_timeout", error.originErrorCode)
        assertNull(error.errorCode)
        scope.cancel()
    }

    @Test
    fun focusMessageUpdatesMessageIdAndIncrementsNonce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val initial = basePayload().copy(focusedMessageId = null, focusedMessageRequestNonce = 0)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, initialPayload = initial)

        store.accept(SharedThreadIntent.FocusMessage(messageId = "msg-1", switchToTab = null))
        assertEquals("msg-1", store.state.value.payload.focusedMessageId)
        val firstNonce = store.state.value.payload.focusedMessageRequestNonce
        assertTrue(firstNonce > 0)

        store.accept(SharedThreadIntent.FocusMessage(messageId = "msg-1", switchToTab = null))
        assertEquals("msg-1", store.state.value.payload.focusedMessageId)
        val secondNonce = store.state.value.payload.focusedMessageRequestNonce
        assertTrue(secondNonce > firstNonce)

        scope.cancel()
    }

    @Test
    fun focusedOriginCompletionRejectsNewerSameRouteOriginState() {
        val loading = focusedReplyRoute()
        val newerOrigin = contentPayload("newer parent").messages.single().copy(id = "parent-1")

        assertTrue(
            loading.acceptsFocusedOriginHydrationCompletion(
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-1",
                originStatusAtStart = "loading",
                originMessageAtStart = null
            )
        )
        assertFalse(
            loading.copy(originStatus = "loaded", originMessage = newerOrigin)
                .acceptsFocusedOriginHydrationCompletion(
                    parentChannelId = "parent-channel-1",
                    parentMessageId = "parent-1",
                    originStatusAtStart = "loading",
                    originMessageAtStart = null
                )
        )
        assertFalse(
            loading.copy(originStatus = "missing")
                .acceptsFocusedOriginHydrationCompletion(
                    parentChannelId = "parent-channel-1",
                    parentMessageId = "parent-1",
                    originStatusAtStart = "loading",
                    originMessageAtStart = null
                )
        )
    }

    @Test
    fun focusedReplyParentCompletionCannotMutateLaterRoute() = runBlocking {
        val parentMessageId = MessageId("parent-1")
        val focusedMessageId = MessageId("reply-focus-1")
        val laterFocusedMessageId = MessageId("later-target")
        val releaseParent = CompletableDeferred<Unit>()
        val parentStarted = CompletableDeferred<Unit>()
        val repository =
            FakeConversationRepository(
                messageContextResultsById =
                    mapOf(
                        focusedMessageId to
                            DataResult.Content(
                                focusedReplySnapshot(
                                    threadId = "thread-parent-1",
                                    parentChannelId = "parent-channel-1",
                                    parentMessageId = parentMessageId.value,
                                    focusedMessageId = focusedMessageId.value
                                )
                            ),
                        parentMessageId to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "parent-channel-1",
                                    messages =
                                        listOf(
                                            message(
                                                threadId = "parent-channel-1",
                                                channelId = "parent-channel-1",
                                                id = parentMessageId.value,
                                                seq = 1,
                                                body = "stale parent"
                                            )
                                        )
                                )
                            ),
                        laterFocusedMessageId to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "channel-2",
                                    messages =
                                        listOf(
                                            message(
                                                threadId = "channel-2",
                                                channelId = "channel-2",
                                                id = laterFocusedMessageId.value,
                                                seq = 3,
                                                body = "later route"
                                            )
                                        ),
                                    conversation = conversationSummary("channel-2")
                                ).copy(highlightedMessageId = laterFocusedMessageId)
                            )
                    )
            ).also { fake ->
                fake.messageContextStartedById[parentMessageId] = parentStarted
                fake.releaseMessageContextById[parentMessageId] = releaseParent
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedThreadIntent.Start(focusedReplyRoute()))
        withTimeout(1_000L) { parentStarted.await() }
        store.accept(
            SharedThreadIntent.Start(
                basePayload().copy(
                    instanceId = "shared-shell-thread-channel-2",
                    sourceThreadId = "channel-2",
                    streamChannelId = "channel-2",
                    focusedMessageId = laterFocusedMessageId.value
                )
            )
        )
        releaseParent.complete(Unit)
        yield()

        val payload = store.state.value.payload
        assertEquals("channel-2", payload.sourceThreadId)
        assertEquals(listOf("later route"), payload.messages.map { it.body })
        assertEquals("none", payload.originStatus)
        assertNull(payload.originMessage)
        scope.cancel()
    }

    @Test
    fun fetchThreadPayloadFallsBackWhenFocusedMessageContextDoesNotMatchRouteThread() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "repository body")),
            messageContextResult =
                DataResult.Content(
                    threadSnapshot(
                        threadId = "channel-2",
                        messages =
                            listOf(
                                message(threadId = "channel-2", id = "target", seq = 19, body = "wrong channel body")
                            ),
                        conversation = conversationSummary("channel-2")
                    ).copy(highlightedMessageId = MessageId("target"))
                )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload =
            (dataSource.fetchThreadPayload(basePayload().copy(focusedMessageId = "target")) as DataResult.Content).data

        assertEquals(listOf(MessageId("target")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(null), repository.getMessageContextChannelIdRequests)
        assertEquals(listOf(ThreadId("channel-1")), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals(listOf("repository body"), payload.messages.map { it.body })
        assertEquals("target", payload.focusedMessageId)
    }

    @Test
    fun fetchThreadPayloadLeavesHeaderBlankWhenRepositorySnapshotTitleIsIdentity() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                currentMember = member("u1", "Artin"),
                channels =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            channelType = "public",
                            kind = ConversationKind.Channel,
                            name = "all",
                            subtitle = "General updates",
                            memberCount = 4
                        ).toHomeChannelRow()
                    ),
                directMessages = emptyList(),
                unreadTotal = 0
            )
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "repository body",
                            conversation = conversationSummary("channel-1").copy(name = "channel-1")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        // fb845a19 removed the durable HomeSnapshot cache: identity titles can
        // no longer be replaced from the local home lookup, so the payload
        // header stays blank and display resolution happens in
        // threadHeaderDisplayTitle. Home-derived subtitle/membersCount
        // enrichment is gone with its source.
        assertEquals("", payload.title)
        assertEquals("Channel", threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals("repository body", payload.messages.single().body)
    }

    @Test
    fun fetchReplyThreadPayloadUsesParentPairRepositoryPath() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
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
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val base =
            basePayload().copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
                parentChannelKind = KuiklyThreadSurfaceKind.Channel,
                originParentMessageId = "m1",
                originMessage = contentPayload("parent body").messages.single(),
                composer =
                    buildThreadSurfaceComposerPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = "m1",
                        enabled = true,
                        mentionChannelId = "channel-1",
                        parentMessageId = "m1",
                        streamChannelId = "channel-1"
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base) as DataResult.Content).data

        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, payload.parentChannelKind)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("reply body", payload.messages.single().body)
        assertEquals("parent body", payload.originMessage?.body)
    }

    @Test
    fun visibleReplyMetadataSeedsSharedStoreForDirectReplyThreadFetch() = runBlocking {
        val messageStore = SharedThreadMessageStore()
        val parent = contentPayload("parent body").messages.single()
        val seedDataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        val provenance = requireNotNull(seedDataSource.captureReplyCacheProducerContext()).provenance
        seedDataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-1",
            parentChannelId = "channel-1",
            parentMessage = parent,
            threadChannelId = "thread-m1"
        )
        val repository =
            FakeConversationRepository(
                threadResultsById =
                    mapOf(
                        ThreadId("thread-m1") to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "thread-m1",
                                    messages =
                                        listOf(
                                            message(
                                                threadId = "thread-m1",
                                                id = "reply-1",
                                                seq = 2,
                                                body = "reply body"
                                            )
                                        ),
                                    parentMessage = null,
                                    conversation =
                                        conversationSummary("thread-m1").copy(
                                            kind = ConversationKind.Thread,
                                            channelId = ChannelId("thread-m1"),
                                            thread =
                                                ThreadConversation(
                                                    parentChannelId = ChannelId("channel-1"),
                                                    parentMessageId = MessageId("m1"),
                                                    streamChannelId = ChannelId("thread-m1")
                                                )
                                        )
                                )
                            )
                    )
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        val base =
            basePayload().copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                originMessage = null,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getMessageContextRequests)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("loaded", payload.originStatus)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
    }

    @Test
    fun replyParentContextStoreDoesNotReuseSameUuidAcrossPrincipals() = runBlocking {
        val messageStore = SharedThreadMessageStore()
        val environmentId = "https://prod.example.test"
        val accountOneDataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(replyResolutionSession("account-1")),
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { replyResolutionSession("account-1") },
                    environmentProvider = { environmentId },
                )
            )
        val provenance = requireNotNull(accountOneDataSource.captureReplyCacheProducerContext()).provenance
        accountOneDataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-a",
            parentChannelId = "channel-1",
            parentMessage = contentPayload("account one parent").messages.single(),
            threadChannelId = "thread-m1"
        )

        val accountTwoParent = message(threadId = "channel-1", id = "m1", seq = 1, body = "account two parent")
        val accountTwoRepository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(
                                        threadId = "thread-m1",
                                        id = "reply-1",
                                        seq = 2,
                                        body = "account two reply"
                                    )
                                ),
                            parentMessage = null,
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(accountTwoParent),
                            parentMessage = accountTwoParent
                        )
                    )
            )
        val accountTwoDataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(replyResolutionSession("account-2")),
                conversationRepository = accountTwoRepository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { replyResolutionSession("account-2") },
                    environmentProvider = { environmentId },
                )
            )
        val route =
            activityReplyPayload(
                parentKind = ConversationKind.Channel,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                threadId = "thread-m1"
            )

        val payload =
            (accountTwoDataSource.fetchThreadPayload(route, persistResult = false) as DataResult.Content).data

        assertTrue(MessageId("m1") in accountTwoRepository.getMessageContextRequests)
        assertEquals("account two parent", payload.originMessage?.body)
        assertEquals(listOf("account two reply"), payload.messages.map { it.body })
    }

    @Test
    fun replyParentCandidateRejectsStaleProducerAfterIdentityPublication() = runBlocking {
        val environmentId = "https://prod.example.test"
        val authStore = InMemorySlockAuthSessionStore(replyResolutionSession("account-1"))
        val messageStore = SharedThreadMessageStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = authStore,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = authStore::readSession,
                    environmentProvider = { environmentId },
                )
            )
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        authStore.saveSession(
            replyResolutionSession("account-2").copy(selectedServerId = "server-b")
        )
        dataSource.readSession()

        val accepted = dataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-a",
            parentChannelId = "channel-1",
            parentMessage = contentPayload("account one parent").messages.single(),
            threadChannelId = "thread-m1"
        )

        assertFalse(accepted)
        assertNull(
            messageStore.getScoped(
                "$environmentId\u001faccount-1\u001fserver-a\u001f1",
                ChannelId("channel-1"),
                MessageId("m1")
            )
        )
        assertNull(
            messageStore.getScoped(
                "$environmentId\u001faccount-2\u001fserver-b\u001f2",
                ChannelId("channel-1"),
                MessageId("m1")
            )
        )
        assertNull(
            messageStore.getScoped(
                "$environmentId\u001faccount-1\u001fserver-b\u001f1",
                ChannelId("channel-1"),
                MessageId("m1")
            )
        )
        assertNull(
            messageStore.getScoped(
                "$environmentId\u001faccount-2\u001fserver-a\u001f2",
                ChannelId("channel-1"),
                MessageId("m1")
            )
        )
    }

    @Test
    fun resolvedParentPairSeedsReplyThreadResolveCache() = runBlocking {
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
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
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                cacheEnvironmentIdProvider = { "https://prod.example.test" }
            )
        val parent = contentPayload("parent body").messages.single()
        val unresolvedBase =
            unresolvedReplyPayload(parentMessageId = "m1").copy(originMessage = parent)

        val firstPayload = (dataSource.fetchThreadPayload(unresolvedBase, persistResult = false) as DataResult.Content).data
        val openedPayload = (dataSource.createOrOpenReplyThread(contentPayload("parent body"), parent) as DataResult.Content).data

        assertEquals("thread-m1", firstPayload.streamChannelId)
        assertEquals("thread-m1", openedPayload.streamChannelId)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(
            listOf(ChannelId("channel-1") to MessageId("m1")),
            repository.getReplyThreadRequests
        )
        assertEquals(listOf(false), repository.getReplyThreadIncludeFollowedSummaries)
        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
    }

    @Test
    fun replyThreadResolutionCacheIsScopedByEnvironmentPrincipalAndRouteServer() = runBlocking {
        val messageStore = SharedThreadMessageStore()
        val parent = contentPayload("parent body").messages.single()
        val base = contentPayload("parent body")
        var environmentId = " https://prod.example.test/ "
        val authStore = InMemorySlockAuthSessionStore(replyResolutionSession("account-1"))
        val selectedServerStore =
            SelectedServerStore(
                SlockShellStoreScope.create(
                    shellInstanceId = "reply-resolution-namespace",
                    initialServerId = "server-a"
                )
            )
        val repository = FakeConversationRepository()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = authStore::readSession,
                    environmentProvider = { environmentId },
                    serverIdProvider = { _ -> selectedServerStore.selectedServerId.value },
                )
            )

        repository.configureReplyThread("thread-a", parent.id)
        assertEquals(
            "thread-a",
            (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )

        environmentId = ""
        repository.configureReplyThread("thread-missing-environment", parent.id)
        // A blank environment is not a "legacy" identity that may reuse the prod bucket: it owns
        // no reply cache at all (namespace and payload owner both absent), so the open fails
        // closed and nothing was cached.
        assertEquals(
            "thread_reply_cache_provenance_stale",
            assertIs<DataResult.Error>(dataSource.createOrOpenReplyThread(base, parent)).error.code
        )
        val blankEnvironmentProvenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        assertEquals("", blankEnvironmentProvenance.environmentId)
        assertNull(blankEnvironmentProvenance.namespaceId)
        assertNull(blankEnvironmentProvenance.threadPayloadOwnerScopeId)
        assertNull(dataSource.cachedPayload("thread-missing-environment"))

        environmentId = "https://prod.example.test"
        selectedServerStore.select("server-b", "server-b")
        repository.configureReplyThread("thread-b", parent.id)
        assertEquals(
            "thread-b",
            (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )

        authStore.saveSession(replyResolutionSession("account-2"))
        repository.configureReplyThread("thread-account-2", parent.id)
        val accountTwoDataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = authStore::readSession,
                    environmentProvider = { environmentId },
                    serverIdProvider = { _ -> selectedServerStore.selectedServerId.value },
                )
            )
        assertEquals(
            "thread-account-2",
            (accountTwoDataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )

        environmentId = "https://staging.example.test/"
        repository.configureReplyThread("thread-staging", parent.id)
        val stagingDataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = authStore::readSession,
                    environmentProvider = { environmentId },
                    serverIdProvider = { _ -> selectedServerStore.selectedServerId.value },
                )
            )
        assertEquals(
            "thread-staging",
            (stagingDataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )

        assertEquals(
            List(5) { ChannelId("channel-1") to MessageId(parent.id) },
            repository.createOrOpenRequests
        )
    }

    @Test
    fun replyThreadResolutionRejectsOwnerChangedWhileCreateOrOpenSuspends() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(replyResolutionSession("account-1"))
        val selectedServerStore =
            SelectedServerStore(
                SlockShellStoreScope.create(
                    shellInstanceId = "reply-resolution-in-flight-identity",
                    initialServerId = "server-a"
                )
            )
        val repository = FakeConversationRepository()
        val createStarted = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        repository.configureReplyThread("thread-a", "m1")
        repository.createOrOpenStarted = createStarted
        repository.releaseCreateOrOpen = releaseCreate
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                conversationRepository = repository,
                messageStore = SharedThreadMessageStore(),
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = authStore::readSession,
                    serverIdProvider = { _ -> selectedServerStore.selectedServerId.value },
                )
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val serverAResult = async { dataSource.createOrOpenReplyThread(base, parent) }
        createStarted.await()
        selectedServerStore.select("server-b", "server-b")
        dataSource.readSession()
        releaseCreate.complete(Unit)
        assertEquals(
            "thread_reply_access_authority_changed",
            assertIs<DataResult.Error>(serverAResult.await()).error.code
        )

        repository.createOrOpenStarted = null
        repository.releaseCreateOrOpen = null
        repository.configureReplyThread("thread-b", parent.id)
        assertEquals(
            "thread-b",
            (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )

        selectedServerStore.select("server-a", "server-a")
        dataSource.readSession()
        repository.configureReplyThread("thread-a-fresh", parent.id)
        assertEquals(
            "thread-a-fresh",
            (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )
        assertEquals(
            List(3) { ChannelId("channel-1") to MessageId(parent.id) },
            repository.createOrOpenRequests
        )
    }

    @Test
    fun fetchReplyThreadPayloadFiltersParentChannelMessagesFromSnapshot() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(threadId = "channel-1", id = "outer-before", seq = 1, body = "outer before"),
                                    message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body"),
                                    message(threadId = "channel-1", id = "outer-after", seq = 3, body = "outer after")
                                ),
                            parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body"),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val base =
            basePayload().copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1"
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base) as DataResult.Content).data

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals("parent body", payload.originMessage?.body)
    }

    @Test
    fun fetchReplyThreadPayloadUsesOriginParentMessageFallbackForParentPair() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
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
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val base =
            basePayload().copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = null,
                originParentMessageId = "m1"
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base) as DataResult.Content).data

        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("reply body", payload.messages.single().body)
        assertEquals("parent body", payload.originMessage?.body)
    }

    @Test
    fun fetchReplyThreadPayloadVerifiesStreamHintBeforeEmitting() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResultsById =
                    mapOf(
                        ThreadId("wrong-thread") to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "wrong-thread",
                                    body = "wrong reply body",
                                    id = "wrong-reply",
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
                            ),
                        ThreadId("thread-m1") to
                            DataResult.Content(
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
                            )
                    ),
                createThreadResult =
                    DataResult.Content(
                        ThreadActionResult(
                            threadChannelId = ChannelId("thread-m1"),
                            changed = false,
                            parentMessageId = MessageId("m1"),
                            replyCount = 2
                        )
                    )
            )
        val base =
            basePayload().copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "wrong-thread",
                streamChannelId = "wrong-thread",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1"
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals(ThreadReplyPageTitle, threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
    }

    @Test
    fun fetchReplyThreadPayloadKeepsResolvedEmptyThreadRenderable() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResultsById =
                    mapOf(
                        ThreadId("wrong-thread") to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "wrong-thread",
                                    body = "wrong reply body",
                                    id = "wrong-reply",
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
                            ),
                        ThreadId("thread-m1") to DataResult.Empty(EmptyReason.NoMessages)
                    ),
                createThreadResult =
                    DataResult.Content(
                        ThreadActionResult(
                            threadChannelId = ChannelId("thread-m1"),
                            changed = false,
                            parentMessageId = MessageId("m1"),
                            replyCount = 0
                        )
                    )
            )
        val base =
            basePayload().copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "wrong-thread",
                streamChannelId = "wrong-thread",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                originStatus = "loading"
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("empty", payload.status)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals(emptyList(), payload.messages)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals(null, payload.errorCode)
    }

    @Test
    fun fetchReplyThreadPayloadNormalizesLoadedOriginWithNoRepliesToEmptyState() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = emptyList(),
                            parentMessage =
                                message(
                                    threadId = "channel-1",
                                    id = "m1",
                                    seq = 10,
                                    body = "parent body"
                                ),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("channel-1"),
                                    name = "thread-m1",
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val base =
            basePayload().copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                originStatus = "loading"
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("empty", payload.status)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("loaded", payload.originStatus)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(emptyList(), payload.messages)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
    }

    @Test
    fun startSkipsMismatchedReplyThreadMemoryCacheBeforeResolvedFetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = replyHintRepository()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        dataSource.cachePayloadForTest(wrongReplyCachedPayload())
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val observedPayloads = mutableListOf<KuiklyThreadScreenPayload>()
        val observer = scope.launch {
            store.state.collect { state -> observedPayloads += state.payload }
        }

        store.accept(SharedThreadIntent.Start(replyRouteWithWrongHint()))

        val payload = store.state.value.payload
        assertFalse(observedPayloads.any { it.containsWrongReplyCacheContent() })
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("", payload.title)
        assertEquals(ThreadReplyPageTitle, threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        observer.cancel()
        scope.cancel()
    }

    @Test
    fun startSkipsMismatchedReplyThreadPersistentCacheBeforeResolvedFetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(wrongReplyThreadSnapshot())
        val repository = replyHintRepository()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val observedPayloads = mutableListOf<KuiklyThreadScreenPayload>()
        val observer = scope.launch {
            store.state.collect { state -> observedPayloads += state.payload }
        }

        store.accept(SharedThreadIntent.Start(replyRouteWithWrongHint()))

        val payload = store.state.value.payload
        assertFalse(observedPayloads.any { it.containsWrongReplyCacheContent() })
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("", payload.title)
        assertEquals(ThreadReplyPageTitle, threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        observer.cancel()
        scope.cancel()
    }

    @Test
    fun startSkipsMismatchedReplyThreadCacheWhenResolutionFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(wrongReplyThreadSnapshot())
        val repository =
            replyHintRepository(
                threadResult = DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true)),
                createThreadResult =
                    DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        dataSource.cachePayloadForTest(wrongReplyCachedPayload())
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val observedPayloads = mutableListOf<KuiklyThreadScreenPayload>()
        val observer = scope.launch {
            store.state.collect { state -> observedPayloads += state.payload }
        }

        store.accept(SharedThreadIntent.Start(replyRouteWithWrongHint()))

        val payload = store.state.value.payload
        assertFalse(observedPayloads.any { it.containsWrongReplyCacheContent() })
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals("error", payload.status)
        assertEquals("thread_reply_network", payload.errorCode)
        assertEquals(emptyList(), payload.messages)
        assertEquals(null, payload.originMessage)
        observer.cancel()
        scope.cancel()
    }

    @Test
    fun startWithUnresolvedActivityReplyThreadCreatesStreamBeforeLoadingReplies() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
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
                    ),
                createThreadResult =
                    DataResult.Content(
                        ThreadActionResult(
                            threadChannelId = ChannelId("thread-m1"),
                            changed = false,
                            parentMessageId = MessageId("m1"),
                            replyCount = 2
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val request =
            InboxItem(
                key = "thread:m1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-compat"),
                channelName = "thread stream hint",
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("m1"),
                parentMessagePreview = "parent body",
                latestActivityMessageId = MessageId("reply-1")
            ).toHomeMessageOpenRequestForTest()
        val initialPayload =
            requireNotNull(
                buildAppShellThreadPayload(request.conversationIdentity, anchorMessageId = request.anchorMessageId)
            )
                .copy(
                    title = "stale launch title",
                    subtitle = "#stale",
                    originMessage =
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "stale-user",
                            authorName = "Stale",
                            body = "stale launch parent"
                        ),
                    originPreview = "stale launch preview",
                    originStatus = "loaded"
                )

        store.accept(SharedThreadIntent.Start(initialPayload))

        val payload = store.state.value.payload
        assertEquals(null, initialPayload.sourceThreadId)
        assertEquals(null, initialPayload.streamChannelId)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("reply-1", payload.focusedMessageId)
        assertEquals(ThreadReplyPageTitle, threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        scope.cancel()
    }

    @Test
    fun startWithUnresolvedEmptyReplyThreadKeepsFirstReplyPanelOpen() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Empty(EmptyReason.NoMessages),
                createThreadResult =
                    DataResult.Content(
                        ThreadActionResult(
                            threadChannelId = ChannelId("thread-m1"),
                            changed = false,
                            parentMessageId = MessageId("m1"),
                            replyCount = 0
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val initialPayload =
            basePayload().copy(
                instanceId = "shared-shell-thread-pending-m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = null,
                streamChannelId = null,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                title = ThreadReplyPageTitle,
                showModeTabs = false,
                originMessage = contentPayload("parent body").messages.single(),
                originStatus = "loaded",
                messages = emptyList(),
                composer =
                    buildThreadSurfaceComposerPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = "m1",
                        enabled = true,
                        mentionChannelId = "channel-1",
                        parentMessageId = "m1",
                        streamChannelId = null
                    )
            )

        store.accept(SharedThreadIntent.Start(initialPayload))

        val payload = store.state.value.payload
        assertEquals("empty", payload.status)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.resolveThreadComposer().streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("loaded", payload.originStatus)
        assertEquals(emptyList(), payload.messages)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals(null, payload.errorCode)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        scope.cancel()
    }

    @Test
    fun startWithZeroReplyTaskDoesNotWaitIndefinitelyForOriginContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Empty(EmptyReason.NoMessages),
                messageContextDelayMillis = 5_000L,
                createThreadResult =
                    DataResult.Content(
                        ThreadActionResult(
                            threadChannelId = ChannelId("thread-task-600"),
                            changed = true,
                            parentMessageId = MessageId("task-message-600"),
                            replyCount = 0
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                diagnosticsLogger = diagnostics::add
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = diagnostics::add
            )
        val initialPayload =
            basePayload().replyThreadLoadingPayload(
                parentMessageId = "task-message-600",
                parentChannelId = "channel-1",
                threadChannelId = null
            ).copy(originStatus = "loading")

        withTimeout(2_000L) {
            store.accept(SharedThreadIntent.Start(initialPayload))
        }

        val payload = store.state.value.payload
        assertEquals("empty", payload.status)
        assertEquals("thread-task-600", payload.sourceThreadId)
        assertEquals("thread-task-600", payload.streamChannelId)
        assertEquals("thread-task-600", payload.resolveThreadComposer().streamChannelId)
        assertEquals("loading", payload.originStatus)
        assertEquals(null, payload.originMessage)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals(
            listOf(ChannelId("channel-1") to MessageId("task-message-600")),
            repository.getReplyThreadRequests
        )
        assertEquals(listOf(MessageId("task-message-600")), repository.getMessageContextRequests)
        assertTrue(diagnostics.any { it.startsWith("thread_parent_context_grace_expired ") })
        assertTrue(
            diagnostics.any {
                it.contains("thread_refresh_result") &&
                    it.contains("threadId=") &&
                    it.contains("result=content") &&
                    it.contains("originStatus=loading")
            }
        )
        scope.cancel()
    }

    @Test
    fun emptyInitialLoadStaysLoadingUntilCanonicalFetchCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository =
            FakeConversationRepository(
                threadDelayMillis = 100L,
                threadResult =
                    DataResult.Content(
                        threadSnapshot(messages = listOf(message(id = "m1", seq = 1, body = "late content")))
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope
            )

        store.dispatch(SharedThreadIntent.Start(basePayload()))

        delay(30L)
        assertEquals("loading", store.state.value.payload.status)
        assertEquals(emptyList(), store.state.value.payload.messages)
        assertEquals(null, store.state.value.payload.errorCode)

        withTimeout(1_000L) {
            while (store.state.value.payload.messages.singleOrNull()?.id != "m1") {
                delay(5L)
            }
        }
        assertEquals("content", store.state.value.payload.status)
        assertEquals(null, store.state.value.payload.errorCode)
        assertEquals("late content", store.state.value.payload.messages.single().body)
        scope.cancel()
    }

    @Test
    fun warmContentStaysVisibleDuringSlowRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "cached content"))
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope
            )

        store.accept(SharedThreadIntent.Start(basePayload()))
        repository.threadDelayMillis = 100L
        repository.threadResult = DataResult.Content(threadSnapshot(body = "refreshed content"))
        store.dispatch(SharedThreadIntent.Refresh)

        delay(30L)
        assertEquals("content", store.state.value.payload.status)
        assertEquals("cached content", store.state.value.payload.messages.single().body)
        assertEquals(null, store.state.value.payload.errorCode)

        withTimeout(1_000L) {
            while (store.state.value.payload.messages.singleOrNull()?.body != "refreshed content") {
                delay(5L)
            }
        }
        assertEquals("content", store.state.value.payload.status)
        assertEquals(null, store.state.value.payload.errorCode)
        scope.cancel()
    }

    @Test
    fun refreshWithUnchangedSnapshotKeepsTimelineList() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            messages =
                                listOf(
                                    message(id = "m1", seq = 1, body = "first"),
                                    message(id = "m2", seq = 2, body = "second")
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val beforeMessages = store.state.value.payload.messages
        val observedStates = mutableListOf<SharedThreadState>()
        val observer = scope.launch {
            store.state.collect { state -> observedStates += state }
        }
        val countBeforeRefresh = observedStates.size

        repository.threadResult =
            DataResult.Content(
                threadSnapshot(
                    messages =
                        listOf(
                            message(id = "m1", seq = 1, body = "first"),
                            message(id = "m2", seq = 2, body = "second")
                        )
                )
            )
        store.accept(SharedThreadIntent.Refresh)

        assertTrue(observedStates.size >= countBeforeRefresh)
        assertTrue(beforeMessages === store.state.value.payload.messages)
        observer.cancel()
        scope.cancel()
    }

    @Test
    fun refreshSummaryPatchOnlyReplacesAffectedMessageRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            messages =
                                listOf(
                                    message(id = "m1", seq = 1, body = "first"),
                                    message(id = "m2", seq = 2, body = "second")
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val beforeMessages = store.state.value.payload.messages

        repository.threadResult =
            DataResult.Content(
                threadSnapshot(
                    messages =
                        listOf(
                            message(id = "m1", seq = 1, body = "first"),
                            message(
                                id = "m2",
                                seq = 2,
                                body = "second",
                                threadChannelId = "thread-m2",
                                replyCount = 2,
                                unreadReplyCount = 1
                            )
                        )
                )
            )
        store.accept(SharedThreadIntent.Refresh)

        val afterMessages = store.state.value.payload.messages
        assertEquals(listOf("m1", "m2"), afterMessages.map { it.id })
        assertTrue(beforeMessages !== afterMessages)
        assertTrue(beforeMessages[0] === afterMessages[0])
        assertTrue(beforeMessages[1] !== afterMessages[1])
        assertEquals("thread-m2", afterMessages[1].threadChannelId)
        assertEquals(2, afterMessages[1].replyCount)
        assertEquals(1, afterMessages[1].unreadReplyCount)
        scope.cancel()
    }

    @Test
    fun restThreadFetchRendersPersistedTaskLifecycleSystemRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(
                    bodyText =
                        systemMessagesResponse(
                            body = "✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done",
                            id = "sys-done-1",
                            seq = 44
                        )
                ),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val systemRow = store.state.value.payload.messages.single()
        assertEquals("sys-done-1", systemRow.id)
        assertEquals("System", systemRow.messageType)
        assertEquals("system", systemRow.authorKind)
        assertEquals("✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done", systemRow.body)
        scope.cancel()
    }

    @Test
    fun realtimeMergeWarmsFirstFrameCacheWithLiveStoreSnapshot() = runBlocking {
        // task #562 contract (KMP-专家/HanXin agreed): the first-frame cache is
        // a read-only snapshot of the LIVE STORE's merged state, written after
        // the reduce — one merge algorithm, no cache-side event logic. Active
        // conversations stay warm on re-entry; every open still refreshes.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "realtime body"
                    )
                )
            )
        )

        val payload = store.state.value.payload
        assertEquals(listOf("cached body", "realtime body"), payload.messages.map { it.body })
        // Cache == what the user is looking at: the post-merge store snapshot.
        assertEquals(
            listOf("cached body", "realtime body"),
            dataSource.cachedPayload("channel-1")?.messages?.map { it.body }
        )
        // Durable local-store never rides the first-frame cache path.
        assertNull(localStore.readThread(ThreadId("channel-1")))
        scope.cancel()
    }

    @Test
    fun noPermissionRefreshInvalidatesFirstFrameCache() = runBlocking {
        // task #562 boundary: invalidation is reserved for unrecoverable
        // scenarios — the canonical fetch reporting permission loss drops the
        // candidate so a revoked thread can never seed a first frame again.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(channelMessagesResponseCode = 403),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        assertNull(dataSource.cachedPayload("channel-1"))
        assertEquals(ThreadAccessResolution.NoPermission, store.state.value.accessResolution)
        assertEquals("no_permission", store.state.value.payload.status)
        assertEquals("thread_forbidden", store.state.value.payload.errorCode)
        assertTrue(store.state.value.payload.messages.isEmpty())
        scope.cancel()
    }

    @Test
    fun realtimeForOtherThreadLeavesThisThreadsFirstFrameCacheIntact() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m-other"),
                        threadId = ThreadId("channel-other"),
                        channelId = ChannelId("channel-other"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "unrelated realtime body"
                    )
                )
            )
        )

        // No projection for this thread → no merge, no invalidation.
        assertEquals(listOf("cached body"), store.state.value.payload.messages.map { it.body })
        assertEquals(
            "cached body",
            dataSource.cachedPayload("channel-1")?.messages?.single()?.body
        )
        scope.cancel()
    }

    @Test
    fun realtimeMessageNewMergesVisiblePayloadWhenMemoryCacheMissesEvent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "realtime body"
                    )
                )
            )
        )

        assertEquals(listOf("cached body", "realtime body"), store.state.value.payload.messages.map { it.body })
        scope.cancel()
    }

    @Test
    fun realtimeMessageNewAcrossContextWindowNewerGapSyncsMissingWindow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("gap body", id = "m11", seq = 11)
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(id = "m12", seq = 12, body = "latest across gap")
                )
            )
        )

        val payload = store.state.value.payload
        assertEquals(listOf("m10", "m11"), payload.messages.map { it.id })
        assertEquals(listOf("current body", "gap body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun localSendAdmissionClearsFocusedNewerContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi =
                    FakeThreadApi(
                        bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false)
                    ),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(focusedMessageId = "m10")))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))

        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-prepare", "send body")
        )

        val payload = store.state.value.payload
        assertEquals(null, payload.focusedMessageId)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun realtimeMessageNewKeepsAvatarFieldsOutOfCachedSenderProfileMerge() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                currentMember = member("u1", "Artin"),
                channels = emptyList(),
                directMessages =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("dm-agent-1"),
                            channelId = ChannelId("dm-agent-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "Product-UX-Designer",
                            presenceMemberId = MemberId("agent-1")
                        ).toHomeDirectMessageRow()
                    ),
                unreadTotal = 0
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author =
                            MemberProfile(
                                id = MemberId("agent-1"),
                                displayName = "Product-UX-Designer",
                                handle = "Product-UX-Designer",
                                kind = MemberKind.Agent,
                                presence = Presence.Offline
                            ),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "realtime agent body"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single { it.id == "m2" }
        assertEquals("pixel:robot", message.avatarWireValue)
        assertNull(localStore.readThread(ThreadId("channel-1")))
        // task #562: cache snapshot carries the same resolved avatar fields.
        val cachedMessage = dataSource.cachedPayload("channel-1")?.messages?.single { it.id == "m2" }
        assertEquals("pixel:robot", cachedMessage?.avatarWireValue)
        scope.cancel()
    }

    @Test
    fun realtimeThreadUpdatedAppliesReplyMetadataToDirectMessageParent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        dataSource.cachePayloadForTest(
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = directMessagePayload(),
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "dm-parent-1",
                                    channelId = "dm-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    seq = 1,
                                    content = "dm parent"
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    ),
                session = sampleSession()
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(directMessagePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("dm-parent-1"),
                    threadChannelId = ChannelId("dm-thread-1"),
                    replyCount = 1,
                    lastReplyAt = "2026-05-19T16:01:00Z",
                    participantIds = listOf(MemberId("u1"), MemberId("agent-1")),
                    unreadCount = 1,
                    firstUnreadMessageId = MessageId("dm-reply-1")
                )
            )
        )

        val parent = store.state.value.payload.messages.single { it.id == "dm-parent-1" }
        assertEquals("dm-thread-1", parent.threadChannelId)
        assertEquals(1, parent.replyCount)
        assertEquals(1, parent.unreadReplyCount)
        assertEquals("dm-reply-1", parent.firstUnreadReplyMessageId)
        scope.cancel()
    }

    @Test
    fun realtimeMessageNewRendersTaskLifecycleSystemRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        val cachedPayload =
            contentPayload("cached body").let { content ->
                content.copy(messages = content.messages.map { message -> message.copy(seq = 43L) })
            }
        dataSource.cachePayloadForTest(cachedPayload)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "sys-done-1",
                        seq = 44,
                        body = "✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done",
                        messageType = MessageType.System,
                        authorKind = MemberKind.System
                    )
                )
            )
        )

        val messages = store.state.value.payload.messages
        assertEquals(listOf("m1", "sys-done-1"), messages.map { it.id })
        val systemRow = messages.last()
        assertEquals("System", systemRow.messageType)
        assertEquals("system", systemRow.authorKind)
        assertEquals("✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done", systemRow.body)
        scope.cancel()
    }

    @Test
    fun realtimeLocalWriteFailureDoesNotRollbackVisiblePayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val backingStore = InMemoryComposeLocalDataStore()
        backingStore.writeThread(threadSnapshot(body = "cached body"))
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "realtime body"
                    )
                )
            )
        )

        assertEquals(listOf("cached body", "realtime body"), store.state.value.payload.messages.map { it.body })
        assertEquals(listOf("cached body"), backingStore.readThread(ThreadId("channel-1"))?.messages?.map { it.body })
        // task #562: the first-frame cache mirrors the visible merged state.
        assertEquals(listOf("cached body", "realtime body"), dataSource.cachedPayload("channel-1")?.messages?.map { it.body })
        assertFalse(logs.any { it.startsWith("thread_realtime_persist_failed") })
        scope.cancel()
    }

    @Test
    fun irrelevantRealtimeMessageDoesNotChangeVisibleThread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload()))
        dataSource.cachePayloadForTest(store.state.value.payload)
        val before = store.state.value
        logs.clear()

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("other"),
                        threadId = ThreadId("other-channel"),
                        channelId = ChannelId("other-channel"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "irrelevant"
                    )
                )
            )
        )

        assertTrue(before === store.state.value)
        assertEquals(listOf("cached body"), store.state.value.payload.messages.map { it.body })
        assertTrue(logs.any { it.contains("reason=no_projection") })
        assertFalse(logs.any { it.startsWith("thread_realtime_cache_result") })
        scope.cancel()
    }

    @Test
    fun payloadSelectorDoesNotEmitForMembersLoadStatusOnlyChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        val payloadEvents = mutableListOf<KuiklyThreadScreenPayload>()
        val job = launch(Dispatchers.Unconfined) {
            store.payloadFlow().collect { payload -> payloadEvents += payload }
        }
        assertEquals(emptyList(), payloadEvents.single().messages)
        assertEquals(listOf("cached body"), store.state.value.timelineSlice.messages.map { it.body })
        payloadEvents.clear()

        store.accept(SharedThreadIntent.LoadMembers)

        assertEquals(LoadMembersStatus.Loaded, store.state.value.membersLoadStatus)
        assertEquals(emptyList(), payloadEvents.toList())
        job.cancel()
        scope.cancel()
    }

    @Test
    fun timelineSelectorDoesNotEmitForMembersOnlyPayloadChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        val timelineEvents = mutableListOf<ThreadTimelineSlice>()
        val job = launch(Dispatchers.Unconfined) {
            store.timelineSliceFlow().collect { slice -> timelineEvents += slice }
        }
        assertEquals(listOf("cached body"), timelineEvents.single().messages.map { it.body })
        timelineEvents.clear()

        store.accept(
            SharedThreadIntent.ApplyMembersPatch(
                membersCount = 1,
                members = listOf(KuiklyThreadMemberItem(id = "u2", displayName = "Member 2")),
                memberCandidates = listOf(KuiklyThreadMemberItem(id = "u3", displayName = "Candidate 3")),
                canAddMembers = true
            )
        )

        assertEquals(1, store.state.value.payload.membersCount)
        assertEquals(listOf("u2"), store.state.value.payload.members.map { it.id })
        assertEquals(emptyList(), timelineEvents.toList())
        job.cancel()
        scope.cancel()
    }

    @Test
    fun timelineSelectorEmitsForMessageChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult =
                            DataResult.Content(
                                threadSnapshot(
                                    body = "cached body",
                                    conversation = conversationSummary().copy(joined = true)
                                )
                            )
                    )
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        val timelineEvents = mutableListOf<ThreadTimelineSlice>()
        val job = launch(Dispatchers.Unconfined) {
            store.timelineSliceFlow().collect { slice -> timelineEvents += slice }
        }
        timelineEvents.clear()

        store.startLocalSendForCurrentOwner(
            dataSource,
            contentPayload("local send", id = "local-1").messages.single()
        )

        assertEquals(listOf("cached body", "local send"), timelineEvents.single().messages.map { it.body })
        job.cancel()
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdatedPreservesExistingAuthorMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u1", "u1"),
                        timestampLabel = "",
                        body = "edited body"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals("edited body", message.body)
        assertEquals("Artin", message.authorName)
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdatedCannotRewriteCreationTimeOrTimelineOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        val first =
            contentPayload("first", id = "m1").messages.single().copy(
                seq = 30,
                timestampLabel = "2026-07-28T19:30:00+08:00"
            )
        val second =
            first.copy(
                id = "m2",
                seq = 31,
                body = "second",
                timestampLabel = "2026-07-28T19:31:00+08:00"
            )
        dataSource.cachePayloadForTest(contentPayload("first").copy(messages = listOf(first, second)))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        seq = 30,
                        author = member("u1", "Artin"),
                        timestampLabel = "2026-07-28T19:32:00+08:00",
                        updatedAtLabel = "2026-07-28T19:32:00+08:00",
                        body = "first updated"
                    )
                )
            )
        )

        assertEquals(listOf("m1", "m2"), store.state.value.payload.messages.map { it.id })
        assertEquals(
            listOf("2026-07-28T19:30:00+08:00", "2026-07-28T19:31:00+08:00"),
            store.state.value.payload.messages.map { it.timestampLabel }
        )
        assertEquals("first updated", store.state.value.payload.messages.first().body)
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdatedCannotReplaceCanonicalAuthorIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "u2"),
                        timestampLabel = "",
                        body = "edited body"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals("edited body", message.body)
        assertEquals("u1", message.authorId)
        assertEquals("Artin", message.authorName)
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdatedCanHydrateUnknownAuthorIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        val cachedPayload = contentPayload("cached body")
        dataSource.cachePayloadForTest(
            cachedPayload.copy(
                messages =
                    listOf(
                        cachedPayload.messages.single().copy(
                            authorId = "unknown",
                            authorName = "Unknown"
                        )
                    )
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Aiden"),
                        timestampLabel = "",
                        body = "edited body"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals("u2", message.authorId)
        assertEquals("Aiden", message.authorName)
        scope.cancel()
    }

    @Test
    fun realtimeParentOriginUpdateDoesNotAppendToReplyThreadTimeline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        val route =
            basePayload().copy(
                instanceId = "shared-shell-thread-thread-m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                showModeTabs = false
            )
        dataSource.cachePayloadForTest(
            route.copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "reply-1",
                            authorId = "u2",
                            authorName = "Cindy",
                            body = "reply body"
                        )
                    ),
                originMessage =
                    KuiklyThreadMessageItem(
                        id = "m1",
                        authorId = "u1",
                        authorName = "Artin",
                        body = "parent body",
                        threadChannelId = "thread-m1",
                        replyCount = 1
                    ),
                originStatus = "loaded"
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(route))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    message(
                        threadId = "thread-m1",
                        channelId = "channel-1",
                        id = "m1",
                        seq = 1,
                        body = "parent edited",
                        threadChannelId = "thread-m1",
                        replyCount = 1
                    )
                )
            )
        )

        val payload = store.state.value.payload
        assertEquals(listOf("reply-1"), payload.messages.map { it.id })
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals("parent edited", payload.originMessage?.body)
        assertEquals("thread-m1", payload.originMessage?.threadChannelId)
        scope.cancel()
    }

    @Test
    fun replyCountGapRefetchesTailWhenDeltaSyncReturnsEmpty() = runBlocking {
        // task #296: a reply thread whose delta `messageSync` returns empty
        // (replies are stranded below the visible tail seq) must fall back to a
        // full channel tail refetch, otherwise the thread shows "N replies" but
        // only the messages that arrived via realtime.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val allReplies =
            (1..4).map { index ->
                message(
                    threadId = "thread-m1",
                    channelId = "thread-m1",
                    id = "reply-$index",
                    seq = index.toLong(),
                    body = "reply $index"
                )
            }
        // The tail fallback calls `getReplyThread` (the reply-thread resolver),
        // which the fake routes through `threadResultsById[thread-m1]` — first
        // call returns the single newest reply, second returns all four.
        var replyThreadFetchCount = 0
        val repository =
            FakeConversationRepository(
                // Delta sync has nothing newer than the visible tail.
                newerResult = DataResult.Empty(EmptyReason.NoMessages),
                threadResultsById =
                    mapOf(
                        ThreadId("thread-m1") to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "thread-m1",
                                    messages = allReplies.takeLast(1),
                                    conversation =
                                        replyConversation()
                                )
                            )
                    ),
                threadResultProvider = { _, _, _ ->
                    replyThreadFetchCount += 1
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = allReplies,
                            conversation = replyConversation()
                        )
                    )
                }
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val route =
            basePayload().copy(
                instanceId = "shared-shell-thread-thread-m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
                parentChannelKind = KuiklyThreadSurfaceKind.Channel,
                originParentMessageId = "m1",
                showModeTabs = false
            )
        dataSource.cachePayloadForTest(
            route.copy(
                status = "content",
                messages = listOf(
                    KuiklyThreadMessageItem(
                        id = "reply-4",
                        authorId = "u2",
                        authorName = "Cindy",
                        body = "reply 4",
                        seq = 4
                    )
                ),
                originMessage =
                    KuiklyThreadMessageItem(
                        id = "m1",
                        authorId = "u1",
                        authorName = "Artin",
                        body = "parent body",
                        threadChannelId = "thread-m1",
                        replyCount = 1
                    ),
                originStatus = "loaded"
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(route))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("m1"),
                    threadChannelId = ChannelId("thread-m1"),
                    replyCount = 4,
                    lastReplyAt = "2026-08-02T00:00:00Z",
                    participantIds = emptyList()
                )
            )
        )

        val payload = store.state.value.payload
        assertEquals(
            listOf("reply-1", "reply-2", "reply-3", "reply-4"),
            payload.messages.map { it.id }
        )
        assertEquals("content", payload.status)
        // The delta sync ran (returned empty), then the tail refetch produced
        // the stranded replies via getThread.
        assertEquals(1, repository.syncNewerRequests.size)
        assertTrue(repository.getThreadRequests.size >= 1)
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdatedAppliesReactionPayloadIncludingEmptyRemoval() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Cindy"),
                        timestampLabel = "",
                        body = "cached body",
                        reactions =
                            listOf(
                                MessageReaction(
                                    emoji = "👀",
                                    count = 1,
                                    reactorIds = listOf("u1"),
                                    reactorNames = listOf("Artin")
                                )
                            )
                    )
                )
            )
        )

        assertEquals(
            listOf(
                KuiklyThreadReactionItem(
                    "👀",
                    count = 1,
                    selected = true,
                    reactorIds = listOf("u1"),
                    reactorNames = listOf("Artin")
                )
            ),
            store.state.value.payload.messages.single().reactions
        )

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Cindy"),
                        timestampLabel = "",
                        body = "cached body",
                        reactions = emptyList()
                    )
                )
            )
        )

        assertEquals(emptyList(), store.state.value.payload.messages.single().reactions)
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdatedDoesNotRevertJustAcknowledgedLocalReactionSelection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.ApplyMessageReactionSelection("m1", "🔥", selected = true))
        store.accept(
            SharedThreadIntent.ApplyMessageMutation(
                MessageMutationSummary(
                    messageId = "m1",
                    reactions =
                        listOf(
                            MessageReactionSummary(
                                emoji = "🔥",
                                count = 1,
                                reactorIds = listOf("u1"),
                                reactorNames = listOf("Artin")
                            )
                        )
                )
            )
        )

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Cindy"),
                        timestampLabel = "",
                        body = "server body edited",
                        reactions = emptyList()
                    )
                )
            )
        )

        val shieldedMessage = store.state.value.payload.messages.single()
        assertEquals("server body edited", shieldedMessage.body)
        assertEquals(
            listOf(KuiklyThreadReactionItem("🔥", count = 1, selected = true, reactorIds = listOf("u1"), reactorNames = listOf("Artin"))),
            shieldedMessage.reactions
        )

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Cindy"),
                        timestampLabel = "",
                        body = "server body edited again",
                        reactions = emptyList()
                    )
                )
            )
        )

        val freshRemoval = store.state.value.payload.messages.single()
        assertEquals("server body edited again", freshRemoval.body)
        assertEquals(emptyList(), freshRemoval.reactions)
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdatedKeepsVisibleReactionOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        val initialMessage =
            contentPayload("cached body").messages.single().copy(
                reactions =
                    listOf(
                        KuiklyThreadReactionItem("👍", count = 1, selected = true),
                        KuiklyThreadReactionItem("🔥", count = 1, selected = false)
                    )
            )
        dataSource.cachePayloadForTest(contentPayload("cached body").copy(messages = listOf(initialMessage)))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Cindy"),
                        timestampLabel = "",
                        body = "cached body",
                        reactions =
                            listOf(
                                MessageReaction(
                                    emoji = "🔥",
                                    count = 2,
                                    reactorIds = listOf("u2", "u3"),
                                    reactorNames = listOf("Cindy", "Artin")
                                ),
                                MessageReaction(
                                    emoji = "👍",
                                    count = 1,
                                    reactorIds = listOf("u1"),
                                    reactorNames = listOf("Artin")
                                )
                            )
                    )
                )
            )
        )

        val reactions = store.state.value.payload.messages.single().reactions
        assertEquals(listOf("👍", "🔥"), reactions.map { it.emoji })
        assertEquals(2, reactions.single { it.emoji == "🔥" }.count)
        assertEquals(listOf("u2", "u3"), reactions.single { it.emoji == "🔥" }.reactorIds)
        scope.cancel()
    }

    @Test
    fun realtimeThreadUpdatedMergesButTaskUpdatedDoesNotMutatePayloadTaskFacts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        val initial =
            contentPayload("task body").copy(
                messages =
                    listOf(
                        contentPayload("task body").messages.single().copy(
                            taskId = "task-1",
                            taskNumber = 44,
                            taskStatus = "Todo"
                        )
                    ),
                tasks =
                    listOf(
                        KuiklyThreadTaskItem(
                            id = "task-1",
                            title = "Old task",
                            status = "Todo",
                            taskNumber = 44
                        )
                    )
            )
        dataSource.cachePayloadForTest(initial)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("m1"),
                    threadChannelId = ChannelId("reply-thread"),
                    replyCount = 3,
                    lastReplyAt = "2026-05-19T16:10:00Z",
                    participantIds = emptyList(),
                    unreadCount = 2,
                    firstUnreadMessageId = MessageId("reply-2"),
                    latestReply =
                        ReplyPreview(
                            messageId = "reply-2",
                            seq = 2,
                            content = "latest reply",
                            senderId = "agent-1",
                            senderType = "agent",
                            senderName = "MingQi",
                            senderAvatarUrl = null,
                            createdAt = "2026-05-19T16:10:00Z",
                            senderDisplayName = "明启",
                        ),
                    syncCoreReplyWindow =
                        build.raft.app.network.sync.ThreadRepliesSyncWindowEnvelope(
                            producer = build.raft.app.network.sync.ThreadRepliesSyncWindowProducer,
                            rootKind = "message",
                            rootServerId = "server-1",
                            rootMessageId = "m1",
                            relationKind = "replies",
                            parentScopeServerId = "server-1",
                            parentScopeKind = "channel",
                            parentScopeId = "channel-1",
                            backing = "sync-scope",
                            windowKind = "sync-scope-window",
                        ),
                )
            )
        )
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                TaskUpdated(
                    TaskSummary(
                        id = TaskId("task-1"),
                        title = "Updated task",
                        status = TaskStatus.InReview,
                        taskNumber = 44,
                        messageId = MessageId("m1"),
                        updatedAtLabel = "2026-05-19T16:11:00Z"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals(3, message.replyCount)
        assertEquals(2, message.unreadReplyCount)
        assertEquals("reply-2", message.firstUnreadReplyMessageId)
        assertEquals(listOf("reply-2"), message.inlineReplies?.replies?.map { it.messageId })
        assertEquals("明启", message.inlineReplies?.replies?.single()?.senderDisplayName)
        assertEquals("Todo", message.taskStatus)
        assertEquals("Old task", store.state.value.payload.tasks.single().title)
        assertEquals("Todo", store.state.value.payload.tasks.single().status)
        scope.cancel()
    }

    @Test
    fun realtimeTaskUpdatedLeavesThreadPayloadTaskFallbackUntouched() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        val initial =
            contentPayload("task body").copy(
                messages =
                    listOf(
                        contentPayload("task body").messages.single().copy(
                            taskId = "local-stale-task",
                            taskNumber = 77,
                            taskStatus = "Todo"
                        )
                    ),
                tasks =
                    listOf(
                        KuiklyThreadTaskItem(
                            id = "local-stale-task",
                            title = "Cached task",
                            status = "Todo",
                            taskNumber = 77,
                            messageId = "m1"
                        )
                    )
            )
        dataSource.cachePayloadForTest(initial)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                TaskUpdated(
                    TaskSummary(
                        id = TaskId("server-task"),
                        title = "Server task",
                        status = TaskStatus.Done,
                        taskNumber = 77,
                        updatedAtLabel = "2026-05-19T16:12:00Z"
                    )
                )
            )
        )

        val payload = store.state.value.payload
        val message = payload.messages.single()
        assertEquals("local-stale-task", message.taskId)
        assertEquals("Todo", message.taskStatus)
        assertEquals(listOf("local-stale-task"), payload.tasks.map { it.id })
        scope.cancel()
    }

    @Test
    fun realtimeThreadUpdatedCanDecreaseAuthoritativeReplyCount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        dataSource.cachePayloadForTest(
            contentPayload("parent").copy(
                messages =
                    listOf(
                        contentPayload("parent").messages.single().copy(
                            replyCount = 5,
                            unreadReplyCount = 3,
                            firstUnreadReplyMessageId = "reply-old"
                        )
                    )
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("m1"),
                    threadChannelId = ChannelId("reply-thread"),
                    replyCount = 1,
                    lastReplyAt = "2026-05-19T16:13:00Z",
                    participantIds = emptyList(),
                    unreadCount = 0,
                    firstUnreadMessageId = null
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals(1, message.replyCount)
        assertEquals(0, message.unreadReplyCount)
        assertNull(message.firstUnreadReplyMessageId)
        scope.cancel()
    }

    @Test
    fun realtimeThreadUpdatedReplyCountSurvivesCanonicalReprojection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val payloadStore = SharedThreadPayloadStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                payloadStore = payloadStore
            )
        dataSource.cachePayloadForTest(
            contentPayload("parent").copy(
                messages =
                    listOf(contentPayload("parent").messages.single().copy(replyCount = 5))
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        // Unconfined scope: Start has already rendered the cached parent. With the channel fetch
        // failing, write actions stay disabled, so waiting on them would never finish.
        assertEquals(5, store.state.value.payload.messages.single().replyCount)
        val provenance = requireNotNull(dataSource.currentReplyCacheProvenance())

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("m1"),
                    threadChannelId = ChannelId("reply-thread"),
                    replyCount = 9,
                    lastReplyAt = "2026-05-19T16:13:00Z",
                    participantIds = emptyList(),
                    unreadCount = 0,
                    firstUnreadMessageId = null
                )
            )
        )
        assertEquals(9, store.state.value.payload.messages.single().replyCount)

        // The realtime fact was recorded, so a stale canonical reprojection cannot erase it.
        val staleCanonical =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent",
                            threadChannelId = null,
                            replyCount = 5
                        )
                    )
            )
        assertEquals(
            9,
            payloadStore
                .applyReplyCountFacts(
                    provenance.serverId,
                    "channel-1",
                    provenance.environmentId,
                    provenance.principalId,
                    staleCanonical
                )
                .messages.single().replyCount
        )
        scope.cancel()
    }

    @Test
    fun visibleReadMarksChannel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                    ),
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.MarkVisibleRead())
        store.accept(SharedThreadIntent.MarkVisibleRead())

        assertEquals(listOf("channel-1"), api.readAllChannels)
        scope.cancel()
    }

    @Test
    fun cachedTimelineMarksReadOnlyAfterCanonicalAccessResolves() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api =
            FakeThreadApi().also {
                it.channelMessagesStarted = CompletableDeferred()
                it.releaseChannelMessages = CompletableDeferred()
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            withTimeout(5_000) { api.channelMessagesStarted?.await() }
            assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
            assertFalse(store.state.value.writeActionsEnabled)

            store.accept(SharedThreadIntent.MarkVisibleRead())
            assertTrue(api.readAllChannels.isEmpty())
            assertTrue(api.readSeqRequests.isEmpty())

            api.releaseChannelMessages?.complete(Unit)
            withTimeout(5_000) {
                while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                    delay(1)
                }
            }
            assertTrue(store.state.value.writeActionsEnabled)
            store.accept(SharedThreadIntent.MarkVisibleRead())
            assertEquals(1, api.readAllChannels.size + api.readSeqRequests.size)
            assertEquals(
                "channel-1",
                api.readAllChannels.singleOrNull() ?: api.readSeqRequests.single().first
            )
        } finally {
            api.releaseChannelMessages?.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun cachedTimelineNeverMarksReadWhenCanonicalAccessIsDenied() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.NoPermission(PermissionBlocker(code = "thread_forbidden"))
                    )
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(ThreadAccessResolution.NoPermission, store.state.value.accessResolution)
        assertFalse(store.state.value.writeActionsEnabled)
        store.accept(SharedThreadIntent.MarkVisibleRead())

        assertTrue(api.readAllChannels.isEmpty())
        assertTrue(api.readSeqRequests.isEmpty())
        scope.cancel()
    }

    @Test
    fun visibleReadUsesLatestSeqWhenAvailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 10))
                    ),
            )
        val payload =
            contentPayload("cached body").let { content ->
                content.copy(messages = content.messages.map { message -> message.copy(seq = 10L) })
            }
        dataSource.cachePayloadForTest(payload)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.MarkVisibleRead())

        assertEquals(listOf("channel-1" to 10L), api.readSeqRequests)
        assertTrue(api.readAllChannels.isEmpty())
        scope.cancel()
    }

    @Test
    fun inlineReplyScopeOpenReadUsesCanonicalReadAllWhenPreviewIsBounded() = runBlocking {
        val api = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )

        val result = dataSource.markVisibleChannelRead("reply-thread", latestSeq = null)

        assertTrue(result is DataResult.Content)
        assertEquals(listOf("reply-thread"), api.readAllChannels)
        assertTrue(api.readSeqRequests.isEmpty())
    }

    @Test
    fun visibleReadCoalescesImmediateSameChannelCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                    ),
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.MarkVisibleRead())
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        seq = 11L,
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "fresh body"
                    )
                )
            )
        )
        store.accept(SharedThreadIntent.MarkVisibleRead())

        assertEquals(listOf("channel-1"), api.readAllChannels)
        scope.cancel()
    }

    @Test
    fun applyReplyThreadReadClearsParentUnreadProjectionAndCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val initial =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent",
                            threadChannelId = "thread-old",
                            replyCount = 2,
                            unreadReplyCount = 1,
                            firstUnreadReplyMessageId = "reply-1"
                        )
                    )
            )
        dataSource.cachePayloadForTest(initial)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.ApplyReplyThreadRead(parentMessageId = "m1", threadChannelId = "thread-new"))

        val message = store.state.value.payload.messages.single()
        assertEquals(2, message.replyCount)
        assertEquals(0, message.unreadReplyCount)
        assertNull(message.firstUnreadReplyMessageId)
        assertEquals("thread-new", message.threadChannelId)
        val cached = dataSource.cachedPayload("channel-1")?.messages?.single()
        assertEquals(0, cached?.unreadReplyCount)
        assertNull(cached?.firstUnreadReplyMessageId)
        scope.cancel()
    }

    @Test
    fun replyThreadLoadingPayloadCarriesFirstUnreadFocus() {
        val parent =
            KuiklyThreadMessageItem(
                id = "m1",
                authorId = "u1",
                authorName = "Artin",
                body = "parent",
                replyCount = 2,
                unreadReplyCount = 1,
                firstUnreadReplyMessageId = "reply-1"
            )

        val loading =
            basePayload().replyThreadLoadingPayload(
                parentMessageId = parent.id,
                parentChannelId = "channel-1",
                threadChannelId = parent.threadChannelId,
                focusedMessageId = parent.firstUnreadReplyMessageId
            )

        assertEquals("reply-1", loading.focusedMessageId)
        assertEquals("m1", loading.parentMessageId)
    }

    @Test
    fun visibleReadCoalescesInFlightBurstToLatestMarker() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(failChannelMessages = true).also {
                it.failReadAll = false
                it.readAllDelayMillis = 50L
            }
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                    ),
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.dispatch(SharedThreadIntent.MarkVisibleRead())
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        seq = 11L,
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "realtime body 2"
                    )
                )
            )
        )
        store.dispatch(SharedThreadIntent.MarkVisibleRead())
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m3"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        seq = 12L,
                        author = member("u3", "Cindy"),
                        timestampLabel = "2026-05-19T16:02:00Z",
                        body = "realtime body 3"
                    )
                )
            )
        )
        store.dispatch(SharedThreadIntent.MarkVisibleRead())

        delay(120L)

        assertEquals(listOf("channel-1"), api.readAllChannels)
        assertEquals(listOf("channel-1" to 12L), api.readSeqRequests)
        assertEquals(1, api.maxConcurrentReadAllCalls)
        scope.cancel()
    }

    @Test
    fun sendMessageForwardsAttachmentIds() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        dataSource.sendMessage(
            channelId = "channel-1",
            body = "with files",
            asTask = false,
            attachmentIds = listOf("att-1", "att-2"),
            randomId = "rid-1"
        )

        assertEquals(listOf("att-1", "att-2"), api.lastAttachmentIds)
        assertEquals("rid-1", api.lastRandomId)
    }

    @Test
    fun saveMessageCallsThreadApi() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        dataSource.saveMessage("m1")

        assertEquals(listOf("m1"), api.savedMessageIds)
    }

    @Test
    fun unsaveMessageCallsThreadApi() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        dataSource.unsaveMessage("m1")

        assertEquals(listOf("m1"), api.unsavedMessageIds)
    }

    @Test
    fun fetchThreadPayloadDefersSavedStatusUntilSecondaryRefresh() = runBlocking {
        val savedStatusRepository = FakeSavedStatusRepository(savedIds = setOf(MessageId("m1")))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                savedStatusRepository = savedStatusRepository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals(emptyList(), savedStatusRepository.checkedMessageIds)
        assertFalse(payload.messages.single().isSaved)

        val enriched = dataSource.enrichSavedStatus(payload)

        assertEquals(listOf(listOf(MessageId("m1"))), savedStatusRepository.checkedMessageIds)
        assertTrue(enriched.messages.single().isSaved)
    }

    @Test
    fun savedStatusCheckNeverSendsLocalSendIds() = runBlocking {
        // task #335: local sends have no server row and non-UUID ids; the server's saved/check used to 500
        // on them, dropping the saved markers of the whole screen.
        val savedStatusRepository = FakeSavedStatusRepository(savedIds = setOf(MessageId("m1")))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                savedStatusRepository = savedStatusRepository
            )
        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data
        val serverMessage = payload.messages.single()
        val withLocalSends =
            payload.copy(
                messages =
                    payload.messages +
                        serverMessage.copy(id = "${ThreadTransientLocalSendIdPrefix}thread-1", deliveryState = ThreadMessageDeliverySending) +
                        serverMessage.copy(id = "${ThreadTransientLocalSendIdPrefix}thread-2", deliveryState = ThreadMessageDeliveryFailed)
            )

        val enriched = dataSource.enrichSavedStatus(withLocalSends)

        assertEquals(listOf(listOf(MessageId(serverMessage.id))), savedStatusRepository.checkedMessageIds)
        assertEquals(serverMessage.id == "m1", enriched.messages.first().isSaved)
        assertEquals(3, enriched.messages.size, "local sends stay in the timeline, only the lookup skips them")
    }

    @Test
    fun savedStatusCheckRebuildsIdsFromTheCurrentPayloadEachTime() = runBlocking {
        // Same request size is not the same request: a fixed-size window of fixed-length ids keeps the byte
        // count constant while its content changes (task #335 follow-up).
        val savedStatusRepository = FakeSavedStatusRepository(savedIds = emptySet())
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                savedStatusRepository = savedStatusRepository
            )
        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data
        val template = payload.messages.single()
        val first = payload.copy(messages = listOf(template.copy(id = "a1"), template.copy(id = "a2")))
        val second = payload.copy(messages = listOf(template.copy(id = "a2"), template.copy(id = "a3")))

        dataSource.enrichSavedStatus(first)
        dataSource.enrichSavedStatus(second)

        assertEquals(
            listOf(listOf(MessageId("a1"), MessageId("a2")), listOf(MessageId("a2"), MessageId("a3"))),
            savedStatusRepository.checkedMessageIds
        )
    }

    @Test
    fun savedStatusCheckIsSkippedWhenOnlyLocalSendsAreOnScreen() = runBlocking {
        val savedStatusRepository = FakeSavedStatusRepository(savedIds = emptySet())
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                savedStatusRepository = savedStatusRepository
            )
        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data
        val onlyLocal =
            payload.copy(
                messages = listOf(payload.messages.single().copy(id = "${ThreadTransientLocalSendIdPrefix}thread-3")),
                originMessage = null
            )

        dataSource.enrichSavedStatus(onlyLocal)

        assertEquals(emptyList(), savedStatusRepository.checkedMessageIds)
    }

    @Test
    fun warmSavedProjectionSurvivesPrimaryRefreshUntilSavedAuthorityCommits() = runBlocking {
        val savedLookupStarted = CompletableDeferred<Unit>()
        val releaseSavedLookup = CompletableDeferred<DataResult<SavedMessageStatusSnapshot>>()
        val savedStatusRepository =
            object : SavedStatusRepository {
                override suspend fun checkSavedMessages(
                    serverId: ServerId,
                    messageIds: List<MessageId>
                ): DataResult<SavedMessageStatusSnapshot> {
                    savedLookupStarted.complete(Unit)
                    return releaseSavedLookup.await()
                }
            }
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            messages = listOf(message(id = "m1", seq = 1, body = "fresh body").copy(isSaved = false))
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                savedStatusRepository = savedStatusRepository
            )
        val accepted =
            contentPayload("cached body").copy(
                messages = listOf(contentPayload("cached body").messages.single().copy(isSaved = true))
            )
        dataSource.cachePayloadForTest(accepted)
        // Production runs the store on viewModelScope (one thread); its state reducers are plain
        // read-modify-writes, so a multi-threaded scope lets a concurrent auxiliary commit overwrite
        // the Saved-authority commit and the projection never clears (CI flake on #2784).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals("fresh body", store.state.value.payload.messages.single().body)
        assertTrue(
            store.state.value.payload.messages.single().isSaved,
            "the messages response has no Saved authority and must not clear the accepted projection"
        )

        store.accept(SharedThreadIntent.FirstContentReady)
        savedLookupStarted.await()
        assertTrue(store.state.value.payload.messages.single().isSaved)

        releaseSavedLookup.complete(
            DataResult.Content(SavedMessageStatusSnapshot(savedIds = emptySet()))
        )
        withTimeout(5_000L) {
            while (store.state.value.payload.messages.single().isSaved) {
                delay(10L)
            }
        }

        assertFalse(store.state.value.payload.messages.single().isSaved)
        scope.cancel()
    }

    @Test
    fun applyMessageSavedPatchesPayloadAndCache() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("saveable body"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertFalse(store.state.value.payload.messages.single().isSaved)

        store.accept(SharedThreadIntent.ApplyMessageSaved("m1", saved = true))

        assertTrue(store.state.value.payload.messages.single().isSaved)
        assertTrue(dataSource.cachedPayload("channel-1")?.messages?.single()?.isSaved == true)
        val cached = localStore.readThread(ThreadId("channel-1"))
        assertNull(cached)
        scope.cancel()
    }

    @Test
    fun setMessageReactionReturnsMutationSummaryWithoutWritingPayload() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body"))

        val result = dataSource.setMessageReaction("m1", "🔥", selected = true)

        val mutation = (result as DataResult.Content).data
        assertEquals(listOf("m1" to "🔥"), api.addedReactions)
        assertEquals("m1", mutation.messageId)
        assertEquals("🔥", mutation.reactions.single().emoji)
        assertEquals(null, localStore.readThread(ThreadId("channel-1")))
    }

    @Test
    fun optimisticReactionSelectionDoesNotWriteCanonicalCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.ApplyMessageReactionSelection("m1", "🔥", selected = true))

        val reaction = store.state.value.payload.messages.single().reactions.single()
        assertEquals("🔥", reaction.emoji)
        assertEquals(true, reaction.selected)
        assertEquals(emptyList(), dataSource.cachedPayload("channel-1")?.messages?.single()?.reactions)
        assertEquals(null, localStore.readThread(ThreadId("channel-1")))
        scope.cancel()
    }

    @Test
    fun applyMessageMutationCachesCanonicalPayloadAndProjectsReadback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        val mutation =
            MessageMutationSummary(
                messageId = "m1",
                reactions =
                    listOf(
                        MessageReactionSummary(
                            emoji = "🔥",
                            count = 1,
                            reactorIds = listOf("u1"),
                            reactorNames = listOf("Artin")
                        )
                    )
            )
        store.accept(SharedThreadIntent.ApplyMessageMutation(mutation))

        val reaction = store.state.value.payload.messages.single().reactions.single()
        assertEquals("🔥", reaction.emoji)
        assertEquals(1, reaction.count)
        assertEquals(true, reaction.selected)
        assertEquals(listOf("u1"), reaction.reactorIds)
        assertEquals(listOf("Artin"), reaction.reactorNames)
        assertNull(localStore.readThread(ThreadId("channel-1")))
        val cachedMessage = dataSource.cachedPayload("channel-1")?.messages?.single()
        assertEquals("reactable body", cachedMessage?.body)
        val cachedReaction = cachedMessage?.reactions?.single()
        assertEquals("🔥", cachedReaction?.emoji)
        assertEquals(1, cachedReaction?.count)
        assertEquals(listOf("u1"), cachedReaction?.reactorIds)
        assertEquals(listOf("Artin"), cachedReaction?.reactorNames)
        assertEquals(true, cachedReaction?.selected)
        scope.cancel()
    }

    @Test
    fun applyMessageMutationTrustsCanonicalEmptyReactionPayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val message =
            contentPayload("reactable body").messages.single().copy(
                reactions = listOf(KuiklyThreadReactionItem("🔥", count = 1, selected = true))
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body").copy(messages = listOf(message)))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyMessageMutation(
                MessageMutationSummary(messageId = "m1", reactions = emptyList())
            )
        )

        assertEquals(emptyList(), store.state.value.payload.messages.single().reactions)
        assertEquals(emptyList(), dataSource.cachedPayload("channel-1")?.messages?.single()?.reactions)
        assertNull(localStore.readThread(ThreadId("channel-1")))
        scope.cancel()
    }

    @Test
    fun applyMessageMutationKeepsVisibleReactionOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val message =
            contentPayload("reactable body").messages.single().copy(
                reactions =
                    listOf(
                        KuiklyThreadReactionItem("👍", count = 1, selected = true),
                        KuiklyThreadReactionItem("🔥", count = 1, selected = false)
                    )
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body").copy(messages = listOf(message)))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyMessageMutation(
                MessageMutationSummary(
                    messageId = "m1",
                    reactions =
                        listOf(
                            MessageReactionSummary(
                                emoji = "🔥",
                                count = 2,
                                reactorIds = listOf("u2", "u3"),
                                reactorNames = listOf("Cindy", "Artin")
                            ),
                            MessageReactionSummary(
                                emoji = "👍",
                                count = 1,
                                reactorIds = listOf("u1"),
                                reactorNames = listOf("Artin")
                            )
                        )
                )
            )
        )

        val reactions = store.state.value.payload.messages.single().reactions
        assertEquals(listOf("👍", "🔥"), reactions.map { it.emoji })
        assertEquals(2, reactions.single { it.emoji == "🔥" }.count)
        assertEquals(listOf("u2", "u3"), reactions.single { it.emoji == "🔥" }.reactorIds)
        assertEquals(listOf("👍", "🔥"), dataSource.cachedPayload("channel-1")?.messages?.single()?.reactions?.map { it.emoji })
        scope.cancel()
    }

    @Test
    fun applyMessageReactionsKeepsVisibleReactionOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        val initialMessage =
            contentPayload("reactable body").messages.single().copy(
                reactions =
                    listOf(
                        KuiklyThreadReactionItem("👍", count = 1, selected = true),
                        KuiklyThreadReactionItem("🔥", count = 1, selected = false)
                    )
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body").copy(messages = listOf(initialMessage)))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyMessageReactions(
                "m1",
                listOf(
                    KuiklyThreadReactionItem(
                        "🔥",
                        count = 2,
                        selected = true,
                        reactorIds = listOf("u2", "u3"),
                        reactorNames = listOf("Cindy", "Artin")
                    ),
                    KuiklyThreadReactionItem(
                        "👍",
                        count = 1,
                        selected = true,
                        reactorIds = listOf("u1"),
                        reactorNames = listOf("Artin")
                    )
                )
            )
        )

        val reactions = store.state.value.payload.messages.single().reactions
        assertEquals(listOf("👍", "🔥"), reactions.map { it.emoji })
        assertEquals(2, reactions.single { it.emoji == "🔥" }.count)
        assertEquals(listOf("u2", "u3"), reactions.single { it.emoji == "🔥" }.reactorIds)
        scope.cancel()
    }

    @Test
    fun setMessageReactionRemovesSelectedReaction() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        val result = dataSource.setMessageReaction("m1", "🔥", selected = false)

        assertTrue(result is DataResult.Content)
        assertEquals(emptyList(), api.addedReactions)
        assertEquals(listOf("m1" to "🔥"), api.removedReactions)
        assertEquals(emptyList(), result.data.reactions)
    }

    @Test
    fun applyMessageReactionsRollsBackOptimisticSelection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val originalReactions = listOf(KuiklyThreadReactionItem("🔥", count = 1, selected = true))
        val message = contentPayload("reactable body").messages.single()
        dataSource.cachePayloadForTest(
            contentPayload("reactable body").copy(
                messages = listOf(message.copy(reactions = originalReactions))
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.ApplyMessageReactionSelection("m1", "🔥", selected = false))
        store.accept(SharedThreadIntent.ApplyMessageReactions("m1", originalReactions))

        assertEquals(originalReactions, store.state.value.payload.messages.single().reactions)
        scope.cancel()
    }

    @Test
    fun createOrOpenReplyThreadFetchesReplyChannelAndPreservesOrigin() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("reply body", id = "reply-1", seq = 11, channelId = "thread-m1"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val result = dataSource.createOrOpenReplyThread(base, parent)

        val payload = (result as DataResult.Content).data
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("reply body", payload.messages.single().body)
        assertEquals(listOf("channel-1" to "m1"), api.createdThreads)
        assertEquals(listOf("thread-m1"), api.channelIds.takeLast(1))
    }

    @Test
    fun createOrOpenReplyThreadKeepsEmptyPanelForFirstReply() = runBlocking {
        val api = FakeThreadApi(bodyText = emptyMessagesResponse())
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val result = dataSource.createOrOpenReplyThread(base, parent)

        val payload = (result as DataResult.Content).data
        assertEquals("empty", payload.status)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("loaded", payload.originStatus)
        assertEquals(emptyList(), payload.messages)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals("thread-m1", payload.resolveThreadComposer().streamChannelId)
        assertEquals("Reply in thread", payload.resolveThreadComposer().placeholder)
        assertEquals(listOf("channel-1" to "m1"), api.createdThreads)
        assertEquals(listOf("thread-m1"), api.channelIds.takeLast(1))
    }

    @Test
    fun replyThreadSnapshotWithOnlyOriginUsesReplyEmptyState() = runBlocking {
        val parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = emptyList(),
                            parentMessage = parentMessage,
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    name = "thread-m1",
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                cacheEnvironmentIdProvider = { "https://prod.example.test" }
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val result = dataSource.createOrOpenReplyThread(base, parent)

        val payload = (result as DataResult.Content).data
        assertEquals("empty", payload.status)
        assertEquals(emptyList(), payload.messages)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
    }

    @Test
    fun fetchThreadPayloadPreservesReplyOriginWhenRepositoryReturnsParentAsMessage() = runBlocking {
        val parent = contentPayload("parent body").messages.single()
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(threadId = "thread-m1", id = "m1", seq = 10, body = "parent body"),
                                    message(threadId = "thread-m1", id = "reply-1", seq = 11, body = "reply body")
                                ),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originMessage = parent,
                originParentMessageId = "m1",
                originStatus = "loaded"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals("loaded", payload.originStatus)
    }

    @Test
    fun fetchThreadPayloadHydratesOriginWithoutBlockingReplyMessagesWhenRepliesExcludeParent() = runBlocking {
        val parent = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 11, body = "reply body")),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(parent),
                            conversation = conversationSummary("channel-1")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals(listOf(MessageId("m1")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(ChannelId("channel-1")), repository.getMessageContextChannelIdRequests)
    }

    @Test
    fun fetchThreadPayloadUsesParentContextRepositoryForReplyOrigin() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val localParent = message(threadId = "channel-1", id = "m1", seq = 1, body = "local parent body")
        localStore.writeThread(
            threadSnapshot(
                threadId = "channel-1",
                messages = listOf(localParent),
                conversation = conversationSummary("channel-1")
            )
        )
        val networkParent = message(threadId = "channel-1", id = "m1", seq = 1, body = "network parent body")
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 11, body = "reply body")),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(networkParent),
                            conversation = conversationSummary("channel-1")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("network parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals(listOf(MessageId("m1")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(ChannelId("channel-1")), repository.getMessageContextChannelIdRequests)
    }

    @Test
    fun fetchThreadPayloadHydratesReplyOriginFromParentContextWhenNoReplyMessagesCanRender() = runBlocking {
        val parent = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = emptyList(),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(parent),
                            conversation = conversationSummary("channel-1")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(emptyList(), payload.messages.map { it.body })
        assertEquals(listOf(MessageId("m1")), repository.getMessageContextRequests)
    }

    @Test
    fun fetchThreadPayloadHydratesReplyOriginInParallelWhenRepliesCanRender() = runBlocking {
        val sparseParent = message(threadId = "thread-m1", id = "m1", seq = 10, body = "")
        val canonicalParent = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
        val messageStore = SharedThreadMessageStore()
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    sparseParent,
                                    message(threadId = "thread-m1", id = "reply-1", seq = 11, body = "reply body")
                                ),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(canonicalParent),
                            conversation = conversationSummary("channel-1")
                        )
                    )
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        dataSource.readSession()
        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals(listOf(MessageId("m1")), repository.getMessageContextRequests)
        assertEquals(
            "parent body",
            messageStore.getScoped(
                "https://prod.example.test\u001fu1\u001fserver-1\u001f1",
                ChannelId("channel-1"),
                MessageId("m1")
            )?.body
        )
    }

    @Test
    fun retiredOwnerReplyOriginMayWarmOldScopeButCannotReturnProjectableParent() = runBlocking {
        val ownerASession = replyResolutionSession("account-a")
        val ownerBSession = replyResolutionSession("account-b")
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "account-a",
                serverId = "server-a",
                authGeneration = 1L,
                selectionGeneration = 1L
            )
        val parent = message(threadId = "channel-1", id = "m1", seq = 1, body = "owner-a parent")
        val repository =
            FakeConversationRepository(
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(parent),
                            parentMessage = parent,
                            conversation = conversationSummary("channel-1")
                        )
                    )
            )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.messageContextStartedById[MessageId("m1")] = started
        repository.releaseMessageContextById[MessageId("m1")] = release
        val messageStore = SharedThreadMessageStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(ownerASession),
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = { identity }
            )
        val ownerA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val route =
            replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                .copy(
                    parentChannelId = "channel-1",
                    originMessage = null,
                    originStatus = "loading"
                )
        val resolution = async { dataSource.resolveReplyOrigin(route, expectedProvenance = ownerA) }
        started.await()

        identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "account-b",
                serverId = "server-a",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        dataSource.captureReplyCacheProducerContext()
        release.complete(Unit)

        val stale = assertIs<SharedThreadOriginResolution.Error>(resolution.await())
        assertEquals("thread_reply_cache_provenance_stale", stale.code)
        assertEquals(
            "owner-a parent",
            messageStore
                .getScoped(
                    requireNotNull(ownerA.messageStoreScopeId),
                    ChannelId("channel-1"),
                    MessageId("m1")
                )
                ?.body
        )
        val ownerB = requireNotNull(dataSource.currentReplyCacheProvenance())
        assertNull(
            messageStore.getScoped(
                requireNotNull(ownerB.messageStoreScopeId),
                ChannelId("channel-1"),
                MessageId("m1")
            )
        )
    }

    @Test
    fun fetchThreadPayloadReusesStoredReplyOriginMessageBeforeNetworkContext() = runBlocking {
        val canonicalParent = message(threadId = "channel-1", id = "m1", seq = 1, body = "stored parent body")
        val messageStore = SharedThreadMessageStore()
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(threadId = "thread-m1", id = "reply-1", seq = 11, body = "reply body")
                                ),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    ),
                messageContextResult =
                    DataResult.Error(ScreenError(code = "should_not_fetch_context", retryable = false))
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        dataSource.readSession()
        messageStore.upsertScoped(
            "https://prod.example.test\u001fu1\u001fserver-1\u001f1",
            ChannelId("channel-1"),
            canonicalParent
        )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("stored parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals(emptyList(), repository.getMessageContextRequests)
    }

    @Test
    fun createOrOpenReplyThreadUsesRepositoryMetadataForOriginAndMemoryCache() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val repository = FakeConversationRepository(
            threadResult =
                DataResult.Content(
                    threadSnapshot(
                        threadId = "thread-m1",
                        body = "reply body",
                        id = "reply-1",
                        seq = 11,
                        conversation =
                            conversationSummary("thread-m1").copy(
                                kind = ConversationKind.Thread,
                                channelId = ChannelId("thread-m1"),
                                thread =
                                    ThreadConversation(
                                        parentChannelId = ChannelId("channel-1"),
                                        parentMessageId = MessageId("m1"),
                                        streamChannelId = ChannelId("thread-m1")
                                    )
                            )
                    )
                ),
            createThreadResult =
                DataResult.Content(
                    ThreadActionResult(
                        threadChannelId = ChannelId("thread-m1"),
                        changed = true,
                        parentMessageId = MessageId("m1"),
                        replyCount = 4
                    )
                )
        )
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                cacheEnvironmentIdProvider = { "https://prod.example.test" }
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val payload = (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data
        dataSource.cachePayloadForTest(payload)

        val cached = localStore.readThread(ThreadId("thread-m1"))
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("", payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals(4, payload.originMessage?.replyCount)
        assertEquals("thread-m1", payload.originMessage?.threadChannelId)
        assertNull(cached)
        val cachedPayload = dataSource.cachedPayload("thread-m1")
        assertEquals(4, cachedPayload?.originMessage?.replyCount)
        assertEquals("thread-m1", cachedPayload?.originMessage?.threadChannelId)
    }

    @Test
    fun loadOlderMessagesMergesBeforeVisiblePayloadAndCachesInMemory() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10),
                olderBodyText = messagesResponse("older body", id = "m5", seq = 5, historyLimited = false)
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.LoadOlder)

        val payload = store.state.value.payload
        assertEquals(listOf("older body", "current body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.beforeSeqs)
        assertEquals(false, payload.canLoadMoreBefore)
        assertEquals("idle", payload.olderMessagesStatus)
        assertNull(localStore.readThread(ThreadId("channel-1")))
        assertEquals("older body", dataSource.cachedPayload("channel-1")?.messages?.first()?.body)
        scope.cancel()
    }

    @Test
    fun threadPageSizeDefaultsToThirtyAndReevaluatesRemoteValuePerRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10),
                olderBodyText = messagesResponse("older body", id = "m5", seq = 5, historyLimited = false)
            )
        var remotePageSize: Int? = null
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                threadPageSizeProvider = { remotePageSize }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        remotePageSize = 20
        store.accept(SharedThreadIntent.LoadOlder)

        assertEquals(listOf(30, 20), api.channelMessageLimits)
        scope.cancel()
    }

    @Test
    fun repositoryThreadPagingUsesTheSameResolvedRemotePageSize() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeConversationRepository()
        var remotePageSize: Int? = null
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                threadPageSizeProvider = { remotePageSize }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        remotePageSize = 50
        store.accept(
            SharedThreadIntent.SetPagingAvailability(
                canLoadMoreBefore = true,
                canLoadMoreAfter = false
            )
        )
        store.accept(SharedThreadIntent.LoadOlder)

        assertEquals(listOf(30), repository.getThreadLimits)
        assertEquals(listOf(50), repository.olderLimits)
        assertEquals(
            listOf(
                ThreadId("channel-1") to listOf(MessageId("m1")),
                ThreadId("channel-1") to listOf(MessageId("m5"), MessageId("m1"))
            ),
            repository.getThreadReplySummariesRequests
        )
        scope.cancel()
    }

    @Test
    fun threadPageSizeAcceptsTwentyThirtyAndFiftyButRejectsUnsafeValues() {
        assertEquals(20, SharedThreadPaging.resolve(20))
        assertEquals(30, SharedThreadPaging.resolve(30))
        assertEquals(50, SharedThreadPaging.resolve(50))
        assertEquals(30, SharedThreadPaging.resolve(null))
        assertEquals(30, SharedThreadPaging.resolve(0))
        assertEquals(30, SharedThreadPaging.resolve(9))
        assertEquals(30, SharedThreadPaging.resolve(51))
    }

    @Test
    fun threadPageSizeVariantRequiresEnabledFlagAndSafeNumericRange() {
        val enabledTwenty =
            ServerFeatureFlagSnapshot(
                serverId = ServerId("server-1"),
                resolved = true,
                values = mapOf(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY to true),
                variants = mapOf(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY to "20")
            )
        val disabledFifty =
            enabledTwenty.copy(
                values = mapOf(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY to false),
                variants = mapOf(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY to "50")
            )
        val malformed =
            enabledTwenty.copy(variants = mapOf(MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY to "fast"))

        assertEquals(20, SharedThreadPaging.resolve(enabledTwenty.mobileThreadPageSize()))
        assertEquals(30, SharedThreadPaging.resolve(disabledFifty.mobileThreadPageSize()))
        assertEquals(30, SharedThreadPaging.resolve(malformed.mobileThreadPageSize()))
    }

    @Test
    fun loadNewerMessagesMergesAfterVisiblePayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = messagesResponse("newer body", id = "m12", seq = 12, historyLimited = false)
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        val outcome = store.loadNewer()

        val payload = store.state.value.payload
        assertEquals(ThreadNewerPageLoadOutcome.Applied(listOf("m12")), outcome)
        assertEquals(listOf("current body", "newer body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.afterSeqs)
        assertEquals(emptyList(), api.syncSinceSeqs)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun realtimeAppendDuringFailedNewerPageDoesNotBecomeAppliedPageOutcome() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerResponseCode = 500
            )
        api.afterMessagesStarted = CompletableDeferred()
        api.releaseAfterMessages = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        val pageOutcome = async { store.loadNewer() }
        api.afterMessagesStarted?.await()
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m11", seq = 11, body = "realtime body"))
            )
        )
        api.releaseAfterMessages?.complete(Unit)

        assertEquals(ThreadNewerPageLoadOutcome.Failed, pageOutcome.await())
        assertEquals(listOf("m10", "m11"), store.state.value.payload.messages.map { it.id })
        assertEquals("error", store.state.value.payload.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun emptyNewerPageReturnsEmptyOutcomeWithoutAppliedIdentities() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = emptyMessagesResponse()
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        assertEquals(ThreadNewerPageLoadOutcome.Empty, store.loadNewer())
        assertEquals(listOf("m10"), store.state.value.payload.messages.map { it.id })
        assertEquals(false, store.state.value.payload.canLoadMoreAfter)
        scope.cancel()
    }

    @Test
    fun deduplicatedNewerPageReturnsEmptyOutcomeWithoutAppliedIdentities() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val currentResponse = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false)
        val api =
            FakeThreadApi(
                bodyText = currentResponse,
                newerBodyText = currentResponse
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        assertEquals(ThreadNewerPageLoadOutcome.Empty, store.loadNewer())
        assertEquals(listOf("m10"), store.state.value.payload.messages.map { it.id })
        scope.cancel()
    }

    @Test
    fun realtimeGapDuringManualLoadNewerHandsOffToRecoveryWithoutUsingLoadingAsOwner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = messagesResponse("manual page", id = "m11", seq = 11, historyLimited = false),
            )
        api.afterMessagesStarted = CompletableDeferred()
        api.releaseAfterMessages = CompletableDeferred()
        api.syncBodyTexts.addLast(syncRangeResponse(12L..61L))
        api.syncBodyTexts.addLast(syncRangeResponse(62L..99L))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        val pagination = launch { store.accept(SharedThreadIntent.LoadNewer) }
        api.afterMessagesStarted?.await()
        assertEquals("loading", store.state.value.payload.newerMessagesStatus)
        assertEquals("loading", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        val realtime = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m100", seq = 100, body = "future during manual pagination")),
                ),
            )
        }
        realtime.join()
        api.releaseAfterMessages?.complete(Unit)
        pagination.join()

        assertEquals(listOf(10L), api.afterSeqs)
        assertEquals(
            listOf(11L, 61L),
            api.syncSinceSeqs,
            "manual pagination must hand its pending gap to a real recovery after the page commits",
        )
        assertEquals((10L..100L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals((10L..100L).toList(), dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun realtimeGapRecoverySyncsFromVisiblePayloadSeqWhenPagingGateIsClosed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("gap body", id = "m12", seq = 12)
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))

        val payload = store.state.value.payload
        assertEquals(listOf("current body", "gap body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun refreshLatestAfterVisibleMaxSeqSyncsFromVisiblePayloadSeq() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("resumed body", id = "m11", seq = 11)
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)

        val payload = store.state.value.payload
        assertEquals(listOf("current body", "resumed body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals(emptyList(), api.afterSeqs)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun channelRealtimeMessageNewGapSyncsFromVisibleTailWhenPagingGateIsClosed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("gap body", id = "m11", seq = 11)
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "latest across gap"))
            )
        )

        val payload = store.state.value.payload
        assertEquals(listOf("current body", "gap body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun currentRealtimeEventDuringGapRecoveryIsAbsorbedWithoutStartingDuplicateSync() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("recovered body", id = "m12", seq = 12),
            )
        api.syncStarted = CompletableDeferred()
        api.releaseSync = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val first = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "gap trigger")),
                ),
            )
        }
        api.syncStarted?.await()
        assertEquals("loading", store.state.value.payload.newerMessagesStatus)
        assertEquals("loading", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        val second = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m13", seq = 13, body = "arrived during recovery")),
                ),
            )
        }
        second.join()
        assertEquals(
            listOf(10L),
            api.syncSinceSeqs,
            "a current event must join the in-flight recovery instead of starting a second sync",
        )
        assertEquals("loading", store.state.value.payload.newerMessagesStatus)

        api.releaseSync?.complete(Unit)
        first.join()

        assertEquals(
            listOf(10L),
            api.syncSinceSeqs,
            "the absorbed event must not start a delayed duplicate sync after recovery completes",
        )
        assertEquals(1, store.state.value.payload.messages.count { it.id == "m12" })
        assertEquals(
            1,
            store.state.value.payload.messages.count { it.id == "m13" },
            "the event that arrived during recovery must land exactly once rather than being dropped",
        )
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf(10L, 12L, 13L),
            dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq },
        )
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun realtimeGapAfterSuccessfulTerminalStartsFreshRecoveryWithoutDuplicateOwner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.syncBodyTexts.addLast(syncRangeResponse(11L..12L))
        api.syncBodyTexts.addLast(syncRangeResponse(13L..14L))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "first gap")),
            ),
        )
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m14", seq = 14, body = "terminal-first successor")),
            ),
        )

        assertEquals(
            listOf(10L, 12L),
            api.syncSinceSeqs,
            "once the first terminal clears ownership, the next gap must start one fresh recovery",
        )
        assertEquals((10L..14L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals((10L..14L).toList(), dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun duplicateAndOutOfOrderRealtimeEventsDoNotExtendActiveRecoveryTarget() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.syncBodyTexts.addLast(syncRangeResponse(11L..12L))
        api.syncStarted = CompletableDeferred()
        api.releaseSync = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val recovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "gap trigger")),
                ),
            )
        }
        api.syncStarted?.await()

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m13", seq = 13, body = "current duplicate target")),
            ),
        )
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m13", seq = 13, body = "duplicate delivery")),
            ),
        )
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m10", seq = 10, body = "out of order duplicate")),
            ),
        )

        api.releaseSync?.complete(Unit)
        recovery.join()

        assertEquals(
            listOf(10L),
            api.syncSinceSeqs,
            "duplicate or lower realtime sequences must not bump target revision into another fetch",
        )
        assertEquals(1, store.state.value.payload.messages.count { it.id == "m10" })
        assertEquals(1, store.state.value.payload.messages.count { it.id == "m13" })
        assertEquals(listOf(10L, 11L, 12L, 13L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf(10L, 11L, 12L, 13L),
            dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq },
        )
        scope.cancel()
    }

    @Test
    fun nonGapRealtimeEventSuspendedBeforeLinearizationMergesIntoLatestRecoveryPayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = ParkableReadSessionStore(sampleSession())
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("recovered body", id = "m12", seq = 12),
            )
        api.syncStarted = CompletableDeferred()
        api.releaseSync = CompletableDeferred()
        val dataSource = testSharedThreadDataSource(networkApi = api, sessionStore = sessionStore, cacheEnvironmentIdProvider = { TestThreadEnvironmentId })
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val recovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "gap trigger")),
                ),
            )
        }
        api.syncStarted?.await()

        sessionStore.parkNextRead()
        val nonGap = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m11", seq = 11, body = "non-gap while recovery lands")),
                ),
            )
        }
        sessionStore.nextReadStarted.await()

        api.releaseSync?.complete(Unit)
        recovery.join()
        assertEquals(listOf(10L, 12L), store.state.value.payload.messages.mapNotNull { it.seq })

        sessionStore.releaseNextRead.complete(Unit)
        nonGap.join()

        assertEquals(
            listOf(10L, 11L, 12L),
            store.state.value.payload.messages.mapNotNull { it.seq },
            "a non-gap event must reduce against the latest recovery payload, not overwrite it from its pre-read snapshot",
        )
        assertEquals(1, store.state.value.payload.messages.count { it.id == "m11" })
        assertEquals(1, store.state.value.payload.messages.count { it.id == "m12" })
        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf(10L, 11L, 12L),
            dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq },
        )
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun futureRealtimeEventDoesNotAdvanceMultiPageRecoveryCursorPastFetchedPage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.syncBodyTexts.addLast(syncRangeResponse(11L..60L))
        api.syncBodyTexts.addLast(syncRangeResponse(61L..99L))
        api.syncStarted = CompletableDeferred()
        api.releaseSync = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val recovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m61", seq = 61, body = "gap trigger")),
                ),
            )
        }
        api.syncStarted?.await()

        val future = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m100", seq = 100, body = "future during recovery")),
                ),
            )
        }
        future.join()
        assertEquals(listOf(10L), api.syncSinceSeqs)

        api.releaseSync?.complete(Unit)
        recovery.join()

        assertEquals(
            listOf(10L, 60L),
            api.syncSinceSeqs,
            "a future live event must not move the recovery cursor beyond the max seq of the fetched full page",
        )
        assertEquals(
            (10L..100L).toList(),
            store.state.value.payload.messages.mapNotNull { it.seq },
            "both recovery pages and the future realtime event must survive exactly once with no skipped middle range",
        )
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals((10L..100L).toList(), dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun futureRealtimeEventExtendsRecoveryAfterShortSnapshotPage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.syncBodyTexts.addLast(syncRangeResponse(11L..12L))
        api.syncBodyTexts.addLast(syncRangeResponse(13L..62L))
        api.syncBodyTexts.addLast(syncRangeResponse(63L..99L))
        api.syncStarted = CompletableDeferred()
        api.releaseSync = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val recovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "initial gap target")),
                ),
            )
        }
        api.syncStarted?.await()

        val future = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m100", seq = 100, body = "future after snapshot")),
                ),
            )
        }
        future.join()
        api.releaseSync?.complete(Unit)
        recovery.join()

        assertEquals(
            listOf(10L, 12L, 62L),
            api.syncSinceSeqs,
            "a target that advances while a short response is in flight must extend recovery from that page's max seq",
        )
        assertEquals(
            (10L..100L).toList(),
            store.state.value.payload.messages.mapNotNull { it.seq },
            "the short snapshot page, follow-up range, and future event must all survive exactly once",
        )
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals((10L..100L).toList(), dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun newGenerationRecoveryReplacesParkedOldGenerationBeforeOldTerminal() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("old baseline", id = "m10", seq = 10, historyLimited = false),
            )
        api.syncBodyTexts.addLast(syncRangeResponse(11L..12L))
        api.syncBodyTexts.addLast(syncRangeResponse(21L..22L))
        api.syncStartedSignals.addLast(oldStarted)
        api.syncStartedSignals.addLast(newStarted)
        api.syncReleases.addLast(releaseOld)
        api.syncReleases.addLast(releaseNew)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val oldRecovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "old route gap")),
                ),
            )
        }
        oldStarted.await()

        api.bodyText = messagesResponse("new baseline", id = "m20", seq = 20, historyLimited = false)
        store.accept(SharedThreadIntent.Refresh)
        assertEquals(listOf(20L), store.state.value.payload.messages.mapNotNull { it.seq })

        val newRecovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m22", seq = 22, body = "new route gap")),
                ),
            )
        }
        newStarted.await()
        assertEquals(
            listOf(10L, 20L),
            api.syncSinceSeqs,
            "the new route generation must start its own recovery before the parked old generation terminates",
        )

        releaseNew.complete(Unit)
        newRecovery.join()
        assertEquals(listOf(20L, 21L, 22L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)

        releaseOld.complete(Unit)
        oldRecovery.join()
        assertEquals(
            listOf(20L, 21L, 22L),
            store.state.value.payload.messages.mapNotNull { it.seq },
            "the old route terminal must neither clear nor overwrite the successor recovery state",
        )
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf(20L, 21L, 22L),
            dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq },
        )
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun newRouteRecoveryReplacesParkedOldRouteBeforeOldTerminal() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("old baseline", id = "m10", seq = 10, historyLimited = false),
            )
        api.bodyTextByThreadId["channel-2"] =
            messagesResponse(
                "new route baseline",
                id = "m20",
                seq = 20,
                historyLimited = false,
                channelId = "channel-2",
            )
        api.syncBodyTexts.addLast(syncRangeResponse(11L..12L))
        api.syncBodyTexts.addLast(syncRangeResponse(21L..22L, channelId = "channel-2"))
        api.syncStartedSignals.addLast(oldStarted)
        api.syncStartedSignals.addLast(newStarted)
        api.syncReleases.addLast(releaseOld)
        api.syncReleases.addLast(releaseNew)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val oldRecovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "old route gap")),
                ),
            )
        }
        oldStarted.await()

        val newRoute =
            basePayload().copy(
                instanceId = "shared-shell-thread-channel-2",
                title = "channel-2",
                sourceThreadId = "channel-2",
                streamChannelId = "channel-2",
                canLoadMoreAfter = false,
            )
        store.accept(SharedThreadIntent.Start(newRoute))
        assertEquals(listOf(20L), store.state.value.payload.messages.mapNotNull { it.seq })

        val newRecovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(
                        message(
                            threadId = "channel-2",
                            id = "m22",
                            seq = 22,
                            body = "new route gap",
                        ),
                    ),
                ),
            )
        }
        newStarted.await()
        assertEquals(listOf(10L, 20L), api.syncSinceSeqs)

        releaseNew.complete(Unit)
        newRecovery.join()
        assertEquals(listOf(20L, 21L, 22L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)

        releaseOld.complete(Unit)
        oldRecovery.join()
        assertEquals(listOf(20L, 21L, 22L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals(
            listOf(20L, 21L, 22L),
            dataSource.cachedPayload("channel-2")?.messages?.mapNotNull { it.seq },
        )
        assertEquals("idle", dataSource.cachedPayload("channel-2")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun cancelledRecoveryReleasesOwnershipAndRestoresIdleForSuccessor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.syncBodyTexts.addLast(syncRangeResponse(11L..12L))
        api.syncBodyTexts.addLast(syncRangeResponse(11L..13L))
        api.syncStartedSignals.addLast(oldStarted)
        api.syncReleases.addLast(oldRelease)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val cancelled = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "cancelled gap")),
                ),
            )
        }
        oldStarted.await()
        cancelled.cancel()
        cancelled.join()

        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m13", seq = 13, body = "successor gap")),
            ),
        )
        assertEquals(listOf(10L, 10L), api.syncSinceSeqs)
        assertEquals(listOf(10L, 11L, 12L, 13L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf(10L, 11L, 12L, 13L),
            dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq },
        )
        scope.cancel()
    }

    @Test
    fun clearInvalidatesParkedRecoveryAndRestoresVisibleAndCachedIdle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncRangeResponse(11L..12L),
            )
        api.syncStarted = CompletableDeferred()
        api.releaseSync = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val recovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "cleared gap")),
                ),
            )
        }
        api.syncStarted?.await()
        assertEquals("loading", store.state.value.payload.newerMessagesStatus)
        assertEquals("loading", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        store.clear()
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        api.releaseSync?.complete(Unit)
        recovery.join()

        assertEquals(
            listOf(10L),
            store.state.value.payload.messages.mapNotNull { it.seq },
            "a response owned by the generation invalidated by clear must perform zero payload mutation",
        )
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(listOf(10L), dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun emptyRecoveryResponseTerminatesIdleAndUpdatesCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = "[]",
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "empty recovery")),
            ),
        )

        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun errorRecoveryResponseReleasesStateForSuccessfulRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.syncBodyTexts.addLast("{}")
        api.syncResponseCodes.addLast(500)
        api.syncBodyTexts.addLast(syncRangeResponse(11L..13L))
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "failing recovery")),
            ),
        )
        assertEquals("error", store.state.value.payload.newerMessagesStatus)
        assertEquals("error", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m13", seq = 13, body = "retry recovery")),
            ),
        )
        assertEquals(listOf(10L, 10L), api.syncSinceSeqs)
        assertEquals(listOf(10L, 11L, 12L, 13L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun fullRecoveryPageWithoutCursorProgressTerminatesWithoutLooping() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncRepeatedSeqResponse(count = 50, seq = 10L),
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "no progress recovery")),
            ),
        )

        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun noPermissionRecoveryResponseTerminatesWithoutWritingRetiredOwnerCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val auth = InMemorySlockAuthSessionStore(sampleSession())
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        val dataSource = testSharedThreadDataSource(networkApi = api, sessionStore = auth, cacheEnvironmentIdProvider = { TestThreadEnvironmentId })
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        val cacheContext = requireNotNull(dataSource.captureReplyCacheProducerContext())
        auth.clearSession()

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "no permission recovery")),
            ),
        )

        assertEquals(emptyList(), api.syncSinceSeqs)
        assertEquals("error", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            "idle",
            dataSource.cachedPayload(
                threadId = "channel-1",
                ownerScopeId = requireNotNull(cacheContext.provenance.threadPayloadOwnerScopeId),
                serverId = cacheContext.provenance.serverId,
                principalId = cacheContext.provenance.principalId
            )?.newerMessagesStatus
        )
        scope.cancel()
    }

    @Test
    fun replyThreadUpdatedReplyCountGapSyncsFromVisiblePayloadTail() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val api =
            FakeThreadApi(
                bodyText =
                    messagesResponse(
                        "current body",
                        id = "m10",
                        seq = 10,
                        historyLimited = false,
                        channelId = "thread-m1"
                    ),
                newerBodyText =
                    syncResponse(
                        body = "recovered body",
                        id = "m11",
                        seq = 11,
                        channelId = "thread-m1"
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1").copy(canLoadMoreAfter = false)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(route))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    threadChannelId = ChannelId("thread-m1"),
                    parentMessageId = MessageId("m1"),
                    replyCount = 2,
                    lastReplyAt = "2026-05-19T16:02:00Z",
                    participantIds = emptyList()
                ),
                activeReplyParentMessageId = MessageId("m1")
            )
        )

        val payload = store.state.value.payload
        assertEquals(listOf("current body", "recovered body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        assertNull(localStore.readThread(ThreadId("thread-m1")))
        assertEquals(
            listOf("current body", "recovered body"),
            dataSource.cachedPayload("thread-m1")?.messages?.map { it.body }
        )
        scope.cancel()
    }

    @Test
    fun realtimeGapRecoverySkipsWhenVisiblePayloadAlreadyCaughtUp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("unused", id = "m12", seq = 12)
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 10))

        assertEquals(emptyList(), api.syncSinceSeqs)
        assertEquals(listOf("current body"), store.state.value.payload.messages.map { it.body })
        scope.cancel()
    }

    @Test
    fun foregroundRefreshRebaselineDropsInFlightRealtimeGapResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("stale gap body", id = "m11", seq = 11)
            )
        api.syncStarted = CompletableDeferred()
        api.releaseSync = CompletableDeferred()
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val gapJob = launch {
            store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))
        }
        api.syncStarted?.await()
        api.bodyText = messagesResponse("foreground body", id = "m20", seq = 20, historyLimited = false)
        store.accept(SharedThreadIntent.Refresh)
        api.releaseSync?.complete(Unit)
        gapJob.join()

        val payload = store.state.value.payload
        assertEquals(listOf("foreground body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals("idle", payload.newerMessagesStatus)
        assertEquals(listOf("foreground body"), dataSource.cachedPayload("channel-1")?.messages?.map { it.body })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        assertTrue(logs.any { it.startsWith("thread_realtime_gap_stale_drop") && it.contains("reason=stale_token") })
        scope.cancel()
    }

    @Test
    fun loadNewerMessagesMergesSystemRowsFromChannelAfter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText =
                    systemMessagesResponse(
                        body = "released #252 \"Implement Thread timeline system-message rendering\"",
                        id = "sys-release-1",
                        seq = 12
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        store.accept(SharedThreadIntent.LoadNewer)

        val messages = store.state.value.payload.messages
        assertEquals(listOf("m10", "sys-release-1"), messages.map { it.id })
        val systemRow = messages.last()
        assertEquals("System", systemRow.messageType)
        assertEquals("system", systemRow.authorKind)
        assertEquals("released #252 \"Implement Thread timeline system-message rendering\"", systemRow.body)
        assertEquals(listOf(10L), api.afterSeqs)
        assertEquals(emptyList(), api.syncSinceSeqs)
        scope.cancel()
    }

    @Test
    fun realtimeGapRecoveryWithNewArrivalsBackfillsSecondaryTimeline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("gap body", id = "m12", seq = 12)
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "current body",
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m10",
                                        seq = 10,
                                        body = "current body",
                                        threadChannelId = "thread-m10",
                                        replyCount = 0
                                    )
                                )
                        )
                    ),
                newerResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "gap body",
                            id = "m12",
                            seq = 12
                        )
                    ),
                threadReplySummariesResult =
                    DataResult.Content(
                        ThreadReplySummariesSnapshot(
                            threads =
                                listOf(
                                    InboxItem(
                                        key = "thread:m10",
                                        kind = InboxItemKind.Thread,
                                        channelId = ChannelId("thread-m10"),
                                        channelName = "reply thread",
                                        threadChannelId = ChannelId("thread-m10"),
                                        parentChannelId = ChannelId("channel-1"),
                                        parentMessageId = MessageId("m10"),
                                        replyCount = 7,
                                        unreadCount = 2,
                                        firstUnreadMessageId = MessageId("reply-6"),
                                        latestReplies =
                                            listOf(
                                                ReplyPreview(
                                                    messageId = "reply-6",
                                                    seq = 6,
                                                    content = "first unread",
                                                    senderId = "u2",
                                                    senderType = "human",
                                                    senderName = "cindy",
                                                    senderAvatarUrl = null,
                                                    createdAt = "2026-05-19T16:06:00Z",
                                                    senderDisplayName = "Cindy",
                                                )
                                            )
                                    )
                                ),
                            followedThreadKeys = setOf("thread:m10")
                        )
                    )
            )
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        store.accept(SharedThreadIntent.FirstContentReady)
        delay(1L)

        val summariesBefore = repository.getThreadReplySummariesRequests.size

        store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))

        try {
            withTimeout(5_000) {
                while (repository.syncNewerRequests.map { it.second } != listOf(10L)) delay(10)
            }
        } catch (e: TimeoutCancellationException) {
            fail(
                "timed out waiting for syncNewerMessages, " +
                    "syncs=${repository.syncNewerRequests} logs=$logs"
            )
        }
        val payload = store.state.value.payload
        assertEquals(listOf("current body", "gap body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), repository.syncNewerRequests.map { it.second })
        withTimeout(5_000) {
            while (
                logs.none { it.startsWith("thread_secondary_backfill") && it.contains("trigger=resume_gap") }
            ) {
                delay(10)
            }
        }
        withTimeout(5_000) {
            while (repository.getThreadReplySummariesRequests.size <= summariesBefore) delay(10)
        }
        scope.cancel()
    }

    @Test
    fun realtimeGapRecoveryStaleDropSkipsSecondaryBackfill() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("stale gap body", id = "m11", seq = 11)
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "foreground body",
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m20",
                                        seq = 20,
                                        body = "foreground body",
                                        threadChannelId = "thread-m20",
                                        replyCount = 0
                                    )
                                )
                        )
                    ),
                threadReplySummariesResult =
                    DataResult.Content(
                        ThreadReplySummariesSnapshot(
                            threads = emptyList(),
                            followedThreadKeys = emptySet()
                        )
                    )
            )
        repository.syncNewerStarted = CompletableDeferred()
        repository.releaseSyncNewer = CompletableDeferred()
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val gapJob = launch {
            store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))
        }
        repository.syncNewerStarted?.await()
        api.bodyText = messagesResponse("foreground body", id = "m20", seq = 20, historyLimited = false)
        store.accept(SharedThreadIntent.Refresh)
        val summariesBefore = repository.getThreadReplySummariesRequests.size
        repository.releaseSyncNewer?.complete(Unit)
        withTimeout(10_000) { gapJob.join() }
        delay(1L)

        val summariesAfter = repository.getThreadReplySummariesRequests.size
        assertTrue(logs.any { it.startsWith("thread_realtime_gap_stale_drop") && it.contains("reason=stale_token") })
        assertTrue(
            logs.none { it.startsWith("thread_secondary_backfill") && it.contains("trigger=resume_gap") },
            "expected no secondary backfill after stale drop, got: $logs"
        )
        assertEquals(summariesBefore, summariesAfter)
        scope.cancel()
    }

    internal class FakeThreadApi(
        var bodyText: String = messagesResponse("network body"),
        var olderBodyText: String = messagesResponse("older body", id = "older", seq = 1, historyLimited = false),
        var newerBodyText: String = messagesResponse("newer body", id = "newer", seq = 100, historyLimited = false),
        var newerResponseCode: Int = 200,
        var failChannelMessages: Boolean = false,
        var channelMessagesResponseCode: Int = 200
    ) : SharedThreadNetworkApi {
        var channelMessageCalls: Int = 0
        var failReadAll: Boolean = false
        var readAllDelayMillis: Long = 0L
        val readAllChannels: MutableList<String> = mutableListOf()
        val readSeqRequests: MutableList<Pair<String, Long>> = mutableListOf()
        var activeReadAllCalls: Int = 0
        var maxConcurrentReadAllCalls: Int = 0
        val beforeSeqs: MutableList<Long> = mutableListOf()
        val afterSeqs: MutableList<Long> = mutableListOf()
        val syncSinceSeqs: MutableList<Long> = mutableListOf()
        val channelMessageLimits: MutableList<Int> = mutableListOf()
        val syncLimits: MutableList<Int> = mutableListOf()
        val channelIds: MutableList<String> = mutableListOf()
        var sendMessageCalls: Int = 0
        /** Optional canonical POST /messages body for tests that exercise the send parser. */
        var sendResponseBody: String? = null
        var lastAttachmentIds: List<String> = emptyList()
        var lastRandomId: String? = null
        val savedMessageIds: MutableList<String> = mutableListOf()
        val unsavedMessageIds: MutableList<String> = mutableListOf()
        val addedReactions: MutableList<Pair<String, String>> = mutableListOf()
        val removedReactions: MutableList<Pair<String, String>> = mutableListOf()
        val createdThreads: MutableList<Pair<String, String>> = mutableListOf()
        val mentionActionRequests: MutableList<Pair<String, List<String>>> = mutableListOf()
        val sentAttachmentComments: MutableList<Pair<String, String>> = mutableListOf()
        val bodyTextByThreadId: MutableMap<String, String> = mutableMapOf()
        val channelMessagesStartedByThreadId: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()
        val releaseChannelMessagesByThreadId: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()
        var channelMessagesStarted: CompletableDeferred<Unit>? = null
        var releaseChannelMessages: CompletableDeferred<Unit>? = null
        var afterMessagesStarted: CompletableDeferred<Unit>? = null
        var releaseAfterMessages: CompletableDeferred<Unit>? = null
        var syncStarted: CompletableDeferred<Unit>? = null
        var releaseSync: CompletableDeferred<Unit>? = null
        val syncStartedSignals = ArrayDeque<CompletableDeferred<Unit>>()
        val syncReleases = ArrayDeque<CompletableDeferred<Unit>>()
        val syncBodyTexts = ArrayDeque<String>()
        val syncResponseCodes = ArrayDeque<Int>()

        override suspend fun channelMessages(
            threadId: String,
            limit: Int,
            beforeSeq: Long?,
            afterSeq: Long?
        ): HomeNetworkResponse {
            channelMessageCalls += 1
            channelIds += threadId
            channelMessageLimits += limit
            if (beforeSeq == null && afterSeq == null) {
                channelMessagesStarted?.complete(Unit)
                channelMessagesStartedByThreadId[threadId]?.complete(Unit)
                releaseChannelMessages?.await()
                releaseChannelMessagesByThreadId[threadId]?.await()
            }
            if (failChannelMessages) throw IllegalStateException("offline")
            val responseBody = bodyTextByThreadId[threadId] ?: bodyText
            return when {
                beforeSeq != null -> {
                    beforeSeqs += beforeSeq
                    HomeNetworkResponse(code = 200, bodyText = olderBodyText)
                }
                afterSeq != null -> {
                    afterSeqs += afterSeq
                    afterMessagesStarted?.complete(Unit)
                    releaseAfterMessages?.await()
                    HomeNetworkResponse(code = newerResponseCode, bodyText = newerBodyText)
                }
                else -> HomeNetworkResponse(code = channelMessagesResponseCode, bodyText = responseBody)
            }
        }

        override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse {
            val startedSignal = syncStartedSignals.removeFirstOrNull()
            val callRelease = syncReleases.removeFirstOrNull()
            val responseBody = syncBodyTexts.removeFirstOrNull() ?: newerBodyText
            syncSinceSeqs += sinceSeq
            syncLimits += limit
            startedSignal?.complete(Unit)
            syncStarted?.complete(Unit)
            (callRelease ?: releaseSync)?.await()
            return HomeNetworkResponse(code = syncResponseCodes.removeFirstOrNull() ?: 200, bodyText = responseBody)
        }

        override suspend fun sendMessage(
            channelId: String,
            body: String,
            asTask: Boolean,
            attachmentIds: List<String>,
            randomId: String?
        ): HomeNetworkResponse {
            sendMessageCalls += 1
            lastAttachmentIds = attachmentIds
            lastRandomId = randomId
            return HomeNetworkResponse(code = 200, bodyText = sendResponseBody ?: messagesResponse(body))
        }

        override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): HomeNetworkResponse {
            mentionActionRequests += action to resolutionIds
            return HomeNetworkResponse(code = 200, bodyText = """{"results":[]}""")
        }

        override suspend fun attachmentUrl(attachmentId: String, disposition: String?): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"url":"https://cdn.example/$attachmentId"}""")

        override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"url":"https://cdn.example/$attachmentId/html"}""")

        override suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"comments":[],"threadChannelId":"comments-$attachmentId"}""")

        override suspend fun sendAttachmentComment(attachmentId: String, body: String): HomeNetworkResponse {
            sentAttachmentComments += attachmentId to body
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        }

        override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse {
            activeReadAllCalls += 1
            maxConcurrentReadAllCalls = maxOf(maxConcurrentReadAllCalls, activeReadAllCalls)
            readAllChannels += channelId
            try {
                if (readAllDelayMillis > 0L) {
                    delay(readAllDelayMillis)
                }
                return if (failReadAll) {
                    HomeNetworkResponse(code = 500, bodyText = null, errorBodyText = "read_failed")
                } else {
                    HomeNetworkResponse(code = 200, bodyText = "{}")
                }
            } finally {
                activeReadAllCalls -= 1
            }
        }

        override suspend fun markChannelReadToSeq(channelId: String, seq: Long): HomeNetworkResponse {
            readSeqRequests += channelId to seq
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true,"seq":$seq}""")
        }

        override suspend fun saveMessage(messageId: String): HomeNetworkResponse {
            savedMessageIds += messageId
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"$messageId","saved":true}""")
        }

        override suspend fun unsaveMessage(messageId: String): HomeNetworkResponse {
            unsavedMessageIds += messageId
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"$messageId","saved":false}""")
        }

        override suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse {
            addedReactions += messageId to emoji
            return HomeNetworkResponse(
                code = 200,
                bodyText = """{"id":"$messageId","reactions":[{"emoji":"$emoji","count":1,"reactorIds":["u1"],"reactorNames":["User"]}]}"""
            )
        }

        override suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse {
            removedReactions += messageId to emoji
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"$messageId","reactions":[]}""")
        }

        override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse {
            createdThreads += channelId to parentMessageId
            return HomeNetworkResponse(code = 200, bodyText = """{"threadChannelId":"thread-$parentMessageId","parentMessageId":"$parentMessageId","created":true}""")
        }

        override suspend fun messageContext(messageId: String, channelId: String?): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"channelId":"$channelId","targetMessageId":"$messageId","messages":[],"historyLimited":false}"""
            )
    }

    private class ParkableReadSessionStore(
        initialSession: StoredAuthSession,
    ) : SlockAuthSessionStore {
        private val delegate = InMemorySlockAuthSessionStore(initialSession)
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

    private class FakeSavedStatusRepository(
        private val savedIds: Set<MessageId>
    ) : SavedStatusRepository {
        val checkedMessageIds: MutableList<List<MessageId>> = mutableListOf()

        override suspend fun checkSavedMessages(
            serverId: ServerId,
            messageIds: List<MessageId>
        ): DataResult<SavedMessageStatusSnapshot> {
            checkedMessageIds += messageIds
            return DataResult.Content(SavedMessageStatusSnapshot(savedIds))
        }
    }

    private class RecordingAgentsApi : SlockAgentsApi {
        val createBodies: MutableList<String> = mutableListOf()

        override suspend fun agents(): HomeNetworkResponse = error("unused")
        override suspend fun agent(agentId: MemberId): HomeNetworkResponse = error("unused")

        override suspend fun createAgent(body: String): HomeNetworkResponse {
            createBodies += body
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"agent-created","name":"Noel","status":"active"}""")
        }

        override suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun startAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun agentActivityLog(agentId: MemberId, limit: Int): HomeNetworkResponse = error("unused")
        override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?): HomeNetworkResponse = error("unused")
        override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse = error("unused")
        override suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse = error("unused")

        override suspend fun agentExternalStatus(agentId: MemberId): HomeNetworkResponse = error("unused")

        override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    }

    private class RecordingComputersApi : SlockComputersApi {
        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
    }

    private class RecordingActionCardsApi(
        private val markResponse: HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"messageId":"m-card","metadata":{"kind":"action-card"}}"""),
        private val executeResponse: HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {
                      "messageId":"m-card",
                      "metadata":{
                        "kind":"action-card",
                        "state":"executed",
                        "executedByUserName":"artin",
                        "action":{
                          "type":"integration:approve_agent_login",
                          "requestId":"request-1",
                          "agentId":"agent-1",
                          "agentName":"KMP-专家",
                          "clientId":"client-1",
                          "clientKey":"hands",
                          "clientName":"Hands",
                          "scopes":["identity","profile"]
                        },
                        "result":{
                          "kind":"agent-integration-login",
                          "id":"grant-1",
                          "name":"Hands"
                        }
                      }
                    }
                    """.trimIndent()
            )
    ) : SlockIntegrationsActionsPushApi {
        val calls: MutableList<String> = mutableListOf()

        override suspend fun integrationsOverview(): HomeNetworkResponse = error("unused")
        override suspend fun integrationClients(): HomeNetworkResponse = error("unused")
        override suspend fun createIntegrationClient(body: String): HomeNetworkResponse = error("unused")
        override suspend fun approveIntegrationRequest(requestId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun denyIntegrationRequest(requestId: String): HomeNetworkResponse = error("unused")
        override suspend fun revokeIntegrationGrant(grantId: String): HomeNetworkResponse = error("unused")
        override suspend fun agentIntegrations(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse {
            calls += "POST actions/${messageId.value}/execute body=$body"
            return executeResponse
        }
        override suspend fun emitActionEvent(messageId: MessageId, body: String): HomeNetworkResponse = error("unused")

        override suspend fun markActionExecuted(messageId: MessageId, body: String): HomeNetworkResponse {
            calls += "POST actions/${messageId.value}/mark-executed body=$body"
            return markResponse
        }

        override suspend fun pushVapidKey(): HomeNetworkResponse = error("unused")
        override suspend fun recordPushPromptEvent(body: String): HomeNetworkResponse = error("unused")
        override suspend fun subscribePush(body: String): HomeNetworkResponse = error("unused")
        override suspend fun unsubscribePush(body: String): HomeNetworkResponse = error("unused")
        override suspend fun testPush(): HomeNetworkResponse = error("unused")
    }

    private class FakeMemberRepository(
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
            agentId: MemberId
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

    private class FakeConversationRepository(
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

        override suspend fun markThreadDone(threadChannelId: ChannelId): DataResult<ThreadActionResult> = error("unused")

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

    private class FailingThreadWriteStore(
        private val delegate: ComposeLocalDataStore
    ) : ComposeLocalDataStore by delegate {
        override suspend fun writeThread(snapshot: ThreadSnapshot) {
            error("thread write failed")
        }
    }

    private fun SharedThreadStore.startLocalSendForCurrentOwner(
        dataSource: SharedThreadDataSource,
        message: KuiklyThreadMessageItem,
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

    private suspend fun SharedThreadStore.failLocalSendForCurrentOwner(
        dataSource: SharedThreadDataSource,
        messageId: String,
        errorCode: String?,
        errorMessage: String? = null
    ) {
        val provenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = messageId,
                errorCode = errorCode,
                errorMessage = errorMessage,
                expectedProvenance = provenance,
                expectedRoute = state.value.payload.resolveThreadRouteIdentity()
            )
        )
    }

    private suspend fun SharedThreadStore.replaceLocalSendForCurrentOwner(
        dataSource: SharedThreadDataSource,
        messageId: String,
        page: ThreadMessagesPage
    ) {
        val provenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = messageId,
                page = page,
                expectedProvenance = provenance,
                expectedRoute = state.value.payload.resolveThreadRouteIdentity()
            )
        )
    }

    private fun basePayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true
        )

    private fun focusedReplyRoute(): KuiklyThreadScreenPayload =
        basePayload().copy(
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-parent-1",
            streamChannelId = "thread-parent-1",
            parentChannelId = "parent-channel-1",
            parentMessageId = "parent-1",
            originParentMessageId = "parent-1",
            originStatus = "loading",
            focusedMessageId = "reply-focus-1"
        )

    private fun directMessagePayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-dm-1",
            title = "artin",
            surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
            sourceThreadId = "dm-1",
            streamChannelId = "dm-1",
            status = "loading",
            canSend = true,
            dmPeerName = "artin",
            dmPeerAvatarUrl = "pixel:crown",
            dmPeerAvatarLabel = "AR",
        )

    private fun replyRouteWithWrongHint(): KuiklyThreadScreenPayload =
        basePayload().copy(
            instanceId = "shared-shell-thread-wrong-thread",
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = "wrong-thread",
            streamChannelId = "wrong-thread",
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            originParentMessageId = "m1",
            title = ThreadReplyPageTitle,
            subtitle = "#general",
            showModeTabs = false
        )

    private fun replyPayload(parentMessageId: String, threadId: String): KuiklyThreadScreenPayload =
        basePayload().copy(
            instanceId = "shared-shell-thread-$threadId",
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = threadId,
            streamChannelId = threadId,
            parentChannelId = "channel-1",
            parentMessageId = parentMessageId,
            originParentMessageId = parentMessageId,
            title = ThreadReplyPageTitle,
            subtitle = "#general",
            showModeTabs = false,
            showReplyActions = false
        )

    private fun activityReplyPayload(
        parentKind: ConversationKind,
        parentChannelId: String,
        parentMessageId: String,
        threadId: String
    ): KuiklyThreadScreenPayload {
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

    private fun replyThreadConversation(
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

    private fun unresolvedReplyPayload(parentMessageId: String): KuiklyThreadScreenPayload =
        basePayload().copy(
            instanceId = "shared-shell-thread-pending-$parentMessageId",
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = null,
            streamChannelId = null,
            parentChannelId = "channel-1",
            parentMessageId = parentMessageId,
            originParentMessageId = parentMessageId,
            title = ThreadReplyPageTitle,
            subtitle = "#general",
            showModeTabs = false,
            showReplyActions = false
        )

    private fun contentPayload(body: String, id: String = "m1"): KuiklyThreadScreenPayload =
        contentPayloadForRoute(basePayload(), body, id = id)

    private fun contentPayloadForRoute(
        base: KuiklyThreadScreenPayload,
        body: String,
        id: String = "m1",
        channelId: String = base.sourceThreadId ?: "channel-1"
    ): KuiklyThreadScreenPayload =
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

    private fun canonicalThreadDataSource(): SharedThreadDataSource =
        testSharedThreadDataSource(
            networkApi = FakeThreadApi(failChannelMessages = true),
            sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
        )

    private fun emptyHomeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels = emptyList(),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun unjoinedHomeSnapshot(): HomeSnapshot =
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

    private fun joinedHomeSnapshot(): HomeSnapshot =
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

    private fun joinedHomeSnapshotWithConflictingReplyChild(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(joined = false, canSend = false).toHomeChannelRow(),
                    conversationSummary().copy(
                        joined = true,
                        permissions = ConversationPermissions(canSend = true)
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun unjoinedHomeSnapshotWithConflictingReplyChild(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(joined = true, canSend = true).toHomeChannelRow(),
                    conversationSummary().copy(
                        joined = false,
                        permissions = ConversationPermissions(canSend = false)
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun joinedDmHomeSnapshotWithConflictingReplyChild(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(
                        threadId = "dm-thread-1",
                        parentChannelId = "dm-1",
                        parentMessageId = "dm-parent-message",
                        joined = false,
                        canSend = false
                    ).toHomeChannelRow()
                ),
            directMessages =
                listOf(
                    conversationSummary("dm-1").copy(
                        kind = ConversationKind.DirectMessage,
                        channelId = ChannelId("dm-1"),
                        joined = true,
                        permissions = ConversationPermissions(canSend = true)
                    ).toHomeDirectMessageRow()
                ),
            unreadTotal = 0
        )

    private fun joinedJointHomeSnapshotWithConflictingReplyChild(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(
                        threadId = "joint-thread-1",
                        parentChannelId = "joint-1",
                        parentMessageId = "joint-parent-message",
                        joined = false,
                        canSend = false
                    ).toHomeChannelRow(),
                    conversationSummary("joint-1").copy(
                        kind = ConversationKind.Joint,
                        channelId = ChannelId("joint-1"),
                        channelType = JointChannelKindWireValue,
                        joined = true,
                        permissions = ConversationPermissions(canRead = true, canSend = true)
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun replyChildOnlyHomeSnapshot(
        joined: Boolean,
        canSend: Boolean
    ): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(joined = joined, canSend = canSend).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun sentPage(
        id: String,
        body: String,
        channelId: String = "channel-1",
        threadId: String? = null,
        randomId: String? = null
    ): ThreadMessagesPage =
        ThreadMessagesPage(
            messages =
                listOf(
                    WireMessage(
                        id = id,
                        channelId = channelId,
                        threadId = threadId,
                        senderType = "user",
                        senderId = "u1",
                        senderName = "Artin",
                        messageType = "chat",
                        content = body,
                        createdAt = "2026-05-19T16:01:00Z",
                        seq = 11,
                        randomId = randomId
                    )
                ),
            canLoadMoreBefore = null,
            canLoadMoreAfter = null
        )

    private fun localPendingMessage(
        id: String,
        body: String,
        asTask: Boolean = false,
        attachmentIds: List<String> = emptyList(),
        streamChannelId: String = "channel-1",
        randomId: String? = null
    ): KuiklyThreadMessageItem =
        KuiklyThreadMessageItem(
            id = id,
            authorId = "u1",
            authorName = "Artin",
            isOwn = true,
            body = body,
            threadChannelId = streamChannelId,
            attachments =
                attachmentIds.map { attachmentId ->
                    KuiklyThreadAttachmentItem(
                        id = attachmentId,
                        name = "$attachmentId.txt",
                        mimeType = "text/plain",
                        sizeLabel = "1 KB"
                    )
                },
            deliveryState = ThreadMessageDeliverySending,
            pendingSendContext =
                KuiklyThreadPendingSendContext(
                    channelId = "channel-1",
                    body = body,
                    asTask = asTask,
                    attachmentIds = attachmentIds,
                    attachments =
                        attachmentIds.map { attachmentId ->
                            KuiklyPendingAttachmentItem(
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

    private fun wrongReplyCachedPayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-wrong-thread",
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = "wrong-thread",
            streamChannelId = "wrong-thread",
            parentChannelId = "other-channel",
            parentMessageId = "other-message",
            originParentMessageId = "other-message",
            title = ThreadReplyPageTitle,
            subtitle = "#wrong",
            status = "content",
            messages =
                listOf(
                    KuiklyThreadMessageItem(
                        id = "wrong-reply",
                        authorId = "u1",
                        authorName = "Artin",
                        body = "wrong cached body"
                    )
                ),
            originMessage =
                KuiklyThreadMessageItem(
                    id = "other-message",
                    authorId = "u1",
                    authorName = "Artin",
                    body = "wrong parent"
                ),
            originStatus = "loaded"
        )

    private fun wrongReplyThreadSnapshot(): ThreadSnapshot =
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

    private fun canonicalReplyThreadSnapshot(): ThreadSnapshot =
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

    private fun replyHintRepository(
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

    private fun KuiklyThreadScreenPayload.containsWrongReplyCacheContent(): Boolean =
        title == "wrong" ||
            subtitle == "#wrong" ||
            originMessage?.body == "wrong parent" ||
            messages.any { message -> message.body == "wrong cached body" }

    private fun taskPayload(): KuiklyThreadScreenPayload {
        val message =
            contentPayload("task body").messages.single().copy(
                taskId = "task-1",
                taskStatus = "Todo"
            )
        return contentPayload("task body").copy(
            messages = listOf(message),
            tasks =
                listOf(
                    KuiklyThreadTaskItem(
                        id = "task-1",
                        title = "#44 Task",
                        status = "Todo",
                        taskNumber = 44
                    )
            )
        )
    }

    private fun taskPayloadWithMessageReferenceOnly(): KuiklyThreadScreenPayload {
        val message = contentPayload("task body").messages.single()
        return contentPayload("task body").copy(
            messages = listOf(message),
            tasks =
                listOf(
                    KuiklyThreadTaskItem(
                        id = "task-1",
                        title = "#44 Task",
                        status = "Todo",
                        taskNumber = 44,
                        messageId = "m1"
                    )
                )
        )
    }

    private fun SharedThreadDataSource.cachePayloadForTest(
        payload: KuiklyThreadScreenPayload
    ): Boolean {
        runBlocking { readSession() }
        return cachePayload(payload, serverId = "server-1")
    }

    private suspend fun SharedThreadDataSource.threadPayloadOwnerScopeIdForTest(): String =
        requireNotNull(captureReplyCacheProducerContext()?.provenance?.threadPayloadOwnerScopeId)

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "u1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1"
        )

    private fun replyResolutionSession(userId: String): StoredAuthSession =
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

    private fun testReplyCacheIdentityAuthority(
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

    private fun member(id: String, name: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = name.lowercase(),
            kind = MemberKind.Human,
            presence = Presence.Active
        )

    companion object {
        private fun focusedReplySnapshot(
            threadId: String,
            parentChannelId: String,
            parentMessageId: String,
            focusedMessageId: String
        ): ThreadSnapshot =
            threadSnapshot(
                threadId = threadId,
                messages =
                    listOf(
                        message(
                            threadId = threadId,
                            channelId = threadId,
                            id = focusedMessageId,
                            seq = 2,
                            body = "focused reply"
                        )
                    ),
                parentMessage = null,
                conversation =
                    conversationSummary(threadId).copy(
                        kind = ConversationKind.Thread,
                        channelId = ChannelId(threadId),
                        thread =
                            ThreadConversation(
                                parentChannelId = ChannelId(parentChannelId),
                                parentMessageId = MessageId(parentMessageId),
                                streamChannelId = ChannelId(threadId)
                            )
                    )
            ).copy(highlightedMessageId = MessageId(focusedMessageId))

        private fun threadSnapshot(
            threadId: String = "channel-1",
            body: String = "repository body",
            id: String = "m1",
            seq: Long = 10,
            messages: List<Message> = listOf(message(threadId = threadId, id = id, seq = seq, body = body)),
            replySummaries: ThreadReplySummariesSnapshot = ThreadReplySummariesSnapshot(emptyList()),
            parentMessage: Message? = null,
            conversation: ConversationSummary = conversationSummary(threadId)
        ): ThreadSnapshot =
            ThreadSnapshot(
                conversation = conversation,
                messages = messages,
                replySummaries = replySummaries,
                replySummariesAuthority =
                    if (replySummaries.threads.isEmpty()) {
                        build.raft.app.data.ThreadReplySummariesAuthority.Unavailable
                    } else {
                        build.raft.app.data.ThreadReplySummariesAuthority.Embedded
                    },
                parentMessage = parentMessage
            )

        private fun replySummaries(replyCount: Int): DataResult<ThreadReplySummariesSnapshot> =
            DataResult.Content(
                ThreadReplySummariesSnapshot(
                    threads =
                        listOf(
                            InboxItem(
                                key = "thread:m1",
                                kind = InboxItemKind.Thread,
                                channelId = ChannelId("thread-m1"),
                                channelName = "reply thread",
                                threadChannelId = ChannelId("thread-m1"),
                                parentChannelId = ChannelId("channel-1"),
                                parentMessageId = MessageId("m1"),
                                replyCount = replyCount
                            )
                        )
                )
            )

        private fun conversationSummary(threadId: String = "channel-1"): ConversationSummary =
            ConversationSummary(
                threadId = ThreadId(threadId),
                channelId = ChannelId(threadId),
                kind = ConversationKind.Channel,
                name = "general"
            )

        private fun replyConversation(): ConversationSummary =
            conversationSummary("thread-m1").copy(
                kind = ConversationKind.Thread,
                channelId = ChannelId("thread-m1"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("m1"),
                        streamChannelId = ChannelId("thread-m1")
                    )
            )

        private fun message(
            threadId: String = "channel-1",
            channelId: String = threadId,
            id: String,
            seq: Long,
            body: String,
            threadChannelId: String? = null,
            replyCount: Int = 0,
            unreadReplyCount: Int = 0,
            messageType: MessageType = MessageType.Chat,
            authorKind: MemberKind = MemberKind.Human,
            forwardBundle: ForwardMessageBundle? = null
        ): Message =
            Message(
                id = MessageId(id),
                threadId = ThreadId(threadId),
                channelId = ChannelId(channelId),
                threadChannelId = threadChannelId?.let(::ChannelId),
                author =
                    MemberProfile(
                        id = if (authorKind == MemberKind.System) MemberId("system") else MemberId("u1"),
                        displayName = if (authorKind == MemberKind.System) "System" else "Artin",
                        handle = if (authorKind == MemberKind.System) "system" else "artin",
                        kind = authorKind,
                        presence = Presence.Active
                ),
                seq = seq,
                timestampLabel = "2026-05-19T16:00:00Z",
                body = body,
                messageType = messageType,
                replyCount = replyCount,
                unreadReplyCount = unreadReplyCount,
                forwardBundle = forwardBundle
            )

        private fun messagesResponse(
            body: String,
            id: String = "m1",
            seq: Long = 10,
            historyLimited: Boolean = true,
            channelId: String = "channel-1"
        ): String =
            """
            {
              "historyLimited": $historyLimited,
              "messages": [
                {
                  "id": "$id",
                  "seq": $seq,
                  "channelId": "$channelId",
                  "content": "$body",
                  "createdAt": "2026-05-19T16:00:00Z",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderType": "user",
                  "messageType": "chat"
                }
              ]
            }
            """.trimIndent()

        private fun emptyMessagesResponse(historyLimited: Boolean = false): String =
            """
            {
              "historyLimited": $historyLimited,
              "messages": []
            }
            """.trimIndent()

        private fun systemMessagesResponse(
            body: String,
            id: String,
            seq: Long,
            historyLimited: Boolean = false
        ): String {
            val escapedBody = body.jsonEscaped()
            return """
            {
              "historyLimited": $historyLimited,
              "messages": [
                {
                  "id": "$id",
                  "seq": $seq,
                  "content": "$escapedBody",
                  "createdAt": "2026-06-13T01:00:00Z",
                  "channelId": "channel-1",
                  "senderId": "system",
                  "senderName": "System",
                  "senderType": "user",
                  "messageType": "system"
                }
              ]
            }
            """.trimIndent()
        }

        private fun agentMessagesResponse(): String =
            """
            {
              "historyLimited": true,
              "messages": [
                {
                  "id": "m1",
                  "seq": 10,
                  "content": "agent body",
                  "createdAt": "2026-05-19T16:00:00Z",
                  "senderId": "agent-1",
                  "senderName": "Product-UX-Designer",
                  "senderType": "agent",
                  "messageType": "chat"
                }
              ]
            }
            """.trimIndent()

        private fun syncResponse(
            body: String,
            id: String = "m1",
            seq: Long = 10,
            channelId: String = "channel-1"
        ): String =
            """
            [
              {
                "id": "$id",
                "seq": $seq,
                "channelId": "$channelId",
                "content": "$body",
                "createdAt": "2026-05-19T16:00:00Z",
                "senderId": "u1",
                "senderName": "Artin",
                "senderType": "user",
                "messageType": "chat"
              }
            ]
            """.trimIndent()

        private fun syncRangeResponse(
            seqs: LongRange,
            channelId: String = "channel-1",
        ): String =
            seqs.joinToString(prefix = "[", postfix = "]") { seq ->
                """
                {
                  "id": "m$seq",
                  "seq": $seq,
                  "channelId": "$channelId",
                  "content": "body-$seq",
                  "createdAt": "2026-05-19T16:00:00Z",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderType": "user",
                  "messageType": "chat"
                }
                """.trimIndent()
            }

        private fun syncRepeatedSeqResponse(
            count: Int,
            seq: Long,
        ): String =
            (1..count).joinToString(prefix = "[", postfix = "]") { index ->
                """
                {
                  "id": "stale-$index",
                  "seq": $seq,
                  "channelId": "channel-1",
                  "content": "stale-$index",
                  "createdAt": "2026-05-19T16:00:00Z",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderType": "user",
                  "messageType": "chat"
                }
                """.trimIndent()
            }

        private fun syncSystemResponse(
            body: String,
            id: String,
            seq: Long
        ): String {
            val escapedBody = body.jsonEscaped()
            return """
            [
              {
                "id": "$id",
                "seq": $seq,
                "content": "$escapedBody",
                "createdAt": "2026-06-13T01:00:00Z",
                "channelId": "channel-1",
                "senderId": "system",
                "senderName": "System",
                "senderType": "user",
                "messageType": "system"
              }
            ]
            """.trimIndent()
        }

        private fun String.jsonEscaped(): String =
            replace("\\", "\\\\")
                .replace("\"", "\\\"")
    }
}
