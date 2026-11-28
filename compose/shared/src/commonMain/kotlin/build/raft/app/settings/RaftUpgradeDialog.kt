package build.raft.app.settings

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
import build.raft.app.RaftContentLucideIcon
import build.raft.app.RaftDialogFooter
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftUnifiedDialog
import build.raft.app.base.Utils
import build.raft.app.compat.pager.Pager
import build.raft.app.currentRaftEffectiveDateTimeContext
import build.raft.app.data.PRO_AGENT_SEAT_BLOCK_SIZE
import build.raft.app.data.ProPricing
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SeatRequestPurpose
import build.raft.app.data.SettingsBillingFileUploadQuotaSnapshot
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.billingPricing
import build.raft.app.data.formatProSeatMonthlyUsd
import build.raft.app.data.loadServerAgentHistoryBlocks7d
import build.raft.app.data.loadServerBillingSubscriptionSummary
import build.raft.app.data.loadServerProPricing
import build.raft.app.data.percentEncode
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.format.RaftFormat
import build.raft.app.get
import build.raft.app.holdsExternalSlot
import build.raft.app.members.membersLocalizedCreatedDateOrNull
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.raftLocalizedMediumDateTime
import build.raft.app.raftLocalizedShortMonth
import build.raft.app.raftPlatformFollowsAppStoreBillingRules
import build.raft.app.resources.*
import build.raft.app.settings.holdsExternalSlot
import build.raft.app.sharedSettingsWebUrl
import build.raft.app.thread.key
import build.raft.app.thread.threadHistoryPaywallCount
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

    /**
     * An AppSumo lifetime server at its retained storage limit (web `AppSumoUploadLimit`,
     * botiverse/slock #17191): directs users to delete unused files without Pro pricing,
     * Stripe checkout, or monthly reset copy; owners open Plan & billing, members Close.
     */
    AppSumoUploadLimit,
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
    isAppSumoUpload: Boolean = false,
): RaftUpgradeOutcome {
    if (gate == RaftUpgradeGate.UploadMonthlyQuota && isAppSumoUpload) {
        return RaftUpgradeOutcome.Dialog(RaftUpgradeMode.AppSumoUploadLimit)
    }
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

/**
 * What the 30-day history window hid where the paywall was met (web `HistoryLimitContext`,
 * botiverse/slock #14512): how many older messages the conversation has (a named channel by its
 * `#name`), or when the message a link opened was sent. Paired strictly with
 * [RaftUpgradeGate.History30d] via [historyContextForGate].
 */
internal sealed interface RaftUpgradeHistoryContext {
    data class Channel(val channel: String, val count: Int, val capped: Boolean) : RaftUpgradeHistoryContext
    data class Thread(val count: Int, val capped: Boolean) : RaftUpgradeHistoryContext
    data class Conversation(val count: Int, val capped: Boolean) : RaftUpgradeHistoryContext
    data class Target(val sentAtIso: String) : RaftUpgradeHistoryContext
}

/**
 * Pairs [context] only with [RaftUpgradeGate.History30d] when well-formed; any other gate or empty
 * count/channel falls back to the gate's own copy (without the 7-day agent hit count).
 */
internal fun historyContextForGate(
    gate: RaftUpgradeGate,
    context: RaftUpgradeHistoryContext?,
): RaftUpgradeHistoryContext? {
    if (gate != RaftUpgradeGate.History30d || context == null) return null
    return when (context) {
        is RaftUpgradeHistoryContext.Channel ->
            context.takeIf { it.channel.isNotBlank() && it.count > 0 }
        is RaftUpgradeHistoryContext.Thread ->
            context.takeIf { it.count > 0 }
        is RaftUpgradeHistoryContext.Conversation ->
            context.takeIf { it.count > 0 }
        is RaftUpgradeHistoryContext.Target ->
            context.takeIf { it.sentAtIso.isNotBlank() }
    }
}

/** Below 1 MiB remaining, web treats the monthly upload allowance as used up rather than short. */
internal const val RaftMonthlyUploadUsedUpBelowBytes = 1024L * 1024L

/** One refused attachment named in a monthly upload quota upgrade dialog (web `UploadLimitContext.files`). */
internal data class RaftUpgradeUploadFile(val name: String, val sizeBytes: Long)

/**
 * Extra context for a monthly upload quota upgrade gate (web `UploadLimitContext` `monthly_quota`,
 * botiverse/slock #14968). Paired strictly with [RaftUpgradeGate.UploadMonthlyQuota] via
 * [uploadContextForGate].
 */
internal sealed interface RaftUpgradeUploadContext {
    data class MonthlyQuota(
        val month: String,
        val limitBytes: Long,
        val remainingBytes: Long,
        val files: List<RaftUpgradeUploadFile> = emptyList(),
        val source: String? = null,
    ) : RaftUpgradeUploadContext {
        val isAppSumo: Boolean get() = source.equals("appsumo", ignoreCase = true)
    }
}

/**
 * Pairs [context] only with [RaftUpgradeGate.UploadMonthlyQuota] when well-formed (`YYYY-MM`
 * month for Free monthly quota, positive limit, non-negative remaining bytes); any other gate or
 * malformed snapshot returns null so the gate's default copy is used without the 7-day agent
 * refusal count.
 */
internal fun uploadContextForGate(
    gate: RaftUpgradeGate,
    context: RaftUpgradeUploadContext?,
): RaftUpgradeUploadContext? {
    if (gate != RaftUpgradeGate.UploadMonthlyQuota || context == null) return null
    return when (context) {
        is RaftUpgradeUploadContext.MonthlyQuota ->
            context.takeIf {
                (it.isAppSumo || settingsBillingYearMonth(it.month) != null) &&
                    it.limitBytes > 0L &&
                    it.remainingBytes >= 0L
            }
    }
}

internal fun SettingsBillingFileUploadQuotaSnapshot.toRaftUpgradeMonthlyUploadContext(
    files: List<RaftUpgradeUploadFile> = emptyList(),
): RaftUpgradeUploadContext.MonthlyQuota? {
    val isAppSumo = source.equals("appsumo", ignoreCase = true)
    val cleanMonth = month?.trim()?.takeIf { settingsBillingYearMonth(it) != null }
    if (!isAppSumo && cleanMonth == null) return null
    if (!limited || limitBytes <= 0L || remainingBytes < 0L) return null
    return RaftUpgradeUploadContext.MonthlyQuota(
        month = cleanMonth.orEmpty(),
        limitBytes = limitBytes,
        remainingBytes = remainingBytes,
        files = files.filter { it.name.isNotBlank() && it.sizeBytes >= 0L },
        source = if (isAppSumo) "appsumo" else source?.trim()?.takeIf { it.isNotEmpty() },
    )
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
    /** Gate-paired conversation history paywall context (`History30d`, botiverse/slock #14512). */
    val historyContext: RaftUpgradeHistoryContext? = null,
    /**
     * How often this server's agents ran into the 30-day history window in the past 7 days
     * (`GET /billing/subscription` `agentHistoryBlocks7d`, botiverse/slock #14512). Shown only
     * inside the contextual [historyContext] line when > 0.
     */
    val agentHistoryBlocks7d: Int? = null,
    /** Gate-paired monthly upload quota context (`UploadMonthlyQuota`, botiverse/slock #14968). */
    val uploadContext: RaftUpgradeUploadContext? = null,
    /**
     * How often this server's agents were refused by the monthly upload limit in the past 7 days
     * (`GET /billing/subscription` `agentUploadQuotaBlocks7d`, botiverse/slock #14968). Shown only
     * inside the contextual [uploadContext] line when > 0.
     */
    val agentUploadQuotaBlocks7d: Int? = null,
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
    historyContext: RaftUpgradeHistoryContext? = null,
    knownAgentHistoryBlocks7d: Int? = null,
    seatRequestPurpose: SeatRequestPurpose? = null,
    uploadContext: RaftUpgradeUploadContext? = null,
    knownAgentUploadQuotaBlocks7d: Int? = null,
    fallbackUploadFiles: List<RaftUpgradeUploadFile> = emptyList(),
    knownBillingSource: String? = null,
) {
    val bridge = Utils.bridgeModule(pagerId)
    val sharedStores = RaftSharedStoreRegistry.get(bridge)
    val webBaseUrl = standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)
    val authStore = sharedStores.authStore(storeScope)
    val authDataSource = sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope)
    val pager = this
    val pairedContext = externalContextForGate(gate, externalContext)
    val pairedHistoryContext = historyContextForGate(gate, historyContext)
    val pairedUploadContext = uploadContextForGate(gate, uploadContext)
    val sanitizedFallbackUploadFiles =
        if (gate == RaftUpgradeGate.UploadMonthlyQuota) {
            fallbackUploadFiles.filter { it.name.isNotBlank() && it.sizeBytes >= 0L }
        } else {
            emptyList()
        }
    val uploadQuotaIsAppSumo = (pairedUploadContext as? RaftUpgradeUploadContext.MonthlyQuota)?.isAppSumo == true
    val knownAppSumoSource = knownBillingSource.equals("appsumo", ignoreCase = true)
    raftUpgradeScope.launch {
        val session = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
        val requestedServerId = targetServerId?.trim()?.takeIf { it.isNotEmpty() }
        val server =
            if (requestedServerId != null) {
                session?.servers?.firstOrNull { it.id == requestedServerId }?.toServerSummary() ?: return@launch
            } else {
                session?.currentServerSummary()
            }
        val canReadBillingSummary = server?.role == "owner" || server?.role == "admin"
        var isAppSumoUpload =
            gate == RaftUpgradeGate.UploadMonthlyQuota && (knownAppSumoSource || uploadQuotaIsAppSumo)
        if (
            gate == RaftUpgradeGate.UploadMonthlyQuota &&
                !isAppSumoUpload &&
                !isFreePlan(server?.plan) &&
                canReadBillingSummary &&
                server != null &&
                server.id.value.isNotBlank()
        ) {
            val summary =
                loadServerBillingSubscriptionSummary(
                    networkClient = sharedStores.networkClient(storeScope),
                    serverId = server.id.value,
                )
            if (
                summary != null &&
                    (summary.source.equals("appsumo", ignoreCase = true) ||
                        summary.appsumo != null ||
                        summary.fileUploadQuota?.source.equals("appsumo", ignoreCase = true))
            ) {
                isAppSumoUpload = true
            }
        }
        val outcome = raftUpgradeOutcome(gate, server?.plan, isAppSumoUpload = isAppSumoUpload)
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
        // Non-owner on a Pro server hitting a seat limit while `pro_seat_add` is enabled:
        // open the native Ask-an-owner seat request dialog (Android/Desktop only, never iOS 3.1.1).
        val proSeatAddEnabled =
            server != null && sharedStores.serverFeatureFlags.isProSeatAddEnabled(server.id)
        if (
            raftShouldOpenNativeAskSeatsDialog(
                mode = mode,
                isOwner = server?.role == "owner",
                proSeatAddEnabled = proSeatAddEnabled,
                offersUpgrade = settingsBillingOffersUpgrade(),
                purpose = seatRequestPurpose,
            ) &&
                server != null &&
                seatRequestPurpose != null
        ) {
            val storedServer = session?.servers?.firstOrNull { it.id == server.id.value }
            pager.openRaftAskSeatsDialog(
                storeScope = storeScope,
                serverId = server.id.value,
                serverName = server.name,
                purpose = seatRequestPurpose,
                creatorUserId = storedServer?.ownerId,
                rosterHidden = server.role == "member" && server.hideHumansFromMembers,
                requesterRole = server.role,
            )
            return@launch
        }
        val resolvedSlug =
            server?.slug?.takeIf { it.isNotBlank() }
                ?: if (requestedServerId == null) standaloneSettingsServerSlug(session) else null
        val isUpgradeOrSeats = mode != RaftUpgradeMode.LimitNotice && mode != RaftUpgradeMode.AppSumoUploadLimit
        val initialPricing = if (isUpgradeOrSeats && knownBillingPresent) billingPricing(knownPricing) else null
        val initialAgentHistoryBlocks =
            if (isUpgradeOrSeats && pairedHistoryContext != null) {
                knownAgentHistoryBlocks7d?.takeIf { it >= 0 }
            } else {
                null
            }
        val hasUploadQuotaContext = pairedUploadContext != null || sanitizedFallbackUploadFiles.isNotEmpty()
        val initialAgentUploadQuotaBlocks =
            if (isUpgradeOrSeats && gate == RaftUpgradeGate.UploadMonthlyQuota && hasUploadQuotaContext) {
                knownAgentUploadQuotaBlocks7d?.takeIf { it >= 0 }
            } else {
                null
            }
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
                historyContext = pairedHistoryContext,
                agentHistoryBlocks7d = initialAgentHistoryBlocks,
                uploadContext = if (mode == RaftUpgradeMode.AppSumoUploadLimit) null else pairedUploadContext,
                agentUploadQuotaBlocks7d = initialAgentUploadQuotaBlocks,
            )
        RaftUpgradeDialogState.refresher = { current -> raftReloadServerPlan(authDataSource, current.serverId) }
        RaftUpgradeDialogState.request = request
        val shouldLoadPricing = isUpgradeOrSeats && !knownBillingPresent && request.serverId.isNotBlank()
        val shouldLoadHistoryBlocks =
            isUpgradeOrSeats &&
                gate == RaftUpgradeGate.History30d &&
                pairedHistoryContext != null &&
                knownAgentHistoryBlocks7d == null &&
                canReadBillingSummary &&
                request.serverId.isNotBlank()
        val shouldLoadUploadQuotaBlocks =
            isUpgradeOrSeats &&
                gate == RaftUpgradeGate.UploadMonthlyQuota &&
                hasUploadQuotaContext &&
                knownAgentUploadQuotaBlocks7d == null &&
                canReadBillingSummary &&
                request.serverId.isNotBlank()
        val networkClient =
            if (shouldLoadPricing || shouldLoadHistoryBlocks || shouldLoadUploadQuotaBlocks) {
                sharedStores.networkClient(storeScope)
            } else {
                null
            }
        // #16367: ask GET /api/billing/pricing (readable by any member) when billing is not already known.
        // Never in LimitNotice (iOS view-only App Store guideline 3.1.1) and never quote a guessed price.
        if (shouldLoadPricing && networkClient != null) {
            launch {
                val resolved =
                    loadServerProPricing(
                        networkClient = networkClient,
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
        // #14512: read `agentHistoryBlocks7d` from GET /api/billing/subscription (owner/admin only)
        // when opening a contextual 30-day history upgrade dialog.
        if (shouldLoadHistoryBlocks && networkClient != null) {
            launch {
                val resolved =
                    loadServerAgentHistoryBlocks7d(
                        networkClient = networkClient,
                        serverId = request.serverId,
                    )
                if (resolved != null) {
                    val current = RaftUpgradeDialogState.request
                    if (current != null &&
                        current.pagerId == request.pagerId &&
                        current.serverId == request.serverId &&
                        current.gate == request.gate &&
                        current.mode == request.mode &&
                        current.historyContext == request.historyContext
                    ) {
                        RaftUpgradeDialogState.request = current.copy(agentHistoryBlocks7d = resolved)
                    }
                }
            }
        }
        // #14968 / #17191: read `agentUploadQuotaBlocks7d` (and fallback `fileUploadQuota` if the
        // 403 lacked a quota payload) from GET /api/billing/subscription (owner/admin only). If
        // billing reveals an AppSumo license, switch to the AppSumo storage limit dialog instead.
        if (shouldLoadUploadQuotaBlocks && networkClient != null) {
            launch {
                val summary =
                    loadServerBillingSubscriptionSummary(
                        networkClient = networkClient,
                        serverId = request.serverId,
                    )
                if (summary != null) {
                    val current = RaftUpgradeDialogState.request
                    if (current != null &&
                        current.pagerId == request.pagerId &&
                        current.serverId == request.serverId &&
                        current.gate == request.gate &&
                        current.mode == request.mode &&
                        current.uploadContext == request.uploadContext
                    ) {
                        val summaryIsAppSumo =
                            summary.source.equals("appsumo", ignoreCase = true) ||
                                summary.appsumo != null ||
                                summary.fileUploadQuota?.source.equals("appsumo", ignoreCase = true)
                        if (summaryIsAppSumo) {
                            RaftUpgradeDialogState.request =
                                current.copy(
                                    mode = RaftUpgradeMode.AppSumoUploadLimit,
                                    uploadContext = null,
                                    agentUploadQuotaBlocks7d = null,
                                    pricing = null,
                                )
                            return@launch
                        }
                        val hydratedContext =
                            current.uploadContext
                                ?: sanitizedFallbackUploadFiles
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { files -> summary.fileUploadQuota?.toRaftUpgradeMonthlyUploadContext(files) }
                        val resolvedBlocks = summary.agentUploadQuotaBlocks7d
                        if (hydratedContext != current.uploadContext || resolvedBlocks != null) {
                            RaftUpgradeDialogState.request =
                                current.copy(
                                    uploadContext = hydratedContext,
                                    agentUploadQuotaBlocks7d = resolvedBlocks ?: current.agentUploadQuotaBlocks7d,
                                )
                        }
                    }
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
        RaftUpgradeMode.AddSeats, RaftUpgradeMode.LimitNotice, RaftUpgradeMode.AppSumoUploadLimit -> false
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
        onManageAppSumoPlan = {
            bridge.log("upgrade_dialog_appsumo_manage_plan serverId=${request.serverId}")
            RaftUpgradeDialogState.close()
            pager.openSettingsBillingPage(request.storeScope)
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
 * Tail of the contextual 30-day history hint (web `upgradeDialog.history.hint*` `{agentTries}`,
 * botiverse/slock #14512): ends the sentence with a period when `agentTries <= 0` (including
 * Japanese/Korean verb conjugation) or appends how often agents hit the window in the past 7 days.
 */
@Composable
internal fun raftHistoryAgentHitsTail(agentTries: Int): String =
    when {
        agentTries <= 0 -> stringResource(Res.string.upgrade_dialog_history_agent_hits_none)
        agentTries == 1 -> stringResource(Res.string.upgrade_dialog_history_agent_hits_one, agentTries)
        else -> stringResource(Res.string.upgrade_dialog_history_agent_hits_other, agentTries)
    }

/**
 * Formats the send date of a hidden target message (web `hiddenMessageDateFormat`): month and day
 * in the current year, with the year when from another year, via [RaftTimeContext].
 */
@Composable
internal fun raftFormatHiddenHistoryTargetDate(
    sentAtIso: String,
    timeContext: RaftTimeContext? = null,
): String {
    val context = currentRaftEffectiveDateTimeContext(timeContext)
    val epochMillis = parseRaftTimestampEpochMillisOrNull(sentAtIso, context.localOffsetMinutes) ?: return sentAtIso
    val targetDate = civilDateFromEpochDay(context.localEpochDay(epochMillis))
    val todayDay = context.resolvedTodayEpochDay()
    val sameYear = todayDay != null && targetDate.year == civilDateFromEpochDay(todayDay).year
    return if (sameYear) {
        stringResource(Res.string.settings_compact_month_day, raftLocalizedShortMonth(targetDate.month), targetDate.day)
    } else {
        sentAtIso.membersLocalizedCreatedDateOrNull(context) ?: sentAtIso
    }
}

/**
 * Web `historyLimitHint` (`upgradeGateCopy.ts`, botiverse/slock #14512): contextual line under the
 * upgrade dialog title when opened from a conversation's history paywall, including how often
 * this server's agents ran into the 30-day window in the past 7 days when [agentTries] > 0.
 */
@Composable
internal fun raftHistoryLimitHint(
    context: RaftUpgradeHistoryContext,
    agentTries: Int = 0,
    timeContext: RaftTimeContext? = null,
): String {
    val days = SettingsBillingFreeHistoryDays
    val tail = raftHistoryAgentHitsTail(agentTries)
    return when (context) {
        is RaftUpgradeHistoryContext.Channel -> {
            val formattedCount = threadHistoryPaywallCount(context.count)
            val res =
                when {
                    context.capped -> Res.string.upgrade_dialog_history_hint_channel_capped
                    context.count == 1 -> Res.string.upgrade_dialog_history_hint_channel_one
                    else -> Res.string.upgrade_dialog_history_hint_channel_other
                }
            stringResource(res, context.channel, formattedCount, days, tail)
        }
        is RaftUpgradeHistoryContext.Thread -> {
            val formattedCount = threadHistoryPaywallCount(context.count)
            val res =
                when {
                    context.capped -> Res.string.upgrade_dialog_history_hint_thread_capped
                    context.count == 1 -> Res.string.upgrade_dialog_history_hint_thread_one
                    else -> Res.string.upgrade_dialog_history_hint_thread_other
                }
            stringResource(res, formattedCount, days, tail)
        }
        is RaftUpgradeHistoryContext.Conversation -> {
            val formattedCount = threadHistoryPaywallCount(context.count)
            val res =
                when {
                    context.capped -> Res.string.upgrade_dialog_history_hint_conversation_capped
                    context.count == 1 -> Res.string.upgrade_dialog_history_hint_conversation_one
                    else -> Res.string.upgrade_dialog_history_hint_conversation_other
                }
            stringResource(res, formattedCount, days, tail)
        }
        is RaftUpgradeHistoryContext.Target -> {
            val formattedDate = raftFormatHiddenHistoryTargetDate(context.sentAtIso, timeContext)
            stringResource(Res.string.upgrade_dialog_history_hint_target, formattedDate, days, tail)
        }
    }
}

/** When a `YYYY-MM` UTC upload quota month resets: the 1st of the following month ("Dec 1", "12月1日"). */
@Composable
internal fun raftMonthlyUploadResetDate(month: String): String {
    val (_, monthNumber) = settingsBillingYearMonth(month) ?: return month
    val nextMonth = if (monthNumber == 12) 1 else monthNumber + 1
    return stringResource(Res.string.settings_compact_month_day, raftLocalizedShortMonth(nextMonth), 1)
}

/** Matches web `formatUploadSize`: bytes/KB below 1 MiB, MB (up to 1 decimal, no trailing .0) from 1 MiB. */
@Composable
internal fun raftFormatUploadQuotaSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    if (safeBytes < RaftMonthlyUploadUsedUpBelowBytes) return RaftFormat.bytes(safeBytes)
    val mb = safeBytes / RaftMonthlyUploadUsedUpBelowBytes.toDouble()
    return stringResource(Res.string.settings_billing_vo_size_mb, settingsBillingFormatSeats(mb))
}

@Composable
internal fun raftUploadQuotaAgentHitsClause(usedUp: Boolean, agentTries: Int): String? {
    if (agentTries <= 0) return null
    val res =
        if (usedUp) {
            if (agentTries == 1) {
                Res.string.upgrade_dialog_upload_quota_agent_hits_used_up_one
            } else {
                Res.string.upgrade_dialog_upload_quota_agent_hits_used_up_other
            }
        } else {
            if (agentTries == 1) {
                Res.string.upgrade_dialog_upload_quota_agent_hits_limit_one
            } else {
                Res.string.upgrade_dialog_upload_quota_agent_hits_limit_other
            }
        }
    return stringResource(res, agentTries)
}

/**
 * Web `uploadLimitHint` (`UpgradeDialog.tsx`, botiverse/slock #14968): contextual line under the
 * monthly upload quota upgrade dialog title, including how often this server's agents were refused
 * by the monthly limit in the past 7 days when [agentTries] > 0.
 */
@Composable
internal fun raftUploadQuotaLimitHint(
    serverName: String,
    context: RaftUpgradeUploadContext.MonthlyQuota,
    agentTries: Int = 0,
): String {
    val monthly = raftFormatUploadQuotaSize(context.limitBytes)
    val remaining = raftFormatUploadQuotaSize(context.remainingBytes)
    val resetDate = raftMonthlyUploadResetDate(context.month)
    val files = context.files
    return when {
        files.isEmpty() -> {
            val agentClause = raftUploadQuotaAgentHitsClause(usedUp = true, agentTries = agentTries)
            if (agentClause == null) {
                stringResource(Res.string.upgrade_dialog_upload_quota_hint_used_up, serverName, monthly, resetDate)
            } else {
                stringResource(
                    Res.string.upgrade_dialog_upload_quota_hint_used_up_with_agent_hits,
                    serverName,
                    monthly,
                    resetDate,
                    agentClause,
                )
            }
        }
        files.size == 1 -> {
            val only = files.single()
            val fileSize = raftFormatUploadQuotaSize(only.sizeBytes)
            val agentClause = raftUploadQuotaAgentHitsClause(usedUp = false, agentTries = agentTries)
            if (agentClause == null) {
                stringResource(
                    Res.string.upgrade_dialog_upload_quota_hint_file,
                    only.name,
                    fileSize,
                    serverName,
                    remaining,
                    monthly,
                    resetDate,
                )
            } else {
                stringResource(
                    Res.string.upgrade_dialog_upload_quota_hint_file_with_agent_hits,
                    only.name,
                    fileSize,
                    serverName,
                    remaining,
                    monthly,
                    resetDate,
                    agentClause,
                )
            }
        }
        else -> {
            val agentClause = raftUploadQuotaAgentHitsClause(usedUp = false, agentTries = agentTries)
            if (agentClause == null) {
                stringResource(
                    Res.string.upgrade_dialog_upload_quota_hint_files,
                    serverName,
                    remaining,
                    monthly,
                    files.size,
                    resetDate,
                )
            } else {
                stringResource(
                    Res.string.upgrade_dialog_upload_quota_hint_files_with_agent_hits,
                    serverName,
                    remaining,
                    monthly,
                    files.size,
                    resetDate,
                    agentClause,
                )
            }
        }
    }
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
    onManageAppSumoPlan: () -> Unit = onOpenWeb,
) {
    val server = request.serverName
    val copy = raftUpgradeGateCopy(request.gate)
    val pairedContext = externalContextForGate(request.gate, request.externalContext)
    val pairedHistoryContext = historyContextForGate(request.gate, request.historyContext)
    val pairedUploadContext = uploadContextForGate(request.gate, request.uploadContext)
    val agentHistoryTries = (request.agentHistoryBlocks7d ?: 0).coerceAtLeast(0)
    val agentUploadTries = (request.agentUploadQuotaBlocks7d ?: 0).coerceAtLeast(0)
    val freezeUpcoming = pairedContext as? RaftUpgradeExternalContext.FreezeUpcoming
    val freezeDeadline = freezeUpcoming?.let { raftFormatFreezeDeadline(it.deadlineIso) }
    val slotChannel = (pairedContext as? RaftUpgradeExternalContext.Slot)?.channelName
    // Web `AppSumoUploadLimit` (botiverse/slock #17191): a finite lifetime storage allowance is
    // managed through AppSumo without Pro pricing, Stripe checkout, or monthly reset copy.
    if (request.mode == RaftUpgradeMode.AppSumoUploadLimit) {
        RaftUnifiedDialog(
            title = stringResource(Res.string.upgrade_dialog_appsumo_upload_limit_title),
            onDismiss = onDismiss,
            maxWidth = 448.dp,
            titleMaxLines = 3,
        ) {
            RaftText(
                text =
                    stringResource(
                        if (request.owner) {
                            Res.string.upgrade_dialog_appsumo_upload_manage_help
                        } else {
                            Res.string.upgrade_dialog_appsumo_upload_ask_owner
                        }
                    ),
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foregroundMuted,
            )
            RaftDialogFooter {
                if (request.owner) {
                    RaftButton(
                        text = stringResource(Res.string.upgrade_dialog_appsumo_manage_plan),
                        onClick = onManageAppSumoPlan,
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Lg,
                    )
                } else {
                    RaftButton(
                        text = stringResource(Res.string.common_close),
                        onClick = onDismiss,
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Lg,
                    )
                }
            }
        }
        return
    }
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
    val monthlyUploadContext = pairedUploadContext as? RaftUpgradeUploadContext.MonthlyQuota
    val title =
        when {
            seats -> stringResource(Res.string.upgrade_dialog_seats_title, server)
            freezeDeadline != null -> stringResource(Res.string.upgrade_dialog_external_freeze_upcoming_title)
            monthlyUploadContext != null && monthlyUploadContext.remainingBytes >= RaftMonthlyUploadUsedUpBelowBytes ->
                stringResource(Res.string.upgrade_dialog_upload_quota_title_short)
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
            monthlyUploadContext != null ->
                raftUploadQuotaLimitHint(server, monthlyUploadContext, agentUploadTries)
            pairedHistoryContext != null ->
                raftHistoryLimitHint(pairedHistoryContext, agentHistoryTries)
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
