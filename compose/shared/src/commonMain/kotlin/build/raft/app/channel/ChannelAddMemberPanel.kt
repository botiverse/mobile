package build.raft.app

import androidx.compose.foundation.background
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.ErrorPresentationKey
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RawServerMessagePriority
import build.raft.app.data.ScreenError
import build.raft.app.data.errorPresentation
import build.raft.app.data.usableServerRefusalMessage
import build.raft.app.members.EmptyRaftMemberAvatarResolver
import build.raft.app.members.MembersCreateAgentMode
import build.raft.app.members.RaftAppBotBadge
import build.raft.app.members.RaftMemberAvatarResolver
import build.raft.app.members.rememberRaftMemberAppBot
import build.raft.app.members.rememberRaftMemberAvatarWireValue
import build.raft.app.model.MemberKind
import build.raft.app.network.raftValidTraceIdOrNull
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.ThreadUserFacingErrorLabels
import build.raft.app.thread.filterThreadAddMemberCandidates
import build.raft.app.thread.key
import build.raft.app.thread.memberKey
import build.raft.app.thread.modalAgentStatusIndicatorState
import build.raft.app.thread.modalAvatarLabel
import build.raft.app.thread.modalAvatarWireValue
import build.raft.app.thread.threadAddMemberCandidateSubtitle
import build.raft.app.thread.threadAddMemberJoinedKeys
import build.raft.app.thread.threadMemberAppBotLookupId
import build.raft.app.thread.threadUserFacingErrorLabels
import androidx.compose.ui.platform.testTag
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
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftDialogSurface
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.LocalRaftListRowInks
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftMultiChoiceList
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.components.raftClickable

/**
 * Add Member (web `ChannelMembers` add view, slock staging 61b6bec84): the members page's add
 * flow, a full page on phones and the right-side pane on the wide layout (the members page is a
 * Supporting pane, #3953). Never a centred modal: the web keeps that only for a legacy visual case.
 *
 * Search, then raft-ui ChoiceList `multiple` ([RaftMultiChoiceList]): an Agents and a Humans
 * heading, a checkbox per candidate (tapping ticks it, never adds it), the selected people as
 * removable badges above the list, the "Create Agent" and "Create External Agent" rows pinned
 * under it as on the web (the new agent joins this channel once created; only for a viewer who may
 * create agents), and "Add Selected (N)"
 * — disabled at 0 — sending one atomic batch.
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
    addMemberErrorServerCode: String? = null,
    addMemberErrorMessage: String? = null,
    addMemberErrorTraceId: String? = null,
    localServerId: String? = null,
    bottomSafeArea: Dp = 0.dp,
    initialSelectedKeys: Set<String> = emptySet(),
    onSubmitBatch: (List<RaftThreadMemberItem>) -> Unit,
    canCreateAgents: Boolean = false,
    onCreateAgent: (String, MembersCreateAgentMode) -> Unit = { _, _ -> },
    /** Web `addExternalMemberRow`: Invite people outside {server}, under Create agent (host owners/admins). */
    externalInviteEntry: (@Composable () -> Unit)? = null,
) {
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    val errorStrings = localizedErrorPresentationStrings()
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
    val noPermissionLabel = stringResource(Res.string.thread_add_member_no_permission)
    val genericErrorLabel = stringResource(Res.string.common_error)
    val errorText =
        threadAddMemberFailureMessage(
            canAddMembers = canAddMembers,
            addMemberStatus = addMemberStatus,
            errorCode = addMemberErrorCode,
            errorServerCode = addMemberErrorServerCode,
            errorMessage = addMemberErrorMessage,
            addFailedLabel = addFailedLabel,
            noPermissionLabel = noPermissionLabel,
            genericErrorLabel = genericErrorLabel,
            userFacingErrorLabels = userFacingErrorLabels,
            errorStrings = errorStrings,
        )
    val showErrorTrace =
        canAddMembers &&
            !errorText.isNullOrBlank() &&
            (addMemberStatus == "failed" || addMemberStatus == "no_permission") &&
            raftValidTraceIdOrNull(addMemberErrorTraceId) != null
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
                modifier = Modifier.fillMaxWidth().padding(bottom = if (showErrorTrace) 4.dp else 12.dp),
            )
            if (showErrorTrace) {
                RaftTraceIdNote(addMemberErrorTraceId, Modifier.padding(bottom = 12.dp))
            }
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
            // Web `createAgentFooter` (slock #14948): without create permission there is no create
            // row at all, only the external-member row when allowed, and no footer when neither is.
            footer =
                if (canCreateAgents || externalInviteEntry != null) {
                    {
                        Column(Modifier.fillMaxWidth()) {
                            if (canCreateAgents) {
                                ThreadAddMemberCreateAgentRows(
                                    canCreateAgents = true,
                                    createName = createName,
                                    channelName = channelName,
                                    enabled = !adding,
                                    onCreateAgent = onCreateAgent,
                                )
                            }
                            externalInviteEntry?.invoke()
                        }
                    }
                } else {
                    null
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
            // web ChannelMembersPanel: Lucide `UserPlus` on Add Selected.
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.UserPlus) },
            enabled = !blocked && !adding && selectedCandidates.isNotEmpty(),
            onClick = { onSubmitBatch(selectedCandidates) },
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Lg,
        )
    }
}

