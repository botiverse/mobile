package build.raft.app

import build.raft.app.format.raftKeepTogether
import build.raft.app.localized
import build.raft.app.members.LocalMembersRuntimeAccountUsageAccess
import build.raft.app.members.MembersRuntimeAccountUsageAccess
import build.raft.app.members.localized
import build.raft.app.resources.*
import build.raft.app.settings.settingsBillingOffersUpgrade
import build.raft.app.components.raftUpsellCopy
import build.raft.app.components.RaftViewPlanButton
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftDesignSystemEditor
import build.raft.app.components.rememberRaftDesignSystemEditor
import build.raft.app.components.rememberRaftTextareaEditorModifier
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftCombobox
import build.raft.designsystem.components.RaftFieldState
import build.raft.designsystem.components.LocalRaftFieldState
import build.raft.designsystem.components.raftFieldSpokenName
import androidx.compose.runtime.CompositionLocalProvider
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.AnnotatedString
import build.raft.app.components.RaftUiBanner
import build.raft.app.components.RaftUiBannerSize
import build.raft.app.components.RaftUiBannerStatus
import build.raft.app.model.RUNTIMES
import build.raft.app.model.suggestAgentName
import build.raft.app.model.validateAgentName
import build.raft.app.data.DataResult
import build.raft.app.data.PROFILE_DESCRIPTION_MAX_LENGTH
import build.raft.app.data.profileDescriptionLength
import build.raft.app.data.RuntimeFormV2Availability
import build.raft.app.data.SharedCreateAgentRuntimeCatalog
import build.raft.app.data.CREATE_AGENT_HOSTED_PROVIDER
import build.raft.app.data.HostedRuntimeModels
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.createAgentRuntimeForOptions
import build.raft.app.data.createAgentSuggestedRuntimeNoticeCause
import build.raft.app.data.createAgentSuggestedRuntimeUnavailable
import build.raft.app.data.CreateAgentSeatState
import build.raft.app.data.CreateAgentSuggestedRuntimeUnavailable
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.RaftRuntimeCatalogNames
import build.raft.app.data.RuntimeCatalogSource
import build.raft.app.data.formatSeatTenths
import build.raft.app.data.runtimeFormV2Availability
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Values
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody
import build.raft.app.data.runtimeform.v2.RUNTIME_FORM_V2_FORM_REVISION_STALE
import build.raft.app.data.runtimeform.v2.applyRuntimeFormV2Change
import build.raft.app.data.runtimeform.v2.initialRuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.runtimeFormV2Submission
import build.raft.app.data.runtimeform.v2.runtimeFormV2ValuesAfterSourceReload
import build.raft.app.data.runtimeform.v2.validateRuntimeFormV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import build.raft.designsystem.tokens.RaftRadius
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.raftClickable
import kotlinx.serialization.json.JsonObject

// #55 visual parity: the legacy `CREATE_AGENT_DIALOG_BODY_MAX_HEIGHT = 430.dp`
// cap has been removed. The dialog body now fills the viewport via
// `Column.fillMaxHeight()` and the LazyColumn uses `weight(1f)` so the
// Cancel/Create row stays pinned at the bottom. The cap value was kept
// commented out below for historical reference only; do not reintroduce
// without also reverting the layout change.
// private val CREATE_AGENT_DIALOG_BODY_MAX_HEIGHT = 430.dp

internal data class SharedCreateAgentDialogSubmit(
    val name: String,
    val description: String?,
    val machineId: String?,
    val external: Boolean = false,
    /** The runtime form v2 submit; null only for an external or cloud agent. */
    val runtimeFormV2: RuntimeFormV2SubmitBody? = null,
    /** Cloud agent ([external] too); [hostedModel] null means the provider's default. */
    val hostedProvider: String? = null,
    val hostedModel: String? = null
)

/** The computer list's value for a cloud agent (web `HOSTED_COMPUTER_CHOICE`). */
internal const val CREATE_AGENT_HOSTED_CHOICE = "__hosted_runtime__"

/** The models a new cloud agent can start on, as the form's Model field shows them. */
internal sealed interface CreateAgentHostedModels {
    data object Loading : CreateAgentHostedModels
    /** Create still works, on the provider's default (web: "the model can change later"). */
    data object Failed : CreateAgentHostedModels
    data class Ready(val models: HostedRuntimeModels) : CreateAgentHostedModels
}

/** Web `isHostedMode`: picked, or the only place the agent can run (the computer list is loaded and empty). */
internal fun createAgentHostedMode(
    available: Boolean,
    selected: Boolean,
    machines: List<SharedCreateAgentMachineOption>,
    machinesLoading: Boolean,
    machinesErrorMessage: String?,
): Boolean =
    available && (selected || (machines.isEmpty() && !machinesLoading && machinesErrorMessage.isNullOrBlank()))

/** Web `hostedModelPickerShown`: no field where the server may not pick; while loading it may yet. */
internal fun createAgentHostedModelFieldShown(models: CreateAgentHostedModels): Boolean =
    (models as? CreateAgentHostedModels.Ready)?.models?.picker != false

/** The model a create names: none while the list is out, an administrator decides, or Raft does. */
internal fun createAgentHostedModelToSend(models: CreateAgentHostedModels, pick: String?): String? {
    val view = (models as? CreateAgentHostedModels.Ready)?.models ?: return null
    if (view.locked || !view.picker) return null
    return pick?.takeIf { id -> view.models.any { it.id == id } }
}

/** What the create dialog's runtime section shows (runtime form v2, the only runtime form). */
internal sealed interface CreateAgentRuntimeSection {
    /** No runtime is chosen yet: only the picker (web's dialog has no form, and no notice, until one is). */
    data object NoRuntime : CreateAgentRuntimeSection
    data object Loading : CreateAgentRuntimeSection
    data class Ready(val loaded: RuntimeFormV2Loaded) : CreateAgentRuntimeSection
    /** The load failed: a warning with Retry. */
    data class Failed(val code: String) : CreateAgentRuntimeSection
    /** The runtime has no v2 form (no `runtimeFormV2` marker): a plain "unavailable" notice. */
    data object Unavailable : CreateAgentRuntimeSection
}

/**
 * The runtime section in every state. A runtime the server marks without a v2 form is
 * [CreateAgentRuntimeSection.Unavailable] (no endless Retry); a failed load is
 * [CreateAgentRuntimeSection.Failed]. Nothing falls back to v1 (task #8, retire runtime form v1).
 */
internal fun createAgentRuntimeSection(
    runtime: String,
    availability: RuntimeFormV2Availability,
    load: RuntimeFormV2DialogLoad
): CreateAgentRuntimeSection =
    if (runtime.isBlank()) {
        CreateAgentRuntimeSection.NoRuntime
    } else if (availability == RuntimeFormV2Availability.Unavailable) {
        CreateAgentRuntimeSection.Unavailable
    } else {
        when (load) {
            is RuntimeFormV2DialogLoad.Ready -> CreateAgentRuntimeSection.Ready(load.loaded)
            is RuntimeFormV2DialogLoad.Failed -> CreateAgentRuntimeSection.Failed(load.code)
            RuntimeFormV2DialogLoad.Loading -> CreateAgentRuntimeSection.Loading
        }
    }

/** Create needs the loaded v2 form and no field errors. */
internal fun createAgentRuntimeFormV2SubmitReady(formLoaded: Boolean, hasErrors: Boolean): Boolean =
    formLoaded && !hasErrors

internal sealed interface RuntimeFormV2DialogLoad {
    data object Loading : RuntimeFormV2DialogLoad
    data class Ready(val loaded: RuntimeFormV2Loaded) : RuntimeFormV2DialogLoad
    data class Failed(val code: String) : RuntimeFormV2DialogLoad
}

/**
 * The create submit: an external agent sends no runtime; any other agent sends the v2 form
 * ([loaded] with [values]), or nothing while the form has not loaded.
 */
internal fun createAgentDialogSubmit(
    name: String,
    description: String,
    machineId: String?,
    external: Boolean,
    runtime: String,
    loaded: RuntimeFormV2Loaded?,
    values: RuntimeFormV2Values,
    hosted: Boolean = false,
    hostedModel: String? = null,
    proposedEnvVars: ProposedEnvVars? = null
): SharedCreateAgentDialogSubmit? {
    val cleanName = name.trim().removePrefix("@")
    val cleanDescription = description.trim().takeIf { it.isNotBlank() }
    if (hosted) {
        return SharedCreateAgentDialogSubmit(
            name = cleanName,
            description = cleanDescription,
            machineId = null,
            external = true,
            hostedProvider = CREATE_AGENT_HOSTED_PROVIDER,
            hostedModel = hostedModel
        )
    }
    if (external) return SharedCreateAgentDialogSubmit(cleanName, cleanDescription, machineId = null, external = true)
    val form = loaded ?: return null
    val submittedFormValues = runtimeFormV2Submission(form.form, values, form.sources)
    val finalFormValues =
        if (proposedEnvVars != null) {
            JsonObject(
                submittedFormValues +
                    ("envVars" to proposedEnvVarsForSubmit(proposedEnvVars, submittedFormValues["envVars"]))
            )
        } else {
            submittedFormValues
        }
    return SharedCreateAgentDialogSubmit(
        name = cleanName,
        description = cleanDescription,
        machineId = machineId,
        runtimeFormV2 =
            RuntimeFormV2SubmitBody(
                runtimeId = form.form.runtimeId.ifBlank { runtime },
                formValues = finalFormValues,
                protocolVersion = form.form.protocolVersion
            )
    )
}

