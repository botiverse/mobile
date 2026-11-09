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

    /** A `gravatar:<hash>` value cached or in a snapshot from before #13674 resolves to the placeholder. */
    @Test
    fun aCachedGravatarValueIsThePlaceholder() {
        val cached = "gravatar:" + "a".repeat(64)
        assertNull(cached.appAvatarWireValueOrNull())
        assertNull(avatarWireValueForAvatarSlot(kind = MemberKind.Human, id = "user-1", avatarWireValue = cached))
        assertNull(avatarWireValueForRenderedAvatar(kind = MemberKind.Human, id = "user-1", avatarWireValue = cached))
        // An agent with one wears its default face.
        assertEquals("pixel:robot", avatarWireValueForAvatarSlot(kind = MemberKind.Agent, id = "agent-1", avatarWireValue = cached))
        assertNull(AvatarReference.fromWireValue(cached))
    }

    @Test
    fun humanUploadedAvatarRecognitionMatchesWebPathContract() {
        assertEquals(
            "/api/avatars/users/0123456789abcdef.webp",
            "/api/avatars/users/0123456789abcdef.webp".raftUploadedHumanAvatarWireValueOrNull()
        )
        assertEquals(
            "https://cdn.slock.ai/avatars/users/0123456789abcdef.webp?cache=1",
            "https://cdn.slock.ai/avatars/users/0123456789abcdef.webp?cache=1"
                .raftUploadedHumanAvatarWireValueOrNull()
        )
        assertNull(
            "https://lh3.googleusercontent.com/a/provider-default"
                .raftUploadedHumanAvatarWireValueOrNull()
        )
        assertNull(
            "https://cdn.slock.ai/avatars/agents/0123456789abcdef.webp"
                .raftUploadedHumanAvatarWireValueOrNull()
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
    fun appAvatarCdnUrlMapsToCurrentEnvironmentApiFallback() {
        assertEquals(
            "https://api-aws-staging.botiverse.dev/api/avatars/agents/0123456789abcdef0123456789abcdef.webp",
            "https://cdn.raft.build/avatars/agents/0123456789abcdef0123456789abcdef.webp?cache=1"
                .appAvatarApiFallbackUrl("https://api-aws-staging.botiverse.dev/api/")
        )
        assertEquals(
            "http://10.0.2.2:3000/api/avatars/server/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.webp",
            "https://cdn.example.test/avatars/server/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.webp"
                .appAvatarApiFallbackUrl("http://10.0.2.2:3000/api")
        )
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
