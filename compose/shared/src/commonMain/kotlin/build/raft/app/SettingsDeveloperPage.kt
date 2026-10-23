package build.raft.app

import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.RaftBrutalTextField
import build.raft.app.components.RaftFormFieldSize
import build.raft.app.components.raftControlSurface
import build.raft.app.data.DEVELOPER_ACTION_API_ENVIRONMENT
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import build.raft.app.data.SharedSettingsApiEnvironmentRequest
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.SharedSettingsDiagnosticFeatureFlagSnapshot
import build.raft.app.data.SharedSettingsDiagnosticsEntry
import build.raft.app.data.SharedSettingsDiagnosticsSnapshot
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.DIAGNOSTICS_ACTION_APPLE_SIGNIN_LOCAL_OVERRIDE
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.flow.map

@Composable
internal fun SharedSettingsDeveloperContent(
    state: ScreenLoadState<SharedSettingsDeveloperSnapshot>,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onCopyValue: ((String) -> Unit)? = null,
    onOpenApiEnvironment: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRouter: () -> Unit
) {
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data
    val payload =
        snapshot.toDeveloperPayload(
            state = state,
            actionState = actionState,
            errorStrings = localizedErrorPresentationStrings(),
            copy = settingsDeveloperPayloadCopy(),
            textInputTraceEnabled = RaftTextInputDiagnostics.enabled
        )
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding, onCopyValue = onCopyValue) { action, value ->
        when (action) {
            DEVELOPER_ACTION_API_ENVIRONMENT -> onOpenApiEnvironment()
            "diagnostics" -> onOpenDiagnostics()
            "router" -> onOpenRouter()
            DEVELOPER_ACTION_TEXT_INPUT_TRACE -> RaftTextInputDiagnostics.setEnabled(value == "true")
        }
    }
}

