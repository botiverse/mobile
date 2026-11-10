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
import build.raft.app.RaftPendingMentionActionItem
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
import build.raft.designsystem.components.RaftMessageListJumpControls
import build.raft.designsystem.components.RaftMessageListJumpPill
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The personal assistant's otter on a channel's composer, as ThreadPage wires it: the otter
 * lying on the composer frame's top edge (phone scale, 52dp from the frame's end) over the dock,
 * over the message list's end (no band: the list keeps its place, artin 11-03). Light and dark. Web: AssistantOtter.tsx on ChatPanel's MessageInput.
 * Its loops and its waking are held still ([LocalRaftAssistantOtterStillAt]) so each capture is
 * one frame: asleep 1.2s in (breathing, two z's up), waking 0.5s in (out past its rest).
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

    /** Nothing new: asleep on the edge (Cody's sleepy art), breathing, z's floating up from its head. */
    @Test
    fun asleepLight() = capture("assistant.otter.asleep", RaftAppAppearance.Light, stillAt = 1_200L)

    @Test
    fun asleepDark() = capture("assistant.otter.asleep.dark", RaftAppAppearance.Dark, stillAt = 1_200L)

    /** A new message: waving, popped out from behind the edge, 6dp past its rest (web keyframe 35%). */
    @Test
    fun wakingLight() = capture("assistant.otter.waking", RaftAppAppearance.Light, mood = RaftOtterMood.New, stillAt = 525L)

    /** The owner types: the otter is in the composer, only the top of its head over the edge, its z's gone. */
    @Test
    fun typingTucksItInLight() = capture("assistant.otter.typing", RaftAppAppearance.Light, typed = "Can you check the release notes")

    /**
     * Artea #proj-assistant:#24 (web `useNoticesLift`): the "@Volta is not in #channel — Add /
     * Notify / Ignore" card above the composer. The otter (here with a new message, so its "New
     * message" bubble too) lies on top of the card, over none of its buttons; the list's jump pill
     * sits at the list's end, above the card.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
    fun mentionCardLiftsTheOtterLight() =
        capture("assistant.otter.mention-card", RaftAppAppearance.Light, mood = RaftOtterMood.New, mentionCard = true)

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        mood: RaftOtterMood = RaftOtterMood.Idle,
        mentionCard: Boolean = false,
        typed: String = "",
        // Past the waking by default: at rest.
        stillAt: Long = RaftOtterWakeMillis.toLong(),
    ) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val otter =
                remember {
                    ThreadAssistantOtter(
                        hostId = "channel",
                        hosts = RaftAssistantOtterHosts(),
                        mood = mood,
                        scale = RaftAssistantOtterDefaults.PhoneScale,
                        endInset = RaftAssistantOtterDefaults.PhoneEndInset,
                        onOpen = {},
                    )
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftAssistantOtterStillAt provides stillAt) {
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
                                if (mentionCard) {
                                    // As ThreadPage draws it: at the bottom of the list's box.
                                    RaftMessageListJumpControls(Modifier.align(Alignment.BottomCenter)) {
                                        RaftMessageListJumpPill(text = "1 new message", onClick = {})
                                    }
                                }
                            }
                        }
                        ThreadComposerDock {
                            ThreadComposerContent(
                                payload =
                                    RaftThreadComposerPayload(
                                        instanceId = "otter",
                                        placeholder = "Message #proj-mobile-release",
                                        seedText = typed,
                                        pendingMentionActions = if (mentionCard) listOf(voltaNotInChannel) else emptyList(),
                                    ),
                                onEvent = { _, _, _, _ -> },
                                attachmentPreviewEnabled = false,
                                frameEdge = { composing, focused, stackedAbove ->
                                    ThreadAssistantOtterFrameEdge(otter, composing, focused, stackedAbove, animateEntrance = false)
                                },
                            )
                        }
                    }
                }
            }
        }
}

private val voltaNotInChannel =
    RaftPendingMentionActionItem(
        resolutionId = "res-volta",
        channelName = "proj-security",
        targetType = "agent",
        targetHandle = "Volta",
        availableActions = listOf("add", "notify_only"),
    )
