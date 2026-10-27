package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * An App Bot: an agent an installed App created (`managedByAppInstallId`, botiverse/slock
 * app-bot identity service). Web parity:
 *  - #11273 `AppBotBadge`: "Bot" beside its name, "Bot · <App>" when the name shown is not the
 *    App's, "Created by the <App> app" on hover (here: the badge's accessibility label and the
 *    profile hero's "From <App>" line). Bots run no model and have no presence.
 *  - #11295 / #11303 / #11321: `inMemberLists: false` while the Bot is removed from the server;
 *    member lists leave it out, and its DM and that DM's threads are read-only under a notice.
 */
@Immutable
internal data class RaftAppBotIdentity(
    /** The Bot's @handle (`agents.name`); the removed-bot notice names it as web does. */
    val handle: String,
    /** The owning App's name (`managedByApp.name`); null when the directory has no attribution. */
    val appName: String?,
    /** Web `isRemovedAppBot`: removed from the server (or deleted), so it cannot answer. */
    val removed: Boolean,
)

/** The App Bot identity of a directory profile; null for humans and ordinary agents. */
internal fun MemberProfile.appBotIdentityOrNull(): RaftAppBotIdentity? {
    if (kind != MemberKind.Agent || managedByAppInstallId.isNullOrBlank()) return null
    return RaftAppBotIdentity(
        handle = handle,
        appName = managedByAppName?.trim()?.takeIf { it.isNotBlank() },
        removed = !agentDeletedAt.isNullOrBlank() || inMemberLists == false,
    )
}

/**
 * Whether the Members page lists this profile (web `isAgentInMemberLists` for #11295): an App Bot
 * the server marks `inMemberLists: false` is left out. Everything else keeps today's behaviour.
 */
internal fun MemberProfile.isListedInMembers(): Boolean = inMemberLists != false

/** What the Bot badge says, before localization. */
internal sealed interface RaftAppBotBadgeLabel {
    data object Bot : RaftAppBotBadgeLabel

    data class BotWithApp(val appName: String) : RaftAppBotBadgeLabel
}

/**
 * Web `AppBotBadge`: "Bot · <App>" when the name rendered beside the badge ([botName]) is not
 * the App's name, plain "Bot" when it is, or when the App is not known.
 */
internal fun raftAppBotBadgeLabel(appName: String?, botName: String?): RaftAppBotBadgeLabel {
    val app = appName?.trim()?.takeIf { it.isNotBlank() } ?: return RaftAppBotBadgeLabel.Bot
    val shown = botName?.trim()?.takeIf { it.isNotBlank() }
    return if (shown != null && shown != app) RaftAppBotBadgeLabel.BotWithApp(app) else RaftAppBotBadgeLabel.Bot
}

/**
 * The Bot badge after an App Bot's name: raft-ui `Badge appearance="soft" variant="muted"`, as
 * web `AppBotBadge` draws it. Screen readers also hear "Created by the <App> app" (the web
 * tooltip; a phone has no hover).
 *
 * [appearance] [RaftBadgeAppearance.Outline] is web `AppBotBadge appearance="outline"`: for a
 * row that tags each result's kind with an outline badge (search results), so the Bot tag
 * reads as one of them (outline, default variant).
 */
@Composable
internal fun RaftAppBotBadge(
    identity: RaftAppBotIdentity,
    botName: String?,
    modifier: Modifier = Modifier,
    appearance: RaftBadgeAppearance = RaftBadgeAppearance.Soft,
) {
    val text =
        when (val label = raftAppBotBadgeLabel(identity.appName, botName)) {
            RaftAppBotBadgeLabel.Bot -> stringResource(Res.string.member_label_bot)
            is RaftAppBotBadgeLabel.BotWithApp -> stringResource(Res.string.member_label_bot_with_app, label.appName)
        }
    val createdBy = identity.appName?.let { stringResource(Res.string.member_label_bot_created_by_app, it) }
    val description = listOfNotNull(text, createdBy).joinToString(", ")
    RaftBadge(
        text = text,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        appearance = appearance,
        variant = if (appearance == RaftBadgeAppearance.Outline) RaftBadgeVariant.Default else RaftBadgeVariant.Muted,
    )
}

/**
 * Resolves a member's App Bot identity live from the server members directory (`GET /agents`),
 * like [RaftMemberModelLabelResolver] does for the model label.
 */
internal class RaftMemberAppBotResolver(
    private val currentIdentity: (String?) -> RaftAppBotIdentity?,
    private val identityFlow: (String?) -> Flow<RaftAppBotIdentity?>,
) {
    fun current(memberId: String?): RaftAppBotIdentity? = currentIdentity(memberId)

    operator fun invoke(memberId: String?): Flow<RaftAppBotIdentity?> = identityFlow(memberId)
}

internal val EmptyRaftMemberAppBotResolver =
    RaftMemberAppBotResolver(currentIdentity = { null }, identityFlow = { flowOf(null) })

/** A fixed directory (tests, visual fixtures). */
internal fun raftStaticMemberAppBotResolver(byMemberId: Map<String, RaftAppBotIdentity>): RaftMemberAppBotResolver =
    RaftMemberAppBotResolver(
        currentIdentity = { memberId -> memberId?.let(byMemberId::get) },
        identityFlow = { memberId -> flowOf(memberId?.let(byMemberId::get)) },
    )

internal fun raftMemberAppBotResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?,
): RaftMemberAppBotResolver {
    val store = serverMembersStore ?: return EmptyRaftMemberAppBotResolver
    val cleanServerId =
        serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return EmptyRaftMemberAppBotResolver
    return RaftMemberAppBotResolver(
        currentIdentity = { memberId -> store.state.value.memberProfile(cleanServerId, memberId)?.appBotIdentityOrNull() },
        identityFlow = { memberId ->
            store
                .memberProfileFlow(cleanServerId, memberId)
                .map { profile -> profile?.appBotIdentityOrNull() }
                .distinctUntilChanged()
        },
    )
}

/** Provided by the Thread route; message rows, the DM header and the composer read it. */
internal val LocalRaftMemberAppBotResolver = staticCompositionLocalOf { EmptyRaftMemberAppBotResolver }

@Composable
internal fun rememberRaftMemberAppBot(memberId: String?): RaftAppBotIdentity? {
    val resolver = LocalRaftMemberAppBotResolver.current
    val cleanMemberId = memberId?.trim()?.takeIf { it.isNotBlank() }
    val identityFlow = remember(resolver, cleanMemberId) { resolver(cleanMemberId) }
    val initial = remember(resolver, cleanMemberId) { resolver.current(cleanMemberId) }
    val identity by identityFlow.collectAsState(initial)
    return identity
}
