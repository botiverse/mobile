package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Apple's rule for the horizontal safe area (Kirby's design review of the four-edge safe area):
 * backgrounds run edge to edge, content gets out of the way. A landscape page laid out from the
 * shared building blocks (the yellow frame page header, list rows with one selected, an empty
 * state strip) sits in [RaftPageHorizontalSafeAreaShell] with the iOS host's insets: the header
 * frame, its seam and the selected row's wash reach both screen edges, while the Back button,
 * title, actions and every row's content are inset by the 59dp on the island's side. With
 * insets 0 (the Android host) nothing moves.
 *
 * The sheet case is a page presented as an iOS page sheet: its own top safe area is 0, and the
 * header keeps [RaftSheetHeaderTopMargin] above it instead of touching the sheet's top edge.
 *
 * Layout bounds are asserted too, so the guarantee does not rest on the goldens alone. Reports
 * land in `build/reports/shell-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h320dp-xxhdpi")
class RaftSafeAreaFullBleedScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val bounds = mutableMapOf<String, Pair<Float, Float>>()
    private val tops = mutableMapOf<String, Float>()
    private val heights = mutableMapOf<String, Int>()
    private var rootWidthPx = 0
    private var densityValue = 0f

    @Test
    fun landscapeIslandOnTheRightKeepsBackgroundsFullBleed() {
        captureLandscape("safearea.landscape.right59", leftDp = 0, rightDp = 59)
        assertBackgroundsFullBleed()
        val inset = px(59)
        // Content keeps clear of the right strip and starts on its usual left margin.
        assertTrue(bounds.getValue("header.trailing").second <= rootWidthPx - inset, "header action left the strip")
        assertTrue(bounds.getValue("header.title").first < px(80), "title stays on its usual left margin")
        assertEquals(rootWidthPx - inset - px(16), bounds.getValue("row.selected.trailing").second.roundToInt(), "row content ends 16dp before the strip")
        assertEquals(px(16), bounds.getValue("row.selected.leading").first.roundToInt(), "row content starts on 16dp")
    }

    @Test
    fun landscapeIslandOnTheLeftKeepsBackgroundsFullBleed() {
        captureLandscape("safearea.landscape.left59", leftDp = 59, rightDp = 0)
        assertBackgroundsFullBleed()
        val inset = px(59)
        assertTrue(bounds.getValue("header.title").first >= inset, "title left the strip")
        assertEquals(inset + px(16), bounds.getValue("row.selected.leading").first.roundToInt(), "row content starts 16dp past the strip")
        assertEquals(rootWidthPx - px(16), bounds.getValue("row.selected.trailing").second.roundToInt(), "row content ends on 16dp")
    }

    @Test
    fun hostWithoutHorizontalInsetsMovesNothing() {
        captureLandscape("safearea.landscape.none", leftDp = 0, rightDp = 0)
        assertBackgroundsFullBleed()
        assertEquals(px(16), bounds.getValue("row.selected.leading").first.roundToInt())
        assertEquals(rootWidthPx - px(16), bounds.getValue("row.selected.trailing").second.roundToInt())
    }

    @Test
    @Config(qualifiers = "w390dp-h260dp-xxhdpi")
    fun sheetPresentedPageKeepsItsHeaderOffTheSheetTop() {
        RaftRobolectricScreenshot.capture(
            "safearea.sheet.top",
            RaftAppAppearance.Light,
            reportDir = REPORT_DIR,
            activities = activities,
        ) {
            densityValue = LocalDensity.current.density
            // The presenting page dimmed behind the sheet, the sheet's rounded top 28dp down.
            Box(Modifier.fillMaxSize().background(Color(0xFF6B6B6B))) { // raft-color: test backdrop
                Box(
                    Modifier
                        .padding(top = SheetTop)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .track("sheet"),
                ) {
                    PageFrame(
                        name = "sheet",
                        values = RaftHostWindowInsetValues(topPx = 0f, bottomPx = 34 * densityValue, statusBarHidden = false, presentedAsSheet = true),
                    ) { SamplePage() }
                }
            }
        }
        val sheetTop = tops.getValue("sheet")
        assertEquals(sheetTop, tops.getValue("header.frame"), "the header frame starts on the sheet's top edge")
        // The frame is the 16dp sheet margin plus the header itself: the header row does not
        // start on the sheet's top edge.
        val margin = RaftSheetHeaderTopMargin.value.roundToInt()
        assertEquals(px(margin) + px(RaftAppSize.PageHeaderHeight.value.roundToInt()), heights.getValue("header.frame"))
        assertTrue(tops.getValue("header.trailing") - sheetTop >= px(margin), "the header content keeps its top margin in the sheet")
    }

    private fun assertBackgroundsFullBleed() {
        for (part in listOf("header.frame", "row.selected", "row.plain")) {
            assertEquals(0f to rootWidthPx.toFloat(), bounds.getValue(part), "$part background runs edge to edge")
        }
    }

    private fun px(dp: Int): Int = (dp * densityValue).roundToInt()

    private fun captureLandscape(name: String, leftDp: Int, rightDp: Int) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = REPORT_DIR, activities = activities) {
            val density = LocalDensity.current.density
            densityValue = density
            Box(Modifier.fillMaxSize().onGloballyPositioned { rootWidthPx = it.size.width }) {
                PageFrame(
                    name = name,
                    // What the iOS host publishes in landscape (pixels): the status bar hidden,
                    // 21dp home indicator, the sensor housing's 59dp on one side.
                    values =
                        RaftHostWindowInsetValues(
                            topPx = 0f,
                            bottomPx = 21 * density,
                            statusBarHidden = true,
                            leftPx = leftDp * density,
                            rightPx = rightDp * density,
                        ),
                ) { SamplePage() }
            }
        }

    @Composable
    private fun PageFrame(name: String, values: RaftHostWindowInsetValues, content: @Composable () -> Unit) {
        val windowId = remember { "screenshot-safearea-$name" }
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject("""{"slockWindowId":"$windowId"}""")) }
            }
        remember { RaftHostWindowInsets.publish(windowId, values) }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftPageHorizontalSafeAreaShell(content)
        }
    }

    @Composable
    private fun SamplePage() {
        Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
            RaftTopPageHeader(
                title = "",
                onBack = {},
                modifier = Modifier.track("header.frame"),
                titleContent = {
                    Text(
                        "general",
                        modifier = Modifier.weight(1f).track("header.title"),
                        style = RaftType.PageTitle,
                    )
                },
                trailingContent = {
                    RaftHeaderIconAction(
                        icon = RaftLucideIconName.Search,
                        contentDescription = "Search",
                        onClick = {},
                        modifier = Modifier.track("header.trailing"),
                    )
                },
                showBodyTopBanner = false,
            )
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxWidth().background(RaftColor.SurfaceWhite)) {
                    RaftListRow(
                        title = "design-review",
                        description = "Kirby: backgrounds edge to edge",
                        selected = true,
                        onClick = {},
                        modifier = Modifier.track("row.selected"),
                        leading = { Box(Modifier.size(20.dp).background(RaftColor.Brand).track("row.selected.leading")) },
                        trailing = { RaftListRowChevron(Modifier.track("row.selected.trailing")) },
                    )
                    RaftListRow(
                        title = "landscape",
                        description = "The selected wash above reaches both edges",
                        onClick = {},
                        divider = true,
                        modifier = Modifier.track("row.plain"),
                        leading = { Box(Modifier.size(20.dp).background(RaftColor.AccentLime)) },
                        trailing = { RaftListRowChevron() },
                    )
                }
            }
        }
    }

    private fun Modifier.track(name: String): Modifier =
        onGloballyPositioned { coordinates ->
            val position = coordinates.positionInRoot()
            bounds[name] = position.x to position.x + coordinates.size.width
            tops[name] = position.y
            heights[name] = coordinates.size.height
        }

    private companion object {
        const val REPORT_DIR = "build/reports/shell-screenshots"
        val SheetTop = 28.dp
    }
}
