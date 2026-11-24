package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSettled
import build.raft.app.data.ArtifactsHomeActor
import build.raft.app.data.ArtifactsHomeDateGroupLabel
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.ArtifactsHomeScope
import build.raft.app.data.ArtifactsHomeStatusFilter
import build.raft.app.data.ArtifactsHomeView
import build.raft.app.data.artifactDateTimestamp
import build.raft.app.data.canToggleFollow
import build.raft.app.markdown.lucideIcon
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.floorMod
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowText
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/*
 * Compact grid tiles, thumbnail list rows, group headings, settled strip, badges, skeleton, and
 * context menu for the Artifacts home page (web `ArtifactTile.tsx`, `ArtifactDashboardRows.tsx`,
 * `ArtifactCardBadges.tsx`, `ArtifactHomeGroupHeads.tsx`, `ArtifactDashboardSkeleton.tsx`,
 * `ArtifactHomeMenu.tsx`; botiverse/slock #15654, #16137, #16182, #16290).
 */

/** Shared grayscale filter for cover/thumbnail images of dimmed settled cards. */
internal val ArtifactsHomeGrayscaleFilter: ColorFilter =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** web `ArtifactListRow` thumbnail width (`w-[76px]`, 16:10 aspect ratio). */
internal val ArtifactsHomeThumbnailWidth: Dp = 76.dp

/**
 * Collapsible "For you" section heading (web `ArtifactForYouHead`, slock #16290):
 * regular section heading (`footnote` bold `foregroundHint`) with an accent count `RaftBadge` and
 * chevron, aligned on the page's column margin — no bordered container box, no helper tagline.
 */
@Composable
internal fun ArtifactForYouHead(
    count: Int,
    open: Boolean,
    onToggle: () -> Unit,
    horizontalPadding: Dp,
) {
    val shownCount = if (count > 99) "99+" else "$count"
    val forYouLabel = stringResource(Res.string.artifacts_dashboard_group_for_you)
    val toggleLabel = stringResource(if (open) Res.string.thread_side_card_collapse else Res.string.thread_side_card_expand, forYouLabel)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = horizontalPadding, end = horizontalPadding, top = 12.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = 24.dp)
                    .clip(RoundedCornerShape(RaftRadius.Sticker))
                    .raftClickable(onClick = onToggle, role = Role.Button)
                    .testTag(ArtifactsHomeTags.ForYouHead)
                    .semantics {
                        heading()
                        stateDescription = toggleLabel
                    },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftText(
                forYouLabel,
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
                color = RaftTheme.colors.foregroundHint,
                maxLines = 1,
            )
            RaftBadge(
                text = shownCount,
                variant = RaftBadgeVariant.Accent,
                modifier = Modifier.testTag(ArtifactsHomeTags.ForYouCount),
            )
            RaftLucideIcon(
                if (open) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
                modifier = Modifier.size(14.dp),
                color = raftArgbHex(RaftTheme.colors.foregroundHint),
            )
        }
    }
}

