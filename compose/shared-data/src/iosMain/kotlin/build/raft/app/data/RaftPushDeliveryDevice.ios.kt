@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo

actual fun defaultRaftPushDeliveryDeviceContext(): RaftPushDeliveryDeviceContext {
    val osVersion =
        runCatching {
            NSProcessInfo.processInfo.operatingSystemVersion.useContents {
                formatIosPushDeliveryOsVersion(majorVersion.toInt(), minorVersion.toInt())
            }
        }.getOrElse { "unknown" }
    return RaftPushDeliveryDeviceContext(
        manufacturer = "apple",
        osVersion = osVersion,
        gmsAvailable = null,
        dndActive = null,
    )
}
