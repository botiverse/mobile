package build.raft.app

import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fallback rules [RaftAppAvatar] applies per kind, web `AvatarSlot` (botiverse/slock
 * `components/ui/AvatarSlot.tsx`, `agent/PixelAvatar.tsx` `AgentAvatar`, `member/PersonAvatar.tsx`):
 * which wire value a face draws, and what it shows without one or when its image fails.
 */
class RaftAvatarKindTest {
    @Test
    fun anAgentAlwaysHasAFaceAndFallsBackToTheRobot() {
        for (none in listOf(null, "", "   ", "gravatar:abc", "https://www.gravatar.com/avatar/abc")) {
            assertEquals("pixel:robot", raftAvatarFaceWireValue(RaftAvatarKind.Agent, none), "agent wire=$none")
        }
        assertEquals("pixel:cat", raftAvatarFaceWireValue(RaftAvatarKind.Agent, " pixel:cat "))
        assertEquals("https://cdn.example/agent.png", raftAvatarFaceWireValue(RaftAvatarKind.Agent, "https://cdn.example/agent.png"))
        // No image, or one that fails: the robot, never the agent's initials.
        assertEquals(RaftAvatarFallbackFace.DefaultPixel, raftAvatarFallbackFace(RaftAvatarKind.Agent, initials = "KI"))
    }

    @Test
    fun aPersonShowsOnlyTheirStoredAvatarElseThePlaceholder() {
        assertEquals(
            "https://app.example/api/avatars/users/abc123.webp",
            raftAvatarFaceWireValue(RaftAvatarKind.Human, "https://app.example/api/avatars/users/abc123.webp"),
        )
        assertEquals("/avatars/users/abc123.webp", raftAvatarFaceWireValue(RaftAvatarKind.Human, "/avatars/users/abc123.webp"))
        // A pixel avatar the person built (web HumanPixelAvatar) is their own face too.
        assertEquals(
            "pixel-human:v1:yellow.2.short.ink.wink.cap.none",
            raftAvatarFaceWireValue(RaftAvatarKind.Human, "pixel-human:v1:yellow.2.short.ink.wink.cap.none"),
        )
        // An old social-provider picture, a pixel key, a retired Gravatar, nothing: no face.
        for (none in listOf(null, "", "https://lh3.googleusercontent.com/a/x", "pixel:robot", "gravatar:abc")) {
            assertEquals(null, raftAvatarFaceWireValue(RaftAvatarKind.Human, none), "human wire=$none")
        }
        // No image, or one that fails: the placeholder person, never initials.
        assertEquals(RaftAvatarFallbackFace.Placeholder, raftAvatarFallbackFace(RaftAvatarKind.Human, initials = "AL"))
    }

    @Test
    fun aLabeledFaceShowsAnyImageElseItsInitials() {
        assertEquals("https://slack.example/u.png", raftAvatarFaceWireValue(RaftAvatarKind.Labeled, "https://slack.example/u.png"))
        assertEquals("pixel:cat", raftAvatarFaceWireValue(RaftAvatarKind.Labeled, "pixel:cat"))
        assertEquals(null, raftAvatarFaceWireValue(RaftAvatarKind.Labeled, " "))
        assertEquals(null, raftAvatarFaceWireValue(RaftAvatarKind.Labeled, "gravatar:abc"))
        assertEquals(RaftAvatarFallbackFace.Initials, raftAvatarFallbackFace(RaftAvatarKind.Labeled, initials = "S"))
        // No letter to take: the placeholder.
        assertEquals(RaftAvatarFallbackFace.Placeholder, raftAvatarFallbackFace(RaftAvatarKind.Labeled, initials = ""))
    }

    @Test
    fun aMemberKindMapsToItsAvatarKind() {
        assertEquals(RaftAvatarKind.Agent, raftAvatarKind(MemberKind.Agent))
        assertEquals(RaftAvatarKind.Human, raftAvatarKind(MemberKind.Human))
        for (other in listOf(MemberKind.External, MemberKind.System, null)) {
            assertEquals(RaftAvatarKind.Labeled, raftAvatarKind(other), "kind=$other")
        }
        assertEquals(RaftColor.Inactive, RaftAvatarKind.Agent.fill)
        assertEquals(RaftColor.AccentLavender, RaftAvatarKind.Human.fill)
    }
}
