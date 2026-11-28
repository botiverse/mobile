package build.raft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArtifactCardUnseen
import build.raft.app.data.ArtifactDeletedShown
import build.raft.app.data.ArtifactExcerpt
import build.raft.app.data.ArtifactExcerptBlock
import build.raft.app.data.ArtifactExcerptListItem
import build.raft.app.data.ArtifactExcerptSpan
import build.raft.app.data.ArtifactFacts
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactScreenshot
import build.raft.app.data.ArtifactScreenshotReader
import build.raft.app.data.ArtifactSettled
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactsHomeActor
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.thread.LocalArtifactScreenshotReader
import build.raft.app.thread.ThreadArtifactMessageRef
import build.raft.app.thread.ThreadArtifactMessageRefsContent
import build.raft.app.thread.ThreadArtifactMessageRefsView
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
 * botiverse/slock staging 84e41d9be; compact, #proj-mobile:#439), light and dark, on a fixed clock:
 *  1. an HTML card, settled, with news for the reader: the HTML type badge, the title, the Settled
 *     tag (the only status a card shows) and the accent New badge "Updated · 2 new comments" after
 *     it (Kirby, raft-design #1493), its description as the preview (2 lines), and the meta line "Lagoon · 2 hours
 *     ago · 3": the latest version says nothing of its version, and no channel tag;
 *  2. a PDF pinned at v2 of 4: its description, "Niko · 3 days ago · v2 · latest v4" (the only card
 *     naming its version), no server tag (a card names no scope anywhere);
 *  3. a deleted one: gray and dimmed, its title row and "Deleted by Martin 1 day ago" as its meta line.
 *
 * `thread.artifact-message-cards.screenshot`: the publish check's screenshot under the header (slock
 * #13046): a card whose phone shot is shown (this window is phone width), cover and top-aligned
 * (the shot's top band stays, its bottom is cut), at most 120dp on a phone; then a card still reading its
 * desktop shot, its place kept empty on `fill-muted`. `thread.artifact-message-cards.cover`: a
 * Markdown card with a publisher cover image and an excerpt: the cover is its preview (cut from the
 * top, 120dp on a phone) and no excerpt is drawn (Kirby, raft-design #1493). A fake reader answers in place of the network
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
    fun artifactMessageCardCoverLight() = captureScreenshots("thread.artifact-message-cards.cover", RaftAppAppearance.Light, cover = true)

    @Test
    fun artifactMessageCardExcerptLight() = captureExcerpt("thread.artifact-message-cards.excerpt", RaftAppAppearance.Light)

    @Test
    fun artifactMessageCardExcerptDark() = captureExcerpt("thread.artifact-message-cards.excerpt.dark", RaftAppAppearance.Dark)

    /**
     * `thread.artifact-message-cards.excerpt`: artin's card (#proj-mobile:#439) on a 390dp phone, a
     * Markdown Artifact with a long Chinese title whose document opens with that title as its `# `
     * heading, then a long body, linked in the channel it lives in: the title once on top, the
     * excerpt from the body (no `# ` title) in four lines, one footer line "Lagoon · 2 hours ago · 2".
     */
    private fun captureExcerpt(name: String, appearance: RaftAppAppearance) {
        val now = 1_792_635_000_000L
        val timeContext = RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = raftEpochDayForDate(2026, 10, 22), nowEpochMillis = now)
        val title = "手机端消息卡片紧凑化方案：标题只出现一次，预览不重复标题，底部只留一行信息"
        val body = "我们把桌面端那套什么都铺出来的信息量原封不动搬到了手机上：标题在预览区和标题栏各占一遍，预览区高度沿用桌面的上限，一张卡就吃掉近半屏。这一版先拿 Artifact 卡片做，其他消息卡片照同一套规则跟进。"
        val excerpt =
            ArtifactExcerpt(
                text = "$title\n\n$body\n\n改动范围\n\n标题行在最上面\n正文预览最多四行\n底部一行小字",
                blocks =
                    listOf(
                        ArtifactExcerptBlock.Heading(1, listOf(ArtifactExcerptSpan(title))),
                        ArtifactExcerptBlock.Paragraph(listOf(ArtifactExcerptSpan(body))),
                        ArtifactExcerptBlock.Heading(2, listOf(ArtifactExcerptSpan("改动范围"))),
                        ArtifactExcerptBlock.ListBlock(
                            listOf(
                                ArtifactExcerptListItem(level = 0, spans = listOf(ArtifactExcerptSpan("标题行在最上面"))),
                                ArtifactExcerptListItem(level = 0, spans = listOf(ArtifactExcerptSpan("正文预览最多四行"))),
                                ArtifactExcerptListItem(level = 0, spans = listOf(ArtifactExcerptSpan("底部一行小字"))),
                            ),
                        ),
                    ),
            )
        val card =
            ArtifactSummary(
                id = "pt_3c1d9e44-7a2b-4f0e-b6d1-5e8f9a0b1c2d",
                title = title,
                description = body,
                descriptionIsExcerpt = true,
                kind = ArtifactKind.Markdown,
                version = 3,
                pinned = false,
                latestVersion = 3,
                home = ArtifactHome.Conversation("wg-live", dm = false),
                authorName = "Lagoon",
                updatedAt = Instant.ofEpochMilli(now - 2 * 60 * 60 * 1000L).toString(),
                commentCount = 2,
                facts = ArtifactFacts(sizeBytes = 6_000),
                excerpt = excerpt,
            )
        val view = ThreadArtifactMessageRefsView.Cards(listOf(ThreadArtifactMessageRef.Card("a", ArtifactLinkTarget(card.id), card)))
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp)) {
                        ThreadArtifactMessageRefsContent(view = view, onOpen = {})
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun captureScreenshots(name: String, appearance: RaftAppAppearance, cover: Boolean = false) {
        // As ThreadMessageAttachmentsScreenshotTest: the app's loader first, then Coil's preview handler.
        build.raft.app.image.raftInstallImageLoader()
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
        val covered =
            shown.copy(
                id = "pt_5d2e8b31-0c4f-4a7e-b915-3e6f7a8b9c0d",
                title = "Brand refresh notes",
                description = "What changes in the logo, the palette and the type.",
                kind = ArtifactKind.Markdown,
                commentCount = 1,
                screenshot = null,
                coverPath = "assets/cover.png",
                excerpt = ArtifactExcerpt(text = "The excerpt a cover replaces.", blocks = listOf(ArtifactExcerptBlock.Paragraph(listOf(ArtifactExcerptSpan("The excerpt a cover replaces."))))),
            )
        val view =
            ThreadArtifactMessageRefsView.Cards(
                if (cover) {
                    listOf(ThreadArtifactMessageRef.Card("c", ArtifactLinkTarget(covered.id), covered))
                } else {
                    listOf(
                        ThreadArtifactMessageRef.Card("a", ArtifactLinkTarget(shown.id), shown),
                        ThreadArtifactMessageRef.Card("b", ArtifactLinkTarget(pending.id), pending),
                    )
                },
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
                        ThreadArtifactMessageRefsContent(view = view, onOpen = {})
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
                settled = ArtifactSettled(ArtifactsHomeActor("agent", "a1", "remy", "Remy"), ago(60 * 60 * 1000L), 4),
                unseen = ArtifactCardUnseen(newerVersion = true, newComments = 2, firstNewComment = null),
            )
        val pdf =
            ArtifactSummary(
                id = "pt_7f3c2a10-5b9e-4d61-8c2f-1a2b3c4d5e6f",
                title = "Onboarding checklist",
                description = "Everything a new teammate needs in the first week, from accounts to the first review.",
                kind = ArtifactKind.Pdf,
                version = 2,
                pinned = true,
                latestVersion = 4,
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
                        ThreadArtifactMessageRefsContent(view = view, onOpen = {})
                    }
                }
            }
        }
    }
}

