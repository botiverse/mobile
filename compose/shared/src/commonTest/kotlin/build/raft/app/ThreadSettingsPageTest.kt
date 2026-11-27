package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.thread.ThreadSettingsPageLink
import build.raft.app.thread.ThreadSettingsPageLinks
import build.raft.app.thread.threadSettingsOpensAsPage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadSettingsPageTest {
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

    @Test
    fun everyPhoneGetsTheSettingsPageAndOnlyAWideWindowTheDrawer() {
        // #307: the page was only for windows under the drawer's own 420dp, so a large phone (430,
        // iPhone Pro Max) and a foldable's inner screen got a side drawer over the conversation.
        assertTrue(threadSettingsOpensAsPage(430.dp), "a large phone")
        assertTrue(threadSettingsOpensAsPage(673.dp), "a foldable's inner screen")
        // From the wide breakpoint, with the rail and two panes: the drawer beside the conversation.
        assertFalse(threadSettingsOpensAsPage(744.dp))
    }
}
