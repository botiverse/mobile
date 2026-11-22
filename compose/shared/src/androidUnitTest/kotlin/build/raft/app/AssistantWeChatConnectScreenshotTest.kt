package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.assistant.WeChatConnectFooter
import build.raft.app.assistant.WeChatConnectStep
import build.raft.app.assistant.WeChatConnectStepBody
import build.raft.app.assistant.WeChatVerifyError
import build.raft.app.assistant.weChatConnectDescription
import build.raft.app.data.WeChatAttempt
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftDialogSurface
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Connect WeChat in a wide window (web WeChatConnectDialog.tsx at a non-phone width), light and dark:
 * - `assistant.wechat.connect.qr`: the QR code (black modules on white with a quiet zone, also in
 *   dark mode), "Waiting for a scan · works until 08:05", the privacy reminder, Cancel;
 * - `assistant.wechat.connect.verify`: WeChat's verification code after a wrong one (the field's
 *   error), Cancel and Verify.
 * Goldens are CI's render (`test.yml` with `record_screenshots=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class AssistantWeChatConnectScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun qrLight() = capture("assistant.wechat.connect.qr", RaftAppAppearance.Light) { ConnectFixture(WeChatConnectStep.Qr(ATTEMPT, scanned = false)) }

    @Test
    fun qrDark() = capture("assistant.wechat.connect.qr.dark", RaftAppAppearance.Dark) { ConnectFixture(WeChatConnectStep.Qr(ATTEMPT, scanned = false)) }

    @Test
    fun verifyLight() =
        capture("assistant.wechat.connect.verify", RaftAppAppearance.Light) {
            ConnectFixture(WeChatConnectStep.Verify(ATTEMPT, WeChatVerifyError.Wrong, checking = false), code = "4F7K2Q")
        }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            MigrationFixtureHost(content)
        }
}

private val ATTEMPT =
    WeChatAttempt(
        attemptId = "5f0c2b9e-3d4a-4e8f-9b1c-7a6d5e4f3c2b",
        qrContent = "https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=0a1b2c3d4e5f6a7b&bot_type=3",
        expiresAt = "2026-11-17T08:05:00.000Z",
    )

/** The connect dialog over the dimmed page, centered as it opens. */
@Composable
private fun ConnectFixture(step: WeChatConnectStep, code: String = "") {
    Box(
        Modifier.fillMaxSize().background(RaftTheme.colors.foreground.copy(alpha = 0.6f)).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftDialogSurface(
            title = stringResource(Res.string.assistant_wechat_connect_title),
            description = weChatConnectDescription("Ada", step),
            onClose = {},
            footer = {
                WeChatConnectFooter(step = step, code = code, onCancel = {}, onClose = {}, onShowCode = {}, onVerify = {}, onNewCode = {})
            },
        ) {
            WeChatConnectStepBody(name = "Ada", step = step, code = code, onCodeChange = {}, onSubmitCode = {}, clockOf = { "08:05" }, autoFocus = false)
        }
    }
}
