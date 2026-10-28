package build.raft.app.data

import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId

data class PerReceiverReadStateUpdate(
    val serverId: ServerId,
    val memberId: MemberId,
    val channelId: ChannelId,
    val version: Long,
    val maxReadSeq: Long?,
    /** The scope's server-side unread after this read (`read_state:updated` `unreadCount`), when sent. */
    val unreadCount: Int? = null,
    /** `read_state:updated` `markedUnread` (botiverse/slock #11795): marked unread with no count. */
    val markedUnread: Boolean = false
)

data class PerReceiverNotificationPrefsUpdate(
    val serverId: ServerId,
    val memberId: MemberId,
    val channelId: ChannelId,
    val version: Long,
    val preference: ChannelActivityNotificationPreference
)

data class PerReceiverServerOrderUpdate(
    val serverId: ServerId,
    val memberId: MemberId,
    val version: Long,
    val sidebarOrder: SettingsServerSidebarOrderSnapshot
)

data class PerReceiverPinnedUpdate(
    val serverId: ServerId,
    val memberId: MemberId,
    val version: Long,
    val sidebarOrder: SettingsServerSidebarOrderSnapshot
)
