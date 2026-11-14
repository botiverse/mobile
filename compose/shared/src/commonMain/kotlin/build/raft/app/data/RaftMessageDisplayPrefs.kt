package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * A person's message display preferences for one channel or DM, stored on the account (web
 * `GET` / `PATCH /channels/:id/message-display-settings`, slock `userChannelDisplayPrefs`):
 * today only "Collapse long messages". [prefsVersion] 0 means never set (the server's default,
 * off).
 */
internal data class RaftMessageDisplayPrefs(
    val collapseLongMessages: Boolean,
    val prefsVersion: Long? = null,
) {
    /** Nobody has set it yet: the server answers its default. */
    val unset: Boolean get() = prefsVersion == null || prefsVersion == 0L
}

/** Web `normalizeMessageDisplayPrefs`: anything but `true` is off; a bad version is dropped. */
internal fun parseRaftMessageDisplayPrefs(body: String): RaftMessageDisplayPrefs? {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val collapse = (root["collapseLongMessages"] as? JsonPrimitive)?.booleanOrNull == true
    val version = (root["prefsVersion"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    return RaftMessageDisplayPrefs(collapseLongMessages = collapse, prefsVersion = version)
}

/** The `PATCH` body: `{"collapseLongMessages":<bool>}`, nothing else. */
internal fun buildRaftMessageDisplayPrefsBody(collapseLongMessages: Boolean): String =
    JsonObject(mapOf("collapseLongMessages" to JsonPrimitive(collapseLongMessages))).toString()

internal fun raftMessageDisplayPrefsKey(serverId: String?, channelId: String?): String? {
    val server = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val channel = channelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "$server:$channel"
}

internal class RaftMessageDisplaySettingsApi(private val networkClient: RaftNetworkClient) {
    suspend fun get(serverId: String, channelId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "channels/$channelId/message-display-settings",
            serverIdOverride = ServerId(serverId)
        )

    suspend fun patch(serverId: String, channelId: String, bodyText: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "channels/$channelId/message-display-settings",
            method = "PATCH",
            serverIdOverride = ServerId(serverId),
            bodyText = bodyText
        )
}

/** What a request came back with: the preferences, "this conversation has none" (thread, old server), or a failure. */
internal sealed interface RaftMessageDisplayPrefsResult {
    data class Loaded(val prefs: RaftMessageDisplayPrefs) : RaftMessageDisplayPrefsResult

    /** 400 (a thread), 404 (an older server, or no access): the device-local setting applies. */
    data object Unsupported : RaftMessageDisplayPrefsResult

    data object Failed : RaftMessageDisplayPrefsResult
}

internal fun raftMessageDisplayPrefsResult(code: Int, body: String?): RaftMessageDisplayPrefsResult =
    when {
        code in 200..299 -> body?.let(::parseRaftMessageDisplayPrefs)?.let(RaftMessageDisplayPrefsResult::Loaded)
            ?: RaftMessageDisplayPrefsResult.Failed
        code == 400 || code == 404 -> RaftMessageDisplayPrefsResult.Unsupported
        else -> RaftMessageDisplayPrefsResult.Failed
    }

/**
 * Process store of the account's message display preferences, per server + channel. A
 * conversation reads it when it opens; a toggle writes at once and rolls back when the server
 * refuses (web ChatPanel `handleToggleCollapseLongMessages`).
 */
internal object RaftMessageDisplayPrefsStore {
    private val mutableState = MutableStateFlow<Map<String, RaftMessageDisplayPrefs>>(emptyMap())
    val state: StateFlow<Map<String, RaftMessageDisplayPrefs>> = mutableState.asStateFlow()

    private val mutableUnsupported = MutableStateFlow<Set<String>>(emptySet())

    /** Conversations the server keeps no display preferences for: their device-local switch applies. */
    val unsupported: StateFlow<Set<String>> = mutableUnsupported.asStateFlow()

    /** Replaced in tests. */
    @Volatile
    internal var apiProvider: () -> RaftMessageDisplaySettingsApi? = {
        RaftSharedStoreRegistry.currentOrNull()?.networkClient?.let(::RaftMessageDisplaySettingsApi)
    }

    /** `GET message-display-settings`; keeps the held copy on failure. */
    suspend fun refresh(serverId: String, channelId: String): RaftMessageDisplayPrefsResult {
        val key = raftMessageDisplayPrefsKey(serverId, channelId) ?: return RaftMessageDisplayPrefsResult.Failed
        val api = apiProvider() ?: return RaftMessageDisplayPrefsResult.Failed
        val result = request { api.get(serverId, channelId) }
        apply(key, result, rollbackTo = null)
        return result
    }

    /** `PATCH message-display-settings`, applied at once; the previous value comes back on failure. */
    suspend fun setCollapseLongMessages(serverId: String, channelId: String, enabled: Boolean): RaftMessageDisplayPrefsResult {
        val key = raftMessageDisplayPrefsKey(serverId, channelId) ?: return RaftMessageDisplayPrefsResult.Failed
        val api = apiProvider() ?: return RaftMessageDisplayPrefsResult.Failed
        val previous = mutableState.value[key]
        mutableState.update { it + (key to RaftMessageDisplayPrefs(enabled, previous?.prefsVersion)) }
        val result = request { api.patch(serverId, channelId, buildRaftMessageDisplayPrefsBody(enabled)) }
        if (previous == null && result == RaftMessageDisplayPrefsResult.Failed) {
            // Never loaded: forget the optimistic copy rather than keep a value the account lacks.
            mutableState.update { it - key }
        } else {
            apply(key, result, rollbackTo = previous)
        }
        return result
    }

    /**
     * Realtime `message_display_prefs:updated`: the person changed it on another device or tab (web
     * `setMessageDisplayPrefsState`). Older than the held version: dropped.
     */
    fun applyRealtime(serverId: String, channelId: String, prefs: RaftMessageDisplayPrefs) {
        val key = raftMessageDisplayPrefsKey(serverId, channelId) ?: return
        apply(key, RaftMessageDisplayPrefsResult.Loaded(prefs), rollbackTo = null)
    }

    private fun apply(key: String, result: RaftMessageDisplayPrefsResult, rollbackTo: RaftMessageDisplayPrefs?) {
        when (result) {
            is RaftMessageDisplayPrefsResult.Loaded -> {
                mutableState.update { all ->
                    val held = all[key]
                    // An answer older than what is held (a later write landed first) is dropped.
                    if (held?.prefsVersion != null && result.prefs.prefsVersion != null && held.prefsVersion > result.prefs.prefsVersion) {
                        all
                    } else {
                        all + (key to result.prefs)
                    }
                }
                mutableUnsupported.update { it - key }
            }
            RaftMessageDisplayPrefsResult.Unsupported -> {
                mutableState.update { it - key }
                mutableUnsupported.update { it + key }
            }
            // A failed write puts the previous value back; a failed read keeps what is held.
            RaftMessageDisplayPrefsResult.Failed ->
                if (rollbackTo != null) mutableState.update { it + (key to rollbackTo) }
        }
    }

    private suspend fun request(block: suspend () -> HomeNetworkResponse): RaftMessageDisplayPrefsResult =
        try {
            val response = block()
            raftMessageDisplayPrefsResult(response.code, response.bodyText)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            RaftMessageDisplayPrefsResult.Failed
        }

    internal fun resetForTests() {
        mutableState.value = emptyMap()
        mutableUnsupported.value = emptySet()
    }
}

/**
 * The one-time move of a conversation's device-local "Collapse long messages" to the account
 * (task: sync across devices): the value this device showed is written when the account has
 * never set one and it differs from the account's default. An account value someone set (on
 * the web or another device) wins. Null: nothing to write.
 */
internal fun raftLongMessageCollapseMigrationWrite(localEffective: Boolean, account: RaftMessageDisplayPrefs): Boolean? =
    localEffective.takeIf { account.unset && it != account.collapseLongMessages }

/** Per server + channel: the device-local value was already moved to the account (or found nothing to move). */
internal const val RAFT_LONG_MESSAGE_COLLAPSE_MIGRATED_KEY_PREFIX = "chatLayout:long_message_collapse_migrated:"

internal fun raftLongMessageCollapseMigratedKey(serverId: String, channelId: String): String =
    "$RAFT_LONG_MESSAGE_COLLAPSE_MIGRATED_KEY_PREFIX${serverId.trim()}:${channelId.trim()}"
