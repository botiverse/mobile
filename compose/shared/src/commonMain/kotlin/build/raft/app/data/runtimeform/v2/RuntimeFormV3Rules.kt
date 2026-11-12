package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Runtime form protocol v3 conditions (contract `Condition`, `Rule`, `ConditionalRequired`), a port of
 * slock `packages/runtime-form/src/v3Rules.ts`: a small JSON Schema subset. Supported keywords: const,
 * enum, pattern, not, anyOf, allOf, properties. An absent (or JSON null) value is evaluated as "". Any
 * other keyword makes the schema unsupported, and the parser then refuses the whole form (fail closed):
 * a form whose rules this client cannot evaluate is never rendered.
 *
 * slock `fixtures/v3.conditions.conformance.json` is the shared conformance table (TS there, Kotlin in
 * RuntimeFormV3ConditionsConformanceTest here).
 */
internal const val RUNTIME_FORM_V3_PROTOCOL_VERSION: Int = 3

private val RUNTIME_FORM_V3_KEYWORDS = setOf("const", "enum", "pattern", "not", "anyOf", "allOf", "properties")

/** A condition scope: `#` is the whole values object ([key] null), `#/properties/<key>` one field. */
internal data class RuntimeFormV3Scope(val key: String?)

/** A parsed rule condition: [schema] evaluated against the value [scope] names. */
internal data class RuntimeFormV3Condition(val scope: RuntimeFormV3Scope, val schema: JsonObject)

internal enum class RuntimeFormV3Effect { Show, Hide }

/** v3 `uiSchema.rules` entry for one field: SHOW while the condition holds, or HIDE while it holds. */
internal data class RuntimeFormV3Rule(val effect: RuntimeFormV3Effect, val condition: RuntimeFormV3Condition)

/**
 * v3 `dataSchema.allOf` entry naming one field: required while [condition] (over the whole values)
 * evaluates to [whenResult] (`then` = true, `else` = false), in addition to the unconditional `required`.
 */
internal data class RuntimeFormV3RequiredWhen(val condition: JsonObject, val whenResult: Boolean)

/** `#` → whole values, `#/properties/<key>` → that key; anything else → null (unsupported). */
internal fun runtimeFormV3Scope(scope: String?): RuntimeFormV3Scope? {
    if (scope == "#") return RuntimeFormV3Scope(null)
    val prefix = "#/properties/"
    if (scope == null || !scope.startsWith(prefix)) return null
    val key = scope.removePrefix(prefix)
    return if (key.isNotEmpty() && '/' !in key) RuntimeFormV3Scope(key) else null
}

/** Whether [schema] uses only the supported keywords, with well-formed operands, all the way down. */
internal fun isSupportedRuntimeFormV3Schema(schema: JsonElement?): Boolean {
    if (schema !is JsonObject) return false
    for ((keyword, operand) in schema) {
        if (keyword !in RUNTIME_FORM_V3_KEYWORDS) return false
        val wellFormed = when (keyword) {
            "enum" -> operand is JsonArray
            "pattern" -> operand.stringOrNull()?.let { isPortableRuntimeFormV3Pattern(it) && runtimeFormV3Regex(it) != null } == true
            "not" -> isSupportedRuntimeFormV3Schema(operand)
            "anyOf", "allOf" -> operand is JsonArray && operand.isNotEmpty() && operand.all(::isSupportedRuntimeFormV3Schema)
            "properties" -> operand is JsonObject && operand.values.all(::isSupportedRuntimeFormV3Schema)
            else -> true
        }
        if (!wellFormed) return false
    }
    return true
}

/** Evaluate a supported schema against a value (absent or JSON null = ""). */
internal fun evaluateRuntimeFormV3Schema(schema: JsonObject, value: JsonElement?): Boolean {
    val subject: JsonElement = if (value == null || value is JsonNull) JsonPrimitive("") else value
    for ((keyword, operand) in schema) {
        val holds = when (keyword) {
            "const" -> sameJson(subject, operand)
            "enum" -> (operand as JsonArray).any { sameJson(subject, it) }
            "pattern" -> {
                val text = (subject as? JsonPrimitive)?.takeIf { it.isString }?.content
                val regex = operand.stringOrNull()?.let(::runtimeFormV3Regex)
                text != null && regex != null && regex.containsMatchIn(text)
            }
            "not" -> !evaluateRuntimeFormV3Schema(operand as JsonObject, subject)
            "anyOf" -> (operand as JsonArray).any { evaluateRuntimeFormV3Schema(it as JsonObject, subject) }
            "allOf" -> (operand as JsonArray).all { evaluateRuntimeFormV3Schema(it as JsonObject, subject) }
            // A non-object subject has no properties: each one is absent ("").
            "properties" -> {
                val record = subject as? JsonObject ?: JsonObject(emptyMap())
                (operand as JsonObject).all { (key, sub) -> evaluateRuntimeFormV3Schema(sub as JsonObject, record[key]) }
            }
            else -> false
        }
        if (!holds) return false
    }
    return true
}

