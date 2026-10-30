package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceListGroup
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMultiChoiceList
import build.raft.designsystem.components.raftPointerHover
import build.raft.designsystem.components.raftStateOverlay
import build.raft.designsystem.components.rememberRaftStateOverlay
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/**
 * Add Member (web `ChannelMembers` add view, slock staging 61b6bec84): the members page's add
 * flow, a full page on phones and the right-side pane on the wide layout (the members page is a
 * Supporting pane, #3953). Never a centred modal: the web keeps that only for a legacy visual case.
 *
 * Search, then raft-ui ChoiceList `multiple` ([RaftMultiChoiceList]): an Agents and a Humans
 * heading, a checkbox per candidate (tapping ticks it, never adds it), the selected people as
 * removable badges above the list, the "Create Agent" and "Create External Agent" rows pinned
 * under it (the new agent joins this channel once created), and "Add Selected (N)" — disabled at
 * 0 — sending one atomic batch.
 * Members already in this server's projection are not listed (web `memberCandidates`).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ThreadAddMemberPanel(
    members: List<RaftThreadMemberItem>,
    candidates: List<RaftThreadMemberItem>,
    canAddMembers: Boolean,
    channelName: String,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    addMemberStatus: String,
    addMemberErrorCode: String?,
    localServerId: String? = null,
    bottomSafeArea: Dp = 0.dp,
    initialSelectedKeys: Set<String> = emptySet(),
    onSubmitBatch: (List<RaftThreadMemberItem>) -> Unit,
    canCreateAgents: Boolean = false,
    onCreateAgent: (String, MembersCreateAgentMode) -> Unit = { _, _ -> },
) {
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    var query by remember { mutableStateOf("") }
    var selectedKeys by remember { mutableStateOf(initialSelectedKeys) }
    // The rows of the last batch the server refused; a row's warning goes once it is touched.
    var failedKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(addMemberStatus) {
        if (addMemberStatus == "failed") failedKeys = selectedKeys
    }
    val currentKeys = remember(members, localServerId) { threadAddMemberJoinedKeys(members, localServerId) }
    val available = remember(candidates, currentKeys) {
        candidates.filter { it.memberKey !in currentKeys }.distinctBy { it.memberKey }
    }
    val selectedCandidates = available.filter { it.memberKey in selectedKeys }
    val matchedKeys =
        if (query.isBlank()) null else filterThreadAddMemberCandidates(query = query, candidates = available).map { it.memberKey }
    val createName = query.trim().removePrefix("@").takeIf { matchedKeys != null && matchedKeys.isEmpty() }.orEmpty()
    val blocked = !canAddMembers || addMemberStatus == "no_permission"
    val adding = addMemberStatus == "adding"
    val addFailedLabel = stringResource(Res.string.thread_add_member_failed)
    val genericErrorLabel = stringResource(Res.string.common_error)
    val errorText =
        when {
            blocked -> stringResource(Res.string.thread_add_member_no_permission)
            addMemberStatus == "failed" ->
                "$addFailedLabel: " +
                    (userFacingErrorLabels.forErrorCode(addMemberErrorCode ?: "member_add_failed")
                        ?: genericErrorLabel)
            else -> null
        }
    val failedLine = stringResource(Res.string.thread_add_member_row_failed)
    val warningInk = RaftTheme.colors.warningStrong
    val items =
        available.map { member ->
            val key = member.memberKey
            val agent = member.kind.equals("agent", ignoreCase = true)
            RaftChoiceListItem(
                value = key,
                label = member.displayName.ifBlank { member.handle },
                descriptions =
                    listOfNotNull(
                        threadAddMemberCandidateSubtitle(member.description).takeIf { it.isNotBlank() }?.let(::AnnotatedString),
                        if (key in failedKeys) {
                            AnnotatedString(failedLine, spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = warningInk), 0, failedLine.length)))
                        } else {
                            null
                        },
                    ),
                searchText = listOf(member.displayName, member.handle, member.description).filter { it.isNotBlank() }.joinToString(" "),
                group = if (agent) "agent" else "human",
                media = { ThreadAddMemberAvatar(member, agentStatusResolver, memberAvatarResolver) },
                labelTrailing =
                    if (agent) {
                        {
                            val appBot = rememberRaftMemberAppBot(member.threadMemberAppBotLookupId())
                            if (appBot != null) RaftAppBotBadge(identity = appBot, botName = member.displayName)
                        }
                    } else {
                        null
                    },
            )
        }
    fun toggle(keys: Set<String>) {
        val touched = (selectedKeys - keys) + (keys - selectedKeys)
        selectedKeys = keys
        failedKeys = failedKeys - touched
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp + bottomSafeArea),
    ) {
        if (!errorText.isNullOrBlank()) {
            RaftBanner(
                description = errorText,
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
        if (selectedCandidates.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                selectedCandidates.forEach { member ->
                    val failed = member.memberKey in failedKeys
                    RaftBadge(
                        text = member.displayName.ifBlank { member.handle },
                        appearance = RaftBadgeAppearance.Soft,
                        variant = if (failed) RaftBadgeVariant.Warning else RaftBadgeVariant.Default,
                        enabled = !adding,
                        trailing = { RaftGlyphIcon(RaftGlyph.X, size = 14.dp) },
                        onClick = { toggle(selectedKeys - member.memberKey) },
                        onClickLabel = stringResource(Res.string.thread_add_member_deselect, member.displayName),
                    )
                }
            }
        }
        RaftMultiChoiceList(
            items = items,
            values = selectedKeys,
            onValuesChange = ::toggle,
            query = query,
            onQueryChange = { query = it },
            searchPlaceholder = stringResource(Res.string.thread_add_member_name_placeholder),
            clearSearchLabel = stringResource(Res.string.thread_add_member_clear_search),
            noMatchLabel = stringResource(Res.string.thread_add_member_no_matches, query.trim()),
            emptyLabel = stringResource(Res.string.thread_add_member_all_added),
            modifier = Modifier.weight(1f),
            enabled = !blocked && !adding,
            groups =
                listOf(
                    RaftChoiceListGroup("agent", stringResource(Res.string.thread_members_agents)),
                    RaftChoiceListGroup("human", stringResource(Res.string.thread_members_humans)),
                ),
            matchedValues = matchedKeys,
            fill = true,
            footer = {
                ThreadAddMemberCreateAgentRows(
                    canCreateAgents = canCreateAgents,
                    createName = createName,
                    channelName = channelName,
                    enabled = !adding,
                    onCreateAgent = { mode -> onCreateAgent(createName, mode) },
                )
            },
        )
        RaftButton(
            text =
                if (adding) {
                    stringResource(Res.string.thread_add_member_adding)
                } else {
                    stringResource(Res.string.thread_members_add_selected, selectedCandidates.size)
                },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.Plus) },
            enabled = !blocked && !adding && selectedCandidates.isNotEmpty(),
            onClick = { onSubmitBatch(selectedCandidates) },
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Lg,
        )
    }
}

/** A candidate's avatar (web AvatarSlot `compact-list`, 20dp) with an agent's activity light. */
@Composable
private fun ThreadAddMemberAvatar(
    member: RaftThreadMemberItem,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver,
) {
    // web `ChannelMembers` add panel: an App Bot gets the Bot badge, no light.
    val appBot = rememberRaftMemberAppBot(member.threadMemberAppBotLookupId())
    val status = if (appBot == null) member.modalAgentStatusIndicatorState(agentStatusResolver) else null
    val avatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, member.id) ?: member.modalAvatarWireValue()
    RaftAvatarWithStatus(
        statusColor = status?.color,
        avatarSize = ThreadAddMemberAvatarSize,
        statusPulsing = status?.pulsing == true,
        cutoutColor = RaftTheme.colors.layerCard,
    ) {
        RaftAppAvatar(
            avatarWireValue = avatarWireValue,
            label = member.modalAvatarLabel(),
            avatarSize = ThreadAddMemberAvatarSize,
            cacheSubject = raftMemberAvatarCacheSubject(member.kind.parsedMemberKindOrNull(), member.id),
            cacheServerId = member.sourceServerId,
            fallbackFill = raftAvatarFallbackFill(member.kind.parsedMemberKindOrNull()),
        )
    }
}

