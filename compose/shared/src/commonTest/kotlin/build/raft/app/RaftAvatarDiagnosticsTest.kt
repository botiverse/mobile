package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftAvatarDiagnosticsTest {
    @Test
    fun diagnosticsExposeOnlyShortSessionScopedHashes() {
        val rawId = "message-personally-identifying-value"
        val first = rawId.raftAvatarDiagnosticsHash()
        val second = rawId.raftAvatarDiagnosticsHash()

        assertEquals(first, second)
        assertEquals(12, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(first.contains(rawId))
    }

    @Test
    fun urlDiagnosticNeverContainsRawUrlOrQuery() {
        val rawUrl = "https://cdn.raft.build/avatars/agents/deadbeef.webp?token=secret"
        val source = rawUrl.raftAvatarDiagnosticsSource()

        assertTrue(source.startsWith("url("))
        assertFalse(source.contains("cdn.raft.build"))
        assertFalse(source.contains("secret"))
        assertEquals("none", "pixel:robot".raftAvatarDiagnosticsSource())
    }

    @Test
    fun urlShapeBucketsHostAgainstApiHostWithoutLeakingUrls() {
        val api = "https://api.raft.build"
        assertEquals(
            "shape=host=api,path=avatars",
            "https://api.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp?sig=x"
                .raftAvatarDiagnosticsUrlShape(api)
        )
        assertEquals(
            "shape=host=other,path=avatars",
            "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
                .raftAvatarDiagnosticsUrlShape(api)
        )
        assertEquals(
            "shape=host=other,path=gravatar",
            "https://www.gravatar.com/avatar/0123456789abcdef0123456789abcdef?s=160&d=404"
                .raftAvatarDiagnosticsUrlShape(api)
        )
        assertEquals(
            "shape=host=other,path=other",
            "https://files.example.com/some/deep/path.png"
                .raftAvatarDiagnosticsUrlShape(api)
        )
        // No raw host, path tail, or token may appear in the shape string.
        val shape = "https://avatars.other-env.example/avatars/agents/feedface.webp?t=sek"
            .raftAvatarDiagnosticsUrlShape(api)
        assertFalse(shape.contains("other-env"))
        assertFalse(shape.contains("feedface"))
        assertFalse(shape.contains("sek"))
        // Non-http values degrade quietly.
        assertEquals("shape=none", "pixel:robot".raftAvatarDiagnosticsUrlShape(api))
        assertEquals("shape=none", null.raftAvatarDiagnosticsUrlShape(api))
        // No API host configured -> host kind is unknown, never guessed.
        assertEquals(
            "shape=host=unknown_api,path=avatars",
            "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
                .raftAvatarDiagnosticsUrlShape(null)
        )
    }
}