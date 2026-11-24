package build.raft.app

import build.raft.app.data.AgentBlockedState
import build.raft.app.data.ComputerAccessRevokedErrorCode
import build.raft.app.assistant.AssistantOwnerSection
import build.raft.app.resources.*
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import build.raft.app.markdown.markdownToPreviewText
import build.raft.app.components.RaftIconSpinner
import build.raft.app.components.raftCardSurface
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.app.data.agentRuntimeHasSubagents
import build.raft.app.data.AgentAppGrantsRevokeIntent
import build.raft.app.data.AgentAppGrantsRevokeState
import build.raft.app.data.AgentAppLoginLoadState
import build.raft.app.data.isActiveGrant
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentScopeSet
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentChannelSummary
import build.raft.app.data.AgentDmSummary
import build.raft.app.data.AgentReminderSummary
import build.raft.app.data.AgentSkillGroup
import build.raft.app.data.AgentSkillPathGroup
import build.raft.app.data.AgentSkillSummary
import build.raft.app.data.DataResult
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.RaftTimeZoneRuntime
import build.raft.app.data.SharedServerMembersState
import build.raft.app.model.MemberId
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.RaftWorkspaceFileKind
import build.raft.app.model.RaftWorkspaceNode
import build.raft.app.model.RaftTimeContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import build.raft.app.data.map
import kotlinx.coroutines.launch
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftEmptyStateDefaults

/**
 * Web AgentSkills (embedded): `Separator`, then `px-5 py-4` holding the SectionHeader (eyebrow +
 * gray count, `mb-3`) over the Global and Workspace sub-sections (`stack-y-4`). Each sub-section
 * is its icon + label + "(n)" row (`mb-2`) over its path groups (`stack-y-4`); a path group is the
 * InlineCode path + "(n)" (`mb-2`) over one card of one-line skill rows split by hairlines.
 */
@Composable
internal fun MembersAgentSkillsSection(detail: AgentDetailSnapshot?) {
    // The owner's assistant settings come before Skills (web `AssistantOwnerSection`), emitted here
    // rather than in [MembersAgentProfileTab], whose body is shape-sensitive.
    if (detail != null && detail.summary?.deletedAt.isNullOrBlank()) MembersAgentAssistantOwnerSection(detail.agentId)
    val groups = detail?.skills?.groups.orEmpty()
    val totalSkills = groups.sumOf { group -> group.paths.sumOf { path -> path.skills.size } }
    Column(modifier = Modifier.fillMaxWidth()) {
        MembersProfileFullBleedDivider()
        Row(
            modifier = Modifier.padding(top = MembersProfileSectionPaddingY),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MembersDetailSectionTitle(stringResource(Res.string.member_section_skills))
            // SectionHeader count: `ml-2 font-mono text-mono-meta font-normal text-foreground-hint`.
            RaftText(
                text = totalSkills.toString(),
                style = RaftTheme.typography.monoMeta,
                color = RaftTheme.colors.foregroundHint
            )
        }
        if (totalSkills > 0) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = MembersProfileEyebrowGap),
                verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)
            ) {
                groups.forEach { group ->
                    MembersAgentSkillSubSection(group)
                }
            }
        }
    }
}

private fun AgentSkillGroup.isWorkspaceGroup(): Boolean = title.equals("workspace", ignoreCase = true)

@Composable
private fun MembersAgentSkillSubSection(group: AgentSkillGroup) {
    val workspace = group.isWorkspaceGroup()
    val count = group.paths.sumOf { path -> path.skills.size }
    Column(modifier = Modifier.fillMaxWidth()) {
        // `mb-2 flex items-center gap-1.5`: icon 14 ink-2, footnote semibold ink-2, mono "(n)" ink-3.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = RaftAppSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftLucideIcon(
                name = if (workspace) RaftLucideIconName.FolderOpen else RaftLucideIconName.Globe,
                modifier = Modifier.size(14.dp),
                color = raftArgbHex(RaftTheme.colors.foregroundMuted)
            )
            RaftText(
                text = when {
                    workspace -> stringResource(Res.string.member_skills_workspace)
                    group.title.equals("global", ignoreCase = true) -> stringResource(Res.string.member_skills_global)
                    else -> group.title
                },
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = RaftTheme.colors.foregroundMuted
            )
            RaftText(
                text = "($count)",
                style = RaftTheme.typography.monoMeta,
                color = RaftTheme.colors.foregroundHint
            )
        }
        if (count == 0) {
            RaftText(
                text = if (workspace) {
                    stringResource(Res.string.member_state_no_workspace_skills)
                } else {
                    stringResource(Res.string.member_state_no_global_skills)
                },
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundHint
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)
            ) {
                group.paths.filter { it.skills.isNotEmpty() }.forEach { pathGroup ->
                    MembersAgentSkillPathGroup(pathGroup)
                }
            }
        }
    }
}

@Composable
private fun MembersAgentSkillPathGroup(pathGroup: AgentSkillPathGroup) {
    val colors = RaftTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        // `mb-2 flex min-w-0 items-center gap-2`: the InlineCode path (`rounded-sticker px-1 py-px
        // bg-fill-muted`, mono-meta ink-3) and its mono "(n)".
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = RaftAppSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftText(
                text = pathGroup.path,
                style = RaftTheme.typography.monoMeta,
                color = colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .background(colors.fillMuted, RoundedCornerShape(RaftRadius.Sticker))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
            RaftText(
                text = "(${pathGroup.skills.size})",
                style = RaftTheme.typography.monoMeta,
                color = colors.foregroundHint
            )
        }
        // raft-ui Card as a `ul` with `rule-y rule-line-hairline`: hairlines between the rows.
        Column(modifier = Modifier.fillMaxWidth().membersRaftCard()) {
            pathGroup.skills.forEachIndexed { index, skill ->
                if (index > 0) RaftSeparator()
                MembersAgentSkillRow(skill)
            }
        }
    }
}

/**
 * Web SkillRow: one line (chevron, name, `/command` tag, truncated description); a tap expands the
 * full description under it (`mt-1 pl-[22px]`).
 */
