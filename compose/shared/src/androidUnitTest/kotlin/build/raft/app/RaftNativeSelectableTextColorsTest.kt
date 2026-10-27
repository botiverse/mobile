package build.raft.app

import android.os.Looper
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import build.raft.app.shared.R
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiThemeColors
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Artea (#proj-mobile:27fca9d9): the Select Text sheet's platform text view drew its selection in
 * the system accent (pink on the emulator). It takes the design system's selection colours: the
 * `selection` highlight and `foreground` handles, in light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftNativeSelectableTextColorsTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun lightUsesTheDesignSystemSelection() = assertColors(RaftAppearance.Light, RaftUiThemeColors.RaftLight)

    @Test
    fun darkUsesTheDesignSystemSelection() = assertColors(RaftAppearance.Dark, RaftUiThemeColors.RaftDark)

    private fun assertColors(appearance: RaftAppearance, colors: RaftUiThemeColors) {
        val activity = activities.launch()
        activity.setContent {
            RaftTheme(appearance = appearance) {
                RaftNativeSelectableText(text = "Select me", style = TextStyle(), modifier = androidx.compose.ui.Modifier, selection = null)
            }
        }
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val view = assertNotNull(activity.findViewById<TextView>(R.id.thread_message_selection_text), "the platform text view")
        assertEquals(colors.selection.toArgb(), view.highlightColor, "highlight is the selection token")
        assertEquals(colors.foreground.toArgb(), view.getTag(R.id.thread_message_selection_text), "handles tinted with foreground")
    }
}
