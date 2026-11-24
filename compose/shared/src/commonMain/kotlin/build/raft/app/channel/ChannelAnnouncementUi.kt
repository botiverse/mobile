package build.raft.app

import androidx.compose.foundation.background
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ChannelAnnouncementChangeKind
import build.raft.app.data.ChannelAnnouncementNotice
import build.raft.app.data.ChannelAnnouncementSlice
import build.raft.app.data.RaftChannelAnnouncement
import build.raft.app.data.ScreenError
import build.raft.app.markdown.lucideIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.LocalRaftListRowInks
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftRowTone
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Channel announcements' UI (#proj-chat:#29, flag channel_announcement_v0), as the web draws them
 * (botiverse/slock 6a848d0a: `components/channel/ChannelAnnouncementStrip.tsx`,
 * `ChannelAnnouncementField.tsx`, `channelAnnouncementNotice.ts`; raft-design
 * products/raft-chat/channel.md "The announcement"). The state is SharedChannelAnnouncementStore's.
 */

/** Test tags: the web's `data-testid`s. */
internal object ChannelAnnouncementTestTags {
    const val Strip = "channel-announcement-strip"
    const val StripLink = "channel-announcement-strip-link"
    const val StripLine = "channel-announcement-strip-line"
    const val StripDot = "channel-announcement-strip-dot"
    const val StripTime = "channel-announcement-strip-time"
    const val StripOptions = "channel-announcement-strip-options"
    const val StripCopyLink = "channel-announcement-strip-copy-link"
    const val StripSettings = "channel-announcement-strip-settings"
    const val Details = "channel-settings-announcement"
    const val DetailsItem = "channel-settings-announcement-item"
    const val DetailsMeta = "channel-settings-announcement-meta"
    const val DetailsMetaName = "channel-settings-announcement-meta-name"
    const val DetailsMetaTime = "channel-settings-announcement-meta-time"
    const val DetailsEmpty = "channel-settings-announcement-empty"
    const val DetailsUnavailable = "channel-settings-announcement-unavailable"
    const val DetailsSet = "channel-settings-announcement-set"
    const val DetailsReplace = "channel-settings-announcement-replace"
    const val DetailsRemove = "channel-settings-announcement-remove"
    const val DetailsActions = "channel-settings-announcement-actions"
    const val PickerConfirm = "channel-settings-announcement-picker-confirm"
    const val ReplaceConfirm = "channel-settings-announcement-replace-confirm"
    const val RemoveConfirm = "channel-settings-announcement-remove-confirm"
}

/** Below the web's `md` (768px) the strip drops the updater's avatar and the time (Kirby, #14069). */
private val ChannelAnnouncementStripWideFrom = 768.dp

/** Who made the announcement's current version, as people read them; "Someone" when unknown. */
@Composable
internal fun channelAnnouncementAuthorName(name: String?): String =
    name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.channel_announcement_someone)

/**
 * The strip's sentence (web `line`): unseen, who updated it (with the version's note when it has
 * one); seen, the first line of the text, else who updated it.
 */
@Composable
internal fun channelAnnouncementStripLine(announcement: RaftChannelAnnouncement, authorName: String): String =
    when {
        !announcement.seen && announcement.note != null ->
            stringResource(Res.string.channel_announcement_strip_updated_with_note, authorName, announcement.note)
        !announcement.seen -> stringResource(Res.string.channel_announcement_strip_updated, authorName)
        announcement.excerpt != null -> announcement.excerpt
        else -> stringResource(Res.string.channel_announcement_strip_updated_by, authorName)
    }

/**
 * The strip above the timeline: megaphone, the title (bold while the current version is unseen,
 * at most 55% of the text), one sentence, the attention dot while unseen, the time (wide only),
 * and ⋯. One line at every width, 40dp, on paper with one seam under it. The whole row opens the
 * announcement; ⋯ is a button of its own (Copy link; Announcement settings for who may change
 * it). It can't be hidden.
 */
