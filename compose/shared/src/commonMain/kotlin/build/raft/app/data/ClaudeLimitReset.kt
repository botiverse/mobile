package build.raft.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * Claude limit resets on the usage card (slock #14336, #14375, #14605, #14757; feature flag
 * `claude_limit_reset`, server-scoped). Wire: packages/shared/src/runtimeAccountUsage.ts
 * (`RUNTIME_LIMIT_RESET_KINDS`, `runtimeLimitResetKindStatusSchema`,
 * `runtimeLimitResetsReadResultSchema`, `claudeFiveHourLimitOf`) and
 * packages/server/src/routes/runtimeAuthRoutes.ts:
 *
 * - `GET …/runtime-auth` carries `claudeLimitResets` (`ok` | `computer_upgrade_required`) only while
 *   the flag is on, for a person who may act, on an online Computer; beside it
 *   `claudeLimitResetAccess {canUse, canConsent}` (only the person who connected the Computer).
 * - `POST …/runtime-account-usage/claude/limit-resets/read` with `{consent?: true}` answers
 *   `{result: RuntimeLimitResetsReadResult}`.
 * - `POST …/runtime-account-usage/claude/limit-reset` with `{idempotencyKey, kind}` answers as the
 *   Codex redeem does ([RuntimeLimitResetReply]).
 */

/** slock `RUNTIME_LIMIT_RESET_KINDS`, in that order. */
internal enum class RuntimeLimitResetKind(val wire: String) {
    FiveHour("five_hour"),
    Weekly("weekly"),
    Fable("fable"),
    Full("full");

    companion object {
        fun fromWire(value: String?): RuntimeLimitResetKind? = entries.firstOrNull { it.wire == value }
    }
}

/** `GET …/runtime-auth` `claudeLimitResets`: what this Computer can do about Claude limit resets. */
internal enum class ClaudeLimitResetsAvailability(val wire: String) {
    Ok("ok"),
    ComputerUpgradeRequired("computer_upgrade_required");

    companion object {
        fun fromWire(value: String?): ClaudeLimitResetsAvailability? = entries.firstOrNull { it.wire == value }
    }
}

/** `GET …/runtime-auth` `claudeLimitResetAccess`: only the person who connected the Computer spends its Claude resets. */
internal data class ClaudeLimitResetAccess(val canUse: Boolean, val canConsent: Boolean)

