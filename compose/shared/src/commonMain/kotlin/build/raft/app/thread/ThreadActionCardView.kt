package build.raft.app.thread

import build.raft.app.resources.*
import build.raft.app.thread.actionCardIconName
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.components.RaftButton
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.map
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.AGENT_CREATE_NAME_TAKEN
import build.raft.app.data.runsInApp
import build.raft.app.raftArgbHex
import build.raft.app.rememberActionCardDisplayLocale
import build.raft.app.data.isInstallMarketplaceAppAction
import build.raft.app.data.InstallMarketplaceAppActionCardType
import build.raft.app.data.ActionCardSpecEntityRef
import build.raft.app.data.ConnectMcpActionCardType
import build.raft.app.data.isConnectMcpAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import build.raft.app.RaftCardTreeContext
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.actionCardIconName
import build.raft.app.actionCardMsgPlain
import org.jetbrains.compose.resources.StringResource
import build.raft.designsystem.components.RaftMessageActionCard
import build.raft.designsystem.components.RaftMessageActionCardActions
import build.raft.designsystem.components.RaftMessageActionCardDefaults
import build.raft.designsystem.components.RaftMessageActionCardHeaderDescription
import build.raft.designsystem.components.RaftMessageActionCardHint
import build.raft.designsystem.components.RaftMessageActionCardNotice
import build.raft.designsystem.components.RaftMessageActionCardProperties
import build.raft.designsystem.components.RaftMessageActionCardProperty
import build.raft.designsystem.components.RaftMessageActionCardScope
import build.raft.designsystem.components.RaftMessageActionCardScopeList
import build.raft.designsystem.components.RaftMessageActionCardStatusTone
import build.raft.app.components.RaftDesignSystemTheme
import androidx.compose.ui.semantics.onClick
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel

/**
 * An agent's action card in a message: the design system's [RaftMessageActionCard] (raft-ui
 * MessageActionCard), laid out as web ActionCard does. The header carries the action's icon
 * and title; a done card says Done with a success pixel, folds to its header and keeps who did
 * it, when, and what came of it under the title. A frozen card says Frozen and its button is
 * disabled; a card to confirm again says so with the pink pixel and its button reconfirms it. A
 * card this person can't run says View only: one prepared on another server folds and says why
 * under its title. The content lists the values to check (web useActionCardDetail), the agent's reason as a
 * quote, an app login's permissions, and the confirm button: the default ink-outline button
 * with the action's verb, never pink (the composer's send is the screen's one pink button).
 */
