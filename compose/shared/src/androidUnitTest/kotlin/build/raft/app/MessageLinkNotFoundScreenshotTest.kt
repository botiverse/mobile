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
 * The message-link empty state (frame d ③, #proj-mobile #25/#26), light and dark, on a phone:
 * the "Message link" panel head with Back, the muted "?" tile, "Message not found", the hint that
 * never says whether the message exists, and the Back button. Verify / record as
 * [RaftRobolectricScreenshot]; reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class MessageLinkNotFoundScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun messageLinkNotFoundLight() = capture("message-link.not-found", RaftAppAppearance.Light)

    @Test
    fun messageLinkNotFoundDark() = capture("message-link.not-found.dark", RaftAppAppearance.Dark)

    @Test
    fun messageLinkPublicPreviewLight() = capture("message-link.public-preview", RaftAppAppearance.Light) { PublicPreviewFixture() }

    @Test
    fun messageLinkPublicPreviewDark() = capture("message-link.public-preview.dark", RaftAppAppearance.Dark) { PublicPreviewFixture() }

    @androidx.compose.runtime.Composable
    private fun PublicPreviewFixture() =
        RaftMessageLinkPublicPreviewScreen(
            state =
                RaftPublicPreviewState.Content(
                    channelName = "release-notes",
                    serverName = "Open Source Club",
                    messages =
                        listOf(
                            build.raft.app.data.RaftPublicMessage("m1", "Sam", "The crash rate is back to normal.", null, null, 0),
                            build.raft.app.data.RaftPublicMessage("m2", "Theo", "Release checklist updated: confirm your three items below.", null, "t1", 4),
                            build.raft.app.data.RaftPublicMessage("m3", "Builder", "Synced the checklist to tasks.", null, null, 0),
                        ),
                    highlightMessageId = "m2",
                    thread = false,
                ),
            onBack = {},
            onRetry = {},
        )

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        content: @androidx.compose.runtime.Composable () -> Unit = { RaftMessageLinkNotFoundScreen(onBack = {}) },
    ) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { content() }
        }
}
