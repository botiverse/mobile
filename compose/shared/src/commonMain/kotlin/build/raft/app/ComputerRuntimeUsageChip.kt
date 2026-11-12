package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageRefreshLoad
import build.raft.app.data.RuntimeAccountUsageRefreshReason
import build.raft.app.data.RuntimeAccountUsageRefreshState
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.RuntimeAuthSignedInProvider
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.RuntimeLimitResetLoad
import build.raft.app.data.RuntimeLimitResetResult
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.settlesAttempt
import build.raft.app.model.RaftTimeContext
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftMeter
import build.raft.designsystem.components.RaftMeterSize
import build.raft.designsystem.components.RaftPreviewCard
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSeparatorOrientation
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSheetSurface
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.rememberRaftPreviewCardState
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Web `RuntimeAccountUsageGateChip` on the computer detail's "Detected Runtimes" (artin task
 * #18): each detected runtime that reports account usage (Claude Code, Codex, Grok, Kimi) is a
 * tappable soft badge with a health pixel; a tap opens a bottom sheet with the account's usage
 * windows as meters and a Refresh. Every other chip is drawn by [plain], as before.
 *
 * [enabled] is web `canViewMachineRuntimeAccountUsage` (the attaching person, the creator or
 * `editMachines`, which is the page's `canEditMachine`); the server still answers 403 to anyone
 * else, which turns the chip back into a plain label.
 */
@Composable
internal fun ComputerRuntimeUsageChips(
    chips: List<RaftComputerRuntimeChip>,
    machineId: String,
    enabled: Boolean,
    /** The machine's `runtimeVersions` (web `MachineRuntimeChips`): the sheet's "Version …" line. */
    runtimeVersions: Map<String, String> = emptyMap(),
    /** The Computer's name and its agents, for the sign-in and sign-out dialogs. */
    computerName: String? = null,
    agents: List<SettingsComputerAgentSnapshot> = emptyList(),
    plain: @Composable (RaftComputerRuntimeChip) -> Unit,
) {
    fun hasCard(chip: RaftComputerRuntimeChip): Boolean =
        chip.availability == RaftComputerRuntimeAvailability.Detected &&
            (RuntimeAccountUsageProvider.forRuntime(chip.id) != null || computerRuntimeCardAuthRuntime(chip.id) != null)
    val usable = enabled && chips.any(::hasCard)
    val access = if (usable) LocalMembersRuntimeAccountUsageAccess.current ?: rememberShellRuntimeAccountUsageAccess() else null
    chips.forEach { chip ->
        if (usable && hasCard(chip)) {
            val experimental = if (chip.experimental) stringResource(Res.string.runtime_suffix_experimental) else ""
            ComputerRuntimeUsageChip(
                label = chip.name + experimental,
                runtimeId = chip.id,
                runtimeName = chip.name,
                machineId = machineId,
                key = membersRuntimeAccountUsageKey(access, chip.id, machineId),
                access = access,
                runtimeVersion = runtimeVersions[chip.id],
                computerName = computerName,
                agents = agents,
                plain = { plain(chip) },
            )
        } else {
            plain(chip)
        }
    }
}

/**
 * The sign-in half of one runtime card (web `useRuntimeAuthView` + the card's dialogs): null when the
 * runtime has no sign-in from here, the page offers none ([MembersRuntimeAccountUsageAccess.auth]) or
 * the viewer is not known yet. [readNow]: read the Computer's sign-in state at once (a chip's orange
 * "not signed in"), else only when its card opens. Follows `machine:runtime_auth:updated` and this
 * person's login pushes while composed; a running login ends when it leaves.
 */
@Composable
internal fun rememberComputerRuntimeAuthController(
    access: MembersRuntimeAccountUsageAccess?,
    machineId: String,
    raftRuntimeId: String,
    readNow: Boolean,
): ComputerRuntimeAuthController? {
    val runtime = computerRuntimeCardAuthRuntime(raftRuntimeId)
    val host = access?.auth
    val owner = access?.owner
    val serverId = access?.serverId?.trim()?.takeIf { it.isNotEmpty() }
    val scope = rememberCoroutineScope()
    val controller =
        remember(runtime, host, owner, serverId, machineId, access?.store) {
            if (runtime == null || host == null || owner == null || serverId == null || machineId.isBlank()) {
                null
            } else {
                ComputerRuntimeAuthController(
                    serverId = serverId,
                    machineId = machineId,
                    raftRuntimeId = raftRuntimeId,
                    runtime = runtime,
                    ops = StoreComputerRuntimeAuthOps(access.store, owner, serverId, machineId),
                    scope = scope,
                    log = access.log,
                    onIdentityChanged = access.onIdentityChanged,
                )
            }
        }
    val realtime = host?.realtime
    DisposableEffect(controller, realtime) {
        if (controller == null) return@DisposableEffect onDispose {}
        val sink = RealtimeGuardedActiveThreadSink { _, event -> controller.onRealtime(event) }
        realtime?.registerGuardedActiveThreadSink(sink)
        onDispose {
            realtime?.unregisterGuardedActiveThreadSink(sink)
            controller.dispose()
        }
    }
    LaunchedEffect(controller, readNow) { if (readNow) controller?.read() }
    return controller
}

/**
 * The shell's usage store, the signed-in owner and the current server, for a page that does
 * not provide [LocalMembersRuntimeAccountUsageAccess] itself. The owner and server are read
 * again on every auth event (sign-out, account or server switch), and cleared at once first.
 */
@Composable
internal fun rememberShellRuntimeAccountUsageAccess(): MembersRuntimeAccountUsageAccess {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val storeScope =
        remember(pager.pageData.params.toString(), pager.pagerId) {
            pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
        }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val store = remember(sharedStores, storeScope) { sharedStores.runtimeAccountUsageStore(storeScope) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val tracker = remember(sharedStores, storeScope) { MembersRuntimeAccountUsageOwnerTracker { sharedStores.computersOwner(storeScope) } }
    var serverId by remember(authStore) { mutableStateOf<String?>(null) }
    LaunchedEffect(tracker, authStore) {
        suspend fun read() {
            serverId = authStore.readSession()?.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
            tracker.refresh()
        }
        read()
        (authStore as? RaftAuthSessionEventSource)?.authSessionEvents?.collect { event ->
            if (event.changesRuntimeAccountUsageOwner()) {
                serverId = null
                tracker.invalidate()
            }
            read()
        }
    }
    val owner by tracker.owner.collectAsState()
    val scope = rememberCoroutineScope()
    val authHost =
        remember(sharedStores, storeScope, bridge) {
            ComputerRuntimeAuthHost(
                realtime = sharedStores.realtimeDomainEventOwner(storeScope),
                // A sign-in page opens in the browser, where the person may already be signed in.
                openUrl = { url -> bridge.openUrl(url, inBrowser = true) },
            )
        }
    return remember(store, owner, serverId, tracker, authHost) {
        MembersRuntimeAccountUsageAccess(
            store = store,
            owner = owner,
            serverId = serverId,
            onIdentityChanged = { scope.launch { tracker.invalidate() } },
            log = { message -> bridge.log(message) },
            auth = authHost,
        )
    }
}

/** Web `SurfaceRefreshState` without `idle`'s neighbours that need no copy of their own. */
internal enum class ComputerRuntimeUsageRefreshStatus {
    Idle,
    Requesting,
    Requested,
    Unconfirmed,
    Timeout,
    Cooldown,
    ComputerOffline,
}

/**
 * One chip's usage: the read, the refresh and the follow-up reads after a refresh the Computer
 * answers later (web `RuntimeAccountUsageChip`). Remembered per [key]; a new key is a new
 * controller, so nothing read for one subject is ever shown for another.
 */
internal class ComputerRuntimeUsageController(
    private val key: RuntimeAccountUsageKey,
    private val store: SharedRuntimeAccountUsageStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val onIdentityChanged: () -> Unit = {},
    private val followUpDelayMillis: Long = COMPUTER_RUNTIME_USAGE_FOLLOW_UP_DELAY_MILLIS,
    private val followUpAttempts: Int = COMPUTER_RUNTIME_USAGE_FOLLOW_UP_ATTEMPTS,
) {
    var load by mutableStateOf<RuntimeAccountUsageLoad?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var refresh by mutableStateOf(ComputerRuntimeUsageRefreshStatus.Idle)
        private set
    var cooldownLeftMillis by mutableStateOf(0L)
        private set

    /** Who may act on this Computer's runtimes (web `RuntimeAuthView`); null until read, or when refused. */
    var authView by mutableStateOf<RuntimeAuthView?>(null)
        private set

    /** What the last limit reset did, said once in the status line until the next Refresh (web `limitResetNote`). */
    var limitResetNote by mutableStateOf<ComputerRuntimeLimitResetNote?>(null)
        private set

    /** The open "Use a limit reset?" confirm, or null. */
    var limitResetDialog by mutableStateOf<ComputerRuntimeLimitResetDialogState?>(null)
        private set

    /** The card's "Use a reset" (web `surfaceLimitReset`); null when there is none to use or the viewer may not. */
    val limitResetOffer: ComputerRuntimeLimitResetOffer?
        get() = computerRuntimeLimitResetOffer(load, authView, key.provider)

    private var followUp: Job? = null

    /** The first read; a stale or missing snapshot asks the Computer for a new one in the background. */
    suspend fun start() {
        val read = (read(force = false) as? RuntimeAccountUsageLoad.Loaded)?.read ?: return
        if (read == RuntimeAccountUsageRead.Missing || (read as? RuntimeAccountUsageRead.Available)?.stale == true) {
            handleRefresh(store.refresh(key, RuntimeAccountUsageRefreshReason.StaleOrMissing), manual = false)
        }
    }

    /**
     * Opening the sheet reads again (from the 60 s cache when it is fresh), and reads who may act
     * on the Computer's runtimes (30 s cache) for the limit-reset action, as the web card does.
     */
    fun onOpen() {
        scope.launch { read(force = false) }
        scope.launch { authView = store.runtimeAuthView(key) }
    }

    fun refreshNow() {
        // Web `handleRefresh`: a refresh replaces what the last limit reset said.
        limitResetNote = null
        startManualRefresh()
    }

    private fun startManualRefresh() {
        if (refresh == ComputerRuntimeUsageRefreshStatus.Requesting) return
        refresh = ComputerRuntimeUsageRefreshStatus.Requesting
        scope.launch {
            handleRefresh(store.refresh(key, RuntimeAccountUsageRefreshReason.Manual), manual = true)
            updateCooldown()
        }
    }

    /**
     * "Use a reset": opens the confirm for the account holding the reset, with this attempt's
     * idempotency key (the one kept from an attempt whose outcome was never learned, else new).
     */
    fun openLimitReset() {
        if (limitResetOffer == null) return
        val account = load.limitResetAccount() ?: return
        scope.launch {
            val idempotencyKey = store.limitResetAttemptKey(key)
            limitResetDialog =
                ComputerRuntimeLimitResetDialogState(
                    account = account.identityLabel,
                    resetsAvailable = account.limitResetsAvailable ?: 0,
                    idempotencyKey = idempotencyKey,
                )
        }
    }

    fun dismissLimitReset() {
        if (limitResetDialog?.submitting == true) return
        limitResetDialog = null
    }

    /**
     * The confirmed click (web `RuntimeLimitResetDialog.handleConfirm`): spends the reset with the
     * dialog's key. An outcome the person has to act on (sign in again, unsupported, unknown) or
     * a request with no outcome keeps the dialog open with what happened; any other closes it.
     */
    fun confirmLimitReset() {
        val dialog = limitResetDialog ?: return
        if (dialog.submitting) return
        limitResetDialog = dialog.copy(submitting = true, error = null)
        scope.launch {
            val result = store.redeemLimitReset(key, dialog.idempotencyKey)
            log("computer_runtime_usage_limit_reset provider=${key.provider.wire} result=${result.diagnosticsLabel()}")
            when (result) {
                RuntimeLimitResetLoad.Superseded -> {
                    limitResetDialog = null
                    load = null
                    onIdentityChanged()
                }
                is RuntimeLimitResetLoad.Failed -> {
                    handleLimitResetOutcome(result = null, snapshot = null)
                    limitResetDialog = dialog.copy(submitting = false, error = result.refusal.limitResetError())
                }
                is RuntimeLimitResetLoad.Done -> {
                    handleLimitResetOutcome(result.reply.result, result.reply.snapshot)
                    val error = result.reply.result.limitResetError()
                    limitResetDialog = if (error == null) null else dialog.copy(submitting = false, error = error)
                }
            }
        }
    }

    /**
     * Web `handleLimitResetOutcome`: a settled attempt drops its key; never assume the limits
     * recovered — show the usage the Computer read right after the redeem, or refresh.
     */
    private suspend fun handleLimitResetOutcome(result: RuntimeLimitResetResult?, snapshot: RuntimeAccountUsageSnapshot?) {
        if (result.settlesAttempt) store.forgetLimitResetAttempt(key)
        limitResetNote = result?.limitResetNote()
        if (snapshot != null) {
            followUp?.cancel()
            load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Available(stale = false, snapshot = snapshot))
            refresh = ComputerRuntimeUsageRefreshStatus.Idle
        } else {
            startManualRefresh()
        }
    }

    suspend fun updateCooldown() {
        cooldownLeftMillis = store.refreshCooldownRemainingMillis(key)
    }

    fun cancel() {
        followUp?.cancel()
    }

    private suspend fun read(force: Boolean): RuntimeAccountUsageLoad {
        loading = true
        val result = store.read(key, force = force)
        log("computer_runtime_usage_read provider=${key.provider.wire} force=$force result=${result.diagnosticsLabel()}")
        // Web keeps what it showed when a later read fails; a 403 or a new identity does not.
        val keepShown = result is RuntimeAccountUsageLoad.Failed && load is RuntimeAccountUsageLoad.Loaded
        if (!keepShown) result.shownRuntimeAccountUsage()?.let { load = it }
        loading = false
        if (result is RuntimeAccountUsageLoad.Superseded) {
            load = null
            onIdentityChanged()
        }
        return result
    }

    private fun handleRefresh(result: RuntimeAccountUsageRefreshLoad, manual: Boolean) {
        log("computer_runtime_usage_refresh provider=${key.provider.wire} manual=$manual result=${result.diagnosticsLabel()}")
        when (result) {
            is RuntimeAccountUsageRefreshLoad.Done -> {
                val answer = result.refresh
                when (answer.state) {
                    RuntimeAccountUsageRefreshState.Fresh -> {
                        val snapshot = answer.snapshot
                        if (snapshot != null) {
                            load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Available(stale = false, snapshot = snapshot))
                            refresh = ComputerRuntimeUsageRefreshStatus.Idle
                        } else {
                            refresh = ComputerRuntimeUsageRefreshStatus.Unconfirmed
                        }
                        return
                    }
                    RuntimeAccountUsageRefreshState.Requested -> refresh = ComputerRuntimeUsageRefreshStatus.Requested
                    RuntimeAccountUsageRefreshState.Cooldown -> refresh = ComputerRuntimeUsageRefreshStatus.Cooldown
                    RuntimeAccountUsageRefreshState.ComputerOffline -> refresh = ComputerRuntimeUsageRefreshStatus.ComputerOffline
                    RuntimeAccountUsageRefreshState.Timeout -> refresh = ComputerRuntimeUsageRefreshStatus.Timeout
                }
                if (answer.accepted || answer.state == RuntimeAccountUsageRefreshState.Cooldown) startFollowUpReads()
            }
            RuntimeAccountUsageRefreshLoad.Forbidden -> {
                load = RuntimeAccountUsageLoad.Forbidden
                refresh = ComputerRuntimeUsageRefreshStatus.Idle
            }
            // Web: a refresh that throws reads as "Computer offline".
            is RuntimeAccountUsageRefreshLoad.Failed -> refresh = ComputerRuntimeUsageRefreshStatus.ComputerOffline
            RuntimeAccountUsageRefreshLoad.Superseded -> {
                load = null
                refresh = ComputerRuntimeUsageRefreshStatus.Idle
                onIdentityChanged()
            }
        }
    }

    /** Web `startFollowUpReads`: re-read until the snapshot is fresh, then give up as unconfirmed. */
    private fun startFollowUpReads() {
        followUp?.cancel()
        followUp =
            scope.launch {
                repeat(followUpAttempts) {
                    delay(followUpDelayMillis)
                    store.invalidate(key)
                    val next = (read(force = true) as? RuntimeAccountUsageLoad.Loaded)?.read
                    if (next is RuntimeAccountUsageRead.Available && !next.stale) {
                        refresh = ComputerRuntimeUsageRefreshStatus.Idle
                        return@launch
                    }
                }
                refresh = ComputerRuntimeUsageRefreshStatus.Unconfirmed
            }
    }
}

