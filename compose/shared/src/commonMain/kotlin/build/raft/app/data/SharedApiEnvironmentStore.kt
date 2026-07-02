package build.raft.app.data

class SharedApiEnvironmentStore(
    private val store: SlockKeyValueStore
) {
    fun current(): SharedSettingsApiEnvironmentOption =
        currentEnvironment(store.getString(KEY_ENVIRONMENT))

    fun developerSnapshot(feedback: String? = null): SharedSettingsDeveloperSnapshot {
        val current = current()
        return SharedSettingsDeveloperSnapshot(
            environmentKey = current.key,
            environmentLabel = current.label,
            apiBaseUrl = current.apiBaseUrl,
            socketBaseUrl = current.socketBaseUrl,
            webUrl = current.webUrl,
            sharedSocketIoTransportEnabled = sharedSocketIoTransportEnabled(),
            options = predefined,
            feedback = feedback
        )
    }

    fun sharedSocketIoTransportEnabled(): Boolean =
        store.getBoolean(KEY_SHARED_SOCKET_IO_TRANSPORT, defaultValue = false)

    fun setSharedSocketIoTransportEnabled(enabled: Boolean): SharedSettingsDeveloperSnapshot {
        store.putBoolean(KEY_SHARED_SOCKET_IO_TRANSPORT, enabled)
        return developerSnapshot(
            feedback =
                if (enabled) {
                    "Shared Socket.IO transport flag enabled. Android and iOS reconnects will use the shared Ktor pilot; OHOS keeps its native realtime host until async KNOI event callbacks are wired."
                } else {
                    "Shared Socket.IO transport disabled. Realtime reconnects will use the platform transport."
                }
        )
    }

    fun select(key: String): SharedSettingsApiEnvironmentOption? {
        val environment = predefined.firstOrNull { it.key == key } ?: return null
        store.putString(KEY_ENVIRONMENT, environment.key)
        return environment
    }

    fun save(request: SharedSettingsApiEnvironmentRequest): SharedSettingsApiEnvironmentOption? {
        predefined.firstOrNull { it.key == request.key }?.let { environment ->
            store.putString(KEY_ENVIRONMENT, environment.key)
            return environment
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
        return custom
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
                webUrl = "https://raft-app-staging.botiverse.dev/s/botiverse/"
            )

        val predefined: List<SharedSettingsApiEnvironmentOption> = listOf(Production, Staging)

        private const val KEY_ENVIRONMENT = "environment"
        private const val KEY_CUSTOM_LABEL = "custom_label"
        private const val KEY_CUSTOM_API_BASE_URL = "custom_api_base_url"
        private const val KEY_CUSTOM_SOCKET_BASE_URL = "custom_socket_base_url"
        private const val KEY_CUSTOM_WEB_URL = "custom_web_url"
        const val KEY_SHARED_SOCKET_IO_TRANSPORT = "shared_socket_io_transport_enabled"
    }
}
