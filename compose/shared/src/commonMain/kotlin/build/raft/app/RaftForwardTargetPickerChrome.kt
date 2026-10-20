package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

// web ForwardComposerMobile (targets step): a phone PanelHeader whose 44dp ghost Back and
// trailing actions sit 3dp from the screen edges, `main p-3` around the search InputGroup,
// a `mb-1 h-6 px-1` SectionEyebrow, then `stack-y-0.5` ListRows.
private val ForwardPickerHeaderEdgeInset = 3.dp
private val ForwardPickerHeaderContentSpacing = 8.dp
internal val ForwardPickerBodyPadding = 12.dp
private val ForwardPickerEyebrowHeight = 24.dp
private val ForwardPickerRowGap = 2.dp
// FORWARD_TARGET_ROW_CLASS: `min-h-11 gap-3 rounded-control px-2 py-1.5 text-subhead`.
private val ForwardPickerRowMinHeight = 44.dp
private val ForwardPickerRowPaddingHorizontal = 8.dp
private val ForwardPickerRowPaddingVertical = 6.dp
private val ForwardPickerRowGapInner = 12.dp
private val ForwardPickerAvatarSize = 27.dp

@Composable
internal fun RaftForwardPickerHeader(
    subtitle: String,
    multiSelect: Boolean,
    multiSelectAvailable: Boolean,
    dismissEnabled: Boolean,
    onBack: () -> Unit,
    onStartMultiSelect: () -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val backLabel =
        if (multiSelect && multiSelectAvailable) stringResource(MR.strings.common_cancel) else stringResource(MR.strings.common_back)
    RaftTopPageHeader(
        title = stringResource(MR.strings.thread_forward_select_destinations),
        backgroundColor = RaftColor.Canvas,
        contentStartPadding = ForwardPickerHeaderEdgeInset,
        contentEndPadding = ForwardPickerHeaderEdgeInset,
        contentSpacing = ForwardPickerHeaderContentSpacing,
        titleLeadingContent = {
            if (multiSelect && multiSelectAvailable) {
                // Leaving multi-select is an undo, not a Back: same 44dp ghost control, Undo2 glyph.
                RaftIconButton(
                    onClick = onBack,
                    tooltip = backLabel,
                    enabled = dismissEnabled,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg
                ) {
                    ForwardPickerGlyph(RaftLucideIconName.Undo2, RaftBackButtonDefaults.IconSize)
                }
            } else {
                RaftBackButton(onClick = onBack, label = backLabel, enabled = dismissEnabled)
            }
        },
        titleContent = {
            Column(modifier = Modifier.weight(1f)) {
                RaftText(
                    text = stringResource(MR.strings.thread_forward_select_destinations),
                    style = typography.headline,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                RaftText(
                    text = subtitle,
                    style = typography.caption,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailingContent =
            if (multiSelectAvailable && !multiSelect) {
                {
                    RaftButton(
                        text = stringResource(MR.strings.thread_forward_select_multiple),
                        onClick = onStartMultiSelect,
                        variant = RaftButtonVariant.Ghost
                    )
                }
            } else {
                null
            }
    )
}

/**
 * web `InputGroup` + inline-start Search addon (`autoFocus` on the phone picker): the DS
 * field, so focus draws the raft-ui focus ring round the group.
 */
@Composable
internal fun RaftForwardPickerSearchField(
    query: String,
    enabled: Boolean,
    autoFocus: Boolean,
    focusKey: Any?,
    onQueryChange: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(focusKey) {
            // The field may not be attached on the first frame; retry briefly.
            repeat(10) {
                kotlinx.coroutines.delay(50)
                if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }
    RaftTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(MR.strings.thread_forward_search_targets),
        enabled = enabled,
        leading = { ForwardPickerGlyph(RaftLucideIconName.Search, 18.dp) },
        trailing =
            if (query.isNotEmpty()) {
                {
                    Box(
                        modifier = Modifier.size(18.dp).clickable(enabled = enabled) { onQueryChange("") },
                        contentAlignment = Alignment.Center
                    ) {
                        ForwardPickerGlyph(RaftLucideIconName.X, 16.dp)
                    }
                }
            } else {
                null
            },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = ForwardPickerBodyPadding, top = ForwardPickerBodyPadding, end = ForwardPickerBodyPadding)
                .focusRequester(focusRequester)
    )
}

@Composable
internal fun RaftForwardPickerEyebrow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = ForwardPickerBodyPadding, end = ForwardPickerBodyPadding, bottom = 4.dp)
                .heightIn(min = ForwardPickerEyebrowHeight)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftSectionEyebrow(text)
    }
}

