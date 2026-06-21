package ai.slock.compose

/**
 * OHOS actual for #195 [SlockPixelAvatarDrawCache] diagnostic logger. Counters in
 * [SlockPixelAvatarDrawCache] still update on OHOS (so any future cross-platform debug
 * tooling can read them), but no log line is emitted — OHOS profiling routes through
 * `HiTraceMeter` / `hilog` and we don't want to pollute that pipeline with per-frame
 * Compose diagnostic noise.
 */
internal actual fun logPixelAvatarDrawCache(message: String) {
    // Intentionally no-op on OHOS. Counter state is preserved for any future reader.
}
