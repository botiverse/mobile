package build.raft.app

import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareServerOption
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalShareLaunchTest {
    @Test
    fun parserAcceptsOnlyIntentAndOpaqueToken() {
        val pageData =
            JSONObject()
                .put(ExternalShareLaunchIntentKey, ExternalShareLaunchIntent)
                .put(ExternalShareLaunchTokenKey, "opaque-token_1234567890")
                .put("serverId", "must-not-be-used")
                .put("slockShellInstanceId", "shell-a")

        assertEquals(
            ExternalShareLaunchRequest("opaque-token_1234567890"),
            pageData.externalShareLaunchRequestOrNull()
        )
    }

    @Test
    fun parserRejectsInvalidTokenAndRawProductState() {
        val traversal =
            JSONObject()
                .put(ExternalShareLaunchIntentKey, ExternalShareLaunchIntent)
                .put(ExternalShareLaunchTokenKey, "../../private/envelope")
        val rawText =
            JSONObject()
                .put(ExternalShareLaunchIntentKey, ExternalShareLaunchIntent)
                .put(ExternalShareLaunchTokenKey, "opaque-token_1234567890")
                .put("text", "must stay in native inbox")
        val wrongIntent =
            JSONObject()
                .put(ExternalShareLaunchIntentKey, "open_thread")
                .put(ExternalShareLaunchTokenKey, "opaque-token_1234567890")

        assertNull(traversal.externalShareLaunchRequestOrNull())
        assertNull(rawText.externalShareLaunchRequestOrNull())
        assertNull(wrongIntent.externalShareLaunchRequestOrNull())
    }

    @Test
    fun authRequiredPreservesTokenThenDispatchesExactlyOnce() {
        val loads = mutableListOf<ExternalShareIntent.Load>()
        val coordinator = ExternalShareLaunchCoordinator { loads += it }
        val request = ExternalShareLaunchRequest("opaque-token_1234567890")

        assertEquals(ExternalShareLaunchDecision.AwaitingAuthentication, coordinator.accept(request))
        assertEquals(ExternalShareLaunchDecision.AwaitingAuthentication, coordinator.reconcile(null))
        assertEquals(emptyList(), loads)

        assertEquals(
            ExternalShareLaunchDecision.Dispatched,
            coordinator.reconcile(ExternalShareLaunchAuthority("account-a", "server-a"))
        )
        assertEquals(
            listOf(
                ExternalShareIntent.Load(
                    token = request.token,
                    accountId = "account-a",
                    serverId = "server-a"
                )
            ),
            loads
        )
        assertEquals(ExternalShareLaunchDecision.Duplicate, coordinator.accept(request))
        assertEquals(ExternalShareLaunchDecision.NoLaunch, coordinator.reconcile(null))
        assertEquals(1, loads.size)
    }

    @Test
    fun processRestoreCreatesOneFreshLoadWithoutAutoSend() {
        val intents = mutableListOf<ExternalShareIntent>()
        val request = ExternalShareLaunchRequest("opaque-token_1234567890")
        val authority = ExternalShareLaunchAuthority("account-a", "server-a")

        repeat(2) {
            ExternalShareLaunchCoordinator { intents += it }.apply {
                accept(request)
                reconcile(authority)
            }
        }

        assertEquals(
            listOf<ExternalShareIntent>(
                ExternalShareIntent.Load(request.token, "account-a", "server-a"),
                ExternalShareIntent.Load(request.token, "account-a", "server-a")
            ),
            intents
        )
        assertEquals(false, intents.any { it === ExternalShareIntent.Continue })
    }

    @Test
    fun authorityIsCapturedFromCanonicalSessionOnceAndLaterServerChangeDoesNotRedispatch() {
        val loads = mutableListOf<ExternalShareIntent.Load>()
        val coordinator = ExternalShareLaunchCoordinator { loads += it }
        val request = ExternalShareLaunchRequest("opaque-token_1234567890")

        coordinator.accept(request)
        assertEquals(
            ExternalShareLaunchDecision.AwaitingAuthentication,
            coordinator.reconcile(ExternalShareLaunchAuthority("account-a", ""))
        )
        assertEquals(
            ExternalShareLaunchDecision.Dispatched,
            coordinator.reconcile(ExternalShareLaunchAuthority("account-a", "server-a"))
        )
        assertEquals(
            ExternalShareLaunchDecision.NoLaunch,
            coordinator.reconcile(ExternalShareLaunchAuthority("account-a", "server-b"))
        )
        assertEquals(
            listOf(ExternalShareIntent.Load(request.token, "account-a", "server-a")),
            loads
        )
    }

    @Test
    fun canonicalServerListIsDispatchedAndMissingDefaultFallsBackWithoutRoutePayload() {
        val loads = mutableListOf<ExternalShareIntent.Load>()
        val coordinator = ExternalShareLaunchCoordinator { loads += it }
        val request = ExternalShareLaunchRequest("opaque-token_1234567890")
        val servers =
            listOf(
                ExternalShareServerOption("server-a", "Alpha", "alpha"),
                ExternalShareServerOption("server-b", "Beta", "beta")
            )

        coordinator.accept(request)
        assertEquals(
            ExternalShareLaunchDecision.Dispatched,
            coordinator.reconcile(
                ExternalShareLaunchAuthority(
                    accountId = "account-a",
                    serverId = "removed-server",
                    servers = servers
                )
            )
        )

        assertEquals(
            listOf(ExternalShareIntent.Load(request.token, "account-a", "server-a", servers)),
            loads
        )
    }
}
