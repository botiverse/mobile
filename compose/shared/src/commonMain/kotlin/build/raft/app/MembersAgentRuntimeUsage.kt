package build.raft.app

import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftProgressBar
import build.raft.app.components.RaftProgressBarTone
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageViewer
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.MR
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource
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
    val viewer: RuntimeAccountUsageViewer? = null
)

/**
 * Live owner (signed-in identity) for runtime account usage reads on one page. Any auth event
 * other than a token rotation clears the owner at once — so the chip drops a finished read of
 * the previous identity before anything else can show it — and then re-resolves it.
 */
internal class MembersRuntimeAccountUsageOwnerTracker(
    private val resolve: suspend () -> SharedComputersOwner?
) {
    private val mutex = Mutex()
    private var generation = 0L
    private val mutableOwner = MutableStateFlow<SharedComputersOwner?>(null)
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
        if (event.changesRuntimeAccountUsageOwner()) invalidate()
    }
}

/** Token rotation keeps the account; every other auth event may change who is signed in. */
internal fun AuthSessionEvent.changesRuntimeAccountUsageOwner(): Boolean = this !is AuthSessionEvent.TokensRotated

internal val LocalMembersRuntimeAccountUsageAccess =
    staticCompositionLocalOf<MembersRuntimeAccountUsageAccess?> { null }

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
                is RuntimeAccountUsageRead.Available ->
                    read.stale ||
                        read.snapshot.accounts.any { account ->
                            account.health != RuntimeAccountUsageHealth.Ok ||
                                account.windows.any { it.status != RuntimeAccountUsageWindowStatus.Ok }
                        }
            }
    }

