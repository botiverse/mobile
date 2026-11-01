package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataResult
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpOutcome
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardInstallText
import build.raft.app.data.boundInstallText
import build.raft.app.data.installSetBound
import build.raft.app.data.isInstallMarketplaceAppAction
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The confirmation an `integration:install_marketplace_app` card's button opens (web
 * `InstallAppCardDialog`, audit V-85). The card asks only for the scopes one agent login needs,
 * but installing approves the app's whole declaration on this Server, so the admin confirms the
 * set the server bound to the card (`installScopes` / `installGroups`) first, and the text the
 * app adds to agent prompts when the card carries one (`installTextRevisionId` / `installText`).
 * Only its Install executes the card; × (Cancel) and Back run nothing.
 */
internal data class ThreadInstallAppActionDialogState(
    val messageId: String,
    /** The live card (follows realtime and the execute answer, not the one the dialog opened with). */
    val card: ThreadActionCard,
    val submitting: Boolean = false,
    /** A refusal no retry fixes (the card no longer matches the app): Install stays disabled. */
    val blocked: Boolean = false,
    /** The server's 409 for a stale card ([ThreadInstallAppStaleCode]), else null. */
    val staleCode: ThreadInstallAppStaleCode? = null,
    /** Any other failure: the server's text, else the generic "Failed to execute action". */
    val failed: Boolean = false,
    val errorServerMessage: String? = null,
) {
    /** The card left `prepared` while the dialog was open (done, frozen, or to confirm again). */
    val cardChange: ThreadInstallAppCardChange?
        get() =
            when {
                card.isExecuted -> ThreadInstallAppCardChange.Executed
                card.needsReconfirmation -> ThreadInstallAppCardChange.NeedsReconfirmation
                card.isFrozen -> ThreadInstallAppCardChange.Frozen
                else -> null
            }

    /** Install is enabled: the set is bound, the card is still prepared, nothing refused it or is in flight. */
    val canInstall: Boolean
        get() = card.installSetBound && cardChange == null && !blocked && !submitting
}

/** Why a card that changed while its confirmation was open can't be installed from it. */
internal enum class ThreadInstallAppCardChange { Executed, NeedsReconfirmation, Frozen }

/**
 * 409s from executing an install card that no retry can fix (web `STALE_CARD_MESSAGE_IDS`): the
 * card is bound to an app, an agent and a set of scopes, and one of them no longer matches.
 * [TextChanged]: the app's prompt text moved past the revision the dialog showed (web
 * `appTextChangedCurrent`: a 409 `APP_PERMISSIONS_CHANGED` whose `current_text_revision_id`
 * differs from the card's `installTextRevisionId`).
 */
internal enum class ThreadInstallAppStaleCode(val wire: String?) {
    PermissionsChanged("APP_PERMISSIONS_CHANGED"),
    AppChanged("MARKETPLACE_APP_CHANGED"),
    ScopesChanged("SCOPE_BINDING_CHANGED"),
    AgentChanged("AGENT_BINDING_CHANGED"),
    TextChanged(null);

    companion object {
        fun from(error: ScreenError, shownTextRevisionId: String?): ThreadInstallAppStaleCode? {
            val code = error.serverCode ?: return null
            if (shownTextRevisionId != null && code == PermissionsChanged.wire) {
                val current = error.serverFields["current_text_revision_id"]
                if (current != null && current != shownTextRevisionId) return TextChanged
            }
            return entries.firstOrNull { it.wire != null && it.wire == code }
        }
    }
}

internal sealed interface ThreadInstallAppActionIntent {
    /** The card's button: show the confirmation. Executes nothing. */
    data class Open(val messageId: String, val card: ThreadActionCard) : ThreadInstallAppActionIntent

    /** The card as the thread now holds it (realtime, the execute answer). */
    data class CardUpdated(val messageId: String, val card: ThreadActionCard) : ThreadInstallAppActionIntent

    /** × / Cancel / Back: close without running anything (ignored while installing). */
    data object Dismiss : ThreadInstallAppActionIntent

    /** The dialog's Install: the one path that executes the card. */
    data object Confirm : ThreadInstallAppActionIntent
}