/** Date group heading ("Today", "Yesterday", "This week", "Last week", "Earlier", or weekday + date). */
@Composable
internal fun ArtifactDateGroupHead(
    label: ArtifactsHomeDateGroupLabel,
    horizontalPadding: Dp,
) {
    RaftText(
        text = artifactsHomeDateGroupLabelText(label),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = horizontalPadding, end = horizontalPadding, top = 12.dp, bottom = 0.dp)
                .testTag(ArtifactsHomeTags.DateGroupHeading)
                .semantics { heading() },
        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun artifactsHomeDateGroupLabelText(label: ArtifactsHomeDateGroupLabel): String =
    when (label) {
        ArtifactsHomeDateGroupLabel.Today -> stringResource(Res.string.artifacts_dashboard_group_today)
        ArtifactsHomeDateGroupLabel.Yesterday -> stringResource(Res.string.artifacts_dashboard_group_yesterday)
        ArtifactsHomeDateGroupLabel.ThisWeek -> stringResource(Res.string.artifacts_dashboard_group_this_week)
        ArtifactsHomeDateGroupLabel.LastWeek -> stringResource(Res.string.artifacts_dashboard_group_last_week)
        ArtifactsHomeDateGroupLabel.Earlier -> stringResource(Res.string.artifacts_dashboard_group_earlier)
        is ArtifactsHomeDateGroupLabel.WeekdayDate -> {
            val date = civilDateFromEpochDay(label.epochDay)
            val weekday = stringResource(artifactsHomeShortWeekdayResource(label.epochDay))
            val monthDay = stringResource(Res.string.settings_compact_month_day, raftLocalizedShortMonth(date.month), date.day)
            stringResource(Res.string.artifacts_dashboard_group_weekday_date, weekday, monthDay)
        }
    }

private fun artifactsHomeShortWeekdayResource(epochDay: Long) =
    ArtifactsHomeShortWeekdays[floorMod(epochDay + 4L, 7L).toInt()]

private val ArtifactsHomeShortWeekdays =
    listOf(
        Res.string.agent_usage_weekday_short_sun,
        Res.string.agent_usage_weekday_short_mon,
        Res.string.agent_usage_weekday_short_tue,
        Res.string.agent_usage_weekday_short_wed,
        Res.string.agent_usage_weekday_short_thu,
        Res.string.agent_usage_weekday_short_fri,
        Res.string.agent_usage_weekday_short_sat,
    )

private const val SettledStripSentinel = "\u0000"

/**
 * Inline quiet settled strip (web `ArtifactSettledStrip`, slock #16137):
 * `{count} settled hidden · Show` (switches status to All) or
 * `{count} settled shown dimmed · Hide` (switches status back to Unsettled).
 * Omitted when settled is off, count is 0, or status is Settled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtifactSettledStrip(
    status: ArtifactsHomeStatusFilter,
    hiddenCount: Int,
    dimmedCount: Int,
    horizontalPadding: Dp,
    onStatusChange: (ArtifactsHomeStatusFilter) -> Unit,
) {
    val mode =
        when {
            status == ArtifactsHomeStatusFilter.Unsettled && hiddenCount > 0 -> "hidden"
            status == ArtifactsHomeStatusFilter.All && dimmedCount > 0 -> "dimmed"
            else -> return
        }
    val template =
        if (mode == "hidden") {
            stringResource(Res.string.artifacts_dashboard_settled_hidden_note, hiddenCount, SettledStripSentinel)
        } else {
            stringResource(Res.string.artifacts_dashboard_settled_dimmed_note, dimmedCount, SettledStripSentinel)
        }
    val actionLabel =
        stringResource(
            if (mode == "hidden") Res.string.artifacts_dashboard_settled_hidden_action else Res.string.artifacts_dashboard_settled_dimmed_action,
        )
    val nextStatus = if (mode == "hidden") ArtifactsHomeStatusFilter.All else ArtifactsHomeStatusFilter.Unsettled
    val parts = template.split(SettledStripSentinel, limit = 2)
    val before = parts.getOrElse(0) { template }
    val after = parts.getOrElse(1) { "" }
    val style = RaftTheme.typography.caption
    val colors = RaftTheme.colors
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = horizontalPadding, end = horizontalPadding, top = 8.dp, bottom = 0.dp)
                .testTag(ArtifactsHomeTags.SettledStrip),
        verticalArrangement = Arrangement.Center,
    ) {
        if (before.isNotEmpty()) {
            RaftText(before, style = style, color = colors.foregroundHint)
        }
        RaftTextLink(
            text = actionLabel,
            onClick = { onStatusChange(nextStatus) },
            modifier = Modifier.testTag(ArtifactsHomeTags.SettledStripAction),
            variant = RaftTextLinkVariant.Strong,
            style = style.copy(color = colors.foregroundLink, fontWeight = FontWeight.Medium),
        )
        if (after.isNotEmpty()) {
            RaftText(after, style = style, color = colors.foregroundHint)
        }
    }
}

/**
 * Compact grid tile (web `ArtifactTile`, slock #16137, #16357, #16418):
 * - 16:10 cover with overlays: top-right Unfollowed (under Relevant to me) + Settled + Public
 *   (only when `home == Public`), bottom-left Mentions you / Updated pills, bottom-right combined
 *   comment badge.
 * - Below cover: 2-line bold title (no version badge, no standalone time line) +
 *   single meta line `Avatar + Author · [Home icon] Home`.
 * - Settled tiles under All and unfollowed tiles under Relevant to me are visually dimmed
 *   (grayscale image + `opacityDimmed`).
 * - The tile open in the wide split viewer shows the selected cell fill and strong hairline border.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtifactsHomeTile(
    row: ArtifactsHomeRow,
    readerId: String?,
    serverName: String?,
    byOpened: Boolean,
    dimmed: Boolean,
    settledEnabled: Boolean,
    settling: Boolean,
    onOpen: () -> Unit,
    onCopyLink: (() -> Unit)?,
    onSetSettled: ((Boolean) -> Unit)?,
    current: Boolean = false,
    unfollowed: Boolean = false,
    followingPending: Boolean = false,
    onSetFollowing: ((Boolean) -> Unit)? = null,
    cover: @Composable (ArtifactsHomeRow, Boolean) -> Unit,
) {
    val colors = RaftTheme.colors
    val label = artifactsHomeOpenLabel(row, readerId, serverName, byOpened)
    val shape = RoundedCornerShape(RaftRadius.Container)
    var menuAnchor by remember { mutableStateOf<IntOffset?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val hasTopRight = unfollowed || row.summary.settled != null || row.summary.home == ArtifactHome.Public
    val hasBottomLeft = row.news.mentioned || row.news.newerVersion
    val hasBottomRight = row.summary.commentCount > 0 || row.news.newComments > 0

    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { boxSize = it.size }
            .artifactHomeContextMenuGestures(
                enabled = true,
                cardSize = { boxSize },
                onOpenMenu = { menuAnchor = it },
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (dimmed) Modifier.alpha(RaftTheme.sizes.opacityDimmed) else Modifier)
                .clip(shape)
                .background(if (current) colors.fillCellSelected else colors.layerCard, shape)
                .border(RaftStroke.Hairline, if (current) colors.lineStrong else colors.lineSeam, shape)
                .testTag(ArtifactsHomeTags.Tile)
                .raftClickable(onClick = onOpen)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    selected = current
                    onClick {
                        onOpen()
                        true
                    }
                },
        ) {
            Box(Modifier.fillMaxWidth()) {
                cover(row, dimmed)
                if (hasTopRight) {
                    FlowRow(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (unfollowed) ArtifactUnfollowedBadge(onCover = true)
                        row.summary.settled?.let { ArtifactCoverSettledBadge(it, readerId) }
                        if (row.summary.home == ArtifactHome.Public) ArtifactPublicBadge(onCover = true)
                    }
                }
                if (hasBottomLeft) {
                    FlowRow(
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ArtifactNewsPills(row.news)
                    }
                }
                if (hasBottomRight) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                        ArtifactCombinedCommentBadge(
                            commentCount = row.summary.commentCount,
                            newComments = row.news.newComments,
                            onCover = true,
                        )
                    }
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftText(
                    artifactsHomeShownTitle(row.summary.title),
                    modifier = Modifier.fillMaxWidth().testTag(ArtifactsHomeTags.TileTitle),
                    style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(ArtifactsHomeTags.TileMeta),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ArtifactsHomeAuthorLine(row.author, readerId, Modifier.weight(1f, fill = false))
                    RaftText("·", style = RaftTheme.typography.footnote, color = colors.foregroundHint, maxLines = 1)
                    ArtifactHomeInline(row, serverName, Modifier.weight(1f))
                }
            }
        }
        if (menuAnchor != null) {
            ArtifactsHomeRowMenu(
                anchor = menuAnchor!!,
                following = row.summary.following,
                showFollow = row.summary.canToggleFollow() && onSetFollowing != null,
                followingPending = followingPending,
                settled = row.summary.settled != null,
                showSettle = settledEnabled && row.summary.settledShown && onSetSettled != null,
                settling = settling,
                onDismiss = { menuAnchor = null },
                onOpen = onOpen,
                onCopyLink = onCopyLink,
                onSetFollowing = onSetFollowing,
                onSetSettled = onSetSettled,
            )
        }
    }
}

/**
 * Thumbnail list row (web `ArtifactListRow`, slock #16137 + #16290 + #16357 + #16418):
 * - Built on [RaftListRow] (`variant = Entry`, `divider = false`, full-row hit target, 16dp edge,
 *   `selected = current` when open in the wide split viewer).
 * - 76dp 16:10 thumbnail (cover image, scaled excerpt, or kind glyph placeholder).
 * - Single-line bold title + single meta line `Avatar + Author · [Home icon] Home · Relative time`.
 * - Badges: Unfollowed (under Relevant to me), Mentions you / Updated, combined comment badge,
 *   Settled, Public (only on public home) — trailing on wide containers, wrapped under the meta
 *   line on narrower containers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtifactsHomeListRow(
    row: ArtifactsHomeRow,
    readerId: String?,
    serverName: String?,
    byOpened: Boolean,
    wide: Boolean,
    dimmed: Boolean,
    settledEnabled: Boolean,
    settling: Boolean,
    onOpen: () -> Unit,
    onCopyLink: (() -> Unit)?,
    onSetSettled: ((Boolean) -> Unit)?,
    current: Boolean = false,
    unfollowed: Boolean = false,
    followingPending: Boolean = false,
    onSetFollowing: ((Boolean) -> Unit)? = null,
    thumbnail: @Composable (ArtifactsHomeRow, Boolean) -> Unit,
) {
    val colors = RaftTheme.colors
    val label = artifactsHomeOpenLabel(row, readerId, serverName, byOpened)
    val time = raftRelativeTimestampLabel(artifactDateTimestamp(row, byOpened))
    val hasBadges =
        unfollowed ||
            row.news.mentioned ||
            row.news.newerVersion ||
            row.summary.commentCount > 0 ||
            row.news.newComments > 0 ||
            row.summary.settled != null ||
            row.summary.home == ArtifactHome.Public
    var menuAnchor by remember { mutableStateOf<IntOffset?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    val badgesContent: @Composable () -> Unit = {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.testTag(ArtifactsHomeTags.ListBadges),
        ) {
            if (unfollowed) ArtifactUnfollowedBadge(onCover = false)
            ArtifactNewsPills(row.news)
            ArtifactCombinedCommentBadge(
                commentCount = row.summary.commentCount,
                newComments = row.news.newComments,
                onCover = false,
            )
            row.summary.settled?.let { ArtifactSettledTag(it, readerId) }
            if (row.summary.home == ArtifactHome.Public) ArtifactPublicBadge(onCover = false)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { boxSize = it.size }
            .artifactHomeContextMenuGestures(
                enabled = true,
                cardSize = { boxSize },
                onOpenMenu = { menuAnchor = it },
            ),
    ) {
        RaftListRow(
            title = artifactsHomeShownTitle(row.summary.title),
            modifier =
                Modifier
                    .then(if (dimmed) Modifier.alpha(RaftTheme.sizes.opacityDimmed) else Modifier)
                    .testTag(ArtifactsHomeTags.Row)
                    .semantics { contentDescription = label },
            onClick = onOpen,
            variant = RaftListRowVariant.Entry,
            divider = false,
            selected = current,
            menuOpen = menuAnchor != null,
            text = RaftListRowText.Member,
            leading = { thumbnail(row, dimmed) },
            subtitleContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag(ArtifactsHomeTags.ListMeta),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ArtifactsHomeAuthorLine(row.author, readerId, Modifier.weight(1f, fill = false))
                        RaftText("·", style = RaftTheme.typography.footnote, color = colors.foregroundHint, maxLines = 1)
                        ArtifactHomeInline(row, serverName, Modifier.weight(1f, fill = false))
                        if (time.isNotBlank()) {
                            RaftText("·", style = RaftTheme.typography.footnote, color = colors.foregroundHint, maxLines = 1)
                            RaftText(
                                time,
                                modifier = Modifier.testTag(ArtifactsHomeTags.ListTime),
                                style = RaftTheme.typography.monoMeta,
                                color = colors.foregroundHint,
                                maxLines = 1,
                            )
                        }
                    }
                    if (!wide && hasBadges) badgesContent()
                }
            },
            trailing = if (wide && hasBadges) { { badgesContent() } } else null,
        )
        if (menuAnchor != null) {
            ArtifactsHomeRowMenu(
                anchor = menuAnchor!!,
                following = row.summary.following,
                showFollow = row.summary.canToggleFollow() && onSetFollowing != null,
                followingPending = followingPending,
                settled = row.summary.settled != null,
                showSettle = settledEnabled && row.summary.settledShown && onSetSettled != null,
                settling = settling,
                onDismiss = { menuAnchor = null },
                onOpen = onOpen,
                onCopyLink = onCopyLink,
                onSetFollowing = onSetFollowing,
                onSetSettled = onSetSettled,
            )
        }
    }
}

/** Pink accent pills for unread mentions and newer versions (comment counts live in [ArtifactCombinedCommentBadge]). */
@Composable
internal fun ArtifactNewsPills(news: ArtifactsHomeNews) {
    if (news.mentioned) {
        RaftBadge(
            text = stringResource(Res.string.artifacts_dashboard_mentioned),
            variant = RaftBadgeVariant.Accent,
            modifier = Modifier.testTag(ArtifactsHomeTags.MentionedPill),
        )
    }
    if (news.newerVersion) {
        RaftBadge(
            text = stringResource(Res.string.artifacts_dashboard_new_version),
            variant = RaftBadgeVariant.Accent,
            modifier = Modifier.testTag(ArtifactsHomeTags.NewVersionPill),
        )
    }
}

/**
 * Public-link badge shown ONLY when `home == ArtifactHome.Public` (web `ArtifactPublicBadge`,
 * slock #16137). [onCover] backs the soft warning tint with opaque `layerCard` for dark-mode legibility.
 */
@Composable
internal fun ArtifactPublicBadge(onCover: Boolean = false) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Chip)
    val badge: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier
                    .background(colors.warningSoft, shape)
                    .border(RaftStroke.Hairline, colors.warningMuted, shape)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .testTag(ArtifactsHomeTags.PublicBadge),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftLucideIcon(RaftLucideIconName.Globe, Modifier.size(12.dp), color = raftArgbHex(colors.warningStrong))
            RaftText(
                stringResource(Res.string.artifacts_dashboard_public_badge),
                style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.warningStrong,
                maxLines = 1,
            )
        }
    }
    if (onCover) {
        Box(Modifier.background(colors.layerCard, shape)) { badge() }
    } else {
        badge()
    }
}

