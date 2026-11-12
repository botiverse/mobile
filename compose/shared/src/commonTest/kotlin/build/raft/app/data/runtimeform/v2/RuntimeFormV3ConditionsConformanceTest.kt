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
 * evaluator runs (slock `v3Rules.test.ts` runs the same one in TS), vendored with its slock SHA in
 * RuntimeFormV3Fixtures.kt, plus the mobile-side cases for the portable pattern subset and structural
 * compare. The checks are shared: this class runs them on the JVM, and
 * RuntimeFormV3ConditionsConformanceIosTest (iosTest) runs the same ones on Kotlin/Native, whose regex
 * engine differs.
 */
class RuntimeFormV3ConditionsConformanceTest {
    @Test fun everyCaseEvaluatesToItsExpectedValue() = runtimeFormV3CheckConformanceCases()

    @Test fun everyUnsupportedSchemaIsReportedUnsupported() = runtimeFormV3CheckConformanceUnsupported()

    @Test fun scopesNameTheWholeValuesOrOneFieldAndNothingElse() = runtimeFormV3CheckScopes()

    @Test fun numbersCompareByValueAndNeverEqualAString() = runtimeFormV3CheckNumbers()

    @Test fun objectsCompareStructurallyWhateverTheKeyOrder() = runtimeFormV3CheckStructuralCompare()

    @Test fun patternsInsideThePortableSubsetAreSupported() = runtimeFormV3CheckPortablePatterns()

    @Test fun patternsOutsideThePortableSubsetMakeTheConditionUnsupported() = runtimeFormV3CheckNonPortablePatterns()

    @Test fun patternsMatchWithEcmaScriptDollarAndDot() = runtimeFormV3CheckEcmaScriptMatching()

    @Test fun aNonPortablePatternInAFormMakesTheWholeFormUnavailable() = runtimeFormV3CheckNonPortablePatternRefusesForm()
}

private val conformanceTable by lazy { Json.parseToJsonElement(V3_CONDITIONS_CONFORMANCE_JSON).jsonObject }

private fun schemaWith(pattern: String) = JsonObject(mapOf("pattern" to JsonPrimitive(pattern)))

internal fun runtimeFormV3CheckConformanceCases() {
    val cases = conformanceTable.getValue("cases").jsonArray
    assertTrue(cases.size > 20, "the table has its cases (${cases.size})")
    for (entry in cases.map { it.jsonObject }) {
        val name = entry.getValue("name").jsonPrimitive.content
        val condition = entry.getValue("condition").jsonObject
        val scope = assertNotNull(runtimeFormV3Scope(condition["scope"].stringOrNull()), "$name: scope")
        val schema = condition.getValue("schema")
        assertTrue(isSupportedRuntimeFormV3Schema(schema), "$name: supported")
        assertEquals(
            entry.getValue("expected").jsonPrimitive.boolean,
            evaluateRuntimeFormV3Condition(RuntimeFormV3Condition(scope, schema as JsonObject), entry.getValue("data").jsonObject),
            name
        )
    }
}

internal fun runtimeFormV3CheckConformanceUnsupported() {
    val unsupported = conformanceTable.getValue("unsupported").jsonArray
    assertTrue(unsupported.isNotEmpty())
    for (entry in unsupported.map { it.jsonObject }) {
        assertFalse(isSupportedRuntimeFormV3Schema(entry["schema"]), entry.getValue("name").jsonPrimitive.content)
    }
}

internal fun runtimeFormV3CheckScopes() {
    assertEquals(RuntimeFormV3Scope(null), runtimeFormV3Scope("#"))
    assertEquals(RuntimeFormV3Scope("providerId"), runtimeFormV3Scope("#/properties/providerId"))
    for (scope in listOf("#/properties/", "#/properties/a/b", "/providerId", null)) {
        assertNull(runtimeFormV3Scope(scope), scope.toString())
    }
}

internal fun runtimeFormV3CheckNumbers() {
    val one = JsonObject(mapOf("const" to JsonPrimitive(1)))
    assertTrue(evaluateRuntimeFormV3Schema(one, JsonPrimitive(1.0)), "JS 1 === 1.0")
    assertFalse(evaluateRuntimeFormV3Schema(one, JsonPrimitive("1")), "JS 1 !== \"1\"")
    val listed = JsonObject(mapOf("enum" to JsonArray(listOf(JsonPrimitive(false)))))
    assertTrue(evaluateRuntimeFormV3Schema(listed, JsonPrimitive(false)))
    assertFalse(evaluateRuntimeFormV3Schema(listed, null), "absent is \"\", not false")
}

