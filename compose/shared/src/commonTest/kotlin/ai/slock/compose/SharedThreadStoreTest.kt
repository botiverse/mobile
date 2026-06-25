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
import ai.slock.compose.data.ChannelUpdateResult
import ai.slock.compose.data.ChannelVisibility
import ai.slock.compose.data.ChannelSnapshot
import ai.slock.compose.data.ConversationRepository
import ai.slock.compose.data.CreateChannelRequest
import ai.slock.compose.data.CreateChannelResult
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.DirectMessageSnapshot
import ai.slock.compose.data.EmptyReason
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
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageDeliveryState
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedThreadStoreTest {
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
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, dm.surfaceKind)
        assertEquals("dm:same-stream", dm.instanceKey)
        assertEquals("same-stream", dm.timelineKey)
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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

        dataSource.cachePayload(unresolved)
        assertNull(dataSource.cachedPayload("channel-1"))

        dataSource.cachePayload(resolved)
        assertEquals("resolved reply body", dataSource.cachedPayload("thread-m1")?.messages?.single()?.body)
    }

    @Test
    fun unresolvedReplyStartDoesNotRenderPreviousResolvedReplyTimeline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val resolvedRoute = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        dataSource.cachePayload(
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
    fun startDoesNotRenderOrCacheContentFromRoutePayload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
    fun routeContextDoesNotOverrideCanonicalCacheMembershipState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(
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
    fun emptyDirectMessageFetchRendersEmptyStateInsteadOfError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(bodyText = emptyMessagesResponse())
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun cachedThreadRefreshExposesBackgroundRefreshingUntilNetworkCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api =
            FakeThreadApi(bodyText = messagesResponse("network body")).apply {
                channelMessagesStarted = CompletableDeferred()
                releaseChannelMessages = CompletableDeferred()
            }
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        api.channelMessagesStarted?.await()

        val refreshingPayload = store.state.value.payload
        assertEquals("content", refreshingPayload.status)
        assertEquals("cached body", refreshingPayload.messages.single().body)
        assertTrue(refreshingPayload.backgroundRefreshing)

        api.releaseChannelMessages?.complete(Unit)
        repeat(50) {
            val payload = store.state.value.payload
            if (!payload.backgroundRefreshing && payload.messages.singleOrNull()?.body == "network body") {
                scope.cancel()
                return@runBlocking
            }
            delay(10)
        }
        val payload = store.state.value.payload
        assertFalse(payload.backgroundRefreshing)
        assertEquals("network body", payload.messages.single().body)
        scope.cancel()
    }

    @Test
    fun contentPatchPreservesReplyThreadChannelSubtitleWhenNetworkPayloadOmitsIt() {
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val current = contentPayloadForRoute(route, "cached body").copy(subtitle = "#general")
        val networkPatch = contentPayloadForRoute(route, "network body").copy(subtitle = null)

        val payload = current.withContentPatchFrom(networkPatch)

        assertEquals("#general", payload.subtitle)
        assertEquals("network body", payload.messages.single().body)
    }

    @Test
    fun contentPatchClearsNonReplySubtitleWhenNetworkPayloadOmitsIt() {
        val current = contentPayload("cached body").copy(subtitle = "General updates")
        val networkPatch = contentPayload("network body").copy(subtitle = null)

        val payload = current.withContentPatchFrom(networkPatch)

        assertNull(payload.subtitle)
        assertEquals("network body", payload.messages.single().body)
    }

    @Test
    fun contentPatchDoesNotOwnDirectMessageHeaderMetadata() {
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
                dmPeerGravatarUrl = null
            )

        val payload = current.withContentPatchFrom(networkPatch)
        val headerDisplay =
            payload.threadHeaderDisplayModel(
                sharedConversationHeaderState(
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "artin",
                            avatar = AvatarReference.Pixel("crown"),
                            gravatarUrl = "https://www.gravatar.com/avatar/artin"
                        )
                    )
                ).entryFor(payload)
            )

        assertEquals("Direct Message", payload.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals(null, payload.dmPeerAvatarLabel)
        assertEquals(null, payload.dmPeerGravatarUrl)
        assertEquals("artin", headerDisplay.title)
        assertEquals("pixel:crown", headerDisplay.dmPeerAvatarWireValue)
        assertEquals("AR", headerDisplay.dmPeerAvatarLabel)
        assertEquals("network body", payload.messages.single().body)
    }

    @Test
    fun memoryCachedDirectMessageSnapshotCanRemainSparseWhenSnapshotOmitsDisplayMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        dataSource.cachePayload(
            contentPayloadForRoute(directMessagePayload(), "cached body")
                .copy(
                    title = "Direct Message",
                    dmPeerName = null,
                    dmPeerAvatarUrl = null,
                    dmPeerAvatarLabel = null,
                    dmPeerGravatarUrl = null
                )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(directMessagePayload()))

        val payload = store.state.value.payload
        val headerDisplay = payload.threadHeaderDisplayModel(null)
        assertEquals("content", payload.status)
        assertEquals("Direct Message", payload.title)
        assertEquals("Direct Message", headerDisplay.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals(null, payload.dmPeerAvatarLabel)
        assertEquals(null, payload.dmPeerGravatarUrl)
        assertEquals("cached body", payload.messages.single().body)
        scope.cancel()
    }

    @Test
    fun contentPatchDoesNotProtectAgentDirectMessageHeaderFromIncomingPatch() {
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
                dmPeerGravatarUrl = null
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
                dmPeerGravatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404"
            )

        val payload = current.withContentPatchFrom(networkPatch)

        assertEquals("Product-UX-Designer", payload.title)
        assertEquals("Product-UX-Designer", payload.dmPeerName)
        assertEquals("https://www.gravatar.com/avatar/agent-hash?s=52&d=404", payload.dmPeerAvatarUrl)
        assertEquals("PU", payload.dmPeerAvatarLabel)
        assertEquals("https://www.gravatar.com/avatar/agent-hash?s=52&d=404", payload.dmPeerGravatarUrl)
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val replyRoute = replyPayload(parentMessageId = "parent-a", threadId = "thread-a")
        dataSource.cachePayload(
            contentPayloadForRoute(
                base = replyRoute,
                body = "reply cached",
                id = "reply-cached",
                channelId = "thread-a"
            )
        )
        val logs = CopyOnWriteArrayList<String>()
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
    fun contentPatchKeepsLoadedMembersWhenNetworkPayloadOmitsMembers() {
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

        val payload = current.withContentPatchFrom(networkPatch)

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
    fun startUsesHomeHeaderAndMembershipForImmediateLoadingPayloadWhenTitleIsIdentity() = runBlocking {
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
                        )
                    ),
                directMessages = emptyList(),
                unreadTotal = 0
            )
        )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload().copy(title = "channel-1")))

        val payload = store.state.value.payload
        assertEquals("loading", payload.status)
        assertEquals("all", payload.title)
        assertEquals("General updates", payload.subtitle)
        assertEquals("public", payload.channelKind)
        assertEquals(false, payload.joined)
        assertEquals(false, payload.canSend)
        assertEquals(false, payload.composer.enabled)
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

        assertEquals(listOf(30, 30, 30), api.channelMessageLimits)
        assertEquals(emptyList(), api.syncLimits)
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
    fun localSendSuccessPersistsCanonicalMessageWithoutTempSendingBubble() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.ApplyLocalSendStarted(localPendingMessage("local-1", "sent body")))
        store.accept(SharedThreadIntent.ReplaceLocalSendWithSentPage("local-1", sentPage("server-1", "sent body")))

        val persistedMessages = localStore.readThread(ThreadId("channel-1"))?.messages.orEmpty()
        assertFalse(persistedMessages.any { it.id.value == "local-1" })
        assertFalse(persistedMessages.any { it.deliveryState == MessageDeliveryState.Sending })
        assertEquals(MessageDeliveryState.Sent, persistedMessages.first { it.id.value == "server-1" }.deliveryState)
        scope.cancel()
    }

    @Test
    fun readCachedPayloadDropsTransientLocalSendingBubble() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            threadSnapshot(
                messages =
                    listOf(
                        message(
                            id = "local-send-channel-1-1",
                            seq = 11,
                            body = "sent body"
                        ).copy(deliveryState = MessageDeliveryState.Sending),
                        message(
                            id = "server-1",
                            seq = 12,
                            body = "sent body"
                        )
                    )
            )
        )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )

        val cachedPayload = dataSource.readCachedPayload(basePayload())

        assertNotNull(cachedPayload)
        assertFalse(cachedPayload.messages.any { it.id == "local-send-channel-1-1" })
        assertFalse(cachedPayload.messages.any { it.deliveryState == ThreadMessageDeliverySending })
        assertEquals(listOf("server-1"), cachedPayload.messages.map { it.id })
    }

    @Test
    fun localSendFailurePersistsFailedBubbleWithRetryContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyLocalSendStarted(
                localPendingMessage(
                    id = localMessageId,
                    body = "retry body",
                    asTask = true,
                    attachmentIds = listOf("att-1")
                )
            )
        )
        store.accept(SharedThreadIntent.ApplyLocalSendFailure(localMessageId, "network_error"))

        val persistedMessage = localStore.readThread(ThreadId("channel-1"))?.messages?.first { it.id.value == localMessageId }
        assertNotNull(persistedMessage)
        assertEquals(MessageDeliveryState.Failed, persistedMessage.deliveryState)
        val cachedPayload = dataSource.readCachedPayload(basePayload())
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.ApplyLocalSendStarted(localPendingMessage(localMessageId, "retry body")))

        store.accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = localMessageId,
                errorCode = "body_too_large",
                errorMessage = "Body is too large"
            )
        )

        val failed = store.state.value.payload.messages.first { it.id == localMessageId }
        assertEquals("body_too_large", failed.pendingSendErrorCode)
        assertEquals("Body is too large", failed.pendingSendErrorMessage)
        val cachedPayload = dataSource.readCachedPayload(basePayload())
        assertNotNull(cachedPayload)
        val cachedFailed = cachedPayload.messages.first { it.id == localMessageId }
        assertEquals("body_too_large", cachedFailed.pendingSendErrorCode)
        assertEquals("Body is too large", cachedFailed.pendingSendErrorMessage)
        scope.cancel()
    }

    @Test
    fun dismissLocalFailedSendRemovesFailedBubbleFromStateAndLocalCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.ApplyLocalSendStarted(localPendingMessage(localMessageId, "delete me")))
        store.accept(SharedThreadIntent.ApplyLocalSendFailure(localMessageId, "network_error"))

        store.accept(SharedThreadIntent.DismissLocalFailedSend(localMessageId))

        assertEquals(null, store.state.value.payload.messages.firstOrNull { it.id == localMessageId })
        val persistedMessages = localStore.readThread(ThreadId("channel-1"))?.messages.orEmpty()
        assertEquals(null, persistedMessages.firstOrNull { it.id.value == localMessageId })
        val cachedPayload = dataSource.readCachedPayload(basePayload())
        assertNotNull(cachedPayload)
        assertEquals(null, cachedPayload.messages.firstOrNull { it.id == localMessageId })
        scope.cancel()
    }

    @Test
    fun refreshRetainsLocalFailedBubbleMissingFromServerPage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.ApplyLocalSendStarted(localPendingMessage(localMessageId, "retry body")))
        store.accept(SharedThreadIntent.ApplyLocalSendFailure(localMessageId, "network_error"))

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
        val beforeMessages = store.state.value.payload.messages
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                memberRepository = flakyMemberRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(1, flakyMemberRepository.channelMemberCalls, "Start should background-fetch channel members")

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
    fun startHydratesThreadMembersInBackgroundAfterRefresh() = runBlocking {
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
    fun channelPersistentCachedSnapshotIsVisibleBeforeLoadingFrame() = runBlocking {
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertTrue(logs.any { it.contains("reason=before_visible") && it.contains("hit=true") })
        assertTrue(logs.any { it.contains("thread_visible_payload") && it.contains("status=content") })
        assertFalse(logs.any { it.contains("thread_visible_payload") && it.contains("status=loading") })
        assertEquals("first visible cached body", store.state.value.payload.messages.single().body)
        scope.cancel()
    }

    @Test
    fun directMessageCachedSnapshotDoesNotCopyLaunchPeerHeader() = runBlocking {
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(directMessagePayload()))

        val payload = store.state.value.payload
        val headerDisplay = payload.threadHeaderDisplayModel(null)
        assertEquals("content", payload.status)
        assertEquals("Direct Message", payload.title)
        assertEquals("Direct Message", headerDisplay.title)
        assertEquals(null, payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals(null, payload.dmPeerAvatarLabel)
        assertEquals(null, payload.dmPeerGravatarUrl)
        assertEquals("cached dm body", payload.messages.single().body)
        scope.cancel()
    }

    @Test
    fun agentDirectMessageCachedSnapshotRejectsLaunchGravatarHeader() = runBlocking {
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
                        presenceMemberId = MemberId("agent-1"),
                        avatar = AvatarReference.fromWireValue("pixel:tree"),
                        avatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                        gravatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404"
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
                                    gravatarHash = "agent-hash"
                                ),
                            timestampLabel = "2026-06-21T15:32:00Z",
                            body = "cached agent dm body"
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
        val gravatarSeed =
            directMessagePayload().copy(
                sourceThreadId = "dm-agent-1",
                streamChannelId = "dm-agent-1",
                title = "Product-UX-Designer",
                dmPeerName = "Product-UX-Designer",
                dmPeerAvatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                dmPeerAvatarLabel = "PU",
                dmPeerGravatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404"
            )

        store.accept(SharedThreadIntent.Start(gravatarSeed))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("Product-UX-Designer", payload.dmPeerName)
        assertEquals("pixel:tree", payload.dmPeerAvatarUrl)
        assertEquals(null, payload.dmPeerGravatarUrl)
        assertEquals("pixel:robot", payload.messages.single().avatarWireValue)
        assertEquals(null, payload.messages.single().gravatarHash)
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
                            gravatarHash = "agent-hash",
                            avatar = AvatarReference.Pixel("tree")
                        )
                    )
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                memberRepository = memberRepository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals(emptyList(), memberRepository.serverMemberRequests)
        val message = payload.messages.single()
        assertEquals("Product-UX-Designer", message.authorName)
        assertEquals(null, message.avatarWireValue)
        assertEquals(null, message.gravatarHash)
    }

    @Test
    fun secondarySenderProfileRefreshPatchesAndPersistsAgentAvatar() = runBlocking {
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                memberRepository = memberRepository,
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertTrue(memberRepository.serverMemberRequests.contains(ServerId("server-1")))
        val message = store.state.value.payload.messages.single()
        assertEquals("Product-UX-Designer", message.authorName)
        assertEquals("pixel:tree", message.avatarWireValue)
        assertEquals(
            "pixel:tree",
            localStore.readThread(ThreadId("channel-1"))?.messages?.single()?.author?.avatar?.wireValue
        )
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
                            gravatarHash = "agent-hash"
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
        assertEquals(null, payload.originMessage?.gravatarHash)
    }

    @Test
    fun taskReplyThreadPayloadResolvesParentChannelNameFromHomeSnapshot() = runBlocking {
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
                        )
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore),
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
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
    }

    @Test
    fun cachedThreadPayloadNormalizesAgentGravatarBeforeFirstRender() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation = conversationSummary(),
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
                                        gravatarHash = "agent-hash"
                                    )
                            )
                    )
            )
        )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )

        val payload = dataSource.readCachedPayload(basePayload())

        val message = payload?.messages?.single()
        assertEquals("pixel:robot", message?.avatarWireValue)
        assertEquals(null, message?.gravatarHash)
    }

    @Test
    fun senderProfileEnrichmentReplacesAgentGravatarWithPixelAvatar() = runBlocking {
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
                            gravatarHash = "agent-hash",
                            avatar = AvatarReference.Pixel("tree")
                        )
                    )
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                memberRepository = memberRepository
            )
        val payload =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "agent-1",
                            authorName = "Product UX",
                            authorKind = "agent",
                            avatarWireValue = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                            gravatarHash = "agent-hash",
                            body = "agent body"
                        )
                    )
            )

        val enriched = dataSource.enrichSenderProfiles(payload)

        val message = enriched.messages.single()
        assertEquals("pixel:tree", message.avatarWireValue)
        assertEquals(null, message.gravatarHash)
    }

    @Test
    fun senderProfileEnrichmentReplacesAgentFallbackWithPixelAvatar() = runBlocking {
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                memberRepository = memberRepository
            )
        val payload =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "agent-1",
                            authorName = "Product UX",
                            authorKind = "agent",
                            avatarWireValue = "pixel:robot",
                            body = "agent body"
                        )
                    )
            )

        val enriched = dataSource.enrichSenderProfiles(payload)

        val message = enriched.messages.single()
        assertEquals("pixel:tree", message.avatarWireValue)
        assertEquals(null, message.gravatarHash)
    }

    @Test
    fun cachedHomeDirectMessageAvatarReplacesAgentFallbackAvatar() = runBlocking {
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
        val payload =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        KuiklyThreadMessageItem(
                            id = "m1",
                            authorId = "agent-1",
                            authorName = "Product UX",
                            authorKind = "agent",
                            avatarWireValue = "pixel:robot",
                            body = "agent body"
                        )
                    )
            )

        val enriched = dataSource.enrichCachedSenderProfiles(payload)

        val message = enriched.messages.single()
        assertEquals("pixel:tree", message.avatarWireValue)
        assertEquals(null, message.gravatarHash)
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository
            )
        val parentRoute =
            directMessagePayload().copy(
                title = "Cindy",
                dmPeerName = "Cindy",
                dmPeerAvatarUrl = "pixel:cindy",
                dmPeerAvatarLabel = "CI",
                dmPeerGravatarUrl = null,
                focusedMessageId = "parent-1"
            )

        val payload = (dataSource.openMessageContext("parent-1", parentRoute) as DataResult.Content).data

        assertEquals(listOf(MessageId("parent-1")), repository.getMessageContextRequests)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("dm-1", payload.sourceThreadId)
        assertEquals("dm-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        assertEquals("Cindy", payload.title)
        assertEquals(null, payload.subtitle)
        assertEquals("Cindy", payload.dmPeerName)
        assertEquals("pixel:cindy", payload.dmPeerAvatarUrl)
        assertEquals("CI", payload.dmPeerAvatarLabel)
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
                                                    gravatarHash = "agent-hash"
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                memberRepository = memberRepository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        val message = payload.messages.single()
        assertEquals("pixel:robot", message.avatarWireValue)
        assertEquals(null, message.gravatarHash)
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository
            )

        val payload =
            (dataSource.fetchThreadPayload(basePayload().copy(focusedMessageId = "target")) as DataResult.Content).data

        assertEquals(listOf(MessageId("target")), repository.getMessageContextRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals(listOf("older", "target", "newer"), payload.messages.map { it.id })
        assertEquals("target", payload.focusedMessageId)
        assertTrue(payload.canLoadMoreBefore)
        assertTrue(payload.canLoadMoreAfter)
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository
            )

        val payload =
            (dataSource.fetchThreadPayload(basePayload().copy(focusedMessageId = "target")) as DataResult.Content).data

        assertEquals(listOf(MessageId("target")), repository.getMessageContextRequests)
        assertEquals(listOf(ThreadId("channel-1")), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals(listOf("repository body"), payload.messages.map { it.body })
        assertEquals("target", payload.focusedMessageId)
    }

    @Test
    fun fetchThreadPayloadUsesHomeHeaderWhenRepositorySnapshotTitleIsIdentity() = runBlocking {
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
                        )
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore),
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals("all", payload.title)
        assertEquals("General updates", payload.subtitle)
        assertEquals(4, payload.membersCount)
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

        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                conversationRepository = repository
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
            listOf(ChannelId("channel-1") to MessageId("m1"), ChannelId("channel-1") to MessageId("m1")),
            repository.getReplyThreadRequests
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
        assertEquals(ThreadReplyPageTitle, payload.title)
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
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
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
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
                threadResult = DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true)),
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
        assertEquals(ThreadReplyPageTitle, payload.title)
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
            SharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
            "realtime body",
            localStore.readThread(ThreadId("channel-1"))?.messages?.firstOrNull { it.id == MessageId("m2") }?.body
        )
        val freshDataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = ComposeLocalWriteThrough(localStore)
            )
        val freshStore = SharedThreadStore(dataSource = freshDataSource, scope = scope)
        freshStore.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals(
            listOf("cached body", "realtime body"),
            freshStore.state.value.payload.messages.map { it.body }
        )
        scope.cancel()
    }

    @Test
    fun realtimeMessageNewMergesVisiblePayloadWhenPersistentCacheMissesEvent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                localWriteThrough = null
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

        assertEquals(listOf("cached body", "realtime body"), store.state.value.payload.messages.map { it.body })
        scope.cancel()
    }

    @Test
    fun realtimeMessageNewDoesNotCrossContextWindowNewerGap() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi =
                    FakeThreadApi(
                        bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false)
                    ),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
        assertEquals(listOf("m10"), payload.messages.map { it.id })
        assertTrue(payload.canLoadMoreAfter)
        scope.cancel()
    }

    @Test
    fun prepareForLocalSendClearsFocusedNewerContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            SharedThreadDataSource(
                networkApi =
                    FakeThreadApi(
                        bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false)
                    ),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(focusedMessageId = "m10")))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))

        store.accept(SharedThreadIntent.PrepareForLocalSend)

        val payload = store.state.value.payload
        assertEquals(null, payload.focusedMessageId)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }

    @Test
    fun realtimeCacheSnapshotReadsPersistentThreadStateWithoutEventProjection() = runBlocking {
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

        dataSource.writeCachedPayload(
            contentPayloadForRoute(
                base = basePayload(),
                body = "cache body",
                id = "m2"
            )
        )

        store.accept(SharedThreadIntent.ApplyRealtimeCacheSnapshot)

        assertEquals(listOf("cache body"), store.state.value.payload.messages.map { it.body })
        assertEquals("cache body", localStore.readThread(ThreadId("channel-1"))?.messages?.single()?.body)
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
    fun realtimeThreadUpdatedAppliesReplyMetadataToDirectMessageParent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
        dataSource.cachePayload(
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
        val dataSource = canonicalThreadDataSource()
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
        dataSource.cachePayload(
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
                        threadId = "channel-1",
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
    fun realtimeMessageUpdatedAppliesReactionPayloadIncludingEmptyRemoval() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource = canonicalThreadDataSource()
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
    fun applyReplyThreadReadClearsParentUnreadProjectionAndCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
        dataSource.cachePayload(initial)
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

        val loading = basePayload().replyThreadLoadingPayload(parent, focusedMessageId = parent.firstUnreadReplyMessageId)

        assertEquals("reply-1", loading.focusedMessageId)
        assertEquals("m1", loading.parentMessageId)
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

        delay(120L)

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
    fun fetchThreadPayloadDefersSavedStatusUntilSecondaryRefresh() = runBlocking {
        val savedStatusRepository = FakeSavedStatusRepository(savedIds = setOf(MessageId("m1")))
        val dataSource =
            SharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
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
        val api = FakeThreadApi(bodyText = messagesResponse("reply body", id = "reply-1", seq = 11, channelId = "thread-m1"))
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
        assertEquals("empty", payload.status)
        assertEquals(emptyList(), payload.messages)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
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
    fun fetchThreadPayloadHydratesReplyOriginFromParentMessageContextWhenRepliesExcludeParent() = runBlocking {
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
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests.takeLast(1))
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
                newerBodyText = messagesResponse("newer body", id = "m12", seq = 12, historyLimited = false)
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
        assertEquals(listOf(10L), api.afterSeqs)
        assertEquals(emptyList(), api.syncSinceSeqs)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
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
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
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
    fun realtimeGapRecoverySkipsWhenVisiblePayloadAlreadyCaughtUp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = syncResponse("unused", id = "m12", seq = 12)
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = api,
                sessionStore = InMemorySlockAuthSessionStore(sampleSession())
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 10))

        assertEquals(emptyList(), api.syncSinceSeqs)
        assertEquals(listOf("current body"), store.state.value.payload.messages.map { it.body })
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
        assertEquals(listOf(10L), api.afterSeqs)
        assertEquals(emptyList(), api.syncSinceSeqs)
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
        val afterSeqs: MutableList<Long> = mutableListOf()
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
        val bodyTextByThreadId: MutableMap<String, String> = mutableMapOf()
        val channelMessagesStartedByThreadId: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()
        val releaseChannelMessagesByThreadId: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()
        var channelMessagesStarted: CompletableDeferred<Unit>? = null
        var releaseChannelMessages: CompletableDeferred<Unit>? = null

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
                    HomeNetworkResponse(code = 200, bodyText = newerBodyText)
                }
                else -> HomeNetworkResponse(code = 200, bodyText = responseBody)
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
        override suspend fun updateChannel(channelId: ChannelId, visibility: ChannelVisibility): DataResult<ChannelUpdateResult> = error("unused")
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
        val syncNewerRequests: MutableList<Pair<ThreadId, Long>> = mutableListOf()
        val syncNewerLimits: MutableList<Int> = mutableListOf()
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

        override suspend fun syncNewerMessages(
            threadId: ThreadId,
            sinceSeq: Long,
            limit: Int
        ): DataResult<ThreadSnapshot> {
            syncNewerRequests += threadId to sinceSeq
            syncNewerLimits += limit
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

    private fun contentPayload(body: String): KuiklyThreadScreenPayload =
        contentPayloadForRoute(basePayload(), body)

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
                    messages = listOf(WireMessage(id = id, channelId = channelId, senderType = "user", senderId = "u1", senderName = "Artin", content = body)),
                    canLoadMoreBefore = false,
                    canLoadMoreAfter = null
                ),
            session = sampleSession()
        )

    private fun canonicalThreadDataSource(): SharedThreadDataSource =
        SharedThreadDataSource(
            networkApi = FakeThreadApi(failChannelMessages = true),
            sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
            localWriteThrough = ComposeLocalWriteThrough(InMemoryComposeLocalDataStore())
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
