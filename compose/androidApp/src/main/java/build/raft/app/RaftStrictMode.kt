package build.raft.app

import android.os.StrictMode

/**
 * Debug-only [StrictMode] guardrail that logs accidental main-thread disk reads, disk writes,
 * and custom slow calls after intentional cold-start setup finishes.
 *
 * Strictly log-only (`penaltyLog`, never `penaltyDeath`) and gated on both `BuildConfig.DEBUG`
 * and `ApplicationInfo.FLAG_DEBUGGABLE`, so release/Play builds never install it and debug
 * builds never crash on third-party or OEM violations.
 */
internal object RaftStrictMode {
    @Volatile
    internal var installed: Boolean = false
        private set

    fun installIfDebug(
        buildConfigDebug: Boolean,
        appDebuggable: Boolean,
        installPolicy: (StrictMode.ThreadPolicy) -> Unit = StrictMode::setThreadPolicy,
    ): Boolean {
        if (!shouldInstall(buildConfigDebug = buildConfigDebug, appDebuggable = appDebuggable)) {
            return false
        }
        installPolicy(buildThreadPolicy())
        installed = true
        return true
    }

    internal fun shouldInstall(buildConfigDebug: Boolean, appDebuggable: Boolean): Boolean =
        buildConfigDebug && appDebuggable

    internal fun buildThreadPolicy(): StrictMode.ThreadPolicy =
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectCustomSlowCalls()
            .penaltyLog()
            .build()

    /**
     * Temporarily permits thread disk reads for intentional synchronous startup hooks when debug
     * [StrictMode] is installed, always restoring the prior policy in `finally`.
     * Zero-cost pass-through in release builds, unit tests, or before install.
     */
    internal inline fun <T> allowDiskReads(block: () -> T): T {
        if (!installed) return block()
        val previous = StrictMode.allowThreadDiskReads()
        return try {
            block()
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }

    /**
     * Temporarily permits thread disk reads and writes for intentional synchronous startup hooks
     * (such as `MMKV.initialize` and [build.raft.app.core.data.diagnostics.RaftStartupPhaseMarker])
     * when debug [StrictMode] is installed, always restoring the prior policy in `finally`.
     * Zero-cost pass-through in release builds, unit tests, or before install.
     */
    internal inline fun <T> allowDiskWrites(block: () -> T): T {
        if (!installed) return block()
        val previous = StrictMode.allowThreadDiskWrites()
        return try {
            block()
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }

    internal fun resetForTests() {
        installed = false
    }
}