internal const val COMPUTER_RUNTIME_USAGE_FOLLOW_UP_DELAY_MILLIS: Long = 1_200L
internal const val COMPUTER_RUNTIME_USAGE_FOLLOW_UP_ATTEMPTS: Int = 8

private fun RuntimeLimitResetLoad.diagnosticsLabel(): String =
    when (this) {
        is RuntimeLimitResetLoad.Done -> reply.result.wire + if (reply.snapshot != null) ":snapshot" else ""
        is RuntimeLimitResetLoad.Failed -> "failed:${refusal.status ?: "none"}:${refusal.code ?: "none"}"
        RuntimeLimitResetLoad.Superseded -> "superseded"
    }

private fun RuntimeAccountUsageRefreshLoad.diagnosticsLabel(): String =
    when (this) {
        is RuntimeAccountUsageRefreshLoad.Done -> refresh.state.wire
        RuntimeAccountUsageRefreshLoad.Forbidden -> "forbidden"
        is RuntimeAccountUsageRefreshLoad.Failed -> "failed:$code"
        RuntimeAccountUsageRefreshLoad.Superseded -> "superseded"
    }

/**
 * The chip's health pixel (web): none until a snapshot is known; `warning` when it is stale or
 * any account or window is not ok; `success` otherwise.
 */
