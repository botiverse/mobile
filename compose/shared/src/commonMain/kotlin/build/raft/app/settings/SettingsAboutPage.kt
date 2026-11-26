package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.DEVELOPER_ACTION_API_ENVIRONMENT
import build.raft.app.data.DEVELOPER_ACTION_HANDS_UPDATE_CHANNEL
import build.raft.app.data.DEVELOPER_ACTION_HANDS_UPDATE_CHECK
import build.raft.app.data.RAFT_EARLY_ACCESS_GUIDE_GOOGLE_PLAY_BETA
import build.raft.app.data.RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_ACTIVE
import build.raft.app.data.RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_JOIN
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.SharedSettingsOpenSourceLicenseItem
import build.raft.app.data.SharedSettingsOpenSourceLicensesSnapshot
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsUpdateTransactionState
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.StoredAuthSession
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.map

@Composable
internal fun SharedSettingsAboutContent(
    state: ScreenLoadState<SharedSettingsAboutSnapshot>,
    fallback: SharedSettingsAboutSnapshot,
    developerState: ScreenLoadState<SharedSettingsDeveloperSnapshot> = ScreenLoadState.Loading,
    includeDeveloperShortcuts: Boolean = false,
    updateCheck: SharedSettingsUpdateCheckSnapshot?,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onCopyBuild: (String) -> Unit,
    onCopyValue: ((String) -> Unit)? = null,
    webUrl: String = SharedSettingsDefaultWebBaseUrl,
    onOpenUrl: (String) -> Unit,
    onCheckHandsUpdate: () -> Unit,
    onPrefetchReleaseNotes: suspend (String) -> Unit = {},
    onOpenSourceLicenses: () -> Unit,
    onCopyEnvironmentInfo: () -> Unit,
    onOpenApiEnvironment: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onSendFeedback: (() -> Unit)? = null,
    /** Settings › Developer, opened by tapping the version row [SettingsDeveloperUnlockTapCount] times. */
    onOpenDeveloper: (() -> Unit)? = null,
    /** Server-driven visibility gate (`mobile_early_access_visible_v0`, `#proj-mobile:e48030bc`). */
    earlyAccessVisible: Boolean = false,
    /** Toggle Android direct-install runtime channel between `alpha` (true) and `main` (false). */
    onToggleEarlyAccessChannel: ((Boolean) -> Unit)? = null
) {
    val versionTaps = remember { SettingsDeveloperUnlockTaps() }
    val snapshot =
        when (state) {
            is ScreenLoadState.Content -> state.data
            else -> fallback
        }
    val releaseNotesUrl =
        remember(snapshot.buildLabel) {
            releaseNotesSanitizeVersionCode(snapshot.buildLabel)
                .takeIf(String::isNotBlank)
                ?.let(::sharedSettingsReleaseNotesUrl)
                .orEmpty()
        }
    LaunchedEffect(releaseNotesUrl) {
        releaseNotesUrl.takeIf(String::isNotBlank)?.let { onPrefetchReleaseNotes(it) }
    }
    val developer = (developerState as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data
    val earlyAccessOption =
        settingsAboutEarlyAccessOption(
            earlyAccessVisible = earlyAccessVisible,
            developer = developer,
            updateCheck = updateCheck,
            actionState = actionState,
            canToggleHandsChannel = onToggleEarlyAccessChannel != null
        )
    val aboutPayload =
        snapshot.toAboutPayload(
            webUrl = webUrl,
            errorStrings = localizedErrorPresentationStrings(),
            updateCheck = updateCheck,
            actionState = actionState,
            connectedServerHost = settingsAboutConnectedServerHost(developer),
            earlyAccessOption = earlyAccessOption,
            copy = settingsAboutPayloadCopy()
        )
    val environmentStatus =
        when (developerState) {
            ScreenLoadState.Loading -> stringResource(Res.string.settings_state_loading)
            is ScreenLoadState.Content -> developer?.environmentLabel.orEmpty().ifBlank { stringResource(Res.string.settings_developer_state_current) }
            else -> stringResource(Res.string.settings_state_unavailable)
        }
    val environmentDetail =
        developer?.apiBaseUrl.orEmpty().ifBlank {
            stringResource(Res.string.settings_developer_environment_detail)
        }
    val payload =
        if (includeDeveloperShortcuts) {
            aboutPayload.copy(
                sections =
                    aboutPayload.sections +
                        RaftSharedSettingsSection(
                            title = stringResource(Res.string.settings_developer_section_api_server),
                            rows = settingsAboutDeveloperShortcutRows(
                                environmentTitle = stringResource(Res.string.settings_developer_api_environment),
                                environmentDetail = environmentDetail,
                                environmentStatus = environmentStatus,
                                environmentEnabled = developerState is ScreenLoadState.Content,
                                diagnosticsTitle = stringResource(Res.string.settings_diagnostics_title),
                                diagnosticsDetail = stringResource(Res.string.settings_developer_diagnostics_detail),
                                feedbackTitle = stringResource(Res.string.settings_feedback_title),
                                feedbackDetail = stringResource(Res.string.settings_feedback_subtitle),
                                openStatus = stringResource(Res.string.settings_state_open),
                                includeFeedback = onSendFeedback != null
                            )
                        )
            )
        } else {
            aboutPayload
        }
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding, onCopyValue = onCopyValue) { action, value ->
        when (action) {
            "copyBuild" -> onCopyBuild(value.orEmpty().ifBlank { snapshot.buildSummary() })
            SETTINGS_ABOUT_SOURCE_CODE_ACTION, SETTINGS_ABOUT_EARLY_ACCESS_EXTERNAL_ACTION ->
                settingsAboutExternalUrl(action, value, webUrl)?.let(onOpenUrl)
            SETTINGS_ABOUT_EARLY_ACCESS_TOGGLE_ACTION ->
                (earlyAccessOption as? SettingsAboutEarlyAccessOption.HandsAlphaToggle)?.let { option ->
                    onToggleEarlyAccessChannel?.invoke(!option.enabled)
                }
            DEVELOPER_ACTION_HANDS_UPDATE_CHECK -> onCheckHandsUpdate()
            "openSourceLicenses" -> onOpenSourceLicenses()
            "copyEnvironmentInfo" -> onCopyEnvironmentInfo()
            DEVELOPER_ACTION_API_ENVIRONMENT, SETTINGS_ABOUT_CONNECTED_SERVER_ROW -> onOpenApiEnvironment()
            "diagnostics" -> onOpenDiagnostics()
            SETTINGS_ABOUT_FEEDBACK_ACTION -> onSendFeedback?.invoke()
            SETTINGS_ABOUT_VERSION_ROW -> if (onOpenDeveloper != null && versionTaps.tap()) onOpenDeveloper()
        }
    }
}