@Composable
internal fun ChannelAnnouncementStrip(
    announcement: RaftChannelAnnouncement,
    authorName: String,
    onOpen: () -> Unit,
    onCopyLink: () -> Unit,
    /** Opens the channel details; null for people who may not change the announcement. */
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenMenu: (() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val unread = !announcement.seen
    val line = channelAnnouncementStripLine(announcement, authorName)
    val linkName = stringResource(Res.string.channel_announcement_strip_link_name, announcement.title)
    val seam = colors.lineSeam
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(colors.layerCanvas)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(seam, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .testTag(ChannelAnnouncementTestTags.Strip),
    ) {
        val wide = maxWidth >= ChannelAnnouncementStripWideFrom
        // The row opens it; ⋯ is a button of its own beside it, never inside the row's target.
        Row(
            Modifier.fillMaxWidth().height(40.dp).raftSafeAreaHorizontalPadding().padding(end = if (wide) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                // Hover lays `fill-subtle` over the row, as the web strip's `hover:bg-fill-subtle`.
                .raftClickable(onClick = onOpen, onClickLabel = linkName, tier = RaftHoverTier.Subtle)
                .padding(start = if (wide) 20.dp else 16.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftLucideIcon(RaftLucideIconName.Megaphone, Modifier.size(16.dp), color = raftArgbHex(colors.foregroundMuted))
            BoxWithConstraints(Modifier.weight(1f)) {
                val titleMax = maxWidth * 0.55f
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftText(
                        announcement.title,
                        Modifier.widthIn(max = titleMax).alignByBaseline().testTag(ChannelAnnouncementTestTags.StripLink),
                        style = typography.callout.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RaftText(
                        line,
                        Modifier.weight(1f, fill = false).alignByBaseline().testTag(ChannelAnnouncementTestTags.StripLine),
                        style = typography.footnote,
                        color = colors.foregroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (unread) {
                RaftStatusDot(
                    Modifier.testTag(ChannelAnnouncementTestTags.StripDot),
                    variant = RaftStatusVariant.Accent,
                    contentDescription = stringResource(Res.string.channel_announcement_strip_new_dot),
                )
            }
            if (wide) {
                RaftText(
                    raftRelativeTimestampLabel(announcement.updatedAt),
                    Modifier.testTag(ChannelAnnouncementTestTags.StripTime),
                    style = typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                )
            }
        }
            ChannelAnnouncementStripMenu(onCopyLink = onCopyLink, onOpenSettings = onOpenSettings, onOpenMenu = onOpenMenu)
        }
    }
}

@Composable
private fun ChannelAnnouncementStripMenu(
    onCopyLink: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onOpenMenu: (() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        RaftIconButton(
            onClick = {
                if (!open) onOpenMenu?.invoke()
                open = true
            },
            tooltip = stringResource(Res.string.channel_announcement_strip_options),
            modifier = Modifier.testTag(ChannelAnnouncementTestTags.StripOptions),
            variant = RaftButtonVariant.Ghost,
        ) {
            RaftLucideIcon(RaftLucideIconName.EllipsisVertical, Modifier.size(16.dp), color = raftArgbHex(colors.foregroundMuted))
        }
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, alignEnd = true) {
            RaftMenuItem(
                text = stringResource(Res.string.channel_announcement_strip_copy_link),
                onClick = {
                    open = false
                    onCopyLink()
                },
                modifier = Modifier.testTag(ChannelAnnouncementTestTags.StripCopyLink),
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Link) },
            )
            if (onOpenSettings != null) {
                RaftMenuSeparator()
                RaftMenuItem(
                    text = stringResource(Res.string.channel_announcement_strip_settings),
                    onClick = {
                        open = false
                        onOpenSettings()
                    },
                    modifier = Modifier.testTag(ChannelAnnouncementTestTags.StripSettings),
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Settings) },
                )
            }
        }
    }
}

/** The change line in the reader's language (web `channelAnnouncementNoticeLine`). */
@Composable
internal fun channelAnnouncementNoticeLine(notice: ChannelAnnouncementNotice): String {
    val name = channelAnnouncementAuthorName(notice.actor.name)
    val title = notice.title
    return if (title == null) {
        when (notice.change) {
            ChannelAnnouncementChangeKind.Set -> stringResource(Res.string.channel_announcement_event_set_untitled, name)
            ChannelAnnouncementChangeKind.Replaced -> stringResource(Res.string.channel_announcement_event_replaced_untitled, name)
            ChannelAnnouncementChangeKind.Removed -> stringResource(Res.string.channel_announcement_event_removed_untitled, name)
        }
    } else {
        when (notice.change) {
            ChannelAnnouncementChangeKind.Set -> stringResource(Res.string.channel_announcement_event_set, name, title)
            ChannelAnnouncementChangeKind.Replaced -> stringResource(Res.string.channel_announcement_event_replaced, name, title)
            ChannelAnnouncementChangeKind.Removed -> stringResource(Res.string.channel_announcement_event_removed, name, title)
        }
    }
}

