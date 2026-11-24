package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.base.Utils
import build.raft.app.compat.pager.Pager
import build.raft.app.data.PRO_AGENT_SEAT_BLOCK_SIZE
import build.raft.app.data.ProPricing
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.billingPricing
import build.raft.app.data.formatProSeatMonthlyUsd
import build.raft.app.data.loadServerProPricing
import build.raft.app.data.percentEncode
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/*
 * The upgrade dialog (#proj-mobile #54/#63; web `components/billing/UpgradeDialog.tsx`, botiverse/slock
 * staging afa0e4ada5): one dialog for every Free limit someone runs into. Payment happens on the web
 * (artin 11-05): the dialog says what was hit and what Pro adds, and its one action opens the web in
 * the system browser, at the web's own upgrade dialog for the same limit (owners pay there, others
 * send an owner a message), or, for seats, at Plan & Billing (Add seats). Coming back to the app
 * reloads the server's plan; once it is Pro the dialog closes and says so.
 *
 * iOS (App Store guideline 3.1.1, artin 11-05 "iOS 先不做"): no upgrade button and no word about
 * paying on the web. The dialog only states the limit ([RaftUpgradeMode.LimitNotice]). The one
 * switch is [iosExternalPurchaseLinkEnabled] (off).
 */

/**
 * A limit that opens the upgrade dialog. [wire] is the web's gate (shared `UPGRADE_GATES`, or the
 * paywall name for the two seat limits, which the web sends to Plan & Billing instead).
 */
internal enum class RaftUpgradeGate(val wire: String) {
    History30d("history_30d"),
    UploadMonthlyQuota("upload_monthly_quota"),
    UploadFileSize("upload_file_size"),
    JointChannelFreeCap("joint_channel_free_cap"),
    JointChannelHostLimit("joint_channel_host_limit"),
    JointChannelOverLimit("joint_channel_over_limit"),
    JointChannelReadOnly("joint_channel_read_only"),
    AgentMigration("agent_migration"),
    PlanDowngraded("plan_downgraded"),
    ProTheme("pro_theme"),

    /**
     * The Free plan's one external channel (shared `UPGRADE_GATES` `external_channel_free_limit`,
     * botiverse/slock #14034/#16295; the server's `free_external_limit` refusal): the server's one
     * slot is in use by a shared channel (open invites no longer count). Its own copy, leading
     * with unlimited external channels (web upgradeGateCopy.ts GATE_COPY, UpgradeDialog
     * GATE_HIGHLIGHT, botiverse/slock #14533). The web's lines naming a channel (`externalLimitCopy`:
     * the channel holding the slot, or another server's channel to connect to) pair with
     * [RaftUpgradeExternalContext.Slot].
     */
    ExternalChannelFreeLimit("external_channel_free_limit"),

    /**
     * Shared `external_channel_frozen` (botiverse/slock #14533): a Free server in more than one
     * external channel, read-only in all of them (web ChannelLimitBanners).
     */
    ExternalChannelFrozen("external_channel_frozen"),
    General("general"),

    /** Web `seat_limit_invite`: an invite past the seats (InviteHumanDialog → Plan & Billing). */
    SeatLimitInvite("seat_limit_invite"),

    /** Web `seat_limit_agent_create`: an agent past the plan's agents (CreateAgentDialog → Plan & Billing). */
    SeatLimitAgentCreate("seat_limit_agent_create");

    val isSeatLimit: Boolean get() = this == SeatLimitInvite || this == SeatLimitAgentCreate

    /** The web upgrade dialog's gate for this limit (a seat limit on Free: `general`). */
    val webDialogGate: String get() = if (isSeatLimit) General.wire else wire
}

/**
 * The server answered with a plan limit: which gate it is (null: not a plan limit). Web
 * `MessageInput` (`FILE_UPLOAD_QUOTA_EXCEEDED`), `AgentMigrationSection`
 * (`MIGRATION_PRO_PLAN_REQUIRED`), the seat limits of an invite or a created agent (server
 * `CapacityLimitError`).
 */
internal fun raftUpgradeGateForErrorCode(code: String?): RaftUpgradeGate? =
    when (code?.trim()?.lowercase()) {
        "file_upload_quota_exceeded",
        "upload_quota_exceeded" -> RaftUpgradeGate.UploadMonthlyQuota
        "external_limit_frozen" -> RaftUpgradeGate.ExternalChannelFrozen
        // server externalChannelLimitService ExternalChannelLimitError (paywallHit.ts → this gate).
        "free_external_limit" -> RaftUpgradeGate.ExternalChannelFreeLimit
        "migration_pro_plan_required" -> RaftUpgradeGate.AgentMigration
        // shared AGENT_LIMIT_REACHED_CODE / HUMAN_SEAT_LIMIT_REACHED_CODE (planService CapacityLimitError).
        "agent_limit_reached" -> RaftUpgradeGate.SeatLimitAgentCreate
        "human_seat_limit_reached" -> RaftUpgradeGate.SeatLimitInvite
        else -> null
    }

