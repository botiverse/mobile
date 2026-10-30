package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import build.raft.app.data.SharedRuntimeAccountUsageStore
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
import build.raft.designsystem.components.RaftMeter
import build.raft.designsystem.components.RaftMeterSize
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSheetSurface
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.text.RaftText
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
    plain: @Composable (RaftComputerRuntimeChip) -> Unit,
) {
    val usable =
        enabled &&
            chips.any { it.availability == RaftComputerRuntimeAvailability.Detected && RuntimeAccountUsageProvider.forRuntime(it.id) != null }
    val access = if (usable) LocalMembersRuntimeAccountUsageAccess.current ?: rememberShellRuntimeAccountUsageAccess() else null
    chips.forEach { chip ->
        if (usable && chip.availability == RaftComputerRuntimeAvailability.Detected && RuntimeAccountUsageProvider.forRuntime(chip.id) != null) {
            val experimental = if (chip.experimental) stringResource(Res.string.runtime_suffix_experimental) else ""
            ComputerRuntimeUsageChip(chip.name + experimental, membersRuntimeAccountUsageKey(access, chip.id, machineId), access)
        } else {
            plain(chip)
        }
    }
}

/**
 * The shell's usage store, the signed-in owner and the current server, for a page that does
 * not provide [LocalMembersRuntimeAccountUsageAccess] itself. The owner and server are read
 * again on every auth event (sign-out, account or server switch), and cleared at once first.
 */
