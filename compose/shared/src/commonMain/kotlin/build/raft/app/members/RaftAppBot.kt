package build.raft.app.members

import build.raft.app.resources.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.MemberDetailAccessMode
import build.raft.app.RaftAppRadius
import build.raft.app.RaftCachedImage
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.fill
import build.raft.app.get
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * An App Bot: an agent an installed App created (`managedByAppInstallId`, botiverse/slock
 * app-bot identity service). Web parity:
 *  - #11273 `AppBotBadge`, renamed `AppBadge` in #11845 (an App is labelled App, never Bot):
 *    "App" beside its name, "App · <App>" when the name shown is not the App's, "Created by
 *    the <App> app" on hover (here: the badge's accessibility label and the profile hero's
 *    "From <App>" line). Bots run no model and have no presence.
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
    /** The owning App's row id (`managedByApp.clientId` = `oauth_clients.id`, slock #14286). */
    val appClientId: String? = null,
    /** The owning App's logo URL (`managedByApp.logoUrl`, slock #14286). */
    val appLogoUrl: String? = null,
)

/** The App Bot identity of a directory profile; null for humans and ordinary agents. */
internal fun MemberProfile.appBotIdentityOrNull(): RaftAppBotIdentity? {
    if (kind != MemberKind.Agent || managedByAppInstallId.isNullOrBlank()) return null
    return RaftAppBotIdentity(
        handle = handle,
        appName = managedByAppName?.trim()?.takeIf { it.isNotBlank() },
        removed = !agentDeletedAt.isNullOrBlank() || inMemberLists == false,
        appClientId = managedByAppClientId?.trim()?.takeIf { it.isNotBlank() },
        appLogoUrl = managedByAppLogoUrl?.trim()?.takeIf { it.isNotBlank() },
    )
}

/**
 * An App bot profile's link target to its owning App's installed detail on the Apps page
 * (`?installed_app=<appId>`, botiverse/slock #14286 `useManagedAppDetailLink`).
 */
@Immutable
internal data class MembersManagedAppDetailTarget(
    /** The App's row id (`managedByApp.clientId` = `oauth_clients.id`), never its public `client_id`. */
    val appId: String,
    val appName: String,
)

/**
 * Web `useManagedAppDetailLink` (botiverse/slock #14286): returns the App detail target when the
 * viewer can open this server's installed App detail; null for guests, another server's bot in a
 * joint channel, bounded public projections (`channel_summary`), or missing App row id/name.
 */
internal fun membersManagedAppDetailTarget(
    member: MemberProfile,
    summary: AgentDetailSummary? = null,
    viewerServerRole: String?,
    currentServerId: String?,
    accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull,
): MembersManagedAppDetailTarget? {
    if (accessMode != MemberDetailAccessMode.LocalFull) return null
    val installId =
        (summary?.managedByAppInstallId ?: member.managedByAppInstallId)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
    if (installId.isEmpty()) return null
    val appId =
        (summary?.managedByAppClientId ?: member.managedByAppClientId)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
    val appName =
        (summary?.managedByAppName ?: member.managedByAppName)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
    val role = viewerServerRole?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (role == "guest") return null
    val activeServer = currentServerId?.trim()?.takeIf { it.isNotEmpty() }
    val agentServer = member.serverId?.value?.trim()?.takeIf { it.isNotEmpty() }
    if (activeServer != null && agentServer != null && agentServer != activeServer) return null
    val projection = (summary?.profileProjection ?: member.profileProjection)?.trim()?.lowercase()
    if (projection == "channel_summary") return null
    return MembersManagedAppDetailTarget(appId = appId, appName = appName)
}

private const val ManagedAppFromPlaceholder = "\u0000"

/** Splits a formatted `"From <placeholder>"` template into `(before, after)` text fragments. */
internal fun membersManagedAppFromParts(
    formatted: String,
    placeholder: String = ManagedAppFromPlaceholder,
): Pair<String, String> {
    val index = formatted.indexOf(placeholder)
    if (index < 0) return formatted.trim() to ""
    val before = formatted.substring(0, index).trimEnd()
    val after = formatted.substring(index + placeholder.length).trimStart()
    return before to after
}

/** Web `ManagedAppIdentity` `AppLogo`: 16dp rounded-sm logo, omitted when absent or broken. */
@Composable
internal fun RaftManagedAppLogo(logoUrl: String?) {
    val cleanUrl = logoUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return
    var broken by remember(cleanUrl) { mutableStateOf(false) }
    if (broken) return
    Box(
        modifier = Modifier.size(16.dp).clip(RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        RaftCachedImage(
            source = cleanUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onImageStateChange = { state -> if (state is AsyncImagePainter.State.Error) broken = true },
        )
    }
}

@Composable
private fun RaftManagedAppIdentityRow(
    openAppDetailsLabel: String?,
    onOpenManagedApp: (() -> Unit)?,
    testTag: String,
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(linked: Boolean, active: Boolean) -> Unit,
) {
    val click = onOpenManagedApp
    val label = openAppDetailsLabel?.takeIf { it.isNotBlank() }
    if (click == null || label == null) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(false, false)
        }
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val active = pressed || hovered
    Row(
        modifier =
            modifier
                .testTag(testTag)
                .raftClickable(
                    onClick = click,
                    role = Role.Button,
                    shape = RoundedCornerShape(RaftAppRadius.Chip),
                    tier = RaftHoverTier.None,
                    interactionSource = interactionSource,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                },
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(true, active)
    }
}

