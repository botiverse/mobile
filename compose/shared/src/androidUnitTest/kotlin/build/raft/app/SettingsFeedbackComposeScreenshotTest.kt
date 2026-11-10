package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.PlatformFile
import build.raft.app.data.SettingsFeedbackType
import build.raft.app.format.RaftFormat
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Feedback › New Feedback on a 390dp phone, light and dark, laid out as the web's
 * hands-feedback-react `NewFeedbackForm`: the head ("New Feedback" over "The team replies in the
 * ticket."), the Type choice (Idea / Bug), the conversation composer with its attachment tray,
 * and the attachment note. Empty (Idea picked, the placeholder); filled (Bug, a message and one
 * screenshot tile); submitting (read-only, the tile under the upload overlay, the send spinner held
 * still); and a failed send (the warning banner with Retry over the composer). The production
 * form, head and composer ([SettingsHandsFeedbackForm], [SettingsHandsFeedbackComposer]) with
 * fixed state. Verify / record as [RaftRobolectricScreenshot]; reports land in
 * `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class SettingsFeedbackComposeScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun emptyLight() = capture("settings.feedback-compose.empty", RaftAppAppearance.Light, FormFixture())

    @Test
    fun emptyDark() = capture("settings.feedback-compose.empty.dark", RaftAppAppearance.Dark, FormFixture())

    @Test
    fun filledLight() = capture("settings.feedback-compose.filled", RaftAppAppearance.Light, filled)

    @Test
    fun filledDark() = capture("settings.feedback-compose.filled.dark", RaftAppAppearance.Dark, filled)

    @Test
    fun submittingLight() = capture("settings.feedback-compose.submitting", RaftAppAppearance.Light, filled.copy(submitting = true))

    @Test
    fun failedLight() = capture("settings.feedback-compose.failed", RaftAppAppearance.Light, failed)

    @Test
    fun failedDark() = capture("settings.feedback-compose.failed.dark", RaftAppAppearance.Dark, failed)

    private data class FormFixture(
        val type: SettingsFeedbackType = SettingsFeedbackType.Idea,
        val message: String = "",
        val attachments: List<PlatformFile> = emptyList(),
        val submitting: Boolean = false,
        /** The failed send's banner: the network failure, which Retry can resend. */
        val failedNetwork: Boolean = false,
    )

    private val screenshot =
        PlatformFile(
            id = "shot",
            fileName = "thread-scroll-jump.png",
            mimeType = "image/png",
            sizeBytes = 284_000L,
            fileRef = "draft://settings-feedback/thread-scroll-jump.png",
            source = "settings_feedback_draft",
        )

    private val filled =
        FormFixture(
            type = SettingsFeedbackType.Problem,
            message = "Opening a thread from Activity jumps to the top instead of the reply I tapped.",
            attachments = listOf(screenshot),
        )

    private val failed = filled.copy(failedNetwork = true)

    private fun capture(name: String, appearance: RaftAppAppearance, fixture: FormFixture) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { NewFeedbackPage(fixture) }
            }
        }

    /** The page as SettingsHandsFeedbackScreen draws it, with the fixture's state. */
    @Composable
    private fun NewFeedbackPage(fixture: FormFixture) {
        val copy = settingsHandsFeedbackCopy(RaftFormat.byteLimit(raftFeedbackAttachmentMaxBytes()))
        val value = TextFieldValue(fixture.message, selection = TextRange(fixture.message.length))
        SettingsDetailShell(title = settingsHandsFeedbackTitle(), subtitle = settingsHandsFeedbackSubtitle(), onBack = {}) {
            SettingsHandsFeedbackForm(
                copy = copy,
                type = fixture.type,
                onTypeChange = {},
                enabled = !fixture.submitting,
                notice =
                    if (fixture.failedNetwork) {
                        SettingsHandsFeedbackNotice.Problem(copy.submissionFailure("settings_feedback_network_failed").composerStatus, retryable = true)
                    } else {
                        null
                    },
                onRetry = {},
                onCopyResult = {},
                bottomContentPadding = RaftAppSpacing.Lg,
            ) {
                SettingsHandsFeedbackComposer(
                    instanceId = "settings-feedback-compose-screenshot",
                    value = value,
                    onValueChange = {},
                    placeholder = copy.placeholder,
                    enabled = !fixture.submitting,
                    submitting = fixture.submitting,
                    attachmentActionsEnabled = fixture.attachments.size < 3,
                    attachments = fixture.attachments.toFeedbackComposerAttachmentItems(copy, uploading = fixture.submitting),
                    composerSendKeyEnabled = false,
                    onEvent = { _, _, _ -> },
                )
            }
        }
    }
}
