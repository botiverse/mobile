package build.raft.app.assistant

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
import build.raft.app.RaftAppAppearance
import build.raft.app.RaftColor
import build.raft.app.RaftRobolectricScreenshot
import build.raft.app.RaftThreadComposerPayload
import build.raft.app.RaftType
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.ThreadComposerContent
import build.raft.app.ThreadComposerDock
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAssistantOtterHosts
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The personal assistant's otter on a channel's composer, as ThreadPage wires it: the otter
 * lying on the composer frame's top edge (phone scale, 52dp from the frame's end) over the dock,
 * the message list ending a band as tall as the otter higher so the last message stays clear of
 * it. Light and dark. Web: AssistantOtter.tsx on ChatPanel's MessageInput.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h360dp-xxhdpi")
class ThreadAssistantOtterComposerScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun channelComposerLight() = capture("assistant.otter.channel-composer", RaftAppAppearance.Light)

    @Test
    fun channelComposerDark() = capture("assistant.otter.channel-composer.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val otter =
                remember {
                    ThreadAssistantOtter(
                        hostId = "channel",
                        hosts = RaftAssistantOtterHosts(),
                        mood = RaftOtterMood.Idle,
                        scale = RaftAssistantOtterDefaults.PhoneScale,
                        endInset = RaftAssistantOtterDefaults.PhoneEndInset,
                        onOpen = {},
                    )
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            Box(
                                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text(
                                    text = "Raft App (raft-ios) 1.15.5 → IN_BETA_TESTING",
                                    style = RaftType.Body,
                                    color = RaftColor.TextBlack,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            ThreadAssistantOtterListEnd(otter)
                        }
                        ThreadComposerDock {
                            ThreadComposerContent(
                                payload = RaftThreadComposerPayload(instanceId = "otter", placeholder = "Message #proj-mobile-release"),
                                onEvent = { _, _, _, _ -> },
                                attachmentPreviewEnabled = false,
                                frameEdge = { composing, focused ->
                                    ThreadAssistantOtterFrameEdge(otter, composing, focused, animateEntrance = false)
                                },
                            )
                        }
                    }
                }
            }
        }
}