private val ThreadAddMemberAvatarSize = 20.dp

/**
 * The list's footer: one "Create Agent" row (artin 10-30, option A: two near-identical rows read
 * as clutter). It opens the web sidebar Agents "+" menu's two entries, in its order and wording
 * (`layout.sidebar.createAgent` / `createExternalAgent`): "Create Agent" (managed) and "Create
 * External Agent". Both open their create dialog with this channel preselected; a search that
 * found no one names the new agent in the row. Without create permission the row stays,
 * disabled, with the reason under it, so the way out is never hidden.
 */
@Composable
internal fun ThreadAddMemberCreateAgentRows(
    canCreateAgents: Boolean,
    createName: String,
    channelName: String,
    enabled: Boolean,
    onCreateAgent: (MembersCreateAgentMode) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box {
            ThreadAddMemberCreateAgentRow(
                mode = MembersCreateAgentMode.Managed,
                canCreateAgents = canCreateAgents,
                createName = createName,
                channelName = channelName,
                enabled = enabled,
                onClick = { menuOpen = true },
            )
            RaftDropdownMenu(
                expanded = menuOpen && canCreateAgents && enabled,
                onDismissRequest = { menuOpen = false },
            ) {
                RaftMenuItem(
                    text = stringResource(Res.string.thread_members_create_agent),
                    leadingIcon = { RaftGlyphIcon(RaftGlyph.Plus, size = 16.dp, color = RaftTheme.colors.foreground) },
                    onClick = {
                        menuOpen = false
                        onCreateAgent(MembersCreateAgentMode.Managed)
                    },
                )
                RaftMenuItem(
                    text = stringResource(Res.string.agent_create_action_create_external),
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Plug, size = 16.dp, color = RaftTheme.colors.foreground) },
                    onClick = {
                        menuOpen = false
                        onCreateAgent(MembersCreateAgentMode.External)
                    },
                )
            }
        }
        if (!canCreateAgents) {
            RaftText(
                stringResource(Res.string.thread_members_create_no_permission),
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp),
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundHint,
            )
        }
    }
}

