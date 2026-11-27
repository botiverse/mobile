package build.raft.app.data

import build.raft.app.model.AvatarReference
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.seedThreadAuthorProfiles
import build.raft.app.raftScopedAgentStatusResolver
import build.raft.app.membersPageMergedMembers
import build.raft.app.model.*
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.ThreadRepliesReadModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedServerMembersStoreTest {
    @Test
    fun fullSparseAndRealtimeAuthorSnapshotsConvergeOnOneServerScopedAvatar() {
        val store = SharedServerMembersStore(FakeMemberRepository())
        val serverId = ServerId("server-1")
        val uploadedAvatar =
            "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
        val fullHistory =
            RaftThreadMessageItem(
                id = "history-message",
                authorId = "agent-1",
                authorName = "Argus",
                authorKind = "agent",
                avatarWireValue = uploadedAvatar
            )
        val sparsePage = fullHistory.copy(id = "sparse-message", avatarWireValue = null)
        val realtime = fullHistory.copy(id = "realtime-message")

        store.seedThreadAuthorProfiles(serverId, listOf(fullHistory, sparsePage), source = "history")
        assertEquals(
            uploadedAvatar,
            store.state.value.memberProfile(serverId, "agent-1")?.avatar?.wireValue
        )

        store.seedThreadAuthorProfiles(serverId, listOf(realtime), source = "realtime")
        assertEquals(
            uploadedAvatar,
            store.state.value.memberProfile(serverId, "agent-1")?.avatar?.wireValue
        )
        assertNull(store.state.value.memberProfile(ServerId("server-2"), "agent-1"))
    }

    @Test
    fun concreteUploadedAvatarUpgradesAgentFallbackInMemberProfileCache() {
        val store = SharedServerMembersStore(FakeMemberRepository())
        val serverId = ServerId("server-1")
        val fallback =
            RaftThreadMessageItem(
                id = "first-message",
                authorId = "agent-1",
                authorName = "Argus",
                authorKind = "agent",
                avatarWireValue = null
            )
        val uploadedAvatar =
            "https://cdn.raft.build/avatars/agents/cafebabecafebabecafebabecafebabe.webp"

        store.seedThreadAuthorProfiles(serverId, listOf(fallback), source = "sparse")
        // A render-time pixel fallback is not member data: a sparse author snapshot must not
        // write `pixel:robot` into the SSOT where it would outrank a later/parallel avatarUrl.
        assertNull(
            store.state.value.memberProfile(serverId, "agent-1")?.avatar?.wireValue
        )

        store.seedThreadAuthorProfiles(
            serverId,
            listOf(fallback.copy(id = "full-message", avatarWireValue = uploadedAvatar)),
            source = "realtime"
        )
        assertEquals(
            uploadedAvatar,
            store.state.value.memberProfile(serverId, "agent-1")?.avatar?.wireValue
        )
    }

    @Test
    fun inlineReplySnapshotSeedsMemberStoreBeforeItsRowReadsTheAvatar() {
        val store = SharedServerMembersStore(FakeMemberRepository())
        val serverId = ServerId("server-1")
        val uploadedAvatar =
            "https://cdn.raft.build/avatars/users/0123456789abcdef0123456789abcdef.webp"
        val message =
            RaftThreadMessageItem(
                id = "parent-message",
                authorId = "agent-1",
                authorName = "Argus",
                authorKind = "agent",
                inlineReplies =
                    ThreadRepliesReadModel(
                        replies =
                            listOf(
                                ReplyPreview(
                                    messageId = "reply-message",
                                    seq = 2L,
                                    content = "reply",
                                    senderId = "user-1",
                                    senderType = "user",
                                    senderName = "artin",
                                    senderAvatarUrl = uploadedAvatar,
                                    createdAt = "2026-08-02T00:00:00Z"
                                )
                            ),
                        replyCount = 1,
                        snapshotSeq = 2L
                    )
            )

        store.seedThreadAuthorProfiles(serverId, listOf(message), source = "initial")

        assertEquals(
            uploadedAvatar,
            store.state.value.memberProfile(serverId, "user-1")?.avatar?.wireValue
        )
    }

    @Test
    fun staleOrNullMessageSnapshotsCannotDowngradeHydratedMemberAvatar() {
        val serverId = ServerId("server-1")
        val currentAvatar =
            "https://cdn.raft.build/avatars/agents/22222222222222222222222222222222.webp"
        val staleAvatar =
            "https://cdn.raft.build/avatars/agents/11111111111111111111111111111111.webp"
        val hydrated =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Argus",
                handle = "argus",
                kind = MemberKind.Agent,
                avatar = AvatarReference.fromWireValue(currentAvatar),
                serverId = serverId
            )
        val store = SharedServerMembersStore(FakeMemberRepository())
        store.seed(MemberSnapshot(server("server-1"), listOf(hydrated)))
        val staleMessage =
            RaftThreadMessageItem(
                id = "stale-message",
                authorId = "agent-1",
                authorName = "Argus",
                authorKind = "agent",
                avatarWireValue = staleAvatar
            )

        store.seedThreadAuthorProfiles(serverId, listOf(staleMessage), source = "old_page")
        store.seedThreadAuthorProfiles(
            serverId,
            listOf(staleMessage.copy(id = "sparse-message", avatarWireValue = null)),
            source = "sparse"
        )

        assertEquals(
            currentAvatar,
            store.state.value.memberProfile(serverId, "agent-1")?.avatar?.wireValue
        )
    }

    @Test
    fun profileCandidateDoesNotBecomeAStatusSource() = runBlocking {
        val serverId = ServerId("server-1")
        val store = SharedServerMembersStore(FakeMemberRepository())
        val messageAuthorCandidate =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Agent",
                handle = "agent",
                kind = MemberKind.Agent,
                presence = Presence.Active,
                agentStatus = AgentStatus.Online,
                serverId = serverId
            )

        store.seedProfileCandidates(serverId, listOf(messageAuthorCandidate))

        // The snapshot's Active/Online must not survive (a message author snapshot is not a
        // status observation), and task #253 makes the stripped result publish nothing rather
        // than a forced Presence.Offline that renders as a definite "Offline" label.
        assertNull(store.memberStatusFlow(serverId, "agent-1").first())
    }

    @Test
    fun agentDetailSeedReplacesStalePixelAvatarInSession() = runBlocking {
        val serverId = ServerId("server-1")
        val uploadedAvatar =
            "https://cdn.raft.build/avatars/agents/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.webp"
        val store = SharedServerMembersStore(FakeMemberRepository())
        // Roster first learned this agent as a pixel fallback (pre-avatar-upload cache).
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Cindy").copy(
                        kind = MemberKind.Agent,
                        avatar = AvatarReference.fromWireValue("pixel:bell"),
                        serverId = serverId
                    )
                )
            )
        )
        assertEquals(
            "pixel:bell",
            store.state.value.memberProfile(serverId, "agent-1")?.avatar?.wireValue
        )

        // Opening the member detail hydrates the canonical profile; seeding it back must
        // replace the stale pixel avatar without a process restart.
        store.seedProfiles(
            serverId,
            listOf(
                member("agent-1", "Cindy").copy(
                    kind = MemberKind.Agent,
                    avatar = AvatarReference.fromWireValue(uploadedAvatar),
                    serverId = serverId
                )
            )
        )

        assertEquals(
            uploadedAvatar,
            store.memberProfileFlow(serverId, "agent-1").first()?.avatar?.wireValue
        )
    }

    @Test
    fun authoritativePixelAvatarCannotEraseConcreteUrl() = runBlocking {
        val serverId = ServerId("server-1")
        val concreteUrl = "https://cdn.raft.build/avatars/server.webp"
        val store = SharedServerMembersStore(FakeMemberRepository())
        store.seedProfiles(
            serverId,
            listOf(member("agent-1", "Agent").copy(avatar = AvatarReference.Url(concreteUrl)))
        )
        store.seedProfiles(
            serverId,
            listOf(member("agent-1", "Agent").copy(avatar = AvatarReference.Pixel("robot")))
        )
        assertEquals(concreteUrl, store.memberProfileFlow(serverId, "agent-1").first()?.avatar?.wireValue)
    }

    @Test
    fun authoritativePixelAvatarReplacesCachedPixelAvatar() = runBlocking {
        // Edgar (artin 10-29): changed on the web from the antenna to the Desk pencil; the app
        // kept the antenna because a cached pixel value was guarded like a concrete URL.
        val serverId = ServerId("server-1")
        val store = SharedServerMembersStore(FakeMemberRepository())
        store.seedProfiles(
            serverId,
            listOf(member("agent-1", "Edgar").copy(kind = MemberKind.Agent, avatar = AvatarReference.Pixel("antenna")))
        )
        store.seedProfiles(
            serverId,
            listOf(member("agent-1", "Edgar").copy(kind = MemberKind.Agent, avatar = AvatarReference.Pixel("pencil")))
        )
        assertEquals("pixel:pencil", store.memberProfileFlow(serverId, "agent-1").first()?.avatar?.wireValue)
        assertEquals("pixel:pencil", store.memberProfileFlow(serverId, "agent-1").first()?.avatarWireValueForRenderedAvatar())
    }

    @Test
    fun statusAndProfileReadsFailClosedWithoutServerScope() = runBlocking {
        val store = SharedServerMembersStore(FakeMemberRepository())
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Working,
                        presence = Presence.Active
                    )
                )
            )
        )

        assertNull(store.state.value.memberStatus(null, "agent-1"))
        assertNull(store.state.value.memberProfile(null, "agent-1"))
        assertNull(store.memberStatusFlow(null, "agent-1").first())
        assertNull(store.memberProfileFlow(null, "agent-1").first())
    }

    @Test
    fun lateMessageAuthorCandidateCannotReplaceCanonicalStatusAndRosterCanAdvanceIt() = runBlocking {
        val serverId = ServerId("server-1")
        val store = SharedServerMembersStore(FakeMemberRepository())
        val canonical =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Agent",
                handle = "agent",
                kind = MemberKind.Agent,
                presence = Presence.Active,
                agentStatus = AgentStatus.Working,
                serverId = serverId
            )

        store.seedProfiles(serverId, listOf(canonical), server = server(serverId.value))
        store.seedThreadAuthorProfiles(
            serverId,
            listOf(
                RaftThreadMessageItem(
                    id = "late-message",
                    authorId = "agent-1",
                    authorName = "Agent",
                    authorKind = "agent"
                )
            ),
            source = "late_thread"
        )

        assertEquals(
            SharedServerMemberStatus(status = AgentStatus.Working, presence = Presence.Active, external = false),
            store.memberStatusFlow(serverId, "agent-1").first()
        )

        store.seedProfiles(
            serverId,
            listOf(canonical.copy(agentStatus = null, presence = Presence.Offline))
        )

        assertEquals(
            SharedServerMemberStatus(status = AgentStatus.Working, presence = Presence.Active, external = false),
            store.memberStatusFlow(serverId, "agent-1").first(),
            "an authoritative profile without a runtime fact must preserve the prior status pair"
        )

        store.seedProfiles(
            serverId,
            listOf(canonical.copy(agentStatus = AgentStatus.Thinking, presence = Presence.Active))
        )

        assertEquals(
            AgentStatus.Thinking,
            store.memberStatusFlow(serverId, "agent-1").first()?.status
        )
    }

    @Test
    fun candidateFirstSparseDirectoryPublicationKeepsAvatarWhileDirectoryOwnsOtherFields() = runBlocking {
        val serverId = ServerId("server-1")
        val candidateAvatar =
            "https://cdn.raft.build/avatars/agents/33333333333333333333333333333333.webp"
        val authoritativeAvatar =
            "https://cdn.raft.build/avatars/agents/44444444444444444444444444444444.webp"
        val repository = FakeMemberRepository()
        repository.membersByServer[serverId.value] =
            listOf(
                member("agent-1", "Directory Authority").copy(
                    handle = "directory-authority",
                    kind = MemberKind.Agent,
                    description = "directory description",
                    roleDescription = "directory role",
                    serverRole = "member",
                    email = "directory@example.test",
                    avatar = null
                )
            )
        val store = SharedServerMembersStore(repository)
        store.seedProfileCandidates(
            serverId = serverId,
            listOf(
                MemberProfile(
                    id = MemberId("agent-1"),
                    displayName = "Stale Candidate Name",
                    handle = "stale-candidate",
                    kind = MemberKind.Agent,
                    description = "stale description",
                    roleDescription = "stale role",
                    serverRole = "stale",
                    email = "stale@example.test",
                    avatar = AvatarReference.fromWireValue(candidateAvatar),
                    serverId = serverId
                )
            )
        )

        assertIs<DataResult.Content<Unit>>(store.refreshMembers(serverId, force = true))

        val sparsePublished = store.currentServerMembers(serverId).single()
        assertEquals("Directory Authority", sparsePublished.displayName)
        assertEquals("directory-authority", sparsePublished.handle)
        assertEquals("directory description", sparsePublished.description)
        assertEquals("directory role", sparsePublished.roleDescription)
        assertEquals("member", sparsePublished.serverRole)
        assertEquals("directory@example.test", sparsePublished.email)
        assertEquals(candidateAvatar, sparsePublished.avatar?.wireValue)
        assertEquals(candidateAvatar, store.state.value.memberProfile(serverId, "agent-1")?.avatar?.wireValue)

        repository.membersByServer[serverId.value] =
            listOf(
                sparsePublished.copy(
                    avatar = AvatarReference.fromWireValue(authoritativeAvatar)
                )
            )
        assertIs<DataResult.Content<Unit>>(store.refreshMembers(serverId, force = true))
        assertEquals(
            authoritativeAvatar,
            store.state.value.memberProfile(serverId, "agent-1")?.avatar?.wireValue
        )
    }

    @Test
    fun processFlightPublishesInFlightAndPreservesExistingRosterUntilLoaded() = runBlocking {
        val processRepository =
            FakeMemberRepository().apply {
                getMembersStarted = CompletableDeferred()
                releaseGetMembers = CompletableDeferred()
                membersByServer["server-1"] = listOf(member("u2", "New"))
            }
        val store =
            SharedServerMembersStore(
                memberRepository = FakeMemberRepository(),
                processCore = ProcessServerMembersCore(processRepository::getMembers)
            )
        store.seed(MemberSnapshot(server("server-1"), listOf(member("u1", "Old"))))

        val refresh = async { store.refreshMembers(ServerId("server-1"), force = true) }
        processRepository.getMembersStarted?.await()

        val inFlight = store.state.value.entryFor(ServerId("server-1"))
        assertEquals(SharedServerMembersLoadStatus.InFlight, inFlight?.status)
        assertEquals(listOf("u1"), inFlight?.members?.map { it.id.value })
        processRepository.releaseGetMembers?.complete(Unit)
        assertIs<DataResult.Content<Unit>>(refresh.await())
        val loaded = store.state.value.entryFor(ServerId("server-1"))
        assertEquals(SharedServerMembersLoadStatus.Loaded, loaded?.status)
        assertEquals(listOf("u2"), loaded?.members?.map { it.id.value })
    }

    @Test
    fun forcedRetryReplacesAFlightStuckSinceTheOutageInsteadOfJoiningIt() = runBlocking {
        // Task #150: a members flight started during a network outage never returns; the user's
        // Retry (a forced load) must start a fresh flight, and the stuck one must never publish.
        val owner = ProcessServerMembersOwner(environmentId = "https://api.example.test", accountId = "a", serverId = "server-1")
        val stuck = CompletableDeferred<DataResult<MemberSnapshot>>()
        val started = CompletableDeferred<Unit>()
        var fetches = 0
        val core =
            ProcessServerMembersCore(
                fetchMembers = { _, _ ->
                    fetches += 1
                    if (fetches == 1) {
                        started.complete(Unit)
                        stuck.await()
                    }
                    else DataResult.Content(MemberSnapshot(server("server-1"), listOf(member("u1", "Fresh"))))
                },
                staleForcedFlightAge = kotlin.time.Duration.ZERO
            )
        val outage = async { core.load(owner, DataRequestPriority.Background, force = false, reason = "outage") }
        started.await()

        val retried = core.load(owner, DataRequestPriority.Visible, force = true, reason = "members_retry")
        assertIs<DataResult.Content<MemberSnapshot>>(retried.result)
        assertTrue(retried.publicationCurrent)
        assertEquals(2, fetches)

        // The stuck flight finally answers: it is stale and does not replace the fresh snapshot.
        stuck.complete(DataResult.Error(ScreenError("network_dns_failed")))
        assertEquals(false, outage.await().publicationCurrent)
        val cached = core.load(owner, DataRequestPriority.Visible, force = false, reason = "after")
        assertIs<DataResult.Content<MemberSnapshot>>(cached.result)
        assertEquals(2, fetches, "served from the fresh flight's cache")
    }

    @Test
    fun oldFailureCannotOverwriteNewerSuccessfulFacadePublication() = runBlocking {
        var fetches = 0
        val core =
            ProcessServerMembersCore(fetchMembers = { _, _ ->
                fetches += 1
                if (fetches == 1) {
                    DataResult.Error(ScreenError("first_failed"))
                } else {
                    DataResult.Content(MemberSnapshot(server("server-1"), listOf(member("u2", "New"))))
                }
            })
        val firstBeforePublish = CompletableDeferred<Unit>()
        val releaseFirstPublish = CompletableDeferred<Unit>()
        var publicationCalls = 0
        val store =
            SharedServerMembersStore(
                memberRepository = FakeMemberRepository(),
                processCore = core,
                beforeProcessPublication = {
                    publicationCalls += 1
                    if (publicationCalls == 1) {
                        firstBeforePublish.complete(Unit)
                        releaseFirstPublish.await()
                    }
                }
            )

        val oldFailure = async { store.refreshMembers(ServerId("server-1"), force = true) }
        firstBeforePublish.await()
        assertIs<DataResult.Content<Unit>>(store.refreshMembers(ServerId("server-1"), force = true))
        releaseFirstPublish.complete(Unit)

        val stale = assertIs<DataResult.Error>(oldFailure.await())
        assertEquals("members_owner_stale", stale.error.code)
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.state.value.entryFor(ServerId("server-1"))?.status)
        assertEquals(listOf("u2"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
    }

    @Test
    fun existingNewerCoreVersionIsAppliedOverOlderLocalSeed() = runBlocking {
        val owner =
            ProcessServerMembersOwner(
                environmentId = "https://api.example.test",
                accountId = "account-1",
                serverId = "server-1"
            )
        val oldRepository = FakeMemberRepository()
        val processRepository =
            FakeMemberRepository().apply {
                membersByServer["server-1"] = listOf(member("u2", "New"))
            }
        val core = ProcessServerMembersCore(processRepository::getMembers)
        val newRepository = FakeMemberRepository()
        val oldStore =
            SharedServerMembersStore(
                memberRepository = oldRepository,
                processCore = core,
                processOwner = { owner },
                channelMembersOwner = { owner.channelMembersOwnerBinding() }
            )
        val newStore =
            SharedServerMembersStore(
                memberRepository = newRepository,
                processCore = core,
                processOwner = { owner },
                channelMembersOwner = { owner.channelMembersOwnerBinding() }
            )
        oldStore.seed(MemberSnapshot(server("server-1"), listOf(member("u1", "Old"))))

        assertIs<DataResult.Content<Unit>>(newStore.refreshMembers(ServerId("server-1"), force = true))
        assertIs<DataResult.Content<Unit>>(oldStore.refreshMembers(ServerId("server-1"), force = false))

        assertEquals(0, oldRepository.getMembersCalls)
        assertEquals(0, newRepository.getMembersCalls)
        assertEquals(1, processRepository.getMembersCalls)
        assertEquals(listOf("u2"), oldStore.currentServerMembers(ServerId("server-1")).map { it.id.value })
    }

    @Test
    fun invalidationBetweenCoreResultAndFacadeCommitPreventsStateWrite() = runBlocking {
        val processRepository = FakeMemberRepository()
        val core = ProcessServerMembersCore(processRepository::getMembers)
        val owner =
            ProcessServerMembersOwner(
                environmentId = "https://api.example.test",
                accountId = "account-1",
                serverId = "server-1"
            )
        val beforePublish = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()
        val store =
            SharedServerMembersStore(
                memberRepository = FakeMemberRepository(),
                processCore = core,
                processOwner = { owner },
                channelMembersOwner = { owner.channelMembersOwnerBinding() },
                beforeProcessPublication = {
                    beforePublish.complete(Unit)
                    releasePublish.await()
                }
            )

        val refresh = async { store.refreshMembers(ServerId("server-1"), force = true) }
        beforePublish.await()
        core.invalidateAll(reason = "same_account_reauthenticated")
        releasePublish.complete(Unit)

        val result = assertIs<DataResult.Error>(refresh.await())
        assertEquals("members_owner_stale", result.error.code)
        assertEquals(emptyList(), store.currentServerMembers(ServerId("server-1")))
    }

    @Test
    fun loadSupersededBySameOwnerInvalidationFollowsTheReplacementFlight() = runBlocking {
        // Hands 291310aa: the Members page load joined a roster flight right after a server
        // switch; the presence transport connected, invalidated the owner and started a
        // replacement flight. The page's load must end with the roster, not members_owner_stale.
        val owner =
            ProcessServerMembersOwner(
                environmentId = "https://api.example.test",
                accountId = "account-1",
                serverId = "server-1"
            )
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var fetches = 0
        val core =
            ProcessServerMembersCore(fetchMembers = { _, _ ->
                fetches += 1
                if (fetches == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    DataResult.Content(MemberSnapshot(server("server-1"), listOf(member("u1", "Old"))))
                } else {
                    DataResult.Content(MemberSnapshot(server("server-1"), listOf(member("u2", "New"))))
                }
            })
        val store =
            SharedServerMembersStore(
                memberRepository = FakeMemberRepository(),
                processCore = core,
                processOwner = { owner },
                channelMembersOwner = { owner.channelMembersOwnerBinding() }
            )

        val page = async { store.refreshMembers(ServerId("server-1"), reason = "members_page") }
        firstStarted.await()
        core.invalidate(owner, reason = "presence_transport_connected")
        assertIs<DataResult.Content<Unit>>(
            store.refreshMembers(
                ServerId("server-1"),
                priority = DataRequestPriority.Background,
                force = true,
                reason = "presence_transport_connected"
            )
        )
        releaseFirst.complete(Unit)

        assertIs<DataResult.Content<Unit>>(page.await())
        assertEquals(2, fetches, "the page follows the replacement flight's cache, no extra fetch")
        assertEquals(listOf("u2"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.state.value.entryFor(ServerId("server-1"))?.status)
    }

    @Test
    fun shellFacadesSharingFailedProcessFlightBothReachTerminalError() = runBlocking {
        val owner =
            ProcessServerMembersOwner(
                environmentId = "https://api.example.test",
                accountId = "account-1",
                serverId = "server-1"
            )
        val processRepository =
            FakeMemberRepository().apply {
                membersResultOverride = DataResult.Error(ScreenError(code = "offline"))
                getMembersStarted = CompletableDeferred()
                releaseGetMembers = CompletableDeferred()
            }
        val core = ProcessServerMembersCore(processRepository::getMembers)
        val firstStore =
            SharedServerMembersStore(
                memberRepository = FakeMemberRepository(),
                processCore = core,
                processOwner = { owner },
                channelMembersOwner = { owner.channelMembersOwnerBinding() }
            )
        val secondStore =
            SharedServerMembersStore(
                memberRepository = FakeMemberRepository(),
                processCore = core,
                processOwner = { owner },
                channelMembersOwner = { owner.channelMembersOwnerBinding() }
            )

        val first = async { firstStore.refreshMembers(ServerId("server-1"), force = true) }
        processRepository.getMembersStarted?.await()
        val second = async { secondStore.refreshMembers(ServerId("server-1"), force = false) }
        yield()
        processRepository.releaseGetMembers?.complete(Unit)

        assertEquals("offline", assertIs<DataResult.Error>(first.await()).error.code)
        assertEquals("offline", assertIs<DataResult.Error>(second.await()).error.code)
        assertEquals(SharedServerMembersLoadStatus.Error, firstStore.state.value.entryFor(ServerId("server-1"))?.status)
        assertEquals(SharedServerMembersLoadStatus.Error, secondStore.state.value.entryFor(ServerId("server-1"))?.status)
    }

    @Test
    fun cancellingFirstWaiterDoesNotCancelSharedMembersFlight() = runBlocking {
        val repository =
            FakeMemberRepository().apply {
                getMembersStarted = CompletableDeferred()
                releaseGetMembers = CompletableDeferred()
            }
        val store = SharedServerMembersStore(repository)

        val first = launch { store.refreshMembers(ServerId("server-1"), force = true) }
        repository.getMembersStarted?.await()
        val second = async { store.refreshMembers(ServerId("server-1"), force = true) }
        yield()
        first.cancelAndJoin()

        assertTrue(second.isActive)
        repository.releaseGetMembers?.complete(Unit)
        assertIs<DataResult.Content<Unit>>(second.await())
        assertEquals(1, repository.getMembersCalls)
    }

    @Test
    fun forceReloadBypassesLoadedCache() = runBlocking {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)

        store.refreshMembers(ServerId("server-1"))
        repository.membersByServer["server-1"] = listOf(member("u2", "Mina"))
        val result = store.refreshMembers(ServerId("server-1"), force = true)

        assertIs<DataResult.Content<Unit>>(result)
        assertEquals(2, repository.getMembersCalls)
        assertEquals(listOf("u2"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
    }

    @Test
    fun seedProfilesCachesHomeDirectMessageAvatarsWithoutMarkingRosterLoaded() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)

        store.seedProfiles(
            serverId = ServerId("server-1"),
            server = server("server-1"),
            profiles =
                listOf(
                    member("agent-1", "Build Agent").copy(
                        kind = MemberKind.Agent,
                        avatar = AvatarReference.fromWireValue("https://cdn.slock.ai/agent.png"),
                    )
                )
        )

        val entry = store.state.value.entryFor(ServerId("server-1"))
        assertEquals(SharedServerMembersLoadStatus.Idle, entry?.status)
        assertEquals(emptyList(), entry?.members)
        val member = store.state.value.memberProfile(ServerId("server-1"), "agent-1")
        assertEquals("agent-1", member?.id?.value)
        assertEquals("https://cdn.slock.ai/agent.png", member?.avatar?.wireValue)
        assertEquals(0, repository.getMembersCalls)
    }

    @Test
    fun directMessageProfileSeedDoesNotExpandCanonicalServerDirectory() {
        val store = SharedServerMembersStore(FakeMemberRepository())
        val agent = member("agent-1", "Build Agent").copy(kind = MemberKind.Agent)
        store.seed(MemberSnapshot(server("server-1"), listOf(member("self", "Self"), agent)))

        store.seedProfiles(
            serverId = ServerId("server-1"),
            profiles = listOf(member("dm-peer", "DM Peer")),
        )

        assertEquals(
            listOf("self", "agent-1"),
            store.currentServerMembers(ServerId("server-1")).map { it.id.value },
        )
        assertEquals("DM Peer", store.state.value.memberProfile(ServerId("server-1"), "dm-peer")?.displayName)
    }

    @Test
    fun hiddenHumanDirectoryReadEdgesExposeAgentsOnlyForMemberRole() = runBlocking {
        val hiddenServer =
            server("server-1").copy(
                role = "member",
                hideHumansFromMembers = true,
            )
        val agent = member("agent-1", "Build Agent").copy(kind = MemberKind.Agent)
        val store = SharedServerMembersStore(FakeMemberRepository())
        store.seed(
            MemberSnapshot(
                hiddenServer,
                listOf(member("self", "Self"), member("other", "Other"), agent),
            ),
        )

        assertEquals(listOf("agent-1"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
        assertEquals(
            listOf("agent-1"),
            store.serverMembersEntryFlow(ServerId("server-1")).first().members.map { it.id.value },
        )
        assertEquals(1, store.currentServerMemberCount(ServerId("server-1")))
        assertEquals(setOf("agent-1"), store.currentServerMemberProfilesById(ServerId("server-1")).keys)
        assertEquals(
            setOf("self", "other", "agent-1"),
            store.currentKnownMemberProfilesById(ServerId("server-1")).keys,
        )
        assertEquals("Self", store.state.value.memberProfile(ServerId("server-1"), "self")?.displayName)
    }

    @Test
    fun hiddenHumanDirectorySettingDoesNotHideHumansFromAdmins() {
        val adminServer =
            server("server-1").copy(
                role = "admin",
                hideHumansFromMembers = true,
            )
        val store = SharedServerMembersStore(FakeMemberRepository())
        store.seed(MemberSnapshot(adminServer, listOf(member("admin", "Admin"), member("other", "Other"))))

        assertEquals(
            listOf("admin", "other"),
            store.currentServerMembers(ServerId("server-1")).map { it.id.value },
        )
    }

    @Test
    fun serverRosterSeedsExcludeExplicitRemoteJointParticipants() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        val localServer = server("server-1")
        val local = member("local", "Local").copy(serverId = ServerId("server-1"))
        val legacy = member("legacy", "Legacy")
        val remote = member("remote", "Remote").copy(serverId = ServerId("server-remote"))

        store.seed(MemberSnapshot(localServer, listOf(local, legacy, remote)))
        store.seedProfiles(ServerId("server-1"), listOf(remote.copy(id = MemberId("remote-seed"))))

        assertEquals(
            listOf("local", "legacy"),
            store.currentServerMembers(ServerId("server-1")).map { it.id.value }
        )
    }

    @Test
    fun applyPresenceUpdatesStatusResolverWithoutRewritingCachedRoster() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("u1", "Cindy"),
                    member("agent-1", "Agent").copy(kind = MemberKind.Agent, agentStatus = AgentStatus.Online)
                )
            )
        )

        store.applyPresence(
            ServerId("server-1"),
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = AgentStatus.Thinking.presence,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Thinking
            )
        )

        // task #136: raw state keeps wire truth (zero mirrors) …
        val members = store.state.value.entryFor(ServerId("server-1"))?.members.orEmpty()
        assertEquals(AgentStatus.Online, members.single { it.id.value == "agent-1" }.agentStatus)
        assertEquals(Presence.Offline, members.single { it.id.value == "agent-1" }.presence)
        // … while the registry-backed read edge shows the live fact.
        assertEquals(
            SharedServerMemberStatus(status = AgentStatus.Thinking, presence = AgentStatus.Thinking.presence, external = false),
            runBlocking { store.memberStatusFlow(ServerId("server-1"), "agent-1").first() }
        )
        assertEquals(MemberKind.Human, members.single { it.id.value == "u1" }.kind)
    }

    @Test
    fun workingAgentsAreThisServersManagedAgentsAtWork() = runBlocking {
        val store = SharedServerMembersStore(FakeMemberRepository())
        fun agent(id: String, name: String) = member(id, name).copy(kind = MemberKind.Agent)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("u1", "Cindy"),
                    agent("niko", "Niko"),
                    agent("kirby", "Kirby"),
                    agent("idle", "Idle"),
                    agent("ext", "External").copy(isExternal = true),
                    agent("bot", "Bot").copy(managedByAppInstallId = "install-1"),
                )
            )
        )
        fun fact(id: String, status: AgentStatus) =
            MemberPresenceUpdated(memberId = MemberId(id), presence = status.presence, kind = MemberKind.Agent, agentStatus = status, serverSeq = 1L)
        store.applyPresence(ServerId("server-1"), fact("niko", AgentStatus.Working))
        // agent:activity_state: a coarse frame.
        store.applyPresence(ServerId("server-1"), fact("kirby", AgentStatus.Thinking).copy(coarseState = true))
        store.applyPresence(ServerId("server-1"), fact("idle", AgentStatus.Online))
        store.applyPresence(ServerId("server-1"), fact("ext", AgentStatus.Working))
        store.applyPresence(ServerId("server-1"), fact("bot", AgentStatus.Working))
        // An agent the app has no profile for is left out, as on the web.
        store.applyPresence(ServerId("server-1"), fact("stranger", AgentStatus.Working))

        val working = store.workingAgentsFlow(ServerId("server-1")).first()
        assertEquals(
            listOf("niko" to AgentStatus.Working, "kirby" to AgentStatus.Thinking),
            working.map { it.agentId to it.status }
        )
        assertEquals("Niko", working.first().name)
        // The frame behind each fact, for the 90 s safety net.
        assertEquals(
            listOf(RaftLiveAgentSignal(serverSeq = 1L, coarse = false), RaftLiveAgentSignal(serverSeq = 1L, coarse = true)),
            working.map { it.signal }
        )
        assertEquals(emptyList(), store.workingAgentsFlow(ServerId("server-2")).first())
    }

    @Test
    fun workingAgentsFlowExcludesOtherServerAgentsFromJointChannelRoster() = runBlocking {
        val repository = FakeMemberRepository()
        fun agent(id: String, name: String) =
            member(id, name).copy(kind = MemberKind.Agent, presence = Presence.Active)
        // Joint-channel roster carries remote agents with inViewerServer = false; serverId may be
        // omitted on the wire (Hands 86e0bc50 / cs26). Both shapes must stay out of Working now.
        val jointRemoteNoServerId =
            agent("frank", "Frank").copy(serverId = null, inViewerServer = false)
        val jointRemoteWithServerId =
            agent("imprint", "Imprint").copy(serverId = ServerId("botiverse"), inViewerServer = false)
        val jointLocalAgent =
            agent("vix", "Vix").copy(serverId = ServerId("server-1"), inViewerServer = true)
        repository.channelMembersByChannel["joint-botiverse"] =
            listOf(jointLocalAgent, jointRemoteNoServerId, jointRemoteWithServerId)

        val store = SharedServerMembersStore(repository)
        store.seed(MemberSnapshot(server("server-1"), listOf(jointLocalAgent)))
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(
            store.loadChannelMembers(ServerId("server-1"), ChannelId("joint-botiverse"))
        )

        fun fact(id: String, status: AgentStatus) =
            MemberPresenceUpdated(
                memberId = MemberId(id),
                presence = status.presence,
                kind = MemberKind.Agent,
                agentStatus = status,
                coarseState = true,
                serverSeq = 1L,
            )
        store.applyPresence(ServerId("server-1"), fact("vix", AgentStatus.Working))
        store.applyPresence(ServerId("server-1"), fact("frank", AgentStatus.Thinking))
        store.applyPresence(ServerId("server-1"), fact("imprint", AgentStatus.Working))

        val working = store.workingAgentsFlow(ServerId("server-1")).first()
        assertEquals(listOf("vix" to AgentStatus.Working), working.map { it.agentId to it.status })
    }

    @Test
    fun workerBusyAgentsFlowIncludesLocalOnlineRunningWorkersAndExcludesHoldsPeersAndOwnWork() = runTest {
        val repository = FakeMemberRepository()
        fun agent(id: String, name: String) =
            member(id, name).copy(kind = MemberKind.Agent, presence = Presence.Active, serverId = ServerId("server-1"))
        val peer = agent("peer", "Peer").copy(serverId = ServerId("server-2"), inViewerServer = false)
        repository.channelMembersByChannel["joint-1"] = listOf(agent("workers", "Kirby"), peer)
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    agent("workers", "Kirby"),
                    agent("self", "Niko"),
                    agent("held", "Sage"),
                    agent("paused", "Pausey").copy(assistantPausedAt = "2026-11-25T10:00:00Z"),
                    agent("blocked", "OldComp").copy(agentBlockedReason = "computer_access_revoked"),
                    agent("readiness", "HeldReady").copy(
                        agentReadiness = AgentReadiness(state = "blocked", primary = AgentReadinessFact(code = "computer_offline"))
                    ),
                    agent("ext", "External").copy(isExternal = true),
                    agent("bot", "Bot").copy(managedByAppInstallId = "install-1"),
                    agent("idle", "Idle"),
                )
            )
        )
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(
            store.loadChannelMembers(ServerId("server-1"), ChannelId("joint-1"))
        )
        fun onlineFact(id: String, workers: Int?, status: AgentStatus = AgentStatus.Online, hold: AgentDeliveryHold? = null) =
            MemberPresenceUpdated(
                memberId = MemberId(id),
                presence = status.presence,
                kind = MemberKind.Agent,
                agentStatus = status,
                deliveryHold = hold,
                runningWorkers = workers,
                serverSeq = 1L,
            )
        val activeHold =
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.CredentialInvalid,
                credentialDetail = AgentCredentialHoldReason.UserReauth,
                since = 1_000L,
            )
        store.applyPresence(ServerId("server-1"), onlineFact("workers", 2))
        store.applyPresence(ServerId("server-1"), onlineFact("self", 1, status = AgentStatus.Working))
        store.applyPresence(ServerId("server-1"), onlineFact("held", 2, hold = activeHold))
        store.applyPresence(ServerId("server-1"), onlineFact("paused", 2))
        store.applyPresence(ServerId("server-1"), onlineFact("blocked", 2))
        store.applyPresence(ServerId("server-1"), onlineFact("readiness", 2))
        store.applyPresence(ServerId("server-1"), onlineFact("peer", 3))
        store.applyPresence(ServerId("server-1"), onlineFact("ext", 2))
        store.applyPresence(ServerId("server-1"), onlineFact("bot", 2))
        store.applyPresence(ServerId("server-1"), onlineFact("idle", null))

        // workingAgentsFlow keeps ONLY own thinking/working (channel "working here" never counts Workers).
        val ownWorking = store.workingAgentsFlow(ServerId("server-1")).first()
        assertEquals(listOf("self"), ownWorking.map { it.agentId })

        // workerBusyAgentsFlow includes ONLY local online agents busy through Workers with no overriding hold/block/pause.
        val workerBusy = store.workerBusyAgentsFlow(ServerId("server-1")).first()
        assertEquals(listOf("workers" to 2), workerBusy.map { it.agentId to it.runningWorkers })

        // Peer agent on another server resolves runningWorkers as presence-only (clamped to 1, never a count).
        val peerStatus = store.memberStatusFlow(ServerId("server-1"), "peer").first()
        assertEquals(1, peerStatus?.runningWorkers)
        assertEquals(true, peerStatus?.runningWorkersPresenceOnly)

        val localStatus = store.memberStatusFlow(ServerId("server-1"), "workers").first()
        assertEquals(2, localStatus?.runningWorkers)
        assertEquals(false, localStatus?.runningWorkersPresenceOnly)

        // Joint-channel peer identified ONLY by its `servers` list (inViewerServer == null, serverId == null)
        // must be treated as other-server consistently across workingAgentsFlow, workerBusyAgentsFlow, and memberStatus.
        val serversOnlyPeer =
            member("peer-servers-only", "ServersOnlyPeer").copy(
                kind = MemberKind.Agent,
                presence = Presence.Active,
                serverId = null,
                inViewerServer = null,
                servers = listOf(MemberServerRef(id = "server-2", name = "Other")),
                agentRunningWorkers = 4,
            )
        val serversOnlyLocal =
            member("local-servers-only", "ServersOnlyLocal").copy(
                kind = MemberKind.Agent,
                presence = Presence.Active,
                serverId = null,
                inViewerServer = null,
                servers = listOf(MemberServerRef(id = "server-1", name = "Local")),
                agentRunningWorkers = 3,
            )
        repository.channelMembersByChannel["joint-servers-list"] = listOf(serversOnlyPeer, serversOnlyLocal)
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(
            store.loadChannelMembers(ServerId("server-1"), ChannelId("joint-servers-list"))
        )
        store.applyPresence(ServerId("server-1"), onlineFact("peer-servers-only", 4))
        store.applyPresence(ServerId("server-1"), onlineFact("local-servers-only", 3))

        val workerBusyAfterServersList = store.workerBusyAgentsFlow(ServerId("server-1")).first()
        assertEquals(
            setOf("workers" to 2, "local-servers-only" to 3),
            workerBusyAfterServersList.map { it.agentId to it.runningWorkers }.toSet(),
        )
        val serversOnlyPeerStatus = store.memberStatusFlow(ServerId("server-1"), "peer-servers-only").first()
        assertEquals(1, serversOnlyPeerStatus?.runningWorkers)
        assertEquals(true, serversOnlyPeerStatus?.runningWorkersPresenceOnly)
        val serversOnlyLocalStatus = store.memberStatusFlow(ServerId("server-1"), "local-servers-only").first()
        assertEquals(3, serversOnlyLocalStatus?.runningWorkers)
        assertEquals(false, serversOnlyLocalStatus?.runningWorkersPresenceOnly)

        store.applyPresence(ServerId("server-1"), onlineFact("peer-servers-only", null, status = AgentStatus.Working))
        store.applyPresence(ServerId("server-1"), onlineFact("local-servers-only", null, status = AgentStatus.Working))
        val workingAfterServersList = store.workingAgentsFlow(ServerId("server-1")).first()
        assertEquals(setOf("self", "local-servers-only"), workingAfterServersList.map { it.agentId }.toSet())

        // Durable roster stripping never persists volatile runningWorkers or serviceTier.
        val durable = agent("workers", "Kirby").copy(agentRunningWorkers = 3, agentServiceTier = "default").forDurableRoster()
        assertNull(durable.agentRunningWorkers)
        assertNull(durable.agentServiceTier)

        // Live serviceTier propagates through presence -> memberStatusFlow and clears when absent.
        store.applyPresence(
            ServerId("server-1"),
            MemberPresenceUpdated(
                memberId = MemberId("workers"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Online,
                serviceTier = "default",
                serverSeq = 2L,
            ),
        )
        assertEquals("default", store.memberStatusFlow(ServerId("server-1"), "workers").first()?.serviceTier)
        store.applyPresence(
            ServerId("server-1"),
            MemberPresenceUpdated(
                memberId = MemberId("workers"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Online,
                serviceTier = null,
                serverSeq = 3L,
                coarseState = true,
            ),
        )
        assertNull(store.memberStatusFlow(ServerId("server-1"), "workers").first()?.serviceTier)
    }

    @Test
    fun applyPresenceStoresOfflineFactWithoutAgentStatus() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Online,
                        presence = Presence.Active
                    )
                )
            )
        )

        store.applyPresence(
            ServerId("server-1"),
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Offline,
                kind = MemberKind.Agent,
                agentStatus = null
            )
        )

        assertEquals(
            SharedServerMemberStatus(status = AgentStatus.Online, presence = Presence.Offline, external = false),
            runBlocking { store.memberStatusFlow(ServerId("server-1"), "agent-1").first() }
        )
    }

    @Test
    fun applyPresenceRetainsFactBeforeRosterLoads() = runBlocking {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)

        store.applyPresence(
            ServerId("server-1"),
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = AgentStatus.Working.presence,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working
            )
        )

        repository.membersByServer["server-1"] =
            listOf(member("agent-1", "Agent").copy(kind = MemberKind.Agent, agentStatus = AgentStatus.Online))

        val result = store.refreshMembers(ServerId("server-1"))

        assertIs<DataResult.Content<Unit>>(result)
        val agent = store.currentServerMembers(ServerId("server-1")).single { it.id.value == "agent-1" }
        assertEquals(AgentStatus.Working, agent.agentStatus)
        assertEquals(AgentStatus.Working.presence, agent.presence)
    }

    @Test
    fun applyAgentActivitySnapshotBecomesCanonicalStatusFact() {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Offline,
                        presence = Presence.Offline
                    )
                )
            )
        )

        store.applyAgentActivitySnapshotForTest(
            ServerId("server-1"),
            MemberId("agent-1"),
            listOf(
                AgentActivityEntry(
                    id = "activity-1",
                    type = "status",
                    title = "Working",
                    statusActivity = "working",
                    statusDetail = "Building"
                )
            )
        )

        assertEquals(
            SharedServerMemberStatus(
                status = AgentStatus.Working,
                presence = Presence.Active,
                statusLabel = "Building",
                external = false
            ),
            runBlocking { store.memberStatusFlow(ServerId("server-1"), "agent-1").first() }
        )
        assertEquals(AgentStatus.Offline, store.state.value.entryFor(ServerId("server-1"))?.members?.single()?.agentStatus)
    }

    @Test
    fun applyAgentActivitySnapshotDoesNotOverwriteSequencedRealtimeFact() {
        val repository = FakeMemberRepository()
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(repository, presenceRegistry = registry)

        store.applyPresence(
            ServerId("server-1"),
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working,
                statusLabel = "Running command...",
                serverSeq = 10L
            )
        )
        store.applyAgentActivitySnapshotForTest(
            ServerId("server-1"),
            MemberId("agent-1"),
            listOf(
                AgentActivityEntry(
                    id = "activity-1",
                    type = "status",
                    title = "Offline",
                    statusActivity = "offline",
                    statusDetail = "Stopped"
                )
            )
        )

        val fact = registry.factFor(ProcessServerMembersOwner(environmentId = "local", accountId = "local", serverId = "server-1"), "agent-1")
        assertEquals(AgentStatus.Working, fact?.agentStatus)
        assertEquals("Running command...", fact?.statusLabel)
        assertEquals(10L, fact?.serverSeq)
    }

    @Test
    fun staleAgentActivitySnapshotDoesNotDisplaceFresherSnapshot() {
        // task #137(b): two racing same-account REST snapshots — the
        // late-arriving STALE one (older server trajectory timestamp) must
        // not roll back the fresher projection.
        val repository = FakeMemberRepository()
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(repository, presenceRegistry = registry)
        val owner = ProcessServerMembersOwner(environmentId = "local", accountId = "local", serverId = "server-1")
        fun statusEntry(id: String, activity: String, detail: String, timestamp: String?) =
            AgentActivityEntry(
                id = id,
                type = "status",
                title = activity,
                statusActivity = activity,
                statusDetail = detail,
                timestamp = timestamp
            )

        store.applyAgentActivitySnapshotForTest(
            ServerId("server-1"),
            MemberId("agent-1"),
            listOf(statusEntry("activity-new", "working", "Building", "200"))
        )
        store.applyAgentActivitySnapshotForTest(
            ServerId("server-1"),
            MemberId("agent-1"),
            listOf(statusEntry("activity-old", "offline", "Stopped", "100"))
        )

        assertEquals(AgentStatus.Working, registry.factFor(owner, "agent-1")?.agentStatus)
    }

    @Test
    fun mountedStatusFlowBindsAfterLaterLoginAndRebindsAcrossAccounts() = runBlocking {
        // Review blockers ①/②/③: a flow mounted BEFORE auth must bind once
        // login happens; after switching A→B the SAME mounted flow must serve
        // B's partition and never A's again; clearing rebinds without
        // recreating the flow. Stage-synchronized (await each expected
        // emission) so StateFlow conflation cannot skip a stage.
        val repository = FakeMemberRepository()
        val registry = ProcessMemberPresenceRegistry()
        val ownerFlow = kotlinx.coroutines.flow.MutableStateFlow<ProcessServerMembersOwner?>(null)
        val store =
            SharedServerMembersStore(
                repository,
                presenceRegistry = registry,
                presenceOwner = { ownerFlow.value },
                presenceOwnerFlow = { ownerFlow }
            )
        val ownerA = ProcessServerMembersOwner("env-1", "acct-A", "server-1")
        val ownerB = ProcessServerMembersOwner("env-1", "acct-B", "server-1")

        val emissions = mutableListOf<AgentStatus?>()
        val collectJob = launch {
            store.memberStatusFlow(ServerId("server-1"), "agent-1")
                .collect { emissions += it?.status }
        }
        suspend fun awaitEmissions(count: Int) {
            var spins = 0
            while (emissions.size < count && spins < 10_000) {
                yield()
                spins += 1
            }
            assertEquals(count, emissions.size)
        }
        fun agentFact(status: AgentStatus, presence: Presence) =
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = presence,
                kind = MemberKind.Agent,
                agentStatus = status
            )

        awaitEmissions(1) // ① mounted pre-auth: initial null

        ownerFlow.value = ownerA
        registry.apply(ownerA, agentFact(AgentStatus.Online, Presence.Active), registry.writeGeneration)
        awaitEmissions(2) // ① same mounted flow received A's fact after login

        // ② relogin as B (partitions cleared as production does): same flow
        // rebinds — first the cleared null, then B's fact.
        registry.clearAll(reason = "authenticated")
        awaitEmissions(3)
        ownerFlow.value = ownerB
        registry.apply(ownerB, agentFact(AgentStatus.Working, Presence.Active), registry.writeGeneration)
        awaitEmissions(4)

        assertEquals(listOf(null, AgentStatus.Online, null, AgentStatus.Working), emissions)

        // ②(never back to A) + ③: a late write to A's partition is invisible
        // to the mounted-B flow.
        registry.apply(ownerA, agentFact(AgentStatus.Offline, Presence.Offline), registry.writeGeneration)
        repeat(50) { yield() }
        assertEquals(listOf(null, AgentStatus.Online, null, AgentStatus.Working), emissions)
        assertEquals(
            AgentStatus.Working,
            store.memberStatusFlow(ServerId("server-1"), "agent-1").first()?.status
        )
        collectJob.cancelAndJoin()
    }

    @Test
    fun stalePublicationBarrierCannotLoseAPresenceFact() = runBlocking {
        // task #136 gate (Cindy): under the mirror design, a roster publication
        // captured before a presence write could clobber the copied slice and
        // the registry would never re-emit — permanent loss. With the registry
        // as the ONLY storage and read-edge combining, the scenario is
        // structurally impossible: publish rosters before AND after the fact,
        // the read edge always shows the registry's latest, with no second
        // registry emission required.
        val repository = FakeMemberRepository()
        val registry = ProcessMemberPresenceRegistry()
        val storeA = SharedServerMembersStore(repository, presenceRegistry = registry)
        val storeB = SharedServerMembersStore(repository, presenceRegistry = registry)
        storeA.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(member("agent-1", "Agent").copy(kind = MemberKind.Agent, agentStatus = AgentStatus.Online))
            )
        )

        // Another scope's path writes the fact (registry = shared truth).
        storeB.applyPresence(
            ServerId("server-1"),
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Offline,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Offline,
                serverSeq = 5L
            )
        )
        val factsBefore = registry.factsByOwner

        // A stale roster publication lands on storeA AFTER the fact.
        storeA.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(member("agent-1", "Agent").copy(kind = MemberKind.Agent, agentStatus = AgentStatus.Online))
            )
        )

        // Both stores' read edges still expose the latest fact…
        assertEquals(
            AgentStatus.Offline,
            storeA.memberStatusFlow(ServerId("server-1"), "agent-1").first()?.status
        )
        assertEquals(
            AgentStatus.Offline,
            storeA.currentServerMembers(ServerId("server-1")).single().agentStatus
        )
        // …and the registry did not need a second emission to survive it.
        assertEquals(factsBefore, registry.factsByOwner)
    }

    @Test
    fun standalonePageSliceUsesCachedRosterOnlyForExactOwnerAndChannel() = runBlocking {
        val repository = FakeMemberRepository()
        repository.channelMembersByChannel["channel-1"] = listOf(member("user-1", "Cached"))
        val processOwner = ProcessServerMembersOwner("https://api.raft.build/api", "account-a", "server-1")
        val store =
            SharedServerMembersStore(
                repository,
                processOwner = { processOwner },
                channelMembersOwner = { processOwner.channelMembersOwnerBinding() }
            )
        store.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))

        val matchingOwner =
            AuthenticatedRouteOwner("https://api.raft.build/api", "account-a", "server-1")
        assertEquals(
            listOf("user-1"),
            store.currentChannelMemberSlice(matchingOwner, ChannelId("channel-1"))
                .members
                .map { it.id.value },
        )
        assertEquals(
            emptyList(),
            store.currentChannelMemberSlice(
                matchingOwner.copy(environmentId = "https://staging.raft.build/api"),
                ChannelId("channel-1"),
            ).members
        )
        assertEquals(
            emptyList(),
            store.currentChannelMemberSlice(
                matchingOwner.copy(accountId = "account-b"),
                ChannelId("channel-1"),
            ).members
        )
        assertEquals(
            emptyList(),
            store.currentChannelMemberSlice(
                matchingOwner.copy(serverId = "server-2"),
                ChannelId("channel-1"),
            ).members
        )
        assertEquals(
            emptyList(),
            store.currentChannelMemberSlice(matchingOwner, ChannelId("channel-2")).members
        )
        assertEquals(
            listOf("user-1"),
            (store.currentChannelMembersHandoff(matchingOwner, ChannelId("channel-1"))
                as SharedServerChannelMembersHandoff.Accepted)
                .slice.members.map { it.id.value },
        )
        assertEquals(
            listOf("user-1"),
            store.channelMemberSliceFlow(matchingOwner, ChannelId("channel-1"))
                .first()
                .members
                .map { it.id.value }
        )
    }

    @Test
    fun standalonePageAcceptedSliceRejectsSameOwnerAfterGenerationChanges() = runBlocking {
        val repository = FakeMemberRepository()
        repository.channelMembersByChannel["channel-1"] = listOf(member("user-1", "Cached"))
        val processOwner = ProcessServerMembersOwner("https://api.raft.build/api", "account-a", "server-1")
        var writeGeneration = 7L
        val store =
            SharedServerMembersStore(
                repository,
                processOwner = { processOwner },
                channelMembersOwner = {
                    processOwner.channelMembersOwnerBinding(writeGeneration)
                },
            )
        val routeOwner =
            AuthenticatedRouteOwner(processOwner.environmentId, processOwner.accountId, processOwner.serverId)
        store.loadChannelMembers(routeOwner, ChannelId("channel-1"))

        assertEquals(
            listOf("user-1"),
            (store.currentChannelMembersHandoff(routeOwner, ChannelId("channel-1"))
                as SharedServerChannelMembersHandoff.Accepted)
                .slice.members.map { it.id.value },
        )

        writeGeneration += 1
        assertEquals(
            SharedServerChannelMembersHandoff.Rejected,
            store.currentChannelMembersHandoff(routeOwner, ChannelId("channel-1")),
        )
    }

    @Test
    fun standalonePageHandoffUsesCurrentBindingWhenChannelSliceIsMissing() {
        val processOwner = ProcessServerMembersOwner("https://api.raft.build/api", "account-a", "server-1")
        val store =
            SharedServerMembersStore(
                FakeMemberRepository(),
                processOwner = { processOwner },
                channelMembersOwner = { processOwner.channelMembersOwnerBinding(writeGeneration = 11L) },
            )
        val routeOwner =
            AuthenticatedRouteOwner(processOwner.environmentId, processOwner.accountId, processOwner.serverId)

        val handoff = assertIs<SharedServerChannelMembersHandoff.Accepted>(
            store.currentChannelMembersHandoff(routeOwner, ChannelId("channel-1"))
        )
        assertEquals("account-a", handoff.slice.accountId)
        assertEquals(11L, handoff.slice.ownerWriteGeneration)
        assertEquals(SharedServerMembersLoadStatus.InFlight, handoff.slice.status)
    }

    @Test
    fun standalonePageSliceKeepsCachedRosterDuringRefreshAndAfterFailure() = runBlocking {
        val repository = FakeMemberRepository()
        repository.channelMembersByChannel["channel-1"] = listOf(member("user-1", "Cached"))
        val processOwner = ProcessServerMembersOwner("https://api.raft.build/api", "account-a", "server-1")
        val routeOwner = AuthenticatedRouteOwner("https://api.raft.build/api", "account-a", "server-1")
        val store =
            SharedServerMembersStore(
                repository,
                processOwner = { processOwner },
                channelMembersOwner = { processOwner.channelMembersOwnerBinding() }
            )
        store.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))
        repository.getChannelMembersStarted = CompletableDeferred()
        repository.releaseGetChannelMembers = CompletableDeferred()
        repository.channelMembersResultOverride = DataResult.Error(ScreenError(code = "offline"))

        val refresh =
            async {
                store.loadChannelMembers(
                    owner = routeOwner,
                    channelId = ChannelId("channel-1"),
                    force = true
                )
            }
        repository.getChannelMembersStarted?.await()
        assertEquals(
            SharedServerMembersLoadStatus.InFlight,
            store.currentChannelMemberSlice(routeOwner, ChannelId("channel-1")).status
        )
        assertEquals(
            listOf("user-1"),
            store.currentChannelMemberSlice(routeOwner, ChannelId("channel-1"))
                .members
                .map { it.id.value }
        )

        repository.releaseGetChannelMembers?.complete(Unit)
        assertEquals("offline", assertIs<DataResult.Error>(refresh.await()).error.code)
        val failedSlice = store.currentChannelMemberSlice(routeOwner, ChannelId("channel-1"))
        assertEquals(SharedServerMembersLoadStatus.Error, failedSlice.status)
        assertEquals("offline", failedSlice.errorCode)
        assertEquals(listOf("user-1"), failedSlice.members.map { it.id.value })
    }

    @Test
    fun standaloneDirectorySliceDoesNotReuseAnotherAccountOnSameServer() = runBlocking {
        val repository = FakeMemberRepository()
        var processOwner = ProcessServerMembersOwner("https://api.raft.build/api", "account-a", "server-1")
        var writeGeneration = 3L
        val store =
            SharedServerMembersStore(
                repository,
                processOwner = { processOwner },
                channelMembersOwner = {
                    processOwner.channelMembersOwnerBinding(writeGeneration)
                }
            )
        val ownerA = AuthenticatedRouteOwner(processOwner.environmentId, processOwner.accountId, processOwner.serverId)
        repository.membersByServer["server-1"] = listOf(member("member-a", "Member A"))
        store.refreshMembers(ownerA, force = true)

        processOwner = processOwner.copy(accountId = "account-b")
        writeGeneration += 1
        val ownerB = AuthenticatedRouteOwner(processOwner.environmentId, processOwner.accountId, processOwner.serverId)

        assertEquals(emptyList(), store.currentServerMembersEntry(ownerB).members)
        assertEquals(emptyList(), store.serverMembersEntryFlow(ownerB).first().members)

        repository.membersByServer["server-1"] = listOf(member("member-b", "Member B"))
        assertIs<DataResult.Content<Unit>>(store.refreshMembers(ownerB, force = false))
        assertEquals(listOf("member-b"), store.currentServerMembersEntry(ownerB).members.map { it.id.value })
        assertEquals(2, repository.getMembersCalls)
    }

    @Test
    fun standaloneDirectoryFirstFrameAppliesHiddenHumanPolicy() {
        val repository = FakeMemberRepository()
        val routeOwner = AuthenticatedRouteOwner("local", "local", "server-1")
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1").copy(role = "member", hideHumansFromMembers = true),
                listOf(
                    member("human-1", "Human"),
                    member("agent-1", "Agent").copy(kind = MemberKind.Agent)
                )
            )
        )

        assertEquals(listOf("agent-1"), store.currentServerMembersEntry(routeOwner).members.map { it.id.value })
    }

    @Test
    fun routeOwnedAddFailsClosedUntilCanonicalPermissionIsKnown() = runBlocking {
        val repository = FakeMemberRepository()
        val processOwner = ProcessServerMembersOwner("https://api.raft.build/api", "account-a", "server-1")
        val routeOwner = AuthenticatedRouteOwner(processOwner.environmentId, processOwner.accountId, processOwner.serverId)
        val store =
            SharedServerMembersStore(
                repository,
                processOwner = { processOwner },
                channelMembersOwner = { processOwner.channelMembersOwnerBinding() }
            )

        val result =
            store.addChannelMember(
                owner = routeOwner,
                request = AddChannelMemberRequest(ChannelId("channel-1"), MemberId("u2"), MemberKind.Human)
            )

        assertEquals("channel_member_add_not_allowed", assertIs<DataResult.NoPermission>(result).blocker.code)
        assertEquals(0, repository.addChannelMemberCalls)
        assertEquals(false, store.currentChannelMemberSlice(routeOwner, ChannelId("channel-1")).canAddMembers)
        assertEquals(false, store.currentChannelMemberSlice(routeOwner, ChannelId("channel-1")).canAddMembersKnown)
    }

    @Test
    fun routeOwnedAddRejectsKnownFalsePermission() = runBlocking {
        val repository = FakeMemberRepository()
        val processOwner = ProcessServerMembersOwner("https://api.raft.build/api", "account-a", "server-1")
        val routeOwner = AuthenticatedRouteOwner(processOwner.environmentId, processOwner.accountId, processOwner.serverId)
        val store =
            SharedServerMembersStore(
                repository,
                processOwner = { processOwner },
                channelMembersOwner = { processOwner.channelMembersOwnerBinding() }
            )
        repository.channelMembersCanAdd = false
        store.loadChannelMembers(owner = routeOwner, channelId = ChannelId("channel-1"))

        val result =
            store.addChannelMember(
                owner = routeOwner,
                request = AddChannelMemberRequest(ChannelId("channel-1"), MemberId("u2"), MemberKind.Human)
            )

        assertIs<DataResult.NoPermission>(result)
        assertEquals(0, repository.addChannelMemberCalls)
        assertEquals(true, store.currentChannelMemberSlice(routeOwner, ChannelId("channel-1")).canAddMembersKnown)
    }

    @Test
    fun canonicalEmptyChannelPermissionAllowsFirstMemberAdd() = runBlocking {
        val repository = FakeMemberRepository()
        val processOwner = ProcessServerMembersOwner("https://api.raft.build/api", "account-a", "server-1")
        val routeOwner = AuthenticatedRouteOwner(processOwner.environmentId, processOwner.accountId, processOwner.serverId)
        val store =
            SharedServerMembersStore(
                repository,
                processOwner = { processOwner },
                channelMembersOwner = { processOwner.channelMembersOwnerBinding() }
            )
        repository.channelMembersCanAdd = true
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(
            store.loadChannelMembers(owner = routeOwner, channelId = ChannelId("channel-empty"))
        )

        val emptySlice = store.currentChannelMemberSlice(routeOwner, ChannelId("channel-empty"))
        assertEquals(emptyList(), emptySlice.members)
        assertTrue(emptySlice.canAddMembersKnown)
        assertTrue(emptySlice.canAddMembers)
        assertIs<DataResult.Content<ChannelMemberActionResult>>(
            store.addChannelMember(
                owner = routeOwner,
                request = AddChannelMemberRequest(ChannelId("channel-empty"), MemberId("u2"), MemberKind.Human)
            )
        )
        assertEquals(1, repository.addChannelMemberCalls)
    }

    @Test
    fun knownAddPermissionSurvivesRefreshFailure() = runBlocking {
        val repository = FakeMemberRepository()
        val processOwner = ProcessServerMembersOwner("https://api.raft.build/api", "account-a", "server-1")
        val routeOwner = AuthenticatedRouteOwner(processOwner.environmentId, processOwner.accountId, processOwner.serverId)
        val store =
            SharedServerMembersStore(
                repository,
                processOwner = { processOwner },
                channelMembersOwner = { processOwner.channelMembersOwnerBinding() }
            )
        repository.channelMembersCanAdd = true
        store.loadChannelMembers(owner = routeOwner, channelId = ChannelId("channel-1"))
        repository.channelMembersResultOverride = DataResult.Error(ScreenError(code = "offline"))
        assertIs<DataResult.Error>(
            store.loadChannelMembers(owner = routeOwner, channelId = ChannelId("channel-1"), force = true)
        )

        val failedSlice = store.currentChannelMemberSlice(routeOwner, ChannelId("channel-1"))
        assertEquals(true, failedSlice.canAddMembersKnown)
        assertEquals(true, failedSlice.canAddMembers)
        assertIs<DataResult.Content<ChannelMemberActionResult>>(
            store.addChannelMember(
                owner = routeOwner,
                request = AddChannelMemberRequest(ChannelId("channel-1"), MemberId("u2"), MemberKind.Human)
            )
        )
        assertEquals(1, repository.addChannelMemberCalls)
    }

    @Test
    fun jointChannelLoadKeepsParticipantsScopedAndRemoteProfilesOutOfServerCache() = runBlocking {
        val repository = FakeMemberRepository()
        repository.channelMembersByChannel["joint-1"] =
            listOf(
                member("local", "Local").copy(serverId = ServerId("server-1")),
                member("remote", "Remote").copy(serverId = ServerId("server-remote"))
            )
        val store = SharedServerMembersStore(repository)

        val result = store.loadChannelMembers(ServerId("server-1"), ChannelId("joint-1"))

        assertIs<DataResult.Content<ChannelMemberSnapshot>>(result)
        assertEquals(listOf("local", "remote"), result.data.members.map { it.id.value })
        assertEquals(
            listOf("local", "remote"),
            store.currentChannelMembers(ServerId("server-1"), ChannelId("joint-1")).map { it.id.value }
        )
        assertEquals(
            emptyList(),
            store.currentServerMembers(ServerId("server-1")).map { it.id.value }
        )
        assertEquals(
            emptySet(),
            store.state.value.profileCacheByServerId["server-1"].orEmpty().keys,
        )
        assertEquals("Local", store.currentMemberProfile(ServerId("server-1"), "local")?.displayName)
        assertEquals("Remote", store.currentMemberProfile(ServerId("server-1"), "remote")?.displayName)
    }

    @Test
    fun removeMemberEvictsServerChannelSuggestionAndPresenceSlices() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] =
            listOf(
                member("u1", "Cindy"),
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active
                )
            )
        repository.channelMembersByChannel["channel-1"] =
            listOf(
                member("u1", "Cindy"),
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active
                )
            )
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(repository, presenceRegistry = registry)
        store.refreshMembers(ServerId("server-1"))
        store.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))
        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )
        store.applyPresence(
            ServerId("server-1"),
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working
            )
        )

        val removed =
            store.removeMember(
                serverId = ServerId("server-1"),
                memberId = MemberId("agent-1"),
                memberKind = MemberKind.Agent
            )

        assertEquals(true, removed)
        assertEquals(listOf("u1"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
        assertEquals(listOf("u1"), store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(listOf("u1"), suggestions.map { it.memberId })
        // task #136: presence lives in the registry; deletion evicted this
        // server's owner partition.
        assertNull(
            registry.factFor(
                ProcessServerMembersOwner(environmentId = "local", accountId = "local", serverId = "server-1"),
                "agent-1"
            )
        )
        assertNull(store.state.value.memberStatus(ServerId("server-1"), "agent-1"))
        assertNull(store.state.value.memberProfile(ServerId("server-1"), "agent-1"))
    }

    @Test
    fun memberProfileSuppressesDeletedAgentFromStaleProfileCache() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] = listOf(
            member("agent-1", "Build Agent").copy(kind = MemberKind.Agent)
        )
        val store = SharedServerMembersStore(repository)
        store.refreshMembers(ServerId("server-1"))

        assertNotNull(store.memberProfile(ServerId("server-1"), "agent-1"))
        assertTrue(store.markAgentDeleted(ServerId("server-1"), MemberId("agent-1")))
        assertNull(store.memberProfile(ServerId("server-1"), "agent-1"))
    }

    @Test
    fun deleteAgentCallsRepositoryAndEvictsMemberThroughStore() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] =
            listOf(
                member("u1", "Cindy"),
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active
                )
            )
        val store = SharedServerMembersStore(repository)
        store.refreshMembers(ServerId("server-1"))

        val result =
            store.deleteAgent(
                serverId = ServerId("server-1"),
                agentId = MemberId("agent-1")
            )

        assertIs<DataResult.Content<AgentLifecycleActionResult>>(result)
        assertEquals(1, repository.deleteAgentCalls)
        assertEquals(listOf("u1"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
    }

    @Test
    fun archivingAnAgentTakesItOffTheMembersPageIncludingAJustCreatedRow() = runTest {
        // A cloud agent created from the app sits on the Members page as the page's own row until
        // the roster refresh lands; archiving it must take it off the list at once (web
        // `deleteAgent` marks it and every list drops it), not only after a reload.
        val repository = FakeMemberRepository()
        val buildAgent = member("agent-1", "Build Agent").copy(handle = "build-agent", kind = MemberKind.Agent)
        repository.membersByServer["server-1"] = listOf(member("u1", "Cindy"), buildAgent)
        val store = SharedServerMembersStore(repository)
        val serverId = ServerId("server-1")
        store.refreshMembers(serverId)
        val justCreated = member("cloud-1", "Cloud Agent").copy(handle = "cloud-agent", kind = MemberKind.Agent, isExternal = true)
        assertEquals(emptySet(), store.deletedAgentIdsFlow(serverId).first())
        assertEquals(
            listOf("u1", "agent-1", "cloud-1"),
            membersPageMergedMembers(store.currentServerMembers(serverId), listOf(justCreated), store.deletedAgentIds(serverId)).map { it.id.value }
        )

        assertIs<DataResult.Content<AgentLifecycleActionResult>>(store.deleteAgent(serverId, MemberId("cloud-1")))
        assertIs<DataResult.Content<AgentLifecycleActionResult>>(store.deleteAgent(serverId, MemberId("agent-1")))

        val archived = store.deletedAgentIdsFlow(serverId).first()
        assertEquals(setOf("cloud-1", "agent-1"), archived)
        assertEquals(archived, store.deletedAgentIds(serverId))
        assertEquals(emptySet(), store.deletedAgentIdsFlow(ServerId("server-2")).first(), "per server")
        assertEquals(
            listOf("u1"),
            membersPageMergedMembers(store.currentServerMembers(serverId), listOf(justCreated), archived).map { it.id.value }
        )
    }

    @Test
    fun deleteAgentEvictsLocallyWithoutImmediateRefreshAndKeepsStaleRosterHidden() = runBlocking {
        val repository = FakeMemberRepository()
        repository.retainDeletedAgentsInRoster = true
        repository.membersByServer["server-1"] =
            listOf(
                member("u1", "Cindy"),
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active
                )
            )
        repository.channelMembersByChannel["channel-1"] =
            listOf(
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active
                )
            )
        val store = SharedServerMembersStore(repository)
        store.refreshMembers(ServerId("server-1"))
        store.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))

        val result =
            store.deleteAgent(
                serverId = ServerId("server-1"),
                agentId = MemberId("agent-1")
            )

        assertIs<DataResult.Content<AgentLifecycleActionResult>>(result)
        assertEquals(1, repository.getMembersCalls)
        assertEquals(listOf("u1"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
        assertEquals(emptyList(), store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })
        assertNull(store.state.value.memberProfile(ServerId("server-1"), "agent-1"))

        store.refreshMembers(ServerId("server-1"), force = true)

        assertEquals(2, repository.getMembersCalls)
        assertEquals(listOf("u1"), store.currentServerMembers(ServerId("server-1")).map { it.id.value })
        assertNull(store.state.value.memberProfile(ServerId("server-1"), "agent-1"))
    }

    @Test
    fun addChannelMemberUpdatesChannelSliceAndSuggestionStateThroughStore() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] =
            listOf(
                member("u1", "Cindy"),
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active
                )
            )
        repository.channelMembersByChannel["channel-1"] = listOf(member("u1", "Cindy"))
        val store = SharedServerMembersStore(repository)
        store.refreshMembers(ServerId("server-1"))
        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )

        val result =
            store.addChannelMember(
                serverId = ServerId("server-1"),
                request =
                    AddChannelMemberRequest(
                        channelId = ChannelId("channel-1"),
                        memberId = MemberId("agent-1"),
                        kind = MemberKind.Agent
                    )
        )

        assertIs<DataResult.Content<ChannelMemberActionResult>>(result)
        assertEquals(1, repository.getChannelMembersCalls)
        assertEquals(listOf("u1", "agent-1"), store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(true, suggestions.single { it.memberId == "agent-1" }.inMentionChannel)
        assertEquals(1, repository.addChannelMemberCalls)
    }

    @Test
    fun routeOwnedMutationDoesNotPatchSliceAfterAccountChanges() = runBlocking {
        val repository = FakeMemberRepository()
        repository.channelMembersByChannel["channel-1"] = listOf(member("u1", "Cindy"))
        repository.membersByServer["server-1"] = listOf(member("u2", "Candidate"))
        repository.addChannelMemberStarted = CompletableDeferred()
        repository.releaseAddChannelMember = CompletableDeferred()
        var processOwner = ProcessServerMembersOwner("https://api.raft.build/api", "account-a", "server-1")
        val store =
            SharedServerMembersStore(
                repository,
                processOwner = { processOwner },
                channelMembersOwner = { processOwner.channelMembersOwnerBinding() }
            )
        store.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))
        val routeOwner = AuthenticatedRouteOwner("https://api.raft.build/api", "account-a", "server-1")

        val result =
            async {
                store.addChannelMember(
                    owner = routeOwner,
                    request =
                        AddChannelMemberRequest(
                            channelId = ChannelId("channel-1"),
                            memberId = MemberId("u2"),
                            kind = MemberKind.Human
                        )
                )
            }
        repository.addChannelMemberStarted?.await()
        processOwner = processOwner.copy(accountId = "account-b")
        repository.releaseAddChannelMember?.complete(Unit)

        assertEquals("channel_members_owner_stale", assertIs<DataResult.Error>(result.await()).error.code)
        assertEquals(
            emptyList(),
            store.currentChannelMemberSlice(routeOwner, ChannelId("channel-1")).members
        )
        assertEquals(
            listOf("u1"),
            store.state.value.channelMemberSlices.getValue(
                SharedServerChannelMembersScope(
                    environmentId = "https://api.raft.build/api",
                    accountId = "account-a",
                    serverId = "server-1",
                    channelId = "channel-1"
                )
            )
                .members
                .map { it.id.value }
        )
    }

    @Test
    fun removeChannelMemberUpdatesChannelSliceAndSuggestionStateThroughStore() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] =
            listOf(
                member("u1", "Cindy"),
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active
                )
            )
        repository.channelMembersByChannel["channel-1"] =
            listOf(
                member("u1", "Cindy"),
                member("agent-1", "Build Agent").copy(
                    handle = "build-agent",
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    presence = Presence.Active
                )
            )
        val store = SharedServerMembersStore(repository)
        store.refreshMembers(ServerId("server-1"))
        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )

        val result =
            store.removeChannelMember(
                serverId = ServerId("server-1"),
                request =
                    RemoveChannelMemberRequest(
                        channelId = ChannelId("channel-1"),
                        memberId = MemberId("agent-1"),
                        kind = MemberKind.Agent
                    )
            )

        assertIs<DataResult.Content<ChannelMemberActionResult>>(result)
        assertEquals(1, repository.getChannelMembersCalls)
        assertEquals(listOf("u1"), store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(false, suggestions.single { it.memberId == "agent-1" }.inMentionChannel)
        assertEquals(1, repository.removeChannelMemberCalls)
    }

    @Test
    fun removeChannelMemberKeepsMemberInRosterAndRefreshesWhenStillInChannel() = runBlocking {
        val repository = FakeMemberRepository()
        val serverA = MemberServerRef(id = "server-a", name = "Server A", slug = "server-a")
        val viewerServer = MemberServerRef(id = "server-1", name = "Workspace", slug = "workspace")
        val aliceBefore =
            member("u1", "Alice").copy(
                serverId = ServerId("server-1"),
                inViewerServer = true,
                canRemoveFromChannel = true,
                servers = listOf(viewerServer, serverA),
            )
        val aliceAfter =
            member("u1", "Alice").copy(
                serverId = ServerId("server-a"),
                serverName = "Server A",
                inViewerServer = false,
                canRemoveFromChannel = false,
                servers = listOf(serverA),
            )
        val bob = member("u2", "Bob")
        val stillInChannel =
            ChannelMemberStillInChannel(
                through = listOf(ChannelMemberStillInChannelServer(id = "server-a", name = "Server A")),
                text = "Alice is still in the channel through Server A. Only your server’s side of their membership was removed.",
            )
        repository.membersByServer["server-1"] = listOf(aliceBefore, bob)
        repository.channelMembersByChannel["channel-1"] = listOf(aliceBefore, bob)
        repository.removeChannelMemberStillInChannel = { stillInChannel }
        repository.onRemoveChannelMemberStillInChannel = {
            repository.channelMembersByChannel["channel-1"] = listOf(aliceAfter, bob)
        }

        val store = SharedServerMembersStore(repository)
        store.refreshMembers(ServerId("server-1"))
        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )
        assertEquals(1, repository.getChannelMembersCalls)

        val result =
            store.removeChannelMember(
                serverId = ServerId("server-1"),
                request =
                    RemoveChannelMemberRequest(
                        channelId = ChannelId("channel-1"),
                        memberId = MemberId("u1"),
                        kind = MemberKind.Human,
                    )
            )

        assertIs<DataResult.Content<ChannelMemberActionResult>>(result)
        assertEquals(stillInChannel, result.data.stillInChannel)
        assertEquals(1, repository.removeChannelMemberCalls)
        assertEquals(2, repository.getChannelMembersCalls)
        val roster = store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1"))
        assertEquals(listOf("u1", "u2"), roster.map { it.id.value })
        val refreshedAlice = roster.single { it.id.value == "u1" }
        assertEquals(false, refreshedAlice.inViewerServer)
        assertEquals(false, refreshedAlice.canRemoveFromChannel)
        assertEquals(listOf(serverA), refreshedAlice.servers)
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(true, suggestions.single { it.memberId == "u1" }.inMentionChannel)
    }

    @Test
    fun seedProfilesCannotOverrideRealtimePresenceFactStatus() = runBlocking {
        val repository = FakeMemberRepository()
        val store = SharedServerMembersStore(repository)
        store.seed(
            MemberSnapshot(
                server("server-1"),
                listOf(
                    member("agent-1", "Agent One").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Online,
                        presence = Presence.Active
                    )
                )
            )
        )
        store.applyPresence(
            ServerId("server-1"),
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working
            )
        )

        store.seedProfiles(
            serverId = ServerId("server-1"),
            server = server("server-1"),
            profiles =
                listOf(
                    member("agent-1", "Agent One").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Idle,
                        presence = Presence.Away
                    )
                )
        )

        assertEquals(
            SharedServerMemberStatus(status = AgentStatus.Working, presence = Presence.Active, external = false),
            store.memberStatusFlow(ServerId("server-1"), "agent-1").first()
        )
        assertEquals(
            AgentStatus.Working,
            store.currentServerMembers(ServerId("server-1")).single().agentStatus
        )
    }

    @Test
    fun memberSuggestionsFailClosedWhenOnlyHandleIsOpaqueIdentity() = runBlocking {
        val repository = FakeMemberRepository()
        val memberId = "123e4567-e89b-12d3-a456-426614174000"
        repository.membersByServer["server-1"] =
            listOf(
                member(memberId, "王小明").copy(handle = memberId)
            )
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(ServerId("server-1"))

        assertEquals(
            emptyList(),
            store.state.value.memberSuggestionSlice(ServerId("server-1")).suggestions
        )
    }

    @Test
    fun channelCanonicalHandleSurvivesOpaqueRosterCandidate() = runBlocking {
        val repository = FakeMemberRepository()
        val memberId = "123e4567-e89b-12d3-a456-426614174000"
        repository.membersByServer["server-1"] =
            listOf(
                member(memberId, "王小明").copy(handle = memberId)
            )
        repository.channelMembersByChannel["channel-1"] =
            listOf(
                member(memberId, "王小明").copy(handle = "王小明")
            )
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )

        val suggestion =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions.single()
        assertEquals("王小明", suggestion.handle)
        assertEquals("王小明", suggestion.displayName)
        assertEquals(true, suggestion.inMentionChannel)
        assertEquals(memberId, suggestion.memberId)
        assertEquals(
            "王小明",
            store.currentCanonicalMentionHandle(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1",
                memberId = memberId
            )
        )
    }

    @Test
    fun channelSnapshotReplacesStaleRosterHandleByMemberIdentity() = runBlocking {
        val repository = FakeMemberRepository()
        val memberId = "member-wang"
        repository.membersByServer["server-1"] =
            listOf(
                member(memberId, "王小明").copy(handle = "stale-wang")
            )
        repository.channelMembersByChannel["channel-1"] =
            listOf(
                member(memberId, "王小明").copy(handle = "王小明")
            )
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )

        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(1, suggestions.size)
        assertEquals("王小明", suggestions.single().handle)
        assertEquals(memberId, suggestions.single().memberId)
    }

    @Test
    fun memberSuggestionsRefreshScopedChannelMembership() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] = listOf(member("u1", "Cindy"), member("u2", "Artin"))
        repository.channelMembersByChannel["channel-1"] = listOf(member("u2", "Artin"))
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )

        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(false, suggestions.single { it.handle == "cindy" }.inMentionChannel)
        assertEquals(true, suggestions.single { it.handle == "artin" }.inMentionChannel)
        assertEquals(1, repository.getChannelMembersCalls)
    }

    @Test
    fun jointMemberSuggestionsKeepSameHandleMembersFromDifferentServers() = runBlocking {
        val repository = FakeMemberRepository()
        repository.channelMembersByChannel["joint-1"] =
            listOf(
                member("local-agent", "Builder").copy(
                    handle = "builder",
                    kind = MemberKind.Agent,
                    serverId = ServerId("server-1"),
                    serverName = "Local Lab"
                ),
                member("remote-agent", "Builder").copy(
                    handle = "builder",
                    kind = MemberKind.Agent,
                    serverId = ServerId("server-2"),
                    serverName = "Remote Lab"
                )
            )
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "joint-1",
            memberScopedMentions = true
        )

        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "joint-1",
                memberScopedMentions = true
            ).suggestions
        assertEquals(listOf("local-agent", "remote-agent"), suggestions.map { it.memberId })
        assertEquals(listOf("server-1", "server-2"), suggestions.map { it.sourceServerId })
        assertEquals(listOf(true, false), suggestions.map { it.isLocalServer })
        assertEquals(listOf(null, "Remote Lab"), suggestions.map { it.sourceServerLabel })
        assertEquals(listOf("builder", "builder"), suggestions.map { it.handle })
        assertEquals(listOf(true, true), suggestions.map { it.inMentionChannel })
    }

    @Test
    fun jointMemberSuggestionPatchKeepsHumanAndAgentWithCollidingIdsDistinct() = runBlocking {
        val repository = FakeMemberRepository()
        repository.channelMembersByChannel["joint-1"] =
            listOf(
                member("same-id", "Builder Agent").copy(handle = "builder", kind = MemberKind.Agent),
                member("same-id", "Builder Human").copy(handle = "builder", kind = MemberKind.Human)
            )
        val store = SharedServerMembersStore(repository)
        val serverId = ServerId("server-1")

        store.ensureMemberSuggestionsForServer(
            serverId = serverId,
            mentionChannelId = "joint-1",
            memberScopedMentions = true
        )
        store.patchMentionChannelMemberSuggestion(
            serverId = serverId,
            mentionChannelId = "joint-1",
            memberScopedMentions = true,
            member = member("same-id", "Updated Human").copy(handle = "builder", kind = MemberKind.Human),
            inMentionChannel = true
        )

        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = serverId,
                mentionChannelId = "joint-1",
                memberScopedMentions = true
            ).suggestions
        assertEquals(2, suggestions.size)
        assertEquals("Builder Agent", suggestions.single { it.kind == MemberKind.Agent }.displayName)
        assertEquals("Updated Human", suggestions.single { it.kind == MemberKind.Human }.displayName)
    }

    @Test
    fun memberSuggestionPatchMarksRemovedChannelMemberWithoutReloading() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] = listOf(member("u1", "Cindy"), member("u2", "Artin"))
        repository.channelMembersByChannel["channel-1"] = listOf(member("u1", "Cindy"), member("u2", "Artin"))
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )
        val patched =
            store.removeMentionChannelMemberSuggestion(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1",
                memberScopedMentions = false,
                memberId = "u2",
                memberKind = MemberKind.Human
            )

        assertEquals(true, patched)
        assertEquals(1, repository.getMembersCalls)
        assertEquals(1, repository.getChannelMembersCalls)
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(false, suggestions.single { it.handle == "artin" }.inMentionChannel)
        assertEquals(listOf("u1"), store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })
    }

    @Test
    fun memberSuggestionPatchAddsChannelMemberWithoutReloading() = runBlocking {
        val repository = FakeMemberRepository()
        repository.membersByServer["server-1"] = listOf(member("u1", "Cindy"), member("u2", "Artin"))
        repository.channelMembersByChannel["channel-1"] = listOf(member("u1", "Cindy"))
        val store = SharedServerMembersStore(repository)

        store.ensureMemberSuggestionsForServer(
            serverId = ServerId("server-1"),
            mentionChannelId = "channel-1"
        )
        val patched =
            store.patchMentionChannelMemberSuggestion(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1",
                memberScopedMentions = false,
                member = member("u2", "Artin"),
                inMentionChannel = true
            )

        assertEquals(true, patched)
        assertEquals(1, repository.getMembersCalls)
        assertEquals(1, repository.getChannelMembersCalls)
        val suggestions =
            store.state.value.memberSuggestionSlice(
                serverId = ServerId("server-1"),
                mentionChannelId = "channel-1"
            ).suggestions
        assertEquals(true, suggestions.single { it.handle == "artin" }.inMentionChannel)
        assertEquals(
            listOf("u1", "u2"),
            store.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value }
        )
    }

    @Test
    fun scopedAgentStatusResolverNeverCrossesServersForTheSameAgentId() = runBlocking {
        val store = SharedServerMembersStore(FakeMemberRepository())
        store.seed(
            MemberSnapshot(
                server("server-route"),
                listOf(
                    member("agent-friday", "Friday").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Working,
                        presence = Presence.Active
                    )
                )
            )
        )
        store.seed(
            MemberSnapshot(
                server("server-other"),
                listOf(
                    member("agent-friday", "Friday Elsewhere").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Error,
                        presence = Presence.Away
                    )
                )
            )
        )

        val routeStatus =
            raftScopedAgentStatusResolver(store, "server-route")
                .invoke("agent-friday")
                .first()
        val unknownScopeStatus =
            raftScopedAgentStatusResolver(store, null)
                .invoke("agent-friday")
                .first()

        assertEquals(AgentStatus.Working, routeStatus?.status)
        assertEquals("Working", routeStatus?.label)
        assertNull(unknownScopeStatus)
    }

    @Test
    fun coldStartThreadAuthorSeedShowsNoIndicatorUntilSomethingAuthoritativeArrives() = runBlocking {
        // Cold start: a thread page paints message authors before the roster hydrates and
        // before realtime attaches a transport, so the only profile in scope is the
        // volatile-status-stripped seed. Presence has no Unknown member, so that seed carries
        // a forced Presence.Offline; publishing it labelled a working agent "Offline"
        // (task #253). The indicator must stay absent instead.
        val store = SharedServerMembersStore(FakeMemberRepository())
        val serverId = ServerId("server-1")
        store.seedThreadAuthorProfiles(
            serverId,
            listOf(
                RaftThreadMessageItem(
                    id = "cold-start-message",
                    authorId = "agent-1",
                    authorName = "Argus",
                    authorKind = "agent"
                )
            ),
            source = "cold_start"
        )

        assertNull(
            raftScopedAgentStatusResolver(store, serverId.value).invoke("agent-1").first(),
            "a stripped seed must not render any dot, least of all one labelled Offline"
        )

        // ...and the indicator returns as soon as an authoritative record exists, so this is a
        // deferral, not a permanent suppression.
        store.seed(
            MemberSnapshot(
                server(serverId.value),
                listOf(
                    member("agent-1", "Argus").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Working,
                        presence = Presence.Active
                    )
                )
            )
        )
        val hydrated = raftScopedAgentStatusResolver(store, serverId.value).invoke("agent-1").first()
        assertEquals(AgentStatus.Working, hydrated?.status)
        assertEquals("Working", hydrated?.label)
    }

    @Test
    fun snapshotMemberStatusAppliesTheSamePolicyForRosterAndSeedProfiles() {
        // Roster and seed only — that is where this edge and memberStatusFlow agree. The
        // slice-only case they disagree on is unreachable from here (see memberStatus).
        val serverId = ServerId("server-1")
        val seedOnlyStore = SharedServerMembersStore(FakeMemberRepository())
        seedOnlyStore.seedThreadAuthorProfiles(
            serverId,
            listOf(
                RaftThreadMessageItem(
                    id = "cold-start-message",
                    authorId = "agent-1",
                    authorName = "Argus",
                    authorKind = "agent"
                )
            ),
            source = "cold_start"
        )
        assertNull(seedOnlyStore.state.value.memberStatus(serverId, "agent-1"))

        val rosterStore = SharedServerMembersStore(FakeMemberRepository())
        rosterStore.seed(
            MemberSnapshot(
                server(serverId.value),
                listOf(
                    member("agent-1", "Argus").copy(
                        kind = MemberKind.Agent,
                        agentStatus = null,
                        presence = Presence.Offline
                    )
                )
            )
        )
        assertEquals(
            SharedServerMemberStatus(status = null, presence = Presence.Offline, external = false),
            rosterStore.state.value.memberStatus(serverId, "agent-1"),
            "a roster member's Offline is an observation and must survive here too"
        )
    }

    @Test
    fun rosterHydrationMakesTheSameMemberAuthoritativeAfterASeed() = runBlocking {
        // The provenance seam must flip once the roster arrives, otherwise whichever policy
        // ships would keep suppressing a real status for the rest of the session.
        val store = SharedServerMembersStore(FakeMemberRepository())
        val serverId = ServerId("server-1")
        store.seedThreadAuthorProfiles(
            serverId,
            listOf(
                RaftThreadMessageItem(
                    id = "cold-start-message",
                    authorId = "agent-1",
                    authorName = "Argus",
                    authorKind = "agent"
                )
            ),
            source = "cold_start"
        )
        assertNull(store.memberStatusFlow(serverId, "agent-1").first())

        store.seed(
            MemberSnapshot(
                server(serverId.value),
                listOf(
                    member("agent-1", "Argus").copy(
                        kind = MemberKind.Agent,
                        agentStatus = AgentStatus.Working,
                        presence = Presence.Active
                    )
                )
            )
        )

        val hydrated = store.memberStatusFlow(serverId, "agent-1").first()
        assertEquals(AgentStatus.Working, hydrated?.status)
        assertEquals(Presence.Active, hydrated?.presence)
    }

    @Test
    fun authoritativeChannelMemberMergePreservesAndUpgradesOriginServerLogos() = runBlocking {
        val repository = FakeMemberRepository()
        val serverId = ServerId("server-1")
        val channelId = ChannelId("joint-1")
        val serverAWithoutLogo = MemberServerRef(id = "server-a", name = "Alpha", slug = "alpha", avatarUrl = null)
        val serverAWithLogo = MemberServerRef(id = "server-a", name = "Alpha", slug = "alpha", avatarUrl = "/alpha.webp")
        val serverBWithLogo = MemberServerRef(id = "server-b", name = "Beta", slug = "beta", avatarUrl = "/beta.webp")

        // Load channel members initially with ServerA (no logo) + ServerB (logo), and a bare-serverId
        // agent initially seeded on the viewer server so authoritative patch must upgrade its serverId.
        val store = SharedServerMembersStore(repository)
        val extHumanFirst =
            member("ext-user-1", "Alice").copy(
                serverId = ServerId("server-a"),
                inViewerServer = false,
                servers = listOf(serverAWithoutLogo, serverBWithLogo),
            )
        val extAgentSeededOnViewer =
            member("ext-agent-1", "Volta").copy(
                kind = MemberKind.Agent,
                serverId = serverId,
            )
        val extAgentAuthoritative =
            extAgentSeededOnViewer.copy(
                serverId = ServerId("server-b"),
            )
        repository.channelMembersByChannel[channelId.value] = listOf(extHumanFirst, extAgentSeededOnViewer)
        store.loadChannelMembers(serverId, channelId, force = true)
        store.ensureMemberSuggestionsForServer(
            serverId = serverId,
            mentionChannelId = channelId.value,
            memberScopedMentions = true,
            force = true,
        )
        val cachedFirst = assertNotNull(store.currentMemberProfile(serverId, "ext-user-1"))
        assertEquals(false, cachedFirst.inViewerServer)
        assertEquals(listOf(serverAWithoutLogo, serverBWithLogo), cachedFirst.servers)
        assertEquals(serverId, store.currentMemberProfile(serverId, "ext-agent-1")?.serverId)

        // Patching with an upgraded ServerA logo while ServerB already had a logo updates both
        // channel member profile and mention suggestion originServers per server id; patching the
        // bare-serverId external agent updates its serverId over the viewer-server seed.
        val extHumanSecond = extHumanFirst.copy(servers = listOf(serverAWithLogo, serverBWithLogo))
        store.patchMentionChannelMemberSuggestion(
            serverId = serverId,
            mentionChannelId = channelId.value,
            memberScopedMentions = true,
            member = extHumanSecond,
            inMentionChannel = true,
        )
        store.patchMentionChannelMemberSuggestion(
            serverId = serverId,
            mentionChannelId = channelId.value,
            memberScopedMentions = true,
            member = extAgentAuthoritative,
            inMentionChannel = true,
        )
        val cachedSecond = assertNotNull(store.currentMemberProfile(serverId, "ext-user-1"))
        assertEquals(listOf(serverAWithLogo, serverBWithLogo), cachedSecond.servers)
        assertEquals(ServerId("server-b"), store.currentMemberProfile(serverId, "ext-agent-1")?.serverId)
        val suggestion =
            store.state.value
                .memberSuggestionSlice(
                    serverId = serverId,
                    mentionChannelId = channelId.value,
                    memberScopedMentions = true,
                )
                .suggestions
                .first { it.memberId == "ext-user-1" }
        assertEquals(listOf(serverAWithLogo, serverBWithLogo), suggestion.originServers)
    }

    private class FakeMemberRepository : MemberRepository {
        val membersByServer = mutableMapOf("server-1" to listOf(member("u1", "Cindy")))
        val channelMembersByChannel = mutableMapOf<String, List<MemberProfile>>()
        var getMembersCalls = 0
        var getChannelMembersCalls = 0
        var addChannelMemberCalls = 0
        var removeChannelMemberCalls = 0
        var deleteAgentCalls = 0
        var retainDeletedAgentsInRoster = false
        var membersResultOverride: DataResult<MemberSnapshot>? = null
        var getMembersStarted: CompletableDeferred<Unit>? = null
        var releaseGetMembers: CompletableDeferred<Unit>? = null
        var getChannelMembersStarted: CompletableDeferred<Unit>? = null
        var releaseGetChannelMembers: CompletableDeferred<Unit>? = null
        var channelMembersResultOverride: DataResult<ChannelMemberSnapshot>? = null
        var channelMembersCanAdd: Boolean = true
        var addChannelMemberStarted: CompletableDeferred<Unit>? = null
        var releaseAddChannelMember: CompletableDeferred<Unit>? = null
        var removeChannelMemberStillInChannel: ((RemoveChannelMemberRequest) -> ChannelMemberStillInChannel?)? = null
        var onRemoveChannelMemberStillInChannel: ((RemoveChannelMemberRequest) -> Unit)? = null

        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> {
            getMembersCalls += 1
            getMembersStarted?.complete(Unit)
            releaseGetMembers?.await()
            membersResultOverride?.let { return it }
            return DataResult.Content(MemberSnapshot(server(serverId.value), membersByServer[serverId.value].orEmpty()))
        }

        override suspend fun getMemberProfile(priority: DataRequestPriority, memberId: MemberId, serverId: ServerId?): DataResult<MemberProfile> = error("unused")
        override suspend fun updateAgentProfile(priority: DataRequestPriority, request: AgentProfileUpdateRequest): DataResult<MemberProfile> = error("unused")
        override suspend fun uploadAgentAvatar(priority: DataRequestPriority, request: AgentAvatarUploadRequest): DataResult<MemberProfile> = error("unused")
        override suspend fun getAgentDetail(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDetailSnapshot> = error("unused")
        override suspend fun getAgentDirectMessages(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDmSnapshot> = error("unused")
        override suspend fun getAgentReminders(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentReminderSnapshot> = error("unused")
        override suspend fun getAgentActivity(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentActivitySnapshot> = error("unused")
        override suspend fun getAgentWorkspaces(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceSnapshot> = error("unused")
        override suspend fun getAgentWorkspaceTree(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceTreeSnapshot> = error("unused")
        override suspend fun getAgentWorkspaceChildren(priority: DataRequestPriority, agentId: MemberId, dirPath: String): DataResult<AgentWorkspaceChildrenSnapshot> = error("unused")
        override suspend fun readAgentWorkspaceFile(priority: DataRequestPriority, agentId: MemberId, path: String): DataResult<AgentWorkspaceFilePreviewSnapshot> = error("unused")
        override suspend fun getAgentSkills(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentSkillsSnapshot> = error("unused")
        override suspend fun startAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun stopAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun resetAgent(priority: DataRequestPriority, request: AgentResetRequest): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun deleteAgent(
            priority: DataRequestPriority,
            agentId: MemberId,
            workspaceChoice: AgentArchiveWorkspaceChoice
        ): DataResult<AgentLifecycleActionResult> {
            deleteAgentCalls += 1
            if (!retainDeletedAgentsInRoster) {
                for (key in membersByServer.keys.toList()) {
                    membersByServer[key] =
                        membersByServer.getValue(key).filterNot { it.id == agentId && it.kind == MemberKind.Agent }
                }
            }
            return DataResult.Content(
                AgentLifecycleActionResult(
                    agentId = agentId,
                    success = true,
                    action = "delete"
                )
            )
        }
        override suspend fun assignAgentMachine(priority: DataRequestPriority, request: AgentMachineAssignmentRequest): DataResult<AgentLifecycleActionResult> = error("unused")
        override suspend fun getChannelMembers(
            priority: DataRequestPriority,
            channelId: ChannelId
        ): DataResult<ChannelMemberSnapshot> {
            getChannelMembersCalls += 1
            getChannelMembersStarted?.complete(Unit)
            releaseGetChannelMembers?.await()
            channelMembersResultOverride?.let { return it }
            return DataResult.Content(
                ChannelMemberSnapshot(
                    channelId = channelId,
                    members = channelMembersByChannel[channelId.value].orEmpty(),
                    canAddMembers = channelMembersCanAdd, canRemoveMembers = true
                )
            )
        }
        override suspend fun addChannelMember(
            priority: DataRequestPriority,
            request: AddChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> {
            addChannelMemberCalls += 1
            addChannelMemberStarted?.complete(Unit)
            releaseAddChannelMember?.await()
            val member =
                membersByServer.values
                    .asSequence()
                    .flatMap { it.asSequence() }
                    .firstOrNull { it.id == request.memberId && it.kind == request.kind }
                    ?: member(request.memberId.value, request.memberId.value).copy(kind = request.kind)
            val current = channelMembersByChannel[request.channelId.value].orEmpty()
            channelMembersByChannel[request.channelId.value] = current.upsertFakeMember(member)
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
            priority: DataRequestPriority,
            request: RemoveChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> {
            removeChannelMemberCalls += 1
            val stillInChannel = removeChannelMemberStillInChannel?.invoke(request)
            if (stillInChannel != null) {
                onRemoveChannelMemberStillInChannel?.invoke(request)
            } else {
                channelMembersByChannel[request.channelId.value] =
                    channelMembersByChannel[request.channelId.value]
                        .orEmpty()
                        .filterNot { it.id == request.memberId && it.kind == request.kind }
            }
            return DataResult.Content(
                ChannelMemberActionResult(
                    channelId = request.channelId,
                    memberId = request.memberId,
                    member = null,
                    added = false,
                    stillInChannel = stillInChannel,
                )
            )
        }
    }
}

private fun server(id: String): ServerSummary =
    ServerSummary(id = ServerId(id), name = "Workspace", slug = "workspace")

private fun member(id: String, name: String): MemberProfile =
    MemberProfile(id = MemberId(id), displayName = name, handle = name.lowercase(), kind = MemberKind.Human)

private fun ProcessServerMembersOwner.channelMembersOwnerBinding(
    writeGeneration: Long = 0L
): SharedServerChannelMembersOwnerBinding =
    SharedServerChannelMembersOwnerBinding(
        environmentId = environmentId.trim().trimEnd('/'),
        accountId = accountId.trim(),
        serverId = serverId.trim(),
        writeGeneration = writeGeneration
    )

private fun List<MemberProfile>.upsertFakeMember(member: MemberProfile): List<MemberProfile> {
    val current = filterNot { it.id == member.id && it.kind == member.kind }
    return current + member
}