// ---- Channel details › Pinned content ------------------------------------------------------------

/** One of the channel's Artifacts, as the picker lists it. */
internal data class ChannelAnnouncementChoice(
    val artifactId: String,
    val title: String,
    val meta: String,
    val kind: ArtifactKind = ArtifactKind.Markdown,
)

/** What the picker shows. */
internal sealed interface ChannelAnnouncementPickerView {
    data object Loading : ChannelAnnouncementPickerView

    data object Failed : ChannelAnnouncementPickerView

    data class Ready(val choices: List<ChannelAnnouncementChoice>, val hasMore: Boolean, val loadingMore: Boolean) : ChannelAnnouncementPickerView
}

internal sealed interface ChannelAnnouncementMetaPart {
    data class Literal(val text: String) : ChannelAnnouncementMetaPart

    data object Name : ChannelAnnouncementMetaPart

    data object Time : ChannelAnnouncementMetaPart
}

private const val ChannelAnnouncementMetaNameToken = "\u0001"
private const val ChannelAnnouncementMetaTimeToken = "\u0002"

/**
 * Splits the localized `"Updated by {name} · {time}"` template into literal runs and the name /
 * time slots so ONLY the updater name truncates on a narrow row while the relative time stays
 * whole across every locale's word order (botiverse/slock #15693).
 */
internal fun splitChannelAnnouncementMetaTemplate(
    template: String,
    nameToken: String = ChannelAnnouncementMetaNameToken,
    timeToken: String = ChannelAnnouncementMetaTimeToken,
): List<ChannelAnnouncementMetaPart> {
    val parts = mutableListOf<ChannelAnnouncementMetaPart>()
    var cursor = 0
    while (cursor < template.length) {
        val nextName = template.indexOf(nameToken, cursor)
        val nextTime = template.indexOf(timeToken, cursor)
        val (matchIndex, tokenLength, part) =
            when {
                nextName >= 0 && (nextTime < 0 || nextName < nextTime) ->
                    Triple(nextName, nameToken.length, ChannelAnnouncementMetaPart.Name)
                nextTime >= 0 ->
                    Triple(nextTime, timeToken.length, ChannelAnnouncementMetaPart.Time)
                else -> {
                    parts += ChannelAnnouncementMetaPart.Literal(template.substring(cursor))
                    break
                }
            }
        if (matchIndex > cursor) {
            parts += ChannelAnnouncementMetaPart.Literal(template.substring(cursor, matchIndex))
        }
        parts += part
        cursor = matchIndex + tokenLength
    }
    return parts
}

