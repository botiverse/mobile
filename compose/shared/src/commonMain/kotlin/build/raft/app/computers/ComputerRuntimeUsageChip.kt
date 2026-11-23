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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import build.raft.app.data.RuntimeAccountUsageBadge
import build.raft.app.data.RuntimeAccountUsageUnsupportedReason
import build.raft.app.data.RuntimePlanHeading
import build.raft.app.data.RuntimeQuotaWindowKind
import build.raft.app.data.cardWindowKind
import build.raft.app.data.isDefaultAccount
import build.raft.app.data.runtimePlanHeading
import build.raft.app.data.unsupportedReasonLine
import build.raft.app.data.usageBadge
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import org.jetbrains.compose.resources.StringResource
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
import build.raft.app.data.RuntimeAccountUsageWindowFill
import build.raft.app.data.RuntimeAccountUsageCreditsLine
import build.raft.app.data.creditsLine
import build.raft.app.data.needsUsageAttention
import build.raft.app.data.selectComputerServerSlice
import build.raft.app.data.windowFill
import build.raft.app.data.RuntimeAuthSignedInProvider
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.RuntimeLimitResetLoad
import build.raft.app.data.RuntimeLimitResetResult
import build.raft.app.data.RuntimeLimitResetKind
import build.raft.app.data.RuntimeLimitResetsReadLoad
import build.raft.app.data.RuntimeLimitResetsReadResult
import build.raft.app.data.RuntimeLimitResetKindStatus
import build.raft.app.data.CLAUDE_LIMIT_RESET_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.model.ServerId
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.settlesAttempt
import build.raft.app.data.allModels
import build.raft.app.data.claudeOAuthApps
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.repeatWhileProcessForeground
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
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
import build.raft.designsystem.components.RaftPreviewCardState
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
import build.raft.designsystem.theme.RaftPointerInput
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
            ComputerRuntimeUsageChip(
                label = chip.name,
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
        // On main: the controller and its sign-in session write Compose state and plain fields that
        // the dialog also writes.
        realtime?.registerGuardedActiveThreadSink(sink, onMain = true)
        onDispose {
            realtime?.unregisterGuardedActiveThreadSink(sink)
            controller.dispose()
        }
    }
    LaunchedEffect(controller, readNow) { if (readNow) controller?.read() }
    return controller
}

/**
 * Applies one auth-session event to shell runtime-usage access. Sign-out and account switch clear
 * immediately before re-reading; metadata refreshes (`BindingMetadataChanged`) refresh in place so
 * returning from an external OAuth browser never flashes `(owner, serverId)` to null and cancels a
 * running sign-in when the active identity and server did not change.
 */
internal suspend fun applyShellRuntimeAccountUsageAuthEvent(
    event: build.raft.app.data.AuthSessionEvent,
    clearImmediateAndRead: suspend () -> Unit,
    refreshInPlace: suspend () -> Unit,
) {
    when {
        event.clearsRuntimeAccountUsageOwnerImmediately() -> clearImmediateAndRead()
        event.changesRuntimeAccountUsageOwner() -> refreshInPlace()
    }
}