@Composable
internal fun SharedSettingsApiEnvironmentContent(
    state: ScreenLoadState<SharedSettingsDeveloperSnapshot>,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onSaveApiEnvironment: (SharedSettingsApiEnvironmentRequest) -> Unit
) {
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data
    val focusManager = LocalFocusManager.current
    var selectedPresetKey by remember(snapshot?.environmentKey, snapshot?.options) {
        mutableStateOf(snapshot?.environmentKey?.takeIf { key -> snapshot.options.any { it.key == key } }.orEmpty())
    }
    var label by remember(snapshot?.environmentKey, snapshot?.environmentLabel) {
        mutableStateOf(snapshot?.environmentLabel.orEmpty())
    }
    var apiBaseUrl by remember(snapshot?.environmentKey, snapshot?.apiBaseUrl) {
        mutableStateOf(snapshot?.apiBaseUrl.orEmpty())
    }
    var socketBaseUrl by remember(snapshot?.environmentKey, snapshot?.socketBaseUrl) {
        mutableStateOf(snapshot?.socketBaseUrl.orEmpty())
    }
    var webUrl by remember(snapshot?.environmentKey, snapshot?.webUrl) {
        mutableStateOf(snapshot?.webUrl.orEmpty())
    }
    val busy = actionState?.action == DEVELOPER_ACTION_API_ENVIRONMENT && actionState.inProgress
    val normalizedApi = apiBaseUrl.trim()
    val normalizedSocket = socketBaseUrl.trim()
    val normalizedWeb = webUrl.trim()
    val matchingPreset =
        snapshot?.options?.firstOrNull { option ->
            option.apiBaseUrl == normalizedApi &&
                option.socketBaseUrl == normalizedSocket &&
                option.webUrl == normalizedWeb
        }
    val requestKey = matchingPreset?.key ?: "custom"
    val customLabel = stringResource(MR.strings.settings_developer_custom)
    val requestLabel = matchingPreset?.label ?: label.trim().ifBlank { customLabel }
    val canSave = normalizedApi.startsWith("http") && normalizedSocket.startsWith("http") && !busy

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SettingsFormSectionCard(iconName = RaftLucideIconName.SlidersHorizontal, title = stringResource(MR.strings.settings_developer_presets)) {
                SettingsFormDetailText(stringResource(MR.strings.settings_developer_presets_detail))
                snapshot?.options.orEmpty().forEach { option ->
                    SettingsApiEnvironmentPresetRow(
                        option = option,
                        selected = selectedPresetKey == option.key,
                        enabled = !busy,
                        onSelect = {
                            focusManager.clearFocus()
                            selectedPresetKey = option.key
                            label = option.label
                            apiBaseUrl = option.apiBaseUrl
                            socketBaseUrl = option.socketBaseUrl
                            webUrl = option.webUrl
                        }
                    )
                }
                if (snapshot?.options.isNullOrEmpty()) {
                    SettingsFormEmptyText(stringResource(MR.strings.settings_developer_presets_empty))
                }
            }
        }
        item {
            SettingsFormSectionCard(iconName = RaftLucideIconName.Server, title = stringResource(MR.strings.settings_developer_environment_form)) {
                SettingsFormDetailText(stringResource(MR.strings.settings_developer_environment_save_detail))
                RaftBrutalTextField(
                    label = stringResource(MR.strings.settings_developer_label),
                    labelSize = RaftFormFieldSize.Compact,
                    value = label,
                    placeholder = customLabel,
                    onValueChange = {
                        selectedPresetKey = ""
                        label = it
                    },
                    enabled = !busy
                )
                RaftBrutalTextField(
                    label = stringResource(MR.strings.settings_developer_api_base_url),
                    labelSize = RaftFormFieldSize.Compact,
                    value = apiBaseUrl,
                    placeholder = "https://api.example.com/api/",
                    onValueChange = {
                        selectedPresetKey = ""
                        apiBaseUrl = it
                    },
                    required = true,
                    enabled = !busy
                )
                RaftBrutalTextField(
                    label = stringResource(MR.strings.settings_developer_socket_base_url),
                    labelSize = RaftFormFieldSize.Compact,
                    value = socketBaseUrl,
                    placeholder = "https://api.example.com",
                    onValueChange = {
                        selectedPresetKey = ""
                        socketBaseUrl = it
                    },
                    required = true,
                    enabled = !busy
                )
                RaftBrutalTextField(
                    label = stringResource(MR.strings.settings_developer_web_url),
                    labelSize = RaftFormFieldSize.Compact,
                    value = webUrl,
                    placeholder = "https://app.raft.build",
                    onValueChange = {
                        selectedPresetKey = ""
                        webUrl = it
                    },
                    optional = true,
                    enabled = !busy
                )
                RaftButton(
                    text = if (busy) stringResource(MR.strings.settings_developer_action_saving) else stringResource(MR.strings.settings_developer_action_save_environment),
                    onClick = {
                        onSaveApiEnvironment(
                            SharedSettingsApiEnvironmentRequest(
                                key = requestKey,
                                label = requestLabel,
                                apiBaseUrl = normalizedApi,
                                socketBaseUrl = normalizedSocket,
                                webUrl = normalizedWeb
                            )
                        )
                    },
                    enabled = canSave,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Save) },
                    modifier = Modifier.fillMaxWidth(),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg
                )
                if (!canSave && !busy) {
                    SettingsFormErrorText(stringResource(MR.strings.settings_developer_error_urls))
                }
                if (actionState?.action == DEVELOPER_ACTION_API_ENVIRONMENT && actionState.errorCode != null) {
                    SettingsFormErrorText(actionState)
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsApiEnvironmentPresetRow(
    option: SharedSettingsApiEnvironmentOption,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .raftControlSurface(
                    fill = if (selected) RaftColor.BrandSoft else RaftColor.Card,
                    border = if (selected) RaftColor.BorderBlack else RaftColor.BorderHairline,
                    radius = RaftAppRadius.Container,
                    borderWidth = if (selected) RaftAppStroke.Control else RaftAppStroke.Thin
                )
                .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(
            name = if (selected) RaftLucideIconName.CircleCheckBig else RaftLucideIconName.Server,
            modifier = Modifier.size(18.dp),
            color = RaftColor.TextBlackArgb
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = option.label,
                style = RaftType.Button.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = RaftColor.TextBlack
            )
            Text(
                text = listOf(option.apiBaseUrl, option.socketBaseUrl).filter { it.isNotBlank() }.joinToString(" | "),
                style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray
            )
        }
        Text(
            text = if (selected) stringResource(MR.strings.settings_developer_state_current) else stringResource(MR.strings.settings_developer_state_preset),
            style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray
        )
    }
}

@Composable
internal fun SharedSettingsDiagnosticsContent(
    state: ScreenLoadState<SharedSettingsDiagnosticsSnapshot>,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onSetDiagnosticFeatureFlagOverride: (String, String) -> Unit,
    onOpenLocalFeatureOverrides: (() -> Unit)? = null,
    onSetAppleSignInLocalOverride: ((Boolean) -> Unit)? = null,
    onCopyValue: ((String) -> Unit)? = null,
    onSendFeedback: () -> Unit
) {
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>)?.data
    val payload =
        (snapshot?.entries ?: emptyList()).toDiagnosticsPayload(
            state = state,
            actionState = actionState,
            copy = settingsDiagnosticsPayloadCopy(),
            errorStrings = localizedErrorPresentationStrings(),
            includeLocalFeatureOverrides = onOpenLocalFeatureOverrides != null,
            appleSignInLocalOverride =
                if (onSetAppleSignInLocalOverride != null && snapshot?.appleSignInLocalOverrideAvailable == true) {
                    snapshot?.appleSignInLocalOverride == true
                } else {
                    null
                },
        )
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding, onCopyValue = onCopyValue) { action, value ->
        when (action) {
            "copyLogs" -> onCopyLogs()
            "shareLogs" -> onShareLogs()
            DIAGNOSTICS_ACTION_APPLE_SIGNIN_LOCAL_OVERRIDE -> onSetAppleSignInLocalOverride?.invoke(value == "true")
            "feedback" -> onSendFeedback()
            LOCAL_FEATURE_OVERRIDES_OPEN_ACTION -> onOpenLocalFeatureOverrides?.invoke()
            else ->
                parseDiagnosticFeatureFlagAction(action)?.let { (key, mode) ->
                    onSetDiagnosticFeatureFlagOverride(key, mode)
                }
        }
    }
}