internal const val SETTINGS_ABOUT_FEEDBACK_ACTION = "sendFeedback"
internal const val SETTINGS_ABOUT_VERSION_ROW = "version"
internal const val SETTINGS_ABOUT_CONNECTED_SERVER_ROW = "connectedServer"
internal const val SETTINGS_ABOUT_SOURCE_CODE_ACTION = "openSourceCode"
internal const val SETTINGS_ABOUT_EARLY_ACCESS_TOGGLE_ACTION = "toggleEarlyAccessChannel"
internal const val SETTINGS_ABOUT_EARLY_ACCESS_EXTERNAL_ACTION = "openEarlyAccessProgram"

/**
 * Early-access enrollment option surfaced in Settings › About when gated on by the server
 * (`mobile_early_access_visible_v0`, `#proj-mobile:e48030bc`), replacing the retired `preview`
 * channel (#proj-mobile:#370).
 */
internal sealed interface SettingsAboutEarlyAccessOption {
    data class HandsAlphaToggle(
        val enabled: Boolean,
        val busy: Boolean = false
    ) : SettingsAboutEarlyAccessOption

    data class ExternalProgram(
        val kind: String,
        val url: String,
        val alreadyActive: Boolean = false
    ) : SettingsAboutEarlyAccessOption
}

internal fun settingsAboutEarlyAccessOption(
    earlyAccessVisible: Boolean,
    developer: SharedSettingsDeveloperSnapshot?,
    updateCheck: SharedSettingsUpdateCheckSnapshot?,
    actionState: SharedSettingsActionState?,
    canToggleHandsChannel: Boolean = true
): SettingsAboutEarlyAccessOption? {
    if (!earlyAccessVisible) return null
    val effectiveUpdate = updateCheck ?: developer?.updateCheck ?: return null
    val channel = effectiveUpdate.updateChannel.trim().lowercase()
    val switchSupported = effectiveUpdate.updateChannelSwitchSupported && canToggleHandsChannel
    if (switchSupported) {
        return SettingsAboutEarlyAccessOption.HandsAlphaToggle(
            enabled = channel == "alpha",
            busy = actionState?.action == DEVELOPER_ACTION_HANDS_UPDATE_CHANNEL && actionState.inProgress
        )
    }
    val guideKind = effectiveUpdate.earlyAccessGuideKind.trim()
    val guideUrl = effectiveUpdate.earlyAccessGuideUrl.trim()
    if (guideKind.isBlank() || !guideUrl.startsWith("https://")) return null
    return SettingsAboutEarlyAccessOption.ExternalProgram(
        kind = guideKind,
        url = guideUrl,
        alreadyActive = guideKind == RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_ACTIVE
    )
}