@Composable
private fun rememberShellRuntimeAccountUsageAccess(): MembersRuntimeAccountUsageAccess? {
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
    return remember(store, owner, serverId, tracker) {
        MembersRuntimeAccountUsageAccess(
            store = store,
            owner = owner,
            serverId = serverId,
            onIdentityChanged = { scope.launch { tracker.invalidate() } },
            log = { message -> bridge.log(message) },
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
    private var followUp: Job? = null

    /** The first read; a stale or missing snapshot asks the Computer for a new one in the background. */
    suspend fun start() {
        val read = (read(force = false) as? RuntimeAccountUsageLoad.Loaded)?.read ?: return
        if (read == RuntimeAccountUsageRead.Missing || (read as? RuntimeAccountUsageRead.Available)?.stale == true) {
            handleRefresh(store.refresh(key, RuntimeAccountUsageRefreshReason.StaleOrMissing), manual = false)
        }
    }

    /** Opening the sheet reads again (from the 60 s cache when it is fresh). */
    fun onOpen() {
        scope.launch { read(force = false) }
    }

    fun refreshNow() {
        if (refresh == ComputerRuntimeUsageRefreshStatus.Requesting) return
        refresh = ComputerRuntimeUsageRefreshStatus.Requesting
        scope.launch {
            handleRefresh(store.refresh(key, RuntimeAccountUsageRefreshReason.Manual), manual = true)
            updateCooldown()
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
    key: RuntimeAccountUsageKey?,
    access: MembersRuntimeAccountUsageAccess?,
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
    var open by remember(controller) { mutableStateOf(false) }
    val load = controller?.load
    val offered = controller != null && load !is RuntimeAccountUsageLoad.Forbidden
    val health = load.computerRuntimeUsageHealth()
    val dialogLabel = stringResource(Res.string.settings_computers_runtime_usage_dialog_label, label)
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
    // The tap handler is always attached and the modifier chain keeps its shape (Kuikly keeps
    // stale native props when a modifier is removed); whether a tap opens is decided inside.
    RaftBadge(
        text = label,
        // A badge button: a tap opens its usage, a long press copies the runtime name; its
        // touch target reaches 44dp (raft-ui 0.52.2).
        onClick = {
            if (offered) {
                open = true
                controller?.onOpen()
            }
        },
        onClickLabel = dialogLabel,
        copyText = label,
        appearance = RaftBadgeAppearance.Soft,
        variant = RaftBadgeVariant.Information,
        trailing = healthDot,
    )
    // Read before the sheet: its window may not carry the page's composition locals.
    val timeContext = LocalRaftTimeContextProvider.current?.current
    if (open && offered && controller != null) {
        LaunchedEffect(controller) {
            while (true) {
                controller.updateCooldown()
                delay(1_000L)
            }
        }
        ComputerRuntimeUsageSheet(
            runtimeLabel = label,
            state = ComputerRuntimeUsageSheetState(controller.load, controller.loading, controller.refresh, controller.cooldownLeftMillis),
            timeContext = timeContext,
            onRefresh = controller::refreshNow,
            onDismiss = { open = false },
        )
    }
}

/** What the usage sheet shows (web `Surface` props). */
internal data class ComputerRuntimeUsageSheetState(
    val load: RuntimeAccountUsageLoad?,
    val loading: Boolean,
    val refresh: ComputerRuntimeUsageRefreshStatus,
    val cooldownLeftMillis: Long,
)

/** Web phone layout: the usage surface in a bottom sheet (`BottomSheet`, below md). */
@Composable
private fun ComputerRuntimeUsageSheet(
    runtimeLabel: String,
    state: ComputerRuntimeUsageSheetState,
    timeContext: RaftTimeContext?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    RaftSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_computers_runtime_usage_title, runtimeLabel),
        actions = { ComputerRuntimeUsageActions(state, timeContext, onRefresh, onDismiss) },
        content = { ComputerRuntimeUsageContents(state, timeContext) },
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
        actions = { ComputerRuntimeUsageActions(state, timeContext, onRefresh = {}, onDismiss = {}) },
        content = { ComputerRuntimeUsageContents(state, timeContext) },
    )
}

@Composable
private fun ColumnScope.ComputerRuntimeUsageActions(
    state: ComputerRuntimeUsageSheetState,
    timeContext: RaftTimeContext?,
    onRefresh: () -> Unit,
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
    val status =
        when {
            state.refresh == ComputerRuntimeUsageRefreshStatus.Requested -> stringResource(Res.string.settings_computers_runtime_usage_refresh_requested)
            state.refresh == ComputerRuntimeUsageRefreshStatus.Unconfirmed -> stringResource(Res.string.settings_computers_runtime_usage_refresh_unconfirmed)
            state.refresh == ComputerRuntimeUsageRefreshStatus.Timeout -> stringResource(Res.string.settings_computers_runtime_usage_refresh_timeout)
            state.cooldownLeftMillis > 0L ->
                stringResource(Res.string.settings_computers_runtime_usage_refresh_cooldown_countdown, ((state.cooldownLeftMillis + 999L) / 1_000L).toInt())
            state.refresh == ComputerRuntimeUsageRefreshStatus.ComputerOffline -> stringResource(Res.string.settings_computers_runtime_usage_computer_offline)
            else -> stringResource(Res.string.settings_computers_runtime_usage_cache_only)
        }
    RaftText(status, style = RaftTheme.typography.caption, color = colors.foregroundHint)
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
    RaftButton(
        text = stringResource(Res.string.common_close),
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        variant = RaftButtonVariant.Ghost,
        size = RaftButtonSize.Lg,
    )
}

@Composable
internal fun ComputerRuntimeUsageContents(state: ComputerRuntimeUsageSheetState, timeContext: RaftTimeContext?) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RaftText(
            stringResource(Res.string.settings_computers_runtime_usage_private),
            style = typography.caption,
            color = colors.foregroundHint,
        )
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
                account.identityLabel?.let { identity ->
                    RaftText(identity, style = typography.monoMeta, color = colors.foregroundHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            RuntimeAccountUsageHealth.Unsupported, RuntimeAccountUsageHealth.Error ->
                RaftText(
                    stringResource(
                        if (account.health == RuntimeAccountUsageHealth.Unsupported) {
                            Res.string.settings_computers_runtime_usage_unsupported_description
                        } else {
                            Res.string.settings_computers_runtime_usage_read_failed
                        }
                    ),
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

/** One usage window: "X% left · resets …" or "Used up", as the web `RuntimeAccountUsageChip`. */
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
    // Used up by the provider's word or the ratio itself, not by rounding: 0.4% left still
    // reads "0% left", as the web says.
    val usedUp = window.status == RuntimeAccountUsageWindowStatus.LimitReached || ratio >= 1.0
    val reset =
        window.resetsAt?.let { resetsAt ->
            raftRelativeTimestampLabel(resetsAt, timeContext).takeIf { it.isNotEmpty() }
                ?: stringResource(Res.string.settings_computers_runtime_usage_later)
        }
    val percentLeft = "${runtimeUsagePercentLeft(ratio)}%"
    val valueText =
        when {
            usedUp && reset == null -> stringResource(Res.string.settings_computers_runtime_usage_window_used_up_reset_unavailable)
            usedUp -> stringResource(Res.string.settings_computers_runtime_usage_window_used_up, reset.orEmpty())
            reset == null -> stringResource(Res.string.settings_computers_runtime_usage_window_left_reset_unavailable, percentLeft)
            else -> stringResource(Res.string.settings_computers_runtime_usage_window_left, percentLeft, reset)
        }
    RaftMeter(
        used = if (usedUp) 100f else (ratio * 100.0).toFloat(),
        valueText = valueText,
        label = window.label,
        size = RaftMeterSize.Sm,
    )
}

/** Web `runtimeUsagePercentLeft`: `100 - round(usedRatio * 100)`. */
internal fun runtimeUsagePercentLeft(usedRatio: Double): Int = 100 - kotlin.math.round(usedRatio * 100.0).toInt()

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
