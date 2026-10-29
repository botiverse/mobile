package build.raft.app.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mirrors `slock-ai/shared` `RuntimeConfig` (TypeScript) for the KMP side.
 * Source of truth lives in the external
 * `<slock-source>/packages/shared/src/index.ts` checkout.
 *
 * Kept intentionally narrow for the create-agent flow: we only need the
 * fields the agent can set on creation. Fields like `agentCredentialKey`
 * and `runtimeContext` are server-attached, not client-authored, so they
 * are absent here.
 */

const val RUNTIME_CONFIG_VERSION: Int = 1

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class RuntimeProviderConfig {
    @Serializable
    @SerialName("default")
    data object Default : RuntimeProviderConfig()

    @Serializable
    @SerialName("custom")
    data class Custom(val apiUrl: String, val apiKey: String) : RuntimeProviderConfig()

    @Serializable
    @SerialName("preset")
    data class Preset(val providerId: String, val apiKey: String) : RuntimeProviderConfig()

    @Serializable
    @SerialName("gateway")
    data class Gateway(
        val providerId: String,
        val baseUrl: String,
        val apiKey: String,
        val supportsImageInput: Boolean = false
    ) : RuntimeProviderConfig()

    @Serializable
    @SerialName("pi-builtin")
    data class PiBuiltin(val providerId: String, val apiKey: String) : RuntimeProviderConfig()
}

