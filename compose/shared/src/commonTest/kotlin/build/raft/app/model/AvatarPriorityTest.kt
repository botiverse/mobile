package build.raft.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarPriorityTest {
    @Test
    fun agentAvatarPriorityUsesAppAvatarThenRobotFallback() {
        val pixelAvatar =
            resolveAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "pixel:tree"
            )
        assertEquals("pixel:tree", pixelAvatar.displayAvatarWireValue)
        assertEquals("pixel:robot", pixelAvatar.fallbackAvatarWireValue)
        assertEquals("pixel:tree", pixelAvatar.effectiveAvatarWireValue)

        assertEquals(
            "https://cdn.raft.build/avatars/server/0123456789abcdef0123456789abcdef.webp",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "https://cdn.raft.build/avatars/server/0123456789abcdef0123456789abcdef.webp"
            )
        )

        val legacyGravatar =
            resolveAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "https://www.gravatar.com/avatar/hash?s=52&d=404"
            )
        assertNull(legacyGravatar.displayAvatarWireValue)
        assertEquals("pixel:robot", legacyGravatar.effectiveAvatarWireValue)
    }

    @Test
    fun humanAvatarPriorityRejectsLegacyGravatarUrl() {
        assertNull(
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = "https://secure.gravatar.com/avatar/hash"
            )
        )
    }

    @Test
    fun avatarReferenceRejectsLegacyGravatarUrls() {
        assertNull(AvatarReference.fromWireValue("https://www.gravatar.com/avatar/hash?s=52&d=404"))
        assertEquals(
            AvatarReference.Url("https://cdn.raft.build/avatars/server/hash.webp"),
            AvatarReference.fromWireValue("https://cdn.raft.build/avatars/server/hash.webp")
        )
    }

    @Test
    fun randomAvatarReferenceUsesReactCanonicalPixelWireValue() {
        val random = AvatarReference.Random("agent-1")

        assertEquals("pixel:random:agent-1", random.wireValue)
        assertEquals(random, AvatarReference.fromWireValue("pixel:random:agent-1"))
        assertNull(AvatarReference.fromWireValue("random:agent-1"))
        assertNull(AvatarReference.fromWireValue("pixel:random:"))
        assertEquals(
            AvatarReference.Pixel("tree"),
            AvatarReference.fromWireValue("pixel:tree")
        )
    }

    @Test
    fun agentWithoutAppAvatarUsesRobotFallback() {
        val member =
            AgentProfile(
                id = MemberId("agent-1"),
                userId = "agent-1",
                displayName = "Agent One",
                handle = "agent-one"
            ).toMemberProfile()

        assertEquals("pixel:robot", member.avatarWireValueForRenderedAvatar())
    }
}
