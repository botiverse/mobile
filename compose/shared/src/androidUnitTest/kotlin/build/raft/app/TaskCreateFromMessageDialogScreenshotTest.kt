package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Task v2 "Create Task from Message" (slock web `CreateTaskDialog` with a source message), light
 * and dark: the quoted message (sender, three lines) and the first title prefilled from it; the
 * failed state shows web's "Failed to create task" banner.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TaskCreateFromMessageDialogScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun dialogLight() = dialog("thread.create-task-from-message", RaftAppAppearance.Light, failed = false)

    @Test
    fun dialogDark() = dialog("thread.create-task-from-message.dark", RaftAppAppearance.Dark, failed = false)

    @Test
    fun failedLight() = dialog("thread.create-task-from-message.failed", RaftAppAppearance.Light, failed = true)

    private fun dialog(name: String, appearance: RaftAppAppearance, failed: Boolean) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
            root = { ShadowDialog.getLatestDialog()?.window?.decorView },
        ) {
            Page {
                TaskCreateFromMessageDialog(
                    source =
                        TaskCreateSourceMessage(
                            channelId = ChannelId("channel-1"),
                            messageId = MessageId("msg-1"),
                            senderName = "artin",
                            text = "preview 包好了把下载方式贴给我，我在模拟器上逐项测，结果贴在这里。",
                        ),
                    submitting = false,
                    failed = failed,
                    onDismiss = {},
                    onSubmit = {},
                )
            }
        }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }
}
