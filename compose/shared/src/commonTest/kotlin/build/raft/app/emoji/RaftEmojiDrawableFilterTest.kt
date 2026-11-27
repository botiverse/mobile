package build.raft.app.emoji

import build.raft.app.thread.raftEmojiPickerSections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftEmojiDrawableFilterTest {
    private val all = RaftEmojiCatalog.All

    private val thumbsUp = requireNotNull(all.byUnified("1f44d"))
    private val thumbsDown = requireNotNull(all.byUnified("1f44e"))

    // A device that cannot draw 👎, anything added after Emoji 12.0 (as Android 10's font), or
    // the dark-toned 👍.
    private val undrawable: Set<String> =
        all.emojis.filter { it.addedIn > 12.0 }.map { it.emoji }.toSet() +
            thumbsDown.emoji +
            thumbsUp.withSkinTone(RaftEmojiSkinTone.Dark)

    private val drawable = all.drawable { it !in undrawable }

    @Test
    fun undrawableEmojiLeaveTheSections() {
        val shown = raftEmojiPickerSections(drawable, emptyList()).flatMap { it.emojis }.map { it.unified }.toSet()
        assertFalse(thumbsDown.unified in shown)
        assertTrue(thumbsUp.unified in shown)
        assertTrue(drawable.emojis.none { it.addedIn > 12.0 })
        assertTrue(drawable.emojis.size < all.emojis.size)
        assertEquals(all.emojis.size - drawable.emojis.size, all.emojis.count { it.emoji in undrawable })
        assertEquals(drawable.emojis.size, shown.size)
    }

    @Test
    fun anUndrawableSkinToneFallsBackToTheEmojiWithoutTone() {
        val filtered = requireNotNull(drawable.byUnified("1f44d"))
        assertEquals("👍", filtered.withSkinTone(RaftEmojiSkinTone.Dark))
        assertEquals("👍🏻", filtered.withSkinTone(RaftEmojiSkinTone.Light))
        assertTrue(filtered.hasSkinTones)
    }
}
