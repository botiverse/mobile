package build.raft.app.settings

import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.traceId
import org.jetbrains.compose.resources.StringResource
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.AppLocale
import build.raft.app.data.LANGUAGE_REGION_ACTION_AUTO_TRANSLATION
import build.raft.app.data.MOBILE_ACTION_LANGUAGE
import build.raft.app.data.LANGUAGE_REGION_ACTION_LANGUAGE
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIME_FORMAT
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIMEZONE
import build.raft.app.data.LANGUAGE_REGION_ACTION_TRANSLATION_DEFAULT_VIEW
import build.raft.app.data.LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY
import build.raft.app.data.TranslationDisplayPreference
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SettingsLanguageRegionPreferenceUpdateRequest
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsLanguageRegionSnapshot
import build.raft.app.data.TranslationTargetLanguages
import build.raft.app.hasUserFacingError
import build.raft.app.iconName
import build.raft.app.localizedUserFacingMessage
import build.raft.app.members.iconName
import build.raft.app.data.ASSISTANT_LANGUAGE_REGION_SETTINGS
import build.raft.app.assistant.AssistantSettingChangesSection
import build.raft.app.assistant.LocalAssistantSettingChangesSource
import build.raft.app.assistant.rememberAssistantSettingChanges
import build.raft.app.available
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.designsystem.components.RaftSaveStatusState
import build.raft.designsystem.components.rememberRaftSaveStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIconName
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
    // Web LanguageRegionSection: what the assistant changed of the display language and time zone
    // (not in a public, logged-out context: there is no source there).
    val assistantChangesSource = LocalAssistantSettingChangesSource.current
    val assistantChanges = rememberAssistantSettingChanges(ASSISTANT_LANGUAGE_REGION_SETTINGS, assistantChangesSource)
    LazyColumn(
        state = rememberSettingsSearchListState(),
        // The 16dp content inset stays under a mouse or trackpad; the cards move 16 apart there
        // (12 with a finger: sizing v12, raft-ui 0.65 desktop density).
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).raftSafeAreaHorizontalPadding().padding(horizontal = 16.dp),
        // The top and bottom inset inside the list, so a located section first on the page has room
        // for its highlight (SettingsSearchSectionShape) instead of being cut where the list starts.
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(RaftTheme.controls.settingsCardGap)
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
                        onRetry,
                        traceId = state.error.traceId
                    )
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Lock,
                        stringResource(Res.string.settings_state_permission_required),
                        state.blocker.localizedUserFacingMessage(),
                        null,
                        onRetry,
                        traceId = state.blocker.traceId
                    )
                }
            is ScreenLoadState.Content -> {
                val snapshot = state.data
                val busy = settingsLanguageRegionBusy(actionState, pick)
                (mobileState as? ScreenLoadState.Content)?.data?.let { mobileSnapshot ->
                    item {
                        Box(Modifier.fillMaxWidth().settingsSearchAnchor("display-language", SettingsSearchSectionShape)) {
                            SettingsAppLanguageCard(
                                snapshot = mobileSnapshot,
                                actionState = actionState,
                                onLocaleChange = onLocaleChange
                            )
                        }
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
                if (assistantChanges.isNotEmpty()) {
                    item {
                        AssistantSettingChangesSection(assistantChanges, currentServerId = assistantChangesSource?.serverId)
                    }
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
    val selectedDefaultView =
        settingsLanguageRegionShownValue(
            snapshot.translationDefaultView.selectedKey,
            LANGUAGE_REGION_ACTION_TRANSLATION_DEFAULT_VIEW,
            pick,
            actionState
        )
    val languageFailure = stringResource(Res.string.settings_language_update_failed)
    val displayStatus = rememberSettingsPickSaveStatus(LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY, pick, actionState, languageFailure)
    val languageStatus = rememberSettingsPickSaveStatus(LANGUAGE_REGION_ACTION_LANGUAGE, pick, actionState, languageFailure)
    val defaultViewStatus =
        rememberSettingsPickSaveStatus(LANGUAGE_REGION_ACTION_TRANSLATION_DEFAULT_VIEW, pick, actionState, languageFailure)
    SettingsFormSectionCard(iconName = RaftLucideIconName.Languages, title = stringResource(Res.string.settings_language_title)) {
        SettingsSegmentedControlField(
            modifier = Modifier.settingsSearchAnchor("translation-mode"),
            label = stringResource(Res.string.settings_translation_mode),
            detail = stringResource(Res.string.settings_translation_mode_detail),
            selectedValue = selectedDisplay,
            options = snapshot.translationDisplay.toTranslationModeOptions(),
            onValueSelected = { onPick(SettingsLanguageRegionPick(LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY, it, actionState)) },
            enabled = !busy,
            saveStatus = displayStatus
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
                enabled = snapshot.browserLanguage.available && !busy,
                saveStatus = languageStatus
            )
            SettingsSegmentedControlField(
                modifier = Modifier.settingsSearchAnchor("translation-default-view"),
                label = stringResource(Res.string.settings_translation_default_view),
                selectedValue = selectedDefaultView,
                options = snapshot.translationDefaultView.toTranslationDefaultViewOptions(),
                onValueSelected = {
                    onPick(SettingsLanguageRegionPick(LANGUAGE_REGION_ACTION_TRANSLATION_DEFAULT_VIEW, it, actionState))
                },
                enabled = snapshot.translationDefaultView.available && !busy,
                saveStatus = defaultViewStatus
            )
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
    val dateTimeFailure = stringResource(Res.string.settings_date_time_update_failed)
    val timezoneStatus = rememberSettingsPickSaveStatus(LANGUAGE_REGION_ACTION_TIMEZONE, pick, actionState, dateTimeFailure)
    val timeFormatStatus = rememberSettingsPickSaveStatus(LANGUAGE_REGION_ACTION_TIME_FORMAT, pick, actionState, dateTimeFailure)
    SettingsFormSectionCard(iconName = RaftLucideIconName.Clock, title = stringResource(Res.string.settings_date_time_title)) {
        Box(Modifier.fillMaxWidth().settingsSearchAnchor("timezone")) {
            SettingsComboboxField(
                label = stringResource(Res.string.settings_timezone),
                description = stringResource(Res.string.settings_timezone_detail),
                value = settingsLanguageRegionShownValue(snapshot.timezone.selectedKey, LANGUAGE_REGION_ACTION_TIMEZONE, pick, actionState),
                options = snapshot.timezone.toComboboxOptions(),
                onValueChange = { onPick(SettingsLanguageRegionPick(LANGUAGE_REGION_ACTION_TIMEZONE, it, actionState)) },
                enabled = snapshot.timezone.available && !busy,
                saveStatus = timezoneStatus
            )
        }
        SettingsSegmentedControlField(
            modifier = Modifier.settingsSearchAnchor("time-format"),
            label = stringResource(Res.string.settings_time_format),
            detail = stringResource(Res.string.settings_time_format_detail),
            selectedValue = settingsLanguageRegionShownValue(snapshot.timeFormat.selectedKey, LANGUAGE_REGION_ACTION_TIME_FORMAT, pick, actionState),
            options =
                listOf(
                    SegmentedControlOption("12h", stringResource(Res.string.settings_time_format_12_hour), RaftLucideIconName.Clock3),
                    SegmentedControlOption("24h", stringResource(Res.string.settings_time_format_24_hour), RaftLucideIconName.Clock)
                ),
            onValueSelected = { onPick(SettingsLanguageRegionPick(LANGUAGE_REGION_ACTION_TIME_FORMAT, it, actionState)) },
            enabled = snapshot.timeFormat.available && !busy,
            saveStatus = timeFormatStatus
        )
    }
}

private val LANGUAGE_REGION_LANGUAGE_ACTIONS =
    setOf(
        LANGUAGE_REGION_ACTION_LANGUAGE,
        LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY,
        LANGUAGE_REGION_ACTION_TRANSLATION_DEFAULT_VIEW,
        LANGUAGE_REGION_ACTION_AUTO_TRANSLATION
    )

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

/** What a pick's request came to, for its control's SaveStatus line. */
internal sealed interface SettingsPickSaveResult {
    /** Still on its way: the line stays empty (the control already shows the pick). */
    data object Pending : SettingsPickSaveResult

    data object Saved : SettingsPickSaveResult

    /** The control is back on the stored value; [serverMessage] is the server's own refusal, if it sent one. */
    data class Failed(val serverMessage: String?) : SettingsPickSaveResult

    /** Settled without a result of its own (the action state was cleared or replaced): say nothing. */
    data object Unknown : SettingsPickSaveResult
}

/**
 * The result of [pick] for its SaveStatus (web `useImmediateSave`, slock #11858/#12162): saved when
 * the store finished its action cleanly, failed when it finished with an error. The control has
 * already gone back to the stored value then ([settingsLanguageRegionShownValue]).
 */
internal fun settingsPickSaveResult(pick: SettingsLanguageRegionPick, actionState: SharedSettingsActionState?): SettingsPickSaveResult =
    when {
        !settingsLanguageRegionPickSettled(pick, actionState) -> SettingsPickSaveResult.Pending
        actionState == null || actionState.action != pick.action -> SettingsPickSaveResult.Unknown
        actionState.hasUserFacingError() ->
            SettingsPickSaveResult.Failed(actionState.screenError?.serverMessage?.takeIf { it.isNotBlank() })
        else -> SettingsPickSaveResult.Saved
    }

/**
 * The SaveStatus line of the control for [action]: empty while [pick] for it is on its way and once
 * it is stored (raft-ui 0.84: the control showing the pick is the confirmation), or what failed (the
 * server's refusal, else [failureMessage], as web `apiErrorText`) until the next pick. Each pick
 * reports once.
 */
@Composable
internal fun rememberSettingsPickSaveStatus(
    action: String,
    pick: SettingsLanguageRegionPick?,
    actionState: SharedSettingsActionState?,
    failureMessage: String
): RaftSaveStatusState {
    val status = rememberRaftSaveStatus()
    // By identity: a new pick of the same value is a new save.
    var reported by remember { mutableStateOf<SettingsLanguageRegionPick?>(null, referentialEqualityPolicy()) }
    val current = pick?.takeIf { it.action == action }
    SideEffect {
        if (current == null || current === reported) return@SideEffect
        when (val result = settingsPickSaveResult(current, actionState)) {
            SettingsPickSaveResult.Pending -> status.saving()
            SettingsPickSaveResult.Saved -> {
                reported = current
                status.saved()
            }
            is SettingsPickSaveResult.Failed -> {
                reported = current
                status.failed(result.serverMessage ?: failureMessage)
            }
            SettingsPickSaveResult.Unknown -> {
                reported = current
                status.saving()
            }
        }
    }
    return status.state
}

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
        LANGUAGE_REGION_ACTION_TRANSLATION_DEFAULT_VIEW ->
            SharedSettingsIntent.UpdateLanguageRegionPreference(
                SettingsLanguageRegionPreferenceUpdateRequest(preferredTranslationDisplay = value)
            ).takeIf { value != snapshot.translationDefaultView.selectedKey }
        LANGUAGE_REGION_ACTION_TIMEZONE ->
            SharedSettingsIntent.UpdateLanguageRegionPreference(SettingsLanguageRegionPreferenceUpdateRequest(preferredTimezone = value))
                .takeIf { value != snapshot.timezone.selectedKey }
        LANGUAGE_REGION_ACTION_TIME_FORMAT ->
            SharedSettingsIntent.UpdateLanguageRegionPreference(SettingsLanguageRegionPreferenceUpdateRequest(preferredTimeFormat = value))
                .takeIf { value != snapshot.timeFormat.selectedKey }
        else -> null
    }
}

@Composable
private fun SettingsMobileSelectionSnapshot.toTranslationModeOptions(): List<SegmentedControlOption> =
    options.map { option ->
        SegmentedControlOption(
            value = option.key,
            // Web `settings.language.modeAuto` / `modeManual` / `modeOff`; the store's label is its id.
            label = settingsTranslationModeLabelRes(option.key)?.let { stringResource(it) } ?: option.label,
            iconName =
                when (option.key) {
                    "automatic" -> RaftLucideIconName.Languages
                    "manual" -> RaftLucideIconName.Pencil
                    else -> RaftLucideIconName.FileText
                }
        )
    }

@Composable
private fun SettingsMobileSelectionSnapshot.toTranslationDefaultViewOptions(): List<SegmentedControlOption> =
    options.map { option ->
        SegmentedControlOption(
            value = option.key,
            label =
                when (option.key) {
                    TranslationDisplayPreference.Translated.wireValue ->
                        stringResource(Res.string.settings_translation_display_translated)
                    TranslationDisplayPreference.Original.wireValue ->
                        stringResource(Res.string.settings_translation_display_original)
                    TranslationDisplayPreference.Bilingual.wireValue ->
                        stringResource(Res.string.settings_translation_display_bilingual)
                    else -> option.label
                }
        )
    }

private fun SettingsMobileSelectionSnapshot.toComboboxOptions(): List<RaftComboboxOption<String>> {
    val listed = options.map { RaftComboboxOption(it.key, it.label) }
    // A saved value the list does not offer still shows under its own label.
    return if (selectedKey.isBlank() || options.any { it.key == selectedKey }) listed else listed + RaftComboboxOption(selectedKey, selectedLabel.ifBlank { selectedKey })
}

/**
 * Each translation target named in the UI language (web `getTranslationLanguageOptions`, which
 * reads CLDR through `Intl.DisplayNames`): the store keeps the English name only as an id.
 */
@Composable
private fun SettingsMobileSelectionSnapshot.toTranslationTargetOptions(): List<RaftComboboxOption<String>> {
    val systemLanguageLabel = stringResource(Res.string.settings_translation_target_follow_display_language)
    return toComboboxOptions().map { option ->
        when {
            option.value == TranslationTargetLanguages.System -> option.copy(label = systemLanguageLabel)
            else -> settingsTranslationTargetLanguageRes(option.value)?.let { option.copy(label = stringResource(it)) } ?: option
        }
    }
}

/** The translation mode's word in this locale, by its storage key; null for a key Raft doesn't know. */
internal fun settingsTranslationModeLabelRes(key: String): StringResource? =
    when (key) {
        "automatic" -> Res.string.settings_translation_mode_auto
        "manual" -> Res.string.settings_translation_mode_manual
        "original" -> Res.string.settings_translation_mode_off
        else -> null
    }

/** A translation target's name in this locale, by its code; null for a code Raft doesn't offer. */
internal fun settingsTranslationTargetLanguageRes(code: String): StringResource? =
    when (code) {
        "en" -> Res.string.settings_translation_target_language_en
        "zh-cn" -> Res.string.settings_translation_target_language_zh_cn
        "zh-tw" -> Res.string.settings_translation_target_language_zh_tw
        "ja" -> Res.string.settings_translation_target_language_ja
        "ko" -> Res.string.settings_translation_target_language_ko
        "es" -> Res.string.settings_translation_target_language_es
        "fr" -> Res.string.settings_translation_target_language_fr
        "de" -> Res.string.settings_translation_target_language_de
        "pt-br" -> Res.string.settings_translation_target_language_pt_br
        "it" -> Res.string.settings_translation_target_language_it
        "ru" -> Res.string.settings_translation_target_language_ru
        else -> null
    }

/** The app's display language (Settings > Language & region), applied when picked. */
@Composable
internal fun SettingsAppLanguageCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onLocaleChange: (String) -> Unit
) {
    val preference = snapshot.localePreference
    val busy = actionState?.action == MOBILE_ACTION_LANGUAGE && actionState.inProgress
    // The app language applies when picked (web display language, slock #11858): its SaveStatus
    // says only what failed, while the control stays on the stored language (raft-ui 0.84).
    val selectedLocale = preference.selectedKey.ifBlank { AppLocale.System.languageTag }
    var pick by remember { mutableStateOf<SettingsLanguageRegionPick?>(null, referentialEqualityPolicy()) }
    val saveStatus =
        rememberSettingsPickSaveStatus(MOBILE_ACTION_LANGUAGE, pick, actionState, stringResource(Res.string.settings_language_update_failed))
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.Languages,
        title = stringResource(Res.string.settings_app_language_title)
    ) {
        // The web's display-language select (botiverse/slock DISPLAY_LOCALES): every language by its
        // own name, in the web's order, after the app-only System choice.
        val systemLabel = stringResource(Res.string.settings_app_language_system)
        SettingsComboboxField(
            label = null,
            description = stringResource(Res.string.settings_app_language_detail),
            value = selectedLocale,
            options =
                AppLocale.entries.map { locale ->
                    RaftComboboxOption(
                        locale.languageTag,
                        if (locale == AppLocale.System) systemLabel else locale.nativeLabel
                    )
                },
            onValueChange = { selected ->
                selected.takeIf { it != selectedLocale }?.let {
                    pick = SettingsLanguageRegionPick(MOBILE_ACTION_LANGUAGE, it, actionState)
                    onLocaleChange(it)
                }
            },
            enabled = preference.available && !busy,
            saveStatus = saveStatus
        )
    }
}