/**
 * Each screenshot file as a fixture: the phone shot tall (390 x 844) with a dark top band, so the
 * card's top-aligned cover shows its top; the publisher cover (`assets/cover.png`) wide and warm;
 * any other file fails.
 */
@OptIn(ExperimentalCoilApi::class)
private val FixtureScreenshots =
    AsyncImagePreviewHandler { _, request ->
        val file = (request.data as? ByteArray)?.decodeToString()
        if (file != null && file.endsWith("assets/cover.png")) {
            val bitmap = Bitmap.createBitmap(1200, 630, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFF4C98E.toInt()) }
            Canvas(bitmap).drawRect(80f, 160f, 700f, 260f, Paint().apply { color = 0xFF5F3A1F.toInt() })
            val image = bitmap.asImage()
            AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
        } else if (file != "of_phone") {
            AsyncImagePainter.State.Error(null, ErrorResult(null, request, IllegalStateException("fixture: no image")))
        } else {
            val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF8EC9F0.toInt()) }
            Canvas(bitmap).drawRect(0f, 0f, 390f, 72f, Paint().apply { color = 0xFF1F3A5F.toInt() })
            Canvas(bitmap).drawRect(24f, 120f, 366f, 300f, Paint().apply { color = 0xFFFFFFFF.toInt() })
            val image = bitmap.asImage()
            AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
        }
    }
