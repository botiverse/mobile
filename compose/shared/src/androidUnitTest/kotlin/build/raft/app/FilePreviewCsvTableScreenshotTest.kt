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
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A CSV attachment's preview page with the table (web `CsvPreviewPane`): "CSV data preview" and
 * the row / column count, the header row (an empty header reads "Column N"), striped rows, a
 * table wider than the phone that pans sideways, and the truncation line. Light and dark;
 * verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class FilePreviewCsvTableScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun csvTableLight() = capture("file.preview.csv.table", RaftAppAppearance.Light)

    @Test
    fun csvTableDark() = capture("file.preview.csv.table.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            // The page root (RaftCmpRoot) provides the design-system theme in the app.
            RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { CsvPreviewPage(csvTablePayload) }
            }
        }
}

private val csvTableCarrier =
    RaftFilePreviewCsvCarrier(
        headers = listOf("id", "name", "", "region", "signups", "owner", "notes"),
        rows =
            List(24) { index ->
                listOf(
                    "${index + 1}",
                    listOf("alpha", "beta", "gamma", "delta")[index % 4] + "-${index + 1}",
                    if (index % 3 == 0) "\"quoted, value\"" else "",
                    listOf("EMEA", "APAC", "AMER")[index % 3],
                    "${(index + 1) * 37}",
                    listOf("koda", "martin", "lagoon")[index % 3],
                    "row ${index + 1} has a long note that the cell truncates"
                )
            },
        rowCount = 5000,
        columnCount = 7,
        truncated = true
    )

internal val csvTablePayload =
    RaftFilePreviewPayload(
        kind = "csv",
        requestedKind = "text",
        name = "signups-2026-q3.csv",
        path = "signups-2026-q3.csv",
        meta = "412 KB",
        externalFileName = "signups-2026-q3.csv",
        externalMimeType = "text/csv",
        source = RaftFilePreviewSourceThreadAttachment,
        attachmentId = "att-csv",
        messageDesc = null,
        csvTableJson = encodeRaftFilePreviewCsv(csvTableCarrier)
    )

@Composable
internal fun CsvPreviewPage(payload: RaftFilePreviewPayload) {
    val view = RaftFilePreviewDownloadView(phase = RaftFilePreviewDownloadPhase.Idle)
    RaftFilePreviewPageScaffold(
        title = payload.previewHeaderTitle(),
        subtitle = payload.previewHeaderSubtitle(),
        iconName = payload.previewIconName(),
        onBack = {}
    ) {
        RaftFilePreviewPayloadContent(
            payload = payload,
            onOpenUrl = {},
            downloadStatusLabel = null,
            downloadInProgress = view.inProgress,
            downloadView = view,
            downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
            onDownload = {},
            onPauseDownload = {},
            onResumeDownload = {},
            onStopDownload = {},
            onExternalBrowserOpen = {},
            onShare = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}
