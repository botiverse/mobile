package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RaftAvatarCacheProductionIdentityTest {
    @Test
    fun onlyTypedHumanOrAgentIdentityCanEnterPersistentAvatarRendering() {
        assertEquals(
            RaftAvatarCacheSubject.Member(MemberKind.Human, "human-1"),
            raftMemberAvatarCacheSubject(MemberKind.Human, "human-1")
        )
        assertEquals(
            RaftAvatarCacheSubject.Member(MemberKind.Agent, "agent-1"),
            raftMemberAvatarCacheSubject(MemberKind.Agent, "agent-1")
        )
        assertNull(raftMemberAvatarCacheSubject(MemberKind.System, "system-1"))
        assertNull(raftMemberAvatarCacheSubject(MemberKind.Human, " "))
        assertNull(raftMemberAvatarCacheSubject(null, "label-or-url"))
        assertFailsWith<IllegalArgumentException> {
            RaftAvatarCacheSubject.Member(MemberKind.System, "system-1")
        }
    }

    @Test
    fun persistentMemberIdentityRequiresTheSubjectAndOwnerServerAsOnePair() {
        assertEquals(
            RaftMemberAvatarCacheIdentity(
                subject = RaftAvatarCacheSubject.Member(MemberKind.Human, "human-1"),
                serverId = "server-1"
            ),
            raftMemberAvatarCacheIdentity(MemberKind.Human, " human-1 ", " server-1 ")
        )
        assertNull(raftMemberAvatarCacheIdentity(MemberKind.Agent, "agent-1", null))
        assertNull(raftMemberAvatarCacheIdentity(MemberKind.Agent, "agent-1", " "))
        assertNull(raftMemberAvatarCacheIdentity(MemberKind.System, "system-1", "server-1"))
        assertNull(raftMemberAvatarCacheIdentity(MemberKind.Human, null, "server-1"))
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
            RaftMemberAvatarCacheIdentity(
                RaftAvatarCacheSubject.Member(MemberKind.Agent, "agent-latest"),
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
            RaftMemberAvatarCacheIdentity(
                RaftAvatarCacheSubject.Member(MemberKind.Human, "human-last"),
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