/**
 * MVI owner of the install confirmation. [install] is the server call
 * (`SharedThreadDataSource.confirmInstallMarketplaceAppActionCard`); it runs only on
 * [ThreadInstallAppActionIntent.Confirm], at most once at a time, with the live card.
 */
internal class ThreadInstallAppActionDialogStore(
    private val scope: CoroutineScope,
    private val install: suspend (messageId: String, card: ThreadActionCard) -> DataResult<ThreadActionCard>,
    /** The executed card, to apply to the thread; the dialog has closed. */
    private val onInstalled: (messageId: String, card: ThreadActionCard) -> Unit = { _, _ -> },
    /** A NoPermission the route handles (session expired); true closes the dialog. */
    private val onNoPermission: (DataResult.NoPermission) -> Boolean = { false },
    /**
     * "Verify it's you" for an install refused with 403 `second_factor_step_up_required` (the
     * install card is approver-class): verified, the install runs once more; closed, the
     * confirmation stays open with no error.
     */
    private val stepUp: SecondFactorStepUpCoordinator? = null,
) {
    private val _state = MutableStateFlow<ThreadInstallAppActionDialogState?>(null)
    val state: StateFlow<ThreadInstallAppActionDialogState?> = _state.asStateFlow()
    private var requestToken = 0L

    fun dispatch(intent: ThreadInstallAppActionIntent) {
        when (intent) {
            is ThreadInstallAppActionIntent.Open -> {
                if (!intent.card.isInstallMarketplaceAppAction()) return
                if (_state.value?.submitting == true) return
                _state.value = ThreadInstallAppActionDialogState(messageId = intent.messageId, card = intent.card)
            }
            is ThreadInstallAppActionIntent.CardUpdated -> {
                val current = _state.value ?: return
                if (current.messageId != intent.messageId || current.card == intent.card) return
                if (!intent.card.isInstallMarketplaceAppAction()) return
                _state.value = current.copy(card = intent.card)
            }
            ThreadInstallAppActionIntent.Dismiss -> {
                if (_state.value?.submitting == true) return
                _state.value = null
            }
            ThreadInstallAppActionIntent.Confirm -> confirm()
        }
    }

    private fun confirm() {
        val current = _state.value ?: return
        if (!current.canInstall) return
        val token = ++requestToken
        _state.value = current.copy(submitting = true, failed = false, staleCode = null, errorServerMessage = null)
        scope.launch {
            val outcome =
                stepUp?.run { install(current.messageId, current.card) }
                    ?: SecondFactorStepUpOutcome.Completed(install(current.messageId, current.card))
            val latest = _state.value
            // Closed, or replaced by another card's dialog: drop the answer.
            if (token != requestToken || latest == null || latest.messageId != current.messageId) return@launch
            val settled = latest.copy(submitting = false)
            val result =
                when (outcome) {
                    is SecondFactorStepUpOutcome.Completed -> outcome.result
                    SecondFactorStepUpOutcome.Cancelled -> {
                        _state.value = settled
                        return@launch
                    }
                }
            when (result) {
                is DataResult.Content -> {
                    _state.value = null
                    onInstalled(current.messageId, result.data)
                }
                is DataResult.Empty -> _state.value = settled.copy(failed = settled.cardChange == null)
                is DataResult.Error -> {
                    val stale = ThreadInstallAppStaleCode.from(result.error, current.card.installTextRevisionId)
                    _state.value =
                        when {
                            // The card moved on meanwhile (done elsewhere, frozen, to reconfirm): say that.
                            settled.cardChange != null -> settled
                            stale != null -> settled.copy(blocked = true, staleCode = stale)
                            else ->
                                settled.copy(
                                    blocked = result.error.code == "action_card_install_unbound",
                                    failed = true,
                                    errorServerMessage = result.error.serverMessage?.takeIf { it.isNotBlank() },
                                )
                        }
                }
                is DataResult.NoPermission -> {
                    _state.value =
                        if (onNoPermission(result)) {
                            null
                        } else {
                            settled.copy(failed = true, errorServerMessage = result.blocker.serverMessage?.takeIf { it.isNotBlank() })
                        }
                }
            }
        }
    }
}

