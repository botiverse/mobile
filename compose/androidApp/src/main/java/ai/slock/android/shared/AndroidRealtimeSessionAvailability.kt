package ai.slock.android.shared

import android.content.Context
import ai.slock.android.core.data.auth.androidMMKVAuthKeyValueStore
import ai.slock.compose.data.KuiklyMMKVAuthSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal object AndroidRealtimeSessionAvailability {
    fun hasStoredRealtimeSession(context: Context): Boolean =
        runCatching {
            val appContext = context.applicationContext
            runBlocking(Dispatchers.IO) {
                KuiklyMMKVAuthSessionStore(androidMMKVAuthKeyValueStore(appContext))
                    .readSession()
                    ?.let { session ->
                        !session.accessToken.isNullOrBlank() && !session.currentServerId.isNullOrBlank()
                    } == true
            }
        }.getOrDefault(false)
}
