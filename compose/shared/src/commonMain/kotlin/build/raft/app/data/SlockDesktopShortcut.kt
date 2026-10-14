package build.raft.app.data

import build.raft.app.SlockHomePinnedRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SlockDesktopShortcutItem(
    val id: String,
    val serverId: String,
    val targetId: String,
    val targetKind: String,
    val title: String,
    val subtitle: String? = null,
    val avatarLabel: String? = null
)

object SlockDesktopShortcutContract {
    const val OpenEventName = "slockDesktopShortcutOpen"
    const val OpenReadyEventName = "slockDesktopShortcutOpenReady"
    const val OpenNotReadyEventName = "slockDesktopShortcutOpenNotReady"

    const val KeyServerId = "slockShortcutServerId"
    const val KeyTargetId = "slockShortcutTargetId"
    const val KeyTargetKind = "slockShortcutTargetKind"
    const val KeyLaunchId = "slockShortcutLaunchId"
    const val KeyTitle = "slockShortcutTitle"

    const val TargetKindChannel = "channel"
    const val TargetKindDm = "dm"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun serializeList(items: List<SlockDesktopShortcutItem>): String =
        json.encodeToString(items)

    fun deserializeList(text: String): List<SlockDesktopShortcutItem> =
        runCatching { json.decodeFromString<List<SlockDesktopShortcutItem>>(text) }.getOrDefault(emptyList())
}

/**
 * Extracts up to [maxCount] desktop shortcuts from a [HomeSnapshot], prioritising
 * explicitly pinned channels and DMs in their configured sidebar order.
 */
internal fun extractTopPinnedDesktopShortcuts(
    snapshot: HomeSnapshot,
    maxCount: Int = 4
): List<SlockDesktopShortcutItem> {
    val serverId = snapshot.server.id.value.trim()
    val serverName = snapshot.server.name.trim()
    if (serverId.isBlank() || maxCount <= 0) return emptyList()

    val sidebarOrder = snapshot.sidebarOrder ?: return emptyList()

    val channelsByThreadId = snapshot.channels.associateBy { it.threadId.value }
    val channelsById = snapshot.channels.associateBy { it.channelId.value }
    val dmsByThreadId = snapshot.directMessages.associateBy { it.threadId.value }
    val dmsByMemberId = buildMap {
        snapshot.directMessages.forEach { dm ->
            dm.presenceMemberId?.value?.takeIf { it.isNotBlank() }?.let { put(it, dm) }
        }
    }

    val results = mutableListOf<SlockDesktopShortcutItem>()
    val seenTargetIds = mutableSetOf<String>()

    fun addChannelShortcut(channel: HomeChannelRow) {
        val targetId = channel.channelId.value.ifBlank { channel.threadId.value }
        if (targetId.isBlank() || !seenTargetIds.add(targetId)) return
        val rawName = channel.name.trim()
        val title = if (rawName.startsWith("#")) rawName else "#$rawName"
        results.add(
            SlockDesktopShortcutItem(
                id = "shortcut_${serverId}_$targetId",
                serverId = serverId,
                targetId = targetId,
                targetKind = SlockDesktopShortcutContract.TargetKindChannel,
                title = title.ifBlank { "#channel" },
                subtitle = serverName.takeIf { it.isNotBlank() }
            )
        )
    }

    fun addDmShortcut(dm: HomeDirectMessageRow) {
        val targetId = dm.threadId.value.trim()
        if (targetId.isBlank() || !seenTargetIds.add(targetId)) return
        val title = dm.name.trim().ifBlank { "Direct Message" }
        results.add(
            SlockDesktopShortcutItem(
                id = "shortcut_${serverId}_$targetId",
                serverId = serverId,
                targetId = targetId,
                targetKind = SlockDesktopShortcutContract.TargetKindDm,
                title = title,
                subtitle = serverName.takeIf { it.isNotBlank() }
            )
        )
    }

    // 1. First preference: typed pinned items in sidebarOrder.pinned
    for (pinned in sidebarOrder.pinned) {
        if (results.size >= maxCount) break
        val id = pinned.id.trim()
        val kind = pinned.kind.trim().lowercase()
        when (kind) {
            "channel" -> {
                val channel = channelsById[id] ?: channelsByThreadId[id]
                if (channel != null) {
                    addChannelShortcut(channel)
                }
            }
            "agent", "human", "dm", "direct_message" -> {
                val dm = dmsByMemberId[id] ?: dmsByThreadId[id]
                if (dm != null) {
                    addDmShortcut(dm)
                }
            }
        }
    }

    // 2. Second preference: pinnedOrder (if pinned is empty or didn't reach maxCount)
    if (results.size < maxCount) {
        for (id in sidebarOrder.pinnedOrder) {
            if (results.size >= maxCount) break
            val trimmedId = id.trim()
            val channel = channelsById[trimmedId] ?: channelsByThreadId[trimmedId]
            if (channel != null) {
                addChannelShortcut(channel)
                continue
            }
            val dm = dmsByMemberId[trimmedId] ?: dmsByThreadId[trimmedId]
            if (dm != null) {
                addDmShortcut(dm)
            }
        }
    }

    // 3. Fallback: pinnedChannelIds and pinnedAgentIds
    if (results.size < maxCount) {
        for (id in sidebarOrder.pinnedChannelIds) {
            if (results.size >= maxCount) break
            val trimmedId = id.trim()
            val channel = channelsById[trimmedId] ?: channelsByThreadId[trimmedId]
            if (channel != null) {
                addChannelShortcut(channel)
            }
        }
    }
    if (results.size < maxCount) {
        for (id in sidebarOrder.pinnedAgentIds) {
            if (results.size >= maxCount) break
            val trimmedId = id.trim()
            val dm = dmsByMemberId[trimmedId] ?: dmsByThreadId[trimmedId]
            if (dm != null) {
                addDmShortcut(dm)
            }
        }
    }

    return results.take(maxCount)
}

/**
 * Extracts desktop shortcuts from rendered [SlockHomePinnedRow] entries.
 */
internal fun extractTopPinnedDesktopShortcuts(
    serverId: String,
    serverName: String,
    pinnedRows: List<SlockHomePinnedRow>,
    maxCount: Int = 4
): List<SlockDesktopShortcutItem> {
    if (serverId.isBlank() || pinnedRows.isEmpty() || maxCount <= 0) return emptyList()
    val results = mutableListOf<SlockDesktopShortcutItem>()
    val seenTargetIds = mutableSetOf<String>()

    for (row in pinnedRows) {
        if (results.size >= maxCount) break
        val conversation = row.conversation ?: continue
        val isDm = conversation.peerType != null ||
            conversation.kind.equals("direct_message", ignoreCase = true) ||
            conversation.kind.equals("dm", ignoreCase = true)
        val targetId = conversation.id.trim()
        if (targetId.isBlank() || !seenTargetIds.add(targetId)) continue
        val targetKind = if (isDm) SlockDesktopShortcutContract.TargetKindDm else SlockDesktopShortcutContract.TargetKindChannel
        val rawName = conversation.name.trim()
        val title = if (isDm) {
            rawName.ifBlank { "Direct Message" }
        } else {
            if (rawName.startsWith("#")) rawName else "#$rawName"
        }
        results.add(
            SlockDesktopShortcutItem(
                id = "shortcut_${serverId}_$targetId",
                serverId = serverId,
                targetId = targetId,
                targetKind = targetKind,
                title = title.ifBlank { "#channel" },
                subtitle = serverName.takeIf { it.isNotBlank() },
                avatarLabel = conversation.avatarLabel
            )
        )
    }
    return results.take(maxCount)
}
