package build.raft.app

/**
 * Android actual for #195 [RaftPixelAvatarDrawCache] diagnostic logger. Emits at INFO level
 * under the `RaftPixelAvatarDrawCache` tag so smoke runs can grep logcat for hit-rate lines.
 * No PII / no avatar content is logged, only aggregate counters.
 */
internal actual fun logPixelAvatarDrawCache(message: String) {
    android.util.Log.i("SlockPixelAvatarDrawCache", message)
}
