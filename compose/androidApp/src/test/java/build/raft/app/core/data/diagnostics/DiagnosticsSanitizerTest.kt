package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSanitizerTest {
    @Test
    fun redactsSensitiveFields() {
        val sanitized =
            DiagnosticsSanitizer.sanitize(
                "authorization: Bearer eyJ.header.payload accessToken=secret password=hunter2 user=artin@slock.ai"
            )

        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("artin@slock.ai"))
        assertTrue(sanitized.contains("[redacted]"))
    }
}
