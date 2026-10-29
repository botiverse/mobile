package build.raft.app.emoji

import android.graphics.Paint
import android.os.Build

private object RaftEmojiGlyphCache {
    val paint = Paint()
    val drawable = HashMap<String, Boolean>()
}

/**
 * `Paint.hasGlyph` on the default typeface (the system emoji font), asked once per emoji: the
 * picker asks on the main thread, the lock only guards a stray caller.
 */
internal actual fun raftCanDrawEmoji(emoji: String): Boolean {
    // hasGlyph is API 23; the module's minSdk is lower than the app's (29).
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    return synchronized(RaftEmojiGlyphCache) {
        RaftEmojiGlyphCache.drawable.getOrPut(emoji) { RaftEmojiGlyphCache.paint.hasGlyph(emoji) }
    }
}
