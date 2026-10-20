package build.raft.app.core.data.auth

import android.content.Context
import build.raft.app.core.data.diagnostics.RaftAndroidLocalDiagnostics
import build.raft.app.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import build.raft.app.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RAFT_AUTH_SESSION_MMKV_NAMESPACE
import build.raft.app.data.RAFT_AUTH_SESSION_MMKV_STORAGE_KEY
import build.raft.app.data.RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE
import build.raft.app.data.RAFT_APP_LOGIN_MMKV_NAMESPACE
import build.raft.app.data.RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.RaftKeyValueStore
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.StoredAuthSession
import build.raft.app.shared.AndroidRealtimeKeepAliveController
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

class AndroidMMKVKeyValueStore(
    context: Context,
    private val namespace: String
) : RaftKeyValueStore {
    private val appContext = context.applicationContext
    private val mmapID = namespace

    override fun getString(key: String): String? {
        val namespacedKey = namespaced(key)
        return if (AndroidAppScopeMMKV.containsKey(appContext, namespacedKey, mmapID)) {
            AndroidAppScopeMMKV.decodeString(appContext, namespacedKey, mmapID)
        } else {
            null
        }
    }

    override fun putString(key: String, value: String?) {
        val namespacedKey = namespaced(key)
        if (value == null) {
            AndroidAppScopeMMKV.removeValueForKey(appContext, namespacedKey, mmapID)
        } else {
            AndroidAppScopeMMKV.encodeString(appContext, namespacedKey, value, mmapID)
        }
    }

    private fun namespaced(key: String): String = "$namespace:$key"
}

internal object AndroidAppScopeMMKV {
    private val gson = Gson()

    @Volatile
    private var initialized = false

    internal fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                MMKV.initialize(context.applicationContext)
                initialized = true
            }
        }
    }

    internal fun containsKey(context: Context, key: String, mmapID: String): Boolean {
        ensureInitialized(context)
        val result = MMKV.mmkvWithID(mmapID).containsKey(key)
        recordAuthKeyOperation(
            operation = "contains",
            mmapID = mmapID,
            key = key,
            result = result.toString()
        )
        return result
    }

    internal fun decodeString(context: Context, key: String, mmapID: String): String? {
        ensureInitialized(context)
        val result = MMKV.mmkvWithID(mmapID).decodeString(key, null)
        recordAuthKeyOperation(
            operation = "decode",
            mmapID = mmapID,
            key = key,
            result = if (result.isNullOrEmpty()) "empty" else "value",
            valueLength = result?.length
        )
        return result
    }

    internal fun encodeString(context: Context, key: String, value: String, mmapID: String) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        MMKV.mmkvWithID(mmapID).encode(key, value)
        recordAuthKeyOperation(
            operation = "encode",
            mmapID = mmapID,
            key = key,
            result = "success",
            valueLength = value.length
        )
        maybeUpdateAuthSessionContext(appContext, mmapID, key, value)
        maybeUpdateForegroundKeepAlive(appContext, mmapID, key, value)
    }

    internal fun removeValueForKey(context: Context, key: String, mmapID: String) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        MMKV.mmkvWithID(mmapID).removeValueForKey(key)
        recordAuthKeyOperation(
            operation = "remove",
            mmapID = mmapID,
            key = key,
            result = "success"
        )
        maybeUpdateAuthSessionContext(appContext, mmapID, key, null)
    }

    private fun recordAuthKeyOperation(
        operation: String,
        mmapID: String?,
        key: String,
        result: String,
        valueLength: Int? = null
    ) {
        if (mmapID != RAFT_AUTH_SESSION_MMKV_NAMESPACE || key != RAFT_AUTH_SESSION_MMKV_STORAGE_KEY) return
        RaftAndroidLocalDiagnostics.recordLog(
            "info",
            "SlockKuikly",
            buildString {
                append("shared_auth_session_native mmkv_auth_key operation=")
                append(operation)
                append(" result=")
                append(result)
                append(" mmapID=")
                append(mmapID)
                append(" key=")
                append(key)
                if (valueLength != null) {
                    append(" valueLen=")
                    append(valueLength)
                }
                append(" scope=app")
            }
        )
    }

    private fun maybeUpdateAuthSessionContext(
        context: Context,
        mmapID: String?,
        key: String,
        encodedSession: String?
    ) {
        if (mmapID != RAFT_AUTH_SESSION_MMKV_NAMESPACE || key != RAFT_AUTH_SESSION_MMKV_STORAGE_KEY) return
        val session =
            encodedSession
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { gson.fromJson(it, StoredAuthSession::class.java) }.getOrNull() }
        RaftAndroidLocalDiagnostics.updateUserContext(
            context = context.applicationContext,
            userId = session?.user?.id,
            selectedServerId = session?.currentServerId,
            selectedServerSlug = session?.selectedServerSlug
        )
    }

    private fun maybeUpdateForegroundKeepAlive(context: Context, mmapID: String?, key: String, value: String?) {
        if (mmapID == RaftLocalPreferenceStore.KuiklyNamespace &&
            (key == NOTIFICATION_ENABLED_PREFERENCE_KEY ||
                key == AndroidRealtimeKeepAliveController.localPreferenceKey(NOTIFICATION_ENABLED_PREFERENCE_KEY))
        ) {
            // The no-Play-services background connection only runs while message notifications are on.
            AndroidRealtimeKeepAliveController.refreshBackgroundConnectionService(
                context.applicationContext,
                source = "message_notifications_preference"
            )
            return
        }
        if (mmapID != RaftLocalPreferenceStore.KuiklyNamespace ||
            key != NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY &&
            key != AndroidRealtimeKeepAliveController.localPreferenceKey(
                NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
            )
        ) {
            return
        }
        if (value == "true") {
            AndroidRealtimeKeepAliveController.start(context.applicationContext, source = "preference_enabled")
        } else {
            AndroidRealtimeKeepAliveController.stop(context.applicationContext, reason = "preference_disabled")
        }
    }
}

