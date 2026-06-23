package ai.slock.android.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockDiagnosticsLoggerTest {
    @Test
    fun warningRoutesThroughSanitizedBuffer() {
        SanitizedDiagnosticsLogBuffer.clear()

        SlockDiagnosticsLogger.w(
            tag = "Diagnostics",
            message = "authorization: Bearer secret accessToken=token password=hunter2"
        )

        val recent = SanitizedDiagnosticsLogBuffer.recent()
        assertEquals(1, recent.size)
        assertEquals("Diagnostics", recent.single().tag)
        assertFalse(recent.single().message.contains("secret"))
        assertFalse(recent.single().message.contains("hunter2"))
        assertTrue(recent.single().message.contains("[redacted]"))
    }

    @Test
    fun debugBuildKeepsVerboseLogsWhenProfilerToggleTurnsOff() {
        SanitizedDiagnosticsLogBuffer.clear()

        SlockDiagnosticsLogger.setVerboseLoggingEnabled(false)
        SlockDiagnosticsLogger.record("Diagnostics", "debug detail")
        SlockDiagnosticsLogger.i("Diagnostics", "info detail")
        assertEquals(2, SanitizedDiagnosticsLogBuffer.recent().size)

        SlockDiagnosticsLogger.w("Diagnostics", "warning detail")
        assertEquals("warning detail", SanitizedDiagnosticsLogBuffer.recent().last().message)

        SlockDiagnosticsLogger.setVerboseLoggingEnabled(true)
    }
}
