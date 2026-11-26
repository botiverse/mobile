package build.raft.app.data

actual fun defaultRaftPushDeliveryDeviceContext(): RaftPushDeliveryDeviceContext =
    RaftPushDeliveryDeviceContext(
        manufacturer = "other",
        osVersion = "unknown",
        gmsAvailable = null,
        dndActive = null,
    )
