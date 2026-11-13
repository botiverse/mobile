package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.AgentArchiveWorkspaceChoice
import build.raft.app.data.AgentCredentialRow
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalAgentSetupState
import build.raft.app.data.ExternalAgentStatusSnapshot
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpOutcome
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.MemberId
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.rememberRaftCopyAction
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// An external agent's setup (web CreateExternalAgentDialog / ExternalAgentConnectionCard,
// botiverse/slock #14534 #14627 #14666, #wg-external-agent-ux:#2). One agent credential serves both
// ways to connect: an existing agent signs in through the Raft CLI (the prompt), a program of your
// own uses the SDK. Switching only changes the instructions and the remembered method.

/** Wire `externalSetupMethod`: only which instructions the agent's page shows first. */
internal enum class ExternalSetupMethod(val wire: String) {
    ExistingAgent("existing_agent"),
    Sdk("sdk");

    val other: ExternalSetupMethod get() = if (this == Sdk) ExistingAgent else Sdk

    companion object {
        /** Null (made before the method existed, or an older server): an existing agent's CLI login. */
        fun fromWire(value: String?): ExternalSetupMethod = if (value == Sdk.wire) Sdk else ExistingAgent
    }
}

/** The SDK's published docs (its README on npm). */
internal const val RaftSdkDocsUrl = "https://www.npmjs.com/package/@botiverse/raft-sdk"
internal const val RaftSdkInstallCommand = "npm install @botiverse/raft-sdk"
internal const val RaftCliInstallCommand = "npm i -g @botiverse/raft@latest"

/** How often a not-yet-connected agent's status is asked again. */
internal const val ExternalAgentStatusPollMillis = 3_000L

/** The credential the create dialog and the agent's page make: Create new credential replaces only this one. */
internal const val ExternalAgentPageCredentialName = "Created on this page"

/** The server the CLI and the SDK sign in to; the shell's environment when the caller has none. */
internal fun externalAgentServerUrl(raw: String?): String {
    fun clean(value: String?) = value?.trim()?.trimEnd('/')?.removeSuffix("/api")?.takeIf { it.isNotBlank() }
    return clean(raw)
        ?: clean(RaftSharedStoreRegistry.currentOrNull()?.apiEnvironmentStore?.current()?.webUrl)
        ?: "<server-url>"
}

internal fun externalProfileSlug(name: String, agentId: String): String =
    name.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifBlank { agentId.take(8) }

internal fun externalAgentLoginCommand(serverUrl: String, agentId: String, name: String): String =
    "raft agent login --server $serverUrl --agent $agentId --profile-slug ${externalProfileSlug(name, agentId)}"

internal fun raftSdkStarterCode(serverUrl: String): String =
    listOf(
        "import { createRaftClient } from \"@botiverse/raft-sdk\";",
        "",
        "const raft = createRaftClient({",
        "  serverUrl: \"$serverUrl\",",
        "  credential: process.env.RAFT_AGENT_CREDENTIAL!,",
        "});",
        "await raft.agent.context(); // now connected",
    ).joinToString("\n")

/** The prompt an existing agent is given: install the CLI, sign in as this agent, ask for the credential. */
@Composable
internal fun rememberExternalAgentPrompt(agentId: String, name: String, serverUrl: String): String {
    val intro = stringResource(Res.string.external_agent_create_prompt_intro, name)
    val install = stringResource(Res.string.external_agent_create_prompt_install)
    val login = stringResource(Res.string.external_agent_create_prompt_login)
    val credential = stringResource(Res.string.external_agent_create_prompt_credential)
    return remember(intro, install, login, credential, agentId, name, serverUrl) {
        listOf(
            intro,
            "",
            install,
            RaftCliInstallCommand,
            "",
            login,
            externalAgentLoginCommand(serverUrl, agentId, name),
            "",
            credential,
        ).joinToString("\n")
    }
}

/**
 * What the agent's page says about an external agent (raft-design status.md): four states. Not signed
 * in yet covers every server state without a sign-in (no credential, one never used, an expired CLI
 * login token); once connected, presence says Online or Offline; disconnected means it was connected
 * and has no working credential left.
 */
internal enum class ExternalConnectionState { NotSignedIn, Online, Offline, Disconnected }

internal fun externalConnectionState(setupState: ExternalAgentSetupState, isOnline: Boolean): ExternalConnectionState =
    when (setupState) {
        ExternalAgentSetupState.Connected -> if (isOnline) ExternalConnectionState.Online else ExternalConnectionState.Offline
        ExternalAgentSetupState.Disconnected -> ExternalConnectionState.Disconnected
        else -> ExternalConnectionState.NotSignedIn
    }

