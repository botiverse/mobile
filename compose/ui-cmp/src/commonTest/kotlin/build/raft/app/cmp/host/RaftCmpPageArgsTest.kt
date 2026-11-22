package build.raft.app.cmp.host

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftCmpPageArgsTest {
    @Test
    fun blankMalformedOrNonObjectPageDataIsEmpty() {
        for (text in listOf(null, "", "  ", "{", "[1]", "\"x\"")) {
            assertEquals(JsonObject(emptyMap()), RaftCmpPageArgs.fromJsonText("p", text).pageData, "$text")
        }
    }
}
