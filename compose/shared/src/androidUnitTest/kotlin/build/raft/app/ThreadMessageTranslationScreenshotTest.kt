package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The translation status line under a message body (web MessageItem TranslationIndicator on
 * raft-ui MessageItemTranslationStatus / FailedStatus), light and dark: translated (Show
 * original), showing the original (Show translation), translating, and failed with Retry.
 * Each sits under a line of body text, 4dp apart as in the message column. Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h360dp-xxhdpi")
class ThreadMessageTranslationScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun translationLineLight() = capture("thread.translation-line", RaftAppAppearance.Light)

    @Test
    fun translationLineDark() = capture("thread.translation-line.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(
                        Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        TranslatedRow(
                            "The launch checklist is ready for review.",
                            ThreadMessageTranslationIndicatorUi(
                                actionLabel = "Show original",
                                message = "",
                                action = ThreadMessageTranslationIndicatorAction.ToggleOriginal,
                            ),
                        )
                        TranslatedRow(
                            "发布清单已经可以审阅了。",
                            ThreadMessageTranslationIndicatorUi(
                                actionLabel = "Show translation",
                                message = "",
                                action = ThreadMessageTranslationIndicatorAction.ToggleOriginal,
                            ),
                        )
                        TranslatedRow(
                            "Moved the design review to Thursday at 4.",
                            ThreadMessageTranslationIndicatorUi(actionLabel = "", message = "Translating…", pending = true),
                        )
                        TranslatedRow(
                            "デザインレビューは木曜日の4時に移動しました。",
                            ThreadMessageTranslationIndicatorUi(
                                actionLabel = "Retry",
                                message = "Translation unavailable",
                                failed = true,
                                action = ThreadMessageTranslationIndicatorAction.Retry,
                            ),
                        )
                    }
                }
            }
        }
}

@Composable
private fun TranslatedRow(body: String, indicator: ThreadMessageTranslationIndicatorUi) {
    Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
        Text(text = body, style = raftMessageBodyTextStyle("md"), color = RaftColor.TextBlack)
        ThreadMessageTranslationIndicator(indicator = indicator, onToggleOriginal = {}, onRetry = {}, stillPulse = true)
    }
}