internal fun RuntimeAccountUsageLoad?.computerRuntimeUsageHealth(): RaftStatusVariant? {
    val read = (this as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available ?: return null
    val attention =
        read.stale ||
            read.snapshot.accounts.any { account ->
                account.health != RuntimeAccountUsageHealth.Ok || account.windows.any { it.status != RuntimeAccountUsageWindowStatus.Ok }
            }
    return if (attention) RaftStatusVariant.Warning else RaftStatusVariant.Success
}

@Composable
private fun ComputerRuntimeUsageChip(
    label: String,
    runtimeId: String,
    runtimeName: String,
    machineId: String,
    key: RuntimeAccountUsageKey?,
    access: MembersRuntimeAccountUsageAccess?,
    runtimeVersion: String?,
    computerName: String?,
    agents: List<SettingsComputerAgentSnapshot>,
    plain: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller =
        remember(key, access?.store) {
            if (key == null || access == null) {
                null
            } else {
                ComputerRuntimeUsageController(
                    key = key,
                    store = access.store,
                    scope = scope,
                    log = access.log,
                    onIdentityChanged = access.onIdentityChanged,
                )
            }
        }
    DisposableEffect(controller) { onDispose { controller?.cancel() } }
    LaunchedEffect(controller) { controller?.start() }
    // Web: the card reads the Computer's sign-in state on mount, so a signed-out chip says so at rest.
    val auth = rememberComputerRuntimeAuthController(access, machineId, runtimeId, readNow = true)
    // An open dialog stays with its session even if the Computer drops offline meanwhile (Antigravity's
    // card is offered only while the server lists it for an online Computer).
    val authBinding = auth?.takeIf { it.offered || it.dialog != null }?.let { ComputerRuntimeAuthBinding(it, runtimeName, computerName, agents, access?.auth?.openUrl ?: {}) }
    // A runtime with neither usage nor a sign-in offered here has no card (Antigravity where the server does not revive it).
    if (controller == null && authBinding == null) {
        plain()
        return
    }
    val load = controller?.load
    val offered = (controller != null && load !is RuntimeAccountUsageLoad.Forbidden) || authBinding != null
    // Signed out on the Computer: an orange chip that says so in words, and no health dot (web).
    val signedOut = authBinding?.controller?.signedOut == true
    val health = if (signedOut) null else load.computerRuntimeUsageHealth()
    val healthDot: (@Composable () -> Unit)? =
        if (health == null) {
            null
        } else {
            {
                RaftStatusDot(
                    variant = health,
                    size = RaftStatusSize.Sm,
                    contentDescription =
                        stringResource(
                            if (health == RaftStatusVariant.Warning) {
                                Res.string.settings_computers_runtime_usage_attention
                            } else {
                                Res.string.settings_computers_runtime_usage_healthy
                            }
                        ),
                )
            }
        }
    val text = if (signedOut) label + stringResource(Res.string.settings_computers_runtime_auth_not_signed_in_suffix) else label
    val chip: @Composable () -> Unit = {
        // A badge whose long press copies the runtime name; the usage card opens on hover and pins
        // on a click (below), so the badge takes no click of its own.
        RaftBadge(
            text = text,
            modifier = Modifier.testTag(if (signedOut) COMPUTER_RUNTIME_CHIP_SIGNED_OUT_TAG else COMPUTER_RUNTIME_CHIP_TAG),
            copyText = label,
            appearance = RaftBadgeAppearance.Soft,
            variant = if (signedOut) RaftBadgeVariant.Warning else RaftBadgeVariant.Information,
            trailing = healthDot,
        )
    }
    // Web `RuntimeUsageHoverCard` (artin 11-02): a mouse resting on the chip shows the usage card,
    // a click (a finger's tap too) pins it.
    ComputerRuntimeUsagePreviewCard(
        runtimeLabel = label,
        controller = controller,
        runtimeVersion = runtimeVersion,
        enabled = offered,
        auth = authBinding,
        trigger = chip,
    )
}

internal const val COMPUTER_RUNTIME_CHIP_TAG = "runtime-chip"
internal const val COMPUTER_RUNTIME_CHIP_SIGNED_OUT_TAG = "runtime-chip-signed-out"

/** A card's sign-in: its controller and what its dialogs name. */
internal class ComputerRuntimeAuthBinding(
    val controller: ComputerRuntimeAuthController,
    /** The runtime's display name ("Claude Code"): the card's title once there is no usage to name. */
    val runtimeName: String,
    val computerName: String?,
    val agents: List<SettingsComputerAgentSnapshot>?,
    val openUrl: (String) -> Unit,
) {
    /** What the card draws; null until the Computer's sign-in state is read. */
    val cardState: ComputerRuntimeAuthCardState?
        get() = controller.view?.let { ComputerRuntimeAuthCardState(controller.raftRuntimeId, controller.runtime, it) }
}

/**
 * The usage sheet of one provider on one computer, opened from a Computers page card line (web
 * rack `UsageCardTrigger`). Opening reads through the shared 60 s cache, as the detail chip's
 * sheet does; it asks the Computer for a new snapshot only when the person taps Refresh.
 */
@Composable
internal fun ComputerRuntimeUsageSheetHost(
    runtimeLabel: String,
    key: RuntimeAccountUsageKey,
    access: MembersRuntimeAccountUsageAccess,
    /** The machine's `runtimeVersions[runtimeId]` (web rack `RackRuntimeUsage`); null when not reported. */
    runtimeVersion: String?,
    onDismiss: () -> Unit,
    /** The rack column's Raft runtime id, for its sign-in; null shows usage alone. */
    runtimeId: String? = null,
    runtimeName: String = runtimeLabel,
    computerName: String? = null,
    agents: List<SettingsComputerAgentSnapshot>? = null,
) {
    val scope = rememberCoroutineScope()
    val controller =
        remember(key, access.store) {
            ComputerRuntimeUsageController(
                key = key,
                store = access.store,
                scope = scope,
                log = access.log,
                onIdentityChanged = access.onIdentityChanged,
            )
        }
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    // The sheet reads the Computer's sign-in state as it opens (web: the card's own read).
    val auth = runtimeId?.let { rememberComputerRuntimeAuthController(access, key.machineId, it, readNow = true) }
    LaunchedEffect(controller) {
        controller.onOpen()
        while (true) {
            controller.updateCooldown()
            delay(1_000L)
        }
    }
    val timeContext = currentRaftTimeContextProvider?.current
    val authState = auth?.view?.let { ComputerRuntimeAuthCardState(auth.raftRuntimeId, auth.runtime, it) }
    ComputerRuntimeUsageSheet(
        runtimeLabel = runtimeLabel,
        state = controller.sheetState(runtimeVersion),
        timeContext = timeContext,
        onRefresh = controller::refreshNow,
        onUseLimitReset = controller::openLimitReset,
        onDismiss = onDismiss,
        auth = authState,
        authRuntimeName = runtimeName,
        onSignIn = { auth?.openSignIn() },
        onSignOut = { auth?.openSignOut() },
    )
    ComputerRuntimeLimitResetDialogHost(runtimeLabel, controller)
    if (auth != null) ComputerRuntimeAuthDialogHost(auth, runtimeName, computerName, agents, access.auth?.openUrl ?: {})
}

/** What the controller shows, for the sheet or the card. */
internal fun ComputerRuntimeUsageController.sheetState(runtimeVersion: String?): ComputerRuntimeUsageSheetState =
    ComputerRuntimeUsageSheetState(
        load = load,
        loading = loading,
        refresh = refresh,
        cooldownLeftMillis = cooldownLeftMillis,
        runtimeVersion = runtimeVersion,
        limitReset = limitResetOffer,
        limitResetNote = limitResetNote,
    )

/** The controller's open "Use a limit reset?" confirm, if any. */
@Composable
private fun ComputerRuntimeLimitResetDialogHost(runtimeLabel: String, controller: ComputerRuntimeUsageController) {
    controller.limitResetDialog?.let { dialog ->
        RaftComputersTheme {
            ComputerRuntimeLimitResetDialog(
                runtimeName = runtimeLabel,
                state = dialog,
                onConfirm = controller::confirmLimitReset,
                onDismiss = controller::dismissLimitReset,
            )
        }
    }
}

/** The usage block already names the account (web `usageShowsAccount`): the sign-in line then does not. */
internal val ComputerRuntimeUsageSheetState.showsAccount: Boolean
    get() = ((load as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available)?.snapshot?.accounts?.isNotEmpty() == true

/** What the usage sheet shows (web `Surface` props). */
internal data class ComputerRuntimeUsageSheetState(
    val load: RuntimeAccountUsageLoad?,
    val loading: Boolean,
    val refresh: ComputerRuntimeUsageRefreshStatus,
    val cooldownLeftMillis: Long,
    /** The runtime CLI version the Computer reported (web `runtimeVersion`); null = "Version unavailable". */
    val runtimeVersion: String? = null,
    /** "Use a reset" (slock #13504); null when there is no reset to use or the viewer may not act. */
    val limitReset: ComputerRuntimeLimitResetOffer? = null,
    /** What the last limit reset did, in place of the refresh line. */
    val limitResetNote: ComputerRuntimeLimitResetNote? = null,
)

/** Web phone layout: the usage surface in a bottom sheet (`BottomSheet`, below md). */
@Composable
private fun ComputerRuntimeUsageSheet(
    runtimeLabel: String,
    state: ComputerRuntimeUsageSheetState,
    timeContext: RaftTimeContext?,
    onRefresh: () -> Unit,
    onUseLimitReset: () -> Unit,
    onDismiss: () -> Unit,
    auth: ComputerRuntimeAuthCardState? = null,
    authRuntimeName: String = runtimeLabel,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    // Signed out: there is no usage to show, only the way to sign in (web).
    val signedOut = auth?.signedOut == true
    RaftSheet(
        onDismissRequest = onDismiss,
        title = if (signedOut) authRuntimeName else stringResource(Res.string.settings_computers_runtime_usage_title, runtimeLabel),
        actions = {
            if (auth != null) ComputerRuntimeAuthActionView(auth, onSignIn, onSignOut, fullWidth = true)
            if (signedOut) {
                RaftButton(
                    text = stringResource(Res.string.common_close),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                )
            } else {
                ComputerRuntimeUsageActions(state, timeContext, onRefresh, onUseLimitReset, onDismiss)
            }
        },
        content = {
            if (auth != null) ComputerRuntimeAuthHeaderLine(auth, usageShowsAccount = state.showsAccount, authOnly = false)
            if (!signedOut) ComputerRuntimeUsageContents(state, timeContext, showsVersion = true)
        },
    )
}

/** The sheet's surface without its window, for screenshots. */
@Composable
internal fun ComputerRuntimeUsageSheetSurface(
    runtimeLabel: String,
    state: ComputerRuntimeUsageSheetState,
    timeContext: RaftTimeContext?,
    modifier: Modifier = Modifier,
) {
    RaftSheetSurface(
        title = stringResource(Res.string.settings_computers_runtime_usage_title, runtimeLabel),
        modifier = modifier,
        actions = { ComputerRuntimeUsageActions(state, timeContext, onRefresh = {}, onUseLimitReset = {}, onDismiss = {}) },
        content = { ComputerRuntimeUsageContents(state, timeContext, showsVersion = true) },
    )
}

@Composable
private fun ColumnScope.ComputerRuntimeUsageActions(
    state: ComputerRuntimeUsageSheetState,
    timeContext: RaftTimeContext?,
    onRefresh: () -> Unit,
    onUseLimitReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RaftTheme.colors
    // The snapshot's footnote stays with the footer, so it never scrolls out of a short sheet.
    val available = (state.load as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available
    if (available != null) {
        val updated =
            available.snapshot.collectedAt
                ?.let { raftRelativeTimestampLabel(it, timeContext, clampFuture = true) }
                ?.takeIf { it.isNotEmpty() }
                ?: stringResource(Res.string.settings_computers_runtime_usage_recently)
        RaftText(
            stringResource(Res.string.settings_computers_runtime_usage_metadata, updated),
            style = RaftTheme.typography.caption,
            color = colors.foregroundHint,
        )
    }
    RaftText(computerRuntimeUsageRefreshStatusText(state), style = RaftTheme.typography.caption, color = colors.foregroundHint)
    RaftButton(
        text = stringResource(Res.string.settings_computers_runtime_usage_refresh),
        onClick = onRefresh,
        modifier = Modifier.fillMaxWidth(),
        variant = RaftButtonVariant.Default,
        size = RaftButtonSize.Lg,
        enabled = state.cooldownLeftMillis <= 0L,
        loading = state.refresh == ComputerRuntimeUsageRefreshStatus.Requesting,
        leadingIcon = { RaftComputersIcon(RaftLucideIconName.RefreshCw) },
    )
    // Web: Use a reset beside Refresh; primary only while the account is rate-limited.
    state.limitReset?.let { offer ->
        ComputerRuntimeLimitResetButton(offer, onUseLimitReset, Modifier.fillMaxWidth(), size = RaftButtonSize.Lg)
    }
    RaftButton(
        text = stringResource(Res.string.common_close),
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        variant = RaftButtonVariant.Ghost,
        size = RaftButtonSize.Lg,
    )
}

/** The refresh line under the usage (web `Surface` footer). */
@Composable
private fun computerRuntimeUsageRefreshStatusText(state: ComputerRuntimeUsageSheetState): String =
    when {
        // Web `statusText`: what the last limit reset did comes first.
        state.limitResetNote != null -> state.limitResetNote.text()
        state.refresh == ComputerRuntimeUsageRefreshStatus.Requested -> stringResource(Res.string.settings_computers_runtime_usage_refresh_requested)
        state.refresh == ComputerRuntimeUsageRefreshStatus.Unconfirmed -> stringResource(Res.string.settings_computers_runtime_usage_refresh_unconfirmed)
        state.refresh == ComputerRuntimeUsageRefreshStatus.Timeout -> stringResource(Res.string.settings_computers_runtime_usage_refresh_timeout)
        state.cooldownLeftMillis > 0L ->
            stringResource(Res.string.settings_computers_runtime_usage_refresh_cooldown_countdown, ((state.cooldownLeftMillis + 999L) / 1_000L).toInt())
        state.refresh == ComputerRuntimeUsageRefreshStatus.ComputerOffline -> stringResource(Res.string.settings_computers_runtime_usage_computer_offline)
        else -> stringResource(Res.string.settings_computers_runtime_usage_cache_only)
    }

@Composable
internal fun ComputerRuntimeUsageContents(
    state: ComputerRuntimeUsageSheetState,
    timeContext: RaftTimeContext?,
    /** The sheet's header line (web `Surface`); the migration target list embeds the contents without it. */
    showsVersion: Boolean = false,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Web `Surface` header: the runtime's version (mono meta), then who may see this.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showsVersion) {
                val version = state.runtimeVersion?.trim()?.takeIf { it.isNotEmpty() }
                RaftText(
                    if (version != null) {
                        stringResource(Res.string.settings_computers_runtime_usage_version, version)
                    } else {
                        stringResource(Res.string.settings_computers_runtime_usage_version_unavailable)
                    },
                    style = typography.monoMeta,
                    color = colors.foregroundHint,
                )
            }
            RaftText(
                stringResource(Res.string.settings_computers_runtime_usage_private),
                style = typography.caption,
                color = colors.foregroundHint,
            )
        }
        val load = state.load
        when {
            load == null && state.loading ->
                RaftText(stringResource(Res.string.settings_computers_runtime_usage_reading), style = typography.callout, color = colors.foregroundMuted)
            load is RuntimeAccountUsageLoad.Failed ->
                RaftBanner(
                    description = stringResource(Res.string.settings_computers_runtime_usage_read_failed),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                )
            load is RuntimeAccountUsageLoad.Loaded && load.read is RuntimeAccountUsageRead.Available ->
                ComputerRuntimeUsageSnapshot(load.read as RuntimeAccountUsageRead.Available, timeContext)
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftText(
                        stringResource(Res.string.settings_computers_runtime_usage_no_snapshot),
                        style = typography.callout.copy(fontWeight = FontWeight.Bold),
                        color = colors.foreground,
                    )
                    RaftText(
                        stringResource(Res.string.settings_computers_runtime_usage_no_snapshot_description),
                        style = typography.footnote,
                        color = colors.foregroundMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComputerRuntimeUsageSnapshot(read: RuntimeAccountUsageRead.Available, timeContext: RaftTimeContext?) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    // A stale snapshot is recoverable: warning, never red.
    if (read.stale) {
        RaftBanner(
            description = stringResource(Res.string.settings_computers_runtime_usage_stale),
            status = RaftBannerStatus.Warning,
            size = RaftBannerSize.Sm,
        )
    }
    read.snapshot.accounts.forEachIndexed { index, account ->
        if (index > 0) RaftSeparator()
        ComputerRuntimeUsageAccountBlock(account, timeContext)
    }
    RaftSeparator()
}

@Composable
internal fun ComputerRuntimeUsageAccountBlock(account: RuntimeAccountUsageAccount, timeContext: RaftTimeContext?) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(
                    account.planLabel ?: stringResource(Res.string.settings_computers_runtime_usage_account_fallback),
                    style = typography.callout.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val resets = account.limitResetsAvailable
                if (resets == null) {
                    account.identityLabel?.let { identity ->
                        RaftText(identity, style = typography.monoMeta, color = colors.foregroundHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    // Web: the account's limit resets (Codex) beside the account they belong to.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        account.identityLabel?.let { identity ->
                            RaftText(
                                identity,
                                modifier = Modifier.weight(1f, fill = false),
                                style = typography.monoMeta,
                                color = colors.foregroundHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            RaftSeparator(Modifier.height(12.dp), orientation = RaftSeparatorOrientation.Vertical)
                        }
                        RaftText(
                            computerRuntimeLimitResetsLeftText(resets),
                            modifier = Modifier.testTag(COMPUTER_RUNTIME_LIMIT_RESETS_LEFT_TAG),
                            style = typography.caption,
                            color = colors.foregroundMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
            // Raft DS StatusChip: soft wash, a square pixel, one word.
            val ok = account.health == RuntimeAccountUsageHealth.Ok
            RaftBadge(
                text = account.health.label(),
                appearance = RaftBadgeAppearance.Soft,
                variant = if (ok) RaftBadgeVariant.Success else RaftBadgeVariant.Warning,
                icon = { RaftStatusDot(variant = if (ok) RaftStatusVariant.Success else RaftStatusVariant.Warning, size = RaftStatusSize.Md) },
            )
        }
        when (account.health) {
            // Unsupported: the badge alone, as on the web (#14543); no line on why.
            RuntimeAccountUsageHealth.Unsupported -> Unit
            RuntimeAccountUsageHealth.Error ->
                RaftText(
                    stringResource(Res.string.settings_computers_runtime_usage_read_failed),
                    style = typography.footnote,
                    color = colors.foregroundMuted,
                )
            RuntimeAccountUsageHealth.ReauthRequired ->
                RaftText(
                    stringResource(Res.string.settings_computers_runtime_usage_reauth_description),
                    style = typography.footnote,
                    color = colors.foregroundMuted,
                )
            else ->
                account.windows.forEach { window -> ComputerRuntimeUsageWindowRow(window, timeContext) }
        }
    }
}

/** One usage window: "X% used · resets …" or "Used up", as the web `RuntimeAccountUsageChip` (raft-ui 0.50). */
@Composable
internal fun ComputerRuntimeUsageWindowRow(window: RuntimeAccountUsageWindow, timeContext: RaftTimeContext?) {
    val ratio = window.usedRatio
    if (window.status == RuntimeAccountUsageWindowStatus.Unavailable || ratio == null) {
        // Never a bar: a window we cannot read is not "0% used".
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftText(
                window.label,
                modifier = Modifier.weight(1f),
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = RaftTheme.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RaftText(
                stringResource(Res.string.settings_computers_runtime_usage_parse_unavailable),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.warningStrong,
            )
        }
        return
    }
    // Used up by the provider's word or the ratio itself, not by rounding: 0.4% left reads
    // "100% used" (rounded), not "Used up", as the web says.
    val usedUp = window.status == RuntimeAccountUsageWindowStatus.LimitReached || ratio >= 1.0
    val reset =
        window.resetsAt?.let { resetsAt ->
            raftRelativeTimestampLabel(resetsAt, timeContext).takeIf { it.isNotEmpty() }
                ?: stringResource(Res.string.settings_computers_runtime_usage_later)
        }
    val percentUsed = "${runtimeUsagePercentUsed(ratio)}%"
    val valueText =
        when {
            usedUp && reset == null -> stringResource(Res.string.settings_computers_runtime_usage_window_used_up_reset_unavailable)
            usedUp -> stringResource(Res.string.settings_computers_runtime_usage_window_used_up, reset.orEmpty())
            reset == null -> stringResource(Res.string.settings_computers_runtime_usage_window_used_reset_unavailable, percentUsed)
            else -> stringResource(Res.string.settings_computers_runtime_usage_window_used, percentUsed, reset)
        }
    // raft-ui 0.50 Meter: fills with what is used, warning from 80% used, a full warning bar
    // when used up.
    RaftMeter(
        used = if (usedUp) 100f else (ratio * 100.0).toFloat(),
        valueText = valueText,
        label = window.label,
        size = RaftMeterSize.Sm,
    )
}

/** Web `runtimeUsagePercentUsed`: `round(usedRatio * 100)`. */
internal fun runtimeUsagePercentUsed(usedRatio: Double): Int = kotlin.math.round(usedRatio * 100.0).toInt()

@Composable
private fun RuntimeAccountUsageHealth.label(): String =
    stringResource(
        when (this) {
            RuntimeAccountUsageHealth.Ok -> Res.string.settings_computers_runtime_usage_health_ok
            RuntimeAccountUsageHealth.RateLimited -> Res.string.settings_computers_runtime_usage_health_rate_limited
            RuntimeAccountUsageHealth.ReauthRequired -> Res.string.settings_computers_runtime_usage_health_reauth_required
            RuntimeAccountUsageHealth.Unsupported -> Res.string.settings_computers_runtime_usage_health_unsupported
            RuntimeAccountUsageHealth.Error -> Res.string.settings_computers_runtime_usage_health_error
            RuntimeAccountUsageHealth.Unknown -> Res.string.settings_computers_runtime_usage_health_unknown
        }
    )

/**
 * Web `RuntimeUsageHoverCard` (desktop): the usage card on a runtime chip under a mouse or
 * trackpad pointer. It opens after the pointer rests on [trigger] for 200ms and closes 160ms
 * after it leaves; a click on [trigger], by any pointer, opens it at once and pins it (it then
 * stays until a click outside, Escape or its close button), so the pointer can reach Refresh.
 * Opening reads through the shared 60 s cache, as the sheet does.
 *
 * The same for every pointer: a finger has no hover, so its tap pins the card. The click goes no
 * further, so give [trigger] no click of its own (no sheet). [enabled] false shows [trigger] alone.
 */
@Composable
internal fun ComputerRuntimeUsagePreviewCard(
    runtimeLabel: String,
    controller: ComputerRuntimeUsageController?,
    runtimeVersion: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The runtime's sign-in on this card (web `SurfaceAuth`); null shows usage alone. */
    auth: ComputerRuntimeAuthBinding? = null,
    trigger: @Composable () -> Unit,
) {
    val state = rememberRaftPreviewCardState(closeDelayMillis = ComputerRuntimeUsagePreviewCardCloseDelayMillis)
    val timeContext = currentRaftTimeContextProvider?.current
    if (state.isOpen) {
        LaunchedEffect(controller, auth) {
            controller?.onOpen()
            // Web `openNow`: the sign-in state is read again with the usage.
            auth?.controller?.read()
            while (controller != null) {
                controller.updateCooldown()
                delay(1_000L)
            }
        }
    }
    val authController = auth?.controller
    // Web: a dialog takes over from the card, so the card does not float above it.
    val onSignIn = {
        state.dismiss()
        authController?.openSignIn()
        Unit
    }
    val onSignOut = {
        state.dismiss()
        authController?.openSignOut()
        Unit
    }
    val onSignOutProvider = { provider: RuntimeAuthSignedInProvider ->
        state.dismiss()
        authController?.openSignOut(provider)
        Unit
    }
    RaftPreviewCard(
        card = {
            if (controller != null) {
                ComputerRuntimeUsageCard(
                    runtimeLabel = runtimeLabel,
                    state = controller.sheetState(runtimeVersion),
                    timeContext = timeContext,
                    onRefresh = controller::refreshNow,
                    onClose = state::dismiss,
                    onUseLimitReset = {
                        state.dismiss()
                        controller.openLimitReset()
                    },
                    auth = auth?.cardState,
                    authRuntimeName = auth?.runtimeName ?: runtimeLabel,
                    onSignIn = onSignIn,
                    onSignOut = onSignOut,
                    onSignOutProvider = onSignOutProvider,
                )
            } else if (auth != null) {
                ComputerRuntimeAuthOnlyCard(
                    runtimeName = auth.runtimeName,
                    runtimeVersion = runtimeVersion,
                    auth = auth.cardState,
                    onClose = state::dismiss,
                    onSignIn = onSignIn,
                    onSignOut = onSignOut,
                    onSignOutProvider = onSignOutProvider,
                )
            }
        },
        modifier = modifier,
        state = state,
        enabled = enabled,
        pinOnClick = true,
        width = RaftPreviewCardDefaults.WideWidth,
        trigger = trigger,
    )
    if (controller != null) ComputerRuntimeLimitResetDialogHost(runtimeLabel, controller)
    if (auth != null) ComputerRuntimeAuthDialogHost(auth.controller, auth.runtimeName, auth.computerName, auth.agents, auth.openUrl)
}

/**
 * Web `RuntimeUsageSurface` for a runtime with no usage to read (Qoder, Pi, Antigravity): its
 * sign-in alone — the title, "Signed in" or "Not signed in" (Pi: its stored providers), the version,
 * who may see it, and the sign-in action.
 */
@Composable
internal fun ComputerRuntimeAuthOnlyCard(
    runtimeName: String,
    runtimeVersion: String?,
    auth: ComputerRuntimeAuthCardState?,
    onClose: (() -> Unit)?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutProvider: (RuntimeAuthSignedInProvider) -> Unit,
) {
    ComputerRuntimeCardHeader(title = runtimeName, runtimeVersion = runtimeVersion, onClose = onClose) {
        if (auth != null) ComputerRuntimeAuthHeaderLine(auth, usageShowsAccount = false, authOnly = true)
    }
    if (auth != null) {
        ComputerRuntimeAuthProviderRows(auth, onSignOutProvider)
        if (auth.action() != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                ComputerRuntimeAuthActionView(auth, onSignIn, onSignOut)
            }
        }
    }
}

/** The card's head (web `Surface`): title, the sign-in line, the version, who may see it, and close. */
@Composable
private fun ComputerRuntimeCardHeader(
    title: String,
    runtimeVersion: String?,
    onClose: (() -> Unit)?,
    authLine: @Composable () -> Unit = {},
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    // `flex items-center justify-between gap-3 border-b border-line-hairline px-4 py-3`
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RaftText(title, style = typography.headline, color = colors.foreground)
            authLine()
            val version = runtimeVersion?.trim()?.takeIf { it.isNotEmpty() }
            RaftText(
                if (version != null) {
                    stringResource(Res.string.settings_computers_runtime_usage_version, version)
                } else {
                    stringResource(Res.string.settings_computers_runtime_usage_version_unavailable)
                },
                style = typography.monoMeta,
                color = colors.foregroundHint,
            )
            RaftText(
                stringResource(Res.string.settings_computers_runtime_usage_private),
                style = typography.caption,
                color = colors.foregroundHint,
            )
        }
        if (onClose != null) {
            RaftIconButton(onClick = onClose, tooltip = stringResource(Res.string.common_close), variant = RaftButtonVariant.Ghost) {
                RaftComputersIcon(RaftLucideIconName.X)
            }
        }
    }
    RaftSeparator()
}

/** Web `HOVER_CLOSE_DELAY_MS`: the usage card waits a little longer than a profile card. */
internal const val ComputerRuntimeUsagePreviewCardCloseDelayMillis = 160L

/**
 * Web `Surface` in its desktop card: a header (title, version, who may see it, close) over a
 * hairline, the accounts, then a footer row with the refresh state and an outline Refresh. The
 * phone keeps the sheet ([ComputerRuntimeUsageSheet]) with the same content.
 */
@Composable
internal fun ComputerRuntimeUsageCard(
    runtimeLabel: String,
    state: ComputerRuntimeUsageSheetState,
    timeContext: RaftTimeContext?,
    onRefresh: () -> Unit,
    onClose: (() -> Unit)?,
    onUseLimitReset: () -> Unit = {},
    /** The runtime's sign-in (web `SurfaceAuth`); null shows usage alone. */
    auth: ComputerRuntimeAuthCardState? = null,
    authRuntimeName: String = runtimeLabel,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onSignOutProvider: (RuntimeAuthSignedInProvider) -> Unit = {},
) {
    // Signed out: there is no usage to show, only the way to sign in (web `showsUsage`).
    if (auth?.signedOut == true) {
        ComputerRuntimeAuthOnlyCard(authRuntimeName, state.runtimeVersion, auth, onClose, onSignIn, onSignOut, onSignOutProvider)
        return
    }
    ComputerRuntimeCardHeader(
        title = stringResource(Res.string.settings_computers_runtime_usage_title, runtimeLabel),
        runtimeVersion = state.runtimeVersion,
        onClose = onClose,
    ) {
        if (auth != null) ComputerRuntimeAuthHeaderLine(auth, usageShowsAccount = state.showsAccount, authOnly = false)
    }
    ComputerRuntimeUsageCardBody(state, timeContext, onRefresh, onUseLimitReset, auth, onSignIn, onSignOut)
}

@Composable
private fun ComputerRuntimeUsageCardBody(
    state: ComputerRuntimeUsageSheetState,
    timeContext: RaftTimeContext?,
    onRefresh: () -> Unit,
    onUseLimitReset: () -> Unit,
    auth: ComputerRuntimeAuthCardState?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val load = state.load
    when {
        load == null && state.loading ->
            RaftText(
                stringResource(Res.string.settings_computers_runtime_usage_reading),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                style = typography.callout,
                color = colors.foregroundMuted,
            )
        load is RuntimeAccountUsageLoad.Failed ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.padding(top = 2.dp)) {
                    CompositionLocalProvider(LocalRaftContentColor provides colors.warningStrong) {
                        RaftComputersIcon(RaftLucideIconName.TriangleAlert)
                    }
                }
                RaftText(
                    stringResource(Res.string.settings_computers_runtime_usage_read_failed),
                    style = typography.callout,
                    color = colors.warningStrong,
                )
            }
        load is RuntimeAccountUsageLoad.Loaded && load.read is RuntimeAccountUsageRead.Available -> {
            val read = load.read as RuntimeAccountUsageRead.Available
            // A stale snapshot sits on the sunken surface under an orange-soft notice.
            Column(Modifier.fillMaxWidth().then(if (read.stale) Modifier.background(colors.fillMuted) else Modifier)) {
                if (read.stale) {
                    RaftText(
                        stringResource(Res.string.settings_computers_runtime_usage_stale),
                        modifier = Modifier.fillMaxWidth().background(colors.warningSoft).padding(horizontal = 16.dp, vertical = 8.dp),
                        style = typography.caption.copy(fontWeight = FontWeight.Bold),
                        color = colors.foreground,
                    )
                    RaftSeparator()
                }
                read.snapshot.accounts.forEachIndexed { index, account ->
                    if (index > 0) RaftSeparator()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        ComputerRuntimeUsageAccountBlock(account, timeContext)
                    }
                }
                // `px-4 py-2 text-caption text-foreground-hint`: when the snapshot was taken.
                RaftSeparator()
                val updated =
                    read.snapshot.collectedAt
                        ?.let { raftRelativeTimestampLabel(it, timeContext, clampFuture = true) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: stringResource(Res.string.settings_computers_runtime_usage_recently)
                RaftText(
                    stringResource(Res.string.settings_computers_runtime_usage_metadata, updated),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    style = typography.caption,
                    color = colors.foregroundHint,
                )
            }
        }
        else ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftText(
                    stringResource(Res.string.settings_computers_runtime_usage_no_snapshot),
                    style = typography.callout.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                )
                RaftText(
                    stringResource(Res.string.settings_computers_runtime_usage_no_snapshot_description),
                    style = typography.footnote,
                    color = colors.foregroundMuted,
                )
            }
    }
    RaftSeparator()
    // `flex items-center justify-between gap-3 border-t border-line-hairline px-4 py-2.5`
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            computerRuntimeUsageRefreshStatusText(state),
            modifier = Modifier.weight(1f),
            style = typography.caption,
            color = colors.foregroundHint,
        )
        RaftButton(
            text = stringResource(Res.string.settings_computers_runtime_usage_refresh),
            onClick = onRefresh,
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Md,
            enabled = state.cooldownLeftMillis <= 0L,
            loading = state.refresh == ComputerRuntimeUsageRefreshStatus.Requesting,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.RefreshCw) },
        )
        state.limitReset?.let { offer -> ComputerRuntimeLimitResetButton(offer, onUseLimitReset) }
    }
    // Web: the quiet sign-in action in the card's action row (here under it: a phone-wide card).
    if (auth?.action() != null) {
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            ComputerRuntimeAuthActionView(auth, onSignIn, onSignOut)
        }
    }
}

internal const val COMPUTER_RUNTIME_LIMIT_RESETS_LEFT_TAG = "runtime-account-limit-resets"