@Composable
private fun MembersAgentSkillRow(skill: AgentSkillSummary) {
    var open by remember(skill.name) { mutableStateOf(false) }
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val detail = skill.detail?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .raftClickable(onClick = { open = !open }, tier = RaftHoverTier.Subtle)
            .semantics { if (open) collapse { open = false; true } else expand { open = true; true } }
            .padding(horizontal = 12.dp, vertical = RaftAppSpacing.Sm)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftLucideIcon(
                name = if (open) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
                modifier = Modifier.size(14.dp),
                color = raftArgbHex(colors.foregroundHint)
            )
            RaftText(
                text = skill.name,
                style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 1
            )
            skill.command?.let { command ->
                RaftBadge(text = "/$command", appearance = RaftBadgeAppearance.Outline)
            }
            if (!open && detail != null) {
                RaftText(
                    text = detail,
                    style = typography.caption,
                    color = colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        if (open && detail != null) {
            RaftText(
                text = detail,
                style = typography.caption,
                color = colors.foregroundMuted,
                modifier = Modifier.padding(start = 22.dp, top = RaftAppSpacing.Xs)
            )
        }
    }
}

@Composable
internal fun MembersAgentChatTab(
    detail: AgentDetailSnapshot?,
    currentServerId: String?,
    serverMembersState: SharedServerMembersState,
    timeContext: RaftTimeContext,
    pageVisibleTick: Int,
    modifier: Modifier = Modifier
) {
    val inheritedTimeContextProvider = LocalRaftTimeContextProvider.current
    val baseFallbackTimeCapabilities = remember(timeContext) { timeContext.toRaftTimeCapabilities() }
    val runtimeTimeFormat = RaftTimeFormatRuntime.observe(fallback = baseFallbackTimeCapabilities.timeFormat)
    val runtimeTimeZone = RaftTimeZoneRuntime.observe()
    val fallbackTimeCapabilities =
        remember(baseFallbackTimeCapabilities, runtimeTimeFormat, runtimeTimeZone) {
            baseFallbackTimeCapabilities.withRuntimePreferences(
                timeFormat = runtimeTimeFormat,
                preferredTimeZoneId = runtimeTimeZone,
            )
        }
    val fallbackTimeContextProvider =
        rememberLiveRaftTimeContext(
            capabilities = fallbackTimeCapabilities,
            pageVisibleTick = pageVisibleTick,
            active = inheritedTimeContextProvider == null
        )
    val timeContextProvider = inheritedTimeContextProvider ?: fallbackTimeContextProvider
    CompositionLocalProvider(LocalRaftTimeContextProvider provides timeContextProvider) {
        MembersAgentChatTabContent(
            detail = detail,
            currentServerId = currentServerId,
            serverMembersState = serverMembersState,
            modifier = modifier
        )
    }
}

@Composable
private fun MembersAgentChatTabContent(
    detail: AgentDetailSnapshot?,
    currentServerId: String?,
    serverMembersState: SharedServerMembersState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
    ) {
        RaftSectionEyebrow(text = stringResource(Res.string.member_section_agent_channels_dms))
        RaftText(
            text = stringResource(Res.string.member_section_channels),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foregroundMuted
        )
        val channels = detail?.channels.orEmpty().filter { it.archivedAt.isNullOrBlank() }
        if (channels.isEmpty()) {
            RaftText(
                text = stringResource(Res.string.member_state_no_active_channels),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundHint
            )
        } else {
            // raft-ui ListRow rows in an inset group (one card, hairlines between rows).
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                channels.forEachIndexed { index, channel ->
                    MembersAgentChannelRow(channel, divider = index > 0)
                }
            }
        }
        RaftSeparator()
        RaftText(
            text = stringResource(Res.string.member_section_private_chats),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foregroundMuted
        )
        RaftText(
            text = stringResource(Res.string.member_hint_chat_audit_only),
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundHint
        )
        RaftSeparator()
        val rows = detail?.directMessages.orEmpty()
        if (rows.isEmpty()) {
            RaftText(
                text = stringResource(Res.string.member_state_no_agent_dms),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundHint
            )
        } else {
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                rows.take(8).forEachIndexed { index, row ->
                    MembersAgentDmRow(row, currentServerId, serverMembersState, divider = index > 0)
                }
            }
        }
    }
}

@Composable
private fun MembersAgentChannelRow(channel: AgentChannelSummary, divider: Boolean = false) {
    // raft-ui ListRow (plain): the channel's 20dp icon, its name, its description (up to 2
    // lines); a muted channel ends with the bell-off icon and a soft Muted tag.
    RaftListRow(
        title = channel.name,
        variant = RaftListRowVariant.Plain,
        divider = divider,
        description = channel.description?.takeIf { it.isNotBlank() },
        wrapDescription = true,
        descriptionMaxLines = 2,
        leading = { RaftContentLucideIcon(channel.type.threadChannelHeaderIcon(), size = 20.dp) },
        trailing = if (channel.activityMuted) {
            {
                RaftContentLucideIcon(RaftLucideIconName.BellOff)
                RaftBadge(
                    text = stringResource(Res.string.member_label_muted),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Muted
                )
            }
        } else {
            null
        }
    )
}

internal fun SharedServerMembersState.membersAgentDmPeerProfile(
    row: AgentDmSummary,
    currentServerId: String?
): MemberProfile? {
    val cleanServerId = currentServerId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val cleanPeerId = row.peerId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val profile = memberProfile(ServerId(cleanServerId), cleanPeerId) ?: return null
    return profile.takeIf {
        it.id.value.trim() == cleanPeerId &&
            it.serverId?.value?.trim() == cleanServerId
    }
}

@Composable
private fun MembersAgentDmRow(
    row: AgentDmSummary,
    currentServerId: String?,
    serverMembersState: SharedServerMembersState,
    divider: Boolean = false
) {
    val avatarCacheIdentity =
        remember(row.peerId, currentServerId, serverMembersState) {
            serverMembersState
                .membersAgentDmPeerProfile(row, currentServerId)
                ?.raftAvatarCacheIdentity(currentServerId)
        }
    // raft-ui AvatarListRow: the 35dp peer avatar, name, "Agent DM · @handle" in Space Mono,
    // the last message as the preview (up to 3 lines) with an Activity only tag, the time on
    // the right.
    RaftListRow(
        title = row.peerName,
        divider = divider,
        subtitle = "${stringResource(Res.string.member_label_agent_dm)} · @${row.peerName}",
        description = row.lastMessagePreview?.let(::markdownToPreviewText)?.takeIf { it.isNotBlank() },
        wrapDescription = true,
        descriptionMaxLines = 3,
        alignTop = true,
        leading = {
            // web AgentDMConversationCard: the peer is an agent (`kind: "agent"`), its face or the robot.
            RaftAppAvatarSticker(
                kind = RaftAvatarKind.Agent,
                avatarWireValue = row.peerAvatarUrl,
                label = row.peerName,
                size = RaftAvatarSize.Md,
                cacheSubject = avatarCacheIdentity?.subject,
                cacheServerId = avatarCacheIdentity?.serverId,
            )
        },
        badge = {
            RaftBadge(
                text = stringResource(Res.string.member_label_activity_only),
                appearance = RaftBadgeAppearance.Soft,
                variant = RaftBadgeVariant.Muted
            )
        },
        trailing = {
            RaftRelativeTimestampText(
                value = row.lastMessageAt,
                style = RaftTheme.typography.monoMeta,
                color = RaftTheme.colors.foregroundHint,
                maxLines = 1
            )
        }
    )
}

