package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The data layer moved from Kuikly's JSONObject to kotlinx JsonObject. These tests pin that the
 * replacement serializes to the same bytes and reads values with the same coercions.
 */
class SlockJsonKuiklyParityTest {
    private val trickyStrings =
        listOf(
            "",
            "plain",
            "https://slock.ai/a/b?c=d&e=f",
            "quote\" backslash\\ slash/",
            "tab\t newline\n return\r backspace\b formfeed\u000c",
            "nul\u0000 unit\u001f del\u007f",
            "中文 emoji 😀 line sep",
        )

    @Test
    fun kotlinxObjectsSerializeLikeKuiklyObjects() {
        val kuikly =
            JSONObject()
                .put("int", 7)
                .put("negativeInt", -3)
                .put("long", 9_007_199_254_740_993L)
                .put("integralDouble", 1.0)
                .put("negativeZero", -0.0)
                .put("double", 1.5)
                .put("smallDouble", 0.000123)
                .put("bigDouble", 1e20)
                .put("float", 0.1f)
                .put("true", true)
                .put("false", false)
                .put("null", null)
                .put("nested", JSONObject().put("a", "b/c").put("n", 2))
                .put("array", JSONArray().put(1).put("x").put(2.5).put(JSONObject().put("k", false)))
        trickyStrings.forEachIndexed { index, value -> kuikly.put("s$index", value) }

        val kotlinx =
            buildJsonObject {
                put("int", 7)
                put("negativeInt", -3)
                put("long", 9_007_199_254_740_993L)
                put("integralDouble", 1.0)
                put("negativeZero", -0.0)
                put("double", 1.5)
                put("smallDouble", 0.000123)
                put("bigDouble", 1e20)
                put("float", 0.1f)
                put("true", true)
                put("false", false)
                put("null", JsonNull)
                putJsonObject("nested") {
                    put("a", "b/c")
                    put("n", 2)
                }
                putJsonArray("array") {
                    add(1)
                    add("x")
                    add(2.5)
                    add(buildJsonObject { put("k", false) })
                }
                trickyStrings.forEachIndexed { index, value -> put("s$index", value) }
            }

        assertEquals(kuikly.toString(), kotlinx.toSlockJsonString())
        assertEquals(kuikly.toString(), kuikly.toJsonObject().toSlockJsonString())
        assertEquals(kuikly.toString(), kotlinx.toKuiklyJson().toString())
    }

    @Test
    fun parsedPayloadsReserializeLikeKuikly() {
        val payloads =
            listOf(
                """{}""",
                """{"a":1,"b":1.0,"c":1.50,"d":-0,"e":1e3,"f":12345678901,"g":1E-7}""",
                """{"url":"https:\/\/x.y\/z","plain":"a/b","u":"é\u0001","t":"\t"}""",
                """{"nested":{"list":[1,"2",[3,{"x":null}]],"empty":[]},"flag":true}""",
                """{"dup":1,"dup":2,"order":"kept"}""",
                "﻿{\"bom\":true}",
                """ { "spaced" : [ 1 , 2 ] } """,
            )
        payloads.forEach { payload ->
            assertEquals(
                JSONObject(payload).toString(),
                parseSlockJsonObject(payload).toSlockJsonString(),
                "payload=$payload",
            )
        }
        assertEquals(JSONArray("""[1,"/",null,2.0]""").toString(), parseSlockJsonArray("""[1,"/",null,2.0]""").toSlockJsonString())
    }

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
        val kotlinx = parseSlockJsonObject(payload)
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
            assertEquals(kuikly.optJSONObject(key)?.toString(), kotlinx.optJsonObject(key)?.toSlockJsonString(), "optJsonObject $key")
            assertEquals(kuikly.optJSONArray(key)?.toString(), kotlinx.optJsonArray(key)?.toSlockJsonString(), "optJsonArray $key")
        }
    }

    @Test
    fun arrayAccessorsCoerceLikeKuikly() {
        val payload = """[5,"7",1.9,true,"true",null,{"a":1},[1],"{\"a\":1}"]"""
        val kuikly = JSONArray(payload)
        val kotlinx = parseSlockJsonArray(payload)
        for (index in -1..kuikly.length()) {
            assertEquals(kuikly.optString(index), kotlinx.optString(index), "optString $index")
            assertEquals(kuikly.optInt(index), kotlinx.optInt(index), "optInt $index")
            assertEquals(kuikly.optLong(index), kotlinx.optLong(index), "optLong $index")
            assertEquals(kuikly.optDouble(index).toString(), kotlinx.optDouble(index).toString(), "optDouble $index")
            assertEquals(kuikly.optBoolean(index), kotlinx.optBoolean(index), "optBoolean $index")
            assertEquals(kuikly.optJSONObject(index)?.toString(), kotlinx.optJsonObject(index)?.toSlockJsonString(), "optJsonObject $index")
            assertEquals(kuikly.optJSONArray(index)?.toString(), kotlinx.optJsonArray(index)?.toSlockJsonString(), "optJsonArray $index")
        }
    }

    @Test
    fun rawValuesMatchKuiklyStorageTypes() {
        val kotlinx = parseSlockJsonObject("""{"i":1,"l":12345678901,"d":1.0,"s":"1","b":true,"n":null}""")
        assertEquals(1, kotlinx.opt("i"))
        assertEquals(12_345_678_901L, kotlinx.opt("l"))
        assertEquals(1.0, kotlinx.opt("d"))
        assertEquals("1", kotlinx.opt("s"))
        assertEquals(true, kotlinx.opt("b"))
        assertNull(kotlinx.opt("n"))
        assertNull(kotlinx.opt("missing"))
        assertEquals(JsonPrimitive(1), JSONObject().put("i", 1).toJsonObject()["i"])
    }

    @Test
    fun parseRejectsNonObjects() {
        assertFailsWith<Exception> { parseSlockJsonObject("[]") }
        assertFailsWith<Exception> { parseSlockJsonObject("") }
        assertFailsWith<Exception> { parseSlockJsonArray("{}") }
        assertNull(parseSlockJsonObjectOrNull("not json"))
        assertEquals(JsonObject(emptyMap()), parseSlockJsonObjectOrNull("{}"))
    }
}