@Composable
private fun ChannelAnnouncementDetailsMetaSubtitle(
    authorName: String,
    timeLabel: String,
) {
    val template =
        stringResource(
            Res.string.channel_announcement_details_meta,
            ChannelAnnouncementMetaNameToken,
            ChannelAnnouncementMetaTimeToken,
        )
    val parts = remember(template) { splitChannelAnnouncementMetaTemplate(template) }
    val activeParts =
        remember(parts, timeLabel) {
            if (timeLabel.isNotBlank()) {
                parts
            } else {
                parts.filterNot { part ->
                    part == ChannelAnnouncementMetaPart.Time ||
                        (part is ChannelAnnouncementMetaPart.Literal && part.text.contains('·'))
                }
            }
        }
    val style = RaftTheme.typography.monoMeta
    val color = LocalRaftListRowInks.current?.ink2 ?: RaftTheme.colors.foregroundMuted
    // Measure Time first so the relative timestamp always stays whole, then reserve literals,
    // and let Name take the remaining width with ellipsis (botiverse/slock #15693).
    Layout(
        modifier = Modifier.fillMaxWidth().testTag(ChannelAnnouncementTestTags.DetailsMeta),
        content = {
            activeParts.forEach { part ->
                when (part) {
                    is ChannelAnnouncementMetaPart.Literal ->
                        RaftText(
                            text = part.text,
                            style = style,
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    ChannelAnnouncementMetaPart.Name ->
                        RaftText(
                            text = authorName,
                            modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsMetaName),
                            style = style,
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    ChannelAnnouncementMetaPart.Time ->
                        RaftText(
                            text = timeLabel,
                            modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsMetaTime),
                            style = style,
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                        )
                }
            }
        },
    ) { measurables, constraints ->
        val maxWidth = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        var remaining = maxWidth
        // 1. Measure Time first at full width so it is never clipped.
        activeParts.forEachIndexed { index, part ->
            if (part == ChannelAnnouncementMetaPart.Time) {
                val p = measurables[index].measure(loose)
                placeables[index] = p
                remaining = (remaining - p.width).coerceAtLeast(0)
            }
        }
        // 2. Measure separator ("·") literals next so they stay intact beside Time.
        activeParts.forEachIndexed { index, part ->
            if (part is ChannelAnnouncementMetaPart.Literal && part.text.contains('·')) {
                val p = measurables[index].measure(loose.copy(maxWidth = remaining))
                placeables[index] = p
                remaining = (remaining - p.width).coerceAtLeast(0)
            }
        }
        // 3. Measure remaining literals, keeping at least a small reserve for Name on ultra-narrow rows.
        val hasName = activeParts.any { it == ChannelAnnouncementMetaPart.Name }
        val minNameReserve = if (hasName) 32.dp.roundToPx() else 0
        activeParts.forEachIndexed { index, part ->
            if (placeables[index] == null && part is ChannelAnnouncementMetaPart.Literal) {
                val cap = if (remaining > minNameReserve) remaining - minNameReserve else remaining
                val p = measurables[index].measure(loose.copy(maxWidth = cap.coerceAtLeast(0)))
                placeables[index] = p
                remaining = (remaining - p.width).coerceAtLeast(0)
            }
        }
        // 4. Measure Name in whatever width remains (truncates with ellipsis).
        activeParts.forEachIndexed { index, part ->
            if (placeables[index] == null && part == ChannelAnnouncementMetaPart.Name) {
                val p = measurables[index].measure(loose.copy(maxWidth = remaining))
                placeables[index] = p
                remaining = (remaining - p.width).coerceAtLeast(0)
            }
        }
        val measured = placeables.map { requireNotNull(it) }
        val totalWidth = measured.sumOf { it.width }.coerceIn(constraints.minWidth, maxWidth)
        val totalHeight = (measured.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(totalWidth, totalHeight) {
            var x = 0
            measured.forEach { placeable ->
                placeable.placeRelative(x, (totalHeight - placeable.height) / 2)
                x += placeable.width
            }
        }
    }
}

/**
 * Channel details › Pinned content, after the members (web `ChannelAnnouncementField` +
 * `PinnedArtifactRow`, botiverse/slock #15659 #15693):
 * a section heading with the pin icon above one box:
 * - pinned: one clickable row with the Artifact kind's 16dp icon, title on one line,
 *   "Updated by {name} · {time}" (only the name truncates), and chevron (for editors, ⋯ with
 *   Replace pin and Unpin sits before the chevron);
 * - nothing pinned: for editors, one row with muted "Nothing pinned" and "Pin an Artifact";
 *   nothing at all for anyone else, nor before the first read.
 */
@Composable
internal fun ChannelAnnouncementDetailsRow(
    slice: ChannelAnnouncementSlice,
    canEdit: Boolean,
    onOpen: () -> Unit,
    onSet: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    artifactKind: ArtifactKind = ArtifactKind.Markdown,
) {
    if (!slice.read) return
    val announcement = slice.announcement
    if (announcement == null && !canEdit) return
    Column(
        modifier = modifier.fillMaxWidth().testTag(ChannelAnnouncementTestTags.Details),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RaftSectionEyebrow(
            text = stringResource(Res.string.channel_announcement_label),
            icon = { RaftComputersIcon(RaftLucideIconName.Pin, size = 16.dp) },
        )
        RaftListRowGroup(Modifier.fillMaxWidth()) {
            if (announcement != null) {
                val authorName = channelAnnouncementAuthorName(announcement.updatedBy.name)
                val timeLabel = raftShortRelativeTimestampLabel(announcement.updatedAt)
                if (canEdit) {
                    ChannelAnnouncementDetailsItemWithActions(
                        title = announcement.title,
                        authorName = authorName,
                        timeLabel = timeLabel,
                        artifactKind = artifactKind,
                        onOpen = onOpen,
                        onReplace = onReplace,
                        onRemove = onRemove,
                    )
                } else {
                    RaftListRow(
                        title = announcement.title,
                        modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsItem),
                        onClick = onOpen,
                        variant = RaftListRowVariant.Plain,
                        subtitleContent = {
                            ChannelAnnouncementDetailsMetaSubtitle(authorName = authorName, timeLabel = timeLabel)
                        },
                        leading = { RaftSlotIcon(artifactKind.lucideIcon(), size = 16.dp) },
                        trailing = { RaftListRowChevron() },
                    )
                }
            } else if (slice.unavailable != null) {
                // Web PinnedUnavailableRow (slock #16305): joint channel where a sharing server has
                // pinning off — show why instead of offering Pin an Artifact.
                RaftListRow(
                    title = stringResource(Res.string.channel_announcement_details_empty_title),
                    modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsUnavailable),
                    variant = RaftListRowVariant.Plain,
                    tone = RaftRowTone.Muted,
                    titleMaxLines = Int.MAX_VALUE,
                    description = stringResource(Res.string.channel_announcement_details_unavailable_joint),
                    wrapDescription = true,
                )
            } else {
                RaftListRow(
                    title = stringResource(Res.string.channel_announcement_details_empty_title),
                    modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsEmpty),
                    variant = RaftListRowVariant.Plain,
                    tone = RaftRowTone.Muted,
                    titleMaxLines = Int.MAX_VALUE,
                    trailing = {
                        RaftButton(
                            text = stringResource(Res.string.channel_announcement_details_set),
                            onClick = onSet,
                            modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsSet),
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                        )
                    },
                )
            }
        }
    }
}

