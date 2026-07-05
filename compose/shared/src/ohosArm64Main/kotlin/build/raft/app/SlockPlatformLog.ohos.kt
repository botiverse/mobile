package build.raft.app

import com.tencent.kuikly.core.log.KLog
import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI

private const val SlockOhosSharedLogSinkService = "slockOhosSharedLogSink"

@ServiceConsumer(name = SlockOhosSharedLogSinkService)
interface SlockOhosSharedLogSink {
    fun record(level: String, tag: String, message: String)
}

internal actual fun slockPlatformLog(level: SlockLogLevel, tag: String, message: String) {
    val recorded =
        runCatching {
            KNOI.get<SlockOhosSharedLogSink>(SlockOhosSharedLogSinkService)
                .record(level.wireValue, tag, message)
        }.isSuccess
    if (recorded) return
    when (level) {
        SlockLogLevel.Debug -> KLog.d(tag, message)
        SlockLogLevel.Info -> KLog.i(tag, message)
        SlockLogLevel.Warn -> KLog.i(tag, "WARN: $message")
        SlockLogLevel.Error -> KLog.e(tag, message)
    }
}
