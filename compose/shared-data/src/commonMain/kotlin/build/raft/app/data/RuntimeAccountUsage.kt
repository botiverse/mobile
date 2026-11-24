package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.reportUnknownTypeDropped
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Runtime account usage (task #137, Web `runtime_account_usage_v0`; refresh: artin task #18).
 *
 * Wire contract: slock `packages/shared/src/runtimeAccountUsage.ts` (protocol 2), served by
 * `GET /servers/:id/machines/:machineId/runtime-account-usage/:provider`. The server answers
 * `{state: "missing", snapshot: null}` or `{state: "fresh" | "stale", snapshot}`; access is
 * decided server-side (attaching human or `editMachines`, otherwise 403). A refresh is asked for
 * with `POST …/runtime-account-usage/:provider/refresh` ([RuntimeAccountUsageRefresh]).
 *
 * Parsing is deliberately per-account and per-window: an unknown health/status value or a
 * malformed window degrades that item to "unavailable" instead of failing the whole read,
 * and nothing unknown is ever presented as healthy or as zero usage.
 */
@RaftCoreInternalApi enum class RuntimeAccountUsageProvider(val wire: String) {
    Claude("claude"),
    Codex("codex"),
    Kimi("kimi"),
    Grok("grok"),

    /** slock #14667 (OAR 0.103): read only on a Computer whose daemon reads it; others answer `unsupported`. */
    Antigravity("antigravity");

    companion object {
        /** Mirrors Web `usageProviderForRuntime`: only these runtimes report account usage. */
        fun forRuntime(runtimeId: String?): RuntimeAccountUsageProvider? =
            when (runtimeId?.trim()) {
                "claude" -> Claude
                "codex" -> Codex
                "grok" -> Grok
                "antigravity" -> Antigravity
                "kimi", "kimi-sdk" -> Kimi
                else -> null
            }
    }
}

@RaftCoreInternalApi enum class RuntimeAccountUsageHealth {
    Ok,
    RateLimited,
    ReauthRequired,
    Unsupported,
    Error,
    Unknown;

    companion object {
        fun fromWire(value: String?): RuntimeAccountUsageHealth =
            when (value) {
                "ok" -> Ok
                "rate_limited" -> RateLimited
                "reauth_required" -> ReauthRequired
                "unsupported" -> Unsupported
                "error" -> Error
                else -> Unknown
            }
    }
}

@RaftCoreInternalApi enum class RuntimeAccountUsageWindowStatus {
    Ok,
    LimitReached,

    /** The provider window could not be parsed, or the value we received is unusable. */
    Unavailable;

    companion object {
        fun fromWire(value: String?): RuntimeAccountUsageWindowStatus? =
            when (value) {
                "ok" -> Ok
                "limit_reached" -> LimitReached
                "parse_unavailable" -> Unavailable
                else -> null
            }
    }
}

/**
 * What the runtime ties a usage window to (slock `runtimeAccountUsageWindowScopeSchema`, OAR's
 * `AccountUsageWindow.scope`): the whole account's, one model's ("Opus", "Fable"), or a group of
 * models sharing one quota (Antigravity's "Gemini Models"). Display only: whether an agent is
 * blocked is the account's health, never a window's scope.
 */
@RaftCoreInternalApi sealed interface RuntimeAccountUsageWindowScope {
    data object Account : RuntimeAccountUsageWindowScope

    data class Model(val name: String) : RuntimeAccountUsageWindowScope

    data class Models(val name: String) : RuntimeAccountUsageWindowScope
}

@RaftCoreInternalApi data class RuntimeAccountUsageWindow(
    val id: String,
    val label: String,
    val status: RuntimeAccountUsageWindowStatus,
    /** 0..1; always null when [status] is [RuntimeAccountUsageWindowStatus.Unavailable]. */
    val usedRatio: Double?,
    val resetsAt: String?,
    /** Null: the runtime says neither, or the Computer predates the field. */
    val scope: RuntimeAccountUsageWindowScope? = null,
)

/** A window tied to one model or a group of models (slock `isModelScopedUsageWindow`), not the whole account. */
@RaftCoreInternalApi val RuntimeAccountUsageWindow.modelScoped: Boolean
    get() = scope is RuntimeAccountUsageWindowScope.Model || scope is RuntimeAccountUsageWindowScope.Models

/** slock `CLAUDE_OAUTH_APPS_WINDOW_LABEL`: Claude's third-party OAuth apps' weekly window. */
@RaftCoreInternalApi const val CLAUDE_OAUTH_APPS_WINDOW_LABEL: String = "Current week (OAuth apps)"

/** slock `isClaudeOAuthAppsWindow`: left out wherever usage windows are listed or summed. */
@RaftCoreInternalApi val RuntimeAccountUsageWindow.claudeOAuthApps: Boolean
    get() = label == CLAUDE_OAUTH_APPS_WINDOW_LABEL

/** Claude's label for one model's window: "Current week (Fable)", "Current session (Opus)". */
private val ClaudeModelWindowLabel = Regex("^Current (?:week|session) \\((.+)\\)$")

private fun RuntimeAccountUsageWindow.claudeModelOfLabel(): String? {
    if (claudeOAuthApps) return null
    val name = ClaudeModelWindowLabel.matchEntire(label)?.groupValues?.get(1)?.trim()
    return name?.takeIf { it.isNotEmpty() && it.lowercase() != "all models" }
}

/**
 * slock `inferClaudeUsageWindowScopes` (#14717): a Computer before window scope (1.22.0 and
 * earlier) sends Claude's "Current week (Fable)" without a scope; it is read as that model's, so
 * an account with only Fable used up is not read as used up. Other providers stay as given.
 */
@RaftCoreInternalApi fun RuntimeAccountUsageSnapshot.withInferredClaudeScopes(): RuntimeAccountUsageSnapshot {
    if (provider != RuntimeAccountUsageProvider.Claude.wire) return this
    if (accounts.none { account -> account.windows.any { it.scope == null && it.claudeModelOfLabel() != null } }) return this
    return copy(
        accounts =
            accounts.map { account ->
                account.copy(
                    windows =
                        account.windows.map { window ->
                            val name = if (window.scope == null) window.claudeModelOfLabel() else null
                            if (name != null) window.copy(scope = RuntimeAccountUsageWindowScope.Model(name)) else window
                        }
                )
            }
    )
}

/** slock `windowTightness`: a reached limit above any share; null when unreadable. */
private fun RuntimeAccountUsageWindow.tightness(): Double? =
    when (status) {
        RuntimeAccountUsageWindowStatus.LimitReached -> 2.0
        RuntimeAccountUsageWindowStatus.Ok -> usedRatio
        RuntimeAccountUsageWindowStatus.Unavailable -> null
    }

/**
 * slock `roomiestModelGroup`: an account whose every window is a group of models' (Antigravity),
 * with two groups or more, runs while any group has quota left, so it counts as its group with
 * the most left. Null for any other account.
 */
private fun roomiestModelGroup(windows: List<RuntimeAccountUsageWindow>): List<RuntimeAccountUsageWindow>? {
    val groups = LinkedHashMap<String, MutableList<RuntimeAccountUsageWindow>>()
    windows.forEach { window ->
        val scope = window.scope as? RuntimeAccountUsageWindowScope.Models ?: return null
        groups.getOrPut(scope.name.lowercase()) { mutableListOf() } += window
    }
    if (groups.size < 2) return null
    var roomiest: List<RuntimeAccountUsageWindow>? = null
    var roomiestTightness = Double.MAX_VALUE
    groups.values.forEach { group ->
        val tightness = group.mapNotNull { it.tightness() }.maxOrNull() ?: return@forEach
        if (roomiest == null || tightness < roomiestTightness) {
            roomiest = group
            roomiestTightness = tightness
        }
    }
    return roomiest
}

/** slock `allModelsUsageAccount`'s answer: the account as summaries count it, and whether it has a model line. */
@RaftCoreInternalApi data class RuntimeAccountUsageAllModels(val account: RuntimeAccountUsageAccount, val modelLine: Boolean)

/**
 * slock `allModelsUsageAccount` (#14605, #14667, #14717): the account as every usage summary
 * counts it (the rack cell and its first line, the rack's sort, the usage card's badge): never
 * Claude's OAuth apps' window, and a model's or a group of models' own window only when the
 * account reports nothing else ([RuntimeAccountUsageAllModels.modelLine] is then false, so the
 * same model is never shown twice). An account of model groups only counts as its group with the
 * most left. A limit reached only on a window left out leaves the account `ok`. Display only.
 */
@RaftCoreInternalApi fun RuntimeAccountUsageAccount.allModels(): RuntimeAccountUsageAllModels {
    val counted = windows.filter { !it.claudeOAuthApps }
    val allModels = counted.filter { !it.modelScoped }
    val roomiestGroup = if (allModels.isEmpty()) roomiestModelGroup(counted) else null
    val modelLine = roomiestGroup != null || (allModels.isNotEmpty() && counted.any { it.modelScoped })
    val kept = roomiestGroup ?: allModels.ifEmpty { counted }
    if (kept.size == windows.size) return RuntimeAccountUsageAllModels(this, modelLine)
    val limitOnlyLeftOut =
        health == RuntimeAccountUsageHealth.RateLimited &&
            kept.none { it.status == RuntimeAccountUsageWindowStatus.LimitReached } &&
            windows.any { it !in kept && it.status == RuntimeAccountUsageWindowStatus.LimitReached }
    return RuntimeAccountUsageAllModels(
        account = copy(health = if (limitOnlyLeftOut) RuntimeAccountUsageHealth.Ok else health, windows = kept),
        modelLine = modelLine,
    )
}

@RaftCoreInternalApi data class RuntimeAccountUsageAccount(
    val accountKey: String,
    val planLabel: String?,
    /** Masked email, or the provider display name when there is no email. */
    val identityLabel: String?,
    val health: RuntimeAccountUsageHealth,
    val windows: List<RuntimeAccountUsageWindow>,
    /**
     * How many limit resets the account holds (slock #13504, Codex: OAR `limitResets.available`);
     * null when the runtime reports none, the Computer predates it, or the value is not a whole
     * number in 0..[RUNTIME_LIMIT_RESETS_AVAILABLE_MAX] (the server's own bound).
     */
    val limitResetsAvailable: Int? = null,
    /**
     * Extra usage credits (slock #15374, Codex: OAR `credits`), which keep the account running
     * after a window is used up. Null: unknown (the server or the Computer predates them, or the
     * runtime did not say); the windows may then still read full, as before.
     */
    val credits: RuntimeAccountUsageCredits? = null,
    /**
     * The agents whose own account folder (CODEX_HOME / CLAUDE_CONFIG_DIR) this account was read
     * from (slock #15348); null: the Computer's default account. Sent only to a client declaring
     * [RUNTIME_USAGE_ACCOUNT_FOLDERS_CLIENT_CAPABILITY].
     */
    val agentIds: List<String>? = null,
    /**
     * Why an `unsupported` account has no reading (slock #15395, OAR `AccountUsageUnsupportedReason`);
     * only with that health. Null: no reason, a reason this app does not know, or a server that
     * predates it. Served only to a request declaring [RUNTIME_USAGE_UNSUPPORTED_REASON_CLIENT_CAPABILITY].
     */
    val unsupportedReason: RuntimeAccountUsageUnsupportedReason? = null,
)

/** slock `RUNTIME_ACCOUNT_USAGE_UNSUPPORTED_REASONS` (#15395): why an account's usage cannot be read. */
@RaftCoreInternalApi enum class RuntimeAccountUsageUnsupportedReason(val wire: String) {
    VersionUnsupported("version_unsupported"),
    CapabilityUnavailable("capability_unavailable"),
    UnsupportedInstallation("unsupported_installation"),
    UnsupportedAuthMode("unsupported_auth_mode"),
    UnsupportedAuthStorage("unsupported_auth_storage"),
    AuthConfigurationUnavailable("auth_configuration_unavailable"),
    EndpointUnavailable("endpoint_unavailable"),
    QuotaUnavailable("quota_unavailable");

    companion object {
        fun fromWire(value: String?): RuntimeAccountUsageUnsupportedReason? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Web `formatRuntimePlanName` (slock #15557, accountText.ts): a runtime's own plan code in lowercase
 * ("pro", "max", "team", ...) reads as a plan ("Pro", "Team"); any other name ("Ultra", "SuperGrok")
 * as sent. Null when there is no plan.
 */
@RaftCoreInternalApi fun formatRuntimePlanName(planLabel: String?): String? {
    val trimmed = planLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return KnownRuntimePlanNames[trimmed.lowercase()] ?: trimmed
}

private val KnownRuntimePlanNames: Map<String, String> =
    mapOf(
        "free" to "Free",
        "go" to "Go",
        "plus" to "Plus",
        "pro" to "Pro",
        "max" to "Max",
        "team" to "Team",
        "business" to "Business",
        "enterprise" to "Enterprise",
        "edu" to "Edu",
    )

/** How an account's heading on the usage card reads (web `formatRuntimePlanHeading`). */
@RaftCoreInternalApi sealed interface RuntimePlanHeading {
    /** No plan: "Runtime account". */
    data object Fallback : RuntimePlanHeading

    /** The plan's own name already says it is a plan ("Team plan", "Pro 套餐"): as it is. */
    data class Named(val name: String) : RuntimePlanHeading

    /** "{plan} plan" / "{plan} 套餐": "Team plan", never a bare "team" that reads like a workspace. */
    data class Plan(val plan: String) : RuntimePlanHeading
}

private val PlanWord = Regex("\\bplan\\b", RegexOption.IGNORE_CASE)

@RaftCoreInternalApi fun runtimePlanHeading(planLabel: String?): RuntimePlanHeading {
    val plan = formatRuntimePlanName(planLabel) ?: return RuntimePlanHeading.Fallback
    return if (PlanWord.containsMatchIn(plan) || plan.contains("套餐")) RuntimePlanHeading.Named(plan) else RuntimePlanHeading.Plan(plan)
}

/** Web `usageOnCredits`: the account keeps running on credits once a window is used up (`credits.available`). */
@RaftCoreInternalApi val RuntimeAccountUsageAccount.usageOnCredits: Boolean
    get() = credits?.available == true

/**
 * Web `usageModelGroupsAllUsedUp` (slock #15483): an account of model groups only (Antigravity's
 * "Gemini Models") whose every group has a window at its limit.
 */
@RaftCoreInternalApi fun RuntimeAccountUsageAccount.modelGroupsAllUsedUp(): Boolean {
    val groups = LinkedHashMap<String, Boolean>()
    windows.forEach { window ->
        val scope = window.scope as? RuntimeAccountUsageWindowScope.Models ?: return false
        val key = scope.name.lowercase()
        groups[key] = (groups[key] ?: false) || window.status == RuntimeAccountUsageWindowStatus.LimitReached
    }
    return groups.isNotEmpty() && groups.values.all { it }
}

/** The usage card's chip for an account fine on the all-models basis whose model windows say otherwise. */
@RaftCoreInternalApi enum class RuntimeAccountUsageModelLimit {
    /** Every model group used up while the runtime says not rate limited, credits unreported: "may be on credits". */
    MaybeOnCredits,

    /** Every model group used up and credits reported as none: "used up", whatever the runtime's flag says. */
    UsedUp,

    /** A model's or group's own window used up while the account runs others: "some models used up". */
    SomeModelsUsedUp,
}

/**
 * Web `modelLimitChip` (slock #15483, runtimeUsageCredits.ts): every group used up reads "may be on
 * credits" while credits are unreported, nothing while they are available (each row says "using
 * credits"), "used up" when reported as none; one model's or group's window used up while the
 * account runs others reads "some models used up", unless the account is on credits.
 */
@RaftCoreInternalApi fun RuntimeAccountUsageAccount.modelLimitChip(): RuntimeAccountUsageModelLimit? {
    if (modelGroupsAllUsedUp()) {
        val reported = credits ?: return RuntimeAccountUsageModelLimit.MaybeOnCredits
        return if (reported.available) null else RuntimeAccountUsageModelLimit.UsedUp
    }
    if (usageOnCredits) return null
    return if (windows.any { it.status == RuntimeAccountUsageWindowStatus.LimitReached && it.modelScoped }) {
        RuntimeAccountUsageModelLimit.SomeModelsUsedUp
    } else {
        null
    }
}

/** The one word an account's chip on the usage card says (web `runtime-account-health`); none when healthy. */
@RaftCoreInternalApi enum class RuntimeAccountUsageBadge {
    RateLimited,
    ReauthRequired,
    Unsupported,
    Error,
    Unknown,
    SomeModelsUsedUp,
    MaybeOnCredits,

    /** Unsupported, the account reports no usage limits (`quota_unavailable`). */
    NoUsageData,

    /** Unsupported, the CLI is too old to report them (`version_unsupported`). */
    UpdateRequired,
}

/**
 * Web `RuntimeUsageContents`' badge rule (slock #15482, #15483, #15395): no chip for a healthy
 * account (on the all-models basis, with no model limit); otherwise its model limit, an
 * unsupported account's reason, or its health.
 */
@RaftCoreInternalApi fun RuntimeAccountUsageAccount.usageBadge(): RuntimeAccountUsageBadge? {
    val health = allModels().account.health
    val modelLimit = if (health == RuntimeAccountUsageHealth.Ok) modelLimitChip() else null
    return when (modelLimit) {
        RuntimeAccountUsageModelLimit.MaybeOnCredits -> RuntimeAccountUsageBadge.MaybeOnCredits
        RuntimeAccountUsageModelLimit.UsedUp -> RuntimeAccountUsageBadge.RateLimited
        RuntimeAccountUsageModelLimit.SomeModelsUsedUp -> RuntimeAccountUsageBadge.SomeModelsUsedUp
        null ->
            when (health) {
                RuntimeAccountUsageHealth.Ok -> null
                RuntimeAccountUsageHealth.RateLimited -> RuntimeAccountUsageBadge.RateLimited
                RuntimeAccountUsageHealth.ReauthRequired -> RuntimeAccountUsageBadge.ReauthRequired
                RuntimeAccountUsageHealth.Error -> RuntimeAccountUsageBadge.Error
                RuntimeAccountUsageHealth.Unknown -> RuntimeAccountUsageBadge.Unknown
                RuntimeAccountUsageHealth.Unsupported ->
                    when (unsupportedReason) {
                        RuntimeAccountUsageUnsupportedReason.QuotaUnavailable -> RuntimeAccountUsageBadge.NoUsageData
                        RuntimeAccountUsageUnsupportedReason.VersionUnsupported -> RuntimeAccountUsageBadge.UpdateRequired
                        else -> RuntimeAccountUsageBadge.Unsupported
                    }
            }
    }
}

/**
 * Web `unsupportedReasonTextId`: the line under an unsupported account saying why; none where its
 * badge already says it ("no usage data", "update required", or a plain "unsupported").
 */
@RaftCoreInternalApi val RuntimeAccountUsageAccount.unsupportedReasonLine: RuntimeAccountUsageUnsupportedReason?
    get() =
        unsupportedReason?.takeIf {
            health == RuntimeAccountUsageHealth.Unsupported &&
                it != RuntimeAccountUsageUnsupportedReason.QuotaUnavailable &&
                it != RuntimeAccountUsageUnsupportedReason.VersionUnsupported &&
                it != RuntimeAccountUsageUnsupportedReason.CapabilityUnavailable
        }

/**
 * Web `runtimeUsageWindowLabel` (slock #15482): the 5-hour and weekly windows, by the labels the
 * Computer sends for them, read in the reader's language ("5h", "Week"); any other window as named.
 */
@RaftCoreInternalApi fun RuntimeAccountUsageWindow.cardWindowKind(): RuntimeQuotaWindowKind? =
    when (label) {
        in RuntimeUsageCardFiveHourLabels -> RuntimeQuotaWindowKind.FiveHour
        in RuntimeUsageCardWeekLabels -> RuntimeQuotaWindowKind.Week
        else -> null
    }

private val RuntimeUsageCardFiveHourLabels = setOf("Current session", "5 hours", "Codex · 5 hours")
private val RuntimeUsageCardWeekLabels = setOf("Current week (all models)", "1 week", "Codex · 1 week", "Weekly included usage")

/** The Computer's own account, not an agent's own folder's (slock `agentIds === undefined`). */
@RaftCoreInternalApi val RuntimeAccountUsageAccount.isDefaultAccount: Boolean get() = agentIds == null

/**
 * slock `defaultRuntimeUsageAccount`: the Computer's default account in [snapshot] (the first
 * without `agentIds`), else the first account. What every Computer-level single-account reading
 * uses (plan line, limit resets, the 5-hour limit).
 */
@RaftCoreInternalApi fun defaultRuntimeUsageAccount(snapshot: RuntimeAccountUsageSnapshot?): RuntimeAccountUsageAccount? =
    snapshot?.let { s -> s.accounts.firstOrNull { it.isDefaultAccount } ?: s.accounts.firstOrNull() }

/**
 * slock `runtimeUsageAccountsForAgent`: the accounts [agentId]'s usage reads from its Computer's
 * [snapshot]: its own folder's account when it has one, else the default accounts. A snapshot
 * where no account lists agents (an older Computer) reads as before: every account.
 */
@RaftCoreInternalApi fun runtimeUsageAccountsForAgent(snapshot: RuntimeAccountUsageSnapshot?, agentId: String?): List<RuntimeAccountUsageAccount> {
    snapshot ?: return emptyList()
    if (snapshot.accounts.all { it.isDefaultAccount }) return snapshot.accounts
    val own = agentId?.let { id -> snapshot.accounts.firstOrNull { it.agentIds?.contains(id) == true } }
    if (own != null) return listOf(own)
    return snapshot.accounts.filter { it.isDefaultAccount }
}

/**
 * slock `runtimeAccountUsageCreditsSchema` (#15374). [balance] is the runtime's own number string
 * ("30784", "1,234.50"), with no unit; null when not said or not one the wire allows.
 */
@RaftCoreInternalApi data class RuntimeAccountUsageCredits(
    val available: Boolean,
    val unlimited: Boolean? = null,
    val balance: String? = null,
)

/**
 * The account can spend credits now (available, or unlimited): a window at its limit does not
 * stop it (slock #15374: "a full window isn't reached while `credits.available`"). False when
 * credits are unknown, so an older server or Computer reads as before.
 */
@RaftCoreInternalApi val RuntimeAccountUsageAccount.spendsCredits: Boolean
    get() = credits?.let { it.available || it.unlimited == true } == true

/** [window] is full but the account runs on credits past it: said as such, never "used up". */
@RaftCoreInternalApi fun RuntimeAccountUsageAccount.carriedByCredits(window: RuntimeAccountUsageWindow): Boolean =
    spendsCredits &&
        (window.status == RuntimeAccountUsageWindowStatus.LimitReached ||
            (window.status == RuntimeAccountUsageWindowStatus.Ok && (window.usedRatio ?: 0.0) >= 1.0))

/** How the usage card reads a window it can read (`ComputerRuntimeUsageWindowRow`). */
@RaftCoreInternalApi enum class RuntimeAccountUsageWindowFill {
    /** "40% used · resets …". */
    Used,

    /** "Used up · resets …". */
    UsedUp,

    /** Full, but the account runs on its credits: "Limit reached · using credits" (slock #15374). */
    OnCredits,
}

/**
 * Used up by the provider's word or the ratio itself, not by rounding (0.4% left reads "100%
 * used", as the web says); [RuntimeAccountUsageWindowFill.OnCredits] when the account's credits
 * carry it. Null: a window that cannot be read.
 */
@RaftCoreInternalApi fun RuntimeAccountUsageAccount.windowFill(window: RuntimeAccountUsageWindow): RuntimeAccountUsageWindowFill? {
    val ratio = window.usedRatio
    if (window.status == RuntimeAccountUsageWindowStatus.Unavailable || ratio == null) return null
    val usedUp = window.status == RuntimeAccountUsageWindowStatus.LimitReached || ratio >= 1.0
    return when {
        !usedUp -> RuntimeAccountUsageWindowFill.Used
        carriedByCredits(window) -> RuntimeAccountUsageWindowFill.OnCredits
        else -> RuntimeAccountUsageWindowFill.UsedUp
    }
}

/** The account's credits on the usage card's facts line, beside its limit resets; null says nothing. */
@RaftCoreInternalApi sealed interface RuntimeAccountUsageCreditsLine {
    /** "30784 credits": the runtime's own number, as it wrote it. */
    data class Balance(val balance: String) : RuntimeAccountUsageCreditsLine

    data object Unlimited : RuntimeAccountUsageCreditsLine

    /** Credits to spend, how many not said. */
    data object Available : RuntimeAccountUsageCreditsLine

    /** "No credits left", in orange (web `usageCreditsText`, slock #15482). */
    data object None : RuntimeAccountUsageCreditsLine
}

/** Web `usageCreditsText`: null only when credits are unknown. */
@RaftCoreInternalApi val RuntimeAccountUsageAccount.creditsLine: RuntimeAccountUsageCreditsLine?
    get() {
        val credits = credits ?: return null
        return when {
            !credits.available -> RuntimeAccountUsageCreditsLine.None
            credits.unlimited == true -> RuntimeAccountUsageCreditsLine.Unlimited
            credits.balance != null -> RuntimeAccountUsageCreditsLine.Balance(credits.balance)
            else -> RuntimeAccountUsageCreditsLine.Available
        }
    }

/**
 * Whether the account asks for attention on the usage light (web `RuntimeAccountUsageChip`): not
 * ok, or a window not ok. A full window the account's credits carry is no attention.
 */
@RaftCoreInternalApi val RuntimeAccountUsageAccount.needsUsageAttention: Boolean
    get() =
        health != RuntimeAccountUsageHealth.Ok ||
            windows.any { it.status != RuntimeAccountUsageWindowStatus.Ok && !carriedByCredits(it) }

/** slock `RUNTIME_LIMIT_RESETS_AVAILABLE_MAX`: a larger count is not a reading Raft trusts. */
@RaftCoreInternalApi const val RUNTIME_LIMIT_RESETS_AVAILABLE_MAX: Int = 999

@RaftCoreInternalApi data class RuntimeAccountUsageSnapshot(
    val provider: String,
    val collectedAt: String?,
    val accounts: List<RuntimeAccountUsageAccount>
)

@RaftCoreInternalApi sealed interface RuntimeAccountUsageRead {
    data object Missing : RuntimeAccountUsageRead

    /** slock #14667: this Computer's daemon does not read the provider's usage; shown as a runtime with none. */
    data object Unsupported : RuntimeAccountUsageRead
    data class Available(
        val stale: Boolean,
        val snapshot: RuntimeAccountUsageSnapshot
    ) : RuntimeAccountUsageRead
}

/** Returns null when the envelope itself is unusable (contract mismatch). */
@RaftCoreInternalApi fun JsonElement.toRuntimeAccountUsageRead(): RuntimeAccountUsageRead? {
    val envelope = this as? JsonObject ?: return null
    return when (envelope.stringOrNull("state")) {
        "missing" -> RuntimeAccountUsageRead.Missing
        "unsupported" -> RuntimeAccountUsageRead.Unsupported
        "fresh", "stale" -> {
            val snapshot = (envelope["snapshot"] as? JsonObject)?.toRuntimeAccountUsageSnapshot() ?: return null
            RuntimeAccountUsageRead.Available(
                stale = envelope.stringOrNull("state") == "stale",
                snapshot = snapshot
            )
        }
        else -> null
    }
}

/** Why a refresh is asked for: the person tapped Refresh, or the chip found no fresh snapshot. */
@RaftCoreInternalApi enum class RuntimeAccountUsageRefreshReason(val wire: String) {
    Manual("manual"),
    StaleOrMissing("stale_or_missing")
}

/** `state` of `POST …/runtime-account-usage/:provider/refresh`. */
@RaftCoreInternalApi enum class RuntimeAccountUsageRefreshState(val wire: String) {
    /** Sent to the Computer; the snapshot arrives later through the read path (background refresh). */
    Requested("requested"),

    /** A refresh for this Computer and provider is already running or ran in the last window. */
    Cooldown("cooldown"),
    ComputerOffline("computer_offline"),

    /** A manual refresh the Computer answered in time; [RuntimeAccountUsageRefresh.snapshot] carries it. */
    Fresh("fresh"),

    /** A manual refresh the Computer did not answer in time. */
    Timeout("timeout"),

    /** slock #14667: this Computer does not read the provider's usage (`accepted: false`). */
    Unsupported("unsupported");

    companion object {
        fun fromWire(value: String?): RuntimeAccountUsageRefreshState? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * `POST /servers/:id/machines/:machineId/runtime-account-usage/:provider/refresh` (slock
 * `packages/server/src/routes/servers.ts`; Web `RuntimeAccountUsageRefreshResult` in
 * `packages/web/src/utils/runtimeAccountUsageClient.ts`): `{accepted, state, snapshot?}`, 200 or 202.
 */
@RaftCoreInternalApi data class RuntimeAccountUsageRefresh(
    val accepted: Boolean,
    val state: RuntimeAccountUsageRefreshState,
    /** Only for [RuntimeAccountUsageRefreshState.Fresh]; null when it carried none we can use. */
    val snapshot: RuntimeAccountUsageSnapshot? = null
)

/** Returns null when the envelope is unusable (no boolean `accepted` or an unknown `state`). */
@RaftCoreInternalApi fun JsonElement.toRuntimeAccountUsageRefresh(): RuntimeAccountUsageRefresh? {
    val envelope = this as? JsonObject ?: return null
    val accepted = (envelope["accepted"] as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.toBooleanStrictOrNull()
        ?: return null
    val state = RuntimeAccountUsageRefreshState.fromWire(envelope.stringOrNull("state")) ?: return null
    val snapshot =
        if (state == RuntimeAccountUsageRefreshState.Fresh) {
            (envelope["snapshot"] as? JsonObject)?.toRuntimeAccountUsageSnapshot()
        } else {
            null
        }
    return RuntimeAccountUsageRefresh(accepted = accepted, state = state, snapshot = snapshot)
}

/** A snapshot carried by another answer (the limit-reset reply); null when it is not one this app can show. */
@RaftCoreInternalApi fun JsonObject.toRuntimeAccountUsageSnapshotOrNull(): RuntimeAccountUsageSnapshot? = toRuntimeAccountUsageSnapshot()

private fun JsonObject.toRuntimeAccountUsageSnapshot(): RuntimeAccountUsageSnapshot? {
    val provider = stringOrNull("provider") ?: return null
    val accounts = (this["accounts"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.toRuntimeAccountUsageAccount() }
        .orEmpty()
    if (accounts.isEmpty()) return null
    return RuntimeAccountUsageSnapshot(
        provider = provider,
        collectedAt = stringOrNull("collectedAt"),
        accounts = accounts
    ).withInferredClaudeScopes()
}

private fun JsonObject.toRuntimeAccountUsageAccount(): RuntimeAccountUsageAccount? {
    val accountKey = stringOrNull("accountKey") ?: return null
    val health = RuntimeAccountUsageHealth.fromWire(stringOrNull("health"))
    val windows =
        if (health == RuntimeAccountUsageHealth.Unsupported) {
            emptyList()
        } else {
            (this["windows"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.toRuntimeAccountUsageWindow() }
                .orEmpty()
        }
    return RuntimeAccountUsageAccount(
        accountKey = accountKey,
        planLabel = stringOrNull("planLabel"),
        identityLabel = stringOrNull("maskedLabel") ?: stringOrNull("displayName"),
        health = health,
        windows = windows,
        limitResetsAvailable = limitResetsAvailableOrNull(),
        credits = (this["credits"] as? JsonObject)?.toRuntimeAccountUsageCredits(),
        // Present (even if no id is readable): an agent's folder, never the default account.
        agentIds =
            (this["agentIds"] as? JsonArray)?.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            },
        unsupportedReason = if (health == RuntimeAccountUsageHealth.Unsupported) unsupportedReasonOrNull() else null,
    )
}

/** A reason this app does not know reads as a plain `unsupported` (slock: an unknown code is left out). */
private fun JsonObject.unsupportedReasonOrNull(): RuntimeAccountUsageUnsupportedReason? {
    val wire = stringOrNull("unsupportedReason") ?: return null
    return RuntimeAccountUsageUnsupportedReason.fromWire(wire)
        ?: run {
            reportUnknownTypeDropped("runtime_account_usage", "unsupported_reason", wire, fallback = "unsupported")
            null
        }
}

/** slock `RUNTIME_ACCOUNT_USAGE_CREDITS_BALANCE_PATTERN`: digits with `.` or `,`, an optional leading `-`. */
private val CreditsBalancePattern = Regex("^-?[0-9][0-9.,]{0,31}$")

/**
 * slock `runtimeAccountUsageCreditsSchema`: `available` must be a boolean, or the credits are
 * unknown (null); an odd `unlimited` or `balance` is dropped on its own, never the credits.
 */
private fun JsonObject.toRuntimeAccountUsageCredits(): RuntimeAccountUsageCredits? {
    val available = booleanOrNull("available") ?: return null
    return RuntimeAccountUsageCredits(
        available = available,
        unlimited = booleanOrNull("unlimited"),
        balance = stringOrNull("balance")?.takeIf { CreditsBalancePattern.matches(it) },
    )
}

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.limitResetsAvailableOrNull(): Int? {
    val primitive = (this["limitResetsAvailable"] as? JsonPrimitive)?.takeUnless { it.isString } ?: return null
    val value = primitive.doubleOrNull ?: return null
    if (!value.isFinite() || value != kotlin.math.floor(value)) return null
    return value.toInt().takeIf { it in 0..RUNTIME_LIMIT_RESETS_AVAILABLE_MAX }
}

private fun JsonObject.toRuntimeAccountUsageWindow(): RuntimeAccountUsageWindow? {
    val id = stringOrNull("id") ?: return null
    val label = stringOrNull("label") ?: return null
    val ratio = (this["usedRatio"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    val validRatio = ratio?.takeIf { it.isFinite() && it in 0.0..1.0 }
    // A window we cannot trust (unknown status, missing or out-of-range ratio) is shown as
    // unavailable — never as a healthy bar and never as 0% used.
    val status =
        when (val wireStatus = RuntimeAccountUsageWindowStatus.fromWire(stringOrNull("status"))) {
            null -> RuntimeAccountUsageWindowStatus.Unavailable
            RuntimeAccountUsageWindowStatus.Unavailable -> RuntimeAccountUsageWindowStatus.Unavailable
            else -> if (validRatio == null) RuntimeAccountUsageWindowStatus.Unavailable else wireStatus
        }
    return RuntimeAccountUsageWindow(
        id = id,
        label = label,
        status = status,
        usedRatio = if (status == RuntimeAccountUsageWindowStatus.Unavailable) null else validRatio,
        resetsAt = if (status == RuntimeAccountUsageWindowStatus.Unavailable) null else stringOrNull("resetsAt"),
        scope = (this["scope"] as? JsonObject)?.toRuntimeAccountUsageWindowScope(),
    )
}

/** slock `runtimeAccountUsageWindowScopeSchema`; an unknown kind or a missing name is no scope. */
private fun JsonObject.toRuntimeAccountUsageWindowScope(): RuntimeAccountUsageWindowScope? =
    when (stringOrNull("kind")) {
        "account" -> RuntimeAccountUsageWindowScope.Account
        "model" -> stringOrNull("name")?.let(RuntimeAccountUsageWindowScope::Model)
        "models" -> stringOrNull("name")?.let(RuntimeAccountUsageWindowScope::Models)
        else -> null
    }

private fun JsonObject.stringOrNull(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