@Composable
internal fun MembersAgentRemindersTab(
    detail: AgentDetailSnapshot?,
    timeContext: RaftTimeContext,
    onOpenMessageRef: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = detail?.reminders.orEmpty()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rows.isEmpty()) {
            RaftEmptyState(
                title = stringResource(Res.string.member_state_no_reminders),
                description = stringResource(Res.string.member_hint_no_scheduled_reminders),
                icon = { RaftContentLucideIcon(RaftLucideIconName.AlarmClock, size = RaftEmptyStateDefaults.IconSize) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 520.dp)
                    .fillMaxSize()
            )
        } else {
            rows.take(8).forEach { reminder ->
                MembersAgentReminderCard(reminder, timeContext, onOpenMessageRef)
            }
        }
    }
}

@Composable
private fun MembersAgentReminderCard(
    reminder: AgentReminderSummary,
    timeContext: RaftTimeContext,
    onOpenMessageRef: (String) -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    // web AgentRemindersSection `ReminderCard`: a non-interactive raft-ui SurfaceListItem with the
    // title (`text-subhead font-semibold`), then `mt-1` when it fires (`text-caption` muted,
    // relative, then the date in Space Mono), then its recurrence and the message it points at.
    RaftCard(modifier = Modifier.fillMaxWidth(), variant = RaftCardVariant.Inset) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RaftText(
                text = reminder.title,
                style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RaftLucideIcon(
                        RaftLucideIconName.Clock3,
                        modifier = Modifier.size(14.dp),
                        color = raftArgbHex(colors.foregroundMuted)
                    )
                    RaftText(
                        text = reminder.fireAt.membersReminderRelativeTime(timeContext),
                        style = typography.caption,
                        color = colors.foregroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RaftText(
                    text = reminder.fireAt.membersReminderShortDateTime(timeContext),
                    style = typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            val recurrence = reminder.recurrenceDescription?.takeIf { it.isNotBlank() }
            val messageRef = reminder.messageRef?.takeIf { it.isNotBlank() }
            if (recurrence != null || messageRef != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (recurrence != null) {
                        // web: `<Badge appearance="outline">` with Lucide `Repeat`.
                        RaftBadge(
                            text = recurrence,
                            appearance = RaftBadgeAppearance.Outline,
                            modifier = Modifier.weight(1f, fill = false),
                            icon = { RaftContentLucideIcon(RaftLucideIconName.Repeat, size = 14.dp) }
                        )
                    }
                    if (messageRef != null) {
                        val permalink = reminder.messagePermalink?.takeIf { it.isNotBlank() }
                        // The 22dp badge sits in a 44dp-tall target when it opens the message; a
                        // reference with nothing to open is dimmed (alpha 0.7).
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .then(
                                    if (permalink != null) {
                                        Modifier
                                            .heightIn(min = 44.dp)
                                            .clickable(role = Role.Button) { onOpenMessageRef(permalink) }
                                    } else {
                                        Modifier.alpha(0.7f)
                                    }
                                ),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            RaftBadge(
                                text = messageRef,
                                appearance = RaftBadgeAppearance.Outline,
                                icon = { RaftContentLucideIcon(RaftLucideIconName.Link2, size = 13.dp) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Web `AgentWorkspace` on a phone: the workspace path bar (copy), the tree header (Workspace +
 * refresh) and the tree, each bar full width over a `line-seam` rule, the rows in Space Mono.
 * The web's hidden-files toggle is not here: the app does not list hidden files.
 */
@Composable
internal fun MembersAgentWorkspaceTab(
    treeState: MembersAgentWorkspaceTreeState,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<RaftWorkspaceNode>>,
    loadingDirs: Set<String>,
    dirErrors: Map<String, String>,
    /** The path bar's text; null hides the bar (an external agent has no local path to copy). */
    workspacePath: String?,
    onCopyPath: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val errorStrings = localizedErrorPresentationStrings()
    val colors = RaftTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        if (workspacePath != null) {
            MembersWorkspaceBar {
                RaftText(
                    text = workspacePath,
                    style = RaftTheme.typography.monoMeta,
                    color = colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Web CopyIconButton: a check in foreground ink for 1.5s once copied.
                var copied by remember(workspacePath) { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (copied) {
                        delay(1_500)
                        copied = false
                    }
                }
                RaftIconButton(
                    onClick = {
                        onCopyPath(workspacePath)
                        copied = true
                    },
                    tooltip =
                        stringResource(
                            if (copied) Res.string.member_action_copied_workspace_path else Res.string.member_action_copy_workspace_path
                        ),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Md
                ) {
                    RaftLucideIcon(
                        if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy,
                        Modifier.size(18.dp),
                        color = raftArgbHex(if (copied) colors.foreground else colors.foregroundHint)
                    )
                }
            }
        }
        MembersWorkspaceBar(horizontalArrangement = Arrangement.SpaceBetween) {
            RaftSectionEyebrow(text = stringResource(Res.string.member_section_workspace))
            // icon-md, pulled out by its icon inset so the glyph ends on the 12dp line (web headEdges).
            RaftIconButton(
                onClick = onRefresh,
                tooltip = stringResource(Res.string.member_action_refresh),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Md,
                modifier = Modifier.offset(x = 7.dp)
            ) {
                RaftLucideIcon(RaftLucideIconName.RefreshCw, Modifier.size(18.dp), color = raftArgbHex(colors.foregroundMuted))
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            when (treeState) {
                MembersAgentWorkspaceTreeState.Idle,
                MembersAgentWorkspaceTreeState.Loading ->
                    MembersWorkspaceCenteredHint(stringResource(Res.string.member_state_workspace_loading))
                MembersAgentWorkspaceTreeState.Empty ->
                    MembersWorkspaceEmptyState(stringResource(Res.string.member_state_no_files), onRefresh)
                // Web AgentWorkspace (botiverse/slock #13604): a revoked Computer never comes back, so
                // the panel says to move the agent, with no Retry.
                is MembersAgentWorkspaceTreeState.Error ->
                    if (treeState.error.code == ComputerAccessRevokedErrorCode) {
                        MembersWorkspaceErrorState(
                            AgentBlockedState.ComputerAccessRevoked.localizedStatusLabel(),
                            onRefresh = null
                        )
                    } else {
                        MembersWorkspaceErrorState(
                            stringResource(
                                Res.string.member_error_workspace_load,
                                treeState.error.userFacingMessage(errorStrings)
                            ),
                            onRefresh
                        )
                    }
                is MembersAgentWorkspaceTreeState.NoPermission ->
                    MembersWorkspaceErrorState(
                        stringResource(
                            Res.string.member_error_workspace_hidden,
                            treeState.blocker.userFacingMessage(errorStrings)
                        ),
                        onRefresh
                    )
                is MembersAgentWorkspaceTreeState.Content ->
                    MembersWorkspaceTreeView(
                        root = treeState.snapshot.root,
                        expandedDirs = expandedDirs,
                        loadedDirs = loadedDirs,
                        loadingDirs = loadingDirs,
                        dirErrors = dirErrors,
                        onToggleDirectory = onToggleDirectory,
                        onSelectFile = onSelectFile
                    )
            }
        }
    }
}

/**
 * Web AgentWorkspace's path bar: the path the runtime reported, else `~/.slock/agents/<id>/`;
 * none for an external agent (web `hosted`), which has no local path to copy.
 */
internal fun membersAgentWorkspacePath(agentId: String, summary: AgentDetailSummary?): String? {
    if (summary?.isExternal == true) return null
    return summary?.workspacePath?.trim()?.takeIf { it.isNotEmpty() } ?: "~/.slock/agents/$agentId/"
}

/** Web workspace bars: `px-3 py-1.5`, a `line-seam` rule below, full width. */
@Composable
private fun MembersWorkspaceBar(
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    content: @Composable RowScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = horizontalArrangement,
            content = content
        )
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(RaftTheme.colors.lineSeam))
    }
}

/** Web `px-3 py-4 text-center text-callout text-foreground-hint`. */
@Composable
private fun MembersWorkspaceCenteredHint(text: String) {
    RaftText(
        text = text,
        style = RaftTheme.typography.callout,
        color = RaftTheme.colors.foregroundHint,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp)
    )
}

@Composable
private fun MembersWorkspaceTreeView(
    root: RaftWorkspaceNode,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<RaftWorkspaceNode>>,
    loadingDirs: Set<String>,
    dirErrors: Map<String, String>,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit
) {
    val rows = remember(root, expandedDirs, loadedDirs) {
        membersWorkspaceRows(root.children, expandedDirs, loadedDirs)
    }
    if (rows.isEmpty()) {
        MembersWorkspaceCenteredHint(stringResource(Res.string.member_state_no_files))
        return
    }
    // The tab's list scrolls; the rows are plain children of its item.
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            key("workspace-${row.node.path}") {
                MembersWorkspaceNodeRow(
                    row = row,
                    expanded = row.node.path in expandedDirs,
                    loading = row.node.path in loadingDirs,
                    errorMessage = dirErrors[row.node.path],
                    onToggleDirectory = onToggleDirectory,
                    onSelectFile = onSelectFile
                )
            }
        }
    }
}

@Composable
private fun MembersWorkspaceNodeRow(
    row: MembersWorkspaceFlatNode,
    expanded: Boolean,
    loading: Boolean,
    errorMessage: String?,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit
) {
    val node = row.node
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val muted = raftArgbHex(colors.foregroundMuted)
    val folder = node.kind == RaftWorkspaceFileKind.Folder
    // Web TreeNode: `py-1 pr-2 gap-1`, Space Mono code text; a folder starts 8dp + 16dp per level
    // (chevron, then the folder), a file 22dp + 16dp per level so its icon lines up with the
    // folder's. 14dp glyphs; no sizes.
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .raftClickable(
                    onClick = { if (folder) onToggleDirectory(node.path) else onSelectFile(node.path) },
                    role = null,
                    tier = RaftHoverTier.Subtle,
                )
                .padding(start = (row.depth * 16 + if (folder) 8 else 22).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (folder) {
                if (loading) {
                    // #70 (@artin 06-23): the spinner stands in for the chevron while the
                    // directory loads.
                    RaftIconSpinner(size = 14.dp, color = RaftColor.TextBlack)
                } else {
                    RaftLucideIcon(
                        if (expanded) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
                        Modifier.size(14.dp),
                        color = raftArgbHex(colors.foregroundHint)
                    )
                }
            }
            RaftLucideIcon(node.membersWorkspaceIconName(expanded), Modifier.size(14.dp), color = muted)
            RaftText(
                text = node.name.ifBlank { node.path },
                style = typography.monoCode.copy(
                    fontWeight =
                        when {
                            folder -> FontWeight.Medium
                            node.name == "memory.md" -> FontWeight.Bold
                            else -> FontWeight.Normal
                        }
                ),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (loading) {
                RaftText(
                    text = stringResource(Res.string.common_loading),
                    style = typography.caption,
                    color = colors.foregroundHint,
                    maxLines = 1
                )
            }
        }
        errorMessage?.let {
            Box(Modifier.padding(start = ((row.depth + 1) * 16 + 22).dp, end = 12.dp, bottom = 6.dp)) {
                RaftText(
                    text = stringResource(Res.string.member_error_workspace_child_load, it),
                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.dangerStrong
                )
            }
        }
    }
}

@Composable
private fun MembersWorkspaceEmptyState(message: String, onRefresh: () -> Unit) {
    // The tree is full width; its own messages keep the bars' 12dp sides.
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
    ) {
        MembersDetailField(stringResource(Res.string.member_label_files), message)
        RaftButton(
            text = stringResource(Res.string.common_retry),
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            size = RaftButtonSize.Lg,
            leadingIcon = { RaftContentLucideIcon(RaftLucideIconName.RefreshCw, size = 18.dp) }
        )
    }
}

@Composable
private fun MembersWorkspaceErrorState(message: String, onRefresh: (() -> Unit)?) {
    // The tree is full width; its own messages keep the bars' 12dp sides.
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
    ) {
        MembersDetailField(stringResource(Res.string.member_label_state), message)
        // No Retry for an error a retry cannot fix (web `isFinalLoadError`).
        if (onRefresh != null) RaftButton(
            text = stringResource(Res.string.common_retry),
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            size = RaftButtonSize.Lg,
            leadingIcon = { RaftContentLucideIcon(RaftLucideIconName.RefreshCw, size = 18.dp) }
        )
    }
}

@Composable
internal fun MembersAgentAppsTab(
    agentId: MemberId,
    detail: AgentDetailSnapshot?,
    timeContext: RaftTimeContext,
    onOpenUrl: (String) -> Unit,
    appEventsLoader: MembersAgentAppEventsLoader?,
    appEventLoader: MembersAgentAppEventLoader?,
    modifier: Modifier = Modifier,
    /** Revoke access and per-grant Revoke (web AgentAppAccessTab); null offers neither. */
    appAccess: MembersAgentAppAccessBinding? = null
) {
    // Web AgentAppAccessTab (`stack-y-6`): the Apps section, then App events.
    // Not in the app yet: Grant Access (its picker, scopes and MCP connection), MCP App rows and
    // pending requests.
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        MembersAgentAppsSection(
            agentId = agentId,
            detail = detail,
            timeContext = timeContext,
            onOpenUrl = onOpenUrl,
            appAccess = appAccess
        )
        MembersAgentAppEventsSection(
            agentId = agentId,
            loader = appEventsLoader,
            detailLoader = appEventLoader,
            timeContext = timeContext
        )
    }
}

