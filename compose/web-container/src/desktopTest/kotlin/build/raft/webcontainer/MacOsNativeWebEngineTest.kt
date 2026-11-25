@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package build.raft.webcontainer

import build.raft.webcontainer.jsb.JsbPolicy
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.jsb.JsbResult
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import java.awt.Component
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MacOsNativeWebEngineTest {
    private data class RecordedDecision(
        val sessionId: Long,
        val decisionId: Long,
        val actionCode: Int,
        val reissueUrl: String?,
        val headerName: String?,
        val headerValue: String?,
    )

    private data class RecordedEval(
        val sessionId: Long,
        val evalId: Long,
        val script: String,
        val isolatedWorld: Boolean,
    )

    private class FakeMacOsNativeBridgeOps(
        override var isAvailable: Boolean = true,
    ) : MacOsNativeWebBridgeOps {
        val handlers = LinkedHashMap<Long, MacOsNativeSessionHandler>()
        val createdParams = mutableListOf<MacOsNativeCreateParams>()
        val boundsUpdates = mutableListOf<IntArray>()
        val loadedUrls = mutableListOf<Triple<String, String?, String?>>()
        val decisions = mutableListOf<RecordedDecision>()
        val jsbReplies = mutableListOf<Pair<Long, String>>()
        val evals = mutableListOf<RecordedEval>()
        val clearedScopes = mutableListOf<String?>()
        val disposedSessions = mutableListOf<Long>()
        var createResult: Boolean = true
        var autoCompleteClear: Boolean = true
        var clearSuccessResult: Boolean = true
        val pendingClearCallbacks = mutableListOf<(Boolean) -> Unit>()
        var backCount: Int = 0
        var forwardCount: Int = 0
        var reloadCount: Int = 0
        var stopCount: Int = 0

        override fun registerSession(
            sessionId: Long,
            handler: MacOsNativeSessionHandler,
        ) {
            handlers[sessionId] = handler
        }

        override fun unregisterSession(sessionId: Long) {
            handlers.remove(sessionId)
        }

        override fun createSession(params: MacOsNativeCreateParams): Boolean {
            createdParams += params
            return createResult
        }

        override fun updateBounds(
            sessionId: Long,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
            visible: Boolean,
        ) {
            boundsUpdates += intArrayOf(x, y, w, h, if (visible) 1 else 0)
        }

        override fun loadUrl(
            sessionId: Long,
            url: String,
            headerName: String?,
            headerValue: String?,
        ) {
            loadedUrls += Triple(url, headerName, headerValue)
        }

        override fun goBack(sessionId: Long) {
            backCount += 1
        }

        override fun goForward(sessionId: Long) {
            forwardCount += 1
        }

        override fun reload(sessionId: Long) {
            reloadCount += 1
        }

        override fun stopLoading(sessionId: Long) {
            stopCount += 1
        }

        override fun evaluateJavaScript(
            sessionId: Long,
            evalId: Long,
            script: String,
            isolatedWorld: Boolean,
        ) {
            evals += RecordedEval(sessionId, evalId, script, isolatedWorld)
        }

        override fun completeNavigationDecision(
            sessionId: Long,
            decisionId: Long,
            actionCode: Int,
            reissueUrl: String?,
            headerName: String?,
            headerValue: String?,
        ) {
            decisions += RecordedDecision(sessionId, decisionId, actionCode, reissueUrl, headerName, headerValue)
        }

        override fun completeJsbReply(
            sessionId: Long,
            replyId: Long,
            replyJson: String,
        ) {
            jsbReplies += replyId to replyJson
        }

        override fun clearIsolationScope(
            namedScopeUuid: String?,
            onComplete: ((Boolean) -> Unit)?,
        ): () -> Unit {
            clearedScopes += namedScopeUuid
            if (onComplete == null) return {}
            if (autoCompleteClear) {
                onComplete(clearSuccessResult)
                return {}
            }
            pendingClearCallbacks += onComplete
            return { pendingClearCallbacks.remove(onComplete) }
        }

        override fun disposeSession(sessionId: Long) {
            disposedSessions += sessionId
        }
    }

    private fun createEngine(
        ops: FakeMacOsNativeBridgeOps,
        isMacOs: Boolean = true,
        scheduleRetry: (Int, () -> Unit) -> (() -> Unit) = { _, _ -> {} },
    ): MacOsNativeWebEngine =
        MacOsNativeWebEngine(
            bridgeOps = ops,
            isMacOsHost = { isMacOs },
            postToEdt = { it() },
            canvasFactory = { JPanel() as Component },
            scheduleRetry = scheduleRetry,
        )

    @Test
    fun unavailableOutsideMacOsOrWhenNativeLibraryMissing() = runTest {
        val unavailableOps = FakeMacOsNativeBridgeOps(isAvailable = false)
        val missingLibEngine = createEngine(unavailableOps, isMacOs = true)
        assertFalse(missingLibEngine.isAvailable)
        assertNull(
            DesktopWebContainerHostController(
                config = WebContainerConfig(url = "https://app.raft.build"),
                engine = missingLibEngine,
                events = WebContainerEvents(),
                scope = this,
            ).session,
        )
        assertFalse(missingLibEngine.clearIsolationScope(WebIsolationScope.Default))

        val linuxEngine = createEngine(FakeMacOsNativeBridgeOps(isAvailable = true), isMacOs = false)
        assertFalse(linuxEngine.isAvailable)
        assertNull(
            DesktopWebContainerHostController(
                config = WebContainerConfig(url = "https://app.raft.build"),
                engine = linuxEngine,
                events = WebContainerEvents(),
                scope = this,
            ).session,
        )
    }

    @Test
    fun supportsScriptsDisabledNativelyAndMapsIsolationScopesDeterministically() = runTest {
        val ops = FakeMacOsNativeBridgeOps()
        val engine = createEngine(ops)
        val controller =
            DesktopWebContainerHostController(
                config =
                    WebContainerConfig(
                        url = "https://files.raft.build/preview.html",
                        allowScripts = false,
                        cacheEnabled = false,
                        isolationScope = WebIsolationScope.Named("raft_third_party"),
                        allowsBackForwardNavigationGestures = true,
                    ),
                engine = engine,
                events = WebContainerEvents(),
                scope = this,
            )
        val session = assertNotNull(controller.session as? MacOsNativeWebSession)
        controller.start()
        assertTrue(session.triggerNativeAttachForTest(10, 20, 640, 480))

        val params = ops.createdParams.single()
        assertFalse(params.allowScripts)
        assertFalse(params.cacheEnabled)
        assertFalse(params.ephemeral)
        assertEquals(deriveDeterministicScopeUuid("raft_third_party"), params.namedScopeUuid)
        assertNull(params.jsbPortName)
        assertEquals("[]", params.userScriptsJson)
        assertTrue(params.allowsBackForwardGestures)
        assertEquals("https://files.raft.build/preview.html", params.initialUrl)

        assertTrue(engine.clearIsolationScope(WebIsolationScope.Default))
        assertTrue(engine.clearIsolationScope(WebIsolationScope.Ephemeral))
        assertTrue(engine.clearIsolationScope(WebIsolationScope.Named("raft_third_party")))
        assertEquals(listOf(null, deriveDeterministicScopeUuid("raft_third_party")), ops.clearedScopes)

        controller.dispose()
        assertEquals(listOf(session.sessionId), ops.disposedSessions)
        assertTrue(ops.handlers.isEmpty())
    }

    @Test
    fun asyncNavigationDecisionsSupportHeaderReissueCrossOriginStripAndSubframeFencing() = runTest {
        val ops = FakeMacOsNativeBridgeOps()
        val engine = createEngine(ops)
        val origin = WebContainerOrigin("https", "app.raft.build")
        val blocked = mutableListOf<Pair<String, String>>()
        val headerRule =
            WebRequestHeaderRule(
                headerName = "Raft-Host",
                headerValue = "desktop-1.30",
                allowedOrigin = origin,
                maxSameOriginReissues = 2,
            )
        val controller =
            DesktopWebContainerHostController(
                config =
                    WebContainerConfig(
                        url = "https://app.raft.build/settings",
                        allowScripts = true,
                        isolationScope = WebIsolationScope.Default,
                        navigationPolicy =
                            WebNavigationPolicy(
                                allowNavigation = true,
                                allowedSchemes = setOf("https"),
                                allowedSubframeHosts = setOf("artifacts.raft.build"),
                                requestHeaderRule = headerRule,
                            ),
                    ),
                engine = engine,
                events = WebContainerEvents(onNavigationBlocked = { u, r -> blocked += u to r }),
                scope = this,
            )
        val session = assertNotNull(controller.session as? MacOsNativeWebSession)
        controller.start()
        assertTrue(session.triggerNativeAttachForTest())

        // Initial launch attaches origin-scoped header automatically.
        val created = ops.createdParams.single()
        assertTrue(created.attachInitialHeader)
        assertEquals("Raft-Host", created.headerName)
        assertEquals("desktop-1.30", created.headerValue)

        val handler = assertNotNull(ops.handlers[session.sessionId])

        // 1. Same-origin link without header -> ReissueWithHeader (actionCode = 2).
        handler.onNavigationDecisionRequest(
            decisionId = 1L,
            url = "https://app.raft.build/account",
            isMainFrame = true,
            method = "GET",
            carriesScopedHeader = false,
            isUserGesture = true,
            starterCode = 1,
        )
        assertEquals(MacOsNativeWebSession.ACTION_REISSUE_WITH_HEADER, ops.decisions.last().actionCode)
        assertEquals("Raft-Host", ops.decisions.last().headerName)
        assertEquals("desktop-1.30", ops.decisions.last().headerValue)

        // 2. Cross-origin redirect while carrying header -> ReissueWithoutHeader (actionCode = 3, null header).
        handler.onNavigationDecisionRequest(
            decisionId = 2L,
            url = "https://other.example.com/auth",
            isMainFrame = true,
            method = "GET",
            carriesScopedHeader = true,
            isUserGesture = false,
            starterCode = 0,
        )
        assertEquals(MacOsNativeWebSession.ACTION_REISSUE_WITHOUT_HEADER, ops.decisions.last().actionCode)
        assertEquals("https://other.example.com/auth", ops.decisions.last().reissueUrl)
        assertNull(ops.decisions.last().headerName)
        assertNull(ops.decisions.last().headerValue)

        // 3. Disallowed scheme -> Cancel (actionCode = 0) + onNavigationBlocked.
        handler.onNavigationDecisionRequest(
            decisionId = 3L,
            url = "javascript:alert(1)",
            isMainFrame = true,
            method = "GET",
            carriesScopedHeader = false,
            isUserGesture = false,
            starterCode = 0,
        )
        assertEquals(MacOsNativeWebSession.ACTION_CANCEL, ops.decisions.last().actionCode)
        assertTrue(blocked.any { it.first == "javascript:alert(1)" })

        // 4. Declared subframe passes (Allow = 1); undeclared subframe cancels (Cancel = 0).
        handler.onNavigationDecisionRequest(
            decisionId = 4L,
            url = "https://content.artifacts.raft.build/embed.html",
            isMainFrame = false,
            method = "GET",
            carriesScopedHeader = false,
            isUserGesture = false,
            starterCode = 0,
        )
        assertEquals(MacOsNativeWebSession.ACTION_ALLOW, ops.decisions.last().actionCode)

        handler.onNavigationDecisionRequest(
            decisionId = 5L,
            url = "https://evil.example.org/frame",
            isMainFrame = false,
            method = "GET",
            carriesScopedHeader = false,
            isUserGesture = false,
            starterCode = 0,
        )
        assertEquals(MacOsNativeWebSession.ACTION_CANCEL, ops.decisions.last().actionCode)

        controller.dispose()
    }

    @Test
    fun verifiedJsbReplyIsolatedWorldProbeAndCrashRecoveryWorkEndToEnd() = runTest {
        val ops = FakeMacOsNativeBridgeOps()
        val engine = createEngine(ops)
        val origin = WebContainerOrigin("https", "app.raft.build")
        val registry =
            JsbRegistry().register("app.ping", setOf("core.ping")) { ctx, _ ->
                JsbResult.Success(buildJsonObject { put("surface", ctx.surfaceId) })
            }
        val probes = mutableListOf<Boolean?>()
        val observedUrls = mutableListOf<String>()
        val telemetry = mutableListOf<WebContainerTelemetryEvent>()
        var loadSuccessCount = 0
        var commitCount = 0
        val webController = WebContainerController()
        val host =
            DesktopWebContainerHostController(
                config =
                    WebContainerConfig(
                        url = "https://app.raft.build/a/1",
                        surfaceId = "artifact_viewer",
                        allowScripts = true,
                        backgroundArgb = 0xFF101214.toInt(),
                        isDarkTheme = true,
                        navigationPolicy =
                            WebNavigationPolicy(
                                allowNavigation = false,
                                allowedSchemes = setOf("https"),
                                lockedMainFrameOrigin = origin,
                            ),
                        jsbPolicy =
                            JsbPolicy(
                                enabled = true,
                                allowedOrigins = setOf(origin),
                                grantedCapabilities = setOf("core.ping"),
                            ),
                    ),
                engine = engine,
                events =
                    WebContainerEvents(
                        onUrlChange = { observedUrls += it },
                        onLoadCommit = { commitCount += 1 },
                        onLoadSuccess = { loadSuccessCount += 1 },
                        onContentProbe = { probes += it },
                    ),
                jsbRegistry = registry,
                scope = this,
                telemetrySink = WebContainerTelemetrySink { telemetry += it },
            )
        host.bind(webController)
        val session = assertNotNull(host.session as? MacOsNativeWebSession)
        host.start()
        assertTrue(session.triggerNativeAttachForTest())

        // Document-start scripts serialized as JSON array containing window.raftBridge + first-paint theme.
        val scriptsArray = Json.parseToJsonElement(ops.createdParams.single().userScriptsJson) as JsonArray
        assertTrue(scriptsArray.size >= 2)
        assertTrue(
            scriptsArray.any {
                val obj = it as JsonObject
                obj["mainFrameOnly"]?.jsonPrimitive?.boolean == true &&
                    obj["source"]?.jsonPrimitive?.contentOrNull?.contains("window.raftBridge") == true
            },
        )

        val handler = assertNotNull(ops.handlers[session.sessionId])
        // Blank provisional URL must not clobber the active URL or emit blank onUrlChange.
        handler.onLoadStarted("")
        assertFalse(observedUrls.contains(""))
        handler.onLoadStarted("https://app.raft.build/a/1")
        handler.onLoadCommit("https://app.raft.build/a/1")
        handler.onLoadFinished("https://app.raft.build/a/1")
        assertEquals(1, commitCount)
        assertEquals(1, loadSuccessCount)

        // KVO URL updates (SPA pushState) emit onUrlChange.
        handler.onStateChanged(
            url = "https://app.raft.build/a/1#section-2",
            title = "Artifact",
            canGoBack = false,
            canGoForward = false,
            isLoading = false,
            progress = 100,
        )
        assertTrue(observedUrls.contains("https://app.raft.build/a/1#section-2"))

        // 1. Valid JSB call on matching frameOrigin + documentUrl succeeds.
        val req = """{"v":1,"id":"c1","method":"app.ping","params":{}}"""
        handler.onJsbMessage(
            replyId = 10L,
            rawPayload = req,
            frameOriginUrl = "https://app.raft.build",
            documentUrl = "https://app.raft.build/a/1",
            isMainFrame = true,
        )
        advanceUntilIdle()
        val okReply = ops.jsbReplies.single { it.first == 10L }.second
        assertTrue(okReply.contains("\"ok\":true"))
        assertTrue(okReply.contains("artifact_viewer"))

        // 2. Opaque / mismatched frameOrigin fails closed (ORIGIN_DENIED).
        handler.onJsbMessage(
            replyId = 11L,
            rawPayload = req,
            frameOriginUrl = "",
            documentUrl = "https://app.raft.build/a/1",
            isMainFrame = true,
        )
        advanceUntilIdle()
        assertTrue(ops.jsbReplies.single { it.first == 11L }.second.contains("ORIGIN_DENIED"))

        // 3. Fenced allowNavigation=false pushState drift fails closed.
        handler.onJsbMessage(
            replyId = 12L,
            rawPayload = req,
            frameOriginUrl = "https://app.raft.build",
            documentUrl = "https://app.raft.build/other-page",
            isMainFrame = true,
        )
        advanceUntilIdle()
        assertTrue(ops.jsbReplies.single { it.first == 12L }.second.contains("ORIGIN_DENIED"))

        // 4. Content probe executes strictly inside isolated defaultClientWorld.
        assertTrue(webController.probeContent())
        val probeEval = ops.evals.last()
        assertTrue(probeEval.isolatedWorld)
        handler.onEvalResult(probeEval.evalId, "1")
        assertEquals(listOf<Boolean?>(true), probes)

        // 5. Pending eval is cancelled with null when disposed.
        var cancelledEvalResult: String? = "sentinel"
        session.evaluateJavaScript("1+1", isolatedWorld = false) { cancelledEvalResult = it }
        val pendingEval = ops.evals.last()
        assertFalse(pendingEval.isolatedWorld)

        // 6. Content process crash after pushState to another same-origin path while allowNavigation=false
        //    must NOT restore the pushed path; it must fall back to the initial fenced URL.
        handler.onStateChanged(
            url = "https://app.raft.build/other-secret",
            title = "Drifted",
            canGoBack = false,
            canGoForward = false,
            isLoading = false,
            progress = 100,
        )
        handler.onProcessTerminated(didCrash = true)
        assertEquals("https://app.raft.build/a/1", ops.loadedUrls.last().first)
        assertTrue(ops.loadedUrls.none { it.first == "https://app.raft.build/other-secret" })
        assertTrue(telemetry.any { it is WebContainerTelemetryEvent.RenderProcessGone && it.recovered })

        // 7. Complete the recovery load so the watchdog returns to Ready, then verify same-document
        //    fragment change under allowNavigation=false DOES restore cleanly on a subsequent crash.
        handler.onLoadStarted("https://app.raft.build/a/1")
        handler.onLoadCommit("https://app.raft.build/a/1")
        handler.onLoadFinished("https://app.raft.build/a/1")
        handler.onStateChanged(
            url = "https://app.raft.build/a/1#section-3",
            title = "Artifact",
            canGoBack = false,
            canGoForward = false,
            isLoading = false,
            progress = 100,
        )
        handler.onProcessTerminated(didCrash = true)
        assertEquals("https://app.raft.build/a/1#section-3", ops.loadedUrls.last().first)

        host.dispose()
        assertNull(cancelledEvalResult)
    }

    @Test
    fun nativeCreateFailureReportsLoadFailureAfterBoundedActiveRetriesAndCancelsPendingEvals() = runTest {
        val ops = FakeMacOsNativeBridgeOps().apply { createResult = false }
        val scheduledRetries = ArrayDeque<() -> Unit>()
        var cancelledRetries = 0
        val engine =
            createEngine(
                ops = ops,
                scheduleRetry = { _, action ->
                    scheduledRetries.addLast(action)
                    {
                        if (scheduledRetries.remove(action)) {
                            cancelledRetries += 1
                        }
                    }
                },
            )
        val failures = mutableListOf<String>()
        val host =
            DesktopWebContainerHostController(
                config = WebContainerConfig(url = "https://app.raft.build/a/1"),
                engine = engine,
                events = WebContainerEvents(onLoadFailure = { failures += it }),
                scope = this,
            )
        val session = assertNotNull(host.session as? MacOsNativeWebSession)
        host.start()

        var evalResult: String? = "sentinel"
        session.evaluateJavaScript("1+1") { evalResult = it }

        // First attach failure automatically schedules an active retry (no extra Swing resize needed).
        assertFalse(session.triggerNativeAttachForTest())
        assertEquals(1, scheduledRetries.size)
        assertTrue(failures.isEmpty())

        // Drain scheduled retries until MAX_NATIVE_CREATE_ATTEMPTS is reached.
        repeat(MacOsNativeWebSession.MAX_NATIVE_CREATE_ATTEMPTS - 2) {
            scheduledRetries.removeFirst().invoke()
            assertTrue(failures.isEmpty(), "must not emit terminal failure before retry limit")
            assertEquals(1, scheduledRetries.size)
        }
        scheduledRetries.removeFirst().invoke()
        assertEquals(listOf(MacOsNativeWebSession.NATIVE_CREATE_FAILED_REASON), failures)
        assertTrue(scheduledRetries.isEmpty(), "no retry scheduled after terminal failure")
        assertNull(evalResult)

        // Further attach attempts are no-ops and do not re-emit onLoadFailure; new evals immediately return null.
        assertFalse(session.triggerNativeAttachForTest())
        assertEquals(1, failures.size)
        var afterFailureEval: String? = "sentinel"
        session.evaluateJavaScript("2+2") { afterFailureEval = it }
        assertNull(afterFailureEval)
        host.dispose()

        // Disposing mid-retry cancels the pending timer cleanly.
        val host2 =
            DesktopWebContainerHostController(
                config = WebContainerConfig(url = "https://app.raft.build/a/2"),
                engine = engine,
                events = WebContainerEvents(),
                scope = this,
            )
        val session2 = assertNotNull(host2.session as? MacOsNativeWebSession)
        host2.start()
        assertFalse(session2.triggerNativeAttachForTest())
        assertEquals(1, scheduledRetries.size)
        host2.dispose()
        assertTrue(scheduledRetries.isEmpty())
        assertEquals(1, cancelledRetries)
    }

    @Test
    fun clearIsolationScopeAndAwaitCompletesOnCallbackCleansUpOnTimeoutAndPropagatesFailure() = runTest {
        val ops = FakeMacOsNativeBridgeOps()
        val engine = createEngine(ops)

        assertTrue(engine.clearIsolationScopeAndAwait(WebIsolationScope.Default, timeoutMs = 500L))
        assertTrue(engine.clearIsolationScopeAndAwait(WebIsolationScope.Ephemeral, timeoutMs = 500L))
        assertTrue(engine.clearIsolationScopeAndAwait(WebIsolationScope.Named("scope-a"), timeoutMs = 500L))
        assertEquals(listOf(null, deriveDeterministicScopeUuid("scope-a")), ops.clearedScopes)

        // Failed clear callback returns false (never false-positive true).
        ops.clearSuccessResult = false
        assertFalse(engine.clearIsolationScopeAndAwait(WebIsolationScope.Default, timeoutMs = 500L))
        ops.clearSuccessResult = true

        // When WebKit never invokes completionHandler, bounded timeout returns false AND removes pending callback.
        ops.autoCompleteClear = false
        assertFalse(engine.clearIsolationScopeAndAwait(WebIsolationScope.Default, timeoutMs = 50L))
        assertTrue(ops.pendingClearCallbacks.isEmpty(), "timed-out clear must unregister its pending callback")

        // Unavailable engine returns false immediately.
        val unavailable = createEngine(FakeMacOsNativeBridgeOps(isAvailable = false))
        assertFalse(unavailable.clearIsolationScopeAndAwait(WebIsolationScope.Default, timeoutMs = 500L))
    }

    @Test
    fun archNormalizationAndMacOsRuntimeLibraryContract() {
        assertEquals("arm64", MacOsNativeWebBridge.normalizeMacOsArch("aarch64"))
        assertEquals("arm64", MacOsNativeWebBridge.normalizeMacOsArch("arm64"))
        assertEquals("x86_64", MacOsNativeWebBridge.normalizeMacOsArch("x86_64"))
        assertEquals("x86_64", MacOsNativeWebBridge.normalizeMacOsArch("amd64"))

        val isMacOs =
            System.getProperty("os.name").orEmpty().lowercase().let { it.contains("mac") || it.contains("darwin") }
        if (isMacOs) {
            assertTrue(
                MacOsNativeWebBridge.isLoaded,
                "libraft_webcontainer_macos.dylib must compile, sign, load, and pass JNI_OnLoad on macOS",
            )
            assertTrue(MacOsNativeWebEngine.Default.isAvailable)
        } else {
            assertFalse(MacOsNativeWebBridge.isLoaded)
            assertFalse(MacOsNativeWebEngine.Default.isAvailable)
        }
    }
}
