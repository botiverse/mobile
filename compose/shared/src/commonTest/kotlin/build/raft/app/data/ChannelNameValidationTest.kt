package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelNameValidationTest {
    @Test
    fun acceptsServerCompatibleChannelNames() {
        assertTrue("general".isValidChannelName())
        assertTrue("AI-research_2".isValidChannelName())
        assertTrue("  release_2026  ".isValidChannelName())
    }

    @Test
    fun rejectsNamesThatTheServerRejects() {
        assertFalse("".isValidChannelName())
        assertFalse("123-start".isValidChannelName())
        assertFalse("-release".isValidChannelName())
        assertFalse("中文频道".isValidChannelName())
        assertFalse("release notes".isValidChannelName())
        assertFalse("release.topic".isValidChannelName())
    }
}
