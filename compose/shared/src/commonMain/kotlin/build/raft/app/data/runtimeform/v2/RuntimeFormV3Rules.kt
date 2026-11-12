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
            "pattern" -> operand.stringOrNull()?.let(::runtimeFormV3Regex) != null
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
 * Whether [pattern] stays inside the portable regex subset (slock README "Protocol v3", #14545): `^` / `$`;
 * literals, with a backslash only before a syntax character `^ $ \ . * + ? ( ) [ ] { } | /` (and `-` inside
 * a class); classes `[…]` / `[^…]` with ordered ranges (a literal `-` only first or last, no nested `[`, no
 * `&&`, not empty); `.`; quantifiers `* + ? {m} {m,} {m,n}` (m <= n) directly after a literal, `.`, class or
 * group; groups `( )`; `|`. Anything else (lookarounds and every `(?…)` group, backreferences,
 * `\d`/`\w`/`\s`/`\b`/`\p{…}` and every other escape, lazy, possessive or stacked quantifiers, a bare `]`
 * `{` `}`) is unsupported, so the condition and the whole form are unavailable. Port of slock
 * `isPortableRuntimeFormV3Pattern`; walks code points as the TS does.
 */
internal fun isPortableRuntimeFormV3Pattern(pattern: String): Boolean = runtimeFormV3PortableSource(pattern) != null

/**
 * The pattern compiled with ECMAScript semantics on the Kotlin engines (JVM and Kotlin/Native), per the
 * slock README: an unescaped `$` outside a class becomes `\z` (`$` also matches before a trailing newline
 * there) and `.` becomes `[^\n\r\u2028\u2029]` (the JVM `.` also excludes U+0085). Null when the pattern is
 * outside the portable subset or does not compile.
 */
internal fun runtimeFormV3Regex(pattern: String): Regex? =
    runtimeFormV3PortableSource(pattern)?.let { source -> runCatching { Regex(source) }.getOrNull() }

/** The `.` translation; U+2028 / U+2029 are inserted as the characters themselves. */
internal const val RUNTIME_FORM_V3_ECMASCRIPT_DOT: String = "[^\\n\\r\u2028\u2029]"
private const val RUNTIME_FORM_V3_SYNTAX_CHARACTERS = "^$\\.*+?()[]{}|/"
private val RUNTIME_FORM_V3_BRACE_QUANTIFIER = Regex("""^\{([0-9]+)(,([0-9]*))?\}""")

/** The pattern's code points with the UTF-16 offset each starts at (one extra offset: the length). */
private class RuntimeFormV3CodePoints(private val text: String) {
    private val codes = mutableListOf<Int>()
    private val starts = mutableListOf<Int>()

    init {
        var i = 0
        while (i < text.length) {
            starts += i
            val high = text[i]
            if (high.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                codes += ((high.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + 0x10000
                i += 2
            } else {
                codes += high.code
                i += 1
            }
        }
        starts += text.length
    }

    val size: Int get() = codes.size
    fun at(index: Int): Int? = codes.getOrNull(index)
    fun slice(from: Int, to: Int): String = text.substring(starts[from], starts[to])
}

private fun isRuntimeFormV3Syntax(code: Int?): Boolean =
    code != null && code < 0x80 && code.toChar() in RUNTIME_FORM_V3_SYNTAX_CHARACTERS

/** The Kotlin regex source for a portable [pattern] (translated as [runtimeFormV3Regex] says), or null. */
private fun runtimeFormV3PortableSource(pattern: String): String? {
    val chars = RuntimeFormV3CodePoints(pattern)
    val out = StringBuilder()
    var i = 0
    var depth = 0
    // Whether the previous token can take a quantifier (a literal, ., a class or a group).
    var quantifiable = false

    // An escaped syntax character (and `-` inside a class) at chars[i] == '\'; returns it or null.
    fun escaped(inClass: Boolean): Int? {
        val next = chars.at(i + 1)
        if (next == null || !(isRuntimeFormV3Syntax(next) || (inClass && next == '-'.code))) return null
        i += 2
        return next
    }

    // One class member at chars[i]: its code point and whether it is an unescaped "-"; null when not portable.
    fun classAtom(): Pair<Int, Boolean>? {
        val char = chars.at(i) ?: return null
        if (char == '['.code || char == ']'.code) return null
        if (char == '\\'.code) return escaped(inClass = true)?.let { it to false }
        if (char == '&'.code && chars.at(i + 1) == '&'.code) return null
        i += 1
        return char to (char == '-'.code)
    }

    // A class at chars[i] == '['; consumed and copied verbatim, false when outside the subset.
    fun characterClass(): Boolean {
        val from = i
        i += 1
        if (chars.at(i) == '^'.code) i += 1
        var first = true
        while (chars.at(i) != ']'.code) {
            val start = classAtom() ?: return false
            if (start.second) {
                // A literal "-" only first or last, never a range endpoint.
                if (!first && chars.at(i) != ']'.code) return false
            } else if (chars.at(i) == '-'.code && chars.at(i + 1) != ']'.code) {
                i += 1
                val end = classAtom() ?: return false
                if (end.second || end.first < start.first) return false
            }
            first = false
        }
        if (first) return false // [] and [^]
        i += 1
        out.append(chars.slice(from, i))
        return true
    }

    // A brace quantifier at chars[i] == '{': {m}, {m,} or {m,n} with m <= n.
    fun braceQuantifier(): Boolean {
        val match = RUNTIME_FORM_V3_BRACE_QUANTIFIER.find(chars.slice(i, chars.size)) ?: return false
        val min = match.groupValues[1].toLongOrNull() ?: return false
        val max = match.groupValues[3]
        if (max.isNotEmpty() && (max.toLongOrNull() ?: return false) < min) return false
        out.append(match.value)
        i += match.value.length // ASCII only: code points == chars
        return true
    }

    while (i < chars.size) {
        when (val char = chars.at(i)!!) {
            '^'.code, '|'.code -> {
                out.append(char.toChar())
                i += 1
                quantifiable = false
            }
            '$'.code -> {
                out.append("\\z")
                i += 1
                quantifiable = false
            }
            '('.code -> {
                if (chars.at(i + 1) == '?'.code) return null
                out.append('(')
                i += 1
                depth += 1
                quantifiable = false
            }
            ')'.code -> {
                if (depth == 0) return null
                out.append(')')
                i += 1
                depth -= 1
                quantifiable = true
            }
            '*'.code, '+'.code, '?'.code, '{'.code -> {
                if (!quantifiable) return null
                if (char == '{'.code) {
                    if (!braceQuantifier()) return null
                } else {
                    out.append(char.toChar())
                    i += 1
                }
                quantifiable = false // no lazy (*?), possessive (*+) or stacked quantifiers
            }
            '['.code -> {
                if (!characterClass()) return null
                quantifiable = true
            }
            ']'.code, '}'.code -> return null
            '\\'.code -> {
                val from = i
                escaped(inClass = false) ?: return null
                out.append(chars.slice(from, i))
                quantifiable = true
            }
            '.'.code -> {
                out.append(RUNTIME_FORM_V3_ECMASCRIPT_DOT)
                i += 1
                quantifiable = true
            }
            else -> {
                out.append(chars.slice(i, i + 1))
                i += 1
                quantifiable = true
            }
        }
    }
    return if (depth == 0) out.toString() else null
}

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
