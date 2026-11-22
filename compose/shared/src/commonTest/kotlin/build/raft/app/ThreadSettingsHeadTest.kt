package build.raft.app

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
