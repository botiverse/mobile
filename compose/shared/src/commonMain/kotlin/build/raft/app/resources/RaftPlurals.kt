package build.raft.app.resources

import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * CLDR plural categories for count strings.
 *
 * The catalogs have no <plurals>. The call site chooses `<base>_one` for a count of 1 and
 * `<base>_many` / `<base>_other` (or the bare `<base>`) otherwise, which cannot express Russian's
 * one / few / many / other (1, 21, 101 / 2-4, 22-24 / 0, 5-20, 25-30). The extra forms live in a
 * separate optional file per language, `composeResources/files/plurals/<language>.json`:
 *
 *   { "thread_message_count": { "one": "%1$d сообщение", "few": "%1$d сообщения", "many": "%1$d сообщений" } }
 *
 * An entry may name the 1-based argument that holds the count (`"arg": 2`); by default it is the first
 * argument that is an integer or reads as a grouped number. `<base>` is the resource key without its `_one` / `_many` / `_other` suffix. Each form is a normal
 * format string (`%N$d`, `%N$s`). The string layer ([stringResource], [StringDesc.resolve]) uses a
 * form when the language has an entry for the base of the key being resolved and the entry has the
 * category the count falls in; in every other case (no file, no entry, a category the entry lacks,
 * no numeric argument) the catalog string the call site chose is shown, so `_other` text is the
 * fallback for few / many. Only ru has a file today; en, es, pt, ko, ja and zh keep their two forms.
 */

/** The CLDR plural category of the integer [count] in [language] (`one`, `few`, `many`, `other`). */
internal fun raftPluralCategory(language: String, count: Long): String {
    val n = abs(count)
    return when (language.lowercase()) {
        "ru" -> {
            val mod10 = n % 10
            val mod100 = n % 100
            when {
                mod10 == 1L && mod100 != 11L -> "one"
                mod10 in 2L..4L && mod100 !in 12L..14L -> "few"
                else -> "many"
            }
        }
        "pt", "fr" -> if (n <= 1L) "one" else "other"
        "ja", "ko", "zh" -> "other"
        else -> if (n == 1L) "one" else "other"
    }
}

/** Plural forms per language: base key to category to format string. */
internal typealias RaftPluralForms = Map<String, Map<String, String>>

/** Parses a `files/plurals/<language>.json` document; malformed input yields no forms. */
internal fun parseRaftPluralForms(json: String): RaftPluralForms =
    runCatching {
        Json.parseToJsonElement(json).jsonObject.mapValues { (_, value) ->
            (value as JsonObject).mapNotNull { (category, text) -> (text as? JsonPrimitive)?.let { category to it.content } }.toMap()
        }
    }.getOrDefault(emptyMap())

private val PluralSuffixes = listOf("_one", "_many", "_other")

/** The base key of a count string: its resource key without the `_one` / `_many` / `_other` suffix. */
internal fun raftPluralBaseKey(resourceKey: String): String =
    PluralSuffixes.firstOrNull { resourceKey.endsWith(it) }?.let { resourceKey.removeSuffix(it) } ?: resourceKey

/**
 * The count a string formats: the 1-based argument [position] when given, else the first argument
 * that is an integer or reads as a grouped number ("1,200", a count the call site formatted).
 */
internal fun raftPluralCount(args: List<Any>, position: Int? = null): Long? {
    fun Any?.asCount(): Long? =
        when (this) {
            is Int, is Long, is Short, is Byte -> (this as Number).toLong()
            is String ->
                takeIf { text -> text.isNotEmpty() && text.all { it.isDigit() || it == ',' || it == '.' } }
                    ?.filter(Char::isDigit)?.toLongOrNull()
            else -> null
        }
    if (position != null) return args.getOrNull(position - 1).asCount()
    return args.firstNotNullOfOrNull { it.asCount() }
}

/** [template] with the positional `%N$s` / `%N$d` placeholders filled from [args]. */
internal fun raftFillPlaceholders(template: String, args: List<Any>): String =
    Regex("""%(\d+)\$[sd]""").replace(template) { match ->
        args.getOrNull(match.groupValues[1].toInt() - 1)?.toString() ?: match.value
    }

/**
 * The language's own form for the count in [args] of the string [resourceKey], or null when the
 * string should be shown as the catalog has it (see the file comment).
 */
internal fun raftPluralFormFor(forms: RaftPluralForms, language: String, resourceKey: String, args: List<Any>): String? {
    val entry = forms[raftPluralBaseKey(resourceKey)] ?: return null
    val count = raftPluralCount(args, entry["arg"]?.toIntOrNull()) ?: return null
    val template = entry[raftPluralCategory(language, count)] ?: return null
    return raftFillPlaceholders(template, args)
}
