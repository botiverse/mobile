package build.raft.app.emoji

import build.raft.app.raftEmojiPickerHeaderIndices
import build.raft.app.raftEmojiPickerSectionAt
import build.raft.app.raftEmojiPickerSections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftEmojiPickerPreferencesTest {
    private val catalog = RaftEmojiCatalog.Default

    private fun emoji(unified: String): RaftEmoji = requireNotNull(catalog.byUnified(unified))

    @Test
    fun recordMovesThePickToTheFrontAndCountsIt() {
        var recents = emptyList<RaftEmojiRecent>()
        recents = RaftEmojiRecents.record(recents, "1f44d")
        recents = RaftEmojiRecents.record(recents, "1f600")
        recents = RaftEmojiRecents.record(recents, "1f44d")
        assertEquals(listOf(RaftEmojiRecent("1f44d", 2), RaftEmojiRecent("1f600", 1)), recents)
    }

    @Test
    fun recordKeepsTheMostRecentFourteen() {
        var recents = emptyList<RaftEmojiRecent>()
        val picked = catalog.emojis.take(RaftEmojiRecents.Capacity + 3).map { it.unified }
        picked.forEach { recents = RaftEmojiRecents.record(recents, it) }
        assertEquals(14, RaftEmojiRecents.Capacity)
        assertEquals(RaftEmojiRecents.Capacity, recents.size)
        // The three picked first fell off; the last pick leads.
        assertEquals(picked.takeLast(RaftEmojiRecents.Capacity).reversed(), recents.map { it.unified })
    }

    @Test
    fun shownOrderIsByCountThenMostRecent() {
        val recents =
            listOf(
                RaftEmojiRecent("1f600", 1),
                RaftEmojiRecent("1f44d", 3),
                RaftEmojiRecent("1f525", 1),
                RaftEmojiRecent("2764-fe0f", 3),
            )
        assertEquals(
            listOf("1f44d", "2764-fe0f", "1f600", "1f525"),
            RaftEmojiRecents.shown(recents).map { it.unified },
        )
    }

    @Test
    fun encodeAndDecodeRoundTripAndSkipJunk() {
        val recents = listOf(RaftEmojiRecent("1f44d", 2), RaftEmojiRecent("2764-fe0f", 1))
        assertEquals(recents, RaftEmojiRecents.decode(RaftEmojiRecents.encode(recents)))
        assertEquals(
            listOf(RaftEmojiRecent("1f44d", 2)),
            RaftEmojiRecents.decode("1f44d:2,:3,1f600:x,1f525:0,1f44d:5,a b:1"),
        )
        assertTrue(RaftEmojiRecents.decode(null).isEmpty())
        assertTrue(RaftEmojiRecents.decode("").isEmpty())
    }

    @Test
    fun preferencesAreKeptPerOwner() {
        val store = RaftEmojiInMemoryPreferenceStore()
        val alice = RaftEmojiPickerPreferences(store, "https://api.example|alice")
        val bob = RaftEmojiPickerPreferences(store, "https://api.example|bob")
        alice.writeSkinTone(RaftEmojiSkinTone.MediumDark)
        alice.recordPick(emoji("1f44d"))
        assertEquals(RaftEmojiSkinTone.MediumDark, alice.readSkinTone())
        assertEquals(RaftEmojiSkinTone.Neutral, bob.readSkinTone())
        assertTrue(bob.readRecents().isEmpty())
        // A new picker for the same owner reads what the last one stored.
        val aliceAgain = RaftEmojiPickerPreferences(store, "https://api.example|alice")
        assertEquals(RaftEmojiSkinTone.MediumDark, aliceAgain.readSkinTone())
        assertEquals(listOf(RaftEmojiRecent("1f44d", 1)), aliceAgain.readRecents())
    }

    @Test
    fun withoutAnOwnerNothingIsStored() {
        val store = RaftEmojiInMemoryPreferenceStore()
        val anonymous = RaftEmojiPickerPreferences(store, null)
        anonymous.writeSkinTone(RaftEmojiSkinTone.Dark)
        anonymous.recordPick(emoji("1f44d"))
        assertEquals(RaftEmojiSkinTone.Dark, anonymous.readSkinTone())
        assertNull(store.getString("${RaftEmojiPickerPreferences.KeyPrefix}.skinTone."))
        assertNull(store.getString("${RaftEmojiPickerPreferences.KeyPrefix}.recent."))
        assertTrue(RaftEmojiPickerPreferences(store, null).readRecents().isEmpty())
    }

    @Test
    fun aTonedPickIsKeptOnceAndShownInTheCurrentTone() {
        val preferences = RaftEmojiPickerPreferences(RaftEmojiInMemoryPreferenceStore(), "owner")
        preferences.writeSkinTone(RaftEmojiSkinTone.Light)
        preferences.recordPick(emoji("1f44d"))
        preferences.writeSkinTone(RaftEmojiSkinTone.Dark)
        preferences.recordPick(emoji("1f44d"))
        val row = raftEmojiFrequentlyUsed(catalog, preferences.readRecents())
        assertEquals(listOf("1f44d"), row.map { it.unified })
        assertEquals("👍🏿", row.single().withSkinTone(preferences.readSkinTone()))
    }

    @Test
    fun frequentlyUsedLeadsTheSectionsOnlyWhenThereIsAny() {
        val empty = raftEmojiPickerSections(catalog, emptyList())
        assertEquals(RaftEmojiCategory.entries.toList(), empty.map { it.category })

        val withRecents = raftEmojiPickerSections(catalog, listOf(emoji("1f44d"), emoji("1f600")))
        assertNull(withRecents.first().category)
        assertEquals(listOf("1f44d", "1f600"), withRecents.first().emojis.map { it.unified })
        assertEquals(RaftEmojiCategory.entries.size + 1, withRecents.size)

        val headers = raftEmojiPickerHeaderIndices(withRecents)
        assertEquals(0, headers[0])
        assertEquals(3, headers[1])
        assertEquals(3 + 1 + withRecents[1].emojis.size, headers[2])
        assertEquals(0, raftEmojiPickerSectionAt(headers, 0))
        assertEquals(0, raftEmojiPickerSectionAt(headers, 2))
        assertEquals(1, raftEmojiPickerSectionAt(headers, 3))
        assertEquals(1, raftEmojiPickerSectionAt(headers, headers[2] - 1))
        assertEquals(withRecents.size - 1, raftEmojiPickerSectionAt(headers, Int.MAX_VALUE))
    }
}