private object AndroidAppScopeKeyValueStores {
    private val stores = mutableMapOf<String, AndroidMMKVKeyValueStore>()

    fun get(context: Context, namespace: String): AndroidMMKVKeyValueStore =
        synchronized(stores) {
            stores.getOrPut(namespace) {
                AndroidMMKVKeyValueStore(context.applicationContext, namespace)
            }
        }
}

private const val RAFT_NETWORK_RECOVERY_MMKV_NAMESPACE = "slockNetworkRecovery"

fun androidMMKVAuthKeyValueStore(context: Context): RaftKeyValueStore =
    AndroidAppScopeKeyValueStores.get(context, RAFT_AUTH_SESSION_MMKV_NAMESPACE)

fun androidMMKVNetworkRecoveryKeyValueStore(context: Context): RaftKeyValueStore =
    AndroidAppScopeKeyValueStores.get(context, RAFT_NETWORK_RECOVERY_MMKV_NAMESPACE)

fun androidMMKVApiEnvironmentKeyValueStore(context: Context): RaftKeyValueStore =
    AndroidAppScopeKeyValueStores.get(context, SharedApiEnvironmentStore.Namespace)

fun androidMMKVActivityUnreadKeyValueStore(context: Context): RaftKeyValueStore =
    AndroidAppScopeKeyValueStores.get(context, RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE)

/** Shared-code namespaces that get app-scope MMKV storage on Android. */
internal val androidAppScopeSharedKeyValueNamespaces: List<String> =
    listOf(
        RAFT_AUTH_SESSION_MMKV_NAMESPACE,
        RaftLocalPreferenceStore.KuiklyNamespace,
        SharedApiEnvironmentStore.Namespace,
        RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE,
        RAFT_APP_LOGIN_MMKV_NAMESPACE,
        RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE
    )

fun installAndroidAppScopeSharedKeyValueStores(context: Context) {
    val appContext = context.applicationContext
    androidAppScopeSharedKeyValueNamespaces.forEach { namespace ->
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(namespace) {
            AndroidAppScopeKeyValueStores.get(appContext, namespace)
        }
    }
}

fun installAndroidAppScopeAuthKeyValueStore(context: Context) {
    installAndroidAppScopeSharedKeyValueStores(context)
}
