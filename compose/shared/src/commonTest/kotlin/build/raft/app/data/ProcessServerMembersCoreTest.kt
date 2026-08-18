package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessServerMembersCoreTest {
    @Test
    fun sameOwnerAcrossShellConsumersUsesOneFlightAndOneReduction() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fetches = 0
        val core =
            ProcessServerMembersCore(fetchMembers = { _, _ ->
                fetches += 1
                started.complete(Unit)
                release.await()
                DataResult.Content(snapshot("server-1", duplicateMember = true))
            })
        val owner = owner(serverId = "server-1")

        val first =
            async {
                core.load(owner, DataRequestPriority.Background, force = true, reason = "foreground")
            }
        started.await()
        val second =
            async {
                core.load(owner, DataRequestPriority.Visible, force = false, reason = "home")
            }
        yield()

        assertEquals(1, fetches)
        release.complete(Unit)
        val firstResult = first.await()
        val secondResult = second.await()
        assertEquals(firstResult.version, secondResult.version)
        assertEquals(1, assertIs<DataResult.Content<MemberSnapshot>>(firstResult.result).data.members.size)
        assertEquals(1, assertIs<DataResult.Content<MemberSnapshot>>(secondResult.result).data.members.size)
    }

    @Test
    fun cancellingFirstShellWaiterDoesNotCancelProcessProducer() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fetches = 0
        val core =
            ProcessServerMembersCore(fetchMembers = { _, _ ->
                fetches += 1
                started.complete(Unit)
                release.await()
                DataResult.Content(snapshot("server-1"))
            })
        val owner = owner(serverId = "server-1")

        val first =
            launch {
                core.load(owner, DataRequestPriority.Background, force = true, reason = "foreground")
            }
        started.await()
        val second =
            async {
                core.load(owner, DataRequestPriority.Visible, force = true, reason = "home")
            }
        yield()
        first.cancelAndJoin()

        assertTrue(second.isActive)
        release.complete(Unit)
        assertTrue(second.await().publicationCurrent)
        assertEquals(1, fetches)
    }

    @Test
    fun differentServersUseIndependentFlights() = runBlocking {
        val startedOne = CompletableDeferred<Unit>()
        val startedTwo = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val core =
            ProcessServerMembersCore(fetchMembers = { _, serverId ->
                if (serverId.value == "server-1") startedOne.complete(Unit) else startedTwo.complete(Unit)
                release.await()
                DataResult.Content(snapshot(serverId.value))
            })

        val first =
            async {
                core.load(owner("server-1"), DataRequestPriority.Background, force = true, reason = "one")
            }
        val second =
            async {
                core.load(owner("server-2"), DataRequestPriority.Background, force = true, reason = "two")
            }

        startedOne.await()
        startedTwo.await()
        release.complete(Unit)
        assertTrue(first.await().publicationCurrent)
        assertTrue(second.await().publicationCurrent)
    }

    @Test
    fun changedOwnerWriteGenerationDoesNotReusePriorGenerationCache() = runBlocking {
        var fetches = 0
        val core =
            ProcessServerMembersCore(fetchMembers = { _, _ ->
                fetches += 1
                DataResult.Content(
                    snapshot(
                        serverId = "server-1",
                        memberId = if (fetches == 1) "old" else "new"
                    )
                )
            })
        val owner = owner("server-1")

        val first =
            core.load(
                owner = owner,
                priority = DataRequestPriority.Visible,
                force = false,
                reason = "generation_one",
                ownerWriteGeneration = 1L
            )
        val second =
            core.load(
                owner = owner,
                priority = DataRequestPriority.Visible,
                force = false,
                reason = "generation_two",
                ownerWriteGeneration = 2L
            )

        assertEquals("old", assertIs<DataResult.Content<MemberSnapshot>>(first.result).data.members.single().id.value)
        assertEquals("new", assertIs<DataResult.Content<MemberSnapshot>>(second.result).data.members.single().id.value)
        assertEquals(2, fetches)
    }

    @Test
    fun everyJoinedFailureConsumerCanCommitBeforeBoundedTerminalEviction() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val core =
            ProcessServerMembersCore(
                fetchMembers = { _, _ ->
                    started.complete(Unit)
                    release.await()
                    DataResult.Error(ScreenError(code = "offline"))
                },
                maxEntries = 1
            )
        val sharedOwner = owner("server-1")
        val first = async { core.load(sharedOwner, DataRequestPriority.Visible, true, "first") }
        started.await()
        val second = async { core.load(sharedOwner, DataRequestPriority.Visible, false, "second") }
        yield()
        release.complete(Unit)
        val firstResult = first.await()
        val secondResult = second.await()

        assertEquals("first", core.commitIfCurrent(firstResult) { "first" })
        assertEquals("second", core.commitIfCurrent(secondResult) { "second" })

        core.load(owner("server-2"), DataRequestPriority.Visible, true, "evict")
        assertEquals(null, core.commitIfCurrent(firstResult) { "stale" })
    }

    @Test
    fun invalidationRejectsOldCompletionAndNewGenerationWinsCache() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        var fetch = 0
        val core =
            ProcessServerMembersCore(fetchMembers = { _, _ ->
                fetch += 1
                if (fetch == 1) {
                    oldStarted.complete(Unit)
                    releaseOld.await()
                    DataResult.Content(snapshot("server-1", memberId = "old"))
                } else {
                    newStarted.complete(Unit)
                    releaseNew.await()
                    DataResult.Content(snapshot("server-1", memberId = "new"))
                }
            })
        val owner = owner("server-1")

        val old =
            async {
                core.load(owner, DataRequestPriority.Background, force = true, reason = "old")
            }
        oldStarted.await()
        core.invalidateAll(reason = "account_changed")
        val fresh =
            async {
                core.load(owner, DataRequestPriority.Visible, force = false, reason = "fresh")
            }
        newStarted.await()
        releaseOld.complete(Unit)
        releaseNew.complete(Unit)

        assertFalse(old.await().publicationCurrent)
        val freshResult = fresh.await()
        assertTrue(freshResult.publicationCurrent)
        val cached = core.load(owner, DataRequestPriority.Visible, force = false, reason = "verify")
        val member = assertIs<DataResult.Content<MemberSnapshot>>(cached.result).data.members.single()
        assertEquals("new", member.id.value)
        assertEquals(2, fetch)
    }

    private fun owner(serverId: String): ProcessServerMembersOwner =
        ProcessServerMembersOwner(
            environmentId = "https://api.example.test",
            accountId = "account-1",
            serverId = serverId
        )

    private fun snapshot(
        serverId: String,
        memberId: String = "member-1",
        duplicateMember: Boolean = false
    ): MemberSnapshot {
        val member =
            MemberProfile(
                id = MemberId(memberId),
                displayName = memberId,
                handle = memberId,
                kind = MemberKind.Human
            )
        return MemberSnapshot(
            server = ServerSummary(id = ServerId(serverId), name = serverId),
            members = if (duplicateMember) listOf(member, member) else listOf(member)
        )
    }
}
