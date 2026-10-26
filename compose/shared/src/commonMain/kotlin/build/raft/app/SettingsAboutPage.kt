package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.DEVELOPER_ACTION_API_ENVIRONMENT
import build.raft.app.data.DEVELOPER_ACTION_HANDS_UPDATE_CHECK
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.SharedSettingsOpenSourceLicenseItem
import build.raft.app.data.SharedSettingsOpenSourceLicensesSnapshot
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.ScreenLoadState
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
    onOpenRaft: (String) -> Unit,
    onCheckHandsUpdate: () -> Unit,
    onPrefetchReleaseNotes: suspend (String) -> Unit = {},
    onOpenSourceLicenses: () -> Unit,
    onCopyEnvironmentInfo: () -> Unit,
    onOpenApiEnvironment: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onSendFeedback: (() -> Unit)? = null
) {
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
    val aboutPayload =
        snapshot.toAboutPayload(
            webUrl = webUrl,
            errorStrings = localizedErrorPresentationStrings(),
            updateCheck = updateCheck,
            actionState = actionState,
            copy = settingsAboutPayloadCopy(sharedSettingsResolvedWebBaseUrl(webUrl))
        )
    val developer = (developerState as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data
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
            "openSlock" -> onOpenRaft(value.orEmpty().ifBlank { webUrl })
            DEVELOPER_ACTION_HANDS_UPDATE_CHECK -> onCheckHandsUpdate()
            "openSourceLicenses" -> onOpenSourceLicenses()
            "copyEnvironmentInfo" -> onCopyEnvironmentInfo()
            DEVELOPER_ACTION_API_ENVIRONMENT -> onOpenApiEnvironment()
            "diagnostics" -> onOpenDiagnostics()
            SETTINGS_ABOUT_FEEDBACK_ACTION -> onSendFeedback?.invoke()
        }
    }
}

internal const val SETTINGS_ABOUT_FEEDBACK_ACTION = "sendFeedback"

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
                    iconName = RaftLucideIconName.MessageSquarePlus,
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
    val application: String = "Application",
    val version: String = "Version",
    val buildTime: String = "Build time",
    val gitCommit: String = "Git commit",
    val packageLabel: String = "Package",
    val unknown: String = "Unknown",
    val buildUnknown: String = "Build unknown",
    val actions: String = "Actions",
    val checkUpdate: String = "Check for update",
    val checkUpdateDetail: String = "Checks for a newer mobile build and opens the installer when available.",
    val checking: String = "Checking",
    val check: String = "Check",
    val licenses: String = "Open Source Licenses",
    val licensesDetail: String = "Review third-party libraries included in the app package.",
    val open: String = "Open",
    val copyEnvironment: String = "Copy environment info",
    val copyEnvironmentDetail: String = "Copy the same sanitized environment used for diagnostics and feedback.",
    val copy: String = "Copy",
    val openRaft: String = "Open Raft",
    val openWebDetail: (String) -> String = { "Open $it in the system browser." }
)

@Composable
private fun settingsAboutPayloadCopy(resolvedWebUrl: String): SettingsAboutPayloadCopy {
    val localizedOpenWebDetail = Res.string.settings_about_open_web_detail.format(listOf(resolvedWebUrl)).localized()
    return SettingsAboutPayloadCopy(
        title = stringResource(Res.string.settings_about_title),
        detail = stringResource(Res.string.settings_about_version_build_source),
        applicationSection = stringResource(Res.string.settings_about_section_application),
        application = stringResource(Res.string.settings_about_label_application),
        version = stringResource(Res.string.settings_about_label_version),
        buildTime = stringResource(Res.string.settings_about_label_build_time),
        gitCommit = stringResource(Res.string.settings_about_label_git_commit),
        packageLabel = stringResource(Res.string.settings_about_label_package),
        unknown = stringResource(Res.string.settings_about_state_unknown),
        buildUnknown = stringResource(Res.string.settings_about_state_build_unknown),
        actions = stringResource(Res.string.settings_about_section_actions),
        checkUpdate = stringResource(Res.string.settings_about_action_check_update),
        checkUpdateDetail = stringResource(Res.string.settings_about_check_update_detail, raftPlatformProductName()),
        checking = stringResource(Res.string.settings_about_state_checking),
        check = stringResource(Res.string.settings_about_action_check),
        licenses = stringResource(Res.string.settings_open_source_licenses_title),
        licensesDetail = stringResource(Res.string.settings_about_licenses_detail, raftPlatformProductName()),
        open = stringResource(Res.string.settings_state_open),
        copyEnvironment = stringResource(Res.string.settings_about_action_copy_environment),
        copyEnvironmentDetail = stringResource(Res.string.settings_about_copy_environment_detail),
        copy = stringResource(Res.string.settings_about_action_copy),
        openRaft = stringResource(Res.string.settings_about_action_open_raft),
        openWebDetail = { localizedOpenWebDetail }
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
    copy: SettingsAboutPayloadCopy = SettingsAboutPayloadCopy()
): RaftSharedSettingsPayload {
    val resolvedWebUrl = sharedSettingsResolvedWebBaseUrl(webUrl)
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
                        listOf(
                            RaftSharedSettingsRow("appName", RaftLucideIconName.Sparkles, copy.application, appName.ifBlank { raftLabel }, copyValue = appName.ifBlank { raftLabel }),
                            RaftSharedSettingsRow("version", RaftLucideIconName.BadgeInfo, copy.version, versionLabel.ifBlank { copy.unknown }, status = buildLabel.ifBlank { copy.buildUnknown }, copyValue = versionLabel),
                            RaftSharedSettingsRow("buildTime", RaftLucideIconName.Clock, copy.buildTime, buildTime.ifBlank { copy.unknown }, copyValue = buildTime),
                            RaftSharedSettingsRow("gitCommit", RaftLucideIconName.GitBranch, copy.gitCommit, gitCommit.ifBlank { copy.unknown }, copyValue = gitCommit),
                            RaftSharedSettingsRow("package", RaftLucideIconName.Package, copy.packageLabel, packageName.ifBlank { copy.unknown }, copyValue = packageName)
                        )
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
                                            updateCheck.detail.ifBlank {
                                                updateCheck.screenError?.userFacingMessage(errorStrings)
                                                    ?: copy.checkUpdateDetail
                                            },
                                        status =
                                            when {
                                                updateCheckBusy -> copy.checking
                                                updateCheck.statusLabel.isNotBlank() -> updateCheck.statusLabel
                                                else -> copy.check
                                            },
                                        enabled = !updateCheckBusy
                                    )
                                )
                            }
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
                                    detail = copy.copyEnvironmentDetail,
                                    status = copy.copy,
                                    enabled = true
                                )
                            )
                            add(RaftSharedSettingsRow("openSlock", RaftLucideIconName.Globe, copy.openRaft, copy.openWebDetail(resolvedWebUrl), value = resolvedWebUrl, status = copy.open, enabled = true, external = true))
                        }
                )
            )
    )
}

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
