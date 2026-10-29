package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ConversationArtifact
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The channel Files tab with its Artifacts (web #11580 `ChannelFilesArtifactRow.tsx`), light and
 * dark: the conversation's Artifacts lead the list as one raft-ui ListRowGroup (kind tile, title,
 * version, updated time), Show More under them while there are more pages, then the files as
 * before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class ThreadFilesArtifactsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun filesTabArtifactsLight() = capture("thread.files-tab.artifacts", RaftAppAppearance.Light)

    @Test
    fun filesTabArtifactsDark() = capture("thread.files-tab.artifacts.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        val timeContext =
            RaftTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = raftEpochDayForDate(2026, 10, 28),
                nowEpochMillis = 1_793_152_800_000L,
            )
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/thread-screenshots", activities = activities) {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
                Box(Modifier.fillMaxSize()) {
                    ThreadFilesContent(
                        payload = filesPayload,
                        artifacts = ThreadFilesArtifactsState(items = artifacts, nextCursor = "next-artifacts"),
                        onEvent = { _, _ -> },
                    )
                }
            }
        }
    }
}

private fun artifact(id: String, title: String, kind: ArtifactKind, version: Int, updatedAt: String) =
    ConversationArtifact(
        summary =
            ArtifactSummary(
                id = id,
                title = title,
                description = null,
                kind = kind,
                version = version,
                pinned = false,
                latestVersion = version,
                home = ArtifactHome.Conversation("proj-workspace", dm = false),
            ),
        updatedAt = updatedAt,
    )

private val artifacts =
    listOf(
        artifact("pt_launch", "Launch plan", ArtifactKind.Html, 3, "2026-10-28T01:50:00Z"),
        artifact("pt_metrics", "Weekly metrics", ArtifactKind.Csv, 1, "2026-10-27T09:10:00Z"),
        artifact("pt_spec", "Files tab Artifacts spec with a long title that truncates", ArtifactKind.Markdown, 12, "2026-10-20T13:00:00Z"),
    )

private val filesPayload =
    RaftThreadFilesPayload(
        instanceId = "files-tab-artifacts",
        status = "content",
        items =
            listOf(
                RaftThreadFileItem(
                    attachmentId = "file-pdf",
                    messageId = "msg-pdf",
                    name = "release-notes.pdf",
                    sizeLabel = "428 KB",
                    createdAtLabel = "Jun 25, 18:17",
                    mimeType = "application/pdf",
                ),
                RaftThreadFileItem(
                    attachmentId = "file-zip",
                    messageId = "msg-zip",
                    name = "android-smoke-log.zip",
                    sizeLabel = "1.8 MB",
                    createdAtLabel = "Jun 25, 18:08",
                    mimeType = "application/zip",
                ),
            ),
    )
