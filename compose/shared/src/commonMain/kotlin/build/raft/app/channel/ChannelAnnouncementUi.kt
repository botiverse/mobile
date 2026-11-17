package build.raft.app

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ChannelAnnouncementChangeKind
import build.raft.app.data.ChannelAnnouncementNotice
import build.raft.app.data.ChannelAnnouncementSlice
import build.raft.app.data.RaftChannelAnnouncement
import build.raft.app.data.ScreenError
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
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
    const val DetailsEmpty = "channel-settings-announcement-empty"
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
            Modifier.fillMaxWidth().height(40.dp).padding(end = if (wide) 12.dp else 8.dp),
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
            ChannelAnnouncementStripMenu(onCopyLink = onCopyLink, onOpenSettings = onOpenSettings)
        }
    }
}

@Composable
private fun ChannelAnnouncementStripMenu(onCopyLink: () -> Unit, onOpenSettings: (() -> Unit)?) {
    val colors = RaftTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        RaftIconButton(
            onClick = { open = true },
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

// ---- Channel details › Announcement --------------------------------------------------------------

/** One of the channel's Artifacts, as the picker lists it. */
internal data class ChannelAnnouncementChoice(val artifactId: String, val title: String, val meta: String)

/** What the picker shows. */
internal sealed interface ChannelAnnouncementPickerView {
    data object Loading : ChannelAnnouncementPickerView

    data object Failed : ChannelAnnouncementPickerView

    data class Ready(val choices: List<ChannelAnnouncementChoice>, val hasMore: Boolean, val loadingMore: Boolean) : ChannelAnnouncementPickerView
}

/**
 * The Announcement row of the channel details, after the members (web `ChannelAnnouncementField`):
 * with an announcement, the Artifact itself (opens it, with a chevron); for who may change it, a ⋯
 * in the chevron's place whose menu has Replace and Remove (slock #14461, raft-design #1212); without, for who may set one, a line on what it is for and Set announcement;
 * nothing for anyone else, nor before the first read.
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
) {
    if (!slice.read) return
    val announcement = slice.announcement
    if (announcement == null && !canEdit) return
    RaftListRowGroup(modifier.testTag(ChannelAnnouncementTestTags.Details)) {
        if (announcement != null) {
            val meta =
                stringResource(
                    Res.string.channel_announcement_details_meta,
                    announcement.version.toString(),
                    channelAnnouncementAuthorName(announcement.updatedBy.name),
                    raftRelativeTimestampLabel(announcement.updatedAt),
                )
            if (canEdit) {
                ChannelAnnouncementDetailsItemWithActions(
                    title = announcement.title,
                    meta = meta,
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
                    subtitle = meta,
                    leading = { RaftSlotIcon(RaftLucideIconName.Megaphone, size = 20.dp) },
                    trailing = { RaftListRowChevron() },
                )
            }
        } else {
            RaftListRow(
                title = stringResource(Res.string.channel_announcement_details_empty_title),
                modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsEmpty),
                variant = RaftListRowVariant.Plain,
                description = stringResource(Res.string.channel_announcement_details_empty_body),
                wrapDescription = true,
                leading = { RaftSlotIcon(RaftLucideIconName.Megaphone, size = 20.dp) },
            )
            // On a phone the button goes under the line (web: at its end, which leaves the text a few
            // words a line at 390dp), in a row that belongs to it.
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                RaftButton(
                    text = stringResource(Res.string.channel_announcement_details_set),
                    onClick = onSet,
                    modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsSet),
                    variant = RaftButtonVariant.Default,
                )
            }
        }
    }
}

/**
 * The announcement row for who may change it (web `AnnouncementItem` with `actions`, slock #14461):
 * the row opens the announcement; ⋯ takes the chevron's place, on top of the row's end as a button
 * of its own, never inside the row's target (raft-ui InboxItem's pattern). The row keeps room for
 * it so its text never runs under it, and keeps its hover fill while the menu is open. The menu has
 * Replace and Remove, neither coloured: the confirmation carries the consequence (DropdownMenu's rule).
 */
@Composable
private fun ChannelAnnouncementDetailsItemWithActions(
    title: String,
    meta: String,
    onOpen: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = RaftTheme.colors
    var open by remember { mutableStateOf(false) }
    val actionsLabel = stringResource(Res.string.channel_announcement_details_actions)
    Box(Modifier.fillMaxWidth()) {
        RaftListRow(
            title = title,
            modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsItem),
            onClick = onOpen,
            variant = RaftListRowVariant.Plain,
            subtitle = meta,
            leading = { RaftSlotIcon(RaftLucideIconName.Megaphone, size = 20.dp) },
            // Room for ⋯ (web `pe-14`), so the title and the line under it end before it.
            trailing = { Spacer(Modifier.width(ChannelAnnouncementActionsRoom)) },
            menuOpen = open,
        )
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
            RaftIconButton(
                onClick = { open = true },
                tooltip = actionsLabel,
                modifier = Modifier.testTag(ChannelAnnouncementTestTags.DetailsActions),
                variant = RaftButtonVariant.Ghost,
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
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.X) },
                )
            }
        }
    }
}

/** What the row's end keeps clear for ⋯: the button (32) past the row's own end padding, plus a gap. */
private val ChannelAnnouncementActionsRoom = 32.dp

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
 * Set / Replace: one of the channel's own Artifacts (a Dialog md with a ChoiceList), the current
 * announcement left out. Set confirms here; Replace goes on to its confirmation ([onNext]).
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
