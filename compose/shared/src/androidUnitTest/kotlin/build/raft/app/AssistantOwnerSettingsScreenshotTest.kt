package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.assistant.AssistantOwnerOtterRow
import build.raft.app.assistant.AssistantOwnerSectionContent
import build.raft.app.assistant.AssistantOwnerWeChatRow
import build.raft.app.assistant.WeChatConnectionBody
import build.raft.app.assistant.WeChatDisconnectConfirmBody
import build.raft.app.assistant.WeChatPhoneBlockedBody
import build.raft.app.data.ScreenError
import build.raft.app.data.WeChatBinding
import build.raft.app.model.RaftTimeContext
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftDialogSurface
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rest of the owner's 「Assistant」 section (web AssistantOwnerSection, AssistantOtterSettings
 * `OtterVisibleRow`, WeChatConnectDialog, WeChatConnectionView, WeChatDisconnectConfirm), on fixed
 * fixture data:
 * - `assistant.owner-section.full`: every row in web's order (the switches, Show Ada on every
 *   page, Extra instructions, WeChat, Recent changes) and Delete assistant last, light and dark;
 * - `assistant.owner-section.otter-failed`: the otter switch after a failed save, with its trace ID;
 * - `assistant.wechat.connection`: WeChat, connected (state, since when, Disconnect), Done;
 * - `assistant.wechat.connection.stopped`: a connection WeChat stopped, with where to reconnect;
 * - `assistant.wechat.phone-blocked`: Connect WeChat, which says to connect from a computer;
 * - `assistant.wechat.disconnect-failed`: Disconnect WeChat? after a failure, with its trace ID;
 * - `assistant.delete-confirm`: Delete assistant? with Cancel and the red Delete.
 * Goldens are CI's render (`test.yml` with `record_screenshots=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class AssistantOwnerSettingsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun fullSectionLight() = capture("assistant.owner-section.full", RaftAppAppearance.Light) { FullSectionFixture() }

    @Test
    fun fullSectionDark() = capture("assistant.owner-section.full.dark", RaftAppAppearance.Dark) { FullSectionFixture() }

    @Test
    fun otterSaveFailed() =
        capture("assistant.owner-section.otter-failed", RaftAppAppearance.Light) {
            FullSectionFixture(otter = AssistantOwnerOtterRow(checked = true, enabled = true, failure = ScreenError("http_500", traceId = TRACE_ID)))
        }

    @Test
    fun weChatConnection() =
        capture("assistant.wechat.connection", RaftAppAppearance.Light) { ConnectionFixture(WeChatBinding(BINDING_ID, "active", CONNECTED_AT)) }

    @Test
    fun weChatConnectionStopped() =
        capture("assistant.wechat.connection.stopped", RaftAppAppearance.Light) {
            ConnectionFixture(WeChatBinding(BINDING_ID, "reconnect_required", CONNECTED_AT))
        }

    @Test
    fun weChatPhoneBlocked() =
        capture("assistant.wechat.phone-blocked", RaftAppAppearance.Light) {
            Backdrop {
                RaftDialogSurface(
                    title = stringResource(Res.string.assistant_wechat_connect_title),
                    description = stringResource(Res.string.assistant_wechat_intro, "Ada"),
                    onClose = {},
                    footer = { RaftButton(stringResource(Res.string.common_close), onClick = {}, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Lg) },
                ) {
                    WeChatPhoneBlockedBody()
                }
            }
        }

    @Test
    fun weChatDisconnectFailed() =
        capture("assistant.wechat.disconnect-failed", RaftAppAppearance.Light) {
            Backdrop {
                RaftDialogSurface(
                    title = stringResource(Res.string.assistant_wechat_disconnect_title),
                    size = RaftDialogSize.Sm,
                    onClose = {},
                    footer = {
                        RaftButton(stringResource(Res.string.common_cancel), onClick = {}, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                        RaftButton(stringResource(Res.string.assistant_wechat_disconnect), onClick = {}, variant = RaftButtonVariant.Warning, size = RaftButtonSize.Lg)
                    },
                ) {
                    WeChatDisconnectConfirmBody("Ada", ScreenError("http_500", traceId = TRACE_ID))
                }
            }
        }

    @Test
    fun deleteConfirm() =
        capture("assistant.delete-confirm", RaftAppAppearance.Light) {
            Backdrop {
                RaftDialogSurface(
                    title = stringResource(Res.string.assistant_delete_title),
                    size = RaftDialogSize.Sm,
                    description = stringResource(Res.string.assistant_delete_body, "Ada"),
                    onClose = {},
                    footer = {
                        RaftButton(stringResource(Res.string.common_cancel), onClick = {}, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                        RaftButton(stringResource(Res.string.assistant_delete), onClick = {}, variant = RaftButtonVariant.Danger, size = RaftButtonSize.Lg)
                    },
                )
            }
        }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val timeContext =
                remember {
                    RaftTimeContextProvider(
                        RaftTimeContext(localOffsetMinutes = 0, timeFormat = "24h")
                            .refreshedAt(Instant.parse(CONNECTED_AT).toEpochMilliseconds())
                    )
                }
            CompositionLocalProvider(LocalRaftTimeContextProvider provides timeContext) {
                MigrationFixtureHost(content)
            }
        }
}

private const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
private const val BINDING_ID = "0b6f4a3e-8c21-4d7a-9f10-3e2d1c0b9a87"
private const val CONNECTED_AT = "2026-11-16T08:12:00.000Z"

@Composable
private fun FullSectionFixture(
    otter: AssistantOwnerOtterRow = AssistantOwnerOtterRow(checked = true, enabled = true),
) {
    Column(
        Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).verticalScroll(rememberScrollState()).padding(vertical = 16.dp, horizontal = 20.dp)
    ) {
        AssistantOwnerSectionContent(
            name = "Ada",
            readsDms = true,
            sendAsOwner = false,
            showInstructions = true,
            onOpenInstructions = {},
            onSettingChange = { _, _ -> },
            showRecentChanges = true,
            otter = otter,
            weChat = AssistantOwnerWeChatRow(connected = true),
            onDelete = {},
        )
    }
}

/** A dialog over the dimmed page, as it opens. */
@Composable
private fun Backdrop(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.foreground.copy(alpha = 0.6f)).padding(16.dp)) {
        content()
    }
}

@Composable
private fun ConnectionFixture(binding: WeChatBinding) {
    Backdrop {
        RaftDialogSurface(
            title = stringResource(Res.string.assistant_wechat_connection_title),
            onClose = {},
            footer = { RaftButton(stringResource(Res.string.common_done), onClick = {}, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Lg) },
        ) {
            WeChatConnectionBody(name = "Ada", binding = binding, available = true, onDisconnect = {})
        }
    }
}
