package build.raft.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Read-only runtime account usage (task #137, Web `runtime_account_usage_v0`).
 *
 * Wire contract: slock `packages/shared/src/runtimeAccountUsage.ts` (protocol 2), served by
 * `GET /servers/:id/machines/:machineId/runtime-account-usage/:provider`. The server answers
 * `{state: "missing", snapshot: null}` or `{state: "fresh" | "stale", snapshot}`; access is
 * decided server-side (attaching human or `editMachines`, otherwise 403).
 *
 * Parsing is deliberately per-account and per-window: an unknown health/status value or a
 * malformed window degrades that item to "unavailable" instead of failing the whole read,
 * and nothing unknown is ever presented as healthy or as zero usage.
 */
internal enum class RuntimeAccountUsageProvider(val wire: String) {
    Claude("claude"),
    Codex("codex"),
    Kimi("kimi"),
    Grok("grok");

    companion object {
        /** Mirrors Web `usageProviderForRuntime`: only these runtimes report account usage. */
        fun forRuntime(runtimeId: String?): RuntimeAccountUsageProvider? =
            when (runtimeId?.trim()) {
                "claude" -> Claude
                "codex" -> Codex
                "grok" -> Grok
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

internal data class RuntimeAccountUsageWindow(
    val id: String,
    val label: String,
    val status: RuntimeAccountUsageWindowStatus,
    /** 0..1; always null when [status] is [RuntimeAccountUsageWindowStatus.Unavailable]. */
    val usedRatio: Double?,
    val resetsAt: String?
)

internal data class RuntimeAccountUsageAccount(
    val accountKey: String,
    val planLabel: String?,
    /** Masked email, or the provider display name when there is no email. */
    val identityLabel: String?,
    val health: RuntimeAccountUsageHealth,
    val windows: List<RuntimeAccountUsageWindow>
)

internal data class RuntimeAccountUsageSnapshot(
    val provider: String,
    val collectedAt: String?,
    val accounts: List<RuntimeAccountUsageAccount>
)

internal sealed interface RuntimeAccountUsageRead {
    data object Missing : RuntimeAccountUsageRead
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
    )
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
        windows = windows
    )
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
        resetsAt = if (status == RuntimeAccountUsageWindowStatus.Unavailable) null else stringOrNull("resetsAt")
    )
}

private fun JsonObject.stringOrNull(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
