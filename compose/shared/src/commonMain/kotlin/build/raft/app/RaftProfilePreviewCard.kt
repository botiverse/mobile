package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.memberOrigin
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberServerRef
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.data.runtimeCatalogDisplayName
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftPreviewCard
import build.raft.designsystem.components.RaftPreviewCardActions
import build.raft.designsystem.components.RaftPreviewCardDescription
import build.raft.designsystem.components.RaftPreviewCardHeader
import build.raft.designsystem.components.RaftPreviewCardSeparator
import build.raft.designsystem.components.RaftPreviewCardTitle
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.flowOf

/** Whether the mentioned agent has read the message the mention is in (web `readReceipt`). */
internal enum class RaftProfilePreviewReadReceipt { Read, Unread }

/**
 * What the profile card shows. Fill it from the members directory with
 * [rememberRaftProfilePreviewCardModel] (or [raftProfilePreviewCardModel] from a
 * [MemberProfile]), or by hand in a fixture.
 */
@Immutable
internal data class RaftProfilePreviewCardModel(
    val memberId: String,
    /** [MemberKind.Human] or [MemberKind.Agent] (the mention's type). */
    val kind: MemberKind,
    /** The name on the card: an App Bot's App name when known (web `managedByApp.name`). */
    val displayName: String,
    /** The @handle without the `@`; null hides the line. */
    val handle: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = displayName.toRaftAvatarLabel(),
    /** One line under a hairline, truncated (raft-ui `PreviewCardDescription`). */
    val description: String? = null,
    /** An App Bot: the Bot badge after the name and "Managed by app"; no presence, no runtime. */
    val appBot: RaftAppBotIdentity? = null,
    /** An agent's live status: the light and its words after the name. Null shows neither. */
    val status: RaftAgentStatusIndicatorState? = null,
    /** An agent's runtime and model (web Runtime / Model rows); null leaves the row out. */
    val runtimeLabel: String? = null,
    /** The runtime behind [runtimeLabel]: its lifecycle status (" (experimental)") is added when drawn. */
    val runtimeId: String? = null,
    val modelLabel: String? = null,
    /** Where an agent runs (web Computer row, above Runtime); null leaves the row out. */
    val computer: RaftProfilePreviewComputer? = null,
    val readReceipt: RaftProfilePreviewReadReceipt? = null,
    /** The server whose avatar cache holds the face. */
    val serverId: String? = null,
    /**
     * Someone from another server than the viewed one ([memberOrigin], web #14535): the ribbon
     * across the card's top with [originServers] and "External"; an agent shows none of this
     * server's facts (Computer, Runtime, Model, Managed by app).
     */
    val external: Boolean = false,
    /** The servers the ribbon names, the viewed one left out; may be empty (none was named). */
    val originServers: List<MemberServerRef> = emptyList(),
    /**
     * Not in the directory (a cross-server mention): the handle (or "Agent" / "Member") and
     * "Profile unavailable", never an empty card.
     */
    val unavailable: Boolean = false,
) {
    companion object {
        fun unavailable(memberId: String, kind: MemberKind, fallbackHandle: String?): RaftProfilePreviewCardModel {
            val handle = fallbackHandle?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
            return RaftProfilePreviewCardModel(
                memberId = memberId,
                kind = kind,
                displayName = handle?.let { "@$it" }.orEmpty(),
                avatarLabel = handle?.toRaftAvatarLabel().orEmpty(),
                unavailable = true,
            )
        }
    }
}

/** An agent's Computer row (web `ProfilePreviewCardContent` `computerLabel`). */
internal sealed interface RaftProfilePreviewComputer {
    data class Named(val name: String) : RaftProfilePreviewComputer

    /** An external agent runs outside Raft's Computers: "External runtime". */
    data object External : RaftProfilePreviewComputer

    /** "No computer assigned". */
    data object Unassigned : RaftProfilePreviewComputer
}

