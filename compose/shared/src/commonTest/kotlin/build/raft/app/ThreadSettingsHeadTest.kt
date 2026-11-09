package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreadSettingsHeadTest {
    @Test
    fun channelKindsMapToTheWebVisibilityTags() {
        assertEquals(ThreadSettingsVisibility.Public, threadSettingsVisibility("channel"))
        assertEquals(ThreadSettingsVisibility.Public, threadSettingsVisibility("public"))
        assertEquals(ThreadSettingsVisibility.Private, threadSettingsVisibility("Private"))
        assertEquals(ThreadSettingsVisibility.Joint, threadSettingsVisibility(" joint "))
    }

    @Test
    fun directMessagesThreadsAndUnknownKindsShowNoTag() {
        assertNull(threadSettingsVisibility("dm"))
        assertNull(threadSettingsVisibility("thread"))
        assertNull(threadSettingsVisibility(""))
        assertNull(threadSettingsVisibility(null))
        assertNull(threadSettingsVisibilityTag("dm"))
    }

    @Test
    fun aChannelKindGivesTheWideHeadATag() {
        assertNotNull(threadSettingsVisibilityTag("private"))
    }
}
