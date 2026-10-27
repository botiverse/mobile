package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Durable last-known copy of the per-workspace notification preference (`servers/:id/notification-settings`),
 * kept per (user, server) so the Settings screen shows the real value when the fetch fails (offline / server
 * error) instead of inventing a default. Written on every successful fetch and successful update.
 *
 * Lives in the last-known copy family ([RaftLastCountsPersistStorage]): keys carry `:user:<id>:server:<id>`,
 * other users' copies are never touched, and [clearAll] runs from the provider's auth-terminal cleanup
 * (sign-out / account switch) with the other last-known copies. Unlike the Home/Activity copies the value is a
 * setting, not a feed, so it does not age out; only the servers bound is kept.
 */
class SettingsServerNotificationLastKnownStore(
    store: SharedPreferenceStore,
    nowMillis: () -> Long = ::defaultNowMillis,
) {
    private val storage =
        RaftLastCountsPersistStorage(
            store = store,
            indexKey = "settings:persist:server_notification:index",
            nowMillis = nowMillis,
            maxAgeMillis = Long.MAX_VALUE,
            maxServersPerUser = MaxServersPerUser,
        )

    fun read(userId: String, serverId: String): SettingsServerNotificationPreferences? {
        val key = key(userId, serverId) ?: return null
        val encoded = storage.read(key) ?: return null
        val decoded =
            runCatching { StoreJson.decodeFromString(PersistedServerNotificationPreferences.serializer(), encoded) }
                .getOrNull()
        val preferences = decoded?.toPreferences()
        if (preferences == null) storage.remove(key)
        return preferences
    }

    fun write(userId: String, serverId: String, preferences: SettingsServerNotificationPreferences) {
        val key = key(userId, serverId) ?: return
        storage.write(
            key,
            StoreJson.encodeToString(
                PersistedServerNotificationPreferences.serializer(),
                PersistedServerNotificationPreferences(
                    mode = preferences.mode.wireName,
                    pushPreviewMode = preferences.pushPreviewMode.wireName,
                    legacyMuted = preferences.legacyMuted,
                    canonicalModeSupported = preferences.canonicalModeSupported,
                )
            )
        )
    }

    /** Sign-out: removes every copy written on this device. */
    fun clearAll() {
        storage.clearAll()
    }

    @Serializable
    private data class PersistedServerNotificationPreferences(
        val mode: String,
        val pushPreviewMode: String,
        val legacyMuted: Boolean,
        val canonicalModeSupported: Boolean,
    ) {
        fun toPreferences(): SettingsServerNotificationPreferences? =
            SettingsServerNotificationPreferences(
                mode = ServerPushMode.fromWire(mode) ?: return null,
                pushPreviewMode = PushPreviewMode.fromWire(pushPreviewMode) ?: return null,
                legacyMuted = legacyMuted,
                canonicalModeSupported = canonicalModeSupported,
            )
    }

    private companion object {
        const val MaxServersPerUser = 64
        val StoreJson = Json { ignoreUnknownKeys = true }

        fun key(userId: String, serverId: String): String? {
            val user = userId.trim().takeIf { it.isNotBlank() && ':' !in it } ?: return null
            val server = serverId.trim().takeIf { it.isNotBlank() && ':' !in it } ?: return null
            return "settings:persist:server_notification:v1:user:$user:server:$server"
        }
    }
}
