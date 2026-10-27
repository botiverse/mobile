package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftTheme
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin (dm 37e8b6e9): when a row's unread count or mention mark appeared, its excerpt kept line 1
 * and lost line 2. The re-measure laid line 1 out with the same text and width, so it did not report
 * where line 1 ends again, and the excerpt read as all on line 1.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h560dp-xxhdpi")
class ActivityExcerptBesideMarksTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val excerpt =
        "Lagoon: 收到，我来查：动态页点顶部回到最上面以后，又被拉回第二屏。先把这张单的诊断包拉下来看看是哪一步把列表滚回去的。"

    @Test
    fun theSecondLineStaysWhenTheRowIsMeasuredAgain() {
        val showMarks = mutableStateOf(false)
        // Any later recomposition of the row (a new marks lambda, the time ticking) measures it again.
        val tick = mutableStateOf(0)
        val activity = activities.launch()
        activity.setContent {
            val t = tick.value
            RaftTheme {
                ActivityExcerptBesideMarks(
                    text = AnnotatedString(excerpt),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foreground,
                    inlineContent = emptyMap(),
                    lineHeight = 22.dp,
                    marks = { if (showMarks.value && t >= 0) Box(Modifier.size(28.dp, 22.dp)) },
                )
            }
        }
        settle()
        assertEquals(listOf(excerpt), texts(activity), "no marks: one two-line text")

        // The first measure with marks lays line 1 out and splits the rest onto line 2.
        showMarks.value = true
        settle()
        val split = texts(activity)
        assertEquals(2, split.size, "marks: line 1 beside them, the rest on line 2")
        val rest = split[1]
        assertTrue(rest.isNotBlank() && excerpt.endsWith(rest) && rest.length < excerpt.length, "line 2 is the rest: $rest")

        // Measured again with the same text and width: line 1's layout is cached and not reported again.
        tick.value++
        settle()
        assertEquals(split, texts(activity), "line 2 is still there")
    }

    private fun texts(activity: android.app.Activity): List<String> {
        val root = assertNotNull(findComposeRoot(activity.window.decorView), "no compose root")
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.let { out += it.joinToString("") { part -> part.text } }
            node.children.forEach(::walk)
        }
        walk((root as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        return out
    }

    private fun settle() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