/** The card's model for a directory [profile] (web `ProfilePreviewCardContent`'s projections). */
internal fun raftProfilePreviewCardModel(
    profile: MemberProfile,
    status: RaftAgentStatusIndicatorState? = null,
    avatarWireValue: String? = null,
    readReceipt: RaftProfilePreviewReadReceipt? = null,
    /** The server the card is opened on; where the profile is from is read against it. */
    viewerServerId: String? = null,
): RaftProfilePreviewCardModel {
    val appBot = profile.appBotIdentityOrNull()
    val isAgent = profile.kind == MemberKind.Agent
    val origin = memberOrigin(profile, viewerServerId)
    // web `hideAgentServerFacts`: another server's agent comes with its identity only.
    val agentFacts = isAgent && appBot == null && !origin.external
    val runtimeId = (status?.runtime ?: profile.roleDescription)?.trim()?.takeIf { it.isNotBlank() }
    val displayName = appBot?.appName ?: profile.displayName.ifBlank { profile.handle }
    return RaftProfilePreviewCardModel(
        memberId = profile.id.value,
        kind = if (isAgent) MemberKind.Agent else MemberKind.Human,
        displayName = displayName,
        handle = profile.handle.trim().removePrefix("@").takeIf { it.isNotBlank() },
        avatarWireValue = avatarWireValue ?: profile.avatarWireValueForAvatarSlot(),
        avatarLabel = profile.avatarLabel,
        // An App Bot's description is its App's (web `managedByApp.description`), which the
        // directory doesn't carry: none rather than the Bot row's own, possibly stale, one.
        description = if (appBot != null) null else profile.description?.trim()?.takeIf { it.isNotBlank() },
        appBot = appBot,
        status = if (isAgent && appBot == null) status else null,
        runtimeLabel = if (agentFacts) runtimeId?.let { runtimeIdentityDisplayName(it, profile.serverId?.value) } else null,
        runtimeId = if (agentFacts) runtimeId else null,
        modelLabel = if (agentFacts) profile.modelLabel() else null,
        computer =
            when {
                !agentFacts -> null
                profile.isExternal -> RaftProfilePreviewComputer.External
                else ->
                    profile.permissionBadges
                        .firstNotNullOfOrNull { badge -> badge.removePrefix("computer:").takeIf { badge.startsWith("computer:") && it.isNotBlank() } }
                        ?.let(RaftProfilePreviewComputer::Named)
                        ?: RaftProfilePreviewComputer.Unassigned
            },
        readReceipt = if (isAgent && appBot == null) readReceipt else null,
        serverId = profile.serverId?.value,
        external = origin.external,
        originServers = origin.servers,
    )
}

/**
 * The card's model for [memberId] from [serverMembersStore], live: the directory profile, the
 * agent's status ([agentStatusResolver]) and the face ([memberAvatarResolver]); the Thread route
 * already holds both resolvers, pass them. [kind] is the mention's type; [fallbackHandle] (the
 * mention's words) names a member the directory doesn't know.
 */
@Composable
internal fun rememberRaftProfilePreviewCardModel(
    memberId: String,
    kind: MemberKind,
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?,
    fallbackHandle: String? = null,
    readReceipt: RaftProfilePreviewReadReceipt? = null,
    agentStatusResolver: RaftAgentStatusResolver = remember(serverMembersStore, serverId) { raftAgentStatusResolver(serverMembersStore, serverId) },
    memberAvatarResolver: RaftMemberAvatarResolver = remember(serverMembersStore, serverId) { raftMemberAvatarResolver(serverMembersStore, serverId) },
    /** The server the card is opened on (the profile may be read from the member's own [serverId]). */
    viewerServerId: String? = serverId,
): RaftProfilePreviewCardModel {
    val cleanServerId = remember(serverId) { serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId) }
    val profileFlow =
        remember(serverMembersStore, cleanServerId, memberId) {
            if (serverMembersStore == null || cleanServerId == null) flowOf(null) else serverMembersStore.memberProfileFlow(cleanServerId, memberId)
        }
    val initialProfile =
        remember(serverMembersStore, cleanServerId, memberId) {
            if (serverMembersStore == null || cleanServerId == null) null else serverMembersStore.memberProfile(cleanServerId, memberId)
        }
    val profile by profileFlow.collectAsState(initialProfile)
    val status = rememberRaftAgentStatusIndicatorState(agentStatusResolver, memberId.takeIf { kind == MemberKind.Agent })
    val avatar = rememberRaftMemberAvatarWireValue(memberAvatarResolver, memberId)
    val current = profile
    return remember(current, status, avatar, readReceipt, memberId, kind, fallbackHandle, viewerServerId) {
        if (current == null) {
            RaftProfilePreviewCardModel.unavailable(memberId, kind, fallbackHandle)
        } else {
            raftProfilePreviewCardModel(current, status, avatar, readReceipt, viewerServerId)
        }
    }
}

