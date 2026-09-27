package build.raft.app

import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.ThreadId
import build.raft.app.model.formatSlockTimestampLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SharedDmsPageTest {

    @Test
    fun testWeChatDmsSorting() {
        val item1 = SlockHomeConversationItem(
            id = "dm-1",
            name = "Alice",
            lastMessageAt = "2026-05-20T10:00:00Z"
        )
        val item2 = SlockHomeConversationItem(
            id = "dm-2",
            name = "Bob",
            lastMessageAt = "2026-05-20T14:30:00Z" // most recent
        )
        val item3 = SlockHomeConversationItem(
            id = "dm-3",
            name = "Charlie",
            lastMessageAt = "2026-05-19T08:00:00Z"
        )
        val item4NoMessage = SlockHomeConversationItem(
            id = "dm-4",
            name = "David",
            createdAt = "2026-05-18T12:00:00Z"
        )
        val item5Empty = SlockHomeConversationItem(
            id = "dm-5",
            name = "Eve"
        )

        val list = listOf(item1, item2, item3, item4NoMessage, item5Empty)
        val sorted = list.sortedForWeChatDms()

        assertEquals(listOf("dm-2", "dm-1", "dm-3", "dm-4", "dm-5"), sorted.map { it.id })
    }

    @Test
    fun testWeChatTimestampFormatting() {
        // Assume today is 2026-05-20 (epochDay for 2026-05-20: 20593)
        // Let's create a SlockTimeContext with localOffsetMinutes = 0
        val epochMillisMay20 = 1779278400000L // 2026-05-20T12:00:00Z
        val todayEpochDay = 20593L // 2026-05-20

        val context = SlockTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = todayEpochDay,
            nowEpochMillis = epochMillisMay20
        )

        // 1. Same day: clock time
        val todayTimestamp = "2026-05-20T14:25:00Z"
        val todayResult = formatSlockTimestampLabel(todayTimestamp, context)
        assertEquals("14:25", todayResult)

        // 2. Yesterday: falls back to "MM-DD HH:MM" (unified label, no "Yesterday")
        val yesterdayTimestamp = "2026-05-19T23:10:00Z"
        val yesterdayResult = formatSlockTimestampLabel(yesterdayTimestamp, context)
        assertEquals("05-19 23:10", yesterdayResult)

        // 3. Earlier this year: "MM-DD HH:MM"
        val earlierThisYear = "2026-03-15T09:00:00Z"
        val earlierResult = formatSlockTimestampLabel(earlierThisYear, context)
        assertEquals("03-15 09:00", earlierResult)

        // 4. Previous year: "YYYY-MM-DD HH:MM"
        val previousYear = "2025-11-20T10:00:00Z"
        val previousResult = formatSlockTimestampLabel(previousYear, context)
        assertEquals("2025-11-20 10:00", previousResult)

        // 5. Blank / null timestamp
        assertEquals("", formatSlockTimestampLabel(null, context))
        assertEquals("", formatSlockTimestampLabel("  ", context))

        // 6. Unparseable string returns original string trimmed
        assertEquals("raw-custom-text", formatSlockTimestampLabel(" raw-custom-text ", context))
    }

    @Test
    fun testWeChatConversationPreview() {
        // 1. lastMessagePreview takes precedence over description
        val item1 = SlockHomeConversationItem(
            name = "Alice",
            lastMessagePreview = "See you tomorrow!",
            description = "Status: busy"
        )
        assertEquals("See you tomorrow!", formatWeChatConversationPreview(item1))

        // 2. Sender prefix when sender name differs from row name
        val item2 = SlockHomeConversationItem(
            name = "Project Sync",
            lastMessagePreview = "PR is up for review",
            lastMessageSenderName = "Bob"
        )
        assertEquals("Bob: PR is up for review", formatWeChatConversationPreview(item2))

        // 3. Sender prefix omitted when sender name equals row name
        val item3 = SlockHomeConversationItem(
            name = "Alice",
            lastMessagePreview = "Let's catch up",
            lastMessageSenderName = "Alice"
        )
        assertEquals("Let's catch up", formatWeChatConversationPreview(item3))

        // 4. When lastMessagePreview is absent, do NOT fall back to description (must be empty)
        val item4 = SlockHomeConversationItem(
            name = "Charlie",
            description = "AI Research Engineer"
        )
        assertEquals("", formatWeChatConversationPreview(item4))

        // 5. Both absent: empty string
        val item5 = SlockHomeConversationItem(name = "David")
        assertEquals("", formatWeChatConversationPreview(item5))
    }

    @Test
    fun testWeChatDmLaunchIdentity() {
        val itemWithKind = SlockHomeConversationItem(
            id = "dm-user-123",
            name = "User 123",
            kind = "dm",
            peerType = "human",
            presenceMemberId = "mem-123"
        )
        val identity1 = itemWithKind.toWeChatDmLaunchIdentity()
        assertNotNull(identity1)
        assertEquals(ThreadId("dm-user-123"), identity1.threadId)
        assertEquals(ChannelId("dm-user-123"), identity1.channelId)
        assertEquals(ConversationKind.DirectMessage, identity1.kind)

        val itemWithoutKind = SlockHomeConversationItem(
            id = "dm-user-456",
            name = "User 456",
            kind = "",
            peerType = "agent",
            presenceMemberId = "mem-456"
        )
        val identity2 = itemWithoutKind.toWeChatDmLaunchIdentity()
        assertNotNull(identity2)
        assertEquals(ThreadId("dm-user-456"), identity2.threadId)
        assertEquals(ConversationKind.DirectMessage, identity2.kind)

        val blankItem = SlockHomeConversationItem(id = "   ")
        assertNull(blankItem.toWeChatDmLaunchIdentity())
    }

    @Test
    fun testDataPipelinePreviewAndAvatarPropagation() {
        val row = HomeDirectMessageRow(
            threadId = ThreadId("dm-alice"),
            channelId = ChannelId("dm-alice"),
            name = "Alice",
            lastMessagePreview = "Hello from the new pipeline",
            lastMessageSenderName = "Alice",
            directMessagePeerAvatarWireValue = "https://example.com/alice.png",
            presenceMemberId = MemberId("member-alice"),
            directMessagePeerType = "human",
            activityNotificationPreference = ChannelActivityNotificationPreference(
                activityMuted = true,
                activityMuteSupported = true
            )
        )

        val projector = SharedHomeConversationRowProjector()
        val projectedItem = projector.project(row)

        assertEquals("dm-alice", projectedItem.id)
        assertEquals("Alice", projectedItem.name)
        assertEquals("Hello from the new pipeline", projectedItem.lastMessagePreview)
        assertEquals("Alice", projectedItem.lastMessageSenderName)
        assertEquals("https://example.com/alice.png", projectedItem.avatarWireValue)
        assertEquals(true, projectedItem.activityMuted)
    }

    @Test
    fun testWeChatDmHeaderTitleAndUnreadCount() {
        val baseTitle = "私信"
        assertEquals("私信", formatWeChatDmHeaderTitle(baseTitle, 0))
        assertEquals("私信", formatWeChatDmHeaderTitle(baseTitle, -1))
        assertEquals("私信 (1)", formatWeChatDmHeaderTitle(baseTitle, 1))
        assertEquals("私信 (32)", formatWeChatDmHeaderTitle(baseTitle, 32))

        val items = listOf(
            SlockHomeConversationItem(id = "1", unreadCount = 5, activityMuted = false),
            SlockHomeConversationItem(id = "2", unreadCount = 10, activityMuted = true), // muted should be excluded
            SlockHomeConversationItem(id = "3", unreadCount = 27, activityMuted = false),
            SlockHomeConversationItem(id = "4", unreadCount = -5, activityMuted = false)
        )
        // 5 + 27 = 32
        assertEquals(32, totalWeChatDmUnreadCount(items))
        assertEquals("私信 (32)", formatWeChatDmHeaderTitle(baseTitle, totalWeChatDmUnreadCount(items)))

        val noUnreadItems = listOf(
            SlockHomeConversationItem(id = "1", unreadCount = 0, activityMuted = false),
            SlockHomeConversationItem(id = "2", unreadCount = 4, activityMuted = true) // only muted unread
        )
        assertEquals(0, totalWeChatDmUnreadCount(noUnreadItems))
        assertEquals("私信", formatWeChatDmHeaderTitle(baseTitle, totalWeChatDmUnreadCount(noUnreadItems)))
    }

    @Test
    fun testResolveEffectiveDmsTimeContextWithAnchoredExplicitContext() {
        val explicit = SlockTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = 20593L,
            nowEpochMillis = 1779278400000L
        )
        val inherited = SlockTimeContext(
            localOffsetMinutes = 480,
            todayEpochDay = 20594L,
            nowEpochMillis = 1779364800000L
        )
        val resolved = resolveEffectiveDmsTimeContext(explicit, inherited)
        assertEquals(explicit, resolved)
    }

    @Test
    fun testResolveEffectiveDmsTimeContextMergesLiveInheritedWhenExplicitLacksAnchors() {
        // Regression test for Task #402:
        // When explicit context (such as threadTimeContext) only has capabilities
        // (localOffsetMinutes, timeFormat) without todayEpochDay or nowEpochMillis,
        // it must NOT shadow the live inherited context from LocalSlockTimeContextProvider.
        val staticExplicit = SlockTimeContext(
            localOffsetMinutes = 480,
            todayEpochDay = null,
            nowEpochMillis = null,
            timeFormat = "12h"
        )
        val liveInherited = SlockTimeContext(
            localOffsetMinutes = 480,
            todayEpochDay = 20593L, // 2026-05-20
            nowEpochMillis = 1779278400000L,
            timeFormat = "24h"
        )

        // Raw static context alone would fail and return raw ISO timestamp
        val rawTimestamp = "2026-05-20T14:25:00+08:00"
        assertEquals(rawTimestamp, formatSlockTimestampLabel(rawTimestamp, staticExplicit))

        // When resolved through resolveEffectiveDmsTimeContext, it takes live anchors and formats properly
        val resolved = resolveEffectiveDmsTimeContext(staticExplicit, liveInherited)
        assertEquals(20593L, resolved.todayEpochDay)
        assertEquals(1779278400000L, resolved.nowEpochMillis)
        assertEquals("12h", resolved.timeFormat)
        assertEquals(480, resolved.localOffsetMinutes)

        val formatted = formatSlockTimestampLabel(rawTimestamp, resolved)
        assertEquals("02:25 PM", formatted)
    }

    @Test
    fun testResolveEffectiveDmsTimeContextFallbackWhenBothLackAnchors() {
        // When both explicit and inherited lack todayEpochDay/nowEpochMillis,
        // it must anchor to the current system clock and compute todayEpochDay
        val staticContext = SlockTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = null,
            nowEpochMillis = null
        )
        val resolved = resolveEffectiveDmsTimeContext(staticContext, null)
        assertNotNull(resolved.todayEpochDay)
        assertNotNull(resolved.nowEpochMillis)
        assertEquals(0, resolved.localOffsetMinutes)

        // Null explicit and inherited context also safely produces an anchored context
        val resolvedFromNull = resolveEffectiveDmsTimeContext(null, null)
        assertNotNull(resolvedFromNull.todayEpochDay)
        assertNotNull(resolvedFromNull.nowEpochMillis)
    }
}
