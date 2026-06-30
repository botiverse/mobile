package build.raft.app.core.data.auth

import build.raft.app.shared.SlockKuiklyMMKVModule
import ai.slock.compose.data.SLOCK_AUTH_SESSION_MMKV_NAMESPACE
import ai.slock.compose.data.SlockKeyValueStore
import android.content.Context

class AndroidMMKVKeyValueStore(
    context: Context,
    private val namespace: String
) : SlockKeyValueStore {
    private val appContext = context.applicationContext
    private val mmapID = namespace

    override fun getString(key: String): String? {
        val namespacedKey = namespaced(key)
        return if (SlockKuiklyMMKVModule.containsKey(appContext, namespacedKey, mmapID)) {
            SlockKuiklyMMKVModule.decodeString(appContext, namespacedKey, mmapID)
        } else {
            null
        }
    }

    override fun putString(key: String, value: String?) {
        val namespacedKey = namespaced(key)
        if (value == null) {
            SlockKuiklyMMKVModule.removeValueForKey(appContext, namespacedKey, mmapID)
        } else {
            SlockKuiklyMMKVModule.encodeString(appContext, namespacedKey, value, mmapID)
        }
    }

    private fun namespaced(key: String): String = "$namespace:$key"
}

fun androidMMKVAuthKeyValueStore(context: Context): SlockKeyValueStore =
    AndroidMMKVKeyValueStore(context, SLOCK_AUTH_SESSION_MMKV_NAMESPACE)
