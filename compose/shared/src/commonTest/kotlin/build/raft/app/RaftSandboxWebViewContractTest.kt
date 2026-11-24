package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RaftSandboxWebViewContractTest {
    @Test
    fun controllerQueuesPreAttachCommandsAndDeliversEveryRapidActionInOrder() {
        val controller = RaftWebViewController()
        val delivered = mutableListOf<String>()

        controller.goBack()
        controller.goBack()
        controller.goForward()
        controller.reload()
        controller.stop()

        assertEquals(emptyList(), delivered)

        val firstOwner = Any()
        controller.bindCommandDispatcher(firstOwner, delivered::add)
        assertEquals(listOf("goBack", "goBack", "goForward", "reload", "stop"), delivered)

        controller.goBack()
        controller.goBack()
        assertEquals(
            listOf("goBack", "goBack", "goForward", "reload", "stop", "goBack", "goBack"),
            delivered
        )

        controller.unbindCommandDispatcher(Any())
        controller.reload()
        assertEquals("reload", delivered.last())

        controller.unbindCommandDispatcher(firstOwner)
        controller.stop()
        assertEquals("reload", delivered.last())

        controller.bindCommandDispatcher(Any(), delivered::add)
        assertEquals("stop", delivered.last())
    }

    @Test
    fun runtimeJavaScriptIsEphemeralAndNeverCrossesAControllerDetach() {
        val controller = RaftWebViewController()
        val delivered = mutableListOf<String>()

        assertFalse(controller.evaluateJavaScript("window.beforeAttach = true"))
        val firstOwner = Any()
        controller.bindCommandDispatcher(firstOwner, delivered::add)
        assertEquals(emptyList(), delivered)

        assertEquals(true, controller.evaluateJavaScript("window.active = true"))
        assertEquals(
            RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX + "window.active = true",
            delivered.single(),
        )

        controller.unbindCommandDispatcher(firstOwner)
        assertFalse(controller.evaluateJavaScript("window.afterDetach = true"))
        controller.bindCommandDispatcher(Any(), delivered::add)
        assertEquals(1, delivered.size)
    }

    @Test
    fun controllerLoadUrlDispatchesImmediatelyWhenAttachedAndNeverQueuesAcrossDetach() {
        val controller = RaftWebViewController()
        val delivered = mutableListOf<String>()

        assertFalse(controller.loadUrl("https://app.example/before-attach"))
        assertFalse(controller.loadUrl("   "))

        val owner = Any()
        controller.bindCommandDispatcher(owner, delivered::add)
        assertEquals(emptyList(), delivered)

        assertEquals(true, controller.loadUrl("  https://app.example/callback?code=1  "))
        assertEquals(
            RaftWebViewController.COMMAND_LOAD_URL_PREFIX + "https://app.example/callback?code=1",
            delivered.single(),
        )
        assertFalse(controller.loadUrl(" "))

        controller.unbindCommandDispatcher(owner)
        assertFalse(controller.loadUrl("https://app.example/after-detach"))
        controller.bindCommandDispatcher(Any(), delivered::add)
        assertEquals(1, delivered.size)
    }

    @Test
    fun storagePartitionDefaultsFromEphemeralAndAllowsExplicitOverride() {
        val defaultCfg =
            RaftWebViewConfig(
                url = "https://example.test",
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = true,
                ephemeral = false,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
            )
        assertEquals(RaftWebViewStoragePartition.Default, defaultCfg.effectiveStoragePartition)

        val ephemeralCopy = defaultCfg.copy(ephemeral = true)
        assertEquals(RaftWebViewStoragePartition.Ephemeral, ephemeralCopy.effectiveStoragePartition)

        val isolated = defaultCfg.copy(storagePartition = RaftWebViewStoragePartition.IsolatedThirdParty)
        assertEquals(RaftWebViewStoragePartition.IsolatedThirdParty, isolated.effectiveStoragePartition)
    }

    @Test
    fun allowedNavigationSchemesAreNormalizedAndStable() {
        assertEquals(
            "http,https",
            setOf("HTTPS:", " http ", "https", "").toRaftWebViewAllowedNavigationSchemesValue()
        )
        assertEquals("", emptySet<String>().toRaftWebViewAllowedNavigationSchemesValue())
    }

    @Test
    fun navigationStateUsesSafeDefaultsForMissingOrInvalidPayload() {
        val missing = RaftWebViewNavigationState.decode(JSONObject().put("progress", -4))
        val invalid = RaftWebViewNavigationState.decode("not-json")

        assertEquals("", missing.url)
        assertEquals("", missing.title)
        assertFalse(missing.canGoBack)
        assertFalse(missing.canGoForward)
        assertFalse(missing.isLoading)
        assertEquals(0, missing.progress)
        assertEquals(RaftWebViewNavigationState(), invalid)
    }

    @Test
    fun navigationStateRelayCoalescesReentrantStopWithoutRecursing() {
        val relay = RaftWebViewNavigationStateRelay()
        val controller = RaftWebViewController()
        var currentLoading = true
        var stopCount = 0
        val delivered = mutableListOf<RaftWebViewNavigationState>()

        controller.bindCommandDispatcher(Any()) { command ->
            if (command == RaftWebViewController.COMMAND_STOP) {
                stopCount += 1
                currentLoading = false
                // Mirrors COMMAND_STOP notifying with force = false (and would previously recurse on force = true).
                relay.notify(
                    force = false,
                    isActive = { true },
                    readState = {
                        RaftWebViewNavigationState(
                            url = "https://api.raft.build/api/oauth/authorize?client_id=billing",
                            isLoading = currentLoading,
                            loadGeneration = 1,
                        )
                    },
                    deliver = { state ->
                        delivered += state
                        // Buggy caller calling stop() unconditionally on every callback must never recurse.
                        controller.stop()
                    },
                )
            }
        }

        relay.notify(
            force = false,
            isActive = { true },
            readState = {
                RaftWebViewNavigationState(
                    url = "https://api.raft.build/api/oauth/authorize?client_id=billing",
                    isLoading = currentLoading,
                    loadGeneration = 1,
                )
            },
            deliver = { state ->
                delivered += state
                controller.stop()
            },
        )

        assertEquals(2, delivered.size)
        assertEquals(true, delivered[0].isLoading)
        assertEquals(false, delivered[1].isLoading)
        assertEquals(2, stopCount)
    }

    @Test
    fun navigationStateRelayBoundsEvenForcedReentrantLoop() {
        val relay = RaftWebViewNavigationStateRelay(maxReentrantRounds = 3)
        var calls = 0

        fun emitForced() {
            relay.notify(
                force = true,
                isActive = { true },
                readState = { RaftWebViewNavigationState(url = "https://api.raft.build/authorize", isLoading = false) },
                deliver = {
                    calls += 1
                    emitForced()
                },
            )
        }

        emitForced()
        assertEquals(3, calls)
    }

    @Test
    fun controllerCoalescesReentrantStopCommandsWithoutStackOverflow() {
        val controller = RaftWebViewController()
        var dispatchCount = 0
        controller.bindCommandDispatcher(Any()) { command ->
            dispatchCount += 1
            if (command == RaftWebViewController.COMMAND_STOP) {
                // Re-entrant duplicate stops collapse iteratively and stop at the hard cap.
                controller.stop()
                controller.stop()
            }
        }

        controller.stop()
        assertEquals(9, dispatchCount) // 1 initial + 8 bounded re-entrant drains
    }
}

@Suppress("unused")
@Composable
private fun legacyTrailingLambdaWebViewCallsStillCompile() {
    RaftWebView("https://example.test")
    RaftWebView("https://example.test") {}
    RaftWebView("https://example.test") { it }
    RaftSandboxWebView("https://example.test")
    RaftSandboxWebView("https://example.test") {}
    RaftSandboxWebView("https://example.test") { it }
}
