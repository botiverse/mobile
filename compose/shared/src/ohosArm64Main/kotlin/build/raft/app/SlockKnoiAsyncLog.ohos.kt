@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import com.tencent.tmm.knoi.logger.logProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.ohos.LOG_APP
import platform.ohos.LOG_WARN
import platform.ohos.LogLevel
import platform.ohos.OH_LOG_Print

private object SlockKnoiAsyncLog {
    // One consumer; no UI/ArkTS/KNOI calls, even when the writer fails.
    val sink = SlockBoundedNativeLog<LogLevel>(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        capacity = 256,
        maxChars = 2_048,
        warningLevel = LOG_WARN
    ) { level, message ->
        check(OH_LOG_Print(LOG_APP, level, 1u, "knoi", "%{public}s", message) >= 0)
    }
    val proxy: (LogLevel, String) -> Unit = { level, message -> sink.enqueue(level, message) }
}

/** Must be installed before the first KNOI setup/init call. Safe to call again. */
fun installSlockKnoiAsyncLog(): Int {
    logProxy = SlockKnoiAsyncLog.proxy
    return 1
}
