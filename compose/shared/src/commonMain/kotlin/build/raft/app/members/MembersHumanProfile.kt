package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.setValue
import build.raft.app.components.raftControlSurface
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.app.components.RaftUiSelectionPopover
import build.raft.app.components.RaftUiSelectionOption
import build.raft.app.data.AccountDescriptionUpdateResult
import build.raft.app.data.DataResult
import build.raft.app.data.DescriptionLinkSubject
import build.raft.app.data.DescriptionLinkWarning
import build.raft.app.data.PROFILE_DESCRIPTION_INVALID_CODE
import build.raft.app.data.PROFILE_DESCRIPTION_MAX_LENGTH
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsServerActionResult
import build.raft.app.data.isEdgeBlockedMarker
import build.raft.app.data.profileDescriptionComparable
import build.raft.app.data.profileDescriptionLength
import build.raft.app.data.profileDescriptionTooLong
import build.raft.app.model.AgentProfile
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.toMemberProfile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftDescriptionValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import build.raft.app.data.map
import kotlinx.coroutines.launch
import build.raft.designsystem.components.raftClickable
import androidx.compose.ui.semantics.selected

internal const val MembersHumanDescriptionEditButtonTag = "human-profile-description-edit"
internal const val MembersHumanDescriptionInputTag = "human-profile-description-input"
internal const val MembersHumanDescriptionSaveButtonTag = "human-profile-description-save"
internal const val MembersHumanDescriptionCancelButtonTag = "human-profile-description-cancel"

// Web HumanDetailPanel (raft-ui ProfilePanelBody): each section is a full-bleed hairline
// Separator over a `px-5 py-4` body; the hero row is `px-5 py-5 gap-4`.
private val MembersHumanSectionPaddingX = 20.dp

private val MembersHumanSectionPaddingY = 16.dp

@Composable
internal fun MembersHumanDetailSections(
    member: MemberProfile,
    timeContext: RaftTimeContext,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    fixtureStatusFallbackEnabled: Boolean = false,
    actorRole: String? = null,
    accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull,
    currentUserId: String? = null,
    currentServerId: String? = null,
    ownerCount: Int? = null,
    onUpdateRole: (suspend (String, MembersHumanServerRole) -> DataResult<SettingsServerActionResult>)? = null,
    onUpdateDescription: (suspend (String?) -> DataResult<AccountDescriptionUpdateResult>)? = null,
    onApplyDescription: ((String?) -> Unit)? = null,
    onRemoveMember: (() -> Unit)? = null,
    onPromoteGuest: (() -> Unit)? = null,
    promotingGuest: Boolean = false,
    onOpenCreatedAgent: ((AgentProfile) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val departure = membersHumanDeparture(member.membershipStatus)
    val isSelf = membersHumanIsSelf(member, currentUserId)
    Column(modifier = modifier.fillMaxWidth()) {
        // Web HumanDetailPanel: "From" and the person's servers, above the avatar, when any is not ours.
        membersHumanSourceServerLabel(member, currentServerId)?.let { label ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = MembersHumanSectionPaddingX, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
            ) {
                MembersDetailSectionTitle(stringResource(Res.string.member_section_from))
                RaftText(
                    text = label,
                    style = RaftTheme.typography.callout.withRaftFontWeight(FontWeight.SemiBold),
                    color = RaftTheme.colors.foreground
                )
            }
            RaftSeparator()
        }
        MembersHumanProfileSummary(
            member = member,
            departure = departure,
            isSelf = isSelf,
            memberAvatarResolver = memberAvatarResolver
        )
        MembersHumanDescriptionSection(
            member = member,
            currentUserId = currentUserId,
            onUpdateDescription = onUpdateDescription,
            onApplyDescription = onApplyDescription
        )
        // Web hides Info and Created Agents for a person seen through another server
        // (`isRemoteJointHuman`) or a channel summary projection.
        if (membersHumanShowsServerFacts(accessMode)) {
            MembersHumanInfoSection(
                member = member,
                departure = departure,
                timeContext = timeContext,
                actorRole = actorRole,
                accessMode = accessMode,
                currentUserId = currentUserId,
                currentServerId = currentServerId,
                ownerCount = ownerCount,
                onUpdateRole = onUpdateRole
            )
            MembersHumanCreatedAgentsSection(
                member = member,
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled,
                onOpenCreatedAgent = onOpenCreatedAgent
            )
        }
        if (onRemoveMember != null || onPromoteGuest != null) {
            MembersHumanActionsSection(
                onRemoveMember = onRemoveMember,
                onPromoteGuest = onPromoteGuest,
                promotingGuest = promotingGuest
            )
        }
    }
}

