package build.raft.app

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app's [RaftType] text takes the window's type scale from its first frame (#type-scale): the
 * page host ([build.raft.app.compat.pager.RaftPagerHost]) runs [RaftProvideAppTypeScale] round
 * every page under the root [RaftTheme]. Before, [RaftAppTypeScale] stayed at the phone scale
 * until some `RaftDesignSystemTheme` composed, so text drawn before one (a page's cached first
 * frame) started at 16sp in a wide window and dropped to 14sp later.
 *
 * Each case records the `text-body` size every time the text composes, from the first
 * composition on, with the rule having put the process-wide scale back to the phone scale.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftAppTypeScaleFirstFrameUiTest {
    @get:Rule val activities = RobolectricComposeActivityRule()

    @Test
    @Config(qualifiers = "w1024dp-h768dp")
    fun wideWindowWithAMouseDrawsWideBodyFromTheFirstFrame() = assertBodySizes(expected = 14.sp, pointerInput = RaftPointerInput.Fine)

    /** raft-design #601: a touch screen keeps the phone's type at any width. */
    @Test
    @Config(qualifiers = "w1024dp-h768dp")
    fun wideWindowByTouchKeepsThePhoneBody() = assertBodySizes(expected = 16.sp, pointerInput = RaftPointerInput.Touch)

    private fun assertBodySizes(expected: TextUnit, pointerInput: RaftPointerInput) {
        val composed = mutableListOf<TextUnit>()
        val committed = mutableListOf<TextUnit>()
        runRobolectricComposeUiTest {
            setContent {
                RaftTheme(pointerInput = pointerInput) {
                    RaftProvideAppTypeScale {
                        val style = RaftType.Body
                        composed += style.fontSize
                        SideEffect { committed += style.fontSize }
                        Text("Body", style = style)
                    }
                }
            }
            waitForIdle()
        }
        assertEquals(expected, committed.firstOrNull(), "first committed frame; composed=$composed")
        assertEquals(listOf(expected), composed.distinct(), "every composition; composed=$composed")
    }
}