/** Evaluate a condition against the form values. */
internal fun evaluateRuntimeFormV3Condition(condition: RuntimeFormV3Condition, values: Map<String, JsonElement>): Boolean =
    evaluateRuntimeFormV3Schema(
        condition.schema,
        when (val key = condition.scope.key) {
            null -> JsonObject(values)
            else -> values[key]
        }
    )

/**
 * Whether [pattern] stays inside the portable regex subset every client evaluates the same way
 * (#wg-runtime-form:#20, Otter): `^`/`$` anchors, literals (a metacharacter escaped with `\`), character
 * classes incl. `[^…]` and ranges, `.`, the quantifiers `*` `+` `?` `{n}` `{n,}` `{n,m}`, groups `(…)` /
 * `(?:…)` and `|`. Anything else (lookaround, named groups, backreferences, `\d`/`\w`/`\p{…}` and other
 * escape classes, inline flags, lazy or possessive quantifiers, a stray `]`/`{`/`}`) is unsupported, so the
 * condition — and the whole form — is unavailable rather than evaluated differently per platform.
 */
internal fun isPortableRuntimeFormV3Pattern(pattern: String): Boolean {
    var i = 0
    var depth = 0
    // Whether the previous token can take a quantifier (an atom), and whether it was itself a quantifier.
    var atom = false
    while (i < pattern.length) {
        val c = pattern[i]
        when (c) {
            '\\' -> {
                if (i + 1 >= pattern.length || pattern[i + 1] !in RUNTIME_FORM_V3_ESCAPABLE) return false
                i += 2
                atom = true
                continue
            }
            '[' -> {
                i = runtimeFormV3ClassEnd(pattern, i + 1) ?: return false
                atom = true
                continue
            }
            '(' -> {
                if (pattern.startsWith("(?", i)) {
                    if (!pattern.startsWith("(?:", i)) return false
                    i += 3
                } else {
                    i += 1
                }
                depth += 1
                atom = false
                continue
            }
            ')' -> {
                if (depth == 0) return false
                depth -= 1
                atom = true
            }
            '|', '^', '$' -> atom = false
            '*', '+', '?' -> {
                if (!atom) return false
                atom = false
            }
            '{' -> {
                if (!atom) return false
                val end = pattern.indexOf('}', i)
                if (end < 0 || !RUNTIME_FORM_V3_BOUNDS.matches(pattern.substring(i + 1, end))) return false
                i = end + 1
                atom = false
                continue
            }
            ']', '}' -> return false
            else -> atom = true
        }
        i += 1
    }
    return depth == 0
}

private const val RUNTIME_FORM_V3_ESCAPABLE = "\\^$.|?*+()[]{}/-"
private val RUNTIME_FORM_V3_BOUNDS = Regex("""\d+(,\d*)?""")

/** The index after the `]` closing a class whose body starts at [start]; null when it is not portable. */
private fun runtimeFormV3ClassEnd(pattern: String, start: Int): Int? {
    var i = start
    if (i < pattern.length && pattern[i] == '^') i += 1
    var members = 0
    while (i < pattern.length) {
        when (val c = pattern[i]) {
            ']' -> return if (members > 0) i + 1 else null
            '[' -> return null
            '\\' -> {
                if (i + 1 >= pattern.length || pattern[i + 1] !in RUNTIME_FORM_V3_ESCAPABLE) return null
                i += 2
            }
            else -> {
                if (c == '&' && pattern.startsWith("&&", i)) return null
                i += 1
            }
        }
        members += 1
    }
    return null
}

/**
 * The pattern as a regex (ECMAScript patterns are unanchored unless they anchor themselves, as
 * [Regex.containsMatchIn] is); null when it does not compile.
 */
private fun runtimeFormV3Regex(pattern: String): Regex? = runCatching { Regex(pattern) }.getOrNull()

/**
 * JS `===` for primitives (a string never equals a boolean or number; numbers compare by value), and
 * structural equality for arrays (in order) and objects (same keys, key order does not count), applied
 * recursively. Contract per #wg-runtime-form:#20 (Otter): slock's `JSON.stringify` compare is a bug to fix.
 */
private fun sameJson(a: JsonElement, b: JsonElement): Boolean =
    when {
        a is JsonPrimitive && b is JsonPrimitive -> samePrimitive(a, b)
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { sameJson(a[it], b[it]) }
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, value) -> sameJson(value, b.getValue(key)) }
        else -> false
    }

private fun samePrimitive(a: JsonPrimitive, b: JsonPrimitive): Boolean {
    if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
    if (a.isString || b.isString) return a.isString && b.isString && a.content == b.content
    val x = a.doubleOrNull
    val y = b.doubleOrNull
    return if (x != null && y != null) x == y else a.content == b.content
}