/**
 * One create row (web `createAgentFooter`): a dashed tile (plus for a managed agent, the web
 * menu's plug for an external one), "Create Agent" / "Create External Agent" (or with
 * “name” when the search found no one; the managed row then sits on the brand wash as the
 * suggested way out) and "Joins #channel automatically after creation".
 */
@Composable
internal fun ThreadAddMemberCreateAgentRow(
    mode: MembersCreateAgentMode,
    canCreateAgents: Boolean,
    createName: String,
    channelName: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val external = mode == MembersCreateAgentMode.External
    val named = createName.isNotBlank() && canCreateAgents
    val promoted = named && !external
    val active = canCreateAgents && enabled
    val chipShape = RoundedCornerShape(RaftRadius.Chip)
    val tileInk = if (canCreateAgents) colors.foregroundHint else colors.foregroundDisabled
    val source = remember { MutableInteractionSource() }
    val overlay = rememberRaftStateOverlay(source, RaftHoverTier.Subtle, enabled = active)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (promoted) Modifier.background(colors.brandSoft, chipShape) else Modifier)
            .raftStateOverlay(overlay, chipShape)
            .raftPointerHover(source, enabled = active)
            .clickable(interactionSource = source, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // web: `size-6 rounded-sticker border-[1.5px] border-dashed border-foreground-hint`.
        Box(
            Modifier.size(24.dp).drawBehind {
                val stroke = RaftStroke.Control.toPx()
                val inset = stroke / 2f
                drawRoundRect(
                    color = tileInk,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius((RaftRadius.Sticker.toPx() - inset).coerceAtLeast(0f)),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            if (external) {
                RaftSlotIcon(RaftLucideIconName.Plug, size = 14.dp, color = tileInk)
            } else {
                RaftGlyphIcon(RaftGlyph.Plus, size = 14.dp, color = tileInk)
            }
        }
        Column(Modifier.weight(1f)) {
            RaftText(
                when {
                    external && named -> stringResource(Res.string.thread_members_create_named_external_agent, createName)
                    external -> stringResource(Res.string.agent_create_action_create_external)
                    named -> stringResource(Res.string.thread_members_create_named_agent, createName)
                    else -> stringResource(Res.string.thread_members_create_agent)
                },
                style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = if (active) colors.foreground else colors.foregroundDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canCreateAgents) {
                RaftText(
                    stringResource(Res.string.thread_add_member_create_agent_auto_join, "#$channelName"),
                    style = typography.caption,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
