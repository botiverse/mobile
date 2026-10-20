package build.raft.app.cmp.host

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftCmpPageArgsTest {
    @Test
    fun keepsThePageDataObject() {
        val args = RaftCmpPageArgs.fromJsonText("compose_cmp_hello", """{"serverId":"s1","n":2}""")
        assertEquals("compose_cmp_hello", args.pageName)
        assertEquals(JsonPrimitive("s1"), args.pageData["serverId"])
        assertEquals(JsonPrimitive(2), args.pageData["n"])
    }

    @Test
    fun blankMalformedOrNonObjectPageDataIsEmpty() {
        for (text in listOf(null, "", "  ", "{", "[1]", "\"x\"")) {
            assertEquals(JsonObject(emptyMap()), RaftCmpPageArgs.fromJsonText("p", text).pageData, "$text")
        }
    }
}