internal const val SharedCreateAgentDialogHostRoute = "create-agent"

@Composable
internal fun CreateAgentDialogVisualContent() {
    val machineRuntimes = (listOf("codex", "claude") + RUNTIMES.filterNot { it.deprecated }.map { it.id }).distinct()
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
    ) {
        SharedCreateAgentDialog(
            submitting = false,
            errorMessage = null,
            initialName = "Product-QA-Bot",
            initialDescription = "Watches release screenshots and reports visual regressions before merge.",
            // Like React's fixture: the computer's runtime list has not answered, so none is chosen.
            initialRuntime = "",
            machines =
                listOf(
                    SharedCreateAgentMachineOption(
                        id = "computer-mbp",
                        name = "Jiachengs-MacBook-Pro",
                        status = "online",
                        runtimes = machineRuntimes
                    ),
                    SharedCreateAgentMachineOption(
                        id = "computer-studio",
                        name = "Studio Test Rig",
                        status = "offline",
                        runtimes = listOf("codex")
                    )
                ),
            // React's deterministic visual fixture seeds the Free plan at its
            // 2-agent capacity, so both providers must render the same warning
            // state instead of comparing React's banner with Android's normal form.
            currentAgentCount = 2,
            maxAgents = 2,
            limitPlanLabel = "Free",
            onUpgrade = {},
            onDismiss = {},
            onSubmit = {}
        )
    }
}

/**
 * After a create succeeds: remembers its Computer and runtime for the next dialog on [serverId]
 * (web writes the last config after the create). External and cloud agents have neither and are skipped.
 */
internal fun SharedCreateAgentFormStore.rememberCreatedAgent(serverId: String?, submit: SharedCreateAgentDialogSubmit) {
    if (submit.external) return
    rememberCreatedAgent(serverId, submit.machineId, submit.runtimeFormV2?.runtimeId)
}

/**
 * Convert a dialog submit to the data-layer [build.raft.app.data.CreateAgentRequest].
 * Keeps the data layer free of UI types — the dialog knows about runtime
 * config, the coordinator knows about the body shape; the mapping lives
 * here at the boundary.
 */
internal fun SharedCreateAgentDialogSubmit.toCreateAgentRequest(): build.raft.app.data.CreateAgentRequest =
    build.raft.app.data.CreateAgentRequest(
        name = name,
        description = description,
        machineId = machineId,
        external = external,
        avatarUrl = randomCreateAgentAvatarWireValue(),
        runtimeFormV2 = runtimeFormV2,
        hostedProvider = hostedProvider,
        hostedModel = hostedModel
    )

private fun randomCreateAgentAvatarWireValue(): String {
    val keys = PixelAvatarPickerKeys
    val key = if (keys.isEmpty()) PixelAvatarActiveDefaultKey else keys.random()
    return "pixel:$key"
}

/** Each computer's quota line: the usage of [runtimeId] (the runtime being picked) on it. */
@Composable
internal fun rememberCreateAgentMachineQuotaLines(
    access: MembersRuntimeAccountUsageAccess?,
    machines: List<SharedCreateAgentMachineOption>,
    runtimeId: String
): Map<String, String> =
    rememberRuntimeQuotaLines(access, remember(machines, runtimeId) { machines.map { it.quotaRequest(runtimeId) } })

/** The computer picker's options, combining each computer's owner description and quota line. */
internal fun createAgentMachineChoiceOptions(
    items: List<CreateAgentDropdownItem>,
    quotaLines: Map<String, String>
): List<RaftComboboxOption<String>> =
    items.map { item ->
        val description =
            listOfNotNull(
                item.description?.trim()?.takeIf { it.isNotEmpty() },
                quotaLines[item.id]?.trim()?.takeIf { it.isNotEmpty() }
            ).joinToString("\n").ifBlank { null }
        RaftComboboxOption(item.id, item.label, description = description, enabled = item.enabled)
    }

internal data class CreateAgentDropdownItem(
    val id: String,
    val label: String,
    val description: String? = null,
    val custom: Boolean = false,
    val enabled: Boolean = true
)

internal const val CREATE_AGENT_NO_MACHINE_RUNTIMES_ERROR =
    "Selected computer has no runtime data. Refresh computers or choose another computer."

private fun normalizeCreateAgentRuntimeIds(runtimeIds: List<String>): List<String> =
    runtimeIds
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

internal fun createAgentRuntimeAvailabilityError(
    selectedMachineId: String?,
    machineRuntimeIds: List<String>,
    external: Boolean
): String? =
    if (!external && !selectedMachineId.isNullOrBlank() && normalizeCreateAgentRuntimeIds(machineRuntimeIds).isEmpty()) {
        CREATE_AGENT_NO_MACHINE_RUNTIMES_ERROR
    } else {
        null
    }

internal fun createAgentMachineDropdownItems(
    machines: List<SharedCreateAgentMachineOption>,
    requiredComputer: String?,
    offlineSuffix: String = " · Offline"
): List<CreateAgentDropdownItem> =
    machines.map { machine ->
        val isOffline = machine.status != "online"
        val blockedByRequired = !requiredComputer.isNullOrBlank() && machine.id != requiredComputer
        CreateAgentDropdownItem(
            id = machine.id,
            label = machine.name + if (isOffline) offlineSuffix else "",
            description = machine.description?.trim()?.takeIf { it.isNotEmpty() },
            enabled = !isOffline && !blockedByRequired
        )
    }

/**
 * The computer the form opens on: the [requiredComputer] (or none while it is offline), else the
 * [initialComputer] the entry opened it for (a Computer card's Create agent), else the
 * [suggestedComputer], else the first online one of the viewer's own (web `pickFallbackMachine`:
 * the person creating the agent usually means their own computer), else the first online one. An
 * offline [initialComputer] falls through, so the user picks another as usual.
 */
internal fun createAgentInitialMachineSelection(
    machines: List<SharedCreateAgentMachineOption>,
    requiredComputer: String?,
    suggestedComputer: String?,
    initialComputer: String? = null
): String? {
    fun online(id: String?): String? =
        id?.takeIf { candidate -> machines.any { it.id == candidate && it.status == "online" } }
    if (!requiredComputer.isNullOrBlank()) {
        return online(requiredComputer)
    }
    return online(initialComputer)
        ?: online(suggestedComputer)
        ?: machines.firstOrNull { it.status == "online" && it.mine }?.id
        ?: machines.firstOrNull { it.status == "online" }?.id
}

internal fun createAgentAvailableMachineId(
    machines: List<SharedCreateAgentMachineOption>,
    selectedMachineId: String?
): String? =
    selectedMachineId?.takeIf { selectedId -> machines.any { machine -> machine.id == selectedId } }