@Composable
internal fun SharedSettingsLocalFeatureOverridesContent(
    snapshot: SharedSettingsDiagnosticsSnapshot,
    bottomContentPadding: Dp,
    onOpenOverride: (String) -> Unit,
) {
    SharedSettingsSectionContent(
        payload = snapshot.toLocalFeatureOverridesPayload(),
        bottomContentPadding = bottomContentPadding,
    ) { action, _ ->
        parseOpenLocalFeatureOverrideAction(action)?.let(onOpenOverride)
    }
}

@Composable
internal fun SharedSettingsLocalFeatureOverrideDetailContent(
    flag: SharedSettingsDiagnosticFeatureFlagSnapshot,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    bottomContentPadding: Dp,
    onSetOverride: (String, String) -> Unit,
    onExportEvidence: () -> Unit,
) {
    SharedSettingsSectionContent(
        payload = flag.toLocalFeatureOverrideDetailPayload(actionState),
        bottomContentPadding = bottomContentPadding,
    ) { action, _ ->
        when (action) {
            LOCAL_FEATURE_OVERRIDE_EXPORT_ACTION -> onExportEvidence()
            else ->
                parseDiagnosticFeatureFlagAction(action)?.let { (key, mode) ->
                    onSetOverride(key, mode)
                }
        }
    }
}

internal const val DEVELOPER_ACTION_TEXT_INPUT_TRACE = "textInputTrace"
private const val LOCAL_FEATURE_OVERRIDES_OPEN_ACTION = "openLocalFeatureOverrides"
private const val LOCAL_FEATURE_OVERRIDE_OPEN_ACTION_PREFIX = "openLocalFeatureOverride|"
private const val LOCAL_FEATURE_OVERRIDE_EXPORT_ACTION = "exportLocalFeatureOverrideEvidence"

internal fun localFeatureOverridePageAllowed(
    route: SettingsNativeDetailRoute,
    available: Boolean,
): Boolean =
    available ||
        route.page !in
            setOf(
                SettingsNativeDetailPage.LocalFeatureOverrides,
                SettingsNativeDetailPage.LocalFeatureOverride,
            )

internal fun shouldCloseLocalFeatureOverridePage(
    route: SettingsNativeDetailRoute,
    availabilityResolved: Boolean,
    available: Boolean,
): Boolean =
    availabilityResolved && !localFeatureOverridePageAllowed(route, available)

internal fun openLocalFeatureOverrideAction(key: String): String =
    "$LOCAL_FEATURE_OVERRIDE_OPEN_ACTION_PREFIX$key"

internal fun parseOpenLocalFeatureOverrideAction(action: String): String? =
    action
        .takeIf { it.startsWith(LOCAL_FEATURE_OVERRIDE_OPEN_ACTION_PREFIX) }
        ?.removePrefix(LOCAL_FEATURE_OVERRIDE_OPEN_ACTION_PREFIX)
        ?.takeIf { it.matches(Regex("^[a-z][a-z0-9_]{2,95}$")) }

private fun diagnosticFeatureFlagAction(key: String, mode: String): String =
    "diagnosticFlagOverride|$key|$mode"

private fun parseDiagnosticFeatureFlagAction(action: String): Pair<String, String>? {
    val parts = action.split('|')
    if (parts.size != 3 || parts[0] != "diagnosticFlagOverride") return null
    val key = parts[1].trim()
    val mode = parts[2].trim()
    return (key to mode).takeIf { key.isNotBlank() && mode in setOf("server", "force_off", "force_on") }
}

private fun build.raft.app.data.SharedSettingsActionState?.isDiagnosticsActionLoading(action: String): Boolean =
    this?.action == action && this.inProgress

internal data class SettingsDeveloperPayloadCopy(
    val title: String = "Developer",
    val detail: String = "API server and diagnostics",
    val apiServer: String = "API Server",
    val currentApi: String = "Current API",
    val currentSocket: String = "Current socket",
    val apiEnvironment: String = "API Environment",
    val environmentLoading: String = "Loading developer environment options.",
    val environmentDetail: String = "Choose presets or edit API, socket, and web URLs.",
    val saving: String = "Saving",
    val loading: String = "Loading",
    val open: String = "Open",
    val unknown: String = "Unknown",
    val current: String = "Current",
    val realtime: String = "Realtime",
    val diagnostics: String = "Diagnostics",
    val diagnosticsDetail: String = "Open auth, sanitized logs, and feedback tools.",
    val routeLauncher: String = "Route launcher",
    val routeLauncherDetail: String = "Open Kuikly route launcher and icon compare.",
    val textInputTrace: String = "Text input trace",
    val textInputTraceDetail: String = "Log every keyboard edit and text sync in text fields to the app logs.",
    val on: String = "On",
    val off: String = "Off"
)