/**
 * The production wiring of [ThreadInstallAppActionDialogStore] (ThreadPage): the data source's
 * confirmation call, the executed card applied to the thread, and an expired session routed
 * through the page's `routeAuthRequired` like every other card path.
 */
internal fun threadInstallAppActionDialogStore(
    scope: CoroutineScope,
    dataSource: SharedThreadDataSource,
    applyCard: (messageId: String, card: ThreadActionCard) -> Unit,
    toastInstalled: () -> Unit,
    routeAuthRequired: (DataResult.NoPermission) -> Boolean,
    stepUp: SecondFactorStepUpCoordinator? = null,
): ThreadInstallAppActionDialogStore =
    ThreadInstallAppActionDialogStore(
        scope = scope,
        install = { messageId, card -> dataSource.confirmInstallMarketplaceAppActionCard(messageId, card) },
        onInstalled = { messageId, card ->
            applyCard(messageId, card)
            toastInstalled()
        },
        onNoPermission = routeAuthRequired,
        stepUp = stepUp,
    )

/** The confirmation as a raft-ui phone sheet (web `NarrowDialogContent narrow="sheet"`). */
@Composable
internal fun ThreadInstallAppActionDialog(
    state: ThreadInstallAppActionDialogState,
    onIntent: (ThreadInstallAppActionIntent) -> Unit,
) {
    // The dialog sits outside the page's design-system theme.
    RaftDesignSystemTheme {
        RaftDialogSheet(
            onDismissRequest = { onIntent(ThreadInstallAppActionIntent.Dismiss) },
            // A dialog opened by a button takes that button's label.
            title = stringResource(Res.string.thread_action_card_install_app),
            commitLabel = stringResource(Res.string.thread_action_card_install_dialog_confirm),
            commitEnabled = state.canInstall,
            onCommit = { onIntent(ThreadInstallAppActionIntent.Confirm) },
            closeLabel = stringResource(Res.string.common_close),
            cancelLabel = stringResource(Res.string.common_cancel),
            // web closeDisabled={installing}
            closeEnabled = !state.submitting,
        ) {
            ThreadInstallAppActionDialogBody(state)
        }
    }
}

/** The sheet's surface without its window, for screenshot fixtures. */
@Composable
internal fun ThreadInstallAppActionSheetSurfaceForFixture(
    state: ThreadInstallAppActionDialogState,
    modifier: Modifier = Modifier,
) {
    RaftDialogSheetSurface(
        title = stringResource(Res.string.thread_action_card_install_app),
        commitLabel = stringResource(Res.string.thread_action_card_install_dialog_confirm),
        commitEnabled = state.canInstall,
        onCommit = {},
        onClose = {},
        modifier = modifier,
        closeLabel = stringResource(Res.string.common_close),
        closeEnabled = !state.submitting,
    ) {
        ThreadInstallAppActionDialogBody(state)
    }
}

@Composable
private fun ColumnScope.ThreadInstallAppActionDialogBody(state: ThreadInstallAppActionDialogState) {
    val card = state.card
    val client = card.clientName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.thread_action_card_app)
    val agent = (card.agentName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.thread_action_card_agent)).removePrefix("@")
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RaftText(
            stringResource(Res.string.thread_action_card_install_dialog_description, agent, client),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundMuted,
        )
        if (card.installSetBound) {
            ThreadAppPermissionSetList(scopes = card.installScopes.orEmpty(), groups = card.installGroups.orEmpty())
        } else {
            RaftBanner(
                description = stringResource(Res.string.thread_action_card_install_dialog_unbound, agent),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
            )
        }
        card.boundInstallText?.let { text -> ThreadAppPromptTextSection(text, service = card.clientKey) }
        val error =
            when (state.cardChange) {
                ThreadInstallAppCardChange.Executed -> stringResource(Res.string.thread_action_card_install_dialog_card_done)
                ThreadInstallAppCardChange.NeedsReconfirmation ->
                    stringResource(Res.string.thread_action_card_install_dialog_card_reconfirm)
                ThreadInstallAppCardChange.Frozen -> stringResource(Res.string.thread_action_card_install_dialog_card_frozen)
                null ->
                    when (state.staleCode) {
                        ThreadInstallAppStaleCode.PermissionsChanged ->
                            stringResource(Res.string.thread_action_card_install_dialog_permissions_changed, client, agent)
                        ThreadInstallAppStaleCode.AppChanged ->
                            stringResource(Res.string.thread_action_card_install_dialog_app_changed, client, agent)
                        ThreadInstallAppStaleCode.ScopesChanged ->
                            stringResource(Res.string.thread_action_card_install_dialog_scopes_changed, client, agent)
                        ThreadInstallAppStaleCode.AgentChanged ->
                            stringResource(Res.string.thread_action_card_install_dialog_agent_changed, agent)
                        ThreadInstallAppStaleCode.TextChanged ->
                            stringResource(Res.string.thread_action_card_install_dialog_text_changed, client, agent)
                        null ->
                            if (state.failed) {
                                state.errorServerMessage ?: stringResource(Res.string.thread_action_card_execute_failed)
                            } else {
                                null
                            }
                    }
            }
        if (error != null) {
            RaftBanner(description = error, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
        }
    }
}

