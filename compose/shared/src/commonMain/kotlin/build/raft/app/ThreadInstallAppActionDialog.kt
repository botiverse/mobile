package build.raft.app

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
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.installSetBound
import build.raft.app.data.isInstallMarketplaceAppAction
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The confirmation an `integration:install_marketplace_app` card's button opens (web
 * `InstallAppCardDialog`, audit V-85). The card asks only for the scopes one agent login needs,
 * but installing approves the app's whole declaration on this Server, so the admin confirms the
 * set the server bound to the card (`installScopes` / `installGroups`) first. Only its Install
 * executes the card; × (Cancel) and Back run nothing.
 */
internal data class ThreadInstallAppActionDialogState(
    val messageId: String,
    val card: ThreadActionCard,
    val submitting: Boolean = false,
    /** A refusal no retry fixes (the card no longer matches the app): Install stays disabled. */
    val blocked: Boolean = false,
    /** The server's 409 `code` for a stale card ([ThreadInstallAppStaleCode]), else null. */
    val staleCode: ThreadInstallAppStaleCode? = null,
    /** Any other failure: the server's text, else the generic "Failed to execute action". */
    val failed: Boolean = false,
    val errorServerMessage: String? = null,
) {
    /** Install is enabled: the set is bound, nothing refused it, nothing in flight. */
    val canInstall: Boolean
        get() = card.installSetBound && !blocked && !submitting
}

/**
 * 409s from executing an install card that no retry can fix (web `STALE_CARD_MESSAGE_IDS`): the
 * card is bound to an app, an agent and a set of scopes, and one of them no longer matches.
 */
internal enum class ThreadInstallAppStaleCode(val wire: String) {
    PermissionsChanged("APP_PERMISSIONS_CHANGED"),
    AppChanged("MARKETPLACE_APP_CHANGED"),
    ScopesChanged("SCOPE_BINDING_CHANGED"),
    AgentChanged("AGENT_BINDING_CHANGED");

    companion object {
        fun fromWire(code: String?): ThreadInstallAppStaleCode? = entries.firstOrNull { it.wire == code }
    }
}

internal sealed interface ThreadInstallAppActionIntent {
    /** The card's button: show the confirmation. Executes nothing. */
    data class Open(val messageId: String, val card: ThreadActionCard) : ThreadInstallAppActionIntent

    /** × / Cancel / Back: close without running anything (ignored while installing). */
    data object Dismiss : ThreadInstallAppActionIntent

    /** The dialog's Install: the one path that executes the card. */
    data object Confirm : ThreadInstallAppActionIntent
}

/**
 * MVI owner of the install confirmation. [install] is the server call
 * (`SharedThreadDataSource.confirmInstallMarketplaceAppActionCard`); it runs only on
 * [ThreadInstallAppActionIntent.Confirm], at most once at a time.
 */
internal class ThreadInstallAppActionDialogStore(
    private val scope: CoroutineScope,
    private val install: suspend (messageId: String, card: ThreadActionCard) -> DataResult<ThreadActionCard>,
    /** The executed card, to apply to the thread; the dialog has closed. */
    private val onInstalled: (messageId: String, card: ThreadActionCard) -> Unit = { _, _ -> },
    /** A NoPermission the route handles (session expired); true closes the dialog. */
    private val onNoPermission: (DataResult.NoPermission) -> Boolean = { false },
) {
    private val _state = MutableStateFlow<ThreadInstallAppActionDialogState?>(null)
    val state: StateFlow<ThreadInstallAppActionDialogState?> = _state.asStateFlow()

    fun dispatch(intent: ThreadInstallAppActionIntent) {
        when (intent) {
            is ThreadInstallAppActionIntent.Open -> {
                if (!intent.card.isInstallMarketplaceAppAction()) return
                if (_state.value?.submitting == true) return
                _state.value = ThreadInstallAppActionDialogState(messageId = intent.messageId, card = intent.card)
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
        val submitting = current.copy(submitting = true, failed = false, staleCode = null, errorServerMessage = null)
        _state.value = submitting
        scope.launch {
            val result = install(submitting.messageId, submitting.card)
            // A later Open (another card) replaced this dialog: drop the answer.
            if (_state.value !== submitting) return@launch
            when (result) {
                is DataResult.Content -> {
                    _state.value = null
                    onInstalled(submitting.messageId, result.data)
                }
                is DataResult.Empty ->
                    _state.value = submitting.copy(submitting = false, failed = true)
                is DataResult.Error -> {
                    val stale = ThreadInstallAppStaleCode.fromWire(result.error.serverCode)
                    _state.value =
                        submitting.copy(
                            submitting = false,
                            blocked = stale != null || result.error.code == "action_card_install_unbound",
                            staleCode = stale,
                            failed = stale == null,
                            errorServerMessage = result.error.serverMessage?.takeIf { stale == null && it.isNotBlank() },
                        )
                }
                is DataResult.NoPermission -> {
                    if (onNoPermission(result)) {
                        _state.value = null
                    } else {
                        _state.value =
                            submitting.copy(
                                submitting = false,
                                failed = true,
                                errorServerMessage = result.blocker.serverMessage?.takeIf { it.isNotBlank() },
                            )
                    }
                }
            }
        }
    }
}

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
            title = stringResource(MR.strings.thread_action_card_install_app),
            commitLabel = stringResource(MR.strings.thread_action_card_install_dialog_confirm),
            commitEnabled = state.canInstall,
            onCommit = { onIntent(ThreadInstallAppActionIntent.Confirm) },
            closeLabel = stringResource(MR.strings.common_close),
            cancelLabel = stringResource(MR.strings.common_cancel),
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
        title = stringResource(MR.strings.thread_action_card_install_app),
        commitLabel = stringResource(MR.strings.thread_action_card_install_dialog_confirm),
        commitEnabled = state.canInstall,
        onCommit = {},
        onClose = {},
        modifier = modifier,
        closeLabel = stringResource(MR.strings.common_close),
    ) {
        ThreadInstallAppActionDialogBody(state)
    }
}