/** The page's own credential among the active ones (web `useExternalAgentCredentials.pageCredential`). */
internal fun List<AgentCredentialRow>.externalPageCredential(): AgentCredentialRow? =
    firstOrNull { it.revokedAt == null && it.name == ExternalAgentPageCredentialName }

// ── Copy surfaces (web ExternalSetupCopy.tsx) ──

/** A value to copy, with its copy button at the field's end (raft-design button.md, "Beside a field"). */
@Composable
internal fun ExternalSetupCopyField(text: String, modifier: Modifier = Modifier) {
    val copy = rememberRaftCopyAction()
    var copied by remember(text) { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        RaftCodeBlock(text = text, modifier = Modifier.weight(1f))
        RaftIconButton(
            onClick = {
                copy(text)
                copied = true
            },
            tooltip = stringResource(Res.string.common_copy),
            variant = RaftButtonVariant.Default,
            size = RaftIconButtonSize.Lg,
        ) {
            ExternalSetupIcon(if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy)
        }
    }
}

/** A longer block (the prompt) with its own copy button under it; [collapsed] shows its start until opened. */
@Composable
internal fun ExternalSetupCopyBlock(text: String, label: String, primary: Boolean, collapsed: Boolean, modifier: Modifier = Modifier) {
    val copy = rememberRaftCopyAction()
    var copied by remember(text) { mutableStateOf(false) }
    var open by remember { mutableStateOf(!collapsed) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftCodeBlock(
            text = text,
            modifier = if (open) Modifier else Modifier.heightIn(max = 96.dp).clipToBounds(),
        )
        if (collapsed) {
            RaftTextLink(
                text = stringResource(if (open) Res.string.external_agent_create_hide_prompt else Res.string.external_agent_create_show_prompt),
                onClick = { open = !open },
                style = RaftTheme.typography.callout,
            )
        }
        Row {
            RaftButton(
                text = if (copied) stringResource(Res.string.common_copied) else label,
                onClick = {
                    copy(text)
                    copied = true
                },
                variant = if (primary) RaftButtonVariant.Primary else RaftButtonVariant.Default,
                leadingIcon = { ExternalSetupIcon(RaftLucideIconName.Copy) },
            )
        }
    }
}

@Composable
private fun ExternalSetupIcon(name: RaftLucideIconName) {
    RaftLucideIcon(name, Modifier.fillMaxSize(), color = raftArgbHex(LocalRaftContentColor.current))
}

@Composable
private fun ExternalSetupStepTitle(text: String) {
    RaftText(text, style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), color = RaftTheme.colors.foreground)
}

@Composable
private fun ExternalSetupNote(text: String, footnote: Boolean = false) {
    RaftText(
        text,
        style = if (footnote) RaftTheme.typography.footnote else RaftTheme.typography.callout,
        color = RaftTheme.colors.foregroundMuted,
    )
}

@Composable
private fun ExternalSetupAlert(text: String) {
    RaftText(
        text,
        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = RaftTheme.colors.warningStrong,
    )
}

/** The status line: a light, a title and a hint in a hairline box; [trailing] sits at its end. */
@Composable
private fun ExternalSetupStatusLine(
    presence: RaftPresence,
    title: String,
    hint: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(RaftRadius.Control)
    Row(
        Modifier
            .fillMaxWidth()
            .border(RaftStroke.Hairline, RaftTheme.colors.lineHairline, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftStatusDot(presence = presence, modifier = Modifier.padding(top = 6.dp))
        Column(Modifier.weight(1f)) {
            RaftText(title, style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), color = RaftTheme.colors.foreground)
            RaftText(hint, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted)
        }
        trailing?.invoke()
    }
}

@Composable
private fun rememberExternalLinkOpener(): (String) -> Unit {
    val navigator = LocalRaftLinkNavigator.current
    val uriHandler = LocalUriHandler.current
    return remember(navigator, uriHandler) {
        { url -> if (navigator != null) navigator.open(url) else uriHandler.openUri(url) }
    }
}

/**
 * The steps both surfaces show: for an existing agent the prompt, then [credentialStep]; for the SDK
 * [credentialStep], then install and the first lines. Without a [credentialStep] (connected, or a
 * reader who can't make one) the remaining step drops its number.
 */