@Composable
private fun settingsDeveloperPayloadCopy(): SettingsDeveloperPayloadCopy =
    SettingsDeveloperPayloadCopy(
        title = stringResource(MR.strings.settings_developer_title),
        detail = stringResource(MR.strings.settings_developer_detail),
        apiServer = stringResource(MR.strings.settings_developer_section_api_server),
        currentApi = stringResource(MR.strings.settings_developer_current_api),
        currentSocket = stringResource(MR.strings.settings_developer_current_socket),
        apiEnvironment = stringResource(MR.strings.settings_developer_api_environment),
        environmentLoading = stringResource(MR.strings.settings_developer_environment_loading),
        environmentDetail = stringResource(MR.strings.settings_developer_environment_detail),
        saving = stringResource(MR.strings.settings_state_saving),
        loading = stringResource(MR.strings.settings_state_loading),
        open = stringResource(MR.strings.settings_state_open),
        unknown = stringResource(MR.strings.settings_about_state_unknown),
        current = stringResource(MR.strings.settings_developer_state_current),
        realtime = stringResource(MR.strings.settings_developer_state_realtime),
        diagnostics = stringResource(MR.strings.settings_diagnostics_title),
        diagnosticsDetail = stringResource(MR.strings.settings_developer_diagnostics_detail),
        routeLauncher = stringResource(MR.strings.settings_developer_route_launcher),
        routeLauncherDetail = stringResource(MR.strings.settings_developer_route_launcher_detail),
        textInputTrace = stringResource(MR.strings.settings_developer_text_input_trace),
        textInputTraceDetail = stringResource(MR.strings.settings_developer_text_input_trace_detail),
        on = stringResource(MR.strings.settings_state_on),
        off = stringResource(MR.strings.settings_state_off)
    )

internal data class SettingsDiagnosticsPayloadCopy(
    val title: String = "Diagnostics",
    val detailBasic: String = "Auth and sanitized app diagnostics",
    val environment: String = "Environment",
    val loadStatus: String = "Load status",
    val actions: String = "Actions",
    val partialDiagnostics: String = "Partial diagnostics",
    val partial: String = "Partial",
    val appleSignInOverride: String = "Apple sign-in (local test)",
    val appleSignInOverrideDetail: String = "Locally show the Apple button on the login page for testing. The backend platform=mobile gate still decides whether an Apple sign-in start succeeds.",
    val loading: String = "Loading",
    val on: String = "On",
    val off: String = "Off",
    val export: String = "Export",
    val disabled: String = "Disabled",
    val send: String = "Send",
    val exportLogs: String = "Export logs",
    val exportLogsDetail: String = "Export environment, status, and recent app JSONL logs.",
    val sendDiagnostics: String = "Send diagnostics",
    val sendDiagnosticsDetail: String = "Share sanitized diagnostics with support.",
    val app: String = "App",
    val build: String = "Build",
    val versionCode: String = "Version code",
    val packageLabel: String = "Package",
    val platform: String = "Platform",
    val deviceFacts: String = "Device facts",
    val display: String = "Display",
    val installTime: String = "Install time",
    val session: String = "Session",
    val selectedServer: String = "Selected server",
    val unknown: String = "Unknown",
    val version: String = "Version",
    val source: String = "Source",
    val sdk: String = "SDK",
    val host: String = "Host",
    val device: String = "Device",
    val screen: String = "Screen",
    val density: String = "Density",
    val fontScale: String = "Font scale",
    val noSignedInUser: String = "No signed-in user",
    val socket: String = "Socket"
)