@Composable
internal fun ThreadActionCardView(
    card: ThreadActionCard,
    pending: Boolean,
    commitEnabled: Boolean = true,
    executedTimeLabel: String? = null,
    modifier: Modifier = Modifier,
    onOpenInWeb: () -> Unit = {},
    onCommit: () -> Unit
) {
    val title = card.localizedActionCardTitle()
    val verb = card.localizedActionCardVerb()
    val pendingLabel = card.localizedActionCardPendingLabel()
    val properties = card.localizedActionCardProperties()
    val doneLine = if (card.isExecuted) card.localizedActionCardDoneLine(executedTimeLabel).takeIf { it.isNotBlank() } else null
    // web `isActionCardReadOnlyInServer`: a card prepared on another server runs only there.
    val viewerServerId = LocalThreadViewerServerId.current
    // Expired (wire state, or past `expiresAt` as the caller drew it) or withdrawn: ended, folded, no button.
    val ended = card.hasEnded
    val endedLine = card.actionCardEndedLine()
    val blockedReason =
        when {
            card.isExecuted || ended -> null
            card.sourceServerId != null && viewerServerId != null && card.sourceServerId != viewerServerId ->
                card.targetServerName?.let { stringResource(Res.string.thread_action_card_blocked_target_server, it) }
                    ?: stringResource(Res.string.thread_action_card_blocked_source_workspace)
            else -> null
        }
    val viewOnly = !card.isExecuted && !ended && (!commitEnabled || blockedReason != null)
    // Web: a done card, an ended one, and a view-only one that says why, fold by default; a frozen or
    // reconfirm card stays open (it waits on the reader). The fold then belongs to the reader.
    val foldsByDefault = card.isExecuted || ended || (blockedReason != null && !card.isFrozen && !card.needsReconfirmation)
    var expanded by remember(foldsByDefault) { mutableStateOf(!foldsByDefault) }
    val feedbackEntryEnabled = LocalThreadFeedbackCardEntryEnabled.current
    RaftDesignSystemTheme {
        RaftMessageActionCard(
            title = title,
            modifier = modifier,
            icon = card.actionCardIconName()?.let { name ->
                {
                    RaftLucideIcon(
                        name = name,
                        color = raftArgbHex(RaftTheme.colors.foregroundMuted),
                        modifier = Modifier.size(RaftMessageActionCardDefaults.HeaderGlyphSize)
                    )
                }
            },
            description =
                doneLine?.let { line -> { RaftMessageActionCardHeaderDescription(line, check = true) } }
                    ?: endedLine?.takeIf { ended }?.let { line -> { RaftMessageActionCardHeaderDescription(line) } }
                    ?: blockedReason?.takeIf { foldsByDefault }?.let { reason -> { RaftMessageActionCardHeaderDescription(reason) } },
            status =
                when {
                    card.isExecuted -> stringResource(Res.string.common_done)
                    card.isClosed -> stringResource(Res.string.thread_action_card_status_closed)
                    card.isExpired -> stringResource(Res.string.thread_action_card_status_expired)
                    card.isWithdrawn -> stringResource(Res.string.thread_action_card_status_withdrawn)
                    card.isUnknownState -> stringResource(Res.string.thread_action_card_view_only)
                    card.isFrozen -> stringResource(Res.string.thread_action_card_frozen)
                    card.needsReconfirmation -> stringResource(Res.string.thread_action_card_status_reconfirm)
                    viewOnly -> stringResource(Res.string.thread_action_card_view_only)
                    else -> null
                },
            statusTone =
                when {
                    card.isExecuted -> RaftMessageActionCardStatusTone.Success
                    // The card waits on the reader: the pink attention pixel.
                    card.needsReconfirmation -> RaftMessageActionCardStatusTone.Accent
                    else -> RaftMessageActionCardStatusTone.Neutral
                },
            expanded = expanded,
            onExpandedChange =
                if (card.isExecuted || ended || card.isFrozen || card.needsReconfirmation || viewOnly) ({ expanded = it }) else null,
            toggleLabel = stringResource(Res.string.thread_action_card_toggle_details),
        ) {
            card.actionCardNotice()?.let { notice -> RaftMessageActionCardNotice(notice) }
            if (properties.isNotEmpty()) {
                RaftMessageActionCardProperties {
                    properties.forEach { property ->
                        RaftMessageActionCardProperty(
                            label = property.label,
                            value = property.value,
                            mono = property.mono,
                            valueColor = if (property.muted) RaftTheme.colors.foregroundHint else Color.Unspecified,
                        )
                    }
                }
            }
            card.actionCardDraftHint()?.let { hint ->
                RaftMessageActionCardHint(hint, label = stringResource(Res.string.thread_action_card_reason))
            }
            if (card.isIntegrationApproveLoginAction() || card.isInstallMarketplaceAppAction()) {
                val scopes =
                    card.scopes.takeIf { it.isNotEmpty() }
                        ?: if (card.isInstallMarketplaceAppAction()) emptyList() else listOf("openid", "profile", "identity")
                if (scopes.isNotEmpty()) RaftMessageActionCardScopeList(
                    label = stringResource(Res.string.thread_action_card_permissions, scopes.size),
                    viewportDescription = stringResource(Res.string.thread_action_card_permissions_label),
                ) {
                    scopes.forEach { scope ->
                        RaftMessageActionCardScope(scope.trim(), localizedIntegrationScopeDescription(scope))
                    }
                }
            }
            // Web shows the button disabled beside the reason; with no reason to give (the
            // thread is read-only here) the card shows no button.
            if (!card.isExecuted && !ended && commitEnabled) {
                // A card this app can't run opens on the web instead of showing a dead button.
                val opensOnWeb = !card.isFrozen && blockedReason == null && !card.runsInApp(feedbackEntryEnabled)
                RaftMessageActionCardActions(note = blockedReason?.takeUnless { foldsByDefault }) {
                    if (opensOnWeb) {
                        RaftButton(
                            text = stringResource(Res.string.thread_action_card_open_on_web),
                            onClick = onOpenInWeb,
                            trailingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowUpRight) },
                        )
                    } else {
                        RaftButton(
                            text =
                                when {
                                    card.isFrozen -> stringResource(Res.string.thread_action_card_frozen)
                                    card.needsReconfirmation -> stringResource(Res.string.thread_action_card_reconfirm)
                                    pending -> pendingLabel
                                    else -> verb
                                },
                            onClick = onCommit,
                            enabled = !pending && !card.isFrozen && blockedReason == null,
                        )
                    }
                }
            }
        }
    }
}

