package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi
object RaftSystemNotificationContract {
    const val OpenEventName = "slockSystemNotificationOpen"
    const val OpenReadyEventName = "slockSystemNotificationOpenReady"
    const val OpenNotReadyEventName = "slockSystemNotificationOpenNotReady"
}
