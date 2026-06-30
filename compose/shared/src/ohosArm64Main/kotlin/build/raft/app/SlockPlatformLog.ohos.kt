package build.raft.app

import com.tencent.kuikly.core.log.KLog

internal actual fun slockPlatformLog(level: SlockLogLevel, tag: String, message: String) {
    when (level) {
        SlockLogLevel.Debug -> KLog.d(tag, message)
        SlockLogLevel.Info -> KLog.i(tag, message)
        SlockLogLevel.Warn -> KLog.i(tag, "WARN: $message")
        SlockLogLevel.Error -> KLog.e(tag, message)
    }
}
