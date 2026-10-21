package build.raft.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextStyle
import build.raft.app.designtokens.RaftUiThemeColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bug C: switching light/dark with a channel/DM/thread page open left message bodies painted in
 * the old theme's foreground, because the page remembered the body TextStyle (which bakes
 * RaftColor.TextBlack) keyed on the font-size preference only. The remembered style must follow
 * the theme in an already-composed page instance.
 */
@RunWith(RobolectricTestRunner::class)
class MessageBodyTextStyleThemeTest {
    @AfterTest
    fun restoreLight() {
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        RaftAppThemeState.onSystemDarkChanged(false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rememberedMessageBodyStyleFollowsThemeSwitchInOpenComposition() = runTest {
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        var style: TextStyle? = null
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(MessageBodyStyleUnitApplier(), recomposer)
        val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        fun applyAndDrain() {
            Snapshot.sendApplyNotifications()
            // Let the recomposer register its frame awaiter before the frame is sent.
            advanceUntilIdle()
            frameClock.sendFrame(System.nanoTime())
            advanceUntilIdle()
        }
        try {
            composition.setContent { style = rememberRaftMessageBodyTextStyle("md") }
            advanceUntilIdle()
            assertEquals(RaftUiThemeColors.RaftLight.foreground, style?.color)

            RaftAppThemeState.apply(RaftAppAppearance.Dark)
            applyAndDrain()
            assertEquals(RaftUiThemeColors.RaftDark.foreground, style?.color)

            RaftAppThemeState.apply(RaftAppAppearance.Light)
            applyAndDrain()
            assertEquals(RaftUiThemeColors.RaftLight.foreground, style?.color)
        } finally {
            composition.dispose()
            recomposer.close()
            recomposerJob.join()
        }
    }
}

private class MessageBodyStyleUnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun onClear() = Unit

    override fun remove(index: Int, count: Int) = Unit
}