/** The app's own source repository, opened from Settings › About › Source code. */
internal const val RAFT_MOBILE_SOURCE_CODE_URL = "https://github.com/botiverse/mobile"

/**
 * The app's license: the Functional Source License 1.1, which converts to Apache 2.0 after two
 * years. It is source-available, not OSI open source, so the row says "Source code".
 */
internal const val RAFT_MOBILE_SOURCE_LICENSE = "FSL-1.1-ALv2"

/**
 * The host of the server the app talks to, for About's Connected server row; null on the official
 * Raft service (or before the environment loads), so the row only shows on a self-hosted or other
 * non-official server. Official means the production API base. (The floating environment indicator,
 * [raftEnvironmentIndicatorLabel], is narrower: staging only.)
 */
internal fun settingsAboutConnectedServerHost(developer: SharedSettingsDeveloperSnapshot?): String? {
    val apiBaseUrl = developer?.apiBaseUrl.orEmpty().trim()
    if (apiBaseUrl.isBlank()) return null
    if (apiBaseUrl.trimEnd('/') == SharedApiEnvironmentStore.Production.apiBaseUrl.trim().trimEnd('/')) return null
    val afterScheme = if ("://" in apiBaseUrl) apiBaseUrl.substringAfter("://") else apiBaseUrl
    return afterScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
        .lowercase()
        .takeIf(String::isNotBlank)
}

/**
 * What a long press on About's Version row copies: version, build, build time, Git commit and
 * package as one block, in English like the diagnostics text, for bug reports.
 */
internal fun SharedSettingsAboutSnapshot.settingsAboutVersionCopyText(): String =
    listOf(
        "Version" to versionLabel,
        "Build" to buildLabel,
        "Build time" to buildTime,
        "Git commit" to gitCommit,
        "Package" to packageName
    )
        .filter { (_, value) -> value.isNotBlank() }
        .joinToString("\n") { (label, value) -> "$label: ${value.trim()}" }

/** The Version row's value: `1.26.0 (12600002)`, the build in parentheses when known. */
internal fun SharedSettingsAboutSnapshot.settingsAboutVersionValue(unknown: String): String {
    val version = versionLabel.trim().ifBlank { unknown }
    val build = buildLabel.trim()
    return if (build.isBlank()) version else "$version ($build)"
}

/** The URL an About row opens in the system browser, or null for rows that don't leave Raft. */
internal fun settingsAboutExternalUrl(action: String, value: String?, webUrl: String): String? =
    when (action) {
        SETTINGS_ABOUT_SOURCE_CODE_ACTION -> value.orEmpty().ifBlank { RAFT_MOBILE_SOURCE_CODE_URL }
        SETTINGS_ABOUT_EARLY_ACCESS_EXTERNAL_ACTION ->
            value.orEmpty().trim().takeIf { it.startsWith("https://") }
        else -> null
    }

internal fun settingsAboutDeveloperShortcutRows(
    environmentTitle: String,
    environmentDetail: String,
    environmentStatus: String,
    environmentEnabled: Boolean,
    diagnosticsTitle: String,
    diagnosticsDetail: String,
    feedbackTitle: String,
    feedbackDetail: String,
    openStatus: String,
    includeFeedback: Boolean
): List<RaftSharedSettingsRow> =
    buildList {
        add(
            RaftSharedSettingsRow(
                key = DEVELOPER_ACTION_API_ENVIRONMENT,
                iconName = RaftLucideIconName.Server,
                title = environmentTitle,
                detail = environmentDetail,
                status = environmentStatus,
                enabled = environmentEnabled,
                copyValue = environmentDetail
            )
        )
        add(
            RaftSharedSettingsRow(
                key = "diagnostics",
                iconName = RaftLucideIconName.ClipboardList,
                title = diagnosticsTitle,
                detail = diagnosticsDetail,
                status = openStatus,
                enabled = true
            )
        )
        if (includeFeedback) {
            add(
                RaftSharedSettingsRow(
                    key = SETTINGS_ABOUT_FEEDBACK_ACTION,
                    iconName = RaftLucideIconName.Feedback,
                    title = feedbackTitle,
                    detail = feedbackDetail,
                    status = openStatus,
                    enabled = true
                )
            )
        }
    }

@Composable
internal fun SharedSettingsOpenSourceLicensesContent(
    state: ScreenLoadState<SharedSettingsOpenSourceLicensesSnapshot>,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onOpenUrl: (String) -> Unit
) {
    val payload =
        state.toOpenSourceLicensesPayload(
            errorStrings = localizedErrorPresentationStrings(),
            copy = settingsOpenSourceLicensesPayloadCopy()
        )
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding) { action, value ->
        when (action) {
            "openLicenseUrl", "openSourceUrl" ->
                value.orEmpty().takeIf { it.isNotBlank() }?.let(onOpenUrl)
        }
    }
}

