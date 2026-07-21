package build.raft.app.data

import com.tencent.kuikly.compose.profiler.RecompositionConfigBuilder
import com.tencent.kuikly.compose.profiler.RecompositionProfiler
import com.tencent.kuikly.compose.profiler.RecompositionReport
import kotlin.math.roundToInt

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

    fun stop()

    fun reset()

    fun getReport(saveToFile: Boolean = true): RecompositionReport
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

    override fun stop() {
        RecompositionProfiler.stop()
    }

    override fun reset() {
        RecompositionProfiler.reset()
    }

    override fun getReport(saveToFile: Boolean): RecompositionReport =
        RecompositionProfiler.getReport(saveToFile = saveToFile)
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
        val report = engine.getReport(saveToFile = false)
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

    internal fun stop(): SharedSettingsRecompositionProfilerActionResult {
        if (!debugGate.supported) {
            return unsupported()
        }
        if (!engine.isEnabled) {
            return SharedSettingsRecompositionProfilerActionResult(
                feedback = "Profiler is not running. The latest exported report remains available."
            )
        }
        engine.stop()
        lastSavedArtifactHintAvailable = true
        return SharedSettingsRecompositionProfilerActionResult(
            feedback = "Profiler stopped. Kuikly writes profiler_report.json automatically; keep profiler_frames.jsonl beside it for analyzer input."
        )
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
        val report = engine.getReport(saveToFile = false)
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

    internal fun exportReport(): SharedSettingsRecompositionProfilerActionResult {
        if (!debugGate.supported) {
            return unsupported()
        }
        val report = engine.getReport(saveToFile = true)
        lastSavedArtifactHintAvailable = true
        return when {
            report.totalFrames > 0L || report.totalRecompositions > 0 || lastSavedArtifactHintAvailable ->
                SharedSettingsRecompositionProfilerActionResult(
                    feedback = "Report exported with Kuikly default file names: profiler_report.json and profiler_frames.jsonl."
                )

            else ->
                SharedSettingsRecompositionProfilerActionResult(
                    feedback = "An empty report is currently loaded. Capture a scenario first if you need meaningful analyzer input."
                )
        }
    }

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
