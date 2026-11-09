package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.RaftLinkHoverStatusHost
import build.raft.app.markdown.RaftLinkHoverStatusShowDelayMs
import build.raft.app.markdown.RaftLinkHoverStatusTestTag
import build.raft.app.markdown.RaftMarkdownBody
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin 11-03 (Compose 版): a mouse resting on a message link shows where it goes at the page's
 * bottom-left after a short delay, as a browser's status bar; it goes when the pointer leaves the
 * link, a finger never shows it, and it takes no click. Drives [RaftMarkdownBody] inside the
 * page's [RaftLinkHoverStatusHost] (BasePager's link-status layer).
 */
@OptIn(ExperimentalTestApi::class)
// Real text measurement, so a character's box holds the pointer.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h600dp")
class RaftLinkHoverStatusUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val opened = mutableListOf<String>()

    private fun ComposeUiTest.showBody() {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    RaftLinkHoverStatusHost {
                        Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp)) {
                            RaftMarkdownBody(
                                body = BODY,
                                modifier = Modifier.width(600.dp),
                                onOpenUrl = { opened += it },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        // The status's delays run on the test clock from here.
        mainClock.autoAdvance = false
    }

    private fun ComposeUiTest.textNode() = onNode(hasText("Read", substring = true), useUnmergedTree = true)

    private fun ComposeUiTest.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        textNode().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun ComposeUiTest.charCenter(words: String): Offset =
        textLayout().let { it.getBoundingBox(it.layoutInput.text.text.indexOf(words) + 1).center }

    private fun ComposeUiTest.status() =
        onNode(hasText(LINK) and hasParent(hasTestTag(RaftLinkHoverStatusTestTag)), useUnmergedTree = true)

    /** Frame by frame, so an effect started by one frame's recomposition runs on the next frames. */
    private fun ComposeUiTest.settle(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, 16L)
            mainClock.advanceTimeBy(step)
            waitForIdle()
            left -= step
        }
    }

    @Test
    fun mouseOverALinkShowsItsUrlAfterADelayAndLeavingHidesIt() =
        runRobolectricComposeUiTest {
            showBody()
            textNode().performMouseInput { moveTo(charCenter("the docs")) }
            settle(RaftLinkHoverStatusShowDelayMs / 2)
            onNodeWithTag(RaftLinkHoverStatusTestTag, useUnmergedTree = true).assertDoesNotExist()
            settle(RaftLinkHoverStatusShowDelayMs)
            status().assertExists()

            // Onto plain words of the same text: the status goes.
            textNode().performMouseInput { moveTo(charCenter("tomorrow")) }
            settle(RaftLinkHoverStatusShowDelayMs)
            onNodeWithTag(RaftLinkHoverStatusTestTag, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun theStatusTakesNoClickAndAFingerNeverShowsIt() =
        runRobolectricComposeUiTest {
            showBody()
            textNode().performMouseInput { moveTo(charCenter("the docs")) }
            settle(RaftLinkHoverStatusShowDelayMs * 2)
            status().assertExists()
            textNode().performMouseInput { click(charCenter("the docs")) }
            settle(100)
            assertEquals(listOf(LINK), opened, "the click with the status up still opens the link")

            textNode().performMouseInput { moveTo(charCenter("tomorrow")) }
            settle(RaftLinkHoverStatusShowDelayMs * 2)
            textNode().performTouchInput { down(charCenter("the docs")) }
            settle(RaftLinkHoverStatusShowDelayMs * 2)
            onNodeWithTag(RaftLinkHoverStatusTestTag, useUnmergedTree = true).assertDoesNotExist()
            textNode().performTouchInput { cancel() }
        }

    /** A page smaller than the window keeps its own size at the top-left; the layer does not stretch it. */
    @Test
    fun aPageSmallerThanTheWindowKeepsItsSize() =
        runRobolectricComposeUiTest {
            setContent {
                RaftLinkHoverStatusHost {
                    Box(Modifier.size(390.dp, 84.dp).testTag(SMALL_PAGE))
                }
            }
            waitForIdle()
            onNodeWithTag(SMALL_PAGE).assertHeightIsEqualTo(84.dp).assertTopPositionInRootIsEqualTo(0.dp)
        }

    private companion object {
        const val SMALL_PAGE = "small-page"
        const val LINK = "https://example.com/docs"
        const val BODY = "Read [the docs]($LINK) before the launch review tomorrow"
    }
}
