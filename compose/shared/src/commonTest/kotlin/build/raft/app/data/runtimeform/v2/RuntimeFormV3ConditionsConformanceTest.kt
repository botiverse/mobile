package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * slock `packages/runtime-form/fixtures/v3.conditions.conformance.json`, the table every v3 client
 * evaluator runs (slock `v3Rules.test.ts` runs the same one in TS). Vendored with its slock SHA in
 * RuntimeFormV3Fixtures.kt.
 */
class RuntimeFormV3ConditionsConformanceTest {
    private val table = Json.parseToJsonElement(V3_CONDITIONS_CONFORMANCE_JSON).jsonObject

    @Test
    fun everyCaseEvaluatesToItsExpectedValue() {
        val cases = table.getValue("cases").jsonArray
        assertTrue(cases.size > 20, "the table has its cases (${cases.size})")
        for (entry in cases.map { it.jsonObject }) {
            val name = entry.getValue("name").jsonPrimitive.content
            val condition = entry.getValue("condition").jsonObject
            val scope = assertNotNull(runtimeFormV3Scope(condition["scope"].stringOrNull()), "$name: scope")
            val schema = condition.getValue("schema")
            assertTrue(isSupportedRuntimeFormV3Schema(schema), "$name: supported")
            val data = entry.getValue("data").jsonObject
            assertEquals(
                entry.getValue("expected").jsonPrimitive.boolean,
                evaluateRuntimeFormV3Condition(RuntimeFormV3Condition(scope, schema as JsonObject), data),
                name
            )
        }
    }

    @Test
    fun everyUnsupportedSchemaIsReportedUnsupported() {
        val unsupported = table.getValue("unsupported").jsonArray
        assertTrue(unsupported.isNotEmpty())
        for (entry in unsupported.map { it.jsonObject }) {
            assertFalse(isSupportedRuntimeFormV3Schema(entry["schema"]), entry.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun scopesNameTheWholeValuesOrOneFieldAndNothingElse() {
        assertEquals(RuntimeFormV3Scope(null), runtimeFormV3Scope("#"))
        assertEquals(RuntimeFormV3Scope("providerId"), runtimeFormV3Scope("#/properties/providerId"))
        for (scope in listOf("#/properties/", "#/properties/a/b", "/providerId", null)) {
            assertNull(runtimeFormV3Scope(scope), scope.toString())
        }
    }

    @Test
    fun numbersCompareByValueAndNeverEqualAString() {
        val one = JsonObject(mapOf("const" to JsonPrimitive(1)))
        assertTrue(evaluateRuntimeFormV3Schema(one, JsonPrimitive(1.0)), "JS 1 === 1.0")
        assertFalse(evaluateRuntimeFormV3Schema(one, JsonPrimitive("1")), "JS 1 !== \"1\"")
        val listed = JsonObject(mapOf("enum" to JsonArray(listOf(JsonPrimitive(false)))))
        assertTrue(evaluateRuntimeFormV3Schema(listed, JsonPrimitive(false)))
        assertFalse(evaluateRuntimeFormV3Schema(listed, null), "absent is \"\", not false")
    }

    @Test
    fun objectsCompareStructurallyWhateverTheKeyOrder() {
        // #wg-runtime-form:#20 (Otter): structural, key order does not count; slock adds this case to the table.
        val ab = Json.parseToJsonElement("""{"a":"1","b":[1,{"c":true}]}""")
        val ba = Json.parseToJsonElement("""{"b":[1.0,{"c":true}],"a":"1"}""")
        assertTrue(evaluateRuntimeFormV3Schema(JsonObject(mapOf("const" to ab)), ba), "same object, other key order")
        assertTrue(evaluateRuntimeFormV3Schema(JsonObject(mapOf("enum" to JsonArray(listOf(ab)))), ba))
        val reordered = Json.parseToJsonElement("""{"a":"1","b":[{"c":true},1]}""")
        assertFalse(evaluateRuntimeFormV3Schema(JsonObject(mapOf("const" to ab)), reordered), "array order still counts")
        val extra = Json.parseToJsonElement("""{"a":"1","b":[1,{"c":true}],"d":""}""")
        assertFalse(evaluateRuntimeFormV3Schema(JsonObject(mapOf("const" to ab)), extra), "an extra key differs")
    }

    @Test
    fun patternsInsideThePortableSubsetAreSupported() {
        for (pattern in listOf(
            "^connection:", "seek$", "^$", ".*", "^dee", "a|b", "^(openai|anthropic)-compatible$", "(?:ab)+",
            "[a-z0-9_-]+", "[^/]+", "x{2}", "x{2,}", "x{1,3}", "^gpt-5\\.6$", "\\(\\)", "[\\]\\-]", "^模型$"
        )) {
            assertTrue(isSupportedRuntimeFormV3Schema(JsonObject(mapOf("pattern" to JsonPrimitive(pattern)))), pattern)
        }
    }

    @Test
    fun patternsOutsideThePortableSubsetMakeTheConditionUnsupported() {
        for (pattern in listOf(
            "(?<=a)b", "(?<!a)b", "a(?=b)", "a(?!b)", "(?<name>a)", "(?i)a", "(a)\\1", "\\d+", "\\w", "\\s", "\\bword",
            "\\p{L}", "\\u0041", "a*?", "a++", "a{2}?", "*a", "^*", "a{", "a{x}", "}", "]", "[]", "[a[b]]", "[a&&b]",
            "[\\d]", "(a", "a)", "\\"
        )) {
            assertFalse(isPortableRuntimeFormV3Pattern(pattern), pattern)
            assertFalse(isSupportedRuntimeFormV3Schema(JsonObject(mapOf("pattern" to JsonPrimitive(pattern)))), pattern)
        }
        // Inside a form: one non-portable pattern makes the whole form unavailable.
        val form = Json.parseToJsonElement(BUILTIN_V3_FORM_SAVED_CONNECTIONS_JSON).jsonObject
        val ui = form.getValue("uiSchema").jsonObject
        val rules = ui.getValue("rules").jsonArray.map { rule ->
            val obj = rule.jsonObject
            val condition = obj.getValue("condition").jsonObject
            if (condition["schema"]?.jsonObject?.containsKey("pattern") == true) {
                JsonObject(obj + ("condition" to JsonObject(condition + ("schema" to JsonObject(mapOf("pattern" to JsonPrimitive("(?<=x)connection:")))))))
            } else {
                obj
            }
        }
        assertNull(parseRuntimeFormV2(JsonObject(form + ("uiSchema" to JsonObject(ui + ("rules" to JsonArray(rules)))))))
    }
}