@Composable
internal fun SharedCreateAgentDialog(
    submitting: Boolean,
    errorMessage: String?,
    initialName: String = "",
    initialDescription: String = "",
    initialRuntime: String = "",
    initialAdvancedOpen: Boolean = false,
    draftHint: String? = null,
    suggestedComputer: String? = null,
    requiredComputer: String? = null,
    /**
     * The computer the entry opened the form for (a Computer card's Create agent): selected while it
     * is online, with no hint line; offline, the form picks as usual. The user may change it.
     */
    initialComputer: String? = null,
    /**
     * A Create Agent card's suggested runtime (web `prefilledRuntime`): the starting pick, before the
     * remembered last runtime, only while the chosen computer offers it; otherwise nothing is picked
     * and the form says why. The person may change it; it is never remembered unless created with.
     */
    suggestedRuntime: String? = null,
    /**
     * A Create Agent card's proposed non-secret env vars (web `prefilledEnvVars`, slock #16341):
     * shown open and editable; config directories start unticked with a warning.
     */
    suggestedEnvVars: Map<String, String> = emptyMap(),
    /** The agent that proposed [suggestedEnvVars], by name; null when unseen by this viewer. */
    suggestedEnvVarsProposer: String? = null,
    machines: List<SharedCreateAgentMachineOption> = emptyList(),
    machinesLoading: Boolean = false,
    machinesErrorMessage: String? = null,
    onMachinesRetry: () -> Unit = {},
    createAgentFormStore: SharedCreateAgentFormStore? = null,
    createAgentServerId: String? = null,
    currentAgentCount: Int = 0,
    maxAgents: Int = -1,
    limitPlanLabel: String? = null,
    /** Universal-seat state under `seat_v1` (slock #16345, #16367); null under `person_v2` or non-seat plans. */
    seatLimit: CreateAgentSeatState? = null,
    external: Boolean = false,
    /**
     * The Members and Add Member flows offer a cloud agent next to the computers. Off for action
     * cards: their confirm flow creates on a computer.
     */
    offersHosting: Boolean = false,
    /**
     * Add Member's channel: the dialog keeps the entry's promise that the new agent joins it
     * (web `autoJoinChannelName`, `create-agent-channel-context`); null elsewhere.
     */
    autoJoinChannelName: String? = null,
    runtimeFormV2Override: RuntimeFormV2Access? = null,
    /** Optional override for tests/fixtures so Advanced env-var copying stays store-free when injected. */
    envVarsCopyOverride: RuntimeEnvVarsCopySource? = null,
    errorFieldKeys: Set<String> = emptySet(),
    takenAgentNames: List<String> = emptyList(),
    /** iOS view-only Billing: "View your plan" on the agent-limit banner (and [errorMessage] when [errorOffersViewPlan]). */
    onViewPlan: (() -> Unit)? = null,
    errorOffersViewPlan: Boolean = false,
    /** Where Billing offers upgrades: "Upgrade for more" in the agent-limit banner opens Billing. */
    onUpgrade: (() -> Unit)? = null,
    /** Like [onUpgrade], carrying the current agent name for a Pro `agent:create` seat request (`pro_seat_add`). */
    onUpgradeWithAgentName: ((String) -> Unit)? = null,
    /**
     * Opens the Add Computer flow from the no-computer dialog (web `setShowAddMachine(true)`);
     * null where that flow is not reachable, which hides the button.
     */
    onAddComputer: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSubmit: (SharedCreateAgentDialogSubmit) -> Unit
) {
    val cleanCreateAgentServerId = createAgentServerId?.trim().orEmpty()
    // A cloud agent: the flag, then the server's probe (web `useAgentRuntimeProvider`); null while it runs.
    val hostingAsked = offersHosting && !external
    var hostedAvailable by remember(createAgentFormStore, cleanCreateAgentServerId, hostingAsked) {
        mutableStateOf<Boolean?>(
            if (hostingAsked && createAgentFormStore?.hostedRuntimeFlagOn(cleanCreateAgentServerId) == true) null else false
        )
    }
    LaunchedEffect(createAgentFormStore, cleanCreateAgentServerId, hostingAsked) {
        if (hostedAvailable != null) return@LaunchedEffect
        hostedAvailable = createAgentFormStore?.loadHostedRuntimeAvailable(cleanCreateAgentServerId) == true
    }
    // Web CreateAgentDialog: with no computer and no cloud there is nothing to configure, so it asks
    // for a computer in a short centred dialog instead of the form (no limit banner, no fields).
    if (hostedAvailable == false && createAgentNeedsComputer(external, machines, machinesLoading, machinesErrorMessage)) {
        CreateAgentNeedsComputerDialog(onAddComputer = onAddComputer, onDismiss = onDismiss)
        return
    }
    val focusManager = LocalFocusManager.current
    // A fresh create starts with a suggested `adjective-noun` name instead of an empty
    // field (react CreateAgentDialog parity); the dice button beside the label rolls
    // another. Prefilled names (action cards, typed member query) are kept as-is.
    var name by remember(initialName) {
        mutableStateOf(initialName.ifBlank { suggestAgentName(takenAgentNames) })
    }
    var description by remember(initialDescription) { mutableStateOf(textFieldValueAtEnd(initialDescription)) }
    // Nothing until the selected computer's runtime-options land (web: `""` until then); see
    // [createAgentRuntimeForOptions] below.
    var runtime by remember(initialRuntime) { mutableStateOf(initialRuntime.trim()) }
    var advancedOpen by remember(initialAdvancedOpen) { mutableStateOf(initialAdvancedOpen) }
    val hasProposedEnvVars = suggestedEnvVars.isNotEmpty()
    var proposedEnvVars by remember(suggestedEnvVars) { mutableStateOf(proposedEnvVarEntries(suggestedEnvVars)) }
    var selectedMachineId by remember {
        mutableStateOf<String?>(
            createAgentInitialMachineSelection(
                machines = machines,
                requiredComputer = requiredComputer,
                suggestedComputer = suggestedComputer,
                initialComputer = initialComputer
            )
        )
    }
    val availableSelectedMachineId = createAgentAvailableMachineId(machines, selectedMachineId)
    var hostedSelected by remember { mutableStateOf(false) }
    val hostedMode =
        createAgentHostedMode(hostedAvailable == true, hostedSelected, machines, machinesLoading, machinesErrorMessage)
    // Neither an external nor a cloud agent runs on a computer: no runtime fields (web `skipsComputer`).
    val skipsComputer = external || hostedMode
    var hostedModelsAttempt by remember { mutableStateOf(0) }
    var hostedModels by remember(createAgentFormStore, cleanCreateAgentServerId, hostedModelsAttempt) {
        mutableStateOf<CreateAgentHostedModels>(CreateAgentHostedModels.Loading)
    }
    var hostedModelPick by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(createAgentFormStore, cleanCreateAgentServerId, hostedMode, hostedModelsAttempt) {
        val formStore = createAgentFormStore ?: return@LaunchedEffect
        if (!hostedMode || hostedModels != CreateAgentHostedModels.Loading) return@LaunchedEffect
        hostedModels =
            when (val result = formStore.loadHostedRuntimeModels(cleanCreateAgentServerId)) {
                is DataResult.Content -> CreateAgentHostedModels.Ready(result.data)
                else -> CreateAgentHostedModels.Failed
            }
    }
    // Read here: dialog content is hosted at the page root, outside the route's usage access.
    val runtimeUsageAccess = LocalMembersRuntimeAccountUsageAccess.current
    val selectedQuotaMachine =
        machines.firstOrNull { it.id == availableSelectedMachineId }?.quotaMachine()?.copy(access = runtimeUsageAccess)
    val modelUpgradeTarget =
        rememberRuntimeModelUpgradeTarget(
            access = runtimeUsageAccess,
            serverId = cleanCreateAgentServerId,
            machineId = availableSelectedMachineId,
            runtimeId = runtime,
            machineOption = machines.firstOrNull { it.id == availableSelectedMachineId },
        )
    // The runtimes this server offers (GET servers/:id/runtimes): the cached answer first, then the
    // server's; the bundled table until either lands. Never blocks the dialog.
    var serverRuntimeCatalog by remember(createAgentFormStore, cleanCreateAgentServerId) {
        mutableStateOf(RaftRuntimeCatalogNames.catalog(cleanCreateAgentServerId) ?: BundledRuntimeCatalog)
    }
    LaunchedEffect(createAgentFormStore, cleanCreateAgentServerId) {
        val formStore = createAgentFormStore ?: return@LaunchedEffect
        if (cleanCreateAgentServerId.isBlank()) return@LaunchedEffect
        if (serverRuntimeCatalog.source == RuntimeCatalogSource.Bundled) {
            formStore.cachedServerRuntimeCatalog(cleanCreateAgentServerId).let { if (it != serverRuntimeCatalog) serverRuntimeCatalog = it }
        }
        formStore.loadServerRuntimeCatalog(cleanCreateAgentServerId, DataRequestPriority.Visible).let {
            if (it != serverRuntimeCatalog) serverRuntimeCatalog = it
        }
    }
    // The computer's runtime-options rows: what a new agent may run here (the server's verdict, each
    // row's `canSelectInThisContext`), which runtimes have a v2 form (`runtimeFormV2` marker) and
    // whether Built-in is offered. Reloaded on every computer change; a failure leaves it unknown.
    var runtimeCatalogAttempt by remember { mutableStateOf(0) }
    var runtimeCatalog by remember(createAgentFormStore, cleanCreateAgentServerId, availableSelectedMachineId, skipsComputer) {
        mutableStateOf<SharedCreateAgentRuntimeCatalog?>(null)
    }
    val selectedMachineRuntimesKey =
        remember(machines, availableSelectedMachineId) {
            machines.firstOrNull { it.id == availableSelectedMachineId }?.runtimes.orEmpty().sorted()
        }
    LaunchedEffect(
        createAgentFormStore,
        cleanCreateAgentServerId,
        availableSelectedMachineId,
        skipsComputer,
        runtimeCatalogAttempt,
        selectedMachineRuntimesKey
    ) {
        val formStore = createAgentFormStore ?: return@LaunchedEffect
        val machineId = availableSelectedMachineId ?: return@LaunchedEffect
        if (skipsComputer || cleanCreateAgentServerId.isBlank()) return@LaunchedEffect
        val result = formStore.loadRuntimeCatalog(cleanCreateAgentServerId, machineId, force = true)
        runtimeCatalog = (result as? DataResult.Content)?.data
    }
    // The selected computer's rows, once they are its own: the list and the submit gate follow them,
    // never a catalog's `deprecated` (#proj-runtime:#57). Until then (or on failure) the catalog decides.
    val runtimeOptionRows = runtimeCatalog?.takeIf { it.machineId == availableSelectedMachineId }?.options
    val supportedRuntimeIds =
        remember(serverRuntimeCatalog, runtimeOptionRows) { serverRuntimeCatalog.creatableRuntimeIds(options = runtimeOptionRows) }
    val builtInRuntimeEnabled = runtimeCatalog?.options?.any { it.runtimeId == "builtin" && it.selectable } == true
    // The default comes from the computer's runtime-options alone (installed and allowed here); the
    // catalog only names, groups and gives forms. The last create's runtime on this server wins while
    // it is still one of them (web CreateAgentDialog `pickCreateAgentRuntime`).
    val rememberedRuntime =
        remember(createAgentFormStore, cleanCreateAgentServerId, skipsComputer) {
            if (skipsComputer) null else createAgentFormStore?.rememberedCreateRuntime(cleanCreateAgentServerId)
        }
    val runtimeFormV2 = runtimeFormV2Override ?: createAgentFormStore?.runtimeFormV2Access(cleanCreateAgentServerId)
    // No v2 access (no form store or server) means no form can load: say so instead of loading forever.
    val v2Availability =
        if (runtimeFormV2 == null) RuntimeFormV2Availability.Unavailable else runtimeCatalog.runtimeFormV2Availability(runtime)
    val v2Candidate =
        runtimeFormV2 != null &&
            !skipsComputer &&
            availableSelectedMachineId != null &&
            runtime.isNotBlank() &&
            v2Availability != RuntimeFormV2Availability.Unavailable
    var v2LoadAttempt by remember(runtimeFormV2, availableSelectedMachineId, runtime, skipsComputer) { mutableStateOf(0) }
    // A form that went stale mid-session (`form_revision_stale`) is reloaded whole, once; after that it
    // fails like any other load (the retry banner).
    var v2StaleReloads by remember(runtimeFormV2, availableSelectedMachineId, runtime, skipsComputer) { mutableStateOf(0) }
    var v2Load by remember(runtimeFormV2, availableSelectedMachineId, runtime, skipsComputer, v2LoadAttempt) {
        mutableStateOf<RuntimeFormV2DialogLoad>(RuntimeFormV2DialogLoad.Loading)
    }
    val runtimeSection = createAgentRuntimeSection(runtime, v2Availability, v2Load)
    val v2Loaded = (runtimeSection as? CreateAgentRuntimeSection.Ready)?.loaded
    var v2Values by remember(v2Loaded) {
        mutableStateOf(v2Loaded?.let { initialRuntimeFormV2Values(it.form, it.sources) }.orEmpty())
    }
    var v2Touched by remember(v2Loaded) { mutableStateOf(emptySet<String>()) }
    // Re-probed option sources sit on top of the loaded form; render, validate and submit against them.
    val (v2Form, v2SourceRefresh) =
        rememberRuntimeFormV2SourceRefresh(
            runtimeFormV2,
            availableSelectedMachineId,
            v2Loaded,
            onFormStale = {
                if (v2StaleReloads < 1) {
                    v2StaleReloads += 1
                    v2LoadAttempt += 1
                } else {
                    v2Load = RuntimeFormV2DialogLoad.Failed(RUNTIME_FORM_V2_FORM_REVISION_STALE)
                }
            }
        ) { updated, sourceId ->
            v2Values = runtimeFormV2ValuesAfterSourceReload(updated.form, updated.sources, v2Values, sourceId)
        }
    val v2Errors = v2Form?.let { validateRuntimeFormV2(it.form, it.sources, v2Values) }.orEmpty()

    LaunchedEffect(machines, requiredComputer, suggestedComputer, initialComputer, selectedMachineId) {
        if (skipsComputer) return@LaunchedEffect
        if (selectedMachineId == null) {
            selectedMachineId = createAgentInitialMachineSelection(machines, requiredComputer, suggestedComputer, initialComputer)
        }
    }

    // Keeps a choice only while this computer's runtime-options still offer it; otherwise picks the
    // default. No usable runtime leaves the field empty instead of guessing; options still loading
    // (or another computer's) change nothing.
    val cleanSuggestedRuntime = suggestedRuntime?.trim()?.takeIf { it.isNotEmpty() }
    LaunchedEffect(availableSelectedMachineId, runtimeCatalog, supportedRuntimeIds, rememberedRuntime, cleanSuggestedRuntime, skipsComputer) {
        if (skipsComputer) return@LaunchedEffect
        val next =
            createAgentRuntimeForOptions(
                current = runtime,
                machineId = availableSelectedMachineId,
                options = runtimeCatalog,
                supported = supportedRuntimeIds,
                remembered = rememberedRuntime,
                suggested = cleanSuggestedRuntime
            )
        if (next != runtime) runtime = next
    }

    LaunchedEffect(runtimeFormV2, availableSelectedMachineId, runtime, skipsComputer, v2LoadAttempt, v2Candidate) {
        val source = runtimeFormV2 ?: return@LaunchedEffect
        val machineId = availableSelectedMachineId ?: return@LaunchedEffect
        if (!v2Candidate) return@LaunchedEffect
        v2Load = when (val result = source.loader.loadCreate(source.serverId, machineId, runtime)) {
            is DataResult.Content -> RuntimeFormV2DialogLoad.Ready(result.data)
            is DataResult.Error -> RuntimeFormV2DialogLoad.Failed(result.error.code)
            is DataResult.NoPermission -> RuntimeFormV2DialogLoad.Failed(result.blocker.code)
            is DataResult.Empty -> RuntimeFormV2DialogLoad.Failed(result.reason.name)
        }
    }

    val trimmedName = name.trim()
    val inlineNameError = if (trimmedName.isNotEmpty()) validateAgentName(trimmedName) else null
    val localizedInlineNameError = inlineNameError?.let { localizedCreateAgentNameError(trimmedName, it) }
    // Web CreateAgentDialog (slock #15664): an action card can prefill a description over the limit
    // (its own cap is longer); the error says so and Create waits for it to fit.
    val descriptionTooLong = profileDescriptionLength(description.text) > PROFILE_DESCRIPTION_MAX_LENGTH
    // Web `CreateAgentDialog` (slock #16345, #16367): agent cap (Free/AppSumo) holds for every agent;
    // universal seats block an agent only under `seat_v1` (`seatLimit != null && seatLimit.reached`),
    // never under `person_v2` where `seatLimit` is null.
    val agentCapReached = createAgentAtLimit(currentAgentCount, maxAgents)
    val seatLimitReached = !agentCapReached && seatLimit?.reached == true
    val atLimit = agentCapReached || seatLimitReached
    val selectedMachine = machines.firstOrNull { it.id == selectedMachineId }
    val envVarsCopy =
        envVarsCopyOverride
            ?: rememberSameComputerEnvVarCopySource(
                formStore = createAgentFormStore,
                serverId = cleanCreateAgentServerId,
                machineId = availableSelectedMachineId,
                machineName = selectedMachine?.name,
                targetRuntimeId = runtime,
                enabled = !skipsComputer,
            )
    val selectedMachineRuntimeIds =
        normalizeCreateAgentRuntimeIds(
            selectedMachine?.runtimes.orEmpty().filterNot { it == "builtin" } +
                if (builtInRuntimeEnabled) {
                    listOf("builtin")
                } else {
                    emptyList()
                }
        )
    val runtimeAvailabilityError =
        createAgentRuntimeAvailabilityError(
            selectedMachineId = selectedMachine?.id,
            machineRuntimeIds = selectedMachineRuntimeIds,
            external = skipsComputer
        )
    // A card's suggested runtime: never a quiet fallback where this computer cannot run it.
    val suggestedRuntimeUnavailable =
        if (skipsComputer || selectedMachine?.status != "online") {
            null
        } else {
            createAgentSuggestedRuntimeUnavailable(
                suggested = cleanSuggestedRuntime,
                runtime = runtime,
                machineId = availableSelectedMachineId,
                options = runtimeCatalog,
                supported = supportedRuntimeIds,
                computerRuntimes = selectedMachineRuntimeIds
            )
        }
    val selectedMachineBlocked = !skipsComputer &&
        (selectedMachine == null ||
            selectedMachine.status != "online" ||
            (!requiredComputer.isNullOrBlank() && selectedMachine.id != requiredComputer))
    val canSubmit = trimmedName.isNotBlank() &&
        inlineNameError == null &&
        !descriptionTooLong &&
        !atLimit &&
        !submitting &&
        !selectedMachineBlocked &&
        runtimeAvailabilityError == null &&
        (skipsComputer || runtime in supportedRuntimeIds) &&
        (skipsComputer || createAgentRuntimeFormV2SubmitReady(v2Loaded != null, v2Errors.isNotEmpty())) &&
        // An unavailable list still allows the default (no model is sent, nothing to check).
        !(hostedMode && hostedModels == CreateAgentHostedModels.Loading)
    val showMachinePicker = !external
    // The one commit: the footer's Create on a wide window, the phone sheet's head button.
    val commitLabel =
        if (submitting) {
            stringResource(Res.string.agent_create_action_creating)
        } else {
            stringResource(Res.string.agent_create_submit)
        }
    val commit: () -> Unit = {
        createAgentDialogSubmit(
            name = trimmedName,
            description = description.text,
            machineId = requiredComputer?.takeIf { it.isNotBlank() } ?: selectedMachineId,
            external = external,
            runtime = runtime,
            loaded = v2Form ?: v2Loaded,
            values = v2Values,
            hosted = hostedMode,
            hostedModel = createAgentHostedModelToSend(hostedModels, hostedModelPick),
            proposedEnvVars = proposedEnvVars.takeIf { hasProposedEnvVars }
        )?.let(onSubmit)
    }

    RaftUnifiedDialog(
        title =
            if (external) {
                stringResource(Res.string.agent_create_title_external)
            } else {
                stringResource(Res.string.agent_create_title)
            },
        dismissEnabled = !submitting,
        dismissOnOutsideTap = false,
        onDismiss = onDismiss,
        keyboardAvoidanceEnabled = true,
        hostRoute = SharedCreateAgentDialogHostRoute,
        presentation = RaftDialogPresentation.Sheet,
        commitLabel = commitLabel,
        onCommit = commit,
        commitEnabled = canSubmit
    ) {
        // #55 visual parity: no body height cap. The Column fills the viewport, the
        // LazyColumn takes the leftover space and Cancel/Create stay pinned at the bottom
        // (React's `card-brutal p-6` dialog).
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            // Web `stack-y-1`: every field keeps its own message line open (RaftField reserveMessage).
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
                autoJoinChannelName?.takeIf { it.isNotBlank() }?.let { channel ->
                    item(key = "auto-join-channel") {
                        CreateAgentAutoJoinChannel(channel)
                    }
                }
                draftHint?.takeIf { it.isNotBlank() }?.let { hint ->
                    item(key = "draft-hint") {
                        CreateAgentDraftHint(hint)
                    }
                }
                if (atLimit) {
                    item(key = "plan-limit") {
                        PlanLimitBanner(
                            currentAgentCount = currentAgentCount,
                            maxAgents = maxAgents,
                            planLabel = limitPlanLabel,
                            seatLimit = seatLimit?.takeIf { seatLimitReached },
                            onViewPlan = onViewPlan,
                            onUpgrade =
                                when {
                                    onUpgradeWithAgentName != null -> ({ onUpgradeWithAgentName(name.trim().take(60)) })
                                    else -> onUpgrade
                                },
                        )
                    }
                } else if (seatLimit != null) {
                    item(key = "seat-usage") {
                        RaftText(
                            text =
                                stringResource(
                                    Res.string.agent_create_seat_usage,
                                    formatSeatTenths(seatLimit.usedTenths),
                                    seatLimit.seatCapacity,
                                ),
                            style = RaftTheme.typography.footnote,
                            color = RaftTheme.colors.foregroundMuted,
                        )
                    }
                }
                errorMessage?.takeIf { !atLimit && it.isNotBlank() }?.let { error ->
                    item(key = "submit-error") {
                        RaftUiBanner(
                            status = RaftUiBannerStatus.Warning,
                            description = error,
                            size = RaftUiBannerSize.Medium,
                            action = onViewPlan?.takeIf { errorOffersViewPlan }?.let { open -> { RaftViewPlanButton(onClick = open) } }
                        )
                    }
                }
                if (showMachinePicker) {
                    item(key = "machine-picker") {
                        MachinePicker(
                            machines = machines,
                            machinesLoading = machinesLoading || (hostedAvailable == null && machines.isEmpty()),
                            machinesErrorMessage = machinesErrorMessage,
                            onRetry = onMachinesRetry,
                            selectedMachineId = selectedMachineId,
                            requiredComputer = requiredComputer,
                            suggestedComputer = suggestedComputer,
                            enabled = !submitting,
                            runtimeId = runtime,
                            usageAccess = runtimeUsageAccess,
                            hostedOption =
                                if (hostedAvailable == true) stringResource(Res.string.agent_create_hosted_option) else null,
                            hostedSelected = hostedMode,
                            onSelectHosted = {
                                focusManager.clearFocus(force = true)
                                hostedSelected = true
                            },
                            onSelect = { id ->
                                if (requiredComputer.isNullOrBlank() || id == requiredComputer) {
                                    focusManager.clearFocus(force = true)
                                    hostedSelected = false
                                    if (id != selectedMachineId) {
                                        selectedMachineId = id
                                        // Another computer: its own runtime-options decide (web clears it too).
                                        runtime = ""
                                    }
                                }
                            }
                        )
                    }
                }
                item(key = "name") {
                    RaftField(
                        label = stringResource(Res.string.agent_create_label_name),
                        modifier = Modifier.fillMaxWidth(),
                        error = inlineNameError?.let { localizedInlineNameError ?: it },
                        enabled = !submitting,
                        required = true,
                        reserveMessage = true,
                        labelTrailing = {
                            CreateAgentRandomNameButton(
                                enabled = !submitting,
                                onClick = { name = suggestAgentName(listOf(name) + takenAgentNames) }
                            )
                        }
                    ) {
                        val editor =
                            rememberRaftDesignSystemEditor(
                                keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                                keyboardActions = KeyboardActions.Default,
                                keyboardHeightChange = {},
                                focusRequester = remember { FocusRequester() },
                                // Web: the form opens without the keyboard over it.
                                autoFocus = false,
                                onFocus = {},
                                onBlur = {}
                            )
                        RaftTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth().then(editor.modifier),
                            placeholder = stringResource(Res.string.agent_create_placeholder_name),
                            keyboardOptions = editor.keyboardOptions
                        )
                    }
                }
                item(key = "description") {
                    val descriptionError =
                        if (descriptionTooLong) {
                            Res.string.agent_create_error_description_length
                                .format(listOf(PROFILE_DESCRIPTION_MAX_LENGTH))
                                .localized()
                        } else {
                            null
                        }
                    RaftField(
                        label = stringResource(Res.string.agent_create_label_description),
                        modifier = Modifier.fillMaxWidth(),
                        description = stringResource(Res.string.agent_create_description_hint),
                        error = descriptionError,
                        enabled = !submitting,
                        optional = true,
                        reserveMessage = true,
                        counter = "${description.text.length}/$PROFILE_DESCRIPTION_MAX_LENGTH"
                    ) {
                        // Typing stops at the limit (web `maxLength`).
                        RaftTextarea(
                            value = description,
                            onValueChange = { description = it.coerceTextLength(PROFILE_DESCRIPTION_MAX_LENGTH) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = stringResource(Res.string.agent_create_placeholder_description),
                            // Web `rows={3}`: three 24dp lines, 10dp above and below, the 1.5dp stroke; then it scrolls.
                            minHeight = CreateAgentDescriptionHeight,
                            maxHeight = CreateAgentDescriptionHeight,
                            inputModifier = rememberRaftTextareaEditorModifier()
                        )
                    }
                }
                if (hostedMode && createAgentHostedModelFieldShown(hostedModels)) {
                    item(key = "hosted-model") {
                        CreateAgentHostedModelField(
                            models = hostedModels,
                            pick = hostedModelPick,
                            enabled = !submitting,
                            onPick = { hostedModelPick = it },
                            onReload = { hostedModelsAttempt += 1 }
                        )
                    }
                }
                if (!skipsComputer && availableSelectedMachineId != null) {
                    when (runtimeSection) {
                        is CreateAgentRuntimeSection.Failed ->
                            item(key = "runtime-form-v2-failure") {
                                RaftUiBanner(
                                    status = RaftUiBannerStatus.Warning,
                                    description =
                                        Res.string.runtime_form_v2_load_failed.format(listOf(runtimeSection.code)).localized(),
                                    size = RaftUiBannerSize.Medium,
                                    action = {
                                        RaftButton(
                                            text = stringResource(Res.string.common_retry),
                                            onClick = {
                                                v2StaleReloads = 0
                                                v2LoadAttempt += 1
                                                runtimeCatalogAttempt += 1
                                            },
                                            modifier = Modifier.widthIn(min = 72.dp),
                                            variant = RaftButtonVariant.Default,
                                            size = RaftButtonSize.Md,
                                            enabled = !submitting,
                                            leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) }
                                        )
                                    }
                                )
                            }
                        CreateAgentRuntimeSection.Unavailable ->
                            item(key = "runtime-form-v2-unavailable") {
                                RaftUiBanner(
                                    status = RaftUiBannerStatus.Warning,
                                    description = stringResource(Res.string.runtime_form_v2_unavailable),
                                    size = RaftUiBannerSize.Medium
                                )
                            }
                        else -> Unit
                    }
                }
                if (!skipsComputer) {
                    item(key = "runtime") {
                        RuntimeFormV2RuntimeSection(
                            runtime = runtime,
                            catalog = serverRuntimeCatalog,
                            options = runtimeOptionRows,
                            quotaMachine = selectedQuotaMachine,
                            onRuntimeChange = { newRuntime -> runtime = newRuntime },
                            machineRuntimeIds = selectedMachineRuntimeIds,
                            runtimeAvailabilityError = runtimeAvailabilityError,
                            enabled = !submitting
                        ) {
                            CreateAgentSuggestedRuntimeNotice(
                                suggestedRuntime = cleanSuggestedRuntime,
                                runtime = runtime,
                                unavailable = suggestedRuntimeUnavailable,
                                runtimeName = cleanSuggestedRuntime?.let { id ->
                                    (serverRuntimeCatalog.entry(id) ?: BundledRuntimeCatalog.entry(id))
                                        ?.displayName?.takeIf { it.isNotBlank() } ?: id
                                }.orEmpty(),
                                computerName = selectedMachine?.name.orEmpty()
                            )
                            if (runtimeSection == CreateAgentRuntimeSection.Loading && availableSelectedMachineId != null) {
                                Text(
                                    text = stringResource(Res.string.agent_create_state_computers_loading),
                                    style = RaftType.Caption,
                                    color = RaftColor.TextMutedGray
                                )
                            } else if (v2Loaded != null) {
                                RuntimeFormV2Fields(
                                    loaded = v2Form ?: v2Loaded,
                                    values = v2Values,
                                    onChange = { key, value ->
                                        val current = v2Form ?: v2Loaded
                                        v2Values = applyRuntimeFormV2Change(current.form, current.sources, v2Values, key, value)
                                        v2Touched = v2Touched + key
                                    },
                                    errors = runtimeFormV2ShownErrors(v2Errors, v2Touched + errorFieldKeys),
                                    serverErrorKeys = errorFieldKeys,
                                    serverMessage = null,
                                    editing = false,
                                    advancedOpen = advancedOpen,
                                    onAdvancedOpenChange = { advancedOpen = it },
                                    enabled = !submitting,
                                    sourceRefresh = v2SourceRefresh,
                                    envVarsCopy = envVarsCopy,
                                    upgradeTarget = modelUpgradeTarget?.copy(runtimeId = (v2Form ?: v2Loaded).form.runtimeId.ifBlank { modelUpgradeTarget.runtimeId }),
                                )
                            }
                        }
                    }
                    if (hasProposedEnvVars) {
                        item(key = "proposed-env-vars") {
                            CreateAgentProposedEnvVarsField(
                                proposal = proposedEnvVars,
                                onChange = { proposedEnvVars = it },
                                proposer = suggestedEnvVarsProposer,
                                enabled = !submitting
                            )
                        }
                    }
                }
            }
            RaftDialogFooter {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    enabled = !submitting,
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg
                )
                RaftButton(
                    text = commitLabel,
                    enabled = canSubmit,
                    modifier = Modifier.widthIn(min = 126.dp),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    onClick = commit
                )
            }
        }
    }

