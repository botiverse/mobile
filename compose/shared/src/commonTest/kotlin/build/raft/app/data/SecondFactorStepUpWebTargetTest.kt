package build.raft.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * "Verify it's you"'s Open on the web: the refused action's page (slock web MainColumnRoutes under
 * `/s/:serverSlug/*`, staging) on the Developer environment's web URL, in the session's server.
 */
class SecondFactorStepUpWebTargetTest {
    @Test
    fun eachActionOpensItsPageOnTheEnvironmentsWebInTheServer() {
        val cases =
            listOf(
                SecondFactorStepUpWebTarget.Account to "https://staging.raft.build/s/acme/settings/account",
                SecondFactorStepUpWebTarget.Computers to "https://staging.raft.build/s/acme/computers",
                SecondFactorStepUpWebTarget.Computer("m-42") to "https://staging.raft.build/s/acme/computer/m-42",
                SecondFactorStepUpWebTarget.Conversation("c-1", directMessage = false) to "https://staging.raft.build/s/acme/channel/c-1",
                SecondFactorStepUpWebTarget.Conversation("d-1", directMessage = true) to "https://staging.raft.build/s/acme/dm/d-1",
            )
        cases.forEach { (target, expected) ->
            assertEquals(expected, secondFactorStepUpWebUrl("https://staging.raft.build/", "acme", target), "$target")
        }
    }

    @Test
    fun noEnvironmentIsProductionAndNoServerLeavesTheServerToTheWeb() {
        assertEquals(
            "https://app.raft.build/s/acme/computers",
            secondFactorStepUpWebUrl(null, "/acme/", SecondFactorStepUpWebTarget.Computers),
        )
        assertEquals(
            "http://localhost:5173/settings/account",
            secondFactorStepUpWebUrl("http://localhost:5173", null, SecondFactorStepUpWebTarget.Account),
        )
        // A server page with no server known: the web's home, which picks the last server.
        assertEquals(
            "http://localhost:5173",
            secondFactorStepUpWebUrl("http://localhost:5173", " ", SecondFactorStepUpWebTarget.Computer("m-42")),
        )
    }

    @Test
    fun theServerIsTheSelectedOneElseTheCurrentOne() {
        val servers = listOf(StoredAuthServer(id = "server-1", name = "One", slug = "one"), StoredAuthServer(id = "server-2", name = "Two", slug = "two"))
        val session = sampleSession(serverId = "server-2").copy(servers = servers)
        assertEquals("two", session.secondFactorStepUpWebServerSlug())
        assertEquals("picked", session.copy(selectedServerSlug = "picked").secondFactorStepUpWebServerSlug())
        assertNull(sampleSession().secondFactorStepUpWebServerSlug(), "a server without a slug")
        assertNull((null as StoredAuthSession?).secondFactorStepUpWebServerSlug())
    }

    @Test
    fun settingsActionsMapToTheirWebPages() {
        val cases =
            listOf(
                Triple(ACCOUNT_ACTION_PASSWORD_CHANGE, null, SecondFactorStepUpWebTarget.Account),
                Triple(ACCOUNT_ACTION_PROVIDER_CONNECT, "github", SecondFactorStepUpWebTarget.Account),
                Triple(ACCOUNT_ACTION_PROVIDER_DISCONNECT, "google", SecondFactorStepUpWebTarget.Account),
                Triple(COMPUTER_ACTION_REGISTER, null, SecondFactorStepUpWebTarget.Computers),
                Triple(COMPUTER_ACTION_ROTATE_KEY, "m-42", SecondFactorStepUpWebTarget.Computer("m-42")),
                Triple(COMPUTER_ACTION_ROTATE_KEY, null, SecondFactorStepUpWebTarget.Computers),
                Triple("somethingElse", null, null),
            )
        cases.forEach { (action, itemId, expected) ->
            assertEquals(expected, settingsSecondFactorStepUpWebTarget(action, itemId), "$action/$itemId")
        }
    }

    @Test
    fun aPageThatCantBeNamedLeavesTheDialogWithoutOpenOnTheWeb() = runTest {
        val coordinator =
            SecondFactorStepUpCoordinator(
                stepUp = { SecondFactorStepUpResult.Verified() },
                nativePasskeys = false,
                webUrl = { error("no session") },
                webTarget = { SecondFactorStepUpWebTarget.Account },
            )
        val refusal =
            DataResult.Error(
                ScreenError(
                    code = SECOND_FACTOR_STEP_UP_REQUIRED_CODE,
                    retryable = false,
                    secondFactorStepUp =
                        parseSharedSecondFactorStepUpRequired(
                            403,
                            """{"error":"Confirm it's you.","code":"second_factor_step_up_required","methods":["passkey"]}"""
                        )
                )
            )
        val outcome = async { coordinator.run<String> { refusal } }
        runCurrent()
        val dialog = assertNotNull(coordinator.state.value)
        assertNull(dialog.method, "only a passkey, and none usable here")
        assertNull(dialog.webUrl)
        assertFalse(dialog.opensOnWeb)
        coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
    }
}