/**
 * The shell's usage store, the signed-in owner and the current server, for a page that does
 * not provide [LocalMembersRuntimeAccountUsageAccess] itself. Sign-out and account switch clear
 * immediately before re-reading; metadata refreshes re-read in place without flashing null.
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
    val computersStore = remember(sharedStores, storeScope) { sharedStores.computersStore(storeScope) }
    var serverId by remember(authStore) { mutableStateOf<String?>(null) }
    var viewer by remember(authStore) { mutableStateOf<build.raft.app.data.RuntimeAccountUsageViewer?>(null) }
    LaunchedEffect(tracker, authStore) {
        suspend fun read() {
            val session = authStore.readSession()
            tracker.refresh()
            serverId = session?.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
            viewer = build.raft.app.data.runtimeAccountUsageViewerFor(session)
        }
        read()
        (authStore as? RaftAuthSessionEventSource)?.authSessionEvents?.collect { event ->
            applyShellRuntimeAccountUsageAuthEvent(
                event = event,
                clearImmediateAndRead = {
                    serverId = null
                    viewer = null
                    tracker.invalidate()
                    val session = authStore.readSession()
                    serverId = session?.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
                    viewer = build.raft.app.data.runtimeAccountUsageViewerFor(session)
                },
                refreshInPlace = ::read,
            )
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
    return remember(store, computersStore, owner, serverId, viewer, tracker, authHost) {
        MembersRuntimeAccountUsageAccess(
            store = store,
            owner = owner,
            serverId = serverId,
            onIdentityChanged = { scope.launch { tracker.invalidate() } },
            log = { message -> bridge.log(message) },
            viewer = viewer,
            auth = authHost,
            serverFeatureFlags = sharedStores.serverFeatureFlags,
            computersStateFlow = computersStore.state,
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
    private val signInFollowUpAttempts: Int = COMPUTER_RUNTIME_USAGE_SIGN_IN_FOLLOW_UP_ATTEMPTS,
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

    /** What the last Claude limit reset did, said once in the status line until the next Refresh. */
    var claudeLimitResetNote by mutableStateOf<ComputerRuntimeClaudeLimitResetText?>(null)
        private set

    /** The open "Use a limit reset" read (and the choice, if any), or null. */
    var claudeLimitResetsDialog by mutableStateOf<ComputerRuntimeClaudeLimitResetsListState?>(null)
        private set

    /** The open "Use a {kind}?" confirm, or null; it takes the read's place. */
    var claudeLimitResetConfirm by mutableStateOf<ComputerRuntimeClaudeLimitResetConfirmState?>(null)
        private set

    /**
     * `claude_limit_reset_v2` for this server (web `useServerFeatureFlag`), bound from composition
     * by [BindClaudeLimitResetFlag]; off (the default) offers nothing about Claude resets.
     */
    var claudeLimitResetFlagOn by mutableStateOf(false)

    /** The server whose flags decide what this card offers. */
    internal val serverId: String
        get() = key.serverId

    /** The Computer answered that Claude limit resets cannot run there (web `notSupportedSubject`). */
    private var claudeNotSupportedHere by mutableStateOf(false)

    private val claudeAccount: RuntimeAccountUsageAccount?
        get() = ((load as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available)?.snapshot?.accounts?.firstOrNull()

    private val claudeAccess: ComputerRuntimeClaudeLimitResetAccess?
        get() = computerRuntimeClaudeLimitResetAccess(key.provider, claudeLimitResetFlagOn, authView, load.claudeAtFiveHourLimit(), claudeNotSupportedHere)

    /** The card's one Claude entry (web `surfaceClaudeLimitReset`); null when nothing is offered. */
    val claudeLimitResetOffer: ComputerRuntimeClaudeLimitResetOffer?
        get() {
            val access = claudeAccess ?: return null
            val canUse = access == ComputerRuntimeClaudeLimitResetAccess.Use
            return ComputerRuntimeClaudeLimitResetOffer(canUse = canUse, primary = canUse && load.claudeAtFiveHourLimit())
        }

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
        claudeLimitResetNote = null
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
        limitResetDialog = dialog.copy(submitting = true, error = null, traceId = null, serverMessage = null)
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
                    limitResetDialog =
                        dialog.copy(
                            submitting = false,
                            error = result.refusal.limitResetError(),
                            traceId = result.refusal.traceId,
                            serverMessage = result.refusal.limitResetServerMessageOrNull(),
                        )
                }
                is RuntimeLimitResetLoad.Done -> {
                    handleLimitResetOutcome(result.reply.result, result.reply.snapshot)
                    val error = result.reply.result.limitResetError()
                    limitResetDialog = if (error == null) null else dialog.copy(submitting = false, error = error, traceId = null, serverMessage = null)
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

    /**
     * "Use a limit reset" (web `ClaudeLimitResetsDialog`, slock #15574): reads at once ("This takes
     * a moment"), asking no one first (artin, #proj-raft-computer:4e327d5c). At the 5-hour limit
     * (by the card's usage) someone who may use a reset reads with consent; below it a
     * `consent_required` answer (the card's usage was stale) gets one more read with consent. A
     * viewer who may not use resets never sends consent: at the limit nothing is sent at all.
     */
    fun openClaudeLimitReset() {
        val offer = claudeLimitResetOffer ?: return
        // One read per press: a tap while the read or its confirm is open does nothing.
        if (claudeLimitResetsDialog != null || claudeLimitResetConfirm != null) return
        val atLimit = load.claudeAtFiveHourLimit()
        if (atLimit && !offer.canUse) {
            claudeLimitResetsDialog =
                ComputerRuntimeClaudeLimitResetsListState(
                    phase = ComputerRuntimeClaudeLimitResetsPhase.Failed(ComputerRuntimeClaudeLimitResetText.Forbidden),
                    canUse = false,
                    atFiveHourLimit = true,
                )
            return
        }
        val opened =
            ComputerRuntimeClaudeLimitResetsListState(
                phase = ComputerRuntimeClaudeLimitResetsPhase.Reading(consent = atLimit),
                canUse = offer.canUse,
                atFiveHourLimit = atLimit,
            )
        claudeLimitResetsDialog = opened
        readClaudeLimitResets(opened, consent = atLimit)
    }

    private fun readClaudeLimitResets(opened: ComputerRuntimeClaudeLimitResetsListState, consent: Boolean) {
        scope.launch {
            var current = opened
            var withConsent = consent
            var result = store.readClaudeLimitResets(key, withConsent)
            log("computer_runtime_usage_claude_limit_resets_read consent=$withConsent result=${result.diagnosticsLabel()}")
            val consentRequired =
                ((result as? RuntimeLimitResetsReadLoad.Done)?.result as? RuntimeLimitResetsReadResult.Unsupported)?.reason == "consent_required"
            if (consentRequired && !withConsent && current.canUse && claudeLimitResetsDialog === current) {
                // The account reached the limit after the card's usage was read: read again, with consent, unasked.
                withConsent = true
                current = current.copy(phase = ComputerRuntimeClaudeLimitResetsPhase.Reading(consent = true))
                claudeLimitResetsDialog = current
                result = store.readClaudeLimitResets(key, withConsent)
                log("computer_runtime_usage_claude_limit_resets_read consent=true result=${result.diagnosticsLabel()}")
            }
            applyClaudeLimitResetsRead(current, result, withConsent)
        }
    }

    /**
     * What a read leaves (web `ClaudeLimitResetsDialog.read` and `claudeLimitResetReadStep`). A
     * read whose dialog was closed meanwhile shows nothing, except a read on Claude's older program
     * that used the 5-hour reset: the card still says so and reads the usage again.
     */
    private fun applyClaudeLimitResetsRead(
        opened: ComputerRuntimeClaudeLimitResetsListState,
        result: RuntimeLimitResetsReadLoad,
        consent: Boolean,
    ) {
        val open = claudeLimitResetsDialog === opened
        val canUse = opened.canUse
        fun fail(message: ComputerRuntimeClaudeLimitResetText, traceId: String? = null, serverMessage: String? = null) {
            if (open) claudeLimitResetsDialog = opened.copy(phase = ComputerRuntimeClaudeLimitResetsPhase.Failed(message, traceId, serverMessage))
        }
        when (result) {
            RuntimeLimitResetsReadLoad.Superseded -> {
                if (open) claudeLimitResetsDialog = null
                load = null
                onIdentityChanged()
            }
            is RuntimeLimitResetsReadLoad.Failed ->
                fail(
                    if (result.refusal.outcomeUnknown) {
                        ComputerRuntimeClaudeLimitResetText.ReadTimeout(viewOnly = !canUse)
                    } else {
                        result.refusal.claudeRequestError()
                    },
                    traceId = result.refusal.traceId,
                    serverMessage = result.refusal.limitResetServerMessageOrNull(),
                )
            is RuntimeLimitResetsReadLoad.Done ->
                when (val read = result.result) {
                    is RuntimeLimitResetsReadResult.Read -> {
                        // A read that used the 5-hour reset is said the same to anyone; otherwise a viewer who cannot use them gets the plain list.
                        if (!canUse && !read.fiveHourSpent) {
                            if (open) claudeLimitResetsDialog = opened.copy(phase = ComputerRuntimeClaudeLimitResetsPhase.Read(read))
                            return
                        }
                        when (val step = claudeLimitResetReadStep(read, opened.atFiveHourLimit)) {
                            is ComputerRuntimeClaudeLimitResetReadStep.Note -> {
                                if (open || step.usageChanged) claudeLimitResetNote = step.text
                                if (open) claudeLimitResetsDialog = null
                                // Never redeemed after it: the older program's read was the command. The usage says what is left.
                                if (step.usageChanged) startManualRefresh()
                            }
                            is ComputerRuntimeClaudeLimitResetReadStep.Confirm -> if (open) openClaudeLimitResetConfirm(step.status)
                            // Nothing chosen for the person: Use stays off until they pick a kind.
                            is ComputerRuntimeClaudeLimitResetReadStep.Choose ->
                                if (open) {
                                    claudeLimitResetsDialog = opened.copy(phase = ComputerRuntimeClaudeLimitResetsPhase.Choose(step.usable), selected = null)
                                }
                        }
                    }
                    is RuntimeLimitResetsReadResult.Unsupported ->
                        if (read.reason == "consent_required" && !consent) {
                            // Someone who cannot give it is not asked: nothing more is sent.
                            fail(ComputerRuntimeClaudeLimitResetText.Forbidden)
                        } else {
                            if (claudeLimitResetsNotSupportedHere(read.reason)) claudeNotSupportedHere = true
                            fail(read.claudeReadFailure(canUse))
                        }
                    is RuntimeLimitResetsReadResult.ReauthRequired -> fail(ComputerRuntimeClaudeLimitResetText.Reauth)
                    RuntimeLimitResetsReadResult.Unknown -> fail(ComputerRuntimeClaudeLimitResetText.ReadFailed(viewOnly = !canUse))
                }
        }
    }

    fun selectClaudeLimitResetKind(kind: RuntimeLimitResetKind) {
        val dialog = claudeLimitResetsDialog ?: return
        if (dialog.phase !is ComputerRuntimeClaudeLimitResetsPhase.Choose) return
        claudeLimitResetsDialog = dialog.copy(selected = kind)
    }

    /** "Use this reset": the chosen kind's confirm takes the choice's place. */
    fun useSelectedClaudeLimitReset() {
        val chosen = claudeLimitResetsDialog?.chosen ?: return
        openClaudeLimitResetConfirm(chosen)
    }

    /** Cancel (or Close) before anything was used: nothing is sent. */
    fun dismissClaudeLimitResets() {
        claudeLimitResetsDialog = null
    }

    private fun openClaudeLimitResetConfirm(status: RuntimeLimitResetKindStatus.Available) {
        val from = claudeLimitResetsDialog
        val account = claudeAccount?.identityLabel
        val atLimit = load.claudeAtFiveHourLimit()
        scope.launch {
            val idempotencyKey = store.claudeLimitResetAttemptKey(key, status.kind)
            // Closed meanwhile: nothing to confirm.
            if (claudeLimitResetsDialog !== from) return@launch
            claudeLimitResetsDialog = null
            claudeLimitResetConfirm =
                ComputerRuntimeClaudeLimitResetConfirmState(
                    kind = status.kind,
                    idempotencyKey = idempotencyKey,
                    account = account,
                    atFiveHourLimit = atLimit,
                    status = status,
                )
        }
    }

    /** "No, keep it" (or ×) before anything was sent: the card says the reset was kept, as Claude Code does. */
    fun dismissClaudeLimitResetConfirm() {
        val dialog = claudeLimitResetConfirm ?: return
        if (dialog.submitting) return
        if (!dialog.sent) claudeLimitResetNote = ComputerRuntimeClaudeLimitResetText.Kept(dialog.kind, dialog.status?.expiresText)
        claudeLimitResetConfirm = null
    }

    /**
     * "Yes, use my …" (web `ClaudeLimitResetConfirmDialog.handleConfirm`): uses the kind with the
     * dialog's key. An answer the card can say closes the confirm; otherwise it stays open with why
     * nothing was used. A request with no answer keeps the key for a retry.
     */
    fun confirmClaudeLimitReset() {
        val dialog = claudeLimitResetConfirm ?: return
        if (dialog.submitting) return
        claudeLimitResetConfirm = dialog.copy(submitting = true, sent = true, error = null, traceId = null, serverMessage = null)
        scope.launch {
            val result = store.redeemLimitReset(key, dialog.idempotencyKey, dialog.kind)
            log("computer_runtime_usage_claude_limit_reset kind=${dialog.kind.wire} result=${result.diagnosticsLabel()}")
            when (result) {
                RuntimeLimitResetLoad.Superseded -> {
                    claudeLimitResetConfirm = null
                    load = null
                    onIdentityChanged()
                }
                is RuntimeLimitResetLoad.Failed -> {
                    val error =
                        if (result.refusal.outcomeUnknown) ComputerRuntimeClaudeLimitResetText.Timeout else result.refusal.claudeRequestError()
                    claudeLimitResetConfirm =
                        dialog.copy(
                            submitting = false,
                            sent = true,
                            error = error,
                            traceId = result.refusal.traceId,
                            serverMessage = result.refusal.limitResetServerMessageOrNull(),
                        )
                }
                is RuntimeLimitResetLoad.Done -> {
                    val answer = result.reply.result
                    store.forgetClaudeLimitResetAttempt(key, dialog.kind)
                    if (answer is RuntimeLimitResetResult.Unsupported && claudeLimitResetsNotSupportedHere(answer.reason)) claudeNotSupportedHere = true
                    val note = claudeLimitResetNote(dialog.kind, answer)
                    if (note != null) {
                        claudeLimitResetNote = note
                        claudeLimitResetConfirm = null
                    } else {
                        claudeLimitResetConfirm =
                            dialog.copy(
                                submitting = false,
                                sent = true,
                                error = claudeLimitResetRefusal(dialog.kind, answer, dialog.atFiveHourLimit),
                                traceId = null,
                                serverMessage = null,
                            )
                    }
                    // Never assume the limits recovered: show the usage read right after it, or read again.
                    val snapshot = result.reply.snapshot
                    if (snapshot != null) {
                        followUp?.cancel()
                        load = RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Available(stale = false, snapshot = snapshot))
                        refresh = ComputerRuntimeUsageRefreshStatus.Idle
                    } else {
                        startManualRefresh()
                    }
                }
            }
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
                    // slock #14667: this Computer reads no usage for it; nothing to say or to wait for.
                    RuntimeAccountUsageRefreshState.Unsupported -> {
                        refresh = ComputerRuntimeUsageRefreshStatus.Idle
                        return
                    }
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

    /**
     * A sign-in on this card just succeeded (web `startFollowUpReads(afterSignIn)`, slock #15786): the
     * server asks the Computer for fresh usage, so read again until a fresh snapshot other than the one
     * shown lands, for about as long as the server's relay waits on a slow read, and end quietly
     * either way (nobody pressed Refresh).
     */
    fun readAfterSignIn() {
        val replacing = ((load as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available)?.snapshot?.collectedAt
        log("computer_runtime_usage_read_after_sign_in provider=${key.provider.wire}")
        followUp?.cancel()
        followUp =
            scope.launch {
                repeat(signInFollowUpAttempts) {
                    delay(followUpDelayMillis)
                    store.invalidate(key)
                    val next = (read(force = true) as? RuntimeAccountUsageLoad.Loaded)?.read
                    if (next is RuntimeAccountUsageRead.Available && !next.stale && next.snapshot.collectedAt != replacing) {
                        refresh = ComputerRuntimeUsageRefreshStatus.Idle
                        return@launch
                    }
                }
                refresh = ComputerRuntimeUsageRefreshStatus.Idle
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

/** Web `SIGN_IN_MAX_READ_ATTEMPTS`: after a sign-in, about the server's relay budget for a usage read (25 s). */
internal const val COMPUTER_RUNTIME_USAGE_SIGN_IN_FOLLOW_UP_ATTEMPTS: Int = 21

/**
 * Binds a card's sign-in to its usage (web `RuntimeSignInDialog onSignedIn={handleSignedIn}`, slock
 * #15786): a sign-in that succeeds reads the usage again at once.
 */
@Composable
internal fun BindSignedInUsageRead(usage: ComputerRuntimeUsageController?, auth: ComputerRuntimeAuthController?) {
    DisposableEffect(usage, auth) {
        if (usage == null || auth == null) return@DisposableEffect onDispose {}
        val hook: () -> Unit = { usage.readAfterSignIn() }
        auth.onSignedIn = hook
        onDispose { if (auth.onSignedIn === hook) auth.onSignedIn = null }
    }
}

private fun RuntimeLimitResetsReadLoad.diagnosticsLabel(): String =
    when (this) {
        is RuntimeLimitResetsReadLoad.Done ->
            when (val read = result) {
                is RuntimeLimitResetsReadResult.Read -> "read" + (if (read.legacy) ":legacy" else "") + (if (read.fiveHourSpent) ":spent" else "")
                is RuntimeLimitResetsReadResult.ReauthRequired -> "reauth_required:${read.reason}"
                is RuntimeLimitResetsReadResult.Unsupported -> "unsupported:${read.reason}"
                RuntimeLimitResetsReadResult.Unknown -> "unknown"
            }
        is RuntimeLimitResetsReadLoad.Failed -> "failed:${refusal.status ?: "none"}:${refusal.code ?: "none"}"
        RuntimeLimitResetsReadLoad.Superseded -> "superseded"
    }

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
 * any account or window is not ok (a full window the account's credits carry is ok, slock
 * #15374); `success` otherwise.
 */
internal fun RuntimeAccountUsageLoad?.computerRuntimeUsageHealth(): RaftStatusVariant? {
    val read = (this as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available ?: return null
    val attention =
        read.stale ||
            read.snapshot.accounts.any { it.needsUsageAttention }
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
    BindClaudeLimitResetFlag(controller, access?.serverFeatureFlags)
    LaunchedEffect(controller) { controller?.start() }
    // Web: the card reads the Computer's sign-in state on mount, so a signed-out chip says so at rest.
    val auth = rememberComputerRuntimeAuthController(access, machineId, runtimeId, readNow = true)
    // Every runtime with a sign-in (or a status-only card) gets a card: Antigravity is no longer
    // gated by the server (slock #15445).
    val authBinding = auth?.let { ComputerRuntimeAuthBinding(it, runtimeName, computerName, agents, access?.auth?.openUrl ?: {}) }
    // A Computer that reads no usage for this runtime (`unsupported`, slock #14667): as a runtime with none.
    val usageController = controller?.takeUnless { it.load.unsupportedRead }
    // A runtime with neither usage nor a sign-in has no card.
    if (usageController == null && authBinding == null) {
        plain()
        return
    }
    val load = usageController?.load
    val offered = (usageController != null && load !is RuntimeAccountUsageLoad.Forbidden) || authBinding != null
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
    val chip: @Composable (onClick: (() -> Unit)?) -> Unit = { onClick ->
        // A badge rendered as a button: its click opens the usage (a card for a mouse, a sheet for
        // a finger); its long press copies the runtime name. One system gesture tells them apart.
        RaftBadge(
            text = text,
            modifier = Modifier.testTag(if (signedOut) COMPUTER_RUNTIME_CHIP_SIGNED_OUT_TAG else COMPUTER_RUNTIME_CHIP_TAG),
            copyText = label,
            appearance = RaftBadgeAppearance.Soft,
            variant = if (signedOut) RaftBadgeVariant.Warning else RaftBadgeVariant.Information,
            trailing = healthDot,
            onClick = onClick,
        )
    }
    // Web `RuntimeUsageHoverCard` (artin 11-02): a mouse resting on the chip shows the usage card
    // and its click pins it; a finger's tap opens the usage sheet.
    ComputerRuntimeUsagePreviewCard(
        runtimeLabel = label,
        controller = usageController,
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
        get() = controller.cardState
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
    BindClaudeLimitResetFlag(controller, access.serverFeatureFlags)
    // The sheet reads the Computer's sign-in state as it opens (web: the card's own read).
    val auth = runtimeId?.let { rememberComputerRuntimeAuthController(access, key.machineId, it, readNow = true) }
    LaunchedEffect(controller) {
        controller.onOpen()
        // The cooldown clock ticks only while the app is in front (a hidden activity keeps its composition).
        repeatWhileProcessForeground(intervalMillis = 1_000L, runImmediatelyOnStart = true) {
            controller.updateCooldown()
        }
    }
    // A sign-in from this sheet reads its usage again at once (slock #15786).
    BindSignedInUsageRead(controller, auth)
    val timeContext = currentRaftTimeContextProvider?.current
    val authState = auth?.cardState
    // A child dialog (sign-in, sign-out, limit reset) takes over from the sheet so two modal
    // windows never stack over the host Activity across an external browser return.
    val childDialogOpen =
        auth?.dialog != null ||
            controller.limitResetDialog != null ||
            controller.claudeLimitResetsDialog != null ||
            controller.claudeLimitResetConfirm != null
    if (!childDialogOpen) {
        ComputerRuntimeUsageSheet(
            runtimeLabel = runtimeLabel,
            state = controller.sheetState(runtimeVersion),
            timeContext = timeContext,
            onRefresh = controller::refreshNow,
            onUseLimitReset = controller::openLimitReset,
            onDismiss = onDismiss,
            auth = authState,
            onClaudeLimitReset = controller::openClaudeLimitReset,
            authRuntimeName = runtimeName,
            onSignIn = { auth?.openSignIn() },
            onSignOut = { auth?.openSignOut() },
            onSignOutProvider = { provider -> auth?.openSignOut(provider) },
        )
    }
    ComputerRuntimeLimitResetDialogHost(runtimeLabel, controller)
    if (auth != null) ComputerRuntimeAuthDialogHost(auth, runtimeName, computerName, agents, access.auth?.openUrl ?: {})
}

/**
 * Binds `claude_limit_reset_v2` for the controller's server (web `useServerFeatureFlag`; not
 * latched, so turning it off hides the entry again). The server gates the same key on its side.
 */
@Composable
internal fun BindClaudeLimitResetFlag(controller: ComputerRuntimeUsageController?, flags: SharedServerFeatureFlags?) {
    if (controller == null) return
    val revision = flags?.snapshotRevision?.collectAsState()?.value
    val on =
        remember(flags, controller, revision) {
            val serverId = controller.serverId.trim()
            serverId.isNotEmpty() && flags?.isEnabled(ServerId(serverId), CLAUDE_LIMIT_RESET_FEATURE_FLAG_KEY) == true
        }
    SideEffect { if (controller.claudeLimitResetFlagOn != on) controller.claudeLimitResetFlagOn = on }
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
        claudeLimitReset = claudeLimitResetOffer,
        claudeLimitResetNote = claudeLimitResetNote,
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
    // Claude: the read (and the choice), then the confirm of one kind in its place.
    controller.claudeLimitResetsDialog?.let { dialog ->
        RaftComputersTheme {
            ComputerRuntimeClaudeLimitResetsDialog(
                state = dialog,
                onSelect = controller::selectClaudeLimitResetKind,
                onUse = controller::useSelectedClaudeLimitReset,
                onDismiss = controller::dismissClaudeLimitResets,
            )
        }
    }
    controller.claudeLimitResetConfirm?.let { dialog ->
        RaftComputersTheme {
            ComputerRuntimeClaudeLimitResetConfirmDialog(
                state = dialog,
                onConfirm = controller::confirmClaudeLimitReset,
                onDismiss = controller::dismissClaudeLimitResetConfirm,
            )
        }
    }
}

/** The read snapshot, when there is one. */
internal val ComputerRuntimeUsageSheetState.available: RuntimeAccountUsageRead.Available?
    get() = (load as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available

/**
 * The usage block draws the Computer's own account (web `usageShowsAccount`): the sign-in line then
 * does not name it, and Sign out goes in that account's menu.
 */
internal val ComputerRuntimeUsageSheetState.showsAccount: Boolean
    get() = available?.snapshot?.accounts?.any { it.isDefaultAccount } == true

/** What the usage sheet shows (web `Surface` props). */
internal data class ComputerRuntimeUsageSheetState(
    val load: RuntimeAccountUsageLoad?,
    val loading: Boolean,
    val refresh: ComputerRuntimeUsageRefreshStatus,
    val cooldownLeftMillis: Long,
    /** The runtime CLI version the Computer reported (web `runtimeVersion`); null: no version line. */
    val runtimeVersion: String? = null,
    /** "Use a reset" (slock #13504); null when there is no reset to offer or the viewer may not act. */
    val limitReset: ComputerRuntimeLimitResetOffer? = null,
    /** What the last limit reset did, in the status line. */
    val limitResetNote: ComputerRuntimeLimitResetNote? = null,
    /** Claude limit resets on the card (slock #14336); null when nothing is offered. */
    val claudeLimitReset: ComputerRuntimeClaudeLimitResetOffer? = null,
    /** What the last Claude limit reset did, in the status line. */
    val claudeLimitResetNote: ComputerRuntimeClaudeLimitResetText? = null,
)

/**
 * Web phone layout: the usage surface in a bottom sheet (`BottomSheet`, below md): the same parts as
 * the card ([ComputerRuntimeUsageCard]) under the sheet's title, and Close.
 */
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
    onSignOutProvider: (RuntimeAuthSignedInProvider) -> Unit = {},
    onClaudeLimitReset: () -> Unit = {},
) {
    // Signed out: there is no usage to show, only the way to sign in (web `showsUsage`).
    val signedOut = auth?.signedOut == true
    RaftSheet(
        onDismissRequest = onDismiss,
        title = if (signedOut) authRuntimeName else stringResource(Res.string.settings_computers_runtime_usage_title, runtimeLabel),
        actions = {
            RaftButton(
                text = stringResource(Res.string.common_close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
        },
        content = {
            ComputerRuntimeUsageSurfaceContent(
                state = state,
                timeContext = timeContext,
                inset = 0.dp,
                showsUsage = !signedOut,
                auth = auth,
                onRefresh = onRefresh,
                onUseLimitReset = onUseLimitReset,
                onClaudeLimitReset = onClaudeLimitReset,
                onSignIn = onSignIn,
                onSignOut = onSignOut,
                onSignOutProvider = onSignOutProvider,
                head = {
                    ComputerRuntimeCardHeadLines(state.runtimeVersion, auth, usageShowsAccount = !signedOut && state.showsAccount, authOnly = false)
                },
            )
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
    auth: ComputerRuntimeAuthCardState? = null,
) {
    RaftSheetSurface(
        title = stringResource(Res.string.settings_computers_runtime_usage_title, runtimeLabel),
        modifier = modifier,
        actions = {
            RaftButton(
                text = stringResource(Res.string.common_close),
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
        },
        content = {
            ComputerRuntimeUsageSurfaceContent(
                state = state,
                timeContext = timeContext,
                inset = 0.dp,
                showsUsage = true,
                auth = auth,
                onRefresh = {},
                onUseLimitReset = {},
                onClaudeLimitReset = {},
                onSignIn = {},
                onSignOut = {},
                onSignOutProvider = {},
                head = { ComputerRuntimeCardHeadLines(state.runtimeVersion, auth, usageShowsAccount = state.showsAccount, authOnly = false) },
            )
        },
    )
}

/**
 * Web `statusText`: only a state worth saying, under the usage: what the last limit reset did, a
 * refresh refused or unanswered, or the Computer offline. One in flight says nothing (Refresh's
 * spinner shows it), and neither does a quiet card.
 */
@Composable
private fun computerRuntimeUsageStatusText(state: ComputerRuntimeUsageSheetState): String? =
    when {
        state.claudeLimitResetNote != null -> state.claudeLimitResetNote.text()
        state.limitResetNote != null -> state.limitResetNote.text()
        state.refresh == ComputerRuntimeUsageRefreshStatus.Unconfirmed -> stringResource(Res.string.settings_computers_runtime_usage_refresh_unconfirmed)
        state.refresh == ComputerRuntimeUsageRefreshStatus.Timeout -> stringResource(Res.string.settings_computers_runtime_usage_refresh_timeout)
        state.cooldownLeftMillis > 0L ->
            stringResource(Res.string.settings_computers_runtime_usage_refresh_cooldown_countdown, ((state.cooldownLeftMillis + 999L) / 1_000L).toInt())
        state.refresh == ComputerRuntimeUsageRefreshStatus.ComputerOffline -> stringResource(Res.string.settings_computers_runtime_usage_computer_offline)
        else -> null
    }

/**
 * Web `RuntimeUsageContents`: the usage block (also the agent move dialog's). Nothing read yet says so;
 * otherwise an out-of-date notice over the sunken accounts, each account, and "Updated …" ([metadata]
 * in its place) with [footerAction] (Refresh) beside it. [accountMenu] and [accountActions] go with
 * the Computer's own account. [inset] is the side padding (16 in a card, 0 in a sheet or a dialog).
 */
@Composable
internal fun ComputerRuntimeUsageContents(
    state: ComputerRuntimeUsageSheetState,
    timeContext: RaftTimeContext?,
    inset: Dp = 0.dp,
    metadata: String? = null,
    accountMenu: (@Composable () -> Unit)? = null,
    accountActions: (@Composable () -> Unit)? = null,
    footerAction: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val bare: @Composable (@Composable () -> Unit) -> Unit = { body ->
        body()
        if (footerAction != null) {
            RaftSeparator()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) { footerAction() }
        }
    }
    val load = state.load
    val available = state.available
    when {
        load == null && state.loading ->
            bare {
                RaftText(
                    stringResource(Res.string.settings_computers_runtime_usage_reading),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 20.dp),
                    style = typography.callout,
                    color = colors.foregroundMuted,
                )
            }
        load is RuntimeAccountUsageLoad.Failed ->
            bare {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.padding(top = 2.dp)) {
                        CompositionLocalProvider(LocalRaftContentColor provides colors.warningStrong) {
                            RaftComputersIcon(RaftLucideIconName.TriangleAlert)
                        }
                    }
                    RaftText(stringResource(Res.string.settings_computers_runtime_usage_read_failed), style = typography.callout, color = colors.warningStrong)
                }
            }
        available == null ->
            bare {
                RaftText(
                    stringResource(Res.string.settings_computers_runtime_usage_no_snapshot),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 20.dp),
                    style = typography.callout.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                )
            }
        else -> {
            val stale = available.stale
            // The Computer's own account (not an agent's own folder's): where its menu and limit actions go.
            val own = available.snapshot.accounts.firstOrNull { it.isDefaultAccount }?.accountKey
            // An out-of-date snapshot sits on the sunken surface under an orange-soft notice: recoverable, so warning, never red.
            Column(Modifier.fillMaxWidth().then(if (stale) Modifier.background(colors.fillMuted) else Modifier)) {
                if (stale) {
                    RaftText(
                        stringResource(Res.string.settings_computers_runtime_usage_stale),
                        modifier = Modifier.fillMaxWidth().background(colors.warningSoft).padding(horizontal = maxOf(inset, 12.dp), vertical = 8.dp),
                        style = typography.caption.copy(fontWeight = FontWeight.Bold),
                        color = colors.foreground,
                    )
                    RaftSeparator()
                }
                available.snapshot.accounts.forEachIndexed { index, account ->
                    if (index > 0) RaftSeparator()
                    val isOwn = account.accountKey == own
                    Column(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 12.dp)) {
                        ComputerRuntimeUsageAccountBlock(
                            account = account,
                            timeContext = timeContext,
                            menu = if (isOwn) accountMenu else null,
                            actions = if (isOwn) accountActions else null,
                        )
                    }
                }
                RaftSeparator()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val updated =
                        available.snapshot.collectedAt
                            ?.let { raftRelativeTimestampLabel(it, timeContext, clampFuture = true) }
                            ?.takeIf { it.isNotEmpty() }
                            ?: stringResource(Res.string.settings_computers_runtime_usage_recently)
                    RaftText(
                        metadata ?: stringResource(Res.string.settings_computers_runtime_usage_metadata, updated),
                        modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                        style = typography.caption,
                        color = colors.foregroundHint,
                    )
                    footerAction?.invoke()
                }
            }
        }
    }
}

/**
 * One account on the usage card (web `RuntimeUsageContents`, slock #15482, #15483, #15557, #15395): the
 * plan as a heading ("Team plan") with the masked email under it, a warning chip only when something is
 * wrong (no "ok"), then its windows, why an unsupported account has no reading, and one facts line
 * (resets left · credits). Error and "sign in again" draw no last-known meters: the chip says it.
 * [menu] (Sign out) ends the heading row and [actions] go under it, for the Computer's own account.
 */
@Composable
internal fun ComputerRuntimeUsageAccountBlock(
    account: RuntimeAccountUsageAccount,
    timeContext: RaftTimeContext?,
    menu: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                RaftText(
                    computerRuntimePlanHeading(account.planLabel),
                    modifier = Modifier.testTag(COMPUTER_RUNTIME_ACCOUNT_PLAN_TAG),
                    style = typography.callout.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                account.identityLabel?.let { identity ->
                    RaftText(
                        identity,
                        modifier = Modifier.padding(top = 2.dp),
                        style = typography.monoMeta,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val badge = account.usageBadge()
            if (badge != null || menu != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Raft DS StatusChip: soft wash, a square pixel, one word. Only for a problem.
                    if (badge != null) {
                        RaftBadge(
                            text = badge.label(),
                            modifier = Modifier.testTag(COMPUTER_RUNTIME_ACCOUNT_HEALTH_TAG),
                            appearance = RaftBadgeAppearance.Soft,
                            variant = RaftBadgeVariant.Warning,
                            icon = { RaftStatusDot(variant = RaftStatusVariant.Warning, size = RaftStatusSize.Md) },
                        )
                    }
                    menu?.invoke()
                }
            }
        }
        when (account.health) {
            RuntimeAccountUsageHealth.Unsupported ->
                account.unsupportedReasonLine?.let { reason ->
                    RaftText(
                        stringResource(reason.lineText()),
                        modifier = Modifier.padding(top = 12.dp).testTag(COMPUTER_RUNTIME_ACCOUNT_UNSUPPORTED_REASON_TAG),
                        style = typography.footnote,
                        color = colors.foregroundMuted,
                    )
                }
            // Its chip says it ("error", "sign in again"; Sign in under the Computer's own account): no
            // sentence repeating it, and no last-known meters until the provider reads it again.
            RuntimeAccountUsageHealth.Error, RuntimeAccountUsageHealth.ReauthRequired -> Unit
            else ->
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Claude's OAuth apps' window (third-party apps' use) is never listed (web #14605).
                    account.windows.filter { !it.claudeOAuthApps }.forEach { window ->
                        ComputerRuntimeUsageWindowRow(window, timeContext, fill = account.windowFill(window))
                    }
                }
        }
        ComputerRuntimeUsageFactsLine(account)
        if (actions != null) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp)) { actions() }
        }
    }
}

/**
 * Web `runtime-account-facts` (slock #15482): always in sight, the resets the account has left and its
 * credits, one line of secondary text under the meters (two numbers, not chips that look pressable).
 * No credits left reads orange.
 */
@Composable
private fun ComputerRuntimeUsageFactsLine(account: RuntimeAccountUsageAccount) {
    val resets = account.limitResetsAvailable
    val credits = account.creditsLine
    if (resets == null && credits == null) return
    val warning = RaftTheme.colors.warningStrong
    val resetsText = resets?.let { computerRuntimeLimitResetsLeftText(it) }
    val creditsText = credits?.let { computerRuntimeUsageCreditsText(it) }
    val line =
        buildAnnotatedString {
            if (resetsText != null) append(resetsText)
            if (resetsText != null && creditsText != null) append(" · ")
            if (creditsText != null) {
                val at = length
                append(creditsText)
                if (credits == RuntimeAccountUsageCreditsLine.None) addStyle(SpanStyle(color = warning), at, length)
            }
        }
    RaftText(
        line,
        modifier = Modifier.padding(top = 12.dp).testTag(COMPUTER_RUNTIME_ACCOUNT_FACTS_TAG),
        style = RaftTheme.typography.footnote,
        color = RaftTheme.colors.foregroundMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Web `formatRuntimePlanHeading` (slock #15557): "Team plan" / "Team 套餐", or "Runtime account" with none. */
@Composable
internal fun computerRuntimePlanHeading(planLabel: String?): String =
    when (val heading = runtimePlanHeading(planLabel)) {
        RuntimePlanHeading.Fallback -> stringResource(Res.string.settings_computers_runtime_usage_account_fallback)
        is RuntimePlanHeading.Named -> heading.name
        is RuntimePlanHeading.Plan -> stringResource(Res.string.settings_computers_runtime_usage_plan_heading, heading.plan)
    }

/** Web `unsupportedReasonTextId`: the line under an unsupported account saying why. */
private fun RuntimeAccountUsageUnsupportedReason.lineText(): StringResource =
    when (this) {
        RuntimeAccountUsageUnsupportedReason.UnsupportedAuthMode -> Res.string.settings_computers_runtime_usage_unsupported_reason_auth_mode
        RuntimeAccountUsageUnsupportedReason.UnsupportedAuthStorage -> Res.string.settings_computers_runtime_usage_unsupported_reason_auth_storage
        RuntimeAccountUsageUnsupportedReason.AuthConfigurationUnavailable -> Res.string.settings_computers_runtime_usage_unsupported_reason_auth_configuration
        RuntimeAccountUsageUnsupportedReason.EndpointUnavailable -> Res.string.settings_computers_runtime_usage_unsupported_reason_endpoint
        RuntimeAccountUsageUnsupportedReason.UnsupportedInstallation -> Res.string.settings_computers_runtime_usage_unsupported_reason_installation
        // Never asked for ([unsupportedReasonLine] leaves these to the chip); the closest words.
        RuntimeAccountUsageUnsupportedReason.VersionUnsupported,
        RuntimeAccountUsageUnsupportedReason.CapabilityUnavailable,
        RuntimeAccountUsageUnsupportedReason.QuotaUnavailable -> Res.string.settings_computers_runtime_usage_unsupported_reason_installation
    }

/**
 * One usage window: "X% used · resets …" or "Used up", as the web `UsageWindowMeter` (raft-ui 0.50);
 * a full window the account's credits carry reads "Used up · using credits" (slock #15374). The 5-hour
 * and weekly windows are named in the reader's language (slock #15482). [fill] is the account's reading
 * of it ([windowFill]).
 */
@Composable
internal fun ComputerRuntimeUsageWindowRow(
    window: RuntimeAccountUsageWindow,
    timeContext: RaftTimeContext?,
    fill: RuntimeAccountUsageWindowFill?,
) {
    val label =
        when (window.cardWindowKind()) {
            RuntimeQuotaWindowKind.FiveHour -> stringResource(Res.string.runtime_quota_window_five_hour)
            RuntimeQuotaWindowKind.Week -> stringResource(Res.string.runtime_quota_window_week)
            null -> window.label
        }
    val ratio = window.usedRatio
    if (fill == null || ratio == null) {
        // Never a bar: a window we cannot read is not "0% used".
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftText(
                label,
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
    val usedUp = fill != RuntimeAccountUsageWindowFill.Used
    val reset =
        window.resetsAt?.let { resetsAt ->
            computerRuntimeUsageResetText(resetsAt, timeContext) ?: stringResource(Res.string.settings_computers_runtime_usage_later)
        }
    val percentUsed = "${runtimeUsagePercentUsed(ratio)}%"
    val valueText =
        when {
            fill == RuntimeAccountUsageWindowFill.OnCredits && reset == null ->
                stringResource(Res.string.settings_computers_runtime_usage_window_on_credits_reset_unavailable)
            fill == RuntimeAccountUsageWindowFill.OnCredits -> stringResource(Res.string.settings_computers_runtime_usage_window_on_credits, reset.orEmpty())
            usedUp && reset == null -> stringResource(Res.string.settings_computers_runtime_usage_window_used_up_reset_unavailable)
            usedUp -> stringResource(Res.string.settings_computers_runtime_usage_window_used_up, reset.orEmpty())
            reset == null -> stringResource(Res.string.settings_computers_rack_percent_used, percentUsed)
            else -> stringResource(Res.string.settings_computers_runtime_usage_window_used, percentUsed, reset)
        }
    // raft-ui 0.50 Meter: fills with what is used, warning from 80% used, a full warning bar
    // when used up.
    RaftMeter(
        used = if (usedUp) 100f else (ratio * 100.0).toFloat(),
        valueText = valueText,
        label = label,
        size = RaftMeterSize.Sm,
    )
}

/**
 * Web `usageResetText` (slock #14717): a usage window's reset wherever one is said (the usage card,
 * the profile row): to come, counted as the rack's cell counts it ([raftRackReset]: down, to the
 * largest unit, "in 5 days"); already passed, how long ago ("3 hours ago"). Null when not a time.
 */
@Composable
internal fun computerRuntimeUsageResetText(resetsAt: String, timeContext: RaftTimeContext?): String? {
    if (parseRaftTimestampEpochMillisOrNull(resetsAt, 0) == null) return null
    val nowMillis = currentRaftTimeContext(timeContext)?.nowEpochMillis ?: defaultNowMillis()
    val reset = raftRackReset(resetsAt, nowMillis)
    if (reset != null) return RaftRelativeTimeLabel.Relative(reset.count, reset.unit).localized()
    return raftRelativeTimestampLabel(resetsAt, timeContext).takeIf { it.isNotEmpty() }
}

/** The account's credits: "30,784 credits", "Unlimited credits", "Credits available" or "No credits left". */
@Composable
internal fun computerRuntimeUsageCreditsText(line: RuntimeAccountUsageCreditsLine): String =
    when (line) {
        is RuntimeAccountUsageCreditsLine.Balance ->
            stringResource(
                Res.string.settings_computers_runtime_usage_credits_balance,
                computerRuntimeUsageCreditsBalanceDisplay(line.balance, androidx.compose.ui.text.intl.Locale.current.language),
            )
        RuntimeAccountUsageCreditsLine.Unlimited -> stringResource(Res.string.settings_computers_runtime_usage_credits_unlimited)
        RuntimeAccountUsageCreditsLine.Available -> stringResource(Res.string.settings_computers_runtime_usage_credits_available)
        RuntimeAccountUsageCreditsLine.None -> stringResource(Res.string.settings_computers_runtime_usage_credits_none)
    }

/**
 * Web runtimeUsageCredits.ts: a plain integer balance (`^-?\d{1,15}$`) is shown with the locale's digit
 * grouping ("30,784"); anything else is shown as the runtime sent it. Separators follow the web's ICU output
 * for the app's languages: "," (en, zh, ja, ko), "." (es, pt), a no-break space (ru); Spanish keeps four digits ungrouped.
 */
internal fun computerRuntimeUsageCreditsBalanceDisplay(balance: String, language: String): String {
    if (!Regex("^-?\\d{1,15}$").matches(balance)) return balance
    val negative = balance.startsWith("-")
    val digits = balance.removePrefix("-").trimStart('0').ifEmpty { "0" }
    val separator =
        when (language.lowercase()) {
            "es", "pt" -> "."
            "ru" -> " "
            else -> ","
        }
    // ICU's Spanish leaves a four-digit number ungrouped ("1234", "12.345").
    if (language.lowercase() == "es" && digits.length == 4) return balance.trimStart('0').ifEmpty { "0" }.let { if (negative) "-${it.removePrefix("-")}" else it }
    val grouped = digits.reversed().chunked(3).joinToString(separator).reversed()
    return if (negative && grouped != "0") "-$grouped" else grouped
}

internal const val COMPUTER_RUNTIME_USAGE_CREDITS_TAG = "runtime-usage-credits"
internal const val COMPUTER_RUNTIME_ACCOUNT_PLAN_TAG = "runtime-account-plan"
internal const val COMPUTER_RUNTIME_ACCOUNT_HEALTH_TAG = "runtime-account-health"
internal const val COMPUTER_RUNTIME_ACCOUNT_UNSUPPORTED_REASON_TAG = "runtime-account-unsupported-reason"
internal const val COMPUTER_RUNTIME_ACCOUNT_FACTS_TAG = "runtime-account-facts"
internal const val COMPUTER_RUNTIME_ACCOUNT_MENU_TAG = "runtime-account-menu"
internal const val COMPUTER_RUNTIME_USAGE_REFRESH_TAG = "runtime-usage-refresh"
internal const val COMPUTER_RUNTIME_USAGE_ACTIONS_TAG = "runtime-usage-actions"
internal const val COMPUTER_RUNTIME_LIMIT_CALLOUT_TAG = "runtime-limit-callout"

/** Web `runtimeUsagePercentUsed`: `round(usedRatio * 100)`. */
internal fun runtimeUsagePercentUsed(usedRatio: Double): Int = kotlin.math.round(usedRatio * 100.0).toInt()

@Composable
private fun RuntimeAccountUsageBadge.label(): String =
    stringResource(
        when (this) {
            RuntimeAccountUsageBadge.RateLimited -> Res.string.settings_computers_runtime_usage_health_rate_limited
            RuntimeAccountUsageBadge.ReauthRequired -> Res.string.settings_computers_runtime_usage_health_reauth_required
            RuntimeAccountUsageBadge.Unsupported -> Res.string.settings_computers_runtime_usage_health_unsupported
            RuntimeAccountUsageBadge.Error -> Res.string.settings_computers_runtime_usage_health_error
            RuntimeAccountUsageBadge.Unknown -> Res.string.settings_computers_runtime_usage_health_unknown
            RuntimeAccountUsageBadge.SomeModelsUsedUp -> Res.string.settings_computers_runtime_usage_health_some_models_used_up
            RuntimeAccountUsageBadge.MaybeOnCredits -> Res.string.settings_computers_runtime_usage_health_maybe_on_credits
            RuntimeAccountUsageBadge.NoUsageData -> Res.string.settings_computers_runtime_usage_health_no_usage_data
            RuntimeAccountUsageBadge.UpdateRequired -> Res.string.settings_computers_runtime_usage_health_update_required
        }
    )

/**
 * Web `RuntimeUsageHoverCard`: the usage card on a runtime chip or a rack usage line. A mouse or
 * trackpad pointer resting on [trigger] for 200ms opens it as a card, which closes 160ms after it
 * leaves; a mouse click on [trigger] opens it at once and pins it (it then stays until a click
 * outside, Escape or its close button), so the pointer can reach Refresh. A finger's (or a pen's)
 * tap opens the usage as a bottom sheet instead (web: a Drawer on a phone): [onTap] when given (the
 * Computers page's own sheet), else this one's. The input decides, per click, not the device: a
 * phone with a mouse plugged in gets the card from the mouse and the sheet from a finger. A key or
 * a screen reader (no pointer) gets what the device's usual input gets ([RaftTheme.pointerInput]).
 * Opening reads through the shared 60 s cache.
 *
 * [trigger] is the button: give its own click (`onClick` of a [RaftBadge], [raftClickable]) the
 * click it is passed. The card only listens, so a press that scrolls the page or stops its fling,
 * a long press (a chip's copy) or a drag is no click, by the system's rules. [enabled] false shows
 * [trigger] alone, with no click.
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
    /** A finger's tap, when the page opens the usage itself; null opens this card's sheet. */
    onTap: (() -> Unit)? = null,
    state: RaftPreviewCardState = rememberRaftPreviewCardState(closeDelayMillis = ComputerRuntimeUsagePreviewCardCloseDelayMillis),
    trigger: @Composable (onClick: (() -> Unit)?) -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val timeContext = currentRaftTimeContextProvider?.current
    if (state.isOpen || sheetOpen) {
        LaunchedEffect(controller, auth) {
            controller?.onOpen()
            // Web `openNow`: the sign-in state is read again with the usage.
            auth?.controller?.read()
            if (controller != null) {
                repeatWhileProcessForeground(intervalMillis = 1_000L, runImmediatelyOnStart = true) {
                    controller.updateCooldown()
                }
            }
        }
    }
    // A sign-in from this card reads its usage again at once (slock #15786).
    BindSignedInUsageRead(controller, auth?.controller)
    val mouseFirst = RaftTheme.pointerInput == RaftPointerInput.Fine
    val onClick: (() -> Unit)? =
        if (!enabled) {
            null
        } else {
            {
                if (state.isMouseClick(noPointer = mouseFirst)) {
                    state.pin()
                } else {
                    state.dismiss()
                    if (onTap == null) sheetOpen = true
                    onTap?.invoke()
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
                    onClaudeLimitReset = {
                        state.dismiss()
                        controller.openClaudeLimitReset()
                    },
                    auth = auth?.cardState,
                    authRuntimeName = auth?.runtimeName ?: runtimeLabel,
                    onSignIn = onSignIn,
                    onSignOut = onSignOut,
                    onSignOutProvider = onSignOutProvider,
                    // The account menu opens in a popup outside the card: a hover card pins first (web #15786).
                    onAccountMenuOpen = state::pin,
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
        trigger = { trigger(onClick) },
    )
    if (sheetOpen && enabled) {
        val closeSheet = { sheetOpen = false }
        if (controller != null) {
            ComputerRuntimeUsageSheet(
                runtimeLabel = runtimeLabel,
                state = controller.sheetState(runtimeVersion),
                timeContext = timeContext,
                onRefresh = controller::refreshNow,
                onUseLimitReset = {
                    closeSheet()
                    controller.openLimitReset()
                },
                onDismiss = closeSheet,
                auth = auth?.cardState,
                authRuntimeName = auth?.runtimeName ?: runtimeLabel,
                onSignIn = {
                    closeSheet()
                    authController?.openSignIn()
                },
                onSignOut = {
                    closeSheet()
                    authController?.openSignOut()
                },
                onSignOutProvider = { provider ->
                    closeSheet()
                    authController?.openSignOut(provider)
                },
                onClaudeLimitReset = {
                    closeSheet()
                    controller.openClaudeLimitReset()
                },
            )
        } else if (auth != null) {
            ComputerRuntimeAuthOnlySheet(
                runtimeName = auth.runtimeName,
                runtimeVersion = runtimeVersion,
                auth = auth.cardState,
                onDismiss = closeSheet,
                onSignIn = {
                    closeSheet()
                    authController?.openSignIn()
                },
                onSignOut = {
                    closeSheet()
                    authController?.openSignOut()
                },
                onSignOutProvider = { provider ->
                    closeSheet()
                    authController?.openSignOut(provider)
                },
            )
        }
    }
    if (controller != null) ComputerRuntimeLimitResetDialogHost(runtimeLabel, controller)
    if (auth != null) ComputerRuntimeAuthDialogHost(auth.controller, auth.runtimeName, auth.computerName, auth.agents, auth.openUrl)
}

/**
 * Web `RuntimeUsageSurface` for a runtime with no usage to read (Qoder, Pi, Antigravity) or one signed
 * out: its sign-in alone — the title with "Signed in" or "Signed out" (Pi: its stored providers), who
 * was signed in last, the version, the sign-in action, and who may see it.
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
    ComputerRuntimeCardHeader(title = runtimeName, runtimeVersion = runtimeVersion, onClose = onClose, auth = auth, usageShowsAccount = false, authOnly = true)
    ComputerRuntimeAuthOnlyBody(auth, inset = 16.dp, onSignIn, onSignOut, onSignOutProvider)
}

/** Web `Surface` without usage: the stored providers, the sign-in action (full width), and who may see it. */
@Composable
private fun ComputerRuntimeAuthOnlyBody(
    auth: ComputerRuntimeAuthCardState?,
    inset: Dp,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutProvider: (RuntimeAuthSignedInProvider) -> Unit,
) {
    if (auth != null) {
        ComputerRuntimeAuthProviderRows(auth, onSignOutProvider)
        if (auth.hasActions) {
            Column(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ComputerRuntimeAuthActionView(auth, onSignIn, onSignOut)
            }
        }
    }
    ComputerRuntimeUsagePrivateLine(inset, top = if (auth?.hasActions == true) 0.dp else 10.dp)
}

/**
 * [ComputerRuntimeAuthOnlyCard] as a finger's bottom sheet (web: the card in a Drawer on a phone):
 * the runtime's name, its sign-in line and version, its stored providers, the sign-in action, and Close.
 */
@Composable
private fun ComputerRuntimeAuthOnlySheet(
    runtimeName: String,
    runtimeVersion: String?,
    auth: ComputerRuntimeAuthCardState?,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutProvider: (RuntimeAuthSignedInProvider) -> Unit,
) {
    RaftSheet(
        onDismissRequest = onDismiss,
        title = runtimeName,
        actions = {
            RaftButton(
                text = stringResource(Res.string.common_close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
        },
        content = {
            ComputerRuntimeCardHeadLines(runtimeVersion, auth, usageShowsAccount = false, authOnly = true)
            ComputerRuntimeAuthOnlyBody(auth, inset = 0.dp, onSignIn, onSignOut, onSignOutProvider)
        },
    )
}

/** Web `privateLine`: who may see this card, at its foot. */
@Composable
private fun ComputerRuntimeUsagePrivateLine(inset: Dp, top: Dp = 0.dp) {
    RaftText(
        stringResource(Res.string.settings_computers_runtime_usage_private),
        modifier = Modifier.fillMaxWidth().padding(start = inset, end = inset, top = top, bottom = 10.dp),
        style = RaftTheme.typography.caption,
        color = RaftTheme.colors.foregroundHint,
    )
}

/**
 * The card's head (web `Surface`, slock #15482): the title with the sign-in chip at its end, the
 * sign-in line, the version line (Upgrade in it), and close.
 */
@Composable
private fun ComputerRuntimeCardHeader(
    title: String,
    runtimeVersion: String?,
    onClose: (() -> Unit)?,
    auth: ComputerRuntimeAuthCardState?,
    usageShowsAccount: Boolean,
    authOnly: Boolean,
) {
    // `flex items-center justify-between gap-3 border-b border-line-hairline px-4 py-3`
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    title,
                    modifier = Modifier.weight(1f),
                    style = RaftTheme.typography.headline,
                    color = RaftTheme.colors.foreground,
                )
                if (auth != null) ComputerRuntimeAuthHeaderBadge(auth, usageShowsAccount, authOnly)
            }
            if (auth != null) ComputerRuntimeAuthHeaderLine(auth, usageShowsAccount)
            ComputerRuntimeVersionLine(runtimeVersion, auth)
        }
        if (onClose != null) {
            RaftIconButton(onClick = onClose, tooltip = stringResource(Res.string.common_close), variant = RaftButtonVariant.Ghost) {
                RaftComputersIcon(RaftLucideIconName.X)
            }
        }
    }
    RaftSeparator()
}

/** The head's lines under a sheet's own title: the sign-in chip, the sign-in line and the version line. */
@Composable
private fun ComputerRuntimeCardHeadLines(runtimeVersion: String?, auth: ComputerRuntimeAuthCardState?, usageShowsAccount: Boolean, authOnly: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (auth != null) ComputerRuntimeAuthHeaderBadge(auth, usageShowsAccount, authOnly)
        if (auth != null) ComputerRuntimeAuthHeaderLine(auth, usageShowsAccount)
        ComputerRuntimeVersionLine(runtimeVersion, auth)
    }
}

/**
 * Web `RuntimeVersionLine` (slock #15482): the runtime's version in mono, then Upgrade as the strong text
 * link of that line where the server offers it. Once a press has an outcome, the outcome takes the line;
 * the link comes back only after a failure another press may fix ([computerRuntimeUpgradeAgainAfter]).
 * While it runs, the link says "Upgrading…" beside a spinner. Nothing to say: no line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComputerRuntimeVersionLine(runtimeVersion: String?, auth: ComputerRuntimeAuthCardState?) {
    val version = runtimeVersion?.trim()?.takeIf { it.isNotEmpty() }
    val upgrade = auth?.upgrade?.takeIf { auth?.view?.canAct == true }
    // The Computer's update check (slock #15429) in place of the bare version, where Upgrade is offered.
    val updateCheck = if (upgrade != null) auth.updateCheckCopy(runtimeVersion) else null
    val outcome = upgrade?.let { computerRuntimeUpgradeCopy(it.state) }
    val running = upgrade?.state == ComputerRuntimeUpgradeState.Running
    val offerUpgrade =
        upgrade != null &&
            if (outcome != null) computerRuntimeUpgradeAgainAfter(upgrade.state) else auth?.upgradeButtonShown == true
    if (outcome == null && updateCheck == null && version == null && !offerUpgrade) return
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (outcome != null) {
            RaftText(
                stringResource(outcome.message, auth?.let { build.raft.app.data.observeRuntimeCatalogDisplayName(it.raftRuntimeId) }.orEmpty(), *outcome.args.toTypedArray()),
                modifier = Modifier.testTag(COMPUTER_RUNTIME_OPERATION_OUTCOME_TAG),
                style = typography.caption,
                color = if (outcome.success) colors.foreground else colors.warningStrong,
            )
        } else if (updateCheck != null) {
            RaftText(
                computerRuntimeUpdateCheckText(updateCheck),
                modifier = Modifier.testTag(COMPUTER_RUNTIME_UPDATE_CHECK_TAG),
                style = typography.caption,
                color = colors.foregroundHint,
            )
        } else if (version != null) {
            RaftText(version, style = typography.monoMeta, color = colors.foregroundHint)
        }
        if (offerUpgrade && upgrade != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) RaftSpinner(size = RaftSpinnerSize.Xs)
                RaftTextLink(
                    text = stringResource(if (running) Res.string.settings_computers_runtime_upgrade_running else Res.string.settings_computers_runtime_upgrade_action),
                    // Busy, not unavailable: it keeps its ink while it runs.
                    onClick = { if (!running) upgrade.onUpgrade() },
                    modifier = Modifier.testTag(COMPUTER_RUNTIME_UPGRADE_TAG),
                    variant = RaftTextLinkVariant.Strong,
                    style = typography.caption,
                )
            }
        }
    }
    outcome?.traceId?.let { RaftTraceIdNote(it) }
}

/** Web `HOVER_CLOSE_DELAY_MS`: the usage card waits a little longer than a profile card. */
internal const val ComputerRuntimeUsagePreviewCardCloseDelayMillis = 160L

/**
 * Web `Surface` in its desktop card (slock #15482): a header (title and sign-in chip, the sign-in line,
 * the version line, close) over a hairline, the accounts, "Updated …" with Refresh beside it, the
 * actions only while they mean something, and who may see it. The phone keeps the sheet
 * ([ComputerRuntimeUsageSheet]) with the same content. Signed out, the card is its sign-in alone.
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
    onClaudeLimitReset: () -> Unit = {},
    /** The account's "…" menu opened (a hover card pins). */
    onAccountMenuOpen: () -> Unit = {},
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
        auth = auth,
        usageShowsAccount = state.showsAccount,
        authOnly = false,
    )
    ComputerRuntimeUsageSurfaceContent(
        state = state,
        timeContext = timeContext,
        inset = 16.dp,
        showsUsage = true,
        auth = auth,
        onRefresh = onRefresh,
        onUseLimitReset = onUseLimitReset,
        onClaudeLimitReset = onClaudeLimitReset,
        onSignIn = onSignIn,
        onSignOut = onSignOut,
        onSignOutProvider = onSignOutProvider,
        onAccountMenuOpen = onAccountMenuOpen,
    )
}

/**
 * Web `Surface` under its head: with usage, the usage block (its own account's menu and actions in
 * it), the actions that have no account to sit under, what the last refresh or reset did, and who
 * may see it; without (signed out), the sign-in alone. [head] goes first (a sheet's lines under its title).
 */
@Composable
private fun ComputerRuntimeUsageSurfaceContent(
    state: ComputerRuntimeUsageSheetState,
    timeContext: RaftTimeContext?,
    inset: Dp,
    showsUsage: Boolean,
    auth: ComputerRuntimeAuthCardState?,
    onRefresh: () -> Unit,
    onUseLimitReset: () -> Unit,
    onClaudeLimitReset: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutProvider: (RuntimeAuthSignedInProvider) -> Unit,
    onAccountMenuOpen: () -> Unit = {},
    head: @Composable () -> Unit = {},
) {
    head()
    if (!showsUsage) {
        ComputerRuntimeAuthOnlyBody(auth, inset, onSignIn, onSignOut, onSignOutProvider)
        return
    }
    val usageShowsAccount = state.showsAccount
    // Signed in with usage drawn: Sign out lives in the account's menu, not as a button on the card.
    val signOutInMenu = auth?.signOutInMenu(usageShowsAccount) == true
    val authAction = if (auth != null && !signOutInMenu) auth.action() else null
    // The provider wants the Computer's own account signed in again: its chip says so, Sign in under it does it.
    val ownReauth = state.available?.snapshot?.accounts?.any { it.isDefaultAccount && it.health == RuntimeAccountUsageHealth.ReauthRequired } == true
    val reauthSignIn = ownReauth && auth?.canSignInHere == true
    val hasAccountActions = reauthSignIn || state.limitReset != null || state.claudeLimitReset != null
    val accountActions: @Composable () -> Unit = {
        ComputerRuntimeUsageAccountActions(
            signIn = if (reauthSignIn && auth != null) ({ ComputerRuntimeSignInButton(auth, onSignIn) }) else null,
            limitReset = state.limitReset,
            onUseLimitReset = onUseLimitReset,
            claudeLimitReset = state.claudeLimitReset,
            onClaudeLimitReset = onClaudeLimitReset,
        )
    }
    ComputerRuntimeUsageContents(
        state = state,
        timeContext = timeContext,
        inset = inset,
        accountMenu = if (signOutInMenu) ({ ComputerRuntimeAccountMenu(onSignOut, onAccountMenuOpen) }) else null,
        accountActions = if (usageShowsAccount && hasAccountActions) accountActions else null,
        footerAction = { ComputerRuntimeUsageRefreshButton(state, onRefresh) },
    )
    // No own account drawn (nothing read yet): the limit and sign-in actions under the usage.
    val loose = !usageShowsAccount && hasAccountActions
    if (loose || (authAction != null && auth != null)) {
        RaftSeparator()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 10.dp).testTag(COMPUTER_RUNTIME_USAGE_ACTIONS_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loose) accountActions()
            if (authAction != null && auth != null) ComputerRuntimeAuthActionView(auth, onSignIn, onSignOut)
        }
    }
    computerRuntimeUsageStatusText(state)?.let { text ->
        RaftText(
            text,
            modifier = Modifier.fillMaxWidth().padding(start = inset, end = inset, bottom = 4.dp),
            style = RaftTheme.typography.caption,
            color = RaftTheme.colors.foregroundHint,
        )
    }
    ComputerRuntimeUsagePrivateLine(inset)
}

/**
 * Under the Computer's own account (web `accountActions`, slock #15482, #15787): Sign in when the
 * provider wants it signed in again, and the limit resets. A used-up account gets the reset as the
 * card's main action; on credits past a full window a callout says the reset stops it spending them.
 */
@Composable
private fun ComputerRuntimeUsageAccountActions(
    signIn: (@Composable () -> Unit)?,
    limitReset: ComputerRuntimeLimitResetOffer?,
    onUseLimitReset: () -> Unit,
    claudeLimitReset: ComputerRuntimeClaudeLimitResetOffer?,
    onClaudeLimitReset: () -> Unit,
) {
    val limitPrimary = limitReset?.primary == true || claudeLimitReset?.primary == true
    val limitButtons: @Composable () -> Unit = {
        limitReset?.let { ComputerRuntimeLimitResetButton(it, onUseLimitReset) }
        claudeLimitReset?.let { ComputerRuntimeClaudeLimitResetButton(it, onClaudeLimitReset) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        signIn?.invoke()
        if (limitReset != null || claudeLimitReset != null) {
            if (limitPrimary && limitReset?.onCredits == true) {
                // Raft DS Banner: only for what the "used up" chip can't say (it is spending credits).
                RaftBanner(
                    description = stringResource(Res.string.settings_computers_runtime_usage_limit_reset_callout_on_credits),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.testTag(COMPUTER_RUNTIME_LIMIT_CALLOUT_TAG),
                    action = { limitButtons() },
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { limitButtons() }
            }
        }
    }
}

/** Web `accountMenu`: the Computer's own account's "…" with Sign out. */
@Composable
private fun ComputerRuntimeAccountMenu(onSignOut: () -> Unit, onOpen: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.settings_computers_runtime_usage_account_menu)
    Box {
        RaftIconButton(
            onClick = {
                onOpen()
                open = true
            },
            tooltip = label,
            modifier = Modifier.testTag(COMPUTER_RUNTIME_ACCOUNT_MENU_TAG),
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Md,
        ) {
            RaftComputersIcon(RaftLucideIconName.Ellipsis)
        }
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, alignEnd = true) {
            RaftMenuItem(
                text = stringResource(Res.string.settings_computers_runtime_auth_sign_out_action),
                modifier = Modifier.testTag(COMPUTER_RUNTIME_AUTH_SIGN_OUT_TAG),
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.LogOut) },
                onClick = {
                    open = false
                    onSignOut()
                },
            )
        }
    }
}

/** Web `refreshButton`: a ghost Refresh beside "Updated …", the pixel spinner while a read is awaited. */
@Composable
private fun ComputerRuntimeUsageRefreshButton(state: ComputerRuntimeUsageSheetState, onRefresh: () -> Unit) {
    val waiting = state.refresh == ComputerRuntimeUsageRefreshStatus.Requesting || state.refresh == ComputerRuntimeUsageRefreshStatus.Requested
    RaftButton(
        text = stringResource(Res.string.settings_computers_runtime_usage_refresh),
        onClick = onRefresh,
        modifier = Modifier.testTag(COMPUTER_RUNTIME_USAGE_REFRESH_TAG),
        variant = RaftButtonVariant.Ghost,
        size = RaftButtonSize.Md,
        enabled = state.refresh != ComputerRuntimeUsageRefreshStatus.Requesting && state.cooldownLeftMillis <= 0L,
        leadingIcon = {
            if (waiting) RaftSpinner(size = RaftSpinnerSize.Xs) else RaftComputersIcon(RaftLucideIconName.RefreshCw)
        },
    )
}

internal const val COMPUTER_RUNTIME_LIMIT_RESETS_LEFT_TAG = "runtime-account-limit-resets"
