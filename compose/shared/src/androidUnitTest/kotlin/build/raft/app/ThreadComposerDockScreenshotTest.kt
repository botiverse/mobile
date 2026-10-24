package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
 * artin 10-24: the composer dock under a conversation's messages (channel, thread, DM). The web
 * draws no line between the messages and the composer; the composer's own box sits on the
 * canvas. The last lines of a message over the dock with an empty composer, light and dark.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h320dp-xxhdpi")
class ThreadComposerDockScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun dockLight() = capture("thread.composer-dock", RaftAppAppearance.Light)

    @Test
    fun dockDark() = capture("thread.composer-dock.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.BottomStart) {
                            Text(
                                text = "Raft App (raft-ios) 1.15.5 → IN_BETA_TESTING",
                                style = RaftType.Body,
                                color = RaftColor.TextBlack,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        ThreadComposerDock {
                            ThreadComposerContent(
                                payload = RaftThreadComposerPayload(instanceId = "dock", placeholder = "Message #proj-mobile-release"),
                                onEvent = { _, _, _, _ -> },
                                attachmentPreviewEnabled = false,
                            )
                        }
                    }
                }
            }
        }
}
