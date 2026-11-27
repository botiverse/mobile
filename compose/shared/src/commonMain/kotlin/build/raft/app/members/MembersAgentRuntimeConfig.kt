package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.label
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import build.raft.app.components.raftCardSurface
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDescriptionListScope
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentResetMode
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.ExistingAgentRuntimeOptions
import build.raft.app.data.raftRuntimeModelSupportsFastMode
import build.raft.app.data.runtimeDeprecatedForAgent
import build.raft.app.components.RaftUiBanner
import build.raft.app.components.RaftUiBannerSize
import build.raft.app.components.RaftUiBannerStatus
import build.raft.app.data.CREATE_AGENT_BUILTIN_RUNTIME_ID
import build.raft.app.data.CodexNonFastServiceTiers
import build.raft.app.data.normalizeAgentServiceTier
import build.raft.app.data.normalizedAgentRuntimeStatus
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.toAgentDetailSummary
import build.raft.app.label
import build.raft.app.model.AgentStatus
import build.raft.app.model.RUNTIME_MODELS
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.MemberId
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.supportsRuntimeFastMode
import build.raft.app.model.RaftTimeContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.designsystem.components.raftCopyable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentConfigApply
import build.raft.app.data.runtimeCatalogIsDeprecated
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import build.raft.app.model.isExternalAgentRuntime
import androidx.compose.ui.semantics.selected

/**
 * The Computer / Computer status / Computer Version / Created / Creator rows of the agent
 * profile's INFO section (web AgentProfileInfo InfoRows), split out so a golden can cover them.
 * The computer's status and version are rows of their own, so a long (wrapping) computer name
 * never has the status wedged into it.
 */
