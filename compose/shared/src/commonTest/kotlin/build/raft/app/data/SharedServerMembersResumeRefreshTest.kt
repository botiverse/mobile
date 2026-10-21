package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Task #60: a return from background fans out several members refreshes (socket reconnect
 * rebaseline, Home agent-status projection, Home start). They must cost one roster fetch and,
 * when the roster did not change, no members-state publication at all.
 */
class SharedServerMembersResumeRefreshTest {
    private val serverId = ServerId("server-1")
    private val roster = listOf(member("u1", "Cindy"), member("u2", "Dora"))

    private class GatedRosterFetch(private val roster: List<MemberProfile>) {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null

        suspend fun fetch(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
            calls += 1
            gate?.await()
            return DataResult.Content(MemberSnapshot(server(serverId.value), roster))
        }
    }

    @Test
    fun resumeBackgroundRefreshesShareOneFetchAndAnUnchangedRosterPublishesNothing() = runTest {
        val fetch = GatedRosterFetch(roster)
        val store =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                processCore = ProcessServerMembersCore(fetchMembers = fetch::fetch, scope = backgroundScope)
            )
        assertIs<DataResult.Content<Unit>>(store.refreshMembers(serverId))
        assertEquals(1, fetch.calls)
        val published = store.state.value
        assertEquals(SharedServerMembersLoadStatus.Loaded, published.entryFor(serverId)?.status)

        fetch.gate = CompletableDeferred()
        val reconnect =
            async {
                store.refreshMembers(
                    serverId,
                    priority = DataRequestPriority.Background,
                    force = true,
                    reason = "presence_transport_connected"
                )
            }
        runCurrent()
        val projection =
            async {
                store.refreshMembers(
                    serverId,
                    priority = DataRequestPriority.Background,
                    reason = "home_agent_status_projection"
                )
            }
        runCurrent()

        assertEquals(2, fetch.calls, "the second resume trigger joins the reconnect flight")
        assertSame(published, store.state.value, "a background revalidation keeps the roster Loaded")

        fetch.gate?.complete(Unit)
        assertIs<DataResult.Content<Unit>>(reconnect.await())
        assertIs<DataResult.Content<Unit>>(projection.await())

        assertEquals(2, fetch.calls)
        assertSame(published, store.state.value, "an unchanged roster must not re-emit members state")
    }

    @Test
    fun versionUnchangedRefreshDoesNotEmit() = runTest {
        val fetch = GatedRosterFetch(roster)
        val store =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                processCore = ProcessServerMembersCore(fetchMembers = fetch::fetch, scope = backgroundScope)
            )
        assertIs<DataResult.Content<Unit>>(store.refreshMembers(serverId))
        val published = store.state.value

        assertIs<DataResult.Content<Unit>>(
            store.refreshMembers(serverId, priority = DataRequestPriority.Visible, reason = "visible_join")
        )

        assertEquals(1, fetch.calls, "a cached roster version is served without a fetch")
        assertSame(published, store.state.value)
    }
}

private fun server(id: String): ServerSummary =
    ServerSummary(id = ServerId(id), name = "Workspace", slug = "workspace")

private fun member(id: String, name: String): MemberProfile =
    MemberProfile(id = MemberId(id), displayName = name, handle = name.lowercase(), kind = MemberKind.Human)
