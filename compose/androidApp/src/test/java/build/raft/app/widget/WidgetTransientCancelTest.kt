package build.raft.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTransientCancelTest {
    @Test
    fun onlyATransportCancellationIsRetried() {
        assertTrue(isWidgetTransientCancel("home_http_0"))
        assertFalse(isWidgetTransientCancel("home_http_500"))
        assertFalse(isWidgetTransientCancel("home_http_401"))
        assertFalse(isWidgetTransientCancel("home_http_10"))
        assertFalse(isWidgetTransientCancel("network_offline"))
    }
}