@Composable
private fun settingsDiagnosticsPayloadCopy(): SettingsDiagnosticsPayloadCopy =
    SettingsDiagnosticsPayloadCopy(
        title = stringResource(MR.strings.settings_diagnostics_title),
        detailBasic = stringResource(MR.strings.settings_diagnostics_detail_basic),
        environment = stringResource(MR.strings.settings_diagnostics_section_environment),
        loadStatus = stringResource(MR.strings.settings_diagnostics_section_load_status),
        actions = stringResource(MR.strings.settings_diagnostics_section_actions),
        partialDiagnostics = stringResource(MR.strings.settings_diagnostics_partial),
        partial = stringResource(MR.strings.settings_diagnostics_state_partial),
        appleSignInOverride = stringResource(MR.strings.settings_diagnostics_apple_signin_override),
        appleSignInOverrideDetail = stringResource(MR.strings.settings_diagnostics_apple_signin_override_detail),
        loading = stringResource(MR.strings.settings_state_loading),
        on = stringResource(MR.strings.settings_state_on),
        off = stringResource(MR.strings.settings_state_off),
        export = stringResource(MR.strings.settings_state_export),
        disabled = stringResource(MR.strings.settings_state_disabled),
        send = stringResource(MR.strings.settings_state_send),
        exportLogs = stringResource(MR.strings.settings_diagnostics_action_export_logs),
        exportLogsDetail = stringResource(MR.strings.settings_diagnostics_export_logs_detail),
        sendDiagnostics = stringResource(MR.strings.settings_diagnostics_action_send),
        sendDiagnosticsDetail = stringResource(MR.strings.settings_diagnostics_send_detail),
        app = stringResource(MR.strings.settings_diagnostics_label_app),
        build = stringResource(MR.strings.settings_diagnostics_label_build),
        versionCode = stringResource(MR.strings.settings_diagnostics_label_version_code),
        packageLabel = stringResource(MR.strings.settings_diagnostics_label_package),
        platform = stringResource(MR.strings.settings_diagnostics_label_platform),
        deviceFacts = stringResource(MR.strings.settings_diagnostics_label_device_facts),
        display = stringResource(MR.strings.settings_diagnostics_label_display),
        installTime = stringResource(MR.strings.settings_diagnostics_label_install_time),
        session = stringResource(MR.strings.settings_diagnostics_label_session),
        selectedServer = stringResource(MR.strings.settings_diagnostics_label_selected_server),
        unknown = stringResource(MR.strings.settings_about_state_unknown),
        version = stringResource(MR.strings.settings_diagnostics_state_version),
        source = stringResource(MR.strings.settings_diagnostics_state_source),
        sdk = stringResource(MR.strings.settings_diagnostics_state_sdk),
        host = stringResource(MR.strings.settings_diagnostics_state_host),
        device = stringResource(MR.strings.settings_diagnostics_state_device),
        screen = stringResource(MR.strings.settings_diagnostics_state_screen),
        density = stringResource(MR.strings.settings_diagnostics_state_density),
        fontScale = stringResource(MR.strings.settings_diagnostics_state_font_scale),
        noSignedInUser = stringResource(MR.strings.settings_diagnostics_state_no_user),
        socket = stringResource(MR.strings.settings_diagnostics_state_socket)
    )

internal fun SharedSettingsDeveloperSnapshot?.toDeveloperPayload(
    state: ScreenLoadState<SharedSettingsDeveloperSnapshot>,
    actionState: SharedSettingsActionState?,
    errorStrings: ErrorPresentationStrings,
    copy: SettingsDeveloperPayloadCopy = SettingsDeveloperPayloadCopy(),
    textInputTraceEnabled: Boolean = false
): RaftSharedSettingsPayload {
    val environmentDetail =
        when (state) {
            is ScreenLoadState.Error -> state.error.userFacingMessage(errorStrings)
            is ScreenLoadState.Empty -> state.reason.userFacingMessage(errorStrings)
            is ScreenLoadState.NoPermission -> state.blocker.userFacingMessage(errorStrings)
            ScreenLoadState.Loading -> copy.environmentLoading
            is ScreenLoadState.Content -> copy.environmentDetail
        }
    val environmentStatus =
        when {
            actionState?.action == DEVELOPER_ACTION_API_ENVIRONMENT && actionState.inProgress -> copy.saving
            this?.environmentLabel.orEmpty().isNotBlank() -> this?.environmentLabel.orEmpty()
            state is ScreenLoadState.Loading -> copy.loading
            else -> copy.open
        }
    return RaftSharedSettingsPayload(
        instanceId = "shared-shell-settings-developer",
        title = copy.title,
        detail = this?.feedback ?: this?.environmentLabel.orEmpty().ifBlank { copy.detail },
        heroIconName = RaftLucideIconName.Terminal,
        heroLabel = copy.title,
        sections =
            listOf(
                RaftSharedSettingsSection(
                    title = copy.apiServer,
                    rows =
                        listOf(
                            RaftSharedSettingsRow(
                                key = "currentApi",
                                iconName = RaftLucideIconName.Globe,
                                title = copy.currentApi,
                                detail = this?.apiBaseUrl.orEmpty().ifBlank { copy.unknown },
                                status = this?.environmentLabel.orEmpty().ifBlank { copy.current },
                                copyValue = this?.apiBaseUrl.orEmpty()
                            ),
                            RaftSharedSettingsRow(
                                key = "currentSocket",
                                iconName = RaftLucideIconName.Activity,
                                title = copy.currentSocket,
                                detail = this?.socketBaseUrl.orEmpty().ifBlank { copy.unknown },
                                status = copy.realtime,
                                copyValue = this?.socketBaseUrl.orEmpty()
                            ),
                            RaftSharedSettingsRow(
                                key = DEVELOPER_ACTION_API_ENVIRONMENT,
                                iconName = RaftLucideIconName.SlidersHorizontal,
                                title = copy.apiEnvironment,
                                detail = environmentDetail,
                                status = environmentStatus,
                                enabled = state is ScreenLoadState.Content
                            )
                        )
                ),
                RaftSharedSettingsSection(
                    title = copy.diagnostics,
                    rows =
                        listOf(
                            RaftSharedSettingsRow("diagnostics", RaftLucideIconName.ClipboardList, copy.diagnostics, copy.diagnosticsDetail, status = copy.open, enabled = true),
                            RaftSharedSettingsRow("router", RaftLucideIconName.Route, copy.routeLauncher, copy.routeLauncherDetail, status = copy.open, enabled = true),
                            RaftSharedSettingsRow(
                                key = DEVELOPER_ACTION_TEXT_INPUT_TRACE,
                                iconName = RaftLucideIconName.ClipboardList,
                                title = copy.textInputTrace,
                                detail = copy.textInputTraceDetail,
                                value = (!textInputTraceEnabled).toString(),
                                status = if (textInputTraceEnabled) copy.on else copy.off,
                                enabled = true,
                                toggleValue = textInputTraceEnabled
                            )
                        )
                )
            )
    )
}

