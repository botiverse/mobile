package build.raft.app

import build.raft.app.thread.ThreadSettingsVisibility
import build.raft.app.thread.threadSettingsVisibility
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadSettingsHeadTest {
    @Test
    fun channelKindsMapToTheWebVisibilityTags() {
        assertEquals(ThreadSettingsVisibility.Public, threadSettingsVisibility("channel"))
        assertEquals(ThreadSettingsVisibility.Public, threadSettingsVisibility("public"))
        assertEquals(ThreadSettingsVisibility.Private, threadSettingsVisibility("Private"))
        assertEquals(ThreadSettingsVisibility.Joint, threadSettingsVisibility(" joint "))
    }
}
