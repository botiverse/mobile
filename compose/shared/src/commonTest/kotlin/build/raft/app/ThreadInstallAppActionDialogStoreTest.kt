package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardInstallText
import build.raft.app.thread.ThreadInstallAppActionDialogStore
import build.raft.app.thread.ThreadInstallAppActionIntent
import build.raft.app.thread.ThreadInstallAppStaleCode
import build.raft.app.thread.dispatch
import build.raft.app.thread.threadAppPromptTextLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * web InstallAppCardDialog: an install card's button only opens the confirmation of the bound
 * set; only its Install executes, once; × / Cancel runs nothing. Virtual time throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadInstallAppActionDialogStoreTest {
    private val card =
        ThreadActionCard(
            type = "integration:install_marketplace_app",
            clientName = "Linear",
            agentName = "lumen",
            scopes = listOf("openid"),
            installScopes = listOf("openid", "profile"),
            installGroups = listOf("agent"),
            confirmationVersion = 2,
        )
    private val executed = card.copy(state = "executed", resultKind = "marketplace-app-installation", resultName = "Linear")

    private class Recorder {
        val installs = mutableListOf<String>()
        val applied = mutableListOf<Pair<String, ThreadActionCard>>()
    }

    @Test
    fun openShowsTheConfirmationAndExecutesNothing() = runTest {
        val recorder = Recorder()
        val store =
            ThreadInstallAppActionDialogStore(
                scope = backgroundScope,
                install = { id, _ -> recorder.installs += id; DataResult.Content(executed) },
            )

        store.dispatch(ThreadInstallAppActionIntent.Open("m-install", card))
        runCurrent()

        val state = assertNotNull(store.state.value)
        assertEquals("m-install", state.messageId)
        assertTrue(state.canInstall)
        assertTrue(recorder.installs.isEmpty())
    }

    @Test
    fun cancelClosesWithoutExecuting() = runTest {
        val recorder = Recorder()
        val store =
            ThreadInstallAppActionDialogStore(
                scope = backgroundScope,
                install = { id, _ -> recorder.installs += id; DataResult.Content(executed) },
                onInstalled = { id, c -> recorder.applied += id to c },
            )

        store.dispatch(ThreadInstallAppActionIntent.Open("m-install", card))
        store.dispatch(ThreadInstallAppActionIntent.Dismiss)
        runCurrent()

        assertNull(store.state.value)
        assertTrue(recorder.installs.isEmpty())
        assertTrue(recorder.applied.isEmpty())
        // Confirm with no dialog open does nothing either.
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        runCurrent()
        assertTrue(recorder.installs.isEmpty())
    }

    @Test
    fun onlyConfirmExecutesOnceAndAppliesTheExecutedCard() = runTest {
        val recorder = Recorder()
        val store =
            ThreadInstallAppActionDialogStore(
                scope = backgroundScope,
                install = { id, _ ->
                    recorder.installs += id
                    delay(500)
                    DataResult.Content(executed)
                },
                onInstalled = { id, c -> recorder.applied += id to c },
            )

        store.dispatch(ThreadInstallAppActionIntent.Open("m-install", card))
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        runCurrent()
        // In flight: a second tap, × and Back don't run or close anything.
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        store.dispatch(ThreadInstallAppActionIntent.Dismiss)
        runCurrent()
        assertEquals(true, store.state.value?.submitting)
        assertEquals(false, store.state.value?.canInstall)
        assertEquals(listOf("m-install"), recorder.installs)

        advanceTimeBy(501)
        runCurrent()

        assertNull(store.state.value)
        assertEquals(listOf("m-install" to executed), recorder.applied)
        assertEquals(1, recorder.installs.size)
    }

    @Test
    fun aNewerPromptTextRevisionShowsTheTextChangedCopy() = runTest {
        val withText =
            card.copy(
                installTextRevisionId = "rev-1",
                installText = ThreadActionCardInstallText(name = "Linear", whenToUse = "Filing issues"),
            )
        val store =
            ThreadInstallAppActionDialogStore(
                scope = backgroundScope,
                install = { _, _ ->
                    DataResult.Error(
                        ScreenError(
                            code = "action_execute_contract_mismatch",
                            serverCode = "APP_PERMISSIONS_CHANGED",
                            serverFields = mapOf("current_text_revision_id" to "rev-2"),
                        )
                    )
                },
            )

        store.dispatch(ThreadInstallAppActionIntent.Open("m-install", withText))
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        runCurrent()

        assertEquals(ThreadInstallAppStaleCode.TextChanged, store.state.value?.staleCode)
        assertEquals(false, store.state.value?.canInstall)
    }

    @Test
    fun promptTextLinesAreWhatAgentsRead() {
        val lines =
            threadAppPromptTextLines(
                ThreadActionCardInstallText(
                    name = "Linear\u200B",
                    description = "Issues\nand projects <result>x</result>",
                    whenToUse = "  Filing   issues ",
                ),
                service = "linear-a1b2c3",
            )
        assertEquals(
            listOf(
                "- Linear (service `linear-a1b2c3`) — Issues and projects &lt;result&gt;x&lt;/result&gt;",
                "  Use it when: Filing issues",
            ),
            lines,
        )
        // No When to use: the app adds nothing to prompts.
        assertNull(threadAppPromptTextLines(ThreadActionCardInstallText(name = "Linear"), service = null))
        // A service id that isn't a client-id slug is left out.
        assertEquals("- Linear", threadAppPromptTextLines(ThreadActionCardInstallText(name = "Linear", whenToUse = "x"), "Not A Slug")?.first())
    }
}