/** One profile section: a full-width hairline, then the eyebrow and [content] in `px-5 py-4`. */
@Composable
private fun MembersHumanSection(
    title: String,
    count: String? = null,
    titleGap: Dp = 12.dp,
    accessory: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RaftSeparator()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MembersHumanSectionPaddingX, vertical = MembersHumanSectionPaddingY)
        ) {
            if (accessory != null) {
                Row(
                    modifier = Modifier.padding(bottom = titleGap),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RaftSectionEyebrow(text = title, count = count)
                    accessory()
                }
            } else {
                RaftSectionEyebrow(text = title, count = count, modifier = Modifier.padding(bottom = titleGap))
            }
            content()
        }
    }
}

/** A Lucide glyph drawn in the enclosing raft-ui component's ink (`currentColor`). */
@Composable
internal fun MembersHumanGlyph(name: RaftLucideIconName, size: Dp) {
    val ink = LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
    RaftLucideIcon(name, modifier = Modifier.size(size), color = raftArgbHex(ink))
}

/**
 * Web KeyValueRow: the label (`text-footnote font-medium text-foreground-hint`) with an optional
 * [accessory] 8dp after it (`mb-1 flex items-center gap-2`), then [content] in foreground ink.
 */
@Composable
private fun MembersHumanKeyValue(
    term: String,
    accessory: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(
            modifier = Modifier.padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftText(
                text = term,
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                color = RaftTheme.colors.foregroundHint
            )
            accessory?.invoke()
        }
        CompositionLocalProvider(LocalRaftContentColor provides RaftTheme.colors.foreground) {
            content()
        }
    }
}

/** CSS `-my-1`: the 32dp icon button takes 8dp less height in its row, centred on it. */
private fun Modifier.membersInlineActionOverhang(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val overhang = 4.dp.roundToPx()
        layout(placeable.width, (placeable.height - overhang * 2).coerceAtLeast(0)) {
            placeable.place(0, -overhang)
        }
    }

/**
 * Web HumanDetailPanel `INLINE_ICON_ACTION_CLASS`: the ghost `icon-md` button's 18dp Lucide glyph
 * in `text-foreground-hint` (HelpCircle beside Role, Pencil to edit it).
 */
@Composable
private fun MembersHumanInlineActionGlyph(name: RaftLucideIconName) {
    RaftLucideIcon(name, modifier = Modifier.size(18.dp), color = raftArgbHex(RaftTheme.colors.foregroundHint))
}

/**
 * raft-ui Avatar (the square sticker) around the member's resolved photo / pixel avatar.
 * [RaftAvatarSize.Xl] is web `AvatarSlot context="profile-hero"`, Md the panel head and list rows.
 */
@Composable
internal fun MembersStickerAvatar(
    member: MemberProfile,
    size: RaftAvatarSize,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver
) {
    val resolvedAvatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, member.id.value)
    val fallbackAvatarWireValue = remember(member.avatar, member.gravatarHash, member.kind, member.id) {
        member.avatarWireValueForAvatarSlot()
    }
    RaftAppAvatarSticker(
        // Web AvatarSlot: a person without a photo is the User placeholder, never initials.
        kind = raftAvatarKind(member.kind),
        avatarWireValue = resolvedAvatarWireValue ?: fallbackAvatarWireValue,
        label = member.raftAvatarName(),
        size = size,
        cacheSubject = member.raftAvatarCacheSubject(),
        cacheServerId = member.serverId?.value,
    )
}

@Composable
private fun MembersHumanProfileSummary(
    member: MemberProfile,
    departure: MembersHumanDeparture?,
    isSelf: Boolean,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MembersHumanSectionPaddingX, vertical = 20.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        MembersStickerAvatar(member, RaftAvatarSize.Xl, memberAvatarResolver)
        Column(modifier = Modifier.weight(1f)) {
            // raft-ui PanelTitle: the name reads the same in the head and the body.
            RaftText(
                text = member.displayName,
                style = typography.headline,
                color = colors.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.raftCopyable(member.displayName)
            )
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftText(
                    text = member.detailHandle,
                    style = typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).raftCopyable(member.detailHandle)
                )
                if (departure != null) {
                    RaftBadge(
                        text = departure.localizedLabel(),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Muted
                    )
                }
                if (isSelf) {
                    RaftBadge(
                        text = stringResource(Res.string.member_label_you_suffix),
                        appearance = RaftBadgeAppearance.Outline
                    )
                }
            }
        }
    }
}

