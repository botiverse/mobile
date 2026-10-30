package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.LANGUAGE_REGION_ACTION_AUTO_TRANSLATION
import build.raft.app.data.LANGUAGE_REGION_ACTION_LANGUAGE
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIME_FORMAT
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIMEZONE
import build.raft.app.data.LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SettingsLanguageRegionPreferenceUpdateRequest
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
    pick: SettingsLanguageRegionPick?,
    bottomContentPadding: Dp,
    onPick: (SettingsLanguageRegionPick) -> Unit,
    onServerTranslationChange: (Boolean) -> Unit,
    onLocaleChange: (String) -> Unit,
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
                val busy = settingsLanguageRegionBusy(actionState, pick)
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
                        actionState = actionState,
                        pick = pick,
                        busy = busy,
                        onPick = onPick
                    )
                }
                item {
                    SettingsDateTimePreferenceCard(
                        snapshot = snapshot,
                        actionState = actionState,
                        pick = pick,
                        busy = busy,
                        onPick = onPick
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
    actionState: SharedSettingsActionState?,
    pick: SettingsLanguageRegionPick?,
    busy: Boolean,
    onPick: (SettingsLanguageRegionPick) -> Unit
) {
    val selectedDisplay =
        settingsLanguageRegionShownValue(
            snapshot.translationDisplay.selectedKey,
            LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY,
            pick,
            actionState
        )
    val selectedLanguage =
        settingsLanguageRegionShownValue(snapshot.browserLanguage.selectedKey, LANGUAGE_REGION_ACTION_LANGUAGE, pick, actionState)
    SettingsFormSectionCard(iconName = RaftLucideIconName.Languages, title = stringResource(Res.string.settings_language_title)) {
        SettingsSegmentedControlField(
            label = stringResource(Res.string.settings_translation_mode),
            detail = stringResource(Res.string.settings_translation_mode_detail),
            selectedValue = selectedDisplay,
            options = snapshot.translationDisplay.toTranslationModeOptions(),
            onValueSelected = { onPick(SettingsLanguageRegionPick(LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY, it, actionState)) },
            enabled = !busy
        )
        if (!snapshot.translationDisplay.available) {
            SettingsFormDetailText(snapshot.translationDisplay.detail)
        }
        if (selectedDisplay != "original") {
            SettingsComboboxField(
                label = stringResource(Res.string.settings_preferred_translation_language_title),
                description = stringResource(Res.string.settings_preferred_translation_language_detail),
                value = selectedLanguage,
                options = snapshot.browserLanguage.toTranslationTargetOptions(),
                onValueChange = { onPick(SettingsLanguageRegionPick(LANGUAGE_REGION_ACTION_LANGUAGE, it, actionState)) },
                enabled = snapshot.browserLanguage.available && !busy
            )
        }
        if (actionState?.let { it.action in LANGUAGE_REGION_LANGUAGE_ACTIONS && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsDateTimePreferenceCard(
    snapshot: SettingsLanguageRegionSnapshot,
    actionState: SharedSettingsActionState?,
    pick: SettingsLanguageRegionPick?,
    busy: Boolean,
    onPick: (SettingsLanguageRegionPick) -> Unit
) {
    SettingsFormSectionCard(iconName = RaftLucideIconName.Clock, title = stringResource(Res.string.settings_date_time_title)) {
        SettingsComboboxField(
            label = stringResource(Res.string.settings_timezone),
            description = stringResource(Res.string.settings_timezone_detail),
            value = settingsLanguageRegionShownValue(snapshot.timezone.selectedKey, LANGUAGE_REGION_ACTION_TIMEZONE, pick, actionState),
            options = snapshot.timezone.toComboboxOptions(),
            onValueChange = { onPick(SettingsLanguageRegionPick(LANGUAGE_REGION_ACTION_TIMEZONE, it, actionState)) },
            enabled = snapshot.timezone.available && !busy
        )
        SettingsSegmentedControlField(
            label = stringResource(Res.string.settings_time_format),
            detail = stringResource(Res.string.settings_time_format_detail),
            selectedValue = settingsLanguageRegionShownValue(snapshot.timeFormat.selectedKey, LANGUAGE_REGION_ACTION_TIME_FORMAT, pick, actionState),
            options =
                listOf(
                    SegmentedControlOption("12h", stringResource(Res.string.settings_time_format_12_hour), RaftLucideIconName.Clock3),
                    SegmentedControlOption("24h", stringResource(Res.string.settings_time_format_24_hour), RaftLucideIconName.Clock)
                ),
            onValueSelected = { onPick(SettingsLanguageRegionPick(LANGUAGE_REGION_ACTION_TIME_FORMAT, it, actionState)) },
            enabled = snapshot.timeFormat.available && !busy
        )
        if (actionState?.let { it.action in LANGUAGE_REGION_TIME_ACTIONS && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState)
        }
    }
}

private val LANGUAGE_REGION_LANGUAGE_ACTIONS =
    setOf(LANGUAGE_REGION_ACTION_LANGUAGE, LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY, LANGUAGE_REGION_ACTION_AUTO_TRANSLATION)

private val LANGUAGE_REGION_TIME_ACTIONS =
    setOf(LANGUAGE_REGION_ACTION_TIMEZONE, LANGUAGE_REGION_ACTION_TIME_FORMAT)

/**
 * A choice picked on Language & Region, applied at once (raft-ui 0.51: a standalone choice has no Save).
 * [actionStateAtPick] is the store's action state when the pick was made, so a result can be told
 * apart from whatever was showing before, even an identical earlier failure.
 */
internal data class SettingsLanguageRegionPick(
    val action: String,
    val value: String,
    val actionStateAtPick: SharedSettingsActionState?
)

/**
 * True once the store has finished with [pick]: the action state has moved on and is no longer this
 * pick's request in flight. Compared by identity because the store makes a new state for every result.
 */
internal fun settingsLanguageRegionPickSettled(pick: SettingsLanguageRegionPick, actionState: SharedSettingsActionState?): Boolean =
    actionState !== pick.actionStateAtPick &&
        !(actionState?.action == pick.action && actionState.inProgress)

/**
 * What a control shows: the picked value until its request settles, then the saved value. On success
 * the saved value is the pick; on failure the store keeps the old snapshot, so the control reverts.
 */
internal fun settingsLanguageRegionShownValue(
    saved: String,
    action: String,
    pick: SettingsLanguageRegionPick?,
    actionState: SharedSettingsActionState?
): String =
    if (pick != null && pick.action == action && !settingsLanguageRegionPickSettled(pick, actionState)) pick.value else saved

/**
 * Store requests run concurrently and share one action state, so the page takes one pick at a time:
 * every control waits while a Language & Region request is pending.
 */
internal fun settingsLanguageRegionBusy(actionState: SharedSettingsActionState?, pick: SettingsLanguageRegionPick?): Boolean =
    (actionState?.inProgress == true && actionState.action in LANGUAGE_REGION_ACTIONS) ||
        (pick != null && !settingsLanguageRegionPickSettled(pick, actionState))

private val LANGUAGE_REGION_ACTIONS = LANGUAGE_REGION_LANGUAGE_ACTIONS + LANGUAGE_REGION_TIME_ACTIONS

/** The request that applies [pick], or null when it changes nothing. */
internal fun settingsLanguageRegionApplyIntent(
    snapshot: SettingsLanguageRegionSnapshot,
    pick: SettingsLanguageRegionPick
): SharedSettingsIntent? {
    val value = pick.value.takeIf { it.isNotBlank() } ?: return null
    return when (pick.action) {
        LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY ->
            SharedSettingsIntent.SetTranslationMode(value).takeIf { value != snapshot.translationDisplay.selectedKey }
        LANGUAGE_REGION_ACTION_LANGUAGE ->
            SharedSettingsIntent.SetTranslationTargetLanguage(value).takeIf { value != snapshot.browserLanguage.selectedKey }
        LANGUAGE_REGION_ACTION_TIMEZONE ->
            SharedSettingsIntent.UpdateLanguageRegionPreference(SettingsLanguageRegionPreferenceUpdateRequest(preferredTimezone = value))
                .takeIf { value != snapshot.timezone.selectedKey }
        LANGUAGE_REGION_ACTION_TIME_FORMAT ->
            SharedSettingsIntent.UpdateLanguageRegionPreference(SettingsLanguageRegionPreferenceUpdateRequest(preferredTimeFormat = value))
                .takeIf { value != snapshot.timeFormat.selectedKey }
        else -> null
    }
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