/** One row of an action card's properties (web `ActionCardProperty`). */
private data class ThreadActionCardProperty(
    val label: String,
    val value: String,
    val mono: Boolean = false,
    /** A placeholder ("No description"), not data. */
    val muted: Boolean = false,
)

/**
 * The action's icon in the card header of a typed card (one drawn without its spec): the icon the
 * web's spec builder gives each type (slock `actionCardSpecBuilder.test.ts` "each type's header icon",
 * the same names [actionCardIconName] maps for a spec card); null draws the default one (zap).
 */
internal fun ThreadActionCard.actionCardIconName(): RaftLucideIconName? =
    when (type) {
        "channel:create" -> RaftLucideIconName.Hash
        "agent:create", "agent:migrate" -> RaftLucideIconName.Bot
        "channel:add_member",
        build.raft.app.data.ChannelInviteExternalActionCardType -> RaftLucideIconName.UserPlus
        "integration:approve_agent_login" -> RaftLucideIconName.KeyRound
        "integration:register_app", "integration:update_app_registration" -> RaftLucideIconName.AppWindow
        "integration:install_marketplace_app" -> RaftLucideIconName.Package
        "integration:recover_app_owner", build.raft.app.data.PreparedRequestActionCardType -> RaftLucideIconName.ShieldAlert
        "integration:connect_mcp" -> RaftLucideIconName.Plug
        "feedback:submit", "question:ask" -> RaftLucideIconName.Message
        "plan:upgrade", build.raft.app.data.SeatsAddActionCardType -> RaftLucideIconName.CreditCard
        else -> null
    }

/** The values to check, per action (web useActionCardDetail); other actions list their metadata. */
@Composable
private fun ThreadActionCard.localizedActionCardProperties(): List<ThreadActionCardProperty> {
    val descriptionLabel = stringResource(Res.string.thread_action_card_field_description)
    val humansLabel = stringResource(Res.string.thread_action_card_field_humans)
    val agentsLabel = stringResource(Res.string.thread_action_card_field_agents)
    // The card stores ids: show names (web useActionCardDetail), never a UUID.
    val names = LocalThreadActionCardNames.current
    val unknownMember = stringResource(Res.string.thread_action_card_unknown_member)
    val unknownAgent = stringResource(Res.string.thread_action_card_unknown_agent)
    fun humanNames(ids: List<String>) =
        ids.joinToString(", ") { names.displayName(ThreadActionCardEntityKind.Human, it, unknownMember) }
    fun agentNames(ids: List<String>) =
        ids.joinToString(", ") { names.displayName(ThreadActionCardEntityKind.Agent, it, unknownAgent) }
    return when (type) {
        "channel:create" ->
            buildList {
                description?.takeIf { it.isNotBlank() }?.let { add(ThreadActionCardProperty(descriptionLabel, it)) }
                if (initialHumans.isNotEmpty()) add(ThreadActionCardProperty(humansLabel, humanNames(initialHumans)))
                if (initialAgents.isNotEmpty()) add(ThreadActionCardProperty(agentsLabel, agentNames(initialAgents)))
            }
        "agent:create" ->
            buildList {
                add(
                    description?.takeIf { it.isNotBlank() }?.let { ThreadActionCardProperty(descriptionLabel, it) }
                        ?: ThreadActionCardProperty(
                            descriptionLabel,
                            stringResource(Res.string.thread_action_card_no_description),
                            muted = true
                        )
                )
                val required = requiredComputer?.takeIf { it.isNotBlank() }
                val computerId = required ?: suggestedComputer?.takeIf { it.isNotBlank() }
                // A computer by its name from this server's list, as the spec card names it (slock #14234).
                val computer = computerId?.let { names.name(ThreadActionCardEntityKind.Computer, it) ?: it }
                if (computer != null) {
                    add(
                        ThreadActionCardProperty(
                            stringResource(
                                if (required != null) {
                                    Res.string.thread_action_card_field_required_computer
                                } else {
                                    Res.string.thread_action_card_field_suggested_computer
                                }
                            ),
                            computer,
                            mono = true
                        )
                    )
                }
            }
        "channel:add_member" ->
            buildList {
                // web: the row only when the channel is known.
                actionCardChannelName(names)?.let {
                    add(ThreadActionCardProperty(stringResource(Res.string.thread_action_card_field_channel), "#$it"))
                }
                if (humans.isNotEmpty()) add(ThreadActionCardProperty(humansLabel, humanNames(humans)))
                if (agents.isNotEmpty()) add(ThreadActionCardProperty(agentsLabel, agentNames(agents)))
            }
        "integration:approve_agent_login",
        InstallMarketplaceAppActionCardType -> {
            val service = clientName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.thread_action_card_app)
            val agent = agentName?.takeIf { it.isNotBlank() }
                ?: name.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_agent)
            listOf(
                ThreadActionCardProperty(stringResource(Res.string.thread_action_card_label_service), service),
                ThreadActionCardProperty(stringResource(Res.string.thread_action_card_label_agent), "@${agent.removePrefix("@")}", mono = true),
            )
        }
        // web: the client key and the new owner by name, not the stored ids.
        "integration:recover_app_owner" -> {
            val recoverClientKey = clientKey
            val recoverTargetAgent = targetAgent
            if (recoverClientKey.isNullOrBlank() || recoverTargetAgent.isNullOrBlank()) {
                genericActionCardProperties()
            } else {
                listOf(
                    ThreadActionCardProperty(stringResource(Res.string.thread_action_card_field_client_key), recoverClientKey, mono = true),
                    ThreadActionCardProperty(
                        stringResource(Res.string.thread_action_card_field_recovery_owner),
                        "@${names.displayName(ThreadActionCardEntityKind.Agent, recoverTargetAgent, unknownAgent).removePrefix("@")}",
                        mono = true
                    ),
                )
            }
        }
        else -> genericActionCardProperties()
    }
}

