package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.authSessionFixture
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedMarkdownDirectMessageThreadRefTest {
    @Test
    fun dmThreadNavigationRemainsAvailableWithoutWriteAccess() {
        assertFalse(threadEventRequiresWriteAccess("openMarkdownDirectMessageThreadRef"))
        assertTrue(threadEventAllowed(writeActionsEnabled = false, "openMarkdownDirectMessageThreadRef"))
    }

    @Test
    fun homeEnrichmentKeepsDmPeersSeparateFromOrdinaryMentionValidity() = runBlocking {
        val dataSource =
            testSharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(authSessionFixture(userId = "viewer")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                homeSnapshotProvider = { dmHomeSnapshot() }
            )

        val enriched =
            dataSource.withKnownMarkdownChannelNames(
                KuiklyThreadScreenPayload(knownMarkdownMentionNames = listOf("message-author"))
            )

        assertEquals(listOf("message-author"), enriched.knownMarkdownMentionNames)
        assertEquals(listOf("Artin"), enriched.knownMarkdownDirectMessagePeerNames)
    }

    @Test
    fun homeEnrichmentOmitsAmbiguousDmAliases() = runBlocking {
        val uniqueHome = dmHomeSnapshot()
        val ambiguousHome =
            uniqueHome.copy(
                directMessages =
                    uniqueHome.directMessages +
                        uniqueHome.directMessages.single().copy(
                            threadId = ThreadId("dm-channel-b"),
                            channelId = ChannelId("dm-channel-b")
                        )
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = InMemorySlockAuthSessionStore(authSessionFixture(userId = "viewer")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                homeSnapshotProvider = { ambiguousHome }
            )

        val enriched = dataSource.withKnownMarkdownChannelNames(KuiklyThreadScreenPayload())

        assertEquals(emptyList(), enriched.knownMarkdownDirectMessagePeerNames)
    }

    @Test
    fun dmPeerClickRequestsContextInCanonicalDmAndBuildsIdentityOnlyReplyRoute() = runBlocking {
        val sessionStore = InMemorySlockAuthSessionStore(authSessionFixture(userId = "viewer"))
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot()),
                parentResult = DataResult.Content(dmMessageContextSnapshot())
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = sessionStore,
                conversationRepository = repository,
                homeSnapshotProvider = { dmHomeSnapshot() },
                cacheEnvironmentIdProvider = { EnvironmentId },
                replyCacheIdentityAuthority = replyThreadAccessIdentityAuthority(sessionStore, EnvironmentId)
            )
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        val result =
            dataSource.markdownThreadRefLaunchPayload(
                provenance = provenance,
                currentPayload = KuiklyThreadScreenPayload(status = "content", canSend = false),
                channelLabel = "artin",
                threadShortId = ShortId,
                requiredParentKind = ConversationKind.DirectMessage
            )

        assertEquals(1, repository.parentRequests.size)
        assertEquals(MessageId(ShortId), repository.parentRequests.single().first)
        assertEquals(ChannelId(DmChannelId), repository.parentRequests.single().second)
        val payload = assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(result).data
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.parentSurfaceKind)
        assertEquals("dm", payload.parentChannelKind)
        assertEquals(DmChannelId, payload.parentChannelId)
        assertEquals(ParentMessageId, payload.parentMessageId)
        assertEquals(ThreadChannelId, payload.sourceThreadId)
        assertEquals(ThreadChannelId, payload.streamChannelId)
        assertEquals(FocusedMessageId, payload.focusedMessageId)
        assertEquals(emptyList(), payload.messages)
    }

    @Test
    fun dmPeerResolverAcceptsProfileHandleAndDisplayNameButFailsClosedOnAmbiguity() {
        val peer = dmPeer()
        val uniqueHome = dmHomeSnapshot(dmName = "Direct conversation")

        listOf("artin", "Artin Zhang").forEach { alias ->
            val payload =
                buildMarkdownThreadRefLaunchPayload(
                    currentPayload = KuiklyThreadScreenPayload(),
                    home = uniqueHome,
                    channelLabel = alias,
                    threadShortId = ShortId,
                    requiredParentKind = ConversationKind.DirectMessage,
                    knownMemberProfilesById = mapOf(peer.id.value to peer),
                    parentMessageId = ParentMessageId
                )
            assertEquals(DmChannelId, requireNotNull(payload).parentChannelId)
        }

        val sameNameChannel =
            ConversationSummary(
                threadId = ThreadId("ordinary-channel-id"),
                channelId = ChannelId("ordinary-channel-id"),
                kind = ConversationKind.Channel,
                name = "artin"
            )
        val withSameNameChannel =
            uniqueHome.copy(channels = listOf(sameNameChannel.toHomeChannelRow()))
        assertEquals(
            DmChannelId,
            requireNotNull(
                buildMarkdownThreadRefLaunchPayload(
                    currentPayload = KuiklyThreadScreenPayload(),
                    home = withSameNameChannel,
                    channelLabel = "artin",
                    threadShortId = ShortId,
                    requiredParentKind = ConversationKind.DirectMessage,
                    knownMemberProfilesById = mapOf(peer.id.value to peer),
                    parentMessageId = ParentMessageId
                )
            ).parentChannelId
        )

        val ambiguousHome =
            uniqueHome.copy(
                directMessages =
                    uniqueHome.directMessages +
                        uniqueHome.directMessages.single().copy(
                            threadId = ThreadId("dm-channel-b"),
                            channelId = ChannelId("dm-channel-b")
                        )
            )
        assertEquals(
            null,
            buildMarkdownThreadRefLaunchPayload(
                currentPayload = KuiklyThreadScreenPayload(),
                home = ambiguousHome,
                channelLabel = "artin",
                threadShortId = ShortId,
                requiredParentKind = ConversationKind.DirectMessage,
                knownMemberProfilesById = mapOf(peer.id.value to peer),
                parentMessageId = ParentMessageId
            )
        )
        assertEquals(
            null,
            buildMarkdownThreadRefLaunchPayload(
                currentPayload = KuiklyThreadScreenPayload(),
                home = uniqueHome,
                channelLabel = "unknown",
                threadShortId = ShortId,
                requiredParentKind = ConversationKind.DirectMessage,
                knownMemberProfilesById = mapOf(peer.id.value to peer),
                parentMessageId = ParentMessageId
            )
        )
    }

    @Test
    fun dmPeerResolverAcceptsTheCompleteTokenWithoutConfusingItsPrefixForAChannel() {
        val payload =
            buildMarkdownThreadRefLaunchPayload(
                currentPayload = KuiklyThreadScreenPayload(),
                home = dmHomeSnapshot(),
                channelLabel = "dm:@artin:$ShortId",
                threadShortId = ShortId,
                requiredParentKind = ConversationKind.DirectMessage,
                parentMessageId = ParentMessageId
            )

        assertEquals(DmChannelId, requireNotNull(payload).parentChannelId)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, payload.parentSurfaceKind)
        assertEquals("dm", payload.parentChannelKind)
    }

    private fun dmHomeSnapshot(dmName: String = "Artin"): HomeSnapshot {
        val peer = dmPeer()
        return HomeSnapshot(
            server = dmServer(),
            currentMember = peer.copy(id = MemberId("viewer"), displayName = "Viewer", handle = "viewer"),
            channels = emptyList(),
            directMessages =
                listOf(
                    ConversationSummary(
                        threadId = ThreadId(DmChannelId),
                        channelId = ChannelId(DmChannelId),
                        kind = ConversationKind.DirectMessage,
                        name = dmName,
                        presenceMemberId = peer.id
                    ).toHomeDirectMessageRow()
                ),
            unreadTotal = 0
        )
    }

    private fun dmPeer(): MemberProfile =
        MemberProfile(
            id = MemberId("peer-artin"),
            displayName = "Artin Zhang",
            handle = "artin",
            kind = MemberKind.Human
        )

    private fun dmServer(): ServerSummary = ServerSummary(id = ServerId("server-1"), name = "One")

    private fun dmMessageContextSnapshot() =
        replyThreadAccessSnapshot().copy(
            conversation =
                ConversationSummary(
                    threadId = ThreadId(ThreadChannelId),
                    channelId = ChannelId(ThreadChannelId),
                    kind = ConversationKind.Thread,
                    name = "Artin",
                    thread =
                        ThreadConversation(
                            parentChannelId = ChannelId(DmChannelId),
                            parentMessageId = MessageId(ParentMessageId),
                            streamChannelId = ChannelId(ThreadChannelId),
                            parentSurfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                            parentChannelKind = "dm"
                        )
                ),
            messages = emptyList(),
            parentMessage = null,
            highlightedMessageId = MessageId(FocusedMessageId)
        )

    private companion object {
        const val EnvironmentId = "https://prod.example.test"
        const val DmChannelId = "dm-channel-id"
        const val ShortId = "1c50c30a"
        const val ParentMessageId = "parent-message-id"
        const val ThreadChannelId = "reply-thread-id"
        const val FocusedMessageId = "reply-message-id"
    }
}
