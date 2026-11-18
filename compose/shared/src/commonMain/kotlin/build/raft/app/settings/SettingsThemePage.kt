package build.raft.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.resources.*
import build.raft.app.assistant.AssistantSettingChangesSection
import build.raft.app.assistant.LocalAssistantSettingChangesSource
import build.raft.app.assistant.rememberAssistantSettingChanges
import build.raft.app.data.ASSISTANT_THEME_SETTINGS
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding

/*
 * Settings > Theme: the member's own look on the current server, and light or dark for this
 * device. Web ServerThemeSection after "choose light or dark in Settings › Theme"
 * (botiverse/slock#11355, cbafa67c, packages/web/src/components/settings/ServerThemeSection.tsx:162-205):
 * the description, "Light and dark", Basics, Avatar series, then the frame's pixel art.
 */

/** What Settings > Theme shows, web ServerThemeSection's sections. */
internal enum class SettingsThemePageSection {
    /** "Light and dark": Light, Dark or System, saved on this device for every server. */
    Mode,
    Basics,
    Avatars,
    /** The frame's pixel art switch. */
    Decor,
}

/** Top to bottom, as on the web: light and dark lead, by the themes. */
internal val SettingsThemePageSections: List<SettingsThemePageSection> =
    listOf(
        SettingsThemePageSection.Mode,
        SettingsThemePageSection.Basics,
        SettingsThemePageSection.Avatars,
        SettingsThemePageSection.Decor,
    )

/**
 * The sections Settings > Theme shows: all of them with a current server; without one (a stale
 * route), only light and dark, which belongs to the device rather than to a server. The frame's
 * pixel art shows on wide screens only, so a device that can't have one ([largeScreen] false, an
 * ordinary phone) has no switch for it (settings cleanup, 1.27.0).
 */
internal fun settingsThemePageSections(
    hasServer: Boolean,
    largeScreen: Boolean = raftPlatformLargeScreenPossible(),
): List<SettingsThemePageSection> =
    when {
        !hasServer -> listOf(SettingsThemePageSection.Mode)
        largeScreen -> SettingsThemePageSections
        else -> SettingsThemePageSections - SettingsThemePageSection.Decor
    }

/** Settings > Theme. [serverTheme] null (fixtures without a store) shows light and dark only. */
@Composable
internal fun SettingsThemeContent(
    serverTheme: SettingsServerThemeBinding?,
    bottomContentPadding: Dp,
) {
    val hasServer = serverTheme?.state?.visible == true
    val sections = settingsThemePageSections(hasServer)
    // Web ServerThemeSection: what the assistant changed of this server's theme and the sidebar.
    val assistantChangesSource = LocalAssistantSettingChangesSource.current
    val assistantChanges = rememberAssistantSettingChanges(ASSISTANT_THEME_SETTINGS, assistantChangesSource)
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
        if (serverTheme != null && hasServer) {
            item {
                SettingsFormDetailText(stringResource(Res.string.settings_server_theme_description, serverTheme.state.serverName))
            }
        }
        if (SettingsThemePageSection.Mode in sections) {
            item {
                Box(Modifier.fillMaxWidth().settingsSearchAnchor("theme", SettingsSearchSectionShape)) {
                    SettingsAppearanceThemeCard(title = stringResource(Res.string.settings_theme_group_mode))
                }
            }
        }
        val pickerSections = sections.filter { it != SettingsThemePageSection.Mode }
        if (serverTheme != null && pickerSections.isNotEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().settingsSearchAnchor("server-theme", SettingsSearchCardShape)) {
                    SettingsServerThemePickerCard(serverTheme, pickerSections)
                }
            }
        }
        if (hasServer && assistantChanges.isNotEmpty()) {
            item {
                AssistantSettingChangesSection(assistantChanges, currentServerId = assistantChangesSource?.serverId)
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}