@Composable
private fun ExternalSetupSteps(
    method: ExternalSetupMethod,
    agentId: String,
    name: String,
    serverUrl: String,
    onSwitch: (() -> Unit)?,
    credentialStep: (@Composable () -> Unit)?,
) {
    val openLink = rememberExternalLinkOpener()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        onSwitch?.let { switch ->
            RaftTextLink(
                text =
                    stringResource(
                        if (method == ExternalSetupMethod.Sdk) Res.string.external_agent_create_switch_to_existing
                        else Res.string.external_agent_create_switch_to_sdk
                    ),
                onClick = switch,
                style = RaftTheme.typography.callout,
            )
        }
        if (method == ExternalSetupMethod.ExistingAgent) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExternalSetupStepTitle(
                    stringResource(if (credentialStep != null) Res.string.external_agent_create_step1_existing else Res.string.external_agent_card_prompt_step)
                )
                ExternalSetupCopyBlock(
                    text = rememberExternalAgentPrompt(agentId, name, serverUrl),
                    label = stringResource(Res.string.external_agent_create_copy_prompt),
                    primary = true,
                    collapsed = true,
                )
            }
            credentialStep?.let { step ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExternalSetupStepTitle(stringResource(Res.string.external_agent_create_step2_existing))
                    step()
                }
            }
        } else {
            credentialStep?.let { step ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExternalSetupStepTitle(stringResource(Res.string.external_agent_create_step1_sdk))
                    step()
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExternalSetupStepTitle(
                    stringResource(if (credentialStep != null) Res.string.external_agent_create_step2_sdk else Res.string.external_agent_card_sdk_step)
                )
                ExternalSetupCopyField(RaftSdkInstallCommand)
                ExternalSetupCopyField(raftSdkStarterCode(serverUrl))
                ExternalSetupNote(stringResource(Res.string.external_agent_create_sdk_connects_hint, name))
                RaftTextLink(
                    text = stringResource(Res.string.external_agent_create_sdk_guide),
                    onClick = { openLink(RaftSdkDocsUrl) },
                    style = RaftTheme.typography.callout,
                )
            }
        }
    }
}

/** A just-made credential: shown once, with the method's note under it. */
@Composable
private fun ExternalSetupNewCredential(secret: String, name: String, method: ExternalSetupMethod?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExternalSetupNote(stringResource(Res.string.external_agent_create_credential_warning, name))
        ExternalSetupCopyField(secret)
        method?.let {
            ExternalSetupNote(
                stringResource(if (it == ExternalSetupMethod.Sdk) Res.string.external_agent_create_env_hint else Res.string.external_agent_create_shown_once),
                footnote = true,
            )
        }
    }
}

/** "Verify it's you" for a credential mint refused for a fresh second step; null without a network client. */
@Composable
private fun rememberExternalAgentStepUp(agentsApiClient: RaftAgentsApiClient): SecondFactorStepUpCoordinator? {
    val coordinator =
        remember(agentsApiClient) {
            agentsApiClient.secondFactorStepUpRepository()?.let { SecondFactorStepUpCoordinator(stepUp = it::stepUp) }
        }
    if (coordinator != null) {
        val scope = rememberCoroutineScope()
        val state by coordinator.state.collectAsState()
        state?.let { dialog ->
            SecondFactorStepUpDialog(state = dialog, onIntent = { intent -> scope.launch { coordinator.accept(intent) } })
        }
    }
    return coordinator
}

/** A credential mint through the step-up when one is asked; null when "Verify it's you" was closed. */
private suspend fun <T> SecondFactorStepUpCoordinator?.runOrDirect(request: suspend () -> DataResult<T>): DataResult<T>? =
    if (this == null) {
        request()
    } else {
        when (val outcome = run(request)) {
            is SecondFactorStepUpOutcome.Completed -> outcome.result
            SecondFactorStepUpOutcome.Cancelled -> null
        }
    }

// ── Create (web CreateExternalAgentDialog) ──

/** What the Members page gives the external agent's create dialog. */
internal class RaftExternalAgentCreateHost(
    val agentsApiClient: RaftAgentsApiClient,
    /** The server the CLI and the SDK sign in to (see [externalAgentServerUrl]). */
    val serverUrl: String?,
    /** Creates the agent (an external one) without landing on it: the dialog stays on its setup. */
    val create: suspend (SharedCreateAgentDialogSubmit) -> DataResult<MemberProfile>,
    /** Done: open the agent's page. */
    val onFinished: (MemberProfile) -> Unit,
    /** The agent was discarded (Cancel, or a failure part-way): drop its stand-in row. */
    val onDiscarded: (MemberProfile) -> Unit,
)

/**
 * Create an external agent in one dialog (web `CreateExternalAgentDialog`, botiverse/slock #14627).
 * Step 1: name, description and how it will run. Next makes the agent, records the method and makes
 * its one agent credential; a failure part-way discards what was made. Step 2 shows how to connect it
 * while it waits for the first sign-in (polled); Cancel before it connects asks, then discards the
 * agent; Done opens its page.
 */
