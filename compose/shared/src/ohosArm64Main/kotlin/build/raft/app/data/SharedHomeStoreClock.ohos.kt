package build.raft.app.data

import kotlin.system.getTimeMillis

/**
 * OHOS K/N legacy wall-clock source retained for the Datetime build rollback.
 *
 * `System.currentTimeMillis()` is not a top-level symbol on K/N
 * `2.0.21-KBA-010` for OHOS, so this actual source set uses the
 * platform stdlib's [getTimeMillis]. The selected owner reads the published
 * KuiklyBase clock by default and calls this only when the build fence is off.
 *
 * Returns wall-clock UTC milliseconds since the epoch, same
 * semantics as `System.currentTimeMillis()` on JVM.
 *
 * Note: [getTimeMillis] intentionally stays in the OHOS actual; it is
 * not available from commonMain in this KBA stdlib line.
 */
@Suppress("DEPRECATION")
internal actual fun legacyPlatformNowMillis(): Long =
    getTimeMillis()