/**
 * Web `HostedModelChoice`: an offered model or the provider's default, read-only while an
 * administrator decides, and a failed list that still lets the create go on the default.
 */
@Composable
private fun CreateAgentHostedModelField(
    models: CreateAgentHostedModels,
    pick: String?,
    enabled: Boolean,
    onPick: (String?) -> Unit,
    onReload: () -> Unit,
) {
    val label = stringResource(Res.string.agent_create_hosted_model_label)
    RaftField(label = label, modifier = Modifier.fillMaxWidth(), enabled = enabled, reserveMessage = true) {
        when (models) {
            CreateAgentHostedModels.Loading ->
                Text(
                    text = stringResource(Res.string.agent_create_state_computers_loading),
                    style = RaftType.Caption,
                    color = RaftColor.TextMutedGray
                )
            CreateAgentHostedModels.Failed ->
                RaftUiBanner(
                    status = RaftUiBannerStatus.Warning,
                    description = stringResource(Res.string.agent_create_hosted_model_load_failed),
                    size = RaftUiBannerSize.Medium,
                    action = {
                        RaftButton(
                            text = stringResource(Res.string.common_retry),
                            onClick = onReload,
                            modifier = Modifier.widthIn(min = 72.dp),
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            enabled = enabled,
                            leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) }
                        )
                    }
                )
            is CreateAgentHostedModels.Ready -> {
                val view = models.models
                if (view.locked) {
                    Column {
                        Text(
                            text = view.defaultLabel ?: view.defaultModel.orEmpty(),
                            style = RaftType.Body,
                            color = RaftColor.TextBlack
                        )
                        Text(
                            text = stringResource(Res.string.agent_create_hosted_model_locked),
                            style = RaftType.Caption,
                            color = RaftColor.TextMutedGray
                        )
                    }
                } else {
                    val defaultLabel =
                        view.defaultLabel?.let { stringResource(Res.string.agent_create_hosted_model_default, it) }
                            ?: stringResource(Res.string.agent_create_hosted_model_default_unnamed)
                    val noMatches = stringResource(Res.string.runtime_picker_no_matches, "\u0001")
                    CompositionLocalProvider(LocalRaftFieldState provides RaftFieldState(enabled = enabled, label = label, inField = true)) {
                        RaftCombobox(
                            value = pick.orEmpty(),
                            options = createAgentHostedModelOptions(view, defaultLabel),
                            onValueChange = { id -> onPick(id.takeIf { it.isNotEmpty() }) },
                            emptyText = { query -> noMatches.replace("\u0001", query) },
                            placeholder = label,
                            title = label,
                            searchPlaceholder = stringResource(Res.string.runtime_picker_search),
                            enabled = enabled,
                            label = label
                        )
                    }
                }
            }
        }
    }
}

