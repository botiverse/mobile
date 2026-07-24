package build.raft.app.data

import com.tencent.kuikly.compose.profiler.RecompositionConfigBuilder
import com.tencent.kuikly.compose.profiler.RecompositionProfiler
import com.tencent.kuikly.compose.profiler.RecompositionProfilerFileOutputResult
import com.tencent.kuikly.compose.profiler.RecompositionReport
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

data class SharedSettingsDiagnosticsHostSnapshot(
    val entries: List<SharedSettingsDiagnosticsEntry> = emptyList(),
    val readStateV1: SharedSettingsReadStateV1DiagnosticSnapshot = SharedSettingsReadStateV1DiagnosticSnapshot(),
    val featureFlagOverrides: List<SharedSettingsDiagnosticFeatureFlagSnapshot> = emptyList(),
)

internal data class SharedSettingsRecompositionProfilerSupportState(
    val supported: Boolean,
    val gateLabel: String,
    val unavailableDetail: String
)

internal interface SharedSettingsRecompositionProfilerDebugGate {
    val supported: Boolean
    val gateLabel: String
    val unavailableDetail: String
}

internal object SharedSettingsDefaultRecompositionProfilerDebugGate : SharedSettingsRecompositionProfilerDebugGate {
    private val supportState: SharedSettingsRecompositionProfilerSupportState
        get() = sharedProfilerSupportState()

    override val supported: Boolean
        get() = supportState.supported

    override val gateLabel: String
        get() = supportState.gateLabel

    override val unavailableDetail: String
        get() = supportState.unavailableDetail
}

internal interface SharedSettingsRecompositionProfilerEngine {
    val isEnabled: Boolean

    fun clearCustomFilters()

    fun configure(block: RecompositionConfigBuilder.() -> Unit)

    fun start()

    fun stop(completion: (RecompositionProfilerFileOutputResult) -> Unit)

    fun reset()

    fun getReportSnapshot(): RecompositionReport

    fun exportReport(completion: (RecompositionProfilerFileOutputResult) -> Unit): RecompositionReport
}

internal object KuiklySharedSettingsRecompositionProfilerEngine : SharedSettingsRecompositionProfilerEngine {
    override val isEnabled: Boolean
        get() = RecompositionProfiler.isEnabled

    override fun clearCustomFilters() {
        RecompositionProfiler.clearCustomFilters()
    }

    override fun configure(block: RecompositionConfigBuilder.() -> Unit) {
        RecompositionProfiler.configure(block)
    }

    override fun start() {
        RecompositionProfiler.start()
    }

    override fun stop(completion: (RecompositionProfilerFileOutputResult) -> Unit) {
        RecompositionProfiler.stop(completion)
    }

    override fun reset() {
        RecompositionProfiler.reset()
    }

    override fun getReportSnapshot(): RecompositionReport =
        RecompositionProfiler.getReport(saveToFile = false)

    override fun exportReport(
        completion: (RecompositionProfilerFileOutputResult) -> Unit
    ): RecompositionReport =
        RecompositionProfiler.getReport(saveToFile = true, completion = completion)
}

internal data class SharedSettingsRecompositionProfilerActionResult(
    val feedback: String,
    val errorCode: String? = null
)

