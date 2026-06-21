package ai.slock.compose

import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.SharedSettingsDiagnosticsSnapshot
import ai.slock.compose.data.DIAGNOSTICS_ACTION_PROFILER_ENABLED
import ai.slock.compose.data.SharedSettingsRecompositionProfilerDefaults
import ai.slock.compose.data.SharedSettingsRecompositionProfilerFiles
import ai.slock.compose.data.SharedSettingsRecompositionProfilerSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsHomePageDiagnosticsTest {
    @Test
    fun unsupportedProfilerSnapshotHidesProfilerSections() {
        val payload =
            emptyList<ai.slock.compose.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state =
                    ScreenLoadState.Content(
                        SharedSettingsDiagnosticsSnapshot(
                            profiler =
                                SharedSettingsRecompositionProfilerSnapshot(
                                    supported = false,
                                    gateLabel = "Debug only",
                                    detail = "Recomposition profiler is available only in shared debug builds."
                                )
                        )
                    ),
                actionState = null
            )

        assertEquals("Sanitized app diagnostics", payload.detail)
        assertEquals(
            listOf("Actions", "Log"),
            payload.sections.map { it.title }
        )
    }

    @Test
    fun emptyDiagnosticsStillAllowsCopyAndShareButNotClear() {
        val payload =
            emptyList<ai.slock.compose.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state =
                    ScreenLoadState.Content(
                        SharedSettingsDiagnosticsSnapshot(
                            profiler = SharedSettingsRecompositionProfilerSnapshot(supported = false)
                        )
                    ),
                actionState = null
            )

        val actionRows = payload.sections.first { it.title == "Actions" }.rows.associateBy { it.key }

        assertTrue(actionRows.getValue("copyLogs").enabled)
        assertEquals("Copy", actionRows.getValue("copyLogs").status)
        assertTrue(actionRows.getValue("shareLogs").enabled)
        assertEquals("Share", actionRows.getValue("shareLogs").status)
        assertFalse(actionRows.getValue("clearLogs").enabled)
        assertEquals("Disabled", actionRows.getValue("clearLogs").status)
    }

    @Test
    fun supportedProfilerSnapshotShowsSharedOnlyProfilerSections() {
        val payload =
            emptyList<ai.slock.compose.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state =
                    ScreenLoadState.Content(
                        SharedSettingsDiagnosticsSnapshot(
                            profiler =
                                SharedSettingsRecompositionProfilerSnapshot(
                                    supported = true,
                                    enabled = true,
                                    gateLabel = "Debug build",
                                    statusLabel = "Idle",
                                    detail = "Profiler is ready.",
                                    defaults =
                                        SharedSettingsRecompositionProfilerDefaults(
                                            enableFile = true,
                                            enableLog = true,
                                            enableOverlay = true,
                                            sampleRateLabel = "100%",
                                            hotspotThreshold = 10,
                                            maxEventBufferSize = 100000,
                                            overlayGuide = "Overlay shows the bottom-right FAB.",
                                            filterStrategy = "Keep defaults.",
                                            resetStrategy = "Reset before replay."
                                        ),
                                    files =
                                        SharedSettingsRecompositionProfilerFiles(
                                            reportFileName = "profiler_report.json",
                                            framesFileName = "profiler_frames.jsonl",
                                            locationHint = "Kuikly docs convention"
                                        )
                                )
                        )
                    ),
                actionState = null
            )

        assertEquals(
            listOf("Recomposition profiler", "Profiler defaults", "Profiler report", "Analyzer", "Actions", "Log"),
            payload.sections.map { it.title }
        )
        assertTrue(
            payload.sections
                .first { it.title == "Profiler defaults" }
                .rows
                .first { it.key == "profilerDefaults" }
                .detail
                .contains("overlay on")
        )
        val switchRow =
            payload.sections
                .first { it.title == "Recomposition profiler" }
                .rows
                .first { it.key == DIAGNOSTICS_ACTION_PROFILER_ENABLED }
        assertEquals(true, switchRow.toggleValue)
        assertEquals("false", switchRow.value)
    }
}
