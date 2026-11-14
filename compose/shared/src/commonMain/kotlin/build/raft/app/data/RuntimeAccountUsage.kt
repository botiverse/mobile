package build.raft.app.data

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
internal enum class RuntimeAccountUsageProvider(val wire: String) {
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

internal enum class RuntimeAccountUsageHealth {
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

internal enum class RuntimeAccountUsageWindowStatus {
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
internal sealed interface RuntimeAccountUsageWindowScope {
    data object Account : RuntimeAccountUsageWindowScope

    data class Model(val name: String) : RuntimeAccountUsageWindowScope

    data class Models(val name: String) : RuntimeAccountUsageWindowScope
}

internal data class RuntimeAccountUsageWindow(
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
internal val RuntimeAccountUsageWindow.modelScoped: Boolean
    get() = scope is RuntimeAccountUsageWindowScope.Model || scope is RuntimeAccountUsageWindowScope.Models

/** slock `CLAUDE_OAUTH_APPS_WINDOW_LABEL`: Claude's third-party OAuth apps' weekly window. */
internal const val CLAUDE_OAUTH_APPS_WINDOW_LABEL: String = "Current week (OAuth apps)"

/** slock `isClaudeOAuthAppsWindow`: left out wherever usage windows are listed or summed. */
internal val RuntimeAccountUsageWindow.claudeOAuthApps: Boolean
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
internal fun RuntimeAccountUsageSnapshot.withInferredClaudeScopes(): RuntimeAccountUsageSnapshot {
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
internal data class RuntimeAccountUsageAllModels(val account: RuntimeAccountUsageAccount, val modelLine: Boolean)

/**
 * slock `allModelsUsageAccount` (#14605, #14667, #14717): the account as every usage summary
 * counts it (the rack cell and its first line, the rack's sort, the usage card's badge): never
 * Claude's OAuth apps' window, and a model's or a group of models' own window only when the
 * account reports nothing else ([RuntimeAccountUsageAllModels.modelLine] is then false, so the
 * same model is never shown twice). An account of model groups only counts as its group with the
 * most left. A limit reached only on a window left out leaves the account `ok`. Display only.
 */
internal fun RuntimeAccountUsageAccount.allModels(): RuntimeAccountUsageAllModels {
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

internal data class RuntimeAccountUsageAccount(
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
    val limitResetsAvailable: Int? = null
)

/** slock `RUNTIME_LIMIT_RESETS_AVAILABLE_MAX`: a larger count is not a reading Raft trusts. */
internal const val RUNTIME_LIMIT_RESETS_AVAILABLE_MAX: Int = 999

internal data class RuntimeAccountUsageSnapshot(
    val provider: String,
    val collectedAt: String?,
    val accounts: List<RuntimeAccountUsageAccount>
)

internal sealed interface RuntimeAccountUsageRead {
    data object Missing : RuntimeAccountUsageRead

    /** slock #14667: this Computer's daemon does not read the provider's usage; shown as a runtime with none. */
    data object Unsupported : RuntimeAccountUsageRead
    data class Available(
        val stale: Boolean,
        val snapshot: RuntimeAccountUsageSnapshot
    ) : RuntimeAccountUsageRead
}

/** Returns null when the envelope itself is unusable (contract mismatch). */
internal fun JsonElement.toRuntimeAccountUsageRead(): RuntimeAccountUsageRead? {
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
internal enum class RuntimeAccountUsageRefreshReason(val wire: String) {
    Manual("manual"),
    StaleOrMissing("stale_or_missing")
}

/** `state` of `POST …/runtime-account-usage/:provider/refresh`. */
internal enum class RuntimeAccountUsageRefreshState(val wire: String) {
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
internal data class RuntimeAccountUsageRefresh(
    val accepted: Boolean,
    val state: RuntimeAccountUsageRefreshState,
    /** Only for [RuntimeAccountUsageRefreshState.Fresh]; null when it carried none we can use. */
    val snapshot: RuntimeAccountUsageSnapshot? = null
)

/** Returns null when the envelope is unusable (no boolean `accepted` or an unknown `state`). */
internal fun JsonElement.toRuntimeAccountUsageRefresh(): RuntimeAccountUsageRefresh? {
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
internal fun JsonObject.toRuntimeAccountUsageSnapshotOrNull(): RuntimeAccountUsageSnapshot? = toRuntimeAccountUsageSnapshot()

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
        limitResetsAvailable = limitResetsAvailableOrNull()
    )
}

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
