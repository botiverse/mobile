package build.raft.app.emoji

import build.raft.app.RaftCoreInternalApi

/**
 * Whether this device's fonts draw [emoji] as one glyph. Android asks the system font
 * (`Paint.hasGlyph`): Android 10-13 lack the emoji added after their release. iOS and desktop
 * answer true. Only the emoji picker asks; reactions show whatever text they carry.
 */
@RaftCoreInternalApi expect fun raftCanDrawEmoji(emoji: String): Boolean
