package build.raft.designsystem

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftTooltipTouchTimer
import build.raft.designsystem.components.RaftPreviewCardBody
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardDetail
import build.raft.designsystem.components.RaftPreviewCardItemActions
import build.raft.designsystem.components.RaftPreviewCardItemTitle
import build.raft.designsystem.components.RaftPreviewCardMeta
import build.raft.designsystem.components.RaftPreviewCardSurface
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred

/**
 * Disabled row buttons in [RaftPreviewCardItemActions] show their reason in their [build.raft.designsystem.components.RaftTooltip]
 * on hover or a touch long press (matching web `LinkCardActions` `openOnLongPress`), rather than
 * printing a caption under the button row.
 */
@OptIn(ExperimentalTestApi::class)
class RaftPreviewCardItemActionsUiTest {
    private val reason = "Checks aren't loaded yet"

    @Test
    fun longPressingADisabledRowButtonShowsItsTooltipWithLabelAndReason() =
        runComposeUiTest {
            var mergeClicks = 0
            val timeUp = CompletableDeferred<Unit>()
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    CompositionLocalProvider(LocalRaftTooltipTouchTimer provides { timeUp.await() }) {
                        Box(Modifier.padding(top = 64.dp, start = 16.dp, end = 16.dp)) {
                            RaftPreviewCardSurface(
                                width = RaftPreviewCardDefaults.ItemWidth,
                                // Match ThreadAppLinkCardPopoverFrame's blank-space tap sink.
                                modifier = Modifier.pointerInput(Unit) { detectTapGestures {} },
                            ) {
                                RaftPreviewCardBody {
                                    RaftPreviewCardItemTitle("Fix the composer tray (#3654)")
                                }
                                RaftPreviewCardItemActions(
                                    buttons =
                                        listOf(
                                            RaftPreviewCardButton(
                                                label = "Approve",
                                                kind = RaftPreviewCardButtonKind.Execute,
                                                onClick = {},
                                                frameless = true,
                                            ),
                                            RaftPreviewCardButton(
                                                label = "Merge",
                                                kind = RaftPreviewCardButtonKind.Execute,
                                                onClick = { mergeClicks++ },
                                                disabledReason = reason,
                                            ),
                                        ),
                                )
                            }
                        }
                    }
                }
            }
            mainClock.autoAdvance = false

            // No footnote caption under the button row at rest.
            onNode(hasText(reason, substring = true)).assertDoesNotExist()

            // Short tap on the disabled Merge button neither clicks nor opens the tooltip.
            onNode(hasText("Merge")).performTouchInput { click() }
            mainClock.advanceTimeBy(300)
            assertEquals(0, mergeClicks)
            onNode(hasText("Merge\n$reason")).assertDoesNotExist()

            // Touch long press on the disabled Merge button opens its RaftTooltip with label and reason.
            onNode(hasText("Merge")).performTouchInput { longClick() }
            mainClock.advanceTimeBy(300)
            assertEquals(0, mergeClicks)
            onNode(hasText("Merge\n$reason")).assertExists()

            timeUp.complete(Unit)
            mainClock.advanceTimeBy(1_000)
            onNode(hasText("Merge\n$reason")).assertDoesNotExist()
        }

    @Test
    fun hoveringADisabledRowButtonShowsItsTooltipWithLabelAndReason() =
        runComposeUiTest {
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Box(Modifier.padding(top = 64.dp, start = 16.dp, end = 16.dp)) {
                        RaftPreviewCardSurface(width = RaftPreviewCardDefaults.ItemWidth) {
                            RaftPreviewCardBody {
                                RaftPreviewCardItemTitle("Fix the composer tray (#3654)")
                            }
                            RaftPreviewCardItemActions(
                                buttons =
                                    listOf(
                                        RaftPreviewCardButton(
                                            label = "Merge",
                                            kind = RaftPreviewCardButtonKind.Execute,
                                            onClick = {},
                                            disabledReason = reason,
                                        ),
                                    ),
                            )
                        }
                    }
                }
            }
            mainClock.autoAdvance = false

            onNode(hasText(reason, substring = true)).assertDoesNotExist()
            onNode(hasText("Merge")).performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(500)
            onNode(hasText("Merge\n$reason")).assertExists()

            onNode(hasText("Merge")).performMouseInput { exit() }
            mainClock.advanceTimeBy(500)
            onNode(hasText("Merge\n$reason")).assertDoesNotExist()
        }

    @Test
    fun tappingTappableTitleOpensItemWhileBodyDoesNot() =
        runComposeUiTest {
            var titleClicks = 0
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Box(Modifier.padding(16.dp)) {
                        RaftPreviewCardSurface(
                            width = RaftPreviewCardDefaults.ItemWidth,
                            modifier = Modifier.pointerInput(Unit) { detectTapGestures {} },
                        ) {
                            RaftPreviewCardBody {
                                RaftPreviewCardItemTitle(
                                    text = "Fix the composer tray (#3654)",
                                    onClick = { titleClicks++ },
                                )
                                RaftPreviewCardMeta(
                                    listOf(
                                        RaftPreviewCardDetail.State("Open"),
                                        RaftPreviewCardDetail.Text("Lagoon"),
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            onNode(hasText("Lagoon")).performTouchInput { click() }
            assertEquals(0, titleClicks, "tapping the body must not open the item")

            onNode(hasText("Fix the composer tray (#3654)")).performTouchInput { click() }
            assertEquals(1, titleClicks, "tapping the title must open the item")
        }
}