/** A type without its own rows lists the server's display items (or the raw action's fields). */
@Composable
private fun ThreadActionCard.genericActionCardProperties(): List<ThreadActionCardProperty> =
    if (metadataItems.isEmpty()) {
        listOf(ThreadActionCardProperty(stringResource(Res.string.thread_action_card_label_type), type, mono = true))
    } else {
        val hidden = stringResource(Res.string.thread_action_card_hidden_value)
        metadataItems.map { item ->
            ThreadActionCardProperty(
                label = humanizeActionCardMetadataKey(item.key),
                value = if (item.redacted) hidden else item.value,
                muted = item.redacted,
            )
        }
    }

/**
 * `channel:add_member`'s channel by name, without "#" (web: `channels.find`); null when this app
 * doesn't know the channel. A stored name (an older card) is shown as it is, an id never.
 */
private fun ThreadActionCard.actionCardChannelName(names: ThreadActionCardNameLookup): String? {
    val raw = channel?.trim()?.removePrefix("#")?.takeIf { it.isNotBlank() } ?: return null
    return names.name(ThreadActionCardEntityKind.Channel, raw) ?: raw.takeUnless { looksLikeActionCardUuid(it) }
}

/** Raft's standing note on the card (web MessageActionCardNotice): an app login's grant, a warning. */
@Composable
private fun ThreadActionCard.actionCardNotice(): String? =
    when {
        type.equals("integration:update_app_registration", ignoreCase = true) ->
            stringResource(Res.string.thread_action_card_legacy_unavailable_hint)
        isInstallMarketplaceAppAction() -> stringResource(Res.string.thread_action_card_install_owner_only)
        isIntegrationApproveLoginAction() -> {
            val service = clientName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.thread_action_card_app)
            val agent = agentName?.takeIf { it.isNotBlank() }
                ?: name.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_agent)
            stringResource(Res.string.thread_action_card_approval_hint, agent.removePrefix("@"), service)
        }
        // A connect card has no notice (slock #13298): its legacy warning ("An agent tried to use this
        // MCP server... Choose a connection to grant") is an instruction for the web's picker, and a
        // state that ends at the grant; the title already says which App, for whom.
        isConnectMcpAction() -> null
        else -> presentationWarning?.takeIf { it.isNotBlank() }
    }

