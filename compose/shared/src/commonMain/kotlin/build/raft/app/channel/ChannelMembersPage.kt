package build.raft.app

import build.raft.app.resources.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.model.MemberServerRef
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftEmptyStateDefaults
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowText
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme

/*
 * The channel members page (web `ChannelMembers` presentation="page", ChannelMemberList.tsx)
 * drawn with compose/designsystem parts. State, intents and the roster logic stay in
 * ThreadMembersPage.kt (`ThreadMembersPageContent`); these composables are the visual layer only and
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
        // PROTOTYPE (native nav bar): the roster count beside the host's title.
        nativeTitleExtras = RaftNativeNavBarTitleExtras(badge = count?.toString()),
        titleContent = {
            RaftText(
                title,
                modifier = Modifier.weight(1f, fill = false),
                style = RaftType.Headline,
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
                placeholder = stringResource(Res.string.thread_members_search_placeholder),
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
 * Trailing: the role tag, then the row's one ⋯ ([moreActions]: the role item and Remove from
 * channel, task #triage:#416). The web keeps a separate remove button there; mobile folds it into
 * the ⋯ menu. A long press on the name copies it (the row's tap still opens the profile).
 */
@Composable
internal fun ChannelMembersRow(
    title: String,
    subtitle: String?,
    avatarWireValue: String?,
    avatarLabel: String,
    avatarKind: RaftAvatarKind,
    cacheSubject: RaftAvatarCacheSubject?,
    cacheServerId: String?,
    statusColor: Color?,
    badges: List<ChannelMemberBadge>,
    divider: Boolean,
    removing: Boolean = false,
    onClick: (() -> Unit)? = null,
    subtitleCopyText: String? = null,
    titleBadge: (@Composable () -> Unit)? = null,
    /** Someone from another server: the first one's tile on the avatar's top-left corner. */
    avatarOriginServers: List<MemberServerRef> = emptyList(),
    /** Custom subtitle slot (e.g. 2-line runtime sign-in status + detail, slock #15282); wins over [subtitle]. */
    subtitleContent: (@Composable () -> Unit)? = null,
    /** Quiet trailing action before the role badges and ⋯ menu (e.g. Sign in, slock #15282). */
    trailingAction: (@Composable () -> Unit)? = null,
    /** The row's ⋯ menu after the tags ([ChannelMemberRowMenuButton]: role action, Remove from channel). */
    moreActions: (@Composable () -> Unit)? = null,
) {
    RaftListRow(
        title = title,
        titleTrailing = titleBadge,
        onClick = onClick,
        enabled = !removing,
        divider = divider,
        text = RaftListRowText.Member,
        titleCopyText = title,
        subtitleCopyText = subtitleCopyText,
        subtitle = if (subtitleContent != null) null else subtitle?.takeIf { it.isNotBlank() },
        subtitleContent = subtitleContent,
        leading = {
            RaftAvatarWithOriginCorner(servers = avatarOriginServers, avatarEdge = ChannelMembersAvatarSize) {
                RaftAvatarWithStatus(
                    statusColor = statusColor,
                    avatarSize = ChannelMembersAvatarSize,
                    cutoutColor = RaftTheme.colors.layerCanvas,
                ) {
                    RaftAppAvatar(
                        kind = avatarKind,
                        avatarWireValue = avatarWireValue,
                        label = avatarLabel,
                        avatarSize = ChannelMembersAvatarSize,
                        cacheSubject = cacheSubject,
                        cacheServerId = cacheServerId,
                    )
                }
            }
        },
        trailing =
            if (trailingAction != null || badges.isNotEmpty() || moreActions != null) {
                {
                    trailingAction?.invoke()
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
                    moreActions?.invoke()
                }
            } else {
                null
            },
    )
}

/**
 * Web `MemberRowName`'s server names after the name of someone from another server ("Acme Corp,
 * Lumen Labs"), and web `JointServerBadges`' "Also in Raft, Acme Corp" for a member of the viewed
 * server who is also in another (#14731, it was a blue chip): caption, regular, `foreground-hint`,
 * one line, truncated; capped so the name keeps its room (the web lets the text give way first).
 * [spokenText], when given, is what a screen reader says in place of [names] (web #12853: "{name}
 * is also a member of {servers}" for a person in a computed joint roster).
 */
@Composable
internal fun ChannelMemberOriginServerNames(
    names: String,
    testTag: String = ChannelMemberOriginServersTestTag,
    spokenText: String? = null,
) {
    RaftText(
        names,
        modifier =
            Modifier.widthIn(max = 140.dp).testTag(testTag)
                .then(spokenText?.let { spoken -> Modifier.semantics { contentDescription = spoken } } ?: Modifier),
        style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.Normal),
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal const val ChannelMemberOriginServersTestTag = "member-row-origin-servers"

internal const val ChannelMemberAlsoInTestTag = "member-row-also-in"

/** Web members-page loading line: a spinner and "Loading members…", centred. */
@Composable
internal fun ChannelMembersLoading() {
    val label = stringResource(Res.string.thread_members_loading)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftSpinner(size = RaftSpinnerSize.Sm, contentDescription = label)
        RaftText(label, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundHint)
    }
}

/** The members page's [RaftEmptyState]: an app icon, the title and, when a retry helps, an action. */
@Composable
internal fun ChannelMembersEmptyState(
    icon: RaftLucideIconName,
    title: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    RaftEmptyState(
        title = title,
        icon = { RaftContentLucideIcon(icon, size = RaftEmptyStateDefaults.IconSize) },
        actions = actionLabel?.let { label -> { RaftButton(text = label, onClick = onAction) } },
    )
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
 * Web bottom-pinned members action bar (`ChannelMembers.tsx`): a seam above, `p-3`, `Add Member`
 * and (when external invite access is on) `Invite people outside {server}` stacked with 8dp spacing.
 */
@Composable
internal fun ChannelMembersAddBar(
    enabled: Boolean,
    bottomSafeArea: Dp,
    onClick: () -> Unit,
    showAddMember: Boolean = true,
    inviteOutsideServerName: String? = null,
    inviteOutsideLoading: Boolean = false,
    onInviteOutside: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftTheme.colors.lineSeam))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp + bottomSafeArea),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showAddMember) {
                RaftButton(
                    text = stringResource(Res.string.thread_action_add_member),
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                    enabled = enabled,
                    leadingIcon = { ChannelMembersIcon(RaftLucideIconName.Plus, size = 14.dp) },
                )
            }
            if (inviteOutsideServerName != null && onInviteOutside != null) {
                RaftButton(
                    text = stringResource(Res.string.channel_external_invite_title, inviteOutsideServerName),
                    onClick = onInviteOutside,
                    modifier = Modifier.fillMaxWidth().testTag(ExternalInviteMembersPageButtonTag),
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                    enabled = !inviteOutsideLoading,
                    loading = inviteOutsideLoading,
                    leadingIcon = { ChannelMembersIcon(RaftLucideIconName.Mail, size = 14.dp) },
                )
            }
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
