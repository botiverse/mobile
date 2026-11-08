package build.raft.app

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Agent read progress on a real composition (slock staging 61eaa22, #13549): the line under your
 * latest message says only who it will not reach, with the hint inline and whole on a narrow width;
 * the faces sit on a message, two on a phone then "+N", and a tap says who read up to there.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h360dp")
class ThreadAgentReadStatusRowUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Composable
    private fun InPage(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme(content = content) }
    }

    private val names = mapOf("a-product" to "ProductDesigner", "a-rayleigh" to "Rayleigh", "a-niko" to "Niko", "a-ray" to "Ray", "a-cindy" to "Cindy", "a-tenny" to "Tenny", "a-kirby" to "Kirby")

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /**
     * Cut: the line ends in an ellipsis. (A one-line ellipsis Text lays its paragraph out at the full
     * constraint, so `hasVisualOverflow` says nothing here.)
     */
    private fun SemanticsNodeInteraction.isCut(): Boolean = layout().isLineEllipsized(0)

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun muted(vararg ids: String) =
        ThreadAgentReadStatusUi("m-3", ids.map { ThreadAgentReadEntry(it, ThreadAgentReadState.Muted) }, names)

    @Test
    fun atANormalWidthTheWholeLineShowsAsWritten() = runRobolectricComposeUiTest {
        setContent { InPage { ThreadAgentReadStatusRow(muted("a-kirby")) } }
        val namesNode = onNodeWithTag(ThreadAgentReadStatusNamesTestTag, useUnmergedTree = true)
        assertEquals("Kirby", namesNode.text())
        assertFalse(namesNode.isCut())
        onNodeWithText("Muted:", substring = true, useUnmergedTree = true).assertExists()
        val hint = onNodeWithTag(ThreadAgentReadStatusHintTestTag, useUnmergedTree = true)
        assertEquals("Mention them to reach them", hint.text())
        assertFalse(hint.isCut())
        onNodeWithTag(ThreadAgentReadStatusTestTag).assertContentDescriptionEquals("Agents on this message: Muted: Kirby · Mention them to reach them")
        // The hint ends the line, inside it.
        val row = onNodeWithTag(ThreadAgentReadStatusTestTag).getBoundsInRoot()
        assertTrue(abs((row.right - hint.getBoundsInRoot().right).value) < 0.5f)
    }

    /** web #13554 in Chinese: `已静音：Produ… +1 · @ 对方才会看到`: the names take the ellipsis; verb, +1 and hint stay whole. */
    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w320dp-h360dp")
    fun chineseNarrowCutsOnlyTheNames() = runRobolectricComposeUiTest {
        setContent { InPage { ThreadAgentReadStatusRow(muted("a-product", "a-rayleigh", "a-cindy", "a-tenny"), Modifier.width(300.dp)) } }
        onNodeWithText("已静音：", useUnmergedTree = true).assertExists()
        val namesNode = onNodeWithTag(ThreadAgentReadStatusNamesTestTag, useUnmergedTree = true)
        assertTrue(namesNode.text().startsWith("ProductDesigner"))
        assertTrue(namesNode.isCut(), "the names take the ellipsis")
        assertFalse(onNodeWithText(" +1", useUnmergedTree = true).isCut(), "+N stays whole")
        val hint = onNodeWithTag(ThreadAgentReadStatusHintTestTag, useUnmergedTree = true)
        assertEquals("@ 对方才会看到", hint.text())
        assertFalse(hint.isCut(), "the hint goes last, and the names are not gone yet")
    }

    /** web #13554 in Chinese, narrower: no name fits, so `已静音：4 个 Agent` and no "+1"; the hint is cut only after. */
    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w320dp-h360dp")
    fun chineseNarrowerWritesTheCount() = runRobolectricComposeUiTest {
        setContent { InPage { ThreadAgentReadStatusRow(muted("a-product", "a-rayleigh", "a-cindy", "a-tenny"), Modifier.width(150.dp)) } }
        onNodeWithText("已静音：4 个 Agent", useUnmergedTree = true).assertExists()
        assertFalse(onNodeWithText("已静音：4 个 Agent", useUnmergedTree = true).isCut())
        onNodeWithTag(ThreadAgentReadStatusNamesTestTag, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText(" +1", useUnmergedTree = true).assertDoesNotExist()
        val row = onNodeWithTag(ThreadAgentReadStatusTestTag).getBoundsInRoot()
        assertTrue(onNodeWithTag(ThreadAgentReadStatusHintTestTag, useUnmergedTree = true).getBoundsInRoot().right <= row.right + 0.5.dp)
    }

    /** web #13554 + Kirby in English: no name fits, so "Muted: 4 agents" (never a bare "+1"), and only then the hint's tail goes. */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h360dp")
    fun englishNarrowWritesTheCountThenCutsTheHint() = runRobolectricComposeUiTest {
        setContent { InPage { ThreadAgentReadStatusRow(muted("a-product", "a-rayleigh", "a-cindy", "a-tenny"), Modifier.width(220.dp)) } }
        onNodeWithText("Muted: 4 agents", useUnmergedTree = true).assertExists()
        onNodeWithTag(ThreadAgentReadStatusNamesTestTag, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText(" +1", useUnmergedTree = true).assertDoesNotExist()
        assertFalse(onNodeWithText("Muted: 4 agents", useUnmergedTree = true).isCut())
        val hint = onNodeWithTag(ThreadAgentReadStatusHintTestTag, useUnmergedTree = true)
        assertTrue(hint.isCut(), "the hint loses its tail last")
        // Everything stays inside the line.
        val row = onNodeWithTag(ThreadAgentReadStatusTestTag).getBoundsInRoot()
        assertTrue(hint.getBoundsInRoot().right <= row.right + 0.5.dp)
        onNodeWithTag(ThreadAgentReadStatusTestTag).assertContentDescriptionEquals(
            "Agents on this message: Muted: ProductDesigner, Rayleigh, Cindy, and Tenny · Mention them to reach them",
        )
        // A tap lists every name, without the hint (the web's tooltip).
        onNodeWithTag(ThreadAgentReadStatusTestTag).performClick()
        waitForIdle()
        onNodeWithText("Muted: ProductDesigner, Rayleigh, Cindy, and Tenny", useUnmergedTree = true).assertExists()
        onNodeWithText("Mention them to reach them", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun facesShowTwoOnAPhoneThenPlusNAndATapSaysWhoReadUpToHere() = runRobolectricComposeUiTest {
        val faces =
            listOf(
                ThreadAgentReadFace("a-niko", "Niko", working = true),
                ThreadAgentReadFace("a-ray", "Ray"),
                ThreadAgentReadFace("a-cindy", "Cindy"),
                ThreadAgentReadFace("a-tenny", "Tenny"),
            )
        setContent { InPage { ThreadAgentReadFacesRow(faces, serverId = "srv-1") } }
        onNodeWithTag("$ThreadAgentReadFacesTestTag-a-niko", useUnmergedTree = true).assertExists()
        onNodeWithTag("$ThreadAgentReadFacesTestTag-a-ray", useUnmergedTree = true).assertExists()
        onNodeWithTag("$ThreadAgentReadFacesTestTag-a-cindy", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("+2", useUnmergedTree = true).assertExists()
        // raft-ui AvatarGroup: each face lies 5dp over the one before.
        val niko = onNodeWithTag("$ThreadAgentReadFacesTestTag-a-niko", useUnmergedTree = true).getBoundsInRoot()
        val ray = onNodeWithTag("$ThreadAgentReadFacesTestTag-a-ray", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(abs((niko.right - 5.dp - ray.left).value) < 0.5f, "overlap ${niko.right - ray.left}")
        val label = "Niko, Ray, Cindy, and Tenny read up to here"
        onNodeWithTag(ThreadAgentReadFacesTestTag).assertContentDescriptionEquals(label)
        onNodeWithTag(ThreadAgentReadFacesDetailsTestTag, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(ThreadAgentReadFacesTestTag).performClick()
        waitForIdle()
        onNodeWithText(label, useUnmergedTree = true).assertExists()
        onNodeWithTag(ThreadAgentReadFacesTestTag).performClick()
        waitForIdle()
        onNodeWithTag(ThreadAgentReadFacesDetailsTestTag, useUnmergedTree = true).assertDoesNotExist()
    }
}
