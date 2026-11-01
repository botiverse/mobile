package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactViewerVersion
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoicePickerSheet
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.raftTouchTarget
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * What the viewer's one bar shows besides Back and the title (the mock artin approved, 2026-11-01).
 * Every part is optional: a part not known yet, or whose read failed, is left out, and the bar
 * never waits for one (the page shows meanwhile).
 */
internal data class RaftArtifactViewerBarContent(
    /** Where it lives ("#channel"); null: not known. */
    val home: String? = null,
    /** The version the page shows; null: not known. */
    val version: Int? = null,
    /** The version list (newest first); null or empty: the version is not a control. */
    val versions: List<ArtifactViewerVersion>? = null,
    val latestVersion: Int? = null,
    /** Who read the current version; null: hidden. */
    val readCount: Int? = null,
    /** Null: the Comments button shows no number. */
    val commentCount: Int? = null,
    val commentsOpen: Boolean = false,
    /** Null: no Follow item (not read yet, or the read failed). */
    val following: Boolean? = null,
)

/** The bar's actions; a null one is not offered. */
internal class RaftArtifactViewerBarActions(
    val onToggleComments: (() -> Unit)? = null,
    val onPickVersion: ((Int) -> Unit)? = null,
    val onCopyLink: (() -> Unit)? = null,
    val onToggleFollow: (() -> Unit)? = null,
    val onOpenInBrowser: (() -> Unit)? = null,
)

/** Where an Artifact lives, as the meta line names it ([ArtifactHome]). */
@Composable
internal fun raftArtifactViewerHomeLabel(home: ArtifactHome): String? =
    when (home) {
        is ArtifactHome.Conversation ->
            when {
                home.dm -> (home.peerDisplayName ?: home.name).takeIf { it.isNotBlank() }?.let { stringResource(Res.string.artifacts_home_dm, it) }
                else -> home.name.takeIf { it.isNotBlank() }?.let { "#$it" }
            }
        ArtifactHome.Server -> stringResource(Res.string.artifacts_scope_server)
        ArtifactHome.Public -> stringResource(Res.string.artifacts_scope_public)
    }

/**
 * The viewer's page: the one 56dp bar (Back; the title over "#channel · vN ▾ · N read"; Comments
 * with its count; ⋮ with Copy Link, Follow / Unfollow, Open in Browser) over [content]. [menuOpen]
 * and [versionsOpen] are for screenshots only (the popups are windows there: see
 * [RaftArtifactViewerMenuItems], [raftArtifactViewerVersionOptions]).
 */
@Composable
internal fun RaftArtifactViewerFrame(
    title: String,
    onBack: () -> Unit,
    bar: RaftArtifactViewerBarContent = RaftArtifactViewerBarContent(),
    actions: RaftArtifactViewerBarActions = RaftArtifactViewerBarActions(),
    /** Screenshot fixtures only (no host window there). */
    topSafeAreaOverride: Dp? = null,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var versionsOpen by remember { mutableStateOf(false) }
    val titleStyle = RaftType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp)
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftTopPageHeader(
            title = title,
            backgroundColor = RaftColor.SurfaceWhite,
            onBack = onBack,
            topSafeAreaOverride = topSafeAreaOverride,
            titleContent = {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RaftText(title, style = titleStyle, color = RaftColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    RaftDesignSystemTheme {
                        RaftArtifactViewerMetaLine(
                            bar = bar,
                            onVersions = if (actions.onPickVersion != null && !bar.versions.isNullOrEmpty()) ({ versionsOpen = true }) else null,
                        )
                    }
                }
            },
            trailingContent = {
                RaftDesignSystemTheme {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        actions.onToggleComments?.let { toggle -> RaftArtifactViewerCommentsButton(bar.commentCount, onClick = toggle) }
                        if (actions.onCopyLink != null || actions.onToggleFollow != null || actions.onOpenInBrowser != null) {
                            Box {
                                RaftIconButton(
                                    onClick = { menuOpen = true },
                                    tooltip = stringResource(Res.string.artifacts_viewer_more),
                                    variant = RaftButtonVariant.Ghost,
                                    size = RaftIconButtonSize.Lg,
                                ) {
                                    RaftSlotIcon(RaftLucideIconName.EllipsisVertical)
                                }
                                RaftDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, minWidth = 220.dp) {
                                    RaftArtifactViewerMenuItems(following = bar.following, actions = actions, onDone = { menuOpen = false })
                                }
                            }
                        }
                    }
                }
            },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
    val pick = actions.onPickVersion
    val versions = bar.versions
    if (versionsOpen && pick != null && !versions.isNullOrEmpty()) {
        RaftDesignSystemTheme {
            RaftChoicePickerSheet(
                value = bar.version,
                options = raftArtifactViewerVersionOptions(versions, bar.latestVersion),
                onValueChange = pick,
                onDismissRequest = { versionsOpen = false },
                title = stringResource(Res.string.artifacts_viewer_version_history),
                emptyText = { "" },
            )
        }
    }
}

