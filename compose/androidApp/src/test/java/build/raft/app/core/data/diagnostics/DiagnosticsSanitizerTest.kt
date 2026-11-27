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

    @Test
    fun redactsVoiceDictationCallerContextFields() {
        val sanitized =
            DiagnosticsSanitizer.sanitize(
                """{"draftBefore":"SECRET_ANDROID_DRAFT","contactHints":["SECRET_HINT"]} draft_before=SECRET2 contact_hints=[A,B]"""
            )

        assertFalse(sanitized.contains("SECRET_ANDROID_DRAFT"))
        assertFalse(sanitized.contains("SECRET_HINT"))
        assertFalse(sanitized.contains("SECRET2"))
        assertFalse(sanitized.contains("draftBefore", ignoreCase = true))
        assertFalse(sanitized.contains("draft_before", ignoreCase = true))
        assertFalse(sanitized.contains("contactHints", ignoreCase = true))
        assertFalse(sanitized.contains("contact_hints", ignoreCase = true))
        assertTrue(sanitized.contains("[redacted]"))
    }
}
