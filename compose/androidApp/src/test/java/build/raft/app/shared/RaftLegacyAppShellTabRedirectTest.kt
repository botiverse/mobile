package build.raft.app.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RaftLegacyAppShellTabRedirectTest {
    @Test
    fun oldHomeActivityPageOpensTheAppShellActivityTab() {
        val launch =
            JSONObject()
                .put("pageName", RaftPages.HOME_ACTIVITY)
                .put("destination", "activity")
                .put("slockShellInstanceId", "shell-1")

        val redirected = raftLegacyAppShellTabRedirect(RaftPages.HOME_ACTIVITY, launch)

        assertNotNull(redirected)
        assertEquals("activity", redirected!!.getString(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB))
        assertEquals("shell-1", redirected.getString("slockShellInstanceId"))
        assertFalse(redirected.has("pageName"))
        assertFalse(redirected.has("destination"))
        // The launch payload itself is not mutated.
        assertEquals(RaftPages.HOME_ACTIVITY, launch.getString("pageName"))
    }

    @Test
    fun otherPagesAreNotRedirected() {
        assertNull(raftLegacyAppShellTabRedirect(RaftPages.APP_SHELL, JSONObject()))
        assertNull(raftLegacyAppShellTabRedirect("compose_home_search", JSONObject()))
        assertNull(raftLegacyAppShellTabRedirect(RaftPages.THREAD, JSONObject()))
    }
}
