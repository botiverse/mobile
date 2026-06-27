package ai.slock.compose.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarPriorityTest {
    @Test
    fun agentAvatarPriorityRejectsGravatarAndKeepsPixelOrUploadedAvatar() {
        val pixelAvatar =
            resolveAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "pixel:tree",
                gravatarHash = "hash"
            )
        assertEquals("pixel:tree", pixelAvatar.displayAvatarWireValue)
        assertNull(pixelAvatar.gravatarHash)
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
        assertNull(gravatarAvatar.displayAvatarWireValue)
        assertNull(gravatarAvatar.gravatarHash)
        assertEquals("pixel:robot", gravatarAvatar.fallbackAvatarWireValue)
        assertEquals("pixel:robot", gravatarAvatar.effectiveAvatarWireValue)
        assertEquals(
            "pixel:robot",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "https://www.gravatar.com/avatar/hash?s=52&d=404"
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
    fun mergedAgentAvatarRejectsCurrentAndIncomingGravatarCandidates() {
        val mergedFromProfile =
            resolveMergedAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                currentAvatarWireValue = "https://www.gravatar.com/avatar/current?s=52&d=404",
                incomingAvatarWireValue = "pixel:tree"
            )
        assertEquals("pixel:tree", mergedFromProfile.effectiveAvatarWireValue)
        assertNull(mergedFromProfile.gravatarHash)

        val mergedFromHomeGravatar =
            resolveMergedAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                currentAvatarWireValue = "pixel:robot",
                incomingAvatarWireValue = "https://www.gravatar.com/avatar/home?s=52&d=404"
            )
        assertEquals("pixel:robot", mergedFromHomeGravatar.effectiveAvatarWireValue)
        assertNull(mergedFromHomeGravatar.gravatarHash)
    }

    @Test
    fun mergedAvatarKeepsSpecificAgentAvatarAndHumanGravatarFallback() {
        val mergedSpecificAgent =
            resolveMergedAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                currentAvatarWireValue = "https://cdn.slock.ai/agent.webp",
                incomingAvatarWireValue = "pixel:tree"
            )
        assertEquals("https://cdn.slock.ai/agent.webp", mergedSpecificAgent.effectiveAvatarWireValue)
        assertNull(mergedSpecificAgent.gravatarHash)

        val mergedHuman =
            resolveMergedAvatarSlot(
                kind = MemberKind.Human,
                id = "human-1",
                currentAvatarWireValue = null,
                incomingGravatarHash = "human-hash"
            )
        assertEquals("https://www.gravatar.com/avatar/human-hash?s=52&d=404", mergedHuman.effectiveAvatarWireValue)
        assertEquals("human-hash", mergedHuman.gravatarHash)
    }

}