internal data class SettingsAboutPayloadCopy(
    val title: String = "About",
    val detail: String = "Version, build, and source",
    val applicationSection: String = "Application",
    val version: String = "Version",
    val versionCopyHint: String = "Long press to copy",
    val connectedServer: String = "Connected server",
    val change: String = "Change",
    val unknown: String = "Unknown",
    val actions: String = "Actions",
    val checkUpdate: String = "Check for update",
    val checkUpdateDetail: String = "Checks for a newer mobile build and opens the installer when available.",
    val checking: String = "Checking",
    val check: String = "Check",
    val updateAvailable: String = "Update available",
    val preparing: String = "Preparing",
    val downloading: String = "Downloading",
    val installing: String = "Installing",
    val install: String = "Install",
    val restartToUpdate: String = "Restart to update",
    val installed: String = "Installed",
    val retry: String = "Retry",
    val permissionRequired: String = "Permission Required",
    val updateUnavailable: String = "Update information is temporarily unavailable.",
    val sourceCode: String = "Source code",
    val licenses: String = "Open Source Licenses",
    val licensesDetail: String = "Review third-party libraries included in the app package.",
    val open: String = "Open",
    val copyEnvironment: String = "Copy environment info",
    /** The copy row carries the environment only; app logs go with Feedback. */
    val copyEnvironmentHint: String = "To include logs, send them with Feedback.",
    val copy: String = "Copy",
    val earlyAccessTitle: String = "Early access",
    val earlyAccessAlphaOffDetail: String = "Receive pre-release Raft updates directly from the alpha channel.",
    val earlyAccessAlphaActiveDetail: String = "Receiving pre-release updates from the alpha channel. Turn off to rejoin Stable on the next public release.",
    val earlyAccessPlayBetaDetail: String = "Join the Google Play beta program to receive pre-release Raft updates.",
    val earlyAccessIosTestFlightJoinDetail: String = "Join Raft on TestFlight to receive pre-release iOS builds.",
    val earlyAccessIosTestFlightActiveDetail: String = "Running Raft on TestFlight and receiving pre-release iOS builds.",
    val earlyAccessJoin: String = "Join",
    val earlyAccessAlphaBadge: String = "Alpha",
    val earlyAccessTestFlightBadge: String = "TestFlight",
)

@Composable
private fun settingsAboutPayloadCopy(): SettingsAboutPayloadCopy {
    return SettingsAboutPayloadCopy(
        title = stringResource(Res.string.settings_about_title),
        detail = stringResource(Res.string.settings_about_version_build_source),
        applicationSection = stringResource(Res.string.settings_about_section_application),
        version = stringResource(Res.string.settings_about_label_version),
        versionCopyHint = stringResource(Res.string.settings_about_version_copy_hint),
        connectedServer = stringResource(Res.string.settings_about_label_connected_server),
        change = stringResource(Res.string.settings_about_action_change),
        unknown = stringResource(Res.string.settings_about_state_unknown),
        actions = stringResource(Res.string.settings_about_section_actions),
        checkUpdate = stringResource(Res.string.settings_about_action_check_update),
        checkUpdateDetail = stringResource(Res.string.settings_about_check_update_detail, raftPlatformProductName()),
        checking = stringResource(Res.string.settings_about_state_checking),
        check = stringResource(Res.string.settings_about_action_check),
        updateAvailable = stringResource(Res.string.settings_about_state_update_available),
        preparing = stringResource(Res.string.app_update_preparing),
        downloading = stringResource(Res.string.app_update_downloading),
        installing = stringResource(Res.string.app_update_installing),
        install = stringResource(Res.string.app_update_install),
        restartToUpdate = stringResource(Res.string.app_update_restart_to_update),
        installed = stringResource(Res.string.app_update_installed),
        retry = stringResource(Res.string.app_update_retry),
        permissionRequired = stringResource(Res.string.settings_state_permission_required),
        updateUnavailable = stringResource(Res.string.app_update_unavailable),
        sourceCode = stringResource(Res.string.settings_about_source_code),
        licenses = stringResource(Res.string.settings_open_source_licenses_title),
        licensesDetail = stringResource(Res.string.settings_about_licenses_detail, raftPlatformProductName()),
        open = stringResource(Res.string.settings_state_open),
        copyEnvironment = stringResource(Res.string.settings_about_action_copy_environment),
        copyEnvironmentHint = stringResource(Res.string.settings_diagnostics_logs_via_feedback_hint),
        copy = stringResource(Res.string.settings_about_action_copy),
        earlyAccessTitle = stringResource(Res.string.settings_about_early_access_title),
        earlyAccessAlphaOffDetail = stringResource(Res.string.settings_about_early_access_alpha_off_detail),
        earlyAccessAlphaActiveDetail = stringResource(Res.string.settings_about_early_access_alpha_active_detail),
        earlyAccessPlayBetaDetail = stringResource(Res.string.settings_about_early_access_play_beta_detail),
        earlyAccessIosTestFlightJoinDetail = stringResource(Res.string.settings_about_early_access_ios_testflight_join_detail),
        earlyAccessIosTestFlightActiveDetail = stringResource(Res.string.settings_about_early_access_ios_testflight_active_detail),
        earlyAccessJoin = stringResource(Res.string.settings_about_early_access_action_join),
        earlyAccessAlphaBadge = stringResource(Res.string.settings_about_early_access_badge_alpha),
        earlyAccessTestFlightBadge = stringResource(Res.string.settings_about_early_access_badge_testflight),
    )
}

