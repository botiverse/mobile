@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package build.raft.webcontainer

import build.raft.webcontainer.jsb.JsbBootstrapScripts
import build.raft.webcontainer.jsb.JsbDispatcher
import build.raft.webcontainer.jsb.JsbErrorCode
import build.raft.webcontainer.jsb.JsbPolicy
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.jsb.JsbResult
import build.raft.webcontainer.jsb.JsbWireCodec
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsbRuntimeTest {
    private val trustedOrigin = parseWebContainerOrigin("https://app.raft.build")!!
    private val untrustedOrigin = parseWebContainerOrigin("https://evil.example")!!

    @Test
    fun wireCodecRejectsInvalidJsonVersionAndNonObjectParams() {
        assertIs<JsbWireCodec.DecodeOutcome.Invalid>(JsbWireCodec.decodeRequest("{not json"))

        val wrongVersion = JsbWireCodec.decodeRequest("""{"v":2,"id":"c1","method":"ping"}""")
        assertIs<JsbWireCodec.DecodeOutcome.Invalid>(wrongVersion)
        assertEquals(JsbErrorCode.UNSUPPORTED_VERSION, wrongVersion.code)
        assertEquals("c1", wrongVersion.callId)

        val stringVersion = JsbWireCodec.decodeRequest("""{"v":"1","id":"c1s","method":"ping"}""")
        assertIs<JsbWireCodec.DecodeOutcome.Invalid>(stringVersion)
        assertEquals(JsbErrorCode.INVALID_MESSAGE, stringVersion.code)

        val badParams = JsbWireCodec.decodeRequest("""{"v":1,"id":"c2","method":"ping","params":[1,2]}""")
        assertIs<JsbWireCodec.DecodeOutcome.Invalid>(badParams)
        assertEquals(JsbErrorCode.INVALID_PARAMS, badParams.code)

        val valid = JsbWireCodec.decodeRequest("""{"v":1,"id":"c3","method":"ping","params":{"x":"y"}}""")
        assertIs<JsbWireCodec.DecodeOutcome.Valid>(valid)
        assertEquals("ping", valid.request.method)
        assertEquals("y", valid.request.params["x"]?.jsonPrimitive?.content)
    }

    @Test
    fun dispatcherEnforcesDisabledPayloadSizeSubframeOriginAndCapabilityGates() = runTest {
        val registry =
            JsbRegistry().register(
                method = "device.info",
                requiredCapabilities = setOf("device.read"),
            ) { _, _ ->
                JsbResult.Success(buildJsonObject { put("ok", "yes") })
            }

        // Disabled by default (still returns request id so web Promises settle cleanly)
        val disabledDispatcher = JsbDispatcher(JsbPolicy.Disabled, registry, this)
        var disabledReply = ""
        disabledDispatcher.dispatch("""{"v":1,"id":"1","method":"device.info"}""", trustedOrigin, "https://app.raft.build/", true) {
            disabledReply = it
        }
        assertEquals("BRIDGE_DISABLED", parseErrorCode(disabledReply))
        assertEquals("1", Json.parseToJsonElement(disabledReply).jsonObject["id"]?.jsonPrimitive?.content)

        val policy =
            JsbPolicy(
                enabled = true,
                allowedOrigins = setOf(trustedOrigin),
                grantedCapabilities = emptySet(),
                maxPayloadBytes = 64,
            )
        val dispatcher = JsbDispatcher(policy, registry, this)

        // Payload cap (preserves request id extracted from bounded prefix)
        var tooLargeReply = ""
        val hugePayload = """{"v":1,"id":"2","method":"device.info","params":{"pad":"${"a".repeat(128)}"}}"""
        dispatcher.dispatch(hugePayload, trustedOrigin, "https://app.raft.build/", true) { tooLargeReply = it }
        assertEquals("PAYLOAD_TOO_LARGE", parseErrorCode(tooLargeReply))
        assertEquals("2", Json.parseToJsonElement(tooLargeReply).jsonObject["id"]?.jsonPrimitive?.content)

        // Subframe denied
        var subframeReply = ""
        dispatcher.dispatch("""{"v":1,"id":"3","method":"device.info"}""", trustedOrigin, "https://app.raft.build/", false) {
            subframeReply = it
        }
        assertEquals("SUBFRAME_DENIED", parseErrorCode(subframeReply))

        // Origin denied
        var originReply = ""
        dispatcher.dispatch("""{"v":1,"id":"4","method":"device.info"}""", untrustedOrigin, "https://evil.example/", true) {
            originReply = it
        }
        assertEquals("ORIGIN_DENIED", parseErrorCode(originReply))

        // Capability denied
        var capReply = ""
        dispatcher.dispatch("""{"v":1,"id":"5","method":"device.info"}""", trustedOrigin, "https://app.raft.build/", true) {
            capReply = it
        }
        assertEquals("CAPABILITY_DENIED", parseErrorCode(capReply))
    }

    @Test
    fun dispatcherExecutesAsyncKotlinHandlerHandlesTimeoutAndCancelsInFlight() = runTest {
        val telemetry = mutableListOf<WebContainerTelemetryEvent>()
        val registry =
            JsbRegistry()
                .register("echo", requiredCapabilities = setOf("core")) { ctx, params ->
                    JsbResult.Success(
                        buildJsonObject {
                            put("surface", ctx.surfaceId)
                            put("msg", params["msg"]?.jsonPrimitive?.content.orEmpty())
                        },
                    )
                }
                .register("slow", timeoutMs = 1_000L) { _, _ ->
                    delay(5_000L)
                    JsbResult.Success()
                }
                .register("cancellable") { _, _ ->
                    delay(10_000L)
                    JsbResult.Success()
                }
                .register("boom") { _, _ ->
                    error("unexpected failure")
                }

        val policy =
            JsbPolicy(
                enabled = true,
                allowedOrigins = setOf(trustedOrigin),
                grantedCapabilities = setOf("core"),
            )
        val dispatcher =
            JsbDispatcher(
                policy = policy,
                registry = registry,
                scope = this,
                surfaceId = "artifact_view",
                telemetrySink = { telemetry += it },
            )

        // Successful async RPC
        var echoReply = ""
        dispatcher.dispatch(
            """{"v":1,"id":"e1","method":"echo","params":{"msg":"hello"}}""",
            trustedOrigin,
            "https://app.raft.build/a",
            true,
        ) { echoReply = it }
        runCurrent()
        val echoObj = Json.parseToJsonElement(echoReply).jsonObject
        assertTrue(echoObj["ok"]!!.jsonPrimitive.boolean)
        assertEquals("artifact_view", echoObj["data"]!!.jsonObject["surface"]!!.jsonPrimitive.content)
        assertEquals("hello", echoObj["data"]!!.jsonObject["msg"]!!.jsonPrimitive.content)

        // Handler exception is caught as INTERNAL_ERROR (never crashes host)
        var boomReply = ""
        dispatcher.dispatch("""{"v":1,"id":"b1","method":"boom"}""", trustedOrigin, "https://app.raft.build/a", true) {
            boomReply = it
        }
        runCurrent()
        assertEquals("INTERNAL_ERROR", parseErrorCode(boomReply))

        // Per-call timeout
        var slowReply = ""
        dispatcher.dispatch("""{"v":1,"id":"s1","method":"slow"}""", trustedOrigin, "https://app.raft.build/a", true) {
            slowReply = it
        }
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals("TIMEOUT", parseErrorCode(slowReply))

        // Cancel in-flight on navigation (guarantees single CANCELLED reply AND single telemetry event)
        var cancelReplies = 0
        var lastCancelReply = ""
        dispatcher.dispatch("""{"v":1,"id":"x1","method":"cancellable"}""", trustedOrigin, "https://app.raft.build/a", true) {
            cancelReplies += 1
            lastCancelReply = it
        }
        runCurrent()
        assertEquals(1, dispatcher.activeCallCount)
        dispatcher.cancelInFlight("main_frame_navigation")
        runCurrent()
        assertEquals(0, dispatcher.activeCallCount)
        assertEquals(1, cancelReplies)
        assertEquals("CANCELLED", parseErrorCode(lastCancelReply))
        assertEquals(
            1,
            telemetry.count { it is WebContainerTelemetryEvent.JsbCallCompleted && it.method == "cancellable" },
            "Cancelled call must emit JsbCallCompleted exactly once",
        )
        assertTrue(telemetry.any { it is WebContainerTelemetryEvent.JsbCallCompleted && it.method == "echo" && it.success })
    }

    @Test
    fun bootstrapScriptsGuardOriginsAndMatchOfflineBundleContract() {
        val bridgeJs = JsbBootstrapScripts.buildPageBridgeScript("raftBridgePort", setOf(trustedOrigin))
        assertTrue(bridgeJs.contains("https://app.raft.build"))
        assertTrue(bridgeJs.contains("window.top!==window.self"))
        assertTrue(bridgeJs.contains("settleDirect(resp,resolve,reject)"))
        assertTrue(bridgeJs.contains("__dispatchResponse"))
        assertTrue(bridgeJs.contains("Object.freeze"))
        assertTrue(bridgeJs.contains("raftBridge"))
        assertTrue(bridgeJs.contains("writable:false,configurable:false"))

        val wrappedDocStart =
            JsbBootstrapScripts.wrapDocumentStartScript(
                source = "window.__boot=1;",
                allowedOrigins = setOf(trustedOrigin),
                mainFrameOnly = true,
            )
        assertTrue(wrappedDocStart.contains("window.top!==window.self"))
        assertTrue(wrappedDocStart.contains("https://app.raft.build"))
        assertTrue(wrappedDocStart.contains("window.__boot=1;"))

        val offlineJs =
            JsbBootstrapScripts.buildOfflineBundleScript(
                WebOfflineBundleDescriptor(
                    bundleId = "bundle-v1",
                    pageSessionId = "sess-42",
                    iosCustomScheme = "raft-bundle",
                    metadata = mapOf("surface" to "hands"),
                ),
                allowedOrigins = setOf(trustedOrigin),
            )
        assertTrue(offlineJs.contains("__RAFT_OFFLINE_BUNDLE__"))
        assertTrue(offlineJs.contains("raft.offlineBundle.ready"))
        assertTrue(offlineJs.contains("https://app.raft.build"))
        assertTrue(offlineJs.contains("bundle-v1"))
        assertTrue(offlineJs.contains("sess-42"))

        assertEquals(true, JsbBootstrapScripts.decodeContentProbeResult("\"1\""))
        assertEquals(false, JsbBootstrapScripts.decodeContentProbeResult("0"))
        assertNull(JsbBootstrapScripts.decodeContentProbeResult("null"))
    }

    @Test
    fun controllerDropsEphemeralCommandsWhenUnboundAndPreventsInfiniteReentry() {
        val controller = WebContainerController(maxCommandsPerDrain = 4)
        assertFalse(controller.isBound)
        assertFalse(controller.loadUrl("https://app.raft.build/a"))
        assertFalse(controller.evaluateJavaScript("1+1"))
        assertFalse(controller.probeContent())
        assertFalse(controller.emitJsbEvent("theme"))

        // Queued navigation commands replay upon bind, ephemeral commands do not.
        controller.reload()
        val received = mutableListOf<WebContainerCommand>()
        val owner = Any()
        controller.bindDispatcher(owner) { cmd ->
            received += cmd
            if (cmd is WebContainerCommand.GoBack) {
                // Re-entrant self-dispatch bounded by maxCommandsPerDrain.
                controller.goBack()
            }
        }
        assertEquals(listOf<WebContainerCommand>(WebContainerCommand.Reload), received)

        received.clear()
        controller.goBack()
        assertEquals(4, received.size)
        // Leftover re-entrant command must have been cleared (next command runs cleanly alone).
        received.clear()
        controller.stop()
        assertEquals(listOf<WebContainerCommand>(WebContainerCommand.Stop), received)
        controller.unbindDispatcher(owner)
        assertFalse(controller.isBound)
    }

    private fun parseErrorCode(rawJson: String): String {
        val root = Json.parseToJsonElement(rawJson).jsonObject
        assertFalse(root["ok"]!!.jsonPrimitive.boolean)
        return assertNotNull(root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }
}
