package build.raft.designsystem.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPreviewCardPreview
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftPreviewCard
import build.raft.designsystem.components.RaftPreviewCardActions
import build.raft.designsystem.components.RaftPreviewCardBody
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardDetail
import build.raft.designsystem.components.RaftPreviewCardDetailTone
import build.raft.designsystem.components.RaftPreviewCardEyebrow
import build.raft.designsystem.components.RaftPreviewCardItemActions
import build.raft.designsystem.components.RaftPreviewCardItemTitle
import build.raft.designsystem.components.RaftPreviewCardMeta
import build.raft.designsystem.components.RaftPreviewCardSurface
import build.raft.designsystem.components.RaftPreviewCardDescription
import build.raft.designsystem.components.RaftPreviewCardHeader
import build.raft.designsystem.components.RaftPreviewCardSeparator
import build.raft.designsystem.components.RaftPreviewCardTitle
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui PreviewCard open over its trigger (forced through [LocalRaftPreviewCardPreview]: an
 * offscreen frame has no pointer to rest on it), light and dark: a profile card under an
 * @mention with room below it, and one flipped above a mention at the bottom of the window. The
 * card is 300dp, 6dp from the mention, start-aligned, on `layer-popover` with the seam ring and
 * `shadow-float`; header (large avatar, name, handle, status), hairline, description, actions at
 * the card's end. [previewCardItem]: the 400dp item card.
 */
class PreviewCardScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, settleFrames = 2) {
                CompositionLocalProvider(LocalRaftPreviewCardPreview provides true) { content() }
            }
        }

    @Test
    fun previewCardOpen() =
        capture("preview_card_open") {
            Row {
                // Room below: the card opens under the mention.
                Box(Modifier.width(400.dp).height(330.dp)) {
                    Mention("@Bugen", Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 8.dp)) { ProfileCard(actions = true) }
                }
                // No room below: the card flips above the mention.
                Box(Modifier.width(400.dp).height(330.dp)) {
                    Mention("@Ada", Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 8.dp)) { ProfileCard(actions = false) }
                }
            }
        }

    @Composable
    private fun Mention(text: String, modifier: Modifier, card: @Composable () -> Unit) {
        RaftPreviewCard(card = { card() }, modifier = modifier) {
            RaftTextLink(text = text, onClick = {}, variant = RaftTextLinkVariant.Strong)
        }
    }

    @Composable
    private fun ProfileCard(actions: Boolean) {
        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        RaftPreviewCardHeader(leading = { RaftAvatar(type = RaftAvatarType.Agent, size = RaftAvatarSize.Lg, initials = "BU") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RaftPreviewCardTitle("Bugen", Modifier.weight(1f, fill = false))
                RaftStatusDot(Modifier.padding(start = 6.dp), size = RaftStatusSize.Sm, presence = RaftPresence.Busy)
                RaftText("Reviewing #25", Modifier.padding(start = 6.dp), style = typography.callout, color = colors.foregroundHint, maxLines = 1)
            }
            RaftText("@bugen", style = typography.monoMeta, color = colors.foregroundMuted, maxLines = 1)
        }
        RaftPreviewCardSeparator()
        RaftPreviewCardDescription("Reviews pull requests for the mobile app and keeps the release notes honest, even the long ones.")
        if (actions) {
            // At the card's end (raft-design #939): the frameless one first, the bordered one last.
            RaftPreviewCardActions {
                RaftButton(text = "View Profile", onClick = {}, variant = RaftButtonVariant.Ghost)
                RaftButton(text = "Message", onClick = {}, variant = RaftButtonVariant.Default)
            }
        }
    }

    /**
     * The item card (raft-ui 0.58 `size="item"`, raft-design #939), 400dp: a pull request with
     * its id line, a title cut at 2 lines, the neutral state chip then "·"-separated details and a
     * warning line, and the ⋯ (Refresh, Open in Stamp ↗) before frameless Approve and the bordered
     * Merge at the end; then a card without actions whose link takes the row, and a disabled
     * action whose reason is a caption under the row.
     */
    @Test
    fun previewCardItem() =
        capture("preview_card_item") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RaftPreviewCardSurface(width = RaftPreviewCardDefaults.ItemWidth) {
                    RaftPreviewCardBody {
                        RaftPreviewCardEyebrow("botiverse/slock#10449")
                        RaftPreviewCardItemTitle("refactor(i18n): one display-locale source for web and desktop, with the zh-TW catalog moved to the shared loader")
                        RaftPreviewCardMeta(
                            listOf(
                                RaftPreviewCardDetail.State("Open"),
                                RaftPreviewCardDetail.Text("Martin"),
                                RaftPreviewCardDetail.Text("+803 −498"),
                                RaftPreviewCardDetail.Text("2 checks failing", RaftPreviewCardDetailTone.Warning),
                            ),
                        )
                    }
                    RaftPreviewCardItemActions(
                        listOf(
                            RaftPreviewCardButton("Merge", RaftPreviewCardButtonKind.Execute, onClick = {}),
                            RaftPreviewCardButton("Approve", RaftPreviewCardButtonKind.Execute, onClick = {}, frameless = true),
                            RaftPreviewCardButton("Refresh", RaftPreviewCardButtonKind.Read, onClick = {}, frameless = true),
                            RaftPreviewCardButton("Open in Stamp", RaftPreviewCardButtonKind.Link, onClick = {}),
                        ),
                    )
                }
                RaftPreviewCardSurface(width = RaftPreviewCardDefaults.ItemWidth) {
                    RaftPreviewCardBody {
                        RaftPreviewCardItemTitle("Fix the composer tray (#3654)")
                        RaftPreviewCardMeta(listOf(RaftPreviewCardDetail.State("Merged"), RaftPreviewCardDetail.Text("Lagoon")))
                    }
                    RaftPreviewCardItemActions(
                        listOf(
                            RaftPreviewCardButton("Refresh", RaftPreviewCardButtonKind.Read, onClick = {}, frameless = true),
                            RaftPreviewCardButton("Open in Stamp", RaftPreviewCardButtonKind.Link, onClick = {}),
                        ),
                    )
                }
                RaftPreviewCardSurface(width = RaftPreviewCardDefaults.ItemWidth) {
                    RaftPreviewCardBody {
                        RaftPreviewCardItemTitle("Close stale review threads")
                        RaftPreviewCardMeta(listOf(RaftPreviewCardDetail.State("Open"), RaftPreviewCardDetail.Text("Niko")))
                    }
                    RaftPreviewCardItemActions(
                        listOf(
                            RaftPreviewCardButton(
                                "Close",
                                RaftPreviewCardButtonKind.Execute,
                                onClick = {},
                                disabledReason = "Only the author can close it",
                            ),
                        ),
                    )
                }
            }
        }
}