/** Maps the usage read state to the Web-aligned inline status dot. */
internal fun membersRuntimeUsageStatusColor(
    offered: Boolean,
    attention: Boolean
): Color? =
    if (!offered) null else if (attention) RaftColor.AccentOrange else RaftColor.AccentLime

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
    var load by remember(key) { mutableStateOf<RuntimeAccountUsageLoad?>(null) }
    var reloadNonce by remember(key) { mutableStateOf(0) }
    var loading by remember(key) { mutableStateOf(false) }
    var dialogOpen by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, reloadNonce) {
        val store = access?.store
        if (key == null || store == null) return@LaunchedEffect
        loading = true
        val result = store.read(key, force = reloadNonce > 0)
        access?.log?.invoke("member_runtime_usage_read provider=${key.provider.wire} force=${reloadNonce > 0} result=${result.diagnosticsLabel()}")
        load = result.shownRuntimeAccountUsage()
        loading = false
        if (!load.offersRuntimeAccountUsage()) dialogOpen = false
        if (result is RuntimeAccountUsageLoad.Superseded) access?.onIdentityChanged?.invoke()
    }
    val offered = key != null && load.offersRuntimeAccountUsage()
    val attention = offered && load.runtimeAccountUsageNeedsAttention()
    val statusColor = membersRuntimeUsageStatusColor(offered, attention)
    // The click handler is always attached and the modifier chain never changes shape (Kuikly
    // keeps stale native props when a modifier is removed); whether a tap opens the card is
    // decided inside the handler.
    MembersAgentRuntimeChip(
        label = label,
        kind = MembersAgentRuntimeChipKind.Runtime,
        statusColor = statusColor,
        // Long press copies the runtime name (never the usage status); a tap opens usage.
        modifier = Modifier.raftCopyable(label) {
            access?.log?.invoke("member_runtime_usage_tap offered=$offered load=${load.diagnosticsLabel()}")
            if (offered) dialogOpen = true
        }
    )
    if (attention) {
        Text(
            text = stringResource(MR.strings.member_runtime_usage_attention),
            style = RaftType.Caption.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    // Read before the dialog: its content is rehosted in a separate native window where page
    // composition locals are not retained.
    val timeContext = LocalRaftTimeContextProvider.current?.current
    if (dialogOpen && offered) {
        LaunchedEffect(Unit) { access?.log?.invoke("member_runtime_usage_dialog_composed") }
        MembersRuntimeAccountUsageDialog(
            runtimeLabel = label,
            timeContext = timeContext,
            load = load,
            loading = loading,
            onReload = { reloadNonce += 1 },
            onDismiss = { dialogOpen = false }
        )
    }
}

@Composable
private fun MembersRuntimeAccountUsageDialog(
    runtimeLabel: String,
    timeContext: RaftTimeContext?,
    load: RuntimeAccountUsageLoad?,
    loading: Boolean,
    onReload: () -> Unit,
    onDismiss: () -> Unit
) {
    RaftUnifiedDialog(
        title = stringResource(MR.strings.member_runtime_usage_title),
        overline = runtimeLabel,
        onDismiss = onDismiss,
        contentSpacing = 12.dp
    ) {
        when (load) {
            null -> MembersRuntimeUsageNote(stringResource(MR.strings.member_runtime_usage_reading))
            RuntimeAccountUsageLoad.Forbidden, RuntimeAccountUsageLoad.Superseded -> Unit
            is RuntimeAccountUsageLoad.Failed ->
                MembersRuntimeUsageNote(
                    text = stringResource(MR.strings.member_runtime_usage_read_failed),
                    color = RaftColor.AccentRed
                )
            is RuntimeAccountUsageLoad.Loaded ->
                when (val read = load.read) {
                    RuntimeAccountUsageRead.Missing -> {
                        MembersRuntimeUsageNote(
                            text = stringResource(MR.strings.member_runtime_usage_missing),
                            bold = true
                        )
                        MembersRuntimeUsageNote(stringResource(MR.strings.member_runtime_usage_missing_detail))
                    }
                    is RuntimeAccountUsageRead.Available -> {
                        if (read.stale) {
                            MembersRuntimeUsageBanner(stringResource(MR.strings.member_runtime_usage_stale))
                        }
                        read.snapshot.accounts.forEach { account ->
                            MembersRuntimeUsageAccountCard(account = account, stale = read.stale, timeContext = timeContext)
                        }
                    }
                }
        }
        RaftButton(
            text = if (loading) {
                    stringResource(MR.strings.member_runtime_usage_reading)
                } else {
                    stringResource(MR.strings.member_runtime_usage_reload)
                },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            onClick = onReload,
            size = RaftButtonSize.Lg
        )
    }
}

@Composable
private fun MembersRuntimeUsageAccountCard(
    account: RuntimeAccountUsageAccount,
    stale: Boolean,
    timeContext: RaftTimeContext?
) {
    // raft-ui card nested in a dialog: rounded container with a hairline ring, not a
    // square ink box (it clashed with the rounded dialog and button around it).
    val shape = RoundedCornerShape(RaftAppRadius.Container)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (stale) RaftColor.SurfaceCream else RaftColor.SurfaceWhite, shape)
                .border(RaftAppStroke.Thin, RaftColor.BorderHairline, shape)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = account.planLabel ?: stringResource(MR.strings.member_runtime_usage_account_fallback),
                    style = RaftType.Caption.copy(fontSize = 13.sp, lineHeight = 17.sp)
                        .withRaftFontWeight(FontWeight.Bold),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                account.identityLabel?.let { identity ->
                    Text(
                        text = identity,
                        style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = RaftColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            MembersRuntimeUsageHealthBadge(account.health)
        }
        if (account.health == RuntimeAccountUsageHealth.Unsupported) {
            MembersRuntimeUsageNote(stringResource(MR.strings.member_runtime_usage_unsupported))
        }
        account.windows.forEach { window -> MembersRuntimeUsageWindowRow(window, timeContext) }
    }
}

