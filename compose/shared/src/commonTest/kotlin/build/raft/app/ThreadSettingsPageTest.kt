package build.raft.app

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

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
}
