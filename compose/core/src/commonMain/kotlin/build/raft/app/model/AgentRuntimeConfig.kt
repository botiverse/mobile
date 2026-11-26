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

    /**
     * Built-in runtime bound to a saved provider connection (slock `BuiltInRuntimeProviderConfig`
     * `{kind:"connection", connectionId}`). The server resolves the credential; no secret here.
     */
    @Serializable
    @SerialName("connection")
    data class Connection(val connectionId: String) : RuntimeProviderConfig()
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

/**
 * Env var keys owned by each runtime's own launch plan / provider config
 * (mirrors slock `packages/shared/src/runtimeConfigEngine.ts` `CONTROLLED_RUNTIME_ENV_KEYS`
 * + `piBuiltinModels.generated.ts` `BUILTIN_RUNTIME_HOST_PROVIDER_ENV_SCRUB_KEYS`).
 */
val BUILTIN_RUNTIME_CONTROLLED_ENV_KEYS: Set<String> = setOf(
    "DEEPSEEK_API_KEY",
    "MINIMAX_API_KEY",
    "MINIMAX_CN_API_KEY",
    "ZAI_API_KEY",
    "ZAI_CODING_CN_API_KEY",
    "MOONSHOT_API_KEY",
    "KIMI_API_KEY",
    "QWEN_TOKEN_PLAN_API_KEY",
    "QWEN_TOKEN_PLAN_CN_API_KEY",
    "QWEN_TOKEN_PLAN_TEAM_API_KEY",
    "QWEN_TOKEN_PLAN_TEAM_CN_API_KEY",
    "QWEN_TOKEN_PLAN_INDIVIDUAL_API_KEY",
    "QWEN_TOKEN_PLAN_INDIVIDUAL_CN_API_KEY",
    "OPENROUTER_API_KEY",
    "OPENAI_API_KEY",
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_OAUTH_TOKEN",
    "GEMINI_API_KEY",
    "XAI_API_KEY",
    "XIAOMI_API_KEY",
    "XIAOMI_TOKEN_PLAN_CN_API_KEY",
    "XIAOMI_TOKEN_PLAN_SGP_API_KEY",
    "XIAOMI_TOKEN_PLAN_AMS_API_KEY",
    "ANT_LING_API_KEY",
    "BASETEN_API_KEY",
    "CEREBRAS_API_KEY",
    "FIREWORKS_API_KEY",
    "GROQ_API_KEY",
    "META_API_KEY",
    "MISTRAL_API_KEY",
    "NVIDIA_API_KEY",
    "OPENCODE_API_KEY",
    "TOGETHER_API_KEY",
    "AI_GATEWAY_API_KEY",
    "OPENAI_BASE_URL",
    "ANTHROPIC_BASE_URL",
)

val CONTROLLED_RUNTIME_ENV_KEYS: Map<String, Set<String>> = mapOf(
    "builtin" to BUILTIN_RUNTIME_CONTROLLED_ENV_KEYS,
    "claude" to setOf("ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY", "ANTHROPIC_CUSTOM_MODEL_OPTION"),
    "codex" to setOf("OPENAI_BASE_URL"),
    "pi" to setOf("DEEPSEEK_API_KEY"),
)

/**
 * Mirrors slock `isControlledRuntimeEnvKey` (runtimeConfigEngine.ts): case-insensitive check
 * because Windows reads env var names case-insensitively.
 */
fun isControlledRuntimeEnvKey(runtime: String, key: String): Boolean {
    val controlled = CONTROLLED_RUNTIME_ENV_KEYS[runtime] ?: return false
    return key.uppercase() in controlled
}

/**
 * Mirrors slock `stripControlledRuntimeEnvVars` (runtimeConfigEngine.ts): trims keys,
 * drops blank keys and keys controlled by [runtime], preserving insertion order.
 */
fun stripControlledRuntimeEnvVars(runtime: String, envVars: Map<String, String>?): Map<String, String>? {
    if (envVars.isNullOrEmpty()) return null
    val normalized = LinkedHashMap<String, String>()
    for ((rawKey, value) in envVars) {
        val key = rawKey.trim()
        if (key.isEmpty()) continue
        if (isControlledRuntimeEnvKey(runtime, key)) continue
        normalized[key] = value
    }
    return normalized.takeIf { it.isNotEmpty() }
}

