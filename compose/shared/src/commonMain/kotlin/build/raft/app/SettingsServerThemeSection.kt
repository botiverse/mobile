package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftProBadge
import build.raft.app.components.RaftProUpgradeDialog
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.ServerAppearanceRepository
import build.raft.app.resources.*
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftThemeId
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.StringResource

/** What the Appearance page needs to draw and drive "Theme on {server}". */
internal class SettingsServerThemeBinding(
    val state: SettingsServerThemeState,
    val onPick: (RaftThemeId) -> Unit,
    val onDecorChange: (Boolean) -> Unit,
    /** The Pro theme upgrade prompt is open. */
    val upgradeOpen: Boolean = false,
    val onDismissUpgrade: () -> Unit = {},
    /** The prompt's Upgrade: opens Settings > Billing. */
    val onOpenBilling: () -> Unit = {},
)

/**
 * The selected server's theme picker for the Appearance page: reads the stored session through
 * [authStore] (its auth events wake it), saves through [repository], toasts a failed save with
 * [onToast] and offers [onOpenBilling] when a Free member picks a Pro theme.
 */
@Composable
internal fun rememberSettingsServerThemeBinding(
    repository: ServerAppearanceRepository,
    authStore: RaftAuthSessionStore,
    onToast: (String) -> Unit,
    onOpenBilling: () -> Unit,
): SettingsServerThemeBinding {
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(Res.string.settings_server_theme_save_failed)
    val currentToast by rememberUpdatedState(onToast)
    val currentSaveFailed by rememberUpdatedState(saveFailed)
    var upgradeOpen by remember { mutableStateOf(false) }
    val store =
        remember(repository, authStore, scope) {
            SettingsServerThemeStore(
                repository = repository,
                sessionStore = authStore,
                scope = scope,
                onSaveFailed = { currentToast(currentSaveFailed) },
                // TODO(upgrade dialog): the app has no unified upgrade dialog yet. When it lands,
                //  open it here with the web's `pro_theme` gate (UpgradeDialog.tsx) in place of
                //  the RaftProUpgradeDialog this flag shows.
                onUpgradeRequired = { upgradeOpen = true },
            )
        }
    LaunchedEffect(store, authStore) {
        store.start((authStore as? RaftAuthSessionEventSource)?.authSessionEvents ?: emptyFlow())
    }
    val state by store.state.collectAsState()
    return SettingsServerThemeBinding(
        state = state,
        onPick = store::pickTheme,
        onDecorChange = store::setDecor,
        upgradeOpen = upgradeOpen,
        onDismissUpgrade = { upgradeOpen = false },
        onOpenBilling = {
            upgradeOpen = false
            onOpenBilling()
        },
    )
}

/**
 * Settings > Appearance > "Theme on {server}", web ServerThemeSection: the description, the
 * Basics and Avatar series swatches (each a miniature of the app in that theme), then the
 * frame's pixel art. Hidden without a selected server.
 */
@Composable
internal fun SettingsServerThemeCard(binding: SettingsServerThemeBinding) {
    val state = binding.state
    if (!state.visible) return
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.Sparkles,
        title = stringResource(Res.string.settings_server_theme_title, state.serverName),
    ) {
        SettingsFormDetailText(stringResource(Res.string.settings_server_theme_description, state.serverName))
        RaftDesignSystemTheme {
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsServerThemeGroup(Res.string.settings_server_theme_group_basics, SettingsServerThemeBasics, state, binding.onPick)
                SettingsServerThemeGroup(Res.string.settings_server_theme_group_avatars, SettingsServerThemeAvatars, state, binding.onPick)
            }
        }
        SettingsFormDivider()
        SettingsNotificationToggleRow(
            title = stringResource(Res.string.settings_server_theme_decor_title),
            detail =
                if (state.decorAvailable) {
                    stringResource(Res.string.settings_server_theme_decor_description) + "\n" +
                        stringResource(Res.string.settings_server_theme_decor_wide_screens)
                } else {
                    stringResource(Res.string.settings_server_theme_decor_unavailable)
                },
            checked = state.decorChecked,
            enabled = state.decorAvailable,
            onCheckedChange = binding.onDecorChange,
        )
    }
    if (binding.upgradeOpen) {
        RaftDesignSystemTheme {
            RaftProUpgradeDialog(
                title = stringResource(Res.string.settings_server_theme_pro_title),
                description = stringResource(Res.string.settings_server_theme_pro_description, state.serverName),
                onUpgrade = binding.onOpenBilling,
                onDismiss = binding.onDismissUpgrade,
            )
        }
    }
}

