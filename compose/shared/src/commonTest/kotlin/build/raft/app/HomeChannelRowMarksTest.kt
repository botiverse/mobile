package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home channel row marks against web Sidebar.tsx `ChannelRow` +
 * `getChannelUnreadIndicatorState` (botiverse/slock packages/web/src/utils/channelUnreadIndicator.ts):
 * loud = something unread (own or followed threads) in a joined, unmuted channel; quiet otherwise;
 * the @ sticker needs `joined`; the draft pencil only when nothing is unread.
 */
class HomeChannelRowMarksTest {
    private fun marks(
        unread: Int = 0,
        threads: Int = 0,
        mentioned: Boolean = false,
        joined: Boolean = true,
        muted: Boolean = false,
        draft: Boolean = false,
    ) = homeChannelRowMarks(
        unreadCount = unread,
        followedThreadUnreadCount = threads,
        mentioned = mentioned,
        joined = joined,
        muted = muted,
        hasDraft = draft,
    )

    @Test
    fun mentionWithUnreadShowsStickerAndLoudCount() {
        // fixture #release: unread 5 + mention -> `@` then `5`.
        val result = marks(unread = 5, mentioned = true)
        assertTrue(result.mention)
        assertEquals(5, result.loudCount)
        assertEquals(0, result.loudThreadCount)
        assertTrue(result.loud)
        assertNull(result.quietLabel)
    }

    @Test
    fun followedThreadsAloneLightTheLoudThreadSegment() {
        // fixture #mobile: no channel unread, 2 followed threads with new replies -> `[thread] 2`.
        val result = marks(threads = 2)
        assertEquals(0, result.loudCount)
        assertEquals(2, result.loudThreadCount)
        assertTrue(result.loud)
        assertFalse(result.mention)
    }

    @Test
    fun unreadAndThreadsShareOneLoudCount() {
        val result = marks(unread = 3, threads = 1)
        assertEquals(3, result.loudCount)
        assertEquals(1, result.loudThreadCount)
    }

    @Test
    fun mutedChannelIsQuietAndKeepsTheMentionSticker() {
        val result = marks(unread = 12, threads = 2, mentioned = true, muted = true)
        assertFalse(result.loud)
        assertEquals("12", result.quietLabel)
        assertTrue(result.mention)
    }

    @Test
    fun mutedThreadOnlyUnreadQuietLabelIsTheThreadCount() {
        assertEquals("2", marks(threads = 2, muted = true).quietLabel)
    }

    @Test
    fun notJoinedChannelIsQuietWithoutMention() {
        val result = marks(unread = 4, mentioned = true, joined = false)
        assertFalse(result.loud)
        assertFalse(result.mention)
        assertEquals("4", result.quietLabel)
    }

    @Test
    fun draftOnlyWhenNothingIsUnread() {
        assertTrue(marks(draft = true).showDraft)
        assertFalse(marks(draft = true, unread = 1).showDraft)
        assertFalse(marks(draft = true, threads = 1).showDraft)
    }

    @Test
    fun nothingUnreadDrawsNothing() {
        val result = marks()
        assertFalse(result.loud)
        assertFalse(result.mention)
        assertNull(result.quietLabel)
        assertFalse(result.showDraft)
    }

    @Test
    fun negativeCountsAreClamped() {
        val result = marks(unread = -1, threads = -3)
        assertFalse(result.loud)
        assertNull(result.quietLabel)
    }

    @Test
    fun fixtureRowsMapMentionAndFollowedThreadsFromGeneratedData() {
        val channels = VisualFixtureData.HomeChannelList.CHANNELS.map(::homeChannelListChannel).associateBy { it.name }
        val release = channels.getValue("release")
        assertTrue(release.mentioned)
        assertEquals(5, release.unreadCount)
        val mobile = channels.getValue("mobile")
        assertEquals(2, mobile.followedThreadUnreadCount)
        assertEquals(0, mobile.unreadCount)
        assertEquals("joint", channels.getValue("partners-sync").kind)
        assertEquals("private", channels.getValue("roadmap").channelType)
        assertTrue(channels.getValue("ops-alerts").activityMuted)
    }

    @Test
    fun fixtureDirectMessagesMapPeerKinds() {
        val dms = VisualFixtureData.HomeChannelList.DIRECT_MESSAGES.map(::homeChannelListDirectMessage)
        assertEquals(listOf("agent", "agent", "agent", "human"), dms.map { it.peerType })
        assertEquals(4, dms.single { it.name == "Builder" }.unreadCount)
    }
}