// ── Runtimes ──

/**
 * The bundled runtime table: the last resort after the server catalog
 * (`GET /api/servers/:id/runtimes`, shared `ServerRuntimeCatalog`) and its per-server cache.
 * Mirrors slock `packages/shared/src/runtimeCatalog.ts` `RUNTIMES` at origin/staging 114dcb89eb
 * (Antigravity no longer deprecated, slock #15445; DeepSeek Harness added, slock #15549).
 * [displayName] is the bare product name; a label that names a lifecycle status adds it
 * (" (deprecated)") from the app's strings. [experimental] is kept as catalog data only: no label
 * shows it (slock #15551).
 */
data class RuntimeInfo(
    val id: String,
    val displayName: String,
    val binary: String,
    val supported: Boolean,
    val deprecated: Boolean = false,
    val abbreviation: String = "",
    val experimental: Boolean = false
)

val RUNTIMES: List<RuntimeInfo> = listOf(
    RuntimeInfo(id = "claude", displayName = "Claude Code", abbreviation = "CC", binary = "claude", supported = true),
    RuntimeInfo(id = "codex", displayName = "Codex CLI", abbreviation = "CX", binary = "codex", supported = true),
    RuntimeInfo(id = "grok", displayName = "Grok Build", abbreviation = "GK", binary = "grok", supported = true),
    RuntimeInfo(id = "builtin", displayName = "Built-in Pi", abbreviation = "BP", binary = "", supported = true),
    RuntimeInfo(id = "antigravity", displayName = "Antigravity CLI", abbreviation = "AG", binary = "agy", supported = true, experimental = true),
    RuntimeInfo(id = "kimi-sdk", displayName = "Kimi Code", abbreviation = "KC", binary = "", supported = true),
    RuntimeInfo(id = "kimi", displayName = "Kimi CLI", abbreviation = "KL", binary = "kimi", supported = true, deprecated = true),
    RuntimeInfo(id = "copilot", displayName = "Copilot CLI", abbreviation = "CP", binary = "copilot", supported = true, deprecated = true),
    RuntimeInfo(id = "cursor", displayName = "Cursor CLI", abbreviation = "CU", binary = "cursor-agent", supported = true),
    RuntimeInfo(id = "gemini", displayName = "Gemini CLI", abbreviation = "GM", binary = "gemini", supported = true, deprecated = true),
    RuntimeInfo(id = "opencode", displayName = "OpenCode", abbreviation = "OC", binary = "opencode", supported = true),
    RuntimeInfo(id = "pi", displayName = "Pi", abbreviation = "PI", binary = "pi", supported = true),
    RuntimeInfo(id = "qwen", displayName = "Qwen Code", abbreviation = "QW", binary = "qwen", supported = true, experimental = true),
    RuntimeInfo(id = "qoder", displayName = "Qoder", abbreviation = "QD", binary = "qodercli", supported = true, experimental = true),
    RuntimeInfo(id = "dsh", displayName = "DeepSeek Harness", abbreviation = "DS", binary = "dsh", supported = true, experimental = true)
)

/** The bundled table's name for [id], else [id] itself. Shared code goes through the catalog first. */
fun getRuntimeDisplayName(id: String): String =
    RUNTIMES.firstOrNull { it.id == id }?.displayName ?: id

/**
 * Retired runtimes (shared `RETIRED_RUNTIME_IDS`, slock #16830): deprecated, and existing agents on
 * them no longer start. The server refuses every start before dispatch with `runtime_retired`.
 */
val RETIRED_RUNTIME_IDS: List<String> = listOf("kimi", "gemini", "copilot")

/** Shared `isRuntimeRetired`: whether [runtimeId] is a retired runtime. */
fun isRuntimeRetired(runtimeId: String?): Boolean =
    runtimeId?.trim()?.takeIf { it.isNotEmpty() } in RETIRED_RUNTIME_IDS

/** The runtime each retired one's copy suggests switching to; null means any other runtime. */
val RETIRED_RUNTIME_REPLACEMENTS: Map<String, String?> = mapOf(
    "kimi" to "kimi-sdk",
    "gemini" to "antigravity",
    "copilot" to null,
)