internal fun JsonElement.toClaudeLimitResetAccess(): ClaudeLimitResetAccess? {
    val obj = this as? JsonObject ?: return null
    val canUse = (obj["canUse"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
    val canConsent = (obj["canConsent"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
    return ClaudeLimitResetAccess(canUse = canUse, canConsent = canConsent)
}

/** slock `RuntimeLimitResetKindStatus`: what Claude Code showed for one kind when asked. */
internal sealed interface RuntimeLimitResetKindStatus {
    val kind: RuntimeLimitResetKind

    data class Available(
        override val kind: RuntimeLimitResetKind,
        val resetsLeft: Int? = null,
        val resetsTotal: Int? = null,
        val expiresAt: String? = null,
        /** Claude Code's own wording ("Nov 19, 4:58pm"). */
        val expiresText: String? = null,
    ) : RuntimeLimitResetKindStatus

    data class None(override val kind: RuntimeLimitResetKind) : RuntimeLimitResetKindStatus

    data class Unknown(override val kind: RuntimeLimitResetKind) : RuntimeLimitResetKindStatus

    data class NotChecked(override val kind: RuntimeLimitResetKind) : RuntimeLimitResetKindStatus

    data class NotOffered(override val kind: RuntimeLimitResetKind) : RuntimeLimitResetKindStatus
}

/** slock `RuntimeLimitResetsReadResult`: how a read of the resets an account holds ended. */
internal sealed interface RuntimeLimitResetsReadResult {
    /** [legacy]: Claude's older once-a-week 5-hour program; [fiveHourSpent]: opening the list spent it. */
    data class Read(
        val legacy: Boolean,
        val fiveHourSpent: Boolean,
        val resets: List<RuntimeLimitResetKindStatus>,
    ) : RuntimeLimitResetsReadResult

    data class ReauthRequired(val reason: String) : RuntimeLimitResetsReadResult

    /** [reason] one of [RUNTIME_LIMIT_RESETS_READ_UNSUPPORTED_REASONS] (`consent_required` among them). */
    data class Unsupported(val reason: String) : RuntimeLimitResetsReadResult

    data object Unknown : RuntimeLimitResetsReadResult
}

/** The strict schema: an unknown kind, state, reason or an extra field is no result (null). */
internal fun JsonElement.toRuntimeLimitResetsReadResult(): RuntimeLimitResetsReadResult? {
    val obj = this as? JsonObject ?: return null
    fun only(vararg keys: String): Boolean = obj.keys.all { it == "kind" || it in keys }
    return when (obj.claudeString("kind")) {
        "read" -> {
            if (!only("program", "fiveHourSpent", "resets")) return null
            val program = obj["program"]
            val legacy =
                when {
                    program == null -> false
                    (program as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == "legacy" -> true
                    else -> return null
                }
            val spent = obj["fiveHourSpent"]
            val fiveHourSpent =
                when {
                    spent == null -> false
                    obj.claudeTrue("fiveHourSpent") -> true
                    else -> return null
                }
            val array = obj["resets"] as? JsonArray ?: return null
            if (array.size > RuntimeLimitResetKind.entries.size) return null
            val resets = array.map { it.toRuntimeLimitResetKindStatus() ?: return null }
            RuntimeLimitResetsReadResult.Read(legacy = legacy, fiveHourSpent = fiveHourSpent, resets = resets)
        }
        "reauth_required" ->
            obj.claudeString("reason")
                ?.takeIf { only("reason") && it in RUNTIME_LIMIT_RESET_REAUTH_REASONS }
                ?.let(RuntimeLimitResetsReadResult::ReauthRequired)
        "unsupported" ->
            obj.claudeString("reason")
                ?.takeIf { only("reason") && it in RUNTIME_LIMIT_RESETS_READ_UNSUPPORTED_REASONS }
                ?.let(RuntimeLimitResetsReadResult::Unsupported)
        "unknown" -> RuntimeLimitResetsReadResult.Unknown.takeIf { obj.keys == setOf("kind") }
        else -> null
    }
}

private fun JsonElement.toRuntimeLimitResetKindStatus(): RuntimeLimitResetKindStatus? {
    val obj = this as? JsonObject ?: return null
    val kind = RuntimeLimitResetKind.fromWire(obj.claudeString("kind")) ?: return null
    fun only(vararg keys: String): Boolean = obj.keys.all { it == "kind" || it == "state" || it in keys }
    return when (obj.claudeString("state")) {
        "available" -> {
            if (!only("resetsLeft", "resetsTotal", "expiresAt", "expiresText")) return null
            RuntimeLimitResetKindStatus.Available(
                kind = kind,
                resetsLeft = obj.claudeCount("resetsLeft"),
                resetsTotal = obj.claudeCount("resetsTotal"),
                expiresAt = obj.claudeString("expiresAt"),
                expiresText = obj.claudeString("expiresText")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        "none" -> RuntimeLimitResetKindStatus.None(kind).takeIf { only() }
        "unknown" -> RuntimeLimitResetKindStatus.Unknown(kind).takeIf { only() }
        "not_checked" -> RuntimeLimitResetKindStatus.NotChecked(kind).takeIf { only() }
        "not_offered" -> RuntimeLimitResetKindStatus.NotOffered(kind).takeIf { only() }
        else -> null
    }
}

/** A `POST …/claude/limit-resets/read` answer: the result, or why the request got none. */
internal sealed interface RuntimeLimitResetsReadLoad {
    data class Done(val result: RuntimeLimitResetsReadResult) : RuntimeLimitResetsReadLoad

    data class Failed(val refusal: RuntimeLimitResetRefusal) : RuntimeLimitResetsReadLoad

    data object Superseded : RuntimeLimitResetsReadLoad
}

/** Web `claudeLimitResetsReadReply`: `{result}`; no valid result is a malformed answer (null). */
internal fun JsonElement.toRuntimeLimitResetsReadReply(): RuntimeLimitResetsReadResult? =
    (this as? JsonObject)?.get("result")?.toRuntimeLimitResetsReadResult()

/** slock `ClaudeFiveHourLimit`. */
internal enum class ClaudeFiveHourLimit { AtLimit, NotAtLimit, Unknown }

/** slock `CLAUDE_FIVE_HOUR_WINDOW_LABEL`: Claude's 5-hour window, as OAR labels it. */
internal const val CLAUDE_FIVE_HOUR_WINDOW_LABEL: String = "Current session"

/**
 * slock `claudeFiveHourLimitOf`: only one account with measured windows (health `ok` or
 * `rate_limited`) and one readable "Current session" window is an answer; anything else unknown.
 */
internal fun claudeFiveHourLimitOf(snapshot: RuntimeAccountUsageSnapshot?): ClaudeFiveHourLimit {
    if (snapshot?.provider != "claude" || snapshot.accounts.size != 1) return ClaudeFiveHourLimit.Unknown
    val account = snapshot.accounts.single()
    if (account.health != RuntimeAccountUsageHealth.Ok && account.health != RuntimeAccountUsageHealth.RateLimited) return ClaudeFiveHourLimit.Unknown
    val window = account.windows.filter { it.label == CLAUDE_FIVE_HOUR_WINDOW_LABEL }.singleOrNull() ?: return ClaudeFiveHourLimit.Unknown
    return when {
        window.status == RuntimeAccountUsageWindowStatus.LimitReached -> ClaudeFiveHourLimit.AtLimit
        window.status == RuntimeAccountUsageWindowStatus.Ok && window.usedRatio != null && window.usedRatio < 1.0 -> ClaudeFiveHourLimit.NotAtLimit
        else -> ClaudeFiveHourLimit.Unknown
    }
}

private fun JsonObject.claudeString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull
}

private fun JsonObject.claudeTrue(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull == "true"

private fun JsonObject.claudeCount(key: String): Int? {
    val value = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
    if (!value.isFinite() || value != kotlin.math.floor(value)) return null
    return value.toInt().takeIf { it in 0..RUNTIME_LIMIT_RESETS_AVAILABLE_MAX }
}
