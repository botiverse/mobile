package build.raft.app.emoji

/**
 * The emoji picker's categories: emoji-picker-react's, in its order ([RaftEmojiData.CategoryIds]).
 * The picker's own "Frequently used" row is not one of them.
 */
internal enum class RaftEmojiCategory(val id: String) {
    SmileysPeople("smileys_people"),
    AnimalsNature("animals_nature"),
    FoodDrink("food_drink"),
    TravelPlaces("travel_places"),
    Activities("activities"),
    Objects("objects"),
    Symbols("symbols"),
    Flags("flags");

    companion object {
        fun fromId(id: String): RaftEmojiCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * emoji-picker-react `SkinTones`: [Neutral] is the emoji as it is; the others are the Fitzpatrick
 * modifiers U+1F3FB..U+1F3FF. [storageKey] is the library's value, stored as the user's choice.
 */
internal enum class RaftEmojiSkinTone(val storageKey: String) {
    Neutral("neutral"),
    Light("1f3fb"),
    MediumLight("1f3fc"),
    Medium("1f3fd"),
    MediumDark("1f3fe"),
    Dark("1f3ff");

    companion object {
        fun fromStorageKey(value: String?): RaftEmojiSkinTone =
            entries.firstOrNull { it.storageKey == value?.trim()?.lowercase() } ?: Neutral
    }
}

/**
 * One emoji of the picker. [unified] is its code points in hex ("1f44d", "2764-fe0f"); [emoji]
 * those code points as text, the value a reaction or a section emoji carries (Web sends
 * emoji-picker-react's `emoji`, the same text).
 */
internal class RaftEmoji(
    val category: RaftEmojiCategory,
    val unified: String,
    val emoji: String,
    /** The Unicode Emoji version that added it (emoji-picker-react `a`). */
    val addedIn: Double,
    /** The variation for each tone after [RaftEmojiSkinTone.Neutral], as text; empty without skin tones. */
    private val toneVariants: List<String>,
    /** English names, lower case; the last is the emoji's name. */
    val names: List<String>,
    /** Chinese keywords (CLDR zh), lower case; the first is the emoji's Chinese name. */
    val zhKeywords: List<String>,
) {
    val hasSkinTones: Boolean get() = toneVariants.isNotEmpty()

    val englishName: String get() = names.last()

    val chineseName: String get() = zhKeywords.firstOrNull() ?: englishName

    /** emoji-picker-react `emojiUnified(emoji, skinTone)` as text: the emoji itself without skin tones. */
    fun withSkinTone(tone: RaftEmojiSkinTone): String =
        if (tone == RaftEmojiSkinTone.Neutral || toneVariants.isEmpty()) emoji else toneVariants[tone.ordinal - 1]

    /** emoji-picker-react `hasMatch` (any name contains the term), plus the Chinese keywords. */
    fun matches(normalizedTerm: String): Boolean =
        names.any { it.contains(normalizedTerm) } || zhKeywords.any { it.contains(normalizedTerm) }

    /**
     * This emoji as [canDraw] lets it show: null when the emoji itself cannot be drawn; otherwise
     * with every skin tone [canDraw] rejects falling back to the emoji without a tone.
     */
    fun drawable(canDraw: (String) -> Boolean): RaftEmoji? {
        if (!canDraw(emoji)) return null
        if (toneVariants.isEmpty()) return this
        val tones = toneVariants.map { if (canDraw(it)) it else emoji }
        if (tones == toneVariants) return this
        return RaftEmoji(category, unified, emoji, addedIn, tones, names, zhKeywords)
    }

    override fun toString(): String = "RaftEmoji($unified)"
}

/** The picker's whole emoji set, parsed from [RaftEmojiData] the first time it is read. */
internal class RaftEmojiCatalog private constructor(
    val emojis: List<RaftEmoji>,
) {
    val byCategory: Map<RaftEmojiCategory, List<RaftEmoji>> = emojis.groupBy { it.category }

    private val unifiedIndex: Map<String, RaftEmoji> = emojis.associateBy { it.unified }

    fun byUnified(unified: String): RaftEmoji? = unifiedIndex[unified.lowercase()]

    /**
     * Every emoji whose English name or Chinese keyword contains [query], case-insensitively, in
     * the picker's order (emoji-picker-react filters the same way: `name.includes(term)`). A blank
     * query matches nothing: the picker shows its categories then.
     */
    fun search(query: String): List<RaftEmoji> {
        val term = raftEmojiSearchTerm(query)
        if (term.isEmpty()) return emptyList()
        return emojis.filter { it.matches(term) }
    }

    /**
     * The emoji [canDraw] accepts ([RaftEmoji.drawable]): what the picker offers, so a device
     * without a newer emoji's glyph (Android 10-13 fonts lack Emoji 12.1+) shows no empty box.
     * Sections, search and the frequently used row all read the result.
     */
    fun drawable(canDraw: (String) -> Boolean = ::raftCanDrawEmoji): RaftEmojiCatalog =
        RaftEmojiCatalog(emojis.mapNotNull { it.drawable(canDraw) })

    companion object {
        /** Every emoji of [RaftEmojiData], drawable or not. */
        val All: RaftEmojiCatalog by lazy {
            parse(RaftEmojiData.CategoryIds, RaftEmojiData.categoryRows())
        }

        /** What the picker shows: [All] without the emoji this device cannot draw, filtered on first use. */
        val Default: RaftEmojiCatalog by lazy { All.drawable() }

        fun parse(categoryIds: List<String>, rows: List<List<String>>): RaftEmojiCatalog {
            require(categoryIds.size == rows.size) { "${categoryIds.size} categories, ${rows.size} row lists" }
            val emojis = ArrayList<RaftEmoji>(rows.sumOf { it.size })
            categoryIds.forEachIndexed { index, id ->
                val category = requireNotNull(RaftEmojiCategory.fromId(id)) { "unknown emoji category $id" }
                rows[index].forEach { row -> emojis += parseRow(category, row) }
            }
            return RaftEmojiCatalog(emojis)
        }

        private fun parseRow(category: RaftEmojiCategory, row: String): RaftEmoji {
            val fields = row.split('\t')
            require(fields.size == 5) { "emoji row has ${fields.size} fields: $row" }
            val (unified, addedIn, tones, names, zh) = fields
            return RaftEmoji(
                category = category,
                unified = unified,
                emoji = raftEmojiFromUnified(unified),
                addedIn = addedIn.toDouble(),
                toneVariants = tones.splitNonEmpty(',').map(::raftEmojiFromUnified),
                names = names.splitNonEmpty('|'),
                zhKeywords = zh.splitNonEmpty('|'),
            )
        }

        private fun String.splitNonEmpty(separator: Char): List<String> =
            if (isEmpty()) emptyList() else split(separator)
    }
}

/** What the picker searches for: the query trimmed and in lower case. */
internal fun raftEmojiSearchTerm(query: String): String = query.trim().lowercase()

/** emoji-picker-react `parseNativeEmoji`: "1f44d-1f3fb" to its code points as text. */
internal fun raftEmojiFromUnified(unified: String): String {
    val builder = StringBuilder()
    unified.split('-').forEach { hex ->
        val codePoint = hex.toInt(16)
        if (codePoint >= 0x10000) {
            val offset = codePoint - 0x10000
            builder.append(Char(0xD800 + (offset shr 10)))
            builder.append(Char(0xDC00 + (offset and 0x3FF)))
        } else {
            builder.append(Char(codePoint))
        }
    }
    return builder.toString()
}
