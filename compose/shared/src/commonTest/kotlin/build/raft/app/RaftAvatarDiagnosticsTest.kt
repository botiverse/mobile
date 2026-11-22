package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftAvatarDiagnosticsTest {
    @Test
    fun urlDiagnosticNeverContainsRawUrlOrQuery() {
        val rawUrl = "https://cdn.raft.build/avatars/agents/deadbeef.webp?token=secret"
        val source = rawUrl.raftAvatarDiagnosticsSource()

        assertTrue(source.startsWith("url("))
        assertFalse(source.contains("cdn.raft.build"))
        assertFalse(source.contains("secret"))
        assertEquals("none", "pixel:robot".raftAvatarDiagnosticsSource())
    }
}