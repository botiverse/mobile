package build.raft.app.data

import build.raft.app.SlockCoreInternalApi

@SlockCoreInternalApi
object SlockSystemNotificationContract {
    const val OpenEventName = "slockSystemNotificationOpen"
    const val OpenReadyEventName = "slockSystemNotificationOpenReady"
    const val OpenNotReadyEventName = "slockSystemNotificationOpenNotReady"
}