/** Outline muted "Unfollowed" badge shown on unfollowed items under "Relevant to me" (web #16357). */
@Composable
internal fun ArtifactUnfollowedBadge(onCover: Boolean = false) {
    val shape = RoundedCornerShape(RaftRadius.Chip)
    val badge: @Composable () -> Unit = {
        RaftBadge(
            text = stringResource(Res.string.artifacts_follow_unfollowed),
            appearance = RaftBadgeAppearance.Outline,
            variant = RaftBadgeVariant.Muted,
            modifier = Modifier.testTag(ArtifactsHomeTags.UnfollowedTag),
        )
    }
    if (onCover) {
        Box(Modifier.background(RaftTheme.colors.layerCard, shape)) { badge() }
    } else {
        badge()
    }
}

/** Settled badge wrapper with opaque `layerCard` backing over cover images (web `ArtifactCoverSettledBadge`). */
@Composable
private fun ArtifactCoverSettledBadge(settled: ArtifactSettled, readerId: String?) {
    Box(Modifier.background(RaftTheme.colors.layerCard, RoundedCornerShape(RaftRadius.Chip))) {
        ArtifactSettledTag(settled, readerId)
    }
}

/**
 * Combined comment badge (web `ArtifactCombinedCommentBadge`, slock #16137):
 * - omitted when both `commentCount` and `newComments` are 0
 * - pink accent `💬 N · M new` when `newComments > 0`
 * - dark HUD pill on cover, muted inline count in list row when `newComments == 0`
 */