@Composable
internal fun RaftCreateExternalAgentDialog(host: RaftExternalAgentCreateHost, onClose: () -> Unit) {
    val client = host.agentsApiClient
    val scope = rememberCoroutineScope()
    val stepUp = rememberExternalAgentStepUp(client)
    val createFailed = stringResource(Res.string.external_agent_create_failed)
    val discardFailed = stringResource(Res.string.external_agent_create_discard_failed)
    val serverUrl = remember(host.serverUrl) { externalAgentServerUrl(host.serverUrl) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(ExternalSetupMethod.ExistingAgent) }
    var created by remember { mutableStateOf<MemberProfile?>(null) }
    var credential by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Step 2 asks until the agent signs in (any of its credentials used).
    val waitingFor = created
    LaunchedEffect(waitingFor, connected) {
        if (waitingFor == null || connected) return@LaunchedEffect
        while (true) {
            val status = client.agentExternalStatus(waitingFor.id)
            if (status is DataResult.Content && status.data.setupState == ExternalAgentSetupState.Connected) {
                connected = true
                break
            }
            // A missed poll is retried on the next tick.
            delay(ExternalAgentStatusPollMillis)
        }
    }

    fun next() {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            var agent: MemberProfile? = null
            var failure: String? = createFailed
            try {
                val made =
                    host.create(
                        SharedCreateAgentDialogSubmit(
                            name = trimmed,
                            description = description.trim().ifBlank { null },
                            machineId = null,
                            external = true,
                        )
                    )
                if (made !is DataResult.Content) {
                    failure = (made as? DataResult.Error)?.error?.serverMessage?.takeIf { it.isNotBlank() } ?: createFailed
                    return@launch
                }
                val profile = made.data
                agent = profile
                if (client.setExternalSetupMethod(profile.id, method.wire) !is DataResult.Content) return@launch
                // Two-step verification on: "Verify it's you" first, then the same request once more.
                val minted = stepUp.runOrDirect { client.createAgentCredential(profile.id, ExternalAgentPageCredentialName) }
                if (minted == null) failure = null
                if (minted is DataResult.Content) {
                    created = profile
                    credential = minted.data.apiKey
                    failure = null
                    agent = null
                }
            } finally {
                // Nothing is left behind: an agent made before the failure is discarded.
                agent?.let { leftover ->
                    client.deleteAgent(leftover.id, AgentArchiveWorkspaceChoice.Keep.toRequestBody())
                    host.onDiscarded(leftover)
                }
                if (failure != null) error = failure
                busy = false
            }
        }
    }

    fun finish() {
        created?.let(host.onFinished)
        onClose()
    }

    fun discard() {
        val agent = created ?: return
        if (busy) return
        busy = true
        scope.launch {
            val result = client.deleteAgent(agent.id, AgentArchiveWorkspaceChoice.Keep.toRequestBody())
            busy = false
            if (result is DataResult.Content) {
                host.onDiscarded(agent)
                onClose()
            } else {
                error = discardFailed
                confirmDiscard = false
            }
        }
    }

    val agent = created
    if (agent == null) {
        RaftUnifiedDialog(
            title = stringResource(Res.string.external_agent_create_title),
            onDismiss = { if (!busy) onClose() },
            dismissEnabled = !busy,
            dismissOnOutsideTap = false,
            keyboardAvoidanceEnabled = true,
            hostRoute = SharedCreateAgentDialogHostRoute,
            presentation = RaftDialogPresentation.Sheet,
            commitLabel = stringResource(Res.string.external_agent_create_next),
            onCommit = ::next,
            commitEnabled = name.isNotBlank() && !busy,
            commitLoading = busy,
            modifier = Modifier.testTag(CreateExternalAgentDialogTestTag),
        ) {
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                error?.let { RaftText(it, style = RaftTheme.typography.callout, color = RaftTheme.colors.dangerStrong) }
                RaftField(label = stringResource(Res.string.external_agent_create_name), modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                    RaftTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth())
                }
                RaftField(
                    label = stringResource(Res.string.external_agent_create_description),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    optional = true,
                ) {
                    RaftTextarea(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(Res.string.external_agent_create_description_placeholder),
                        minHeight = 82.dp,
                        maxHeight = 92.dp,
                    )
                }
                RaftField(label = stringResource(Res.string.external_agent_create_how_it_runs), modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SegmentedControl(
                            selectedValue = method.wire,
                            options =
                                listOf(
                                    SegmentedControlOption(ExternalSetupMethod.ExistingAgent.wire, stringResource(Res.string.external_agent_create_existing), RaftLucideIconName.Bot),
                                    SegmentedControlOption(ExternalSetupMethod.Sdk.wire, stringResource(Res.string.external_agent_create_sdk), RaftLucideIconName.Code),
                                ),
                            onValueSelected = { method = ExternalSetupMethod.fromWire(it) },
                            enabled = !busy,
                            fillWidth = true,
                        )
                        ExternalSetupNote(
                            stringResource(if (method == ExternalSetupMethod.Sdk) Res.string.external_agent_create_sdk_hint else Res.string.external_agent_create_existing_hint)
                        )
                    }
                }
            }
            RaftDialogFooter {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onClose,
                    enabled = !busy,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                )
                RaftButton(
                    text = stringResource(Res.string.external_agent_create_next),
                    onClick = ::next,
                    enabled = name.isNotBlank(),
                    loading = busy,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                )
            }
        }
        return
    }

    val agentName = agent.displayName
    if (confirmDiscard) {
        RaftDesignSystemTheme {
            RaftDialog(
                onDismissRequest = { if (!busy) confirmDiscard = false },
                title = stringResource(Res.string.external_agent_create_discard_title, agentName),
                description = stringResource(Res.string.external_agent_create_discard_body),
                size = RaftDialogSize.Sm,
                dismissOnClickOutside = false,
                closeLabel = stringResource(Res.string.common_close),
                footer = {
                    RaftButton(
                        text = stringResource(Res.string.external_agent_create_keep),
                        onClick = { confirmDiscard = false },
                        enabled = !busy,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg,
                    )
                    RaftButton(
                        text = stringResource(Res.string.external_agent_create_discard),
                        onClick = ::discard,
                        loading = busy,
                        variant = RaftButtonVariant.Danger,
                        size = RaftButtonSize.Lg,
                    )
                },
            )
        }
        return
    }

    // Once made, closing before it connects asks first; connected, closing is Done.
    fun close() {
        if (connected) finish() else confirmDiscard = true
    }
    RaftUnifiedDialog(
        title = stringResource(Res.string.external_agent_create_setup_title, agentName),
        onDismiss = ::close,
        dismissEnabled = !busy,
        dismissOnOutsideTap = false,
        hostRoute = SharedCreateAgentDialogHostRoute,
        presentation = RaftDialogPresentation.Sheet,
        maxWidth = 672.dp,
        commitLabel = stringResource(Res.string.common_done),
        onCommit = ::finish,
        commitVariant = if (connected) RaftButtonVariant.Primary else RaftButtonVariant.Default,
        modifier = Modifier.testTag(CreateExternalAgentSetupTestTag),
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            error?.let { RaftText(it, style = RaftTheme.typography.callout, color = RaftTheme.colors.dangerStrong) }
            // Waiting needs someone to act: orange, as every "needs action" state (raft-design status.md).
            ExternalSetupStatusLine(
                presence = if (connected) RaftPresence.Online else RaftPresence.Error,
                title = stringResource(if (connected) Res.string.external_agent_create_online else Res.string.external_agent_create_waiting, agentName),
                hint = stringResource(if (connected) Res.string.external_agent_create_online_hint else Res.string.external_agent_create_waiting_hint, agentName),
            )
            if (!connected) {
                ExternalSetupSteps(
                    method = method,
                    agentId = agent.id.value,
                    name = agentName,
                    serverUrl = serverUrl,
                    onSwitch = {
                        val nextMethod = method.other
                        method = nextMethod
                        // The same credential works both ways: only the instructions and the remembered method change.
                        scope.launch { client.setExternalSetupMethod(agent.id, nextMethod.wire) }
                    },
                    credentialStep = { ExternalSetupNewCredential(credential.orEmpty(), agentName, method) },
                )
            }
        }
        RaftDialogFooter {
            if (!connected) {
                RaftText(
                    stringResource(Res.string.external_agent_create_finish_later),
                    modifier = Modifier.weight(1f),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                )
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = { confirmDiscard = true },
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                )
            }
            RaftButton(
                text = stringResource(Res.string.common_done),
                onClick = ::finish,
                variant = if (connected) RaftButtonVariant.Primary else RaftButtonVariant.Default,
                size = RaftButtonSize.Lg,
            )
        }
    }
}

