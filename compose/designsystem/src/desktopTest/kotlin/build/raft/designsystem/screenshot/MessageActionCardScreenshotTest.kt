package build.raft.designsystem.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMessageActionCard
import build.raft.designsystem.components.RaftMessageActionCardActions
import build.raft.designsystem.components.RaftMessageActionCardDefaults
import build.raft.designsystem.components.RaftMessageActionCardHeaderDescription
import build.raft.designsystem.components.RaftMessageActionCardHint
import build.raft.designsystem.components.RaftMessageActionCardNotice
import build.raft.designsystem.components.RaftMessageActionCardProperties
import build.raft.designsystem.components.RaftMessageActionCardProperty
import build.raft.designsystem.components.RaftMessageActionCardScope
import build.raft.designsystem.components.RaftMessageActionCardScopeList
import build.raft.designsystem.components.RaftMessageActionCardStatusTone
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui MessageActionCard (raft-design `message-action-card`, its doc fixtures): one channel
 * card in every state (waiting open with its buttons; paused and confirm-again open with the
 * note beside disabled or re-labelled buttons; done, declined and view-only folded to the
 * header with the line under the title), an agent card done and opened again, and an app
 * login card with its notice, reason and scrolling permission list. The properties switch on
 * the card's own content width: one column in a phone's 360dp card (336dp of content), two
 * columns in a card at its 440dp cap.
 */
class MessageActionCardScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, content = content)
        }

    @Test
    fun messageActionCardStates() =
        capture("message_action_card_states") {
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Caption("Waiting")
                ChannelCard(note = null)
                Caption("Paused")
                ChannelCard(
                    status = "Paused",
                    note = "Paused while this channel is being converted to a Joint channel.",
                    enabled = false,
                )
                Caption("Confirm again")
                ChannelCard(
                    status = "Confirm again",
                    tone = RaftMessageActionCardStatusTone.Accent,
                    note = "This channel became a Joint channel. Confirm again to continue.",
                    verb = "Reconfirm",
                )
                Caption("Done, declined, view only (folded)")
                FoldedChannelCard("Done", RaftMessageActionCardStatusTone.Success, "Created by Ada · 10:51 → #release-review", check = true)
                FoldedChannelCard("Declined", RaftMessageActionCardStatusTone.Neutral, "Declined by Ada. Nothing was changed.")
                FoldedChannelCard("View only", RaftMessageActionCardStatusTone.Neutral, "Join this channel to act on this card.")
            }
        }

    @Test
    fun messageActionCardContent() =
        capture("message_action_card_content") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("Done, opened again; a long title wraps")
                    RaftMessageActionCard(
                        title = "Create agent @Folio for the documentation and its publishing pipeline",
                        icon = { AppIconImage(AppIcon.Bot, RaftMessageActionCardDefaults.HeaderGlyphSize) },
                        description = { RaftMessageActionCardHeaderDescription("Submitted by RC · 10/20 23:09 → @Folio", check = true) },
                        status = "Done",
                        statusTone = RaftMessageActionCardStatusTone.Success,
                        expanded = true,
                        onExpandedChange = {},
                        toggleLabel = "Show or hide details",
                    ) {
                        RaftMessageActionCardProperties {
                            RaftMessageActionCardProperty(
                                "Description",
                                "Documentation DRI — owns how Raft's written docs are shaped and kept current.",
                            )
                            RaftMessageActionCardProperty("Suggested computer", "build-mac-01", mono = true)
                        }
                        RaftMessageActionCardHint("For stdrc — a dedicated agent for writing docs.", label = "Reason")
                    }
                    Caption("App login: notice, reason, permissions (default icon)")
                    RaftMessageActionCard(title = "Approve Linear for @lumen") {
                        RaftMessageActionCardNotice(
                            "@lumen acts as you in Linear with these permissions.",
                            icon = { RaftGlyphIcon(RaftGlyph.Info, size = 14.dp) },
                        )
                        RaftMessageActionCardProperties {
                            RaftMessageActionCardProperty("App", "Linear")
                            RaftMessageActionCardProperty("Agent", "@lumen")
                        }
                        RaftMessageActionCardHint("I need to file the issues you listed in this morning's triage.", label = "Reason")
                        RaftMessageActionCardScopeList("Permissions (4)", viewportDescription = "Permissions") {
                            Scope("openid", "Signs you in with Raft and issues a stable identity token. Authentication only.")
                            Scope("profile", "Reads your basic profile fields. No access to messages, files, or agents.")
                            Scope("agent:read", "Reads the IDs, handles, names and avatars of agents in the Server you authorize.")
                            Scope("agent:event:write", "Sends structured event messages to the one agent you authorize.")
                        }
                        RaftMessageActionCardActions {
                            RaftButton("Decline", onClick = {}, variant = RaftButtonVariant.Ghost)
                            RaftButton("Approve", onClick = {})
                        }
                    }
                }
                Caption("A card at its 440dp cap (416dp of content): properties in two columns")
                Column(Modifier.width(440.dp)) {
                    RaftMessageActionCard(
                        title = "Add 3 members to #general",
                        icon = { RaftGlyphIcon(RaftGlyph.Plus, size = RaftMessageActionCardDefaults.HeaderGlyphSize) },
                    ) {
                        RaftMessageActionCardProperties {
                            RaftMessageActionCardProperty("People", "Ada, Will")
                            RaftMessageActionCardProperty("Agents", "Lumen")
                            RaftMessageActionCardProperty("Hidden value", "••••", valueColor = RaftTheme.colors.foregroundHint)
                        }
                        RaftMessageActionCardActions { RaftButton("Add Members", onClick = {}) }
                    }
                }
            }
        }

    @Composable
    private fun ChannelCard(
        status: String? = null,
        tone: RaftMessageActionCardStatusTone = RaftMessageActionCardStatusTone.Neutral,
        note: String?,
        enabled: Boolean = true,
        verb: String = "Create Channel",
    ) {
        RaftMessageActionCard(
            title = "Create private channel #release-review",
            icon = { RaftGlyphIcon(RaftGlyph.Hash, size = RaftMessageActionCardDefaults.HeaderGlyphSize) },
            status = status,
            statusTone = tone,
            expanded = true,
            onExpandedChange = if (status != null) ({}) else null,
            toggleLabel = "Show or hide details",
        ) {
            ChannelProperties()
            RaftMessageActionCardHint("You asked for a private space for the release review.", label = "Reason")
            RaftMessageActionCardActions(note = note) {
                RaftButton("Decline", onClick = {}, variant = RaftButtonVariant.Ghost, enabled = enabled)
                RaftButton(verb, onClick = {}, enabled = enabled)
            }
        }
    }

    @Composable
    private fun FoldedChannelCard(status: String, tone: RaftMessageActionCardStatusTone, line: String, check: Boolean = false) {
        RaftMessageActionCard(
            title = "Create private channel #release-review",
            icon = { RaftGlyphIcon(RaftGlyph.Hash, size = RaftMessageActionCardDefaults.HeaderGlyphSize) },
            description = { RaftMessageActionCardHeaderDescription(line, check = check) },
            status = status,
            statusTone = tone,
            expanded = false,
            onExpandedChange = {},
            toggleLabel = "Show or hide details",
        ) {
            ChannelProperties()
        }
    }

    @Composable
    private fun ChannelProperties() {
        RaftMessageActionCardProperties {
            RaftMessageActionCardProperty("Description", "Coordinate the final release review.")
            RaftMessageActionCardProperty("People", "Ada, Will")
            RaftMessageActionCardProperty("Agents", "Atlas")
        }
    }

    @Composable
    private fun Scope(name: String, description: String) = RaftMessageActionCardScope(name, description)

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
