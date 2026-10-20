package build.raft.app.data

import build.raft.app.toKuiklyJson
import build.raft.app.ConversationOpenSource
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftHomeConversationItem
import build.raft.app.RaftHomePinnedMemberItem
import build.raft.app.RaftHomePinnedRow
import build.raft.app.appShellDesktopShortcutNavigationIntent
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RaftDesktopShortcutTest {

    private fun homeSnapshot(
        channels: List<HomeChannelRow>,
        directMessages: List<HomeDirectMessageRow>,
        sidebarOrder: SettingsServerSidebarOrderSnapshot?
    ) = HomeSnapshot(
        server = ServerSummary(id = ServerId("srv-1"), name = "Acme Corp"),
        currentMember = MemberProfile(
            id = MemberId("self-1"),
            displayName = "Self",
            handle = "self",
            kind = MemberKind.Human
        ),
        channels = channels,
        directMessages = directMessages,
        unreadTotal = 0,
        sidebarOrder = sidebarOrder
    )

    @Test
    fun extractTopPinnedDesktopShortcutsFromSnapshot_ordersAndFormatsChannelsAndDms() {
        val channel1 = HomeChannelRow(
            threadId = ThreadId("t-c1"),
            channelId = ChannelId("c1"),
            name = "general"
        )
        val channel2 = HomeChannelRow(
            threadId = ThreadId("t-c2"),
            channelId = ChannelId("c2"),
            name = "#dev"
        )
        val dm1 = HomeDirectMessageRow(
            threadId = ThreadId("dm-user1"),
            channelId = ChannelId("dm-user1"),
            name = "Alice",
            presenceMemberId = MemberId("user-1")
        )
        val dm2 = HomeDirectMessageRow(
            threadId = ThreadId("dm-user2"),
            channelId = ChannelId("dm-user2"),
            name = "Bob",
            presenceMemberId = MemberId("user-2")
        )
        val snapshot = homeSnapshot(
            channels = listOf(channel1, channel2),
            directMessages = listOf(dm1, dm2),
            sidebarOrder = SettingsServerSidebarOrderSnapshot(
                pinned = listOf(
                    SettingsServerSidebarPinnedItem(kind = "channel", id = "c1"),
                    SettingsServerSidebarPinnedItem(kind = "agent", id = "user-1"),
                    SettingsServerSidebarPinnedItem(kind = "channel", id = "c2"),
                    SettingsServerSidebarPinnedItem(kind = "human", id = "user-2")
                )
            )
        )

        val shortcuts = extractTopPinnedDesktopShortcuts(snapshot, maxCount = 4)
        assertEquals(4, shortcuts.size)

        assertEquals("shortcut_srv-1_c1", shortcuts[0].id)
        assertEquals("srv-1", shortcuts[0].serverId)
        assertEquals("c1", shortcuts[0].targetId)
        assertEquals(RaftDesktopShortcutContract.TargetKindChannel, shortcuts[0].targetKind)
        assertEquals("#general", shortcuts[0].title)
        assertEquals("Acme Corp", shortcuts[0].subtitle)

        assertEquals("shortcut_srv-1_dm-user1", shortcuts[1].id)
        assertEquals("srv-1", shortcuts[1].serverId)
        assertEquals("dm-user1", shortcuts[1].targetId)
        assertEquals(RaftDesktopShortcutContract.TargetKindDm, shortcuts[1].targetKind)
        assertEquals("Alice", shortcuts[1].title) // DMs do not prefix with #
        assertEquals("Acme Corp", shortcuts[1].subtitle)

        assertEquals("shortcut_srv-1_c2", shortcuts[2].id)
        assertEquals("#dev", shortcuts[2].title) // Keeps existing # without double ##

        assertEquals("shortcut_srv-1_dm-user2", shortcuts[3].id)
        assertEquals("Bob", shortcuts[3].title)
    }

    @Test
    fun extractTopPinnedDesktopShortcutsFromSnapshot_honorsMaxCount() {
        val channels = (1..6).map { idx ->
            HomeChannelRow(
                threadId = ThreadId("t-c$idx"),
                channelId = ChannelId("c$idx"),
                name = "chan-$idx"
            )
        }
        val snapshot = homeSnapshot(
            channels = channels,
            directMessages = emptyList(),
            sidebarOrder = SettingsServerSidebarOrderSnapshot(
                pinned = (1..6).map { SettingsServerSidebarPinnedItem(kind = "channel", id = "c$it") }
            )
        )

        val shortcuts = extractTopPinnedDesktopShortcuts(snapshot, maxCount = 4)
        assertEquals(4, shortcuts.size)
        assertEquals(listOf("c1", "c2", "c3", "c4"), shortcuts.map { it.targetId })
    }

    @Test
    fun extractTopPinnedDesktopShortcutsFromPinnedRows_skipsMembersWithoutConversation() {
        val channelConversation = RaftHomeConversationItem(
            id = "c1",
            name = "general",
            kind = "channel",
            channelType = "channel"
        )
        val dmConversation = RaftHomeConversationItem(
            id = "dm1",
            name = "Charlie",
            kind = "dm",
            channelType = "dm"
        )
        val standaloneMember = RaftHomePinnedMemberItem(
            identity = MemberDetailIdentity(MemberId("agent-orphan"), MemberKind.Agent),
            name = "Orphan Agent",
            description = "@orphan",
            avatarLabel = "O"
        )

        val pinnedRows = listOf(
            RaftHomePinnedRow(key = "channel:c1", conversation = channelConversation),
            RaftHomePinnedRow(key = "agent:orphan", member = standaloneMember),
            RaftHomePinnedRow(key = "dm:dm1", conversation = dmConversation)
        )

        val shortcuts = extractTopPinnedDesktopShortcuts(
            serverId = "srv-1",
            serverName = "Acme",
            pinnedRows = pinnedRows,
            maxCount = 4
        )

        assertEquals(2, shortcuts.size)
        assertEquals("c1", shortcuts[0].targetId)
        assertEquals("#general", shortcuts[0].title)
        assertEquals("dm1", shortcuts[1].targetId)
        assertEquals("Charlie", shortcuts[1].title)
    }

    @Test
    fun contractSerializationAndDeserialization_roundTripsCorrectly() {
        val items = listOf(
            RaftDesktopShortcutItem(
                id = "shortcut_srv1_c1",
                serverId = "srv1",
                targetId = "c1",
                targetKind = RaftDesktopShortcutContract.TargetKindChannel,
                title = "#general",
                subtitle = "Acme",
                avatarLabel = null
            ),
            RaftDesktopShortcutItem(
                id = "shortcut_srv1_dm1",
                serverId = "srv1",
                targetId = "dm1",
                targetKind = RaftDesktopShortcutContract.TargetKindDm,
                title = "Alice",
                subtitle = "Acme",
                avatarLabel = "A"
            )
        )

        val serialized = RaftDesktopShortcutContract.serializeList(items)
        assertTrue(serialized.contains("shortcut_srv1_c1"))
        assertTrue(serialized.contains("#general"))
        assertTrue(serialized.contains("Alice"))

        val deserialized = RaftDesktopShortcutContract.deserializeList(serialized)
        assertEquals(items, deserialized)

        assertEquals(emptyList(), RaftDesktopShortcutContract.deserializeList(""))
        assertEquals(emptyList(), RaftDesktopShortcutContract.deserializeList("invalid json"))
    }

    @Test
    fun appShellDesktopShortcutNavigationIntent_reconstructsNavigationWithDistinctLaunchKeys() {
        val params1 = buildJsonObject {
            put(RaftDesktopShortcutContract.KeyServerId, "srv-1")
            put(RaftDesktopShortcutContract.KeyTargetId, "chan-1")
            put(RaftDesktopShortcutContract.KeyTargetKind, "channel")
            put(RaftDesktopShortcutContract.KeyLaunchId, "launch-1001")
        }
        val intent1 = params1.toKuiklyJson().appShellDesktopShortcutNavigationIntent(fallbackServerId = null)
        assertNotNull(intent1)
        assertEquals("srv-1", intent1.serverId)
        assertEquals("chan-1", intent1.sourceIdentity.channelId)
        assertEquals(ConversationKind.Channel, intent1.sourceIdentity.conversationKind)
        assertEquals(ConversationOpenSource.AppShortcut, intent1.sourceIdentity.source)
        assertNull(intent1.sourceIdentity.messageId) // Resolves anchor message id to null
        assertEquals("launch-1001", intent1.launchId)

        val params2 = buildJsonObject {
            put(RaftDesktopShortcutContract.KeyServerId, "srv-1")
            put(RaftDesktopShortcutContract.KeyTargetId, "chan-1")
            put(RaftDesktopShortcutContract.KeyTargetKind, "channel")
            put(RaftDesktopShortcutContract.KeyLaunchId, "launch-1002")
        }
        val intent2 = params2.toKuiklyJson().appShellDesktopShortcutNavigationIntent(fallbackServerId = null)
        assertNotNull(intent2)

        // Launch IDs make the deduplication keys unique so consecutive taps on the shortcut open
        assertTrue(intent1.key != intent2.key)
        assertTrue(intent1.key.contains("launch-1001"))
        assertTrue(intent2.key.contains("launch-1002"))
    }

    @Test
    fun appShellDesktopShortcutNavigationIntent_fallsBackToServerIdWhenMissing() {
        val params = buildJsonObject {
            put(RaftDesktopShortcutContract.KeyTargetId, "chan-1")
            put(RaftDesktopShortcutContract.KeyTargetKind, "channel")
        }
        val intent = params.toKuiklyJson().appShellDesktopShortcutNavigationIntent(fallbackServerId = "fallback-srv")
        assertNotNull(intent)
        assertEquals("fallback-srv", intent.serverId)
    }

    @Test
    fun appShellDesktopShortcutNavigationIntent_returnsNullWhenTargetIdIsMissing() {
        val params = buildJsonObject {
            put(RaftDesktopShortcutContract.KeyServerId, "srv-1")
        }
        assertNull(params.toKuiklyJson().appShellDesktopShortcutNavigationIntent(fallbackServerId = null))
    }
}
