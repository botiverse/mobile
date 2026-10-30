package build.raft.app.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAppAppearance
import build.raft.app.RaftRobolectricScreenshot
import build.raft.app.RaftThreadComposerPayload
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
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The assistant's panel open over a page (web AssistantOtter's phone Drawer with
 * AssistantOtterPanel): the drawer window, 82% of the window tall over the scrim; the panel head
 * (the otter's head, the name, the meta line, collapse) and the DM under it. The DM here is a
 * stand-in for the embedded conversation page (THREAD_PAGE, which needs a live shell): two of
 * the assistant's lines over the real composer. Light, dark, and paused (the chip and the
 * sleeping mood's reading line).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ThreadAssistantPanelScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun openLight() = capture("assistant.panel.open", RaftAppAppearance.Light, paused = false)

    @Test
    fun openDark() = capture("assistant.panel.open.dark", RaftAppAppearance.Dark, paused = false)

    @Test
    fun pausedLight() = capture("assistant.panel.paused", RaftAppAppearance.Light, paused = true)

    private fun capture(name: String, appearance: RaftAppAppearance, paused: Boolean) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { dialogFittedToItsWindow() },
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            val otter =
                remember {
                    ThreadAssistantOtter(
                        hostId = "channel",
                        hosts = RaftAssistantOtterHosts(),
                        mood = if (paused) RaftOtterMood.Away else RaftOtterMood.Idle,
                        scale = RaftAssistantOtterDefaults.PhoneScale,
                        endInset = RaftAssistantOtterDefaults.PhoneEndInset,
                        onOpen = {},
                        assistantName = "Otto",
                        agentId = "agent-otto",
                        serverId = "server-1",
                        dmChannelId = "dm-otto",
                        readsDms = true,
                        paused = paused,
                        panelOpen = true,
                    )
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    ThreadAssistantPanelSheet(otter) { StandInDm() }
                }
            }
        }

    /** The assistant's DM as the conversation page shows it, reduced to two lines and the composer. */
    @Composable
    private fun ColumnScope.StandInDm() {
        Column(
            Modifier.weight(1f).fillMaxWidth().background(RaftTheme.colors.layerCanvas).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            RaftText("Otto · 9:02", style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
            RaftText(
                "Morning brief: 3 threads need you. #proj-mobile-release is waiting on the TestFlight build.",
                style = RaftTheme.typography.body,
                color = RaftTheme.colors.foreground,
            )
        }
        ThreadComposerDock {
            ThreadComposerContent(
                payload = RaftThreadComposerPayload(instanceId = "assistant-dm", placeholder = "Message Otto"),
                onEvent = { _, _, _, _ -> },
                attachmentPreviewEnabled = false,
            )
        }
    }

    /** The drawer's window, its compose content measured to it (see TaskCreateFromMessageDialogScreenshotTest). */
    private fun dialogFittedToItsWindow(): android.view.View? {
        val decor = ShadowDialog.getLatestDialog()?.window?.decorView ?: return null
        val content = findCompose(decor) ?: return decor
        val parent = content.parent as? android.view.View ?: return decor
        val exactly = android.view.View.MeasureSpec.EXACTLY
        content.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(parent.width, exactly),
            android.view.View.MeasureSpec.makeMeasureSpec(parent.height, exactly),
        )
        content.layout(0, 0, parent.width, parent.height)
        return decor
    }

    private fun findCompose(view: android.view.View): android.view.View? {
        if (view is androidx.compose.ui.platform.ViewRootForTest) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findCompose(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
