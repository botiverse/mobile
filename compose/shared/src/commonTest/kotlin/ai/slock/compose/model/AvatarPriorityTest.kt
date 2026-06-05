package ai.slock.compose.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarPriorityTest {
    @Test
    fun agentAvatarPriorityRejectsGravatarAndKeepsPixelOrUploadedAvatar() {
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
        assertEquals(
            "random:agent-1",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = "https://www.gravatar.com/avatar/hash?s=52&d=404"
            )
        )
    }

    @Test
    fun humanAvatarPriorityAllowsGravatarFallback() {
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
}