internal val ForwardPickerListRowGap: Dp get() = ForwardPickerRowGap

/** web `ForwardComposerTargetList` local-target row: a frameless DS ListRow. */
@Composable
internal fun <T> RaftForwardPickerTargetRow(
    target: RaftForwardTargetPickerItem<T>,
    selected: Boolean,
    showCheckbox: Boolean,
    interactive: Boolean,
    onClick: () -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ForwardPickerBodyPadding)
                .alpha(if (interactive) 1f else 0.72f)
                .background(if (pressed) colors.fillPressed else Color.Transparent, shape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .heightIn(min = ForwardPickerRowMinHeight)
                .padding(horizontal = ForwardPickerRowPaddingHorizontal, vertical = ForwardPickerRowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForwardPickerRowGapInner)
    ) {
        if (showCheckbox) {
            RaftCheckbox(checked = selected, onCheckedChange = null)
        }
        when (target.visualKind) {
            RaftForwardTargetVisualKind.DirectMessage ->
                if (target.avatarWireValue.isNullOrBlank()) {
                    // AvatarSlot `humanPlaceholder`: no photo is the person glyph, not initials.
                    // web AvatarSlot `humanPlaceholder`: lucide User inside the lavender sticker.
                    RaftAvatar(
                        type =
                            if (target.cacheMemberKind == build.raft.app.model.MemberKind.Agent) {
                                RaftAvatarType.Agent
                            } else {
                                RaftAvatarType.Human
                            },
                        size = RaftAvatarSize.Sm,
                        image = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                RaftLucideIcon(
                                    RaftLucideIconName.User,
                                    modifier = Modifier.size(RaftAvatarSize.Sm.iconSize),
                                    color = raftArgbHex(RaftColor.StickerInk)
                                )
                            }
                        }
                    )
                } else RaftAppAvatar(
                    avatarWireValue = target.avatarWireValue,
                    label = target.title.ifBlank { target.avatarLabel },
                    avatarSize = ForwardPickerAvatarSize,
                    cacheSubject = raftMemberAvatarCacheSubject(target.cacheMemberKind, target.cacheMemberId),
                    cacheServerId = target.cacheServerId,
                    fallbackFill = raftAvatarFallbackFill(target.cacheMemberKind)
                )
            RaftForwardTargetVisualKind.PublicChannel,
            RaftForwardTargetVisualKind.PrivateChannel,
            RaftForwardTargetVisualKind.JointChannel ->
                RaftIconTile(size = RaftIconTileSize.Sm) {
                    ForwardPickerGlyph(
                        target.visualKind.iconName() ?: RaftLucideIconName.Hash,
                        RaftIconTileSize.Sm.icon
                    )
                }
        }
        RaftText(
            text = target.forwardPickerLabel(),
            style = typography.subhead.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** web `targetPickerLabel`: a DM reads `@name`, a channel its bare name. */
internal fun <T> RaftForwardTargetPickerItem<T>.forwardPickerLabel(): String =
    if (visualKind == RaftForwardTargetVisualKind.DirectMessage && !title.startsWith("@")) "@$title" else title

@Composable
private fun ForwardPickerGlyph(name: RaftLucideIconName, size: Dp) {
    val ink = LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
    RaftLucideIcon(name, modifier = Modifier.size(size), color = raftArgbHex(ink))
}
