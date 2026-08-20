package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedApiEnvironmentStore(
    private val store: SlockKeyValueStore
) {
    private val mutableState = MutableStateFlow(currentEnvironment(store.getString(KEY_ENVIRONMENT)))
    private var observedEnvironment = mutableState.value
    /**
     * Persisted environment authority revision. This is deliberately not a process-local
     * counter: OAuth invite callbacks may complete after a cold process restart, and an A→B→A
     * cycle must not reuse the old A witness.
     */
    private var environmentGeneration =
        store.getString(KEY_ENVIRONMENT_GENERATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    init {
        // Make the persisted witness explicit even for a first-run production environment.
        if (store.getString(KEY_ENVIRONMENT_GENERATION) == null) {
            store.putString(KEY_ENVIRONMENT_GENERATION, environmentGeneration.toString())
        }
    }

    val state: StateFlow<SharedSettingsApiEnvironmentOption> = mutableState.asStateFlow()

    fun current(): SharedSettingsApiEnvironmentOption =
        observe(currentEnvironment(store.getString(KEY_ENVIRONMENT)))

    /**
     * Captures the environment identity and its in-process generation as one witness.
     * Invite requests must carry this witness through queueing and executor dispatch; reading
     * the mutable base URL separately at dispatch time would reopen a production→custom race.
     */
    internal fun currentSnapshot(): SharedApiEnvironmentSnapshot {
        val environment = current()
        return SharedApiEnvironmentSnapshot(
            key = environment.key,
            apiBaseUrl = environment.apiBaseUrl,
            generation = environmentGeneration,
        )
    }

    fun developerSnapshot(feedback: String? = null): SharedSettingsDeveloperSnapshot {
        val current = current()
        return SharedSettingsDeveloperSnapshot(
            environmentKey = current.key,
            environmentLabel = current.label,
            apiBaseUrl = current.apiBaseUrl,
            socketBaseUrl = current.socketBaseUrl,
            webUrl = current.webUrl,
            options = predefined,
            feedback = feedback
        )
    }

    fun select(key: String): SharedSettingsApiEnvironmentOption? {
        val environment = predefined.firstOrNull { it.key == key } ?: return null
        return persistEnvironment(environment) {
            store.putString(KEY_ENVIRONMENT, environment.key)
        }
    }

    fun save(request: SharedSettingsApiEnvironmentRequest): SharedSettingsApiEnvironmentOption? {
        predefined.firstOrNull { it.key == request.key }?.let { environment ->
            return persistEnvironment(environment) {
                store.putString(KEY_ENVIRONMENT, environment.key)
            }
        }
        val apiBaseUrl = request.apiBaseUrl.trim()
        val socketBaseUrl = request.socketBaseUrl.trim()
        val webUrl = request.webUrl.trim()
        if (!apiBaseUrl.startsWith("http") || !socketBaseUrl.startsWith("http")) return null
        val custom =
            SharedSettingsApiEnvironmentOption(
                key = CustomKey,
                label = request.label.trim().ifBlank { "Custom" },
                apiBaseUrl = apiBaseUrl,
                socketBaseUrl = socketBaseUrl,
                webUrl = webUrl
            )
        return persistEnvironment(custom) {
            store.putString(KEY_CUSTOM_LABEL, custom.label)
            store.putString(KEY_CUSTOM_API_BASE_URL, custom.apiBaseUrl)
            store.putString(KEY_CUSTOM_SOCKET_BASE_URL, custom.socketBaseUrl)
            store.putString(KEY_CUSTOM_WEB_URL, custom.webUrl)
            store.putString(KEY_ENVIRONMENT, CustomKey)
        }
    }

    private fun observe(environment: SharedSettingsApiEnvironmentOption): SharedSettingsApiEnvironmentOption {
        if (environment != observedEnvironment) {
            advanceEnvironmentGeneration()
            observedEnvironment = environment
        }
        mutableState.value = environment
        return environment
    }

    /** Writes the revision before the environment selector so a torn update fails closed. */
    private fun persistEnvironment(
        environment: SharedSettingsApiEnvironmentOption,
        writeEnvironment: () -> Unit,
    ): SharedSettingsApiEnvironmentOption {
        if (environment != observedEnvironment) {
            advanceEnvironmentGeneration()
        }
        writeEnvironment()
        observedEnvironment = environment
        mutableState.value = environment
        return environment
    }

    private fun advanceEnvironmentGeneration() {
        if (environmentGeneration < Long.MAX_VALUE) {
            environmentGeneration += 1L
        }
        store.putString(KEY_ENVIRONMENT_GENERATION, environmentGeneration.toString())
    }

    fun rewriteApiUrl(url: String): String {
        val current = current()
        val productionBase = Production.apiBaseUrl
        if (current.apiBaseUrl == productionBase) return url
        return if (url.startsWith(productionBase)) {
            current.apiBaseUrl.trimEnd('/') + "/" + url.removePrefix(productionBase).trimStart('/')
        } else {
            url
        }
    }

    fun resolveSocketUrl(socketUrl: String): String {
        val requested = socketUrl.trim()
        val productionSocket = Production.socketBaseUrl.trimEnd('/')
        return if (requested.isBlank() || requested.trimEnd('/') == productionSocket) {
            current().socketBaseUrl
        } else {
            requested
        }
    }

    private fun currentEnvironment(key: String?): SharedSettingsApiEnvironmentOption {
        predefined.firstOrNull { it.key == key }?.let { return it }
        if (key == CustomKey) {
            val apiBaseUrl = store.getString(KEY_CUSTOM_API_BASE_URL).orEmpty()
            val socketBaseUrl = store.getString(KEY_CUSTOM_SOCKET_BASE_URL).orEmpty()
            if (apiBaseUrl.startsWith("http") && socketBaseUrl.startsWith("http")) {
                return SharedSettingsApiEnvironmentOption(
                    key = CustomKey,
                    label = store.getString(KEY_CUSTOM_LABEL).orEmpty().ifBlank { "Custom" },
                    apiBaseUrl = apiBaseUrl,
                    socketBaseUrl = socketBaseUrl,
                    webUrl = store.getString(KEY_CUSTOM_WEB_URL).orEmpty()
                )
            }
        }
        return Production
    }

    companion object {
        const val Namespace = "slock_api_server_environment"
        const val CustomKey = "custom"

        val Production =
            SharedSettingsApiEnvironmentOption(
                key = "production",
                label = "Production",
                apiBaseUrl = SLOCK_API_BASE_URL,
                socketBaseUrl = SLOCK_SOCKET_BASE_URL,
                webUrl = "https://app.raft.build"
            )

        val Staging =
            SharedSettingsApiEnvironmentOption(
                key = "staging",
                label = "Staging",
                apiBaseUrl = "https://api-aws-staging.botiverse.dev/api/",
                socketBaseUrl = "https://api-aws-staging.botiverse.dev",
                webUrl = "https://raft-app-staging.botiverse.dev"
            )

        val predefined: List<SharedSettingsApiEnvironmentOption> = listOf(Production, Staging)

        private const val KEY_ENVIRONMENT = "environment"
        private const val KEY_CUSTOM_LABEL = "custom_label"
        private const val KEY_CUSTOM_API_BASE_URL = "custom_api_base_url"
        private const val KEY_CUSTOM_SOCKET_BASE_URL = "custom_socket_base_url"
        private const val KEY_CUSTOM_WEB_URL = "custom_web_url"
        private const val KEY_ENVIRONMENT_GENERATION = "environment_generation"
    }
}

/** Environment identity and persisted authority revision; never log it with capability tokens. */
data class SharedApiEnvironmentSnapshot(
    val key: String,
    val apiBaseUrl: String,
    val generation: Long,
)