private fun ThreadActionCard.actionCardVerb(): String =
    when (type) {
        "channel:create" -> "Create Channel"
        "agent:create" -> "Create Agent"
        "channel:add_member" -> "Add Members"
        "integration:approve_agent_login" -> "Approve Login"
        "integration:register_app" -> "Register App"
        "integration:recover_app_owner" -> "Recover Owner"
        "integration:update_app_registration" -> "Unavailable"
        else -> "Approve Action"
    }

@Composable
private fun ThreadActionCard.localizedActionCardVerb(): String =
    when (type) {
        "channel:create" -> stringResource(Res.string.thread_action_card_create_channel)
        "agent:create" -> stringResource(Res.string.thread_action_card_create_agent)
        "channel:add_member" -> stringResource(Res.string.thread_action_card_add_members)
        "integration:approve_agent_login" -> stringResource(Res.string.thread_action_card_approve_login)
        InstallMarketplaceAppActionCardType -> stringResource(Res.string.thread_action_card_install_app)
        "integration:register_app" -> stringResource(Res.string.thread_action_card_register_app)
        "integration:recover_app_owner" -> stringResource(Res.string.thread_action_card_recover_owner)
        "integration:update_app_registration" -> stringResource(Res.string.thread_action_card_unavailable)
        else ->
            presentationConfirmLabel?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_approve_action)
    }

@Composable
private fun ThreadActionCard.localizedActionCardPendingLabel(): String =
    when (type) {
        "channel:add_member" -> stringResource(Res.string.thread_action_card_adding)
        "integration:approve_agent_login" -> stringResource(Res.string.thread_action_card_approving)
        InstallMarketplaceAppActionCardType -> stringResource(Res.string.thread_action_card_installing)
        "integration:register_app",
        "integration:recover_app_owner" -> stringResource(Res.string.thread_action_card_approving)
        else -> stringResource(Res.string.thread_action_card_creating)
    }

@Composable
private fun ThreadActionCard.localizedActionCardTitle(): String =
    when (type) {
        "channel:create" -> {
            val localizedVisibility =
                when (visibility?.lowercase()) {
                    "private" -> stringResource(Res.string.thread_action_card_private)
                    else -> stringResource(Res.string.thread_action_card_public)
                }
            stringResource(
                Res.string.thread_action_card_title_create_channel,
                localizedVisibility,
                name.removePrefix("#")
            )
        }
        "agent:create" ->
            stringResource(Res.string.thread_action_card_title_create_agent, name.removePrefix("@"))
        "channel:add_member" -> {
            val count = humans.size + agents.size
            val target = actionCardChannelName(LocalThreadActionCardNames.current)?.let { "#$it" }
                ?: stringResource(Res.string.thread_action_card_channel)
            if (count == 1) {
                stringResource(Res.string.thread_action_card_title_add_member, target)
            } else {
                stringResource(Res.string.thread_action_card_title_add_members, count, target)
            }
        }
        "integration:approve_agent_login" -> {
            val service = clientName?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_app)
            val agent = agentName?.takeIf { it.isNotBlank() }
                ?: name.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_agent)
            stringResource(
                Res.string.thread_action_card_title_approve_login,
                service,
                agent.removePrefix("@")
            )
        }
        InstallMarketplaceAppActionCardType -> {
            val service = clientName?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_app)
            val agent = agentName?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_agent)
            stringResource(Res.string.thread_action_card_title_install_app, service, agent.removePrefix("@"))
        }
        "integration:register_app" -> {
            val appName = name.takeIf { it.isNotBlank() }
                ?: clientName?.takeIf { it.isNotBlank() }
                ?: clientKey?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_app)
            stringResource(Res.string.thread_action_card_title_register_app, appName)
        }
        "integration:recover_app_owner" ->
            stringResource(Res.string.thread_action_card_title_recover_owner)
        "integration:update_app_registration" ->
            stringResource(Res.string.thread_action_card_unavailable)
        ConnectMcpActionCardType -> {
            val names = LocalThreadActionCardNames.current
            val viewerServerId = LocalThreadViewerServerId.current
            connectMcpTitle(rememberActionCardDisplayLocale()) { ref ->
                names.actionCardSpecEntityName(ref, ref.serverIdOr(sourceServerId), viewerServerId)
            } ?: legacyActionCardTitle()
        }
        else -> legacyActionCardTitle()
    }

