package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedApiEnvironmentStore(
    private val store: RaftKeyValueStore
) {
    private val writeLock = SynchronizedObject()
    private val mutableState = MutableStateFlow(currentEnvironment(store.getString(KEY_ENVIRONMENT)))
    val state: StateFlow<SharedSettingsApiEnvironmentOption> = mutableState.asStateFlow()

    /**
     * Returns the cached active API environment without hitting backing key-value storage.
     * Safe to call on UI / Composable hot paths (e.g. avatar URL resolution during scroll flings).
     */
    fun current(): SharedSettingsApiEnvironmentOption = mutableState.value

    /** Re-reads the persisted API environment from backing storage and refreshes [state]. */
    fun reload(): SharedSettingsApiEnvironmentOption =
        synchronized(writeLock) {
            currentEnvironment(store.getString(KEY_ENVIRONMENT)).also { mutableState.value = it }
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

    fun select(key: String): SharedSettingsApiEnvironmentOption? =
        synchronized(writeLock) {
            val environment = predefined.firstOrNull { it.key == key } ?: return@synchronized null
            store.putString(KEY_ENVIRONMENT, environment.key)
            mutableState.value = environment
            environment
        }

    fun save(request: SharedSettingsApiEnvironmentRequest): SharedSettingsApiEnvironmentOption? =
        synchronized(writeLock) {
            predefined.firstOrNull { it.key == request.key }?.let { environment ->
                store.putString(KEY_ENVIRONMENT, environment.key)
                mutableState.value = environment
                return@synchronized environment
            }
            val apiBaseUrl = request.apiBaseUrl.trim()
            val socketBaseUrl = request.socketBaseUrl.trim()
            val webUrl = request.webUrl.trim()
            if (!apiBaseUrl.startsWith("http") || !socketBaseUrl.startsWith("http")) return@synchronized null
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
            mutableState.value = custom
            custom
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
                apiBaseUrl = RAFT_API_BASE_URL,
                socketBaseUrl = RAFT_SOCKET_BASE_URL,
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