@Composable
private fun MembersHumanDescriptionSection(
    member: MemberProfile,
    currentUserId: String?,
    onUpdateDescription: (suspend (String?) -> DataResult<AccountDescriptionUpdateResult>)?,
    onApplyDescription: ((String?) -> Unit)?
) {
    val colors = RaftTheme.colors
    val errorStrings = localizedErrorPresentationStrings()
    val permissionErrorTemplate = membersStringTemplate(Res.string.member_error_permission)
    val descriptionTooLongMessage =
        stringResource(Res.string.agent_create_error_description_length, PROFILE_DESCRIPTION_MAX_LENGTH)
    val updateFailedFallback = stringResource(Res.string.member_error_update_description)
    val edgeBlockedMessage = stringResource(Res.string.error_edge_blocked)
    val editDescriptionLabel = stringResource(Res.string.member_action_edit_description)
    val canEdit = onUpdateDescription != null && membersHumanCanEditDescription(member, currentUserId)
    val scope = rememberCoroutineScope()
    val sessionToken = remember(member.id.value) { Any() }
    val currentSessionToken by rememberUpdatedState(sessionToken)
    val currentCanEdit by rememberUpdatedState(canEdit)
    val currentOnApplyDescription by rememberUpdatedState(onApplyDescription)
    var displayedDescription by remember(member.id.value) {
        mutableStateOf(member.description?.takeIf { it.isNotBlank() })
    }
    var editing by remember(member.id.value) { mutableStateOf(false) }
    var draft by remember(member.id.value) { mutableStateOf(member.description.orEmpty()) }
    var saving by remember(member.id.value) { mutableStateOf(false) }
    var saveGeneration by remember(member.id.value) { mutableStateOf(0L) }
    var saveError by remember(member.id.value) { mutableStateOf<String?>(null) }
    var saveErrorTraceId by remember(member.id.value) { mutableStateOf<String?>(null) }
    var linkWarnings by remember(member.id.value) { mutableStateOf<List<DescriptionLinkWarning>>(emptyList()) }

    LaunchedEffect(member.id.value, member.description) {
        if (!saving) {
            displayedDescription = member.description?.takeIf { it.isNotBlank() }
        }
    }

    val descriptionTooLong =
        remember(draft, displayedDescription) {
            profileDescriptionTooLong(draft, displayedDescription)
        }
    val shownError =
        saveError?.takeIf { it.isNotBlank() } ?: descriptionTooLongMessage.takeIf { descriptionTooLong }

    fun saveDescription() {
        val update = onUpdateDescription ?: return
        if (!canEdit || saving) return
        if (profileDescriptionTooLong(draft, displayedDescription)) {
            saveError = descriptionTooLongMessage
            saveErrorTraceId = null
            return
        }
        val editSessionToken = sessionToken
        val requestGeneration = saveGeneration + 1L
        saveGeneration = requestGeneration
        val nextDescription = profileDescriptionComparable(draft).takeIf { it.isNotEmpty() }
        saving = true
        saveError = null
        saveErrorTraceId = null
        scope.launch {
            try {
                val result =
                    try {
                        update(nextDescription)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError("member_description_save_failed"))
                    }
                if (currentSessionToken !== editSessionToken || saveGeneration != requestGeneration || !currentCanEdit) {
                    return@launch
                }
                when (result) {
                    is DataResult.Content -> {
                        val saved = result.data.description?.takeIf { it.isNotBlank() }
                        displayedDescription = saved
                        draft = saved.orEmpty()
                        linkWarnings = result.data.warnings
                        currentOnApplyDescription?.invoke(saved)
                        editing = false
                    }
                    is DataResult.Empty -> {
                        saveError = updateFailedFallback
                    }
                    is DataResult.NoPermission -> {
                        saveError =
                            membersFormattedPermissionError(
                                permissionErrorTemplate,
                                result.blocker,
                                errorStrings
                            )
                        saveErrorTraceId = result.blocker.traceId
                    }
                    is DataResult.Error -> {
                        saveError =
                            when {
                                result.error.serverCode == PROFILE_DESCRIPTION_INVALID_CODE -> descriptionTooLongMessage
                                result.error.serverMessage.isEdgeBlockedMarker() -> edgeBlockedMessage
                                !result.error.serverMessage.isNullOrBlank() -> result.error.serverMessage
                                else -> updateFailedFallback
                            }
                        saveErrorTraceId = result.error.traceId
                    }
                }
            } finally {
                if (currentSessionToken === editSessionToken && saveGeneration == requestGeneration) {
                    saving = false
                }
            }
        }
    }

    // Web HumanDetailPanel: eyebrow `mb-1` with inline Pencil button for self active human,
    // then `text-callout text-foreground-muted` (empty state in hint ink) + link warnings.
    MembersHumanSection(
        title = stringResource(Res.string.member_section_description),
        titleGap = 4.dp,
        accessory =
            if (canEdit && !editing) {
                {
                    RaftIconButton(
                        onClick = {
                            saveGeneration += 1L
                            draft = displayedDescription.orEmpty()
                            saveError = null
                            saveErrorTraceId = null
                            editing = true
                        },
                        tooltip = editDescriptionLabel,
                        variant = RaftButtonVariant.Ghost,
                        modifier = Modifier.membersInlineActionOverhang().testTag(MembersHumanDescriptionEditButtonTag)
                    ) {
                        MembersHumanInlineActionGlyph(RaftLucideIconName.Pencil)
                    }
                }
            } else {
                null
            }
    ) {
        if (canEdit && editing) {
            val descriptionFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                delay(100)
                runCatching { descriptionFocus.requestFocus() }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Intentionally uncapped while typing (matching MembersAgentProfile) so IME/paste
                // with outer whitespace isn't truncated mid-edit; Save is gated on the 300-char rule.
                RaftTextarea(
                    value = draft,
                    onValueChange = { next ->
                        draft = next
                        if (saveError != null) {
                            saveError = null
                            saveErrorTraceId = null
                        }
                    },
                    placeholder = stringResource(Res.string.member_placeholder_human_description),
                    enabled = !saving,
                    invalid = shownError != null,
                    modifier = Modifier.fillMaxWidth(),
                    inputModifier = Modifier.focusRequester(descriptionFocus).testTag(MembersHumanDescriptionInputTag)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RaftButton(
                        text = if (saving) stringResource(Res.string.member_action_saving) else stringResource(Res.string.common_save),
                        onClick = { saveDescription() },
                        enabled = !saving && !descriptionTooLong,
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Md,
                        modifier = Modifier.testTag(MembersHumanDescriptionSaveButtonTag)
                    )
                    RaftButton(
                        text = stringResource(Res.string.common_cancel),
                        onClick = {
                            saveGeneration += 1L
                            draft = displayedDescription.orEmpty()
                            saveError = null
                            saveErrorTraceId = null
                            editing = false
                        },
                        enabled = !saving,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        modifier = Modifier.testTag(MembersHumanDescriptionCancelButtonTag)
                    )
                }
                RaftProfileDescriptionFoot(
                    count = profileDescriptionLength(draft),
                    hint = null,
                    error = shownError,
                    enabled = !saving
                )
                if (saveError != null) RaftTraceIdNote(saveErrorTraceId)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RaftText(
                    text = displayedDescription ?: stringResource(Res.string.member_state_no_description),
                    style = RaftTheme.typography.callout,
                    color = if (displayedDescription == null) colors.foregroundHint else colors.foregroundMuted
                )
                RaftDescriptionLinkWarnings(
                    warnings = linkWarnings,
                    subject = DescriptionLinkSubject.Person,
                    onDismiss = { linkWarnings = emptyList() }
                )
            }
        }
    }
}