/**
 * The provider's default first, under the empty value (web `emptyOption`); then the offered models,
 * each with its id under its name (web RuntimeChoiceCombobox).
 */
internal fun createAgentHostedModelOptions(models: HostedRuntimeModels, defaultLabel: String): List<RaftComboboxOption<String>> =
    listOf(RaftComboboxOption("", defaultLabel)) +
        models.models.map { RaftComboboxOption(it.id, it.label, identifier = runtimeChoiceIdentifier(it.id, it.label)) }

/**
 * Web CreateAgentDialog's needs-computer gate (`!isExternalMode && machines.length === 0`): a
 * managed agent with the computer list loaded, without error, and empty. While loading or after a
 * failed load the form keeps its own picker states. The caller skips it where a cloud agent is offered.
 */
internal fun createAgentNeedsComputer(
    external: Boolean,
    machines: List<SharedCreateAgentMachineOption>,
    machinesLoading: Boolean,
    machinesErrorMessage: String?,
): Boolean = !external && !machinesLoading && machinesErrorMessage.isNullOrBlank() && machines.isEmpty()

/**
 * Web `create-agent-needs-computer`: a plain centred DialogCard (not the phone sheet) with an
 * info banner and Cancel + Add Computer. Add Computer closes this dialog, then opens the flow.
 */
