package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.resources.*
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A file that cannot be previewed opens to the download card (web downloads instead of
 * previewing), here a spreadsheet the server says is too large; a load error keeps the error and
 * offers Retry. Light and dark, through the page's own scaffold and [RaftFilePreviewPayloadContent];
 * verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class FilePreviewNoPreviewScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun tooLargeLight() = capture("file.preview.no-preview.too-large", RaftAppAppearance.Light, tooLargePayload)

    @Test
    fun tooLargeDark() = capture("file.preview.no-preview.too-large.dark", RaftAppAppearance.Dark, tooLargePayload)

    @Test
    fun loadErrorLight() = capture("file.preview.load-error.retry", RaftAppAppearance.Light, loadErrorPayload)

    @Test
    fun loadErrorDark() = capture("file.preview.load-error.retry.dark", RaftAppAppearance.Dark, loadErrorPayload)

    private fun capture(name: String, appearance: RaftAppAppearance, payload: RaftFilePreviewPayload) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { NoPreviewPage(payload) }
        }
}

private val spreadsheet =
    RaftFilePreviewPayload(
        requestedKind = "xlsx",
        name = "q3-pipeline-export.xlsx",
        path = "q3-pipeline-export.xlsx",
        meta = "38.2 MB",
        externalFileName = "q3-pipeline-export.xlsx",
        externalMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        source = RaftFilePreviewSourceThreadAttachment,
        attachmentId = "att-xlsx"
    )

private val tooLargePayload = spreadsheet.withNoPreviewDownload(RaftFilePreviewNoPreviewReasonTooLarge)

private val loadErrorPayload =
    spreadsheet.copy(
        requestedKind = "text",
        name = "botiverse-pages.tsv",
        path = "botiverse-pages.tsv",
        meta = "17.3 KB",
        externalFileName = "botiverse-pages.tsv",
        externalMimeType = "application/octet-stream",
        kind = "error",
        titleDesc = Res.string.file_preview_unavailable.desc(),
        messageDesc = Res.string.file_preview_error_load_text.desc(),
        error = "text_preview_fetch_failed"
    )

@Composable
private fun NoPreviewPage(payload: RaftFilePreviewPayload) {
    RaftFilePreviewPageScaffold(
        title = payload.previewHeaderTitle(),
        subtitle = payload.previewHeaderSubtitle(),
        iconName = payload.previewIconName(),
        onBack = {},
    ) {
        RaftFilePreviewPayloadContent(
            payload = payload,
            onOpenUrl = {},
            downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
            onDownload = {},
            onExternalBrowserOpen = {},
            onRetryLoad = if (payload.canRetryPreviewLoad()) ({}) else null,
            modifier = Modifier.fillMaxSize()
        )
    }
}
