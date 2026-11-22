package build.raft.app.emoji

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftEmojiPickerPreferencesTest {
    private val catalog = RaftEmojiCatalog.All

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
}
