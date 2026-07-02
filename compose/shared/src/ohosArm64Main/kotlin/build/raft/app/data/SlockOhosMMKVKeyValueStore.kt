package build.raft.app.data

import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI

private const val SLOCK_OHOS_MMKV_SERVICE = "slockOhosAppScopeMMKV"

@ServiceConsumer(name = SLOCK_OHOS_MMKV_SERVICE)
interface SlockOhosAppScopeMMKVService {
    fun containsKey(namespace: String, key: String): Boolean
    fun getString(namespace: String, key: String): String?
    fun putString(namespace: String, key: String, value: String)
    fun removeString(namespace: String, key: String)
}

internal class SlockOhosMMKVKeyValueStore(
    private val namespace: String
) : SlockKeyValueStore {
    override fun getString(key: String): String? {
        val namespacedKey = namespaced(key)
        val service = service()
        return if (service.containsKey(namespace, namespacedKey)) {
            service.getString(namespace, namespacedKey)
        } else {
            null
        }
    }

    override fun putString(key: String, value: String?) {
        val namespacedKey = namespaced(key)
        if (value == null) {
            service().removeString(namespace, namespacedKey)
        } else {
            service().putString(namespace, namespacedKey, value)
        }
    }

    private fun service(): SlockOhosAppScopeMMKVService =
        KNOI.get<SlockOhosAppScopeMMKVService>(SLOCK_OHOS_MMKV_SERVICE)

    private fun namespaced(key: String): String = "$namespace:$key"
}