@Composable
internal fun appShellAboutFallback(session: StoredAuthSession?): SharedSettingsAboutSnapshot =
    SharedSettingsAboutSnapshot(
        appName = "Raft",
        versionLabel = stringResource(Res.string.settings_state_loading),
        signedInUserLabel = session?.user?.bestDisplayName ?: stringResource(Res.string.settings_about_state_unknown),
        selectedServerLabel = session?.currentServerSummary()?.name ?: stringResource(Res.string.settings_about_state_none),
        sessionStatusLabel = if (session != null) stringResource(Res.string.settings_about_state_signed_in) else stringResource(Res.string.settings_about_state_signed_out),
        socketStatusLabel = stringResource(Res.string.settings_about_state_checking),
        notificationStatusLabel = stringResource(Res.string.settings_about_state_checking)
    )

internal fun SharedSettingsAboutSnapshot.toAboutPayload(
    webUrl: String,
    errorStrings: ErrorPresentationStrings,
    updateCheck: SharedSettingsUpdateCheckSnapshot? = null,
    actionState: SharedSettingsActionState? = null,
    /** The self-hosted or other non-official server's host ([settingsAboutConnectedServerHost]); null hides the row. */
    connectedServerHost: String? = null,
    /** Optional Botiverse early-access enrollment row (#proj-mobile:#370). */
    earlyAccessOption: SettingsAboutEarlyAccessOption? = null,
    copy: SettingsAboutPayloadCopy = SettingsAboutPayloadCopy()
): RaftSharedSettingsPayload {
    val raftLabel = "Raft"
    val updateCheckBusy =
        actionState?.action == DEVELOPER_ACTION_HANDS_UPDATE_CHECK && actionState.inProgress
    return RaftSharedSettingsPayload(
        instanceId = "shared-shell-settings-about",
        title = copy.title,
        heroIconName = RaftLucideIconName.Info,
        heroLabel = appName.ifBlank { raftLabel },
        detail = copy.detail,
        sections =
            listOf(
                RaftSharedSettingsSection(
                    title = copy.applicationSection,
                    rows =
                        buildList {
                            // Build time, Git commit and package are not rows of their own: a long
                            // press on Version copies them with the version and build.
                            val versionCopyText = settingsAboutVersionCopyText()
                            add(
                                RaftSharedSettingsRow(
                                    key = SETTINGS_ABOUT_VERSION_ROW,
                                    iconName = RaftLucideIconName.BadgeInfo,
                                    title = copy.version,
                                    detail = settingsAboutVersionValue(copy.unknown),
                                    supportingDetail = if (versionCopyText.isNotBlank()) copy.versionCopyHint else "",
                                    copyValue = versionCopyText,
                                    hiddenTap = true
                                )
                            )
                            connectedServerHost?.takeIf(String::isNotBlank)?.let { host ->
                                add(
                                    RaftSharedSettingsRow(
                                        key = SETTINGS_ABOUT_CONNECTED_SERVER_ROW,
                                        iconName = RaftLucideIconName.Server,
                                        title = copy.connectedServer,
                                        detail = host,
                                        status = copy.change,
                                        enabled = true,
                                        copyValue = host
                                    )
                                )
                            }
                        }
                ),
                RaftSharedSettingsSection(
                    title = copy.actions,
                    rows =
                        buildList {
                            if (updateCheck?.supported == true) {
                                add(
                                    RaftSharedSettingsRow(
                                        key = DEVELOPER_ACTION_HANDS_UPDATE_CHECK,
                                        iconName = RaftLucideIconName.Download,
                                        title = copy.checkUpdate,
                                        detail =
                                            when {
                                                // The settings store's empty-answer fallback carries English detail.
                                                updateCheck.errorCode == SETTINGS_ABOUT_UPDATE_CHECK_EMPTY -> copy.updateUnavailable
                                                else ->
                                                    updateCheck.detail.ifBlank {
                                                        updateCheck.screenError?.userFacingMessage(errorStrings)
                                                            ?: copy.checkUpdateDetail
                                                    }
                                            },
                                        status = settingsAboutUpdateStatus(updateCheck, updateCheckBusy, copy),
                                        enabled = !updateCheckBusy
                                    )
                                )
                            }
                            when (earlyAccessOption) {
                                is SettingsAboutEarlyAccessOption.HandsAlphaToggle -> {
                                    add(
                                        RaftSharedSettingsRow(
                                            key = SETTINGS_ABOUT_EARLY_ACCESS_TOGGLE_ACTION,
                                            iconName = RaftLucideIconName.FlaskConical,
                                            title = copy.earlyAccessTitle,
                                            detail =
                                                if (earlyAccessOption.enabled) {
                                                    copy.earlyAccessAlphaActiveDetail
                                                } else {
                                                    copy.earlyAccessAlphaOffDetail
                                                },
                                            badgeLabel =
                                                if (earlyAccessOption.enabled) {
                                                    copy.earlyAccessAlphaBadge
                                                } else {
                                                    ""
                                                },
                                            toggleValue = earlyAccessOption.enabled,
                                            loading = earlyAccessOption.busy,
                                            enabled = !earlyAccessOption.busy
                                        )
                                    )
                                }
                                is SettingsAboutEarlyAccessOption.ExternalProgram -> {
                                    val detailText =
                                        when (earlyAccessOption.kind) {
                                            RAFT_EARLY_ACCESS_GUIDE_GOOGLE_PLAY_BETA -> copy.earlyAccessPlayBetaDetail
                                            RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_ACTIVE -> copy.earlyAccessIosTestFlightActiveDetail
                                            RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_JOIN -> copy.earlyAccessIosTestFlightJoinDetail
                                            else -> copy.earlyAccessPlayBetaDetail
                                        }
                                    val statusText =
                                        when (earlyAccessOption.kind) {
                                            RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_ACTIVE -> copy.open
                                            RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_JOIN -> copy.earlyAccessTestFlightBadge
                                            else -> copy.earlyAccessJoin
                                        }
                                    add(
                                        RaftSharedSettingsRow(
                                            key = SETTINGS_ABOUT_EARLY_ACCESS_EXTERNAL_ACTION,
                                            iconName = RaftLucideIconName.FlaskConical,
                                            title = copy.earlyAccessTitle,
                                            detail = detailText,
                                            value = earlyAccessOption.url,
                                            badgeLabel =
                                                if (earlyAccessOption.alreadyActive) {
                                                    copy.earlyAccessTestFlightBadge
                                                } else {
                                                    ""
                                                },
                                            status = statusText,
                                            enabled = true,
                                            copyValue = earlyAccessOption.url,
                                            external = true
                                        )
                                    )
                                }
                                null -> Unit
                            }
                            add(
                                RaftSharedSettingsRow(
                                    key = SETTINGS_ABOUT_SOURCE_CODE_ACTION,
                                    iconName = RaftLucideIconName.Code,
                                    title = copy.sourceCode,
                                    detail = RAFT_MOBILE_SOURCE_LICENSE,
                                    value = RAFT_MOBILE_SOURCE_CODE_URL,
                                    status = copy.open,
                                    enabled = true,
                                    copyValue = RAFT_MOBILE_SOURCE_CODE_URL,
                                    external = true
                                )
                            )
                            add(
                                RaftSharedSettingsRow(
                                    key = "openSourceLicenses",
                                    iconName = RaftLucideIconName.FileText,
                                    title = copy.licenses,
                                    detail = copy.licensesDetail,
                                    status = copy.open,
                                    enabled = true
                                )
                            )
                            add(
                                RaftSharedSettingsRow(
                                    key = "copyEnvironmentInfo",
                                    iconName = RaftLucideIconName.Clipboard,
                                    title = copy.copyEnvironment,
                                    detail = copy.copyEnvironmentHint,
                                    status = copy.copy,
                                    enabled = true
                                )
                            )
                        }
                )
            )
    )
}

