package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedPerformanceTraceRingTest {
    @Test
    fun threadMetadataUsesPhaseAllowlistAndSanitizer() {
        val metadata =
            sanitizeThreadPerformanceMetadata(
                phase = "composer_send",
                metadata =
                    mapOf(
                        "result" to "failed user@example.com Bearer secret-token",
                        "attempt" to "2",
                        "localMessageId" to "local-sensitive-id",
                        "parentMessageId" to "parent-sensitive-id",
                        "body" to "private body"
                    )
            )

        assertTrue(metadata.contains("attempt:2"))
        assertTrue(metadata.contains("[redacted]"))
        assertFalse(metadata.contains("secret-token"))
        assertFalse(metadata.contains("local-sensitive-id"))
        assertFalse(metadata.contains("parent-sensitive-id"))
        assertFalse(metadata.contains("private body"))
    }
}