/**
 * Resolves the warning banner copy in [ThreadAddMemberPanel] using the 4-tier refusal policy:
 * preserves server refusal sentences (e.g. `channel_full`, `guest_other_channel`,
 * `users_not_in_server`) while scrubbing 401/5xx/`edge_blocked`/`internal_error`/HTML responses.
 */
internal fun threadAddMemberFailureMessage(
    canAddMembers: Boolean,
    addMemberStatus: String,
    errorCode: String?,
    errorServerCode: String? = null,
    errorMessage: String? = null,
    addFailedLabel: String,
    noPermissionLabel: String,
    genericErrorLabel: String,
    userFacingErrorLabels: ThreadUserFacingErrorLabels,
    errorStrings: ErrorPresentationStrings,
): String? {
    if (!canAddMembers) return noPermissionLabel
    return when (addMemberStatus) {
        "no_permission" -> {
            val presentation =
                PermissionBlocker(
                    code = errorCode ?: "permission_denied",
                    serverMessage = errorMessage,
                    serverCode = errorServerCode,
                ).errorPresentation()
            val raw = usableServerRefusalMessage(presentation.rawServerMessage)
            when {
                presentation.rawServerMessagePriority == RawServerMessagePriority.ServerMessageFirst && raw != null -> raw
                presentation.key != ErrorPresentationKey.PermissionDenied && presentation.key != ErrorPresentationKey.Generic ->
                    errorStrings.value(presentation.key)
                else -> noPermissionLabel
            }
        }
        "failed" -> {
            val presentation =
                ScreenError(
                    code = errorCode ?: "member_add_failed",
                    retryable = false,
                    serverMessage = errorMessage,
                    serverCode = errorServerCode,
                ).errorPresentation()
            val raw = usableServerRefusalMessage(presentation.rawServerMessage)
            when {
                presentation.rawServerMessagePriority == RawServerMessagePriority.ServerMessageFirst && raw != null -> raw
                presentation.key == ErrorPresentationKey.EdgeBlocked ||
                    presentation.key == ErrorPresentationKey.SessionExpired ||
                    presentation.key == ErrorPresentationKey.ChannelArchived ||
                    presentation.key == ErrorPresentationKey.ChannelReadOnly ||
                    presentation.key == ErrorPresentationKey.TooManyAttempts ->
                    errorStrings.value(presentation.key)
                else ->
                    "$addFailedLabel: " +
                        (userFacingErrorLabels.forErrorCode(errorCode ?: "member_add_failed")
                            ?: genericErrorLabel)
            }
        }
        else -> null
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
    val status = if (appBot == null) member.modalAgentStatusIndicatorState(agentStatusResolver).knownPresenceDotOrNull() else null
    val avatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, member.id) ?: member.modalAvatarWireValue()
    RaftAvatarWithStatus(
        statusColor = status?.color,
        avatarSize = ThreadAddMemberAvatarSize,
        cutoutColor = RaftTheme.colors.layerCard,
    ) {
        RaftAppAvatar(
            // web AvatarSlot: a person is their uploaded avatar or the placeholder person, never initials.
            kind = raftAvatarKind(member.kind.parsedMemberKindOrNull()),
            avatarWireValue = avatarWireValue,
            label = member.modalAvatarLabel(),
            avatarSize = ThreadAddMemberAvatarSize,
            cacheSubject = raftMemberAvatarCacheSubject(member.kind.parsedMemberKindOrNull(), member.id),
            cacheServerId = member.sourceServerId,
        )
    }
}

