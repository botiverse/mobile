package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.unresolvedReplyPayload
import build.raft.app.SharedThreadStoreTestFixtures.virtualForegroundLoadGate
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class SharedThreadStoreRouteIdentityAndCacheScopeTest {
    @Test
    fun threadRouteIdentitySeparatesUnresolvedReplyParentFromResolvedTimeline() {
        val unresolved = unresolvedReplyPayload(parentMessageId = "m1").resolveThreadRouteIdentity()
        val resolved = replyPayload(parentMessageId = "m1", threadId = "thread-m1").resolveThreadRouteIdentity()

        assertEquals(RaftThreadSurfaceKind.ReplyThread, unresolved.surfaceKind)
        assertEquals("reply-parent:channel-1:m1", unresolved.instanceKey)
        assertNull(unresolved.timelineKey)
        assertFalse(unresolved.canRenderTimelineCandidates)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, resolved.surfaceKind)
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
                surfaceKind = RaftThreadSurfaceKind.Channel
            ).resolveThreadRouteIdentity()
        val dm =
            basePayload().copy(
                sourceThreadId = "same-stream",
                streamChannelId = "same-stream",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage
            ).resolveThreadRouteIdentity()

        assertEquals(RaftThreadSurfaceKind.Channel, channel.surfaceKind)
        assertEquals("channel:same-stream", channel.instanceKey)
        assertEquals("same-stream", channel.timelineKey)
        assertEquals("channel", channel.channelKind)
        assertEquals("channel", channel.routeKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, dm.surfaceKind)
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

        assertEquals(RaftThreadSurfaceKind.Channel, identity.surfaceKind)
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

        assertEquals(RaftThreadSurfaceKind.Channel, identity.surfaceKind)
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

        assertEquals(RaftThreadSurfaceKind.ReplyThread, identity.surfaceKind)
        assertEquals("reply-stream:reply-thread-1", identity.instanceKey)
        assertEquals("reply-thread-1", identity.timelineKey)
        assertTrue(identity.canRenderTimelineCandidates)
    }
    @Test
    fun threadRouteTimelineChannelIdOwnsMemoryCacheKey() {
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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

        // Positive control: the pending-send journal keeps the generation-exact owner, while the
        // timeline cache is keyed by environment + account + server only (data-layer plan step 2).
        assertEquals(
            "https://prod.example.test\u001fuser-1\u001fserver-1\u001f1\u001f2",
            full.threadPendingSendOwnerScopeId
        )
        assertEquals("https://prod.example.test\u001fuser-1\u001fserver-1", full.threadPayloadOwnerScopeId)
        assertEquals(
            full.threadPayloadOwnerScopeId,
            full.copy(authGeneration = 7L, selectionGeneration = 9L).threadPayloadOwnerScopeId
        )

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
            full.copy(selectionGeneration = 0L).threadPendingSendOwnerScopeId
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
                        RaftThreadMessageItem(
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
                        RaftThreadMessageItem(
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
                        RaftThreadMessageItem(
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
                        RaftThreadMessageItem(
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
    fun sameServerOwnerCannotJoinOrConsumeAnotherOwnersPrewarm() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
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
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(ownerBResult).data.messages.single().body
        )
        releaseOwnerA.complete(Unit)
        assertEquals(
            "owner-a body",
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(ownerAPrewarm.await()).data.messages.single().body
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
                        RaftThreadMessageItem(
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
}
