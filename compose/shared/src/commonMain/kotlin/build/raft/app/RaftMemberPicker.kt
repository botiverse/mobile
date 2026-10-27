package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.rememberRaftDesignSystemEditor
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

/**
 * One pick in a [RaftMemberPicker]: agents and humans keep separate id spaces, so the kind is
 * part of the key.
 */
internal data class RaftMemberPickerKey(val kind: MemberKind, val id: String)

internal val MemberProfile.memberPickerKey: RaftMemberPickerKey
    get() = RaftMemberPickerKey(kind, id.value)

internal enum class RaftMemberPickerMode {
    /** A check box per row; any number of members (the create-channel dialogs). */
    Multiple,

    /** One member; the picked row is highlighted with a check (an assignee menu). */
    Single,
}

/** The rows a picker shows for a query: agents first, then humans, as the web groups them. */
internal data class RaftMemberPickerSections(
    val agents: List<MemberProfile>,
    val humans: List<MemberProfile>,
) {
    val isEmpty: Boolean get() = agents.isEmpty() && humans.isEmpty()
}

/**
 * Web `usePeopleSuggestionSearch` (slock `utils/peopleSuggestionSearch.ts` +
 * `composerSuggestionSearch.ts`): agents then humans as candidates, ranked by handle (priority
 * 0), display name (1), description (3) and source server (4) through the shared composer
 * ranking — exact, prefix, word prefix, substring, pinyin, then fuzzy. A blank query keeps
 * every candidate in roster order (the web does not cap it); a query keeps the 30 best.
 * Only agents and humans are candidates.
 */
internal fun rankRaftMemberPickerCandidates(query: String, members: List<MemberProfile>): RaftMemberPickerSections {
    val candidates = members.filter { it.kind == MemberKind.Agent } + members.filter { it.kind == MemberKind.Human }
    val ranked =
        if (ThreadComposerSuggestionQueryKey.from(query).isBlank) {
            candidates
        } else {
            filterPeopleSuggestions(
                query = query,
                suggestions = candidates,
                handle = { it.handle },
                displayName = { it.displayName },
                description = { it.description?.takeIf(String::isNotBlank) ?: it.roleDescription },
                sourceServerLabel = { it.serverName ?: it.serverSlug },
            )
        }
    return RaftMemberPickerSections(
        agents = ranked.filter { it.kind == MemberKind.Agent },
        humans = ranked.filter { it.kind == MemberKind.Human },
    )
}

/**
 * The selection after tapping [key]: [RaftMemberPickerMode.Multiple] toggles it,
 * [RaftMemberPickerMode.Single] makes it the only pick (tapping the picked row keeps it).
 */
internal fun raftMemberPickerToggle(
    selected: Set<RaftMemberPickerKey>,
    key: RaftMemberPickerKey,
    mode: RaftMemberPickerMode,
): Set<RaftMemberPickerKey> =
    when (mode) {
        RaftMemberPickerMode.Multiple -> if (key in selected) selected - key else selected + key
        RaftMemberPickerMode.Single -> setOf(key)
    }

/**
 * The shared member / agent picker (web `CreateChannelDialog` / `CreateJointChannelDialog`
 * member field, `memberPickerClasses.ts`): a search field over a card list grouped into
 * Agents and Humans, each row an avatar with the agent status dot, the name, and a check box
 * ([RaftMemberPickerMode.Multiple]) or a picked highlight ([RaftMemberPickerMode.Single]).
 * The list scrolls inside the card up to [listMaxHeight] (web `max-h-48`).
 *
 * The caller owns [query] and [selectedKeys]; [onToggle] reports the tapped row's key
 * ([raftMemberPickerToggle] computes the next set for a caller that holds one). With no
 * members the picker shows a loading or "no members" line instead of the search.
 */
