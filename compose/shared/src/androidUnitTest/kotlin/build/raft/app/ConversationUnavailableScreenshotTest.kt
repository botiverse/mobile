package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "this conversation can't be opened" state (web slock #13147): one sentence and one button,
 * the phone's conversation-list button here, the wide window's channel-list button in
 * [ConversationUnavailableWideScreenshotTest]. Record on CI (`record_screenshots=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ConversationUnavailablePhoneScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun phoneLight() = capture("conversation-unavailable.phone", RaftAppAppearance.Light)

    @Test
    fun phoneDark() = capture("conversation-unavailable.phone.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        captureConversationUnavailable(activities, name, appearance)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class ConversationUnavailableWideScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun wideLight() = captureConversationUnavailable(activities, "conversation-unavailable.wide", RaftAppAppearance.Light)

    @Test
    fun wideDark() = captureConversationUnavailable(activities, "conversation-unavailable.wide.dark", RaftAppAppearance.Dark)
}

private fun captureConversationUnavailable(
    activities: RobolectricComposeActivityRule,
    name: String,
    appearance: RaftAppAppearance,
) =
    RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftConversationUnavailableScreen(onBack = {})
        }
    }