private val ThreadAddMemberAvatarSize = 20.dp

/**
 * The one "Create agent" row's state (web `createAgentFooter`, slock #13140): whether it can be
 * tapped, the name the managed dialog opens with and how it reads.
 */
internal data class ThreadAddMemberCreateRowSpec(
    /** Tappable: create permission, and no batch is being added. */
    val enabled: Boolean,
    /**
     * The name the managed dialog opens with; blank lets it suggest one. A search that found no
     * one (web `createEntryPromoted`) is carried into it; the external dialog never gets it.
     */
    val initialName: String,
    /** On the brand wash, named after the search, as the suggested way out. */
    val promoted: Boolean,
    /** "Joins #channel automatically after creation" under the label; not without permission. */
    val showsAutoJoin: Boolean,
)

/**
 * The footer's one create row. Both menu items share one gate (owner or admin, web
 * `capabilities.createAgents`). Without it the row is not drawn at all (slock #14948, cindyz
 * #wg-rbac:#76: a button the viewer can't use is noise), so [ThreadAddMemberPanel] only draws it
 * for a viewer who may create agents; the spec stays inert for `false` all the same.
 */
internal fun threadAddMemberCreateRowSpec(
    canCreateAgents: Boolean,
    createName: String,
    adding: Boolean,
): ThreadAddMemberCreateRowSpec {
    val named = createName.isNotBlank() && canCreateAgents
    return ThreadAddMemberCreateRowSpec(
        enabled = canCreateAgents && !adding,
        initialName = if (named) createName else "",
        promoted = named,
        showsAutoJoin = canCreateAgents,
    )
}

/** The menu's items, in the Members page agent "+" menu's order. */
internal val ThreadAddMemberCreateMenuModes = listOf(MembersCreateAgentMode.Managed, MembersCreateAgentMode.External)

/** The name a menu item hands to the create dialog: only the managed one carries the search. */
internal fun ThreadAddMemberCreateRowSpec.nameFor(mode: MembersCreateAgentMode): String =
    if (mode == MembersCreateAgentMode.Managed) initialName else ""

/** Screenshot tests open the chooser dialog immediately when enabled. */
internal val LocalThreadAddMemberCreateMenuPreview = staticCompositionLocalOf { false }

/**
 * Who may create agents from Add Member (web `capabilities.createAgents`, which
 * `serverPermissions.ts` grants to owners and admins only).
 */
internal fun threadAddMemberCanCreateAgents(permissions: build.raft.app.model.ServerPermissions?): Boolean =
    permissions?.canManageSettings == true

/**
 * The list's footer (web `CreateAgentEntry`, slock #16589 & #16573): ONE "Add agent" row that
 * opens a direct-entry chooser dialog ("Create agent" / "Connect an external agent"), each row
 * opening its create dialog with this channel preselected; the new agent joins this channel once
 * created (an external one then opens on its profile, to be set up). A search that found no one
 * names the row ("Add agent “name”"). Drawn only with create permission (slock #14948).
 */
@Composable
internal fun ThreadAddMemberCreateAgentRows(
    canCreateAgents: Boolean,
    createName: String,
    channelName: String,
    enabled: Boolean,
    onCreateAgent: (name: String, mode: MembersCreateAgentMode) -> Unit,
) {
    val spec = threadAddMemberCreateRowSpec(canCreateAgents = canCreateAgents, createName = createName, adding = !enabled)
    val preview = LocalThreadAddMemberCreateMenuPreview.current
    var chooserOpen by remember(preview) { mutableStateOf(preview) }
    val select: (MembersCreateAgentMode) -> Unit = { mode ->
        chooserOpen = false
        onCreateAgent(spec.nameFor(mode), mode)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ThreadAddMemberCreateAgentRow(
            spec = spec,
            canCreateAgents = canCreateAgents,
            channelName = channelName,
            onClick = { chooserOpen = true },
        )
        if (chooserOpen && spec.enabled) {
            ThreadAddMemberCreateAgentChooserDialog(
                onSelect = select,
                onDismiss = { chooserOpen = false },
            )
        }
    }
}

/**
 * Web `CreateAgentChooserDialog` (slock #16589): centred modal titled "Add agent" with two direct
 * entry rows (Create agent / Connect an external agent), no radios, no Next button, backdrop
 * dismissal enabled.
 */
