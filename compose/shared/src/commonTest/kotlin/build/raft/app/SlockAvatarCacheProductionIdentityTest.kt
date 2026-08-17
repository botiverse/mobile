package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.data.SlockAvatarCacheSubject
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SlockAvatarCacheProductionIdentityTest {
    @Test
    fun onlyTypedHumanOrAgentIdentityCanEnterPersistentAvatarRendering() {
        assertEquals(
            SlockAvatarCacheSubject.Member(MemberKind.Human, "human-1"),
            slockMemberAvatarCacheSubject(MemberKind.Human, "human-1")
        )
        assertEquals(
            SlockAvatarCacheSubject.Member(MemberKind.Agent, "agent-1"),
            slockMemberAvatarCacheSubject(MemberKind.Agent, "agent-1")
        )
        assertNull(slockMemberAvatarCacheSubject(MemberKind.System, "system-1"))
        assertNull(slockMemberAvatarCacheSubject(MemberKind.Human, " "))
        assertNull(slockMemberAvatarCacheSubject(null, "label-or-url"))
        assertFailsWith<IllegalArgumentException> {
            SlockAvatarCacheSubject.Member(MemberKind.System, "system-1")
        }
    }

    @Test
    fun persistentMemberIdentityRequiresTheSubjectAndOwnerServerAsOnePair() {
        assertEquals(
            SlockMemberAvatarCacheIdentity(
                subject = SlockAvatarCacheSubject.Member(MemberKind.Human, "human-1"),
                serverId = "server-1"
            ),
            slockMemberAvatarCacheIdentity(MemberKind.Human, " human-1 ", " server-1 ")
        )
        assertNull(slockMemberAvatarCacheIdentity(MemberKind.Agent, "agent-1", null))
        assertNull(slockMemberAvatarCacheIdentity(MemberKind.Agent, "agent-1", " "))
        assertNull(slockMemberAvatarCacheIdentity(MemberKind.System, "system-1", "server-1"))
        assertNull(slockMemberAvatarCacheIdentity(MemberKind.Human, null, "server-1"))
    }

    @Test
    fun searchAndSavedSenderIdentityUsesOneCompleteSlotWithoutCrossSlotGuessing() {
        val latest =
            inboxItem(
                latestKind = MemberKind.Agent,
                latestId = "agent-latest",
                lastKind = MemberKind.Human,
                lastId = "human-last"
            )
        assertEquals(
            SlockMemberAvatarCacheIdentity(
                SlockAvatarCacheSubject.Member(MemberKind.Agent, "agent-latest"),
                "server-1"
            ),
            latest.previewSenderAvatarCacheIdentity("server-1")
        )

        val incompleteLatest =
            inboxItem(
                latestKind = MemberKind.Agent,
                latestId = " ",
                lastKind = MemberKind.Human,
                lastId = "human-last"
            )
        assertEquals(
            SlockMemberAvatarCacheIdentity(
                SlockAvatarCacheSubject.Member(MemberKind.Human, "human-last"),
                "server-1"
            ),
            incompleteLatest.previewSenderAvatarCacheIdentity("server-1")
        )

        val systemLatest =
            inboxItem(
                latestKind = MemberKind.System,
                latestId = "system-latest",
                lastKind = MemberKind.Human,
                lastId = "human-last"
            )
        assertNull(systemLatest.previewSenderAvatarCacheIdentity("server-1"))
        assertNull(latest.previewSenderAvatarCacheIdentity(null))
    }

    private fun inboxItem(
        latestKind: MemberKind?,
        latestId: String?,
        lastKind: MemberKind?,
        lastId: String?
    ): InboxItem =
        InboxItem(
            key = "item-1",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-1"),
            channelName = "general",
            latestActivitySenderKind = latestKind,
            latestActivitySenderId = latestId,
            lastMessageSenderKind = lastKind,
            lastMessageSenderId = lastId
        )
}
