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

    /** slock #13674: Gravatar is retired; a person is their uploaded avatar, else the placeholder (null). */
    @Test
    fun aPersonIsTheirUploadedAvatarElseThePlaceholder() {
        val hash = "a".repeat(64)
        assertEquals(
            "https://cdn.raft.build/avatars/users/0123456789abcdef.webp",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = "https://cdn.raft.build/avatars/users/0123456789abcdef.webp"
            )
        )
        assertNull(avatarWireValueForAvatarSlot(kind = MemberKind.Human, id = "user-1", avatarWireValue = null))
        assertNull(
            avatarWireValueForRenderedAvatar(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = "https://avatars.githubusercontent.com/u/123456"
            )
        )
        // A hash the wire still carries is never a face.
        val person = MemberProfile(id = MemberId("user-1"), displayName = "Ada", handle = "ada", kind = MemberKind.Human, gravatarHash = hash)
        assertNull(person.avatarWireValueForAvatarSlot())
        assertNull(person.avatarWireValueForRenderedAvatar())
    }

    @Test
    fun appAvatarApiFallbackRejectsUnvalidatedExternalUrls() {
        assertNull(
            "https://example.com/avatar.png"
                .appAvatarApiFallbackUrl("https://api.raft.build/api/")
        )
        assertNull(
            "https://example.com/profile/avatars/agents/0123456789abcdef0123456789abcdef.webp"
                .appAvatarApiFallbackUrl("https://api.raft.build/api/")
        )
        assertNull(
            "https://example.com/avatars/agents/not-a-content-hash.webp"
                .appAvatarApiFallbackUrl("https://api.raft.build/api/")
        )
        assertNull(
            "https://example.com/avatars/agents/0123456789abcdef0123456789abcdef.png"
                .appAvatarApiFallbackUrl("https://api.raft.build/api/")
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
    fun relativeAppAvatarPathsResolveAgainstApiBaseUrl() {
        val relativeAgentPath = "/api/avatars/agents/0123456789abcdef.webp"
        val relativeUserPath = "/avatars/users/0123456789abcdef.webp"

        assertEquals(AvatarReference.Url(relativeAgentPath), AvatarReference.fromWireValue(relativeAgentPath))
        assertEquals(AvatarReference.Url(relativeUserPath), AvatarReference.fromWireValue(relativeUserPath))
        assertNull(AvatarReference.fromWireValue("/api/avatars/../../auth/me"))
        assertNull(AvatarReference.fromWireValue("/api/avatars/"))
        assertNull(AvatarReference.fromWireValue("//evil.example/avatars/agents/0123456789abcdef.webp"))

        assertEquals(
            "https://api.raft.build/api/avatars/agents/0123456789abcdef.webp",
            relativeAgentPath.appAvatarRelativeApiUrl("https://api.raft.build/api/"),
        )
        assertEquals(
            "https://api.raft.build/api/avatars/users/0123456789abcdef.webp",
            relativeUserPath.appAvatarRelativeApiUrl("https://api.raft.build/api/"),
        )
        assertEquals(
            "https://selfhosted.example/api/avatars/agents/0123456789abcdef.webp",
            relativeAgentPath.appAvatarRelativeApiUrl("https://selfhosted.example"),
        )
        assertEquals(
            "https://selfhosted.example/api/avatars/users/0123456789abcdef.webp",
            relativeUserPath.appAvatarRelativeApiUrl("https://selfhosted.example"),
        )
        assertNull("/api/avatars/../../auth/me".appAvatarRelativeApiUrl("https://api.raft.build/api/"))
        assertNull("/avatars/a\\b.webp".appAvatarRelativeApiUrl("https://api.raft.build/api/"))
        assertNull(relativeAgentPath.appAvatarRelativeApiUrl("https://"))
        assertNull(relativeAgentPath.appAvatarRelativeApiUrl(null))
    }
}
