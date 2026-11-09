package build.raft.app.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The saveable page key: identity-only pageData (AGENTS.md route rule), JSON round trip. */
class RaftPageKeyTest {
    private val identity = JsonObject(mapOf("channelId" to JsonPrimitive("c1"), "serverId" to JsonPrimitive("s1")))

    @Test fun `create drops every content field and keeps identity`() {
        val dropped = mutableListOf<String>()
        val withContent = JsonObject(identity + RaftPageKeyContentFields.associateWith { JsonArray(emptyList()) })
        val key = RaftPageKey.create("compose_thread", withContent, id = 3, onDropped = { dropped += it })
        assertEquals(identity, key.pageData)
        assertEquals(RaftPageKeyContentFields, dropped.toSet())
        assertEquals("compose_thread#3", key.contentKey)
    }

    @Test fun `identity-only data is kept as is`() {
        val dropped = mutableListOf<String>()
        val key = RaftPageKey.create("compose_thread", identity, id = 0, onDropped = { dropped += it })
        assertEquals(identity, key.pageData)
        assertEquals(emptyList(), dropped)
    }

    @Test fun `key survives a JSON round trip`() {
        val key = RaftPageKey.create("compose_thread", identity, id = 7)
        val text = Json.encodeToString(RaftPageKey.serializer(), key)
        val restored = Json.decodeFromString(RaftPageKey.serializer(), text)
        assertEquals(key, restored)
        assertEquals("compose_thread#7", restored.contentKey)
        assertEquals(identity, restored.pageData)
    }

    @Test fun `a saved key carrying content is rejected on decode`() {
        val text = """{"pageName":"compose_thread","pageData":{"channelId":"c1","messages":[]},"id":1}"""
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(RaftPageKey.serializer(), text) }
    }

    @Test fun `two opens of the same page and data are two keys`() {
        assertNotEquals(
            RaftPageKey.create("compose_thread", identity, id = 1),
            RaftPageKey.create("compose_thread", identity, id = 2),
        )
    }
}
