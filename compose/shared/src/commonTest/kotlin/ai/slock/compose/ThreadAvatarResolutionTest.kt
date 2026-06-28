package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadAvatarResolutionTest {
    @Test
    fun agentThreadAvatarUsesUploadedOrGravatarBeforeRobotFallback() {
        val uploadedAgent =
            KuiklyThreadMessageItem(
                id = "m1",
                authorId = "agent-1",
                authorName = "Build Bot",
                authorKind = "agent",
                avatarWireValue = "https://cdn.slock.ai/avatars/agents/agent-1.webp",
                gravatarHash = "agenthash"
            )
        assertEquals(
            "https://cdn.slock.ai/avatars/agents/agent-1.webp",
            uploadedAgent.resolvedThreadAvatarWireValue()
        )
        assertEquals(
            "https://www.gravatar.com/avatar/agenthash?s=52&d=404",
            uploadedAgent.threadAvatarGravatarUrl()
        )

        val gravatarAgent =
            uploadedAgent.copy(
                id = "m2",
                avatarWireValue = null,
                gravatarHash = "agenthash"
            )
        assertEquals(
            "https://www.gravatar.com/avatar/agenthash?s=52&d=404",
            gravatarAgent.resolvedThreadAvatarWireValue()
        )

        val fallbackAgent =
            uploadedAgent.copy(
                id = "m3",
                avatarWireValue = null,
                gravatarHash = null
            )
        assertEquals("pixel:robot", fallbackAgent.resolvedThreadAvatarWireValue())
    }
}
