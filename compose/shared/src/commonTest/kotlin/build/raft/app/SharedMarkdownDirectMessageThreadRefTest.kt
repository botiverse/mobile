package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.authSessionFixture
import build.raft.app.data.sharedReferenceFailure
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.markdown.RaftMarkdownMessageRef
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
import kotlinx.coroutines.async
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
        assertFalse(threadEventRequiresWriteAccess("openMarkdownDirectMessage"))
        assertTrue(threadEventAllowed(writeActionsEnabled = false, "openMarkdownDirectMessage"))
    }

    @Test
    fun homeEnrichmentKeepsDmPeersSeparateFromOrdinaryMentionValidity() = runBlocking {
        val dataSource =
            testSharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "viewer")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                homeSnapshotProvider = { dmHomeSnapshot() }
            )

        val enriched =
            dataSource.withKnownMarkdownChannelNames(
                RaftThreadScreenPayload(knownMarkdownMentionNames = listOf("message-author"))
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
                sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "viewer")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                homeSnapshotProvider = { ambiguousHome }
            )

        val enriched = dataSource.withKnownMarkdownChannelNames(RaftThreadScreenPayload())

        assertEquals(emptyList(), enriched.knownMarkdownDirectMessagePeerNames)
    }

    @Test
    fun dmPeerClickRequestsContextInCanonicalDmAndBuildsIdentityOnlyReplyRoute() = runBlocking {
        val sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "viewer"))
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
                currentPayload = RaftThreadScreenPayload(status = "content", canSend = false),
                channelLabel = "artin",
                threadShortId = ShortId,
                requiredParentKind = ConversationKind.DirectMessage
            )

        assertEquals(1, repository.parentRequests.size)
        assertEquals(MessageId(ShortId), repository.parentRequests.single().first)
        assertEquals(ChannelId(DmChannelId), repository.parentRequests.single().second)
        val payload = assertIs<DataResult.Content<RaftThreadScreenPayload>>(result).data
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.parentSurfaceKind)
        assertEquals("dm", payload.parentChannelKind)
        assertEquals(DmChannelId, payload.parentChannelId)
        assertEquals(ParentMessageId, payload.parentMessageId)
        assertEquals(ThreadChannelId, payload.sourceThreadId)
        assertEquals(ThreadChannelId, payload.streamChannelId)
        assertEquals(FocusedMessageId, payload.focusedMessageId)
        assertEquals(emptyList(), payload.messages)
    }

    @Test
    fun secondTapOnAFetchedThreadRefOpensLocallyWithoutFetchingAgain() = runBlocking {
        // Device log 1.21.0: the same `#ch:short` tapped twice fetched messages/context twice.
        // Two pages (two data sources) share the process-wide payload store, as in the app.
        val payloadStore = SharedThreadPayloadStore()
        val (firstPage, repository) = threadRefDataSource(payloadStore = payloadStore)
        val (secondPage, _) = threadRefDataSource(payloadStore = payloadStore, repository = repository)
        val currentPayload = RaftThreadScreenPayload(status = "content", canSend = false)

        listOf(firstPage, firstPage, secondPage).forEach { page ->
            val provenance = requireNotNull(page.captureReplyCacheProducerContext()).provenance
            val payload =
                assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                    page.markdownThreadRefLaunchPayload(
                        provenance = provenance,
                        currentPayload = currentPayload,
                        channelLabel = "artin",
                        threadShortId = ShortId,
                        requiredParentKind = ConversationKind.DirectMessage
                    )
                ).data
            assertEquals(ThreadChannelId, payload.streamChannelId)
            assertEquals(ParentMessageId, payload.parentMessageId)
        }

        assertEquals(1, repository.parentRequests.size, "only the first tap may fetch messages/context")

        // The hits came from the resolver's kept resolution, not another local source: the parent
        // id does not start with the short id, so no prefix scan can match it, and once that
        // resolution is cleared the same tap fetches again.
        assertTrue(!ParentMessageId.startsWith(ShortId))
        payloadStore.referenceResolver.clear()
        val provenance = requireNotNull(secondPage.captureReplyCacheProducerContext()).provenance
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(
            secondPage.markdownThreadRefLaunchPayload(
                provenance = provenance,
                currentPayload = currentPayload,
                channelLabel = "artin",
                threadShortId = ShortId,
                requiredParentKind = ConversationKind.DirectMessage
            )
        )
        assertEquals(2, repository.parentRequests.size, "without the kept resolution the tap fetches")
    }

    @Test
    fun aShortIdMatchingTwoDifferentLoadedMessagesAcrossSourcesFetchesInsteadOfGuessing() = runBlocking {
        // The page shows one parent starting with the short id; the message store holds another.
        // Each source alone has a single match, but the prefix is ambiguous overall.
        val messageStore = SharedThreadMessageStore()
        val (dataSource, repository) = threadRefDataSource(messageStore = messageStore)
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        messageStore.upsertScoped(
            scopeId = requireNotNull(provenance.messageStoreScopeId),
            channelId = ChannelId(DmChannelId),
            message =
                build.raft.app.model.Message(
                    id = MessageId("$ShortId-0000-other-parent"),
                    threadId = ThreadId(DmChannelId),
                    channelId = ChannelId(DmChannelId),
                    threadChannelId = ChannelId("other-thread-id"),
                    author = dmPeer(),
                    timestampLabel = "",
                    body = "other parent"
                )
        )
        val currentPayload =
            RaftThreadScreenPayload(
                status = "content",
                canSend = false,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                sourceThreadId = DmChannelId,
                streamChannelId = DmChannelId,
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = TopLevelMessageId,
                            authorId = "peer-artin",
                            authorName = "Artin",
                            timestampLabel = "",
                            body = "page parent",
                            threadChannelId = ThreadChannelId
                        )
                    )
            )

        val payload =
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                dataSource.markdownThreadRefLaunchPayload(
                    provenance = provenance,
                    currentPayload = currentPayload,
                    channelLabel = "artin",
                    threadShortId = ShortId,
                    requiredParentKind = ConversationKind.DirectMessage
                )
            ).data

        assertEquals(1, repository.parentRequests.size, "an ambiguous prefix must be resolved by the server")
        assertEquals(ParentMessageId, payload.parentMessageId)
    }

    @Test
    fun aKeptResolutionWhoseThreadPageFailsIsForgottenAndTheNextTapFetches() = runBlocking {
        val (dataSource, repository) = threadRefDataSource()
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val currentPayload = RaftThreadScreenPayload(status = "content", canSend = false)
        suspend fun tap() =
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                dataSource.markdownThreadRefLaunchPayload(
                    provenance = provenance,
                    currentPayload = currentPayload,
                    channelLabel = "artin",
                    threadShortId = ShortId,
                    requiredParentKind = ConversationKind.DirectMessage
                )
            ).data
        val opened = tap()
        tap()
        assertEquals(1, repository.parentRequests.size)

        // The opened thread was deleted: its page lands on Error / NoPermission and forgets it.
        assertEquals(1, dataSource.forgetReferenceTargetsOf(opened))
        tap()

        assertEquals(2, repository.parentRequests.size, "a forgotten resolution must be fetched again")
    }

    @Test
    fun aFetchedMessageWithoutAThreadIsNotKeptForThreadRefs() = runBlocking {
        val noThreadYet =
            replyThreadAccessSnapshot().copy(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId(DmChannelId),
                        channelId = ChannelId(DmChannelId),
                        kind = ConversationKind.DirectMessage,
                        name = "Artin"
                    ),
                messages = emptyList(),
                parentMessage = null,
                highlightedMessageId = MessageId(TopLevelMessageId)
            )
        val (dataSource, repository) = threadRefDataSource(parentResult = DataResult.Content(noThreadYet))
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        repeat(2) {
            dataSource.markdownThreadRefLaunchPayload(
                provenance = provenance,
                currentPayload = RaftThreadScreenPayload(status = "content", canSend = false),
                channelLabel = "artin",
                threadShortId = ShortId,
                requiredParentKind = ConversationKind.DirectMessage
            )
        }

        assertEquals(2, repository.parentRequests.size, "a thread started later must still be found")
    }

    @Test
    fun threadRefKnownFromTheMessageStoreOpensWithoutFetching() = runBlocking {
        // The parent is a message the device already loaded (another page's timeline).
        val messageStore = SharedThreadMessageStore()
        val (dataSource, repository) = threadRefDataSource(messageStore = messageStore)
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        messageStore.upsertScoped(
            scopeId = requireNotNull(provenance.messageStoreScopeId),
            channelId = ChannelId(DmChannelId),
            message =
                build.raft.app.model.Message(
                    id = MessageId(TopLevelMessageId),
                    threadId = ThreadId(DmChannelId),
                    channelId = ChannelId(DmChannelId),
                    threadChannelId = ChannelId(ThreadChannelId),
                    author = dmPeer(),
                    timestampLabel = "",
                    body = "parent"
                )
        )

        val payload =
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                dataSource.markdownThreadRefLaunchPayload(
                    provenance = provenance,
                    currentPayload = RaftThreadScreenPayload(status = "content", canSend = false),
                    channelLabel = "artin",
                    threadShortId = ShortId,
                    requiredParentKind = ConversationKind.DirectMessage,
                    actionId = "message-a:thread"
                )
            ).data

        assertEquals(0, repository.parentRequests.size, "a local hit must not call messages/context")
        assertEquals(TopLevelMessageId, payload.parentMessageId)
        assertEquals(ThreadChannelId, payload.streamChannelId)
        assertTrue(dataSource.referenceResolver.inFlight.value.isEmpty())
    }

    @Test
    fun threadRefMissIsTheResolverFetchPhaseAndAFailureClearsIt() = runBlocking {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val (dataSource, repository) =
            threadRefDataSource(
                parentResult = DataResult.NoPermission(build.raft.app.data.PermissionBlocker(code = "channel_forbidden"))
            )
        repository.releaseParent = release
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        val pending =
            async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                dataSource.markdownThreadRefLaunchPayload(
                    provenance = provenance,
                    currentPayload = RaftThreadScreenPayload(status = "content", canSend = false),
                    channelLabel = "artin",
                    threadShortId = ShortId,
                    requiredParentKind = ConversationKind.DirectMessage,
                    actionId = "message-a:thread"
                )
            }
        repository.parentStarted.await()
        assertTrue(dataSource.referenceResolver.isFetching("message-a:thread"))

        release.complete(Unit)
        val failed = assertIs<DataResult.NoPermission>(pending.await())

        assertEquals(build.raft.app.data.SharedReferenceFailure.NoAccess, failed.sharedReferenceFailure())
        assertTrue(dataSource.referenceResolver.inFlight.value.isEmpty(), "loading must end with the failed fetch")
        assertEquals(1, repository.parentRequests.size)
    }

    @Test
    fun dmMessageRefToAReplyOpensItsThreadFocusedOnTheMessage() = runBlocking {
        val (dataSource, repository) = messageRefDataSource(dmMessageContextSnapshot())
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        val result =
            dataSource.markdownMessageRefLaunchPayload(
                provenance = provenance,
                currentPayload = RaftThreadScreenPayload(status = "content", canSend = false),
                ref = RaftMarkdownMessageRef(messageId = "reply123", directMessagePeerName = "artin")
            )

        assertEquals(MessageId("reply123"), repository.parentRequests.single().first)
        assertEquals(ChannelId(DmChannelId), repository.parentRequests.single().second)
        val payload = assertIs<DataResult.Content<RaftThreadScreenPayload>>(result).data
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(ParentMessageId, payload.parentMessageId)
        assertEquals(ThreadChannelId, payload.streamChannelId)
        assertEquals(FocusedMessageId, payload.focusedMessageId)
    }

    @Test
    fun dmMessageRefToATopLevelMessageAnchorsTheDmOnItsFullId() = runBlocking {
        val (dataSource, _) = messageRefDataSource(topLevelMessageContextSnapshot())
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        val result =
            dataSource.markdownMessageRefLaunchPayload(
                provenance = provenance,
                currentPayload = RaftThreadScreenPayload(status = "content", canSend = false),
                ref = RaftMarkdownMessageRef(messageId = "1c50", directMessagePeerName = "artin")
            )

        val payload = assertIs<DataResult.Content<RaftThreadScreenPayload>>(result).data
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals(DmChannelId, payload.streamChannelId)
        assertEquals(TopLevelMessageId, payload.focusedMessageId)
    }

    @Test
    fun channelMessageRefResolvesTheChannelByNameInTheCurrentServer() = runBlocking {
        val (dataSource, repository) =
            messageRefDataSource(
                topLevelMessageContextSnapshot(conversationId = MessageRefChannelId, kind = ConversationKind.Channel),
                home = messageRefChannelHomeSnapshot()
            )
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        val result =
            dataSource.markdownMessageRefLaunchPayload(
                provenance = provenance,
                currentPayload = RaftThreadScreenPayload(status = "content", canSend = false),
                ref = RaftMarkdownMessageRef(messageId = "1c50", channelName = "Triage")
            )

        assertEquals(ChannelId(MessageRefChannelId), repository.parentRequests.single().second)
        val payload = assertIs<DataResult.Content<RaftThreadScreenPayload>>(result).data
        assertEquals(RaftThreadSurfaceKind.Channel, payload.surfaceKind)
        assertEquals(MessageRefChannelId, payload.streamChannelId)
        assertEquals(TopLevelMessageId, payload.focusedMessageId)
    }

    @Test
    fun messageRefNamingAThreadOpensTheThreadEvenForItsTopLevelParent() = runBlocking {
        val (dataSource, _) = messageRefDataSource(topLevelMessageContextSnapshot())
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        val result =
            dataSource.markdownMessageRefLaunchPayload(
                provenance = provenance,
                currentPayload = RaftThreadScreenPayload(status = "content", canSend = false),
                ref = RaftMarkdownMessageRef(messageId = "1c50c30a", threadShortId = ShortId, directMessagePeerName = "artin")
            )

        val payload = assertIs<DataResult.Content<RaftThreadScreenPayload>>(result).data
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals(DmChannelId, payload.parentChannelId)
        assertEquals(TopLevelMessageId, payload.parentMessageId)
        assertEquals(null, payload.focusedMessageId)
    }

    @Test
    fun messageRefToALoadedMessageNeedsNoNetwork() = runBlocking {
        val (dataSource, repository) = messageRefDataSource(topLevelMessageContextSnapshot())
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        val result =
            dataSource.markdownMessageRefLaunchPayload(
                provenance = provenance,
                currentPayload =
                    RaftThreadScreenPayload(
                        status = "content",
                        streamChannelId = DmChannelId,
                        messages =
                            listOf(
                                RaftThreadMessageItem(
                                    id = TopLevelMessageId,
                                    authorId = "peer-artin",
                                    authorName = "Artin",
                                    authorKind = "human",
                                    avatarLabel = "A",
                                    timestampLabel = "",
                                    body = "hello"
                                )
                            )
                    ),
                ref = RaftMarkdownMessageRef(messageId = "1c50", directMessagePeerName = "artin")
            )

        assertEquals(0, repository.parentRequests.size)
        assertEquals(TopLevelMessageId, assertIs<DataResult.Content<RaftThreadScreenPayload>>(result).data.focusedMessageId)
    }

    @Test
    fun messageRefToAnUnknownConversationIsEmptyLikeOtherRefs() = runBlocking {
        val (dataSource, repository) = messageRefDataSource(topLevelMessageContextSnapshot())
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val currentPayload = RaftThreadScreenPayload(status = "content", canSend = false)

        assertIs<DataResult.Empty>(
            dataSource.markdownMessageRefLaunchPayload(
                provenance,
                currentPayload,
                RaftMarkdownMessageRef(messageId = "1c50", channelName = "nope")
            )
        )
        assertIs<DataResult.Empty>(
            dataSource.markdownMessageRefLaunchPayload(
                provenance,
                currentPayload,
                RaftMarkdownMessageRef(messageId = "1c50", directMessagePeerName = "nope")
            )
        )
        assertEquals(0, repository.parentRequests.size)
    }

    @Test
    fun dmPeerResolverAcceptsProfileHandleAndDisplayNameButFailsClosedOnAmbiguity() {
        val peer = dmPeer()
        val uniqueHome = dmHomeSnapshot(dmName = "Direct conversation")

        listOf("artin", "Artin Zhang").forEach { alias ->
            val payload =
                buildMarkdownThreadRefLaunchPayload(
                    currentPayload = RaftThreadScreenPayload(),
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
                    currentPayload = RaftThreadScreenPayload(),
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
                currentPayload = RaftThreadScreenPayload(),
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
                currentPayload = RaftThreadScreenPayload(),
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
                currentPayload = RaftThreadScreenPayload(),
                home = dmHomeSnapshot(),
                channelLabel = "dm:@artin:$ShortId",
                threadShortId = ShortId,
                requiredParentKind = ConversationKind.DirectMessage,
                parentMessageId = ParentMessageId
            )

        assertEquals(DmChannelId, requireNotNull(payload).parentChannelId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.parentSurfaceKind)
        assertEquals("dm", payload.parentChannelKind)
    }

    @Test
    fun dmPeerClickResolvesSelfAddressedDmWhenViewingDmDirectly() {
        val artin = MemberProfile(id = MemberId("viewer-artin"), displayName = "Artin Zhang", handle = "artin", kind = MemberKind.Human)
        val friday = MemberProfile(id = MemberId("peer-friday"), displayName = "Friday", handle = "friday", kind = MemberKind.Agent)
        val dmWithFridayChannelId = "dm-friday-channel"
        val home = HomeSnapshot(
            server = dmServer(),
            currentMember = artin,
            channels = emptyList(),
            directMessages = listOf(
                ConversationSummary(
                    threadId = ThreadId(dmWithFridayChannelId),
                    channelId = ChannelId(dmWithFridayChannelId),
                    kind = ConversationKind.DirectMessage,
                    name = "Friday",
                    presenceMemberId = friday.id
                ).toHomeDirectMessageRow()
            ),
            unreadTotal = 0
        )

        val currentPayload = RaftThreadScreenPayload(
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            channelKind = "dm",
            streamChannelId = dmWithFridayChannelId,
            dmPeerName = "Friday"
        )

        val payload = buildMarkdownThreadRefLaunchPayload(
            currentPayload = currentPayload,
            home = home,
            channelLabel = "artin",
            threadShortId = "1f1def75",
            parentMessageId = ParentMessageId,
            requiredParentKind = ConversationKind.DirectMessage,
            knownMemberProfilesById = mapOf(friday.id.value to friday)
        )

        val resolved = requireNotNull(payload)
        assertEquals(dmWithFridayChannelId, resolved.parentChannelId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, resolved.parentSurfaceKind)
        assertEquals("dm", resolved.parentChannelKind)
    }

    @Test
    fun dmPeerClickResolvesSelfAddressedDmWhenViewingDmReplyThread() {
        val artin = MemberProfile(id = MemberId("viewer-artin"), displayName = "Artin Zhang", handle = "artin", kind = MemberKind.Human)
        val friday = MemberProfile(id = MemberId("peer-friday"), displayName = "Friday", handle = "friday", kind = MemberKind.Agent)
        val dmWithFridayChannelId = "dm-friday-channel"
        val home = HomeSnapshot(
            server = dmServer(),
            currentMember = artin,
            channels = emptyList(),
            directMessages = listOf(
                ConversationSummary(
                    threadId = ThreadId(dmWithFridayChannelId),
                    channelId = ChannelId(dmWithFridayChannelId),
                    kind = ConversationKind.DirectMessage,
                    name = "Friday",
                    presenceMemberId = friday.id
                ).toHomeDirectMessageRow()
            ),
            unreadTotal = 0
        )

        val currentPayload = RaftThreadScreenPayload(
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage,
            parentChannelKind = "dm",
            parentChannelId = dmWithFridayChannelId,
            sourceThreadId = "reply-thread-123"
        )

        val payload = buildMarkdownThreadRefLaunchPayload(
            currentPayload = currentPayload,
            home = home,
            channelLabel = "artin",
            threadShortId = "1f1def75",
            parentMessageId = ParentMessageId,
            requiredParentKind = ConversationKind.DirectMessage,
            knownMemberProfilesById = mapOf(friday.id.value to friday)
        )

        val resolved = requireNotNull(payload)
        assertEquals(dmWithFridayChannelId, resolved.parentChannelId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, resolved.parentSurfaceKind)
    }

    @Test
    fun dmPeerClickResolvesSelfAddressedDmInChannelViaReferencingMessageAuthor() {
        val artin = MemberProfile(id = MemberId("viewer-artin"), displayName = "Artin Zhang", handle = "artin", kind = MemberKind.Human)
        val friday = MemberProfile(id = MemberId("peer-friday"), displayName = "Friday", handle = "friday", kind = MemberKind.Agent)
        val dmWithFridayChannelId = "dm-friday-channel"
        val home = HomeSnapshot(
            server = dmServer(),
            currentMember = artin,
            channels = emptyList(),
            directMessages = listOf(
                ConversationSummary(
                    threadId = ThreadId(dmWithFridayChannelId),
                    channelId = ChannelId(dmWithFridayChannelId),
                    kind = ConversationKind.DirectMessage,
                    name = "Friday",
                    presenceMemberId = friday.id
                ).toHomeDirectMessageRow()
            ),
            unreadTotal = 0
        )

        val messageWithDmTag = RaftThreadMessageItem(
            id = "msg-tag-1",
            authorId = friday.id.value,
            authorName = "Friday",
            authorHandle = "friday",
            body = "Please review dm:@artin:1f1def75"
        )
        val currentPayload = RaftThreadScreenPayload(
            surfaceKind = RaftThreadSurfaceKind.Channel,
            streamChannelId = "general-channel",
            messages = listOf(messageWithDmTag)
        )

        val payload = buildMarkdownThreadRefLaunchPayload(
            currentPayload = currentPayload,
            home = home,
            channelLabel = "artin",
            threadShortId = "1f1def75",
            parentMessageId = ParentMessageId,
            requiredParentKind = ConversationKind.DirectMessage,
            knownMemberProfilesById = mapOf(friday.id.value to friday)
        )

        val resolved = requireNotNull(payload)
        assertEquals(dmWithFridayChannelId, resolved.parentChannelId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, resolved.parentSurfaceKind)
    }

    @Test
    fun withHomeContextProjectionFromPreservesExistingDmPeersWhenPatchIsEmpty() {
        val basePayload = RaftThreadScreenPayload(
            knownMarkdownDirectMessagePeerNames = listOf("friday", "artin"),
            knownMarkdownChannelNames = listOf("general")
        )

        val emptyPatch = RaftThreadScreenPayload(
            knownMarkdownDirectMessagePeerNames = emptyList(),
            knownMarkdownChannelNames = emptyList()
        )
        val projected1 = basePayload.withHomeContextProjectionFrom(emptyPatch)
        assertEquals(listOf("friday", "artin"), projected1.knownMarkdownDirectMessagePeerNames)
        assertEquals(listOf("general"), projected1.knownMarkdownChannelNames)

        val newPatch = RaftThreadScreenPayload(
            knownMarkdownDirectMessagePeerNames = listOf("saturday"),
            knownMarkdownChannelNames = listOf("random")
        )
        val projected2 = basePayload.withHomeContextProjectionFrom(newPatch)
        assertEquals(listOf("saturday"), projected2.knownMarkdownDirectMessagePeerNames)
        assertEquals(listOf("random"), projected2.knownMarkdownChannelNames)
    }

    @Test
    fun toKnownMarkdownDirectMessagePeerNameSetIncludesDmPeerAndMembers() {
        val friday = RaftThreadMemberItem(id = "peer-friday", displayName = "Friday", handle = "friday")
        val payload = RaftThreadScreenPayload(
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            channelKind = "dm",
            dmPeerName = "Friday",
            members = listOf(friday),
            knownMarkdownDirectMessagePeerNames = listOf("bot")
        )

        val names = payload.toKnownMarkdownDirectMessagePeerNameSet()
        assertTrue("bot" in names)
        assertTrue("friday" in names)
        assertTrue(friday.id in names)
    }

    @Test
    fun sameConversationMarkdownThreadRefTargetsRetainRouteIdentity() {
        val current =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                streamChannelId = DmChannelId,
                sourceThreadId = DmChannelId,
                dmPeerName = "Artin"
            )
        val targetSameDm =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                streamChannelId = DmChannelId,
                sourceThreadId = DmChannelId,
                dmPeerName = "Artin",
                focusedMessageId = "1c50c30a"
            )
        val otherDm =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                streamChannelId = "other-dm-channel",
                sourceThreadId = "other-dm-channel",
                dmPeerName = "Bob",
                focusedMessageId = "1c50c30a"
            )

        assertTrue(current.resolveThreadRouteIdentity().canRetainThreadPageFor(targetSameDm.resolveThreadRouteIdentity()))
        assertFalse(current.resolveThreadRouteIdentity().canRetainThreadPageFor(otherDm.resolveThreadRouteIdentity()))
    }

    @Test
    fun kindPinnedDmRefOpensTheMatchingTwinAndBareTwinOpensTheFirstDm() {
        val home = twinHomeSnapshot(withHumanDm = true)
        val profiles = twinProfiles()

        fun resolve(label: String) =
            buildMarkdownThreadRefLaunchPayload(
                currentPayload = RaftThreadScreenPayload(),
                home = home,
                channelLabel = label,
                threadShortId = ShortId,
                requiredParentKind = ConversationKind.DirectMessage,
                knownMemberProfilesById = profiles,
                parentMessageId = ParentMessageId
            )?.parentChannelId

        assertEquals(TwinAgentDmId, resolve("skyzh~agent"))
        assertEquals(TwinHumanDmId, resolve("skyzh~human"))
        assertEquals(TwinAgentDmId, resolve("SkyZH~agent"))
        // Web `findDmByPeer`: a bare twin name opens the first DM in list order.
        assertEquals(TwinAgentDmId, resolve("skyzh"))
        assertEquals(null, resolve("skyzh~bot"))
        assertEquals(null, resolve("skyzh~"))
        assertEquals(null, resolve("~agent"))
    }

    @Test
    fun bareTwinOpensTheOnlyExistingTwinDm() {
        val home = twinHomeSnapshot(withHumanDm = false)
        val profiles = twinProfiles()

        fun resolve(label: String) =
            resolveMarkdownChannelConversation(
                home = home,
                channelLabel = label,
                requiredKind = ConversationKind.DirectMessage,
                knownMemberProfilesById = profiles
            )?.channelId?.value

        assertEquals(TwinAgentDmId, resolve("skyzh"))
        assertEquals(TwinAgentDmId, resolve("skyzh~agent"))
        assertEquals(null, resolve("skyzh~human"))
        assertEquals(
            listOf("skyzh", "skyzh~agent"),
            home.knownMarkdownDirectMessagePeerNames(profiles).filter { it.startsWith("skyzh") }.sorted()
        )
    }

    @Test
    fun bareTwinFollowsHomeListOrder() {
        val home = twinHomeSnapshot(withHumanDm = true)
        val humanFirst = home.copy(directMessages = home.directMessages.sortedBy { it.channelId.value != TwinHumanDmId })

        assertEquals(
            TwinHumanDmId,
            resolveMarkdownChannelConversation(
                home = humanFirst,
                channelLabel = "skyzh",
                requiredKind = ConversationKind.DirectMessage,
                knownMemberProfilesById = twinProfiles()
            )?.channelId?.value
        )
    }

    @Test
    fun bareAndKindedDmRefsWithoutShortIdOpenTheDm() {
        val home = twinHomeSnapshot(withHumanDm = true)
        val profiles = twinProfiles()

        fun open(label: String) =
            buildMarkdownChannelLaunchPayload(
                currentPayload = RaftThreadScreenPayload(),
                home = home,
                channelLabel = label,
                requiredKind = ConversationKind.DirectMessage,
                knownMemberProfilesById = profiles
            )

        assertEquals(RaftThreadSurfaceKind.DirectMessage, open("skyzh~human")?.surfaceKind)
        assertEquals(TwinHumanDmId, open("skyzh~human")?.streamChannelId)
        assertEquals(TwinAgentDmId, open("skyzh~agent")?.streamChannelId)
        assertEquals(TwinAgentDmId, open("skyzh")?.streamChannelId)
        assertEquals(FridayDmId, open("friday")?.streamChannelId)
        assertEquals(null, open("friday~human"))
        assertEquals(null, open("nobody"))
    }

    @Test
    fun knownDmPeerNamesListBareAndKindedFormsForTwins() {
        val home = twinHomeSnapshot(withHumanDm = true)
        val known = home.knownMarkdownDirectMessagePeerNames(twinProfiles()).map { it.lowercase() }

        assertTrue("skyzh~agent" in known)
        assertTrue("skyzh~human" in known)
        assertEquals(1, known.count { it == "skyzh" })
        assertTrue("friday" in known)
        assertTrue("friday~agent" in known)
        assertFalse("friday~human" in known)
    }

    @Test
    fun wrongKindForUniquePeerDoesNotResolve() {
        val home = twinHomeSnapshot(withHumanDm = true)
        val resolved =
            resolveMarkdownChannelConversation(
                home = home,
                channelLabel = "friday~human",
                requiredKind = ConversationKind.DirectMessage,
                knownMemberProfilesById = twinProfiles()
            )
        assertEquals(null, resolved)
        assertEquals(
            FridayDmId,
            resolveMarkdownChannelConversation(
                home = home,
                channelLabel = "friday",
                requiredKind = ConversationKind.DirectMessage,
                knownMemberProfilesById = twinProfiles()
            )?.channelId?.value
        )
    }

    @Test
    fun routeLocalAliasesKeepBareTwin() {
        val payload =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                dmPeerName = "skyzh",
                dmPeerIdentity = MemberDetailIdentity(MemberId("skyzh-agent"), MemberKind.Agent),
                knownMarkdownDirectMessagePeerNames = listOf("skyzh~agent", "skyzh~human", "friday", "friday~agent")
            )

        val known = payload.toKnownMarkdownDirectMessagePeerNameSet()

        assertTrue("skyzh~agent" in known)
        assertTrue("skyzh~human" in known)
        assertTrue("skyzh" in known)
        assertTrue("friday" in known)
    }

    private fun twinProfiles(): Map<String, MemberProfile> =
        listOf(
            MemberProfile(id = MemberId("skyzh-human"), displayName = "Alex Chi", handle = "skyzh", kind = MemberKind.Human),
            MemberProfile(id = MemberId("skyzh-agent"), displayName = "skyzh", handle = "skyzh", kind = MemberKind.Agent),
            MemberProfile(id = MemberId("friday"), displayName = "Friday", handle = "friday", kind = MemberKind.Agent)
        ).associateBy { it.id.value }

    private fun twinHomeSnapshot(withHumanDm: Boolean): HomeSnapshot {
        fun dm(id: String, name: String, peerId: String, peerType: String) =
            ConversationSummary(
                threadId = ThreadId(id),
                channelId = ChannelId(id),
                kind = ConversationKind.DirectMessage,
                name = name,
                directMessagePeerType = peerType,
                presenceMemberId = MemberId(peerId)
            ).toHomeDirectMessageRow()
        return HomeSnapshot(
            server = dmServer(),
            currentMember = MemberProfile(id = MemberId("viewer"), displayName = "Viewer", handle = "viewer", kind = MemberKind.Human),
            channels = emptyList(),
            directMessages =
                listOfNotNull(
                    dm(TwinAgentDmId, "skyzh", "skyzh-agent", "agent"),
                    if (withHumanDm) dm(TwinHumanDmId, "Alex Chi", "skyzh-human", "human") else null,
                    dm(FridayDmId, "Friday", "friday", "agent")
                ),
            unreadTotal = 0
        )
    }

    private fun messageRefDataSource(
        context: build.raft.app.data.ThreadSnapshot,
        home: HomeSnapshot = dmHomeSnapshot()
    ): Pair<SharedThreadDataSource, ReplyThreadAccessConversationRepositoryRecorder> {
        val sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "viewer"))
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot()),
                parentResult = DataResult.Content(context)
            )
        return SharedThreadDataSource(
            networkApi = SharedThreadStoreTest.FakeThreadApi(),
            sessionStore = sessionStore,
            conversationRepository = repository,
            homeSnapshotProvider = { home },
            cacheEnvironmentIdProvider = { EnvironmentId },
            replyCacheIdentityAuthority = replyThreadAccessIdentityAuthority(sessionStore, EnvironmentId)
        ) to repository
    }

    private fun threadRefDataSource(
        parentResult: DataResult<build.raft.app.data.ThreadSnapshot> = DataResult.Content(dmMessageContextSnapshot()),
        repository: ReplyThreadAccessConversationRepositoryRecorder =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot()),
                parentResult = parentResult
            ),
        messageStore: SharedThreadMessageStore? = null,
        payloadStore: SharedThreadPayloadStore? = null
    ): Pair<SharedThreadDataSource, ReplyThreadAccessConversationRepositoryRecorder> {
        val sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "viewer"))
        return SharedThreadDataSource(
            networkApi = SharedThreadStoreTest.FakeThreadApi(),
            sessionStore = sessionStore,
            conversationRepository = repository,
            homeSnapshotProvider = { dmHomeSnapshot() },
            messageStore = messageStore,
            cacheEnvironmentIdProvider = { EnvironmentId },
            replyCacheIdentityAuthority = replyThreadAccessIdentityAuthority(sessionStore, EnvironmentId),
            payloadStore = payloadStore
        ) to repository
    }

    private fun messageRefChannelHomeSnapshot(): HomeSnapshot =
        dmHomeSnapshot().copy(
            channels =
                listOf(
                    ConversationSummary(
                        threadId = ThreadId(MessageRefChannelId),
                        channelId = ChannelId(MessageRefChannelId),
                        kind = ConversationKind.Channel,
                        name = "triage"
                    ).toHomeChannelRow()
                )
        )

    private fun topLevelMessageContextSnapshot(
        conversationId: String = DmChannelId,
        kind: ConversationKind = ConversationKind.DirectMessage
    ) =
        replyThreadAccessSnapshot().copy(
            conversation =
                ConversationSummary(
                    threadId = ThreadId(conversationId),
                    channelId = ChannelId(conversationId),
                    kind = kind,
                    name = "Artin"
                ),
            messages = emptyList(),
            parentMessage = null,
            highlightedMessageId = MessageId(TopLevelMessageId)
        )

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
                            parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage,
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
        const val TopLevelMessageId = "1c50c30a-d8c1-420e-8a04-7d21fee6fb1f"
        const val MessageRefChannelId = "triage-channel-id"
        const val TwinAgentDmId = "dm-skyzh-agent"
        const val TwinHumanDmId = "dm-skyzh-human"
        const val FridayDmId = "dm-friday"
    }
}
