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
        assertFalse(threadEventRequiresWriteAccess("openMarkdownDirectMessage"))
        assertTrue(threadEventAllowed(writeActionsEnabled = false, "openMarkdownDirectMessage"))
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

        val currentPayload = KuiklyThreadScreenPayload(
            surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
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
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, resolved.parentSurfaceKind)
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

        val currentPayload = KuiklyThreadScreenPayload(
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            parentSurfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
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
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, resolved.parentSurfaceKind)
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

        val messageWithDmTag = KuiklyThreadMessageItem(
            id = "msg-tag-1",
            authorId = friday.id.value,
            authorName = "Friday",
            authorHandle = "friday",
            body = "Please review dm:@artin:1f1def75"
        )
        val currentPayload = KuiklyThreadScreenPayload(
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
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
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, resolved.parentSurfaceKind)
    }

    @Test
    fun withHomeContextProjectionFromPreservesExistingDmPeersWhenPatchIsEmpty() {
        val basePayload = KuiklyThreadScreenPayload(
            knownMarkdownDirectMessagePeerNames = listOf("friday", "artin"),
            knownMarkdownChannelNames = listOf("general")
        )

        val emptyPatch = KuiklyThreadScreenPayload(
            knownMarkdownDirectMessagePeerNames = emptyList(),
            knownMarkdownChannelNames = emptyList()
        )
        val projected1 = basePayload.withHomeContextProjectionFrom(emptyPatch)
        assertEquals(listOf("friday", "artin"), projected1.knownMarkdownDirectMessagePeerNames)
        assertEquals(listOf("general"), projected1.knownMarkdownChannelNames)

        val newPatch = KuiklyThreadScreenPayload(
            knownMarkdownDirectMessagePeerNames = listOf("saturday"),
            knownMarkdownChannelNames = listOf("random")
        )
        val projected2 = basePayload.withHomeContextProjectionFrom(newPatch)
        assertEquals(listOf("saturday"), projected2.knownMarkdownDirectMessagePeerNames)
        assertEquals(listOf("random"), projected2.knownMarkdownChannelNames)
    }

    @Test
    fun toKnownMarkdownDirectMessagePeerNameSetIncludesDmPeerAndMembers() {
        val friday = KuiklyThreadMemberItem(id = "peer-friday", displayName = "Friday", handle = "friday")
        val payload = KuiklyThreadScreenPayload(
            surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
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
            KuiklyThreadScreenPayload(
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                streamChannelId = DmChannelId,
                sourceThreadId = DmChannelId,
                dmPeerName = "Artin"
            )
        val targetSameDm =
            KuiklyThreadScreenPayload(
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                streamChannelId = DmChannelId,
                sourceThreadId = DmChannelId,
                dmPeerName = "Artin",
                focusedMessageId = "1c50c30a"
            )
        val otherDm =
            KuiklyThreadScreenPayload(
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
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
                currentPayload = KuiklyThreadScreenPayload(),
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
                currentPayload = KuiklyThreadScreenPayload(),
                home = home,
                channelLabel = label,
                requiredKind = ConversationKind.DirectMessage,
                knownMemberProfilesById = profiles
            )

        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, open("skyzh~human")?.surfaceKind)
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
            KuiklyThreadScreenPayload(
                surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
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
        const val TwinAgentDmId = "dm-skyzh-agent"
        const val TwinHumanDmId = "dm-skyzh-human"
        const val FridayDmId = "dm-friday"
    }
}
