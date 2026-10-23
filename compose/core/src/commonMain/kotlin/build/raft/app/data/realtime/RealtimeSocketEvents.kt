package build.raft.app.data.realtime

/**
 * Socket.IO event names emitted by the Slock server.
 *
 * Mirrors `packages/server/src/socket/index.ts` and the various route emit
 * sites (e.g. `routes/messages.ts`, `routes/tasks.ts`, `services/taskRealtimeEvents.ts`).
 */
object RealtimeSocketEvents {
    const val Connect = GeneratedRealtimeContract.SocketEvent.Connect
    const val ConnectError = GeneratedRealtimeContract.SocketEvent.ConnectError
    const val Disconnect = GeneratedRealtimeContract.SocketEvent.Disconnect
    const val Reconnect = GeneratedRealtimeContract.SocketEvent.Reconnect
    const val ReconnectAttempt = GeneratedRealtimeContract.SocketEvent.ReconnectAttempt
    const val ReconnectError = GeneratedRealtimeContract.SocketEvent.ReconnectError
    const val ReconnectFailed = GeneratedRealtimeContract.SocketEvent.ReconnectFailed
    const val RoomsJoined = GeneratedRealtimeContract.SocketEvent.RoomsJoined

    /**
     * Task #14c: the server's answer to every `join:channel` (`{ channelId }`, botiverse/slock #10457):
     * `channel:joined` once this socket is in `channel:<channelId>`, `channel:join_denied` otherwise.
     * Connection signals like [RoomsJoined]: never mapped to a RealtimeEvent, never in [ServerWireEvents].
     */
    const val ChannelJoined = GeneratedRealtimeContract.SocketEvent.ChannelJoined
    const val ChannelJoinDenied = GeneratedRealtimeContract.SocketEvent.ChannelJoinDenied
    const val SyncResume = GeneratedRealtimeContract.SocketEvent.SyncResume
    const val SyncResumeResponse = GeneratedRealtimeContract.SocketEvent.SyncResumeResponse
    const val JoinChannel = GeneratedRealtimeContract.SocketEvent.JoinChannel
    const val LeaveChannel = GeneratedRealtimeContract.SocketEvent.LeaveChannel
    const val Heartbeat = GeneratedRealtimeContract.SocketEvent.Heartbeat
    const val MessageNew = GeneratedRealtimeContract.SocketEvent.MessageNew
    const val MessageUpdated = GeneratedRealtimeContract.SocketEvent.MessageUpdated
    const val DirectMessageNew = GeneratedRealtimeContract.SocketEvent.DirectMessageNew
    const val ThreadFollowersUpdated = "thread:followers-updated"
    const val ThreadUpdated = GeneratedRealtimeContract.SocketEvent.ThreadUpdated
    const val ChannelUpdated = GeneratedRealtimeContract.SocketEvent.ChannelUpdated
    const val ChannelMembersUpdated = GeneratedRealtimeContract.SocketEvent.ChannelMembersUpdated
    /** read_receipts_v0: an agent peer's read watermark advanced, or a large scope's summary changed. */
    const val ScopeReadUpdated = GeneratedRealtimeContract.SocketEvent.ScopeReadUpdated
    const val TaskCreated = GeneratedRealtimeContract.SocketEvent.TaskCreated
    const val TaskUpdated = GeneratedRealtimeContract.SocketEvent.TaskUpdated
    const val TaskDeleted = GeneratedRealtimeContract.SocketEvent.TaskDeleted
    const val AgentActivity = GeneratedRealtimeContract.SocketEvent.AgentActivity
    const val AgentSeen = GeneratedRealtimeContract.SocketEvent.AgentSeen
    /** Agent typing line (slock `packages/shared/src/agentTyping.ts`): an agent is about to post into `channel:<channelId>`. */
    const val AgentTyping = GeneratedRealtimeContract.SocketEvent.AgentTyping
    const val AgentSession = GeneratedRealtimeContract.SocketEvent.AgentSession
    const val AgentCreated = GeneratedRealtimeContract.SocketEvent.AgentCreated
    const val AgentDeleted = GeneratedRealtimeContract.SocketEvent.AgentDeleted
    const val MachineStatus = GeneratedRealtimeContract.SocketEvent.MachineStatus
    const val MachineCapabilities = GeneratedRealtimeContract.SocketEvent.MachineCapabilities
    const val MachineUpdated = GeneratedRealtimeContract.SocketEvent.MachineUpdated
    const val ComputerUpgradeProgress = GeneratedRealtimeContract.SocketEvent.ComputerUpgradeProgress
    const val ComputerUpgradeDone = GeneratedRealtimeContract.SocketEvent.ComputerUpgradeDone
    const val MachineUpgradeRequest = GeneratedRealtimeContract.SocketEvent.MachineUpgradeRequest
    const val ComputerRestartDone = GeneratedRealtimeContract.SocketEvent.ComputerRestartDone
    const val DaemonStatus = GeneratedRealtimeContract.SocketEvent.DaemonStatus
    const val ServerMemberAdded = GeneratedRealtimeContract.SocketEvent.ServerMemberAdded
    const val ServerMemberUpdated = GeneratedRealtimeContract.SocketEvent.ServerMemberUpdated
    const val ServerMemberLeft = GeneratedRealtimeContract.SocketEvent.ServerMemberLeft
    const val ServerMemberRemoved = GeneratedRealtimeContract.SocketEvent.ServerMemberRemoved
    const val ServerMembershipRemoved = GeneratedRealtimeContract.SocketEvent.ServerMembershipRemoved
    const val ServerDeleted = GeneratedRealtimeContract.SocketEvent.ServerDeleted
    const val ServerPlanUpdated = GeneratedRealtimeContract.SocketEvent.ServerPlanUpdated
    const val AnnouncementNew = GeneratedRealtimeContract.SocketEvent.AnnouncementNew
    const val ReminderFired = GeneratedRealtimeContract.SocketEvent.ReminderFired
    const val ReminderScheduled = GeneratedRealtimeContract.SocketEvent.ReminderScheduled
    const val ReminderCanceled = GeneratedRealtimeContract.SocketEvent.ReminderCanceled

    // RFC 041 per-receiver state push, emitted to the caller's `user:<id>`
    // room from `routes/channels.ts` (server PR #4175). Literals live here
    // instead of GeneratedRealtimeContract because the generator last ran
    // against a pre-#4175 server tree — swap to the generated constants on
    // the next `generate_realtime_contract.py` regeneration.
    const val ReadStateUpdated = "read_state:updated"
    const val ReadStateUpdatedBulk = "read_state:updated_bulk"
    const val NotificationPrefsUpdated = "notification_prefs:updated"
    const val PinnedUpdated = "pinned:updated"

    // task #459: server-qualified notification stream, emitted to the caller's
    // `user:<id>` room at the sendPushNotifications call point (server PR
    // #4233, staging 31042ed06). Literal until the next contract regeneration.
    const val NotificationPush = "notification:push"

    val ServerWireEvents: List<String> =
        GeneratedRealtimeContract.SocketEvent.ServerWireEvents + listOf(
            ThreadFollowersUpdated,
            ReadStateUpdated,
            ReadStateUpdatedBulk,
            NotificationPrefsUpdated,
            PinnedUpdated,
            NotificationPush,
        )
}
