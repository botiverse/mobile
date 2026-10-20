package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadCanonicalAccessAuthorityTest {
    @Test
    fun stalePreJoinHomeCandidateCannotOverrideCanonicalChannelAccess() {
        val staleCandidate =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "new-channel",
                joined = false,
                permissions = ConversationPermissions(canRead = true, canSend = false),
            ).toConversationAccessCandidate()
        val canonical =
            RaftThreadScreenPayload(
                instanceId = "channel-1",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                joined = true,
                canSend = true,
                composer = RaftThreadComposerPayload(enabled = true),
            )

        val beforeCanonical =
            canonical.withInitialHomeConversationAccessFallbackUnlessCanonical(
                canonicalAccessResolved = false,
                candidate = staleCandidate,
            )
        val afterCanonical =
            canonical.withInitialHomeConversationAccessFallbackUnlessCanonical(
                canonicalAccessResolved = true,
                candidate = staleCandidate,
            )

        assertFalse(beforeCanonical.joined)
        assertFalse(beforeCanonical.composer.enabled)
        assertTrue(afterCanonical.joined)
        assertTrue(afterCanonical.canSend)
        assertTrue(afterCanonical.composer.enabled)
    }
}