@Composable
private fun CreateAgentNeedsComputerDialog(
    onAddComputer: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    RaftUnifiedDialog(
        title = stringResource(Res.string.agent_create_title),
        onDismiss = onDismiss,
        hostRoute = SharedCreateAgentDialogHostRoute,
        presentation = RaftDialogPresentation.Centered,
        // DialogCard's banner then `mt-5` footer.
        contentSpacing = 20.dp,
    ) {
        RaftBanner(
            title = stringResource(Res.string.agent_create_needs_computer_title),
            description = stringResource(Res.string.agent_create_needs_computer_description),
            status = RaftBannerStatus.Info,
            // raft-ui Banner draws no icon unless asked; the web dialog does not ask.
            showIcon = false,
        )
        RaftDialogFooter {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg
            )
            onAddComputer?.let { addComputer ->
                RaftButton(
                    text = stringResource(Res.string.agent_create_action_add_computer),
                    onClick = {
                        onDismiss()
                        addComputer()
                    },
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg
                )
            }
        }
    }
}

@Composable
private fun localizedCreateAgentNameError(name: String, fallback: String): String =
    when {
        " is reserved." in fallback ->
            Res.string.agent_create_error_name_reserved
                .format(listOf(name.removePrefix("@").lowercase()))
                .localized()
        "at least" in fallback -> stringResource(Res.string.agent_create_error_name_min)
        "at most" in fallback -> stringResource(Res.string.agent_create_error_name_max)
        "must start with a letter" in fallback -> stringResource(Res.string.agent_create_error_name_characters)
        "one script only" in fallback -> stringResource(Res.string.agent_create_error_name_mixed_script)
        else -> fallback
    }