internal fun runtimeFormV3CheckStructuralCompare() {
    // #wg-runtime-form:#20 (Otter): structural, key order does not count; array order does.
    val ab = Json.parseToJsonElement("""{"a":"1","b":[1,{"c":true}]}""")
    val ba = Json.parseToJsonElement("""{"b":[1.0,{"c":true}],"a":"1"}""")
    assertTrue(evaluateRuntimeFormV3Schema(JsonObject(mapOf("const" to ab)), ba), "same object, other key order")
    assertTrue(evaluateRuntimeFormV3Schema(JsonObject(mapOf("enum" to JsonArray(listOf(ab)))), ba))
    val reordered = Json.parseToJsonElement("""{"a":"1","b":[{"c":true},1]}""")
    assertFalse(evaluateRuntimeFormV3Schema(JsonObject(mapOf("const" to ab)), reordered), "array order still counts")
    val extra = Json.parseToJsonElement("""{"a":"1","b":[1,{"c":true}],"d":""}""")
    assertFalse(evaluateRuntimeFormV3Schema(JsonObject(mapOf("const" to ab)), extra), "an extra key differs")
}

internal fun runtimeFormV3CheckPortablePatterns() {
    for (pattern in listOf(
        "^connection:", "seek$", "^$", ".*", "^dee", "a|b", "^(openai|anthropic)-compatible$", "(ab)+",
        "[a-z0-9_-]+", "[-a]", "[^/]+", "x{2}", "x{2,}", "x{1,3}", "^gpt-5\\.6$", "\\(\\)", "[\\]\\-]", "^模型$", "^😀+$"
    )) {
        assertTrue(isPortableRuntimeFormV3Pattern(pattern), pattern)
        assertTrue(isSupportedRuntimeFormV3Schema(schemaWith(pattern)), pattern)
    }
}

internal fun runtimeFormV3CheckNonPortablePatterns() {
    for (pattern in listOf(
        "(?<=a)b", "(?<!a)b", "a(?=b)", "a(?!b)", "(?<name>a)", "(?:ab)", "(?i)a", "(a)\\1", "\\d+", "\\w", "\\s",
        "\\bword", "\\p{L}", "\\u0041", "a\\-b", "a*?", "a++", "a{2}?", "a{2}{3}", "*a", "^*", "a|+b", "a{", "a{x}",
        "a{,3}", "a{3,2}", "}", "]", "[]", "[^]", "[a[b]]", "[a&&b]", "[z-a]", "[a-c-e]", "[!--]", "[\\d]", "(a",
        "a)", "\\"
    )) {
        assertFalse(isPortableRuntimeFormV3Pattern(pattern), pattern)
        assertFalse(isSupportedRuntimeFormV3Schema(schemaWith(pattern)), pattern)
    }
}

/**
 * The slock README's substitutions for the Kotlin engines: `$` only at the very end (never before a
 * trailing newline) and `.` excluding only \n, \r, U+2028, U+2029 (so U+0085 matches).
 */
internal fun runtimeFormV3CheckEcmaScriptMatching() {
    fun matches(pattern: String, value: String) = evaluateRuntimeFormV3Schema(schemaWith(pattern), JsonPrimitive(value))
    assertTrue(matches("^a$", "a"))
    assertFalse(matches("^a$", "a\n"), "\$ is the very end")
    assertFalse(matches("^(a|b)$", "b\n"))
    assertTrue(matches("^a.b$", "a\u0085b"), "ECMAScript . matches U+0085")
    for (separator in listOf("\n", "\r", " ", " ")) {
        assertFalse(matches("^a.b$", "a${separator}b"), "ECMAScript . excludes U+${separator[0].code.toString(16)}")
    }
    assertTrue(matches("^a\\$$", "a$"), "an escaped \$ stays a literal")
    assertTrue(matches("^[.$]+$", ".$"), "inside a class . and \$ are literals")
    assertFalse(matches("^[.$]+$", "x"))
    assertTrue(matches("^😀.$", "😀😀"), ". takes a whole code point")
}

internal fun runtimeFormV3CheckNonPortablePatternRefusesForm() {
    val form = Json.parseToJsonElement(BUILTIN_V3_FORM_SAVED_CONNECTIONS_JSON).jsonObject
    val ui = form.getValue("uiSchema").jsonObject
    val rules = ui.getValue("rules").jsonArray.map { rule ->
        val obj = rule.jsonObject
        val condition = obj.getValue("condition").jsonObject
        if (condition["schema"]?.jsonObject?.containsKey("pattern") == true) {
            JsonObject(obj + ("condition" to JsonObject(condition + ("schema" to schemaWith("(?<=x)connection:")))))
        } else {
            obj
        }
    }
    assertNotNull(parseRuntimeFormV2(form), "precondition: the served form parses")
    assertNull(parseRuntimeFormV2(JsonObject(form + ("uiSchema" to JsonObject(ui + ("rules" to JsonArray(rules)))))))
}