/** A refused invite that is the seat limit: its code, or web InviteHumanDialog `isSeatLimitError`'s words. */
internal fun raftIsSeatLimitError(code: String?, message: String?): Boolean =
    raftUpgradeGateForErrorCode(code) == RaftUpgradeGate.SeatLimitInvite || raftIsSeatLimitMessage(message)

/** Web InviteHumanDialog `isSeatLimitError`: a refused invite that is the seat limit, by its words. */
internal fun raftIsSeatLimitMessage(message: String?): Boolean {
    val text = message ?: return false
    return text.contains("seat limit") || text.contains("limit reached") || text.contains("requires the Pro plan")
}

/** Free's per-file upload ceiling (shared FREE_SINGLE_FILE_UPLOAD_LIMIT_BYTES). */
internal const val RaftFreeSingleFileUploadBytes = 50L * 1024 * 1024

/**
 * Whether files refused for size ran into Free's per-file ceiling (an upgrade raises it), not
 * Pro's: the server's per-file limit is Free's.
 */
internal fun raftUploadSizeLimitIsFree(maxBytes: Long): Boolean = maxBytes in 1..RaftFreeSingleFileUploadBytes

/**
 * Whether the app may link out to buy on the web from iOS (an external purchase link, App Store
 * guideline 3.1.1 / the US storefront's link entitlement). Off (artin 11-05): iOS states the limit
 * only, with no upgrade or add-seats entry. Turning it on also turns iOS Billing to the system
 * browser ([iosBillingPresentation] External), so every upgrade entry offers the link.
 */
internal fun iosExternalPurchaseLinkEnabled(): Boolean = false

/**
 * Whether the upgrade dialog links out to the web: Android and the Compose desktop app always;
 * iOS only with [iosExternalPurchaseLinkEnabled].
 */
internal fun raftUpgradeLinksOut(
    appStoreBillingRules: Boolean = raftPlatformFollowsAppStoreBillingRules(),
    iosLinkEnabled: Boolean = iosExternalPurchaseLinkEnabled(),
): Boolean = !appStoreBillingRules || iosLinkEnabled

/** What the dialog offers. */
internal enum class RaftUpgradeMode {
    /** A Free server: what Pro adds, and the web's upgrade dialog for the limit. */
    Upgrade,

    /** A Pro server out of seats (#63): Plan & Billing on the web, where Add seats is. */
    AddSeats,

    /** No link out (iOS): the limit, stated, and OK. */
    LimitNotice,
}

/** What a limit leads to on this server ([raftUpgradeOutcome]). */
internal sealed interface RaftUpgradeOutcome {
    data class Dialog(val mode: RaftUpgradeMode) : RaftUpgradeOutcome

    /**
     * A paid server: upgrading this server is not the answer (web `usePaywallUpgrade`: a Joint
     * Channel held back by other servers' plans), so Plan & Billing opens as before.
     */
    data object OpenBilling : RaftUpgradeOutcome

    /** Nothing to offer (e.g. a Pro server's own upload ceiling). */
    data object None : RaftUpgradeOutcome
}

private fun isFreePlan(plan: String?): Boolean = plan.isNullOrBlank() || plan == "free"

/**
 * The trigger mapping (web `usePaywallUpgrade` + the seat paywalls): a Free server gets the dialog;
 * a server out of seats on a paid plan gets Add seats; other limits on a paid server keep Plan &
 * Billing (joint channels, the history window after a downgrade) or nothing (uploads, themes,
 * migration: Pro already has them). Without a link out (iOS) only the limit is stated, and nothing
 * opens where a paid plan already lifts it.
 */
internal fun raftUpgradeOutcome(
    gate: RaftUpgradeGate,
    plan: String?,
    linksOut: Boolean = raftUpgradeLinksOut(),
): RaftUpgradeOutcome {
    val free = isFreePlan(plan)
    val offered =
        when {
            free -> RaftUpgradeMode.Upgrade
            gate.isSeatLimit -> RaftUpgradeMode.AddSeats
            else -> null
        }
    if (offered == null) {
        val billing =
            gate == RaftUpgradeGate.JointChannelFreeCap ||
                gate == RaftUpgradeGate.JointChannelHostLimit ||
                gate == RaftUpgradeGate.JointChannelOverLimit ||
                gate == RaftUpgradeGate.JointChannelReadOnly ||
                gate == RaftUpgradeGate.ExternalChannelFreeLimit ||
                gate == RaftUpgradeGate.ExternalChannelFrozen ||
                gate == RaftUpgradeGate.General
        return if (billing && linksOut) RaftUpgradeOutcome.OpenBilling else RaftUpgradeOutcome.None
    }
    return RaftUpgradeOutcome.Dialog(if (linksOut) offered else RaftUpgradeMode.LimitNotice)
}

