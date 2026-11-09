package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentHostedRuntimeSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.HostedAgentModelView
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.createAgentFallbackErrorText
import build.raft.app.model.MemberId
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/** Web `HOSTED_RUNTIME_POLL_MS`: how often the profile re-reads a state that is still changing. */
private const val HOSTED_RUNTIME_POLL_MS = 5_000L

internal enum class MembersExternalAgentSection {
    /** A cloud agent: its provisioning state replaces the steps (web AgentProfileInfo). */
    Hosted,
    Setup,
    /** The detail has not loaded: a cloud agent must not flash the self-setup steps on its way in. */
    NotKnownYet
}

internal fun membersExternalAgentSection(summary: AgentDetailSummary?): MembersExternalAgentSection =
    when {
        summary == null -> MembersExternalAgentSection.NotKnownYet
        summary.hostedRuntime != null -> MembersExternalAgentSection.Hosted
        else -> MembersExternalAgentSection.Setup
    }

internal enum class HostedRuntimeState { Provisioning, Active, Syncing, Failed, Deleting, Deleted }

internal data class HostedRuntimeView(
    /** Null for a state this version doesn't know: no chip rather than a wrong one. */
    val state: HostedRuntimeState?,
    val inProgress: Boolean,
    val canRetry: Boolean
)

/** Web `describeHostedRuntime`. */
internal fun describeHostedRuntime(summary: AgentHostedRuntimeSnapshot): HostedRuntimeView {
    val hasError = summary.lastErrorCode != null
    return when (summary.state) {
        "provisioning" -> HostedRuntimeView(HostedRuntimeState.Provisioning, inProgress = true, canRetry = hasError)
        "active" ->
            if (summary.syncPending) {
                HostedRuntimeView(HostedRuntimeState.Syncing, inProgress = true, canRetry = hasError)
            } else {
                HostedRuntimeView(HostedRuntimeState.Active, inProgress = false, canRetry = hasError)
            }
        "failed" -> HostedRuntimeView(HostedRuntimeState.Failed, inProgress = false, canRetry = true)
        "deleting" -> HostedRuntimeView(HostedRuntimeState.Deleting, inProgress = true, canRetry = false)
        "deleted" -> HostedRuntimeView(HostedRuntimeState.Deleted, inProgress = false, canRetry = false)
        else -> HostedRuntimeView(null, inProgress = false, canRetry = false)
    }
}

private val HostedRuntimeState.label: StringResource
    get() =
        when (this) {
            HostedRuntimeState.Provisioning -> Res.string.member_hosted_runtime_state_provisioning
            HostedRuntimeState.Active -> Res.string.member_hosted_runtime_state_active
            HostedRuntimeState.Syncing -> Res.string.member_hosted_runtime_state_syncing
            HostedRuntimeState.Failed -> Res.string.member_hosted_runtime_state_failed
            HostedRuntimeState.Deleting -> Res.string.member_hosted_runtime_state_deleting
            HostedRuntimeState.Deleted -> Res.string.member_hosted_runtime_state_deleted
        }

private val HostedRuntimeState.badgeVariant: RaftBadgeVariant
    get() =
        when (this) {
            HostedRuntimeState.Active -> RaftBadgeVariant.Success
            HostedRuntimeState.Failed -> RaftBadgeVariant.Warning
            HostedRuntimeState.Deleted -> RaftBadgeVariant.Muted
            HostedRuntimeState.Provisioning, HostedRuntimeState.Syncing, HostedRuntimeState.Deleting -> RaftBadgeVariant.Busy
        }

@Composable
internal fun hostedRuntimeStateLabel(summary: AgentHostedRuntimeSnapshot): String? =
    describeHostedRuntime(summary).state?.let { stringResource(it.label) }

