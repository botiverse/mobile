package build.raft.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarPriorityTest {
    @Test
    fun agentAvatarPriorityKeepsExplicitAvatarThenGravatarThenRobotFallback() {
        val pixelAvatar =
            resolveAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "pixel:tree",
                gravatarHash = "hash"
            )
        assertEquals("pixel:tree", pixelAvatar.displayAvatarWireValue)
        assertEquals("hash", pixelAvatar.gravatarHash)
        assertEquals("pixel:robot", pixelAvatar.fallbackAvatarWireValue)
        assertEquals("pixel:tree", pixelAvatar.effectiveAvatarWireValue)

        assertEquals(
            "pixel:tree",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "pixel:tree",
                gravatarHash = "hash"
            )
        )
        assertEquals(
            "https://cdn.slock.ai/avatar.webp",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "https://cdn.slock.ai/avatar.webp"
            )
        )
        val gravatarAvatar =
            resolveAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "https://www.gravatar.com/avatar/hash?s=52&d=404",
                gravatarHash = "hash"
            )
        assertEquals("https://www.gravatar.com/avatar/hash?s=52&d=404", gravatarAvatar.displayAvatarWireValue)
        assertEquals("hash", gravatarAvatar.gravatarHash)
        assertEquals("pixel:robot", gravatarAvatar.fallbackAvatarWireValue)
        assertEquals("https://www.gravatar.com/avatar/hash?s=52&d=404", gravatarAvatar.effectiveAvatarWireValue)
        assertEquals(
            "https://www.gravatar.com/avatar/hash?s=52&d=404",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "https://www.gravatar.com/avatar/hash?s=52&d=404"
            )
        )

        val gravatarHashOnly =
            resolveAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = null,
                gravatarHash = "hash"
            )
        assertNull(gravatarHashOnly.displayAvatarWireValue)
        assertEquals("hash", gravatarHashOnly.gravatarHash)
        assertEquals("pixel:robot", gravatarHashOnly.fallbackAvatarWireValue)
        assertEquals("https://www.gravatar.com/avatar/hash?s=52&d=404", gravatarHashOnly.effectiveAvatarWireValue)

        assertEquals(
            "pixel:robot",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = null,
                gravatarHash = null
            )
        )
    }

    @Test
    fun humanAvatarPriorityAllowsGravatarFallback() {
        val humanGravatar =
            resolveAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = null,
                gravatarHash = "hash"
            )
        assertNull(humanGravatar.displayAvatarWireValue)
        assertEquals("hash", humanGravatar.gravatarHash)
        assertNull(humanGravatar.fallbackAvatarWireValue)
        assertEquals("https://www.gravatar.com/avatar/hash?s=52&d=404", humanGravatar.effectiveAvatarWireValue)

        assertEquals(
            "https://www.gravatar.com/avatar/hash?s=52&d=404",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = null,
                gravatarHash = "hash"
            )
        )
        assertNull(
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = null,
                gravatarHash = null
            )
        )
    }

    @Test
    fun renderedAvatarProjectionAllowsAgentProfileGravatarBeforeRobotFallback() {
        assertEquals(
            "https://www.gravatar.com/avatar/hash?s=52&d=404",
            avatarWireValueForRenderedAvatar(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = null,
                gravatarHash = "hash"
            )
        )
        assertEquals(
            "pixel:robot",
            avatarWireValueForRenderedAvatar(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = null,
                gravatarHash = null
            )
        )
    }

    @Test
    fun agentProfileMemberProjectionPreservesGravatarFallback() {
        val agent =
            AgentProfile(
                id = MemberId("agent-1"),
                userId = "agent-1",
                displayName = "Agent One",
                handle = "agent-one",
                gravatarHash = "agenthash"
            )

        val member = agent.toMemberProfile()

        assertEquals("agenthash", member.gravatarHash)
        assertEquals(
            "https://www.gravatar.com/avatar/agenthash?s=52&d=404",
            member.avatarWireValueForRenderedAvatar()
        )
    }

}
