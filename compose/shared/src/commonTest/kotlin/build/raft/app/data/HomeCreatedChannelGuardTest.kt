package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** tygg / artin 11-06: the Refresh right after a create reads a lagging replica without the new channel. */
class HomeCreatedChannelGuardTest {
    private val created = row("new")

    private fun row(id: String) = HomeChannelRow(threadId = ThreadId(id), channelId = ChannelId(id), name = id)

    private fun snapshot(server: String, vararg ids: String) =
        HomeSnapshot(
            server = ServerSummary(ServerId(server), "Server"),
            currentMember =
                MemberProfile(id = MemberId("user-1"), displayName = "User", handle = "user", kind = MemberKind.Human),
            channels = ids.map(::row),
            directMessages = emptyList(),
            unreadTotal = 0,
        )

    private fun guard(server: String = "server-a", age: Int = 0) =
        HomeCreatedChannelGuard(ServerId(server), created, TimeSource.Monotonic.markNow() - age.seconds)

    @Test
    fun aFetchWithoutTheChannelKeepsIt() {
        val merged = snapshot("server-a", "old").withCreatedChannelsFrom(listOf(guard()))
        assertEquals(listOf("old", "new"), merged.channels.map { it.channelId.value })
    }

    @Test
    fun aFetchListingTheChannelUsesItsOwnRowAndSettlesTheGuard() {
        val fetched = snapshot("server-a", "new")
        assertSame(fetched, fetched.withCreatedChannelsFrom(listOf(guard())))
        assertTrue(guard().settledBy(fetched))
    }

    @Test
    fun anotherServersFetchIsLeftAlone() {
        val other = snapshot("server-b", "old")
        assertSame(other, other.withCreatedChannelsFrom(listOf(guard())))
        assertFalse(guard().settledBy(other))
    }

    @Test
    fun theGuardEndsWithTheLagWindow() {
        assertFalse(guard(age = 59).settledBy(snapshot("server-a", "old")))
        assertTrue(guard(age = 61).settledBy(snapshot("server-a", "old")))
    }
}