internal const val CreateExternalAgentDialogTestTag = "create-external-agent-dialog"
internal const val CreateExternalAgentSetupTestTag = "create-external-agent-setup"

// ── The agent's page (web ExternalAgentConnectionCard + ExternalAgentCredentialList) ──

private sealed interface ExternalCredentialConfirm {
    val credentialId: String

    data class Replace(override val credentialId: String) : ExternalCredentialConfirm

    data class Revoke(override val credentialId: String) : ExternalCredentialConfirm
}

private enum class ExternalCredentialError { Create, Revoke }

/**
 * An external agent's "External setup" on its page (web `ExternalAgentConnectionCard`, botiverse/slock
 * #14666). One status line in four states: not signed in yet (orange: someone must act), Online,
 * Offline, disconnected (orange). Until it has signed in, or once disconnected, the steps the create
 * dialog showed are open below it with the switch link; once connected they fold behind "How it
 * connects". Credentials sit beside it: the agent's creator or a server owner/admin (who may issue
 * credentials, and the only ones who see this card) may make or revoke one.
 */
@Composable
internal fun MembersExternalAgentConnectionCard(
    agentId: MemberId,
    name: String,
    /** The agent's @name: the CLI profile slug is made from it (web `agent.name`). */
    handle: String,
    initialStatus: ExternalAgentStatusSnapshot?,
    lastSeenAt: String?,
    serverUrl: String?,
    agentsApiClient: RaftAgentsApiClient,
    timeContext: RaftTimeContext,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val stepUp = rememberExternalAgentStepUp(agentsApiClient)
    val cleanServerUrl = remember(serverUrl) { externalAgentServerUrl(serverUrl) }
    var status by remember(agentId) { mutableStateOf(initialStatus) }
    LaunchedEffect(agentId, initialStatus) {
        if (initialStatus != null) {
            status = initialStatus
        } else if (status == null) {
            (agentsApiClient.agentExternalStatus(agentId) as? DataResult.Content)?.let { status = it.data }
        }
    }
    val current = status ?: return
    val now = timeContext.nowEpochMillis ?: remember(current, lastSeenAt) { defaultNowMillis() }
    val isOnline = isExternalAgentSeenOnline(lastSeenAt, now)
    val state = externalConnectionState(current.setupState, isOnline)
    val connected = state == ExternalConnectionState.Online || state == ExternalConnectionState.Offline
    val method = ExternalSetupMethod.fromWire(current.externalSetupMethod)
    var expanded by remember(agentId) { mutableStateOf(false) }

    // Credentials (web useExternalAgentCredentials): the secret of a new one lives only here.
    var credentials by remember(agentId) { mutableStateOf<List<AgentCredentialRow>>(emptyList()) }
    var credentialsLoading by remember(agentId) { mutableStateOf(true) }
    var credentialsListError by remember(agentId) { mutableStateOf(false) }
    var credentialsRevision by remember(agentId) { mutableStateOf(0) }
    var secret by remember(agentId) { mutableStateOf<String?>(null) }
    var credentialBusy by remember(agentId) { mutableStateOf(false) }
    var credentialError by remember(agentId) { mutableStateOf<ExternalCredentialError?>(null) }
    var confirm by remember(agentId) { mutableStateOf<ExternalCredentialConfirm?>(null) }
    LaunchedEffect(agentId, credentialsRevision) {
        when (val result = agentsApiClient.agentCredentials(agentId)) {
            is DataResult.Content -> {
                credentials = result.data
                credentialsListError = false
            }
            else -> {
                credentials = emptyList()
                credentialsListError = true
            }
        }
        credentialsLoading = false
    }
    val active = credentials.filter { it.revokedAt == null }
    val pageCredentialId = credentials.externalPageCredential()?.id

    suspend fun refreshStatus() {
        (agentsApiClient.agentExternalStatus(agentId) as? DataResult.Content)?.let { status = it.data }
    }

    // Until it signs in, the line updates by itself (the create dialog's promise, kept here).
    LaunchedEffect(agentId, connected) {
        if (connected) return@LaunchedEffect
        while (true) {
            delay(ExternalAgentStatusPollMillis)
            refreshStatus()
        }
    }

    fun createCredential(replacesCredentialId: String?) {
        if (credentialBusy) return
        credentialBusy = true
        credentialError = null
        scope.launch {
            val minted =
                stepUp.runOrDirect { agentsApiClient.createAgentCredential(agentId, ExternalAgentPageCredentialName, replacesCredentialId) }
            when (minted) {
                is DataResult.Content -> {
                    secret = minted.data.apiKey
                    credentialsRevision += 1
                    refreshStatus()
                }
                // Closed "Verify it's you": nothing was created, nothing to say.
                null -> Unit
                else -> credentialError = ExternalCredentialError.Create
            }
            credentialBusy = false
        }
    }

    fun revokeCredential(credentialId: String) {
        if (credentialBusy) return
        credentialBusy = true
        credentialError = null
        scope.launch {
            if (agentsApiClient.revokeAgentCredential(agentId, credentialId) is DataResult.Content) {
                credentials = credentials.map { if (it.id == credentialId) it.copy(revokedAt = "revoked") else it }
                refreshStatus()
            } else {
                // An ambiguous failure is not a revocation: the row stays.
                credentialError = ExternalCredentialError.Revoke
            }
            credentialBusy = false
        }
    }

    val credentialStep: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val made = secret
            if (made != null) {
                ExternalSetupNewCredential(made, name, method)
            } else {
                Row {
                    RaftButton(
                        text =
                            stringResource(
                                if (pageCredentialId != null) Res.string.external_agent_card_create_new_credential
                                else Res.string.external_agent_card_create_credential
                            ),
                        onClick = {
                            // A new page credential replaces the page's earlier one, so it asks first; a first one doesn't.
                            if (!credentialsLoading) {
                                if (pageCredentialId != null) confirm = ExternalCredentialConfirm.Replace(pageCredentialId)
                                else createCredential(null)
                            }
                        },
                        enabled = !credentialsLoading,
                        loading = credentialBusy,
                        leadingIcon = { ExternalSetupIcon(RaftLucideIconName.Plus) },
                    )
                }
                if (pageCredentialId != null) ExternalSetupNote(stringResource(Res.string.external_agent_card_shown_before, name), footnote = true)
            }
            if (credentialError == ExternalCredentialError.Create) ExternalSetupAlert(stringResource(Res.string.external_agent_card_create_error))
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftSectionEyebrow(text = stringResource(Res.string.external_agent_card_title))
            val lastSeen = lastSeenAt ?: current.lastActivityAt ?: current.credentialLastUsedAt
            val lastSeenLabel = raftRelativeTimestampLabel(lastSeen, timeContext)
            val lastActiveLabel = raftRelativeTimestampLabel(current.lastActivityAt ?: current.credentialLastUsedAt, timeContext)
            val (title, hint) =
                when (state) {
                    ExternalConnectionState.Online ->
                        stringResource(Res.string.external_agent_card_online) to
                            if (lastActiveLabel.isNotBlank()) stringResource(Res.string.external_agent_card_online_active, lastActiveLabel)
                            else stringResource(Res.string.external_agent_card_online_hint)
                    ExternalConnectionState.Offline ->
                        (
                            if (lastSeenLabel.isNotBlank()) stringResource(Res.string.external_agent_card_offline_last_seen, lastSeenLabel)
                            else stringResource(Res.string.external_agent_card_offline)
                        ) to stringResource(Res.string.external_agent_card_offline_hint)
                    ExternalConnectionState.Disconnected ->
                        stringResource(Res.string.external_agent_card_disconnected, name) to
                            stringResource(Res.string.external_agent_card_disconnected_hint, name)
                    ExternalConnectionState.NotSignedIn ->
                        stringResource(Res.string.external_agent_create_waiting, name) to
                            if (current.setupState == ExternalAgentSetupState.CredentialMinted) {
                                stringResource(Res.string.external_agent_create_waiting_hint, name)
                            } else {
                                stringResource(Res.string.external_agent_card_no_credential_hint, name)
                            }
                }
            // Not signed in and disconnected need someone to act: orange (status.md "needs attention").
            ExternalSetupStatusLine(
                presence =
                    when (state) {
                        ExternalConnectionState.Online -> RaftPresence.Online
                        ExternalConnectionState.Offline -> RaftPresence.Offline
                        else -> RaftPresence.Error
                    },
                title = title,
                hint = hint,
                trailing =
                    if (connected) {
                        {
                            RaftButton(
                                text = stringResource(Res.string.external_agent_card_how_it_connects),
                                onClick = { expanded = !expanded },
                                variant = RaftButtonVariant.Ghost,
                                trailingIcon = { ExternalSetupIcon(if (expanded) RaftLucideIconName.ChevronUp else RaftLucideIconName.ChevronDown) },
                            )
                        }
                    } else {
                        null
                    },
            )
            if (!connected || expanded) {
                ExternalSetupSteps(
                    method = method,
                    agentId = agentId.value,
                    name = handle,
                    serverUrl = cleanServerUrl,
                    // Switching how it connects is offered only while it isn't connected.
                    onSwitch =
                        if (connected) {
                            null
                        } else {
                            {
                                val previous = current
                                status = previous.copy(externalSetupMethod = method.other.wire)
                                scope.launch {
                                    if (agentsApiClient.setExternalSetupMethod(agentId, method.other.wire) !is DataResult.Content) status = previous
                                }
                            }
                        },
                    credentialStep = if (connected) null else credentialStep,
                )
            }
        }
        ExternalAgentCredentialList(
            credentials = active,
            loading = credentialsLoading,
            listError = credentialsListError,
            enabled = !credentialBusy,
            timeContext = timeContext,
            onReplace = { confirm = ExternalCredentialConfirm.Replace(it) },
            onRevoke = { confirm = ExternalCredentialConfirm.Revoke(it) },
        )
        if (credentialError == ExternalCredentialError.Revoke) ExternalSetupAlert(stringResource(Res.string.external_agent_card_revoke_error))
        if (credentialError == ExternalCredentialError.Create && connected) ExternalSetupAlert(stringResource(Res.string.external_agent_card_create_error))
        val made = secret
        if (made != null && connected) ExternalSetupNewCredential(made, name, method = null)
    }

    confirm?.let { pending ->
        val replace = pending is ExternalCredentialConfirm.Replace
        RaftDesignSystemTheme {
            RaftDialog(
                onDismissRequest = { confirm = null },
                title = stringResource(if (replace) Res.string.external_agent_card_replace_title else Res.string.external_agent_card_revoke_title),
                description =
                    stringResource(if (replace) Res.string.external_agent_card_replace_message else Res.string.external_agent_card_revoke_message, name),
                size = RaftDialogSize.Sm,
                dismissOnClickOutside = false,
                closeLabel = stringResource(Res.string.common_close),
                footer = {
                    RaftButton(
                        text = stringResource(Res.string.common_cancel),
                        onClick = { confirm = null },
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg,
                    )
                    RaftButton(
                        text = stringResource(if (replace) Res.string.external_agent_card_create_new_credential else Res.string.external_agent_card_revoke),
                        onClick = {
                            confirm = null
                            if (replace) createCredential(pending.credentialId) else revokeCredential(pending.credentialId)
                        },
                        variant = if (replace) RaftButtonVariant.Warning else RaftButtonVariant.Danger,
                        size = RaftButtonSize.Lg,
                    )
                },
            )
        }
    }
}

