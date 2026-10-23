package build.raft.app

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftUiMenuItem
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The PDF preview page scrolled into the document on a phone with a 3-button navigation bar
 * (#3584 smoke findings): page 1 scrolled up past the header must stay under the opaque header,
 * and the page indicator and the last page must stay clear of the navigation bar. Robolectric has
 * no pdfium, so pages come from a fake [RaftPdfPageSource]; the 48dp navigation bar the app draws
 * under is painted on top as a dark strip. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class RaftPdfPreviewScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun scrolledUnderHeaderAboveNavigationBar() =
        RaftRobolectricScreenshot.capture(
            "pdf.preview.scrolled",
            RaftAppAppearance.Light,
            reportDir = "build/reports/home-screenshots",
            activities = activities
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { PdfPreviewPage(initialOffsetY = 900f) }
        }
}

private val NavigationBarHeight = 48.dp

@Composable
private fun PdfPreviewPage(initialOffsetY: Float) {
    Box(Modifier.fillMaxSize()) {
        RaftFilePreviewPageScaffold(
            title = "smoke-3page.pdf",
            subtitle = "1.2 KB",
            iconName = RaftLucideIconName.FileText,
            onBack = {},
            contentPadding = 0.dp,
            overflowMenuItems =
                listOf(RaftUiMenuItem(id = "open-with", label = "Open with", icon = RaftLucideIconName.ExternalLink))
        ) {
            var page by remember { mutableStateOf(0 to 0) }
            RaftDesignSystemTheme {
                RaftFilePreviewPdfViewerFrame(
                    currentPage = page.first,
                    pageCount = page.second,
                    bottomInset = NavigationBarHeight,
                    modifier = Modifier.fillMaxSize()
                ) { viewerModifier ->
                    RaftAndroidPdfPages(
                        document = FakePdfPages,
                        pageSizes = List(3) { 595 to 842 },
                        onPageChanged = { current, count -> page = current to count },
                        bottomInset = NavigationBarHeight,
                        modifier = viewerModifier,
                        initialOffsetY = initialOffsetY
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(NavigationBarHeight)
                .background(Color(0xCC202124))
        )
    }
}

/** Each page: white, a grey frame, and "Page N" large enough to show where it is drawn. */
private object FakePdfPages : RaftPdfPageSource {
    override suspend fun renderPage(index: Int, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val canvas = android.graphics.Canvas(bitmap)
        val frame = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = width / 100f
            color = android.graphics.Color.GRAY
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), frame)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = width / 6f
            color = android.graphics.Color.BLACK
        }
        canvas.drawText("Page ${index + 1}", width * 0.2f, height * 0.12f, text)
        canvas.drawText("Page ${index + 1}", width * 0.2f, height * 0.95f, text)
        return bitmap
    }

    override suspend fun renderRegion(layout: RaftPdfPageLayout, region: RaftPdfDetailRegion, scale: Float): Bitmap? = null
}
