package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.base.BasePager
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedUpdateDownloadProgress
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.Page

@Page("compose_app_update_progress_visual", supportInLocal = true)
internal class AppUpdateProgressVisualFixture : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UpdateProgressVisualContent() }
    }
}

@Composable
private fun UpdateProgressVisualContent() {
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        val downloading = SharedSettingsUpdateCheckSnapshot(
            supported = true, updateAvailable = true, currentVersionLabel = "1.12.0", latestVersionLabel = "1.13.0",
            statusLabel = "Downloading", actionLabel = "Downloading"
        )
        for (progress in listOf(
            SharedUpdateDownloadProgress("1", 26_214_400L, 104_857_600L, "downloading"),
            SharedUpdateDownloadProgress("2", 26_214_400L, null, "downloading"),
            SharedUpdateDownloadProgress("3", 26_214_400L, 104_857_600L, "paused")
        )) {
            AppUpdateSummary(downloading, false, false, null, {}, {}, {}, progress)
        }
        AppUpdateSummary(downloading.copy(statusLabel = "Install", actionLabel = "Install"), false, false, null, {}, {}, {})
        AppUpdateSummary(downloading.copy(statusLabel = "Failed", actionLabel = "Download", errorCode = "settings_hands_update_download_failed"),
            false, false, "Failed", {}, {}, {})
    }
}
