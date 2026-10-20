package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Collapse long messages" is a device-local, mobile-only preference: a global switch in
 * Settings -> Mobile plus an optional per-conversation override (channel or DM), keyed by
 * server + channel id. The effective value is `override ?: global`.
 *
 * The global switch keeps its historical storage key
 * ((`chatLayout:long_message_collapse_enabled`)); only its default
 * changed to ON, so a user who explicitly turned it off keeps it off.
 */
internal const val RAFT_LONG_MESSAGE_COLLAPSE_DEFAULT_ENABLED: Boolean = true

/** English fallback detail of the global switch (the Settings card renders the localized MR string). */
internal const val RAFT_LONG_MESSAGE_COLLAPSE_SETTINGS_DETAIL =
    "Clip messages taller than most of the screen behind a Show More link. Each channel or DM can override this in its settings."

/** Persisted key prefix of a per-conversation override; the value is "on" / "off", absent = follow global. */
internal const val RAFT_LONG_MESSAGE_COLLAPSE_OVERRIDE_KEY_PREFIX = "chatLayout:long_message_collapse_override:"

private const val OVERRIDE_ON = "on"
private const val OVERRIDE_OFF = "off"

internal fun raftLongMessageCollapseOverrideKey(serverId: String, channelId: String): String =
    "$RAFT_LONG_MESSAGE_COLLAPSE_OVERRIDE_KEY_PREFIX${serverId.trim()}:${channelId.trim()}"

/** The effective switch for one conversation: its own override when it has one, else the global switch. */
internal fun raftLongMessageCollapseEffective(globalEnabled: Boolean, channelOverride: Boolean?): Boolean =
    channelOverride ?: globalEnabled

/**
 * The override to store when the user sets a conversation's switch to [requested]. Choosing the
 * value the global switch already has clears the override, so the conversation follows the global
 * switch again (the switch never needs a separate "reset" control).
 */
internal fun raftLongMessageCollapseOverrideAfterSet(globalEnabled: Boolean, requested: Boolean): Boolean? =
    if (requested == globalEnabled) null else requested

internal fun raftLongMessageCollapseParseOverride(raw: String?): Boolean? =
    when (raw?.trim()) {
        OVERRIDE_ON -> true
        OVERRIDE_OFF -> false
        else -> null
    }

/** What a conversation's settings row shows. */
internal data class RaftLongMessageCollapseConversationPreference(
    val globalEnabled: Boolean,
    val channelOverride: Boolean?
) {
    val effective: Boolean get() = raftLongMessageCollapseEffective(globalEnabled, channelOverride)
    val followsGlobal: Boolean get() = channelOverride == null
}

/**
 * Process-wide change signal. Every write (global switch or a conversation override) bumps it so an
 * open thread or settings page re-reads the stored values without its own subscription plumbing.
 */
internal object RaftLongMessageCollapsePreferenceRevision {
    private val state = MutableStateFlow(0L)
    val revision: StateFlow<Long> = state.asStateFlow()

    fun bump() {
        state.value = state.value + 1L
    }
}

internal class RaftLongMessageCollapsePreferenceStore(
    private val store: SharedPreferenceStore
) {
    private val chatLayout = KeyValueChatLayoutPreferenceStore(store)

    fun readGlobalEnabled(): Boolean = chatLayout.readLongMessageCollapseEnabledSync()

    fun readChannelOverride(serverId: String?, channelId: String?): Boolean? {
        val server = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val channel = channelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raftLongMessageCollapseParseOverride(store.getString(raftLongMessageCollapseOverrideKey(server, channel)))
    }

    fun readConversation(serverId: String?, channelId: String?): RaftLongMessageCollapseConversationPreference =
        RaftLongMessageCollapseConversationPreference(
            globalEnabled = readGlobalEnabled(),
            channelOverride = readChannelOverride(serverId, channelId)
        )

    /** Sets one conversation's switch; see [raftLongMessageCollapseOverrideAfterSet]. */
    fun setConversationEnabled(
        serverId: String?,
        channelId: String?,
        enabled: Boolean
    ): RaftLongMessageCollapseConversationPreference {
        val server = serverId?.trim()?.takeIf { it.isNotEmpty() }
        val channel = channelId?.trim()?.takeIf { it.isNotEmpty() }
        if (server == null || channel == null) return readConversation(serverId, channelId)
        val override = raftLongMessageCollapseOverrideAfterSet(readGlobalEnabled(), enabled)
        store.putString(
            raftLongMessageCollapseOverrideKey(server, channel),
            when (override) {
                true -> OVERRIDE_ON
                false -> OVERRIDE_OFF
                null -> null
            }
        )
        RaftLongMessageCollapsePreferenceRevision.bump()
        return readConversation(server, channel)
    }
}