internal const val SETTINGS_ABOUT_UPDATE_CHECK_EMPTY = "update_check_empty"

/**
 * The Check for update row's trailing text. The host's `statusLabel` is an untranslated English
 * word ("Current", "Ready", "Downloading", ...), so the row never shows it: it maps the update's
 * state onto localized labels, worded like the update page. Up to date shows nothing, since the
 * description already says Raft is up to date.
 */
internal fun settingsAboutUpdateStatus(
    updateCheck: SharedSettingsUpdateCheckSnapshot,
    busy: Boolean,
    copy: SettingsAboutPayloadCopy
): String {
    val label = updateCheck.statusLabel.trim().lowercase()
    val failed =
        updateCheck.errorCode.isNotBlank() ||
            updateCheck.screenError != null ||
            label in setOf("failed", "check failed", "install failed")
    return when {
        updateCheck.transactionState == SharedSettingsUpdateTransactionState.Patching -> copy.preparing
        updateCheck.transactionState == SharedSettingsUpdateTransactionState.Downloading -> copy.downloading
        label == "preparing" -> copy.preparing
        label == "downloading" -> copy.downloading
        label == "installing" -> copy.installing
        busy -> copy.checking
        updateCheck.errorCode == "install_permission_required" || label == "permission required" ->
            copy.permissionRequired
        updateCheck.transactionState == SharedSettingsUpdateTransactionState.Checking -> copy.checking
        updateCheck.transactionState == SharedSettingsUpdateTransactionState.ReadyToRestart -> copy.restartToUpdate
        updateCheck.transactionState == SharedSettingsUpdateTransactionState.ReadyToInstall ||
            updateCheck.transactionState == SharedSettingsUpdateTransactionState.InstallerOpened -> copy.install
        updateCheck.transactionState == SharedSettingsUpdateTransactionState.Installed -> copy.installed
        updateCheck.transactionState == SharedSettingsUpdateTransactionState.Failed ||
            updateCheck.transactionState == SharedSettingsUpdateTransactionState.Stale -> copy.retry
        failed -> copy.retry
        label == "checking" -> copy.checking
        label == "install" -> copy.install
        label == "installed" -> copy.installed
        updateCheck.updateAvailable -> copy.updateAvailable
        settingsAboutUpdateIsCurrent(updateCheck, label) -> ""
        else -> copy.check
    }
}

