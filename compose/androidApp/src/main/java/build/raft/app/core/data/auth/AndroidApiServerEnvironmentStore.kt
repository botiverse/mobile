package build.raft.app.core.data.auth

import android.content.Context
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import build.raft.app.data.SharedSettingsApiEnvironmentRequest

data class AndroidApiServerEnvironment(
    val key: String,
    val label: String,
    val apiBaseUrl: String,
    val socketBaseUrl: String,
    val webUrl: String
)

class AndroidApiServerEnvironmentStore(context: Context) {
    private val sharedStore =
        SharedApiEnvironmentStore(
            AndroidMMKVKeyValueStore(context.applicationContext, SharedApiEnvironmentStore.Namespace)
        )

    fun current(): AndroidApiServerEnvironment =
        sharedStore.current().toAndroidEnvironment()

    fun select(key: String): AndroidApiServerEnvironment? {
        return sharedStore.select(key)?.toAndroidEnvironment()
    }

    fun save(environment: AndroidApiServerEnvironment): AndroidApiServerEnvironment? {
        return sharedStore.save(environment.toSharedRequest())?.toAndroidEnvironment()
    }

    fun rewriteApiUrl(url: String): String =
        sharedStore.rewriteApiUrl(url)

    companion object {
        const val CustomKey = SharedApiEnvironmentStore.CustomKey

        val Production: AndroidApiServerEnvironment =
            SharedApiEnvironmentStore.Production.toAndroidEnvironment()

        val Staging: AndroidApiServerEnvironment =
            SharedApiEnvironmentStore.Staging.toAndroidEnvironment()

        val predefined: List<AndroidApiServerEnvironment> =
            SharedApiEnvironmentStore.predefined.map { it.toAndroidEnvironment() }

        fun environmentForKey(key: String?): AndroidApiServerEnvironment =
            predefined.firstOrNull { it.key == key } ?: Production
    }
}

private fun AndroidApiServerEnvironment.toSharedRequest(): SharedSettingsApiEnvironmentRequest =
    SharedSettingsApiEnvironmentRequest(
        key = key,
        label = label,
        apiBaseUrl = apiBaseUrl,
        socketBaseUrl = socketBaseUrl,
        webUrl = webUrl
    )

private fun SharedSettingsApiEnvironmentOption.toAndroidEnvironment(): AndroidApiServerEnvironment =
    AndroidApiServerEnvironment(
        key = key,
        label = label,
        apiBaseUrl = apiBaseUrl,
        socketBaseUrl = socketBaseUrl,
        webUrl = webUrl
    )
