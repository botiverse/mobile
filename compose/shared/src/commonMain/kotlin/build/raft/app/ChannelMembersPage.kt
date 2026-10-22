package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowText
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/*
 * The channel members page (web `ChannelMembers` presentation="page", ChannelMemberList.tsx)
 * drawn with compose/designsystem parts. State, intents and the roster logic stay in
 * ThreadPage.kt (`ThreadMembersPageContent`); these composables are the visual layer only and
 * must sit inside `RaftDesignSystemTheme`.
 */

/**
 * Web `PanelHeader` of the members page: Back, the `Members` title and an outline count
 * badge (hidden while the roster loads), on the plain page surface like the other
 * design-system detail pages (`SettingsDetailShell`), not on the yellow frame.
 */
@Composable
internal fun ChannelMembersPageHeader(
    title: String,
    count: Int?,
    refreshing: Boolean,
    onBack: () -> Unit,
) {
    RaftTopPageHeader(
        title = title,
        onBack = onBack,
        backgroundColor = RaftColor.SurfaceWhite,
        refreshing = refreshing,
        titleContent = {
            RaftText(
                title,
                modifier = Modifier.weight(1f, fill = false),
                style = RaftType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                RaftBadge(text = count.toString(), appearance = RaftBadgeAppearance.Outline)
            }
            Spacer(Modifier.weight(1f))
        },
    )
}

/** Web `InputGroup` search under the header: `px-4 py-3`, a hairline below. */
@Composable
internal fun ChannelMembersSearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            RaftTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(MR.strings.thread_members_search_placeholder),
                leading = { ChannelMembersIcon(RaftLucideIconName.Search) },
            )
        }
        Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftTheme.colors.lineHairline))
    }
}

/** A role or server chip in a member row's trailing slot (web `MemberRoleTag`, `JointPeerBadge`). */
internal enum class ChannelMemberBadgeKind {
    /** Channel admin (owner, admin): neutral outline tag. */
    Admin,

    /** Guest or a joint channel's peer server: the cyan-soft external chip. */
    External,

    /** A bridge provider (Slack): outline tag. */
    Provider,
}

internal data class ChannelMemberBadge(val text: String, val kind: ChannelMemberBadgeKind)

/**
 * Web `ChannelMemberRow` in page mode: a design-system [RaftListRow] (35dp avatar with the
 * agent's activity light, the name in 15/20, the activity or description line, a hairline from where
 * the text starts), the whole row opening the profile.
 *
 * Trailing: the role tag, then Remove as a visible ghost icon button. On the web the
 * remove button is hover-only with a mouse but shown at rest below `md` (touch), which is
 * what this page does; a long press on it names the action ([RaftIconButton] tooltip).
 */
@Composable
internal fun ChannelMembersRow(
    title: String,
    subtitle: String?,
    avatarWireValue: String?,
    avatarLabel: String,
    avatarFill: Color,
    cacheSubject: RaftAvatarCacheSubject?,
    cacheServerId: String?,
    statusColor: Color?,
    statusPulsing: Boolean,
    badges: List<ChannelMemberBadge>,
    divider: Boolean,
    removing: Boolean = false,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    RaftListRow(
        title = title,
        onClick = onClick,
        enabled = !removing,
        divider = divider,
        text = RaftListRowText.Member,
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        leading = {
            RaftAvatarWithStatus(
                statusColor = statusColor,
                avatarSize = ChannelMembersAvatarSize,
                statusPulsing = statusPulsing,
                cutoutColor = RaftTheme.colors.layerCanvas,
            ) {
                RaftAppAvatar(
                    avatarWireValue = avatarWireValue,
                    label = avatarLabel,
                    avatarSize = ChannelMembersAvatarSize,
                    cacheSubject = cacheSubject,
                    cacheServerId = cacheServerId,
                    fallbackFill = avatarFill,
                )
            }
        },
        trailing =
            if (badges.isNotEmpty() || onRemove != null || removing) {
                {
                    badges.forEach { badge ->
                        RaftBadge(
                            text = badge.text,
                            modifier = Modifier.widthIn(max = 140.dp),
                            appearance =
                                if (badge.kind == ChannelMemberBadgeKind.External) RaftBadgeAppearance.Soft else RaftBadgeAppearance.Outline,
                            variant =
                                if (badge.kind == ChannelMemberBadgeKind.External) RaftBadgeVariant.Information else RaftBadgeVariant.Default,
                        )
                    }
                    if ((onRemove != null || removing) && removeLabel != null) {
                        RaftIconButton(
                            onClick = { onRemove?.invoke() },
                            tooltip = removeLabel,
                            variant = RaftButtonVariant.Ghost,
                            size = RaftIconButtonSize.Md,
                            loading = removing,
                        ) {
                            ChannelMembersIcon(RaftLucideIconName.UserMinus, size = 18.dp)
                        }
                    }
                }
            } else {
                null
            },
    )
}

/** Web members-page loading line: a spinner and "Loading members…", centred. */
@Composable
internal fun ChannelMembersLoading() {
    val label = stringResource(MR.strings.thread_members_loading)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftSpinner(size = RaftSpinnerSize.Sm, contentDescription = label)
        RaftText(label, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundHint)
    }
}

/**
 * raft-ui EmptyState (web `EmptyState`): a bare 36dp icon in `foreground-hint` (no frame), the
 * title in `text-headline` bold `foreground-muted`, an optional `text-callout` hint line and
 * an action.
 */
@Composable
internal fun ChannelMembersEmptyState(
    icon: RaftLucideIconName,
    title: String,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftLucideIcon(icon, modifier = Modifier.size(36.dp), color = raftArgbHex(RaftTheme.colors.foregroundHint))
        RaftText(
            title,
            modifier = Modifier.padding(bottom = 8.dp),
            style = RaftTheme.typography.headline,
            color = RaftTheme.colors.foregroundMuted,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            RaftText(
                detail,
                modifier = Modifier.widthIn(max = 300.dp),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundHint,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null) {
            RaftButton(text = actionLabel, onClick = onAction)
        }
    }
}

/** A page-level warning above the roster (web `Banner intent="warning" className="m-3"`). */
@Composable
internal fun ChannelMembersWarning(message: String) {
    RaftBanner(
        description = message,
        status = RaftBannerStatus.Warning,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        size = RaftBannerSize.Sm,
    )
}

/**
 * Web bottom-pinned `＋ Add Member`: a seam above, `p-3`, a full-width `lg` outline button
 * (design-system [RaftButtonVariant.Default]).
 */
@Composable
internal fun ChannelMembersAddBar(
    enabled: Boolean,
    bottomSafeArea: Dp,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftTheme.colors.lineSeam))
        Box(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp + bottomSafeArea)) {
            RaftButton(
                text = stringResource(MR.strings.thread_action_add_member),
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Lg,
                enabled = enabled,
                leadingIcon = { ChannelMembersIcon(RaftLucideIconName.Plus, size = 14.dp) },
            )
        }
    }
}

/** A Lucide icon in the enclosing design-system slot's ink. */
@Composable
private fun ChannelMembersIcon(name: RaftLucideIconName, size: Dp = 16.dp) {
    val ink = LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
    RaftLucideIcon(name, modifier = Modifier.size(size), color = raftArgbHex(ink))
}

private val ChannelMembersAvatarSize = 35.dp
