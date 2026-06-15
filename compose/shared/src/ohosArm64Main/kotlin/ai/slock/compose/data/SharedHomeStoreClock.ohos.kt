package ai.slock.compose.data

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * #83: OHOS K/N actual for the SharedHomeStore default wall-clock
 * source.
 *
 * `System.currentTimeMillis()` is not a top-level symbol on K/N
 * `2.0.21-KBA-010` for OHOS, so we route through
 * [Clock.System.now]. The common declaration is `expect fun
 * defaultNowMillis(): Long` so the K/N OHOS klib only has to resolve
 * the symbol at the actual site, where the [OptIn] is local.
 *
 * Returns wall-clock UTC milliseconds since the epoch, same
 * semantics as `System.currentTimeMillis()` on JVM.
 *
 * Note: `kotlin.system.getTimeMillis()` (the alternative explored in
 * the #83 follow-up) is not available on `kotlin-stdlib-2.0.21-KBA-010`
 * because the source jar doesn't ship a commonMain implementation.
 * Hence the explicit `kotlin.time.Clock.System.now()` call here.
 */
@OptIn(ExperimentalTime::class)
internal actual fun defaultNowMillis(): Long =
    Clock.System.now().toEpochMilliseconds()
