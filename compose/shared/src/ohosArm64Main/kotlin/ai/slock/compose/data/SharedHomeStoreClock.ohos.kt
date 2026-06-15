package ai.slock.compose.data

import kotlin.system.getTimeMillis

/**
 * #83: OHOS K/N actual for the SharedHomeStore default wall-clock
 * source.
 *
 * `System.currentTimeMillis()` is not a top-level symbol on K/N
 * `2.0.21-KBA-010` for OHOS, so this actual source set uses the
 * platform stdlib's [getTimeMillis]. The common declaration is
 * `expect fun defaultNowMillis(): Long`, so commonMain does not have
 * to resolve this platform-only symbol.
 *
 * Returns wall-clock UTC milliseconds since the epoch, same
 * semantics as `System.currentTimeMillis()` on JVM.
 *
 * Note: [getTimeMillis] intentionally stays in the OHOS actual; it is
 * not available from commonMain in this KBA stdlib line.
 */
@Suppress("DEPRECATION")
internal actual fun defaultNowMillis(): Long =
    getTimeMillis()