@Composable
private fun MembersHumanInfoSection(
    member: MemberProfile,
    departure: MembersHumanDeparture?,
    timeContext: RaftTimeContext,
    actorRole: String?,
    accessMode: MemberDetailAccessMode,
    currentUserId: String?,
    currentServerId: String?,
    ownerCount: Int?,
    onUpdateRole: (suspend (String, MembersHumanServerRole) -> DataResult<SettingsServerActionResult>)?
) {
    val errorStrings = localizedErrorPresentationStrings()
    val joinedDate = member.joinedAt.membersJoinedDateOrNull(timeContext)
    val joined = joinedDate?.let { membersLocalizedDate(it) }
    val initialRole = member.serverRole.toMembersHumanServerRoleOrNull()
        ?: member.roleDescription.toMembersHumanServerRoleOrNull()
    var displayedRole by remember(member.id.value, initialRole?.wireValue) { mutableStateOf(initialRole) }
    var draftRole by remember(member.id.value, initialRole?.wireValue) {
        mutableStateOf(initialRole ?: MembersHumanServerRole.Member)
    }
    var editingRole by remember(member.id.value) { mutableStateOf(false) }
    var roleMenuExpanded by remember(member.id.value) { mutableStateOf(false) }
    var savingRole by remember(member.id.value) { mutableStateOf(false) }
    var roleError by remember(member.id.value) { mutableStateOf<String?>(null) }
    var roleHelpOpen by remember(member.id.value) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val permissionErrorTemplate = membersStringTemplate(Res.string.member_error_permission)
    val saveErrorTemplate = membersStringTemplate(Res.string.member_error_save)
    val editableRoles = membersEditableHumanServerRoles(
        actorRole = actorRole,
        accessMode = accessMode,
        targetRole = displayedRole?.wireValue,
        targetMembershipStatus = member.membershipStatus,
        targetMemberId = member.id.value,
        currentUserId = currentUserId,
        targetServerId = member.serverId?.value,
        currentServerId = currentServerId,
        ownerCount = ownerCount
    )
    val canEditRole = departure == null && onUpdateRole != null && editableRoles.isNotEmpty()
    val roleBadge = membersHumanRoleBadge(
        serverRole = displayedRole?.wireValue ?: member.serverRole,
        roleDescription = member.roleDescription
    )
    val roleHelpLabel = stringResource(Res.string.member_title_human_roles)
    val editRoleLabel = stringResource(Res.string.member_action_edit_role)
    MembersHumanSection(title = stringResource(Res.string.member_section_info)) {
        // Web HumanDetailPanel: `stack-y-3` of KeyValueRows (label `mb-1`, then the value), not a
        // raft-ui DescriptionList: no 28dp value rows, and the inline icon buttons are `-my-1`.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MembersHumanKeyValue(
                term = if (departure != null) {
                    stringResource(Res.string.member_label_status)
                } else {
                    stringResource(Res.string.member_label_role)
                },
                accessory = if (departure != null) {
                    null
                } else {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RaftIconButton(
                                onClick = { roleHelpOpen = true },
                                tooltip = roleHelpLabel,
                                variant = RaftButtonVariant.Ghost,
                                modifier = Modifier.membersInlineActionOverhang()
                            ) {
                                MembersHumanInlineActionGlyph(RaftLucideIconName.CircleQuestionMark)
                            }
                            if (canEditRole && !editingRole) {
                                RaftIconButton(
                                    onClick = {
                                        draftRole = editableRoles.first()
                                        roleError = null
                                        roleMenuExpanded = true
                                        editingRole = true
                                    },
                                    tooltip = editRoleLabel,
                                    variant = RaftButtonVariant.Ghost,
                                    // `ml-1`: 12dp from the help button, as on the web.
                                    modifier = Modifier.padding(start = 4.dp).membersInlineActionOverhang()
                                ) {
                                    MembersHumanInlineActionGlyph(RaftLucideIconName.Pencil)
                                }
                            }
                        }
                    }
                }
            ) {
                when {
                    departure != null ->
                        RaftBadge(
                            text = departure.localizedLabel(),
                            appearance = RaftBadgeAppearance.Soft,
                            variant = RaftBadgeVariant.Muted
                        )
                    editingRole && canEditRole ->
                        // TODO(human-profile): the inline role editor is shared with the agent
                        // profile and still uses the older chip/button styling.
                        MembersServerRoleEditor(
                            displayValue = draftRole.localizedLabel(),
                            expanded = roleMenuExpanded,
                            enabled = !savingRole,
                            saving = savingRole,
                            saveEnabled = draftRole != displayedRole,
                            options = editableRoles.map { role ->
                                RaftUiSelectionOption(
                                    id = role.wireValue,
                                    label = role.localizedLabel(),
                                    selected = role == draftRole
                                )
                            },
                            selectedId = draftRole.wireValue,
                            error = roleError,
                            onToggle = { roleMenuExpanded = !roleMenuExpanded },
                            onSelect = { roleId ->
                                roleId.toMembersHumanServerRoleOrNull()?.let {
                                    draftRole = it
                                    roleMenuExpanded = false
                                    roleError = null
                                }
                            },
                            onSave = {
                                onUpdateRole?.let { update -> scope.launch {
                                    savingRole = true
                                    roleError = null
                                    val result = try {
                                        update(member.id.value, draftRole)
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (_: Throwable) {
                                        DataResult.Error(ScreenError("member_role_save_failed"))
                                    }
                                    when (result.membersHumanRoleSaveOutcome()) {
                                        MembersHumanRoleSaveOutcome.Success -> {
                                            displayedRole = draftRole
                                            editingRole = false
                                            roleMenuExpanded = false
                                        }
                                        MembersHumanRoleSaveOutcome.NoPermission -> {
                                            val blocker = (result as DataResult.NoPermission).blocker
                                            roleError =
                                                membersFormattedPermissionError(
                                                    permissionErrorTemplate,
                                                    blocker,
                                                    errorStrings
                                                )
                                        }
                                        MembersHumanRoleSaveOutcome.Error -> {
                                            val message =
                                                (result as? DataResult.Error)?.error?.userFacingMessage(errorStrings)
                                                ?: (result as? DataResult.Content)?.data?.message
                                                ?: "member_role_save_failed".userFacingErrorCodeMessage(errorStrings)
                                            roleError = saveErrorTemplate.membersFormat(message)
                                        }
                                    }
                                    savingRole = false
                                } }
                            },
                            onCancel = {
                                draftRole = displayedRole ?: MembersHumanServerRole.Member
                                roleError = null
                                roleMenuExpanded = false
                                editingRole = false
                            }
                        )
                    else ->
                        RaftBadge(
                            text = roleBadge.localizedLabel(),
                            appearance = RaftBadgeAppearance.Soft,
                            variant = roleBadge.badgeVariant
                        )
                }
            }
            member.email?.takeIf { it.isNotBlank() }?.let { email ->
                MembersHumanKeyValue(term = stringResource(Res.string.member_label_email)) {
                    RaftDescriptionValue(email)
                }
            }
            // Web GuestChannelRow: "Channel: Guest in #x" for a Guest whose channel the viewer may read.
            member.guestChannel?.name?.takeIf {
                it.isNotBlank() && displayedRole == null && member.serverRole.normalizedMembersServerRole() == "guest"
            }?.let { channelName ->
                MembersHumanKeyValue(term = stringResource(Res.string.member_label_channel)) {
                    RaftDescriptionValue(stringResource(Res.string.member_guest_in_channel, channelName))
                }
            }
            joined?.takeIf { it.isNotBlank() }?.let { joinedText ->
                MembersHumanKeyValue(term = stringResource(Res.string.member_label_joined)) {
                    RaftDescriptionValue(joinedText, mono = true)
                }
            }
        }
    }
    if (roleHelpOpen) {
        MembersHumanRoleHelpDialog(onDismiss = { roleHelpOpen = false })
    }
}

