package build.raft.app

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Duration
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone Home list's page assembly against web mobile (botiverse/slock `Sidebar.tsx`,
 * iPhone 393pt): the list is `px-2 pt-1`, rows carry their own `my-px`, and a section header's
 * own `pt-4` is the only space above it. Reads real bounds from the semantics tree at a
 * 393dp-wide xhdpi window and writes the measured table plus a PNG to
 * `build/reports/home-web-metrics/` for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class HomeSidebarWebMetricsTest {
    @Test
    fun phoneHomeListMatchesWebPageAssembly() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // Moko string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val pager =
            object : Pager() {}.apply {
                pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            }
        activity.setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { HomeSidebarWebMetricsFixture() }
        }
        settle()
        val composeView = findComposeRoot(activity.window.decorView)
        assertNotNull(composeView, "no compose root")
        val density = activity.resources.displayMetrics.density
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        walk((composeView as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)

        fun Rect.dp(): String = "x=${fmt(left / density)} y=${fmt(top / density)} w=${fmt(width / density)} h=${fmt(height / density)}"
        fun textOf(node: SemanticsNode): String =
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: ""

        val report = StringBuilder()
        nodes.forEach { node ->
            val label = textOf(node)
            val role = node.config.getOrNull(SemanticsProperties.Role)
            val scroll = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            if (label.isNotEmpty() || role != null || scroll) {
                report.appendLine("${node.id}\t${role ?: if (scroll) "Scroll" else "-"}\t$label\t${node.boundsInRoot.dp()}")
            }
        }
        val out = File("build/reports/home-web-metrics").apply { mkdirs() }
        File(out, "home_phone_semantics.tsv").writeText(report.toString())
        println(report)

        val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        composeView.draw(android.graphics.Canvas(bitmap))
        File(out, "home_phone_light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        fun textNode(text: String): SemanticsNode =
            nodes.first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        fun rowOf(text: String): Rect {
            val textNode = textNode(text)
            var node: SemanticsNode? = textNode
            while (node != null && node.config.getOrNull(SemanticsProperties.Role) == null) node = node.parent
            return (node ?: textNode).boundsInRoot
        }
        fun Float.toDp(): Int = (this / density).roundToInt()

        val list = nodes.first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }.boundsInRoot
        val saved = rowOf("Saved")
        val pinnedHeader = rowOf("Pinned")
        val firstChannel = rowOf("general")
        val secondChannel = rowOf("design")

        // Web `px-2`: rows and section headers 8dp from each screen edge.
        assertEquals(8, saved.left.toDp(), "entry row inset")
        assertEquals(8, firstChannel.left.toDp(), "channel row inset")
        assertEquals(393 - 8, saved.right.toDp(), "entry row end inset")
        // Web phone `pt-1` + the row's `my-px`: the first row's box starts 5dp under the list top.
        assertEquals(5, (saved.top - list.top).toDp(), "first row offset")
        // Web: a row's `my-px` is the only gap above a section header (its own pt-4 sits inside).
        assertEquals(1, (pinnedHeader.top - saved.bottom).toDp(), "entry row -> section header gap")
        assertEquals(44, saved.height.toDp(), "entry row height")
        assertEquals(44, firstChannel.height.toDp(), "channel row height")
        // Recorded, not asserted here: the 2dp inter-row gap is RaftSidebarItem's (DS) `my-px`.
        println("inter-row gap = ${(secondChannel.top - firstChannel.bottom) / density}dp")
    }

    private fun settle() {
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun fmt(value: Float): String = ((value * 10).roundToInt() / 10f).toString()
}

@androidx.compose.runtime.Composable
private fun HomeSidebarWebMetricsFixture() {
    val channels =
        listOf(
            RaftHomeConversationItem(id = "c-general", name = "general", kind = "channel"),
            RaftHomeConversationItem(id = "c-design", name = "design", kind = "channel"),
            RaftHomeConversationItem(id = "c-mobile", name = "mobile", kind = "channel", unreadCount = 3),
            RaftHomeConversationItem(id = "c-ops", name = "ops", kind = "channel", channelType = "private"),
            RaftHomeConversationItem(id = "c-pinned", name = "announcements", kind = "channel"),
            RaftHomeConversationItem(id = "j-partners", name = "partners", kind = "joint"),
        )
    val directMessages =
        listOf(
            RaftHomeConversationItem(id = "dm-ada", name = "Ada", kind = "dm", avatarLabel = "AD"),
            RaftHomeConversationItem(id = "dm-linus", name = "Linus", kind = "dm", avatarLabel = "LI"),
        )
    RaftStructuralHomeShell(
        currentServerName = "Raft Mobile",
        currentServerId = "server-metrics",
        servers = listOf(RaftServerSwitcherItem(id = "server-metrics", name = "Raft Mobile", slug = "mobile")),
        channels = channels,
        directMessages = directMessages,
        pinnedChannelIds = listOf("c-pinned"),
        savedCount = 0,
        applyTopSafeArea = false,
        topSafeAreaOverride = 0.dp,
        showBottomBar = false,
        searchAndActivityInBottomBar = true,
        bottomContentPadding = 0.dp,
        outerPadding = 0.dp,
        contentRefreshEnabled = false,
    )
}
