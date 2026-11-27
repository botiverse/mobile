package build.raft.app.thread

import build.raft.app.SharedCreateAgentDialogSubmit
import build.raft.app.data.ActionCardCloseOutcome
import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.ExternalInviteAddressesOutcome
import build.raft.app.data.SeatRequestCheckOutcome
import build.raft.app.data.SeatRequestCompleteOutcome
import build.raft.app.data.AddChannelMembersBatchRequest
import build.raft.app.data.ChannelRepository
import build.raft.app.data.CreateAgentBody
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.ExecuteActionFormBody
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.MemberRepository
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftFeedbackAgentDraftSent
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.RaftRatingPrompt
import build.raft.app.data.ScreenError
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpOutcome
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.buildCreateAgentBody
import build.raft.app.data.buildExecuteActionBody
import build.raft.app.data.buildMarkAgentActionExecutedBody
import build.raft.app.data.buildMarkAgentMigrationActionExecutedBody
import build.raft.app.data.buildMarkChannelActionExecutedBody
import build.raft.app.data.buildMarkChannelMembersActionExecutedBody
import build.raft.app.data.buildMarkExternalInvitesActionExecutedBody
import build.raft.app.data.buildMarkMcpConnectionActionExecutedBody
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.installSetBound
import build.raft.app.data.isAgentMigrateAction
import build.raft.app.data.isConnectMcpAction
import build.raft.app.data.isFeedbackSubmitAction
import build.raft.app.data.isGenericInlineCommitAction
import build.raft.app.data.isInstallMarketplaceAppAction
import build.raft.app.data.isInviteExternalAction
import build.raft.app.data.withExecutedAgent
import build.raft.app.data.withExecutedAgentMigration
import build.raft.app.data.withExecutedChannel
import build.raft.app.data.withExecutedChannelMembers
import build.raft.app.data.withExecutedExternalInvites
import build.raft.app.data.withExecutedMcpConnection
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.toCreateAgentRequest

/**
 * The action-card commands of [SharedThreadDataSource] (commit, reconfirm, execute, mark
 * executed), moved out of it unchanged; the data source forwards to them. Holds only the
 * immutable collaborators it was constructed with and reads the session through [readSession].
 */