@Composable
private fun MembersHumanDeparture.localizedLabel(): String =
    when (this) {
        MembersHumanDeparture.Left -> stringResource(Res.string.member_status_left)
        MembersHumanDeparture.Removed -> stringResource(Res.string.member_role_removed)
    }

@Composable
private fun MembersHumanRoleBadge.localizedLabel(): String =
    when (this) {
        MembersHumanRoleBadge.Owner -> stringResource(Res.string.member_role_owner)
        MembersHumanRoleBadge.Admin -> stringResource(Res.string.member_role_admin)
        MembersHumanRoleBadge.Member -> stringResource(Res.string.member_role_member)
        MembersHumanRoleBadge.Guest -> stringResource(Res.string.channel_member_role_guest)
        MembersHumanRoleBadge.Unknown -> stringResource(Res.string.member_role_unknown)
    }

@Composable
private fun MembersHumanRoleHelpDialog(onDismiss: () -> Unit) {
    RaftUnifiedDialog(
        title = stringResource(Res.string.member_title_human_roles),
        onDismiss = onDismiss,
        // Web RolePermissionHelpDialog: DialogCard max-w-md, rows stack-y-2.
        maxWidth = 448.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MembersAgentRoleHelpRow(
                role = stringResource(Res.string.member_role_owner),
                summary = stringResource(Res.string.member_roles_help_owner_summary),
                details = stringResource(Res.string.member_roles_help_owner_details)
            )
            MembersAgentRoleHelpRow(
                role = stringResource(Res.string.member_role_admin),
                summary = stringResource(Res.string.member_roles_help_admin_summary),
                details = stringResource(Res.string.member_roles_help_human_admin_details)
            )
            MembersAgentRoleHelpRow(
                role = stringResource(Res.string.member_role_member),
                summary = stringResource(Res.string.member_roles_help_member_summary),
                details = stringResource(Res.string.member_roles_help_human_member_details)
            )
        }
    }
}

