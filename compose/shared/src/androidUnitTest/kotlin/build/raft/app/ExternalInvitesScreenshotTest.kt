package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ExternalInviteGuestElsewhere
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.resources.Res
import build.raft.app.resources.channel_external_invite_title
import build.raft.app.resources.common_close
import build.raft.app.resources.stringResource
import build.raft.designsystem.components.RaftDialogSheetDefaults
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of external invites (`channel.external-invite.*`): Invite people outside
 * {server}'s emails and review steps (the sheet's surface, no window) and the members page's
 * Invited rows with Add Member's entry row. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ExternalInvitesScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val emails = ExternalInviteDialogState(text = "lena@contoso.com, sam@fabrikam.com\nnot-an-email")

    private val review =
        ExternalInviteDialogState(
            text = "x",
            preview =
                ExternalInvitePreview(
                    valid = listOf("lena@contoso.com"),
                    alreadyMembers = listOf("kai@acme.com"),
                    duplicates = listOf("lena@contoso.com"),
                    alreadyPending = listOf("sam@fabrikam.com"),
                    guestsElsewhere = listOf(ExternalInviteGuestElsewhere("rio@vendor.io", "vendor-sync")),
                    invalid = listOf("not-an-email"),
                    slotsLeft = 3,
                ),
        )

    @Test
    fun emailsLight() = sheet("channel.external-invite.emails", RaftAppAppearance.Light, emails)

    @Test
    fun reviewLight() = sheet("channel.external-invite.review", RaftAppAppearance.Light, review)

    @Test
    fun reviewDark() = sheet("channel.external-invite.review.dark", RaftAppAppearance.Dark, review)

    @Test
    fun invitedRowsLight() = rows("channel.external-invite.rows", RaftAppAppearance.Light)

    @Test
    fun invitedRowsDark() = rows("channel.external-invite.rows.dark", RaftAppAppearance.Dark)

    private fun sheet(name: String, appearance: RaftAppAppearance, state: ExternalInviteDialogState) =
        capture(name, appearance) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                RaftDialogSheetSurface(
                    title = stringResource(Res.string.channel_external_invite_title, "Acme"),
                    commitLabel = if (state.preview == null) "Next" else "Invite",
                    commitEnabled = true,
                    onCommit = {},
                    onClose = {},
                    modifier = Modifier.fillMaxHeight(RaftDialogSheetDefaults.HeightFraction),
                    closeLabel = stringResource(Res.string.common_close),
                ) {
                    ExternalInviteSheetBody(
                        dialog = state,
                        serverName = "Acme",
                        willBecomePrivate = true,
                        joint = false,
                        channelSlotsLeft = 3,
                        onTextChange = {},
                        onUpgrade = {},
                    )
                }
            }
        }

    private fun rows(name: String, appearance: RaftAppAppearance) =
        capture(name, appearance) {
            Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                ExternalInviteEntryRow(serverName = "Acme", enabled = true, onClick = {})
                val now = 1_763_337_600_000L // 2025-11-17T00:00:00Z
                listOf(
                    externalTestInvite("i1", "lena@contoso.com", expiresAt = "2025-12-01T00:00:00.000Z"),
                    externalTestInvite("i2", "sam@fabrikam.com", expiresAt = "2025-11-17T12:00:00.000Z"),
                    externalTestInvite("i3", "kai@northwind.io", awaitingApproval = true),
                    externalTestInvite("i4", "rio@vendor.io", connecting = true, revocable = false),
                ).forEachIndexed { index, invite ->
                    Box(Modifier.fillMaxWidth()) {
                        ExternalInviteRow(
                            invite = invite,
                            nowEpochMillis = now,
                            canResend = true,
                            busy = false,
                            divider = index > 0,
                            onResend = {},
                            onWithdraw = {},
                        )
                    }
                }
            }
        }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme { content() }
        }
    }
}