/**
 * Extra context for an external-channel upgrade gate (web `ExternalLimitContext`, botiverse/slock
 * #15653). Paired strictly with its gate via [externalContextForGate]; mismatched pairings are
 * ignored and fall back to the gate's own copy.
 */
internal sealed interface RaftUpgradeExternalContext {
    /**
     * Opened from `ExternalFreezeNotice` before the server's external channel freeze begins:
     * carries the ISO `externalFreezeAt` deadline so the dialog and web return say from when the
     * freeze takes effect rather than "again".
     */
    data class FreezeUpcoming(val deadlineIso: String) : RaftUpgradeExternalContext

    /**
     * Opened when a Free server's single external-channel slot is held by one known live channel
     * (web `ExternalLimitContext` `{ kind: "slot", channel: "#name" }`).
     */
    data class Slot(val channelName: String?) : RaftUpgradeExternalContext
}

/**
 * Web `externalContextForGate` (`upgradeGateCopy.ts`): pairs [context] only with the gate that
 * understands it (`FreezeUpcoming` only with [RaftUpgradeGate.ExternalChannelFrozen] and a
 * non-blank deadline; `Slot` only with [RaftUpgradeGate.ExternalChannelFreeLimit] and a non-blank
 * channel name). Any other pairing returns null so the gate's default copy is used.
 */
internal fun externalContextForGate(
    gate: RaftUpgradeGate,
    context: RaftUpgradeExternalContext?,
): RaftUpgradeExternalContext? {
    if (context == null) return null
    return when (context) {
        is RaftUpgradeExternalContext.FreezeUpcoming ->
            context.takeIf {
                gate == RaftUpgradeGate.ExternalChannelFrozen &&
                    it.deadlineIso.isNotBlank() &&
                    parseRaftTimestampEpochMillisOrNull(it.deadlineIso, 0) != null
            }
        is RaftUpgradeExternalContext.Slot ->
            context.takeIf {
                gate == RaftUpgradeGate.ExternalChannelFreeLimit &&
                    !it.channelName.isNullOrBlank()
            }
    }
}

/**
 * Web `holdsExternalSlot` (`externalChannelSlot.ts`): whether a live channel occupies the Free
 * plan's single external-channel slot on [serverId].
 */
internal fun build.raft.app.data.HomeChannelRow.holdsExternalSlot(serverId: String): Boolean {
    if (serverId.isBlank() || archived) return false
    val type = channelType ?: if (kind == build.raft.app.model.ConversationKind.Joint) "joint" else null
    return when (type) {
        "joint" -> hasExternalRelation || jointServers.isEmpty() || jointServers.any { it.id != serverId }
        "channel", "private" -> hasExternalRelation
        else -> false
    }
}

/**
 * Web `externalSlotContext`: when exactly one live channel (excluding [exceptChannelId]) holds
 * [serverId]'s Free external-channel slot, returns [RaftUpgradeExternalContext.Slot] naming it
 * (`#channel`); otherwise null so the dialog falls back to the general external-limit wording.
 */
internal fun externalSlotUpgradeContext(
    channels: List<build.raft.app.data.HomeChannelRow>,
    serverId: String,
    exceptChannelId: String? = null,
): RaftUpgradeExternalContext.Slot? {
    val held = channels.filter { it.channelId.value != exceptChannelId && it.holdsExternalSlot(serverId) }
    val single = held.singleOrNull() ?: return null
    val cleanName = single.name.trim().removePrefix("#").takeIf { it.isNotBlank() } ?: return null
    return RaftUpgradeExternalContext.Slot("#$cleanName")
}

/** Web `slotChannelsKey`: changes whenever the set of channels holding the external slot changes. */
internal fun externalSlotChannelsKey(
    channels: List<build.raft.app.data.HomeChannelRow>,
    serverId: String,
): String = channels.filter { it.holdsExternalSlot(serverId) }.joinToString(",") { it.channelId.value }

/**
 * The external channel freeze's start in the viewer's [RaftTimeContext] (web `formatFreezeDeadline`,
 * medium date + short time); falls back to the raw ISO string when unparseable.
 */
@Composable
internal fun raftFormatFreezeDeadline(iso: String, timeContext: RaftTimeContext? = null): String =
    raftLocalizedMediumDateTime(iso, timeContext)

