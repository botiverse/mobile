package build.raft.app.data

import android.os.Build

actual fun defaultRaftPushDeliveryDeviceContext(): RaftPushDeliveryDeviceContext =
    RaftPushDeliveryDeviceContext(
        manufacturer = normalizePushDeliveryManufacturer(Build.MANUFACTURER, Build.BRAND),
        osVersion = Build.VERSION.SDK_INT.toString(),
        gmsAvailable = null,
        dndActive = null,
    )
