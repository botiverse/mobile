package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftUiMenuItem
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The file page an attachment without an in-app preview opens to (task #75: a tap on an apk in
 * the conversation opens this page; the download runs here). Three states, light and dark:
 * idle (Download), downloading (progress card with percent, bytes, speed, Pause / Cancel) and
 * done (Install for an apk, Share, Open in browser). Rendered through the page's own scaffold
 * and [RaftFilePreviewPayloadContent]; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class FileDetailPageScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun idleLight() = capture("file.detail.idle", RaftAppAppearance.Light, FileDetailState.Idle)

    @Test
    fun idleDark() = capture("file.detail.idle.dark", RaftAppAppearance.Dark, FileDetailState.Idle)

    @Test
    fun downloadingLight() = capture("file.detail.downloading", RaftAppAppearance.Light, FileDetailState.Downloading)

    @Test
    fun downloadingDark() = capture("file.detail.downloading.dark", RaftAppAppearance.Dark, FileDetailState.Downloading)

    @Test
    fun doneLight() = capture("file.detail.done", RaftAppAppearance.Light, FileDetailState.Done)

    @Test
    fun doneDark() = capture("file.detail.done.dark", RaftAppAppearance.Dark, FileDetailState.Done)

    private fun capture(name: String, appearance: RaftAppAppearance, state: FileDetailState) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { FileDetailPage(state) }
        }
}

private enum class FileDetailState { Idle, Downloading, Done }

private val apkPayload =
    RaftFilePreviewPayload(
        kind = "unsupported",
        requestedKind = "file",
        name = "raft-1.15.3-alpha.apk",
        path = "raft-1.15.3-alpha.apk",
        meta = "43.6 MB",
        externalFileName = "raft-1.15.3-alpha.apk",
        externalMimeType = "application/vnd.android.package-archive",
        source = RaftFilePreviewSourceThreadAttachment,
        attachmentId = "att-apk"
    )

@Composable
private fun FileDetailPage(state: FileDetailState) {
    val payload = apkPayload
    val downloadState =
        when (state) {
            FileDetailState.Idle -> RaftFilePreviewDownloadUiState.NotDownloaded
            FileDetailState.Downloading -> RaftFilePreviewDownloadUiState.Downloading
            FileDetailState.Done -> RaftFilePreviewDownloadUiState.Downloaded
        }
    val view =
        if (state == FileDetailState.Downloading) {
            RaftFilePreviewDownloadView(
                phase = RaftFilePreviewDownloadPhase.Active,
                percent = 42,
                downloadedBytes = 19_293_798L,
                totalBytes = 45_717_094L,
                bytesPerSecond = 2_516_582L
            )
        } else {
            RaftFilePreviewDownloadView(phase = RaftFilePreviewDownloadPhase.Idle)
        }
    RaftFilePreviewPageScaffold(
        title = payload.previewHeaderTitle(),
        subtitle = payload.previewHeaderSubtitle(),
        iconName = payload.previewIconName(),
        onBack = {},
        overflowMenuItems =
            listOf(RaftUiMenuItem(id = "open-with", label = "Open with", icon = RaftLucideIconName.ExternalLink))
    ) {
        RaftFilePreviewPayloadContent(
            payload = payload,
            onOpenUrl = {},
            downloadStatusLabel = null,
            downloadInProgress = view.inProgress,
            downloadView = view,
            downloadState = downloadState,
            onDownload = {},
            onPauseDownload = {},
            onResumeDownload = {},
            onStopDownload = {},
            onExternalBrowserOpen = {},
            onShare =
                if (raftFilePreviewShowsShareAction(canOpenWith = true, downloadState = downloadState)) ({}) else null,
            modifier = Modifier.fillMaxSize()
        )
    }
}
