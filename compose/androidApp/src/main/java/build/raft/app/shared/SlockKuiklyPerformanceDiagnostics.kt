package build.raft.app.shared

import build.raft.app.core.data.diagnostics.SlockAppLog
import com.tencent.kuikly.core.render.android.performace.KRMonitorType
import com.tencent.kuikly.core.render.android.performace.KRPerformanceData
import com.tencent.kuikly.core.render.android.performace.launch.KRLaunchData
import kotlin.math.roundToInt

internal object SlockKuiklyPerformanceDiagnostics {
    private const val TAG = "SlockKuiklyPerf"

    val monitorTypes: List<KRMonitorType> =
            listOf(KRMonitorType.LAUNCH, KRMonitorType.FRAME, KRMonitorType.MEMORY)

    fun recordLaunch(source: String, pageName: String, data: KRLaunchData) {
        SlockAppLog.record(
                TAG,
                "launch source=$source page=$pageName firstFrameMs=${data.firstFramePaintCost} " +
                        "buildMs=${data.pageBuildCost} layoutMs=${data.pageLayoutCost} " +
                        "createMs=${data.pageCreateCost} renderMs=${data.renderCost} " +
                        "instanceMs=${data.createInstanceCost}"
        )
    }

    fun recordPerformance(source: String, fallbackPageName: String, data: KRPerformanceData) {
        val pageName = data.pageName.takeUnless { it.isBlank() } ?: fallbackPageName
        val frameData = data.frameData
        val memoryData = data.memoryData
        SlockAppLog.record(
                TAG,
                "perf source=$source page=$pageName spentMs=${data.spentTime} " +
                        "cold=${data.isColdLaunch} pageCold=${data.isPageColdLaunch} " +
                        "fps=${frameData?.getFps()?.roundToInt() ?: 0} " +
                        "kfps=${frameData?.getKuiklyFps()?.roundToInt() ?: 0} " +
                        "avgPssKb=${memoryData?.getAvgPss() ?: 0} " +
                        "maxPssKb=${memoryData?.getMaxPss() ?: 0} " +
                        "peakPssDeltaKb=${memoryData?.getMaxPssIncrement() ?: 0}"
        )
    }
}