/** The server's legacy `presentation.title`, else "Approve {type}". */
@Composable
private fun ThreadActionCard.legacyActionCardTitle(): String =
    presentationTitle?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.thread_action_card_title_generic, humanizeActionCardType(type))

/**
 * A connect card's title as its spec says it (slock #13298, shared `connectMcp`): "Authorize {app}
 * for {agent}", the App by the name the server resolved when it prepared the card and the agent by
 * [entityName] (its display name here), else by the name the server had; a card prepared without
 * the App's name keeps the spec's fixed "Authorize MCP connection", in the reader's language
 * ([displayLocale]). Null for another type, or a card without a spec this App reads (the legacy
 * title then).
 */
internal fun ThreadActionCard.connectMcpTitle(displayLocale: String, entityName: (ActionCardSpecEntityRef) -> String?): String? {
    if (!isConnectMcpAction()) return null
    val title = presentationCard?.title ?: return null
    return actionCardMsgPlain(title, RaftCardTreeContext(displayLocale = displayLocale, entityName = entityName))
}

private fun ThreadActionCard.actionCardPendingLabel(): String =
    when (type) {
        "channel:add_member" -> "Adding..."
        "integration:approve_agent_login" -> "Approving..."
        "integration:register_app",
        "integration:recover_app_owner" -> "Approving..."
        else -> "Creating..."
    }

private fun ThreadActionCard.actionCardSuccessToast(): String =
    when (type) {
        "channel:create" -> "Channel created"
        "agent:create" -> "Agent created"
        "channel:add_member" -> "Members added"
        "integration:approve_agent_login" -> "Login approved"
        "integration:register_app" -> "Connected App created"
        "integration:recover_app_owner" -> "Connected App owner recovered"
        else -> "Action completed"
    }

private fun ThreadActionCard.actionCardFailureToast(): String =
    when (type) {
        "channel:create" -> "Unable to create channel"
        "agent:create" -> "Unable to create agent"
        "channel:add_member" -> "Unable to add members"
        "integration:approve_agent_login" -> "Unable to approve login"
        "integration:register_app" -> "Unable to create Connected App"
        "integration:recover_app_owner" -> "Unable to recover Connected App owner"
        else -> "Unable to complete action"
    }

private fun ThreadActionCard.actionCardTitle(): String =
    when (type) {
        "channel:create" -> "Create ${visibility ?: "public"} channel #$name"
        "agent:create" -> "Create Agent @${name.removePrefix("@")}"
        "channel:add_member" -> {
            val count = humans.size + agents.size
            "Add $count member${if (count == 1) "" else "s"} to ${channel?.let { "#${it.removePrefix("#")}" } ?: "channel"}"
        }
        "integration:approve_agent_login" -> {
            val service = clientName?.takeIf { it.isNotBlank() } ?: "app"
            val agent = agentName?.takeIf { it.isNotBlank() } ?: name.takeIf { it.isNotBlank() } ?: "agent"
            "Approve $service for @${agent.removePrefix("@")}"
        }
        "integration:register_app" -> "Register Connected App ${name.ifBlank { clientName ?: clientKey ?: "app" }}"
        "integration:recover_app_owner" -> "Recover Connected App owner"
        "integration:update_app_registration" -> "Unavailable"
        else -> "Approve ${humanizeActionCardType(type)}"
    }

private fun ThreadActionCard.actionCardDetails(): String =
    buildList {
        description?.takeIf { it.isNotBlank() }?.let { add("Description: $it") }
        if (type.equals("agent:create", ignoreCase = true)) {
            requiredComputer?.takeIf { it.isNotBlank() }?.let { add("Required computer: $it") }
            if (requiredComputer.isNullOrBlank()) {
                suggestedComputer?.takeIf { it.isNotBlank() }?.let { add("Suggested computer: $it") }
            }
            if (description.isNullOrBlank()) add("No description prefilled.")
        }
        if (initialHumans.isNotEmpty()) add("Humans: ${initialHumans.joinToString(", ")}")
        if (initialAgents.isNotEmpty()) add("Agents: ${initialAgents.joinToString(", ")}")
        if (humans.isNotEmpty()) add("Humans: ${humans.joinToString(", ")}")
        if (agents.isNotEmpty()) add("Agents: ${agents.joinToString(", ")}")
        if (type.equals("integration:approve_agent_login", ignoreCase = true)) {
            clientName?.takeIf { it.isNotBlank() }?.let { add("Service: $it") }
            agentName?.takeIf { it.isNotBlank() }?.let { add("Agent: @${it.removePrefix("@")}") }
            val scopeLine = scopes.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "-"
            add("Scopes: $scopeLine")
        }
    }.joinToString("\n")