@Composable
private fun ColumnScope.ThreadInstallAppActionDialogBody(state: ThreadInstallAppActionDialogState) {
    val card = state.card
    val client = card.clientName?.takeIf { it.isNotBlank() } ?: stringResource(MR.strings.thread_action_card_app)
    val agent = (card.agentName?.takeIf { it.isNotBlank() } ?: stringResource(MR.strings.thread_action_card_agent)).removePrefix("@")
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RaftText(
            stringResource(MR.strings.thread_action_card_install_dialog_description, agent, client),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundMuted,
        )
        if (card.installSetBound) {
            ThreadAppPermissionSetList(scopes = card.installScopes.orEmpty(), groups = card.installGroups.orEmpty())
        } else {
            RaftBanner(
                description = stringResource(MR.strings.thread_action_card_install_dialog_unbound, agent),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
            )
        }
        val error =
            when (state.staleCode) {
                ThreadInstallAppStaleCode.PermissionsChanged ->
                    stringResource(MR.strings.thread_action_card_install_dialog_permissions_changed, client, agent)
                ThreadInstallAppStaleCode.AppChanged ->
                    stringResource(MR.strings.thread_action_card_install_dialog_app_changed, client, agent)
                ThreadInstallAppStaleCode.ScopesChanged ->
                    stringResource(MR.strings.thread_action_card_install_dialog_scopes_changed, client, agent)
                ThreadInstallAppStaleCode.AgentChanged ->
                    stringResource(MR.strings.thread_action_card_install_dialog_agent_changed, agent)
                null ->
                    if (state.failed) {
                        state.errorServerMessage ?: stringResource(MR.strings.thread_action_card_execute_failed)
                    } else {
                        null
                    }
            }
        if (error != null) {
            RaftBanner(description = error, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
        }
    }
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
        Item(stringResource(MR.strings.thread_action_card_permission_set_login_scopes)) { ThreadAppPermissionRows(scopeRows) }
        Item(stringResource(MR.strings.thread_action_card_permission_set_data_access)) { ThreadAppPermissionRows(groupRows) }
    }
}

private data class ThreadAppPermissionRow(val name: String, val mono: Boolean, val description: String)

@Composable
private fun ThreadAppPermissionRows(rows: List<ThreadAppPermissionRow>) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    if (rows.isEmpty()) {
        RaftText(stringResource(MR.strings.thread_action_card_permission_set_none), style = typography.callout, color = colors.foregroundHint)
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
        "openid" -> stringResource(MR.strings.thread_action_card_scope_openid)
        "profile" -> stringResource(MR.strings.thread_action_card_scope_profile)
        "email" -> stringResource(MR.strings.thread_action_card_scope_email)
        "identity" -> stringResource(MR.strings.thread_action_card_scope_identity)
        "agent:read" -> stringResource(MR.strings.thread_action_card_scope_agent_read)
        "agent:event:write" -> stringResource(MR.strings.thread_action_card_scope_agent_event_write)
        "agent:notification:write" -> stringResource(MR.strings.thread_action_card_scope_agent_notification_write)
        "agent:action_request:write" -> stringResource(MR.strings.thread_action_card_scope_agent_action_request_write)
        else -> stringResource(MR.strings.thread_action_card_permission_set_unrecognized_scope)
    }

/** web `permissionGroupLabel`: "<resource> · read", except the reminder-write group. Null when unknown. */
@Composable
private fun localizedAppPermissionGroupLabel(group: String): String? {
    val resource =
        when (group) {
            "server" -> stringResource(MR.strings.thread_action_card_group_server)
            "agent" -> stringResource(MR.strings.thread_action_card_group_agent)
            "channel" -> stringResource(MR.strings.thread_action_card_group_channel)
            "computer" -> stringResource(MR.strings.thread_action_card_group_computer)
            "agent_reminder_write" -> return stringResource(MR.strings.thread_action_card_group_agent_reminder_write)
            else -> return null
        }
    return stringResource(MR.strings.thread_action_card_group_read, resource)
}

@Composable
private fun localizedAppPermissionGroupSummary(group: String): String =
    when (group) {
        "server" -> stringResource(MR.strings.thread_action_card_group_server_summary)
        "agent" -> stringResource(MR.strings.thread_action_card_group_agent_summary)
        "channel" -> stringResource(MR.strings.thread_action_card_group_channel_summary)
        "computer" -> stringResource(MR.strings.thread_action_card_group_computer_summary)
        "agent_reminder_write" -> stringResource(MR.strings.thread_action_card_group_agent_reminder_write_summary)
        else -> stringResource(MR.strings.thread_action_card_permission_set_unrecognized_group)
    }
