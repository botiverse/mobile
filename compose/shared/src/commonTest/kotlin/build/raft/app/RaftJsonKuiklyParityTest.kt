package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray

/**
 * The data layer moved from Kuikly's JSONObject to kotlinx JsonObject. These tests pin that the
 * replacement serializes to the same bytes and reads values with the same coercions.
 */
class RaftJsonKuiklyParityTest {
    @Test
    fun optAccessorsCoerceLikeKuikly() {
        val payload =
            """
            {"int":5,"negInt":-5,"long":12345678901,"double":1.9,"negDouble":-1.9,"integralDouble":2.0,
             "intString":"42","longString":"12345678901","doubleString":"3.5","spacedDouble":" 3.5 ",
             "trueString":"TRUE","falseString":"false","otherString":"yes","emptyString":"",
             "true":true,"false":false,"zero":0,"half":0.5,"null":null,
             "obj":{"a":1},"arr":[1,2],"objString":"{\"x\":1}","arrString":"[1]","badString":"{"}
            """.trimIndent()
        val kuikly = JSONObject(payload)
        val kotlinx = parseRaftJsonObject(payload)
        val keys = kuikly.keySet().toList() + "missing"
        keys.forEach { key ->
            assertEquals(kuikly.has(key), kotlinx.has(key), "has $key")
            assertEquals(kuikly.optString(key), kotlinx.optString(key), "optString $key")
            assertEquals(kuikly.optString(key, "fb"), kotlinx.optString(key, "fb"), "optString fallback $key")
            assertEquals(kuikly.optInt(key), kotlinx.optInt(key), "optInt $key")
            assertEquals(kuikly.optInt(key, -9), kotlinx.optInt(key, -9), "optInt fallback $key")
            assertEquals(kuikly.optLong(key), kotlinx.optLong(key), "optLong $key")
            assertEquals(kuikly.optLong(key, -9L), kotlinx.optLong(key, -9L), "optLong fallback $key")
            assertEquals(kuikly.optDouble(key), kotlinx.optDouble(key), "optDouble $key")
            assertEquals(kuikly.optBoolean(key), kotlinx.optBoolean(key), "optBoolean $key")
            assertEquals(kuikly.optBoolean(key, true), kotlinx.optBoolean(key, true), "optBoolean fallback $key")
            assertEquals(kuikly.optJSONObject(key)?.toString(), kotlinx.optJsonObject(key)?.toRaftJsonString(), "optJsonObject $key")
            assertEquals(kuikly.optJSONArray(key)?.toString(), kotlinx.optJsonArray(key)?.toRaftJsonString(), "optJsonArray $key")
        }
    }

    @Test
    fun parseRejectsNonObjects() {
        assertFailsWith<Exception> { parseRaftJsonObject("[]") }
        assertFailsWith<Exception> { parseRaftJsonObject("") }
        assertFailsWith<Exception> { parseRaftJsonArray("{}") }
        assertNull(parseRaftJsonObjectOrNull("not json"))
        assertEquals(JsonObject(emptyMap()), parseRaftJsonObjectOrNull("{}"))
    }
}
