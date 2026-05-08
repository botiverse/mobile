package ai.slock.android.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizedDiagnosticsLogBufferTest {
    @Test
    fun redactsSensitiveFieldsBeforeExport() {
        SanitizedDiagnosticsLogBuffer.clear()

        SanitizedDiagnosticsLogBuffer.record(
            tag = "Auth",
            message = "authorization: Bearer eyJ.header.payload accessToken=secret password=hunter2 user=artin@slock.ai"
        )
        val exported = SanitizedDiagnosticsLogBuffer.exportText()

        assertFalse(exported.contains("secret"))
        assertFalse(exported.contains("hunter2"))
        assertFalse(exported.contains("artin@slock.ai"))
        assertTrue(exported.contains("[redacted]"))
    }

    @Test
    fun keepsOnlyRecentEntries() {
        SanitizedDiagnosticsLogBuffer.clear()

        repeat(90) { index ->
            SanitizedDiagnosticsLogBuffer.record("Test", "entry=$index", timestampMillis = index.toLong())
        }

        val recent = SanitizedDiagnosticsLogBuffer.recent()
        assertEquals(80, recent.size)
        assertEquals("entry=10", recent.first().message)
        assertEquals("entry=89", recent.last().message)
    }
}