@Composable
private fun PlanLimitBanner(
    currentAgentCount: Int,
    maxAgents: Int,
    planLabel: String?,
    seatLimit: CreateAgentSeatState? = null,
    onViewPlan: (() -> Unit)? = null,
    onUpgrade: (() -> Unit)? = null
) {
    // iOS view-only Billing: the limit stated, with no "Upgrade for more" (raftUpsellCopy), and
    // "View your plan" ([onViewPlan]) to the view-only Plan & Billing page.
    val offersUpgrade = settingsBillingOffersUpgrade()
    val viewPlan: (@Composable RowScope.() -> Unit)? =
        if (onViewPlan != null && !offersUpgrade) ({ RaftViewPlanButton(onClick = onViewPlan) }) else null
    val description =
        if (seatLimit != null) {
            val summary =
                Res.string.agent_create_error_seat_limit_plan
                    .format(listOf(formatSeatTenths(seatLimit.usedTenths), seatLimit.seatCapacity, planLabel.orEmpty().ifBlank { "Pro" }))
                    .localized()
            val upgrade = raftKeepTogether(stringResource(raftUpsellCopy(Res.string.agent_create_action_upgrade_for_more, offersUpgrade)))
            val upgradeLink = onUpgrade?.takeIf { offersUpgrade && viewPlan == null }
            buildAnnotatedString {
                append(summary)
                append(" ")
                if (upgradeLink != null) {
                    appendRaftTextLink(upgrade, onClick = upgradeLink)
                } else {
                    append(upgrade)
                }
            }
        } else if (planLabel.isNullOrBlank()) {
            AnnotatedString(
                raftUpsellCopy(Res.string.agent_create_error_limit, offersUpgrade)
                    .format(listOf(currentAgentCount, maxAgents))
                    .localized()
            )
        } else {
            val summary =
                Res.string.agent_create_error_limit_plan
                    .format(listOf(currentAgentCount, maxAgents, planLabel))
                    .localized()
            // Web `<upgrade>` is one unbreakable run: on a narrow line the whole phrase wraps, never its middle.
            val upgrade = raftKeepTogether(stringResource(raftUpsellCopy(Res.string.agent_create_action_upgrade_for_more, offersUpgrade)))
            // raft-ui: an action inside a Banner's text is a strong TextLink (web CreateAgentDialog
            // `<upgrade>`); only the upgrade wording links, never the neutral iOS statement.
            val upgradeLink = onUpgrade?.takeIf { offersUpgrade && viewPlan == null }
            buildAnnotatedString {
                append(summary)
                append(" ")
                if (upgradeLink != null) {
                    appendRaftTextLink(upgrade, onClick = upgradeLink)
                } else {
                    append(upgrade)
                }
            }
        }
    RaftBanner(
        description = description,
        status = RaftBannerStatus.Warning,
        // Web `<Banner status="warning">` draws no icon here.
        showIcon = false,
        action = viewPlan
    )
}

/**
 * The action card's draft note above the form: web AddMembersDialog's context line (`border-l-2
 * border-line-seam pl-2.5 text-callout text-foreground-muted`), context rather than a warning.
 */
/** One more agent would pass [maxAgents] (-1 is no cap): the banner shows and Create is disabled. */
internal fun createAgentAtLimit(currentAgentCount: Int, maxAgents: Int): Boolean =
    maxAgents >= 0 && currentAgentCount >= maxAgents

/**
 * Web `create-agent-channel-context`: a plain line on the info wash, not a banner (it is context,
 * not a warning), saying the new agent joins the channel; it stays until the create is sent.
 */
@Composable
internal fun CreateAgentAutoJoinChannel(channelName: String) {
    val colors = RaftTheme.colors
    RaftText(
        text = stringResource(Res.string.agent_create_auto_join_channel, "#${channelName.removePrefix("#")}"),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.infoSoft, RoundedCornerShape(RaftRadius.Container))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = colors.foreground
    )
}

@Composable
private fun CreateAgentDraftHint(hint: String) {
    val colors = RaftTheme.colors
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(colors.lineSeam))
        RaftText(
            text = hint,
            modifier = Modifier.padding(start = 10.dp),
            style = RaftTheme.typography.callout,
            color = colors.foregroundMuted
        )
    }
}

@Composable
internal fun MachinePicker(
    machines: List<SharedCreateAgentMachineOption>,
    machinesLoading: Boolean,
    machinesErrorMessage: String?,
    onRetry: () -> Unit,
    selectedMachineId: String?,
    requiredComputer: String?,
    suggestedComputer: String?,
    enabled: Boolean,
    runtimeId: String,
    usageAccess: MembersRuntimeAccountUsageAccess?,
    /** The cloud agent's row after the computers (web `computerOptions`); null where it isn't offered. */
    hostedOption: String? = null,
    hostedSelected: Boolean = false,
    onSelectHosted: () -> Unit = {},
    onSelect: (String) -> Unit
) {
    val selectedComputerNote =
        if (hostedSelected) {
            null
        } else {
            machines.firstOrNull { it.id == selectedMachineId }?.description?.trim()?.takeIf { it.isNotEmpty() }
        }
    val fieldDescription =
        when {
            hostedSelected -> stringResource(Res.string.agent_create_hosted_hint)
            !requiredComputer.isNullOrBlank() ->
                Res.string.agent_create_hint_required_computer.format(listOf(requiredComputer)).localized()
            !suggestedComputer.isNullOrBlank() ->
                Res.string.agent_create_hint_suggested_computer.format(listOf(suggestedComputer)).localized()
            else -> null
        }
    Column(modifier = Modifier.fillMaxWidth()) {
        RaftField(
            label = stringResource(Res.string.agent_create_label_computer),
            modifier = Modifier.fillMaxWidth(),
            description = fieldDescription,
            enabled = enabled,
            required = true,
            reserveMessage = selectedComputerNote == null
        ) {
            when {
                machinesLoading -> Text(
                    text = stringResource(Res.string.agent_create_state_computers_loading),
                    style = RaftType.Caption,
                    color = RaftColor.TextMutedGray
                )
                !machinesErrorMessage.isNullOrBlank() ->
                    RaftBanner(
                        description = machinesErrorMessage,
                        status = RaftBannerStatus.Warning,
                        action = {
                            RaftButton(
                                text = stringResource(Res.string.common_retry),
                                onClick = onRetry,
                                variant = RaftButtonVariant.Default,
                                size = RaftButtonSize.Md,
                                enabled = enabled,
                                leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) }
                            )
                        }
                    )
                machines.isEmpty() && hostedOption == null ->
                    RaftBanner(
                        description = stringResource(Res.string.agent_create_state_computers_empty),
                        status = RaftBannerStatus.Info
                    )
                else -> {
                    val machineItems =
                        createAgentMachineDropdownItems(
                            machines = machines,
                            requiredComputer = requiredComputer,
                            offlineSuffix = stringResource(Res.string.agent_create_suffix_offline)
                        )
                    // Web CreateAgentDialog: a select at any count (artin 2026-11-02: no radios), raft-ui
                    // ComboboxSelect — the list under the trigger up to ten, a searchable sheet above.
                    // Each computer carries its owner description and the chosen runtime's quota line.
                    val quotaLines = rememberCreateAgentMachineQuotaLines(usageAccess, machines, runtimeId)
                    val computerLabel = stringResource(Res.string.agent_create_label_computer)
                    val noMatches = stringResource(Res.string.runtime_picker_no_matches, "\u0001")
                    CompositionLocalProvider(LocalRaftFieldState provides RaftFieldState(enabled = enabled, label = computerLabel, required = true, inField = true)) {
                        RaftCombobox(
                            value = if (hostedSelected) CREATE_AGENT_HOSTED_CHOICE else selectedMachineId,
                            options =
                                createAgentMachineChoiceOptions(machineItems, quotaLines) +
                                    listOfNotNull(hostedOption?.let { RaftComboboxOption(CREATE_AGENT_HOSTED_CHOICE, it) }),
                            onValueChange = { id -> if (id == CREATE_AGENT_HOSTED_CHOICE) onSelectHosted() else onSelect(id) },
                            emptyText = { query -> noMatches.replace("\u0001", query) },
                            placeholder = stringResource(Res.string.agent_create_placeholder_select_computer),
                            title = computerLabel,
                            searchPlaceholder = stringResource(Res.string.runtime_picker_search),
                            enabled = enabled,
                            label = computerLabel
                        )
                    }
                }
            }
        }
        // Web CreateAgentDialog `ComputerNoteHint` (#16578, #triage:#620): show the chosen computer's
        // owner note whole under the closed Computer field (wrapping, never truncated), for ordinary,
        // suggested, and required picks.
        if (selectedComputerNote != null) {
            RaftText(
                text = selectedComputerNote,
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundHint,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = if (fieldDescription == null) 6.dp else 2.dp)
                        .testTag("create-agent-selected-computer-description")
            )
        }
    }
}

