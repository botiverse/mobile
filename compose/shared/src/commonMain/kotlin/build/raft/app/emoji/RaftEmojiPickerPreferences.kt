package build.raft.app.emoji

import build.raft.app.data.SharedPreferenceStore

/** One emoji the user picked: its [unified] without a skin tone, and how many times. */
internal data class RaftEmojiRecent(val unified: String, val count: Int)

/**
 * The picker's "Frequently used" row, as emoji-picker-react keeps it (`setSuggested`, the default
 * `SuggestionMode.FREQUENT`): picking an emoji moves it to the front and counts it, the list keeps
 * the [Capacity] most recently picked, and the row shows them by count, most first, ties in the
 * order picked last. Unlike the library, an emoji is kept once whatever its skin tone (the
 * library keeps a toned pick apart but shows it in the current tone, so it can show twice): the
 * row shows each in the current tone.
 */
internal object RaftEmojiRecents {
    /** emoji-picker-react keeps 14. */
    const val Capacity: Int = 14

    /** The list after picking [unified] (an emoji without a skin tone). */
    fun record(recents: List<RaftEmojiRecent>, unified: String): List<RaftEmojiRecent> {
        val existing = recents.firstOrNull { it.unified == unified }
        val picked = RaftEmojiRecent(unified, (existing?.count ?: 0) + 1)
        return (listOf(picked) + recents.filter { it.unified != unified }).take(Capacity)
    }

    /** The order the row shows: by count, most first; ties keep their order (most recent first). */
    fun shown(recents: List<RaftEmojiRecent>): List<RaftEmojiRecent> = recents.sortedByDescending { it.count }

    fun encode(recents: List<RaftEmojiRecent>): String = recents.joinToString(",") { "${it.unified}:${it.count}" }

    /** Reads [encode]'s text; anything it cannot read is skipped. */
    fun decode(value: String?): List<RaftEmojiRecent> =
        value.orEmpty()
            .split(',')
            .mapNotNull { item ->
                val separator = item.lastIndexOf(':')
                if (separator <= 0) return@mapNotNull null
                val unified = item.substring(0, separator).trim().lowercase()
                val count = item.substring(separator + 1).trim().toIntOrNull() ?: return@mapNotNull null
                if (count <= 0 || unified.any { it != '-' && !it.isLetterOrDigit() }) return@mapNotNull null
                RaftEmojiRecent(unified, count)
            }
            .distinctBy { it.unified }
            .take(Capacity)
}

/**
 * The picker's stored choices for one signed-in user ([ownerKey], the environment and account the
 * message body font size is kept for): the skin tone and the frequently used emoji. Without an
 * owner (no session yet) they are kept for the picker's lifetime only, so one account's emoji
 * never show in another's picker.
 */
internal class RaftEmojiPickerPreferences(
    store: SharedPreferenceStore?,
    ownerKey: String?,
) {
    private val owner: String? = ownerKey?.trim()?.takeIf { it.isNotEmpty() }
    private val backing: SharedPreferenceStore = if (store != null && owner != null) store else RaftEmojiInMemoryPreferenceStore()
    private val skinToneKey = "$KeyPrefix.skinTone.${owner.orEmpty()}"
    private val recentsKey = "$KeyPrefix.recent.${owner.orEmpty()}"

    fun readSkinTone(): RaftEmojiSkinTone = RaftEmojiSkinTone.fromStorageKey(backing.getString(skinToneKey))

    fun writeSkinTone(tone: RaftEmojiSkinTone) {
        backing.putString(skinToneKey, tone.storageKey)
    }

    fun readRecents(): List<RaftEmojiRecent> = RaftEmojiRecents.decode(backing.getString(recentsKey))

    /** Counts a pick of [emoji] and returns the new list. */
    fun recordPick(emoji: RaftEmoji): List<RaftEmojiRecent> {
        val next = RaftEmojiRecents.record(readRecents(), emoji.unified)
        backing.putString(recentsKey, RaftEmojiRecents.encode(next))
        return next
    }

    companion object {
        const val KeyPrefix: String = "raft.emojiPicker"
    }
}

internal class RaftEmojiInMemoryPreferenceStore : SharedPreferenceStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** The "Frequently used" row: [recents] in the order shown; emoji no longer in [catalog] are left out. */
internal fun raftEmojiFrequentlyUsed(
    catalog: RaftEmojiCatalog,
    recents: List<RaftEmojiRecent>,
): List<RaftEmoji> = RaftEmojiRecents.shown(recents).mapNotNull { catalog.byUnified(it.unified) }
