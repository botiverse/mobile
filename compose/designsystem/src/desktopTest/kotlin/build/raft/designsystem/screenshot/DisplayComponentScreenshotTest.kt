package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftHoveredPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import build.raft.designsystem.components.LocalRaftTooltipPreview
import build.raft.designsystem.components.RaftAgentStateChip
import build.raft.designsystem.components.RaftAgentTag
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarBadgeBox
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftInboxItemLeadingDefaults
import build.raft.designsystem.components.RaftInboxItemLeadingBadge
import build.raft.designsystem.components.RaftInboxItemLeading
import build.raft.designsystem.components.RaftAvatarEmpty
import build.raft.designsystem.components.RaftMessageDelegatedAvatar
import build.raft.designsystem.components.RaftAvatarGroup
import build.raft.designsystem.components.RaftAvatarGroupCount
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBackButtonDefaults
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
import build.raft.designsystem.components.RaftListRowText
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
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSectionEyebrowIcon
import build.raft.designsystem.components.RaftSidebarGroupLabel
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftSidebarItem
import build.raft.designsystem.components.RaftSidebarItemIcon
import build.raft.designsystem.components.RaftSidebarItemMeta
import build.raft.designsystem.components.RaftSidebarItemVariant
import build.raft.designsystem.components.RaftSidebarSectionAction
import build.raft.designsystem.components.RaftSidebarSectionHeader
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.RaftStickerTile
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.RaftMessageImageDimensions
import build.raft.designsystem.components.RaftMessageImageFit
import build.raft.designsystem.components.RaftMessageImageFrame
import build.raft.designsystem.components.RaftMessageImageGallery
import build.raft.designsystem.components.RaftUnreadCount
import build.raft.designsystem.components.RaftMessageReferenceReadMark
import build.raft.designsystem.components.appendRaftMessageReferenceReadMark
import build.raft.designsystem.components.raftMessageReferenceReadMarkInlineContent
import build.raft.designsystem.components.raftMessageReferenceSilentReadStateInlineContent
import build.raft.designsystem.components.raftActiveStickerLook
import build.raft.designsystem.components.raftInsetFocusOutline
import build.raft.designsystem.components.raftPressOverlay
import build.raft.designsystem.components.raftPressOverlayColor
import build.raft.designsystem.components.RaftPressSurface
import build.raft.designsystem.components.RaftProgressBar
import build.raft.designsystem.components.RaftProgressBarSize
import build.raft.designsystem.components.RaftProgressBarVariant
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
 * matrix (see [ComponentScreenshotTest] for the conventions). Pressed, hovered and focused
 * cells are forced through [LocalRaftPressedPreview] / [LocalRaftHoveredPreview] /
 * [LocalRaftFocusedPreview], open tooltips through [LocalRaftTooltipPreview]; spinners are
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

    private fun capture(name: String, settleFrames: Int = 0, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, settleFrames = settleFrames) {
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
                Caption("Md (32, 36 on the frame) and Lg (44): rest, pressed, hovered, focused, disabled")
                RaftButtonVariant.entries.forEach { variant ->
                    val onFrame = variant == RaftButtonVariant.OnFrame
                    Row(
                        Modifier.then(if (onFrame) Modifier.background(RaftTheme.colors.layerFrame) else Modifier).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Caption(variant.name, Modifier.width(72.dp))
                        RaftIconButtonSize.entries.forEach { size ->
                            listOf("Rest", "Pressed", "Hovered", "Focused", "Disabled").forEach { state ->
                                StateCell(pressed = state == "Pressed", focused = state == "Focused", hovered = state == "Hovered") {
                                    RaftIconButton(
                                        onClick = {},
                                        tooltip = "Add Reaction",
                                        variant = variant,
                                        size = size,
                                        enabled = state != "Disabled",
                                    ) {
                                        Glyph(
                                            RaftGlyph.Plus,
                                            when (size) {
                                                RaftIconButtonSize.Lg -> 22.dp
                                                RaftIconButtonSize.Md -> 18.dp
                                                else -> 16.dp
                                            },
                                        )
                                    }
                                }
                            }
                            Box(Modifier.width(12.dp))
                        }
                    }
                }
            }
        }

    /**
     * The top-bar Back: rest, pressed, focused, disabled; a leading Close; a light arrow on a
     * dark overlay (image preview); and in a header row at its 3dp edge inset.
     */
    @Test
    fun backButton() =
        capture("back_button") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Back (44, 22 arrow): rest, pressed, focused, disabled; Close")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Rest", "Pressed", "Focused", "Disabled").forEach { state ->
                        StateCell(pressed = state == "Pressed", focused = state == "Focused") {
                            RaftBackButton(onClick = {}, label = "Back", enabled = state != "Disabled")
                        }
                    }
                    RaftBackButton(onClick = {}, label = "Close", glyph = RaftGlyph.X)
                }
                Caption("On a dark overlay")
                Row(Modifier.background(Color(0xFF111111)).padding(4.dp)) {
                    RaftBackButton(onClick = {}, label = "Back", color = Color.White)
                }
                Caption("In a top bar")
                Row(
                    Modifier.width(320.dp).height(56.dp).background(RaftTheme.colors.layerFrame).padding(start = RaftBackButtonDefaults.EdgeInset),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftBackButton(onClick = {}, label = "Back")
                    RaftText("Search", style = RaftTheme.typography.headline)
                }
            }
        }

    /**
     * The long-press / hover tooltip of an icon button, open: above a ghost button with
     * room above it, below one at the top of the window. The button under it is pressed.
     */
    @Test
    fun iconButtonTooltip() =
        capture("icon_button_tooltip", settleFrames = 2) {
            CompositionLocalProvider(LocalRaftTooltipPreview provides true) {
                Box(Modifier.width(360.dp).height(150.dp)) {
                    StateCell(pressed = true) {
                        RaftIconButton(onClick = {}, tooltip = "Add Reaction", variant = RaftButtonVariant.Ghost, modifier = Modifier.align(Alignment.TopStart).padding(start = 48.dp)) {
                            Glyph(RaftGlyph.Plus, 18.dp)
                        }
                    }
                    RaftIconButton(
                        onClick = {},
                        tooltip = "Copy Connect Command",
                        variant = RaftButtonVariant.Ghost,
                        size = RaftIconButtonSize.Lg,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) { Glyph(RaftGlyph.Copy, 22.dp) }
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Narrower than its label: ends in an ellipsis, never a half glyph.
                    Caption("Truncated", Modifier.width(96.dp))
                    RaftBadge(
                        "Thinking about the release notes",
                        modifier = Modifier.width(120.dp),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Primary,
                    )
                    RaftBadge("OPENAI_API_KEY=••••••••", modifier = Modifier.width(110.dp), appearance = RaftBadgeAppearance.Outline)
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

    /**
     * raft-ui empty identity (`rf-avatar--empty`, web members strip "Add Member" tile): at md
     * next to a real md face and a `+N` count, then pressed, disabled, and at sm / lg.
     */
    @Test
    fun avatarEmpty() =
        capture("avatar_empty") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Md, initials = "A")
                RaftAvatarGroupCount("+12", RaftAvatarSize.Md)
                RaftAvatarEmpty(RaftAvatarSize.Md, contentDescription = "Add Member", onClick = {})
                CompositionLocalProvider(LocalRaftPressedPreview provides true) {
                    RaftAvatarEmpty(RaftAvatarSize.Md, contentDescription = "Add Member", onClick = {})
                }
                RaftAvatarEmpty(RaftAvatarSize.Md, contentDescription = "Add Member", enabled = false, onClick = {})
                RaftAvatarEmpty(RaftAvatarSize.Sm, contentDescription = "Add Member")
                RaftAvatarEmpty(RaftAvatarSize.Lg, contentDescription = "Add Member")
                Caption("Default, pressed, disabled, sm, lg")
            }
        }

    /**
     * raft-ui `MessageItemDelegatedAvatar` (RFC 077): an assistant's 2xs face in the corner
     * of its owner's md avatar, next to an agent's presence light for the corner and
     * overhang; a deleted assistant (grayed, faded, over the surface); on a highlighted row,
     * where the ring and backing take the row's surface.
     */
    @Test
    fun messageDelegatedAvatar() =
        capture("message_delegated_avatar") {
            val pixelFace: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(RaftUiPalette.brand400)) }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Md, presence = RaftPresence.Online)
                    DelegatedOwner { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xxs, image = pixelFace) }
                    DelegatedOwner { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xxs) }
                    DelegatedOwner(deleted = true) { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xxs) }
                    Caption("Presence, face, placeholder, deleted")
                }
                val rowFill = RaftTheme.colors.fillPressed.compositeOver(RaftTheme.colors.layerCanvas)
                Row(
                    Modifier.background(rowFill).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DelegatedOwner(surface = rowFill) { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xxs, image = pixelFace) }
                    DelegatedOwner(surface = rowFill, deleted = true) { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xxs) }
                    Caption("On a highlighted row")
                }
            }
        }

    /**
     * raft-ui InboxItemLeading + InboxItemLeadingBadge: a channel thread (tile + badge), a DM
     * thread with a person and with an agent (avatar + badge, no presence light), and for
     * contrast a plain agent DM (presence light) and a plain channel (no badge); then the
     * same badge on a selected (`info-soft`) row, whose ring stays the canvas.
     */
    @Test
    fun inboxItemLeading() =
        capture("inbox_item_leading") {
            val threadBadge: @Composable () -> Unit = {
                RaftInboxItemLeadingBadge {
                    RaftConversationKindIcon(RaftConversationKindGlyph.Thread, size = RaftInboxItemLeadingDefaults.BadgeIcon)
                }
            }
            val channelTile: @Composable () -> Unit = {
                RaftIconTile(size = RaftIconTileSize.Md) { RaftGlyphIcon(RaftGlyph.Hash, size = RaftIconTileSize.Md.icon) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftInboxItemLeading(badge = threadBadge) { channelTile() }
                    RaftInboxItemLeading(badge = threadBadge) { RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Md, initials = "SK") }
                    RaftInboxItemLeading(badge = threadBadge) { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Md) }
                    Caption("Threads")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftInboxItemLeading { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Md, presence = RaftPresence.Online) }
                    RaftInboxItemLeading { channelTile() }
                    Caption("DM, channel")
                }
                Row(
                    Modifier.background(RaftTheme.colors.infoSoft.compositeOver(RaftTheme.colors.layerCanvas)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftInboxItemLeading(badge = threadBadge) { channelTile() }
                    RaftInboxItemLeading(badge = threadBadge) { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Md) }
                    Caption("Selected row")
                }
            }
        }

    @Composable
    private fun DelegatedOwner(
        deleted: Boolean = false,
        surface: Color = RaftTheme.surfaceColor,
        face: @Composable () -> Unit,
    ) {
        RaftAvatarBadgeBox(
            size = RaftAvatarSize.Md,
            badge = { RaftMessageDelegatedAvatar(deleted = deleted, surfaceColor = surface) { face() } },
        ) {
            RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Md, initials = "AL")
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

    /**
     * raft-ui MessageReferenceReadMark (raft-design #715): the check after a read mention, on
     * the desktop body (14) and the phone body (16); an unread mention shows nothing. The last
     * row puts the check in a row of its own beside 14dp text.
     */
    @Test
    fun messageReferenceReadMark() =
        capture("message_reference_read_mark") {
            val inline =
                mapOf(
                    "read" to raftMessageReferenceReadMarkInlineContent { "Read" },
                    "unread" to raftMessageReferenceSilentReadStateInlineContent { "Not read yet" },
                )
            val sentence =
                buildAnnotatedString {
                    append("Ask ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("@Atlas") }
                    appendRaftMessageReferenceReadMark("read")
                    append(" or ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("@Sage") }
                    appendRaftMessageReferenceReadMark("unread")
                    append(", then loop in ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("@Noah") }
                    appendRaftMessageReferenceReadMark("read")
                    append(".")
                }
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(14 to 22, 16 to 24).forEach { (size, line) ->
                    Caption("${size}/${line}")
                    RaftText(
                        sentence,
                        style = RaftTheme.typography.body.copy(fontSize = size.sp, lineHeight = line.sp),
                        inlineContent = inline,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RaftText("@Atlas", style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold))
                    RaftMessageReferenceReadMark(label = "Read")
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

    /** Determinate sizes, header, warning, complete (success fill) and a frozen indeterminate bar. */
    @Test
    fun progressBar() =
        capture("progress_bar") {
            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RaftProgressBar(value = 0f, size = RaftProgressBarSize.Sm, contentDescription = "Empty")
                RaftProgressBar(value = 0.42f, label = "Downloading design-crop.png", valueText = "42%")
                RaftProgressBar(value = 0.08f, size = RaftProgressBarSize.Lg, contentDescription = "Started")
                RaftProgressBar(
                    value = 0.93f,
                    variant = RaftProgressBarVariant.Warning,
                    label = "Agent hours this month",
                    valueText = "93 / 100",
                )
                RaftProgressBar(value = 1f, label = "Downloaded", valueText = "100%")
                RaftProgressBar(value = null, label = "Waiting for the server", animated = false)
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
                RaftBanner(description = "Your changes are saved.", status = RaftBannerStatus.Success, onDismiss = {})
                RaftBanner(description = "Reports include the last 200 lines of the log.", status = RaftBannerStatus.Info, size = RaftBannerSize.Sm)
                RaftBanner(description = "You have used 80% of this month's hours.", status = RaftBannerStatus.Default)
                Caption("Pending: a spinner leads, md and sm")
                RaftBanner(
                    title = "Reconnecting after the upgrade",
                    description = "Wug's computer is restarting on version 0.42.0. This takes about a minute.",
                    status = RaftBannerStatus.Info,
                    pending = true,
                )
                RaftBanner(description = "Waiting for the computer to connect.", status = RaftBannerStatus.Info, size = RaftBannerSize.Sm, pending = true)
                Caption("Sm: warning with a title, success")
                RaftBanner(
                    title = "Wug stops while it restarts",
                    description = "Its open tasks stay where they are.",
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                )
                RaftBanner(description = "Moved to the new computer.", status = RaftBannerStatus.Success, size = RaftBannerSize.Sm)
                Caption("Panel notice")
                RaftPanelNotice(
                    "Wug couldn't start: the runtime is missing.",
                    actions = { RaftButton("View Logs", onClick = {}, variant = RaftButtonVariant.Ghost) },
                )
                Caption("Panel notice: info, destructive, success, default")
                RaftPanelNotice("Refreshing activity…", status = RaftBannerStatus.Info)
                RaftPanelNotice("Wug's memory was deleted and can't be restored.", status = RaftBannerStatus.Destructive)
                RaftPanelNotice("Saved.", status = RaftBannerStatus.Success)
                RaftPanelNotice("This panel shows the last 24 hours.", status = RaftBannerStatus.Default)
                Caption("Panel notice, stacked actions")
                RaftPanelNotice(
                    "Wug couldn't start: the runtime exited with code 1 after loading the workspace. Check the computer's log for the missing dependency and restart the agent.",
                    maxLines = 4,
                    stackActions = true,
                    actions = {
                        RaftButton("Copy Info", onClick = {}, variant = RaftButtonVariant.Ghost)
                        RaftButton("View Logs", onClick = {}, variant = RaftButtonVariant.Ghost)
                    },
                )
            }
        }

    /**
     * `components.ui.avatar-list-row.states`, with the web case's content: 342 wide, 16
     * padding, the two rows in one ListRowGroup (a 310dp card), human placeholder avatars,
     * the second row selected with the divider from the text column.
     */
    @Test
    fun avatarListRow() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("avatar_list_row_states_${appearance.name.lowercase()}", appearance) {
                Box(Modifier.width(342.dp).background(RaftTheme.colors.layerCanvas).padding(16.dp)) {
                    RaftListRowGroup(Modifier.fillMaxWidth()) {
                        RaftListRow(
                            title = "Cindy",
                            subtitle = "Claude Code",
                            onClick = {},
                            leading = { RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Md) },
                            trailing = { RaftBadge("Online", variant = RaftBadgeVariant.Success) },
                        )
                        RaftListRow(
                            title = "Product UX Designer",
                            subtitle = "product@slock.ai",
                            onClick = {},
                            divider = true,
                            selected = true,
                            leading = { RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Md) },
                            trailing = { RaftButton("Open", onClick = {}) },
                        )
                    }
                }
            }
            // An agent row with a presence light cut out of the card and of the selected wash.
            RaftScreenshot.capture("avatar_list_row_presence_${appearance.name.lowercase()}", appearance) {
                Box(Modifier.width(342.dp).background(RaftTheme.colors.layerCanvas).padding(16.dp)) {
                    RaftListRowGroup(Modifier.fillMaxWidth()) {
                        RaftListRow(
                            title = "Wug",
                            subtitle = "@wug · claude-code",
                            onClick = {},
                            leading = { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Md, presence = RaftPresence.Online) },
                            trailing = { RaftBadge("Online", variant = RaftBadgeVariant.Success) },
                        )
                        RaftListRow(
                            title = "Bugen",
                            subtitle = "@bugen · codex",
                            onClick = {},
                            divider = true,
                            selected = true,
                            leading = { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Md, presence = RaftPresence.Offline) },
                            trailing = { RaftButton("Open", onClick = {}) },
                        )
                    }
                }
            }
        }

    /**
     * The web members page roster (ChannelMembers presentation="page"): a group heading with
     * the count after a separator ("Humans · 2") and [RaftListRowText.Member] rows (15/20 title,
     * sans 12/16 sub-line), a role tag and a ghost remove button.
     */
    @Test
    fun memberListRowLight() = memberListRow(RaftAppearance.Light)

    @Test
    fun memberListRowDark() = memberListRow(RaftAppearance.Dark)

    private fun memberListRow(appearance: RaftAppearance) =
            RaftScreenshot.capture("member_list_row_${appearance.name.lowercase()}", appearance) {
                Column(Modifier.width(342.dp)) {
                    RaftSidebarSectionHeader("Humans", expanded = true, onExpandedChange = {}, count = 2, countSeparator = " · ")
                    RaftListRow(
                        title = "Artea",
                        subtitle = "Product Designer",
                        onClick = {},
                        text = RaftListRowText.Member,
                        leading = { RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Md) },
                        trailing = { RaftBadge("Channel Admin", appearance = RaftBadgeAppearance.Outline) },
                    )
                    RaftListRow(
                        title = "Wug",
                        subtitle = "Online",
                        onClick = {},
                        divider = true,
                        text = RaftListRowText.Member,
                        leading = { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Md, presence = RaftPresence.Online) },
                    )
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
                    Caption("Narrow, 240dp: the title gives way, then a long meta truncates")
                    RaftListRowGroup(Modifier.width(240.dp)) {
                        ChannelRow("release-announcements", RaftRowTone.Default, "Wug: Release notes are drafted.")
                        ChannelRow("invites", RaftRowTone.Default, "Sent to", meta = "ada.lovelace@analytical-engine.example", divider = true)
                    }
                }
            }
        }

    /**
     * raft-ui Sidebar rows at the phone size (Home): the entry rows (20dp icon, 12dp gap),
     * group headers (collapsed, with attention, expanded with controls), DM rows (the peer
     * variant: aside parts 6dp apart) and channel rows in every state: mention plus
     * threads, unread plus threads, muted with unread, plain unread, selected (the yellow
     * sticker), private, away, a subtitle, pressed, a row and a header whose press-and-hold
     * menu is open (pressed; the header's attention pixel hidden), focused, a truncated
     * name, and a mark right after the name (`titleTrailing`; with a long name, and on a
     * selected row with a subtitle).
     */
    @Test
    fun sidebar() =
        capture("sidebar") {
            Column(Modifier.width(360.dp)) {
                // Home's entry rows: a 20dp icon, 12dp to the name.
                val entry = RaftSidebarItemVariant.Entry
                RaftSidebarItem("Search", onClick = {}, variant = entry, leading = { RaftSidebarItemIcon(RaftGlyph.Search) })
                RaftSidebarItem(
                    "Activity",
                    onClick = {},
                    variant = entry,
                    leading = { RaftSidebarItemIcon(RaftGlyph.Info) },
                    trailing = { RaftUnreadCount(41) },
                )
                RaftSidebarItem(
                    "Saved",
                    onClick = {},
                    variant = entry,
                    leading = { RaftSidebarItemIcon(RaftGlyph.Copy) },
                    trailing = { RaftSidebarItemMeta("2") },
                )
                RaftSidebarSectionHeader(
                    "Pinned",
                    expanded = false,
                    onExpandedChange = {},
                    count = 3,
                    actions = { RaftSidebarSectionAction(RaftGlyph.ArrowUpDown, "Sort Pinned", onClick = {}) },
                )
                RaftSidebarSectionHeader(
                    "automation/infra",
                    expanded = false,
                    onExpandedChange = {},
                    count = 6,
                    attention = true,
                    icon = { RaftStatusDot(variant = RaftStatusVariant.Information, size = RaftStatusSize.Lg) },
                )
                RaftSidebarSectionHeader(
                    "Channels",
                    expanded = true,
                    onExpandedChange = {},
                    count = 38,
                    attention = true,
                    actions = {
                        RaftSidebarSectionAction(RaftGlyph.ArrowDownUp, "Sort Channels", onClick = {})
                        // Any icon: an app icon through the slot.
                        RaftSidebarSectionAction(tooltip = "Create Channel", onClick = {}) { Glyph(RaftGlyph.Plus) }
                    },
                )
                RaftSidebarChannelItem("proj-raft-computer", onClick = {}, mentioned = true, unreadCount = 4, threadCount = 2)
                RaftSidebarChannelItem("proj-runtime", onClick = {}, unreadCount = 5, threadCount = 1)
                RaftSidebarChannelItem("proj-mobile", onClick = {}, threadCount = 3)
                RaftSidebarChannelItem("proj-connectors", onClick = {}, tone = RaftRowTone.Muted, unreadCount = 7)
                RaftSidebarChannelItem("wg-task-v2", onClick = {}, unreadCount = 5)
                RaftSidebarChannelItem("general", onClick = {}, selected = true, unreadCount = 120)
                RaftSidebarChannelItem("leadership", onClick = {}, isPrivate = true)
                RaftSidebarChannelItem("acme-shared", onClick = {}, tone = RaftRowTone.Away)
                // A DM: the aside's parts 6dp apart.
                RaftSidebarItem(
                    "Bugen",
                    onClick = {},
                    variant = RaftSidebarItemVariant.Peer,
                    subtitle = "planning the release candidate for next week",
                    leading = { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xxs, initials = "B", presence = RaftPresence.Online) },
                    trailing = {
                        RaftSidebarItemMeta("Codex")
                        RaftUnreadCount(4)
                    },
                )
                StateCell(pressed = true) { RaftSidebarChannelItem("pressed", onClick = {}) }
                // Press and hold opened the row's menu: it keeps the press overlay.
                RaftSidebarChannelItem("menu-open", onClick = {}, onLongClick = {}, menuOpen = true)
                RaftSidebarSectionHeader(
                    "Joint Channels",
                    expanded = false,
                    onExpandedChange = {},
                    count = 1,
                    attention = true,
                    onLongClick = {},
                    menuOpen = true,
                )
                StateCell(focused = true) { RaftSidebarChannelItem("focused", onClick = {}, unreadCount = 2) }
                StateCell(focused = true) { RaftSidebarChannelItem("focused-selected", onClick = {}, selected = true) }
                RaftSidebarChannelItem("a-channel-name-long-enough-to-run-past-the-end-of-the-row", onClick = {}, unreadCount = 9)
                // A mark right after the name (activity muted, a bridge): the name truncates, the mark stays.
                RaftSidebarChannelItem("release", onClick = {}, titleTrailing = { Glyph(RaftGlyph.Info, 14.dp) })
                RaftSidebarChannelItem(
                    "a-bridged-channel-name-long-enough-to-run-past-the-end",
                    onClick = {},
                    unreadCount = 3,
                    titleTrailing = { Glyph(RaftGlyph.Copy, 14.dp) },
                )
                RaftSidebarItem(
                    "Wug",
                    onClick = {},
                    variant = RaftSidebarItemVariant.Peer,
                    selected = true,
                    subtitle = "idle",
                    leading = { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xxs, initials = "W") },
                    titleTrailing = { Glyph(RaftGlyph.Info, 14.dp) },
                )
                RaftSidebarItem("This Mac", leading = { RaftSidebarItemIcon(RaftGlyph.Info) }, subtitle = "display-only row")
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
                    Caption("Horizontal under 384dp (a phone): one column, 10dp between facts")
                    RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                        Item("Runtime") { RaftBadge("Claude Code", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information) }
                        Item("Agent ID", "agt_7f3k29xq", mono = true)
                        Item("Joined", "Oct 12, 2026")
                    }
                }
                Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("Horizontal with no width limit (in a horizontal scroll)")
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                            Item("Runtime") { RaftBadge("Claude Code", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information) }
                            Item("Workspace path", "/home/wug/work/raft-mobile", mono = true)
                        }
                    }
                    Caption("Horizontal at 384dp of its own width and wider: two columns")
                    RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                        Item("Runtime") { RaftBadge("Claude Code", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information) }
                        Item("Agent ID", "agt_7f3k29xq", mono = true)
                        Item(
                            "Role",
                            termAccessory = {
                                RaftIconButton(onClick = {}, tooltip = "Explain Member Roles", variant = RaftButtonVariant.Ghost) {
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
                            RaftIconButton(onClick = {}, tooltip = "Copy Connect Command", variant = RaftButtonVariant.Ghost) {
                                Glyph(RaftGlyph.Copy)
                            }
                        },
                    )
                    Box(Modifier.height(8.dp))
                    RaftReadOnlyField("rft_9c2b…9a8f", mono = true)
                    Box(Modifier.height(8.dp))
                    RaftReadOnlyField(
                        "Reviews pull requests in #design and posts a summary of every change that touches the tokens.",
                        maxLines = Int.MAX_VALUE,
                    )
                    Box(Modifier.height(8.dp))
                    RaftReadOnlyField("/home/wug/work/raft-mobile/compose/designsystem/src/commonMain/kotlin", mono = true, maxLines = 2)
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

    /**
     * The section heading (web `SectionHeader`): the eyebrow alone, with a count, with a
     * 16dp hint icon, with both; and a [RaftSection] with an icon and an action.
     */
    @Test
    fun sectionHeading() =
        capture("section_heading") {
            Column(Modifier.width(360.dp).background(RaftTheme.colors.layerPanel)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RaftSectionEyebrow("Profile")
                    RaftSectionEyebrow("Created agents", count = "3")
                    RaftSectionEyebrow("Notifications", icon = { RaftSectionEyebrowIcon(RaftGlyph.Info) })
                    RaftSectionEyebrow("Computers", count = "12", icon = { RaftSectionEyebrowIcon(RaftGlyph.Hash) })
                }
                RaftSection(
                    "Danger zone",
                    icon = { RaftSectionEyebrowIcon(RaftGlyph.TriangleAlert) },
                    action = { RaftButton("Edit", onClick = {}, size = RaftButtonSize.Md) },
                ) {
                    RaftReadOnlyField("Leave or delete this server")
                }
            }
        }

    /**
     * The settings parts the web builds from raft-ui: the hub (S-1, `SettingsSidebarList`:
     * `SidebarGroupLabel` over `SidebarItem` rows at the phone size, one selected, one with
     * an attention dot) and a group of setting rows (S-16, `ListRowGroup` > plain
     * `ListRow` + `Switch`).
     */
    @Test
    fun settingsParts() =
        capture("settings_parts") {
            Column(Modifier.width(360.dp)) {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    RaftSidebarGroupLabel("Personal")
                    RaftSidebarItem("Account", onClick = {}, selected = true, leading = { RaftSidebarItemIcon(RaftGlyph.AtSign) })
                    RaftSidebarItem("Notifications", onClick = {}, leading = { RaftSidebarItemIcon(RaftGlyph.Info) })
                    RaftSidebarGroupLabel("Resources")
                    RaftSidebarItem(
                        "Feedback",
                        onClick = {},
                        leading = { RaftSidebarItemIcon(RaftGlyph.Copy) },
                        trailing = { RaftStatusDot(variant = RaftStatusVariant.Accent) },
                    )
                }
                RaftListRowGroup(Modifier.padding(16.dp).fillMaxWidth()) {
                    RaftListRow(
                        title = "Desktop notifications",
                        variant = RaftListRowVariant.Plain,
                        description = "Show a notification when a message arrives while Raft is in the background.",
                        wrapDescription = true,
                        trailing = { RaftSwitch(checked = true, onCheckedChange = {}, label = "Desktop notifications") },
                    )
                    RaftListRow(
                        title = "Sound",
                        variant = RaftListRowVariant.Plain,
                        divider = true,
                        trailing = { RaftSwitch(checked = false, onCheckedChange = {}, label = "Sound") },
                    )
                }
            }
        }

    /**
     * raft-ui MessageImageGallery: a lone landscape, portrait and unsized image (each its
     * reserved box, `contain` on `fill-muted`), a row of two, a wrapped row of three, and
     * frames at rest, pressed and focused.
     */
    @Test
    fun messageImageGallery() =
        capture("message_image_gallery") {
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Caption("Lone images: landscape, portrait (letterboxed), unknown size")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    RaftMessageImageGallery(listOf(RaftMessageImageDimensions(400, 300)), Modifier.width(160.dp)) { FakeImage(4f / 3f, it.fit) }
                    RaftMessageImageGallery(listOf(RaftMessageImageDimensions(300, 1000)), Modifier.width(80.dp)) { FakeImage(9f / 16f, it.fit) }
                }
                RaftMessageImageGallery(listOf(RaftMessageImageDimensions())) { FakeImage(9f / 19f, it.fit) }
                Caption("At its full 360dp: two (144dp rows), then three (128dp; the third wraps)")
                RaftMessageImageGallery(List(2) { RaftMessageImageDimensions(800, 600) }) { FakeImage(4f / 3f, it.fit) }
                RaftMessageImageGallery(listOf(RaftMessageImageDimensions(800, 600), RaftMessageImageDimensions(300, 1000), RaftMessageImageDimensions(800, 600))) {
                    FakeImage(if (it.index == 1) 0.3f else 4f / 3f, it.fit)
                }
                Caption("Narrower, 300dp: two (128dp rows), then three (112dp)")
                RaftMessageImageGallery(List(2) { RaftMessageImageDimensions(800, 600) }, Modifier.width(300.dp)) { FakeImage(4f / 3f, it.fit) }
                RaftMessageImageGallery(
                    listOf(RaftMessageImageDimensions(800, 600), RaftMessageImageDimensions(300, 1000), RaftMessageImageDimensions(800, 600)),
                    Modifier.width(300.dp),
                ) {
                    FakeImage(if (it.index == 1) 0.3f else 4f / 3f, it.fit)
                }
                Caption("Frame: rest, pressed, focused, pending")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StateCell { RaftMessageImageFrame(Modifier.size(64.dp), onClick = {}) { FakeImage(1f, RaftMessageImageFit.Cover) } }
                    StateCell(pressed = true) { RaftMessageImageFrame(Modifier.size(64.dp), onClick = {}) { FakeImage(1f, RaftMessageImageFit.Cover) } }
                    StateCell(focused = true) { RaftMessageImageFrame(Modifier.size(64.dp), onClick = {}) { FakeImage(1f, RaftMessageImageFit.Cover) } }
                    StateCell { RaftMessageImageFrame(Modifier.size(64.dp), pending = true) { FakeImage(1f, RaftMessageImageFit.Cover) } }
                }
            }
        }

    /** A stand-in photo of [ratio]: fills the cell when cropped, else sits letterboxed. */
    @Composable
    private fun FakeImage(ratio: Float, fit: RaftMessageImageFit) {
        val photo = Modifier.background(RaftUiPalette.attention400)
        if (fit == RaftMessageImageFit.Cover) {
            Box(Modifier.fillMaxSize().then(photo))
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f).then(photo))
            }
        }
    }

    // Fixture parts ------------------------------------------------------------------

    @Composable
    private fun StateCell(pressed: Boolean = false, focused: Boolean = false, hovered: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalRaftPressedPreview provides pressed,
            LocalRaftFocusedPreview provides focused,
            LocalRaftHoveredPreview provides hovered,
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