@Composable
internal fun ArtifactCombinedCommentBadge(
    commentCount: Int,
    newComments: Int,
    onCover: Boolean,
) {
    val total = maxOf(commentCount, newComments)
    if (total == 0) return
    val colors = RaftTheme.colors
    val totalLabel =
        if (total == 1) stringResource(Res.string.artifacts_card_comments_one) else stringResource(Res.string.artifacts_card_comments_many, total)
    if (newComments > 0) {
        val newLabel =
            if (newComments == 1) {
                stringResource(Res.string.artifacts_dashboard_new_comments_one, newComments)
            } else {
                stringResource(Res.string.artifacts_dashboard_new_comments_many, newComments)
            }
        val desc = stringResource(Res.string.artifacts_dashboard_comment_badge_new_label, totalLabel, newLabel)
        RaftBadge(
            text = stringResource(Res.string.artifacts_dashboard_comment_badge_new, total, newComments),
            variant = RaftBadgeVariant.Accent,
            icon = { RaftLucideIcon(RaftLucideIconName.MessageSquare, Modifier.fillMaxSize(), color = raftArgbHex(LocalRaftContentColor.current)) },
            modifier = Modifier.testTag(ArtifactsHomeTags.CommentBadge).semantics { contentDescription = desc },
        )
        return
    }
    if (onCover) {
        Row(
            modifier =
                Modifier
                    .background(colors.layerHud.copy(alpha = 0.8f), RoundedCornerShape(RaftRadius.Sticker))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag(ArtifactsHomeTags.CommentBadge)
                    .semantics(mergeDescendants = true) { contentDescription = totalLabel },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftLucideIcon(RaftLucideIconName.MessageSquare, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundOnHud))
            RaftText("$total", style = RaftTheme.typography.monoMeta, color = colors.foregroundOnHud, maxLines = 1)
        }
    } else {
        Row(
            modifier =
                Modifier
                    .padding(horizontal = 2.dp)
                    .testTag(ArtifactsHomeTags.CommentBadge)
                    .semantics(mergeDescendants = true) { contentDescription = totalLabel },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftLucideIcon(RaftLucideIconName.MessageSquare, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundMuted))
            RaftText("$total", style = RaftTheme.typography.monoMeta, color = colors.foregroundMuted, maxLines = 1)
        }
    }
}