/**
 * web `ProfilePreviewCardContent` in raft-ui's PreviewCard parts, for the `card` of a
 * [RaftPreviewCard] (or inside a `RaftPreviewCardSurface` in a fixture):
 *
 * - Someone from another server ([RaftProfilePreviewCardModel.external]): the ribbon across the
 *   card's top first ([RaftProfilePreviewExternalRibbon]: their servers and "External"); an agent
 *   then has no Computer / Runtime / Model or Managed by app rows.
 * - Header: the large avatar ([RaftAvatarSize.Lg], 51dp) and, 12dp beside it, the name
 *   (headline, one line); after it the Bot badge for an App Bot, or an agent's status light
 *   (`Status sm`) and its words (`text-callout` `foreground-hint`). Then the `@handle` in Space
 *   Mono `foreground-muted`, "Read" / "Not read yet" (caption `foreground-hint`) for an agent
 *   mention whose read state is known, and the facts: "Managed by app" for an App Bot; a hairline
 *   and Runtime / Model for an agent (labels caption `foreground-hint`, values mono `foreground-muted`).
 * - The description under a hairline, one line, truncated.
 * - [onMessage] / [onViewProfile]: Message and View Profile, Md buttons (raft-ui allows two;
 *   the web card has none, its mention is the link to the profile). Each should also dismiss
 *   the card. Neither given: no buttons.
 * - [RaftProfilePreviewCardModel.unavailable]: the handle (or Agent / Member) and "Profile
 *   unavailable" in footnote `foreground-muted`.
 *
 * ```
 * val state = rememberRaftPreviewCardState()
 * val model = rememberRaftProfilePreviewCardModel(memberId, MemberKind.Agent, serverMembersStore, serverId, fallbackHandle = "bugen")
 * RaftPreviewCard(card = { RaftProfilePreviewCardContent(model, onViewProfile = { state.dismiss(); openProfile(memberId) }) }, state = state) {
 *     mention()
 * }
 * ```
 */