/** A check ran and found nothing newer: the row's description already says so. */
private fun settingsAboutUpdateIsCurrent(updateCheck: SharedSettingsUpdateCheckSnapshot, label: String): Boolean =
    !updateCheck.updateAvailable &&
        (label == "current" ||
            updateCheck.checkedAtLabel.isNotBlank() ||
            (updateCheck.storeChannel.isNotBlank() && updateCheck.detail.isNotBlank()))

private fun SharedSettingsAboutSnapshot.buildSummary(): String =
    buildString {
        append(appName.ifBlank { "Raft" })
        versionLabel.takeIf { it.isNotBlank() }?.let { append(" $it") }
        buildLabel.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
        packageName.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
    }

internal data class SettingsOpenSourceLicensesPayloadCopy(
    val title: String = "Open Source Licenses",
    val detail: (Int, String) -> String = { count, source -> "$count libraries from $source" },
    val defaultSource: String = "Release runtime",
    val sectionLicenses: String = "Licenses",
    val loading: String = "Loading",
    val loadingDetail: String = "Loading generated package license metadata.",
    val wait: String = "Wait",
    val unavailable: String = "Unavailable",
    val emptyTitle: String = "No licenses found",
    val emptyDetail: String = "The generated package license asset did not include any dependencies.",
    val empty: String = "Empty",
    val libraries: String = "Libraries",
    val numberedLibraries: (Int) -> String = { section -> "Libraries $section" },
    val licenseUnknown: String = "License unknown",
    val licenseLink: String = "License link",
    val sourceLink: String = "Source link",
    val noLink: String = "No link",
    val license: String = "License",
    val source: String = "Source"
)

@Composable
private fun settingsOpenSourceLicensesPayloadCopy(): SettingsOpenSourceLicensesPayloadCopy {
    val detailTemplate = stringResource(Res.string.settings_open_source_licenses_detail_libraries_from)
    val numberedLibrariesTemplate = stringResource(Res.string.settings_open_source_licenses_section_libraries_numbered)
    return SettingsOpenSourceLicensesPayloadCopy(
        title = stringResource(Res.string.settings_open_source_licenses_title),
        detail = { count, source -> detailTemplate.replaceLocalizedFormatArgs(count, source) },
        defaultSource = stringResource(Res.string.settings_open_source_licenses_default_source, raftPlatformProductName()),
        sectionLicenses = stringResource(Res.string.settings_licenses_title),
        loading = stringResource(Res.string.settings_open_source_licenses_state_loading),
        loadingDetail = stringResource(Res.string.settings_open_source_licenses_loading_detail, raftPlatformProductName()),
        wait = stringResource(Res.string.settings_open_source_licenses_state_wait),
        unavailable = stringResource(Res.string.settings_open_source_licenses_action_unavailable),
        emptyTitle = stringResource(Res.string.settings_open_source_licenses_empty_title),
        emptyDetail = stringResource(Res.string.settings_open_source_licenses_empty_detail, raftPlatformProductName()),
        empty = stringResource(Res.string.settings_open_source_licenses_state_empty),
        libraries = stringResource(Res.string.settings_open_source_licenses_section_libraries),
        numberedLibraries = { section -> numberedLibrariesTemplate.replaceLocalizedFormatArgs(section) },
        licenseUnknown = stringResource(Res.string.settings_open_source_licenses_license_unknown),
        licenseLink = stringResource(Res.string.settings_open_source_licenses_license_link),
        sourceLink = stringResource(Res.string.settings_open_source_licenses_source_link),
        noLink = stringResource(Res.string.settings_open_source_licenses_no_link),
        license = stringResource(Res.string.settings_open_source_licenses_action_license),
        source = stringResource(Res.string.settings_open_source_licenses_action_source)
    )
}

