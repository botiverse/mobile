package build.raft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.ArtifactExcerpt
import build.raft.app.data.ArtifactExcerptBlock
import build.raft.app.data.ArtifactExcerptListItem
import build.raft.app.data.ArtifactExcerptSpan
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactScreenshot
import build.raft.app.data.ArtifactScreenshotReader
import build.raft.app.data.ArtifactSettled
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactsHomeActor
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.app.thread.LocalArtifactScreenshotReader
import build.raft.designsystem.theme.RaftTheme
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.LocalAsyncImagePreviewHandler
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi screenshot regression tests for Artifacts Home grid tiles with top-right corner badges
 * on Markdown excerpt covers vs screenshot covers (#proj-workspace:#428, web botiverse/slock
 * #16751 `components.artifacts.tiles.*`):
 * 1. Markdown excerpt (rich blocks) + Public
 * 2. Markdown excerpt (plain `\n\n`) + Settled + comment badge
 * 3. Markdown excerpt (plain) + Unfollowed (dimmed)
 * 4. Markdown excerpt (plain) + multi-badge (Settled + Public)
 * 5. Markdown excerpt (plain) + no badges (server home, mentions pill)
 * 6. HTML screenshot cover + Public (overlaid at top-right 6dp, unchanged)
 */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h700dp-xxhdpi")
class ArtifactsHomeTileCornerScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun artifactsHomeTilesCornerLight() = capture("artifacts.home.tiles.corner", RaftAppAppearance.Light)

    @Test
    fun artifactsHomeTilesCornerDark() = capture("artifacts.home.tiles.corner.dark", RaftAppAppearance.Dark)

    @Test
    fun exportSideBySideVerificationPngWhenRequested() {
        val outPath = System.getenv("RAFT_ARTIFACT_BADGE_VERIFY_PNG")?.takeIf { it.isNotBlank() } ?: return
        build.raft.app.image.raftInstallImageLoader()
        var lightBmp: Bitmap? = null
        var darkBmp: Bitmap? = null
        RaftRobolectricScreenshot.render(RaftAppAppearance.Light, activities, content = { TileGridFixture() }) { _, bmp ->
            lightBmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        }
        RaftRobolectricScreenshot.render(RaftAppAppearance.Dark, activities, content = { TileGridFixture() }) { _, bmp ->
            darkBmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        }
        val light = requireNotNull(lightBmp)
        val dark = requireNotNull(darkBmp)
        val divider = 8
        val combined = Bitmap.createBitmap(light.width + divider + dark.width, maxOf(light.height, dark.height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        canvas.drawColor(0xFF7F7F7F.toInt())
        canvas.drawBitmap(light, 0f, 0f, null)
        canvas.drawBitmap(dark, (light.width + divider).toFloat(), 0f, Paint())
        val out = File(outPath)
        out.parentFile?.mkdirs()
        out.outputStream().use { combined.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun capture(name: String, appearance: RaftAppAppearance) {
        build.raft.app.image.raftInstallImageLoader()
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            TileGridFixture()
        }
    }

    @Composable
    private fun TileGridFixture() {
        val remy = ArtifactsHomeActor("agent", "a1", "remy", "Remy")
        val settled = ArtifactSettled(remy, "2026-11-18T14:59:00.000Z", 2)
        val timeContext =
            RaftTimeContext(
                localOffsetMinutes = 0,
                todayEpochDay = raftEpochDayForDate(2026, 11, 18),
                nowEpochMillis = 1_794_996_000_000L,
            )
        val reader =
            object : ArtifactScreenshotReader {
                override fun kept(cardId: String, fileId: String): ByteArray = fileId.encodeToByteArray()

                override suspend fun read(cardId: String, fileId: String): ByteArray? = awaitCancellation()
            }
        val items =
            listOf(
                FixtureTile(
                    row =
                        fixtureRow(
                            id = "tile-md-public",
                            title = "Shared Artifacts: public beta launch plan",
                            home = ArtifactHome.Public,
                            author = remy,
                            excerpt =
                                ArtifactExcerpt(
                                    text = "Everything we need before the public beta of shared Artifacts opens next week, and who owns each part of it.",
                                    blocks =
                                        listOf(
                                            ArtifactExcerptBlock.Paragraph(
                                                listOf(
                                                    ArtifactExcerptSpan(
                                                        "Everything we need before the public beta of shared Artifacts opens next week, and who owns each part of it.",
                                                    ),
                                                ),
                                            ),
                                            ArtifactExcerptBlock.Heading(2, listOf(ArtifactExcerptSpan("Open questions"))),
                                            ArtifactExcerptBlock.ListBlock(
                                                listOf(
                                                    ArtifactExcerptListItem(0, spans = listOf(ArtifactExcerptSpan("Who can see comments?"))),
                                                    ArtifactExcerptListItem(0, spans = listOf(ArtifactExcerptSpan("Do links expire?"))),
                                                ),
                                            ),
                                        ),
                                ),
                        ),
                ),
                FixtureTile(
                    row =
                        fixtureRow(
                            id = "tile-md-settled",
                            title = "Weekly notes",
                            home = ArtifactHome.Conversation("proj-workspace", dm = false),
                            author = remy,
                            settled = settled,
                            commentCount = 3,
                            news = ArtifactsHomeNews(newComments = 1),
                            excerpt =
                                ArtifactExcerpt(
                                    text = "Decisions this week: the excerpt cover keeps its frame, the badges no longer cover its words.\n\nNext: the list view.",
                                ),
                        ),
                ),
                FixtureTile(
                    row =
                        fixtureRow(
                            id = "tile-md-unfollowed",
                            title = "Q3 retrospective",
                            home = ArtifactHome.Conversation("engineering", dm = false),
                            author = remy,
                            following = false,
                            excerpt =
                                ArtifactExcerpt(
                                    text = "Summary of what shipped cleanly in Q3 and where mobile startup latency improved across Android and iOS.",
                                ),
                        ),
                    unfollowed = true,
                    dimmed = true,
                ),
                FixtureTile(
                    row =
                        fixtureRow(
                            id = "tile-md-multi",
                            title = "Public rollout sign-off",
                            home = ArtifactHome.Public,
                            author = remy,
                            settled = settled,
                            excerpt =
                                ArtifactExcerpt(
                                    text = "All launch checklists are signed off and excerpt words clear multi-badge corners cleanly without cutting words.",
                                ),
                        ),
                ),
                FixtureTile(
                    row =
                        fixtureRow(
                            id = "tile-md-nobadge",
                            title = "Release checklist",
                            home = ArtifactHome.Server,
                            author = remy,
                            news = ArtifactsHomeNews(mentioned = true),
                            excerpt =
                                ArtifactExcerpt(
                                    text = "A server Artifact has no badge in its corner, so its first line runs the whole width of the tile as before.",
                                ),
                        ),
                ),
                FixtureTile(
                    row =
                        fixtureRow(
                            id = "tile-shot-public",
                            title = "Pricing page draft",
                            kind = ArtifactKind.Html,
                            home = ArtifactHome.Public,
                            author = remy,
                            screenshot = ArtifactScreenshot(desktop = "of_tile_shot", phone = null),
                        ),
                ),
            )

        CompositionLocalProvider(
            LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides TileScreenshotPreviewHandler,
            LocalArtifactScreenshotReader provides reader,
        ) {
            RaftDesignSystemTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(RaftTheme.colors.layerCanvas)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items.chunked(2).forEach { pair ->
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pair.forEach { item ->
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    ArtifactsHomeTile(
                                        row = item.row,
                                        readerId = "u1",
                                        serverName = "Northwind Labs",
                                        byOpened = false,
                                        dimmed = item.dimmed,
                                        settledEnabled = true,
                                        settling = false,
                                        onOpen = {},
                                        onCopyLink = null,
                                        onSetSettled = null,
                                        unfollowed = item.unfollowed,
                                        fillHeight = true,
                                        modifier = Modifier.fillMaxHeight(),
                                        cover = { r, d, c -> ArtifactsHomeTileCover(r, d, c) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class FixtureTile(
    val row: ArtifactsHomeRow,
    val unfollowed: Boolean = false,
    val dimmed: Boolean = false,
)

private fun fixtureRow(
    id: String,
    title: String,
    kind: ArtifactKind = ArtifactKind.Markdown,
    home: ArtifactHome,
    author: ArtifactsHomeActor,
    settled: ArtifactSettled? = null,
    following: Boolean? = true,
    commentCount: Int = 0,
    news: ArtifactsHomeNews = ArtifactsHomeNews(),
    excerpt: ArtifactExcerpt? = null,
    screenshot: ArtifactScreenshot? = null,
) =
    ArtifactsHomeRow(
        summary =
            ArtifactSummary(
                id = id,
                title = title,
                description = null,
                kind = kind,
                version = 2,
                pinned = false,
                latestVersion = 2,
                home = home,
                updatedAt = "2026-11-18T08:00:00.000Z",
                commentCount = commentCount,
                settled = settled,
                settledShown = settled != null,
                following = following,
                excerpt = excerpt,
                screenshot = screenshot,
            ),
        author = author,
        openedAt = null,
        news = news,
    )