internal fun List<SharedSettingsDiagnosticsEntry>.toDiagnosticsPayload(
    state: ScreenLoadState<SharedSettingsDiagnosticsSnapshot>,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    copy: SettingsDiagnosticsPayloadCopy = SettingsDiagnosticsPayloadCopy(),
    errorStrings: ErrorPresentationStrings? = null,
    includeLocalFeatureOverrides: Boolean = true,
    appleSignInLocalOverride: Boolean? = null,
): RaftSharedSettingsPayload {
    val diagnosticsExportAvailable = state is ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>)?.data
    val environmentRows =
        snapshot?.about?.toDiagnosticsEnvironmentRows(copy).orEmpty()
    val structuredLoadErrorMessage =
        snapshot?.loadErrors
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { loadError ->
                val strings = requireNotNull(errorStrings) {
                    "ErrorPresentationStrings are required for structured diagnostics load errors"
                }
                "${loadError.label}: ${loadError.presentation.resolve(strings)}"
            }
    val loadStatusRows =
        (structuredLoadErrorMessage ?: snapshot?.loadErrorMessage)
            ?.takeIf { it.isNotBlank() }
            ?.let { errorMessage ->
                listOf(
                    RaftSharedSettingsRow(
                        key = "diagnosticsLoadStatus",
                        iconName = RaftLucideIconName.TriangleAlert,
                        title = copy.partialDiagnostics,
                        detail = errorMessage,
                        status = copy.partial
                    )
                )
            }
            .orEmpty()
    val appleSignInOverrideSection =
        appleSignInLocalOverride?.let { enabled ->
            RaftSharedSettingsSection(
                title = copy.appleSignInOverride,
                rows =
                    listOf(
                        RaftSharedSettingsRow(
                            DIAGNOSTICS_ACTION_APPLE_SIGNIN_LOCAL_OVERRIDE,
                            RaftLucideIconName.SlidersHorizontal,
                            copy.appleSignInOverride,
                            copy.appleSignInOverrideDetail,
                            value = (!enabled).toString(),
                            status =
                                when {
                                    actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_APPLE_SIGNIN_LOCAL_OVERRIDE) -> copy.loading
                                    enabled -> copy.on
                                    else -> copy.off
                                },
                            enabled = true,
                            toggleValue = enabled
                        )
                    )
            )
        }
    val localFeatureOverridesSection =
        snapshot
            ?.takeIf { includeLocalFeatureOverrides && it.localFeatureOverridesAvailable() }
            ?.let {
                RaftSharedSettingsSection(
                    title = "Developer tools",
                    rows =
                        listOf(
                            RaftSharedSettingsRow(
                                key = LOCAL_FEATURE_OVERRIDES_OPEN_ACTION,
                                iconName = RaftLucideIconName.SlidersHorizontal,
                                title = "Local Feature Overrides",
                                detail = "Inspect registered keys and override them for this app process.",
                                status = "Open",
                                enabled = true,
                            )
                        )
                )
            }
    return RaftSharedSettingsPayload(
        instanceId = "shared-shell-diagnostics",
        title = copy.title,
        detail = copy.detailBasic,
        heroIconName = RaftLucideIconName.ClipboardList,
        heroLabel = copy.title,
        sections =
            listOfNotNull(
                environmentRows.takeIf { it.isNotEmpty() }?.let {
                    RaftSharedSettingsSection(title = copy.environment, rows = it)
                },
                loadStatusRows.takeIf { it.isNotEmpty() }?.let {
                    RaftSharedSettingsSection(title = copy.loadStatus, rows = it)
                }
            ) + listOfNotNull(localFeatureOverridesSection) + listOfNotNull(appleSignInOverrideSection) + listOf(
                RaftSharedSettingsSection(
                    title = copy.actions,
                    rows =
                        listOf(
                            RaftSharedSettingsRow("shareLogs", RaftLucideIconName.Share, copy.exportLogs, copy.exportLogsDetail, status = if (diagnosticsExportAvailable) copy.export else copy.disabled, enabled = diagnosticsExportAvailable),
                            RaftSharedSettingsRow("feedback", RaftLucideIconName.MessageSquarePlus, copy.sendDiagnostics, copy.sendDiagnosticsDetail, status = copy.send, enabled = true)
                        )
                )
            )
    )
}