@Composable
internal fun MembersServerRoleEditor(
    displayValue: String,
    expanded: Boolean,
    enabled: Boolean,
    saving: Boolean,
    saveEnabled: Boolean,
    options: List<RaftUiSelectionOption>,
    selectedId: String,
    error: String?,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
    ) {
        val roleChipShape = RoundedCornerShape(RaftAppRadius.Chip)
        Row(
            modifier = Modifier
                .widthIn(min = 160.dp)
                .height(33.dp)
                // Web InlineBadgeEditor with the roles' `default` soft badge: the fill-muted wash, no border.
                .clip(roleChipShape)
                .background(RaftColor.FillMuted, roleChipShape)
                .raftClickable(onClick = onToggle, enabled = enabled, shape = roleChipShape)
                .padding(horizontal = RaftAppSpacing.Sm, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayValue,
                // Web InlineBadgeEditor soft Badge: text-caption font-semibold (12/16 600).
                style = RaftType.Caption.withRaftFontWeight(FontWeight.SemiBold),
                color = RaftColor.TextBlack,
                modifier = Modifier.weight(1f)
            )
            RaftLucideIcon(
                RaftLucideIconName.ChevronDown,
                modifier = Modifier.size(14.dp),
                color = RaftColor.TextBlackArgb,
                rotationDegrees = if (expanded) 180f else 0f
            )
        }
        if (expanded && enabled) {
            RaftUiSelectionPopover(
                title = stringResource(Res.string.member_label_role),
                options = options,
                selectedId = selectedId,
                modifier = Modifier.widthIn(min = 160.dp),
                showHeader = false,
                onSelect = { option -> onSelect(option.id) }
            )
        }
        // Web HumanDetailPanel role editor: accent md Save, default md Cancel.
        Row(horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
            RaftButton(
                text = if (saving) stringResource(Res.string.member_action_saving) else stringResource(Res.string.common_save),
                onClick = onSave,
                enabled = enabled && saveEnabled,
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Md
            )
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onCancel,
                enabled = enabled,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md
            )
        }
        error?.let {
            Text(
                text = it,
                // Web HumanDetailPanel: text-caption font-semibold (12/16 600).
                style = RaftType.Caption.withRaftFontWeight(FontWeight.SemiBold),
                color = RaftColor.AccentRed
            )
        }
    }
}