/**
 * The pinned Artifact row for who may change it (web `PinnedArtifactRow` with `actions`, slock
 * #15659): the whole row opens the Artifact; ⋯ (Replace pin, Unpin) sits right before the chevron
 * as a sibling button layered above the row, never inside the row's click target. The row reserves
 * space for ⋯ before the chevron so text never runs under it, and keeps its hover fill while the
 * menu is open.
 */
@Composable
private fun ChannelAnnouncementDetailsItemWithActions(
    title: String,
    authorName: String,
    timeLabel: String,
    artifactKind: ArtifactKind,
    onOpen: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = RaftTheme.colors
    val actionsRoom = ChannelAnnouncementActionsRoom
    var open by remember { mutableStateOf(false) }
    val actionsLabel = stringResource(Res.string.channel_announcement_details_actions)
    Box(Modifier.fillMaxWidth()) {
        RaftListRow(
            title = title,
            modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsItem),
            onClick = onOpen,
            variant = RaftListRowVariant.Plain,
            subtitleContent = {
                ChannelAnnouncementDetailsMetaSubtitle(authorName = authorName, timeLabel = timeLabel)
            },
            leading = { RaftSlotIcon(artifactKind.lucideIcon(), size = 16.dp) },
            // Reserve room for ⋯ before the chevron so title and subtitle end before it.
            trailing = {
                Spacer(Modifier.width(actionsRoom))
                RaftListRowChevron()
            },
            menuOpen = open,
        )
        Box(Modifier.align(Alignment.CenterEnd).padding(end = ChannelAnnouncementActionsEndOffset)) {
            RaftIconButton(
                onClick = { open = true },
                tooltip = actionsLabel,
                modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsActions),
                variant = RaftButtonVariant.Ghost,
                // Web `size="icon-sm"`: the row action, 28dp on every platform (raft-ui 0.103).
                size = RaftIconButtonSize.Sm,
            ) {
                RaftLucideIcon(RaftLucideIconName.Ellipsis, Modifier.size(16.dp), color = raftArgbHex(colors.foregroundMuted))
            }
            RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, alignEnd = true) {
                RaftMenuItem(
                    text = stringResource(Res.string.channel_announcement_details_replace),
                    onClick = {
                        open = false
                        onReplace()
                    },
                    modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsReplace),
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Replace) },
                )
                RaftMenuItem(
                    text = stringResource(Res.string.channel_announcement_details_remove),
                    onClick = {
                        open = false
                        onRemove()
                    },
                    modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsRemove),
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.PinOff) },
                )
            }
        }
    }
}

/** Width of the ⋯ row action ([RaftIconButtonSize.Sm], 28dp) reserved inside `trailing` before the chevron. */
private val ChannelAnnouncementActionsRoom = 28.dp

/** End offset of ⋯: row end padding (16dp) + chevron (20dp) + trailing gap (6dp). */
private val ChannelAnnouncementActionsEndOffset = 42.dp

/** A refused or failed change, in the dialog: the server's sentence (else the generic one) and the trace id. */
@Composable
private fun ChannelAnnouncementErrorNote(error: ScreenError) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(
            description = error.serverMessage ?: stringResource(Res.string.error_generic),
            status = RaftBannerStatus.Warning,
        )
        RaftTraceIdNote(error.traceId)
    }
}

