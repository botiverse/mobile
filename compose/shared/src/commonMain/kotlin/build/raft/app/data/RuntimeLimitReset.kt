package build.raft.app.data

import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Using a Codex limit reset from the usage card (slock #13504, staging a6885e181).
 *
 * Spend: `POST /servers/:id/machines/:machineId/runtime-account-usage/:provider/limit-reset` with
 * `{idempotencyKey}` (packages/server/src/routes/runtimeAuthRoutes.ts). People only; the human who
 * attached the Computer or a server owner/admin (as a sign-out), else 403. 400 for a bad key, 404
 * for an unknown provider or Computer, 409 `computer_offline` / `computer_upgrade_required` /
 * `computer_not_attached`, 504 `computer_timeout`, 500. A 200 answers
 * `{result: RuntimeLimitResetResult, snapshot: RuntimeAccountUsageSnapshot | null}`
 * (packages/shared/src/runtimeAccountUsage.ts `runtimeLimitResetResultSchema`).
 *
 * Who may act: `canAct` of `GET /servers/:id/machines/:machineId/runtime-auth` (same file), the
 * read the web's card gates the action on (`RuntimeAuthView`).
 */

/** slock `RUNTIME_LIMIT_RESET_REAUTH_REASONS` (OAR `AccountUsageReauthReason`). */
internal val RUNTIME_LIMIT_RESET_REAUTH_REASONS: Set<String> =
    setOf("not_authenticated", "credentials_missing", "scope_missing", "credentials_rejected")

/** slock `RUNTIME_LIMIT_RESET_UNSUPPORTED_REASONS` (OAR `AccountUsageUnsupportedReason` and `not_installed`). */
internal val RUNTIME_LIMIT_RESET_UNSUPPORTED_REASONS: Set<String> =
    setOf(
        "capability_unavailable",
        "unsupported_installation",
        "unsupported_auth_mode",
        "unsupported_auth_storage",
        "auth_configuration_unavailable",
        "endpoint_unavailable",
        "quota_unavailable",
        "not_installed",
        // The Claude driver's (slock #14336): a Claude Code it was not tested against, a platform it
        // cannot drive, and nothing attempted (busy or out of time; the same click may be tried again).
        "unsupported_version",
        "unsupported_platform",
        "not_attempted",
    )

/** slock `RUNTIME_LIMIT_RESETS_READ_UNSUPPORTED_REASONS`: a read may also need the person's consent first. */
internal val RUNTIME_LIMIT_RESETS_READ_UNSUPPORTED_REASONS: Set<String> = RUNTIME_LIMIT_RESET_UNSUPPORTED_REASONS + "consent_required"

/**
 * How a redeem ended (slock `RuntimeLimitResetResult`). None of them says what the limits are now:
 * the card shows the usage read that came with the answer, or reads again.
 */
internal sealed interface RuntimeLimitResetResult {
    val wire: String

    data object Reset : RuntimeLimitResetResult { override val wire = "reset" }
    data object NothingToReset : RuntimeLimitResetResult { override val wire = "nothing_to_reset" }
    data object NoReset : RuntimeLimitResetResult { override val wire = "no_reset" }
    data object AlreadyRedeemed : RuntimeLimitResetResult { override val wire = "already_redeemed" }
    data class ReauthRequired(val reason: String) : RuntimeLimitResetResult { override val wire = "reauth_required" }
    data class Unsupported(val reason: String) : RuntimeLimitResetResult { override val wire = "unsupported" }

    /** The redeem threw: it is not known whether a reset was spent; retry with the same key. */
    data object Unknown : RuntimeLimitResetResult { override val wire = "unknown" }

    /**
     * `{kind: "unknown", fiveHourSpent: true}` (Claude only): Claude's older program spent its
     * 5-hour reset with no confirmation while a different kind was being asked for.
     */
    data object UnknownFiveHourSpent : RuntimeLimitResetResult { override val wire = "unknown" }
}

/** Web `limitResetOutcomeIsSettled`: every answer but `unknown` ends the attempt; the next click mints a new key. */
internal val RuntimeLimitResetResult?.settlesAttempt: Boolean
    get() = this != null && this != RuntimeLimitResetResult.Unknown && this != RuntimeLimitResetResult.UnknownFiveHourSpent

/**
 * The strict result schema (`z.strictObject` per kind): an unknown kind, a missing or unknown
 * reason, or an extra field is no result at all (null), as the web's `safeParse` refuses it.
 */
internal fun JsonElement.toRuntimeLimitResetResult(): RuntimeLimitResetResult? {
    val obj = this as? JsonObject ?: return null
    val kind = obj.plainString("kind") ?: return null
    fun only(vararg keys: String): Boolean = obj.keys == setOf("kind", *keys)
    return when (kind) {
        "reset" -> RuntimeLimitResetResult.Reset.takeIf { only() }
        "nothing_to_reset" -> RuntimeLimitResetResult.NothingToReset.takeIf { only() }
        "no_reset" -> RuntimeLimitResetResult.NoReset.takeIf { only() }
        "already_redeemed" -> RuntimeLimitResetResult.AlreadyRedeemed.takeIf { only() }
        "unknown" ->
            when {
                only() -> RuntimeLimitResetResult.Unknown
                only("fiveHourSpent") && obj.trueFlag("fiveHourSpent") -> RuntimeLimitResetResult.UnknownFiveHourSpent
                else -> null
            }
        "reauth_required" ->
            obj.plainString("reason")
                ?.takeIf { only("reason") && it in RUNTIME_LIMIT_RESET_REAUTH_REASONS }
                ?.let(RuntimeLimitResetResult::ReauthRequired)
        "unsupported" ->
            obj.plainString("reason")
                ?.takeIf { only("reason") && it in RUNTIME_LIMIT_RESET_UNSUPPORTED_REASONS }
                ?.let(RuntimeLimitResetResult::Unsupported)
        else -> null
    }
}

/** A 200 from the limit-reset route: the outcome and the usage the Computer read right after it. */
internal data class RuntimeLimitResetReply(
    val result: RuntimeLimitResetResult,
    /** Null when the Computer could not read usage again, or sent one this app cannot use. */
    val snapshot: RuntimeAccountUsageSnapshot?
)

/** Web `RuntimeAuthClient.redeemLimitReset`: no valid `result` is a malformed answer (null). */
internal fun JsonElement.toRuntimeLimitResetReply(): RuntimeLimitResetReply? {
    val envelope = this as? JsonObject ?: return null
    val result = envelope["result"]?.toRuntimeLimitResetResult() ?: return null
    val snapshot = (envelope["snapshot"] as? JsonObject)?.toRuntimeAccountUsageSnapshotOrNull()
    return RuntimeLimitResetReply(result = result, snapshot = snapshot)
}

/**
 * One limit-reset attempt (slock `RuntimeLimitResetIdempotencyKey`, 16..128 of `[A-Za-z0-9_-]`).
 * Minted once per attempt and sent again when the person retries an attempt whose outcome was
 * never learned, so a retry never spends a second reset.
 */
@JvmInline
internal value class RuntimeLimitResetIdempotencyKey private constructor(val value: String) {
    companion object {
        private val Shape = Regex("^[A-Za-z0-9_-]{16,128}$")

        fun of(value: String): RuntimeLimitResetIdempotencyKey? = value.takeIf(Shape::matches)?.let(::RuntimeLimitResetIdempotencyKey)

        /** The web mints `crypto.randomUUID()`; the same shape here. */
        @OptIn(ExperimentalUuidApi::class)
        fun mint(): RuntimeLimitResetIdempotencyKey = RuntimeLimitResetIdempotencyKey(Uuid.random().toString())
    }
}

/** Why a limit-reset request got no outcome: the HTTP status and the route's `code`, when it sent one. */
internal data class RuntimeLimitResetRefusal(val status: Int?, val code: String?)

/** What [RaftComputersApiClient.redeemRuntimeLimitReset] / the store's redeem got back. */
internal sealed interface RuntimeLimitResetLoad {
    data class Done(val reply: RuntimeLimitResetReply) : RuntimeLimitResetLoad

    /** No outcome (refused, offline, timed out, a server error, a dropped connection or a malformed answer). */
    data class Failed(val refusal: RuntimeLimitResetRefusal) : RuntimeLimitResetLoad

    /** As [RuntimeAccountUsageLoad.Superseded]: the signed-in identity changed; never shown. */
    data object Superseded : RuntimeLimitResetLoad
}

/** slock `runtimeAuthRuntimeFor` for the usage providers. */
internal val RuntimeAccountUsageProvider.authRuntime: RuntimeAuthRuntime
    get() =
        when (this) {
            RuntimeAccountUsageProvider.Claude -> RuntimeAuthRuntime.Claude
            RuntimeAccountUsageProvider.Codex -> RuntimeAuthRuntime.Codex
            RuntimeAccountUsageProvider.Kimi -> RuntimeAuthRuntime.Kimi
            // Grok Build signs in from the web since slock #14343.
            RuntimeAccountUsageProvider.Grok -> RuntimeAuthRuntime.Grok
            RuntimeAccountUsageProvider.Antigravity -> RuntimeAuthRuntime.Antigravity
        }

/** `z.literal(true)`: a boolean `true`, never the string. */
private fun JsonObject.trueFlag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull == "true"

private fun JsonObject.plainString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull
}
