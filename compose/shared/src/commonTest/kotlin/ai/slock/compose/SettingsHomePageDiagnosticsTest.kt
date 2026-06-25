package ai.slock.compose

import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.SharedSettingsApiEnvironmentOption
import ai.slock.compose.data.SharedSettingsAboutSnapshot
import ai.slock.compose.data.SharedSettingsDeveloperSnapshot
import ai.slock.compose.data.SharedSettingsDiagnosticsSnapshot
import ai.slock.compose.data.DEVELOPER_ACTION_API_ENVIRONMENT
import ai.slock.compose.data.DIAGNOSTICS_ACTION_PROFILER_ENABLED
import ai.slock.compose.data.SharedSettingsNetworkProbeSnapshot
import ai.slock.compose.data.SharedSettingsRecompositionProfilerSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsHomePageDiagnosticsTest {
    @Test
    fun unsupportedProfilerSnapshotShowsDisabledProfilerStatus() {
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

        assertEquals("Auth, network, sanitized app diagnostics, and Kuikly profiler status", payload.detail)
        assertEquals(
            listOf("Recomposition profiler", "Actions"),
            payload.sections.map { it.title }
        )
        val profilerRows = payload.sections.first { it.title == "Recomposition profiler" }.rows.associateBy { it.key }
        assertFalse(profilerRows.getValue(DIAGNOSTICS_ACTION_PROFILER_ENABLED).enabled)
        assertEquals("Debug only", profilerRows.getValue(DIAGNOSTICS_ACTION_PROFILER_ENABLED).status)
        assertEquals("Recomposition profiler is available only in shared debug builds.", profilerRows.getValue(DIAGNOSTICS_ACTION_PROFILER_ENABLED).detail)
    }

    @Test
    fun diagnosticsPayloadIncludesMergedAuthEnvironmentAndNetworkProbe() {
        val payload =
            emptyList<ai.slock.compose.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
                state =
                    ScreenLoadState.Content(
                        SharedSettingsDiagnosticsSnapshot(
                            about =
                                SharedSettingsAboutSnapshot(
                                    appName = "Raft",
                                    versionLabel = "1.2.3",
                                    sourceLabel = "auth-bootstrap-diagnostics/e36bc138+",
                                    signedInUserLabel = "Artin",
                                    selectedServerLabel = "Botiverse",
                                    sessionStatusLabel = "Signed in",
                                    socketStatusLabel = "Host active"
                                ),
                            networkProbe =
                                SharedSettingsNetworkProbeSnapshot(
                                    baseUrl = "https://api.raft.build/api/",
                                    statusLabel = "API host reachable",
                                    detail = "Host responded with HTTP 401 Unauthorized",
                                    reachable = true,
                                    code = 401,
                                    durationMillis = 37L
                                ),
                            profiler = SharedSettingsRecompositionProfilerSnapshot(supported = false)
                        )
                    ),
                actionState = null
            )

        assertEquals(
            listOf("Environment", "Network probe", "Recomposition profiler", "Actions"),
            payload.sections.map { it.title }
        )
        val environmentRows = payload.sections.first { it.title == "Environment" }.rows.associateBy { it.key }
        assertEquals("Artin", environmentRows.getValue("diagnosticsSession").detail)
        assertEquals("Botiverse", environmentRows.getValue("diagnosticsServer").detail)
        val probeRows = payload.sections.first { it.title == "Network probe" }.rows.associateBy { it.key }
        assertEquals("https://api.raft.build/api/", probeRows.getValue("diagnosticsProbeBaseUrl").detail)
        assertEquals("HTTP 401 | 37ms", probeRows.getValue("diagnosticsProbeHttp").detail)
    }

    @Test
    fun developerPayloadUsesSingleMergedDiagnosticsEntry() {
        val payload =
            SharedSettingsDeveloperSnapshot().toDeveloperPayload(
                state = ScreenLoadState.Content(SharedSettingsDeveloperSnapshot()),
                actionState = null
            )
        val rows = payload.sections.first { it.title == "Diagnostics" }.rows

        assertEquals(listOf("API Server", "Diagnostics"), payload.sections.map { it.title })
        assertEquals(listOf("diagnostics", "router"), rows.map { it.key })
        assertEquals("Diagnostics", rows.first().title)
        assertTrue(rows.first().detail.contains("auth"))
        assertTrue(rows.first().detail.contains("network"))
    }

    @Test
    fun developerPayloadOpensApiEnvironmentEditor() {
        val production =
            SharedSettingsApiEnvironmentOption(
                key = "production",
                label = "Production",
                apiBaseUrl = "https://api.raft.build/api/",
                socketBaseUrl = "https://api.raft.build",
                webUrl = "https://app.raft.build"
            )
        val staging =
            SharedSettingsApiEnvironmentOption(
                key = "staging",
                label = "Staging",
                apiBaseUrl = "https://api-aws-staging.botiverse.dev/api/",
                socketBaseUrl = "https://api-aws-staging.botiverse.dev",
                webUrl = "https://raft-app-staging.botiverse.dev/s/botiverse/"
            )
        val snapshot =
            SharedSettingsDeveloperSnapshot(
                environmentKey = production.key,
                environmentLabel = production.label,
                apiBaseUrl = production.apiBaseUrl,
                socketBaseUrl = production.socketBaseUrl,
                webUrl = production.webUrl,
                options = listOf(production, staging)
            )

        val payload =
            snapshot.toDeveloperPayload(
                state = ScreenLoadState.Content(snapshot),
                actionState = null
            )

        val apiRows = payload.sections.first { it.title == "API Server" }.rows
        val environmentRow = apiRows.first { it.key == DEVELOPER_ACTION_API_ENVIRONMENT }

        assertEquals("API Environment", environmentRow.title)
        assertEquals("Production", environmentRow.status)
        assertTrue(environmentRow.enabled)
        assertTrue(environmentRow.detail.contains("Choose presets"))
    }

    @Test
    fun emptyDiagnosticsStillAllowsDiagnosticExportsWithoutLogPreview() {
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
        assertTrue(actionRows.getValue("feedback").enabled)
        assertFalse("clearLogs" in actionRows)
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
                                    detail = "Profiler is ready."
                                )
                        )
                    ),
                actionState = null
            )

        assertEquals(
            listOf("Recomposition profiler", "Actions"),
            payload.sections.map { it.title }
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