@Composable
private fun MembersRuntimeUsageWindowRow(
    window: RuntimeAccountUsageWindow,
    timeContext: RaftTimeContext?
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val percent = window.displayPercentOrNull()
        if (percent == null) {
            // Never drawn as a bar: an unusable window is not "0% used".
            Text(
                text = window.label + " · " + stringResource(MR.strings.member_runtime_usage_window_unavailable),
                style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = RaftColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            RaftProgressBar(
                value = percent,
                tone =
                    if (window.status == RuntimeAccountUsageWindowStatus.LimitReached) {
                        RaftProgressBarTone.Orange
                    } else {
                        RaftProgressBarTone.Cyan
                    },
                label = window.label,
                showPercent = true
            )
            if (window.status == RuntimeAccountUsageWindowStatus.LimitReached) {
                Text(
                    text = stringResource(MR.strings.member_runtime_usage_limit_reached),
                    style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp).withRaftFontWeight(FontWeight.Bold),
                    color = RaftColor.TextBlack
                )
            }
            window.resetsAt?.let { resetsAt ->
                val resetLabel = raftRelativeTimestampLabel(resetsAt, timeContext)
                Text(
                    text = stringResource(MR.strings.member_runtime_usage_resets_at, resetLabel),
                    style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = RaftColor.TextMutedGray,
                    maxLines = 1
                )
            }
        }
    }
}

/** Whole-percent bar value, or null when the window must be shown as unavailable (never 0%). */
internal fun RuntimeAccountUsageWindow.displayPercentOrNull(): Int? {
    val ratio = usedRatio ?: return null
    if (status == RuntimeAccountUsageWindowStatus.Unavailable) return null
    return (ratio * 100).toInt().coerceIn(0, 100)
}

@Composable
private fun MembersRuntimeUsageHealthBadge(health: RuntimeAccountUsageHealth) {
    val (label, color) =
        when (health) {
            RuntimeAccountUsageHealth.Ok -> stringResource(MR.strings.member_runtime_usage_health_ok) to RaftColor.AccentLime
            RuntimeAccountUsageHealth.RateLimited ->
                stringResource(MR.strings.member_runtime_usage_health_rate_limited) to RaftColor.AccentOrange
            RuntimeAccountUsageHealth.ReauthRequired ->
                stringResource(MR.strings.member_runtime_usage_health_reauth_required) to RaftColor.AccentOrange
            RuntimeAccountUsageHealth.Unsupported ->
                stringResource(MR.strings.member_runtime_usage_health_unsupported) to RaftColor.SurfaceCream
            RuntimeAccountUsageHealth.Error ->
                stringResource(MR.strings.member_runtime_usage_health_error) to RaftColor.AccentRed
            RuntimeAccountUsageHealth.Unknown ->
                stringResource(MR.strings.member_runtime_usage_health_unknown) to RaftColor.SurfaceCream
        }
    // Accent fills are stickers (fixed ink); the cream fill is a themed surface.
    val sticker = color != RaftColor.SurfaceCream
    Text(
        text = label,
        style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp).withRaftFontWeight(FontWeight.Bold),
        color = if (sticker) RaftColor.StickerInk else RaftColor.TextBlack,
        maxLines = 1,
        modifier =
            Modifier
                .background(color)
                .border(RaftAppStroke.Thin, if (sticker) RaftColor.StickerInk else RaftColor.BorderBlack)
                .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun MembersRuntimeUsageBanner(text: String) {
    Text(
        text = text,
        style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withRaftFontWeight(FontWeight.Bold),
        color = RaftColor.StickerInk,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.AccentOrange)
                .border(RaftAppStroke.Thin, RaftColor.StickerInk)
                .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun MembersRuntimeUsageNote(
    text: String,
    color: Color = RaftColor.TextMutedGray,
    bold: Boolean = false
) {
    Text(
        text = text,
        style =
            RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 17.sp).let {
                if (bold) it.withRaftFontWeight(FontWeight.Bold) else it
            },
        color = if (bold) RaftColor.TextBlack else color
    )
}
