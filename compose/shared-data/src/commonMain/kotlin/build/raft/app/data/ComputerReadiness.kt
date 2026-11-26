package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.reportUnknownTypeDropped
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * Agent & Computer readiness contract (`#wg-mobile-sync:#337`, botiverse/slock #14554, #14587,
 * #14590, #14602; `packages/shared/src/readiness.ts`).
 *
 * Wire models on `GET /api/servers/:id/machines`:
 * - `attention?: ReadinessAttention` — per-Computer needs-attention summary (`count`, `items`,
 *   `waitingAgents`, `waitingUntil`), already trimmed by the server for the viewer (no `code` /
 *   `resource` / `agentIds` for viewers who may not see the reason, and no `actorName` for guests).
 * - `readiness?: ComputerReadiness` — the Computer's own composed readiness plus per-runtime
 *   readiness map (`runtimes[runtimeId]`), already trimmed for the viewer.
 *
 * Parsing is intentionally lenient and additive: an unknown readiness `code` is kept (rendered
 * with the generic sentence), an unreadable item or fact is skipped, and older servers that omit
 * `attention` / `readiness` leave both fields null.
 */

/** Fixed Anthropic Verification Portal URL; never taken from provider error text. */
@RaftCoreInternalApi const val ANTHROPIC_VERIFICATION_PORTAL_URL: String = "https://portal.anthropic.com/verify"

/** Upper bound on facts decoded from a single readiness list (shared `READINESS_REPORT_MAX_FACTS`). */
@RaftCoreInternalApi const val COMPUTER_READINESS_REPORT_MAX_FACTS: Int = 50

/** Attention-only summary item codes beside [COMPUTER_READINESS_KNOWN_CODES]. */
@RaftCoreInternalApi const val COMPUTER_READINESS_ATTENTION_UPDATE_AVAILABLE: String = "computer_update_available"
@RaftCoreInternalApi const val COMPUTER_READINESS_ATTENTION_UPDATE_REQUIRED: String = "computer_update_required"

/** Known readiness codes in `packages/shared/src/readiness.ts` `READINESS_CODES`. */
@RaftCoreInternalApi val COMPUTER_READINESS_KNOWN_CODES: Set<String> =
    setOf(
        "computer_offline",
        "computer_reconnecting",
        "computer_restarting",
        "computer_updating",
        "computer_too_old",
        "runtime_missing",
        "verification_required",
        "model_unavailable",
    )

/**
 * Waits that end when a usage limit resets (`READINESS_LIMIT_WAIT_CODES` in `readinessCopy.ts`):
 * orange light like a block, and title says when it is back ("Back in 6d").
 */
@RaftCoreInternalApi val COMPUTER_READINESS_LIMIT_WAIT_CODES: Set<String> =
    setOf("quota_exhausted", "rate_limited")

private val READINESS_CODE_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")

/** Shared `ReadinessBehaviour`: blocked (someone acts), waiting (clears itself), advisory (can run). */
@RaftCoreInternalApi enum class ComputerReadinessBehaviour(val wire: String) {
    Blocked("blocked"),
    Waiting("waiting"),
    Advisory("advisory");

    companion object {
        fun fromWire(value: String?): ComputerReadinessBehaviour? =
            entries.firstOrNull { it.wire == value }
    }
}

/** Shared `Readiness["state"]`. */
@RaftCoreInternalApi enum class ComputerReadinessState(val wire: String) {
    Ready("ready"),
    Blocked("blocked"),
    Waiting("waiting"),
    Advisory("advisory");

    companion object {
        fun fromWire(value: String?): ComputerReadinessState? =
            entries.firstOrNull { it.wire == value }
    }
}

/** Shared `Readiness["scope"]`: whose problem the broken resource is. */
@RaftCoreInternalApi enum class ComputerReadinessScope(val wire: String) {
    Server("server"),
    Computer("computer"),
    Agent("agent");

    companion object {
        fun fromWire(value: String?): ComputerReadinessScope? =
            entries.firstOrNull { it.wire == value }
    }
}

/** Shared `ReadinessActor`: owner of the broken resource who can act. */
@RaftCoreInternalApi enum class ComputerReadinessActor(val wire: String) {
    ServerAdmin("server_admin"),
    ComputerOwner("computer_owner"),
    AgentOwner("agent_owner");

    companion object {
        fun fromWire(value: String?): ComputerReadinessActor? =
            entries.firstOrNull { it.wire == value }
    }
}

/** Shared `ReadinessAction`: closed set of actions shown to viewers (`fix` is the fallback). */
@RaftCoreInternalApi enum class ComputerReadinessAction(val wire: String) {
    SignIn("sign_in"),
    VerifyAccount("verify_account"),
    UpdateComputer("update_computer"),
    ChangeModel("change_model"),
    Fix("fix");

    companion object {
        fun fromWire(value: String?): ComputerReadinessAction? =
            entries.firstOrNull { it.wire == value }
    }
}

/** Shared `ReadinessResource`: dependency chain resource that a fact or attention item targets. */
@RaftCoreInternalApi sealed interface ComputerReadinessResource {
    data object Server : ComputerReadinessResource

    data object Computer : ComputerReadinessResource

    data class Runtime(val runtime: String) : ComputerReadinessResource

    data class Login(val key: String) : ComputerReadinessResource

    data class LoginWindow(val key: String, val window: String) : ComputerReadinessResource

    data class Model(val key: String, val model: String) : ComputerReadinessResource

    data class Agent(val agentId: String) : ComputerReadinessResource
}

/** Shared `ReadinessFact`. Unknown `code` values are preserved; `behaviour` governs handling. */
@RaftCoreInternalApi data class ComputerReadinessFact(
    val resource: ComputerReadinessResource,
    val code: String,
    val behaviour: ComputerReadinessBehaviour,
    val sinceMs: Long,
    val untilMs: Long? = null,
    /** `params.managedByDesktop` for `computer_too_old` (Raft Desktop bundled Computer). */
    val managedByDesktop: Boolean? = null,
    /** `params.model` for `model_unavailable` (configured model that left the runtime catalog). */
    val model: String? = null,
)

/** Shared `Readiness`: composed conclusion for one chain (the Computer itself or one runtime on it). */
@RaftCoreInternalApi data class ComputerReadinessEntry(
    val state: ComputerReadinessState,
    val primary: ComputerReadinessFact? = null,
    val scope: ComputerReadinessScope? = null,
    val actor: ComputerReadinessActor? = null,
    /** Server member display name of the actor; omitted by server for guests. */
    val actorName: String? = null,
    val action: ComputerReadinessAction? = null,
    val also: List<ComputerReadinessFact> = emptyList(),
)

/** Shared `ComputerReadiness`: the Computer's own readiness plus per-runtime readiness entries. */
@RaftCoreInternalApi data class ComputerReadinessSnapshot(
    val own: ComputerReadinessEntry,
    val runtimes: Map<String, ComputerReadinessEntry> = emptyMap(),
)

/** Shared `ReadinessAttentionItem`: one thing a person must act on for a Computer. */
@RaftCoreInternalApi data class ComputerReadinessAttentionItem(
    /** Readiness code or attention update code; null when viewer may not see the reason. */
    val code: String? = null,
    /** Target resource; null when viewer may not see the reason. */
    val resource: ComputerReadinessResource? = null,
    val actor: ComputerReadinessActor,
    /** Server member display name of the actor; null for guests (guest privacy) or when unknown. */
    val actorName: String? = null,
    val action: ComputerReadinessAction,
    /** Held agent IDs; null when viewer may not see the reason. */
    val agentIds: List<String>? = null,
    val agentCount: Int = 0,
)

/** Shared `ReadinessAttention`: per-Computer summary of actionable items and self-clearing waits. */
@RaftCoreInternalApi data class ComputerReadinessAttention(
    val count: Int,
    val items: List<ComputerReadinessAttentionItem> = emptyList(),
    val waitingAgents: Int = 0,
    /** Earliest epoch ms when any waiting agent's wait ends; null when unknown. */
    val waitingUntilMs: Long? = null,
)

/** Web `readinessHolds`: true when the readiness blocks or waits on something. */
@RaftCoreInternalApi fun ComputerReadinessEntry?.holds(): Boolean =
    this?.state == ComputerReadinessState.Blocked || this?.state == ComputerReadinessState.Waiting

/**
 * True when the Computer's readiness reports a planned `computer_restarting` window that has not
 * yet expired at [nowMillis] (or unconditionally when [nowMillis] is null / `untilMs` is absent).
 */
@RaftCoreInternalApi fun ComputerReadinessSnapshot?.isComputerRestartingWindow(nowMillis: Long? = null): Boolean {
    val primary = this?.own?.primary ?: return false
    if (primary.code != "computer_restarting") return false
    val until = primary.untilMs
    return nowMillis == null || until == null || until > nowMillis
}

/**
 * True when the Computer's readiness reports a `computer_updating` window that has not yet expired
 * at [nowMillis] (or unconditionally when [nowMillis] is null / `untilMs` is absent).
 */
@RaftCoreInternalApi fun ComputerReadinessSnapshot?.isComputerUpdatingWindow(nowMillis: Long? = null): Boolean {
    val primary = this?.own?.primary ?: return false
    if (primary.code != "computer_updating") return false
    val until = primary.untilMs
    return nowMillis == null || until == null || until > nowMillis
}

/**
 * Projects [ComputerReadinessAttention.items] into the usage-cell login steps (`Verify` / `Sign in`,
 * web `rackLoginStep`, slock #14602): items with a visible `code`, at least one held agent, a
 * machine login resource (`shared:<runtime>`), and `verify_account` or `sign_in` action.
 */
@RaftCoreInternalApi fun ComputerReadinessAttention.toLoginSteps(): List<ComputerLoginStep> =
    items.mapNotNull { item ->
        if (item.code.isNullOrBlank() || item.agentCount <= 0) return@mapNotNull null
        val login = item.resource as? ComputerReadinessResource.Login ?: return@mapNotNull null
        if (!login.key.startsWith("shared:")) return@mapNotNull null
        val runtimeId = login.key.removePrefix("shared:").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val step =
            when (item.action) {
                ComputerReadinessAction.VerifyAccount -> ComputerLoginStepAction.VerifyAccount
                ComputerReadinessAction.SignIn -> ComputerLoginStepAction.SignIn
                ComputerReadinessAction.UpdateComputer,
                ComputerReadinessAction.ChangeModel,
                ComputerReadinessAction.Fix -> return@mapNotNull null
            }
        ComputerLoginStep(runtimeId, step)
    }

/** Lenient parser for `GET /servers/:id/machines` item `attention`. Returns null when unreadable. */
@RaftCoreInternalApi fun parseComputerReadinessAttention(element: JsonElement): ComputerReadinessAttention? {
    val obj = element as? JsonObject ?: return null
    val count = obj["count"].finiteNonNegativeIntOrNull() ?: return null
    val waitingAgents = obj["waitingAgents"].finiteNonNegativeIntOrNull() ?: 0
    val waitingUntilMs = obj["waitingUntil"].finitePositiveLongOrNull()
    val rawItems = obj["items"] as? JsonArray
    val items =
        rawItems?.mapNotNull { row ->
            parseComputerReadinessAttentionItem(row)
        }.orEmpty()
    return ComputerReadinessAttention(
        count = count,
        items = items,
        waitingAgents = waitingAgents,
        waitingUntilMs = waitingUntilMs,
    )
}

private fun parseComputerReadinessAttentionItem(element: JsonElement): ComputerReadinessAttentionItem? {
    val obj = element as? JsonObject ?: return null
    val actorRaw = obj["actor"].nonBlankStringOrNull()
    val actor = ComputerReadinessActor.fromWire(actorRaw)
    if (actor == null) {
        if (actorRaw != null) {
            reportUnknownTypeDropped(source = "machines.attention", field = "actor", value = actorRaw, fallback = "drop_item")
        }
        return null
    }
    val actionRaw = obj["action"].nonBlankStringOrNull()
    val action =
        if (actionRaw == null) {
            ComputerReadinessAction.Fix
        } else {
            ComputerReadinessAction.fromWire(actionRaw) ?: run {
                reportUnknownTypeDropped(source = "machines.attention", field = "action", value = actionRaw, fallback = "fix")
                ComputerReadinessAction.Fix
            }
        }
    val code = obj["code"].nonBlankStringOrNull()
    val resource = obj["resource"]?.let(::parseComputerReadinessResource)
    val actorName = obj["actorName"].nonBlankStringOrNull()
    val agentIds =
        (obj["agentIds"] as? JsonArray)?.mapNotNull { it.nonBlankStringOrNull() }
    val agentCount = obj["agentCount"].finiteNonNegativeIntOrNull() ?: 0
    return ComputerReadinessAttentionItem(
        code = code,
        resource = resource,
        actor = actor,
        actorName = actorName,
        action = action,
        agentIds = agentIds,
        agentCount = agentCount,
    )
}

/** Lenient parser for `GET /servers/:id/machines` item `readiness`. Returns null when unreadable. */
@RaftCoreInternalApi fun parseComputerReadiness(element: JsonElement): ComputerReadinessSnapshot? {
    val obj = element as? JsonObject ?: return null
    val own = parseComputerReadinessEntry(obj) ?: return null
    val runtimesObj = obj["runtimes"] as? JsonObject
    val runtimes =
        if (runtimesObj == null) {
            emptyMap()
        } else {
            buildMap {
                runtimesObj.forEach { (runtimeId, value) ->
                    val cleanId = runtimeId.trim()
                    if (cleanId.isNotEmpty()) {
                        parseComputerReadinessEntry(value)?.let { entry -> put(cleanId, entry) }
                    }
                }
            }
        }
    return ComputerReadinessSnapshot(own = own, runtimes = runtimes)
}

/** Lenient parser for a single `Readiness` JSON object. */
@RaftCoreInternalApi fun parseComputerReadinessEntry(element: JsonElement): ComputerReadinessEntry? {
    val obj = element as? JsonObject ?: return null
    val stateRaw = obj["state"].nonBlankStringOrNull()
    val state = ComputerReadinessState.fromWire(stateRaw)
    if (state == null) {
        if (stateRaw != null) {
            reportUnknownTypeDropped(source = "machines.readiness", field = "state", value = stateRaw, fallback = "drop_readiness")
        }
        return null
    }
    val primary = obj["primary"]?.let(::parseComputerReadinessFact)
    val scope = ComputerReadinessScope.fromWire(obj["scope"].nonBlankStringOrNull())
    val actor = ComputerReadinessActor.fromWire(obj["actor"].nonBlankStringOrNull())
    val actorName = obj["actorName"].nonBlankStringOrNull()
    val actionRaw = obj["action"].nonBlankStringOrNull()
    val action =
        actionRaw?.let { raw ->
            ComputerReadinessAction.fromWire(raw) ?: run {
                reportUnknownTypeDropped(source = "machines.readiness", field = "action", value = raw, fallback = "fix")
                ComputerReadinessAction.Fix
            }
        }
    val also =
        (obj["also"] as? JsonArray)
            ?.asSequence()
            ?.mapNotNull(::parseComputerReadinessFact)
            ?.take(COMPUTER_READINESS_REPORT_MAX_FACTS)
            ?.toList()
            .orEmpty()
    return ComputerReadinessEntry(
        state = state,
        primary = primary,
        scope = scope,
        actor = actor,
        actorName = actorName,
        action = action,
        also = also,
    )
}

/** Lenient parser for a single `ReadinessFact` (keeps unknown codes matching `^[a-z][a-z0-9_]{0,63}$`). */
@RaftCoreInternalApi fun parseComputerReadinessFact(element: JsonElement): ComputerReadinessFact? {
    val obj = element as? JsonObject ?: return null
    val resource = obj["resource"]?.let(::parseComputerReadinessResource) ?: return null
    val code = obj["code"].nonBlankStringOrNull()?.takeIf { READINESS_CODE_PATTERN.matches(it) } ?: return null
    val behaviour = ComputerReadinessBehaviour.fromWire(obj["behaviour"].nonBlankStringOrNull()) ?: return null
    val sinceMs = obj["since"].finitePositiveLongOrNull() ?: return null
    val untilMs = obj["until"].finitePositiveLongOrNull()
    val paramsObj = obj["params"] as? JsonObject
    val managedByDesktop =
        (paramsObj?.get("managedByDesktop") as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.booleanOrNull
    val model = paramsObj?.get("model").nonBlankStringOrNull()?.takeIf { it.length <= 200 }
    return ComputerReadinessFact(
        resource = resource,
        code = code,
        behaviour = behaviour,
        sinceMs = sinceMs,
        untilMs = untilMs,
        managedByDesktop = managedByDesktop,
        model = model,
    )
}

private fun parseComputerReadinessResource(element: JsonElement): ComputerReadinessResource? {
    val obj = element as? JsonObject ?: return null
    fun boundedText(field: String): String? =
        obj[field].nonBlankStringOrNull()?.takeIf { it.length <= 200 }
    return when (obj["kind"].nonBlankStringOrNull()) {
        "server" -> ComputerReadinessResource.Server
        "computer" -> ComputerReadinessResource.Computer
        "runtime" -> boundedText("runtime")?.let(ComputerReadinessResource::Runtime)
        "login" -> boundedText("key")?.let(ComputerReadinessResource::Login)
        "login_window" -> {
            val key = boundedText("key") ?: return null
            val window = boundedText("window") ?: return null
            ComputerReadinessResource.LoginWindow(key, window)
        }
        "model" -> {
            val key = boundedText("key") ?: return null
            val model = boundedText("model") ?: return null
            ComputerReadinessResource.Model(key, model)
        }
        "agent" -> boundedText("agentId")?.let(ComputerReadinessResource::Agent)
        else -> null
    }
}

private fun JsonElement?.nonBlankStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement?.finiteNonNegativeIntOrNull(): Int? =
    (this as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.doubleOrNull
        ?.takeIf { it.isFinite() && it >= 0.0 && it <= Int.MAX_VALUE.toDouble() }
        ?.toInt()

private fun JsonElement?.finitePositiveLongOrNull(): Long? =
    (this as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.doubleOrNull
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.toLong()
