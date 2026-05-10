package ai.slock.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.slock.android.R
import ai.slock.android.core.data.diagnostics.SanitizedDiagnosticLogEntry
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType

data class SettingsAboutSnapshot(
    val appVersion: String,
    val buildNumber: String,
    val packageName: String,
    val deviceLabel: String,
    val androidVersion: String,
    val sessionStatus: String,
    val serverStatus: String,
    val socketStatus: String,
    val notificationStatus: String,
    val diagnosticLogs: List<SanitizedDiagnosticLogEntry>
)

@Composable
fun SettingsAboutScreen(
    snapshot: SettingsAboutSnapshot,
    modifier: Modifier = Modifier,
    onOpenDiagnostics: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
            .verticalScroll(rememberScrollState())
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SettingsAboutInfoCard(
            title = stringResource(R.string.settings_about_app_section),
            rows = listOf(
                stringResource(R.string.settings_about_version) to snapshot.appVersion,
                stringResource(R.string.settings_about_build) to snapshot.buildNumber,
                stringResource(R.string.settings_about_package) to snapshot.packageName,
                stringResource(R.string.settings_about_device) to snapshot.deviceLabel,
                stringResource(R.string.settings_about_android) to snapshot.androidVersion
            )
        )
        SettingsAboutInfoCard(
            title = stringResource(R.string.settings_about_status_section),
            rows = listOf(
                stringResource(R.string.settings_about_session) to snapshot.sessionStatus,
                stringResource(R.string.settings_about_server) to snapshot.serverStatus,
                stringResource(R.string.settings_about_socket) to snapshot.socketStatus,
                stringResource(R.string.settings_about_notifications) to snapshot.notificationStatus
            )
        )
        SettingsDiagnosticsEntry(onOpenDiagnostics = onOpenDiagnostics)
    }
}

@Composable
fun SettingsDiagnosticsScreen(
    logs: List<SanitizedDiagnosticLogEntry>,
    modifier: Modifier = Modifier,
    onCopyLogs: () -> Unit = {},
    onShareLogs: () -> Unit = {},
    onClearLogs: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
            .verticalScroll(rememberScrollState())
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Text(
            text = stringResource(R.string.settings_about_diagnostics_detail),
            style = SlockType.Body,
            color = SlockColor.TextMutedGray
        )
        SettingsDiagnosticsActions(
            logsAvailable = logs.isNotEmpty(),
            onCopyLogs = onCopyLogs,
            onShareLogs = onShareLogs,
            onClearLogs = onClearLogs
        )
        SettingsDiagnosticsText(logs = logs)
    }
}

@Composable
private fun SettingsAboutInfoCard(
    title: String,
    rows: List<Pair<String, String>>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = title.uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        rows.forEach { (label, value) ->
            SettingsAboutInfoRow(label = label, value = value)
        }
    }
}

@Composable
private fun SettingsAboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(0.38f)
        )
        Text(
            text = value,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.62f)
        )
    }
}

@Composable
private fun SettingsDiagnosticsEntry(
    onOpenDiagnostics: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = stringResource(R.string.settings_about_diagnostics_section).uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        Text(
            text = stringResource(R.string.settings_about_diagnostics_entry_detail),
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
        SlockButton(
            text = stringResource(R.string.settings_about_open_diagnostics),
            onClick = onOpenDiagnostics,
            modifier = Modifier.fillMaxWidth(),
            variant = SlockButtonVariant.Outline
        )
    }
}

@Composable
private fun SettingsDiagnosticsActions(
    logsAvailable: Boolean,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            SlockButton(
                text = stringResource(R.string.settings_about_copy_logs),
                onClick = onCopyLogs,
                modifier = Modifier.weight(1f),
                variant = SlockButtonVariant.Outline,
                enabled = logsAvailable
            )
            SlockButton(
                text = stringResource(R.string.settings_about_share_logs),
                onClick = onShareLogs,
                modifier = Modifier.weight(1f),
                variant = SlockButtonVariant.Yellow,
                enabled = logsAvailable
            )
        }
        SlockButton(
            text = stringResource(R.string.settings_about_clear_logs),
            onClick = onClearLogs,
            modifier = Modifier.fillMaxWidth(),
            variant = SlockButtonVariant.Red,
            enabled = logsAvailable
        )
    }
}

@Composable
private fun SettingsDiagnosticsText(
    logs: List<SanitizedDiagnosticLogEntry>,
) {
    val text =
        if (logs.isEmpty()) {
            stringResource(R.string.settings_about_no_logs)
        } else {
            logs.takeLast(80).joinToString(separator = "\n") { log ->
                "${log.timestampMillis} ${log.tag}: ${log.message}"
            }
        }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = text,
            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
            color = if (logs.isEmpty()) SlockColor.TextMutedGray else SlockColor.TextBlack
        )
    }
}
