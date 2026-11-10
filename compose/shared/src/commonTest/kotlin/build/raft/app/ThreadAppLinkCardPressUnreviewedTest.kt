package build.raft.app

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC 076 section 17 (botiverse/slock #13945, `unreviewed_app.v1`): an App the read marks
 * `app.reviewed: false` confirms every execute button in Raft's dialog first, undo-window ones
 * too; an App without the field (official) presses as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadAppLinkCardPressUnreviewedTest {
    private val url = "https://deploy.example.test/releases/7"

    private val ship = RaftLinkCardV2Element.Button("Ship", RaftLinkCardButtonVariant.Default, RaftLinkCardButtonAction.Execute("ship", confirm = false, undoWindowMs = 3000))
    private val approve = RaftLinkCardV2Element.Button("Approve", RaftLinkCardButtonVariant.Default, RaftLinkCardButtonAction.Execute("approve", confirm = true))

    private fun card(reviewed: Boolean?) =
        AppLinkResult.Card(
            url = url,
            app = AppLinkApp("Deployer", null, reviewed = reviewed),
            title = "Release 7",
            block =
                AppLinkBlockCard(
                    "Release 7",
                    RaftAppLinkCardV2("root", mapOf("root" to CardStack(children = listOf("ship", "approve")), "ship" to ship, "approve" to approve)),
                    businessRevision = "br-1",
                ),
            chip = false,
            handles = mapOf("ship" to "h-ship", "approve" to "h-approve"),
        )

    private val prepared =
        AppLinkPrepared("pa-1", "digest", AppLinkActionDescription(title = "Approve release 7?", body = "Deploys to production.", confirmLabel = "Approve", cancelLabel = "Not now"))

    private class Recorder {
        val prepares = mutableListOf<String>()
        val executes = mutableListOf<Pair<String, AppLinkPrepared?>>()
    }

    private fun press(reviewed: Boolean?, scope: CoroutineScope, recorder: Recorder): ThreadAppLinkCardPress {
        val host =
            ThreadAppLinkCardActionHost(
                prepare = { handle, _ ->
                    recorder.prepares += handle
                    AppLinkActionResult.Ok(prepared)
                },
                execute = { handle, _, prepared ->
                    recorder.executes += handle to prepared
                    AppLinkActionResult.Ok(AppLinkExecuted("op-1", "done", null))
                },
                reread = { true },
                toast = {},
            )
        return ThreadAppLinkCardPress(card(reviewed), host, scope, "failed", "Undo", "refresh failed")
    }

    private fun ThreadAppLinkCardPress.click(element: RaftLinkCardV2Element.Button) = assertNotNull(button(element, element.action as RaftLinkCardButtonAction.Execute)).onClick()

    @Test
    fun unreviewedUndoWindowButtonConfirmsFirstThenStartsItsUndoWindow() = runTest {
        val recorder = Recorder()
        val press = press(reviewed = false, scope = this, recorder = recorder)
        press.click(ship)
        runCurrent()
        val pending = assertNotNull(press.confirmation, "the undo-window button asks first")
        assertTrue(pending.unreviewed)
        assertEquals("Ship", pending.label, "the dialog's title is the button's label")
        assertNull(pending.prepared)
        assertEquals("Ship", press.button(ship, ship.action as RaftLinkCardButtonAction.Execute)!!.label, "no Undo countdown while asking")
        advanceTimeBy(10_000)
        assertTrue(recorder.executes.isEmpty(), "nothing runs while the dialog waits")

        press.confirm()
        runCurrent()
        assertNull(press.confirmation)
        assertEquals("Undo", press.button(ship, ship.action as RaftLinkCardButtonAction.Execute)!!.label, "confirmed: the undo window starts")
        advanceTimeBy(2_999)
        runCurrent()
        assertTrue(recorder.executes.isEmpty(), "still within the undo window")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf<Pair<String, AppLinkPrepared?>>("h-ship" to null), recorder.executes)
    }

    @Test
    fun unreviewedUndoAfterConfirmingSendsNothing() = runTest {
        val recorder = Recorder()
        val press = press(reviewed = false, scope = this, recorder = recorder)
        press.click(ship)
        runCurrent()
        press.confirm()
        runCurrent()
        press.click(ship) // now Undo
        advanceUntilIdle()
        assertTrue(recorder.executes.isEmpty())
        assertEquals("Ship", press.button(ship, ship.action as RaftLinkCardButtonAction.Execute)!!.label)
    }

    @Test
    fun unreviewedConfirmationCancelledSendsNothing() = runTest {
        val recorder = Recorder()
        val press = press(reviewed = false, scope = this, recorder = recorder)
        press.click(ship)
        runCurrent()
        assertNotNull(press.confirmation)
        press.dismissConfirmation()
        advanceUntilIdle()
        assertNull(press.confirmation)
        assertTrue(recorder.executes.isEmpty())
    }

    @Test
    fun unreviewedPrepareButtonKeepsThePreparedWordsAndIsMarkedUnreviewed() = runTest {
        val recorder = Recorder()
        val press = press(reviewed = false, scope = this, recorder = recorder)
        press.click(approve)
        runCurrent()
        assertEquals(listOf("h-approve"), recorder.prepares)
        val pending = assertNotNull(press.confirmation)
        assertTrue(pending.unreviewed)
        assertEquals("Approve release 7?", pending.prepared?.description?.title)
        press.confirm()
        runCurrent()
        assertEquals(listOf<Pair<String, AppLinkPrepared?>>("h-approve" to prepared), recorder.executes)
    }

    @Test
    fun reviewedAbsentKeepsTheUndoWindowAndThePlainPrepareConfirmation() = runTest {
        val recorder = Recorder()
        val press = press(reviewed = null, scope = this, recorder = recorder)
        press.click(ship)
        runCurrent()
        assertNull(press.confirmation, "an official App's undo button asks nothing")
        assertEquals("Undo", press.button(ship, ship.action as RaftLinkCardButtonAction.Execute)!!.label)
        advanceTimeBy(2_999)
        runCurrent()
        assertTrue(recorder.executes.isEmpty(), "still within the undo window")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf<Pair<String, AppLinkPrepared?>>("h-ship" to null), recorder.executes)

        press.click(approve)
        runCurrent()
        val pending = assertNotNull(press.confirmation)
        assertFalse(pending.unreviewed)
        assertEquals(prepared, pending.prepared)
    }
}