/** Inline `[Home icon] Home name` segment for compact tile and list row meta lines (web `ArtifactHomeInline`). */
@Composable
internal fun ArtifactHomeInline(
    row: ArtifactsHomeRow,
    serverName: String?,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val home = row.summary.home
    val icon =
        when (home) {
            ArtifactHome.Server -> RaftLucideIconName.Users
            ArtifactHome.Public -> RaftLucideIconName.Globe
            is ArtifactHome.Conversation ->
                when {
                    home.private -> RaftLucideIconName.Lock
                    home.dm -> RaftLucideIconName.MessageSquare
                    else -> RaftLucideIconName.Hash
                }
        }
    val text =
        when (home) {
            ArtifactHome.Server -> artifactsHomeScopeLabel(ArtifactsHomeScope.Server, serverName)
            ArtifactHome.Public ->
                if (row.fromAnotherServer) {
                    stringResource(Res.string.artifacts_dashboard_public_from_another_server)
                } else {
                    stringResource(Res.string.artifacts_scope_public)
                }
            is ArtifactHome.Conversation ->
                when {
                    home.sharedChannelWithoutCopy -> stringResource(Res.string.artifacts_scope_shared_channel)
                    home.dm -> (home.peerDisplayName ?: home.name).ifBlank { "#" }
                    else -> home.name.ifBlank { "#" }
                }
        }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftLucideIcon(icon, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundHint))
        RaftText(
            text,
            style = RaftTheme.typography.footnote,
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 76dp 16:10 thumbnail placeholder (web `ArtifactCoverPicture` `variant="thumbnail"`). */
@Composable
internal fun ArtifactsHomeRowThumbnailPlaceholder(kind: ArtifactKind) {
    val colors = RaftTheme.colors
    ArtifactsHomeRowThumbnailFrame {
        RaftLucideIcon(kind.lucideIcon(), Modifier.size(18.dp), color = raftArgbHex(colors.foregroundMuted))
    }
}

@Composable
internal fun ArtifactsHomeRowThumbnailFrame(
    alignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Chip)
    Box(
        modifier =
            Modifier
                .width(ArtifactsHomeThumbnailWidth)
                .aspectRatio(16f / 10f)
                .clip(shape)
                .background(colors.fillMuted, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape),
        contentAlignment = alignment,
    ) {
        content()
    }
}

