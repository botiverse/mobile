package build.raft.app

import build.raft.app.data.SharedActivitySenderProfile
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.agentAvatarFallbackWireValue
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedInboxPanelAvatarIdentityTest {
    private val fixtures = SharedInboxPanelTest()

    @Test
    fun threadUsesLatestActivitySenderIdentity() {
        val thread =
            fixtures.activityItem(key = "thread:agent").copy(
                kind = InboxItemKind.Thread,
                latestActivitySenderKind = MemberKind.Agent,
                latestActivitySenderId = "agent-1",
                latestActivitySenderAvatarWireValue = "pixel:agent-1",
                lastMessageSenderKind = MemberKind.Human,
                lastMessageSenderId = "human-9",
                lastMessageSenderAvatarWireValue = "gravatar:human-9"
            )

        val identity = thread.activitySenderAvatarIdentity()

        assertEquals(MemberKind.Agent, identity.senderKind)
        assertEquals("agent-1", identity.senderId)
        assertEquals("pixel:agent-1", identity.avatarWireValue)
    }

    @Test
    fun channelAndDirectMessageUseLastMessageSenderIdentity() {
        val channel =
            fixtures.activityItem(key = "channel:human").copy(
                kind = InboxItemKind.Channel,
                lastMessageSenderKind = MemberKind.Human,
                lastMessageSenderId = "human-2",
                lastMessageSenderAvatarWireValue = "gravatar:human-2",
                latestActivitySenderKind = MemberKind.Agent,
                latestActivitySenderId = "agent-9",
                latestActivitySenderAvatarWireValue = "pixel:agent-9"
            )
        val dm = channel.copy(key = "dm:human", kind = InboxItemKind.DirectMessage)

        listOf(channel.activitySenderAvatarIdentity(), dm.activitySenderAvatarIdentity()).forEach { identity ->
            assertEquals(MemberKind.Human, identity.senderKind)
            assertEquals("human-2", identity.senderId)
            assertEquals("gravatar:human-2", identity.avatarWireValue)
        }
    }

    @Test
    fun mentionActionIsSystemAppWithoutMemberIdentity() {
        val mention =
            fixtures.activityItem(key = "mention:1").copy(
                kind = InboxItemKind.MentionAction,
                lastMessageSenderKind = MemberKind.Human,
                lastMessageSenderId = "human-3",
                lastMessageSenderAvatarWireValue = "gravatar:human-3"
            )

        val identity = mention.activitySenderAvatarIdentity()

        assertEquals(MemberKind.System, identity.senderKind)
        assertEquals(null, identity.senderId)
        assertEquals(null, identity.avatarWireValue)
    }

    @Test
    fun senderFieldsFallbackButParentAuthorNeverBecomesAvatarIdentity() {
        val threadFallback =
            fixtures.activityItem(key = "thread:fallback").copy(
                kind = InboxItemKind.Thread,
                latestActivitySenderKind = null,
                latestActivitySenderAvatarWireValue = null,
                lastMessageSenderKind = MemberKind.Human,
                lastMessageSenderId = "human-4",
                lastMessageSenderAvatarWireValue = "gravatar:human-4"
            ).activitySenderAvatarIdentity()
        assertEquals(MemberKind.Human, threadFallback.senderKind)
        assertEquals("human-4", threadFallback.senderId)
        assertEquals("gravatar:human-4", threadFallback.avatarWireValue)

        val channelFallback =
            fixtures.activityItem(key = "channel:fallback").copy(
                kind = InboxItemKind.Channel,
                lastMessageSenderKind = null,
                lastMessageSenderAvatarWireValue = null,
                latestActivitySenderKind = MemberKind.Agent,
                latestActivitySenderId = "agent-5",
                latestActivitySenderAvatarWireValue = "pixel:agent-5"
            ).activitySenderAvatarIdentity()
        assertEquals(MemberKind.Agent, channelFallback.senderKind)
        assertEquals("agent-5", channelFallback.senderId)
        assertEquals("pixel:agent-5", channelFallback.avatarWireValue)

        val parentOnly =
            fixtures.activityItem(key = "thread:parent-only").copy(
                kind = InboxItemKind.Thread,
                latestActivitySenderKind = null,
                latestActivitySenderAvatarWireValue = null,
                lastMessageSenderKind = null,
                lastMessageSenderAvatarWireValue = null,
                parentMessageSenderKind = MemberKind.Agent,
                parentMessageSenderId = "parent-author"
            ).activitySenderAvatarIdentity()
        assertEquals(null, parentOnly.senderKind)
        assertEquals(null, parentOnly.senderId)
        assertEquals(null, parentOnly.avatarWireValue)
    }

    @Test
    fun systemAndUnknownKindsRenderAppPlaceholderWithoutMemberIdentity() {
        val system =
            fixtures.activityItem(key = "system:1").copy(
                kind = InboxItemKind.System,
                lastMessageSenderKind = MemberKind.System
            ).activitySenderAvatarIdentity()
        assertEquals(MemberKind.System, system.senderKind)
        assertEquals(null, system.senderId)

        val unknown = fixtures.activityItem(key = "channel:unknown").activitySenderAvatarIdentity()
        assertEquals(null, unknown.senderKind)
        assertEquals(null, unknown.senderId)
        assertEquals(null, unknown.avatarWireValue)
    }

    @Test
    fun senderKindAndIdentityRemainWhenAvatarWireIsMissing() {
        val agent =
            fixtures.activityItem(key = "channel:agent-no-avatar").copy(
                kind = InboxItemKind.Channel,
                lastMessageSenderKind = MemberKind.Agent,
                lastMessageSenderId = "agent-noavatar",
                lastMessageSenderName = "NoAvatar",
                lastMessageSenderAvatarWireValue = null
            ).activitySenderAvatarIdentity()
        assertEquals(MemberKind.Agent, agent.senderKind)
        assertEquals("agent-noavatar", agent.senderId)
        assertEquals(null, agent.avatarWireValue)

        val human =
            fixtures.activityItem(key = "dm:human-no-avatar").copy(
                kind = InboxItemKind.DirectMessage,
                lastMessageSenderKind = MemberKind.Human,
                lastMessageSenderId = "human-noavatar",
                lastMessageSenderName = "NoAvatarHuman",
                lastMessageSenderAvatarWireValue = null
            ).activitySenderAvatarIdentity()
        assertEquals(MemberKind.Human, human.senderKind)
        assertEquals("human-noavatar", human.senderId)
        assertEquals(null, human.avatarWireValue)
    }

    @Test
    fun labelResolvesFromSenderProfilesWithWireNameFallback() {
        val thread =
            fixtures.activityItem(key = "thread:label").copy(
                kind = InboxItemKind.Thread,
                latestActivitySenderKind = MemberKind.Agent,
                latestActivitySenderId = "agent-7",
                latestActivitySenderName = "agent-fallback-name",
                latestActivitySenderAvatarWireValue = "pixel:agent-7"
            )
        val profiles = mapOf("agent-7" to SharedActivitySenderProfile(displayName = "Agent Seven"))

        assertEquals("Agent Seven", thread.activitySenderAvatarIdentity(profiles).label)
        assertEquals("agent-fallback-name", thread.activitySenderAvatarIdentity(emptyMap()).label)
    }

    @Test
    fun preferenceDefaultsToExactNoAvatarLayout() {
        assertEquals(
            ActivitySenderAvatarPresentation(visible = false, contentStartPadding = 12.dp),
            activitySenderAvatarPresentation(enabled = false)
        )
        assertEquals(
            ActivitySenderAvatarPresentation(visible = true, contentStartPadding = 0.dp),
            activitySenderAvatarPresentation(enabled = true)
        )
    }

    @Test
    fun activityFaceTakesTheMemberDirectoryAvatarThenTheItemThenTheKindDefault() {
        val agent = ActivitySenderAvatarIdentity(MemberKind.Agent, avatarWireValue = null, label = "Lagoon", senderId = "agent-1")
        val human = ActivitySenderAvatarIdentity(MemberKind.Human, avatarWireValue = null, label = "Ren", senderId = "human-1")

        // The member directory wins over what the inbox item carries.
        assertEquals(
            "https://cdn.example/agent-1.png",
            activityFaceAvatarWireValue("https://cdn.example/agent-1.png", agent.copy(avatarWireValue = "pixel:old"))
        )
        // No directory avatar: the item's own avatar.
        assertEquals("pixel:old", activityFaceAvatarWireValue(null, agent.copy(avatarWireValue = "pixel:old")))
        // Neither: an agent shows its pixel face, never a letter on the person fill.
        assertEquals(agentAvatarFallbackWireValue("agent-1"), activityFaceAvatarWireValue(null, agent))
        assertEquals(agentAvatarFallbackWireValue(""), activityFaceAvatarWireValue("  ", agent.copy(senderId = null)))
        // A person with nothing to show stays null: the placeholder person.
        assertEquals(null, activityFaceAvatarWireValue(null, human))
        assertEquals("gravatar:abc", activityFaceAvatarWireValue("gravatar:abc", human))
    }
}