internal fun SharedSettingsDiagnosticsSnapshot?.localFeatureOverridesAvailable(): Boolean =
    this?.featureFlagOverrides?.any { it.supported && it.entitled } == true

internal fun SharedSettingsDiagnosticsSnapshot.registeredLocalFeatureOverrides(): List<SharedSettingsDiagnosticFeatureFlagSnapshot> =
    featureFlagOverrides
        .filter { it.supported && it.entitled }
        .distinctBy { it.key }
        .sortedBy { it.key }

internal fun SharedSettingsDiagnosticsSnapshot.toLocalFeatureOverridesPayload(): RaftSharedSettingsPayload =
    RaftSharedSettingsPayload(
        instanceId = "shared-shell-local-feature-overrides",
        title = "Local Feature Overrides",
        detail = "Process-local controls registered by this internal build.",
        heroIconName = RaftLucideIconName.SlidersHorizontal,
        heroLabel = "Local Feature Overrides",
        sections =
            listOf(
                RaftSharedSettingsSection(
                    title = "Registered keys",
                    rows =
                        registeredLocalFeatureOverrides().map { flag ->
                            RaftSharedSettingsRow(
                                key = openLocalFeatureOverrideAction(flag.key),
                                iconName = RaftLucideIconName.SlidersHorizontal,
                                title = flag.localFeatureOverrideDisplayName(),
                                detail = flag.key,
                                copyValue = flag.key,
                                status = flag.localFeatureOverrideFinalStatus(),
                                enabled = true,
                            )
                        }
                )
            )
    )

internal fun SharedSettingsDiagnosticFeatureFlagSnapshot.toLocalFeatureOverrideDetailPayload(
    actionState: build.raft.app.data.SharedSettingsActionState?,
): RaftSharedSettingsPayload {
    val busy = actionState?.action == "diagnosticFeatureFlagOverride:$key" && actionState.inProgress
    return RaftSharedSettingsPayload(
        instanceId = "shared-shell-local-feature-override-$key",
        title = localFeatureOverrideDisplayName(),
        detail = key,
        heroIconName = RaftLucideIconName.SlidersHorizontal,
        heroLabel = localFeatureOverrideDisplayName(),
        sections =
            listOf(
                RaftSharedSettingsSection(
                    title = "Effective decision",
                    rows =
                        listOf(
                            RaftSharedSettingsRow(
                                key = "localFeatureOverrideServerEffective|$key",
                                iconName = RaftLucideIconName.Server,
                                title = "Server effective",
                                detail = precedence,
                                status = serverEffective.localFeatureOverrideBooleanStatus(),
                            ),
                            RaftSharedSettingsRow(
                                key = "localFeatureOverrideClientOverride|$key",
                                iconName = RaftLucideIconName.SlidersHorizontal,
                                title = "Client override",
                                detail = "Applies to every use of this feature in the current app process.",
                                status = clientOverride,
                            ),
                            RaftSharedSettingsRow(
                                key = "localFeatureOverrideFinalEffective|$key",
                                iconName = RaftLucideIconName.Activity,
                                title = "Final effective",
                                detail = "Server value after the process-local override is applied.",
                                status = localFeatureOverrideFinalEffective().localFeatureOverrideBooleanStatus(),
                            )
                        )
                ),
                RaftSharedSettingsSection(
                    title = "Lifecycle and evidence",
                    rows =
                        listOf(
                            RaftSharedSettingsRow(
                                key = "localFeatureOverrideLifecycle|$key",
                                iconName = RaftLucideIconName.Clock,
                                title = "TTL and clear reason",
                                detail = "clear=${clearReason.ifBlank { "active" }} · expiresAt=${expiresAtMillis ?: "none"}",
                                status = if (expiresAtMillis == null) "Process local" else "15 min TTL",
                            ),
                            RaftSharedSettingsRow(
                                key = "localFeatureOverrideEvidence|$key",
                                iconName = RaftLucideIconName.ClipboardList,
                                title = "Consumer evidence",
                                detail = evidence.ifBlank { "No consumer evidence has been observed yet." },
                                status = if (evidence.isBlank()) "Unobserved" else "Observed",
                            )
                        )
                ),
                RaftSharedSettingsSection(
                    title = "Override",
                    rows =
                        listOf(
                            RaftSharedSettingsRow(
                                key = diagnosticFeatureFlagAction(key, "server"),
                                iconName = RaftLucideIconName.Server,
                                title = "Follow Server",
                                detail = "Clear this key's process-local override and follow the server result.",
                                status = if (clientOverride == "Server") "Current" else "Select",
                                enabled = supported && entitled && !busy,
                            ),
                            RaftSharedSettingsRow(
                                key = diagnosticFeatureFlagAction(key, "force_off"),
                                iconName = RaftLucideIconName.Shield,
                                title = "Force OFF",
                                detail = "Disable this feature throughout the current app process for 15 minutes.",
                                status = if (clientOverride == "ForceOff") "Current" else "15 min",
                                enabled = supported && entitled && !busy,
                            ),
                            RaftSharedSettingsRow(
                                key = diagnosticFeatureFlagAction(key, "force_on"),
                                iconName = RaftLucideIconName.ShieldCheck,
                                title = "Force ON",
                                detail = "Enable this feature throughout the current app process for 15 minutes.",
                                status = if (clientOverride == "ForceOn") "Current" else "15 min",
                                enabled = supported && entitled && !busy,
                            )
                        )
                ),
                RaftSharedSettingsSection(
                    title = "Evidence",
                    rows =
                        listOf(
                            RaftSharedSettingsRow(
                                key = LOCAL_FEATURE_OVERRIDE_EXPORT_ACTION,
                                iconName = RaftLucideIconName.Share,
                                title = "Export diagnostics evidence",
                                detail = "Share the sanitized diagnostics archive for this process.",
                                status = "Export",
                                enabled = true,
                            )
                        )
                )
            )
    )
}

