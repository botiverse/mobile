package build.raft.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.data.AppLinkActionDescription
import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkBlockCard
import build.raft.app.data.AppLinkExecuted
import build.raft.app.data.AppLinkPrepared
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardStack
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.data.RaftMessageCardHandle
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * botiverse/slock #14958 (a6158cff91) on an App's block card: while a button's work runs, its
 * spinner says "Working…" (web `message.appLink.action.working`, the `loadingLabel` of
 * LinkCardActions' buttons; never a task status such as In progress), and Raft's confirmation is
 * the small dialog (web `LinkCardConfirmDialog`, raft-ui Dialog `size="sm"`, 400dp): × in the head,
 * Cancel first and the confirm last. The window is wide, so the dialog's width shows its size.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h900dp")
class ThreadAppLinkCardConfirmUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val approve =
        RaftLinkCardV2Element.Button("Approve", RaftLinkCardButtonVariant.Default, RaftLinkCardButtonAction.Execute("approve", confirm = true))

    private val card =
        AppLinkResult.Card(
            url = "https://deploy.example.test/releases/7",
            app = AppLinkApp("Deployer", null),
            title = "Release 7",
            block =
                AppLinkBlockCard(
                    "Release 7",
                    RaftAppLinkCardV2("root", mapOf("root" to CardStack(children = listOf("approve")), "approve" to approve)),
                    businessRevision = "br-1",
                ),
            chip = false,
            handles = mapOf("approve" to "h-approve"),
        )

    private val prepared =
        AppLinkPrepared(
            "pa-1",
            "digest",
            AppLinkActionDescription(title = "Approve release 7?", body = "Deploys to production.", confirmLabel = "Approve release", cancelLabel = "Not now"),
        )

    private fun actionHost(prepare: suspend () -> AppLinkActionResult<AppLinkPrepared>) =
        ThreadAppLinkCardActionHost(
            prepare = { _, _ -> prepare() },
            execute = { _, _, _ -> AppLinkActionResult.Ok(AppLinkExecuted("op-1", "done", null)) },
            reread = { true },
            toast = {},
        )

    @Test
    fun aRunningLinkCardButtonSaysWorking() = runRobolectricComposeUiTest {
        // Raft asks the App to describe the action, and the App has not answered yet.
        val prepare = CompletableDeferred<AppLinkActionResult<AppLinkPrepared>>()
        val host = actionHost { prepare.await() }
        setContent { RaftTheme(appearance = RaftAppearance.Light) { ThreadAppLinkBlockCard(card, openUri = {}, actions = host) } }
        onNodeWithText("Approve").performClick()
        waitUntilShown("the pressed button's Working… spinner") {
            onAllNodesWithContentDescription("Working…", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun raftsConfirmationIsTheSmallDialogWithCloseInItsHeadAndCancelFirst() = runRobolectricComposeUiTest {
        val host = actionHost { AppLinkActionResult.Ok(prepared) }
        setContent { RaftTheme(appearance = RaftAppearance.Light) { ThreadAppLinkBlockCard(card, openUri = {}, actions = host) } }
        onNodeWithText("Approve").performClick()
        waitUntilShown("Raft's confirmation") { onAllNodesWithTag(AppLinkConfirmDialogTestTag).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AppLinkConfirmDialogTestTag).assertWidthIsEqualTo(400.dp)
        assertTrue(onAllNodesWithContentDescription("Close", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "× in the head")
        val cancel = onNodeWithText("Not now").getBoundsInRoot()
        val confirm = onNodeWithText("Approve release").getBoundsInRoot()
        assertTrue(cancel.right <= confirm.left, "Cancel first, the confirm last: cancel=$cancel confirm=$confirm")
    }

    @Test
    fun aCardMessagesActingButtonSaysWorkingWhileItRuns() = runRobolectricComposeUiTest {
        val prepare = CompletableDeferred<AppLinkActionResult<AppLinkPrepared>>()
        val host = actionHost { prepare.await() }
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftDesignSystemTheme {
                    // RFC 086: a card message's acting button runs through the same press (web MessageCardActingButtons).
                    val press = rememberThreadAppLinkCardPress(card, host, key = "m-1")
                    RaftMessageCardActingButton(
                        elementId = "approve",
                        button = approve,
                        action = approve.action as RaftLinkCardButtonAction.Execute,
                        state = RaftMessageCardActingState.Pressable(RaftMessageCardHandle("approve", "approve", card.url, "ticket-1", "h-approve")),
                        press = press,
                        onAsk = {},
                    )
                }
            }
        }
        onNodeWithText("Approve").performClick()
        waitUntilShown("the acting button's Working… spinner") {
            onAllNodesWithContentDescription("Working…", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.waitUntilShown(what: String, shown: () -> Boolean) {
        try {
            waitUntil(timeoutMillis = 5_000, condition = shown)
        } catch (timeout: Throwable) {
            error("waited for $what: ${timeout.message}")
        }
    }
}
