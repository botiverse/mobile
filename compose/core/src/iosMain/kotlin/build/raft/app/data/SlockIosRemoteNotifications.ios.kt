package build.raft.app.data

import platform.Foundation.NSNotificationCenter

actual suspend fun slockSetIosRemoteNotificationsEnabled(enabled: Boolean) {
    NSNotificationCenter.defaultCenter.postNotificationName(
        aName = "slockIosRemoteNotificationsState",
        `object` = null,
        userInfo = mapOf("enabled" to enabled)
    )
}
