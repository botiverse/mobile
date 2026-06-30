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
    const val SyncResume = GeneratedRealtimeContract.SocketEvent.SyncResume
    const val SyncResumeResponse = GeneratedRealtimeContract.SocketEvent.SyncResumeResponse
    const val JoinChannel = GeneratedRealtimeContract.SocketEvent.JoinChannel
    const val LeaveChannel = GeneratedRealtimeContract.SocketEvent.LeaveChannel
    const val Heartbeat = GeneratedRealtimeContract.SocketEvent.Heartbeat
    const val MessageNew = GeneratedRealtimeContract.SocketEvent.MessageNew
    const val MessageUpdated = GeneratedRealtimeContract.SocketEvent.MessageUpdated
    const val DirectMessageNew = GeneratedRealtimeContract.SocketEvent.DirectMessageNew
    const val ThreadUpdated = GeneratedRealtimeContract.SocketEvent.ThreadUpdated
    const val ChannelUpdated = GeneratedRealtimeContract.SocketEvent.ChannelUpdated
    const val ChannelMembersUpdated = GeneratedRealtimeContract.SocketEvent.ChannelMembersUpdated
    const val TaskCreated = GeneratedRealtimeContract.SocketEvent.TaskCreated
    const val TaskUpdated = GeneratedRealtimeContract.SocketEvent.TaskUpdated
    const val TaskDeleted = GeneratedRealtimeContract.SocketEvent.TaskDeleted
    const val AgentActivity = GeneratedRealtimeContract.SocketEvent.AgentActivity
    const val AgentSession = GeneratedRealtimeContract.SocketEvent.AgentSession
    const val AgentCreated = GeneratedRealtimeContract.SocketEvent.AgentCreated
    const val AgentDeleted = GeneratedRealtimeContract.SocketEvent.AgentDeleted
    const val MachineStatus = GeneratedRealtimeContract.SocketEvent.MachineStatus
    const val MachineCapabilities = GeneratedRealtimeContract.SocketEvent.MachineCapabilities
    const val MachineUpdated = GeneratedRealtimeContract.SocketEvent.MachineUpdated
    const val ComputerUpgradeProgress = GeneratedRealtimeContract.SocketEvent.ComputerUpgradeProgress
    const val ComputerUpgradeDone = GeneratedRealtimeContract.SocketEvent.ComputerUpgradeDone
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

    val ServerWireEvents: List<String> = GeneratedRealtimeContract.SocketEvent.ServerWireEvents
}
