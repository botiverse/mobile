package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last create's Computer and runtime on a server (web `utils/createAgentLastConfig.ts`,
 * `CreateAgentLastConfig`). Only these two: the runtime's server form owns every other default.
 */
@Serializable
internal data class CreateAgentLastConfig(val machineId: String, val runtime: String)

/**
 * [CreateAgentLastConfig] per server on this device, written after a create succeeds and read when
 * the create dialog picks its default runtime ([pickCreateAgentRuntime]). Best effort like the web:
 * a blank server id, an unreadable value or a failing store reads as nothing and writes nothing.
 */
internal class CreateAgentLastConfigRepository(private val store: SharedPreferenceStore) {
    fun read(serverId: String?): CreateAgentLastConfig? {
        val key = key(serverId) ?: return null
        val raw = runCatching { store.getString(key) }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(CreateAgentLastConfig.serializer(), raw) }.getOrNull()?.takeIf { it.isValid() }
    }

    fun write(serverId: String?, config: CreateAgentLastConfig) {
        val key = key(serverId) ?: return
        val clean = CreateAgentLastConfig(config.machineId.trim(), config.runtime.trim()).takeIf { it.isValid() } ?: return
        runCatching { store.putString(key, json.encodeToString(CreateAgentLastConfig.serializer(), clean)) }
    }

    private fun key(serverId: String?): String? = serverId?.trim()?.takeIf { it.isNotEmpty() }?.let { "$KeyPrefix$it" }

    private fun CreateAgentLastConfig.isValid(): Boolean =
        machineId.isNotEmpty() && runtime.isNotEmpty() &&
            machineId.length <= MaxValueLength && runtime.length <= MaxValueLength

    companion object {
        /** The web's localStorage prefix, so the two read alike in a diagnostics dump. */
        const val KeyPrefix = "raft:create-agent:last-config:v1:"
        private const val MaxValueLength = 512
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** web `ONBOARDING_RUNTIME_PRIORITY`. */
internal val CreateAgentOnboardingRuntimePriority: List<String> = listOf("claude", "codex", "grok", "builtin")

/**
 * The runtime a new create dialog starts on (web CreateAgentDialog `pickCreateAgentRuntime`,
 * botiverse/slock staging 6d6d78d4c4). [candidates] are the selected computer's runtime-options
 * rows the server lets this person pick here (`canSelectInThisContext`, [createAgentRuntimeCandidates]):
 * installed and allowed, so a "not installed" or "needs a Raft Computer update" runtime is never one.
 * [supported] is the creatable set from the catalog: a runtime this app has a form for. Onboarding
 * goes by [CreateAgentOnboardingRuntimePriority]; otherwise the [remembered] runtime of the last
 * create while it is still a candidate, else the first candidate. Unlike the web, a candidate this
 * app cannot set up is never the default: no usable candidate is "" (nothing selected).
 */
internal fun pickCreateAgentRuntime(
    candidates: List<String>,
    supported: Set<String>,
    onboarding: Boolean,
    remembered: String?,
): String {
    if (onboarding) {
        return CreateAgentOnboardingRuntimePriority.firstOrNull { it in candidates && it in supported }
            ?: candidates.firstOrNull { it in supported }
            ?: ""
    }
    if (remembered != null && remembered in candidates && remembered in supported) return remembered
    return candidates.firstOrNull { it in supported } ?: ""
}

/**
 * The create dialog's runtime once [options] (runtime-options of a computer) are known for the
 * selected [machineId]. Options still loading, or answered for another computer, change nothing:
 * the dialog clears the runtime when the computer changes, so a previous computer's rows never
 * pick one. Loaded options keep [current] while it is a usable candidate (offered here and in
 * [supported]); else [pickCreateAgentRuntime]; no usable candidate is "".
 */
internal fun createAgentRuntimeForOptions(
    current: String,
    machineId: String?,
    options: SharedCreateAgentRuntimeCatalog?,
    supported: Set<String>,
    remembered: String?,
    onboarding: Boolean = false,
): String {
    if (machineId == null || options == null || options.machineId != machineId) return current
    val usable = createAgentRuntimeCandidates(options).filter { it in supported }
    if (current in usable) return current
    return pickCreateAgentRuntime(usable, supported, onboarding, remembered)
}

/** The runtimes a create may default to: the runtime-options rows with `canSelectInThisContext`. */
internal fun createAgentRuntimeCandidates(catalog: SharedCreateAgentRuntimeCatalog?): List<String> =
    catalog?.options.orEmpty()
        .filter { it.canSelectInThisContext }
        .map { it.runtimeId.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
