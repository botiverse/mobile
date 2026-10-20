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
    fun allowedNavigationSchemesAreNormalizedAndStable() {
        assertEquals(
            "http,https",
            setOf("HTTPS:", " http ", "https", "").toRaftWebViewAllowedNavigationSchemesValue()
        )
        assertEquals("", emptySet<String>().toRaftWebViewAllowedNavigationSchemesValue())
    }

    @Test
    fun navigationStateDecodesNativePayloadAndClampsProgress() {
        val state =
            RaftWebViewNavigationState.decode(
                JSONObject()
                    .put("url", "https://example.test/path?secret=1")
                    .put("title", "Example")
                    .put("canGoBack", true)
                    .put("canGoForward", true)
                    .put("isLoading", true)
                    .put("progress", 145)
            )

        assertEquals("https://example.test/path?secret=1", state.url)
        assertEquals("Example", state.title)
        assertEquals(true, state.canGoBack)
        assertEquals(true, state.canGoForward)
        assertEquals(true, state.isLoading)
        assertEquals(100, state.progress)
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