@Composable
internal fun RaftMemberPicker(
    members: List<MemberProfile>,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    selectedKeys: Set<RaftMemberPickerKey>,
    onToggle: (RaftMemberPickerKey) -> Unit,
    modifier: Modifier = Modifier,
    mode: RaftMemberPickerMode = RaftMemberPickerMode.Multiple,
    loading: Boolean = false,
    enabled: Boolean = true,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    fixtureStatusFallbackEnabled: Boolean = false,
    listMaxHeight: Dp = 192.dp,
    onSearchFocus: () -> Unit = {},
    onSearchBlur: () -> Unit = {},
) {
    RaftDesignSystemTheme {
        val hint = RaftTheme.colors.foregroundHint
        if (members.none { it.kind == MemberKind.Agent || it.kind == MemberKind.Human }) {
            RaftText(
                stringResource(if (loading) Res.string.settings_members_loading else Res.string.joint_create_no_members),
                modifier = modifier,
                style = RaftTheme.typography.callout,
                color = hint,
            )
            return@RaftDesignSystemTheme
        }
        val sections = remember(query.text, members) { rankRaftMemberPickerCandidates(query.text, members) }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val editor =
                rememberRaftDesignSystemEditor(
                    keyboardOptions = KeyboardOptions.Default,
                    keyboardActions = KeyboardActions.Default,
                    keyboardHeightChange = {},
                    focusRequester = remember { FocusRequester() },
                    autoFocus = false,
                    onFocus = onSearchFocus,
                    onBlur = onSearchBlur,
                )
            RaftTextField(
                value = query,
                onValueChange = { if (enabled) onQueryChange(it) },
                modifier = Modifier.fillMaxWidth().then(editor.modifier),
                placeholder = stringResource(Res.string.joint_create_members_search),
                enabled = enabled,
                leading = { RaftSlotIcon(RaftLucideIconName.Search) },
                keyboardOptions = editor.keyboardOptions,
            )
            RaftListRowGroup(Modifier.fillMaxWidth().retainInputFocusForInteractions()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMaxHeight).padding(4.dp)) {
                    if (sections.isEmpty) {
                        item(key = "no-matches") {
                            RaftText(
                                stringResource(Res.string.joint_create_no_matches, query.text.trim()),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                style = RaftTheme.typography.callout,
                                color = hint,
                            )
                        }
                    }
                    listOf(
                        Res.string.joint_create_agents to sections.agents,
                        Res.string.joint_create_humans to sections.humans,
                    ).forEach { (label, group) ->
                        if (group.isEmpty()) return@forEach
                        item(key = "label-${group.first().kind.name}") {
                            RaftSectionEyebrow(
                                stringResource(label),
                                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }
                        group.forEach { member ->
                            val key = member.memberPickerKey
                            item(key = key.toString()) {
                                RaftMemberPickerRow(
                                    member = member,
                                    picked = key in selectedKeys,
                                    mode = mode,
                                    enabled = enabled,
                                    agentStatusResolver = agentStatusResolver,
                                    fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled,
                                    onClick = { onToggle(key) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One picker option: web `PICKER_ROW_CLASS` over the RaftModalCompactMemberRow visuals. */
@Composable
private fun RaftMemberPickerRow(
    member: MemberProfile,
    picked: Boolean,
    mode: RaftMemberPickerMode,
    enabled: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    fixtureStatusFallbackEnabled: Boolean,
    onClick: () -> Unit,
) {
    // web `CreateChannelDialog` / `CreateJointChannelDialog`: an App Bot's name carries the Bot
    // badge, and it has no presence to show.
    val appBot = remember(member) { member.appBotIdentityOrNull() }
    // Dot-facing resolution: the OrNull variant, so an agent whose status is not known yet
    // shows no dot rather than an Offline-grey one (tasks #253 / #256).
    val status =
        if (member.kind == MemberKind.Agent && appBot == null) {
            resolveMembersAgentStatusIndicatorStateOrNull(
                canonicalState = rememberRaftAgentStatusIndicatorState(agentStatusResolver, member.id.value),
                fixtureFallback =
                    raftAgentStatusIndicatorState(
                        status = member.agentStatus,
                        presence = member.presence,
                        external = member.isExternal,
                    ),
                fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled,
            )
        } else {
            null
        }
    val single = mode == RaftMemberPickerMode.Single
    val highlighted = single && picked
    val selection =
        if (single) {
            Modifier.selectable(selected = picked, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        } else {
            Modifier.toggleable(value = picked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
        }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RaftRadius.Chip))
            .then(if (highlighted) Modifier.background(RaftColor.BrandYellow) else Modifier)
            .then(selection)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!single) RaftCheckboxIndicator(checked = picked, enabled = enabled)
        RaftModalCompactMemberRowContent(
            title = member.displayName.ifBlank { member.handle },
            subtitle = "",
            avatarWireValue = member.avatarWireValueForAvatarSlot(),
            avatarLabel = member.raftAvatarName(),
            statusColor = status?.color,
            statusPulsing = status?.pulsing == true,
            avatarSize = 28.dp,
            sourceBadgeLabel = null,
            enabled = enabled,
            selected = highlighted,
            trailingText = "",
            trailingIcon = if (highlighted) RaftLucideIconName.Check else null,
            onTrailingClick = null,
            cacheSubject = member.raftAvatarCacheSubject(),
            cacheServerId = member.serverId?.value,
            avatarFill = raftAvatarFallbackFill(member.kind),
            titleBadge = appBot?.let { bot -> { RaftAppBotBadge(identity = bot, botName = member.displayName.ifBlank { member.handle }) } },
        )
    }
}
