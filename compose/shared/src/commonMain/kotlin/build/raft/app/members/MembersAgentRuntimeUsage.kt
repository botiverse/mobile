package build.raft.app.members

import build.raft.app.resources.*
import build.raft.app.thread.key
import build.raft.designsystem.components.RaftRuntimeChip
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.SharedComputersState
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageViewer
import build.raft.app.data.needsUsageAttention
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.model.RaftTimeContext
import build.raft.app.rememberRuntimeUsageNowMillis
import build.raft.app.resolve
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.BindClaudeLimitResetFlag
import build.raft.app.ComputerRuntimeAuthHost
import build.raft.app.ComputerRuntimeUsageAccountBlock
import build.raft.app.ComputerRuntimeUsageController
import build.raft.app.ComputerRuntimeUsagePreviewCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything the agent detail needs to read runtime account usage (task #137). Provided once
 * by the member detail route; a null value (or a null owner/server) keeps the runtime chip in
 * its existing inert form.
 */
internal class MembersRuntimeAccountUsageAccess(
    val store: SharedRuntimeAccountUsageStore,
    val owner: SharedComputersOwner?,
    val serverId: String?,
    /** Called when a read found [owner] is no longer the signed-in identity; re-resolve it. */
    val onIdentityChanged: () -> Unit = {},
    /** Diagnostics sink (no identity, token or machine data is ever written to it). */
    val log: (String) -> Unit = {},
    /** Who is looking, for [canViewMachineRuntimeAccountUsage]; null while unknown (the server decides). */
    val viewer: RuntimeAccountUsageViewer? = null,
    /** Signing the Computer's runtimes in and out from their cards; null offers no sign-in (the agent page). */
    val auth: ComputerRuntimeAuthHost? = null,
    /** The server's feature flags (`claude_limit_reset_v2` for the cards' Claude limit resets); null reads every flag as off. */
    val serverFeatureFlags: SharedServerFeatureFlags? = null,
    /**
     * Live computers state for fail-closed `canViewMachineRuntimeAccountUsage` and computer display
     * name resolution. Collected only by leaves that actually need sign-in status, so Computer
     * heartbeats never recompose the shell or reset sibling usage/auth controllers.
     */
    val computersStateFlow: StateFlow<SharedComputersState>? = null,
    /**
     * Explicit override for web `canViewMachineRuntimeAccountUsage` (tests); when null, leaves
     * evaluate fail-closed from [viewer] and [computersStateFlow] (false when viewer is unknown).
     */
    val canViewMachine: ((serverId: String, machineId: String) -> Boolean)? = null,
    /** Explicit override for Computer display name (`name` or `hostname`); when null, reads [computersStateFlow]. */
    val machineName: ((serverId: String, machineId: String) -> String?)? = null,
)

/**
 * Live owner (signed-in identity) for runtime account usage reads on one page. Any auth event
 * other than a token rotation clears the owner at once — so the chip drops a finished read of
 * the previous identity before anything else can show it — and then re-resolves it.
 */
internal class MembersRuntimeAccountUsageOwnerTracker(
    initialOwner: SharedComputersOwner? = null,
    private val resolve: suspend () -> SharedComputersOwner?
) {
    private val mutex = Mutex()
    private var generation = 0L
    private val mutableOwner = MutableStateFlow<SharedComputersOwner?>(initialOwner)
    val owner: StateFlow<SharedComputersOwner?> = mutableOwner.asStateFlow()

    /** Re-reads the owner; a result that an [invalidate] overtook is dropped. */
    suspend fun refresh() {
        val started = mutex.withLock { generation }
        val resolved = resolve()
        mutex.withLock { if (generation == started) mutableOwner.value = resolved }
    }

    /** The identity may have changed: show nothing until it is re-resolved. */
    suspend fun invalidate() {
        mutex.withLock {
            generation += 1
            mutableOwner.value = null
        }
        refresh()
    }

    suspend fun onAuthSessionEvent(event: AuthSessionEvent) {
        when {
            event.clearsRuntimeAccountUsageOwnerImmediately() -> invalidate()
            event.changesRuntimeAccountUsageOwner() -> refresh()
        }
    }
}

/** Token rotation keeps the account; every other auth event may change who is signed in or which server is active. */
internal fun AuthSessionEvent.changesRuntimeAccountUsageOwner(): Boolean = this !is AuthSessionEvent.TokensRotated

/**
 * Only sign-out and a new sign-in clear the owner to null before re-resolving: a metadata refresh
 * (`BindingMetadataChanged`, e.g. `auth/me` or server appearance catch-up on foreground return)
 * usually keeps the same `(owner, serverId)` and must not flash null and tear down an active
 * runtime sign-in dialog.
 */
internal fun AuthSessionEvent.clearsRuntimeAccountUsageOwnerImmediately(): Boolean =
    this is AuthSessionEvent.Authenticated || this is AuthSessionEvent.Cleared

internal val LocalMembersRuntimeAccountUsageAccess =
    compositionLocalOf<MembersRuntimeAccountUsageAccess?> { null }

/** The exact read subject, or null when this runtime/agent cannot show account usage. */
internal fun membersRuntimeAccountUsageKey(
    access: MembersRuntimeAccountUsageAccess?,
    runtimeId: String?,
    machineId: String?
): RuntimeAccountUsageKey? {
    val provider = RuntimeAccountUsageProvider.forRuntime(runtimeId) ?: return null
    val owner = access?.owner ?: return null
    val serverId = access.serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cleanMachineId = machineId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return RuntimeAccountUsageKey(owner = owner, serverId = serverId, machineId = cleanMachineId, provider = provider)
}

/** Coarse, data-free label of a load for diagnostics. */
internal fun RuntimeAccountUsageLoad?.diagnosticsLabel(): String =
    when (this) {
        null -> "none"
        RuntimeAccountUsageLoad.Forbidden -> "forbidden"
        RuntimeAccountUsageLoad.Superseded -> "superseded"
        is RuntimeAccountUsageLoad.Failed -> "failed:$code"
        is RuntimeAccountUsageLoad.Loaded ->
            when (val read = read) {
                RuntimeAccountUsageRead.Missing -> "missing"
                RuntimeAccountUsageRead.Unsupported -> "unsupported"
                is RuntimeAccountUsageRead.Available -> if (read.stale) "stale" else "fresh"
            }
    }

/** The usage entry is offered only once a read has returned something other than 403. */
internal fun RuntimeAccountUsageLoad?.offersRuntimeAccountUsage(): Boolean =
    this != null && this !is RuntimeAccountUsageLoad.Forbidden && this !is RuntimeAccountUsageLoad.Superseded

/**
 * What the chip keeps after a read. A read for an identity that is no longer signed in shows
 * nothing (the entry goes inert until the owner is re-resolved); everything else is kept.
 */
internal fun RuntimeAccountUsageLoad.shownRuntimeAccountUsage(): RuntimeAccountUsageLoad? =
    takeUnless { it is RuntimeAccountUsageLoad.Superseded }

/**
 * Whether anything in the read needs attention. Missing data, stale data, an unknown health or
 * an unavailable window are all attention states — none of them is presented as healthy.
 */
internal fun RuntimeAccountUsageLoad?.runtimeAccountUsageNeedsAttention(): Boolean =
    when (this) {
        null, RuntimeAccountUsageLoad.Forbidden, RuntimeAccountUsageLoad.Superseded -> false
        is RuntimeAccountUsageLoad.Failed -> true
        is RuntimeAccountUsageLoad.Loaded ->
            when (val read = read) {
                RuntimeAccountUsageRead.Missing -> true
                // Web: `unsupported` is no usage, no health dot and no attention (slock #14667).
                RuntimeAccountUsageRead.Unsupported -> false
                is RuntimeAccountUsageRead.Available ->
                    read.stale ||
                        // A full window the account's credits carry is not attention (slock #15374).
                        read.snapshot.accounts.any { it.needsUsageAttention }
            }
    }

/**
 * Maps the usage read state to the inline usage light: web `RuntimeAccountUsageChip`
 * `<Status size="sm" variant={attention ? "warning" : "success"}>`; none when not offered.
 */
internal fun membersRuntimeUsageStatusVariant(
    offered: Boolean,
    attention: Boolean
): RaftStatusVariant? =
    if (!offered) null else if (attention) RaftStatusVariant.Warning else RaftStatusVariant.Success

/**
 * Runtime chip of the agent's Runtime Config section. For runtimes that report account usage
 * (Claude, Codex, Kimi, Grok) it reads usage once while this section is composed, and becomes
 * tappable unless the server answers 403; tapping opens a read-only usage card.
 *
 * The read is keyed by identity/server/Computer/provider: a change of any of them resets the
 * local state and cancels the previous read, so an old response can never be shown for the new
 * subject. A 403 (including on a later reload) clears whatever was shown.
 */
@Composable
internal fun MembersAgentRuntimeUsageChip(
    label: String,
    runtimeId: String,
    machineId: String?
) {
    val access = LocalMembersRuntimeAccountUsageAccess.current
    val key = membersRuntimeAccountUsageKey(access, runtimeId, machineId)
    val scope = rememberCoroutineScope()
    val nowMillis = rememberRuntimeUsageNowMillis()
    // The Computer page's controller: the same read, refresh and follow-ups as its chips.
    val controller =
        remember(key, access) {
            if (key == null || access == null) {
                null
            } else {
                ComputerRuntimeUsageController(
                    key = key,
                    store = access.store,
                    scope = scope,
                    log = { message -> access.log("member_$message") },
                    onIdentityChanged = access.onIdentityChanged,
                    nowMillis = nowMillis,
                )
            }
        }
    DisposableEffect(controller) { onDispose { controller?.cancel() } }
    BindClaudeLimitResetFlag(controller, access?.serverFeatureFlags)
    LaunchedEffect(controller) { controller?.start() }
    val load = controller?.load
    val offered = controller != null && load.offersRuntimeAccountUsage()
    val attention = offered && load.runtimeAccountUsageNeedsAttention()
    val statusVariant = membersRuntimeUsageStatusVariant(offered, attention)
    val statusDescription =
        when {
            !offered -> null
            attention -> stringResource(Res.string.member_runtime_usage_attention)
            else -> stringResource(Res.string.settings_computers_runtime_usage_healthy)
        }
    val chip: @Composable (onClick: (() -> Unit)?) -> Unit = { onClick ->
        RaftRuntimeChip(
            text = label,
            // A button when it opens the usage (a card for a mouse, a sheet for a finger); its
            // long press copies the runtime name (never the usage status).
            onClick = onClick,
            copyText = label,
            // Web `RuntimeAccountUsageChip`: Status `sm` after the label (`ml-1`); the attention
            // state is the light's accessible name only, never drawn as text.
            trailing =
                statusVariant?.let { variant ->
                    {
                        RaftStatusDot(
                            variant = variant,
                            size = RaftStatusSize.Sm,
                            contentDescription = statusDescription
                        )
                    }
                }
        )
    }
    // Web `RuntimeUsageHoverCard` (artin 11-02): a mouse resting on the chip shows the usage
    // card and its click pins it; a finger's tap opens the usage sheet; the card's own Refresh
    // and close. Without usage to show, the chip alone.
    if (controller == null) {
        chip(null)
    } else {
        ComputerRuntimeUsagePreviewCard(
            runtimeLabel = label,
            controller = controller,
            runtimeVersion = null,
            // As the Computer chips: hover works while the first read is still loading (the card
            // has its loading state); only a 403 turns it off.
            enabled = load !is RuntimeAccountUsageLoad.Forbidden,
            trigger = chip,
        )
    }
}

@Composable
internal fun MembersRuntimeUsageAccountCard(
    account: RuntimeAccountUsageAccount,
    stale: Boolean,
    timeContext: RaftTimeContext?
) {
    // raft-ui Card nested in a dialog; a stale snapshot lays it on `fill-subtle` (inactive),
    // as raft-ui marks a subject that is away, instead of a cream panel.
    RaftCard(modifier = Modifier.fillMaxWidth(), inactive = stale) {
        // The Computers sheet's account block: the raft-ui StatusChip and "X% used · resets …"
        // rows, the same as the web (artin 10-29: no square badge; web #11855 / raft-ui 0.50:
        // usage says what is used).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ComputerRuntimeUsageAccountBlock(account, timeContext)
        }
    }
}
