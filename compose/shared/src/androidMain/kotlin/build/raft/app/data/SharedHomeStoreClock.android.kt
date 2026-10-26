package build.raft.app.data

/**
 * Android legacy wall-clock source retained for the Datetime build rollback.
 *
 * `System.currentTimeMillis()` is available on the Android JVM runtime
 * (this KMP module's `androidMain` is a JVM target). Returns
 * wall-clock UTC milliseconds since the epoch.
 *
 * Note: `kotlin.system.getTimeMillis()` (the alternative explored in the
 * #83 follow-up) is NOT available on `kotlin-stdlib-2.0.21-KBA-010`
 * because the source jar doesn't ship a commonMain implementation.
 * Hence this JVM-only `System.currentTimeMillis()` actual remains available
 * while the selected owner reads `kotlin.time.Clock.System` by default.
 */
internal actual fun legacyPlatformNowMillis(): Long =
    System.currentTimeMillis()