/** Loading skeleton matching either the compact grid tiles or thumbnail list rows (web `ArtifactDashboardSkeleton`). */
@Composable
internal fun ArtifactsHomeSkeleton(
    view: ArtifactsHomeView,
    wide: Boolean,
    grouped: Boolean,
    horizontalPadding: Dp,
) {
    val colors = RaftTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(ArtifactsHomeTags.Skeleton),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (grouped) {
            Box(Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 8.dp)) {
                RaftSkeleton(Modifier.width(80.dp), variant = RaftSkeletonVariant.Line)
            }
        }
        if (view == ArtifactsHomeView.Grid) {
            val columns = if (wide) 3 else 2
            val count = if (wide) 6 else 4
            val gap = if (wide) 12.dp else 8.dp
            Column(
                Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                for (rowStart in 0 until count step columns) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (col in 0 until columns) {
                            val shape = RoundedCornerShape(RaftRadius.Container)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .background(colors.layerCard, shape)
                                    .border(RaftStroke.Hairline, colors.lineSeam, shape),
                            ) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(colors.fillMuted))
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RaftSkeleton(Modifier.fillMaxWidth(0.75f), variant = RaftSkeletonVariant.Line)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        RaftSkeleton(Modifier.size(16.dp), variant = RaftSkeletonVariant.Circle)
                                        RaftSkeleton(Modifier.width(96.dp), variant = RaftSkeletonVariant.Line)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val rows = if (wide) 6 else 4
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(rows) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RaftSkeleton(
                            Modifier.width(ArtifactsHomeThumbnailWidth).aspectRatio(16f / 10f).clip(RoundedCornerShape(RaftRadius.Chip)),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            RaftSkeleton(Modifier.fillMaxWidth(0.6f), variant = RaftSkeletonVariant.Line)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RaftSkeleton(Modifier.size(16.dp), variant = RaftSkeletonVariant.Circle)
                                RaftSkeleton(Modifier.fillMaxWidth(0.45f), variant = RaftSkeletonVariant.Line)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Context menu on a home tile or list row (web `ArtifactHomeMenu`, slock #16357: Open, Copy link, Follow/Unfollow, Settle/Unsettle). */
@Composable
private fun ArtifactsHomeRowMenu(
    anchor: IntOffset,
    following: Boolean?,
    showFollow: Boolean,
    followingPending: Boolean,
    settled: Boolean,
    showSettle: Boolean,
    settling: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopyLink: (() -> Unit)?,
    onSetFollowing: ((Boolean) -> Unit)?,
    onSetSettled: ((Boolean) -> Unit)?,
) {
    val canFollow = showFollow && following != null && onSetFollowing != null
    Box(Modifier.offset { anchor }.size(1.dp)) {
        RaftDropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag(ArtifactsHomeTags.HomeMenu),
            minWidth = 192.dp,
            offset = 0.dp,
        ) {
            RaftMenuItem(
                text = stringResource(Res.string.common_open),
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.PanelRightOpen) },
                modifier = Modifier.testTag(ArtifactsHomeTags.HomeMenuOpen),
                onClick = {
                    onDismiss()
                    onOpen()
                },
            )
            if (onCopyLink != null || canFollow) {
                RaftMenuSeparator()
            }
            if (onCopyLink != null) {
                RaftMenuItem(
                    text = stringResource(Res.string.artifacts_menu_copy_link),
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Link) },
                    modifier = Modifier.testTag(ArtifactsHomeTags.HomeMenuCopyLink),
                    onClick = {
                        onDismiss()
                        onCopyLink()
                    },
                )
            }
            if (canFollow) {
                RaftMenuItem(
                    text = stringResource(if (following == true) Res.string.artifacts_menu_unfollow else Res.string.artifacts_menu_follow),
                    leadingIcon = {
                        RaftSlotIcon(if (following == true) RaftLucideIconName.UnfollowArtifact else RaftLucideIconName.FollowArtifact)
                    },
                    enabled = !followingPending,
                    modifier = Modifier.testTag(ArtifactsHomeTags.HomeMenuFollow),
                    onClick = {
                        onDismiss()
                        onSetFollowing(following != true)
                    },
                )
            }
            if (showSettle && onSetSettled != null) {
                RaftMenuSeparator()
                RaftMenuItem(
                    text = stringResource(if (settled) Res.string.artifacts_settled_unsettle else Res.string.artifacts_settled_settle),
                    leadingIcon = { RaftSlotIcon(if (settled) RaftLucideIconName.Undo2 else RaftLucideIconName.CircleCheck) },
                    enabled = !settling,
                    modifier = Modifier.testTag(ArtifactsHomeTags.HomeMenuSettle),
                    onClick = {
                        onDismiss()
                        onSetSettled(!settled)
                    },
                )
            }
        }
    }
}

