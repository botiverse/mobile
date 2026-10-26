package build.raft.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The pieces Raft's two closed, inert card catalogs share (botiverse/slock 2d901b90
 * `packages/shared/src/cardElements.ts`): App link cards ([RaftAppLinkCard], RFC 076) and
 * first-party action cards ([ActionCardSpecCard], `action-card.v1`). Each catalog builds its own
 * element union from these, so neither reads the other's schema: a shared element type
 * ([CardStack], [CardRelativeTime], [CardDuration]) is a member of each catalog's union, and every
 * element of either catalog is a [CardElement] so one bounded-tree check ([checkCardBoundedTree])
 * serves both.
 *
 * A change here must keep the link card tests and the action-card.v1 vector passing.
 *
 * Card text lengths count Unicode code points ([cardCodePoints]), so Go, Kotlin and TypeScript
 * agree.
 */

/** A node of a card's bounded tree. Only a Stack has children. */
interface CardElement {
    /** The element ids under this one, top to bottom (empty for everything but a Stack). */
    val childIds: List<String> get() = emptyList()
}

/** Element ids: `^[a-zA-Z][a-zA-Z0-9_-]{0,63}$` (`CARD_ELEMENT_ID`). */
internal val CARD_ELEMENT_ID = Regex("^[a-zA-Z][a-zA-Z0-9_-]{0,63}$")

/** Names a JavaScript object treats specially; never an element id (or, in action cards, any other key). */
val CARD_RESERVED_IDS: Set<String> = setOf("constructor", "prototype", "__proto__")

/** At most this many children under one Stack (`cardChildren(12)`). */
internal const val CARD_STACK_MAX_CHILDREN = 12