/** Web `ExternalAgentCredentialList`: each active credential by where it came from and when it was last used. */
@Composable
private fun ExternalAgentCredentialList(
    credentials: List<AgentCredentialRow>,
    loading: Boolean,
    listError: Boolean,
    enabled: Boolean,
    timeContext: RaftTimeContext,
    onReplace: (String) -> Unit,
    onRevoke: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftSectionEyebrow(text = stringResource(Res.string.external_agent_card_credentials))
        when {
            loading ->
                RaftText(stringResource(Res.string.external_agent_card_credentials_loading), style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundHint)
            listError -> ExternalSetupAlert(stringResource(Res.string.external_agent_card_credentials_error))
            credentials.isEmpty() ->
                RaftText(stringResource(Res.string.external_agent_card_credentials_empty), style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundHint)
            else ->
                Column(Modifier.fillMaxWidth().membersRaftCard()) {
                    credentials.forEachIndexed { index, credential ->
                        if (index > 0) {
                            Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(RaftTheme.colors.lineHairline))
                        }
                        ExternalAgentCredentialItem(credential, enabled, timeContext, onReplace, onRevoke)
                    }
                }
        }
    }
}

@Composable
private fun ExternalAgentCredentialItem(
    credential: AgentCredentialRow,
    enabled: Boolean,
    timeContext: RaftTimeContext,
    onReplace: (String) -> Unit,
    onRevoke: (String) -> Unit,
) {
    val fromPage = credential.name == ExternalAgentPageCredentialName
    val title =
        if (fromPage) stringResource(Res.string.external_agent_card_page_credential)
        else credential.name ?: stringResource(Res.string.external_agent_card_unnamed_credential)
    val createdLabel = timeContext.formatTimestampLabel(credential.createdAt).ifBlank { credential.createdAt }
    val used =
        credential.lastUsedAt?.let { lastUsed ->
            stringResource(Res.string.external_agent_card_credential_used, createdLabel, raftRelativeTimestampLabel(lastUsed, timeContext))
        } ?: stringResource(Res.string.external_agent_card_credential_not_used, createdLabel)
    var menuOpen by remember(credential.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RaftText(title, style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold), color = RaftTheme.colors.foreground)
            RaftText(used, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
        }
        Box {
            RaftIconButton(
                onClick = { menuOpen = true },
                tooltip = stringResource(Res.string.external_agent_card_credential_actions, title),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Lg,
                enabled = enabled,
            ) {
                ExternalSetupIcon(RaftLucideIconName.EllipsisVertical)
            }
            RaftDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, alignEnd = true) {
                if (fromPage) {
                    RaftMenuItem(
                        text = stringResource(Res.string.external_agent_card_create_new_credential),
                        onClick = {
                            menuOpen = false
                            onReplace(credential.id)
                        },
                        leadingIcon = { ExternalSetupIcon(RaftLucideIconName.Plus) },
                    )
                }
                RaftMenuItem(
                    text = stringResource(Res.string.external_agent_card_revoke),
                    onClick = {
                        menuOpen = false
                        onRevoke(credential.id)
                    },
                    leadingIcon = { ExternalSetupIcon(RaftLucideIconName.Ban) },
                )
            }
        }
    }
}