@Serializable
enum class RuntimeHostUserState {
    @SerialName("forbidden")
    Forbidden
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class RuntimeModelConfig {
    @Serializable
    @SerialName("preset")
    data class Preset(val id: String) : RuntimeModelConfig()

    @Serializable
    @SerialName("custom")
    data class Custom(val name: String) : RuntimeModelConfig()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class RuntimeModeConfig {
    @Serializable
    @SerialName("default")
    data object Default : RuntimeModeConfig()

    @Serializable
    @SerialName("fast")
    data object Fast : RuntimeModeConfig()
}

@Serializable
data class RuntimeConfig(
    val version: Int = RUNTIME_CONFIG_VERSION,
    val runtime: String,
    val provider: RuntimeProviderConfig? = null,
    val model: RuntimeModelConfig,
    val mode: RuntimeModeConfig = RuntimeModeConfig.Default,
    val reasoningEffort: String? = null,
    val envVars: Map<String, String>? = null,
    val command: String? = null,
    val loadLocalPlugins: Boolean? = null,
    val hostUserState: RuntimeHostUserState? = null
)

// ── Runtimes ──

data class RuntimeInfo(
    val id: String,
    val displayName: String,
    val binary: String,
    val supported: Boolean,
    val deprecated: Boolean = false
)

val RUNTIMES: List<RuntimeInfo> = listOf(
    RuntimeInfo(id = "claude", displayName = "Claude Code", binary = "claude", supported = true),
    RuntimeInfo(id = "codex", displayName = "Codex CLI", binary = "codex", supported = true),
    RuntimeInfo(id = "grok", displayName = "Grok Build", binary = "grok", supported = true),
    RuntimeInfo(id = "builtin", displayName = "Built-in Pi", binary = "", supported = true),
    RuntimeInfo(id = "antigravity", displayName = "Antigravity CLI", binary = "agy", supported = true),
    RuntimeInfo(id = "kimi-sdk", displayName = "Kimi Code", binary = "", supported = true),
    RuntimeInfo(id = "kimi", displayName = "Kimi CLI (deprecated)", binary = "kimi", supported = true, deprecated = true),
    RuntimeInfo(id = "copilot", displayName = "Copilot CLI", binary = "copilot", supported = true, deprecated = true),
    RuntimeInfo(id = "cursor", displayName = "Cursor CLI", binary = "cursor-agent", supported = true),
    RuntimeInfo(id = "gemini", displayName = "Gemini CLI (deprecated)", binary = "gemini", supported = true, deprecated = true),
    RuntimeInfo(id = "opencode", displayName = "OpenCode", binary = "opencode", supported = true),
    RuntimeInfo(id = "pi", displayName = "Pi", binary = "pi", supported = true)
)

fun getRuntimeDisplayName(id: String): String =
    RUNTIMES.firstOrNull { it.id == id }?.displayName ?: id

// ── Reasoning effort ──

val REASONING_EFFORT_RUNTIMES: Set<String> = setOf("builtin", "claude", "codex", "grok", "copilot", "pi")
val RUNTIME_FAST_MODE_RUNTIMES: Set<String> = setOf("claude", "codex")

// ── Runtime models ──

data class RuntimeModelInfo(
    val id: String,
    val label: String,
    val verified: String? = null, // "launchable" | "suggestion_only" | null
    val supportedReasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null
)

data class RuntimeModelPresentation(
    val models: List<RuntimeModelInfo>,
    val defaultModel: String?
)

val RUNTIME_MODELS: Map<String, List<RuntimeModelInfo>> = mapOf(
    "claude" to listOf(
        RuntimeModelInfo("opus", "Claude Opus"),
        RuntimeModelInfo("claude-opus-4-8", "Claude Opus 4.8"),
        RuntimeModelInfo("claude-opus-4-7", "Claude Opus 4.7"),
        RuntimeModelInfo("claude-opus-4-6", "Claude Opus 4.6"),
        RuntimeModelInfo("fable", "Claude Fable"),
        RuntimeModelInfo("sonnet", "Claude Sonnet"),
        RuntimeModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6"),
        RuntimeModelInfo("haiku", "Claude Haiku"),
        RuntimeModelInfo("claude-haiku-4-5", "Claude Haiku 4.5")
    ),
    "codex" to listOf(
        RuntimeModelInfo(
            "gpt-5.6-sol",
            "GPT-5.6 Sol",
            verified = "launchable",
            supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
            defaultReasoningEffort = "medium"
        ),
        RuntimeModelInfo(
            "gpt-5.6-terra",
            "GPT-5.6 Terra",
            verified = "launchable",
            supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
            defaultReasoningEffort = "medium"
        ),
        RuntimeModelInfo(
            "gpt-5.6-luna",
            "GPT-5.6 Luna",
            verified = "launchable",
            supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max"),
            defaultReasoningEffort = "medium"
        ),
        RuntimeModelInfo("gpt-5.5", "GPT-5.5"),
        RuntimeModelInfo("gpt-5.4", "GPT-5.4"),
        RuntimeModelInfo("gpt-5.3-codex", "GPT-5.3 Codex"),
        RuntimeModelInfo("gpt-5.3-codex-spark", "GPT-5.3 Codex Spark"),
        RuntimeModelInfo("gpt-5.2-codex", "GPT-5.2 Codex"),
        RuntimeModelInfo("gpt-5.2", "GPT-5.2"),
        RuntimeModelInfo("gpt-5.1-codex-max", "GPT-5.1 Codex Max"),
        RuntimeModelInfo("gpt-5.1-codex", "GPT-5.1 Codex"),
        RuntimeModelInfo("gpt-5-codex", "GPT-5 Codex"),
        RuntimeModelInfo("gpt-5", "GPT-5")
    ),
    "antigravity" to listOf(
        RuntimeModelInfo("default", "AGY configured default", verified = "suggestion_only")
    ),
    "copilot" to listOf(
        RuntimeModelInfo("gpt-5.4", "GPT-5.4"),
        RuntimeModelInfo("gpt-5.2", "GPT-5.2"),
        RuntimeModelInfo("claude-4-sonnet", "Claude 4 Sonnet"),
        RuntimeModelInfo("claude-4.5-sonnet", "Claude 4.5 Sonnet")
    ),
    "cursor" to listOf(
        RuntimeModelInfo("composer-2-fast", "Composer 2 Fast"),
        RuntimeModelInfo("composer-2", "Composer 2"),
        RuntimeModelInfo("auto", "Auto")
    ),
    "gemini" to listOf(
        RuntimeModelInfo("default", "Configured Default / Auto", verified = "suggestion_only"),
        RuntimeModelInfo("gemini-3.1-pro-preview", "Gemini 3.1 Pro (Preview)"),
        RuntimeModelInfo("gemini-3-flash-preview", "Gemini 3 Flash (Preview)"),
        RuntimeModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro"),
        RuntimeModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash")
    ),
    "opencode" to listOf(
        RuntimeModelInfo("default", "Configured Default / Auto", verified = "suggestion_only"),
        RuntimeModelInfo("deepseek/deepseek-v4-pro", "DeepSeek V4 Pro (OpenCode)", verified = "suggestion_only"),
        RuntimeModelInfo("openrouter/anthropic/claude-opus-4.5", "Claude Opus 4.5 via OpenRouter", verified = "suggestion_only"),
        RuntimeModelInfo("fusecode/opus[1m]", "Opus 1M via FuseCode", verified = "suggestion_only")
    ),
    "pi" to listOf(
        RuntimeModelInfo("default", "Configured Default / Auto", verified = "suggestion_only")
    ),
    "kimi" to listOf(
        RuntimeModelInfo("default", "Configured Default")
    )
)

fun getDefaultModel(runtimeId: String): String =
    RUNTIME_MODELS[runtimeId]?.firstOrNull()?.id ?: "sonnet"

fun getModelLabel(runtimeId: String, modelId: String): String =
    RUNTIME_MODELS[runtimeId]?.firstOrNull { it.id == modelId }?.label ?: modelId

/**
 * Mirrors React `canonicalizeCodexPresentation`.
 *
 * Codex hosts can report the older machine default (`gpt-5.5`) first even when
 * the Raft GPT-5.6 presets are available. Keep live-only models, but present
 * known Codex presets in the shared catalog order and prefer GPT-5.6 Sol only
 * when the selected machine actually surfaced it. Other runtimes keep the
 * machine-provided order/default unchanged.
 */
fun canonicalizeRuntimeModelPresentation(
    runtimeId: String,
    models: List<RuntimeModelInfo>,
    machineDefault: String?
): RuntimeModelPresentation {
    if (runtimeId != "codex") {
        return RuntimeModelPresentation(models = models, defaultModel = machineDefault)
    }
    val canonicalOrder = RUNTIME_MODELS["codex"].orEmpty().map { it.id }
    val rankById = canonicalOrder.withIndex().associate { (index, id) -> id to index }
    val ordered = models.sortedBy { model -> rankById[model.id] ?: Int.MAX_VALUE }
    val availableIds = ordered.mapTo(linkedSetOf()) { it.id }
    val preferred = getDefaultModel("codex")
    val defaultModel =
        when {
            preferred in availableIds -> preferred
            machineDefault != null && machineDefault in availableIds -> machineDefault
            else -> ordered.firstOrNull()?.id
        }
    return RuntimeModelPresentation(models = ordered, defaultModel = defaultModel)
}

// ── Capability predicates ──

fun supportsRuntimeFastMode(runtime: String): Boolean = RUNTIME_FAST_MODE_RUNTIMES.contains(runtime)

fun supportsRuntimeReasoningEffort(runtime: String): Boolean = REASONING_EFFORT_RUNTIMES.contains(runtime)

// ── Name validation ──

const val NAME_MIN_LENGTH: Int = 1
const val NAME_MAX_LENGTH: Int = 32
val RESERVED_AGENT_NAMES: List<String> = listOf(
    "all", "human", "humans", "agent", "agents", "here", "idle", "busy", "system"
)

fun isReservedAgentName(name: String): Boolean {
    val normalized = name.trim().removePrefix("@").lowercase()
    return normalized in RESERVED_AGENT_NAMES
}

fun validateAgentName(name: String, label: String = "Agent name"): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "$label is required"
    if (isReservedAgentName(trimmed)) {
        return "$label @${trimmed.removePrefix("@").lowercase()} is reserved. Choose another name."
    }
    if (trimmed.length < NAME_MIN_LENGTH) {
        return "$label must be at least $NAME_MIN_LENGTH characters"
    }
    if (trimmed.length > NAME_MAX_LENGTH) {
        return "$label must be at most $NAME_MAX_LENGTH characters"
    }
    if (!trimmed.isValidAgentNameCharacters()) {
        return "$label must start with a letter and can only contain letters, numbers, hyphens, and underscores"
    }
    return null
}

// ── Server plans ──

private fun String.isValidAgentNameCharacters(): Boolean {
    val first = firstOrNull() ?: return false
    if (!first.isLetter()) return false
    return drop(1).all { char ->
        char.isLetterOrDigit() || char == '_' || char == '-'
    }
}

@Serializable
enum class ServerPlan { free, founder }

data class PlanLimits(
    val maxMachines: Int,       // -1 = unlimited
    val maxAgents: Int,         // -1 = unlimited
    val maxChannels: Int,       // -1 = unlimited
    val messageHistoryDays: Int // -1 = unlimited
)

data class PlanConfig(
    val displayName: String,
    val limits: PlanLimits,
    val comingSoon: Boolean = false,
    val price: Double = 0.0
)

val PLAN_CONFIG: Map<ServerPlan, PlanConfig> = mapOf(
    ServerPlan.free to PlanConfig(
        displayName = "Free",
        limits = PlanLimits(maxMachines = 2, maxAgents = 5, maxChannels = 5, messageHistoryDays = 30)
    ),
    ServerPlan.founder to PlanConfig(
        displayName = "Founder",
        limits = PlanLimits(maxMachines = -1, maxAgents = -1, maxChannels = -1, messageHistoryDays = -1)
    )
)

/**
 * Mirrors `getEffectiveLimits(plan, now?)` from `slock-ai/shared`.
 * The KMP side skips the free-trial `isTrialActive` check for now (no trial state
 * surfaced into KMP); if @artin wants trial-aware limits later, plug it in here.
 */
fun getEffectiveLimits(plan: ServerPlan): PlanLimits =
    PLAN_CONFIG[plan]?.limits ?: PlanLimits(-1, -1, -1, -1)