/**
 * Web `AppBotFromApp` (botiverse/slock #14286): the profile hero's `"From <logo> <App>"` after the
 * App badge, linking to the App's installed detail when [onOpenManagedApp] is provided.
 */
@Composable
internal fun RaftAppBotFromAppLine(
    appName: String,
    logoUrl: String?,
    onOpenManagedApp: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val formatted = stringResource(Res.string.member_label_app_from_app, ManagedAppFromPlaceholder)
    val (before, after) = remember(formatted) { membersManagedAppFromParts(formatted) }
    val openLabel =
        if (onOpenManagedApp != null) {
            stringResource(Res.string.member_detail_open_app_details, appName)
        } else {
            null
        }
    Row(
        modifier = modifier.testTag("agent-profile-bot-from-app"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (before.isNotEmpty()) {
            RaftText(
                text = before,
                style = typography.footnote,
                color = colors.foregroundMuted,
                maxLines = 1,
            )
        }
        RaftManagedAppIdentityRow(
            openAppDetailsLabel = openLabel,
            onOpenManagedApp = onOpenManagedApp,
            testTag = "agent-profile-bot-from-app-link",
            spacing = 4.dp,
        ) { linked, active ->
            RaftManagedAppLogo(logoUrl)
            RaftText(
                text = appName,
                style =
                    typography.footnote.let { base ->
                        if (linked) {
                            base.copy(
                                fontWeight = FontWeight.SemiBold,
                                textDecoration = if (active) TextDecoration.Underline else TextDecoration.None,
                            )
                        } else {
                            base
                        }
                    },
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (after.isNotEmpty()) {
            RaftText(
                text = after,
                style = typography.footnote,
                color = colors.foregroundMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * Web `ManagedByAppValue` (botiverse/slock #14286): the `"Managed by app"` row's value
 * (`logo + App badge + name`), as one link to the App's detail when [onOpenManagedApp] is set.
 */
@Composable
internal fun RaftManagedByAppRowValue(
    identity: RaftAppBotIdentity,
    onOpenManagedApp: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val appName = identity.appName
    val openLabel =
        if (onOpenManagedApp != null && !appName.isNullOrBlank()) {
            stringResource(Res.string.member_detail_open_app_details, appName)
        } else {
            null
        }
    RaftManagedAppIdentityRow(
        openAppDetailsLabel = openLabel,
        onOpenManagedApp = onOpenManagedApp?.takeIf { !appName.isNullOrBlank() },
        testTag = "agent-profile-managed-by-app-link",
        spacing = 6.dp,
        modifier = modifier,
    ) { linked, active ->
        RaftManagedAppLogo(identity.appLogoUrl)
        RaftAppBotBadge(identity = identity, botName = appName)
        RaftText(
            text = appName ?: stringResource(Res.string.profile_preview_unknown_app),
            style =
                membersInfoValueStyle().let { base ->
                    if (linked) {
                        base.copy(
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (active) TextDecoration.Underline else TextDecoration.None,
                        )
                    } else {
                        base
                    }
                },
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * Whether the Members page lists this profile (web `isAgentInMemberLists` for #11295): an App Bot
 * the server marks `inMemberLists: false` is left out. Everything else keeps today's behaviour.
 */
internal fun MemberProfile.isListedInMembers(): Boolean = inMemberLists != false

/** What the App badge says, before localization. */
internal sealed interface RaftAppBotBadgeLabel {
    data object Bot : RaftAppBotBadgeLabel

    data class BotWithApp(val appName: String) : RaftAppBotBadgeLabel
}

/**
 * Web `AppBadge` (#11845, was `AppBotBadge`): "App · <App>" when the name rendered beside the
 * badge ([botName]) is not the App's name, plain "App" when it is, or when the App is not known.
 */
internal fun raftAppBotBadgeLabel(appName: String?, botName: String?): RaftAppBotBadgeLabel {
    val app = appName?.trim()?.takeIf { it.isNotBlank() } ?: return RaftAppBotBadgeLabel.Bot
    val shown = botName?.trim()?.takeIf { it.isNotBlank() }
    return if (shown != null && shown != app) RaftAppBotBadgeLabel.BotWithApp(app) else RaftAppBotBadgeLabel.Bot
}

/**
 * The App badge after an App Bot's name: raft-ui `Badge appearance="soft" variant="muted"`, as
 * web `AppBadge` draws it. Screen readers also hear "Created by the <App> app" (the web
 * tooltip; a phone has no hover).
 *
 * [appearance] [RaftBadgeAppearance.Outline] is web `AppBadge appearance="outline"`: for a
 * row that tags each result's kind with an outline badge (search results), so the App tag
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
            RaftAppBotBadgeLabel.Bot -> stringResource(Res.string.member_label_app)
            is RaftAppBotBadgeLabel.BotWithApp -> stringResource(Res.string.member_label_app_with_app, label.appName)
        }
    val createdBy = identity.appName?.let { stringResource(Res.string.member_label_app_tooltip, it) }
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
