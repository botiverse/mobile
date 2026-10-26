package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun aStaleCardRefusalBlocksInstallAndKeepsTheDialogOpen() = runTest {
        val recorder = Recorder()
        val store =
            ThreadInstallAppActionDialogStore(
                scope = backgroundScope,
                install = { id, _ ->
                    recorder.installs += id
                    DataResult.Error(
                        ScreenError(
                            code = "action_execute_contract_mismatch",
                            retryable = false,
                            serverMessage = "English text for the agent",
                            serverCode = "APP_PERMISSIONS_CHANGED",
                        )
                    )
                },
            )

        store.dispatch(ThreadInstallAppActionIntent.Open("m-install", card))
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        runCurrent()

        val state = assertNotNull(store.state.value)
        assertEquals(ThreadInstallAppStaleCode.PermissionsChanged, state.staleCode)
        assertTrue(state.blocked)
        assertFalse(state.canInstall)
        // Raft's copy replaces the server's English (web STALE_CARD_MESSAGE_IDS).
        assertNull(state.errorServerMessage)
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        runCurrent()
        assertEquals(1, recorder.installs.size)
    }

    @Test
    fun anOtherFailureShowsTheServerTextAndCanBeRetried() = runTest {
        var attempts = 0
        val store =
            ThreadInstallAppActionDialogStore(
                scope = backgroundScope,
                install = { _, _ ->
                    attempts++
                    if (attempts == 1) {
                        DataResult.Error(ScreenError(code = "action_execute_contract_mismatch", serverMessage = "Server busy"))
                    } else {
                        DataResult.Content(executed)
                    }
                },
            )

        store.dispatch(ThreadInstallAppActionIntent.Open("m-install", card))
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        runCurrent()
        val failed = assertNotNull(store.state.value)
        assertTrue(failed.failed)
        assertEquals("Server busy", failed.errorServerMessage)
        assertTrue(failed.canInstall)

        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        runCurrent()
        assertNull(store.state.value)
        assertEquals(2, attempts)
    }

    @Test
    fun aCardWithoutTheBoundSetCannotBeInstalled() = runTest {
        val recorder = Recorder()
        val store =
            ThreadInstallAppActionDialogStore(
                scope = backgroundScope,
                install = { id, _ -> recorder.installs += id; DataResult.Content(executed) },
            )

        store.dispatch(ThreadInstallAppActionIntent.Open("m-install", card.copy(installScopes = null, installGroups = null)))
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        runCurrent()

        assertEquals(false, store.state.value?.canInstall)
        assertTrue(recorder.installs.isEmpty())
    }

    @Test
    fun anExpiredSessionClosesTheDialogThroughTheRoute() = runTest {
        var routed = 0
        val store =
            ThreadInstallAppActionDialogStore(
                scope = backgroundScope,
                install = { _, _ -> DataResult.NoPermission(PermissionBlocker(code = "auth_required")) },
                onNoPermission = { routed++; true },
            )

        store.dispatch(ThreadInstallAppActionIntent.Open("m-install", card))
        store.dispatch(ThreadInstallAppActionIntent.Confirm)
        runCurrent()

        assertNull(store.state.value)
        assertEquals(1, routed)
    }

    @Test
    fun otherCardTypesNeverOpenTheInstallConfirmation() = runTest {
        val store =
            ThreadInstallAppActionDialogStore(scope = backgroundScope, install = { _, _ -> error("must not run") })

        store.dispatch(ThreadInstallAppActionIntent.Open("m-login", card.copy(type = "integration:approve_agent_login")))

        assertNull(store.state.value)
    }
}