class SharedSettingsRecompositionProfilerController internal constructor(
    private val engine: SharedSettingsRecompositionProfilerEngine = KuiklySharedSettingsRecompositionProfilerEngine,
    private val debugGate: SharedSettingsRecompositionProfilerDebugGate = SharedSettingsDefaultRecompositionProfilerDebugGate
) {
    constructor() : this(
        engine = KuiklySharedSettingsRecompositionProfilerEngine,
        debugGate = SharedSettingsDefaultRecompositionProfilerDebugGate
    )

    private var lastSavedArtifactHintAvailable: Boolean = false

    internal val isRunning: Boolean
        get() = debugGate.supported && engine.isEnabled

    fun buildSnapshot(
        enabled: Boolean = isRunning,
        feedback: String? = null
    ): SharedSettingsRecompositionProfilerSnapshot {
        val report = engine.getReportSnapshot()
        val supported = debugGate.supported
        val reportAvailable = report.totalFrames > 0L || report.totalRecompositions > 0 || lastSavedArtifactHintAvailable
        val statusLabel =
            when {
                !supported -> "Unavailable"
                engine.isEnabled -> "Running"
                reportAvailable -> "Stopped"
                else -> "Idle"
            }
        val detail =
            when {
                !supported -> debugGate.unavailableDetail
                engine.isEnabled ->
                    "Profiler is collecting recomposition data for the current Kuikly session. Reproduce the target interaction now, then stop or export. The default overlay FAB should stay visible at the bottom-right during capture."
                reportAvailable ->
                    "Profiler is idle. The latest session summary is ready for export and analyzer follow-up."
                else ->
                    "Profiler is ready. Start capture, open the target page, then reset right before the exact interaction if you want a cleaner window."
            }
        return SharedSettingsRecompositionProfilerSnapshot(
            supported = supported,
            enabled = enabled,
            gateLabel = debugGate.gateLabel,
            statusLabel = statusLabel,
            detail = detail,
            actionFeedback = feedback,
            defaults =
                SharedSettingsRecompositionProfilerDefaults(
                    enableFile = DEFAULT_ENABLE_FILE,
                    enableLog = DEFAULT_ENABLE_LOG,
                    enableOverlay = DEFAULT_ENABLE_OVERLAY,
                    sampleRateLabel = "${(DEFAULT_SAMPLE_RATE * 100).roundToInt()}%",
                    hotspotThreshold = DEFAULT_HOTSPOT_THRESHOLD,
                    maxEventBufferSize = DEFAULT_MAX_EVENT_BUFFER_SIZE,
                    overlayGuide = OVERLAY_GUIDE,
                    filterStrategy = FILTER_STRATEGY,
                    resetStrategy = RESET_STRATEGY
                ),
            report =
                SharedSettingsRecompositionProfilerReport(
                    sessionId = report.sessionId,
                    durationLabel = report.durationMs.formatDurationLabel(),
                    totalFrames = report.totalFrames,
                    totalRecompositions = report.totalRecompositions,
                    hotspotCount = report.hotspots.size,
                    stateChangeCount = report.stateChanges.size,
                    generatedLabel =
                        when {
                            lastSavedArtifactHintAvailable -> "Saved"
                            report.totalFrames > 0L || report.totalRecompositions > 0 -> "In memory"
                            else -> "Empty"
                        },
                    reportAvailable = reportAvailable,
                    topHotspots =
                        report.hotspots.take(5).map { hotspot ->
                            SharedSettingsRecompositionProfilerHotspot(
                                name = hotspot.name,
                                sourceLocation = hotspot.sourceLocation,
                                recompositionCount = hotspot.recompositionCount,
                                avgDurationLabel = hotspot.avgDurationMs.formatMillisLabel()
                            )
                        }
                ),
            files =
                SharedSettingsRecompositionProfilerFiles(
                    reportFileName = PROFILER_REPORT_FILE_NAME,
                    framesFileName = PROFILER_FRAMES_FILE_NAME,
                    locationHint = PROFILER_LOCATION_HINT
                ),
            analyzerHint =
                "Export profiler_report.json and keep profiler_frames.jsonl beside it, then ask kuikly-recomposition-analyzer to analyze both files.",
            extensionNote = SHARED_ONLY_NOTE
        )
    }

    internal fun start(): SharedSettingsRecompositionProfilerActionResult {
        if (!debugGate.supported) {
            return unsupported()
        }
        lastSavedArtifactHintAvailable = false
        applyDefaultConfig()
        return if (engine.isEnabled) {
            SharedSettingsRecompositionProfilerActionResult(
                feedback = "Profiler is already running. The shared default debug policy was refreshed with file, logcat, and overlay enabled."
            )
        } else {
            runCatching {
                engine.start()
            }.fold(
                onSuccess = {
                    SharedSettingsRecompositionProfilerActionResult(
                        feedback = "Profiler started with file, logcat, and overlay enabled."
                    )
                },
                onFailure = { throwable ->
                    SharedSettingsRecompositionProfilerActionResult(
                        feedback =
                            "Profiler could not start because the bundled Compose runtime tooling hook is incompatible: ${throwable.profilerFailureLabel()}.",
                        errorCode = "settings_profiler_runtime_incompatible"
                    )
                }
            )
        }
    }

    internal suspend fun stop(): SharedSettingsRecompositionProfilerActionResult {
        if (!debugGate.supported) {
            return unsupported()
        }
        if (!engine.isEnabled) {
            return SharedSettingsRecompositionProfilerActionResult(
                feedback = "Profiler is not running. The latest exported report remains available."
            )
        }
        return when (val outputResult = awaitFileOutput { completion -> engine.stop(completion) }) {
            is RecompositionProfilerFileOutputResult.Success -> {
                lastSavedArtifactHintAvailable = true
                SharedSettingsRecompositionProfilerActionResult(
                    feedback = "Profiler stopped after profiler_report.json and profiler_frames.jsonl were committed for analyzer input."
                )
            }

            is RecompositionProfilerFileOutputResult.Failure ->
                fileOutputFailure(
                    feedback = "Profiler stopped, but its files were not committed: ${outputResult.reason}."
                )
        }
    }

    internal fun reset(): SharedSettingsRecompositionProfilerActionResult {
        if (!debugGate.supported) {
            return unsupported()
        }
        if (!engine.isEnabled) {
            return SharedSettingsRecompositionProfilerActionResult(
                feedback = "Start the profiler first, then reset right before the target page or interaction."
            )
        }
        engine.reset()
        return SharedSettingsRecompositionProfilerActionResult(
            feedback = "Profiler counters were reset. Open the target page or replay the interaction now."
        )
    }

    internal fun refreshReport(): SharedSettingsRecompositionProfilerActionResult {
        if (!debugGate.supported) {
            return unsupported()
        }
        val report = engine.getReportSnapshot()
        return if (report.totalFrames > 0L) {
            SharedSettingsRecompositionProfilerActionResult(
                feedback = "Report summary refreshed without rewriting files."
            )
        } else {
            SharedSettingsRecompositionProfilerActionResult(
                feedback = "No recomposition frames captured yet. Start the profiler and trigger the target flow first."
            )
        }
    }

    internal suspend fun exportReport(): SharedSettingsRecompositionProfilerActionResult {
        if (!debugGate.supported) {
            return unsupported()
        }
        return when (val outputResult = awaitFileOutput { completion -> engine.exportReport(completion) }) {
            is RecompositionProfilerFileOutputResult.Success -> {
                lastSavedArtifactHintAvailable = true
                SharedSettingsRecompositionProfilerActionResult(
                    feedback = "Report exported after Kuikly committed profiler_report.json and profiler_frames.jsonl."
                )
            }

            is RecompositionProfilerFileOutputResult.Failure ->
                fileOutputFailure(
                    feedback = "Report export failed before the native file commit: ${outputResult.reason}."
                )
        }
    }

    private suspend fun awaitFileOutput(
        operation: ((RecompositionProfilerFileOutputResult) -> Unit) -> Unit
    ): RecompositionProfilerFileOutputResult =
        suspendCancellableCoroutine { continuation ->
            try {
                operation { result ->
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
            } catch (throwable: Throwable) {
                if (continuation.isActive) {
                    continuation.resume(
                        RecompositionProfilerFileOutputResult.Failure(
                            sessionId = "",
                            reason = throwable.profilerFailureLabel()
                        )
                    )
                }
            }
        }

    private fun fileOutputFailure(
        feedback: String
    ): SharedSettingsRecompositionProfilerActionResult =
        SharedSettingsRecompositionProfilerActionResult(
            feedback = feedback,
            errorCode = PROFILER_FILE_OUTPUT_FAILURE_CODE
        )

    private fun unsupported(): SharedSettingsRecompositionProfilerActionResult =
        SharedSettingsRecompositionProfilerActionResult(
            feedback = debugGate.unavailableDetail,
            errorCode = "settings_profiler_unavailable"
        )

    private fun applyDefaultConfig() {
        engine.clearCustomFilters()
        engine.configure {
            sampleRate = DEFAULT_SAMPLE_RATE
            hotspotThreshold = DEFAULT_HOTSPOT_THRESHOLD
            maxEventBufferSize = DEFAULT_MAX_EVENT_BUFFER_SIZE
            enableStateTracking = true
            includeFrameworkComposables = false
            enableOverlay = DEFAULT_ENABLE_OVERLAY
            overlayTopCount = 20
            enableLog = DEFAULT_ENABLE_LOG
            enableFile = DEFAULT_ENABLE_FILE
            enableBuiltinFilters = true
        }
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 1.0f
        private const val DEFAULT_HOTSPOT_THRESHOLD = 10
        private const val DEFAULT_MAX_EVENT_BUFFER_SIZE = 100_000
        private const val DEFAULT_ENABLE_FILE = true
        private const val DEFAULT_ENABLE_LOG = true
        private const val DEFAULT_ENABLE_OVERLAY = true
        private const val PROFILER_FILE_OUTPUT_FAILURE_CODE = "settings_profiler_file_output_failed"
        private const val PROFILER_REPORT_FILE_NAME = "profiler_report.json"
        private const val PROFILER_FRAMES_FILE_NAME = "profiler_frames.jsonl"

        private const val OVERLAY_GUIDE =
            "Shared diagnostics applies the Kuikly enableLog=true and enableOverlay=true flow before start(). RCProfiler logcat output stays enabled, and a draggable FAB appears at the bottom-right."

        private const val FILTER_STRATEGY =
            "Keep Kuikly built-in framework filters enabled. Start without custom business filters; if shared shell chrome dominates the hotspots, rerun with exclusions such as build.raft.app.components. or build.raft.app.base.."

        private const val RESET_STRATEGY =
            "Reset immediately before opening the target page or replaying the target interaction so one report only contains one scenario window."

        private const val PROFILER_LOCATION_HINT =
            "Kuikly docs convention: Android/iOS write to the app sandbox Caches directory, HarmonyOS writes to files. Use the default file names instead of relying on an absolute path from the host."

        private const val SHARED_ONLY_NOTE =
            "Diagnostics persists the profiler switch in shared settings, routes toggle acknowledgements through the host bridge, and uses Kuikly RecompositionProfiler common APIs for capture and export."
    }
}

private fun Throwable.profilerFailureLabel(): String =
    message?.takeIf { it.isNotBlank() }
        ?: this::class.simpleName.orEmpty().ifBlank { "unknown runtime error" }

private fun Long.formatDurationLabel(): String =
    when {
        this <= 0L -> "0 ms"
        this < 1_000L -> "$this ms"
        else -> "${(this / 10.0).roundToInt() / 100.0} s"
    }

private fun Double.formatMillisLabel(): String =
    "${(this * 10).roundToInt() / 10.0} ms avg"

internal expect fun sharedProfilerSupportState(): SharedSettingsRecompositionProfilerSupportState