/**
 * Shared `retiredRuntimeNeedsComputerUpdate` (slock #16830): whether the agent's Computer is too
 * old to offer the retired runtime's suggested replacement, so updating the Computer has to come
 * first. An in-process replacement (`binary == ""`, e.g. Kimi Code) absent from [machineRuntimeIds]
 * means a Computer that predates it; a CLI replacement may just not be installed (false). Null or
 * empty [machineRuntimeIds] (no Computer or none reported) stays undecided (false).
 */
fun retiredRuntimeNeedsComputerUpdate(runtimeId: String?, machineRuntimeIds: List<String>?): Boolean {
    val normalized = runtimeId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (!isRuntimeRetired(normalized)) return false
    val replacement = RETIRED_RUNTIME_REPLACEMENTS[normalized] ?: return false
    val info = RUNTIMES.firstOrNull { it.id == replacement } ?: return false
    if (machineRuntimeIds.isNullOrEmpty()) return false
    return info.binary.isEmpty() && info.id !in machineRuntimeIds
}

// ── Reasoning effort ──

/**
 * Shared `REASONING_EFFORT_RUNTIMES` without `kimi-sdk` (its effort follows the live model's
 * capability, see MembersPage). Antigravity's is agy's thought level, and DeepSeek Harness's its
 * model's reasoning effort, each offered only for a model whose levels the Computer lists
 * (slock #14619, #15549).
 */
val REASONING_EFFORT_RUNTIMES: Set<String> = setOf("builtin", "claude", "codex", "grok", "antigravity", "copilot", "pi", "dsh")
val RUNTIME_FAST_MODE_RUNTIMES: Set<String> = setOf("claude", "codex")

// ── Runtime models ──

data class RuntimeModelInfo(
    val id: String,
    val label: String,
    val verified: String? = null, // "launchable" | "suggestion_only" | null
    /**
     * The reasoning effort ids this model accepts (shared `RuntimeModelInfo`, slock #15162): null
     * when not reported (readers fall back to the catalog or the runtime's base set); an empty list
     * when the model takes no reasoning effort (no fallback, nothing offered).
     */
    val supportedReasoningEfforts: List<String>? = null,
    val defaultReasoningEffort: String? = null
)

data class RuntimeModelPresentation(
    val models: List<RuntimeModelInfo>,
    val defaultModel: String?
)

private val CLAUDE_REASONING_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

/**
 * The bundled model table: the fallback when neither the Computer nor the server catalog lists a
 * runtime's models. Mirrors slock `packages/shared/src/index.ts` `RUNTIME_MODELS` at origin/staging
 * 0b369d1aef: the same runtimes, rows, labels, order (the first row is [getDefaultModel]) and
 * reasoning metadata.
 */