private fun SharedSettingsDiagnosticFeatureFlagSnapshot.localFeatureOverrideDisplayName(): String =
    key
        .replace(Regex("_v[0-9]+$"), "")
        .split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replaceFirstChar { it.uppercase() }
        .ifBlank { key }

private fun SharedSettingsDiagnosticFeatureFlagSnapshot.localFeatureOverrideFinalStatus(): String =
    localFeatureOverrideFinalEffective().localFeatureOverrideBooleanStatus()

private fun SharedSettingsDiagnosticFeatureFlagSnapshot.localFeatureOverrideFinalEffective(): Boolean? =
    when (clientOverride) {
        "ForceOff" -> false
        "ForceOn" -> true
        else -> finalEffective
    }

private fun Boolean?.localFeatureOverrideBooleanStatus(): String =
    when (this) {
        true -> "ON"
        false -> "OFF"
        null -> "Unobserved"
    }

private fun SharedSettingsAboutSnapshot.toDiagnosticsEnvironmentRows(copy: SettingsDiagnosticsPayloadCopy): List<RaftSharedSettingsRow> =
    listOf(
        RaftSharedSettingsRow("diagnosticsApp", RaftLucideIconName.Info, copy.app, appName.ifBlank { "Raft" }, status = versionLabel.ifBlank { copy.version }, copyValue = appName.ifBlank { "Raft" }),
        RaftSharedSettingsRow("diagnosticsBuild", RaftLucideIconName.GitBranch, copy.build, buildLabel.ifBlank { copy.unknown }, status = sourceLabel.ifBlank { copy.source }, copyValue = buildLabel),
        RaftSharedSettingsRow("diagnosticsVersionCode", RaftLucideIconName.BadgeInfo, copy.versionCode, versionCodeLabel.ifBlank { buildLabel.ifBlank { copy.unknown } }, status = sdkLabel.ifBlank { copy.sdk }, copyValue = versionCodeLabel.ifBlank { buildLabel }),
        RaftSharedSettingsRow("diagnosticsPackage", RaftLucideIconName.Package, copy.packageLabel, packageName.ifBlank { copy.unknown }, status = copy.host, copyValue = packageName),
        RaftSharedSettingsRow("diagnosticsPlatform", RaftLucideIconName.Monitor, copy.platform, platformLabel.ifBlank { copy.unknown }, status = deviceLabel.ifBlank { copy.device }, copyValue = platformLabel),
        RaftSharedSettingsRow("diagnosticsDeviceFacts", RaftLucideIconName.Smartphone, copy.deviceFacts, modelLabel.ifBlank { deviceLabel.ifBlank { copy.unknown } }, status = screenLabel.ifBlank { copy.screen }, copyValue = modelLabel.ifBlank { deviceLabel }),
        RaftSharedSettingsRow("diagnosticsDisplay", RaftLucideIconName.Monitor, copy.display, densityLabel.ifBlank { copy.density }, status = fontScaleLabel.ifBlank { copy.fontScale }, copyValue = densityLabel),
        RaftSharedSettingsRow("diagnosticsInstallTime", RaftLucideIconName.Clock, copy.installTime, installTimeLabel.ifBlank { "--" }, status = copy.host, copyValue = installTimeLabel),
        RaftSharedSettingsRow("diagnosticsSession", RaftLucideIconName.User, copy.session, signedInUserLabel.ifBlank { copy.noSignedInUser }, status = sessionStatusLabel.ifBlank { copy.unknown }, copyValue = signedInUserLabel),
        RaftSharedSettingsRow("diagnosticsServer", RaftLucideIconName.Server, copy.selectedServer, selectedServerLabel.ifBlank { copy.unknown }, status = socketStatusLabel.ifBlank { copy.socket }, copyValue = selectedServerLabel)
    )
