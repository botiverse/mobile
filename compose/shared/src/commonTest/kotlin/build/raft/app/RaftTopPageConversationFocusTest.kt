package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.RaftThreadTab
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.dispatch
import build.raft.app.thread.resolveThreadRouteIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftTopPageConversationFocusTest {
    @Test
    fun dispatchesImmediatelyToMatchingMountedRouteAndIgnoresOtherConversations() {
        val signal = RaftTopPageConversationFocusSignal(nowEpochMillis = { 1_000L })
        val dmRoute = dmRoute("dm-1")
        val otherRoute = dmRoute("dm-2")
        val dmReceived = mutableListOf<RaftTopPageConversationFocusRequest>()
        val otherReceived = mutableListOf<RaftTopPageConversationFocusRequest>()

        val unregisterDm = signal.register(routeProvider = { dmRoute }) { req ->
            dmReceived += req
            true
        }
        val unregisterOther = signal.register(routeProvider = { otherRoute }) { req ->
            otherReceived += req
            true
        }

        val first = signal.dispatch(targetRoute = dmRoute, messageId = "msg-2", source = "system_notification")
        assertNotNull(first)
        assertTrue(first.deliveredImmediately)
        assertEquals(1L, first.request.nonce)
        assertEquals(listOf("msg-2"), dmReceived.map { it.messageId })
        assertTrue(otherReceived.isEmpty(), "non-matching conversation must not receive focus")

        // Second dispatch advances monotonic nonce even for the same message.
        val second = signal.dispatch(targetRoute = dmRoute, messageId = "msg-2", source = "system_notification")
        assertNotNull(second)
        assertTrue(second.deliveredImmediately)
        assertEquals(2L, second.request.nonce)
        assertEquals(2, dmReceived.size)

        // Blank message id is ignored.
        assertNull(signal.dispatch(targetRoute = dmRoute, messageId = "   ", source = "system_notification"))

        // Unregistered (popped) page never receives subsequent focus requests.
        unregisterDm()
        unregisterOther()
        val afterPop = signal.dispatch(targetRoute = dmRoute, messageId = "msg-3", source = "system_notification")
        assertNotNull(afterPop)
        assertFalse(afterPop.deliveredImmediately)
        assertEquals(2, dmReceived.size)
    }

    @Test
    fun topmostMountedTargetWinsAndUnresolvedReplyThreadMatchesByParentKey() {
        val signal = RaftTopPageConversationFocusSignal(nowEpochMillis = { 1_000L })
        val unresolvedReply =
            resolveThreadRouteIdentity(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = null,
                instanceId = "reply-parent:ch-1:root-1",
                sourceThreadId = null,
                streamChannelId = null,
                parentChannelId = "ch-1",
                parentMessageId = "root-1",
                parentConversationKind = ConversationKind.Channel,
            )
        val resolvedReplyFromNotification =
            resolveThreadRouteIdentity(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = null,
                instanceId = "reply-stream:stream-99",
                sourceThreadId = "stream-99",
                streamChannelId = "stream-99",
                parentChannelId = "ch-1",
                parentMessageId = "root-1",
                parentConversationKind = ConversationKind.Channel,
            )
        val bottomReceived = mutableListOf<String>()
        val topReceived = mutableListOf<String>()

        signal.register(routeProvider = { unresolvedReply }) { req ->
            bottomReceived += req.messageId
            true
        }
        signal.register(routeProvider = { unresolvedReply }) { req ->
            topReceived += req.messageId
            true
        }

        val result = signal.dispatch(resolvedReplyFromNotification, messageId = "reply-2", source = "system_notification")
        assertNotNull(result)
        assertTrue(result.deliveredImmediately)
        assertEquals(listOf("reply-2"), topReceived, "topmost registered page must handle the focus")
        assertTrue(bottomReceived.isEmpty())
    }

    @Test
    fun mountingWindowPendingRequestConsumesOnceNeverLeaksAndExpiresOrClears() {
        var nowMillis = 10_000L
        val signal = RaftTopPageConversationFocusSignal(nowEpochMillis = { nowMillis }, pendingTtlMillis = 5_000L)
        val dmRoute = dmRoute("dm-1")
        val otherRoute = dmRoute("dm-2")

        // Dispatched while the pushed top page is still mounting.
        val staged = signal.dispatch(targetRoute = dmRoute, messageId = "msg-2", source = "system_notification")
        assertNotNull(staged)
        assertFalse(staged.deliveredImmediately)

        // A non-matching conversation mounting must NOT steal or clear it.
        val otherReceived = mutableListOf<String>()
        signal.register(routeProvider = { otherRoute }) { req ->
            otherReceived += req.messageId
            true
        }()
        assertTrue(otherReceived.isEmpty())

        // The matching page mounts and consumes it once.
        val firstMountReceived = mutableListOf<String>()
        signal.register(routeProvider = { dmRoute }) { req ->
            firstMountReceived += req.messageId
            true
        }()
        assertEquals(listOf("msg-2"), firstMountReceived)

        // A later fresh open of the same conversation must NOT replay the already-consumed request.
        val secondMountReceived = mutableListOf<String>()
        signal.register(routeProvider = { dmRoute }) { req ->
            secondMountReceived += req.messageId
            true
        }()
        assertTrue(secondMountReceived.isEmpty(), "consumed focus request must never replay")

        // clearPending() prevents replay when a fresh conversation opens before an old pending was consumed.
        signal.dispatch(targetRoute = dmRoute, messageId = "msg-stale-clear", source = "system_notification")
        signal.clearPending()
        val afterClearReceived = mutableListOf<String>()
        signal.register(routeProvider = { dmRoute }) { req ->
            afterClearReceived += req.messageId
            true
        }()
        assertTrue(afterClearReceived.isEmpty(), "clearPending must drop unconsumed pending focus")

        // Expired pending request must not replay to a future open.
        signal.dispatch(targetRoute = dmRoute, messageId = "msg-expired", source = "system_notification")
        nowMillis += 5_001L
        val afterExpiryReceived = mutableListOf<String>()
        signal.register(routeProvider = { dmRoute }) { req ->
            afterExpiryReceived += req.messageId
            true
        }()
        assertTrue(afterExpiryReceived.isEmpty(), "expired pending focus must not replay")
    }

    @Test
    fun shellScopedForegroundBannerStoresDoNotCrossDeliverAndClearDropsPending() = runTest {
        val shellA = SharedForegroundBannerStore(this) { CompletableDeferred<Unit>().await() }
        val shellB = SharedForegroundBannerStore(this) { CompletableDeferred<Unit>().await() }
        val dmRoute = dmRoute("dm-1")
        val receivedA = mutableListOf<String>()
        val receivedB = mutableListOf<String>()

        val unregA = shellA.topPageFocus.register(routeProvider = { dmRoute }) { req ->
            receivedA += req.messageId
            true
        }
        val unregB = shellB.topPageFocus.register(routeProvider = { dmRoute }) { req ->
            receivedB += req.messageId
            true
        }

        shellA.topPageFocus.dispatch(dmRoute, messageId = "msg-a", source = "system_notification")
        assertEquals(listOf("msg-a"), receivedA)
        assertTrue(receivedB.isEmpty(), "window A focus must never leak into window B")

        unregA()
        unregB()
        shellA.topPageFocus.dispatch(dmRoute, messageId = "msg-pending", source = "system_notification")
        shellA.clear()
        val afterClear = mutableListOf<String>()
        shellA.topPageFocus.register(routeProvider = { dmRoute }) { req ->
            afterClear += req.messageId
            true
        }()
        assertTrue(afterClear.isEmpty(), "SharedForegroundBannerStore.clear() must clear pending focus")
    }

    @Test
    fun topPageFocusWiringJumpsToUnloadedBackgroundMessageSwitchesTabAndBlocksConcurrentResumeClobber() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot()),
            )
        val contextGate = CompletableDeferred<Unit>()
        repository.releaseParent = contextGate
        repository.parentResult = DataResult.Content(dmContext("msg-1", "msg-2"))
        val sessionStore =
            InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1", selectedServerId = ServerIdValue))
        val dataSource =
            SharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = sessionStore,
                conversationRepository = repository,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                replyCacheIdentityAuthority = replyThreadAccessIdentityAuthority(sessionStore, TestThreadEnvironmentId),
            )
        val initialPayload = dmWindow(selectedTab = RaftThreadTab.Tasks.wireValue, messageIds = listOf("msg-1"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, initialPayload = initialPayload)
        val signal = RaftTopPageConversationFocusSignal(nowEpochMillis = { 1_000L })
        val dmRoute = initialPayload.resolveThreadRouteIdentity()
        val nonceBefore = store.state.value.payload.focusedMessageRequestNonce

        try {
            val unregister =
                signal.register(routeProvider = { store.state.value.payload.resolveThreadRouteIdentity() }) { req ->
                    store.dispatch(
                        SharedThreadIntent.JumpToMessage(
                            messageId = req.messageId,
                            switchToTab = RaftThreadTab.Chat.wireValue,
                            unreadThreadPill = false,
                        )
                    )
                    true
                }

            // Background push #2 arrives before msg-2 is in DM X's local message list (and while on Tasks tab).
            val dispatched = signal.dispatch(targetRoute = dmRoute, messageId = "msg-2", source = "system_notification")
            assertNotNull(dispatched)
            assertTrue(dispatched.deliveredImmediately)
            advanceUntilIdle()

            // Store switched to Chat and started anchored context fetch for msg-2.
            assertEquals(RaftThreadTab.Chat.wireValue, store.state.value.payload.selectedTab)
            assertEquals("msg-2", store.state.value.payload.focusedMessageId)
            assertEquals(listOf(MessageId("msg-2")), repository.parentRequests.map { it.first })

            // Concurrent foreground-resume catch-up while the context jump is in flight is skipped cleanly.
            store.dispatch(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
            advanceUntilIdle()
            assertEquals(listOf(MessageId("msg-2")), repository.parentRequests.map { it.first })

            // Context fetch completes: msg-2 is now in the timeline, focused with a fresh nonce.
            contextGate.complete(Unit)
            advanceUntilIdle()
            val afterJump = store.state.value.payload
            assertEquals(listOf("msg-1", "msg-2"), afterJump.messages.map { it.id })
            assertEquals("msg-2", afterJump.focusedMessageId)
            assertNotEquals(nonceBefore, afterJump.focusedMessageRequestNonce)
            assertEquals(RaftThreadTab.Chat.wireValue, afterJump.selectedTab)

            // Focusing an already-loaded message updates nonce in place without issuing another network fetch.
            val nonceAfterContextJump = afterJump.focusedMessageRequestNonce
            store.dispatch(SharedThreadIntent.SelectTab(RaftThreadTab.Files.wireValue))
            advanceUntilIdle()
            signal.dispatch(targetRoute = dmRoute, messageId = "msg-1", source = "system_notification")
            advanceUntilIdle()
            val afterInPlace = store.state.value.payload
            assertEquals("msg-1", afterInPlace.focusedMessageId)
            assertNotEquals(nonceAfterContextJump, afterInPlace.focusedMessageRequestNonce)
            assertEquals(RaftThreadTab.Chat.wireValue, afterInPlace.selectedTab)
            assertEquals(1, repository.parentRequests.size, "already-loaded focus must not re-fetch")

            unregister()
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    private fun dmRoute(dmId: String): ThreadRouteIdentity =
        resolveThreadRouteIdentity(
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            channelKind = null,
            instanceId = "dm:$dmId",
            sourceThreadId = dmId,
            streamChannelId = dmId,
            parentChannelId = null,
            parentMessageId = null,
        )

    private fun dmWindow(selectedTab: String, messageIds: List<String>): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "dm:$DmIdValue",
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            sourceThreadId = DmIdValue,
            streamChannelId = DmIdValue,
            selectedTab = selectedTab,
            status = "content",
            messages =
                messageIds.mapIndexed { index, id ->
                    RaftThreadMessageItem(
                        id = id,
                        authorId = "u2",
                        authorName = "Alice",
                        seq = 10L + index,
                        body = id,
                    )
                },
            canSend = true,
        )

    private fun dmContext(vararg ids: String): ThreadSnapshot {
        val channelId = ChannelId(DmIdValue)
        return ThreadSnapshot(
            conversation =
                ConversationSummary(
                    threadId = ThreadId(DmIdValue),
                    channelId = channelId,
                    kind = ConversationKind.DirectMessage,
                    name = "Alice",
                    joined = true,
                    permissions = ConversationPermissions(canRead = true, canSend = true),
                ),
            messages =
                ids.mapIndexed { index, id ->
                    Message(
                        id = MessageId(id),
                        threadId = ThreadId(DmIdValue),
                        channelId = channelId,
                        author =
                            MemberProfile(
                                id = MemberId("u2"),
                                displayName = "Alice",
                                handle = "alice",
                                kind = MemberKind.Human,
                                presence = Presence.Active,
                            ),
                        seq = 10L + index,
                        timestampLabel = "2026-11-25T12:00:00Z",
                        body = id,
                    )
                },
        )
    }

    private companion object {
        const val DmIdValue = "dm-f0a32ee2"
        const val ServerIdValue = "server-1"
    }
}
