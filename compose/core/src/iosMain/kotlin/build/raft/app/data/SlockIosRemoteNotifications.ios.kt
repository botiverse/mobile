package build.raft.app.data

import build.raft.app.SlockCoreInternalApi
import platform.Foundation.NSNotificationCenter

@SlockCoreInternalApi
actual suspend fun slockSetIosRemoteNotificationsEnabled(enabled: Boolean) {
    NSNotificationCenter.defaultCenter.postNotificationName(
        aName = "slockIosRemoteNotificationsState",
        `object` = null,
        userInfo = mapOf("enabled" to enabled)
    )
}