@Composable
private fun MembersHumanCreatedAgentsSection(
    member: MemberProfile,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    fixtureStatusFallbackEnabled: Boolean = false,
    onOpenCreatedAgent: ((AgentProfile) -> Unit)? = null
) {
    val createdAgents = member.createdAgents
    MembersHumanSection(
        title = stringResource(Res.string.member_section_created_agents),
        count = createdAgents.size.toString()
    ) {
        if (createdAgents.isEmpty()) {
            RaftText(
                text = stringResource(Res.string.member_state_no_created_agents),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundHint
            )
        } else {
            // Web: raft-ui ListRowGroup (the inset card) of AvatarListRows, hairlines between rows.
            RaftListRowGroup(modifier = Modifier.fillMaxWidth()) {
                createdAgents.forEachIndexed { index, agent ->
                    MembersHumanCreatedAgentRow(
                        agent = agent,
                        // The agents a person created on this server: its runtime catalog names them.
                        serverId = member.serverId?.value,
                        divider = index > 0,
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
                        fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled,
                        onClick = onOpenCreatedAgent?.let { open -> { open(agent) } }
                    )
                }
            }
        }
    }
}

/**
 * Web HumanProfileActions' promote ConfirmDialog: "Make {name} a member of {server}?", what it
 * takes (a seat), Cancel and the accent "Make member" (loading "Promoting…").
 */
