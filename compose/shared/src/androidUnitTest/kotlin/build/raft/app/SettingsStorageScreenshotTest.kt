package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsStorageRow
import build.raft.app.data.SharedSettingsStorageSnapshot
import build.raft.app.downloads.RaftDownloadEntry
import build.raft.app.transfer.testTransfer
import build.raft.app.downloads.RaftDownloadErrorKind
import build.raft.app.downloads.RaftDownloadStatus
import build.raft.app.model.RaftTimeContext
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Storage, light and dark (English) and light in zh-CN: the download centre (two
 * finished files, one downloading, one failed), the total with its refresh, and the four
 * storage categories with their clear actions. Fixed fixture data and a fixed clock, so the
 * download dates read as relative times. Reports land in `build/reports/home-screenshots/`;
 * verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp-xxhdpi")
class SettingsStorageScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun settingsStorageLight() = capture("settings.storage", RaftAppAppearance.Light)

    @Test
    fun settingsStorageDark() = capture("settings.storage.dark", RaftAppAppearance.Dark)

    @Test
    @Config(qualifiers = "zh-rCN-w390dp-h1500dp-xxhdpi")
    fun settingsStorageZhLight() = capture("settings.storage.zh", RaftAppAppearance.Light)

    @Test
    @Config(qualifiers = "zh-rCN-w390dp-h1500dp-xxhdpi")
    fun settingsStorageZhDark() = capture("settings.storage.zh.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            val timeContext =
                remember {
                    RaftTimeContextProvider(
                        RaftTimeContext(localOffsetMinutes = 0, timeFormat = "24h").refreshedAt(StorageFixtureNowMillis)
                    )
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides timeContext,
            ) { build.raft.app.components.RaftDesignSystemTheme { SettingsStorageVisualContent() } }
        }
}

/** 2026-10-23T01:50:00Z. */
private const val StorageFixtureNowMillis = 1_792_720_200_000L
private const val Minute = 60_000L

private val storageFixtureDownloads =
    listOf(
        RaftDownloadEntry(
            id = "d1",
            ownerKey = "fixture",
            attachmentId = "a1",
            fileName = "androidApp-alpha-1.15.4-arm64-v8a-release.apk",
            mimeType = "application/vnd.android.package-archive",
            status = RaftDownloadStatus.Downloading,
            downloadedBytes = 41_200_000L,
            totalBytes = 102_760_448L,
            // The owner's meter state for these bytes (40%).
            transfer = testTransfer(downloadedBytes = 41_200_000L, totalBytes = 102_760_448L, percent = 40),
            createdAtMillis = StorageFixtureNowMillis - Minute,
            updatedAtMillis = StorageFixtureNowMillis,
        ),
        RaftDownloadEntry(
            id = "d2",
            ownerKey = "fixture",
            attachmentId = "a2",
            fileName = "quarterly-design-review-notes-final.pdf",
            mimeType = "application/pdf",
            status = RaftDownloadStatus.Completed,
            downloadedBytes = 3_040_870L,
            totalBytes = 3_040_870L,
            createdAtMillis = StorageFixtureNowMillis - 6 * Minute,
            updatedAtMillis = StorageFixtureNowMillis - 5 * Minute,
        ),
        RaftDownloadEntry(
            id = "d3",
            ownerKey = "fixture",
            attachmentId = "a3",
            fileName = "raft-test-archive-2026-10-22.zip",
            mimeType = "application/zip",
            status = RaftDownloadStatus.Completed,
            downloadedBytes = 110_834_483L,
            totalBytes = 110_834_483L,
            createdAtMillis = StorageFixtureNowMillis - 181 * Minute,
            updatedAtMillis = StorageFixtureNowMillis - 180 * Minute,
        ),
        RaftDownloadEntry(
            id = "d4",
            ownerKey = "fixture",
            attachmentId = "a4",
            fileName = "screen-recording.mp4",
            mimeType = "video/mp4",
            status = RaftDownloadStatus.Failed,
            errorKind = RaftDownloadErrorKind.Interrupted,
            createdAtMillis = StorageFixtureNowMillis - 2 * 1440 * Minute,
            updatedAtMillis = StorageFixtureNowMillis - 2 * 1440 * Minute,
        ),
    )

private val storageFixtureSnapshot =
    SharedSettingsStorageSnapshot(
        totalSizeLabel = "74.8 MB",
        rows =
            listOf(
                SharedSettingsStorageRow(
                    target = "database",
                    title = "Local database",
                    detail = "Messages, task metadata, and server snapshots stored on this device.",
                    sizeLabel = "30.0 MB",
                ),
                SharedSettingsStorageRow(
                    target = "imageCache",
                    title = "Image cache",
                    detail = "Downloaded image previews kept for faster viewing.",
                    sizeLabel = "43.1 MB",
                    clearEnabled = true,
                ),
                SharedSettingsStorageRow(
                    target = "attachmentDownloads",
                    title = "Attachment staging",
                    detail = "Temporary data from active or failed downloads. Completed files remain in Downloads.",
                    sizeLabel = "1.2 MB",
                    clearEnabled = true,
                ),
                SharedSettingsStorageRow(
                    target = "temporaryAndLogs",
                    title = "Temporary files and logs",
                    detail = "Debug logs and temporary files generated by the app.",
                    sizeLabel = "512 KB",
                    clearEnabled = true,
                ),
            ),
    )

@Composable
private fun SettingsStorageVisualContent() {
    SettingsDetailShell(
        title = build.raft.app.resources.stringResource(Res.string.settings_storage_title),
        subtitle = "",
        applyTopSafeArea = false,
        onBack = {},
    ) {
        SettingsStorageContent(
            payload =
                ScreenLoadState.Content(storageFixtureSnapshot).toStoragePayload(actionState = null, refreshing = false),
            onEvent = { _, _ -> },
            downloads = {
                RaftDownloadCenterSection(
                    entries = storageFixtureDownloads,
                    canRetry = { false },
                    onAction = {},
                    onClearFinished = {},
                    animated = false,
                )
            },
        )
    }
}