internal fun ThreadActionCard.actionCardDraftHint(): String? =
    draftHint
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { isIntegrationApproveLoginAction() }

private fun ThreadActionCard.isIntegrationApproveLoginAction(): Boolean =
    type.equals("integration:approve_agent_login", ignoreCase = true)

private fun ThreadActionCard.usesGenericActionCardPresentation(): Boolean =
    !type.equals("channel:create", ignoreCase = true) &&
        !type.equals("agent:create", ignoreCase = true) &&
        !type.equals("channel:add_member", ignoreCase = true) &&
        !isIntegrationApproveLoginAction()

internal fun humanizeActionCardMetadataKey(raw: String): String {
    val clean = raw.trim()
    val words =
        buildString {
            clean.forEachIndexed { index, char ->
                when {
                    char == '_' || char == '-' || char == '.' -> append(' ')
                    char.isUpperCase() && index > 0 && clean[index - 1].isLowerCase() -> {
                        append(' ')
                        append(char)
                    }
                    else -> append(char)
                }
            }
        }.trim().replace(Regex("\\s+"), " ")
    return words
        .split(' ')
        .filter { it.isNotBlank() }
        .mapIndexed { index, word ->
            when (word.lowercase()) {
                "url" -> "URL"
                "id" -> "ID"
                "uuid" -> "UUID"
                "api" -> "API"
                "oauth" -> "OAuth"
                else -> {
                    val normalized = word.lowercase()
                    if (index == 0) {
                        normalized.replaceFirstChar { first -> first.titlecase() }
                    } else {
                        normalized
                    }
                }
            }
        }
        .joinToString(" ")
}

internal fun humanizeActionCardType(raw: String): String =
    humanizeActionCardMetadataKey(raw.substringAfter(':', raw))

private fun integrationScopeDescription(scope: String): String =
    when (scope.trim().lowercase()) {
        "identity" ->
            "Reads who you are - human or agent, your Raft subject, and which server/principal you are acting as. No content access."
        "openid" ->
            "Signs you in with Raft and issues a stable identity token. Authentication only."
        "profile" ->
            "Reads your basic profile fields, such as display name. No access to messages, files, or agents."
        else ->
            "Allows the app to request this named permission from Raft."
    }

@Composable
private fun localizedIntegrationScopeDescription(scope: String): String =
    // web ActionCardScopes: the same copy as the install confirmation's permission set.
    localizedAppPermissionScopeDescription(scope.trim().lowercase())