internal fun ScreenLoadState<SharedSettingsOpenSourceLicensesSnapshot>.toOpenSourceLicensesPayload(
    errorStrings: ErrorPresentationStrings,
    copy: SettingsOpenSourceLicensesPayloadCopy = SettingsOpenSourceLicensesPayloadCopy()
): RaftSharedSettingsPayload {
    val snapshot = (this as? ScreenLoadState.Content<SharedSettingsOpenSourceLicensesSnapshot>)?.data
    val detail =
        when (this) {
            is ScreenLoadState.Content ->
                copy.detail(
                    snapshot?.items.orEmpty().size,
                    snapshot?.sourceConfiguration.orEmpty().ifBlank { copy.defaultSource }
                )
            is ScreenLoadState.Error -> error.userFacingMessage(errorStrings)
            is ScreenLoadState.Empty -> reason.userFacingMessage(errorStrings)
            is ScreenLoadState.NoPermission -> blocker.userFacingMessage(errorStrings)
            ScreenLoadState.Loading -> copy.loadingDetail
        }
    val sections =
        when {
            snapshot == null ->
                listOf(
                    RaftSharedSettingsSection(
                        title = copy.sectionLicenses,
                        rows =
                            listOf(
                                RaftSharedSettingsRow(
                                    key = "loading",
                                    iconName = RaftLucideIconName.TriangleAlert,
                                    loading = this == ScreenLoadState.Loading,
                                    title = if (this == ScreenLoadState.Loading) copy.loading else copy.unavailable,
                                    detail = detail,
                                    status = if (this == ScreenLoadState.Loading) copy.wait else copy.unavailable
                                )
                            )
                    )
                )
            snapshot.items.isEmpty() ->
                listOf(
                    RaftSharedSettingsSection(
                        title = copy.sectionLicenses,
                        rows =
                            listOf(
                                RaftSharedSettingsRow(
                                    key = "empty",
                                    iconName = RaftLucideIconName.FileText,
                                    title = copy.emptyTitle,
                                    detail = copy.emptyDetail,
                                    status = copy.empty
                                )
                            )
                    )
                )
            else ->
                snapshot.items
                    .sortedWith(compareBy({ it.displayName.ifBlank { it.name }.lowercase() }, { it.version }))
                    .chunked(32)
                    .mapIndexed { sectionIndex, items ->
                        RaftSharedSettingsSection(
                            title = if (sectionIndex == 0) copy.libraries else copy.numberedLibraries(sectionIndex + 1),
                            rows =
                                items.map { item ->
                                    val title = item.displayName.ifBlank { item.name.ifBlank { item.id } }
                                    val link = item.licenseUrl.ifBlank { item.sourceUrl }
                                    RaftSharedSettingsRow(
                                        key = "openLicenseUrl",
                                        iconName = RaftLucideIconName.Package,
                                        title = title,
                                        detail = item.settingsLicenseCoordinates(),
                                        value = link,
                                        status = item.settingsLicenseActionLabel(copy),
                                        enabled = link.isNotBlank(),
                                        supportingDetail = item.settingsLicenseMetaLine(copy)
                                    )
                                }
                        )
                    }
        }
    return RaftSharedSettingsPayload(
        instanceId = "shared-shell-settings-open-source-licenses",
        title = copy.title,
        heroIconName = RaftLucideIconName.FileText,
        heroLabel = copy.title,
        detail = detail,
        sections = sections
    )
}

private fun SharedSettingsOpenSourceLicenseItem.settingsLicenseCoordinates(): String =
    listOf(group, name, version)
        .filter { it.isNotBlank() }
        .joinToString(":")
        .ifBlank { id }

private fun SharedSettingsOpenSourceLicenseItem.settingsLicenseMetaLine(copy: SettingsOpenSourceLicensesPayloadCopy): String =
    listOf(
        licenseName.ifBlank { copy.licenseUnknown },
        when {
            licenseUrl.isNotBlank() -> copy.licenseLink
            sourceUrl.isNotBlank() -> copy.sourceLink
            else -> copy.noLink
        }
    ).joinToString(" / ")

private fun SharedSettingsOpenSourceLicenseItem.settingsLicenseActionLabel(copy: SettingsOpenSourceLicensesPayloadCopy): String =
    when {
        licenseUrl.isNotBlank() -> copy.license
        sourceUrl.isNotBlank() -> copy.source
        else -> copy.unavailable
    }
