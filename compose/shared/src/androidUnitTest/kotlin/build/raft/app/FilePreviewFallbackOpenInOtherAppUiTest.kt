package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #proj-workspace:#396: File Preview fallback cards offer "Open in another app" (via the native
 * open-with / share-sheet path) instead of "Open in browser", and suppress the secondary button
 * whenever the card already exposes an equivalent Open action (Downloaded or Desktop saved file).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class FilePreviewFallbackOpenInOtherAppUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Calls {
        var openInOtherApp = 0
        var openUrl = 0
        var download = 0
    }

    private fun ComposeUiTest.showPreview(
        payload: RaftFilePreviewPayload,
        calls: Calls,
        downloadState: RaftFilePreviewDownloadUiState? = RaftFilePreviewDownloadUiState.NotDownloaded,
        primaryActionEnabled: Boolean = true,
        savedFileActions: RaftFilePreviewSavedFileActions? = null,
        hasDownload: Boolean = true,
        hasOpenInOtherApp: Boolean = true,
    ) {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftFilePreviewPayloadContent(
                        payload = payload,
                        onOpenUrl = { calls.openUrl++ },
                        downloadState = downloadState,
                        primaryActionEnabled = primaryActionEnabled,
                        savedFileActions = savedFileActions,
                        onDownload = if (hasDownload) ({ calls.download++ }) else null,
                        onOpenInOtherApp = if (hasOpenInOtherApp) ({ calls.openInOtherApp++ }) else null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun unsupportedFallbackOffersOpenInAnotherAppAndDispatchesNativeOpen() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            val payload =
                RaftFilePreviewPayload(
                    kind = "unsupported",
                    requestedKind = "file",
                    name = "archive.zip",
                    path = "archive.zip",
                    meta = "12.4 MB",
                    externalFileName = "archive.zip",
                    externalMimeType = "application/zip",
                    source = RaftFilePreviewSourceThreadAttachment,
                    attachmentId = "att-zip"
                )
            showPreview(payload, calls, downloadState = RaftFilePreviewDownloadUiState.NotDownloaded)

            onNodeWithText("Download").assertExists()
            onNodeWithText("Open in browser").assertDoesNotExist()
            onNodeWithText("Open in another app").assertExists().assertIsEnabled().performClick()
            waitForIdle()

            assertEquals(1, calls.openInOtherApp)
            assertEquals(0, calls.openUrl)
            assertEquals(0, calls.download)
        }

    @Test
    fun downloadedUnsupportedFileHidesDuplicateOpenInAnotherAppButton() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            val payload =
                RaftFilePreviewPayload(
                    kind = "unsupported",
                    requestedKind = "file",
                    name = "archive.zip",
                    path = "archive.zip",
                    meta = "12.4 MB",
                    externalFileName = "archive.zip",
                    externalMimeType = "application/zip",
                    source = RaftFilePreviewSourceThreadAttachment,
                    attachmentId = "att-zip"
                )
            showPreview(payload, calls, downloadState = RaftFilePreviewDownloadUiState.Downloaded)

            onNodeWithText("Open").assertExists()
            onNodeWithText("Open in another app").assertDoesNotExist()
            onNodeWithText("Open in browser").assertDoesNotExist()
        }

    @Test
    fun desktopSavedFileCardHidesDuplicateOpenInAnotherAppButton() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            val payload =
                RaftFilePreviewPayload(
                    kind = "unsupported",
                    requestedKind = "file",
                    name = "archive.zip",
                    path = "archive.zip",
                    meta = "12.4 MB",
                    externalFileName = "archive.zip",
                    externalMimeType = "application/zip",
                    source = RaftFilePreviewSourceThreadAttachment,
                    attachmentId = "att-zip"
                )
            showPreview(
                payload = payload,
                calls = calls,
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
                savedFileActions =
                    RaftFilePreviewSavedFileActions(
                        revealLabel = "Show in Finder",
                        openLabel = "Open",
                        message = "Saved to Downloads",
                        onReveal = {},
                        onOpen = {},
                    )
            )

            onNodeWithText("Show in Finder").assertExists()
            onNodeWithText("Open").assertExists()
            onNodeWithText("Open in another app").assertDoesNotExist()
            onNodeWithText("Open in browser").assertDoesNotExist()
        }

    @Test
    fun unavailableImageFallbackOffersOpenInAnotherAppUntilDownloaded() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            val payload =
                RaftFilePreviewPayload(
                    kind = "image",
                    requestedKind = "image",
                    name = "diagram.png",
                    path = "diagram.png",
                    meta = "2.1 MB",
                    imageUrl = null,
                    externalFileName = "diagram.png",
                    externalMimeType = "image/png",
                    source = RaftFilePreviewSourceThreadAttachment,
                    attachmentId = "att-img"
                )
            showPreview(payload, calls, downloadState = RaftFilePreviewDownloadUiState.NotDownloaded)

            onNodeWithText("Download").assertExists()
            onNodeWithText("Open in browser").assertDoesNotExist()
            onNodeWithText("Open in another app").assertExists().assertIsEnabled().performClick()
            waitForIdle()

            assertEquals(1, calls.openInOtherApp)
            assertEquals(0, calls.openUrl)
            assertEquals(0, calls.download)
        }

    @Test
    fun downloadedUnavailableImageHidesDuplicateOpenInAnotherAppButton() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            val payload =
                RaftFilePreviewPayload(
                    kind = "image",
                    requestedKind = "image",
                    name = "diagram.png",
                    path = "diagram.png",
                    meta = "2.1 MB",
                    imageUrl = null,
                    externalFileName = "diagram.png",
                    externalMimeType = "image/png",
                    source = RaftFilePreviewSourceThreadAttachment,
                    attachmentId = "att-img"
                )
            showPreview(payload, calls, downloadState = RaftFilePreviewDownloadUiState.Downloaded)

            onNodeWithText("Open").assertExists()
            onNodeWithText("Open in another app").assertDoesNotExist()
            onNodeWithText("Open in browser").assertDoesNotExist()
        }

    @Test
    fun csvFallbackOffersOpenInAnotherAppAndDispatchesNativeOpen() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            val payload =
                RaftFilePreviewPayload(
                    kind = "csv",
                    requestedKind = "text",
                    name = "metrics.csv",
                    path = "metrics.csv",
                    meta = "1.2 MB",
                    externalFileName = "metrics.csv",
                    externalMimeType = "text/csv",
                    csvTableJson = "",
                    source = RaftFilePreviewSourceThreadAttachment,
                    attachmentId = "att-csv"
                )
            showPreview(payload, calls, downloadState = RaftFilePreviewDownloadUiState.NotDownloaded)

            onNodeWithText("Open in browser").assertDoesNotExist()
            onNodeWithText("Open in another app").assertExists().assertIsEnabled().performClick()
            waitForIdle()

            assertEquals(1, calls.openInOtherApp)
            assertEquals(0, calls.openUrl)
        }

    @Test
    fun xlsxFallbackOffersOpenInAnotherAppAndRespectsPrimaryActionEnabled() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            val payload =
                RaftFilePreviewPayload(
                    kind = "xlsx",
                    requestedKind = "xlsx",
                    name = "budget.xlsx",
                    path = "budget.xlsx",
                    meta = "4.8 MB",
                    externalFileName = "budget.xlsx",
                    externalMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    xlsxSheetsJson = "",
                    source = RaftFilePreviewSourceThreadAttachment,
                    attachmentId = "att-xlsx"
                )
            showPreview(
                payload = payload,
                calls = calls,
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
                primaryActionEnabled = false
            )

            onNodeWithText("Open in browser").assertDoesNotExist()
            onNodeWithText("Open in another app").assertExists().assertIsNotEnabled()
            assertEquals(0, calls.openInOtherApp)
            assertEquals(0, calls.openUrl)
        }
}