/** "#channel · vN ▾ · N read": the parts known, the version a control when [onVersions] is set. */
@Composable
private fun RaftArtifactViewerMetaLine(bar: RaftArtifactViewerBarContent, onVersions: (() -> Unit)?) {
    val colors = RaftTheme.colors
    val caption = RaftTheme.typography.caption
    val parts = listOfNotNull(bar.home?.let { "home" }, bar.version?.let { "version" }, bar.readCount?.let { "read" })
    if (parts.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        parts.forEachIndexed { index, part ->
            if (index > 0) RaftText("·", style = caption, color = colors.foregroundHint)
            when (part) {
                "home" ->
                    RaftText(
                        bar.home.orEmpty(),
                        // The place gives way first: the version and the count stay whole.
                        modifier = Modifier.weight(1f, fill = false),
                        style = caption,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                "version" -> RaftArtifactViewerVersionControl(bar.version ?: 0, onVersions)
                "read" ->
                    RaftText(
                        stringResource(Res.string.artifacts_viewer_read_count, bar.readCount ?: 0),
                        style = caption,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        softWrap = false,
                    )
            }
        }
    }
}

/**
 * "vN ▾": a TextLink (the version list) whose touch target is widened to 44dp round it
 * ([raftTouchTarget]) without changing the line; plain text when there is no list.
 */
@Composable
private fun RaftArtifactViewerVersionControl(version: Int, onVersions: (() -> Unit)?) {
    val colors = RaftTheme.colors
    val label = stringResource(Res.string.artifacts_card_version, version)
    val style = RaftTheme.typography.monoMeta
    if (onVersions == null) {
        RaftText(label, style = style, color = colors.foregroundMuted, maxLines = 1, softWrap = false)
        return
    }
    val source = remember { MutableInteractionSource() }
    val description = stringResource(Res.string.artifacts_viewer_switch_version, version)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        RaftTextLink(
            label,
            onClick = onVersions,
            modifier = Modifier.raftTouchTarget(true, source, onVersions).semantics { contentDescription = description },
            style = style,
            maxLines = 1,
            interactionSource = source,
        )
        RaftSlotIcon(RaftLucideIconName.ChevronDown, size = 12.dp, color = colors.foregroundMuted)
    }
}

/** Comments: a ghost button with the count (not an unread badge); the icon alone while it is not known. */
@Composable
private fun RaftArtifactViewerCommentsButton(count: Int?, onClick: () -> Unit) {
    if (count == null) {
        RaftIconButton(
            onClick = onClick,
            tooltip = stringResource(Res.string.artifacts_viewer_comments),
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Lg,
        ) {
            RaftSlotIcon(RaftLucideIconName.MessageSquare)
        }
        return
    }
    val description = stringResource(Res.string.artifacts_viewer_comments_count, count)
    RaftButton(
        text = count.toString(),
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
        variant = RaftButtonVariant.Ghost,
        size = RaftButtonSize.Lg,
        leadingIcon = { RaftSlotIcon(RaftLucideIconName.MessageSquare) },
    )
}

/** The ⋮ menu's items: Copy Link, Follow / Unfollow (once known), then Open in Browser. */
@Composable
internal fun ColumnScope.RaftArtifactViewerMenuItems(following: Boolean?, actions: RaftArtifactViewerBarActions, onDone: () -> Unit) {
    actions.onCopyLink?.let { copy ->
        RaftMenuItem(
            text = stringResource(Res.string.artifacts_viewer_copy_link),
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.Link) },
            onClick = {
                onDone()
                copy()
            },
        )
    }
    val toggleFollow = actions.onToggleFollow
    if (toggleFollow != null && following != null) {
        RaftMenuItem(
            text = stringResource(if (following) Res.string.artifacts_viewer_unfollow else Res.string.artifacts_viewer_follow),
            leadingIcon = { RaftSlotIcon(if (following) RaftLucideIconName.BellOff else RaftLucideIconName.Bell) },
            onClick = {
                onDone()
                toggleFollow()
            },
        )
    }
    actions.onOpenInBrowser?.let { open ->
        if (actions.onCopyLink != null || (toggleFollow != null && following != null)) RaftMenuSeparator()
        RaftMenuItem(
            text = stringResource(Res.string.artifacts_viewer_open_in_browser),
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowUpRight) },
            onClick = {
                onDone()
                open()
            },
        )
    }
}

/** The version list's rows: "vN", its publisher and when, the latest marked. */
@Composable
internal fun raftArtifactViewerVersionOptions(versions: List<ArtifactViewerVersion>, latestVersion: Int?): List<RaftComboboxOption<Int>> {
    val latestLabel = stringResource(Res.string.artifacts_viewer_latest)
    val latest = latestVersion ?: versions.maxOfOrNull { it.number }
    return versions.map { version ->
        val time = version.createdAt.takeIf { parseRaftTimestampEpochMillisOrNull(it) != null }?.let { raftRelativeTimestampLabel(it, clampFuture = true) }?.takeIf { it.isNotBlank() }
        val description =
            when {
                version.publisherName.isBlank() -> time
                time == null -> version.publisherName
                else -> stringResource(Res.string.artifacts_viewer_version_published, version.publisherName, time)
            }
        RaftComboboxOption(
            value = version.number,
            label = stringResource(Res.string.artifacts_card_version, version.number),
            description = description,
            badge = latestLabel.takeIf { version.number == latest },
        )
    }
}