/**
 * The web page the dialog opens. [RaftUpgradeMode.Upgrade]: Plan & Billing with the web upgrade
 * dialog open for the gate (web UpgradeDialog's Stripe-return parameters `upgrade=<gate>` plus
 * `upgradeCanceled=1`, and `upgradeFreezeAt=<iso>` for [RaftUpgradeExternalContext.FreezeUpcoming],
 * which reopen it at its first step: an owner's plan choice, or a member's message to an owner).
 * [RaftUpgradeMode.AddSeats]: Plan & Billing, where an owner's Add seats is (web
 * PlanSectionContent; the web has no link straight into AddSeatsDialog).
 */
internal fun raftUpgradeWebUrl(
    webBaseUrl: String?,
    serverSlug: String?,
    gate: RaftUpgradeGate,
    mode: RaftUpgradeMode,
    localeTag: String,
    externalContext: RaftUpgradeExternalContext? = null,
): String {
    val path = serverSlug?.takeIf { it.isNotBlank() }?.let { "s/$it/settings/billing" } ?: "settings/billing"
    val base = sharedSettingsWebUrl(webBaseUrl, path)
    val freezeAt =
        (externalContextForGate(gate, externalContext) as? RaftUpgradeExternalContext.FreezeUpcoming)
            ?.deadlineIso
    val query =
        buildList {
            if (mode == RaftUpgradeMode.Upgrade) {
                add("upgrade" to gate.webDialogGate)
                add("upgradeCanceled" to "1")
                if (freezeAt != null) {
                    add("upgradeFreezeAt" to freezeAt.percentEncode())
                }
            }
            add("lang" to localeTag)
        }
    return base + "?" + query.joinToString("&") { (key, value) -> "$key=$value" }
}

/** One open upgrade dialog: shown by the [RaftUpgradeDialogHost] of the page that opened it. */
internal data class RaftUpgradeRequest(
    val gate: RaftUpgradeGate,
    val mode: RaftUpgradeMode,
    val pagerId: String,
    val serverId: String,
    val serverName: String,
    /** Web `manageBilling` (the owner): pays on the web; anyone else asks an owner there. */
    val owner: Boolean,
    val webUrl: String,
    /** The web was opened and the app has not seen the plan change yet. */
    val waitingForWeb: Boolean = false,
    /** Gate-paired external channel context (e.g. `FreezeUpcoming` from `ExternalFreezeNotice`). */
    val externalContext: RaftUpgradeExternalContext? = null,
    /** Owning shell store scope for pushing the authenticated in-app billing WebView (#proj-mobile:#204). */
    val storeScope: RaftShellStoreScope = RaftShellStoreScope.Legacy,
    /**
     * Server-resolved Pro pricing (slock #16367, `GET /api/billing/pricing`): null until the server
     * answers, so the dialog never quotes a `seat_v1` price on a guess.
     */
    val pricing: ProPricing? = null,
)

/** The open upgrade dialog, if any (one per process, like web `useUpgradeDialogStore`). */
internal object RaftUpgradeDialogState {
    private val requestFlow = MutableStateFlow<RaftUpgradeRequest?>(null)

    var request: RaftUpgradeRequest?
        get() = requestFlow.value
        internal set(value) {
            requestFlow.value = value
        }

    @Composable
    fun observeRequest(): RaftUpgradeRequest? = requestFlow.collectAsState().value

    internal var refresher: (suspend (RaftUpgradeRequest) -> String?)? = null

    fun close() {
        requestFlow.value = null
        refresher = null
    }
}

private val raftUpgradeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * Opens the upgrade dialog for [gate] on the current server (or Plan & Billing, or nothing:
 * [raftUpgradeOutcome]). Reads the server's plan, name, slug and the viewer's role from the session.
 */
