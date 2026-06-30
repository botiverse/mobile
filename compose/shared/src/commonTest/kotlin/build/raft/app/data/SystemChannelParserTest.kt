package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemChannelParserTest {
    @Test
    fun parsesAllChannelAsHiddenWhenTypeIsPrivate() {
        val body = """
            {"channels":[
              {"id":"chan-1","name":"general","type":"public"},
              {"id":"chan-all","name":"all","type":"private"}
            ]}
        """.trimIndent()
        val snapshot = parseSharedSystemChannelSnapshot(body)
        assertNotNull(snapshot)
        assertEquals("chan-all", snapshot.allChannelId)
        assertTrue(snapshot.allChannelHidden)
        assertTrue(snapshot.canManage)
    }

    @Test
    fun parsesAllChannelAsVisibleWhenTypeIsPublic() {
        val body = """
            {"channels":[
              {"id":"chan-all","name":"all","type":"public"}
            ]}
        """.trimIndent()
        val snapshot = parseSharedSystemChannelSnapshot(body)
        assertNotNull(snapshot)
        assertEquals("chan-all", snapshot.allChannelId)
        assertEquals(false, snapshot.allChannelHidden)
    }

    @Test
    fun returnsNullWhenNoAllChannel() {
        val body = """{"channels":[{"id":"chan-1","name":"general","type":"public"}]}"""
        assertNull(parseSharedSystemChannelSnapshot(body))
    }

    @Test
    fun returnsNullWhenChannelsKeyMissing() {
        assertNull(parseSharedSystemChannelSnapshot("{}"))
    }

    @Test
    fun returnsNullWhenAllChannelHasNoId() {
        val body = """{"channels":[{"name":"all","type":"private"}]}"""
        assertNull(parseSharedSystemChannelSnapshot(body))
    }
}