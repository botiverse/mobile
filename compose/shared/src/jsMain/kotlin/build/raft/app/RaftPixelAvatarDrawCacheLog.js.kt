package build.raft.app

/**
 * js actual for #195 [RaftPixelAvatarDrawCache] diagnostic logger. Mirrors the OHOS
 * no-op behavior — counters update but no log is emitted, since the web runtime has
 * its own debug pipeline and we don't want to spam `console.log`.
 */
internal actual fun logPixelAvatarDrawCache(message: String) {
    // Intentionally no-op on js. Counter state is preserved for any future reader.
}