internal fun Pager.openRaftUpgradeDialog(
    gate: RaftUpgradeGate,
    storeScope: RaftShellStoreScope,
    externalContext: RaftUpgradeExternalContext? = null,
    targetServerId: String? = null,
    knownBillingPresent: Boolean = false,
    knownPricing: ProPricing? = null,
) {
    val bridge = Utils.bridgeModule(pagerId)
    val sharedStores = RaftSharedStoreRegistry.get(bridge)
    val webBaseUrl = standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)
    val authStore = sharedStores.authStore(storeScope)
    val authDataSource = sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope)
    val pager = this
    val pairedContext = externalContextForGate(gate, externalContext)
    raftUpgradeScope.launch {
        val session = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
        val requestedServerId = targetServerId?.trim()?.takeIf { it.isNotEmpty() }
        val server =
            if (requestedServerId != null) {
                session?.servers?.firstOrNull { it.id == requestedServerId }?.toServerSummary() ?: return@launch
            } else {
                session?.currentServerSummary()
            }
        val outcome = raftUpgradeOutcome(gate, server?.plan)
        bridge.log("upgrade_dialog_open gate=${gate.wire} plan=${server?.plan ?: "unknown"} outcome=$outcome")
        when (outcome) {
            RaftUpgradeOutcome.None -> return@launch
            RaftUpgradeOutcome.OpenBilling -> {
                pager.openSettingsBillingPage(storeScope)
                return@launch
            }
            is RaftUpgradeOutcome.Dialog -> Unit
        }
        val mode = (outcome as RaftUpgradeOutcome.Dialog).mode
        val resolvedSlug =
            server?.slug?.takeIf { it.isNotBlank() }
                ?: if (requestedServerId == null) standaloneSettingsServerSlug(session) else null
        val initialPricing = if (mode != RaftUpgradeMode.LimitNotice && knownBillingPresent) billingPricing(knownPricing) else null
        val request =
            RaftUpgradeRequest(
                gate = gate,
                mode = mode,
                pagerId = pagerId,
                serverId = server?.id?.value.orEmpty(),
                serverName = server?.name.orEmpty(),
                owner = server?.role == "owner",
                webUrl =
                    raftUpgradeWebUrl(
                        webBaseUrl,
                        resolvedSlug,
                        gate,
                        mode,
                        readStandaloneSettingsLocaleTag(),
                        pairedContext,
                    ),
                externalContext = pairedContext,
                storeScope = storeScope,
                pricing = initialPricing,
            )
        RaftUpgradeDialogState.refresher = { current -> raftReloadServerPlan(authDataSource, current.serverId) }
        RaftUpgradeDialogState.request = request
        // #16367: ask GET /api/billing/pricing (readable by any member) when billing is not already known.
        // Never in LimitNotice (iOS view-only App Store guideline 3.1.1) and never quote a guessed price.
        if (mode != RaftUpgradeMode.LimitNotice && !knownBillingPresent && request.serverId.isNotBlank()) {
            val resolved =
                loadServerProPricing(
                    networkClient = sharedStores.networkClient(storeScope),
                    serverId = request.serverId,
                )
            if (resolved != null) {
                val current = RaftUpgradeDialogState.request
                if (current != null &&
                    current.pagerId == request.pagerId &&
                    current.serverId == request.serverId &&
                    current.gate == request.gate &&
                    current.mode == request.mode
                ) {
                    RaftUpgradeDialogState.request = current.copy(pricing = resolved)
                }
            }
        }
    }
}

/** `GET /api/servers` again (the session's plans); the server's plan after it, or null. */
private suspend fun raftReloadServerPlan(authDataSource: SharedAuthDataSource, serverId: String): String? {
    val result = runCatchingPreservingCancellation { authDataSource.loadServers() }.getOrNull()
    val session = (result as? SharedAuthResult.Success<StoredAuthSession>)?.data ?: return null
    return session.servers.firstOrNull { it.id == serverId }?.plan
}

/**
 * Whether coming back with [plan] ends the dialog: the server turned Pro (an upgrade), or the
 * person came back from Plan & Billing (seats: the web counts them; nothing to wait for here).
 */
internal fun raftUpgradeDoneOnReturn(mode: RaftUpgradeMode, plan: String?): Boolean =
    when (mode) {
        RaftUpgradeMode.Upgrade -> plan != null && !isFreePlan(plan)
        RaftUpgradeMode.AddSeats, RaftUpgradeMode.LimitNotice -> false
    }

/**
 * Shows the open upgrade dialog in the page that opened it ([RaftUpgradeRequest.pagerId]); mounted
 * once per page by `RaftPagerHost`. Opens the web through the host (the system browser), and when
 * the page shows again after that, reloads the plan.
 */
