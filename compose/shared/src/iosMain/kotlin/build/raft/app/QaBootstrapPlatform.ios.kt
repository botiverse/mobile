package build.raft.app

import build.raft.app.data.SharedAuthDataSource
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.posix.getpid

// iOS Simulator twin of QaBootstrapPlatform.android.kt. Only a session whose `qaBootstrap` metadata
// was written by RaftIosQaSessionBootstrapBridge (Debug simulator builds) ever reaches the READY path.
private val qaBootstrapProcessInstanceId by lazy { NSUUID().UUIDString().lowercase() }

// Monotonic uptime (ms) when this process first touched the QA bootstrap path: stable for the process,
// different for every launch, like Android's Process.getStartUptimeMillis().
private val qaBootstrapProcessGeneration by lazy { (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong().toString() }

@OptIn(ExperimentalNativeApi::class)
internal actual suspend fun raftPlatformCompleteQaBootstrapRouteReady(authDataSource: SharedAuthDataSource): Boolean {
    if (!Platform.isDebugBinary) return false
    return completeQaBootstrapRouteReady(
        authDataSource = authDataSource,
        pid = getpid(),
        processInstanceId = qaBootstrapProcessInstanceId,
        processGeneration = qaBootstrapProcessGeneration,
        readyNanos = { (NSProcessInfo.processInfo.systemUptime * 1_000_000_000.0).toLong().toString() },
        log = ::raftIosQaBootstrapLog,
    )
}

/**
 * Writes one `[SlockQaBootstrap][info] <line>` record straight to the unified log (NSLog), which the
 * harness reads with `xcrun simctl spawn <udid> log stream`. Bypasses the diagnostics sanitizer on
 * purpose: the READY line's digests must arrive verbatim. Lines carry stage/code/digest tokens only.
 */
internal fun raftIosQaBootstrapLog(line: String) {
    NSLog("[$QA_BOOTSTRAP_LOG_TAG][info] $line".replace("%", "%%"))
}
