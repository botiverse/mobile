package build.raft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArtifactDeletedShown
import build.raft.app.data.ArtifactFacts
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactScreenshot
import build.raft.app.data.ArtifactScreenshotReader
import build.raft.app.data.ArtifactSummary
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Artifact cards under a message (web `ArtifactMessageRefs.tsx` / `ArtifactMessageCard.tsx`,
 * botiverse/slock staging 84e41d9be), light and dark, on a fixed clock:
 *  1. an HTML card: the code glyph with its HTML badge, title and v4, the description (2 lines: HTML
 *     has no fact line), ↗, and the footer: `# proj-workspace`, "Lagoon updated 2 hours ago", 3 comments;
 *  2. a PDF pinned at v2 of 5: "Latest is v5 · 6 pages", the description cut at one line, the whole
 *     server, no comments;
 *  3. a deleted one: gray and dimmed, its title and "Deleted by Martin 1 day ago".
 *
 * `thread.artifact-message-cards.screenshot`: the publish check's screenshot above the header (slock
 * #13046): a card whose phone shot is shown (this window is phone width), cover and top-aligned
 * (the shot's top band stays, its bottom is cut), 16:10 at most 192dp; then a card still reading its
 * desktop shot, its place kept empty on `fill-muted`. A fake reader answers in place of the network
 * and Coil's preview handler draws each file as a flat fixture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h700dp-xxhdpi")
class ThreadArtifactMessageCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun artifactMessageCardsLight() = capture("thread.artifact-message-cards", RaftAppAppearance.Light)

    @Test
    fun artifactMessageCardsDark() = capture("thread.artifact-message-cards.dark", RaftAppAppearance.Dark)

    @Test
    fun artifactMessageCardScreenshotLight() = captureScreenshots("thread.artifact-message-cards.screenshot", RaftAppAppearance.Light)

    @Test
    fun artifactMessageCardScreenshotDark() = captureScreenshots("thread.artifact-message-cards.screenshot.dark", RaftAppAppearance.Dark)

    @OptIn(ExperimentalCoilApi::class)
    private fun captureScreenshots(name: String, appearance: RaftAppAppearance) {
        // As ThreadMessageAttachmentsScreenshotTest: the app's loader first, then Coil's preview handler.
        runCatching { build.raft.app.image.raftInstallImageLoader() }
        val now = 1_792_635_000_000L
        val timeContext = RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = raftEpochDayForDate(2026, 10, 22), nowEpochMillis = now)
        val ago = { millis: Long -> Instant.ofEpochMilli(now - millis).toString() }
        val shown =
            ArtifactSummary(
                id = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11",
                title = "Q3 revenue report",
                description = "Revenue by region with the October forecast and the open questions for finance.",
                kind = ArtifactKind.Html,
                version = 4,
                pinned = false,
                latestVersion = 4,
                home = ArtifactHome.Conversation("proj-workspace", dm = false),
                authorName = "Lagoon",
                updatedAt = ago(2 * 60 * 60 * 1000L),
                commentCount = 3,
                facts = ArtifactFacts(sizeBytes = 24_000),
                screenshot = ArtifactScreenshot(desktop = "of_desktop", phone = "of_phone"),
            )
        val pending =
            shown.copy(
                id = "pt_7f3c2a10-5b9e-4d61-8c2f-1a2b3c4d5e6f",
                title = "Launch dashboard",
                description = "Sign-ups and activation for the launch week.",
                commentCount = 0,
                screenshot = ArtifactScreenshot(desktop = "of_pending", phone = null),
            )
        val view =
            ThreadArtifactMessageRefsView.Cards(
                listOf(
                    ThreadArtifactMessageRef.Card("a", ArtifactLinkTarget(shown.id), shown),
                    ThreadArtifactMessageRef.Card("b", ArtifactLinkTarget(pending.id), pending),
                ),
            )
        val reader =
            object : ArtifactScreenshotReader {
                override fun kept(cardId: String, fileId: String): ByteArray? = fileId.takeIf { it != "of_pending" }?.encodeToByteArray()

                override suspend fun read(cardId: String, fileId: String): ByteArray? = awaitCancellation()
            }
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FixtureScreenshots,
                LocalArtifactScreenshotReader provides reader,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp)) {
                        ThreadArtifactMessageRefsContent(view = view, onOpen = {}, onOpenInNewPage = {})
                    }
                }
            }
        }
    }

    private fun capture(name: String, appearance: RaftAppAppearance) {
        val now = 1_792_635_000_000L
        val timeContext = RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = raftEpochDayForDate(2026, 10, 22), nowEpochMillis = now)
        val ago = { millis: Long -> Instant.ofEpochMilli(now - millis).toString() }
        val html =
            ArtifactSummary(
                id = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11",
                title = "Q3 revenue report",
                description = "Revenue by region with the October forecast and the open questions for finance.",
                kind = ArtifactKind.Html,
                version = 4,
                pinned = false,
                latestVersion = 4,
                home = ArtifactHome.Conversation("proj-workspace", dm = false),
                authorName = "Lagoon",
                updatedAt = ago(2 * 60 * 60 * 1000L),
                commentCount = 3,
                facts = ArtifactFacts(sizeBytes = 24_000),
            )
        val pdf =
            ArtifactSummary(
                id = "pt_7f3c2a10-5b9e-4d61-8c2f-1a2b3c4d5e6f",
                title = "Onboarding checklist",
                description = "Everything a new teammate needs in the first week, from accounts to the first review.",
                kind = ArtifactKind.Pdf,
                version = 2,
                pinned = true,
                latestVersion = 5,
                home = ArtifactHome.Server,
                authorName = "Niko",
                updatedAt = ago(3 * 24 * 60 * 60 * 1000L),
                facts = ArtifactFacts(sizeBytes = 400_000, pages = 6),
            )
        val view =
            ThreadArtifactMessageRefsView.Cards(
                listOf(
                    ThreadArtifactMessageRef.Card("a", ArtifactLinkTarget(html.id), html),
                    ThreadArtifactMessageRef.Card("b", ArtifactLinkTarget(pdf.id, 2), pdf),
                    ThreadArtifactMessageRef.Deleted("c", ArtifactDeletedShown("Launch plan draft", "Martin", ago(26 * 60 * 60 * 1000L))),
                ),
            )
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp)) {
                        ThreadArtifactMessageRefsContent(view = view, onOpen = {}, onOpenInNewPage = {})
                    }
                }
            }
        }
    }
}

/**
 * Each screenshot file as a fixture: the phone shot tall (390 x 844) with a dark top band, so the
 * card's top-aligned cover shows its top; any other file fails.
 */
@OptIn(ExperimentalCoilApi::class)
private val FixtureScreenshots =
    AsyncImagePreviewHandler { _, request ->
        val file = (request.data as? ByteArray)?.decodeToString()
        if (file != "of_phone") {
            AsyncImagePainter.State.Error(null, ErrorResult(null, request, IllegalStateException("fixture: no image")))
        } else {
            val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF8EC9F0.toInt()) }
            Canvas(bitmap).drawRect(0f, 0f, 390f, 72f, Paint().apply { color = 0xFF1F3A5F.toInt() })
            Canvas(bitmap).drawRect(24f, 120f, 366f, 300f, Paint().apply { color = 0xFFFFFFFF.toInt() })
            val image = bitmap.asImage()
            AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
        }
    }
