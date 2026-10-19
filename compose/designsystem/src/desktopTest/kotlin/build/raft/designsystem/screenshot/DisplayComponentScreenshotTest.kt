package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import build.raft.designsystem.components.RaftAgentStateChip
import build.raft.designsystem.components.RaftAgentTag
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarGroup
import build.raft.designsystem.components.RaftAvatarGroupCount
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardFooterText
import build.raft.designsystem.components.RaftCardSection
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftIconTileVariant
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowValue
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftMentionMarker
import build.raft.designsystem.components.RaftMutedCount
import build.raft.designsystem.components.RaftPanelNotice
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftRadio
import build.raft.designsystem.components.RaftReadOnlyField
import build.raft.designsystem.components.RaftRowTone
import build.raft.designsystem.components.RaftSection
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.RaftStickerTile
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.components.raftActiveStickerLook
import build.raft.designsystem.components.raftInsetFocusOutline
import build.raft.designsystem.components.raftPressOverlay
import build.raft.designsystem.components.raftPressOverlayColor
import build.raft.designsystem.components.RaftPressSurface
import build.raft.designsystem.components.raftSticker
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.test.Test

/**
 * PR 2 display components: one golden per component x appearance holding its state
 * matrix (see [ComponentScreenshotTest] for the conventions). Pressed and focused cells
 * are forced through [LocalRaftPressedPreview] / [LocalRaftFocusedPreview]; spinners are
 * frozen on their first step.
 *
 * Visual-testing case ids (mobile DRI hand-off): [avatarListRow] reproduces
 * `components.ui.avatar-list-row.states`; [listRow], [card], [descriptionList],
 * [badgeTags], [sectionAndCode] and [banner] hold the parts of
 * `screens.members.agent-detail.*`, `components.members.agent-detail.profile` and
 * `components.settings.root.page`.
 */
class DisplayComponentScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance) {
                CompositionLocalProvider(LocalRaftSpinnerStillStep provides 0) { content() }
            }
        }

    /** The shared state primitives on a bare 36dp item, and the inset outline on rows. */
    @Test
    fun states() =
        capture("states") {
            val colors = RaftTheme.colors
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Caption("Active sticker: rest, pressed, focused; active, pressed, focused")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(false, true).forEach { active ->
                        StateCell { StickerItem("Overview", active) }
                        StateCell(pressed = true) { StickerItem("Overview", active) }
                        StateCell(focused = true) { StickerItem("Overview", active) }
                    }
                }
                Caption("Press overlays: paper, sticker, frame")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OverlaySwatch(colors.layerCard, RaftPressSurface.Paper)
                    OverlaySwatch(RaftUiPalette.attention400, RaftPressSurface.Sticker)
                    OverlaySwatch(colors.layerFrame, RaftPressSurface.Frame)
                }
                Caption("Inset focus outline on a row touching its container's edge")
                Column(
                    Modifier
                        .width(280.dp)
                        .clip(RoundedCornerShape(RaftRadius.Container))
                        .background(colors.layerPopover, RoundedCornerShape(RaftRadius.Container))
                        .border(RaftStroke.Hairline, colors.lineSeam, RoundedCornerShape(RaftRadius.Container)),
                ) {
                    Row(Modifier.fillMaxWidth().height(40.dp).raftInsetFocusOutline(true, colors.foreground).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftText("Search channels", color = colors.foregroundPlaceholder)
                    }
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftText("general")
                    }
                }
            }
        }

    @Test
    fun button() =
        capture("button") {
            val states = listOf("Rest", "Pressed", "Focused", "Disabled", "Busy")
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Caption("", Modifier.width(72.dp))
                    states.forEach { Caption(it, Modifier.width(132.dp)) }
                }
                RaftButtonVariant.entries.forEach { variant ->
                    val onFrame = variant == RaftButtonVariant.OnFrame
                    Row(
                        Modifier.then(if (onFrame) Modifier.background(RaftTheme.colors.layerFrame).padding(vertical = 6.dp) else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Caption(variant.name, Modifier.width(72.dp))
                        states.forEach { state ->
                            Box(Modifier.width(132.dp)) {
                                StateCell(pressed = state == "Pressed", focused = state == "Focused") {
                                    RaftButton(
                                        text = buttonLabel(variant),
                                        onClick = {},
                                        variant = variant,
                                        enabled = state != "Disabled",
                                        loading = state == "Busy",
                                        loadingLabel = "Saving…",
                                    )
                                }
                            }
                        }
                    }
                }
                Caption("Sizes: md 32, lg 44, xl 52; icons before and after the label")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftButton("Create Channel", onClick = {}, variant = RaftButtonVariant.Primary, leadingIcon = { Glyph(RaftGlyph.Plus) })
                    RaftButton("Send for Review", onClick = {}, size = RaftButtonSize.Lg, trailingIcon = { Glyph(RaftGlyph.ChevronRight) })
                    RaftButton("Continue", onClick = {}, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Xl, modifier = Modifier.width(240.dp))
                }
            }
        }

    @Test
    fun iconButton() =
        capture("icon_button") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Md (32, 36 on the frame) and Lg (44): rest, pressed, focused, disabled")
                RaftButtonVariant.entries.forEach { variant ->
                    val onFrame = variant == RaftButtonVariant.OnFrame
                    Row(
                        Modifier.then(if (onFrame) Modifier.background(RaftTheme.colors.layerFrame) else Modifier).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Caption(variant.name, Modifier.width(72.dp))
                        RaftIconButtonSize.entries.forEach { size ->
                            listOf("Rest", "Pressed", "Focused", "Disabled").forEach { state ->
                                StateCell(pressed = state == "Pressed", focused = state == "Focused") {
                                    RaftIconButton(
                                        onClick = {},
                                        contentDescription = "Add Reaction",
                                        variant = variant,
                                        size = size,
                                        enabled = state != "Disabled",
                                    ) { Glyph(RaftGlyph.Plus, if (size == RaftIconButtonSize.Lg) 22.dp else 18.dp) }
                                }
                            }
                            Box(Modifier.width(12.dp))
                        }
                    }
                }
            }
        }

    @Test
    fun card() =
        capture("card") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Caption("Default: header, content, footer")
                    RaftCard(
                        title = "Release readiness",
                        description = "Three checks left before Friday.",
                        trailing = { RaftBadge("In Review", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information) },
                        footer = { RaftCardFooterText("Updated by Wug 2 hours ago") },
                    ) {
                        RaftText("Changelog drafted and the build is green on every platform.")
                    }
                    Caption("Default: header only, leading icon")
                    RaftCard(
                        title = "Notifications",
                        description = "Mentions and direct messages.",
                        leading = { Glyph(RaftGlyph.AtSign) },
                    )
                    Caption("Inset: header and sections")
                    RaftCard(
                        variant = RaftCardVariant.Inset,
                        title = "Auto-archive",
                        description = "Archive channels with no messages for 30 days.",
                        trailing = { RaftSwitch(checked = true, onCheckedChange = {}) },
                    ) {
                        RaftCardSection { RaftText("Keep pinned channels") }
                        RaftCardSection { RaftText("Notify the owner first") }
                    }
                }
                Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("Option: unchecked, checked, pressed, focused")
                    RaftCard(
                        variant = RaftCardVariant.Option,
                        title = "This computer",
                        description = "The agent runs here while the app is open.",
                        leading = { RaftRadio(selected = false, onClick = null) },
                        onClick = {},
                    )
                    RaftCard(
                        variant = RaftCardVariant.Option,
                        title = "A cloud computer",
                        description = "Always on. Uses your plan's hours.",
                        leading = { RaftRadio(selected = true, onClick = null) },
                        onClick = {},
                        selected = true,
                    )
                    StateCell(pressed = true) {
                        RaftCard(
                            variant = RaftCardVariant.Option,
                            title = "Pressed",
                            description = "The pressed overlay over the card.",
                            leading = { RaftRadio(selected = false, onClick = null) },
                            onClick = {},
                        )
                    }
                    StateCell(focused = true) {
                        RaftCard(
                            variant = RaftCardVariant.Option,
                            title = "Focused",
                            description = "The focus ring outside the card.",
                            leading = { RaftRadio(selected = false, onClick = null) },
                            onClick = {},
                        )
                    }
                }
            }
        }

    @Test
    fun badgeTags() =
        capture("badge_tags") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Caption("Unread", Modifier.width(96.dp))
                    RaftUnreadCount(3)
                    RaftUnreadCount(12)
                    RaftUnreadCount(140)
                    Caption("Mention")
                    RaftMentionMarker()
                    Caption("Muted")
                    RaftMutedCount(8)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Caption("Agent", Modifier.width(96.dp))
                    RaftText("Wug", style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold))
                    RaftAgentTag()
                    RaftBadge("Claude Code", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Caption("State chips", Modifier.width(96.dp))
                    RaftPresence.entries.forEach { RaftAgentStateChip(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Caption("Roles", Modifier.width(96.dp))
                    RaftBadge("Owner", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Primary)
                    RaftBadge("Admin", appearance = RaftBadgeAppearance.Soft)
                    RaftBadge("Online", variant = RaftBadgeVariant.Success)
                    RaftBadge("API_KEY", appearance = RaftBadgeAppearance.Outline)
                }
            }
        }

    @Test
    fun avatar() =
        capture("avatar") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                    RaftAvatarSize.entries.forEach { size ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            RaftAvatar(RaftAvatarType.Human, size, initials = if (size.maxInitials == 2) "AL" else "A", presence = RaftPresence.Online)
                            RaftAvatar(RaftAvatarType.Agent, size, presence = RaftPresence.Busy)
                            RaftAvatar(RaftAvatarType.Human, size)
                            Caption(size.name)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Caption("Presence")
                    RaftPresence.entries.forEach { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Md, presence = it) }
                    Caption("Stack")
                    RaftAvatarGroup {
                        RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Sm, initials = "A")
                        RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Sm)
                        RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Sm, initials = "B")
                        RaftAvatarGroupCount("+3", RaftAvatarSize.Sm)
                    }
                }
            }
        }

    @Test
    fun statusDot() =
        capture("status_dot") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RaftStatusSize.entries.forEach { size ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Caption(size.name, Modifier.width(28.dp))
                        RaftStatusVariant.entries.forEach { RaftStatusDot(variant = it, size = size) }
                        Box(Modifier.width(12.dp))
                        RaftPresence.entries.forEach { RaftStatusDot(presence = it, size = size) }
                    }
                }
                RaftPresence.entries.forEach { presence ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftStatusDot(presence = presence)
                        RaftText(presence.word, style = RaftTheme.typography.footnote)
                    }
                }
            }
        }

    @Test
    fun skeleton() =
        capture("skeleton") {
            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftSkeleton(Modifier.width(120.dp), variant = RaftSkeletonVariant.Line, animated = false)
                    RaftSkeleton(Modifier.size(64.dp, 40.dp), variant = RaftSkeletonVariant.Block, animated = false)
                    RaftSkeleton(Modifier.size(35.dp), variant = RaftSkeletonVariant.Circle, animated = false)
                }
                RaftListRowGroup {
                    repeat(3) { index ->
                        RaftListRowSkeleton(divider = index > 0)
                    }
                }
            }
        }

    @Composable
    private fun RaftListRowSkeleton(divider: Boolean) {
        if (divider) Box(Modifier.fillMaxWidth().padding(start = 63.dp).height(1.dp).background(RaftTheme.colors.lineHairline))
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RaftSkeleton(Modifier.size(35.dp), variant = RaftSkeletonVariant.Circle, animated = false)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftSkeleton(Modifier.width(140.dp), variant = RaftSkeletonVariant.Line, animated = false)
                RaftSkeleton(Modifier.width(200.dp), variant = RaftSkeletonVariant.Line, animated = false)
            }
        }
    }

    @Test
    fun banner() =
        capture("banner") {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RaftBanner(
                    title = "This channel is shared with Acme",
                    description = "People and agents from Acme will see what you post here.",
                    status = RaftBannerStatus.Info,
                    action = { RaftButton("See Who", onClick = {}) },
                )
                RaftBanner(
                    title = "The agent stopped",
                    description = "Wug lost its connection to the computer. Restarting keeps its memory and its open tasks; resetting clears the session, the scratch files and every setting changed since the agent was created.",
                    status = RaftBannerStatus.Warning,
                )
                RaftBanner(description = "Deleting Wug removes its messages from every channel.", status = RaftBannerStatus.Destructive)
                RaftBanner(description = "Your changes are saved.", status = RaftBannerStatus.Success)
                RaftBanner(description = "Reports include the last 200 lines of the log.", status = RaftBannerStatus.Info, size = RaftBannerSize.Sm)
                RaftBanner(description = "You have used 80% of this month's hours.", status = RaftBannerStatus.Default)
                Caption("Panel notice")
                RaftPanelNotice(
                    "Wug couldn't start: the runtime is missing.",
                    actions = { RaftButton("View Logs", onClick = {}, variant = RaftButtonVariant.Ghost) },
                )
            }
        }

    /** `components.ui.avatar-list-row.states`: 342 wide, 16 padding on `layer-panel`, a 310dp card. */
    @Test
    fun avatarListRow() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("avatar_list_row_states_${appearance.name.lowercase()}", appearance) {
                Box(Modifier.width(342.dp).background(RaftTheme.colors.layerPanel).padding(16.dp)) {
                    RaftListRowGroup(Modifier.fillMaxWidth()) {
                        RaftListRow(
                            title = "Wug",
                            subtitle = "@wug · claude-code",
                            onClick = {},
                            leading = {
                                RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Md, presence = RaftPresence.Online)
                            },
                            trailing = { RaftBadge("Online", variant = RaftBadgeVariant.Success) },
                        )
                        RaftListRow(
                            title = "Bugen",
                            subtitle = "@bugen · codex",
                            onClick = {},
                            divider = true,
                            selected = true,
                            // The light is cut out of the surface by default: the card, the cyan wash.
                            leading = {
                                RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Md, presence = RaftPresence.Offline)
                            },
                            trailing = { RaftButton("Open", onClick = {}) },
                        )
                    }
                }
            }
        }

    @Test
    fun listRow() =
        capture("list_row") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("Default: tones, selected, pressed, focused, disabled")
                    RaftListRowGroup {
                        ChannelRow("general", RaftRowTone.Default, "Wug: Release notes are drafted.", badge = null)
                        ChannelRow("design", RaftRowTone.Unread, "Ada: The new tabs are in.", badge = { RaftUnreadCount(4) }, divider = true)
                        ChannelRow("ops", RaftRowTone.Unread, "Bugen: @you can you check the deploy?", badge = { RaftMentionMarker() }, divider = true)
                        ChannelRow("random", RaftRowTone.Muted, "Lin: Lunch?", meta = "12", divider = true)
                        ChannelRow("acme-shared", RaftRowTone.Away, "You haven't joined yet.", divider = true)
                        ChannelRow("release", RaftRowTone.Unread, "Selected: no count shown.", badge = { RaftUnreadCount(2) }, divider = true, selected = true)
                        StateCell(pressed = true) { ChannelRow("pressed", RaftRowTone.Default, "The pressed overlay.", divider = true) }
                        StateCell(focused = true) { ChannelRow("focused", RaftRowTone.Default, "The inset focus outline.", divider = true) }
                        ChannelRow("archived", RaftRowTone.Unread, "Disabled: no tone, no count.", badge = { RaftUnreadCount(9) }, divider = true, enabled = false)
                    }
                }
                Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("Plain: settings")
                    RaftListRowGroup {
                        RaftListRow("Appearance", variant = RaftListRowVariant.Plain, onClick = {}, trailing = { RaftListRowValue("System"); RaftListRowChevron() })
                        RaftListRow(
                            "Notifications",
                            variant = RaftListRowVariant.Plain,
                            divider = true,
                            description = "Mentions and direct messages, on every device you are signed in on.",
                            wrapDescription = true,
                            trailing = { RaftSwitch(checked = true, onCheckedChange = {}) },
                        )
                        RaftListRow("Language", variant = RaftListRowVariant.Plain, divider = true, onClick = {}, enabled = false, trailing = { RaftListRowValue("English"); RaftListRowChevron() })
                    }
                    Caption("Entry: shortcuts")
                    RaftListRowGroup {
                        RaftListRow("Search", variant = RaftListRowVariant.Entry, onClick = {}, leading = { Glyph(RaftGlyph.Info, 20.dp) })
                        RaftListRow("Activity", variant = RaftListRowVariant.Entry, onClick = {}, leading = { Glyph(RaftGlyph.AtSign, 20.dp) }, trailing = { RaftUnreadCount(3) })
                    }
                }
            }
        }

    @Composable
    private fun ChannelRow(
        title: String,
        tone: RaftRowTone,
        description: String,
        badge: (@Composable () -> Unit)? = null,
        meta: String = "10:42",
        divider: Boolean = false,
        selected: Boolean = false,
        enabled: Boolean = true,
    ) {
        RaftListRow(
            title = title,
            onClick = {},
            tone = tone,
            selected = selected,
            enabled = enabled,
            divider = divider,
            meta = meta,
            description = description,
            leading = {
                RaftIconTile(size = RaftIconTileSize.Md) { RaftText("#", style = RaftTheme.typography.headline, color = Color.Unspecified) }
            },
            badge = badge,
        )
    }

    @Test
    fun descriptionList() =
        capture("description_list") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("Vertical")
                    RaftDescriptionList {
                        Item("Status") { RaftAgentStateChip(RaftPresence.Busy) }
                        Item("Assignee") {
                            RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xs)
                            RaftText("Wug")
                        }
                        Item("Created by", "Ada Lovelace")
                        Item("Due date", null)
                    }
                    Caption("Vertical: values wrap")
                    RaftDescriptionList {
                        Item("Labels") {
                            listOf("design", "mobile", "release", "needs review").forEach {
                                RaftBadge(it, appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information)
                            }
                        }
                        Item("Reviewer") {
                            RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Xs, initials = "A")
                            RaftText("Ada Lovelace of the Analytical Engine Society")
                        }
                    }
                    Caption("Horizontal on a phone: one column")
                    RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                        Item("Runtime") { RaftBadge("Claude Code", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information) }
                        Item("Agent ID", "agt_7f3k29xq", mono = true)
                        Item("Joined", "Oct 12, 2026")
                    }
                }
                Column(Modifier.width(660.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("Horizontal with no width limit (in a horizontal scroll)")
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                            Item("Runtime") { RaftBadge("Claude Code", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information) }
                            Item("Workspace path", "/home/wug/work/raft-mobile", mono = true)
                        }
                    }
                    Caption("Horizontal at 640dp and wider")
                    RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                        Item("Runtime") { RaftBadge("Claude Code", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information) }
                        Item("Agent ID", "agt_7f3k29xq", mono = true)
                        Item(
                            "Role",
                            termAccessory = {
                                RaftIconButton(onClick = {}, contentDescription = "Explain Member Roles", variant = RaftButtonVariant.Ghost) {
                                    Glyph(RaftGlyph.Info, 14.dp)
                                }
                            },
                        ) { RaftText("Member") }
                        Item("Email", "ada@example.com", mono = true)
                    }
                }
            }
        }

    @Test
    fun sectionAndCode() =
        capture("section_code") {
            Column(Modifier.width(360.dp).background(RaftTheme.colors.layerPanel)) {
                RaftSection("Connect a computer", separator = false) {
                    RaftCodeBlock(
                        "npx @raft/agent connect --token rft_9c2b1f7e4a8d4c0e9b6f3a2d1c0b9a8f",
                        action = {
                            RaftIconButton(onClick = {}, contentDescription = "Copy Connect Command", variant = RaftButtonVariant.Ghost) {
                                Glyph(RaftGlyph.Copy)
                            }
                        },
                    )
                    Box(Modifier.height(8.dp))
                    RaftReadOnlyField("rft_9c2b…9a8f", mono = true)
                }
                RaftSection("Created agents", count = "3") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftIconTileVariant.entries.forEach { variant ->
                            RaftIconTile(variant = variant) { Glyph(RaftGlyph.Info, 18.dp) }
                        }
                        RaftStickerTile(RaftUiPalette.brand) { Glyph(RaftGlyph.CircleCheck, 18.dp) }
                        RaftStickerTile(RaftUiPalette.attention400, size = RaftIconTileSize.Lg) { Glyph(RaftGlyph.Plus, 22.dp) }
                    }
                }
            }
        }

    // Fixture parts ------------------------------------------------------------------

    @Composable
    private fun StateCell(pressed: Boolean = false, focused: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalRaftPressedPreview provides pressed,
            LocalRaftFocusedPreview provides focused,
            content = content,
        )
    }

    /** A navigation item drawn with the active-sticker primitive (what tabs will use). */
    @Composable
    private fun StickerItem(label: String, active: Boolean) {
        val colors = RaftTheme.colors
        val look = raftActiveStickerLook(active, colors, restingInk = colors.foregroundMuted)
        Box(
            Modifier
                .height(32.dp)
                .raftSticker(
                    look,
                    RaftRadius.Control,
                    pressed = LocalRaftPressedPreview.current,
                    focused = LocalRaftFocusedPreview.current,
                    colors = colors,
                )
                .padding(horizontal = 12.dp + RaftStroke.Control),
            contentAlignment = Alignment.Center,
        ) {
            RaftText(
                label,
                style = RaftTheme.typography.callout.copy(
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                ),
                color = look.ink,
            )
        }
    }

    @Composable
    private fun OverlaySwatch(fill: Color, surface: RaftPressSurface) {
        val colors = RaftTheme.colors
        val shape = RoundedCornerShape(RaftRadius.Control)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(false, true).forEach { pressed ->
                Box(
                    Modifier
                        .size(48.dp, 32.dp)
                        .background(fill, shape)
                        .raftPressOverlay(pressed, raftPressOverlayColor(surface, colors), RaftRadius.Control)
                        .border(RaftStroke.Hairline, colors.lineHairline, shape),
                )
            }
        }
    }

    @Composable
    private fun Glyph(glyph: RaftGlyph, size: Dp = 16.dp) = RaftGlyphIcon(glyph, size = size)

    @Composable
    private fun Caption(text: String, modifier: Modifier = Modifier) {
        RaftText(text = text, modifier = modifier, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }

    private fun buttonLabel(variant: RaftButtonVariant): String =
        when (variant) {
            RaftButtonVariant.Primary -> "Create Channel"
            RaftButtonVariant.Default -> "Assign"
            RaftButtonVariant.Ghost -> "Cancel"
            RaftButtonVariant.Warning -> "Stop Wug"
            RaftButtonVariant.Danger -> "Delete Agent"
            RaftButtonVariant.OnFrame -> "Raft Labs"
        }
}
