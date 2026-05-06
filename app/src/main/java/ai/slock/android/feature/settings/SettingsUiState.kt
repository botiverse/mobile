package ai.slock.android.feature.settings

import ai.slock.android.core.model.SettingsSummary
import ai.slock.android.core.state.ScreenLoadState

data class SettingsUiState(
    val loadState: ScreenLoadState<SettingsContent>
)

data class SettingsContent(
    val settings: SettingsSummary
)

