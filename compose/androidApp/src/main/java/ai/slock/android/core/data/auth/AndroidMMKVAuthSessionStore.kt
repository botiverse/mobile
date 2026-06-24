package ai.slock.android.core.data.auth

import android.content.Context
import ai.slock.android.shared.SlockKuiklyMMKVModule
import ai.slock.compose.data.SLOCK_AUTH_SESSION_MMKV_NAMESPACE
import ai.slock.compose.data.SLOCK_AUTH_SESSION_MMKV_STORAGE_KEY
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.StoredAuthSession
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

class AndroidMMKVAuthSessionStore(context: Context) : SlockAuthSessionStore {
    private val appContext = context.applicationContext
    private val gson = Gson()

    private val store: MMKV
        get() {
            SlockKuiklyMMKVModule.ensureInitialized(appContext)
            return MMKV.mmkvWithID(SLOCK_AUTH_SESSION_MMKV_NAMESPACE)
        }

    override suspend fun readSession(): StoredAuthSession? {
        val encoded = store.decodeString(SLOCK_AUTH_SESSION_MMKV_STORAGE_KEY, null)
        if (!encoded.isNullOrBlank()) {
            return encoded.decodeSessionOrNull()
        }
        return migrateEncryptedSession()
    }

    override suspend fun saveSession(session: StoredAuthSession) {
        store.encode(SLOCK_AUTH_SESSION_MMKV_STORAGE_KEY, gson.toJson(session))
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val current = readSession() ?: return
        saveSession(current.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        val current = readSession() ?: return
        saveSession(current.copy(selectedServerId = serverId, selectedServerSlug = serverSlug))
    }

    override suspend fun clearSession() {
        store.removeValueForKey(SLOCK_AUTH_SESSION_MMKV_STORAGE_KEY)
        EncryptedAuthSessionStore(appContext).clearSession()
    }

    private suspend fun migrateEncryptedSession(): StoredAuthSession? {
        val legacy = EncryptedAuthSessionStore(appContext)
        val session = legacy.readSession() ?: return null
        saveSession(session)
        legacy.clearSession()
        return session
    }

    private fun String.decodeSessionOrNull(): StoredAuthSession? =
        runCatching {
            gson.fromJson(this, StoredAuthSession::class.java)
        }.getOrElse {
            store.removeValueForKey(SLOCK_AUTH_SESSION_MMKV_STORAGE_KEY)
            null
        }
}
