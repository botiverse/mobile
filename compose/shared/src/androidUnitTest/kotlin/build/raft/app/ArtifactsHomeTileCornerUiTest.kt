package build.raft.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
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
import build.raft.app.thread.ArtifactSettledTagTestTag
import build.raft.app.thread.LocalArtifactScreenshotReader
import build.raft.designsystem.tokens.RaftUiThemeColors
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral, layout, click-passthrough, semantics, RTL, CJK, and pixel-level invariants for
 * Artifacts Home tile corner badges on Markdown excerpt vs screenshot covers (#proj-workspace:#428,
 * web botiverse/slock #16751).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoilApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h780dp-xxhdpi")
class ArtifactsHomeTileCornerUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 11, 21),
            nowEpochMillis = 1_795_248_000_000L,
        )

    private val remy = ArtifactsHomeActor("agent", "a1", "remy", "Remy")
    private val settledInfo = ArtifactSettled(remy, "2026-11-18T09:30:00.000Z", 2)

    private val longExcerpt =
        ArtifactExcerpt(
            text = "Everything we need before the public beta of shared Artifacts opens next week, and who owns each part of it.",
        )

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
    fun markdownExcerptPlacesCornerInWordsWithClickPassthroughAndPreservesSettledSemantics() = runRobolectricComposeUiTest {
        val opened = mutableListOf<String>()
        val mdPublic =
            tileRow(
                id = "pt_md_pub",
                title = "Public beta plan",
                home = ArtifactHome.Public,
                excerpt = longExcerpt,
            )
        val mdUnfollowed =
            tileRow(
                id = "pt_md_unf",
                title = "Muted draft",
                home = ArtifactHome.Server,
                following = false,
                excerpt = longExcerpt,
            )
        val mdSettled =
            tileRow(
                id = "pt_md_settled",
                title = "Weekly notes",
                home = ArtifactHome.Server,
                settled = settledInfo,
                excerpt = longExcerpt,
            )
        var showSettledCase by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
                RaftDesignSystemTheme {
                    if (!showSettledCase) {
                        Row(Modifier.fillMaxWidth().padding(8.dp).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                ArtifactsHomeTile(
                                    row = mdPublic,
                                    readerId = "u1",
                                    serverName = "Northwind",
                                    byOpened = false,
                                    dimmed = false,
                                    settledEnabled = true,
                                    settling = false,
                                    onOpen = { opened += mdPublic.summary.id },
                                    onCopyLink = null,
                                    onSetSettled = null,
                                    fillHeight = true,
                                    cover = { r, d, c -> ArtifactsHomeTileCover(r, d, c) },
                                )
                            }
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                ArtifactsHomeTile(
                                    row = mdUnfollowed,
                                    readerId = "u1",
                                    serverName = "Northwind",
                                    byOpened = false,
                                    dimmed = true,
                                    settledEnabled = true,
                                    settling = false,
                                    onOpen = { opened += mdUnfollowed.summary.id },
                                    onCopyLink = null,
                                    onSetSettled = null,
                                    unfollowed = true,
                                    fillHeight = true,
                                    cover = { r, d, c -> ArtifactsHomeTileCover(r, d, c) },
                                )
                            }
                        }
                    } else {
                        Box(Modifier.width(180.dp)) {
                            ArtifactsHomeTile(
                                row = mdSettled,
                                readerId = "u1",
                                serverName = "Northwind",
                                byOpened = false,
                                dimmed = false,
                                settledEnabled = true,
                                settling = false,
                                onOpen = { opened += mdSettled.summary.id },
                                onCopyLink = null,
                                onSetSettled = null,
                                cover = { r, d, c -> ArtifactsHomeTileCover(r, d, c) },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()

        // Both Markdown excerpt tiles place their corner inside the excerpt, never as a cover overlay.
        onAllNodesWithTag(ArtifactsHomeTags.TileExcerptCorner, useUnmergedTree = true).assertCountEquals(2)
        onAllNodesWithTag(ArtifactsHomeTags.TileCorner, useUnmergedTree = true).assertCountEquals(0)

        // Clicking Public or Unfollowed passes through to open the Artifact card.
        onNodeWithTag(ArtifactsHomeTags.PublicBadge, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("pt_md_pub"), opened)

        onNodeWithTag(ArtifactsHomeTags.UnfollowedTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("pt_md_pub", "pt_md_unf"), opened)

        // Settled badge keeps its full screen-reader semantics outside the excerpt's clearAndSetSemantics.
        showSettledCase = true
        waitForIdle()
        onAllNodesWithTag(ArtifactsHomeTags.TileExcerptCorner, useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithTag(ArtifactsHomeTags.TileCorner, useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag(ArtifactSettledTagTestTag, useUnmergedTree = true)
            .assertContentDescriptionEquals("Settled: Remy settled it on 11-18 17:30 (v2)")
    }

    @Test
    fun screenshotCoverKeepsOverlayCornerAtExactSameInsetAsExcerptCover() = runRobolectricComposeUiTest {
        build.raft.app.image.raftInstallImageLoader()
        val shotRow =
            tileRow(
                id = "pt_shot",
                title = "Pricing page draft",
                kind = ArtifactKind.Html,
                home = ArtifactHome.Public,
                screenshot = ArtifactScreenshot(desktop = "of_tile_shot", phone = null),
            )
        val mdRow =
            tileRow(
                id = "pt_md",
                title = "Launch plan",
                kind = ArtifactKind.Markdown,
                home = ArtifactHome.Public,
                excerpt = longExcerpt,
            )
        val noBadgeMdRow =
            tileRow(
                id = "pt_plain",
                title = "Release checklist",
                kind = ArtifactKind.Markdown,
                home = ArtifactHome.Server,
                excerpt = longExcerpt,
            )
        var shotCoverBounds: Rect? = null
        var mdCoverBounds: Rect? = null
        var shotCornerBounds: Rect? = null
        var mdCornerBounds: Rect? = null

        val fakeReader =
            object : ArtifactScreenshotReader {
                override fun kept(cardId: String, fileId: String): ByteArray = fileId.encodeToByteArray()

                override suspend fun read(cardId: String, fileId: String): ByteArray? = awaitCancellation()
            }

        var showNoBadgeCase by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides TileScreenshotPreviewHandler,
                LocalArtifactScreenshotReader provides fakeReader,
            ) {
                RaftDesignSystemTheme {
                    if (!showNoBadgeCase) {
                        Row(Modifier.fillMaxWidth().padding(16.dp).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                ArtifactsHomeTile(
                                    row = shotRow,
                                    readerId = "u1",
                                    serverName = "Northwind",
                                    byOpened = false,
                                    dimmed = false,
                                    settledEnabled = true,
                                    settling = false,
                                    onOpen = {},
                                    onCopyLink = null,
                                    onSetSettled = null,
                                    fillHeight = true,
                                    cover = { r, d, c ->
                                        Box(Modifier.onGloballyPositioned { shotCoverBounds = it.boundsInRoot() }) {
                                            ArtifactsHomeTileCover(r, d, c)
                                        }
                                    },
                                )
                            }
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                ArtifactsHomeTile(
                                    row = mdRow,
                                    readerId = "u1",
                                    serverName = "Northwind",
                                    byOpened = false,
                                    dimmed = false,
                                    settledEnabled = true,
                                    settling = false,
                                    onOpen = {},
                                    onCopyLink = null,
                                    onSetSettled = null,
                                    fillHeight = true,
                                    cover = { r, d, c ->
                                        Box(Modifier.onGloballyPositioned { mdCoverBounds = it.boundsInRoot() }) {
                                            ArtifactsHomeTileCover(r, d, c)
                                        }
                                    },
                                )
                            }
                        }
                    } else {
                        Box(Modifier.width(180.dp)) {
                            ArtifactsHomeTile(
                                row = noBadgeMdRow,
                                readerId = "u1",
                                serverName = "Northwind",
                                byOpened = false,
                                dimmed = false,
                                settledEnabled = true,
                                settling = false,
                                onOpen = {},
                                onCopyLink = null,
                                onSetSettled = null,
                                cover = { r, d, c -> ArtifactsHomeTileCover(r, d, c) },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()

        onAllNodesWithTag(ArtifactsHomeTags.TileCorner, useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithTag(ArtifactsHomeTags.TileExcerptCorner, useUnmergedTree = true).assertCountEquals(1)
        shotCornerBounds = onNodeWithTag(ArtifactsHomeTags.TileCorner, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        mdCornerBounds = onNodeWithTag(ArtifactsHomeTags.TileExcerptCorner, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        val shotCover = assertNotNull(shotCoverBounds)
        val mdCover = assertNotNull(mdCoverBounds)
        val shotTopInset = shotCornerBounds.top - shotCover.top
        val mdTopInset = mdCornerBounds.top - mdCover.top
        val shotRightInset = shotCover.right - shotCornerBounds.right
        val mdRightInset = mdCover.right - mdCornerBounds.right

        // Exact 6dp top and right inset parity between screenshot overlay and Markdown excerpt corner.
        assertTrue(
            abs(shotTopInset - mdTopInset) <= 1.5f,
            "top inset mismatch: screenshot=$shotTopInset vs excerpt=$mdTopInset",
        )
        assertTrue(
            abs(shotRightInset - mdRightInset) <= 1.5f,
            "right inset mismatch: screenshot=$shotRightInset vs excerpt=$mdRightInset",
        )

        // No-badge Markdown excerpt emits neither corner container.
        showNoBadgeCase = true
        waitForIdle()
        onAllNodesWithTag(ArtifactsHomeTags.TileCorner, useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithTag(ArtifactsHomeTags.TileExcerptCorner, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun renderedPixelsConfirmExcerptWrapsBesideBadgeClearsUnderItAndFillsTopRightWhenUnbadged() {
        val publicRow =
            tileRow(
                id = "pt_pub",
                title = "Public guide",
                home = ArtifactHome.Public,
                excerpt = longExcerpt,
            )
        val plainRow =
            tileRow(
                id = "pt_plain",
                title = "Server guide",
                home = ArtifactHome.Server,
                excerpt = longExcerpt,
            )
        var publicCoverBounds: Rect? = null
        var plainCoverBounds: Rect? = null
        var publicBadgeBounds: Rect? = null

        RaftRobolectricScreenshot.render(RaftAppAppearance.Light, activities, content = {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
                RaftDesignSystemTheme {
                    Row(Modifier.fillMaxWidth().padding(16.dp).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ArtifactsHomeTile(
                                row = publicRow,
                                readerId = "u1",
                                serverName = "Northwind",
                                byOpened = false,
                                dimmed = false,
                                settledEnabled = true,
                                settling = false,
                                onOpen = {},
                                onCopyLink = null,
                                onSetSettled = null,
                                fillHeight = true,
                                cover = { r, d, c ->
                                    Box(Modifier.onGloballyPositioned { publicCoverBounds = it.boundsInRoot() }) {
                                        ArtifactsHomeTileCover(
                                            r,
                                            d,
                                            c?.let { badge ->
                                                {
                                                    Box(Modifier.onGloballyPositioned { publicBadgeBounds = it.boundsInRoot() }) {
                                                        badge()
                                                    }
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ArtifactsHomeTile(
                                row = plainRow,
                                readerId = "u1",
                                serverName = "Northwind",
                                byOpened = false,
                                dimmed = false,
                                settledEnabled = true,
                                settling = false,
                                onOpen = {},
                                onCopyLink = null,
                                onSetSettled = null,
                                fillHeight = true,
                                cover = { r, d, c ->
                                    Box(Modifier.onGloballyPositioned { plainCoverBounds = it.boundsInRoot() }) {
                                        ArtifactsHomeTileCover(r, d, c)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }) { activity, bitmap ->
            val density = activity.resources.displayMetrics.density
            val pubCover = assertNotNull(publicCoverBounds)
            val plainCover = assertNotNull(plainCoverBounds)
            val badge = assertNotNull(publicBadgeBounds)
            val inkArgb = RaftUiThemeColors.RaftLight.foreground.toArgb()

            // 1. On the Public-badged excerpt tile, foreground ink renders to the left of the badge on line 0.
            val besideInkCount =
                countInkPixels(
                    bitmap = bitmap,
                    left = (pubCover.left + 12f * density).roundToInt(),
                    top = (pubCover.top + 12f * density).roundToInt(),
                    right = (badge.left - 8f * density).roundToInt(),
                    bottom = (pubCover.top + 30f * density).roundToInt(),
                    inkArgb = inkArgb,
                )
            assertTrue(besideInkCount > 20, "expected wrapped excerpt text beside Public badge, got $besideInkCount ink px")

            // 2. The 8dp horizontal gap immediately left of the Public badge is completely clean (zero foreground ink).
            val gapInkCount =
                countInkPixels(
                    bitmap = bitmap,
                    left = (badge.left - 6f * density).roundToInt(),
                    top = (pubCover.top + 12f * density).roundToInt(),
                    right = (badge.left - 1f * density).roundToInt(),
                    bottom = (badge.bottom + 2f * density).roundToInt(),
                    inkArgb = inkArgb,
                )
            assertEquals(0, gapInkCount, "expected clean breathing gap beside Public badge, found $gapInkCount ink px")

            // 3. Below the Public badge, wrapped text expands back into the badge's X span (full width).
            val belowBadgeInkCount =
                countInkPixels(
                    bitmap = bitmap,
                    left = badge.left.roundToInt(),
                    top = (badge.bottom + 6f * density).roundToInt(),
                    right = (pubCover.right - 12f * density).roundToInt(),
                    bottom = (pubCover.bottom - 24f * density).roundToInt(),
                    inkArgb = inkArgb,
                )
            assertTrue(belowBadgeInkCount > 20, "expected full-width excerpt text below Public badge, got $belowBadgeInkCount ink px")

            // 4. On the unbadged server excerpt tile, line 0 spans all the way into the top-right badge region.
            val plainTopRightOffsetLeft = badge.left - pubCover.left
            val plainTopRightInk =
                countInkPixels(
                    bitmap = bitmap,
                    left = (plainCover.left + plainTopRightOffsetLeft).roundToInt(),
                    top = (plainCover.top + 12f * density).roundToInt(),
                    right = (plainCover.right - 12f * density).roundToInt(),
                    bottom = (plainCover.top + 30f * density).roundToInt(),
                    inkArgb = inkArgb,
                )
            assertTrue(plainTopRightInk > 20, "expected unbadged excerpt line 0 to span full width, got $plainTopRightInk ink px")
        }
    }

    @Test
    fun dynamicBadgeToggleRichBlocksCjkAndRtlRenderWithoutCrashOrOverlap() = runRobolectricComposeUiTest {
        val richExcerpt =
            ArtifactExcerpt(
                text = "Release Readiness",
                blocks =
                    listOf(
                        ArtifactExcerptBlock.Heading(1, listOf(ArtifactExcerptSpan("Release Plan"))),
                        ArtifactExcerptBlock.Paragraph(
                            listOf(
                                ArtifactExcerptSpan("All lanes pass "),
                                ArtifactExcerptSpan("artifact_badge_wrap", code = true),
                                ArtifactExcerptSpan(" with "),
                                ArtifactExcerptSpan("zero jank", bold = true),
                                ArtifactExcerptSpan("."),
                            ),
                        ),
                        ArtifactExcerptBlock.Quote(listOf(ArtifactExcerptSpan("Keep badges off excerpt words."))),
                        ArtifactExcerptBlock.ListBlock(
                            listOf(
                                ArtifactExcerptListItem(level = 0, checked = true, spans = listOf(ArtifactExcerptSpan("Wrap beside corner"))),
                                ArtifactExcerptListItem(level = 0, checked = false, spans = listOf(ArtifactExcerptSpan("Preserve screenshot overlay"))),
                                ArtifactExcerptListItem(level = 1, order = 1, spans = listOf(ArtifactExcerptSpan("Verify RTL and CJK"))),
                            ),
                        ),
                        ArtifactExcerptBlock.Code(listOf("corner = badges")),
                        ArtifactExcerptBlock.Image(alt = "diagram"),
                        ArtifactExcerptBlock.Rule,
                        ArtifactExcerptBlock.Table(
                            header = listOf(listOf(ArtifactExcerptSpan("Platform")), listOf(ArtifactExcerptSpan("Status"))),
                            rows = listOf(listOf(listOf(ArtifactExcerptSpan("Mobile")), listOf(ArtifactExcerptSpan("Green")))),
                        ),
                    ),
            )
        val cjkExcerpt =
            ArtifactExcerpt(
                text = "本周决定：摘要封面保留原有比例，公开与已定稿徽标不再遮挡正文文字，首行在徽标左侧自然折行。",
            )
        var showSettled by mutableStateOf(false)
        var layoutDir by mutableStateOf(LayoutDirection.Ltr)
        var coverBounds: Rect? = null
        var cornerBounds: Rect? = null

        setContent {
            CompositionLocalProvider(
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
                LocalLayoutDirection provides layoutDir,
            ) {
                RaftDesignSystemTheme {
                    Row(Modifier.fillMaxWidth().padding(16.dp).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ArtifactsHomeTile(
                                row =
                                    tileRow(
                                        id = "pt_rich",
                                        title = "Rich Markdown",
                                        home = ArtifactHome.Public,
                                        settled = if (showSettled) settledInfo else null,
                                        excerpt = richExcerpt,
                                    ),
                                readerId = "u1",
                                serverName = "Northwind",
                                byOpened = false,
                                dimmed = false,
                                settledEnabled = true,
                                settling = false,
                                onOpen = {},
                                onCopyLink = null,
                                onSetSettled = null,
                                fillHeight = true,
                                cover = { r, d, c ->
                                    Box(Modifier.onGloballyPositioned { coverBounds = it.boundsInRoot() }) {
                                        ArtifactsHomeTileCover(r, d, c)
                                    }
                                },
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ArtifactsHomeTile(
                                row =
                                    tileRow(
                                        id = "pt_cjk",
                                        title = "中文摘要",
                                        home = ArtifactHome.Public,
                                        excerpt = cjkExcerpt,
                                    ),
                                readerId = "u1",
                                serverName = "Northwind",
                                byOpened = false,
                                dimmed = false,
                                settledEnabled = true,
                                settling = false,
                                onOpen = {},
                                onCopyLink = null,
                                onSetSettled = null,
                                fillHeight = true,
                                cover = { r, d, c -> ArtifactsHomeTileCover(r, d, c) },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag(ArtifactsHomeTags.TileExcerptCorner, useUnmergedTree = true).assertCountEquals(2)
        onAllNodesWithTag(ArtifactSettledTagTestTag, useUnmergedTree = true).assertCountEquals(0)

        // Toggle Settled dynamically on the rich-block tile -> corner widens without crashing or losing tags.
        showSettled = true
        waitForIdle()
        onAllNodesWithTag(ArtifactSettledTagTestTag, useUnmergedTree = true).assertCountEquals(1)

        // Switch to RTL -> corner moves to physical left of the cover (`6.dp` from cover.left).
        layoutDir = LayoutDirection.Rtl
        waitForIdle()
        cornerBounds = onAllNodesWithTag(ArtifactsHomeTags.TileExcerptCorner, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val cover = assertNotNull(coverBounds)
        assertTrue(
            cornerBounds.left - cover.left < cover.right - cornerBounds.right,
            "in RTL, excerpt corner must sit near physical left edge: corner=$cornerBounds cover=$cover",
        )
    }

    private fun countInkPixels(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int, inkArgb: Int): Int {
        val x0 = left.coerceIn(0, bitmap.width - 1)
        val x1 = right.coerceIn(x0, bitmap.width - 1)
        val y0 = top.coerceIn(0, bitmap.height - 1)
        val y1 = bottom.coerceIn(y0, bitmap.height - 1)
        val targetR = AndroidColor.red(inkArgb)
        val targetG = AndroidColor.green(inkArgb)
        val targetB = AndroidColor.blue(inkArgb)
        var hits = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val px = bitmap.getPixel(x, y)
                val dr = abs(AndroidColor.red(px) - targetR)
                val dg = abs(AndroidColor.green(px) - targetG)
                val db = abs(AndroidColor.blue(px) - targetB)
                // Match foreground text ink (including antialiased dark gray strokes, excluding warm yellow/green badges and white bg).
                if (dr + dg + db < 280) {
                    hits++
                }
            }
        }
        return hits
    }

    private fun tileRow(
        id: String,
        title: String,
        kind: ArtifactKind = ArtifactKind.Markdown,
        home: ArtifactHome = ArtifactHome.Public,
        settled: ArtifactSettled? = null,
        following: Boolean? = true,
        excerpt: ArtifactExcerpt? = null,
        screenshot: ArtifactScreenshot? = null,
        commentCount: Int = 0,
        news: ArtifactsHomeNews = ArtifactsHomeNews(),
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
            author = remy,
            openedAt = null,
            news = news,
        )
}

@OptIn(ExperimentalCoilApi::class)
internal val TileScreenshotPreviewHandler =
    AsyncImagePreviewHandler { _, request ->
        val file = (request.data as? ByteArray)?.decodeToString()
        if (file != "of_tile_shot") {
            AsyncImagePainter.State.Error(null, ErrorResult(null, request, IllegalStateException("fixture: no image")))
        } else {
            // Canvas-drawn page matching web ArtifactTileCases.tsx screenshotPng() (16:10 header + hero + lines).
            val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFFFFFF.toInt()) }
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xFF1F2937.toInt()
            canvas.drawRect(0f, 0f, 640f, 48f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = 22f
            paint.isFakeBoldText = true
            canvas.drawText("Pricing", 24f, 32f, paint)
            paint.color = 0xFF111827.toInt()
            paint.textSize = 36f
            canvas.drawText("Simple plans for teams", 24f, 110f, paint)
            paint.color = 0xFFD1D5DB.toInt()
            for (row in 0 until 6) {
                val width = if (row % 2 == 1) 450f else 590f
                val top = 145f + row * 32f
                canvas.drawRect(24f, top, 24f + width, top + 14f, paint)
            }
            val image = bitmap.asImage()
            AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
        }
    }
