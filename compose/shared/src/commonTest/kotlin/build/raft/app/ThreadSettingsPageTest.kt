package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftSideDrawerDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadSettingsPageTest {
    @Test
    fun aPhoneOpensTheSettingsAsAPushedPage() {
        assertTrue(threadSettingsOpensAsPage(360.dp))
        assertTrue(threadSettingsOpensAsPage(390.dp))
        // Exactly the drawer's width: the drawer would fill the window.
        assertTrue(threadSettingsOpensAsPage(RaftSideDrawerDefaults.MaxWidth))
    }

    @Test
    fun aWideWindowKeepsTheSideDrawer() {
        assertFalse(threadSettingsOpensAsPage(RaftSideDrawerDefaults.MaxWidth + 1.dp))
        assertFalse(threadSettingsOpensAsPage(834.dp))
        assertFalse(threadSettingsOpensAsPage(1280.dp))
    }

    @Test
    fun pageDataNamesTheLinkAndTheChannelForTheRouteAuthority() {
        val data = threadSettingsPageData("thread-settings:c1", ServerId("server-1"), ChannelId("c1"))
        assertEquals(THREAD_SETTINGS_PAGE, data.optString("pageName"))
        assertEquals("thread-settings:c1", data.optString(THREAD_SETTINGS_LINK_KEY))
        // openRaftPage(authenticatedRouteAuthorityRequired = true) checks this server id.
        assertEquals("server-1", data.optString("serverId"))
        assertEquals("c1", data.optString("channelId"))
    }

    @Test
    fun aLinkIsFoundUntilItsConversationGoes() {
        val link = ThreadSettingsPageLink()
        ThreadSettingsPageLinks.register("thread-settings:test-a", link)
        assertSame(link, ThreadSettingsPageLinks.get("thread-settings:test-a"))
        // A newer conversation under the same key keeps its own link when the old one leaves.
        val newer = ThreadSettingsPageLink()
        ThreadSettingsPageLinks.register("thread-settings:test-a", newer)
        ThreadSettingsPageLinks.unregister("thread-settings:test-a", link)
        assertSame(newer, ThreadSettingsPageLinks.get("thread-settings:test-a"))
        ThreadSettingsPageLinks.unregister("thread-settings:test-a", newer)
        assertNull(ThreadSettingsPageLinks.get("thread-settings:test-a"))
    }
}
