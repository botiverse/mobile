package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.LANGUAGE_REGION_ACTION_AUTO_TRANSLATION
import build.raft.app.data.LANGUAGE_REGION_ACTION_LANGUAGE
import build.raft.app.data.LANGUAGE_REGION_ACTION_SERVER_TRANSLATION
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIME_FORMAT
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIMEZONE
import build.raft.app.data.LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsLanguageRegionSnapshot
import build.raft.app.data.TranslationTargetLanguages
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map

@Composable
internal fun SettingsLanguageRegionContent(
    state: ScreenLoadState<SettingsLanguageRegionSnapshot>,
    mobileState: ScreenLoadState<SettingsMobileSnapshot>,
    actionState: SharedSettingsActionState?,
    languageDraft: String,
    displayDraft: String,
    timezoneDraft: String,
    timeFormatDraft: String,
    bottomContentPadding: Dp,
    onLanguageDraftChange: (String) -> Unit,
    onDisplayDraftChange: (String) -> Unit,
    onTimezoneDraftChange: (String) -> Unit,
    onTimeFormatDraftChange: (String) -> Unit,
    onServerTranslationChange: (Boolean) -> Unit,
    onLocaleChange: (String) -> Unit,
    onSaveLanguage: () -> Unit,
    onSaveTime: () -> Unit,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).raftSafeAreaHorizontalPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Languages,
                        stringResource(Res.string.common_loading),
                        stringResource(Res.string.settings_language_loading),
                        null,
                        onRetry,
                        loading = true
                    )
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Languages,
                        stringResource(Res.string.settings_state_unavailable),
                        state.reason.localizedUserFacingMessage(),
                        stringResource(Res.string.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.TriangleAlert,
                        stringResource(Res.string.settings_error_language),
                        state.error.localizedUserFacingMessage(),
                        stringResource(Res.string.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Lock,
                        stringResource(Res.string.settings_state_permission_required),
                        state.blocker.localizedUserFacingMessage(),
                        null,
                        onRetry
                    )
                }
            is ScreenLoadState.Content -> {
                val snapshot = state.data
                (mobileState as? ScreenLoadState.Content)?.data?.let { mobileSnapshot ->
                    item {
                        SettingsAppLanguageCard(
                            snapshot = mobileSnapshot,
                            actionState = actionState,
                            onLocaleChange = onLocaleChange
                        )
                    }
                }
                item {
                    SettingsLanguagePreferenceCard(
                        snapshot = snapshot,
                        languageDraft = languageDraft,
                        displayDraft = displayDraft,
                        actionState = actionState,
                        onLanguageDraftChange = onLanguageDraftChange,
                        onDisplayDraftChange = onDisplayDraftChange,
                        onSubmit = onSaveLanguage
                    )
                }
                item {
                    SettingsDateTimePreferenceCard(
                        snapshot = snapshot,
                        timezoneDraft = timezoneDraft,
                        timeFormatDraft = timeFormatDraft,
                        actionState = actionState,
                        onTimezoneDraftChange = onTimezoneDraftChange,
                        onTimeFormatDraftChange = onTimeFormatDraftChange,
                        onSubmit = onSaveTime
                    )
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsLanguagePreferenceCard(
    snapshot: SettingsLanguageRegionSnapshot,
    languageDraft: String,
    displayDraft: String,
    actionState: SharedSettingsActionState?,
    onLanguageDraftChange: (String) -> Unit,
    onDisplayDraftChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val languageBusy = actionState?.action == LANGUAGE_REGION_ACTION_LANGUAGE && actionState.inProgress
    val displayBusy = actionState?.action == LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY && actionState.inProgress
    val busy = languageBusy || displayBusy
    val currentDisplay = snapshot.translationDisplay.selectedKey
    val selectedDisplay = displayDraft.ifBlank { currentDisplay }
    val languageDirty = selectedDisplay != "original" && languageDraft != snapshot.browserLanguage.selectedKey
    val displayDirty = selectedDisplay != currentDisplay
    val dirty = languageDirty || displayDirty
    val canSave =
        !busy &&
            dirty &&
            ((languageDirty && snapshot.browserLanguage.available) || displayDirty)
    val saveLabel = actionState.languageRegionSaveLabel(LANGUAGE_REGION_LANGUAGE_ACTIONS)
    SettingsFormSectionCard(iconName = RaftLucideIconName.Languages, title = stringResource(Res.string.settings_language_title)) {
        SettingsSegmentedControlField(
            label = stringResource(Res.string.settings_translation_mode),
            detail = stringResource(Res.string.settings_translation_mode_detail),
            selectedValue = selectedDisplay,
            options = snapshot.translationDisplay.toTranslationModeOptions(),
            onValueSelected = onDisplayDraftChange,
            enabled = !busy
        )
        if (!snapshot.translationDisplay.available) {
            SettingsFormDetailText(snapshot.translationDisplay.detail)
        }
        if (selectedDisplay != "original") {
            SettingsComboboxField(
                label = stringResource(Res.string.settings_preferred_translation_language_title),
                description = stringResource(Res.string.settings_preferred_translation_language_detail),
                value = languageDraft.ifBlank { snapshot.browserLanguage.selectedKey },
                options = snapshot.browserLanguage.toTranslationTargetOptions(),
                onValueChange = onLanguageDraftChange,
                enabled = snapshot.browserLanguage.available && !busy
            )
        }
        if (actionState?.let { it.action in setOf(LANGUAGE_REGION_ACTION_LANGUAGE, LANGUAGE_REGION_ACTION_AUTO_TRANSLATION, LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY) && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState)
        }
        SettingsLanguageRegionSaveButton(
            label = saveLabel,
            enabled = canSave,
            onClick = onSubmit
        )
    }
}

@Composable
private fun SettingsDateTimePreferenceCard(
    snapshot: SettingsLanguageRegionSnapshot,
    timezoneDraft: String,
    timeFormatDraft: String,
    actionState: SharedSettingsActionState?,
    onTimezoneDraftChange: (String) -> Unit,
    onTimeFormatDraftChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val timezoneBusy = actionState?.action == LANGUAGE_REGION_ACTION_TIMEZONE && actionState.inProgress
    val timeFormatBusy = actionState?.action == LANGUAGE_REGION_ACTION_TIME_FORMAT && actionState.inProgress
    val busy = timezoneBusy || timeFormatBusy
    val dirty = timezoneDraft != snapshot.timezone.selectedKey || timeFormatDraft != snapshot.timeFormat.selectedKey
    val canSave = dirty && !busy && (snapshot.timezone.available || snapshot.timeFormat.available)
    val saveLabel = actionState.languageRegionSaveLabel(LANGUAGE_REGION_TIME_ACTIONS)
    SettingsFormSectionCard(iconName = RaftLucideIconName.Clock, title = stringResource(Res.string.settings_date_time_title)) {
        SettingsComboboxField(
            label = stringResource(Res.string.settings_timezone),
            description = stringResource(Res.string.settings_timezone_detail),
            value = timezoneDraft.ifBlank { snapshot.timezone.selectedKey },
            options = snapshot.timezone.toComboboxOptions(),
            onValueChange = onTimezoneDraftChange,
            enabled = snapshot.timezone.available && !busy
        )
        SettingsSegmentedControlField(
            label = stringResource(Res.string.settings_time_format),
            detail = stringResource(Res.string.settings_time_format_detail),
            selectedValue = timeFormatDraft.ifBlank { snapshot.timeFormat.selectedKey },
            options =
                listOf(
                    SegmentedControlOption("12h", stringResource(Res.string.settings_time_format_12_hour), RaftLucideIconName.Clock3),
                    SegmentedControlOption("24h", stringResource(Res.string.settings_time_format_24_hour), RaftLucideIconName.Clock)
                ),
            onValueSelected = onTimeFormatDraftChange,
            enabled = snapshot.timeFormat.available && !busy
        )
        if (actionState?.let { it.action in setOf(LANGUAGE_REGION_ACTION_TIMEZONE, LANGUAGE_REGION_ACTION_TIME_FORMAT) && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState)
        }
        SettingsLanguageRegionSaveButton(
            label = saveLabel,
            enabled = canSave,
            onClick = onSubmit
        )
    }
}

private val LANGUAGE_REGION_LANGUAGE_ACTIONS =
    setOf(LANGUAGE_REGION_ACTION_LANGUAGE, LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY, LANGUAGE_REGION_ACTION_AUTO_TRANSLATION)

private val LANGUAGE_REGION_TIME_ACTIONS =
    setOf(LANGUAGE_REGION_ACTION_TIMEZONE, LANGUAGE_REGION_ACTION_TIME_FORMAT)

internal fun resolveSettingsTranslationTargetUpdate(
    snapshot: SettingsLanguageRegionSnapshot,
    languageDraft: String,
    displayDraft: String
): String? {
    val currentDisplay = snapshot.translationDisplay.selectedKey
    val nextDisplay = displayDraft.ifBlank { currentDisplay }
    val currentLanguage = snapshot.browserLanguage.selectedKey
    val nextLanguage = languageDraft.ifBlank { currentLanguage }
    val languageChanged = nextDisplay != "original" && nextLanguage != currentLanguage
    if (!languageChanged) return null
    return nextLanguage
}

private fun SettingsMobileSelectionSnapshot.toTranslationModeOptions(): List<SegmentedControlOption> =
    options.map { option ->
        SegmentedControlOption(
            value = option.key,
            label = option.label,
            iconName =
                when (option.key) {
                    "automatic" -> RaftLucideIconName.Languages
                    "manual" -> RaftLucideIconName.Pencil
                    else -> RaftLucideIconName.FileText
                }
        )
    }

@Composable
private fun build.raft.app.data.SharedSettingsActionState?.languageRegionSaveLabel(
    actions: Set<String>
): String =
    when {
        this == null || action !in actions -> stringResource(Res.string.common_save)
        inProgress -> stringResource(Res.string.settings_state_saving)
        errorCode != null -> stringResource(Res.string.common_retry)
        else -> stringResource(Res.string.settings_state_saved)
    }

@Composable
private fun SettingsLanguageRegionSaveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    RaftButton(
        text = label,
        onClick = onClick,
        enabled = enabled,
        variant = RaftButtonVariant.Primary,
        size = RaftButtonSize.Md,
        leadingIcon = { RaftSlotIcon(RaftLucideIconName.Save) }
    )
}

@Composable
private fun SettingsServerTranslationPreferenceCard(
    snapshot: SettingsLanguageRegionSnapshot,
    actionState: SharedSettingsActionState?,
    onServerTranslationChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == LANGUAGE_REGION_ACTION_SERVER_TRANSLATION && actionState.inProgress
    SettingsFormSectionCard(iconName = RaftLucideIconName.Wand, title = stringResource(Res.string.settings_workspace_translation)) {
        SettingsSegmentedControlField(
            detail = snapshot.serverTranslation.detail,
            selectedValue = if (snapshot.serverTranslation.enabled) "on" else "off",
            options =
                listOf(
                    SegmentedControlOption("on", stringResource(Res.string.settings_option_on), RaftLucideIconName.CircleCheck),
                    SegmentedControlOption("off", stringResource(Res.string.settings_option_off), RaftLucideIconName.X)
                ),
            onValueSelected = { onServerTranslationChange(it == "on") },
            enabled = snapshot.serverTranslation.available && !busy
        )
        if (actionState?.action == LANGUAGE_REGION_ACTION_SERVER_TRANSLATION && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

private fun SettingsMobileSelectionSnapshot.toComboboxOptions(): List<RaftComboboxOption<String>> {
    val listed = options.map { RaftComboboxOption(it.key, it.label) }
    // A saved value the list does not offer still shows under its own label.
    return if (selectedKey.isBlank() || options.any { it.key == selectedKey }) listed else listed + RaftComboboxOption(selectedKey, selectedLabel.ifBlank { selectedKey })
}

@Composable
private fun SettingsMobileSelectionSnapshot.toTranslationTargetOptions(): List<RaftComboboxOption<String>> {
    val systemLanguageLabel = stringResource(Res.string.settings_translation_target_follow_display_language)
    return toComboboxOptions().map { option ->
        if (option.value == TranslationTargetLanguages.System) option.copy(label = systemLanguageLabel) else option
    }
}

private fun ScreenLoadState<SettingsLanguageRegionSnapshot>.toLanguageRegionPayload(
    actionState: build.raft.app.data.SharedSettingsActionState?,
    refreshing: Boolean,
    errorStrings: ErrorPresentationStrings
): RaftSharedSettingsPayload {
    val languageRegion =
        (this as? ScreenLoadState.Content<SettingsLanguageRegionSnapshot>)?.data
            ?: return appShellLanguageRegionPlaceholderPayload(this, refreshing, errorStrings)
    return RaftSharedSettingsPayload(
        instanceId = "shared-shell-settings-language-region",
        title = "Language & Region",
        heroIconName = RaftLucideIconName.Languages,
        heroLabel = "Language & Region",
        detail =
            if (refreshing) {
                "Refreshing language and region settings..."
            } else {
                "Manage language, timezone, and translation preferences."
            },
        sections =
            listOf(
                RaftSharedSettingsSection(
                    title = "Language",
                    rows =
                        listOf(
                            RaftSharedSettingsRow(
                                key = LANGUAGE_REGION_ACTION_SERVER_TRANSLATION,
                                iconName = RaftLucideIconName.Languages,
                                title = "Enable message translation for this server",
                                detail =
                                    languageRegion.serverTranslation.errorPresentation?.resolve(errorStrings)
                                        ?: languageRegion.serverTranslation.detail,
                                value = (!languageRegion.serverTranslation.enabled).toString(),
                                status =
                                    actionState.statusFor(
                                        LANGUAGE_REGION_ACTION_SERVER_TRANSLATION,
                                        languageRegion.serverTranslation.statusLabel
                                    ),
                                enabled = languageRegion.serverTranslation.available && actionState?.inProgress != true
                            ),
                            RaftSharedSettingsRow(
                                key = LANGUAGE_REGION_ACTION_AUTO_TRANSLATION,
                                iconName = RaftLucideIconName.Wand,
                                title = "Auto-translate messages",
                                detail =
                                    languageRegion.autoTranslation.errorPresentation?.resolve(errorStrings)
                                        ?: languageRegion.autoTranslation.detail,
                                value = (!languageRegion.autoTranslation.enabled).toString(),
                                status =
                                    actionState.statusFor(
                                        LANGUAGE_REGION_ACTION_AUTO_TRANSLATION,
                                        languageRegion.autoTranslation.statusLabel
                                    ),
                                enabled = languageRegion.autoTranslation.available && actionState?.inProgress != true
                            ),
                            languageRegion.browserLanguage.toMobileSelectionRow(
                                key = LANGUAGE_REGION_ACTION_LANGUAGE,
                                iconName = RaftLucideIconName.Globe,
                                title = "Preferred translation language",
                                fallbackStatus = "Others",
                                actionState = actionState
                            )
                        )
                ),
                RaftSharedSettingsSection(
                    title = "Date & Time",
                    rows =
                        listOf(
                            languageRegion.timezone.toMobileSelectionRow(
                                key = LANGUAGE_REGION_ACTION_TIMEZONE,
                                iconName = RaftLucideIconName.Clock,
                                title = "Timezone",
                                fallbackStatus = "UTC",
                                actionState = actionState
                            ),
                            languageRegion.timeFormat.toMobileSelectionRow(
                                key = LANGUAGE_REGION_ACTION_TIME_FORMAT,
                                iconName = RaftLucideIconName.Clock3,
                                title = "Time format",
                                fallbackStatus = "24-hour",
                                actionState = actionState
                            )
                        )
                )
            )
    )
}

private fun appShellLanguageRegionPlaceholderPayload(
    state: ScreenLoadState<SettingsLanguageRegionSnapshot>,
    refreshing: Boolean,
    errorStrings: ErrorPresentationStrings
): RaftSharedSettingsPayload {
    val status =
        when (state) {
            ScreenLoadState.Loading -> if (refreshing) "Refreshing" else "Loading"
            is ScreenLoadState.Empty -> "Unavailable"
            is ScreenLoadState.Error -> "Error"
            is ScreenLoadState.NoPermission -> "No permission"
            is ScreenLoadState.Content -> "Ready"
        }
    val detail =
        when (state) {
            is ScreenLoadState.Error -> state.error.userFacingMessage(errorStrings)
            is ScreenLoadState.NoPermission -> state.blocker.userFacingMessage(errorStrings)
            is ScreenLoadState.Empty -> state.reason.userFacingMessage(errorStrings)
            else -> "Loading language and region settings."
        }
    return RaftSharedSettingsPayload(
        instanceId = "shared-shell-settings-language-region-placeholder",
        title = "Language & Region",
        heroIconName = RaftLucideIconName.Languages,
        heroLabel = "Language & Region",
        detail = detail,
        sections =
            listOf(
                RaftSharedSettingsSection(
                    title = "Status",
                    rows =
                        listOf(
                            RaftSharedSettingsRow(
                                key = "languageRegionStatus",
                                iconName = RaftLucideIconName.Languages,
                                title = "Language & Region",
                                detail = detail,
                                status = status,
                                enabled = false
                            )
                        )
                )
            )
    )
}