/**
 * Web `HostedRuntimeStatus`, in place of the self-setup steps. One difference: the app has no model
 * picker here, so a retry after a refused model says it runs on the provider's default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MembersAgentHostedRuntimeSection(
    agentId: MemberId,
    initial: AgentHostedRuntimeSnapshot,
    agentsApiClient: RaftAgentsApiClient?,
    /** A paused personal assistant's runtime is up but runs nothing: Paused, not Running (web `paused`). */
    paused: Boolean = false,
    modifier: Modifier = Modifier
) {
    var summary by remember(agentId, initial) { mutableStateOf(initial) }
    var retrying by remember(agentId) { mutableStateOf(false) }
    var retryError by remember(agentId) { mutableStateOf<String?>(null) }
    val view = describeHostedRuntime(summary)
    val needsModel = view.canRetry && summary.retryNeedsModel
    val scope = rememberCoroutineScope()
    val retryFailed = stringResource(Res.string.member_hosted_runtime_retry_failed)
    suspend fun reload() {
        val status = agentsApiClient?.agentExternalStatus(agentId) as? DataResult.Content
        status?.data?.hostedRuntime?.let { summary = it }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(agentId, view.inProgress, agentsApiClient, lifecycleOwner) {
        if (!view.inProgress) return@LaunchedEffect
        // Nothing while the app is in the background (the shared refresh rule).
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(HOSTED_RUNTIME_POLL_MS)
                reload()
            }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().membersRaftCard().padding(RaftAppSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (paused && view.state == HostedRuntimeState.Active) {
                RaftBadge(
                    text = stringResource(Res.string.assistant_paused_chip),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Muted
                )
            } else {
                view.state?.let { state ->
                    RaftBadge(text = stringResource(state.label), appearance = RaftBadgeAppearance.Soft, variant = state.badgeVariant)
                }
            }
            RaftBadge(
                text = stringResource(Res.string.member_hosted_runtime_location),
                appearance = RaftBadgeAppearance.Soft,
                variant = RaftBadgeVariant.Information
            )
            RaftText(
                stringResource(Res.string.member_hosted_runtime_free),
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundMuted
            )
        }
        if (summary.state == "active" && summary.pushRegistered == false) {
            HostedRuntimeWarning(
                listOfNotNull(stringResource(Res.string.member_hosted_runtime_push_not_registered), summary.pushError)
                    .joinToString(" ")
            )
        }
        summary.lastErrorCode?.let { code ->
            HostedRuntimeWarning(
                stringResource(Res.string.member_hosted_runtime_last_error, code, summary.lastErrorMessage ?: code)
            )
        }
        if (view.canRetry) {
            RaftButton(
                text =
                    if (needsModel) {
                        stringResource(
                            Res.string.member_hosted_runtime_retry_with_model,
                            stringResource(Res.string.agent_create_hosted_model_default_unnamed)
                        )
                    } else {
                        stringResource(Res.string.member_hosted_runtime_retry)
                    },
                enabled = !retrying && agentsApiClient != null,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                onClick = {
                    val client = agentsApiClient ?: return@RaftButton
                    retrying = true
                    retryError = null
                    scope.launch {
                        when (val result = client.retryHostedRuntime(agentId, namesDefaultModel = needsModel)) {
                            is DataResult.Content -> reload()
                            is DataResult.Error -> retryError = createAgentFallbackErrorText(result.error, retryFailed)
                            else -> retryError = retryFailed
                        }
                        retrying = false
                    }
                }
            )
            retryError?.let { HostedRuntimeWarning(it) }
        }
    }
    if (summary.state == "active") {
        MembersAgentHostedRuntimeModel(agentId = agentId, agentsApiClient = agentsApiClient)
    }
}

/** What the hosted Model row says (web `AgentHostedRuntimeModel`). */
internal sealed interface HostedAgentModelLabel {
    /** A model is named: the pick, an administrator's, or the locked one. */
    data class Named(val text: String) : HostedAgentModelLabel

    /** Nothing picked: "Default ([defaultLabel])", or "Provider default" when the provider names none. */
    data class Default(val defaultLabel: String?) : HostedAgentModelLabel
}

/**
 * Web `AgentHostedRuntimeModel`, read only: null where the web shows no Model section (the server
 * may not pick, `picker: false`). Locked shows `label ?? model`; otherwise the combobox shows the
 * default choice for `source: "default"`, else the picked option's label (the raw id when the
 * list doesn't name it).
 */
internal fun hostedAgentModelLabel(view: HostedAgentModelView): HostedAgentModelLabel? {
    if (view.picker == false) return null
    val named = view.label ?: view.models.firstOrNull { it.id == view.model }?.label ?: view.model
    return when {
        view.locked -> named?.let(HostedAgentModelLabel::Named) ?: HostedAgentModelLabel.Default(view.defaultLabel)
        view.source == "default" || named == null -> HostedAgentModelLabel.Default(view.defaultLabel)
        else -> HostedAgentModelLabel.Named(named)
    }
}

/**
 * A running cloud agent's Model (web: `hostedRuntime.state === "active"`, managers only, as the
 * section above). Its runtime and model fields are the "external" placeholders, so the model is
 * read from the provider (`GET /agents/:id/hosted-runtime/model`). Read only: the app has no
 * picker. Hidden until it loads, and when the server answers anything else (web: 4xx hides it).
 */
@Composable
private fun MembersAgentHostedRuntimeModel(agentId: MemberId, agentsApiClient: RaftAgentsApiClient?) {
    var view by remember(agentId) { mutableStateOf<HostedAgentModelView?>(null) }
    LaunchedEffect(agentId, agentsApiClient) {
        view =
            try {
                (agentsApiClient?.agentHostedRuntimeModel(agentId) as? DataResult.Content)?.data
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // A transport without the endpoint (an older fake): no row, as on a failed load.
                null
            }
    }
    val label = view?.let(::hostedAgentModelLabel) ?: return
    val text =
        when (label) {
            is HostedAgentModelLabel.Named -> label.text
            is HostedAgentModelLabel.Default ->
                label.defaultLabel?.let { stringResource(Res.string.agent_create_hosted_model_default, it) }
                    ?: stringResource(Res.string.agent_create_hosted_model_default_unnamed)
        }
    MembersProfileSection(title = stringResource(Res.string.member_label_model)) {
        RaftText(text = text, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
        if (view?.locked == true) {
            RaftText(
                stringResource(Res.string.agent_create_hosted_model_locked),
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundHint
            )
        }
    }
}

@Composable
private fun HostedRuntimeWarning(text: String) {
    RaftText(text, style = RaftTheme.typography.footnote, color = RaftTheme.colors.warningStrong)
}
