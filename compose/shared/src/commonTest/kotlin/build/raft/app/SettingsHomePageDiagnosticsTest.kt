package build.raft.app

import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.SharedSettingsDiagnosticsSnapshot
import build.raft.app.data.DEVELOPER_ACTION_API_ENVIRONMENT
import build.raft.app.data.DEVELOPER_ACTION_QUIVER_UPDATE_CHECK
import build.raft.app.data.DEVELOPER_ACTION_SHARED_SOCKET_IO_TRANSPORT
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_ENABLED
import build.raft.app.data.SharedSettingsRecompositionProfilerSnapshot
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsHomePageDiagnosticsTest {
    @Test
    fun unsupportedProfilerSnapshotShowsDisabledProfilerStatus() {
        val payload =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
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

        assertEquals("Auth, sanitized app diagnostics, and Kuikly profiler status", payload.detail)
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
    fun diagnosticsPayloadIncludesMergedAuthEnvironment() {
        val payload =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
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
                            profiler = SharedSettingsRecompositionProfilerSnapshot(supported = false)
                        )
                    ),
                actionState = null
            )

        assertEquals(
            listOf("Environment", "Recomposition profiler", "Actions"),
            payload.sections.map { it.title }
        )
        val environmentRows = payload.sections.first { it.title == "Environment" }.rows.associateBy { it.key }
        assertEquals("Artin", environmentRows.getValue("diagnosticsSession").detail)
        assertEquals("Botiverse", environmentRows.getValue("diagnosticsServer").detail)
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
        assertFalse(rows.first().detail.contains("network"))
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
                updateCheck =
                    SharedSettingsUpdateCheckSnapshot(
                        supported = true,
                        statusLabel = "Current",
                        detail = "No Quiver update is available on main."
                    ),
                options = listOf(production, staging)
            )

        val payload =
            snapshot.toDeveloperPayload(
                state = ScreenLoadState.Content(snapshot),
                actionState = null
            )

        val apiRows = payload.sections.first { it.title == "API Server" }.rows
        val environmentRow = apiRows.first { it.key == DEVELOPER_ACTION_API_ENVIRONMENT }
        val transportRow = apiRows.first { it.key == DEVELOPER_ACTION_SHARED_SOCKET_IO_TRANSPORT }

        assertEquals("API Environment", environmentRow.title)
        assertEquals("Production", environmentRow.status)
        assertTrue(environmentRow.enabled)
        assertTrue(environmentRow.detail.contains("Choose presets"))
        assertEquals("Shared Socket.IO transport", transportRow.title)
        assertEquals(false, transportRow.toggleValue)
        assertEquals("true", transportRow.value)
        assertTrue(transportRow.detail.contains("Android"))
        assertFalse(apiRows.any { it.key == DEVELOPER_ACTION_QUIVER_UPDATE_CHECK })
    }

    @Test
    fun aboutPayloadShowsManualUpdateCheckAction() {
        val payload =
            SharedSettingsAboutSnapshot(
                appName = "Raft",
                versionLabel = "1.0.0",
                buildLabel = "1000000"
            ).toAboutPayload(
                webUrl = "https://app.raft.build",
                updateCheck =
                    SharedSettingsUpdateCheckSnapshot(
                        supported = true,
                        statusLabel = "Current",
                        detail = "No Quiver update is available on main."
                    ),
                actionState = null
            )

        val rows = payload.sections.first { it.title == "Actions" }.rows
        val updateRow = rows.first { it.key == DEVELOPER_ACTION_QUIVER_UPDATE_CHECK }

        assertEquals("Check for update", updateRow.title)
        assertEquals("Current", updateRow.status)
        assertTrue(updateRow.enabled)
        assertTrue(updateRow.detail.contains("Quiver"))
    }

    @Test
    fun aboutPayloadOnlyKeepsVersionBuildStatusInApplicationRows() {
        val payload =
            SharedSettingsAboutSnapshot(
                appName = "Raft",
                versionLabel = "1.0.1",
                buildLabel = "1000100",
                buildTime = "2026-07-03T19:03:28Z",
                gitCommit = "15fb7be3",
                packageName = "build.raft.app"
            ).toAboutPayload(webUrl = "https://app.raft.build")

        val rows = payload.sections.first { it.title == "Application" }.rows.associateBy { it.key }

        assertEquals("", rows.getValue("appName").status)
        assertEquals("1000100", rows.getValue("version").status)
        assertEquals("", rows.getValue("buildTime").status)
        assertEquals("", rows.getValue("gitCommit").status)
        assertEquals("", rows.getValue("package").status)
    }

    @Test
    fun aboutPayloadHidesUpdateCheckBeforeHostCapabilityLoads() {
        val payload =
            SharedSettingsAboutSnapshot(appName = "Raft", versionLabel = "1.0.1")
                .toAboutPayload(
                    webUrl = "https://app.raft.build",
                    updateCheck = SharedSettingsUpdateCheckSnapshot()
                )

        val actionRows =
            payload.sections
                .first { it.title == "Actions" }
                .rows

        assertFalse(actionRows.any { it.key == DEVELOPER_ACTION_QUIVER_UPDATE_CHECK })
        assertTrue(actionRows.any { it.key == "openSlock" })
    }

    @Test
    fun aboutPayloadHidesExplicitUnsupportedUpdateChecks() {
        val payload =
            SharedSettingsAboutSnapshot(appName = "Raft", versionLabel = "1.0.1")
                .toAboutPayload(
                    webUrl = "https://app.raft.build",
                    updateCheck =
                        SharedSettingsUpdateCheckSnapshot(
                            statusLabel = "Disabled",
                            detail = "This build does not expose in-app update checks."
                        )
                )

        val actionRows =
            payload.sections
                .first { it.title == "Actions" }
                .rows

        assertFalse(actionRows.any { it.key == DEVELOPER_ACTION_QUIVER_UPDATE_CHECK })
        assertTrue(actionRows.any { it.key == "openSlock" })
    }

    @Test
    fun emptyDiagnosticsStillAllowsDiagnosticExportsWithoutLogPreview() {
        val payload =
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
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
            emptyList<build.raft.app.data.SharedSettingsDiagnosticsEntry>().toDiagnosticsPayload(
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
