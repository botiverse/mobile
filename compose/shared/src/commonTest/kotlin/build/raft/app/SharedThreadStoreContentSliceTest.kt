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
import build.raft.app.SharedThreadStoreTestFixtures.localPendingMessage
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.RaftThreadReactionItem
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadSendEchoMatchPolicy
import build.raft.app.thread.preserveStableTimelineFrom
import build.raft.app.thread.threadReactionToggleTarget
import build.raft.app.thread.withContentSliceFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
}