@Composable
internal fun RaftUpgradeDialogHost(pager: Pager) {
    val request = RaftUpgradeDialogState.observeRequest()?.takeIf { it.pagerId == pager.pagerId } ?: return
    val bridge = Utils.bridgeModule(pager.pagerId)
    val paidToast =
        stringResource(
            if (externalContextForGate(request.gate, request.externalContext) is RaftUpgradeExternalContext.FreezeUpcoming) {
                Res.string.upgrade_dialog_external_freeze_upcoming_done
            } else {
                Res.string.upgrade_dialog_paid_toast
            },
            request.serverName,
        )
    val currentRequest by rememberUpdatedState(request)
    val currentPaidToast by rememberUpdatedState(paidToast)
    fun checkUpgradeReturn() {
        val waiting = currentRequest.takeIf { it.waitingForWeb } ?: return
        val refresh = RaftUpgradeDialogState.refresher ?: return
        raftUpgradeScope.launch {
            val plan = refresh(waiting)
            bridge.log("upgrade_dialog_return gate=${waiting.gate.wire} plan=${plan ?: "unknown"}")
            if (RaftUpgradeDialogState.request != waiting) return@launch
            if (raftUpgradeDoneOnReturn(waiting.mode, plan)) {
                RaftUpgradeDialogState.close()
                bridge.toast(currentPaidToast)
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) checkUpgradeReturn()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val pagerAppeared = rememberRaftPagerAppeared(pager)
    var wasPagerAppeared by remember(pager) { mutableStateOf(pagerAppeared) }
    LaunchedEffect(pagerAppeared) {
        if (pagerAppeared && !wasPagerAppeared) {
            checkUpgradeReturn()
        }
        wasPagerAppeared = pagerAppeared
    }
    // Opening the authenticated billing WebView synchronously fires the page-cover dismiss signal
    // before the new page covers this pager. Suppress closing RaftUpgradeDialogState during that
    // push so `waitingForWeb` and `refresher` survive until the user returns, and hide the modal
    // while covered (`pagerAppeared == false`) so it never floats over the pushed WebView.
    val suppressCoverDismiss = remember(pager) { booleanArrayOf(false) }
    if (!pagerAppeared) return
    RaftUpgradeDialog(
        request = request,
        onOpenWeb = {
            bridge.log("upgrade_dialog_open_web gate=${request.gate.wire} mode=${request.mode}")
            // #proj-mobile:#204: open the upgrade page in the authenticated in-app WebView on Android/Desktop;
            // iOS keeps the external browser path (App Store Guideline 3.1.1).
            val webBaseUrl = standaloneSettingsWebBaseUrl(RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore)
            val openedInApp =
                if (!raftPlatformFollowsAppStoreBillingRules()) {
                    suppressCoverDismiss[0] = true
                    try {
                        pager.openRaftOwnWebPage(
                            rawUrl = request.webUrl,
                            storeScopeOverride = request.storeScope,
                            activeWebBaseUrl = webBaseUrl,
                        )
                    } finally {
                        suppressCoverDismiss[0] = false
                    }
                } else {
                    false
                }
            if (!openedInApp) {
                // A web browser even though it is a Raft link (the app would otherwise open it itself).
                bridge.hostCapabilities.openUrlInBrowser(request.webUrl)
            }
            RaftUpgradeDialogState.request = request.copy(waitingForWeb = true)
        },
        onDismiss = {
            if (!suppressCoverDismiss[0]) {
                RaftUpgradeDialogState.close()
            }
        },
    )
}

/** Copy for one gate: the web dialog's title and hint, and the limit stated plainly (iOS). */
internal data class RaftUpgradeGateCopy(
    val title: StringResource,
    val hint: StringResource,
    val limit: StringResource,
    /** The Pro benefit listed first ([RaftUpgradeBenefit]); null keeps the offer's order. */
    val first: RaftUpgradeBenefit?,
)

internal enum class RaftUpgradeBenefit(val pro: StringResource, val free: StringResource) {
    History(Res.string.upgrade_dialog_benefit_history_pro, Res.string.upgrade_dialog_benefit_history_free),
    Files(Res.string.upgrade_dialog_benefit_files_pro, Res.string.upgrade_dialog_benefit_files_free),
    Joint(Res.string.upgrade_dialog_benefit_joint_pro, Res.string.upgrade_dialog_benefit_joint_free),
    Migration(Res.string.upgrade_dialog_benefit_migration_pro, Res.string.upgrade_dialog_benefit_migration_free),
}

/** Web ProOffer: what Pro adds over Free, the limit just hit first. */
internal fun raftUpgradeBenefits(first: RaftUpgradeBenefit?): List<RaftUpgradeBenefit> =
    RaftUpgradeBenefit.entries.let { all -> if (first == null) all else listOf(first) + all.filter { it != first } }

internal fun raftUpgradeGateCopy(gate: RaftUpgradeGate): RaftUpgradeGateCopy =
    when (gate) {
        RaftUpgradeGate.History30d ->
            RaftUpgradeGateCopy(Res.string.upgrade_dialog_history_title, Res.string.upgrade_dialog_history_hint, Res.string.upgrade_dialog_limit_history, RaftUpgradeBenefit.History)
        RaftUpgradeGate.UploadMonthlyQuota ->
            RaftUpgradeGateCopy(Res.string.upgrade_dialog_upload_quota_title, Res.string.upgrade_dialog_upload_quota_hint, Res.string.upgrade_dialog_limit_upload_quota, RaftUpgradeBenefit.Files)
        RaftUpgradeGate.UploadFileSize ->
            RaftUpgradeGateCopy(Res.string.upgrade_dialog_file_size_title, Res.string.upgrade_dialog_file_size_hint, Res.string.upgrade_dialog_limit_file_size, RaftUpgradeBenefit.Files)
        // Legacy Joint Channel gates map to the external channel copy (botiverse/slock #15686).
        RaftUpgradeGate.JointChannelFreeCap,
        RaftUpgradeGate.JointChannelHostLimit,
        RaftUpgradeGate.ExternalChannelFreeLimit ->
            RaftUpgradeGateCopy(Res.string.upgrade_dialog_external_limit_title, Res.string.upgrade_dialog_external_limit_hint, Res.string.upgrade_dialog_limit_external_limit, RaftUpgradeBenefit.Joint)
        RaftUpgradeGate.JointChannelOverLimit,
        RaftUpgradeGate.JointChannelReadOnly,
        RaftUpgradeGate.ExternalChannelFrozen ->
            RaftUpgradeGateCopy(Res.string.upgrade_dialog_external_frozen_title, Res.string.upgrade_dialog_external_frozen_hint, Res.string.upgrade_dialog_limit_external_frozen, RaftUpgradeBenefit.Joint)
        RaftUpgradeGate.AgentMigration ->
            RaftUpgradeGateCopy(Res.string.upgrade_dialog_migration_title, Res.string.upgrade_dialog_migration_hint, Res.string.upgrade_dialog_limit_migration, RaftUpgradeBenefit.Migration)
        RaftUpgradeGate.PlanDowngraded ->
            RaftUpgradeGateCopy(Res.string.upgrade_dialog_downgraded_title, Res.string.upgrade_dialog_downgraded_hint, Res.string.upgrade_dialog_limit_downgraded, RaftUpgradeBenefit.History)
        RaftUpgradeGate.ProTheme ->
            RaftUpgradeGateCopy(Res.string.upgrade_dialog_pro_theme_title, Res.string.upgrade_dialog_pro_theme_hint, Res.string.upgrade_dialog_limit_pro_theme, null)
        RaftUpgradeGate.General ->
            RaftUpgradeGateCopy(Res.string.upgrade_dialog_general_title, Res.string.upgrade_dialog_general_hint, Res.string.upgrade_dialog_limit_general, null)
        // On Free a seat limit is Free's: the web's `general` offer.
        RaftUpgradeGate.SeatLimitInvite, RaftUpgradeGate.SeatLimitAgentCreate ->
            RaftUpgradeGateCopy(Res.string.upgrade_dialog_general_title, Res.string.upgrade_dialog_general_hint, Res.string.upgrade_dialog_limit_seats, null)
    }

/**
 * The upgrade dialog (see the file comment), in the app's dialog card: eyebrow, a title of up to
 * three lines, the limit, what Pro adds, who finishes it, and the action in the footer.
 * Every string takes the server name as `%1$s` (unused where the copy has none).
 */
@Composable
internal fun RaftUpgradeDialog(
    request: RaftUpgradeRequest,
    onOpenWeb: () -> Unit,
    onDismiss: () -> Unit,
) {
    val server = request.serverName
    val copy = raftUpgradeGateCopy(request.gate)
    val pairedContext = externalContextForGate(request.gate, request.externalContext)
    val freezeUpcoming = pairedContext as? RaftUpgradeExternalContext.FreezeUpcoming
    val freezeDeadline = freezeUpcoming?.let { raftFormatFreezeDeadline(it.deadlineIso) }
    val slotChannel = (pairedContext as? RaftUpgradeExternalContext.Slot)?.channelName
    if (request.mode == RaftUpgradeMode.LimitNotice) {
        RaftUnifiedDialog(
            title = stringResource(Res.string.upgrade_dialog_limit_title),
            onDismiss = onDismiss,
            maxWidth = 448.dp,
            titleMaxLines = 3,
        ) {
            RaftText(
                text =
                    when {
                        freezeDeadline != null ->
                            stringResource(Res.string.upgrade_dialog_limit_external_freeze_upcoming, freezeDeadline, server)
                        slotChannel != null ->
                            stringResource(Res.string.upgrade_dialog_limit_external_limit_channel, server, slotChannel)
                        else -> stringResource(copy.limit, server)
                    },
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foreground,
            )
            RaftDialogFooter {
                RaftButton(
                    text = stringResource(Res.string.common_ok),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                )
            }
        }
        return
    }
    val seats = request.mode == RaftUpgradeMode.AddSeats
    val title =
        when {
            seats -> stringResource(Res.string.upgrade_dialog_seats_title, server)
            freezeDeadline != null -> stringResource(Res.string.upgrade_dialog_external_freeze_upcoming_title)
            else -> stringResource(copy.title, server)
        }
    val hint =
        when {
            seats && request.pricing == ProPricing.PersonV2 -> stringResource(Res.string.upgrade_dialog_seats_hint_person, server)
            seats -> stringResource(Res.string.upgrade_dialog_seats_hint, server)
            freezeDeadline != null ->
                stringResource(Res.string.upgrade_dialog_external_freeze_upcoming_hint, freezeDeadline, server)
            slotChannel != null ->
                stringResource(Res.string.upgrade_dialog_external_limit_hint_channel, server, slotChannel)
            else -> stringResource(copy.hint, server)
        }
    val action =
        stringResource(
            when {
                request.waitingForWeb -> Res.string.upgrade_dialog_open_again
                seats -> Res.string.upgrade_dialog_seats_continue
                request.owner -> Res.string.upgrade_dialog_continue_owner
                else -> Res.string.upgrade_dialog_continue_member
            }
        )
    val leavesRaft: @Composable () -> Unit = { RaftContentLucideIcon(RaftLucideIconName.ArrowUpRight, size = 18.dp) }
    RaftUnifiedDialog(
        title = title,
        overline = stringResource(if (seats) Res.string.upgrade_dialog_eyebrow_seats else Res.string.upgrade_dialog_eyebrow),
        onDismiss = onDismiss,
        maxWidth = 480.dp,
        titleMaxLines = 3,
    ) {
        RaftText(
            text = hint,
            style = RaftTheme.typography.subhead,
            color = RaftTheme.colors.foregroundMuted,
        )
        if (!seats) {
            RaftUpgradeBenefitList(raftUpgradeBenefits(copy.first), firstIsHit = copy.first != null)
            request.pricing?.let { resolvedPricing ->
                RaftUpgradePricingSummary(resolvedPricing)
            }
        }
        if (request.waitingForWeb) {
            RaftBanner(
                description =
                    stringResource(if (seats) Res.string.upgrade_dialog_seats_waiting else Res.string.upgrade_dialog_waiting, server),
                status = RaftBannerStatus.Info,
                size = RaftBannerSize.Sm,
            )
        } else {
            RaftText(
                text =
                    stringResource(
                        when {
                            seats && request.owner -> Res.string.upgrade_dialog_seats_owner_note
                            seats -> Res.string.upgrade_dialog_seats_member_note
                            request.owner -> Res.string.upgrade_dialog_owner_note
                            else -> Res.string.upgrade_dialog_member_note
                        },
                        server,
                    ),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
            )
        }
        // Web UpgradeDialog's footer: the one action (× closes), on a phone too, where the dialog
        // sheet's one-line head would cut the title and the long action short.
        RaftDialogFooter {
            RaftButton(
                text = action,
                onClick = onOpenWeb,
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                trailingIcon = leavesRaft,
            )
        }
    }
}

/**
 * Web ProOfferPanel's list: each Pro line with Free's struck through under it; the limit just hit
 * first, on an attention-soft row in bold (`bg-attention-soft font-bold`).
 */
@Composable
private fun RaftUpgradeBenefitList(benefits: List<RaftUpgradeBenefit>, firstIsHit: Boolean) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        benefits.forEachIndexed { index, benefit ->
            val hit = firstIsHit && index == 0
            Row(
                modifier =
                    if (hit) {
                        Modifier.fillMaxWidth()
                            .background(RaftTheme.colors.attentionSoft, RoundedCornerShape(RaftRadius.Control))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    } else {
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RaftContentLucideIcon(
                    RaftLucideIconName.Check,
                    size = 16.dp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RaftText(
                        text = stringResource(benefit.pro),
                        style =
                            RaftTheme.typography.callout.copy(
                                fontWeight = if (hit) FontWeight.Bold else FontWeight.Medium
                            ),
                        color = RaftTheme.colors.foreground,
                    )
                    RaftText(
                        text = stringResource(benefit.free),
                        style = RaftTheme.typography.footnote.copy(textDecoration = TextDecoration.LineThrough),
                        color = RaftTheme.colors.foregroundHint,
                    )
                }
            }
        }
    }
}

/**
 * Server-resolved Pro pricing lines (web `ProOffer` / `PlanSectionContent`, slock #16367):
 * only rendered once the server has answered (`pricing != null`), never from a client-side guess.
 */
@Composable
private fun RaftUpgradePricingSummary(pricing: ProPricing) {
    val monthly = formatProSeatMonthlyUsd(annual = false, pricing = pricing)
    val annualMonthly = formatProSeatMonthlyUsd(annual = true, pricing = pricing)
    val priceLine =
        if (pricing.agentsTakeSeats) {
            stringResource(Res.string.upgrade_dialog_price_seat, monthly, annualMonthly)
        } else {
            stringResource(Res.string.upgrade_dialog_price_person, monthly, annualMonthly)
        }
    val seatNote =
        if (pricing.agentsTakeSeats) {
            stringResource(Res.string.upgrade_dialog_seat_note_seat, PRO_AGENT_SEAT_BLOCK_SIZE)
        } else {
            stringResource(Res.string.upgrade_dialog_seat_note_person)
        }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RaftText(
            text = priceLine,
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foreground,
        )
        RaftText(
            text = seatNote,
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundHint,
        )
    }
}
