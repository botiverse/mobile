package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import platform.Foundation.NSNotificationCenter

@RaftCoreInternalApi
actual suspend fun raftSetIosRemoteNotificationsEnabled(enabled: Boolean) {
    NSNotificationCenter.defaultCenter.postNotificationName(
        aName = "slockIosRemoteNotificationsState",
        `object` = null,
        userInfo = mapOf("enabled" to enabled)
    )
}