@Composable
internal fun RaftDescriptionListScope.MembersAgentComputerInfoRows(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    machineStatusRow: (machineId: String?) -> MembersMachineStatusRow,
    onOpenComputer: (() -> Unit)?,
    timeContext: RaftTimeContext,
    memberAvatarResolver: RaftMemberAvatarResolver,
    onOpenManagedApp: (() -> Unit)? = null,
) {
    val appBotIdentity =
        member.appBotIdentityOrNull()
            ?: summary?.managedByAppInstallId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                RaftAppBotIdentity(
                    handle = member.handle,
                    appName = summary.managedByAppName?.trim()?.takeIf { name -> name.isNotEmpty() },
                    removed = !summary.deletedAt.isNullOrBlank() || member.inMemberLists == false,
                    appClientId = summary.managedByAppClientId?.trim()?.takeIf { id -> id.isNotEmpty() },
                    appLogoUrl = summary.managedByAppLogoUrl?.trim()?.takeIf { url -> url.isNotEmpty() },
                )
            }
    if (appBotIdentity == null) {
        // Truth source is the computers store, not `machineId != null` (task #259).
        val statusRow = machineStatusRow(summary?.machineId)
        val machineName = summary?.machineName?.takeIf { it.isNotBlank() } ?: member.membersComputerName
        MembersAgentInfoField(label = stringResource(Res.string.member_label_computer)) {
            when (statusRow) {
                // artin: 甲 -- until the store has answered, the row has no value (web
                // `agentMachineRow.kind === "pending" ? null`), so it cannot flash
                // "No computer assigned" before flipping to the machine.
                MembersMachineStatusRow.Loading -> Unit
                // Web `text-foreground-hint` on the callout.
                MembersMachineStatusRow.NoComputer ->
                    RaftText(
                        text = stringResource(Res.string.member_machine_no_computer_assigned),
                        style = membersInfoValueStyle(),
                        color = RaftTheme.colors.foregroundHint
                    )
                is MembersMachineStatusRow.Machine -> {
                    // Web: a `font-mono text-foreground` button that opens the computer, underlined on
                    // hover. Android has no hover, so the underline shows whenever it is tappable.
                    val machineNameStyle =
                        membersInfoValueStyle(mono = true).let { base ->
                            if (onOpenComputer != null) base.copy(textDecoration = TextDecoration.Underline) else base
                        }
                    RaftText(
                        text = machineName.orDash(),
                        style = machineNameStyle,
                        color = RaftTheme.colors.foreground,
                        // No maxLines/ellipsis on purpose: truncating a machine name hides exactly
                        // the part that distinguishes two similar hosts; it wraps instead.
                        softWrap = true,
                        // Long press copies the name; a tap still opens the computer.
                        modifier = Modifier
                            .raftCopyable(machineName?.takeIf { it.isNotBlank() }, onClick = onOpenComputer)
                            .fillMaxWidth()
                    )
                }
            }
        }
        if (statusRow is MembersMachineStatusRow.Machine) {
            // web `agent-computer-connection`: "Computer status", not "Status" -- the hero already
            // shows the agent's own activity. <StatusDot> (Status md, 10px) then the word.
            MembersAgentInfoField(label = stringResource(Res.string.member_label_computer_status)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MembersInfoStatusGapX),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RaftStatusDot(
                        presence = if (statusRow.online) RaftPresence.Online else RaftPresence.Offline,
                        size = RaftStatusSize.Md
                    )
                    RaftText(
                        text = stringResource(
                            if (statusRow.online) Res.string.member_label_connected else Res.string.member_label_offline
                        ),
                        style = membersInfoValueStyle(),
                        color = RaftTheme.colors.foreground
                    )
                }
            }
            // web `agent-computer-version` (#14585): the version only, "v1.0.53"; "—" while the
            // Computer is offline or reports none. Whether it is online is the row above.
            val runLabel = membersAgentComputerVersionText(statusRow.runLabel)
            MembersAgentInfoField(
                label = stringResource(Res.string.settings_computers_label_version),
                copyText = runLabel
            ) {
                RaftText(
                    text = runLabel,
                    style = membersInfoValueStyle(mono = true),
                    color = RaftTheme.colors.foreground
                )
            }
        }
    }
    summary?.createdAt.membersLocalizedCreatedDateOrNull(timeContext)?.let { createdDate ->
        MembersAgentInfoField(label = stringResource(Res.string.member_label_created), copyText = createdDate) {
            // Web `<span className="font-mono">` on the callout.
            RaftText(
                text = createdDate,
                style = membersInfoValueStyle(mono = true),
                color = RaftTheme.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (appBotIdentity != null) {
        // Web AgentProfileInfo (botiverse/slock #14286): an App bot's owning App IS its identity
        // (logo + App badge + name, linking to App details when eligible), followed by any
        // app-published status line.
        val botStatusText =
            (summary?.managedByAppBotStatusText ?: member.managedByAppBotStatusText)?.trim()?.takeIf { it.isNotEmpty() }
        MembersAgentInfoField(label = stringResource(Res.string.profile_preview_managed_by_app)) {
            RaftManagedByAppRowValue(
                identity = appBotIdentity,
                onOpenManagedApp = onOpenManagedApp,
            )
            if (botStatusText != null) {
                RaftText(
                    text = botStatusText,
                    style = RaftTheme.typography.caption,
                    color = RaftTheme.colors.foreground,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }
    // Creator value stays a clickable identity chip (web opens the profile).
    val creatorProfile = summary?.creator?.toStrictMemberProfile()
    MembersAgentInfoField(label = stringResource(Res.string.member_label_creator), copyText = creatorProfile?.displayName) {
        if (creatorProfile != null) {
            // Web creator button: `flex items-center gap-2`, the name on the callout at
            // 400, the @handle `font-mono text-mono-meta text-foreground-hint`.
            Row(
                horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MembersRaftAvatar(
                    member = creatorProfile,
                    size = RaftAvatarSize.Xs,
                    memberAvatarResolver = memberAvatarResolver
                )
                RaftText(
                    text = creatorProfile.displayName,
                    style = membersInfoValueStyle(),
                    color = RaftTheme.colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill = false so the name shrinks to its content and the handle beside it
                    // is not pushed out.
                    modifier = Modifier.weight(1f, fill = false)
                )
                RaftText(
                    text = creatorProfile.detailHandle,
                    style = RaftTheme.typography.monoMeta,
                    color = RaftTheme.colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            RaftText(
                text = stringResource(Res.string.member_state_no_creator_assigned),
                style = membersInfoValueStyle(),
                color = RaftTheme.colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Web AgentProfileInfo `!isExternalAgent` (`agent.external === true || isExternalAgentRuntime(runtime)`):
 * whether the profile shows the Runtime config section. An external agent, a cloud (hosted) one
 * included, has none: its stored runtime and model are both the literal "external".
 */
internal fun membersAgentProfileShowsRuntimeConfig(member: MemberProfile, summary: AgentDetailSummary?): Boolean =
    !(member.isExternal || summary?.isExternal == true || isExternalAgentRuntime(summary?.runtime))

/**
 * RUNTIME CONFIG section of the agent detail tab: read-only rows plus the shared runtime-config
 * editor dialog and its save flow (task #1130 part 2, moved verbatim out of [MembersAgentProfileTab]).
 */
@Composable
internal fun MembersAgentRuntimeConfigSection(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    agentStatusIndicatorState: RaftAgentStatusIndicatorState?,
    edit: MembersAgentProfileEditInputs,
    runtimeModels: MembersAgentRuntimeModelInputs,
    createAgentFormStore: SharedCreateAgentFormStore?,
    onApplyAgentRuntimeConfigProjection: suspend (MembersAgentProfileEditAuthority, RuntimeConfig, Boolean?) -> Boolean
) {
    // Web AgentProfileInfo renders this section only `!isExternalAgent`: an external or cloud
    // agent's runtime and model are the "external" placeholders (a cloud agent's model is its own
    // row under the hosted status, [MembersAgentHostedRuntimeSection]).
    if (!membersAgentProfileShowsRuntimeConfig(member, summary)) return
    val canEdit = edit.canEdit
    val agentProfileEditAuthority = edit.agentProfileEditAuthority
    val agentDetailActions = edit.agentDetailActions
    val onUpdateAgentProfile = edit.onUpdateAgentProfile
    val onApplyAgentProfileResult = edit.onApplyAgentProfileResult
    val onUnavailableAction = edit.onUnavailableAction
    val errorStrings = edit.errorStrings
    val agentMachineRuntimes = runtimeModels.agentMachineRuntimes
    val agentRuntimeModelsByRuntime = runtimeModels.agentRuntimeModelsByRuntime
    val onAgentRuntimeModelsRequest = runtimeModels.onAgentRuntimeModelsRequest
    val runtimeConfigResolution = remember(summary) { summary.toMembersRuntimeConfigResolution() }
    val canonicalRuntimeConfig =
        (runtimeConfigResolution as? MembersAgentRuntimeConfigResolution.Content)?.config
    val canonicalRuntimeId = canonicalRuntimeConfig?.runtime
    val canonicalRuntimeModel = canonicalRuntimeConfig?.model
    // Runtime mutations need an explicit canonical status. A null status is
    // deliberately neutral in the renderer; treating it as "inactive" here
    // would allow a reset-sensitive edit while presence is still unknown.
    val canonicalRuntimeStatus = agentStatusIndicatorState?.status
    val runtimeStatusResolved = membersAgentRuntimeStatusResolved(canonicalRuntimeStatus)
    val activeAgent = membersAgentRuntimeActive(canonicalRuntimeStatus)
    // The v2 editor reads and validates the agent's config on the server, so opening it only needs a
    // known status (for the restart decision) and a readable config.
    val runtimeEditorReady = runtimeStatusResolved && canonicalRuntimeConfig != null
    val scope = rememberCoroutineScope()
    val currentProfileEditAuthority by rememberUpdatedState(agentProfileEditAuthority)
    val runtimeEditingUnavailable = stringResource(Res.string.member_error_runtime_edit_unavailable)
    val runtimeFormV2SavedRefresh = stringResource(Res.string.runtime_form_v2_saved_refresh)
    val permissionErrorTemplate = membersStringTemplate(Res.string.member_error_permission)
    val saveErrorTemplate = membersStringTemplate(Res.string.member_error_save)
    var editingRuntimeConfig by remember(member.id.value) { mutableStateOf(false) }
    var runtimeEditorAuthority by remember(member.id.value) {
        mutableStateOf<MembersAgentProfileEditAuthority?>(null)
    }
    // Inputs latched when the editor opens (artin 612602c0): a background/foreground cycle
    // reconnects presence, which publishes status=null until the roster refresh lands, and
    // re-reads detail/flags. Gating the dialog on those live values unmounted it and lost the
    // user's typing; the dialog now composes from this snapshot instead.
    var runtimeEditorSession by remember(member.id.value) {
        mutableStateOf<MembersRuntimeEditorSession?>(null)
    }
    var savingRuntimeConfig by remember(member.id.value) { mutableStateOf(false) }
    var runtimeConfigError by remember(member.id.value) { mutableStateOf<String?>(null) }
    // Runtime form v2 edit, the only runtime editor (task #8): a load failure shows Retry, a runtime
    // switch loads the new runtime's v2 form, and a runtime without a v2 form shows "unavailable".
    val runtimeFormV2Access =
        agentProfileEditAuthority?.serverId?.value?.let { createAgentFormStore?.runtimeFormV2Access(it) }
    var runtimeFormV2ErrorFields by remember(member.id.value, editingRuntimeConfig) { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(canonicalRuntimeId) {
        canonicalRuntimeId?.let { onAgentRuntimeModelsRequest(it, false) }
    }

    // The runtimes this agent may switch to, as this server decides (`GET agents/:id/runtime-options`):
    // the switch picker's list and the "(deprecated)" label/warning of its runtime. Read again when its
    // runtime changes (a switch); a failure leaves none, and the catalog decides.
    val agentRuntimeOptionsServerId = agentProfileEditAuthority?.serverId?.value
    var agentRuntimeOptions by remember(member.id.value, agentRuntimeOptionsServerId) {
        mutableStateOf<ExistingAgentRuntimeOptions?>(null)
    }
    val agentMachineRuntimesKey = remember(agentMachineRuntimes) { agentMachineRuntimes.sorted() }
    LaunchedEffect(createAgentFormStore, agentRuntimeOptionsServerId, member.id.value, canonicalRuntimeId, agentMachineRuntimesKey) {
        val store = createAgentFormStore ?: return@LaunchedEffect
        val serverId = agentRuntimeOptionsServerId ?: return@LaunchedEffect
        if (canonicalRuntimeId == null) return@LaunchedEffect
        agentRuntimeOptions = (store.loadAgentRuntimeOptions(serverId, member.id.value) as? DataResult.Content)?.data
    }

    // The one way an editing session ends: explicit close, save success, or a real close condition.
    fun closeRuntimeEditor() {
        editingRuntimeConfig = false
        runtimeEditorAuthority = null
        runtimeEditorSession = null
    }

    val toastHost = LocalRaftToastHostState.current
    val applyCopy = membersRuntimeConfigApplyCopy()

    fun showRuntimeConfigApplyNotice(notice: MembersRuntimeConfigApplyNotice) {
        if (toastHost == null) {
            onUnavailableAction(applyCopy.title(notice.kind, member.displayName))
            return
        }
        showMembersRuntimeConfigApplyNotice(toastHost, notice, member.displayName, applyCopy)
    }

    fun saveRuntimeConfigV2(submit: RuntimeFormV2SubmitBody, runtimeSwitched: Boolean, newSession: Boolean) {
        val callback = onUpdateAgentProfile
        // The access the form was loaded through; the authority check below still fences
        // account/server/profile changes.
        val access = runtimeEditorSession?.formV2Access ?: runtimeFormV2Access
        if (
            !canEdit ||
                !runtimeStatusResolved ||
                access == null ||
                (agentDetailActions == null && callback == null) ||
                savingRuntimeConfig
        ) {
            onUnavailableAction(runtimeEditingUnavailable)
            return
        }
        val openedAuthority = runtimeEditorAuthority
        val authorityCurrent = {
            membersAgentProfileEditAuthorityMatches(
                opened = openedAuthority,
                current = currentProfileEditAuthority,
                openedCatalogGeneration = 0L,
                currentCatalogGeneration = 0L
            )
        }
        if (!authorityCurrent()) {
            runtimeConfigError = "agent_runtime_editor_stale"
            onUnavailableAction(runtimeEditingUnavailable)
            return
        }
        // Set before launching: a second tap in the same frame (e.g. on the switch confirmation)
        // must hit the guard above instead of sending a second PATCH.
        savingRuntimeConfig = true
        scope.launch {
            // Every exit (a stale authority after the request included) ends the saving state.
            membersRuntimeEditorSaving(setSaving = { savingRuntimeConfig = it }) {
                runtimeConfigError = null
                runtimeFormV2ErrorFields = emptySet()
                val result =
                    try {
                        val request =
                            membersRuntimeFormV2UpdateRequest(
                                agentId = member.id,
                                displayName = member.displayName,
                                description = member.description,
                                submit = submit,
                                runtimeSwitched = runtimeSwitched,
                                activeAgent = activeAgent
                            )
                        if (agentDetailActions != null) {
                            agentDetailActions.updateAgentProfile(request)
                        } else {
                            requireNotNull(callback).invoke(request)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError("agent_runtime_config_save_failed"))
                    }
                if (!authorityCurrent()) return@membersRuntimeEditorSaving
                when (result) {
                    is DataResult.Content, is DataResult.Empty -> {
                        val savedProfile = (result as? DataResult.Content)?.data
                        val configApply = savedProfile?.configApply
                        val effectiveNewSession = savedProfile?.configApplyNewSession ?: newSession
                        savedProfile?.let { onApplyAgentProfileResult(it.withoutConfigApply()) }
                        // The server assembled the config; show what it stored rather than guessing locally.
                        val stored = (access.loader.agent(member.id) as? DataResult.Content)?.data
                        val config = (stored?.toAgentDetailSummary().toMembersRuntimeConfigResolution()
                            as? MembersAgentRuntimeConfigResolution.Content)?.config
                        val authority = runtimeEditorAuthority
                        val projected =
                            config != null && authority != null &&
                                onApplyAgentRuntimeConfigProjection(authority, config, stored?.modelLimitsDefaultsReported)
                        closeRuntimeEditor()
                        // The save landed; only the read-back failed, so the rows may lag until a refresh.
                        if (!projected) onUnavailableAction(runtimeFormV2SavedRefresh)
                        membersRuntimeConfigApplyNotice(
                            configApply = configApply,
                            agentActive = activeAgent,
                            newSession = effectiveNewSession
                        )?.let { notice -> showRuntimeConfigApplyNotice(notice) }
                    }
                    is DataResult.NoPermission ->
                        runtimeConfigError =
                            membersFormattedPermissionError(permissionErrorTemplate, result.blocker, errorStrings)
                    is DataResult.Error -> {
                        runtimeFormV2ErrorFields = result.error.formFieldKeys.toSet()
                        runtimeConfigError = membersFormattedScreenError(saveErrorTemplate, result.error, errorStrings)
                    }
                }
            }
        }
    }

    val runtimeEditorCanOpen =
        membersRuntimeEditorCanOpen(
            canEdit = canEdit,
            hasAuthority = agentProfileEditAuthority != null,
            hasConfig = canonicalRuntimeConfig != null,
            runtimeEditorReady = runtimeEditorReady,
            hasFormV2Access = runtimeFormV2Access != null
        )

    fun openRuntimeEditor() {
        val authority = agentProfileEditAuthority
        val config = canonicalRuntimeConfig
        if (runtimeEditorCanOpen && authority != null && config != null && runtimeFormV2Access != null) {
            runtimeConfigError = null
            runtimeEditorAuthority = authority
            runtimeEditorSession =
                MembersRuntimeEditorSession(
                    authority = authority,
                    config = config,
                    machineId = (summary?.machineId ?: member.membersComputerId)?.takeIf { it.isNotBlank() },
                    formV2Access = runtimeFormV2Access
                )
            editingRuntimeConfig = true
        } else {
            // Shown as on the web, but the editor needs a known status and a
            // readable config; until then say so instead of opening nothing.
            onUnavailableAction(runtimeEditingUnavailable)
        }
    }

    // Web AgentProfileInfo `openRuntimeConfigRequested` (botiverse/slock #13435, #13460): Change
    // model on the status, or View model settings on the billing banner, opens this editor, from
    // another tab once this one is shown. As on the web (`editing = opened || (requested &&
    // canOpen)`), a request waits while the editor cannot open yet (status still unknown, the
    // config or the form access still loading) and opens it once it can; it is consumed then.
    val runtimeEditorOpenRequested = edit.runtimeEditorOpenRequested
    LaunchedEffect(runtimeEditorOpenRequested, runtimeEditorCanOpen) {
        if (!membersRuntimeEditorOpenRequestActs(runtimeEditorOpenRequested, runtimeEditorCanOpen)) return@LaunchedEffect
        if (!editingRuntimeConfig) openRuntimeEditor()
        edit.onRuntimeEditorOpenRequestHandled()
    }

    // RUNTIME CONFIG section — web AgentProfileInfo: the "Runtime config" eyebrow with its pencil
    // (`canManageAgent && !editing`), the stacked Runtime / Model / Reasoning / Mode / Provider /
    // Command rows, then Environment Variables (`mt-3`), shown read-only and only when the agent
    // has some (web `EnvVarsSection canManageAgent={false}`).
    // No catalog-retry affordance in this read-only view (React parity): a failed
    // model-catalog prefetch only matters inside the editor dialog, which
    // re-requests on open and renders its own error + Retry (task #96).
    MembersProfileSection(
        title = stringResource(Res.string.member_section_runtime_config),
        titleAction =
            if (canEdit && !editingRuntimeConfig) {
                {
                    MembersProfilePencilButton(
                        contentDescription = stringResource(Res.string.member_dialog_edit_runtime_config),
                        onClick = { openRuntimeEditor() }
                    )
                }
            } else {
                null
            }
    ) {
        val runtimeModelsCatalog = canonicalRuntimeId?.let(agentRuntimeModelsByRuntime::get)
        // The agent's server: its catalog says whether the runtime is deprecated there.
        val runtimeCatalogServerId = member.serverId?.value ?: agentProfileEditAuthority?.serverId?.value
        val runtimeCatalog = build.raft.app.data.RaftRuntimeCatalogNames.observeCatalog(runtimeCatalogServerId)
        val runtimeDeprecated =
            canonicalRuntimeId?.let { id ->
                runtimeDeprecatedForAgent(id, agentRuntimeOptions, runtimeCatalogServerId) { rt ->
                    val normalized = rt.trim()
                    (runtimeCatalog?.entry(normalized) ?: build.raft.app.data.BundledRuntimeCatalog.entry(normalized))?.deprecated == true
                }
            }
        val liveActivityStatus =
            agentStatusIndicatorState?.status
                ?: normalizedAgentRuntimeStatus(activity = summary?.activity, lifecycleStatus = summary?.status)
        val liveServiceTier =
            if (agentStatusIndicatorState?.status != null) {
                agentStatusIndicatorState.serviceTier
            } else {
                summary?.serviceTier
            }
        MembersAgentRuntimeConfigRows(
            resolution = runtimeConfigResolution,
            runtimeModelsCatalog = runtimeModelsCatalog,
            machineId = summary?.machineId ?: member.membersComputerId,
            serverId = runtimeCatalogServerId,
            runtimeDeprecated = runtimeDeprecated,
            liveActivityStatus = liveActivityStatus,
            liveServiceTier = liveServiceTier,
        )
        // Web AgentProfileInfo: under the runtime rows (`mt-3`), before Environment Variables, only
        // when the server says the session reported Raft default limits (botiverse/slock #13492).
        if (summary?.modelLimitsDefaultsReported == true) {
            MembersAgentModelLimitsWarning(Modifier.padding(top = MembersProfileEnvVarsGap - MembersInfoItemGap))
        }
        MembersAgentRuntimeEnvVars(summary, modifier = Modifier.padding(top = MembersProfileEnvVarsGap - MembersInfoItemGap))
    }

    val session = runtimeEditorSession
    val runtimeEditorDialog =
        membersRuntimeEditorDialog(
            editing = editingRuntimeConfig,
            opened = session?.authority,
            openedFormV2 = session?.formV2Access != null,
            live = MembersRuntimeEditorLiveFacts(
                authority = agentProfileEditAuthority,
                canManage = edit.canManage,
                deleted = summary?.deletedAt?.isNotBlank() == true,
                runtimeStatusResolved = runtimeStatusResolved,
                config = canonicalRuntimeConfig,
                formV2Available = runtimeFormV2Access != null
            )
        )
    // A real close condition (deleted / permission lost) ends the session, so the editor does not
    // pop back up if that condition later clears.
    LaunchedEffect(editingRuntimeConfig, runtimeEditorDialog) {
        if (editingRuntimeConfig && runtimeEditorDialog == MembersRuntimeEditorDialog.None) {
            closeRuntimeEditor()
        }
    }
    if (session != null && runtimeEditorDialog == MembersRuntimeEditorDialog.FormV2) {
        MembersAgentRuntimeFormV2Dialog(
            access = checkNotNull(session.formV2Access),
            agentId = member.id,
            machineId = session.machineId,
            runtimeId = session.config.runtime,
            machineRuntimeIds = agentMachineRuntimes,
            activeAgent = activeAgent,
            modelLimitsDefaultsReported = summary?.modelLimitsDefaultsReported == true,
            saving = savingRuntimeConfig,
            error = runtimeConfigError,
            errorFieldKeys = runtimeFormV2ErrorFields,
            onDismiss = {
                if (!savingRuntimeConfig) {
                    closeRuntimeEditor()
                    runtimeConfigError = null
                }
            },
            onRuntimeSelected = {
                runtimeConfigError = null
                runtimeFormV2ErrorFields = emptySet()
            },
            runtimeCatalog = { machineId ->
                val store = createAgentFormStore
                (store?.loadRuntimeCatalog(session.authority.serverId.value, machineId) as? DataResult.Content)?.data
            },
            agentRuntimeOptions = agentRuntimeOptions,
            createAgentFormStore = createAgentFormStore,
            machineName = (summary?.machineName ?: member.membersComputerName)?.takeIf { it.isNotBlank() },
            onSave = ::saveRuntimeConfigV2
        )
    }
}

internal const val MEMBERS_RUNTIME_DEPRECATED_BANNER_TAG = "members-runtime-deprecated-banner"
internal const val MEMBERS_RUNTIME_FAST_MODE_FALLBACK_HINT_TAG = "members-runtime-fast-mode-fallback-hint"

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MembersAgentRuntimeConfigRows(
    resolution: MembersAgentRuntimeConfigResolution,
    runtimeModelsCatalog: SharedCreateAgentRuntimeModelCatalog?,
    machineId: String?,
    /** The agent's server: its runtime catalog names the runtime and says whether it is deprecated. */
    serverId: String?,
    /**
     * Whether this server deprecated the agent's runtime ([runtimeDeprecatedForAgent]: its own
     * runtime-options row); null: the catalog's answer. Drives " (deprecated)" and the warning.
     */
    runtimeDeprecated: Boolean? = null,
    /** Live agent status for fast-mode fallback display (botiverse/slock #16868). */
    liveActivityStatus: AgentStatus? = null,
    /** Live session `serviceTier` for fast-mode fallback display (botiverse/slock #16868). */
    liveServiceTier: String? = null,
) {
    val errorStrings = localizedErrorPresentationStrings()
    val runtimeConfig = (resolution as? MembersAgentRuntimeConfigResolution.Content)?.config
    if (runtimeConfig == null) {
        Text(
            text = stringResource(Res.string.member_state_runtime_config_unavailable),
            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
            color = RaftColor.DangerStrong
        )
        Text(
            text = (resolution as MembersAgentRuntimeConfigResolution.Unavailable)
                .code
                .userFacingErrorCodeMessage(errorStrings),
            style = RaftType.Caption,
            color = RaftColor.TextMutedGray
        )
        return
    }
    build.raft.app.data.EnsureServerModelLabelCatalog(serverId)
    build.raft.app.data.RaftModelLabelCatalogs.observeCatalog(serverId)
    build.raft.app.data.RaftRuntimeCatalogNames.observeCatalog(serverId)
    val runtimeId = runtimeConfig.runtime
    val modelLabel =
        membersAgentRuntimeModelDisplayLabel(
            runtimeId = runtimeId,
            modelId = runtimeConfig.toMembersRuntimeModelValue(),
            runtimeModelsCatalog = runtimeModelsCatalog,
            serverId = serverId,
            machineId = machineId
        )
    // The label below brings [serverId]'s catalog in; this follows it when it lands.
    val deprecated = runtimeDeprecated ?: runtimeCatalogIsDeprecated(runtimeId, serverId)
    // Web: the same DescriptionList as Info.
    RaftDescriptionList(Modifier.fillMaxWidth(), RaftDescriptionListDirection.Horizontal) {
        MembersAgentInfoField(stringResource(Res.string.member_label_runtime)) {
            MembersAgentRuntimeUsageChip(
                label = runtimeLabelWithStatus(runtimeId, serverId, deprecated = deprecated),
                runtimeId = runtimeId,
                machineId = machineId
            )
        }
        if (modelLabel != null) {
            MembersAgentInfoField(stringResource(Res.string.member_label_model), copyText = modelLabel) {
                // Web `<span className="min-w-0 break-all font-mono">`: plain machine text, no chip.
                RaftText(text = modelLabel, style = membersInfoValueStyle(mono = true), color = RaftTheme.colors.foreground)
            }
        }
        // Web AgentProfileInfo `reasoningLabel` (botiverse/slock #15691): the value the Members list's
        // agent rows show too; no row where the runtime takes no reasoning effort.
        membersReasoningEffortDisplayLabel(runtimeId, runtimeConfig.reasoningEffort)?.let { reasoning ->
            MembersAgentInfoField(stringResource(Res.string.member_label_reasoning)) {
                // Web `<span>`: the plain callout value.
                RaftText(
                    text = reasoning.localized(),
                    style = membersInfoValueStyle(),
                    color = RaftTheme.colors.foreground
                )
            }
        }
        if (supportsRuntimeFastMode(runtimeId)) {
            val displayedFastMode =
                membersResolveDisplayedFastMode(
                    config = runtimeConfig,
                    status = liveActivityStatus,
                    serviceTier = liveServiceTier,
                )
            val fastModeFallback =
                membersIsDisplayedFastModeFallback(
                    config = runtimeConfig,
                    status = liveActivityStatus,
                    serviceTier = liveServiceTier,
                )
            MembersAgentInfoField(stringResource(Res.string.member_label_mode)) {
                RaftText(
                    text = if (displayedFastMode) stringResource(Res.string.member_label_fast) else stringResource(Res.string.member_label_default),
                    style = membersInfoValueStyle(),
                    color = RaftTheme.colors.foreground
                )
                if (fastModeFallback) {
                    RaftText(
                        text = stringResource(Res.string.member_runtime_fast_mode_fallback_hint),
                        style = RaftTheme.typography.caption,
                        color = RaftTheme.colors.foregroundMuted,
                        modifier = Modifier.padding(top = 4.dp).testTag(MEMBERS_RUNTIME_FAST_MODE_FALLBACK_HINT_TAG)
                    )
                }
            }
        }
        if (runtimeId == CREATE_AGENT_BUILTIN_RUNTIME_ID) {
            val providerId = runtimeConfig.provider.membersBuiltInProviderId()
            // A saved provider connection (`kind:"connection"`) is a real choice, not "Default". The
            // agent read carries only its id, so the row names the kind with the web's
            // "Saved providers" wording (`agent.runtimeConfig.savedProviders`).
            val savedConnection = runtimeConfig.provider is RuntimeProviderConfig.Connection
            MembersAgentInfoField(stringResource(Res.string.member_label_provider), copyText = providerId.ifBlank { null }) {
                // Web: `font-mono` value, else "Default" in `text-foreground-hint`, on the callout.
                RaftText(
                    text = when {
                        providerId.isNotBlank() -> providerId
                        savedConnection -> stringResource(Res.string.member_label_saved_provider)
                        else -> stringResource(Res.string.member_label_default)
                    },
                    style = membersInfoValueStyle(mono = providerId.isNotBlank()),
                    color = if (providerId.isBlank() && !savedConnection) RaftTheme.colors.foregroundHint else RaftTheme.colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (runtimeId == "pi") {
            val providerId =
                (runtimeConfig.provider as? RuntimeProviderConfig.PiBuiltin)?.providerId
            MembersAgentInfoField(stringResource(Res.string.member_label_provider), copyText = providerId) {
                // Web: `font-mono` value, else "Default" in `text-foreground-hint`, on the callout.
                RaftText(
                    text = providerId ?: stringResource(Res.string.member_label_default),
                    style = membersInfoValueStyle(mono = !(providerId == null)),
                    color = if (providerId == null) RaftTheme.colors.foregroundHint else RaftTheme.colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (runtimeId == "claude") {
            val provider = runtimeConfig.provider as? RuntimeProviderConfig.Custom
            MembersAgentInfoField(
                stringResource(Res.string.member_label_provider),
                copyText = provider?.apiUrl?.takeIf { it.isNotBlank() }
            ) {
                // Web: `font-mono` value, else "Default" in `text-foreground-hint`, on the callout.
                RaftText(
                    text = provider?.apiUrl?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.member_label_default),
                    style = membersInfoValueStyle(mono = !(provider == null)),
                    color = if (provider == null) RaftTheme.colors.foregroundHint else RaftTheme.colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val command = runtimeConfig.command?.takeIf { it.isNotBlank() }
            MembersAgentInfoField(stringResource(Res.string.member_label_command), copyText = command) {
                // Web: `font-mono` value, else "Default" in `text-foreground-hint`, on the callout.
                RaftText(
                    text = command ?: stringResource(Res.string.member_label_default),
                    style = membersInfoValueStyle(mono = !(command == null)),
                    color = if (command == null) RaftTheme.colors.foregroundHint else RaftTheme.colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    // Web AgentProfileInfo, while the agent's runtime is deprecated (not retired: a retired one
    // cannot keep running and shows AgentRuntimeRetiredNotice under the status chip instead).
    if (deprecated && !build.raft.app.model.isRuntimeRetired(runtimeConfig.runtime)) {
        RaftUiBanner(
            status = RaftUiBannerStatus.Warning,
            description = stringResource(Res.string.member_runtime_deprecated_warning),
            size = RaftUiBannerSize.Small,
            modifier = Modifier.testTag(MEMBERS_RUNTIME_DEPRECATED_BANNER_TAG)
        )
    }
}

internal fun membersAgentRuntimeModelDisplayLabel(
    runtimeId: String,
    modelId: String,
    runtimeModelsCatalog: SharedCreateAgentRuntimeModelCatalog?,
    /** The agent's server: its catalogs' models, else the bundled list. */
    serverId: String? = null,
    /** The agent's computer: for looking up daemon-reported labels in the server model-label catalog. */
    machineId: String? = null
): String? {
    val normalizedRuntimeId = runtimeId.trim()
    val normalizedModelId = modelId.trim()
    if (normalizedRuntimeId.isBlank() || normalizedModelId.isBlank()) return null
    fun String?.verifiedDisplayLabel(): String? =
        this?.trim()?.takeIf { it.isNotBlank() && it != normalizedModelId }

    // Server model-label catalog first (web `projectRuntimeModelLabelPresentation`, slock #15780):
    // always checked before `runtimeModelsCatalog` so updated labels (including after live detect)
    // win instead of letting a stale per-runtime `runtimeModelsCatalog` mask them. Callers in
    // composition subscribe via `RaftModelLabelCatalogs.observeCatalog` / `RaftRuntimeCatalogNames.observeCatalog`.
    return build.raft.app.data.RaftModelLabelCatalogs.label(serverId, machineId, normalizedRuntimeId, normalizedModelId)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: runtimeModelsCatalog
            ?.models
            ?.firstOrNull { it.id.trim() == normalizedModelId }
            ?.label
            .verifiedDisplayLabel()
        // The server catalog's models for this runtime, else the bundled list.
        ?: build.raft.app.data.RaftRuntimeCatalogNames.entry(normalizedRuntimeId, serverId)
            ?.models
            ?.firstOrNull { it.id == normalizedModelId }
            ?.label
            .verifiedDisplayLabel()
        // The agent genuinely carries this model id; hiding it entirely reads as
        // "model missing" (task #284). Fall back to the raw id rather than nothing.
        ?: normalizedModelId
}

/**
 * Web EnvVarsSection, read-only (`canManageAgent={false}` on the profile): nothing when the agent
 * has no variables; else the "Environment Variables" eyebrow (`mb-1`) over `flex-wrap gap-2`
 * chips -- `rounded-sticker bg-fill-muted px-1.5 py-0.5`, Space Mono meta, `KEY=` in ink and the
 * masked value in hint ink.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MembersAgentRuntimeEnvVars(summary: AgentDetailSummary?, modifier: Modifier = Modifier) {
    val envVarNames = summary.membersRuntimeEnvVarNames()
    if (envVarNames.isEmpty()) return
    val envVars = summary?.envVars.orEmpty()
    val colors = RaftTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
    ) {
        RaftSectionEyebrow(text = stringResource(Res.string.member_section_environment_variables))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
        ) {
            envVarNames.forEach { name ->
                val shape = RoundedCornerShape(RaftRadius.Sticker)
                // Long press copies the variable name (the value stays masked).
                RaftText(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.foreground)) { append("$name=") }
                        withStyle(SpanStyle(color = colors.foregroundHint)) {
                            append(membersMaskedEnvValue(envVars[name]))
                        }
                    },
                    style = RaftTheme.typography.monoMeta,
                    color = colors.foreground,
                    modifier = Modifier
                        .clip(shape)
                        .background(colors.fillMuted, shape)
                        .raftCopyable(name)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun membersMaskedEnvValue(value: String?): String =
    "•".repeat(value?.length?.coerceIn(1, 8) ?: 3)

internal fun membersAgentRuntimeActive(status: AgentStatus?): Boolean =
    status != null && status.presence != Presence.Offline

internal fun membersAgentRuntimeStatusResolved(status: AgentStatus?): Boolean = status != null

/** What the runtime editor captured when the user opened it; the dialog composes from this. */
internal data class MembersRuntimeEditorSession(
    val authority: MembersAgentProfileEditAuthority,
    val config: RuntimeConfig,
    val machineId: String?,
    val formV2Access: RuntimeFormV2Access?
)

/**
 * The page's live facts on this frame. [runtimeStatusResolved], [config] and [formV2Available]
 * go null/false transiently on a background/foreground reconnect (presence publishes status=null
 * until the forced roster refresh lands); they gate opening and saving, never an open editor.
 */
internal data class MembersRuntimeEditorLiveFacts(
    val authority: MembersAgentProfileEditAuthority?,
    val canManage: Boolean,
    val deleted: Boolean,
    val runtimeStatusResolved: Boolean,
    val config: RuntimeConfig?,
    val formV2Available: Boolean
)

internal enum class MembersRuntimeEditorDialog { None, FormV2 }

/** The agent-detail route's manage right: private surfaces plus server admin or agent creator. */
internal fun membersRouteCanManageAgent(
    allowsPrivateAgentSurfaces: Boolean,
    canManageSettings: Boolean,
    currentUserId: String?,
    detail: AgentDetailSnapshot?
): Boolean =
    allowsPrivateAgentSurfaces &&
        (canManageSettings || detail?.summary?.creatorId == currentUserId)

/**
 * Manage right for an already open runtime editor. While a detail refresh is in flight or failed
 * the page has no [currentDetail] (the store still holds the last one), and a creator's right is
 * read from the detail, so fall back to [lastContentDetail]. A fresh detail always wins, so a
 * changed creator / revoked role / deletion still closes the editor.
 */
internal fun membersRuntimeEditorCanManage(
    currentDetail: AgentDetailSnapshot?,
    lastContentDetail: AgentDetailSnapshot?,
    refreshTransient: Boolean,
    canManageAgent: (AgentDetailSnapshot?) -> Boolean
): Boolean {
    val basis = currentDetail ?: lastContentDetail.takeIf { refreshTransient }
    return basis?.summary?.deletedAt?.isNotBlank() != true && canManageAgent(basis)
}

/**
 * Which runtime editor dialog is on screen. Once opened, only an explicit close, a real agent
 * deletion, or lost manage permission closes it. A null live authority (detail reloading, or an
 * account/server switch, which nulls it rather than replacing it) keeps it open; the save path's
 * authority checks are what fence a switch. The different-owner branch below is defensive only:
 * production does not hand this a non-null authority for another owner.
 */
internal fun membersRuntimeEditorDialog(
    editing: Boolean,
    opened: MembersAgentProfileEditAuthority?,
    openedFormV2: Boolean,
    live: MembersRuntimeEditorLiveFacts
): MembersRuntimeEditorDialog {
    if (!editing || opened == null) return MembersRuntimeEditorDialog.None
    if (live.deleted || !live.canManage) return MembersRuntimeEditorDialog.None
    val current = live.authority
    if (
        current != null &&
        (current.memberId != opened.memberId ||
            current.environmentId != opened.environmentId ||
            current.accountId != opened.accountId ||
            current.serverId != opened.serverId)
    ) {
        return MembersRuntimeEditorDialog.None
    }
    // Runtime form v2 is the only editor (task #8): load failures, runtime switches and an agent
    // without a computer all stay in it. Without v2 access (no loader or server) nothing opens.
    return if (openedFormV2) MembersRuntimeEditorDialog.FormV2 else MembersRuntimeEditorDialog.None
}

/**
 * Runs a v2 save [block] and always ends the saving state, whatever way it exits (a stale
 * authority after the request returns early; before this every such exit left Save spinning).
 */
internal suspend fun membersRuntimeEditorSaving(setSaving: (Boolean) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } finally {
        setSaving(false)
    }
}

/**
 * The PATCH for a v2 edit save: only the form ref and values travel. Web AgentProfileInfo
 * (botiverse/slock #13372, reconciler step 11c): a running agent is not interrupted, so its save
 * carries no `restartMode` (the server applies it at the agent's next idle point and says so in
 * `configApply`); only a stopped agent's runtime switch asks for a new session
 * (`restartMode: "session"`).
 */
internal fun membersRuntimeFormV2UpdateRequest(
    agentId: MemberId,
    displayName: String,
    description: String?,
    submit: RuntimeFormV2SubmitBody,
    runtimeSwitched: Boolean,
    activeAgent: Boolean
): AgentProfileUpdateRequest =
    AgentProfileUpdateRequest(
        agentId = agentId,
        displayName = displayName,
        description = description?.takeIf { it.isNotBlank() },
        runtimeResetMode = if (runtimeSwitched && !activeAgent) AgentResetMode.Session else null,
        runtimeFormV2 = submit
    )

/** The saved notice's words, resolved in composition. */
internal class MembersRuntimeConfigApplyCopy(
    /** "%1$s's settings saved". */
    private val savedTitleTemplate: String,
    /** "%1$s's settings applied". */
    private val appliedTitleTemplate: String,
    val appliesWhenIdle: String,
    val newSessionWhenIdle: String
) {
    fun title(kind: MembersRuntimeConfigApplyKind, agentName: String): String =
        when (kind) {
            MembersRuntimeConfigApplyKind.Applied -> appliedTitleTemplate.membersFormat(agentName)
            MembersRuntimeConfigApplyKind.Saved,
            MembersRuntimeConfigApplyKind.WhenIdle,
            MembersRuntimeConfigApplyKind.NewSessionWhenIdle -> savedTitleTemplate.membersFormat(agentName)
        }
}

@Composable
internal fun membersRuntimeConfigApplyCopy(): MembersRuntimeConfigApplyCopy =
    MembersRuntimeConfigApplyCopy(
        savedTitleTemplate = membersStringTemplate(Res.string.member_runtime_config_saved_title),
        appliedTitleTemplate = membersStringTemplate(Res.string.member_runtime_config_applied_title),
        appliesWhenIdle = stringResource(Res.string.member_runtime_config_applies_when_idle),
        newSessionWhenIdle = stringResource(Res.string.member_runtime_config_new_session_when_idle)
    )

/**
 * Web `announceRuntimeConfigApply` (botiverse/slock #15669 / #15714 / #16556, `#proj-runtime:#101`,
 * Raft DS › Toast):
 * - `Applied`: single-line confirmation toast ("<name>'s settings applied", no subtitle).
 * - `Saved`: single-line saved toast ("<name>'s settings saved", no subtitle).
 * - `WhenIdle` / `NewSessionWhenIdle`: saved title + subtitle saying it applies after the current
 *   message and inviting a Raft Computer update for instant switching.
 */
internal fun showMembersRuntimeConfigApplyNotice(
    host: RaftToastHostState,
    notice: MembersRuntimeConfigApplyNotice,
    agentName: String,
    copy: MembersRuntimeConfigApplyCopy
): Long {
    val description =
        when (notice.kind) {
            MembersRuntimeConfigApplyKind.Applied,
            MembersRuntimeConfigApplyKind.Saved -> null
            MembersRuntimeConfigApplyKind.WhenIdle -> copy.appliesWhenIdle
            MembersRuntimeConfigApplyKind.NewSessionWhenIdle -> copy.newSessionWhenIdle
        }
    return host.show(
        title = copy.title(notice.kind, agentName),
        description = description,
        intent = RaftToastIntent.Success
    )
}

/** When a saved runtime config change applies, as the saved notice says it. */
internal enum class MembersRuntimeConfigApplyKind { Applied, Saved, WhenIdle, NewSessionWhenIdle }

/** The saved notice: when the saved change applies. */
internal data class MembersRuntimeConfigApplyNotice(
    val kind: MembersRuntimeConfigApplyKind
)

/**
 * Web `announceRuntimeConfigApply` (botiverse/slock #15669 / #15714 / #16556, `#proj-runtime:#101`):
 * - `applied` / `restarted` on an active agent: single-line applied confirmation.
 * - `applied` / `restarted` on an inactive agent, or `saved`: single-line saved confirmation.
 * - `when_idle` (older Computer without `agent:config-interrupt-v1` while busy): saved title +
 *   subtitle saying it applies after the current message and inviting a Raft Computer update.
 * - `null` (older server): no toast.
 */
internal fun membersRuntimeConfigApplyNotice(
    configApply: AgentConfigApply?,
    agentActive: Boolean,
    newSession: Boolean
): MembersRuntimeConfigApplyNotice? {
    if (configApply == AgentConfigApply.Applied || configApply == AgentConfigApply.Restarted) {
        val kind =
            if (agentActive) MembersRuntimeConfigApplyKind.Applied
            else MembersRuntimeConfigApplyKind.Saved
        return MembersRuntimeConfigApplyNotice(kind)
    }
    if (configApply == AgentConfigApply.Saved) {
        return MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.Saved)
    }
    if (configApply != AgentConfigApply.WhenIdle) return null
    val kind =
        if (newSession) MembersRuntimeConfigApplyKind.NewSessionWhenIdle
        else MembersRuntimeConfigApplyKind.WhenIdle
    return MembersRuntimeConfigApplyNotice(kind)
}

/** The runtime-switch confirmation used by the runtime editor. */
@Composable
internal fun MembersRuntimeSwitchConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    RaftUnifiedDialog(
        title = title,
        maxWidth = 384.dp,
        contentPadding = 24.dp,
        onDismiss = onCancel
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1043
                    .raftCardSurface(fill = RaftColor.WarningSoft)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                RaftLucideIcon(
                    RaftLucideIconName.Info,
                    modifier = Modifier.size(18.dp),
                    color = RaftColor.TextBlackArgb
                )
                Text(
                    text = message,
                    // Web ConfirmDialog warning body: text-subhead (15/20 400).
                    style = RaftType.Subhead,
                    color = RaftColor.TextBlack
                )
            }
            RaftDialogFooter {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onCancel,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg
                )
                RaftButton(
                    text = confirmLabel,
                    variant = RaftButtonVariant.Warning,
                    size = RaftButtonSize.Lg,
                    onClick = onConfirm
                )
            }
        }
    }
}

internal sealed interface MembersAgentRuntimeConfigResolution {
    data class Content(val config: RuntimeConfig) : MembersAgentRuntimeConfigResolution
    data class Unavailable(val code: String) : MembersAgentRuntimeConfigResolution
}

private fun RuntimeProviderConfig?.membersBuiltInProviderId(): String =
    when (this) {
        is RuntimeProviderConfig.Preset -> providerId
        is RuntimeProviderConfig.Gateway -> providerId
        else -> ""
    }

internal fun AgentDetailSummary?.toMembersRuntimeConfigResolution(): MembersAgentRuntimeConfigResolution {
    val summary = this ?: return MembersAgentRuntimeConfigResolution.Unavailable("agent_runtime_profile_missing")
    val runtime = summary.runtime?.trim()?.takeIf { it.isNotBlank() }
        ?: return MembersAgentRuntimeConfigResolution.Unavailable("agent_runtime_missing")
    val model = summary.model?.trim()?.takeIf { it.isNotBlank() }
        ?: return MembersAgentRuntimeConfigResolution.Unavailable("agent_runtime_model_missing")
    return MembersAgentRuntimeConfigResolution.Content(RuntimeConfig(
        runtime = runtime,
        provider = summary.runtimeProvider,
        model = membersRuntimeModelConfig(runtime, model, summary.runtimeModelKind),
        // The saved `runtimeConfig.mode` (web `hydrateRuntimeConfigForm`), the value the edit form's
        // Fast mode / Speed start from (server `valuesFromRuntimeConfig`); none saved is Default. Never
        // `executionMode`: that is where the agent runs (byoc / cloud), not a mode.
        mode = if (summary.runtimeMode == "fast") RuntimeModeConfig.Fast else RuntimeModeConfig.Default,
        reasoningEffort = summary.reasoning?.takeIf { it.isNotBlank() },
        envVars = summary.envVars.takeIf { it.isNotEmpty() }
    ))
}

/**
 * The profile's Mode row, web `runtimeConfigFastMode`: fast mode as it launches. A saved fast mode on a
 * model that does not run fast (shared `runtimeModelSupportsFastMode`: Claude Opus 4.8) launches at the
 * standard speed, so it reads Default, as the edit form's Fast mode starts unchecked for it.
 */
internal fun membersRuntimeConfigFastMode(config: RuntimeConfig): Boolean =
    config.mode is RuntimeModeConfig.Fast &&
        raftRuntimeModelSupportsFastMode(config.runtime, config.toMembersRuntimeModelValue())

/**
 * Resolves whether an agent's Mode row should show Fast or Default (web `resolveDisplayedFastMode`,
 * botiverse/slock #16868). When a live session reports its actual `serviceTier` (e.g. `default`
 * after a fast-mode fallback), show the real tier rather than claiming Fast.
 */
internal fun membersResolveDisplayedFastMode(
    config: RuntimeConfig,
    status: AgentStatus? = null,
    serviceTier: String? = null,
): Boolean {
    val normalizedTier = normalizeAgentServiceTier(serviceTier)
    if (status != null && status != AgentStatus.Offline && status != AgentStatus.Disconnected && normalizedTier != null) {
        return normalizedTier !in CodexNonFastServiceTiers
    }
    return membersRuntimeConfigFastMode(config)
}

/**
 * True when the agent is configured for fast mode, is live, and the session reported a non-fast
 * `serviceTier` (such as `default` or `flex` after a fallback; web `isDisplayedFastModeFallback`,
 * botiverse/slock #16868).
 */
internal fun membersIsDisplayedFastModeFallback(
    config: RuntimeConfig,
    status: AgentStatus? = null,
    serviceTier: String? = null,
): Boolean =
    membersRuntimeConfigFastMode(config) && !membersResolveDisplayedFastMode(config, status, serviceTier)

internal fun membersRuntimeModelConfig(
    runtime: String,
    model: String,
    modelKind: String?
): RuntimeModelConfig =
    when (modelKind?.trim()?.lowercase()) {
        "custom" -> RuntimeModelConfig.Custom(model)
        "preset" -> RuntimeModelConfig.Preset(model)
        else -> {
            // No stored kind: a value outside the runtime's bundled presets is a custom model name.
            val trimmed = model.trim()
            val customModelMode = trimmed.isNotBlank() && RUNTIME_MODELS[runtime].orEmpty().none { it.id == trimmed }
            if (customModelMode) RuntimeModelConfig.Custom(model) else RuntimeModelConfig.Preset(model)
        }
    }

private fun RuntimeConfig.toMembersRuntimeModelValue(): String =
    when (val modelConfig = model) {
        is RuntimeModelConfig.Preset -> modelConfig.id
        is RuntimeModelConfig.Custom -> modelConfig.name
}

internal fun membersAgentMachineRuntimeIds(
    machines: List<SharedCreateAgentMachineOption>,
    machineId: String?
): List<String> {
    val normalizedMachineId = machineId?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    return machines.firstOrNull { it.id == normalizedMachineId }?.runtimes.orEmpty()
}

private fun AgentDetailSummary?.membersRuntimeEnvVarNames(): List<String> =
    if (this == null) {
        emptyList()
    } else {
        (envVars.keys + envVarNames)
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
    }

private fun List<Pair<String, String>>.toMembersRuntimeEnvVars(): Map<String, String>? {
    val values =
        mapNotNull { (key, value) ->
            key.trim().takeIf { it.isNotBlank() }?.let { it to value }
        }.toMap()
    return values.takeIf { it.isNotEmpty() }
}
