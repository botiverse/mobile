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

/**
 * Persisted key prefix of a per-conversation override. The value is "on@<rev>" / "off@<rev>" where
 * rev is the global switch's revision when the override was set; absent = follow global.
 */
internal const val RAFT_LONG_MESSAGE_COLLAPSE_OVERRIDE_KEY_PREFIX = "chatLayout:long_message_collapse_override:"

/** Revision of the global switch, advanced every time its value changes. */
internal const val RAFT_LONG_MESSAGE_COLLAPSE_GLOBAL_REVISION_KEY = "chatLayout:long_message_collapse_global_revision"

private const val OVERRIDE_ON = "on"
private const val OVERRIDE_OFF = "off"

internal fun SharedPreferenceStore.raftLongMessageCollapseGlobalRevision(): Long =
    getString(RAFT_LONG_MESSAGE_COLLAPSE_GLOBAL_REVISION_KEY)?.toLongOrNull() ?: 0L

/** Called when the global switch's value changes: every existing override becomes void. */
internal fun SharedPreferenceStore.advanceRaftLongMessageCollapseGlobalRevision() {
    putString(RAFT_LONG_MESSAGE_COLLAPSE_GLOBAL_REVISION_KEY, (raftLongMessageCollapseGlobalRevision() + 1L).toString())
}

internal data class RaftLongMessageCollapseStoredOverride(val enabled: Boolean, val globalRevision: Long)

internal fun raftLongMessageCollapseEncodeOverride(enabled: Boolean, globalRevision: Long): String =
    "${if (enabled) OVERRIDE_ON else OVERRIDE_OFF}@$globalRevision"

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

internal fun raftLongMessageCollapseParseOverride(raw: String?): RaftLongMessageCollapseStoredOverride? {
    val parts = raw?.trim()?.split('@') ?: return null
    if (parts.size !in 1..2) return null
    val enabled =
        when (parts[0]) {
            OVERRIDE_ON -> true
            OVERRIDE_OFF -> false
            else -> return null
        }
    // #3233 (Android build 2) stored bare "on"/"off": treat as set under global revision 0, the
    // revision every install had until the global switch first changed after this build.
    val revision = if (parts.size == 1) 0L else parts[1].toLongOrNull() ?: return null
    return RaftLongMessageCollapseStoredOverride(enabled, revision)
}

/**
 * An override is live only while the global switch has not changed since it was set (a boolean
 * override always differs from the global switch when stored, so any later global change makes
 * them equal) and it still differs from the global switch.
 */
internal fun raftLongMessageCollapseLiveStoredOverride(
    globalEnabled: Boolean,
    globalRevision: Long,
    stored: RaftLongMessageCollapseStoredOverride?
): Boolean? =
    stored
        ?.takeIf { it.globalRevision == globalRevision }
        ?.enabled
        ?.let { raftLongMessageCollapseLiveOverride(globalEnabled, it) }

/**
 * A stored override only means something while it differs from the global switch. Once the global
 * switch is changed to the override's value, the override is void: the conversation follows the
 * global switch again (and keeps following it if the global switch later changes back).
 */
internal fun raftLongMessageCollapseLiveOverride(globalEnabled: Boolean, storedOverride: Boolean?): Boolean? =
    storedOverride?.takeIf { it != globalEnabled }

internal enum class RaftLongMessageCollapseDetailKind { FollowsGlobalOn, FollowsGlobalOff, OverrideOn, OverrideOff }

/** Which settings detail line describes a conversation, from both the global switch and its override. */
internal fun raftLongMessageCollapseDetailKind(globalEnabled: Boolean, channelOverride: Boolean?): RaftLongMessageCollapseDetailKind =
    when (raftLongMessageCollapseLiveOverride(globalEnabled, channelOverride)) {
        null -> if (globalEnabled) RaftLongMessageCollapseDetailKind.FollowsGlobalOn else RaftLongMessageCollapseDetailKind.FollowsGlobalOff
        true -> RaftLongMessageCollapseDetailKind.OverrideOn
        false -> RaftLongMessageCollapseDetailKind.OverrideOff
    }

/** What a conversation's settings row shows. */
internal data class RaftLongMessageCollapseConversationPreference(
    val globalEnabled: Boolean,
    val channelOverride: Boolean?
) {
    val effective: Boolean get() =
        raftLongMessageCollapseEffective(globalEnabled, raftLongMessageCollapseLiveOverride(globalEnabled, channelOverride))
    val followsGlobal: Boolean get() = raftLongMessageCollapseLiveOverride(globalEnabled, channelOverride) == null
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

    /**
     * The live override of one conversation (a pure read). An override set before the global switch
     * last changed is void (the global switch now equals it): the conversation follows the global
     * switch from then on, also if the global switch changes back. [pruneVoidOverride] removes it.
     */
    fun readChannelOverride(serverId: String?, channelId: String?): Boolean? {
        val key = overrideKeyOrNull(serverId, channelId) ?: return null
        return raftLongMessageCollapseLiveStoredOverride(
            globalEnabled = readGlobalEnabled(),
            globalRevision = store.raftLongMessageCollapseGlobalRevision(),
            stored = raftLongMessageCollapseParseOverride(store.getString(key))
        )
    }

    /** Deletes a stored override that is no longer live (housekeeping; reads already ignore it). */
    fun pruneVoidOverride(serverId: String?, channelId: String?) {
        val key = overrideKeyOrNull(serverId, channelId) ?: return
        if (store.getString(key) != null && readChannelOverride(serverId, channelId) == null) {
            store.putString(key, null)
        }
    }

    private fun overrideKeyOrNull(serverId: String?, channelId: String?): String? {
        val server = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val channel = channelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raftLongMessageCollapseOverrideKey(server, channel)
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
            override?.let { raftLongMessageCollapseEncodeOverride(it, store.raftLongMessageCollapseGlobalRevision()) }
        )
        RaftLongMessageCollapsePreferenceRevision.bump()
        return readConversation(server, channel)
    }
}
