package ai.slock.compose.data.realtime

/**
 * Socket.IO event names emitted by the Slock server.
 *
 * Mirrors `packages/server/src/socket/index.ts` and the various route emit
 * sites (e.g. `routes/messages.ts`, `routes/tasks.ts`, `services/taskRealtimeEvents.ts`).
 */
object RealtimeSocketEvents {
    const val Connect = "connect"
    const val ConnectError = "connect_error"
    const val Disconnect = "disconnect"
    const val Reconnect = "reconnect"
    const val ReconnectAttempt = "reconnect_attempt"
    const val ReconnectError = "reconnect_error"
    const val ReconnectFailed = "reconnect_failed"
    const val RoomsJoined = "rooms:joined"
    const val SyncResume = "sync:resume"
    const val SyncResumeResponse = "sync:resume:response"
    const val JoinChannel = "join:channel"
    const val Heartbeat = "heartbeat"
    const val MessageNew = "message:new"
    const val MessageUpdated = "message:updated"
    const val ReadUpdated = "read:updated"
    const val DirectMessageNew = "dm:new"
    const val ThreadUpdated = "thread:updated"
    const val ChannelUpdated = "channel:updated"
    const val ChannelMembersUpdated = "channel:members-updated"
    const val TaskCreated = "task:created"
    const val TaskUpdated = "task:updated"
    const val TaskDeleted = "task:deleted"
    const val AgentActivity = "agent:activity"
    const val AgentSession = "agent:session"
    const val AgentCreated = "agent:created"
    const val AgentDeleted = "agent:deleted"
    const val MachineStatus = "machine:status"
    const val MachineCapabilities = "machine:capabilities"
    const val MachineUpdated = "machine:updated"
    const val ComputerUpgradeProgress = "computer:upgrade:progress"
    const val ComputerUpgradeDone = "computer:upgrade:done"
    const val DaemonStatus = "daemon:status"
    const val ServerMemberAdded = "server:member-added"
    const val ServerMemberUpdated = "server:member-updated"
    const val ServerMemberLeft = "server:member:left"
    const val ServerMemberRemoved = "server:member-removed"
    const val ServerMembershipRemoved = "server:membership-removed"
    const val ServerDeleted = "server:deleted"
    const val ServerPlanUpdated = "server:plan-updated"
    const val AnnouncementNew = "announcement:new"
    const val ReminderFired = "reminder:fired"
    const val ReminderScheduled = "reminder:scheduled"
    const val ReminderCanceled = "reminder:canceled"

    val ServerWireEvents: List<String> = listOf(
        MessageNew,
        DirectMessageNew,
        MessageUpdated,
        ReadUpdated,
        ThreadUpdated,
        ChannelUpdated,
        ChannelMembersUpdated,
        TaskCreated,
        TaskUpdated,
        TaskDeleted,
        AgentActivity,
        AgentSession,
        AgentCreated,
        AgentDeleted,
        MachineStatus,
        MachineCapabilities,
        MachineUpdated,
        ComputerUpgradeProgress,
        ComputerUpgradeDone,
        DaemonStatus,
        ServerMemberAdded,
        ServerMemberUpdated,
        ServerMemberLeft,
        ServerMemberRemoved,
        ServerMembershipRemoved,
        ServerDeleted,
        ServerPlanUpdated,
        AnnouncementNew,
        ReminderFired,
        ReminderScheduled,
        ReminderCanceled,
        SyncResumeResponse,
        Heartbeat
    )
}
