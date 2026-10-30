package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/** osPermission on Android (slock docs/contracts/notifications.md §3.1). */
class RaftNotificationPermissionReporterTest {
    @Test
    fun androidThirteenReadsUnknownUntilAskedThenTheAnswer() {
        fun value(enabled: Boolean, api33: Boolean, granted: Boolean, asked: Boolean) =
            RaftNotificationPermissionReporter.osPermissionValue(enabled, api33, granted, asked)
        assertEquals("unknown", value(enabled = false, api33 = true, granted = false, asked = false))
        assertEquals("denied", value(enabled = false, api33 = true, granted = false, asked = true))
        assertEquals("granted", value(enabled = true, api33 = true, granted = true, asked = true))
        // Granted at runtime but notifications blocked in system settings: denied.
        assertEquals("denied", value(enabled = false, api33 = true, granted = true, asked = true))
        // Below 13 there is no runtime prompt: off in settings is the user's no.
        assertEquals("denied", value(enabled = false, api33 = false, granted = true, asked = false))
    }
}