internal class ThreadActionCardCommands(
    private val agentsApiClient: RaftAgentsApiClient?,
    private val actionCardsApiClient: RaftIntegrationsActionsPushApiClient?,
    private val createAgentCoordinator: build.raft.app.data.SharedCreateAgentCoordinator?,
    private val channelRepository: ChannelRepository?,
    private val memberRepository: MemberRepository?,
    private val serverMembersStore: SharedServerMembersStore?,
    private val readSession: suspend () -> StoredAuthSession?,
) {
    suspend fun commitAgentCreateActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (threadActionCardCommitBlockedByEnd(card, defaultNowMillis())) {
            return DataResult.Error(ScreenError(code = ActionCardEndedErrorCode, retryable = false))
        }
        return commitAgentCreateActionCardInternal(messageId, card)
    }

    /**
     * Task #43: action-card create-agent path that goes through the shared
     * [SharedCreateAgentCoordinator]. The dialog collects a full
     * [SharedCreateAgentDialogSubmit] (name, description, machineId,
     * runtimeConfig, external); the coordinator owns the body construction
     * and the API call. An allowlisted zero-side-effect runtime-form rejection
     * may refresh the canonical form, revalidate the original input, and retry
     * once with the fresh ref. Changed input or a second rejection requires
     * explicit confirmation. The action card is just the metadata that gets
     * marked executed on success.
     *
     * Replaces the legacy `commitAgentCreateActionCard(messageId, card)`
     * which only used name/description/machineId from the action card.
     */
    suspend fun commitAgentCreateActionCardFromDialog(
        messageId: String,
        card: ThreadActionCard,
        submit: SharedCreateAgentDialogSubmit
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("agent:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val actionsApi = actionCardsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val coordinator = createAgentCoordinator
            ?: return DataResult.Error(ScreenError(code = "action_card_coordinator_missing", retryable = false))

        val created = coordinator.createAgent(submit.toCreateAgentRequest())
        return when (created) {
            is DataResult.Content -> {
                val resultName = created.data.name.takeIf { it.isNotBlank() } ?: submit.name
                val executedCard = card.withExecutedAgent(id = created.data.id, name = resultName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body = buildMarkAgentActionExecutedBody(agentId = created.data.id, agentName = resultName, expectedConfirmationVersion = card.confirmationVersion),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "agent_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    /** [onChannelCreated]: a `channel:create` card made its channel (it exists, whether or not the card can be marked). */
    suspend fun commitThreadActionCard(
        payload: RaftThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard,
        onChannelCreated: (HomeChannelRow) -> Unit = {},
    ): DataResult<ThreadActionCard> {
        val result: DataResult<ThreadActionCard> = when {
            // web ActionCard: a frozen card runs nothing; a reconfirm card's button reconfirms it.
            card.isFrozen ->
                DataResult.Error(ScreenError(code = "action_card_frozen", retryable = false))
            // Expired (state, or past `expiresAt` on this clock) or withdrawn: nothing is created.
            threadActionCardCommitBlockedByEnd(card, defaultNowMillis()) ->
                DataResult.Error(ScreenError(code = ActionCardEndedErrorCode, retryable = false))
            card.needsReconfirmation ->
                reconfirmThreadActionCard(messageId, card)
            card.type.equals("agent:create", ignoreCase = true) ->
                DataResult.Error(ScreenError(code = "action_card_agent_create_dialog_required", retryable = false))
            card.isAgentMigrateAction() ->
                DataResult.Error(ScreenError(code = "action_card_agent_migrate_dialog_required", retryable = false))
            card.type.equals("channel:create", ignoreCase = true) ->
                commitChannelCreateActionCard(messageId, card, onChannelCreated)
            card.type.equals("channel:add_member", ignoreCase = true) ->
                commitChannelAddMembersActionCard(payload, messageId, card)
            card.type.equals("integration:approve_agent_login", ignoreCase = true) ->
                executeInlineThreadActionCard(messageId, card)
            // web InstallAppCardDialog: runs only from the confirmation of the bound set.
            card.isInstallMarketplaceAppAction() ->
                DataResult.Error(ScreenError(code = "action_card_install_confirm_required", retryable = false))
            card.isGenericInlineCommitAction() ->
                executeInlineThreadActionCard(messageId, card)
            else ->
                DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        // A reconfirm only re-arms the card; it has not run.
        if (!card.needsReconfirmation) noteActionCardApproved(result)
        return result
    }

    /** The rating prompt's `action_card_approved`: the card the person approved has run. */
    private fun noteActionCardApproved(result: DataResult<ThreadActionCard>) {
        if ((result as? DataResult.Content)?.data?.isExecuted == true) RaftRatingPrompt.onActionCardApproved()
    }

    /**
     * [commitThreadActionCard] behind step-up: an Approve Login card's execute refused with 403
     * `second_factor_step_up_required` opens [stepUp]'s "Verify it's you" dialog and, once it
     * passed, runs once more. [SecondFactorStepUpOutcome.Cancelled] when the dialog was closed (the
     * caller shows nothing). Any other card's commit is the same as [commitThreadActionCard].
     */
    suspend fun commitThreadActionCard(
        payload: RaftThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard,
        stepUp: SecondFactorStepUpCoordinator,
        onChannelCreated: (HomeChannelRow) -> Unit = {},
    ): SecondFactorStepUpOutcome<ThreadActionCard> =
        stepUp.run(webTarget = null, action = threadActionCardStepUpAction(card.type)) {
            commitThreadActionCard(payload, messageId, card, onChannelCreated)
        }

    /**
     * Marks an `agent:migrate` card executed once the native migration dialog started the move
     * (`POST /api/actions/:messageId/mark-executed`, `{ result: { kind: "agent-migration", agentId, targetMachineId } }`).
     */
    suspend fun markAgentMigrationActionCardExecuted(
        messageId: String,
        card: ThreadActionCard,
        agentId: String,
        targetMachineId: String,
    ): DataResult<ThreadActionCard> {
        if (!card.isAgentMigrateAction()) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val executedCard = card.withExecutedAgentMigration(agentId = agentId, targetMachineId = targetMachineId)
        return markThreadActionCardExecuted(
            actionsApi = actionsApi,
            messageId = messageId,
            body =
                buildMarkAgentMigrationActionExecutedBody(
                    agentId = agentId,
                    targetMachineId = targetMachineId,
                    expectedConfirmationVersion = card.confirmationVersion,
                ),
            executedCard = executedCard,
        ).also(::noteActionCardApproved)
    }

    /**
     * The Install of an `integration:install_marketplace_app` card's confirmation (web
     * `InstallAppCardDialog`): `POST /actions/:id/execute` with the card's state and
     * confirmation version and nothing else, so the server installs the set it bound to the
     * card. A card prepared before that binding is refused here, as the web disables Install.
     */
    suspend fun confirmInstallMarketplaceAppActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> =
        when {
            !card.isInstallMarketplaceAppAction() ->
                DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
            card.isFrozen ->
                DataResult.Error(ScreenError(code = "action_card_frozen", retryable = false))
            !card.installSetBound ->
                DataResult.Error(ScreenError(code = "action_card_install_unbound", retryable = false))
            else -> executeInlineThreadActionCard(messageId, card).also(::noteActionCardApproved)
        }

    /**
     * A spec card's execute (`action-card.v1`, web `ActionCardSpecCard`): POST
     * /actions/:id/execute with the card's state and confirmation version, plus the form and its
     * values when an `open_form` sheet submits ([form]). The legacy type paths
     * ([commitThreadActionCard]) are not involved; the server runs the card it stored. An answer
     * without the card keeps a truthful terminal state, as [executeInlineThreadActionCard] does.
     */
    suspend fun executeActionCardSpec(
        messageId: String,
        card: ThreadActionCard,
        form: ExecuteActionFormBody? = null,
    ): DataResult<ActionCardExecuteOutcome> {
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        if (card.isFrozen) return DataResult.Error(ScreenError(code = "action_card_frozen", retryable = false))
        val body =
            buildExecuteActionBody(expectedState = "prepared", expectedConfirmationVersion = card.confirmationVersion, form = form)
        return when (val result = actionsApi.executeActionCardSpec(MessageId(messageId), body)) {
            is DataResult.Content -> {
                val outcome = result.data
                // A feedback card sent through its form sheet: Settings › Feedback stops suggesting an
                // agent's draft (web executeActionCard, slock #14076). The native form marks it too.
                if (outcome is ActionCardExecuteOutcome.Executed && outcome.card?.let { it.isExecuted && it.isFeedbackSubmitAction() } == true) {
                    RaftFeedbackAgentDraftSent.repository.markSent(readSession()?.user?.id)
                }
                if (outcome is ActionCardExecuteOutcome.Executed && outcome.card == null) {
                    DataResult.Content(ActionCardExecuteOutcome.Executed(card.copy(state = "executed")))
                } else {
                    result
                }
            }
            is DataResult.Empty -> DataResult.Error(ScreenError(code = "action_card_execute_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * Closes an open `question:ask` card without answering (`POST /actions/:id/close`, web
     * `closeQuestionCard`, slock #16473). An answer without metadata keeps a truthful `closed`
     * terminal state, as [executeActionCardSpec] does for `executed`.
     */
    suspend fun closeQuestionActionCard(
        messageId: String,
        card: ThreadActionCard,
    ): DataResult<ActionCardCloseOutcome> {
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        if (card.isFrozen) return DataResult.Error(ScreenError(code = "action_card_frozen", retryable = false))
        return when (val result = actionsApi.closeQuestionActionCard(MessageId(messageId))) {
            is DataResult.Content -> {
                val outcome = result.data
                if (outcome is ActionCardCloseOutcome.Closed && outcome.card == null) {
                    DataResult.Content(ActionCardCloseOutcome.Closed(card.copy(state = "closed")))
                } else {
                    result
                }
            }
            is DataResult.Empty -> DataResult.Error(ScreenError(code = "action_card_close_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * Reads an open `channel:invite_external` card's email list on click
     * (`GET /api/actions/:messageId/external-invite-addresses`, slock #16530, #16671).
     */
    suspend fun readExternalInviteCardAddresses(
        messageId: String,
        card: ThreadActionCard,
    ): DataResult<ExternalInviteAddressesOutcome> {
        if (!card.isInviteExternalAction()) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        if (card.isFrozen) return DataResult.Error(ScreenError(code = "action_card_frozen", retryable = false))
        return actionsApi.externalInviteCardAddresses(MessageId(messageId))
    }

    /**
     * Declines an open `channel:invite_external` card (`POST /api/actions/:messageId/close`,
     * slock #16530).
     */
    suspend fun declineExternalInviteActionCard(
        messageId: String,
        card: ThreadActionCard,
    ): DataResult<ActionCardCloseOutcome> {
        if (!card.isInviteExternalAction()) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        if (card.isFrozen) return DataResult.Error(ScreenError(code = "action_card_frozen", retryable = false))
        return when (val result = actionsApi.closeQuestionActionCard(MessageId(messageId))) {
            is DataResult.Content -> {
                val outcome = result.data
                if (outcome is ActionCardCloseOutcome.Closed && outcome.card == null) {
                    DataResult.Content(ActionCardCloseOutcome.Closed(card.copy(state = "closed")))
                } else {
                    result
                }
            }
            is DataResult.Empty -> DataResult.Error(ScreenError(code = "action_card_close_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * Marks a `channel:invite_external` card executed once invites were sent or a server was
     * connected (`POST /api/actions/:messageId/mark-executed`, `{ result: { kind: "external-invites",
     * channelId, addresses } }`, slock #16530, #16671).
     */
    suspend fun markExternalInvitesActionCardExecuted(
        messageId: String,
        card: ThreadActionCard,
        channelId: String,
        addresses: Int,
    ): DataResult<ThreadActionCard> {
        if (!card.isInviteExternalAction()) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val count = addresses.coerceAtLeast(0)
        val executedCard = card.withExecutedExternalInvites(channelId = channelId, addresses = count)
        return markThreadActionCardExecuted(
            actionsApi = actionsApi,
            messageId = messageId,
            body =
                buildMarkExternalInvitesActionExecutedBody(
                    channelId = channelId,
                    addresses = count,
                    expectedConfirmationVersion = card.confirmationVersion,
                ),
            executedCard = executedCard,
        ).also(::noteActionCardApproved)
    }

    /**
     * Marks an `integration:connect_mcp` card executed once the Agent has an authorized connection
     * (`POST /api/actions/:messageId/mark-executed`, `{ result: { kind: "mcp-connection",
     * mcpServerId, agentId } }`, botiverse/slock `actionCardIntentFlows.tsx`).
     */
    suspend fun markMcpConnectionActionCardExecuted(
        messageId: String,
        card: ThreadActionCard,
        mcpServerId: String,
        agentId: String,
    ): DataResult<ThreadActionCard> {
        if (!card.isConnectMcpAction()) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val executedCard = card.withExecutedMcpConnection(mcpServerId = mcpServerId, agentId = agentId)
        return markThreadActionCardExecuted(
            actionsApi = actionsApi,
            messageId = messageId,
            body =
                buildMarkMcpConnectionActionExecutedBody(
                    mcpServerId = mcpServerId,
                    agentId = agentId,
                    expectedConfirmationVersion = card.confirmationVersion,
                ),
            executedCard = executedCard,
        ).also(::noteActionCardApproved)
    }

    /**
     * Reconfirms a `reconfirm_required` card (`POST /api/actions/:messageId/reconfirm`), bringing
     * it back to `prepared` under a new confirmation version.
     */
    suspend fun reconfirmActionCard(
        messageId: String,
        card: ThreadActionCard,
    ): DataResult<ThreadActionCard> =
        when {
            card.isFrozen -> DataResult.Error(ScreenError(code = "action_card_frozen", retryable = false))
            threadActionCardCommitBlockedByEnd(card, defaultNowMillis()) ->
                DataResult.Error(ScreenError(code = ActionCardEndedErrorCode, retryable = false))
            else -> reconfirmThreadActionCard(messageId, card)
        }

    /**
     * Checks a `seats:add` card before paying (`GET /api/actions/:messageId/seat-request`, slock
     * #16449, #16453). Routes with the card's `sourceServerId` because the DM may live on another
     * server than the currently open server.
     */
    suspend fun checkSeatRequest(
        messageId: String,
        card: ThreadActionCard,
    ): DataResult<SeatRequestCheckOutcome> {
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        if (card.isFrozen) return DataResult.Error(ScreenError(code = "action_card_frozen", retryable = false))
        val serverIdOverride = card.sourceServerId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId)
        return actionsApi.checkSeatRequest(MessageId(messageId), serverIdOverride)
    }

    /**
     * Completes a `seats:add` card (`POST /api/actions/:messageId/seats-added`, slock #16449,
     * #16453), routing to the card's `sourceServerId` when present.
     */
    suspend fun completeSeatRequest(
        messageId: String,
        card: ThreadActionCard,
    ): DataResult<SeatRequestCompleteOutcome> {
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        if (card.isFrozen) return DataResult.Error(ScreenError(code = "action_card_frozen", retryable = false))
        val serverIdOverride = card.sourceServerId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId)
        return when (val result = actionsApi.completeSeatRequest(MessageId(messageId), serverIdOverride)) {
            is DataResult.Content -> {
                val outcome = result.data
                if (outcome.card == null) {
                    DataResult.Content(SeatRequestCompleteOutcome.Completed(card.copy(state = "executed")))
                } else {
                    result
                }
            }
            is DataResult.Empty -> DataResult.Error(ScreenError(code = "action_card_seats_added_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    /**
     * web ActionCard Reconfirm: POST /actions/:id/reconfirm brings a `reconfirm_required` card
     * back to `prepared` under a new confirmation version; the response carries the card.
     */
    private suspend fun reconfirmThreadActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        return when (val result = actionsApi.reconfirmAction(MessageId(messageId))) {
            is DataResult.Content ->
                result.data.actionCard?.let { DataResult.Content(it) }
                    ?: DataResult.Error(ScreenError(code = "action_card_reconfirm_empty", retryable = true))
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "action_card_reconfirm_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun executeInlineThreadActionCard(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        return when (val result = actionsApi.executeAction(
            MessageId(messageId),
            buildExecuteActionBody(expectedState = "prepared", expectedConfirmationVersion = card.confirmationVersion)
        )) {
            is DataResult.Content ->
                DataResult.Content(
                    result.data.actionCard
                        ?: if (card.type.equals("integration:approve_agent_login", ignoreCase = true)) {
                            card.copy(
                                state = "executed",
                                resultKind = "agent-integration-login",
                                resultId = card.agentId,
                                resultName = card.clientName,
                                executedByUserName = card.executedByUserName
                            )
                        } else {
                            // The canonical execute response normally carries
                            // the updated card. Keep a truthful terminal state
                            // if an older compatible server only returns the
                            // message id; never invent a type-specific result.
                            card.copy(state = "executed")
                        }
                )
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "action_card_execute_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun commitAgentCreateActionCardInternal(
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("agent:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val agentName = card.name.trim()
        if (agentName.isBlank()) {
            return DataResult.Error(ScreenError(code = "action_card_agent_name_missing", retryable = false))
        }
        val agentsApi = agentsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_agents_api_missing", retryable = false))
        val actionsApi = actionCardsApiClient
            ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))

        return when (
            val created =
                agentsApi.createAgent(
                    buildCreateAgentBody(
                        CreateAgentBody(
                            name = agentName.removePrefix("@"),
                            description = card.description?.takeIf { it.isNotBlank() },
                            machineId = card.requiredComputer?.takeIf { it.isNotBlank() }
                                ?: card.suggestedComputer?.takeIf { it.isNotBlank() }
                        )
                    )
                )
        ) {
            is DataResult.Content -> {
                val resultName = created.data.name.takeIf { it.isNotBlank() } ?: agentName.removePrefix("@")
                val executedCard = card.withExecutedAgent(id = created.data.id, name = resultName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body = buildMarkAgentActionExecutedBody(agentId = created.data.id, agentName = resultName, expectedConfirmationVersion = card.confirmationVersion),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "agent_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    private suspend fun commitChannelCreateActionCard(
        messageId: String,
        card: ThreadActionCard,
        onChannelCreated: (HomeChannelRow) -> Unit,
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("channel:create", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val channelName = card.name.trim()
        if (channelName.isBlank()) {
            return DataResult.Error(ScreenError(code = "action_card_channel_name_missing", retryable = false))
        }
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "action_card_channel_repository_missing", retryable = false))
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        return when (
            val created =
                repository.createChannel(
                    CreateChannelRequest(
                        name = channelName,
                        description = card.description?.trim()?.takeIf { it.isNotBlank() },
                        visibility = card.visibility?.trim()?.takeIf { it.isNotBlank() } ?: "public",
                        agentMemberIds = card.initialAgents.cleanActionCardIds(),
                        userMemberIds = card.initialHumans.cleanActionCardIds()
                    )
                )
        ) {
            is DataResult.Content -> {
                val channel = created.data.channel
                onChannelCreated(channel)
                val createdChannelId = channel.channelId?.value ?: channel.threadId.value
                val createdChannelName = channel.name.takeIf { it.isNotBlank() } ?: channelName
                val executedCard = card.withExecutedChannel(id = createdChannelId, name = createdChannelName)
                markThreadActionCardExecuted(
                    actionsApi = actionsApi,
                    messageId = messageId,
                    body =
                        buildMarkChannelActionExecutedBody(
                            channelId = createdChannelId,
                            channelName = createdChannelName,
                            expectedConfirmationVersion = card.confirmationVersion
                        ),
                    executedCard = executedCard
                )
            }
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "channel_create_empty", retryable = true))
            is DataResult.Error -> created
            is DataResult.NoPermission -> created
        }
    }

    private suspend fun commitChannelAddMembersActionCard(
        payload: RaftThreadScreenPayload,
        messageId: String,
        card: ThreadActionCard
    ): DataResult<ThreadActionCard> {
        if (!card.type.equals("channel:add_member", ignoreCase = true)) {
            return DataResult.Error(ScreenError(code = "action_card_unsupported", retryable = false))
        }
        val targetChannelId =
            card.channel?.trim()?.takeIf { it.isNotBlank() }
                ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "action_card_channel_missing", retryable = false))
        val humanIds = card.humans.cleanActionCardIds()
        val agentIds = card.agents.cleanActionCardIds()
        if (humanIds.isEmpty() && agentIds.isEmpty()) {
            return DataResult.Error(ScreenError(code = "action_card_members_missing", retryable = false))
        }
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "action_card_member_repository_missing", retryable = false))
        val actionsApi =
            actionCardsApiClient
                ?: return DataResult.Error(ScreenError(code = "action_card_actions_api_missing", retryable = false))
        val channelId = ChannelId(targetChannelId)
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)

        // task #352 (Web #7209): ONE atomic batch instead of per-member loops. The
        // server dedupes and validates the whole batch, rolling back on any failure,
        // so there is no partial success and we never accumulate a half-applied set
        // here. `alreadyMembers`-only is a success (idempotent re-send).
        val batchRequest =
            AddChannelMembersBatchRequest(
                channelId = channelId,
                userIds = humanIds.map(::MemberId),
                agentIds = agentIds.map(::MemberId)
            )
        val batchResult =
            if (serverMembersStore != null && serverId != null) {
                serverMembersStore.addChannelMembersBatch(serverId, batchRequest, DataRequestPriority.Foreground)
            } else {
                repository.addChannelMembersBatch(DataRequestPriority.Foreground, batchRequest)
            }
        val addedHumans: List<String>
        val addedAgents: List<String>
        when (batchResult) {
            is DataResult.Content -> {
                addedHumans = batchResult.data.addedUserIds
                addedAgents = batchResult.data.addedAgentIds
            }
            is DataResult.Empty -> return DataResult.Empty(batchResult.reason)
            is DataResult.Error -> return batchResult
            is DataResult.NoPermission -> return batchResult
        }
        // `added` may be empty when every requested member was ALREADY present: the
        // server contract defines that as an idempotent SUCCESS (added:[] with the
        // ids under alreadyMembers), so we must NOT fail the action card here. The
        // card still advances to executed; only the recorded added-ids stay empty.
        val channelName =
            payload.title.takeIf { targetChannelId == payload.sourceThreadId && it.isNotBlank() }
                ?: card.resultChannelName?.takeIf { it.isNotBlank() }
                ?: targetChannelId
        val executedCard =
            card.withExecutedChannelMembers(
                channelId = targetChannelId,
                channelName = channelName,
                addedHumanIds = addedHumans,
                addedAgentIds = addedAgents
            )
        return markThreadActionCardExecuted(
            actionsApi = actionsApi,
            messageId = messageId,
            body =
                buildMarkChannelMembersActionExecutedBody(
                    channelId = targetChannelId,
                    channelName = channelName,
                    addedHumanIds = addedHumans,
                    addedAgentIds = addedAgents,
                    expectedConfirmationVersion = card.confirmationVersion
                ),
            executedCard = executedCard
        )
    }

    /**
     * POST /actions/:id/mark-executed answers `{ messageId, metadata }` with the stored card
     * (executedBy/At, result, `presentation.card`), as execute does; that card is applied.
     * [executedCard], built here, is only the fallback for an answer without the card.
     */
    private suspend fun markThreadActionCardExecuted(
        actionsApi: RaftIntegrationsActionsPushApiClient,
        messageId: String,
        body: String,
        executedCard: ThreadActionCard
    ): DataResult<ThreadActionCard> =
        when (val result = actionsApi.markActionExecuted(MessageId(messageId), body)) {
            is DataResult.Content -> DataResult.Content(result.data.actionCard ?: executedCard)
            is DataResult.Empty ->
                DataResult.Error(ScreenError(code = "action_card_mark_executed_empty", retryable = true))
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
}

/**
 * The server's `StepUpAction` key of an action card kind gated by step-up (slock
 * `actionCardSecondFactor.ts`), for Continue in browser; null for any other kind (the dialog then
 * keeps Open on the web).
 */
internal fun threadActionCardStepUpAction(type: String): String? =
    when (type.trim().lowercase()) {
        "integration:register_app" -> "integration_app_register"
        else -> null
    }