/**
 * Opens the tile/row context menu on secondary click (mouse/trackpad/stylus) or touch long-press
 * without interfering with normal click / [RaftListRow] hover/press/focus.
 */
@Composable
private fun Modifier.artifactHomeContextMenuGestures(
    enabled: Boolean,
    cardSize: () -> IntSize,
    onOpenMenu: (IntOffset) -> Unit,
): Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCardSize by rememberUpdatedState(cardSize)
    val currentOnOpenMenu by rememberUpdatedState(onOpenMenu)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            var pending: PointerEvent? = null
            while (true) {
                val event = pending ?: awaitPointerEvent(PointerEventPass.Initial)
                pending = null
                if (!currentEnabled) continue
                val press = event.changes.firstOrNull() ?: continue
                if (!event.changes.all { it.changedToDownIgnoreConsumed() }) continue
                if (
                    artifactCardOpensContextMenuForPointer(
                        pointerType = press.type,
                        secondaryButtonPressed = event.buttons.isSecondaryPressed,
                        pressStarted = true,
                        menuEnabled = true,
                    )
                ) {
                    event.changes.forEach { it.consume() }
                    currentOnOpenMenu(artifactCardPointerMenuAnchorPx(press.position, currentCardSize()))
                    while (true) {
                        val next = awaitPointerEvent(PointerEventPass.Initial)
                        if (next.changes.any { it.changedToDownIgnoreConsumed() }) {
                            pending = next
                            break
                        }
                        next.changes.forEach { it.consume() }
                        if (next.changes.none { it.pressed }) break
                    }
                    continue
                }
                if (event.buttons.isSecondaryPressed || event.changes.size != 1 || !artifactCardLongPressPointer(press.type)) {
                    continue
                }
                val touchSlop = viewConfiguration.touchSlop
                val endedEarly =
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var ended = false
                        while (!ended) {
                            val next = awaitPointerEvent(PointerEventPass.Initial)
                            val change = next.changes.firstOrNull { it.id == press.id }
                            ended =
                                next.changes.size > 1 ||
                                    change == null ||
                                    !change.pressed ||
                                    (change.position - press.position).getDistance() > touchSlop
                        }
                        true
                    }
                if (endedEarly != null) continue
                currentOnOpenMenu(artifactCardPointerMenuAnchorPx(press.position, currentCardSize()))
                while (true) {
                    val next = awaitPointerEvent(PointerEventPass.Initial)
                    if (next.changes.any { it.changedToDownIgnoreConsumed() }) {
                        pending = next
                        break
                    }
                    next.changes.forEach { it.consume() }
                    if (next.changes.none { it.pressed }) break
                }
            }
        }
    }
}
