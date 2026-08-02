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
}
