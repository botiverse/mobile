package build.raft.app.data

import build.raft.app.data.ohosmmkv.mmkv_c_contains_key
import build.raft.app.data.ohosmmkv.mmkv_c_get_string
import build.raft.app.data.ohosmmkv.mmkv_c_mmkv_with_id
import build.raft.app.data.ohosmmkv.mmkv_c_remove_value_for_key
import build.raft.app.data.ohosmmkv.mmkv_c_set_string
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.free

private const val SLOCK_OHOS_MMKV_SINGLE_PROCESS_MODE = 1

internal class SlockOhosMMKVKeyValueStore(
    private val namespace: String
) : SlockKeyValueStore {
    @OptIn(ExperimentalForeignApi::class)
    private val mmkvHandle: Long by lazy {
        mmkv_c_mmkv_with_id(
            namespace,
            SLOCK_OHOS_MMKV_SINGLE_PROCESS_MODE,
            null,
            null,
            0
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun getString(key: String): String? {
        val namespacedKey = namespaced(key)
        return if (mmkv_c_contains_key(mmkvHandle, namespacedKey) != 0) {
            val rawValue = mmkv_c_get_string(mmkvHandle, namespacedKey)
            try {
                rawValue?.toKString()
            } finally {
                if (rawValue != null) {
                    free(rawValue)
                }
            }
        } else {
            null
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun putString(key: String, value: String?) {
        val namespacedKey = namespaced(key)
        if (value == null) {
            mmkv_c_remove_value_for_key(mmkvHandle, namespacedKey)
        } else {
            mmkv_c_set_string(mmkvHandle, namespacedKey, value)
        }
    }

    private fun namespaced(key: String): String = "$namespace:$key"
}
