package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Home read model lags the primary database: a catch-up re-fetch (foreground, socket reconnect)
 * may answer with a row older than a realtime message already applied locally.
 */
class HomeStaleFetchMergeTest {
    private fun home(channels: List<HomeChannelRow> = emptyList(), dms: List<HomeDirectMessageRow> = emptyList()) =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "Slock"),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Artin",
                    handle = "artin@slock.ai",
                    kind = MemberKind.Human,
                    presence = Presence.Active
                ),
            channels = channels,
            directMessages = dms,
            unreadTotal = 0
        )

    private fun channel(
        seq: Long?,
        preview: String,
        unread: Int,
        name: String = "general",
        readSeq: Long? = null,
    ) = HomeChannelRow(
        threadId = ThreadId("ch-1"),
        channelId = ChannelId("ch-1"),
        name = name,
        unreadCount = unread,
        unreadCountKnown = true,
        lastMessageId = MessageId("m-$seq"),
        lastMessageSeq = seq,
        lastMessagePreview = preview,
        lastMessageAt = "t$seq",
        lastMessageSenderName = "Alice",
        readStateMaxReadSeq = readSeq,
    )

    private fun dm(seq: Long?, preview: String, unread: Int) =
        HomeDirectMessageRow(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            name = "Bob",
            unreadCount = unread,
            unreadCountKnown = true,
            lastMessageId = MessageId("d-$seq"),
            lastMessageSeq = seq,
            lastMessagePreview = preview,
            lastMessageAt = "t$seq",
            lastMessageSenderName = "Bob",
        )

    /** The local state after realtime messages seq 11 (channel) and 21 (DM) on top of the last fetch. */
    private val beforeRealtime = home(listOf(channel(10, "ten", 0)), listOf(dm(20, "twenty", 0)))
    private val local = home(listOf(channel(11, "eleven", 1)), listOf(dm(21, "twenty-one", 1)))
    private val guarded = local.rowsWithNewerMessageThan(beforeRealtime).keys

    @Test
    fun aFetchOlderThanTheRealtimeMessageKeepsTheLocalMessageAndUnread() {
        // The replica has not seen seq 11 / 21 yet; the channel was renamed meanwhile.
        val fetched = home(listOf(channel(10, "ten", 0, name = "general-renamed")), listOf(dm(20, "twenty", 0)))

        val merged = fetched.keepingNewerLocalMessagesFrom(local, guarded)

        val mergedChannel = merged.channels.single()
        assertEquals(11L, mergedChannel.lastMessageSeq)
        assertEquals("eleven", mergedChannel.lastMessagePreview)
        assertEquals(MessageId("m-11"), mergedChannel.lastMessageId)
        assertEquals(1, mergedChannel.unreadCount, "the realtime unread survives")
        assertEquals("general-renamed", mergedChannel.name, "non-message fields take the fetched value")
        val mergedDm = merged.directMessages.single()
        assertEquals(21L, mergedDm.lastMessageSeq)
        assertEquals("twenty-one", mergedDm.lastMessagePreview)
        assertEquals(1, mergedDm.unreadCount)
    }

    @Test
    fun aFetchThatReachedOrPassedTheRealtimeMessageWins() {
        listOf(11L, 12L).forEach { seq ->
            val fetched = home(listOf(channel(seq, "server $seq", 2)), listOf(dm(seq + 10, "server dm", 0)))
            assertSame(fetched, fetched.keepingNewerLocalMessagesFrom(local, guarded), "seq $seq")
        }
    }

    @Test
    fun aStaleFetchWhoseReadStateCoversTheNewerMessageTakesTheFetchedUnread() {
        // Read on another device up to seq 11: the row is read even though the replica's preview lags.
        val fetched = home(listOf(channel(10, "ten", 0, readSeq = 11)))

        val merged = fetched.keepingNewerLocalMessagesFrom(local, guarded).channels.single()

        assertEquals("eleven", merged.lastMessagePreview)
        assertEquals(0, merged.unreadCount)
    }

    @Test
    fun withoutAUsableVersionOrGuardTheFetchWins() {
        val cases =
            listOf(
                "fetched row without seq" to (home(listOf(channel(null, "no seq", 0))) to guarded),
                "row not advanced by realtime (guard expired or never set)" to (home(listOf(channel(10, "ten", 0))) to emptySet()),
            )
        cases.forEach { (label, input) ->
            val (fetched, keys) = input
            assertSame(fetched, fetched.keepingNewerLocalMessagesFrom(local, keys), label)
        }
    }

    @Test
    fun theGuardHoldsUntilAFetchReachesTheSeqOrTheLagWindowPasses() {
        val now = kotlin.time.TimeSource.Monotonic.markNow()
        val guard = HomeRealtimeRowGuard(ServerId("server-1"), seq = 11, at = now)
        assertTrue(guard.guards(ServerId("server-1"), "k", mapOf("k" to 10L)), "fetch still behind")
        assertTrue(guard.guards(ServerId("server-1"), "k", emptyMap()), "row absent from the fetch")
        assertFalse(guard.guards(ServerId("server-1"), "k", mapOf("k" to 11L)), "fetch reached it")
        assertFalse(guard.guards(ServerId("server-2"), "k", mapOf("k" to 10L)), "another server")
        val expired = HomeRealtimeRowGuard(ServerId("server-1"), seq = 11, at = now - HOME_REPLICA_LAG_GUARD)
        assertFalse(expired.guards(ServerId("server-1"), "k", mapOf("k" to 10L)), "past the lag window")
    }
}