@Composable
internal fun MembersPromoteGuestConfirmDialog(
    personName: String,
    serverName: String,
    promoting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.member_promote_dialog_title, personName, serverName),
        description = stringResource(Res.string.member_promote_dialog_message, serverName),
        dismissOnClickOutside = !promoting,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !promoting,
            )
            RaftButton(
                text = stringResource(Res.string.member_promote_dialog_confirm),
                onClick = { if (!promoting) onConfirm() },
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                loading = promoting,
                loadingLabel = stringResource(Res.string.member_promote_dialog_promoting),
            )
        },
        content =
            if (errorMessage != null) {
                { RaftBanner(description = errorMessage, status = RaftBannerStatus.Warning) }
            } else {
                null
            },
    )
}

@Composable
private fun MembersHumanActionsSection(
    onRemoveMember: (() -> Unit)?,
    onPromoteGuest: (() -> Unit)? = null,
    promotingGuest: Boolean = false
) {
    MembersHumanSection(title = stringResource(Res.string.member_section_actions)) {
        // Web HumanProfileActions: `stack-y-2`, Promote to server member (a Guest, design D2)
        // above Remove Member.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onPromoteGuest != null) {
                // Web: raft-ui Button size="lg" variant="default", full width, UserPlus 18.
                RaftButton(
                    text = stringResource(Res.string.member_action_promote_to_member),
                    onClick = onPromoteGuest,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                    enabled = !promotingGuest,
                    leadingIcon = { MembersHumanGlyph(RaftLucideIconName.UserPlus, 18.dp) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (onRemoveMember != null) {
                // Web: raft-ui Button size="lg" variant="danger", full width, Trash2 18.
                RaftButton(
                    text = stringResource(Res.string.member_action_remove_member),
                    onClick = onRemoveMember,
                    variant = RaftButtonVariant.Danger,
                    size = RaftButtonSize.Lg,
                    leadingIcon = { MembersHumanGlyph(RaftLucideIconName.Trash2, 18.dp) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun MembersHumanCreatedAgentRow(
    agent: AgentProfile,
    serverId: String?,
    divider: Boolean,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    fixtureStatusFallbackEnabled: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val profile = remember(agent) { agent.toStaticMembersProfile() }
    val observedIndicatorState =
        resolveMembersAgentStatusIndicatorStateOrNull(
            canonicalState = rememberRaftAgentStatusIndicatorState(agentStatusResolver, profile.id.value),
            fixtureFallback =
                if (fixtureStatusFallbackEnabled) {
                    agent.toFixtureRaftAgentStatusIndicatorState()
                } else {
                    null
                },
            fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled
        )
    val observedDisplayState = observedIndicatorState?.toMembersAgentStatusDisplayState()
    // Web CreatedAgentRow: AvatarListRow with the list-row avatar, the name, the runtime as the
    // mono subtitle and the StatusDot on the right; the row opens the agent's profile.
    RaftListRow(
        title = profile.displayName,
        onClick = onClick,
        divider = divider,
        subtitle = agent.runtime?.takeIf { it.isNotBlank() }?.let { runtimeLabelWithStatus(it, serverId) }
            ?: profile.roleDescription?.takeIf { it.isNotBlank() }
            ?: stringResource(Res.string.member_label_agent),
        leading = { MembersStickerAvatar(profile, RaftAvatarSize.Md, memberAvatarResolver) },
        trailing = observedDisplayState?.let { observed ->
            {
                // Unknown is not Offline: no dot until something is observed (task #256).
                RaftStatusIndicator(
                    color = observed.color,
                    size = 10.dp,
                )
            }
        }
    )
}

private fun AgentProfile.toFixtureRaftAgentStatusIndicatorState(): RaftAgentStatusIndicatorState =
    raftAgentStatusIndicatorState(
        status = agentStatus,
        presence = presence,
        statusLabel = statusLabel,
        external = isExternal
    )

private fun AgentProfile.toStaticMembersProfile(): MemberProfile =
    toMemberProfile().copy(
        presence = Presence.Offline,
        agentStatus = null
    )
