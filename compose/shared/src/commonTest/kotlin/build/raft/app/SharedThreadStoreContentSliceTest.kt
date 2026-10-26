package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.ConcurrentTestLogBuffer
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.directMessagePayload
import build.raft.app.SharedThreadStoreTestFixtures.emptyHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.localPendingMessage
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.unresolvedReplyPayload
import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.toHomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.Message
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SharedThreadStoreContentSliceTest {
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
                            RaftThreadMessageItem(
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
                        RaftThreadMessageItem(
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
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
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
    fun memoryCachedDirectMessageSnapshotCanRemainSparseWhenSnapshotOmitsDisplayMetadata() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
                        RaftThreadMessageItem(
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
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
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
    fun staleRefreshResultDoesNotOverwriteCurrentThreadRoute() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
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
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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

        withTimeout(10_000) {
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
        withTimeout(10_000) {
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
        withTimeout(10_000) {
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
        val loadedMember = RaftThreadMemberItem(id = "u1", displayName = "Artin")
        val loadedCandidate = RaftThreadMemberItem(id = "agent-1", displayName = "Cindy", kind = "agent")
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
        val loadedMember = RaftThreadMemberItem(id = "u1", displayName = "Artin")
        val loadedCandidate = RaftThreadMemberItem(id = "agent-1", displayName = "Cindy", kind = "agent")
        val networkMember = RaftThreadMemberItem(id = "u2", displayName = "Network User")
        val networkCandidate = RaftThreadMemberItem(id = "agent-2", displayName = "Network Agent", kind = "agent")
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
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                settingsActions = listOf(RaftThreadSettingsAction(id = "current-action")),
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
                surfaceKind = RaftThreadSurfaceKind.Channel,
                settingsActions = listOf(RaftThreadSettingsAction(id = "network-action")),
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
                    RaftThreadComposerPayload(
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
                    RaftThreadComposerPayload(
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
            RaftThreadTaskItem(
                id = "current-task",
                title = "Current task",
                status = "Todo",
                taskNumber = 1
            )
        val networkTask =
            RaftThreadTaskItem(
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
    fun startKeepsPayloadAccessDefaultsWithoutHomeSnapshot() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
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
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
    fun startAndInitialContentDoNotBlockOnHomeSnapshotFallback() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
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
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))

        withTimeout(10_000) {
            while (store.state.value.payload.messages.firstOrNull()?.body != "network body") {
                delay(1)
            }
        }
        withTimeout(10_000) {
            homeRequested.await()
        }
        releaseHome.complete(Unit)
        scope.cancel()
    }
    @Test
    fun startAndPagingUseConfiguredThreadPageSize() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))
        store.accept(SharedThreadIntent.LoadOlder)
        store.accept(SharedThreadIntent.LoadNewer)

        assertEquals(listOf(30, 30, 30), api.channelMessageLimits)
        assertEquals(0, api.catchUpCalls)
        scope.cancel()
    }
    @Test
    fun messageReactionIntentPatchesOnlyPayloadSliceAndNoopsWhenEquivalent() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        assertEquals(listOf(RaftThreadReactionItem("🔥", count = 1, selected = true)), patched.payload.messages.single().reactions)

        store.accept(SharedThreadIntent.ApplyMessageReactionSelection("m1", "🔥", selected = true))
        assertTrue(patched === store.state.value)
        scope.cancel()
    }
    @Test
    fun reactionToggleTargetPrefersSelectedDuplicateForRemoval() {
        val selectedReactions = listOf(RaftThreadReactionItem("🔥", count = 1, selected = true))
        val staleReactions = listOf(RaftThreadReactionItem("🔥", count = 1, selected = false))
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
                            reactions = listOf(RaftThreadReactionItem("🎉", count = 1, selected = true))
                        )
                    )
            )
        val staleRefresh =
            contentPayload("reactable body").copy(
                messages = listOf(baseMessage.copy(reactions = emptyList()))
            )

        val preserved = staleRefresh.preserveStableTimelineFrom(current)

        assertEquals(
            listOf(RaftThreadReactionItem("🎉", count = 1, selected = true)),
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
                            reactions = listOf(RaftThreadReactionItem("🎉", count = 1, selected = true))
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
            RaftThreadMessageItem(
                id = "local-send-thread-1-1",
                authorId = "user-1",
                authorName = "Artin",
                body = "pending reply",
                deliveryState = ThreadMessageDeliverySending,
                pendingSendContext =
                    RaftThreadPendingSendContext(
                        channelId = "channel-1",
                        body = "pending reply",
                        asTask = false,
                        attachmentIds = emptyList(),
                        streamChannelId = threadChannelId,
                        parentMessageId = parentMessageId
                    )
            )
        val current =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-pending-$parentMessageId",
                sourceThreadId = threadChannelId,
                streamChannelId = threadChannelId,
                parentChannelId = "channel-1",
                parentMessageId = parentMessageId,
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                status = "content",
                messages = listOf(pendingReply),
                originMessage =
                    RaftThreadMessageItem(
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
}
