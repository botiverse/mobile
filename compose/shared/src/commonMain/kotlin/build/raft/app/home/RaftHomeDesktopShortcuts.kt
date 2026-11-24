package build.raft.app.data

import build.raft.app.RaftHomePinnedRow

/**
 * Extracts desktop shortcuts from rendered [RaftHomePinnedRow] entries; a DM without a name is
 * labelled [directMessageFallbackTitle].
 */
internal fun extractTopPinnedDesktopShortcuts(
    serverId: String,
    serverName: String,
    pinnedRows: List<RaftHomePinnedRow>,
    directMessageFallbackTitle: String,
    maxCount: Int = 4
): List<RaftDesktopShortcutItem> {
    if (serverId.isBlank() || pinnedRows.isEmpty() || maxCount <= 0) return emptyList()
    val results = mutableListOf<RaftDesktopShortcutItem>()
    val seenTargetIds = mutableSetOf<String>()

    for (row in pinnedRows) {
        if (results.size >= maxCount) break
        val conversation = row.conversation ?: continue
        val isDm = conversation.peerType != null ||
            conversation.kind.equals("direct_message", ignoreCase = true) ||
            conversation.kind.equals("dm", ignoreCase = true)
        val targetId = conversation.id.trim()
        if (targetId.isBlank() || !seenTargetIds.add(targetId)) continue
        val targetKind = if (isDm) RaftDesktopShortcutContract.TargetKindDm else RaftDesktopShortcutContract.TargetKindChannel
        val rawName = conversation.name.trim()
        val title = if (isDm) {
            rawName.ifBlank { directMessageFallbackTitle }
        } else {
            if (rawName.startsWith("#")) rawName else "#$rawName"
        }
        results.add(
            RaftDesktopShortcutItem(
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
