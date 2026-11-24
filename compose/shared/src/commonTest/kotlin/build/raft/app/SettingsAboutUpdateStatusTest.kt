package build.raft.app

import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsUpdateTransactionState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings › About › Check for update: the trailing text is localized copy, never the host's English word. */
class SettingsAboutUpdateStatusTest {
    private val copy =
        SettingsAboutPayloadCopy(
            checking = "检查中",
            check = "检查",
            updateAvailable = "有可用更新",
            preparing = "准备中",
            downloading = "下载中",
            installing = "安装中",
            install = "安装",
            installed = "已安装",
            retry = "重试",
            permissionRequired = "需要权限",
            updateUnavailable = "更新信息暂时不可用。"
        )

    private fun status(snapshot: SharedSettingsUpdateCheckSnapshot, busy: Boolean = false) =
        settingsAboutUpdateStatus(snapshot, busy, copy)

    @Test
    fun informativeStatesAreLocalized() {
        assertEquals("检查中", status(SharedSettingsUpdateCheckSnapshot(supported = true, statusLabel = "Current"), busy = true))
        assertEquals(
            "准备中",
            status(
                SharedSettingsUpdateCheckSnapshot(
                    supported = true,
                    transactionState = SharedSettingsUpdateTransactionState.Patching,
                    statusLabel = "Preparing",
                    updateAvailable = true
                ),
                busy = true
            )
        )
        assertEquals(
            "下载中",
            status(
                SharedSettingsUpdateCheckSnapshot(
                    supported = true,
                    transactionState = SharedSettingsUpdateTransactionState.Downloading,
                    statusLabel = "Downloading",
                    updateAvailable = true
                ),
                busy = true
            )
        )
        assertEquals("有可用更新", status(SharedSettingsUpdateCheckSnapshot(supported = true, statusLabel = "Update available", updateAvailable = true)))
        assertEquals("下载中", status(SharedSettingsUpdateCheckSnapshot(supported = true, statusLabel = "Downloading", updateAvailable = true)))
        assertEquals("安装", status(SharedSettingsUpdateCheckSnapshot(supported = true, statusLabel = "Install", updateAvailable = true)))
        assertEquals(
            "准备中",
            status(SharedSettingsUpdateCheckSnapshot(supported = true, transactionState = SharedSettingsUpdateTransactionState.Patching, statusLabel = "Preparing"))
        )
        assertEquals(
            "已安装",
            status(SharedSettingsUpdateCheckSnapshot(supported = true, transactionState = SharedSettingsUpdateTransactionState.Installed, statusLabel = "Installed"))
        )
        assertEquals(
            "需要权限",
            status(
                SharedSettingsUpdateCheckSnapshot(
                    supported = true,
                    statusLabel = "Permission required",
                    updateAvailable = true,
                    errorCode = "install_permission_required"
                )
            )
        )
    }

    @Test
    fun failuresOfferRetry() {
        assertEquals("重试", status(SharedSettingsUpdateCheckSnapshot(supported = true, statusLabel = "Check failed", errorCode = "update_check_failed")))
        assertEquals("重试", status(SharedSettingsUpdateCheckSnapshot(supported = true, statusLabel = "Install failed", updateAvailable = true, errorCode = "install_intent_failed")))
        assertEquals(
            "重试",
            status(SharedSettingsUpdateCheckSnapshot(supported = true, transactionState = SharedSettingsUpdateTransactionState.Failed, statusLabel = "Download"))
        )
    }
}
