package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockProgressBar
import build.raft.app.components.SlockProgressBarTone
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.model.SlockTimeContext
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * Everything the agent detail needs to read runtime account usage (task #137). Provided once
 * by the member detail route; a null value (or a null owner/server) keeps the runtime chip in
 * its existing inert form.
 */
internal class MembersRuntimeAccountUsageAccess(
    val store: SharedRuntimeAccountUsageStore,
    val owner: SharedComputersOwner?,
    val serverId: String?
)

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

/** The usage entry is offered only once a read has returned something other than 403. */
internal fun RuntimeAccountUsageLoad?.offersRuntimeAccountUsage(): Boolean =
    this != null && this !is RuntimeAccountUsageLoad.Forbidden

/**
 * Whether anything in the read needs attention. Missing data, stale data, an unknown health or
 * an unavailable window are all attention states — none of them is presented as healthy.
 */
internal fun RuntimeAccountUsageLoad?.runtimeAccountUsageNeedsAttention(): Boolean =
    when (this) {
        null, RuntimeAccountUsageLoad.Forbidden -> false
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
        load = result
        loading = false
        if (result is RuntimeAccountUsageLoad.Forbidden) dialogOpen = false
    }
    val offered = key != null && load.offersRuntimeAccountUsage()
    val attention = offered && load.runtimeAccountUsageNeedsAttention()
    // Keep the modifier chain shape stable (Kuikly keeps stale native props when a modifier is
    // removed); only the enabled flag changes.
    MembersAgentRuntimeChip(
        label = if (offered) "$label ›" else label,
        kind = MembersAgentRuntimeChipKind.Runtime,
        modifier = Modifier.clickable(enabled = offered) { dialogOpen = true }
    )
    if (attention) {
        Text(
            text = stringResource(MR.strings.member_runtime_usage_attention),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    // Read before the dialog: its content is rehosted in a separate native window where page
    // composition locals are not retained.
    val timeContext = LocalSlockTimeContextProvider.current?.current
    if (dialogOpen && offered) {
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
    timeContext: SlockTimeContext?,
    load: RuntimeAccountUsageLoad?,
    loading: Boolean,
    onReload: () -> Unit,
    onDismiss: () -> Unit
) {
    SlockUnifiedDialog(
        title = stringResource(MR.strings.member_runtime_usage_title),
        overline = runtimeLabel,
        onDismiss = onDismiss,
        contentSpacing = 12.dp
    ) {
        when (load) {
            null -> MembersRuntimeUsageNote(stringResource(MR.strings.member_runtime_usage_reading))
            RuntimeAccountUsageLoad.Forbidden -> Unit
            is RuntimeAccountUsageLoad.Failed ->
                MembersRuntimeUsageNote(
                    text = stringResource(MR.strings.member_runtime_usage_read_failed),
                    color = SlockColor.AccentRed
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
        SlockBrutalActionButton(
            label =
                if (loading) {
                    stringResource(MR.strings.member_runtime_usage_reading)
                } else {
                    stringResource(MR.strings.member_runtime_usage_reload)
                },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            onClick = onReload
        )
    }
}

@Composable
private fun MembersRuntimeUsageAccountCard(
    account: RuntimeAccountUsageAccount,
    stale: Boolean,
    timeContext: SlockTimeContext?
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .background(if (stale) SlockColor.SurfaceCream else SlockColor.SurfaceWhite)
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
                    style = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 17.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                account.identityLabel?.let { identity ->
                    Text(
                        text = identity,
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = SlockColor.TextMutedGray,
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
    timeContext: SlockTimeContext?
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val percent = window.displayPercentOrNull()
        if (percent == null) {
            // Never drawn as a bar: an unusable window is not "0% used".
            Text(
                text = window.label + " · " + stringResource(MR.strings.member_runtime_usage_window_unavailable),
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            SlockProgressBar(
                value = percent,
                tone =
                    if (window.status == RuntimeAccountUsageWindowStatus.LimitReached) {
                        SlockProgressBarTone.Orange
                    } else {
                        SlockProgressBarTone.Cyan
                    },
                label = window.label,
                showPercent = true
            )
            if (window.status == RuntimeAccountUsageWindowStatus.LimitReached) {
                Text(
                    text = stringResource(MR.strings.member_runtime_usage_limit_reached),
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp).withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack
                )
            }
            window.resetsAt?.let { resetsAt ->
                val resetLabel = slockAbsoluteTimestampLabel(resetsAt, timeContext) ?: resetsAt
                Text(
                    text = stringResource(MR.strings.member_runtime_usage_resets_at, resetLabel),
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = SlockColor.TextMutedGray,
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
            RuntimeAccountUsageHealth.Ok -> stringResource(MR.strings.member_runtime_usage_health_ok) to SlockColor.AccentLime
            RuntimeAccountUsageHealth.RateLimited ->
                stringResource(MR.strings.member_runtime_usage_health_rate_limited) to SlockColor.AccentOrange
            RuntimeAccountUsageHealth.ReauthRequired ->
                stringResource(MR.strings.member_runtime_usage_health_reauth_required) to SlockColor.AccentOrange
            RuntimeAccountUsageHealth.Unsupported ->
                stringResource(MR.strings.member_runtime_usage_health_unsupported) to SlockColor.SurfaceCream
            RuntimeAccountUsageHealth.Error ->
                stringResource(MR.strings.member_runtime_usage_health_error) to SlockColor.AccentRed
            RuntimeAccountUsageHealth.Unknown ->
                stringResource(MR.strings.member_runtime_usage_health_unknown) to SlockColor.SurfaceCream
        }
    Text(
        text = label,
        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp).withSlockFontWeight(FontWeight.Bold),
        color = SlockColor.TextBlack,
        maxLines = 1,
        modifier =
            Modifier
                .background(color)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun MembersRuntimeUsageBanner(text: String) {
    Text(
        text = text,
        style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withSlockFontWeight(FontWeight.Bold),
        color = SlockColor.TextBlack,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.AccentOrange)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun MembersRuntimeUsageNote(
    text: String,
    color: Color = SlockColor.TextMutedGray,
    bold: Boolean = false
) {
    Text(
        text = text,
        style =
            SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 17.sp).let {
                if (bold) it.withSlockFontWeight(FontWeight.Bold) else it
            },
        color = if (bold) SlockColor.TextBlack else color
    )
}