@Composable
internal fun ThreadAddMemberCreateAgentChooserDialog(
    onSelect: (MembersCreateAgentMode) -> Unit,
    onDismiss: () -> Unit,
) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.thread_add_member_add_agent),
        modifier = Modifier.testTag("add-member-create-agent-chooser"),
        size = RaftDialogSize.Md,
        dismissOnClickOutside = true,
        closeLabel = stringResource(Res.string.common_close),
    ) {
        ThreadAddMemberCreateAgentChooserRows(onSelect = onSelect)
    }
}

/** Standalone chooser surface for previews and screenshot fixtures. */
@Composable
internal fun ThreadAddMemberCreateAgentChooserSurface(
    onSelect: (MembersCreateAgentMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftDialogSurface(
        title = stringResource(Res.string.thread_add_member_add_agent),
        modifier = modifier.testTag("add-member-create-agent-chooser"),
        size = RaftDialogSize.Md,
        onClose = onClose,
        closeLabel = stringResource(Res.string.common_close),
    ) {
        ThreadAddMemberCreateAgentChooserRows(onSelect = onSelect)
    }
}

@Composable
private fun ThreadAddMemberCreateAgentChooserRows(onSelect: (MembersCreateAgentMode) -> Unit) {
    val hostedMeta = stringResource(Res.string.thread_add_member_add_agent_hosted_meta)
    val externalMeta = stringResource(Res.string.thread_add_member_add_agent_external_meta)
    RaftListRowGroup {
        RaftListRow(
            title = stringResource(Res.string.thread_members_create_agent),
            subtitleContent = { ThreadAddMemberChooserSubtitle(hostedMeta) },
            variant = RaftListRowVariant.Plain,
            titleMaxLines = 2,
            leading = { RaftSlotIcon(RaftLucideIconName.Bot, size = 20.dp) },
            trailing = { RaftListRowChevron() },
            onClick = { onSelect(MembersCreateAgentMode.Managed) },
            modifier = Modifier.testTag("add-member-create-agent-chooser-hosted"),
        )
        RaftListRow(
            title = stringResource(Res.string.members_create_agent_external),
            subtitleContent = { ThreadAddMemberChooserSubtitle(externalMeta) },
            variant = RaftListRowVariant.Plain,
            titleMaxLines = 2,
            divider = true,
            leading = { RaftSlotIcon(RaftLucideIconName.Plug, size = 20.dp) },
            trailing = { RaftListRowChevron() },
            onClick = { onSelect(MembersCreateAgentMode.External) },
            modifier = Modifier.testTag("add-member-create-agent-chooser-external"),
        )
    }
}

@Composable
private fun ThreadAddMemberChooserSubtitle(text: String) {
    val color = LocalRaftListRowInks.current?.ink3 ?: RaftTheme.colors.foregroundHint
    RaftText(
        text = text,
        style = RaftTheme.typography.monoMeta,
        color = color,
    )
}

/**
 * The create row (web `CreateAgentEntry`, slock #16589): a dashed plus tile, "Add agent"
 * (`Add agent “name”` on the brand wash when the search found no one) and "Joins #channel
 * automatically after creation".
 */
@Composable
internal fun ThreadAddMemberCreateAgentRow(
    spec: ThreadAddMemberCreateRowSpec,
    canCreateAgents: Boolean,
    channelName: String,
    onClick: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val active = spec.enabled
    val chipShape = RoundedCornerShape(RaftRadius.Chip)
    val tileInk = if (canCreateAgents) colors.foregroundHint else colors.foregroundDisabled
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("add-member-create-agent-entry")
            .then(if (spec.promoted) Modifier.background(colors.brandSoft, chipShape) else Modifier)
            .raftClickable(onClick = onClick, enabled = active, shape = chipShape, tier = RaftHoverTier.Subtle)
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
            RaftGlyphIcon(RaftGlyph.Plus, size = 14.dp, color = tileInk)
        }
        Column(Modifier.weight(1f)) {
            RaftText(
                when {
                    spec.promoted -> stringResource(Res.string.thread_add_member_add_agent_named, spec.initialName)
                    else -> stringResource(Res.string.thread_add_member_add_agent)
                },
                style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = if (active) colors.foreground else colors.foregroundDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (spec.showsAutoJoin) {
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
