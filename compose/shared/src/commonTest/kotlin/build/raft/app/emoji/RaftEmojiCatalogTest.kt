package build.raft.app.emoji

import build.raft.app.ThreadQuickReactionEmojis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RaftEmojiCatalogTest {
    private val catalog = RaftEmojiCatalog.All

    @Test
    fun generatedDataHasEveryCategoryInThePickersOrder() {
        assertEquals(RaftEmojiCategory.entries.map { it.id }, RaftEmojiData.CategoryIds)
        assertEquals(RaftEmojiData.EmojiCount, catalog.emojis.size)
        assertEquals(RaftEmojiData.CategoryCounts.sum(), catalog.emojis.size)
        RaftEmojiCategory.entries.forEachIndexed { index, category ->
            assertEquals(RaftEmojiData.CategoryCounts[index], catalog.byCategory[category].orEmpty().size, category.id)
        }
        // emoji-picker-react 4.19.1: the whole set Web shows (no emojiVersion cutoff), 1898 emoji.
        assertEquals(1898, catalog.emojis.size)
        assertTrue(catalog.emojis.size > 1800)
    }

    @Test
    fun generatedDataHasNoDuplicatesAndEveryEmojiIsNamed() {
        assertEquals(catalog.emojis.size, catalog.emojis.map { it.unified }.toSet().size)
        assertEquals(catalog.emojis.size, catalog.emojis.map { it.emoji }.toSet().size)
        catalog.emojis.forEach { emoji ->
            assertTrue(emoji.names.isNotEmpty(), emoji.unified)
            assertTrue(emoji.zhKeywords.isNotEmpty(), emoji.unified)
            assertTrue(emoji.emoji.isNotEmpty(), emoji.unified)
            assertTrue(emoji.addedIn > 0.0, emoji.unified)
        }
        assertEquals(RaftEmojiData.SkinToneEmojiCount, catalog.emojis.count { it.hasSkinTones })
    }

    @Test
    fun emojiTextIsItsCodePointsLikeWebSends() {
        assertEquals("👍", assertNotNull(catalog.byUnified("1f44d")).emoji)
        // U+FE0F stays: Web sends "❤️" (2764 FE0F), the same text the quick reactions carry.
        assertEquals("❤️", assertNotNull(catalog.byUnified("2764-fe0f")).emoji)
        assertEquals("🇨🇳", assertNotNull(catalog.byUnified("1f1e8-1f1f3")).emoji)
        assertEquals("👨‍💻", assertNotNull(catalog.byUnified("1f468-200d-1f4bb")).emoji)
    }

    @Test
    fun quickReactionsAreInThePicker() {
        val all = catalog.emojis.map { it.emoji }.toSet()
        ThreadQuickReactionEmojis.forEach { assertTrue(it in all, it) }
    }

    @Test
    fun searchMatchesEnglishNamesCaseInsensitively() {
        val thumbs = catalog.search("  Thumbs ").map { it.emoji }
        assertEquals(listOf("👍", "👎"), thumbs)
        // "+1" is one of 👍's names (emoji-picker-react `n`).
        assertTrue("👍" in catalog.search("+1").map { it.emoji })
        // Contains, not only prefix: "face" is in "grinning face".
        assertTrue("😀" in catalog.search("ning fa").map { it.emoji })
    }

    @Test
    fun searchMatchesChineseKeywords() {
        val thumbs = catalog.search("拇指").map { it.emoji }
        assertTrue("👍" in thumbs)
        assertTrue("👎" in thumbs)
        assertTrue("😺" in catalog.search("猫").map { it.emoji })
        assertTrue("🇨🇳" in catalog.search("中国").map { it.emoji })
        assertEquals("拇指向上", assertNotNull(catalog.byUnified("1f44d")).chineseName)
    }

    @Test
    fun searchKeepsThePickersOrderAndBlankMatchesNothing() {
        val hearts = catalog.search("heart")
        val order = catalog.emojis.withIndex().associate { it.value.unified to it.index }
        assertEquals(hearts.sortedBy { order.getValue(it.unified) }, hearts)
        assertTrue(catalog.search("").isEmpty())
        assertTrue(catalog.search("   ").isEmpty())
        assertTrue(catalog.search("zzzz-no-such-emoji").isEmpty())
    }

    @Test
    fun skinToneAppliesOnlyToEmojiThatHaveSkinTones() {
        val thumbsUp = assertNotNull(catalog.byUnified("1f44d"))
        assertTrue(thumbsUp.hasSkinTones)
        assertEquals("👍", thumbsUp.withSkinTone(RaftEmojiSkinTone.Neutral))
        assertEquals("👍🏻", thumbsUp.withSkinTone(RaftEmojiSkinTone.Light))
        assertEquals("👍🏿", thumbsUp.withSkinTone(RaftEmojiSkinTone.Dark))

        val grinning = assertNotNull(catalog.byUnified("1f600"))
        assertFalse(grinning.hasSkinTones)
        RaftEmojiSkinTone.entries.forEach { assertEquals("😀", grinning.withSkinTone(it)) }

        // emoji-picker-react picks the first variation with the tone: both people in it.
        val holdingHands = assertNotNull(catalog.byUnified("1f9d1-200d-1f91d-200d-1f9d1"))
        assertEquals(
            raftEmojiFromUnified("1f9d1-1f3fd-200d-1f91d-200d-1f9d1-1f3fd"),
            holdingHands.withSkinTone(RaftEmojiSkinTone.Medium),
        )
    }

    @Test
    fun skinToneStorageKeysAreTheLibrarys() {
        assertEquals(RaftEmojiSkinTone.MediumDark, RaftEmojiSkinTone.fromStorageKey("1f3fe"))
        assertEquals(RaftEmojiSkinTone.Neutral, RaftEmojiSkinTone.fromStorageKey("neutral"))
        assertEquals(RaftEmojiSkinTone.Neutral, RaftEmojiSkinTone.fromStorageKey(null))
        assertEquals(RaftEmojiSkinTone.Neutral, RaftEmojiSkinTone.fromStorageKey("bogus"))
    }

    @Test
    fun unifiedToTextHandlesSupplementaryAndBmpCodePoints() {
        assertEquals("❤️", raftEmojiFromUnified("2764-fe0f"))
        assertEquals("😀", raftEmojiFromUnified("1f600"))
    }
}