@Composable
private fun MembersAgentAppsSection(
    agentId: MemberId,
    detail: AgentDetailSnapshot?,
    timeContext: RaftTimeContext,
    onOpenUrl: (String) -> Unit,
    appAccess: MembersAgentAppAccessBinding?
) {
    val errorStrings = localizedErrorPresentationStrings()
    // Web AgentDetailPanel `canManageAgentAccess`: every Revoke, and the confirmation, need it.
    val canManage = appAccess?.canManage?.invoke(detail) == true
    val fallbackRevokeState = remember { MutableStateFlow(AgentAppGrantsRevokeState()) }
    val revokeState by (appAccess?.store?.state ?: fallbackRevokeState).collectAsState()
    // Web AgentAppAccessTab's Apps section (`stack-y-3`): the eyebrow, its description, then the
    // apps or the hint.
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MembersDetailSectionTitle(stringResource(Res.string.member_section_connected_apps))
            RaftText(
                text = stringResource(Res.string.member_hint_connected_apps),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundMuted
            )
        }
        val appLogins = detail?.appLogins
        when (appLogins?.loadState) {
            AgentAppLoginLoadState.NoPermission ->
                MembersDetailField(
                    stringResource(Res.string.member_tab_apps),
                    appLogins.permissionBlocker?.userFacingMessage(errorStrings)
                        ?: appLogins.errorCode?.userFacingErrorCodeMessage(errorStrings)
                        ?: stringResource(Res.string.member_error_apps_private)
                )
            AgentAppLoginLoadState.Error ->
                MembersDetailField(
                    stringResource(Res.string.member_tab_apps),
                    stringResource(
                        Res.string.member_error_apps_load,
                        appLogins.screenError?.userFacingMessage(errorStrings)
                            ?: appLogins.errorCode.userFacingErrorCodeMessage(errorStrings)
                    )
                )
            else -> {
                // The re-read after a revoke failed: the list stays as it is, and says so (web: the
                // tab's warning banner with the server's reason), with the request's trace ID.
                appLogins?.refreshError?.let { error ->
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RaftBanner(
                            description = stringResource(Res.string.member_error_apps_load, error.userFacingMessage(errorStrings)),
                            status = RaftBannerStatus.Warning,
                            size = RaftBannerSize.Sm
                        )
                        RaftTraceIdNote(error.traceId)
                    }
                }
                // One row per App (`clientId`), however many grants it holds.
                val items = appLogins?.items.orEmpty()
                val apps = remember(items) { membersAgentAppGrantGroups(items) }
                if (apps.isEmpty()) {
                    RaftText(
                        text = stringResource(Res.string.member_state_no_connected_apps),
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foregroundHint
                    )
                } else {
                    apps.forEach { app ->
                        key(app.clientId) {
                            MembersAgentAppGrantsCard(
                                group = app,
                                canManage = canManage,
                                timeContext = timeContext,
                                onOpenUrl = onOpenUrl,
                                onRevoke = { whole, grantIds ->
                                    appAccess?.store?.dispatch(
                                        AgentAppGrantsRevokeIntent.Start(
                                            agentId = agentId,
                                            clientId = app.clientId,
                                            appName = app.name,
                                            whole = whole,
                                            grantIds = grantIds
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    // Web `revoking && canManageAgentAccess`: this agent's confirmation, listing the grants it
    // still takes as the list now has them (the revoked ones gone).
    val revoking = revokeState.revoking?.takeIf { it.agentId == agentId && canManage }
    if (revoking != null) {
        MembersAgentAppRevokeDialog(
            revoke = revoking,
            grants = detail?.appLogins?.items.orEmpty().filter { it.isActiveGrant && it.id in revoking.grantIds },
            inFlight = revokeState.inFlight,
            onIntent = { intent -> appAccess?.store?.dispatch(intent) }
        )
    }
}

/**
 * What an App can do for an Agent (web `agentAppGrants.ts` `ABILITIES`, slock #14769). The identity
 * scopes together are one ability, signing in as the Agent; [short] reads after "{app} can", [long]
 * names it on its own (a grant's details, the revoke confirmation).
 */
internal enum class MembersAgentAppAbility(val scopes: List<String>, val short: StringResource, val long: StringResource) {
    SignIn(listOf("identity", "openid", "profile"), Res.string.member_app_ability_sign_in, Res.string.member_app_ability_sign_in_long),
    Email(listOf("email"), Res.string.member_app_ability_email, Res.string.member_app_ability_email_long),
    AgentDirectory(listOf("agent:read"), Res.string.member_app_ability_agent_directory, Res.string.member_app_ability_agent_directory_long),
    Events(listOf("agent:event:write"), Res.string.member_app_ability_events, Res.string.member_app_ability_events_long),
    Notifications(
        listOf("agent:notification:write"),
        Res.string.member_app_ability_notifications,
        Res.string.member_app_ability_notifications_long
    ),
    ActionRequests(
        listOf("agent:action_request:write"),
        Res.string.member_app_ability_action_requests,
        Res.string.member_app_ability_action_requests_long
    ),
}

/** One thing a grant lets the App do: a known [ability], or an unknown [rawScope] shown as itself. */
internal data class MembersAgentAppAbilityItem(val ability: MembersAgentAppAbility?, val rawScope: String?)

/** Web `appAbilities`: the abilities behind [scopes], known ones in a fixed order, then unknown scopes as given. */
internal fun membersAgentAppAbilities(scopes: List<String>): List<MembersAgentAppAbilityItem> {
    val unique = scopes.distinct()
    val known = MembersAgentAppAbility.entries
        .filter { ability -> ability.scopes.any { it in unique } }
        .map { MembersAgentAppAbilityItem(ability = it, rawScope = null) }
    val labelled = MembersAgentAppAbility.entries.flatMap { it.scopes }.toSet()
    val unknown = unique.filter { it !in labelled }.map { MembersAgentAppAbilityItem(ability = null, rawScope = it) }
    return known + unknown
}

/** Web `agent.apps.canSummary`: "{app} can {abilities}" across [scopes] (all of an App's grants); null when they name none. */
@Composable
internal fun membersAgentAppCanSummary(appName: String, scopes: List<String>): String? {
    val abilities = membersAgentAppAbilities(scopes)
    if (abilities.isEmpty()) return null
    val words = abilities.map { item -> item.ability?.let { stringResource(it.short) } ?: item.rawScope.orEmpty() }
    return stringResource(Res.string.member_app_can_summary, appName, jointMemberConnectNames(words))
}

/**
 * Web AgentActivityLog `showSubagents` (slock #14961): the Sub-agents segment shows for a runtime
 * that can start sub-agents ([agentRuntimeHasSubagents]) even at 0, else once one has appeared, and
 * always while it is the view.
 */
internal fun membersAgentActivityShowsSubagents(runtime: String?, subagentCount: Int, viewing: Boolean): Boolean =
    agentRuntimeHasSubagents(runtime) || subagentCount > 0 || viewing

// #89 React parity: agent scope grant list + per-group row definitions.
// Mirrors `packages/shared/src/agentScopes.ts` AGENT_SCOPE_GROUPS. Kept local
// (private to MembersAgentTabs.kt) for now — owner pending whether to lift to
// shared model module if other surfaces need it. Two sections
// ("notifications" / "cli") group 14 grantable scopes into 8 resources;
// each row carries the scope token, a short verbLabel and a one-sentence
// description that mirrors the React `AgentScopeGroup.scopes[]` shape.
private data class MembersAgentScopeRow(
    val scope: String,
    val verbLabel: String,
    val description: String
)

private data class MembersAgentScopeGroup(
    val resource: String,
    val section: String,
    val label: String,
    val description: String,
    val scopes: List<MembersAgentScopeRow>
)

private val membersAgentScopeGroups: List<MembersAgentScopeGroup> = listOf(
    MembersAgentScopeGroup(
        resource = "inbox",
        section = "notifications",
        label = "Inbox",
        description = "Control whether new activity can be delivered to this agent and wake it up.",
        scopes = listOf(
            MembersAgentScopeRow(
                scope = "inbox:receive",
                verbLabel = "Receive notifications",
                description = "Receive new inbox events and wake for activity from other people and agents."
            )
        )
    ),
    MembersAgentScopeGroup(
        resource = "server",
        section = "cli",
        label = "Server",
        description = "Discover channels, members, and other agents in this server.",
        scopes = listOf(
            MembersAgentScopeRow(
                scope = "server:read",
                verbLabel = "Read",
                description = "List channels, members, and agents (slock server info)."
            )
        )
    ),
    MembersAgentScopeGroup(
        resource = "channel",
        section = "cli",
        label = "Channels",
        description = "View channel rosters, join visible public channels, and leave channels the agent is no longer using.",
        scopes = listOf(
            MembersAgentScopeRow("channel:read", "Read", "View channel membership and metadata."),
            MembersAgentScopeRow("channel:join", "Join", "Join visible public channels."),
            MembersAgentScopeRow("channel:leave", "Leave", "Leave channels the agent is no longer using.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "thread",
        section = "cli",
        label = "Threads",
        description = "Follow and unfollow thread updates.",
        scopes = listOf(
            MembersAgentScopeRow("thread:unfollow", "Unfollow", "Unfollow a thread to stop receiving updates.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "message",
        section = "cli",
        label = "Messages",
        description = "Read, search, and send messages across channels, threads, and DMs.",
        scopes = listOf(
            MembersAgentScopeRow("message:read", "Read", "Read / search / resolve messages."),
            MembersAgentScopeRow("message:send", "Send", "Send messages (DM / channel / thread).")
        )
    ),
    MembersAgentScopeGroup(
        resource = "attachment",
        section = "cli",
        label = "Attachments",
        description = "Upload and view file attachments in conversation.",
        scopes = listOf(
            MembersAgentScopeRow("attachment:upload", "Upload", "Upload files to conversation."),
            MembersAgentScopeRow("attachment:view", "View", "View uploaded files in conversation.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "task",
        section = "cli",
        label = "Tasks",
        description = "List, create, and update tasks on the board.",
        scopes = listOf(
            MembersAgentScopeRow("task:read", "Read", "List tasks."),
            MembersAgentScopeRow("task:write", "Write", "Create, claim, unclaim, update tasks.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "knowledge",
        section = "cli",
        label = "Knowledge",
        description = "Read agent manual / knowledge base entries.",
        scopes = listOf(
            MembersAgentScopeRow("knowledge:read", "Read", "Read slock knowledge base entries.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "action",
        section = "cli",
        label = "Actions",
        description = "Prepare action cards for the user to commit.",
        scopes = listOf(
            MembersAgentScopeRow("action:prepare", "Prepare", "Prepare an action card; the human commits the action.")
        )
    )
)

@Composable
private fun MembersAgentScopeGroup.localizedLabel(): String =
    when (resource) {
        "inbox" -> stringResource(Res.string.member_permission_inbox)
        "server" -> stringResource(Res.string.member_permission_server)
        "channel" -> stringResource(Res.string.member_permission_channels)
        "thread" -> stringResource(Res.string.member_permission_threads)
        "message" -> stringResource(Res.string.member_permission_messages)
        "attachment" -> stringResource(Res.string.member_permission_attachments)
        "task" -> stringResource(Res.string.member_permission_tasks)
        "knowledge" -> stringResource(Res.string.member_permission_knowledge)
        "action" -> stringResource(Res.string.member_permission_actions)
        else -> label
    }

@Composable
private fun MembersAgentScopeGroup.localizedDescription(): String =
    when (resource) {
        "inbox" -> stringResource(Res.string.member_permission_inbox_hint)
        "server" -> stringResource(Res.string.member_permission_server_hint)
        "channel" -> stringResource(Res.string.member_permission_channels_hint)
        "thread" -> stringResource(Res.string.member_permission_threads_hint)
        "message" -> stringResource(Res.string.member_permission_messages_hint)
        "attachment" -> stringResource(Res.string.member_permission_attachments_hint)
        "task" -> stringResource(Res.string.member_permission_tasks_hint)
        "knowledge" -> stringResource(Res.string.member_permission_knowledge_hint)
        "action" -> stringResource(Res.string.member_permission_actions_hint)
        else -> description
    }

@Composable
private fun MembersAgentScopeRow.localizedVerbLabel(): String =
    when (scope) {
        "inbox:receive" -> stringResource(Res.string.member_permission_receive)
        "channel:join" -> stringResource(Res.string.member_permission_join)
        "channel:leave" -> stringResource(Res.string.member_permission_leave)
        "thread:unfollow" -> stringResource(Res.string.member_permission_unfollow)
        "message:send" -> stringResource(Res.string.member_permission_send)
        "attachment:upload" -> stringResource(Res.string.member_permission_upload)
        "attachment:view" -> stringResource(Res.string.member_permission_view)
        "task:write" -> stringResource(Res.string.member_permission_write)
        "action:prepare" -> stringResource(Res.string.member_permission_prepare)
        else -> stringResource(Res.string.member_permission_read)
    }

@Composable
private fun MembersAgentScopeRow.localizedDescription(): String =
    when (scope) {
        "inbox:receive" -> stringResource(Res.string.member_permission_receive_hint)
        "server:read" -> stringResource(Res.string.member_permission_server_read_hint)
        "channel:read" -> stringResource(Res.string.member_permission_channel_read_hint)
        "channel:join" -> stringResource(Res.string.member_permission_channel_join_hint)
        "channel:leave" -> stringResource(Res.string.member_permission_channel_leave_hint)
        "thread:unfollow" -> stringResource(Res.string.member_permission_thread_unfollow_hint)
        "message:read" -> stringResource(Res.string.member_permission_message_read_hint)
        "message:send" -> stringResource(Res.string.member_permission_message_send_hint)
        "attachment:upload" -> stringResource(Res.string.member_permission_attachment_upload_hint)
        "attachment:view" -> stringResource(Res.string.member_permission_attachment_view_hint)
        "task:read" -> stringResource(Res.string.member_permission_task_read_hint)
        "task:write" -> stringResource(Res.string.member_permission_task_write_hint)
        "knowledge:read" -> stringResource(Res.string.member_permission_knowledge_read_hint)
        "action:prepare" -> stringResource(Res.string.member_permission_action_prepare_hint)
        else -> description
    }

@Composable
internal fun MembersAgentPermissionsTab(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    canManage: Boolean,
    agentsApiClient: build.raft.app.data.RaftAgentsApiClient?,
    initialScopeSet: AgentScopeSet? = null,
    onUnavailableAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val errorStrings = localizedErrorPresentationStrings()
    // #89 React parity: mirror `AgentScopesPanel.tsx` state machine.
    // Local state only (per owner 06-25 04:08 — `SharedAgentDetailStore`
    // integration deferred to AD4 #56 lane).
    var serverSet by remember(member.id, initialScopeSet) { mutableStateOf<AgentScopeSet?>(initialScopeSet) }
    var draft by remember(member.id, initialScopeSet) { mutableStateOf(initialScopeSet?.granted?.toSet()) }
    var loading by remember(member.id, initialScopeSet) { mutableStateOf(initialScopeSet == null) }
    var saving by remember(member.id, initialScopeSet) { mutableStateOf(false) }
    var error by remember(member.id, initialScopeSet) { mutableStateOf<String?>(null) }
    var errorTraceId by remember(member.id, initialScopeSet) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val loadPermissionsFailed = stringResource(Res.string.member_error_load_permissions)
    val noPermissionsData = stringResource(Res.string.member_state_no_permissions_data)
    val viewPermissionsForbidden = stringResource(Res.string.member_error_view_permissions)
    val savePermissionsFailed = stringResource(Res.string.member_error_save_permissions)
    val emptyResponse = stringResource(Res.string.member_error_empty_response)
    val savePermissionsForbidden = stringResource(Res.string.member_error_save_permissions_forbidden)
    val resetPermissionsFailed = stringResource(Res.string.member_error_reset_permissions)
    val resetPermissionsForbidden = stringResource(Res.string.member_error_reset_permissions_forbidden)

    suspend fun loadScopes() {
        loading = true
        error = null
        errorTraceId = null
        if (agentsApiClient == null) {
            // Rare: this host wired no agents API client. Simple UX ("Failed to load"), but traced.
            raftLogWarn("RaftMembers", "members_agent_permissions_load_failed reason=no_agents_api_client agentId=${member.id}")
            error = loadPermissionsFailed
            errorTraceId = null
            loading = false
            return
        }
        when (val result = agentsApiClient.agentScopes(member.id)) {
            is DataResult.Content -> {
                serverSet = result.data
                draft = result.data.granted.toSet()
                error = null
                errorTraceId = null
            }
            is DataResult.Error -> {
                error = "$loadPermissionsFailed: ${result.error.userFacingMessage(errorStrings)}"
                errorTraceId = result.error.traceId
            }
            is DataResult.Empty -> {
                error = noPermissionsData
                errorTraceId = null
            }
            is DataResult.NoPermission -> {
                error = viewPermissionsForbidden
                errorTraceId = result.blocker.traceId
            }
        }
        loading = false
    }

    LaunchedEffect(member.id) {
        if (initialScopeSet == null) {
            loadScopes()
        }
    }

    fun toggle(scope: String) {
        val current = draft ?: return
        val next = if (scope in current) current - scope else current + scope
        draft = next
    }

    suspend fun save() {
        val current = draft ?: return
        val currentServer = serverSet ?: return
        if (agentsApiClient == null) return
        saving = true
        error = null
        errorTraceId = null
        when (val result = agentsApiClient.updateAgentScopes(member.id, current.toList())) {
            is DataResult.Content -> {
                serverSet = result.data
                draft = result.data.granted.toSet()
            }
            is DataResult.Error -> {
                error = "$savePermissionsFailed: ${result.error.userFacingMessage(errorStrings)}"
                errorTraceId = result.error.traceId
            }
            is DataResult.Empty -> {
                error = emptyResponse
                errorTraceId = null
            }
            is DataResult.NoPermission -> {
                error = savePermissionsForbidden
                errorTraceId = result.blocker.traceId
            }
        }
        saving = false
    }

    suspend fun resetToDefault() {
        if (agentsApiClient == null) return
        saving = true
        error = null
        errorTraceId = null
        when (val result = agentsApiClient.resetAgentScopes(member.id)) {
            is DataResult.Content -> {
                serverSet = result.data
                draft = result.data.granted.toSet()
            }
            is DataResult.Error -> {
                error = "$resetPermissionsFailed: ${result.error.userFacingMessage(errorStrings)}"
                errorTraceId = result.error.traceId
            }
            is DataResult.Empty -> {
                error = emptyResponse
                errorTraceId = null
            }
            is DataResult.NoPermission -> {
                error = resetPermissionsForbidden
                errorTraceId = result.blocker.traceId
            }
        }
        saving = false
    }

    fun discard() {
        val currentServer = serverSet ?: return
        draft = currentServer.granted.toSet()
    }

    val currentServer = serverSet
    val currentDraft = draft
    val isDirty = currentServer != null && currentDraft != null && !currentDraft.toSet().equals(currentServer.granted.toSet())

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
        if (error != null && currentServer == null) {
            // Initial-load error: brutal warning card + Retry button. Return
            // early after rendering so the rest of the panel is not drawn
            // over the error.
            ErrorBanner(
                text = error ?: "",
                action = stringResource(Res.string.common_retry),
                onAction = { scope.launch { loadScopes() } },
                traceId = errorTraceId,
            )
            return
        }
        if (loading || currentServer == null || currentDraft == null) {
            // Loading state: text "Loading permissions…"
            Text(
                text = stringResource(Res.string.member_state_loading_permissions),
                style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = RaftColor.TextMutedGray,
                modifier = Modifier.padding(RaftAppSpacing.Sm)
            )
            return
        }

        if (error != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .raftCardSurface(fill = RaftColor.FillMuted)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RaftLucideIcon(
                        RaftLucideIconName.TriangleAlert,
                        modifier = Modifier.size(16.dp),
                        color = "#FFD32F2F"
                    )
                    Text(
                        text = error ?: "",
                        style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = RaftColor.TextBlack,
                        modifier = Modifier.weight(1f)
                    )
                }
                RaftTraceIdNote(errorTraceId)
            }
        }

        // Mode banner (custom / default)
        val isCustom = currentServer.mode == "custom"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .raftCardSurface(fill = RaftColor.Card)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (isCustom) stringResource(Res.string.member_status_permissions_custom) else stringResource(Res.string.member_status_permissions_default),
                    style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = RaftColor.TextBlack
                )
                Text(
                    text = if (isCustom) stringResource(Res.string.member_hint_permissions_custom) else stringResource(Res.string.member_hint_permissions_default),
                    style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = RaftColor.TextMutedGray
                )
            }
            if (isCustom) {
                RaftButton(
                    text = stringResource(Res.string.member_action_use_default),
                    onClick = {
                        scope.launch { resetToDefault() }
                    },
                    enabled = !saving,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md
                )
            }
        }

        // Notifications section
        val notificationGroups = membersAgentScopeGroups.filter { it.section == "notifications" }
        val cliGroups = membersAgentScopeGroups.filter { it.section == "cli" }

        PermissionsSection(title = stringResource(Res.string.member_label_notifications_inbox), description = stringResource(Res.string.member_hint_permissions_notifications), groups = notificationGroups, draft = currentDraft, canManage = canManage, onToggle = ::toggle)
        PermissionsSection(title = stringResource(Res.string.member_role_raft_cli), description = stringResource(Res.string.member_hint_permissions_cli), groups = cliGroups, draft = currentDraft, canManage = canManage, onToggle = ::toggle)

        // Action bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RaftButton(
                text = if (saving) stringResource(Res.string.common_loading) else stringResource(Res.string.member_action_save_permissions),
                onClick = {
                    scope.launch { save() }
                },
                enabled = !saving && isDirty,
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg
            )
            RaftButton(
                text = stringResource(Res.string.member_action_discard),
                onClick = ::discard,
                enabled = !saving && isDirty,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Lg
            )
            Text(
                text = stringResource(Res.string.member_label_revision, currentServer.revision.toString()),
                style = RaftType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = RaftColor.TextMutedGray.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun PermissionsSection(
    title: String,
    description: String,
    groups: List<MembersAgentScopeGroup>,
    draft: Set<String>,
    canManage: Boolean,
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = RaftColor.TextBlack
            )
            Text(
                text = description,
                style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = RaftColor.TextMutedGray
            )
        }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
                PermissionsGroupBox(group = group, draft = draft, canManage = canManage, onToggle = onToggle)
            }
        }
    }
}

@Composable
private fun PermissionsGroupBox(
    group: MembersAgentScopeGroup,
    draft: Set<String>,
    canManage: Boolean,
    onToggle: (String) -> Unit
) {
    // raft-ui ListRowGroup: the group's card, a hairline between the header and each scope.
    RaftListRowGroup(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = group.localizedLabel(),
                style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = RaftColor.TextBlack
            )
            Text(
                text = group.localizedDescription(),
                style = RaftType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = RaftColor.TextMutedGray.copy(alpha = 0.5f)
            )
        }
        group.scopes.forEach { row ->
            RaftSeparator()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // One target: the row toggles and carries the checked state; the box only shows it.
                    .raftClickable(onClick = { onToggle(row.scope) }, enabled = canManage, role = Role.Checkbox)
                    .semantics { toggleableState = ToggleableState(row.scope in draft) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RaftCheckboxIndicator(checked = row.scope in draft, enabled = canManage)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = row.localizedVerbLabel(),
                            style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = RaftColor.TextBlack
                        )
                        Text(
                            text = row.scope,
                            style = RaftType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp),
                            color = RaftColor.TextMutedGray.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        text = row.localizedDescription(),
                        style = RaftType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = RaftColor.TextMutedGray.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

private data class MembersWorkspaceFlatNode(
    val node: RaftWorkspaceNode,
    val depth: Int
)

private fun membersWorkspaceRows(
    nodes: List<RaftWorkspaceNode>,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<RaftWorkspaceNode>>
): List<MembersWorkspaceFlatNode> =
    buildList {
        fun visit(current: List<RaftWorkspaceNode>, depth: Int) {
            current.forEach { node ->
                add(MembersWorkspaceFlatNode(node, depth))
                if (node.kind == RaftWorkspaceFileKind.Folder && node.path in expandedDirs) {
                    visit(loadedDirs[node.path] ?: node.children, depth + 1)
                }
            }
        }
        visit(nodes, 0)
    }

private fun RaftWorkspaceNode.membersWorkspaceIconName(expanded: Boolean): String =
    when (kind) {
        RaftWorkspaceFileKind.Folder -> if (expanded) "folder-open" else "folder"
        RaftWorkspaceFileKind.Markdown,
        RaftWorkspaceFileKind.Text -> "file-text"
        RaftWorkspaceFileKind.Image -> "image"
        RaftWorkspaceFileKind.Unsupported -> "file"
    }