private fun ThreadActionCard.actionCardCommittedLabel(): String =
    buildString {
        executedByUserName?.takeIf { it.isNotBlank() }?.let { append("Committed by $it") }
        val result = when (resultKind) {
            "agent" -> resultName?.let { "@${it.removePrefix("@")}" }
            "channel" -> resultName?.let { "#${it.removePrefix("#")}" }
            "channel-members" -> {
                val total = resultAddedHumanIds.size + resultAddedAgentIds.size
                resultChannelName?.let { "$total added to #${it.removePrefix("#")}" }
            }
            "agent-integration-login" -> {
                val service = resultName?.takeIf { it.isNotBlank() } ?: clientName
                val agent = agentName?.takeIf { it.isNotBlank() }
                when {
                    !service.isNullOrBlank() && !agent.isNullOrBlank() -> "$service approved for @${agent.removePrefix("@")}"
                    !service.isNullOrBlank() -> "$service approved"
                    else -> "Login approved"
                }
            }
            "integration-app-registration" ->
                resultName?.takeIf { it.isNotBlank() }
                    ?: name.takeIf { it.isNotBlank() }
                    ?: clientName?.takeIf { it.isNotBlank() }
            "integration-app-owner-recovery" ->
                resultName?.takeIf { it.isNotBlank() }
            else -> null
        }
        if (!result.isNullOrBlank()) {
            if (isNotBlank()) append(" -> ") else append("Result: ")
            append(result)
        }
    }

@Composable
/**
 * The line under a done card's title (web ActionCard's done line): who ran it, when, and
 * what came of it: "Committed by Ada · 10:51 → #release-review".
 */
private fun ThreadActionCard.localizedActionCardDoneLine(executedTimeLabel: String?): String {
    val committedBy = executedByUserName?.takeIf { it.isNotBlank() }?.let {
        if (type.equals(build.raft.app.data.ChannelInviteExternalActionCardType, ignoreCase = true)) {
            stringResource(Res.string.thread_action_card_invite_external_handled_by, it)
        } else {
            stringResource(Res.string.thread_action_card_committed_by, it)
        }
    }
    val result =
        when (resultKind) {
            "agent" -> resultName?.let { "@${it.removePrefix("@")}" }
            "channel" -> resultName?.let { "#${it.removePrefix("#")}" }
            "channel-members" -> {
                val total = resultAddedHumanIds.size + resultAddedAgentIds.size
                resultChannelName?.let {
                    stringResource(Res.string.thread_action_card_members_added, total, it.removePrefix("#"))
                }
            }
            "agent-integration-login" -> {
                val service = resultName?.takeIf { it.isNotBlank() } ?: clientName
                val agent = agentName?.takeIf { it.isNotBlank() }
                when {
                    !service.isNullOrBlank() && !agent.isNullOrBlank() ->
                        stringResource(
                            Res.string.thread_action_card_service_approved_for,
                            service,
                            agent.removePrefix("@")
                        )
                    !service.isNullOrBlank() ->
                        stringResource(Res.string.thread_action_card_service_approved, service)
                    else -> stringResource(Res.string.thread_action_card_login_approved)
                }
            }
            "integration-app-registration" ->
                resultName?.takeIf { it.isNotBlank() }
                    ?: name.takeIf { it.isNotBlank() }
                    ?: clientName?.takeIf { it.isNotBlank() }
            "integration-app-owner-recovery" ->
                resultName?.takeIf { it.isNotBlank() }
            "marketplace-app-installation" ->
                (resultName?.takeIf { it.isNotBlank() } ?: clientName?.takeIf { it.isNotBlank() })
                    ?.let { stringResource(Res.string.thread_action_card_installed_on_server, it) }
            else -> null
        }
    val who = committedBy?.let { by -> executedTimeLabel?.let { "$by · $it" } ?: by }
    return when {
        who != null && result != null -> "$who → $result"
        who != null -> who
        result != null -> stringResource(Res.string.thread_action_card_result, result)
        else -> executedTimeLabel.orEmpty()
    }
}

@Composable
internal fun threadCreateAgentDialogErrorCopy(code: String, serverMessage: String? = null): String =
    when (code) {
        "agent_name_required",
        "action_card_agent_name_missing" -> stringResource(Res.string.thread_action_card_error_agent_name_required)
        // The card's API is not wired in this host: nothing was created, so say that (no build detail).
        "action_card_agents_api_missing",
        "action_card_actions_api_missing" -> stringResource(Res.string.thread_action_card_error_agent_not_created)
        "action_card_mark_executed_empty",
        "action_mark_executed_contract_mismatch" -> stringResource(Res.string.thread_action_card_error_mark_done)
        "agent_create_empty" -> stringResource(Res.string.thread_action_card_error_agent_not_created)
        AGENT_CREATE_NAME_TAKEN ->
            stringResource(Res.string.agent_create_error_name_taken)
        "agent_create_form_refresh_required" ->
            stringResource(Res.string.thread_action_card_error_agent_form_refresh_required)
        "agent_create_form_refresh_failed" ->
            stringResource(Res.string.thread_action_card_error_agent_form_refresh_failed)
        // task #193 honest-states: unmapped code -> raw server reason first,
        // localized generic-with-code only when the server sent no text.
        else ->
            serverMessage?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_error_agent_create, code)
    }

/**
 * A card a phone shows as one line ([ThreadActionCard.actionCardMobileLine]): the line as message
 * text, no frame, no buttons, no fields (the web's mobile layout for `plan:trial`).
 */
@Composable
internal fun ThreadActionCardMobileLineView(
    line: StringResource,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(line),
        style = textStyle,
        color = RaftColor.TextBlack,
        modifier = modifier.testTag(ThreadActionCardMobileLineTestTag),
    )
}

internal const val ThreadActionCardMobileLineTestTag = "thread_action_card_mobile_line"