@Composable
internal fun RaftProfilePreviewCardContent(
    model: RaftProfilePreviewCardModel,
    onMessage: (() -> Unit)? = null,
    onViewProfile: (() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    // Member labels (web #14535 ExternalOriginRibbon): someone from another server, a band across
    // the card's top naming their servers and "External".
    RaftProfilePreviewExternalRibbon(external = model.external && !model.unavailable, servers = model.originServers)
    if (model.unavailable) {
        RaftPreviewCardHeader(leading = { ProfilePreviewAvatar(model) }) {
            val fallback =
                if (model.kind == MemberKind.Agent) stringResource(Res.string.member_label_agent) else stringResource(Res.string.member_role_member)
            RaftPreviewCardTitle(model.displayName.ifBlank { fallback })
            RaftText(
                text = stringResource(Res.string.profile_preview_unavailable),
                style = typography.footnote,
                color = colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    RaftPreviewCardHeader(leading = { ProfilePreviewAvatar(model) }) {
        // Web `flex items-center gap-1.5`: the name, then the Bot badge or the status.
        Row(verticalAlignment = Alignment.CenterVertically) {
            RaftPreviewCardTitle(model.displayName, Modifier.weight(1f, fill = false))
            val appBot = model.appBot
            val status = model.status
            if (appBot != null) {
                RaftAppBotBadge(identity = appBot, botName = model.displayName, modifier = Modifier.padding(start = 6.dp))
            } else if (status != null) {
                RaftStatusDot(Modifier.padding(start = 6.dp), size = RaftStatusSize.Sm, color = status.color)
                RaftText(
                    text = status.localizedMemberStatusLabel(),
                    modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
                    style = typography.callout,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        model.handle?.let { handle ->
            RaftText(
                text = "@$handle",
                style = typography.monoMeta,
                color = colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        model.readReceipt?.let { read ->
            RaftText(
                text =
                    stringResource(
                        if (read == RaftProfilePreviewReadReceipt.Read) Res.string.thread_mention_agent_read else Res.string.thread_mention_agent_unread,
                    ),
                modifier = Modifier.padding(top = 4.dp),
                style = typography.caption,
                color = colors.foregroundHint,
                maxLines = 1,
            )
        }
        val appBot = model.appBot
        if (appBot != null && !model.external) {
            ProfilePreviewFacts(
                listOf(stringResource(Res.string.profile_preview_managed_by_app) to (appBot.appName ?: stringResource(Res.string.profile_preview_unknown_app))),
                Modifier.padding(top = 8.dp),
            )
        } else if (model.kind == MemberKind.Agent && (model.runtimeLabel != null || model.modelLabel != null)) {
            // Web `<Separator className="mt-2" />` then the dl, `pt-2`.
            RaftSeparator(Modifier.padding(top = 8.dp))
            ProfilePreviewFacts(
                listOfNotNull(
                    model.computer?.let { computer ->
                        stringResource(Res.string.member_label_computer) to
                            when (computer) {
                                is RaftProfilePreviewComputer.Named -> computer.name
                                RaftProfilePreviewComputer.External -> stringResource(Res.string.profile_preview_external_runtime)
                                RaftProfilePreviewComputer.Unassigned -> stringResource(Res.string.member_machine_no_computer_assigned)
                            }
                    },
                    model.runtimeLabel?.let { label ->
                        stringResource(Res.string.member_label_runtime) to
                            (model.runtimeId?.let { runtimeLabelWithStatus(it, model.serverId) } ?: label)
                    },
                    model.modelLabel?.let { stringResource(Res.string.member_label_model) to it },
                ),
                Modifier.padding(top = 8.dp),
            )
        }
    }
    model.description?.let { description ->
        RaftPreviewCardSeparator()
        RaftPreviewCardDescription(description)
    }
    if (onMessage != null || onViewProfile != null) {
        // At the card's end, as in a Popover (raft-design #939): the frameless one first, the bordered Message last.
        RaftPreviewCardActions {
            onViewProfile?.let { RaftButton(text = stringResource(Res.string.profile_preview_view_profile), onClick = it, variant = RaftButtonVariant.Ghost) }
            onMessage?.let { RaftButton(text = stringResource(Res.string.member_label_message), onClick = it, variant = RaftButtonVariant.Default) }
        }
    }
}

/**
 * raft-ui Avatar `lg` (51dp sticker) round the member's face, as the Members page draws it. An
 * unavailable profile has no face of its own: the kind's default (web ProfilePreviewCardContent:
 * `AvatarSlot type="agent"`, the robot; `humanPlaceholder`, the placeholder person).
 */
@Composable
private fun ProfilePreviewAvatar(model: RaftProfilePreviewCardModel) {
    RaftAppAvatarSticker(
        kind = raftAvatarKind(model.kind),
        avatarWireValue = model.avatarWireValue.takeUnless { model.unavailable },
        label = model.avatarLabel,
        size = RaftAvatarSize.Lg,
        cacheSubject = raftMemberAvatarCacheSubject(model.kind, model.memberId).takeUnless { model.unavailable },
        cacheServerId = model.serverId,
    )
}

/**
 * Web `dl grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1`: the labels in a column as wide as
 * the widest (caption `foreground-hint`), each value 12dp after it on one line (Space Mono
 * `foreground-muted`, truncated), 4dp between rows.
 */
@Composable
private fun ProfilePreviewFacts(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    if (facts.isEmpty()) return
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Layout(
        modifier = modifier,
        content = {
            facts.forEach { (label, value) ->
                RaftText(text = label, style = typography.caption, color = colors.foregroundHint, maxLines = 1)
                RaftText(text = value, style = typography.monoMeta, color = colors.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    ) { measurables, constraints ->
        val gapX = 12.dp.roundToPx()
        val gapY = 4.dp.roundToPx()
        val labels = measurables.filterIndexed { index, _ -> index % 2 == 0 }.map { it.measure(Constraints()) }
        val column = labels.maxOf { it.width }
        val valueMax = if (constraints.hasBoundedWidth) (constraints.maxWidth - column - gapX).coerceAtLeast(0) else Constraints.Infinity
        val values = measurables.filterIndexed { index, _ -> index % 2 == 1 }.map { it.measure(Constraints(maxWidth = valueMax)) }
        val rowHeights = labels.indices.map { maxOf(labels[it].height, values[it].height) }
        val width =
            if (constraints.hasBoundedWidth) constraints.maxWidth else column + gapX + (values.maxOfOrNull { it.width } ?: 0)
        val height = rowHeights.sum() + gapY * (rowHeights.size - 1)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            labels.indices.forEach { row ->
                labels[row].place(0, y)
                values[row].place(column + gapX, y)
                y += rowHeights[row] + gapY
            }
        }
    }
}