@Composable
private fun SettingsServerThemeGroup(
    title: StringResource,
    themes: List<RaftThemeId>,
    state: SettingsServerThemeState,
    onPick: (RaftThemeId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftText(
            text = stringResource(title),
            style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
        )
        // Web: two columns, three from 32rem, four from 48rem.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns =
                when {
                    maxWidth >= 768.dp -> 4
                    maxWidth >= 512.dp -> 3
                    else -> 2
                }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                themes.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { theme ->
                            SettingsServerThemeSwatch(
                                theme = theme,
                                selected = state.look.theme == theme,
                                onClick = { onPick(theme) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * One theme to choose, web `ThemeOptionCard`: a radio card (checked draws a 1.5dp strong edge
 * and a check) over a miniature of the app in that theme, its name, "Default" by Raft Yellow and
 * the Pro tag by a Pro theme.
 */
@Composable
private fun SettingsServerThemeSwatch(
    theme: RaftThemeId,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(10.dp)
    val name = stringResource(theme.settingsDisplayName())
    val description =
        when {
            theme.isPro -> stringResource(Res.string.settings_server_theme_option_pro, name)
            theme == RaftThemeId.Raft -> stringResource(Res.string.settings_server_theme_option_default, name)
            else -> name
        }
    Column(
        modifier =
            modifier
                .clip(shape)
                .background(colors.layerCard)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.lineStrong else colors.line, shape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsServerThemeMiniature(theme)
        Row(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The name and its label share the row before the check; the name is the only weighted
            // child here, so it gets every bit of width the label leaves (a second weighted spacer
            // would take half and cut "Raft Yellow" short).
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    text = name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (theme == RaftThemeId.Raft) {
                    RaftText(
                        text = stringResource(Res.string.settings_server_theme_default),
                        style = RaftTheme.typography.footnote,
                        color = colors.foregroundHint,
                        maxLines = 1,
                    )
                }
                if (theme.isPro) RaftProBadge()
            }
            if (selected) {
                RaftLucideIcon(RaftLucideIconName.Check, Modifier.size(16.dp), color = raftArgbHex(colors.foreground))
            }
        }
    }
}

/**
 * The app in miniature wearing [theme] in the current mode, web `ThemeMiniature`: the frame
 * (top bar and rail), the sidebar with a brand sticker, and the canvas.
 */
@Composable
private fun SettingsServerThemeMiniature(theme: RaftThemeId) {
    val dark = RaftTheme.colors is RaftUiThemeColors.Dark
    val themed = RaftUiThemeColors.of(theme, dark)
    val bar = RoundedCornerShape(2.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, themed.lineSeam, RoundedCornerShape(8.dp))
                .background(themed.layerFrame)
                .clearAndSetSemantics {},
    ) {
        Spacer(Modifier.fillMaxWidth().height(12.dp).background(themed.layerFrame))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Spacer(Modifier.width(12.dp).fillMaxHeight().background(themed.layerFrame))
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(themed.layerSidebar)
                        .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.fillMaxWidth(0.75f).height(6.dp).background(themed.foreground.copy(alpha = 0.3f), bar))
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(themed.brand)
                        .border(1.dp, themed.stickerEdge, RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.fillMaxWidth(0.66f).height(6.dp).background(themed.foreground.copy(alpha = 0.15f), bar))
            }
            Column(
                modifier =
                    Modifier
                        .weight(2f)
                        .fillMaxHeight()
                        .background(themed.layerCanvas)
                        .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Spacer(Modifier.fillMaxWidth(0.5f).height(6.dp).background(themed.foreground.copy(alpha = 0.4f), bar))
                Spacer(Modifier.fillMaxWidth(0.75f).height(6.dp).background(themed.foreground.copy(alpha = 0.15f), bar))
                Spacer(Modifier.fillMaxWidth(0.66f).height(6.dp).background(themed.foreground.copy(alpha = 0.15f), bar))
            }
        }
    }
}

/** Web `RAFT_THEME_NAME_ID`: a theme's name in the picker. */
internal fun RaftThemeId.settingsDisplayName(): StringResource =
    when (this) {
        RaftThemeId.Raft -> Res.string.settings_server_theme_name_raft
        RaftThemeId.Boring -> Res.string.settings_server_theme_name_boring
        RaftThemeId.Bots -> Res.string.settings_server_theme_name_bots
        RaftThemeId.Critters -> Res.string.settings_server_theme_name_critters
        RaftThemeId.Space -> Res.string.settings_server_theme_name_space
        RaftThemeId.Desk -> Res.string.settings_server_theme_name_desk
        RaftThemeId.Food -> Res.string.settings_server_theme_name_food
        RaftThemeId.Quest -> Res.string.settings_server_theme_name_quest
        RaftThemeId.Weather -> Res.string.settings_server_theme_name_weather
        RaftThemeId.Plants -> Res.string.settings_server_theme_name_plants
        RaftThemeId.Spooky -> Res.string.settings_server_theme_name_spooky
    }
