package build.raft.app

import build.raft.app.data.APP_LINK_HANDLE_FRESH_MILLIS
import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkBlockCard
import build.raft.app.data.AppLinkExecuted
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardStack
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.thread.ThreadAppLinkCardActionHost
import build.raft.app.thread.ThreadAppLinkCardPress
import build.raft.app.thread.button
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #proj-mobile:#198 (web `pressExecute`, `LINK_CARD_HANDLE_FRESH_MS`): an App card's handles are
 * signed for at most 10 minutes. A press with a handle read more than 9 minutes ago (the app was in
 * the background) reads the card again first and sends the fresh handle, instead of failing with
 * the expired one; a recent handle is sent as it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadAppLinkCardPressFreshHandleTest {
    private val url = "https://deploy.example.test/releases/7"
    private val now = 100 * 60_000L
    private val approve =
        RaftLinkCardV2Element.Button("Approve", RaftLinkCardButtonVariant.Default, RaftLinkCardButtonAction.Execute("approve", confirm = false, undoWindowMs = 1000))

    private fun card(handle: String?, readAt: Long?) =
        AppLinkResult.Card(
            url = url,
            app = AppLinkApp("Deployer", null),
            title = "Release 7",
            block = AppLinkBlockCard("Release 7", RaftAppLinkCardV2("root", mapOf("root" to CardStack(children = listOf("approve")), "approve" to approve)), businessRevision = "br-1"),
            chip = false,
            handles = handle?.let { mapOf("approve" to it) }.orEmpty(),
            readAtMillis = readAt,
        )

    /** In order: "reread", "execute:<handle>" (a press reads the card again after it runs, too). */
    private class Recorder {
        val events = mutableListOf<String>()
        val executes get() = events.filter { it.startsWith("execute:") }.map { it.removePrefix("execute:") }
        val rereadsBeforeExecute get() = events.takeWhile { !it.startsWith("execute:") }.count { it == "reread" }
        val toasts = mutableListOf<String>()
    }

    private fun press(
        scope: CoroutineScope,
        recorder: Recorder,
        readAt: Long?,
        rereadOk: Boolean = true,
        fresh: AppLinkResult.Card? = card("h-fresh", now),
    ): ThreadAppLinkCardPress {
        val host =
            ThreadAppLinkCardActionHost(
                prepare = { _, _ -> error("not a prepare button") },
                execute = { handle, _, _ ->
                    recorder.events += "execute:$handle"
                    AppLinkActionResult.Ok(AppLinkExecuted("op-1", "done", null))
                },
                reread = {
                    recorder.events += "reread"
                    rereadOk
                },
                toast = { recorder.toasts += it },
                held = { fresh },
                nowMillis = { now },
            )
        return ThreadAppLinkCardPress(card("h-old", readAt), host, scope, "failed", "Undo", "refresh failed", "changed")
    }

    private fun ThreadAppLinkCardPress.click() = assertNotNull(button(approve, approve.action as RaftLinkCardButtonAction.Execute)).onClick()

    @Test
    fun anOldOrUnstampedHandleIsReadAgainFirstAndTheFreshOneSent() = runTest {
        listOf(now - APP_LINK_HANDLE_FRESH_MILLIS - 1, null).forEach { readAt ->
            val recorder = Recorder()
            press(this, recorder, readAt = readAt).click()
            advanceUntilIdle()
            assertEquals(1, recorder.rereadsBeforeExecute, "readAt=$readAt")
            assertEquals(listOf("h-fresh"), recorder.executes, "readAt=$readAt")
            assertTrue(recorder.toasts.isEmpty(), "readAt=$readAt")
        }
    }

    @Test
    fun aFailedReadSendsNothingAndSaysSo() = runTest {
        val recorder = Recorder()
        press(this, recorder, readAt = 0L, rereadOk = false).click()
        advanceUntilIdle()
        assertTrue(recorder.executes.isEmpty())
        assertEquals(listOf("reread"), recorder.events)
        assertEquals(listOf("refresh failed"), recorder.toasts)
    }

    @Test
    fun aCardThatNoLongerHasTheButtonSendsNothing() = runTest {
        val recorder = Recorder()
        press(this, recorder, readAt = 0L, fresh = card(handle = null, readAt = now)).click()
        advanceUntilIdle()
        assertTrue(recorder.executes.isEmpty())
        assertEquals(listOf("changed"), recorder.toasts)
    }
}
