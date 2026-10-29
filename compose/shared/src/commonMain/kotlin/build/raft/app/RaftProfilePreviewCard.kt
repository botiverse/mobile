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
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.getRuntimeDisplayName
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
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
import build.raft.designsystem.tokens.RaftStroke
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
    val modelLabel: String? = null,
    val readReceipt: RaftProfilePreviewReadReceipt? = null,
    /** The server whose avatar cache holds the face. */
    val serverId: String? = null,
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

/** The card's model for a directory [profile] (web `ProfilePreviewCardContent`'s projections). */
internal fun raftProfilePreviewCardModel(
    profile: MemberProfile,
    status: RaftAgentStatusIndicatorState? = null,
    avatarWireValue: String? = null,
    readReceipt: RaftProfilePreviewReadReceipt? = null,
): RaftProfilePreviewCardModel {
    val appBot = profile.appBotIdentityOrNull()
    val isAgent = profile.kind == MemberKind.Agent
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
        runtimeLabel = if (isAgent && appBot == null) runtimeId?.let(::getRuntimeDisplayName) else null,
        modelLabel = if (isAgent && appBot == null) profile.modelLabel() else null,
        readReceipt = if (isAgent && appBot == null) readReceipt else null,
        serverId = profile.serverId?.value,
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
    return remember(current, status, avatar, readReceipt, memberId, kind, fallbackHandle) {
        if (current == null) {
            RaftProfilePreviewCardModel.unavailable(memberId, kind, fallbackHandle)
        } else {
            raftProfilePreviewCardModel(current, status, avatar, readReceipt)
        }
    }
}

/**
 * web `ProfilePreviewCardContent` in raft-ui's PreviewCard parts, for the `card` of a
 * [RaftPreviewCard] (or inside a `RaftPreviewCardSurface` in a fixture):
 *
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
        if (appBot != null) {
            ProfilePreviewFacts(
                listOf(stringResource(Res.string.profile_preview_managed_by_app) to (appBot.appName ?: stringResource(Res.string.profile_preview_unknown_app))),
                Modifier.padding(top = 8.dp),
            )
        } else if (model.kind == MemberKind.Agent && (model.runtimeLabel != null || model.modelLabel != null)) {
            // Web `<Separator className="mt-2" />` then the dl, `pt-2`.
            RaftSeparator(Modifier.padding(top = 8.dp))
            ProfilePreviewFacts(
                listOfNotNull(
                    model.runtimeLabel?.let { stringResource(Res.string.member_label_runtime) to it },
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
        RaftPreviewCardActions {
            onMessage?.let { RaftButton(text = stringResource(Res.string.member_label_message), onClick = it, variant = RaftButtonVariant.Default) }
            onViewProfile?.let { RaftButton(text = stringResource(Res.string.profile_preview_view_profile), onClick = it, variant = RaftButtonVariant.Ghost) }
        }
    }
}

/** raft-ui Avatar `lg` (51dp sticker) round the member's face, as the Members page draws it. */
@Composable
private fun ProfilePreviewAvatar(model: RaftProfilePreviewCardModel) {
    val size = RaftAvatarSize.Lg
    val type = if (model.kind == MemberKind.Agent) RaftAvatarType.Agent else RaftAvatarType.Human
    if (model.unavailable) {
        RaftAvatar(type = type, size = size)
        return
    }
    RaftAvatar(
        type = type,
        size = size,
        image = {
            RaftAppAvatar(
                avatarWireValue = model.avatarWireValue,
                label = model.avatarLabel,
                // The face fills the inside of the 1.5dp sticker edge.
                avatarSize = size.edge - RaftStroke.Control * 2,
                borderWidth = 0.dp,
                cacheSubject = raftMemberAvatarCacheSubject(model.kind, model.memberId),
                cacheServerId = model.serverId,
            )
        },
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