/**
 * web `AppPromptTextSection`: the heading, where the text goes, and the text exactly as agents
 * get it ([threadAppPromptTextLines]) in a plain code block that wraps, so the admin reads every
 * word before confirming.
 */
@Composable
internal fun ThreadAppPromptTextSection(text: ThreadActionCardInstallText, service: String?, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftText(
            stringResource(Res.string.thread_action_card_prompt_text_title),
            style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
        )
        RaftText(stringResource(Res.string.thread_action_card_prompt_text_install_note), style = typography.footnote, color = colors.foregroundMuted)
        val lines = threadAppPromptTextLines(text, service)
        if (lines == null) {
            RaftText(stringResource(Res.string.thread_action_card_prompt_text_empty), style = typography.footnote, color = colors.foregroundMuted)
        } else {
            RaftCodeBlock(text = lines.joinToString("\n"), wrap = true)
        }
    }
}

private val PromptTextServiceId = Regex("^[a-z][a-z0-9-]{2,63}$")
private const val PromptTextLineMax = 160
private const val PromptTextInertMax = 4_000
private val PromptTextAgentMarkup = Regex("</?(?:result|preview|match)\\b[^>]*>|<omit\\s*/>", RegexOption.IGNORE_CASE)

/**
 * The two lines one app adds to every agent's system prompt (slock shared `appPromptTextLines`:
 * the inert rendering, then the directory's one-line clamp and format), or null when it adds
 * nothing (no name or no When to use).
 */
internal fun threadAppPromptTextLines(text: ThreadActionCardInstallText, service: String?): List<String>? {
    fun inert(value: String): String {
        val clamped = if (value.length <= PromptTextInertMax) value else value.take(PromptTextInertMax) + "\n[truncated]"
        return PromptTextAgentMarkup.replace(clamped) { tag ->
            tag.value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        }
    }
    fun oneLine(value: String): String {
        val collapsed =
            buildString {
                value.forEach { char ->
                    when (char.category) {
                        CharCategory.FORMAT -> Unit
                        CharCategory.CONTROL -> append(' ')
                        else -> append(char)
                    }
                }
            }.replace(Regex("\\s+"), " ").trim()
        val points = collapsed.codePointList()
        return if (points.size <= PromptTextLineMax) collapsed else points.take(PromptTextLineMax).joinToString("") + "[truncated]"
    }
    val name = oneLine(inert(text.name))
    val whenToUse = oneLine(text.whenToUse?.let(::inert).orEmpty())
    if (name.isEmpty() || whenToUse.isEmpty()) return null
    val description = text.description?.takeIf { it.isNotEmpty() }?.let { oneLine(inert(it)) }.orEmpty()
    val serviceId = service?.takeIf { PromptTextServiceId.matches(it) }
    return listOf(
        "- $name" + (serviceId?.let { " (service `$it`)" } ?: "") + (if (description.isNotEmpty()) " — $description" else ""),
        "  Use it when: $whenToUse",
    )
}

/** Splits into Unicode code points (a surrogate pair stays one), as JS `[...string]` does. */
private fun String.codePointList(): List<String> {
    val out = ArrayList<String>(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            out += substring(i, i + 2)
            i += 2
        } else {
            out += c.toString()
            i += 1
        }
    }
    return out
}

