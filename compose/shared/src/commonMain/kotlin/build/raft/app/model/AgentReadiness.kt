package build.raft.app.model

import kotlinx.serialization.Serializable

/**
 * Where the account holder verifies an Anthropic account (web `ANTHROPIC_VERIFICATION_PORTAL_URL`,
 * `botiverse/slock` `packages/shared/src/readiness.ts`). Always this constant: a link is never
 * taken from provider error text.
 */
const val ANTHROPIC_VERIFICATION_PORTAL_URL: String = "https://portal.anthropic.com/verify"

/**
 * Daemon-reported waits that end when a provider usage limit or rate limit resets
 * (web `READINESS_LIMIT_WAIT_CODES`, `botiverse/slock` `packages/web/src/utils/readinessCopy.ts`).
 */
val AGENT_READINESS_LIMIT_WAIT_CODES: Set<String> = setOf("quota_exhausted", "rate_limited")

/** Known composed readiness codes rendered with dedicated catalog copy (web `READINESS_CODES`). */
val AGENT_READINESS_KNOWN_CODES: Set<String> =
    setOf(
        "computer_offline",
        "computer_reconnecting",
        "computer_restarting",
        "computer_updating",
        "computer_too_old",
        "runtime_missing",
        "verification_required",
    )

/**
 * The resource a readiness fact refers to (`ReadinessResource` in `botiverse/slock`
 * `packages/shared/src/readiness.ts`). Additive: unknown [kind] values are preserved raw.
 */
@Serializable
data class AgentReadinessResource(
    val kind: String,
    val runtime: String? = null,
    val key: String? = null,
    val window: String? = null,
    val model: String? = null,
    val agentId: String? = null,
)

/**
 * Primary blocking/waiting fact of an agent's composed readiness (`ReadinessFact` in
 * `botiverse/slock` `packages/shared/src/readiness.ts`).
 */
@Serializable
data class AgentReadinessFact(
    val resource: AgentReadinessResource? = null,
    val code: String,
    val behaviour: String? = null,
    val since: Long? = null,
    val until: Long? = null,
    val managedByDesktop: Boolean? = null,
)

/**
 * Server-composed agent readiness from `GET /api/agents` (`Readiness` in `botiverse/slock`
 * `packages/shared/src/readiness.ts`, already trimmed for the current viewer by
 * `trimReadinessForViewer`). When the viewer may not see the reason, [primary] and [scope] are
 * null while [state], [actor], [actorName], and [action] remain.
 */
@Serializable
data class AgentReadiness(
    val state: String,
    val primary: AgentReadinessFact? = null,
    val scope: String? = null,
    val actor: String? = null,
    val actorName: String? = null,
    val action: String? = null,
)

/** Presence light tier for a holding readiness (web `readinessLight` in `readinessCopy.ts`). */
enum class AgentReadinessLight {
    /** Orange (error/warning): blocked states and usage/rate limit waits (`quota_exhausted`, `rate_limited`). */
    Error,
    /** Yellow (busy): transient waits in progress (`computer_restarting`, `computer_updating`). */
    Busy,
    /** Stone gray (offline): `computer_offline`. */
    Offline,
}

/** Countdown until a limit wait's [AgentReadinessFact.until] resets (web `rackReset`). */
data class AgentReadinessResetCountdown(
    val unit: RaftRelativeTimeUnit,
    val count: Int,
)

/**
 * Whether [this] readiness holds the agent from taking turns right now (web `readinessHolds`:
 * `state === "blocked" || state === "waiting"`).
 */
fun AgentReadiness?.holds(): Boolean =
    this != null && (state == "blocked" || state == "waiting")

/** True when [AgentReadiness.primary] names a provider quota/rate-limit wait. */
fun AgentReadiness.isLimitWait(): Boolean =
    primary?.code in AGENT_READINESS_LIMIT_WAIT_CODES

/** Web `readinessLight`: orange for blocked or limit waits, stone for offline Computer, yellow for transient waits. */
fun AgentReadiness.light(): AgentReadinessLight {
    val code = primary?.code
    if (code == "computer_offline") return AgentReadinessLight.Offline
    if (state == "blocked" || code in AGENT_READINESS_LIMIT_WAIT_CODES) return AgentReadinessLight.Error
    return AgentReadinessLight.Busy
}

/**
 * Web `runtimeOf`: extracts the runtime id named by [AgentReadinessFact.resource] (`runtime` or
 * `shared:<runtime>` on `login` / `login_window`), falling back to the agent's own runtime id.
 */
fun AgentReadiness.resolvedRuntimeId(fallbackRuntimeId: String?): String? {
    val resource = primary?.resource
    if (resource?.kind == "runtime") {
        resource.runtime?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    if (
        (resource?.kind == "login" || resource?.kind == "login_window") &&
        resource.key?.startsWith("shared:") == true
    ) {
        resource.key.removePrefix("shared:").trim().takeIf { it.isNotEmpty() }?.let { return it }
    }
    return fallbackRuntimeId?.trim()?.takeIf { it.isNotEmpty() }
}

private const val READINESS_MILLIS_PER_MINUTE = 60_000L
private const val READINESS_MILLIS_PER_HOUR = 60L * READINESS_MILLIS_PER_MINUTE
private const val READINESS_MILLIS_PER_DAY = 24L * READINESS_MILLIS_PER_HOUR

/**
 * Web `rackReset` applied to [AgentReadinessFact.until] (epoch ms): minutes under 1h (floor, min 1),
 * hours under 48h (floor), days at/above 48h (floor). Null when unknown or already elapsed.
 */
fun agentReadinessResetCountdown(
    untilEpochMillis: Long?,
    nowEpochMillis: Long,
): AgentReadinessResetCountdown? {
    val until = untilEpochMillis ?: return null
    val left = until - nowEpochMillis
    if (left <= 0L) return null
    return when {
        left < READINESS_MILLIS_PER_HOUR ->
            AgentReadinessResetCountdown(
                unit = RaftRelativeTimeUnit.Minute,
                count = (left / READINESS_MILLIS_PER_MINUTE).toInt().coerceAtLeast(1),
            )
        left < 48L * READINESS_MILLIS_PER_HOUR ->
            AgentReadinessResetCountdown(
                unit = RaftRelativeTimeUnit.Hour,
                count = (left / READINESS_MILLIS_PER_HOUR).toInt(),
            )
        else ->
            AgentReadinessResetCountdown(
                unit = RaftRelativeTimeUnit.Day,
                count = (left / READINESS_MILLIS_PER_DAY).toInt(),
            )
    }
}