/**
 * Pin / Replace pin: one of the channel's own Artifacts (a Dialog md with a ChoiceList), the
 * currently pinned Artifact left out. Pin confirms here; Replace pin goes on to its confirmation.
 */
@Composable
internal fun ChannelAnnouncementPickerDialog(
    replacing: Boolean,
    channelName: String,
    view: ChannelAnnouncementPickerView,
    running: Boolean,
    error: ScreenError?,
    onRetry: () -> Unit,
    onShowMore: () -> Unit,
    onConfirm: (ChannelAnnouncementChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    var chosen by remember { mutableStateOf<String?>(null) }
    val choices = (view as? ChannelAnnouncementPickerView.Ready)?.choices.orEmpty()
    val chosenChoice = choices.firstOrNull { it.artifactId == chosen }
    RaftDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title =
            stringResource(
                if (replacing) Res.string.channel_announcement_picker_replace_title else Res.string.channel_announcement_picker_set_title
            ),
        size = RaftDialogSize.Md,
        footer = {
            RaftButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss, variant = RaftButtonVariant.Ghost, enabled = !running)
            RaftButton(
                text =
                    stringResource(
                        if (replacing) Res.string.channel_announcement_picker_next else Res.string.channel_announcement_details_set
                    ),
                onClick = { chosenChoice?.let(onConfirm) },
                modifier = Modifier.testTag(ChannelAnnouncementTestTags.PickerConfirm),
                variant = RaftButtonVariant.Primary,
                enabled = chosenChoice != null,
                loading = running,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftText(stringResource(Res.string.channel_announcement_picker_lead, channelName), style = typography.subhead, color = colors.foregroundMuted)
            when (view) {
                ChannelAnnouncementPickerView.Loading ->
                    RaftText(stringResource(Res.string.channel_announcement_picker_loading), style = typography.callout, color = colors.foregroundHint)
                ChannelAnnouncementPickerView.Failed ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RaftText(stringResource(Res.string.channel_announcement_picker_failed), style = typography.callout, color = colors.foregroundMuted)
                        RaftButton(text = stringResource(Res.string.channel_announcement_picker_retry), onClick = onRetry, variant = RaftButtonVariant.Default)
                    }
                is ChannelAnnouncementPickerView.Ready -> {
                    RaftChoiceList(
                        items =
                            view.choices.map { choice ->
                                RaftChoiceListItem(
                                    value = choice.artifactId,
                                    label = choice.title,
                                    descriptions = listOf(androidx.compose.ui.text.AnnotatedString(choice.meta)),
                                    searchText = choice.title,
                                    media = {
                                        RaftSlotIcon(
                                            choice.kind.lucideIcon(),
                                            size = 16.dp,
                                            color = if (running) colors.foregroundDisabled else colors.foregroundMuted,
                                        )
                                    },
                                )
                            },
                        value = chosen,
                        onValueChange = { chosen = it },
                        searchPlaceholder = stringResource(Res.string.channel_announcement_picker_search),
                        clearSearchLabel = stringResource(Res.string.channel_announcement_picker_clear_search),
                        noMatchLabel = stringResource(Res.string.channel_announcement_picker_no_match),
                        emptyLabel = stringResource(Res.string.channel_announcement_picker_empty, channelName),
                        enabled = !running,
                    )
                    if (view.hasMore) {
                        RaftButton(
                            text = stringResource(Res.string.thread_files_artifacts_show_more),
                            onClick = onShowMore,
                            variant = RaftButtonVariant.Ghost,
                            loading = view.loadingMore,
                        )
                    }
                }
            }
            error?.let { ChannelAnnouncementErrorNote(it) }
        }
    }
}

/**
 * Replace and Remove confirm in a Dialog sm, Cancel first, then the confirm at `primary` (not
 * `danger`: nothing is deleted, the Artifact stays in the channel and can be set again).
 */
@Composable
internal fun ChannelAnnouncementConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    confirmTag: String,
    running: Boolean,
    error: ScreenError?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    RaftDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = title,
        size = RaftDialogSize.Sm,
        footer = {
            RaftButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss, variant = RaftButtonVariant.Ghost, enabled = !running)
            RaftButton(
                text = confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.testTag(confirmTag),
                variant = RaftButtonVariant.Primary,
                loading = running,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftText(body, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
            error?.let { ChannelAnnouncementErrorNote(it) }
        }
    }
}
