package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedApiEnvironmentStore(
    private val store: SlockKeyValueStore
) {
    private val mutableState = MutableStateFlow(currentEnvironment(store.getString(KEY_ENVIRONMENT)))
    private var observedEnvironment = mutableState.value
    private var environmentGeneration = 0L
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
        store.putString(KEY_ENVIRONMENT, environment.key)
        return observe(environment)
    }

    fun save(request: SharedSettingsApiEnvironmentRequest): SharedSettingsApiEnvironmentOption? {
        predefined.firstOrNull { it.key == request.key }?.let { environment ->
            store.putString(KEY_ENVIRONMENT, environment.key)
            return observe(environment)
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
        store.putString(KEY_ENVIRONMENT, CustomKey)
        store.putString(KEY_CUSTOM_LABEL, custom.label)
        store.putString(KEY_CUSTOM_API_BASE_URL, custom.apiBaseUrl)
        store.putString(KEY_CUSTOM_SOCKET_BASE_URL, custom.socketBaseUrl)
        store.putString(KEY_CUSTOM_WEB_URL, custom.webUrl)
        return observe(custom)
    }

    private fun observe(environment: SharedSettingsApiEnvironmentOption): SharedSettingsApiEnvironmentOption {
        if (environment != observedEnvironment) {
            environmentGeneration += 1L
            observedEnvironment = environment
        }
        mutableState.value = environment
        return environment
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
    }
}

/** Runtime-only environment witness; never persist or log it with capability tokens. */
data class SharedApiEnvironmentSnapshot(
    val key: String,
    val apiBaseUrl: String,
    val generation: Long,
)