/**
 * The login scopes and data groups an install records (web `AppPermissionSetList`), as a
 * DescriptionList. Nothing is filtered: a scope or group this build has no copy for is listed by
 * its raw name with "Raft has no description", never dropped (it would still be granted).
 */
@Composable
internal fun ThreadAppPermissionSetList(scopes: List<String>, groups: List<String>, modifier: Modifier = Modifier) {
    val scopeRows =
        scopes.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { scope ->
            ThreadAppPermissionRow(name = scope, mono = true, description = localizedAppPermissionScopeDescription(scope))
        }
    val groupRows =
        groups.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { group ->
            val label = localizedAppPermissionGroupLabel(group)
            ThreadAppPermissionRow(
                name = label ?: group,
                mono = label == null,
                description = localizedAppPermissionGroupSummary(group),
            )
        }
    RaftDescriptionList(modifier = modifier.fillMaxWidth(), direction = RaftDescriptionListDirection.Horizontal) {
        Item(stringResource(Res.string.thread_action_card_permission_set_login_scopes)) { ThreadAppPermissionRows(scopeRows) }
        Item(stringResource(Res.string.thread_action_card_permission_set_data_access)) { ThreadAppPermissionRows(groupRows) }
    }
}

private data class ThreadAppPermissionRow(val name: String, val mono: Boolean, val description: String)

@Composable
private fun ThreadAppPermissionRows(rows: List<ThreadAppPermissionRow>) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    if (rows.isEmpty()) {
        RaftText(stringResource(Res.string.thread_action_card_permission_set_none), style = typography.callout, color = colors.foregroundHint)
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(
                    row.name,
                    style = if (row.mono) typography.monoMeta else typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                )
                RaftText(row.description, style = typography.footnote, color = colors.foregroundMuted)
            }
        }
    }
}

/** web `OAUTH_SCOPE_PRESENTATION` copy, then the reserved scope, then "no description". */
@Composable
internal fun localizedAppPermissionScopeDescription(scope: String): String =
    when (scope) {
        "openid" -> stringResource(Res.string.thread_action_card_scope_openid)
        "profile" -> stringResource(Res.string.thread_action_card_scope_profile)
        "email" -> stringResource(Res.string.thread_action_card_scope_email)
        "identity" -> stringResource(Res.string.thread_action_card_scope_identity)
        "agent:read" -> stringResource(Res.string.thread_action_card_scope_agent_read)
        "agent:event:write" -> stringResource(Res.string.thread_action_card_scope_agent_event_write)
        "agent:notification:write" -> stringResource(Res.string.thread_action_card_scope_agent_notification_write)
        "agent:action_request:write" -> stringResource(Res.string.thread_action_card_scope_agent_action_request_write)
        else -> stringResource(Res.string.thread_action_card_permission_set_unrecognized_scope)
    }

/** web `permissionGroupLabel`: "<resource> · read", except the reminder-write group. Null when unknown. */
@Composable
private fun localizedAppPermissionGroupLabel(group: String): String? {
    val resource =
        when (group) {
            "server" -> stringResource(Res.string.thread_action_card_group_server)
            "agent" -> stringResource(Res.string.thread_action_card_group_agent)
            "channel" -> stringResource(Res.string.thread_action_card_group_channel)
            "computer" -> stringResource(Res.string.thread_action_card_group_computer)
            "agent_reminder_write" -> return stringResource(Res.string.thread_action_card_group_agent_reminder_write)
            else -> return null
        }
    return stringResource(Res.string.thread_action_card_group_read, resource)
}

@Composable
private fun localizedAppPermissionGroupSummary(group: String): String =
    when (group) {
        "server" -> stringResource(Res.string.thread_action_card_group_server_summary)
        "agent" -> stringResource(Res.string.thread_action_card_group_agent_summary)
        "channel" -> stringResource(Res.string.thread_action_card_group_channel_summary)
        "computer" -> stringResource(Res.string.thread_action_card_group_computer_summary)
        "agent_reminder_write" -> stringResource(Res.string.thread_action_card_group_agent_reminder_write_summary)
        else -> stringResource(Res.string.thread_action_card_permission_set_unrecognized_group)
    }
