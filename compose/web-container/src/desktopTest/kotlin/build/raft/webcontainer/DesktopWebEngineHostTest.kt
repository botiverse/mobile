@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package build.raft.webcontainer

import build.raft.webcontainer.jsb.JsbPolicy
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.jsb.JsbResult
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.Component
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWebEngineHostTest {
    private class FakeDesktopWebSession(
        override val engineName: String = "macos_wkwebview",
    ) : DesktopWebSession {
        override val component: Component = JPanel()
        var started = false
        var disposed = false
        var lastVisible: Boolean? = null
        val loadedUrls = mutableListOf<String>()
        val evaluatedScripts = mutableListOf<String>()
        var nextProbeResult: String? = "1"

        override fun start() {
            started = true
        }

        override fun setVisible(visible: Boolean) {
            lastVisible = visible
        }

        override fun loadUrl(url: String) {
            loadedUrls += url
        }

        override fun goBack() = Unit

        override fun goForward() = Unit

        override fun reload() = Unit

        override fun stopLoading() = Unit

        override fun evaluateJavaScript(
            script: String,
            callback: ((String?) -> Unit)?,
        ) {
            evaluatedScripts += script
            callback?.invoke(nextProbeResult)
        }

        override fun dispose() {
            disposed = true
        }
    }

    @Test
    fun desktopHostControllerDrivesLifecycleVisibilityNavigationFenceAndJsb() = runTest {
        val origin = parseWebContainerOrigin("https://app.raft.build")!!
        val fakeSession = FakeDesktopWebSession()
        var capturedCallbacks: DesktopWebSessionCallbacks? = null
        val engine =
            DesktopWebEngine { _, callbacks ->
                capturedCallbacks = callbacks
                fakeSession
            }

        val blocked = mutableListOf<Pair<String, String>>()
        val navStates = mutableListOf<WebNavigationState>()
        var readyFlag = false

        val registry =
            JsbRegistry().register("desktop.ping", requiredCapabilities = setOf("ping")) { _, params ->
                JsbResult.Success(buildJsonObject { put("pong", params["v"]?.jsonPrimitive?.content.orEmpty()) })
            }
        val config =
            WebContainerConfig(
                url = "https://app.raft.build/workspace",
                surfaceId = "desktop_test",
                coverActive = true,
                navigationPolicy =
                    WebNavigationPolicy(
                        allowNavigation = true,
                        allowedSchemes = setOf("https"),
                        lockedMainFrameOrigin = origin,
                    ),
                jsbPolicy =
                    JsbPolicy(
                        enabled = true,
                        allowedOrigins = setOf(origin),
                        grantedCapabilities = setOf("ping"),
                    ),
            )
        val telemetry = mutableListOf<WebContainerTelemetryEvent>()
        var now = 1_000L
        val host =
            DesktopWebContainerHostController(
                config = config,
                engine = engine,
                events =
                    WebContainerEvents(
                        onNavigationStateChange = { navStates += it },
                        onNavigationBlocked = { u, r -> blocked += (u to r) },
                    ),
                jsbRegistry = registry,
                scope = this,
                telemetrySink = WebContainerTelemetrySink { telemetry += it },
                nowMs = { now },
                onReadyChanged = { readyFlag = it },
            )
        val controller = WebContainerController()
        host.bind(controller)
        host.start()
        assertTrue(fakeSession.started)

        val cb = requireNotNull(capturedCallbacks)
        // Cross-origin navigation blocked by lockedMainFrameOrigin.
        assertFalse(cb.onNavigationRequested("https://evil.example/phish", isMainFrame = true))
        assertEquals("origin_locked", blocked.single().second)
        assertTrue(cb.onNavigationRequested("https://app.raft.build/doc", isMainFrame = true))

        // While coverActive=true, even after page commits, native session stays hidden.
        cb.onLoadStarted("https://app.raft.build/workspace")
        cb.onLoadCommit("https://app.raft.build/workspace")
        assertTrue(readyFlag)
        assertEquals(null, fakeSession.lastVisible)

        // Lifting coverActive reveals the native session.
        host.setDisplayAllowed(true)
        assertEquals(true, fakeSession.lastVisible)
        host.setDisplayAllowed(false)
        assertEquals(false, fakeSession.lastVisible)

        // JSB round-trip through Desktop callbacks.
        var jsbReply = ""
        cb.onJsbMessage(
            rawPayload = """{"v":1,"id":"d1","method":"desktop.ping","params":{"v":"ok"}}""",
            originUrl = "https://app.raft.build/workspace",
            isMainFrame = true,
        ) { jsbReply = it }
        runCurrent()
        val parsed = Json.parseToJsonElement(jsbReply).jsonObject
        assertTrue(parsed["ok"]!!.jsonPrimitive.boolean)
        assertEquals("ok", parsed["data"]!!.jsonObject["pong"]!!.jsonPrimitive.content)

        // Process termination recovers cleanly via sanitized restore URL and tags telemetry with engineName.
        cb.onLoadFinished("https://app.raft.build/workspace?token=secret#frag")
        now += 2_000L
        host.tickWatchdog()
        cb.onProcessTerminated(didCrash = true)
        assertEquals(listOf("https://app.raft.build/workspace"), fakeSession.loadedUrls)
        val goneEvent = telemetry.filterIsInstance<WebContainerTelemetryEvent.RenderProcessGone>().single()
        assertEquals("desktop_test", goneEvent.surfaceId)
        assertEquals("macos_wkwebview", goneEvent.engine)
        assertTrue(goneEvent.didCrash)
        assertTrue(goneEvent.recovered)
        assertFalse(goneEvent.parked)

        // Native create exhaustion emits StartupFailed telemetry with engine attribution.
        cb.onLoadFailure(MacOsNativeWebSession.NATIVE_CREATE_FAILED_REASON)
        val startupFail = telemetry.filterIsInstance<WebContainerTelemetryEvent.StartupFailed>().single()
        assertEquals(MacOsNativeWebSession.NATIVE_CREATE_FAILED_REASON, startupFail.reason)
        assertEquals("macos_wkwebview", startupFail.engine)

        host.dispose()
        assertTrue(fakeSession.disposed)
        assertFalse(controller.isBound)
    }

    @Test
    fun desktopHostControllerEmitsStartupFailedTelemetryWhenEngineUnavailable() = runTest {
        val telemetry = mutableListOf<WebContainerTelemetryEvent>()
        val failures = mutableListOf<String>()
        val host =
            DesktopWebContainerHostController(
                config = WebContainerConfig(url = "https://app.raft.build/workspace", surfaceId = "desktop_test"),
                engine = DesktopWebEngine { _, _ -> null },
                events = WebContainerEvents(onLoadFailure = { failures += it }),
                scope = this,
                telemetrySink = WebContainerTelemetrySink { telemetry += it },
            )
        host.start()
        assertEquals(listOf("desktop_engine_unavailable"), failures)
        val startupFail = telemetry.filterIsInstance<WebContainerTelemetryEvent.StartupFailed>().single()
        assertEquals("desktop_engine_unavailable", startupFail.reason)
        assertEquals("desktop_web", startupFail.engine)
    }
}