val RUNTIME_MODELS: Map<String, List<RuntimeModelInfo>> = mapOf(
    // Shared `EXTERNAL_AGENT_RUNTIME_ID` / `EXTERNAL_AGENT_RUNTIME_MODEL` / display name.
    "external" to listOf(
        RuntimeModelInfo("external", "External agent")
    ),
    "builtin" to PI_BUILTIN_RUNTIME_MODELS,
    // Same rows and order as slock packages/shared RUNTIME_MODELS.claude (the fallback when the
    // Computer's own catalog is unavailable).
    "claude" to listOf(
        RuntimeModelInfo("opus", "Claude Opus", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("fable", "Claude Fable", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("sonnet", "Claude Sonnet", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("haiku", "Claude Haiku"),
        RuntimeModelInfo("claude-opus-5-5", "Claude Opus 5.5", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-opus-5", "Claude Opus 5", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-opus-4-8", "Claude Opus 4.8", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-opus-4-7", "Claude Opus 4.7", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-opus-4-6", "Claude Opus 4.6", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-fable-5-2", "Claude Fable 5.2", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-fable-5-1", "Claude Fable 5.1", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-fable-5", "Claude Fable 5", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-sonnet-5-5", "Claude Sonnet 5.5", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-sonnet-5", "Claude Sonnet 5", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-haiku-5-5", "Claude Haiku 5.5", supportedReasoningEfforts = CLAUDE_REASONING_EFFORTS),
        RuntimeModelInfo("claude-haiku-4-5", "Claude Haiku 4.5")
    ),
    // Order and per-model reasoning metadata follow upstream Codex's bundled catalog
    // (codex-rs/models-manager/models.json, rust-v0.162.0), as slock's.
    "codex" to listOf(
        RuntimeModelInfo(
            "gpt-6.1-sol",
            "GPT-6.1 Sol",
            verified = "launchable",
            supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
            defaultReasoningEffort = "low"
        ),
        RuntimeModelInfo(
            "gpt-6-astra",
            "GPT-6-Astra",
            verified = "launchable",
            supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
            defaultReasoningEffort = "low"
        ),
        RuntimeModelInfo(
            "gpt-6-sol",
            "GPT-6 Sol",
            verified = "launchable",
            supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
            defaultReasoningEffort = "medium"
        ),
        RuntimeModelInfo(
            "gpt-6-luna",
            "GPT-6 Luna",
            verified = "launchable",
            supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max"),
            defaultReasoningEffort = "medium"
        ),
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
    "grok" to listOf(
        RuntimeModelInfo(
            "grok-4.5",
            "Grok 4.5",
            verified = "launchable",
            supportedReasoningEfforts = listOf("high", "medium", "low"),
            defaultReasoningEffort = "high"
        ),
        // A new choice without changing the bundled default; live detection owns availability.
        RuntimeModelInfo("grok-4.6", "Grok 4.6", verified = "suggestion_only"),
        RuntimeModelInfo("grok-composer-2.5-fast", "Composer 2.5", verified = "launchable")
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
        // slock `formatRuntimeProviderModelLabel(id)`.
        RuntimeModelInfo("deepseek/deepseek-v4-pro", "DeepSeek V4 Pro · DeepSeek", verified = "suggestion_only"),
        RuntimeModelInfo("openrouter/anthropic/claude-opus-4.5", "Claude Opus 4.5 · Anthropic via OpenRouter", verified = "suggestion_only"),
        RuntimeModelInfo("fusecode/opus[1m]", "Opus 1M · FuseCode", verified = "suggestion_only")
    ),
    "pi" to listOf(
        RuntimeModelInfo("default", "Configured Default / Auto", verified = "suggestion_only")
    ),
    // Kimi CLI resolves model keys from each user's local config: defer to its default model.
    "kimi" to listOf(
        RuntimeModelInfo("default", "Configured Default")
    ),
    // Kimi Code's live list comes from the Computer; this one entry is its managed default, and keeps
    // getDefaultModel("kimi-sdk") from falling through to Claude's "sonnet".
    "kimi-sdk" to listOf(
        RuntimeModelInfo("kimi-code/kimi-for-coding", "Kimi for Coding (default)", verified = "launchable")
    )
)

fun getDefaultModel(runtimeId: String): String =
    RUNTIME_MODELS[runtimeId]?.firstOrNull()?.id ?: "sonnet"

fun getModelLabel(runtimeId: String, modelId: String): String =
    RUNTIME_MODELS[runtimeId]?.firstOrNull { it.id == modelId }?.label ?: modelId

/**
 * Mirrors React `canonicalizeCodexPresentation`.
 *
 * Codex hosts can report an older machine default (`gpt-5.5`) first even when
 * the Raft presets are available. Keep live-only models, but present known
 * Codex presets in the shared catalog order and prefer the bundled default
 * ([getDefaultModel], GPT-6.1 Sol) only when the selected machine actually
 * surfaced it; else the machine default, else the first ordered model. Other runtimes keep the
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

/**
 * Shared `runtimeOffersUndeclaredReasoningEfforts` (slock #14619): whether a model that declares no
 * efforts still offers the runtime's base set in the pickers. Antigravity does not: agy can only
 * apply a level the Computer listed for that model. Nor does DeepSeek Harness (slock #15549): OAR
 * lists each model's own efforts and dsh refuses one it does not support.
 */
fun runtimeOffersUndeclaredReasoningEfforts(runtime: String): Boolean = runtime != "antigravity" && runtime != "dsh"

/**
 * Shared `runtimeChoiceModel` (slock #14619): the model entry meaning "the runtime chooses" (no
 * model is passed), Antigravity's `default` ("AGY configured default"). Model pickers keep it first
 * and preselect it.
 */
fun runtimeChoiceModel(runtime: String): RuntimeModelInfo? =
    if (runtime == "antigravity") RUNTIME_MODELS["antigravity"]?.firstOrNull { it.id == "default" } else null

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
