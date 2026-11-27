package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.RaftAppLinkCard
import build.raft.app.data.RaftAppLinkCardElement.Text
import build.raft.app.data.RaftAppLinkTextTone
import build.raft.app.resources.Res
import build.raft.app.resources.stringResource
import build.raft.app.resources.thread_link_preview_connect_title
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [ThreadAppLinkCardPopover] and [RaftAppLinkCardView] tap behavior on a phone:
 * - Tapping a normal popover card's title invokes [onOpenLink] (which in [ThreadAppLinkCardPopoverHost]
 *   closes the card first and routes via [openThreadAppLinkAddress]).
 * - Tapping body text or empty space on the card neither opens the link nor dismisses the card.
 * - Tapping outside the card dismisses it without opening the link.
 * - A `connect_account` popover's heading is not tappable (only its Connect button opens `connectUrl`).
 * - A hover card rendered with default `onTitleClick = null` keeps its title non-tappable.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h840dp-mdpi")
class ThreadAppLinkCardPopoverTapUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun tappingPopoverTitleOpensLinkWhileBodyStaysSilent() =
        runRobolectricComposeUiTest {
            var opened = 0
            var dismissed = 0
            val title = "Fix the composer tray (#3654)"
            val body = "Approved at 7bbf8f4 by reviewer"
            val card =
                AppLinkResult.Card(
                    url = "https://stamp.build/botiverse/mobile/pull/3654",
                    app = AppLinkApp("Stamp", null),
                    title = title,
                    card =
                        RaftAppLinkCard(
                            root = "body",
                            elements = mapOf("body" to Text(body, RaftAppLinkTextTone.Muted)),
                        ),
                )

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        Box(Modifier.fillMaxSize().testTag("root")) {
                            ThreadAppLinkCardPopover(
                                card = card,
                                anchor = RaftPopoverAnchor.forRootOffset(Offset(x = 40f, y = 700f)),
                                onDismiss = { dismissed++ },
                                onOpenLink = { opened++ },
                            )
                        }
                    }
                }
            }

            waitForIdle()

            // Body tap: does nothing and keeps the popover open.
            onNode(hasText(body)).performTouchInput { click() }
            waitForIdle()
            assertEquals(0, opened, "tapping the popover body must not open the link")
            assertEquals(0, dismissed, "tapping the popover body must not dismiss the card")

            // Title tap: invokes onOpenLink without firing onDismiss directly.
            onNode(hasText(title)).performTouchInput { click() }
            waitForIdle()
            assertEquals(1, opened, "tapping the popover title must open the link")
            assertEquals(0, dismissed, "title tap delegates closing to onOpenLink")

            // Outside tap: dismisses without opening the link.
            onNodeWithTag("root").performTouchInput {
                down(Offset(20f, 20f))
                up()
            }
            waitForIdle()
            assertEquals(1, opened, "tapping outside must not open the link")
            assertEquals(1, dismissed, "tapping outside must dismiss the card")
        }

    @Test
    fun connectAccountPopoverTitleIsNotTappable() =
        runRobolectricComposeUiTest {
            var opened = 0
            var connectTitle = ""
            val card =
                AppLinkResult.Card(
                    url = "https://stamp.build/botiverse/mobile/pull/3654",
                    app = AppLinkApp("Stamp", null),
                    title = "Fix the composer tray (#3654)",
                    connectUrl = "https://stamp.build/connect",
                )

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        connectTitle = stringResource(Res.string.thread_link_preview_connect_title, card.app.name)
                        Box(Modifier.fillMaxSize()) {
                            ThreadAppLinkCardPopover(
                                card = card,
                                anchor = RaftPopoverAnchor.forRootOffset(Offset(x = 40f, y = 700f)),
                                onDismiss = {},
                                onOpenLink = { opened++ },
                            )
                        }
                    }
                }
            }

            waitForIdle()
            onNode(hasText(connectTitle) and hasClickAction()).assertDoesNotExist()
            onNode(hasText(connectTitle)).performTouchInput { click() }
            waitForIdle()
            assertEquals(0, opened, "tapping a connect_account card heading must not open the link")
        }

    @Test
    fun hoverCardTitleWithoutOnTitleClickIsNotClickable() =
        runRobolectricComposeUiTest {
            val title = "Fix the composer tray (#3654)"
            val card =
                AppLinkResult.Card(
                    url = "https://stamp.build/botiverse/mobile/pull/3654",
                    app = AppLinkApp("Stamp", null),
                    title = title,
                )

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        RaftAppLinkCardView(
                            card = card,
                            onAction = {},
                            showFooterIcon = false,
                        )
                    }
                }
            }

            waitForIdle()
            onNode(hasText(title) and hasClickAction()).assertDoesNotExist()
        }
}
