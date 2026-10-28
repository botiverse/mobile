package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
 * route), only light and dark, which belongs to the device rather than to a server.
 */
internal fun settingsThemePageSections(hasServer: Boolean): List<SettingsThemePageSection> =
    if (hasServer) SettingsThemePageSections else listOf(SettingsThemePageSection.Mode)

/** Settings > Theme. [serverTheme] null (fixtures without a store) shows light and dark only. */
@Composable
internal fun SettingsThemeContent(
    serverTheme: SettingsServerThemeBinding?,
    bottomContentPadding: Dp,
) {
    val hasServer = serverTheme?.state?.visible == true
    val sections = settingsThemePageSections(hasServer)
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).raftSafeAreaHorizontalPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (serverTheme != null && hasServer) {
            item {
                SettingsFormDetailText(stringResource(Res.string.settings_server_theme_description, serverTheme.state.serverName))
            }
        }
        if (SettingsThemePageSection.Mode in sections) {
            item { SettingsAppearanceThemeCard(title = stringResource(Res.string.settings_theme_group_mode)) }
        }
        val pickerSections = sections.filter { it != SettingsThemePageSection.Mode }
        if (serverTheme != null && pickerSections.isNotEmpty()) {
            item { SettingsServerThemePickerCard(serverTheme, pickerSections) }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}
