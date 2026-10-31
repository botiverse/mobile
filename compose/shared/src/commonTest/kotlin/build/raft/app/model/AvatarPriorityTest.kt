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
    fun humanAvatarUsesValidatedServerGravatarHashAfterUploadedAvatar() {
        val hash = "A".repeat(64)
        assertEquals(
            "gravatar:${hash.lowercase()}",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = null,
                gravatarHash = hash
            )
        )
        assertEquals(
            "https://cdn.raft.build/avatars/users/0123456789abcdef.webp",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = "https://cdn.raft.build/avatars/users/0123456789abcdef.webp",
                gravatarHash = hash
            )
        )
        assertEquals(
            "gravatar:${hash.lowercase()}",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = "https://lh3.googleusercontent.com/a/provider-default",
                gravatarHash = hash
            )
        )
        assertEquals(
            "gravatar:${hash.lowercase()}",
            avatarWireValueForRenderedAvatar(
                kind = MemberKind.Human,
                id = "user-1",
                avatarWireValue = "https://avatars.githubusercontent.com/u/123456",
                gravatarHash = hash
            )
        )
        assertEquals(
            "pixel:robot",
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Agent,
                id = "agent-1",
                avatarWireValue = null,
                gravatarHash = hash
            )
        )
        assertNull("not-a-hash".humanGravatarAvatarWireValueOrNull(MemberKind.Human))
        assertEquals(
            "https://www.gravatar.com/avatar/${hash.lowercase()}?s=160&d=404",
            "gravatar:${hash.lowercase()}".gravatarAvatarHttpUrlOrNull()
        )
        assertNull("https://www.gravatar.com/avatar/${hash.lowercase()}".gravatarAvatarHttpUrlOrNull())
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
