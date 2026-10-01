package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockAvatarDiagnosticsTest {
    @Test
    fun diagnosticsExposeOnlyShortSessionScopedHashes() {
        val rawId = "message-personally-identifying-value"
        val first = rawId.slockAvatarDiagnosticsHash()
        val second = rawId.slockAvatarDiagnosticsHash()

        assertEquals(first, second)
        assertEquals(12, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(first.contains(rawId))
    }

    @Test
    fun urlDiagnosticNeverContainsRawUrlOrQuery() {
        val rawUrl = "https://cdn.raft.build/avatars/agents/deadbeef.webp?token=secret"
        val source = rawUrl.slockAvatarDiagnosticsSource()

        assertTrue(source.startsWith("url("))
        assertFalse(source.contains("cdn.raft.build"))
        assertFalse(source.contains("secret"))
        assertEquals("none", "pixel:robot".slockAvatarDiagnosticsSource())
    }

    @Test
    fun urlShapeBucketsHostAgainstApiHostWithoutLeakingUrls() {
        val api = "https://api.raft.build"
        assertEquals(
            "shape=host=api,path=avatars",
            "https://api.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp?sig=x"
                .slockAvatarDiagnosticsUrlShape(api)
        )
        assertEquals(
            "shape=host=other,path=avatars",
            "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
                .slockAvatarDiagnosticsUrlShape(api)
        )
        assertEquals(
            "shape=host=other,path=gravatar",
            "https://www.gravatar.com/avatar/0123456789abcdef0123456789abcdef?s=160&d=404"
                .slockAvatarDiagnosticsUrlShape(api)
        )
        assertEquals(
            "shape=host=other,path=other",
            "https://files.example.com/some/deep/path.png"
                .slockAvatarDiagnosticsUrlShape(api)
        )
        // No raw host, path tail, or token may appear in the shape string.
        val shape = "https://avatars.other-env.example/avatars/agents/feedface.webp?t=sek"
            .slockAvatarDiagnosticsUrlShape(api)
        assertFalse(shape.contains("other-env"))
        assertFalse(shape.contains("feedface"))
        assertFalse(shape.contains("sek"))
        // Non-http values degrade quietly.
        assertEquals("shape=none", "pixel:robot".slockAvatarDiagnosticsUrlShape(api))
        assertEquals("shape=none", null.slockAvatarDiagnosticsUrlShape(api))
        // No API host configured -> host kind is unknown, never guessed.
        assertEquals(
            "shape=host=unknown_api,path=avatars",
            "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
                .slockAvatarDiagnosticsUrlShape(null)
        )
    }
}