/** Unicode code points in [value]; a lone surrogate counts as one (as JavaScript's `for...of`). */
fun cardCodePoints(value: String): Int {
    var count = 0
    var index = 0
    while (index < value.length) {
        val char = value[index]
        index += if (char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/** A string of [min]-[max] code points (`cardText`). */
internal fun isCardText(value: String, min: Int, max: Int): Boolean = cardCodePoints(value).let { it in min..max }

/** `cardBadgeTone`: neutral, review, progress, warning, action, done, closed. */
enum class CardBadgeTone(val wire: String) {
    Neutral("neutral"),
    Review("review"),
    Progress("progress"),
    Warning("warning"),
    Action("action"),
    Done("done"),
    Closed("closed"),
    ;

    companion object {
        fun fromWire(value: String?): CardBadgeTone? = entries.firstOrNull { it.wire == value }
    }
}

/** A Stack's layout: a column (the v1 behaviour) or a row that may wrap; spacing is the client's. */
enum class CardStackDirection(val wire: String) {
    Column("column"),
    Row("row"),
}

/**
 * `Stack` (`cardStackElement`): [children] (element ids, at most 12) in order. [children] is null
 * when the card did not write the key (an empty Stack), which is not the same JSON as `[]`.
 * Only a row can [wrap].
 */
data class CardStack(
    val direction: CardStackDirection = CardStackDirection.Column,
    val wrap: Boolean = false,
    val children: List<String>? = null,
) : ActionCardSpecDisplayElement {
    override val childIds: List<String> get() = children.orEmpty()
}

/** `RelativeTime` (`cardRelativeTimeElement`): [at] is strict RFC 3339 UTC; the client words it ("3 minutes ago"). */
data class CardRelativeTime(val at: String) : ActionCardSpecDisplayElement

/**
 * `Duration` (`cardDurationElement`): no [end] = still running, the client ticks; with [end] =
 * finished, frozen. Both strict RFC 3339 UTC, [end] not before [start] (millisecond precision).
 */
data class CardDuration(val start: String, val end: String? = null) : ActionCardSpecDisplayElement

/** Why [checkCardBoundedTree] refuses a tree. */
enum class CardTreeProblem {
    /** Too many elements, or an element with a reserved id. */
    Size,

    /** Not one tree from the root reaching every element once within the depth limit. */
    Tree,
}

/**
 * Whether a parsed card is one bounded tree (`checkBoundedTree`): at most [maxElements] elements
 * and no reserved id ([CardTreeProblem.Size]); every element reached exactly once from [root]
 * through Stack children, the root at depth 0 and none deeper than [maxDepth]
 * ([CardTreeProblem.Tree]). Null when it is.
 */
fun checkCardBoundedTree(
    root: String,
    elements: Map<String, CardElement>,
    maxElements: Int,
    maxDepth: Int,
): CardTreeProblem? {
    if (elements.size > maxElements || elements.keys.any { it in CARD_RESERVED_IDS }) return CardTreeProblem.Size
    val visited = HashSet<String>()
    fun visit(id: String, depth: Int): Boolean {
        val node = elements[id] ?: return false
        if (!visited.add(id) || depth > maxDepth) return false
        return node.childIds.all { visit(it, depth + 1) }
    }
    if (!visit(root, 0) || visited.size != elements.size) return CardTreeProblem.Tree
    return null
}

private val RFC3339_UTC = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?Z$")

/** Strict RFC 3339 UTC on a date that exists (protocol v3.2.3 §5.2; `isStrictRfc3339Utc`). */
fun isStrictRfc3339Utc(value: String): Boolean = cardRfc3339EpochMillis(value) != null

/**
 * [value] as epoch milliseconds (the fraction truncated to milliseconds, as `Date.parse`), or
 * null when it is not strict RFC 3339 UTC. The day check follows the shared code's
 * `Date.UTC(year, month, 0)`, which reads a year 0-99 as 1900-1999.
 */
internal fun cardRfc3339EpochMillis(value: String): Long? {
    val match = RFC3339_UTC.matchEntire(value) ?: return null
    val groups = match.groupValues
    val year = groups[1].toInt()
    val month = groups[2].toInt()
    val day = groups[3].toInt()
    val hour = groups[4].toInt()
    val minute = groups[5].toInt()
    val second = groups[6].toInt()
    if (month !in 1..12 || hour > 23 || minute > 59 || second > 59) return null
    if (day < 1 || day > daysInMonth(if (year in 0..99) year + 1900 else year, month)) return null
    val millis = groups[7].padEnd(3, '0').take(3).toLong()
    val days = daysFromCivil(year, month, day)
    return ((days * 24 + hour) * 60 + minute) * 60_000L + second * 1000L + millis
}

private fun daysInMonth(year: Int, month: Int): Int =
    when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

/** Days since 1970-01-01 in the proleptic Gregorian calendar (H. Hinnant's algorithm). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

/**
 * A public HTTPS URL (`publicPreviewUrl`, checked here without the WHATWG parser): https, no user
 * info, no port other than the default, no fragment, a dotted host that is not an IP literal, at
 * most 2,000 UTF-16 units. Link previews, link-card and action-card navigate buttons accept only
 * these.
 *
 * Stricter than the WHATWG reading in a few inputs a server never builds, so such a card is
 * refused (the client shows the legacy presentation) rather than read differently: whitespace,
 * control characters or `\` anywhere, `https:` without `//`, any `@` before the path, a
 * percent-escape or non-ASCII character in the host.
 */
fun isCardPublicUrl(raw: String): Boolean {
    if (raw.length > 2000 || !raw.startsWith("https://", ignoreCase = true)) return false
    if (raw.any { it.code <= 0x20 || it.code == 0x7f || it == '\\' }) return false
    val rest = raw.substring("https://".length)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    if ('@' in authority || '[' in authority || ']' in authority) return false
    val host = authority.substringBefore(':').lowercase()
    if (':' in authority) {
        // WHATWG drops the default port (`:443`, `:0443`, or an empty one): `url.port` is then "".
        val port = authority.substringAfter(':')
        if (!port.all(Char::isDigit) || (port.isNotEmpty() && port.trimStart('0') != "443")) return false
    }
    if (host.isEmpty() || '.' !in host) return false
    if (!host.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' }) return false
    // A host whose last label is a number is an IPv4 address to WHATWG (or a parse failure).
    val labels = host.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    val last = labels.last()
    val numeric = (last.isNotEmpty() && last.all(Char::isDigit)) ||
        (last.length >= 2 && last[0] == '0' && (last[1] == 'x') && last.drop(2).all { it in '0'..'9' || it in 'a'..'f' })
    if (numeric) return false
    // `url.hash` is "" for a bare trailing `#`.
    val fragment = rest.indexOf('#')
    return fragment < 0 || fragment == rest.length - 1
}

// ---------------------------------------------------------------------------
// Readers for the shared elements, as zod reads them: strict objects (or stripping, for an action
// card read: [CardUnknownKeys]), defaults filled in.

/** A JSON string's value; null for anything else (a number or `null` is not a string to zod). */
internal fun JsonElement?.cardString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON boolean's value; null for anything else (zod `z.boolean()` takes only `true` / `false`). */
internal fun JsonElement?.cardBoolean(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** A JSON number that is a safe integer (zod v4 `z.number().int()`: |n| <= 2^53 - 1), or null. */
internal fun JsonElement?.cardSafeInteger(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    val content = primitive.content
    if (content == "true" || content == "false") return null
    val number = content.toDoubleOrNull() ?: return null
    if (number.isNaN() || number.isInfinite() || number != kotlin.math.floor(number)) return null
    if (kotlin.math.abs(number) > 9_007_199_254_740_991.0) return null
    return number.toLong()
}

/** The object's keys are all among [allowed] (zod `.strict()`). */
internal fun JsonObject.hasOnlyKeys(allowed: Set<String>): Boolean = allowed.containsAll(keys)

/**
 * What a card object does with a key it does not know (`CardUnknownKeys`): [Strict] refuses it
 * (link cards; action cards as a server emits them), [Strip] drops it without looking at its value
 * (action cards as a client reads them, so an older App still shows a card with a newer prop).
 */
enum class CardUnknownKeys {
    Strict,
    Strip,
    ;

    /** Whether [obj] passes: [Strip] takes any key, [Strict] only [allowed] (`cardObject`). */
    internal fun accepts(obj: JsonObject, allowed: Set<String>): Boolean = this == Strip || obj.hasOnlyKeys(allowed)

    /**
     * An element object other than a Stack (`cardElementObject`): `{type, props}`; with [Strip] any
     * other key is dropped too, but `children` belongs to a Stack and is refused either way.
     */
    internal fun acceptsElement(obj: JsonObject): Boolean = if (this == Strip) "children" !in obj else obj.hasOnlyKeys(setOf("type", "props"))
}

/** `cardStackElement`: `{type: "Stack", props: {direction?, wrap?}, children?}`, or null. */
internal fun readCardStack(obj: JsonObject, unknownKeys: CardUnknownKeys = CardUnknownKeys.Strict): CardStack? {
    if (!unknownKeys.accepts(obj, setOf("type", "props", "children"))) return null
    val props = obj["props"] as? JsonObject ?: return null
    if (!unknownKeys.accepts(props, setOf("direction", "wrap"))) return null
    val direction =
        if ("direction" in props) {
            CardStackDirection.entries.firstOrNull { it.wire == props["direction"].cardString() } ?: return null
        } else {
            CardStackDirection.Column
        }
    val wrap = if ("wrap" in props) props["wrap"].cardBoolean() ?: return null else false
    if (wrap && direction != CardStackDirection.Row) return null
    val children =
        if ("children" in obj) {
            val list = obj["children"] as? JsonArray ?: return null
            if (list.size > CARD_STACK_MAX_CHILDREN) return null
            list.map { child -> child.cardString()?.takeIf(CARD_ELEMENT_ID::matches) ?: return null }
        } else {
            null
        }
    return CardStack(direction, wrap, children)
}

/** `cardRelativeTimeElement`: `{type: "RelativeTime", props: {at}}`, or null. */
internal fun readCardRelativeTime(obj: JsonObject, unknownKeys: CardUnknownKeys = CardUnknownKeys.Strict): CardRelativeTime? {
    if (!unknownKeys.acceptsElement(obj)) return null
    val props = obj["props"] as? JsonObject ?: return null
    if (!unknownKeys.accepts(props, setOf("at"))) return null
    val at = props["at"].cardString()?.takeIf(::isCardTimestamp) ?: return null
    return CardRelativeTime(at)
}

/** `cardDurationElement`: `{type: "Duration", props: {start, end?}}`, `end` not before `start`, or null. */
internal fun readCardDuration(obj: JsonObject, unknownKeys: CardUnknownKeys = CardUnknownKeys.Strict): CardDuration? {
    if (!unknownKeys.acceptsElement(obj)) return null
    val props = obj["props"] as? JsonObject ?: return null
    if (!unknownKeys.accepts(props, setOf("start", "end"))) return null
    val start = props["start"].cardString()?.takeIf(::isCardTimestamp) ?: return null
    val end = if ("end" in props) props["end"].cardString()?.takeIf(::isCardTimestamp) ?: return null else null
    if (end != null && cardRfc3339EpochMillis(end)!! < cardRfc3339EpochMillis(start)!!) return null
    return CardDuration(start, end)
}

/** `cardRfc3339Utc`: at most 40 characters, strict RFC 3339 UTC. */
private fun isCardTimestamp(value: String): Boolean = value.length <= 40 && isStrictRfc3339Utc(value)
