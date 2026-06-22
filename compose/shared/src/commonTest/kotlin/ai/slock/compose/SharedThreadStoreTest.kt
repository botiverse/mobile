package ai.slock.compose

import ai.slock.compose.data.AddChannelMemberRequest
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.ComposeLocalDataStore
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.AgentActivitySnapshot
import ai.slock.compose.data.AgentDetailSnapshot
import ai.slock.compose.data.AgentDmSnapshot
import ai.slock.compose.data.AgentLifecycleActionResult
import ai.slock.compose.data.AgentMachineAssignmentRequest
import ai.slock.compose.data.AgentProfileUpdateRequest
import ai.slock.compose.data.AgentAvatarUploadRequest
import ai.slock.compose.data.AgentReminderSnapshot
import ai.slock.compose.data.AgentResetRequest
import ai.slock.compose.data.AgentSkillsSnapshot
import ai.slock.compose.data.AgentWorkspaceChildrenSnapshot
import ai.slock.compose.data.AgentWorkspaceFilePreviewSnapshot
import ai.slock.compose.data.AgentWorkspaceSnapshot
import ai.slock.compose.data.AgentWorkspaceTreeSnapshot
import ai.slock.compose.data.AttachmentOpenTarget
import ai.slock.compose.data.AttachmentUploadRequest
import ai.slock.compose.data.AttachmentUploadResult
import ai.slock.compose.data.ChannelMemberActionResult
import ai.slock.compose.data.ChannelMemberSnapshot
import ai.slock.compose.data.ChannelArchiveResult
import ai.slock.compose.data.ChannelFilesSnapshot
import ai.slock.compose.data.ChannelJoinResult
import ai.slock.compose.data.ChannelLeaveResult
import ai.slock.compose.data.ChannelDeleteResult
import ai.slock.compose.data.ChannelRepository
import ai.slock.compose.data.ChannelSnapshot
import ai.slock.compose.data.ConversationRepository
import ai.slock.compose.data.CreateChannelRequest
import ai.slock.compose.data.CreateChannelResult
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.DirectMessageSnapshot
import ai.slock.compose.data.HomeNetworkResponse
import ai.slock.compose.data.HomeSnapshot
import ai.slock.compose.data.InMemoryComposeLocalDataStore
import ai.slock.compose.data.InMemorySlockAuthSessionStore
import ai.slock.compose.data.InboxItem
import ai.slock.compose.data.OpenDirectMessageResult
import ai.slock.compose.data.MemberRepository
import ai.slock.compose.data.MemberSnapshot
import ai.slock.compose.data.MessageMutationSummary
import ai.slock.compose.data.MessageReactionSummary
import ai.slock.compose.data.RemoveChannelMemberRequest
import ai.slock.compose.data.SendMessageResult
import ai.slock.compose.data.SavedMessageStatusSnapshot
import ai.slock.compose.data.SavedStatusRepository
import ai.slock.compose.data.SharedCreateAgentCoordinator
import ai.slock.compose.data.SharedThreadNetworkApi
import ai.slock.compose.data.SlockAgentsApi
import ai.slock.compose.data.SlockAgentsApiClient
import ai.slock.compose.data.SlockComputersApi
import ai.slock.compose.data.SlockComputersApiClient
import ai.slock.compose.data.SlockIntegrationsActionsPushApi
import ai.slock.compose.data.SlockIntegrationsActionsPushApiClient
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.data.ThreadActionCard
import ai.slock.compose.data.ThreadActionResult
import ai.slock.compose.data.ThreadMessagesPage
import ai.slock.compose.data.ThreadSnapshot
import ai.slock.compose.data.ThreadListSnapshot
import ai.slock.compose.data.WireMessage
import ai.slock.compose.data.buildSharedThreadPayloadFromMessagesPage
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageReaction
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageType
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.ThreadUpdated
import ai.slock.compose.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedThreadStoreTest {
    @Test
    fun cachedThreadPayloadSurvivesVisibleFetchFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("cached body", payload.messages.single().body)
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun successfulFetchWritesPayloadCacheForNextStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
    fun startAndPagingUseConfiguredThreadPageSize() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))
        store.accept(SharedThreadIntent.LoadOlder)
        store.accept(SharedThreadIntent.LoadNewer)

        assertEquals(listOf(30, 30), api.channelMessageLimits)
        assertEquals(listOf(30), api.syncLimits)
        scope.cancel()
    }

    @Test
    fun messageReactionIntentPatchesOnlyPayloadSliceAndNoopsWhenEquivalent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(contentPayload("cached body"))
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
    fun appendSentPagePatchesTimelineAndPreservesStoreHeaderIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        dataSource.cachePayload(contentPayload("cached body"))
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
        assertEquals("sent body", localStore.readThread(ThreadId("channel-1"))?.messages?.last()?.body)
        scope.cancel()
    }

    @Test
    fun localSendSuccessReplacesTempBubbleWithCanonicalMessage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.ApplyLocalSendStarted(localPendingMessage("local-1", "sent body")))
        store.accept(SharedThreadIntent.ReplaceLocalSendWithSentPage("local-1", sentPage("server-1", "sent body")))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        assertEquals(ThreadMessageDeliverySent, messages.first { it.id == "server-1" }.deliveryState)
        scope.cancel()
    }

    @Test
    fun localSendFailureKeepsFailedBubbleWithRetryContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = FakeThreadApi(),
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession())
                    ),
                scope = scope
            )
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(
            SharedThreadIntent.ApplyLocalSendStarted(
                localPendingMessage(
                    id = "local-1",
                    body = "retry body",
                    asTask = true,
                    attachmentIds = listOf("att-1")
                )
            )
        )

        store.accept(SharedThreadIntent.ApplyLocalSendFailure("local-1", "network_error"))

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
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = FakeThreadApi(),
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession())
                    ),
                scope = scope
            )
        store.accept(SharedThreadIntent.Start(basePayload()))
        val local = localPendingMessage("local-1", "retry body")
        store.accept(SharedThreadIntent.ApplyLocalSendStarted(local))
        store.accept(SharedThreadIntent.ApplyLocalSendFailure("local-1", "offline"))

        store.accept(SharedThreadIntent.ApplyLocalSendStarted(local.copy(deliveryState = ThreadMessageDeliverySending)))
        store.accept(SharedThreadIntent.ApplyLocalSendFailure("local-1", "still_offline"))

        val failedMessages = store.state.value.payload.messages.filter { it.id == "local-1" }
        assertEquals(1, failedMessages.size)
        assertEquals(ThreadMessageDeliveryFailed, failedMessages.single().deliveryState)
        assertEquals("still_offline", failedMessages.single().pendingSendErrorCode)

        store.accept(SharedThreadIntent.ApplyLocalSendStarted(failedMessages.single().copy(deliveryState = ThreadMessageDeliverySending)))
        store.accept(SharedThreadIntent.ReplaceLocalSendWithSentPage("local-1", sentPage("server-1", "retry body")))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        scope.cancel()
    }

    @Test
    fun localSendReplacementDoesNotDuplicateServerMessageAlreadyInFlow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = FakeThreadApi(),
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession())
                    ),
                scope = scope
            )
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.ApplyLocalSendStarted(localPendingMessage("local-1", "sent body")))
        store.accept(SharedThreadIntent.AppendSentPage(sentPage("server-1", "sent body")))

        store.accept(SharedThreadIntent.ReplaceLocalSendWithSentPage("local-1", sentPage("server-1", "sent body")))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        scope.cancel()
    }

    @Test
    fun retryPreservesAttachmentIdsAcrossFailureAndRetryCycle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = FakeThreadApi(),
                        sessionStore = InMemorySlockAuthSessionStore(sampleSession())
                    ),
                scope = scope
            )
        store.accept(SharedThreadIntent.Start(basePayload()))
        val local =
            localPendingMessage(
                id = "local-1",
                body = "with attachments",
                attachmentIds = listOf("att-1", "att-2")
            )
        store.accept(SharedThreadIntent.ApplyLocalSendStarted(local))
        store.accept(SharedThreadIntent.ApplyLocalSendFailure("local-1", "network_error"))

        val failed = store.state.value.payload.messages.first { it.id == "local-1" }
        assertEquals(ThreadMessageDeliveryFailed, failed.deliveryState)
        assertEquals(listOf("att-1", "att-2"), failed.pendingSendContext?.attachmentIds)
        assertEquals(2, failed.attachments.size)

        store.accept(SharedThreadIntent.ApplyLocalSendStarted(failed.copy(deliveryState = ThreadMessageDeliverySending)))

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
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                memberRepository = memberRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        // Start now eagerly loads members via the Refresh path, so the
        // first LoadMembers below is a no-op (idempotency contract).
        assertEquals(2, store.state.value.payload.membersCount)
        assertEquals(listOf(ChannelId("channel-1")), memberRepository.channelMemberRequests)
        val beforeMessages = store.state.value.payload.messages

        store.accept(SharedThreadIntent.LoadMembers)
        // No second enrichment: members are already Loaded for this thread.
        assertEquals(
            listOf(ChannelId("channel-1")),
            memberRepository.channelMemberRequests
        )

        val payload = store.state.value.payload
        assertTrue(beforeMessages === payload.messages)
        assertEquals(2, payload.membersCount)
        assertEquals(listOf("u1", "agent-1"), payload.members.map { it.id })
        assertEquals(listOf("agent-2"), payload.memberCandidates.map { it.id })
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                memberRepository = flakyMemberRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        // Start -> refresh path -> loadMembers(force = true) -> first
        // getChannelMembers returns Error. Status becomes Error.
        store.accept(SharedThreadIntent.Start(basePayload()))
        val callsAfterStart = flakyMemberRepository.channelMemberCalls
        assertEquals(1, callsAfterStart, "Start should have triggered exactly one enrichment attempt")
        // Explicit LoadMembers should retry, not skip.
        store.accept(SharedThreadIntent.LoadMembers)
        val callsAfterRetry = flakyMemberRepository.channelMemberCalls
        assertEquals(
            2,
            callsAfterRetry,
            "After transient failure, the next LoadMembers must re-fetch (status should be Error, not Loaded)"
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
    fun loadMembersKeepsHumanUploadedAvatarFromServerProfile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val uploadedAvatar = "https://cdn.slock.ai/avatars/users/stdrc.webp"
        val memberRepository =
            FakeMemberRepository(
                serverMembers = listOf(member("u1", "Artin").copy(avatarUrl = uploadedAvatar)),
                channelMembers = listOf(member("u1", "Artin"))
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                memberRepository = memberRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.LoadMembers)

        assertEquals(uploadedAvatar, store.state.value.payload.members.single().avatarUrl)
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                createAgentCoordinator = coordinator
            )

        assertTrue(dataSource.createAgentCoordinatorOrNull === coordinator)
    }

    @Test
    fun agentCreateActionCardDialogSubmitUsesTypedApiAndMarksExecuted() = runBlocking {
        val agentsApi = RecordingAgentsApi()
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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

        assertEquals(
            listOf(
                AddChannelMemberRequest(ChannelId("channel-2"), MemberId("u3"), MemberKind.Human),
                AddChannelMemberRequest(ChannelId("channel-2"), MemberId("agent-3"), MemberKind.Agent)
            ),
            memberRepository.addRequests
        )
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
    fun removeChannelMemberUsesTypedRepositoryAndRefreshesMembers() = runBlocking {
        val memberRepository =
            FakeMemberRepository(
                serverMembers = listOf(member("u1", "Artin"), member("u2", "Stdrc")),
                channelMembers = listOf(member("u1", "Artin"), member("u2", "Stdrc"))
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                memberRepository = memberRepository
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
    fun startLoadsThreadMembersEagerlyAfterRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val memberRepository =
            FakeMemberRepository(
                serverMembers = listOf(member("u1", "Artin"), member("u2", "Stdrc")),
                channelMembers = listOf(member("u1", "Artin"))
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                memberRepository = memberRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals("content", store.state.value.payload.status)
        assertEquals(listOf(ChannelId("channel-1")), memberRepository.channelMemberRequests)
        assertEquals(listOf(ServerId("server-1")), memberRepository.serverMemberRequests)
        assertEquals(listOf("u1"), store.state.value.payload.members.map { it.id })
        assertEquals(listOf("u2"), store.state.value.payload.memberCandidates.map { it.id })
        scope.cancel()
    }

    @Test
    fun persistentCachedThreadSnapshotRendersBeforeVisibleFetchAndSurvivesFailure() = runBlocking {
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("persistent cached body", payload.messages.single().body)
        assertEquals("thread_network", payload.errorCode)
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun directMessageCachedSnapshotPreservesLaunchPeerHeader() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("dm-1"),
                        channelId = ChannelId("dm-1"),
                        kind = ConversationKind.DirectMessage,
                        name = "b2cddd71-75a9-4cfc-b3e0"
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(directMessagePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("artin", payload.title)
        assertEquals("artin", payload.dmPeerName)
        assertEquals("pixel:crown", payload.dmPeerAvatarUrl)
        assertEquals("AR", payload.dmPeerAvatarLabel)
        assertEquals("https://www.gravatar.com/avatar/artin", payload.dmPeerGravatarUrl)
        assertEquals("cached dm body", payload.messages.single().body)
        scope.cancel()
    }

    @Test
    fun persistentCachedThreadSnapshotUsesConfiguredInitialWindow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation = conversationSummary(),
                messages = (1..35).map { index -> message(id = "cached-$index", seq = index.toLong(), body = "cached $index") }
            )
        )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals((6..35).map { "cached $it" }, payload.messages.map { it.body })
        assertEquals(true, payload.canLoadMoreBefore)
        scope.cancel()
    }

    @Test
    fun successfulFetchWritesPersistentThreadSnapshotForColdRestart() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )

        dataSource.fetchThreadPayload(basePayload())

        val cached = localStore.readThread(ThreadId("channel-1"))
        assertEquals("general", cached?.conversation?.name)
        assertEquals("network body", cached?.messages?.single()?.body)
        assertEquals("Artin", cached?.messages?.single()?.author?.displayName)
    }

    @Test
    fun fetchedThreadPayloadUsesHomeDirectMessageAvatarBeforePersistentCacheWrite() = runBlocking {
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
                            avatar = AvatarReference.fromWireValue("pixel:tree"),
                            presenceMemberId = MemberId("agent-1")
                        )
                    ),
                unreadTotal = 0
            )
        )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals("pixel:tree", payload.messages.single().avatarWireValue)
        assertEquals(null, payload.messages.single().gravatarHash)
        val cachedAuthor = localStore.readThread(ThreadId("channel-1"))?.messages?.single()?.author
        assertEquals("pixel:tree", cachedAuthor?.avatar?.wireValue)
        assertEquals(null, cachedAuthor?.gravatarHash)
    }

    @Test
    fun fetchThreadPayloadWithoutSessionReturnsAuthRequiredNoPermission() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore()
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(),
                conversationRepository = repository
            )

        val result = dataSource.openMessageContext("message-1", basePayload())
        val blocker = (result as DataResult.NoPermission).blocker

        assertEquals("auth_required", blocker.code)
        assertEquals("auth_session", blocker.requiredCapability)
        assertEquals(0, repository.getMessageContextRequests.size)
    }

    @Test
    fun fetchThreadPayloadPrefersConversationRepositorySnapshotWhenAvailable() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "repository body"))
        )
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals(listOf(ThreadId("channel-1")), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("repository body", payload.messages.single().body)
    }

    @Test
    fun fetchReplyThreadPayloadResolvesThreadChannelBeforeLoadingMessages() = runBlocking {
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base) as DataResult.Content).data

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(listOf(ThreadId("wrong-thread"), ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
    }

    @Test
    fun startSkipsMismatchedReplyThreadMemoryCacheBeforeResolvedFetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = replyHintRepository()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository
            )
        dataSource.cachePayload(wrongReplyCachedPayload())
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val observedPayloads = mutableListOf<KuiklyThreadScreenPayload>()
        val observer = scope.launch {
            store.state.collect { state -> observedPayloads += state.payload }
        }

        store.accept(SharedThreadIntent.Start(replyRouteWithWrongHint()))

        val payload = store.state.value.payload
        assertFalse(observedPayloads.any { it.containsWrongReplyCacheContent() })
        assertEquals(listOf(ThreadId("wrong-thread"), ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals(ThreadReplyPageTitle, payload.title)
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore),
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
        assertEquals(listOf(ThreadId("wrong-thread"), ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals(ThreadReplyPageTitle, payload.title)
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
                createThreadResult =
                    DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore),
                conversationRepository = repository
            )
        dataSource.cachePayload(wrongReplyCachedPayload())
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val observedPayloads = mutableListOf<KuiklyThreadScreenPayload>()
        val observer = scope.launch {
            store.state.collect { state -> observedPayloads += state.payload }
        }

        store.accept(SharedThreadIntent.Start(replyRouteWithWrongHint()))

        val payload = store.state.value.payload
        assertFalse(observedPayloads.any { it.containsWrongReplyCacheContent() })
        assertEquals(listOf(ThreadId("wrong-thread")), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
                parentMessageId = MessageId("m1"),
                parentMessagePreview = "parent body",
                latestActivityMessageId = MessageId("reply-1")
            ).toHomeMessageOpenRequest()
        val initialPayload =
            buildAppShellThreadPayload(request.conversation, anchorMessageId = request.anchorMessageId)
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
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("reply-1", payload.focusedMessageId)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        scope.cancel()
    }

    @Test
    fun refreshWithUnchangedSnapshotDoesNotEmitAndKeepsTimelineList() = runBlocking {
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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

        assertEquals(countBeforeRefresh, observedStates.size)
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(
                    bodyText =
                        systemMessagesResponse(
                            body = "✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done",
                            id = "sys-done-1",
                            seq = 44
                        )
                ),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
    fun realtimeMessageNewMergesIntoVisiblePayloadAndPersistentSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        dataSource.cachePayload(contentPayload("cached body"))
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
        assertEquals(
            null,
            localStore.readThread(ThreadId("channel-1"))?.messages?.firstOrNull { it.id == MessageId("m2") }?.body
        )
        delay(SharedThreadRealtimePersistenceBatchDelayMillis + 80L)
        assertEquals(
            "realtime body",
            localStore.readThread(ThreadId("channel-1"))?.messages?.firstOrNull { it.id == MessageId("m2") }?.body
        )
        scope.cancel()
    }

    @Test
    fun realtimeMessageNewUsesCachedHomeDirectMessageAvatar() = runBlocking {
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
                            avatar = AvatarReference.fromWireValue("pixel:tree"),
                            presenceMemberId = MemberId("agent-1")
                        )
                    ),
                unreadTotal = 0
            )
        )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        dataSource.cachePayload(contentPayload("cached body"))
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
        assertEquals("pixel:tree", message.avatarWireValue)
        assertEquals(null, message.gravatarHash)
        delay(SharedThreadRealtimePersistenceBatchDelayMillis + 80L)
        val cachedAuthor =
            localStore
                .readThread(ThreadId("channel-1"))
                ?.messages
                ?.single { it.id == MessageId("m2") }
                ?.author
        assertEquals("pixel:tree", cachedAuthor?.avatar?.wireValue)
        assertEquals(null, cachedAuthor?.gravatarHash)
        scope.cancel()
    }

    @Test
    fun realtimeMessageNewRendersTaskLifecycleSystemRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(contentPayload("cached body"))
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
    fun realtimePersistenceFailureDoesNotRollbackVisiblePayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val backingStore = InMemoryComposeLocalDataStore()
        backingStore.writeThread(threadSnapshot(body = "cached body"))
        val logs = mutableListOf<String>()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(FailingThreadWriteStore(backingStore))
            )
        dataSource.cachePayload(contentPayload("cached body"))
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
        delay(SharedThreadRealtimePersistenceBatchDelayMillis + 80L)
        assertEquals(listOf("cached body", "realtime body"), store.state.value.payload.messages.map { it.body })
        assertTrue(logs.any { it.startsWith("thread_realtime_persist_failed") })
        scope.cancel()
    }

    @Test
    fun irrelevantRealtimeMessageDoesNotChangeVisibleThread() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val before = store.state.value

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
        scope.cancel()
    }

    @Test
    fun realtimeMessageUpdatedPreservesExistingAuthorMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(contentPayload("cached body"))
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
    fun realtimeMessageUpdatedAppliesReactionPayloadIncludingEmptyRemoval() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(contentPayload("cached body"))
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
            listOf(KuiklyThreadReactionItem("👀", count = 1, selected = true)),
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
    fun realtimeThreadUpdatedAndTaskUpdatedMergeIntoPayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
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
        dataSource.cachePayload(initial)
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
                    firstUnreadMessageId = MessageId("reply-2")
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
        assertEquals("InReview", message.taskStatus)
        assertEquals("Updated task", store.state.value.payload.tasks.single().title)
        scope.cancel()
    }

    @Test
    fun visibleReadAllMarksChannel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false }
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(InMemoryComposeLocalDataStore())
            )
        dataSource.cachePayload(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.MarkVisibleReadAll)
        store.accept(SharedThreadIntent.MarkVisibleReadAll)

        assertEquals(listOf("channel-1"), api.readAllChannels)
        scope.cancel()
    }

    @Test
    fun visibleReadAllCoalescesInFlightBurstToLatestMarker() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(failChannelMessages = true).also {
                it.failReadAll = false
                it.readAllDelayMillis = 50L
            }
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(InMemoryComposeLocalDataStore())
            )
        dataSource.cachePayload(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.dispatch(SharedThreadIntent.MarkVisibleReadAll)
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
        store.dispatch(SharedThreadIntent.MarkVisibleReadAll)
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
        store.dispatch(SharedThreadIntent.MarkVisibleReadAll)

        delay(SharedThreadRealtimePersistenceBatchDelayMillis + 120L)

        assertEquals(listOf("channel-1", "channel-1"), api.readAllChannels)
        assertEquals(1, api.maxConcurrentReadAllCalls)
        scope.cancel()
    }

    @Test
    fun sendMessageForwardsAttachmentIds() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )

        dataSource.sendMessage(
            channelId = "channel-1",
            body = "with files",
            asTask = false,
            attachmentIds = listOf("att-1", "att-2")
        )

        assertEquals(listOf("att-1", "att-2"), api.lastAttachmentIds)
    }

    @Test
    fun saveMessageCallsThreadApi() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )

        dataSource.saveMessage("m1")

        assertEquals(listOf("m1"), api.savedMessageIds)
    }

    @Test
    fun unsaveMessageCallsThreadApi() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )

        dataSource.unsaveMessage("m1")

        assertEquals(listOf("m1"), api.unsavedMessageIds)
    }

    @Test
    fun fetchThreadPayloadChecksSavedStatus() = runBlocking {
        val savedStatusRepository = FakeSavedStatusRepository(savedIds = setOf(MessageId("m1")))
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                savedStatusRepository = savedStatusRepository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals(listOf(listOf(MessageId("m1"))), savedStatusRepository.checkedMessageIds)
        assertTrue(payload.messages.single().isSaved)
    }

    @Test
    fun applyMessageSavedPatchesPayloadAndCache() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        dataSource.cachePayload(contentPayload("saveable body"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertFalse(store.state.value.payload.messages.single().isSaved)

        store.accept(SharedThreadIntent.ApplyMessageSaved("m1", saved = true))

        assertTrue(store.state.value.payload.messages.single().isSaved)
        assertTrue(dataSource.cachedPayload("channel-1")?.messages?.single()?.isSaved == true)
        val cached = localStore.readThread(ThreadId("channel-1"))
        assertEquals("m1", cached?.messages?.single()?.id?.value)
        scope.cancel()
    }

    @Test
    fun setMessageReactionReturnsMutationSummaryWithoutWritingPayload() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val api = FakeThreadApi()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        dataSource.cachePayload(contentPayload("reactable body"))

        val result = dataSource.setMessageReaction("m1", "🔥", selected = true)

        val mutation = (result as DataResult.Content).data
        assertEquals(listOf("m1" to "🔥"), api.addedReactions)
        assertEquals("m1", mutation.messageId)
        assertEquals("🔥", mutation.reactions.single().emoji)
        assertEquals(null, localStore.readThread(ThreadId("channel-1")))
    }

    @Test
    fun applyMessageMutationWritesCachedPayloadAfterReducer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        dataSource.cachePayload(contentPayload("reactable body"))
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
        assertEquals("reactable body", localStore.readThread(ThreadId("channel-1"))?.messages?.single()?.body)
        scope.cancel()
    }

    @Test
    fun setMessageReactionRemovesSelectedReaction() = runBlocking {
        val api = FakeThreadApi()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val originalReactions = listOf(KuiklyThreadReactionItem("🔥", count = 1, selected = true))
        val message = contentPayload("reactable body").messages.single()
        dataSource.cachePayload(
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
    fun updateTaskStatusCallsThreadApiWithoutWritingPayload() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val api = FakeThreadApi()
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )

        val result = dataSource.updateTaskStatus("task-1", TaskStatus.Done)

        assertTrue(result is DataResult.Content)
        assertEquals(listOf("task-1" to TaskStatus.Done), api.updatedTaskStatuses)
        assertEquals(null, localStore.readThread(ThreadId("channel-1")))
    }

    @Test
    fun applyTaskStatusWritesPersistentSnapshotAfterReducer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        dataSource.cachePayload(taskPayload())
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.ApplyTaskStatus("task-1", TaskStatus.Done.name))

        assertEquals("Done", store.state.value.payload.messages.single().taskStatus)
        assertEquals("Done", store.state.value.payload.tasks.single().status)
        assertEquals("Done", localStore.readThread(ThreadId("channel-1"))?.tasks?.single()?.status?.name)
        scope.cancel()
    }

    @Test
    fun createOrOpenReplyThreadFetchesReplyChannelAndPreservesOrigin() = runBlocking {
        val api = FakeThreadApi(bodyText = messagesResponse("reply body", id = "reply-1", seq = 11))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val result = dataSource.createOrOpenReplyThread(base, parent)

        val payload = (result as DataResult.Content).data
        assertEquals("content", payload.status)
        assertEquals(emptyList(), payload.messages)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
    fun createOrOpenReplyThreadUsesRepositoryMetadataForOriginAndPersistentSnapshot() = runBlocking {
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore),
                conversationRepository = repository
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val payload = (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data
        dataSource.writeCachedPayload(payload)

        val cached = localStore.readThread(ThreadId("thread-m1"))
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests.takeLast(1))
        assertEquals(0, api.channelMessageCalls)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals(4, payload.originMessage?.replyCount)
        assertEquals("thread-m1", payload.originMessage?.threadChannelId)
        assertEquals(4, cached?.parentMessage?.replyCount)
        assertEquals(ChannelId("thread-m1"), cached?.parentMessage?.threadChannelId)
    }

    @Test
    fun loadOlderMessagesMergesBeforeVisiblePayloadAndWritesCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10),
                olderBodyText = messagesResponse("older body", id = "m5", seq = 5, historyLimited = false)
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.LoadOlder)

        val payload = store.state.value.payload
        assertEquals(listOf("older body", "current body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.beforeSeqs)
        assertEquals(false, payload.canLoadMoreBefore)
        assertEquals("idle", payload.olderMessagesStatus)
        assertEquals("older body", localStore.readThread(ThreadId("channel-1"))?.messages?.first()?.body)
        scope.cancel()
    }

    @Test
    fun loadNewerMessagesMergesAfterVisiblePayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("newer body", id = "m12", seq = 12)
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        store.accept(SharedThreadIntent.LoadNewer)

        val payload = store.state.value.payload
        assertEquals(listOf("current body", "newer body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.syncSinceSeqs)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun loadNewerMessagesMergesSystemRowsFromSyncRecovery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText =
                    syncSystemResponse(
                        body = "released #252 \"Implement Thread timeline system-message rendering\"",
                        id = "sys-release-1",
                        seq = 12
                    )
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
        assertEquals(listOf(10L), api.syncSinceSeqs)
        scope.cancel()
    }

    private class FakeThreadApi(
        var bodyText: String = messagesResponse("network body"),
        var olderBodyText: String = messagesResponse("older body", id = "older", seq = 1, historyLimited = false),
        var newerBodyText: String = messagesResponse("newer body", id = "newer", seq = 100, historyLimited = false),
        var failChannelMessages: Boolean = false
    ) : SharedThreadNetworkApi {
        var channelMessageCalls: Int = 0
        var failReadAll: Boolean = false
        var readAllDelayMillis: Long = 0L
        val readAllChannels: MutableList<String> = mutableListOf()
        var activeReadAllCalls: Int = 0
        var maxConcurrentReadAllCalls: Int = 0
        val beforeSeqs: MutableList<Long> = mutableListOf()
        val syncSinceSeqs: MutableList<Long> = mutableListOf()
        val channelMessageLimits: MutableList<Int> = mutableListOf()
        val syncLimits: MutableList<Int> = mutableListOf()
        val channelIds: MutableList<String> = mutableListOf()
        var lastAttachmentIds: List<String> = emptyList()
        val savedMessageIds: MutableList<String> = mutableListOf()
        val unsavedMessageIds: MutableList<String> = mutableListOf()
        val addedReactions: MutableList<Pair<String, String>> = mutableListOf()
        val removedReactions: MutableList<Pair<String, String>> = mutableListOf()
        val updatedTaskStatuses: MutableList<Pair<String, TaskStatus>> = mutableListOf()
        val createdThreads: MutableList<Pair<String, String>> = mutableListOf()
        val mentionActionRequests: MutableList<Pair<String, List<String>>> = mutableListOf()

        override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?): HomeNetworkResponse {
            channelMessageCalls += 1
            channelIds += threadId
            channelMessageLimits += limit
            if (failChannelMessages) throw IllegalStateException("offline")
            return if (beforeSeq != null) {
                beforeSeqs += beforeSeq
                HomeNetworkResponse(code = 200, bodyText = olderBodyText)
            } else {
                HomeNetworkResponse(code = 200, bodyText = bodyText)
            }
        }

        override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse {
            syncSinceSeqs += sinceSeq
            syncLimits += limit
            return HomeNetworkResponse(code = 200, bodyText = newerBodyText)
        }

        override suspend fun sendMessage(
            channelId: String,
            body: String,
            asTask: Boolean,
            attachmentIds: List<String>
        ): HomeNetworkResponse {
            lastAttachmentIds = attachmentIds
            return HomeNetworkResponse(code = 200, bodyText = messagesResponse(body))
        }

        override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): HomeNetworkResponse {
            mentionActionRequests += action to resolutionIds
            return HomeNetworkResponse(code = 200, bodyText = """{"results":[]}""")
        }

        override suspend fun attachmentUrl(attachmentId: String, disposition: String?): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"url":"https://cdn.example/$attachmentId"}""")

        override suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"comments":[],"threadChannelId":"comments-$attachmentId"}""")

        override suspend fun sendAttachmentComment(attachmentId: String, body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")

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

        override suspend fun updateTaskStatus(taskId: String, status: TaskStatus): HomeNetworkResponse {
            updatedTaskStatuses += taskId to status
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"$taskId","status":"${status.name}"}""")
        }

        override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse {
            createdThreads += channelId to parentMessageId
            return HomeNetworkResponse(code = 200, bodyText = """{"threadChannelId":"thread-$parentMessageId","parentMessageId":"$parentMessageId","created":true}""")
        }
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

    private class FakeChannelRepository : ChannelRepository {
        val createRequests: MutableList<CreateChannelRequest> = mutableListOf()

        override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> = error("unused")
        override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> = error("unused")
        override suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot> = error("unused")
        override suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult> = error("unused")
        override suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult> = error("unused")

        override suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult> {
            createRequests += request
            return DataResult.Content(
                CreateChannelResult(
                    channel =
                        ConversationSummary(
                            threadId = ThreadId("channel-created"),
                            channelId = ChannelId("channel-created"),
                            kind = ConversationKind.Channel,
                            name = request.name
                        ),
                    addedAgentMemberIds = request.agentMemberIds,
                    addedUserMemberIds = request.userMemberIds
                )
            )
        }

        override suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult> = error("unused")
        override suspend fun leaveChannel(channelId: ChannelId): DataResult<ChannelLeaveResult> = error("unused")
        override suspend fun archiveChannel(channelId: ChannelId): DataResult<ChannelArchiveResult> = error("unused")
        override suspend fun deleteChannel(channelId: ChannelId): DataResult<ChannelDeleteResult> = error("unused")
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
        override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
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
            HomeNetworkResponse(code = 200, bodyText = """{"messageId":"m-card","metadata":{"kind":"action-card"}}""")
    ) : SlockIntegrationsActionsPushApi {
        val calls: MutableList<String> = mutableListOf()

        override suspend fun integrationsOverview(): HomeNetworkResponse = error("unused")
        override suspend fun integrationClients(): HomeNetworkResponse = error("unused")
        override suspend fun createIntegrationClient(body: String): HomeNetworkResponse = error("unused")
        override suspend fun approveIntegrationRequest(requestId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun denyIntegrationRequest(requestId: String): HomeNetworkResponse = error("unused")
        override suspend fun revokeIntegrationGrant(grantId: String): HomeNetworkResponse = error("unused")
        override suspend fun agentIntegrations(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse = error("unused")
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
        val removeRequests: MutableList<RemoveChannelMemberRequest> = mutableListOf()

        override suspend fun getMembers(
            priority: ai.slock.compose.data.DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> {
            serverMemberRequests += serverId
            return DataResult.Content(
                MemberSnapshot(
                    server = ai.slock.compose.model.ServerSummary(id = serverId, name = "Slock"),
                    members = serverMembers
                )
            )
        }

        override suspend fun getChannelMembers(
            priority: ai.slock.compose.data.DataRequestPriority,
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
            priority: ai.slock.compose.data.DataRequestPriority,
            memberId: MemberId
        ): DataResult<MemberProfile> = error("unused")

        override suspend fun updateAgentProfile(
            priority: ai.slock.compose.data.DataRequestPriority,
            request: AgentProfileUpdateRequest
        ): DataResult<MemberProfile> = error("unused")

        override suspend fun uploadAgentAvatar(
            priority: ai.slock.compose.data.DataRequestPriority,
            request: AgentAvatarUploadRequest
        ): DataResult<MemberProfile> = error("unused")

        override suspend fun getAgentDetail(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentDetailSnapshot> = error("unused")

        override suspend fun getAgentDirectMessages(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentDmSnapshot> = error("unused")

        override suspend fun getAgentReminders(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentReminderSnapshot> = error("unused")

        override suspend fun getAgentActivity(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentActivitySnapshot> = error("unused")

        override suspend fun getAgentWorkspaces(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentWorkspaceSnapshot> = error("unused")

        override suspend fun getAgentWorkspaceTree(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentWorkspaceTreeSnapshot> = error("unused")

        override suspend fun getAgentWorkspaceChildren(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId,
            dirPath: String
        ): DataResult<AgentWorkspaceChildrenSnapshot> = error("unused")

        override suspend fun readAgentWorkspaceFile(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId,
            path: String
        ): DataResult<AgentWorkspaceFilePreviewSnapshot> = error("unused")

        override suspend fun getAgentSkills(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentSkillsSnapshot> = error("unused")

        override suspend fun startAgent(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun stopAgent(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun resetAgent(
            priority: ai.slock.compose.data.DataRequestPriority,
            request: AgentResetRequest
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun deleteAgent(
            priority: ai.slock.compose.data.DataRequestPriority,
            agentId: MemberId
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun assignAgentMachine(
            priority: ai.slock.compose.data.DataRequestPriority,
            request: AgentMachineAssignmentRequest
        ): DataResult<AgentLifecycleActionResult> = error("unused")

        override suspend fun addChannelMember(
            priority: ai.slock.compose.data.DataRequestPriority,
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

        override suspend fun removeChannelMember(
            priority: ai.slock.compose.data.DataRequestPriority,
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
        var threadResultsById: Map<ThreadId, DataResult<ThreadSnapshot>> = emptyMap(),
        var olderResult: DataResult<ThreadSnapshot> = DataResult.Content(threadSnapshot(body = "older repo body", id = "m5", seq = 5)),
        var newerResult: DataResult<ThreadSnapshot> = DataResult.Content(threadSnapshot(body = "newer repo body", id = "m12", seq = 12)),
        var messageContextResult: DataResult<ThreadSnapshot> = DataResult.Content(threadSnapshot()),
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
        val getThreadRequests: MutableList<ThreadId> = mutableListOf()
        val getThreadLimits: MutableList<Int> = mutableListOf()
        val olderRequests: MutableList<Pair<ThreadId, Long>> = mutableListOf()
        val olderLimits: MutableList<Int> = mutableListOf()
        val newerRequests: MutableList<Pair<ThreadId, Long>> = mutableListOf()
        val newerLimits: MutableList<Int> = mutableListOf()
        val getMessageContextRequests: MutableList<MessageId> = mutableListOf()
        val createOrOpenRequests: MutableList<Pair<ChannelId, MessageId>> = mutableListOf()
        val getReplyThreadRequests: MutableList<Pair<ChannelId, MessageId>> = mutableListOf()
        val getReplyThreadLimits: MutableList<Int> = mutableListOf()

        override suspend fun getThread(
            threadId: ThreadId,
            includeFollowedSummaries: Boolean,
            limit: Int
        ): DataResult<ThreadSnapshot> {
            getThreadRequests += threadId
            getThreadLimits += limit
            return threadResultsById[threadId] ?: threadResult
        }

        override suspend fun getReplyThread(
            parentChannelId: ChannelId,
            parentMessageId: MessageId,
            includeFollowedSummaries: Boolean,
            limit: Int
        ): DataResult<ThreadSnapshot> {
            getReplyThreadRequests += parentChannelId to parentMessageId
            getReplyThreadLimits += limit
            return threadResult
        }

        override suspend fun getMessageContext(messageId: MessageId): DataResult<ThreadSnapshot> {
            getMessageContextRequests += messageId
            return messageContextResult
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

        override suspend fun getChannelThreads(channelId: ChannelId, limit: Int): DataResult<ThreadListSnapshot> = error("unused")

        override suspend fun getFollowedThreads(serverId: ServerId, limit: Int): DataResult<ThreadListSnapshot> = error("unused")

        override suspend fun createOrOpenThread(channelId: ChannelId, parentMessageId: MessageId): DataResult<ThreadActionResult> {
            createOrOpenRequests += channelId to parentMessageId
            return createThreadResult
        }

        override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> = error("unused")

        override suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> = error("unused")

        override suspend fun markThreadDone(threadChannelId: ChannelId): DataResult<ThreadActionResult> = error("unused")

        override suspend fun markThreadUndone(threadChannelId: ChannelId): DataResult<ThreadActionResult> = error("unused")

        override suspend fun uploadAttachments(request: AttachmentUploadRequest): DataResult<AttachmentUploadResult> = error("unused")

        override suspend fun getAttachmentOpenTarget(attachmentId: ai.slock.compose.model.AttachmentId): DataResult<AttachmentOpenTarget> =
            error("unused")

        override suspend fun getChannelFiles(
            channelId: ChannelId,
            limit: Int,
            cursor: String?
        ): DataResult<ChannelFilesSnapshot> = error("unused")

        override suspend fun sendMessage(
            channelId: ChannelId,
            body: String,
            attachmentIds: List<ai.slock.compose.model.AttachmentId>,
            asTask: Boolean
        ): DataResult<SendMessageResult> = error("unused")
    }

    private class FailingThreadWriteStore(
        private val delegate: ComposeLocalDataStore
    ) : ComposeLocalDataStore by delegate {
        override suspend fun writeThread(snapshot: ThreadSnapshot) {
            error("thread write failed")
        }
    }

    private fun basePayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true
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
            dmPeerGravatarUrl = "https://www.gravatar.com/avatar/artin"
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

    private fun contentPayload(body: String): KuiklyThreadScreenPayload =
        buildSharedThreadPayloadFromMessagesPage(
            basePayload = basePayload(),
            page =
                ThreadMessagesPage(
                    messages = listOf(WireMessage(id = "m1", channelId = "channel-1", senderType = "user", senderId = "u1", senderName = "Artin", content = body)),
                    canLoadMoreBefore = false,
                    canLoadMoreAfter = null
                ),
            session = sampleSession()
        )

    private fun sentPage(id: String, body: String): ThreadMessagesPage =
        ThreadMessagesPage(
            messages =
                listOf(
                    WireMessage(
                        id = id,
                        channelId = "channel-1",
                        senderType = "user",
                        senderId = "u1",
                        senderName = "Artin",
                        content = body,
                        createdAt = "2026-05-19T16:01:00Z",
                        seq = 11
                    )
                ),
            canLoadMoreBefore = null,
            canLoadMoreAfter = null
        )

    private fun localPendingMessage(
        id: String,
        body: String,
        asTask: Boolean = false,
        attachmentIds: List<String> = emptyList()
    ): KuiklyThreadMessageItem =
        KuiklyThreadMessageItem(
            id = id,
            authorId = "u1",
            authorName = "Artin",
            isOwn = true,
            body = body,
            threadChannelId = "channel-1",
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
                    streamChannelId = "channel-1",
                    memberScopedMentions = false
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

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "u1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1"
        )

    private fun member(id: String, name: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = name.lowercase(),
            kind = MemberKind.Human,
            presence = Presence.Active
        )

    companion object {
        private fun threadSnapshot(
            threadId: String = "channel-1",
            body: String = "repository body",
            id: String = "m1",
            seq: Long = 10,
            messages: List<Message> = listOf(message(threadId = threadId, id = id, seq = seq, body = body)),
            parentMessage: Message? = null,
            conversation: ConversationSummary = conversationSummary(threadId)
        ): ThreadSnapshot =
            ThreadSnapshot(
                conversation = conversation,
                messages = messages,
                parentMessage = parentMessage
            )

        private fun conversationSummary(threadId: String = "channel-1"): ConversationSummary =
            ConversationSummary(
                threadId = ThreadId(threadId),
                channelId = ChannelId(threadId),
                kind = ConversationKind.Channel,
                name = "general"
            )

        private fun message(
            threadId: String = "channel-1",
            id: String,
            seq: Long,
            body: String,
            threadChannelId: String? = null,
            replyCount: Int = 0,
            unreadReplyCount: Int = 0,
            messageType: MessageType = MessageType.Chat,
            authorKind: MemberKind = MemberKind.Human
        ): Message =
            Message(
                id = MessageId(id),
                threadId = ThreadId(threadId),
                channelId = ChannelId(threadId),
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
                unreadReplyCount = unreadReplyCount
            )

        private fun messagesResponse(
            body: String,
            id: String = "m1",
            seq: Long = 10,
            historyLimited: Boolean = true
        ): String =
            """
            {
              "historyLimited": $historyLimited,
              "messages": [
                {
                  "id": "$id",
                  "seq": $seq,
                  "content": "$body",
                  "createdAt": "2026-05-19T16:00:00Z",
                  "senderId": "u1",
                  "senderName": "Artin",
                  "senderType": "user"
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
                  "senderType": "agent"
                }
              ]
            }
            """.trimIndent()

        private fun syncResponse(
            body: String,
            id: String = "m1",
            seq: Long = 10
        ): String =
            """
            [
              {
                "id": "$id",
                "seq": $seq,
                "content": "$body",
                "createdAt": "2026-05-19T16:00:00Z",
                "senderId": "u1",
                "senderName": "Artin",
                "senderType": "user"
              }
            ]
            """.trimIndent()

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