/** Web `rows={3}` of the description: 3 x 24dp lines, 10dp above and below, the 1.5dp stroke. */
private val CreateAgentDescriptionHeight = 95.dp

/**
 * Under the runtime picker, for a card's suggested runtime (web `SuggestedRuntimeNotice`, slock
 * #14976): only where the chosen computer cannot run it (and nothing is picked), why: the computer
 * lacks it, or this server does not offer it for new agents. A suggestion that was preselected
 * needs no note, so the dialog no longer says who suggested it.
 */
@Composable
private fun CreateAgentSuggestedRuntimeNotice(
    suggestedRuntime: String?,
    runtime: String,
    unavailable: CreateAgentSuggestedRuntimeUnavailable?,
    runtimeName: String,
    computerName: String
) {
    val cause = createAgentSuggestedRuntimeNoticeCause(suggestedRuntime, runtime, unavailable) ?: return
    Text(
        text =
            when (cause) {
                CreateAgentSuggestedRuntimeUnavailable.NotAdmitted ->
                    Res.string.agent_create_suggested_runtime_not_admitted.format(listOf(runtimeName)).localized()
                CreateAgentSuggestedRuntimeUnavailable.NotOnComputer ->
                    Res.string.agent_create_suggested_runtime_unavailable.format(listOf(runtimeName, computerName)).localized()
            },
        style = RaftType.Caption,
        color = RaftColor.WarningStrong
    )
}

/**
 * The "Advanced" disclosure as web RuntimeDisclosureToggle (a SectionEyebrow button): `text-footnote`
 * bold in `foreground-hint`, a 14dp chevron 8dp before it.
 */
@Composable
internal fun AdvancedToggleButton(open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .raftClickable(onClick = onToggle, shape = RoundedCornerShape(RaftRadius.Control))
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RaftLucideIcon(
            if (open) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
            modifier = Modifier.size(14.dp),
            color = raftArgbHex(RaftColor.ForegroundHint)
        )
        Text(
            text = stringResource(Res.string.agent_create_label_advanced),
            style = RaftType.Footnote.withRaftFontWeight(FontWeight.Bold),
            color = RaftColor.ForegroundHint
        )
    }
}

/**
 * Dice button beside the Name label that rolls another suggested name (react
 * `create-agent-random-name`: ghost, icon-xs, Dices 12px). Ghost = no fill, border or
 * shadow, so it reads as a label accessory rather than a primary control.
 */
@Composable
private fun CreateAgentRandomNameButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    RaftIconButton(
        onClick = onClick,
        tooltip = stringResource(Res.string.agent_create_random_name),
        variant = RaftButtonVariant.Ghost,
        size = RaftIconButtonSize.Md,
        enabled = enabled
    ) {
        RaftSlotIcon(RaftLucideIconName.Dices)
    }
}

/** The app editor behaviour (IME action, tap-outside dismiss) for one env-var field. */
@Composable
private fun rememberCreateAgentEnvVarEditor(keyboardOptions: KeyboardOptions): RaftDesignSystemEditor =
    rememberRaftDesignSystemEditor(
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions.Default,
        keyboardHeightChange = {},
        focusRequester = remember { FocusRequester() },
        autoFocus = false,
        onFocus = {},
        onBlur = {}
    )

private data class EnvVarsCopyFeedback(
    val agentName: String,
    val addedCount: Int,
    val keptKeys: List<String>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EnvVarsEditor(
    entries: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
    enabled: Boolean,
    label: String? = null,
    required: Boolean = false,
    /** Help under the label, above the rows (web RuntimeEnvVarsField). */
    hint: (@Composable () -> Unit)? = null,
    /** #16606: same-Computer agents whose Advanced env vars can be appended into this field. */
    copySource: RuntimeEnvVarsCopySource? = null
) {
    val currentResetKey = copySource?.resetKey
    var prevResetKey by remember { mutableStateOf(currentResetKey) }
    var copyFeedback by remember { mutableStateOf<EnvVarsCopyFeedback?>(null) }
    if (prevResetKey != currentResetKey) {
        prevResetKey = currentResetKey
        if (copyFeedback != null) copyFeedback = null
    }
    val activeFeedback = if (prevResetKey == currentResetKey) copyFeedback else null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val groupLabel = label ?: stringResource(Res.string.agent_create_label_environment_variables)
        // A group of key/value fields: no one control to say "required", so the group's label says it.
        val spokenLabel = raftFieldSpokenName(groupLabel, required)
        RaftFieldLabel(
            text = groupLabel,
            modifier = if (required && spokenLabel != null) Modifier.semantics { contentDescription = spokenLabel } else Modifier,
            optional = !required
        )
        hint?.invoke()
        entries.forEachIndexed { idx, (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftField(
                    label = stringResource(Res.string.agent_create_label_key),
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                ) {
                    val editor = rememberCreateAgentEnvVarEditor(RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions)
                    RaftTextField(
                        value = key,
                        onValueChange = { newKey ->
                            onChange(entries.toMutableList().also { it[idx] = newKey to value })
                        },
                        modifier = Modifier.fillMaxWidth().then(editor.modifier),
                        placeholder = "FOO",
                        keyboardOptions = editor.keyboardOptions
                    )
                }
                RaftField(
                    label = stringResource(Res.string.agent_create_label_value),
                    modifier = Modifier.weight(1.5f),
                    enabled = enabled
                ) {
                    val editor = rememberCreateAgentEnvVarEditor(KeyboardOptions.Default)
                    RaftTextField(
                        value = value,
                        onValueChange = { newValue ->
                            onChange(entries.toMutableList().also { it[idx] = key to newValue })
                        },
                        modifier = Modifier.fillMaxWidth().then(editor.modifier),
                        placeholder = "bar",
                        keyboardOptions = editor.keyboardOptions
                    )
                }
                Spacer(Modifier.width(4.dp))
                val removeLabel = stringResource(Res.string.common_remove)
                Box(
                    modifier =
                        Modifier
                            .semantics { contentDescription = removeLabel }
                            .raftClickable(
                                enabled = enabled,
                                onClickLabel = removeLabel,
                                onClick = { onChange(entries.toMutableList().also { it.removeAt(idx) }) },
                                shape = RoundedCornerShape(RaftRadius.Control),
                            )
                            .padding(horizontal = 8.dp)
                ) {
                    // Icon affordances are Lucide, never text glyphs (task #494).
                    RaftLucideIcon(
                        name = RaftLucideIconName.X,
                        color = if (enabled) RaftColor.AccentRedArgb else RaftColor.TextMutedGrayArgb,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        val hasCopyCandidates = copySource != null && copySource.candidates.isNotEmpty()
        val addButton: @Composable () -> Unit = {
            Box(
                modifier =
                    Modifier
                        .raftClickable(
                            enabled = enabled,
                            onClick = { onChange(entries + ("" to "")) },
                            shape = RoundedCornerShape(RaftRadius.Control),
                        )
                        .padding(vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(Res.string.agent_create_action_add_env_var),
                    style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
                    color = if (enabled) RaftColor.TextBlack else RaftColor.TextMutedGray
                )
            }
        }
        if (hasCopyCandidates && copySource != null) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                addButton()
                CopyEnvVarsFromAgentMenu(
                    candidates = copySource.candidates,
                    computerName = copySource.computerName,
                    enabled = enabled,
                    onPick = { candidate ->
                        val merged =
                            mergeCopiedEnvVars(
                                current = entries.map { (k, v) -> CopyableEnvVarEntry(k, v) },
                                source = candidate.entries,
                            )
                        if (merged.addedCount > 0) {
                            onChange(merged.next.map { it.key to it.value })
                        }
                        copyFeedback =
                            EnvVarsCopyFeedback(
                                agentName = candidate.name,
                                addedCount = merged.addedCount,
                                keptKeys = merged.keptKeys,
                            )
                    },
                )
            }
        } else {
            addButton()
        }
        if (activeFeedback != null) {
            RaftText(
                text =
                    copyEnvVarsFeedbackText(
                        agentName = activeFeedback.agentName,
                        addedCount = activeFeedback.addedCount,
                        keptKeys = activeFeedback.keptKeys,
                    ),
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundMuted,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("env-vars-copy-status")
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
