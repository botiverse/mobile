@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package build.raft.app.desktop

import build.raft.app.RaftDesktopWebContainerBridgeFactory
import build.raft.app.RaftDesktopWebContainerEngine
import build.raft.app.RaftDesktopWryBridge
import build.raft.app.RaftDesktopWryPollSnapshot
import build.raft.app.RaftDesktopWryWebSession
import build.raft.app.resolveDesktopWebContainerStorage
import build.raft.webcontainer.DesktopWebContainerHostController
import build.raft.webcontainer.WebContainerConfig
import build.raft.webcontainer.WebContainerEvents
import build.raft.webcontainer.WebContainerOrigin
import build.raft.webcontainer.WebIsolationScope
import build.raft.webcontainer.WebNavigationPolicy
import build.raft.webcontainer.jsb.JsbPolicy
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.jsb.JsbResult
import java.awt.Component
import java.io.File
import java.nio.file.Files
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RaftDesktopWebContainerEngineTest {
    private class FakeDesktopWryBridge : RaftDesktopWryBridge {
        override val component: Component = JPanel()
        var ready: Boolean = true
        var disposed: Boolean = false
        var revealed: Boolean = false
        var navigateListener: ((String) -> Boolean)? = null
        var nextHref: String = "\"https://app.raft.build/a/1\""
        var deferHrefCallback: Boolean = false
        var pendingHrefCallback: ((String) -> Unit)? = null
        val loadedUrls = mutableListOf<String>()
        val evaluatedScripts = mutableListOf<String>()
        private val pendingSnapshots = ArrayDeque<RaftDesktopWryPollSnapshot>()

        fun enqueueSnapshot(snapshot: RaftDesktopWryPollSnapshot) {
            pendingSnapshots.addLast(snapshot)
        }

        override fun isReady(): Boolean = !disposed && ready

        override fun addNavigateListener(listener: (String) -> Boolean) {
            navigateListener = listener
        }

        override fun reveal() {
            revealed = true
        }

        override fun hide() {
            revealed = false
        }

        override fun poll(onResult: (RaftDesktopWryPollSnapshot) -> Unit): Boolean {
            if (!isReady()) return false
            val snapshot =
                pendingSnapshots.removeFirstOrNull()
                    ?: RaftDesktopWryPollSnapshot(
                        ipcMessages = emptyList(),
                        isLoading = false,
                        title = "Raft",
                        canGoBack = false,
                        canGoForward = false,
                    )
            onResult(snapshot)
            return true
        }

        override fun evaluateJavaScript(script: String, onResult: (String) -> Unit) {
            evaluatedScripts += script
            if (script.contains("window.location.href")) {
                if (deferHrefCallback) {
                    pendingHrefCallback = onResult
                } else {
                    onResult(nextHref)
                }
            } else {
                onResult("")
            }
        }

        override fun loadUrl(url: String) {
            loadedUrls += url
        }

        override fun goBackIfPossible(onCompleted: () -> Unit) = onCompleted()

        override fun goForwardIfPossible(onCompleted: () -> Unit) = onCompleted()

        override fun reload(onCompleted: () -> Unit) = onCompleted()

        override fun stopLoading(onCompleted: () -> Unit) = onCompleted()

        override fun dispose() {
            disposed = true
        }
    }

    @Test
    fun failsClosedWhenScriptsDisabledOrOnWindows() = runTest {
        val fakeBridge = FakeDesktopWryBridge()
        val factory = RaftDesktopWebContainerBridgeFactory { _, _, _, _ -> fakeBridge }
        val tempDir = Files.createTempDirectory("raft-wc-desktop-test").toFile()
        try {
            val noScriptsEngine =
                RaftDesktopWebContainerEngine(
                    bridgeFactory = factory,
                    isWindowsHost = { false },
                    isMacOsHost = { true },
                    baseDataDir = { tempDir },
                    postToEdt = { it() },
                    autoStartPollTimer = false,
                )
            val noScriptsController =
                DesktopWebContainerHostController(
                    config = WebContainerConfig(url = "https://app.raft.build", allowScripts = false),
                    engine = noScriptsEngine,
                    events = WebContainerEvents(),
                    scope = this,
                )
            assertNull(noScriptsController.session)

            val windowsEngine =
                RaftDesktopWebContainerEngine(
                    bridgeFactory = factory,
                    isWindowsHost = { true },
                    isMacOsHost = { false },
                    baseDataDir = { tempDir },
                    postToEdt = { it() },
                    autoStartPollTimer = false,
                )
            val windowsController =
                DesktopWebContainerHostController(
                    config = WebContainerConfig(url = "https://app.raft.build", allowScripts = true),
                    engine = windowsEngine,
                    events = WebContainerEvents(),
                    scope = this,
                )
            assertNull(windowsController.session)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun storageResolutionFailsClosedToEphemeralOnMacOsAndUsesScopedDirOnLinux() {
        val tempDir = Files.createTempDirectory("raft-wc-storage-test").toFile()
        try {
            val (defaultEph, defaultDir) =
                resolveDesktopWebContainerStorage(WebIsolationScope.Default, tempDir, isMacOs = true)
            assertFalse(defaultEph)
            assertEquals(File(tempDir, "webview").path, defaultDir)

            val (ephFlag, ephDir) =
                resolveDesktopWebContainerStorage(WebIsolationScope.Ephemeral, tempDir, isMacOs = true)
            assertTrue(ephFlag)
            assertNull(ephDir)

            // macOS Wry ignores dataDirectory when incognito=false, so Named scopes must fail closed to ephemeral.
            val (macNamedEph, macNamedDir) =
                resolveDesktopWebContainerStorage(
                    WebIsolationScope.Named("raft_third_party"),
                    tempDir,
                    isMacOs = true,
                )
            assertTrue(macNamedEph)
            assertNull(macNamedDir)

            // Non-macOS (Linux WebKitGTK) isolates Named scopes in separate data directories.
            val (linuxNamedEph, linuxNamedDir) =
                resolveDesktopWebContainerStorage(
                    WebIsolationScope.Named("raft_third_party"),
                    tempDir,
                    isMacOs = false,
                )
            assertFalse(linuxNamedEph)
            assertEquals(File(File(tempDir, "webview-scopes"), "raft_third_party").path, linuxNamedDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun emptySubframeHostsDoesNotBypassOriginLockAndDispatchesBlockedOnEdt() = runTest {
        val fakeBridge = FakeDesktopWryBridge()
        val edtQueue = ArrayDeque<() -> Unit>()
        val tempDir = Files.createTempDirectory("raft-wc-empty-subframe-test").toFile()
        try {
            val engine =
                RaftDesktopWebContainerEngine(
                    bridgeFactory = RaftDesktopWebContainerBridgeFactory { _, _, _, _ -> fakeBridge },
                    isWindowsHost = { false },
                    isMacOsHost = { true },
                    baseDataDir = { tempDir },
                    postToEdt = { edtQueue.addLast(it) },
                    autoStartPollTimer = false,
                )
            val origin = WebContainerOrigin("https", "app.raft.build")
            val blocked = mutableListOf<Pair<String, String>>()
            val routed = mutableListOf<String>()
            val controller =
                DesktopWebContainerHostController(
                    config =
                        WebContainerConfig(
                            url = "https://app.raft.build/settings",
                            allowScripts = true,
                            navigationPolicy =
                                WebNavigationPolicy(
                                    allowNavigation = true,
                                    allowedSchemes = setOf("https"),
                                    lockedMainFrameOrigin = origin,
                                    allowedSubframeHosts = emptySet(),
                                ),
                        ),
                    engine = engine,
                    events =
                        WebContainerEvents(
                            onNavigationBlocked = { url, reason -> blocked += url to reason },
                            routesMainFrameNavigation = { candidate ->
                                routed += candidate
                                candidate.endsWith("/external")
                            },
                        ),
                    scope = this,
                )
            val session = assertNotNull(controller.session as? RaftDesktopWryWebSession)
            controller.start()

            // Foreign origin must be blocked synchronously even when allowedSubframeHosts is empty,
            // and the UI callback must be posted to EDT (not fired synchronously on native thread).
            assertFalse(fakeBridge.navigateListener!!.invoke("https://evil.example/steal"))
            assertTrue(blocked.isEmpty(), "blocked callback must not run synchronously on Wry native thread")
            while (edtQueue.isNotEmpty()) edtQueue.removeFirst().invoke()
            assertEquals(listOf("https://evil.example/steal" to "origin_locked"), blocked)

            // Same-origin route check fires safely on EDT during top-level href reconciliation.
            fakeBridge.nextHref = "\"https://app.raft.build/external\""
            session.pollOnce()
            assertTrue(routed.contains("https://app.raft.build/external"))
            assertTrue(blocked.any { it.first == "https://app.raft.build/external" && it.second == "routed_externally" })
            assertTrue(fakeBridge.loadedUrls.isNotEmpty(), "routed URL must be restored back to last trusted page")

            controller.dispose()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun authenticatedJsbRoundTripSubframeFencingDeferredFinishAndSpaUrlChange() = runTest {
        val fakeBridge = FakeDesktopWryBridge()
        var capturedInitScript = ""
        val tempDir = Files.createTempDirectory("raft-wc-jsb-test").toFile()
        try {
            val engine =
                RaftDesktopWebContainerEngine(
                    bridgeFactory =
                        RaftDesktopWebContainerBridgeFactory { _, initScript, _, _ ->
                            capturedInitScript = initScript
                            fakeBridge
                        },
                    isWindowsHost = { false },
                    isMacOsHost = { true },
                    baseDataDir = { tempDir },
                    postToEdt = { it() },
                    autoStartPollTimer = false,
                )
            val origin = WebContainerOrigin("https", "app.raft.build")
            val registry =
                JsbRegistry().register("app.ping", setOf("core.ping")) { _, _ ->
                    JsbResult.Success(buildJsonObject { put("pong", true) })
                }
            val blocked = mutableListOf<Pair<String, String>>()
            val observedUrls = mutableListOf<String>()
            var loadSuccessCount = 0
            val controller =
                DesktopWebContainerHostController(
                    config =
                        WebContainerConfig(
                            url = "https://app.raft.build/a/1",
                            allowScripts = true,
                            isolationScope = WebIsolationScope.Default,
                            navigationPolicy =
                                WebNavigationPolicy(
                                    allowNavigation = true,
                                    allowedSchemes = setOf("https"),
                                    lockedMainFrameOrigin = origin,
                                    allowedSubframeHosts = setOf("artifacts.raft.build"),
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
                            onLoadSuccess = { loadSuccessCount += 1 },
                            onNavigationBlocked = { url, reason -> blocked += url to reason },
                        ),
                    jsbRegistry = registry,
                    scope = this,
                )
            val session = assertNotNull(controller.session as? RaftDesktopWryWebSession)
            controller.start()

            val secretMatch = Regex("""s:"([0-9a-f]{48})"""").find(capturedInitScript)
            val secret = assertNotNull(secretMatch?.groupValues?.get(1), "expected per-session secret in initScript")
            assertTrue(capturedInitScript.contains("window.raftBridge"))

            // 1. Declared subframe subdomain passes without firing onNavigationBlocked; foreign top-level blocks.
            assertTrue(fakeBridge.navigateListener!!.invoke("https://content.artifacts.raft.build/index.html"))
            assertTrue(blocked.isEmpty())
            assertFalse(fakeBridge.navigateListener!!.invoke("https://evil.example.com/phish"))
            assertEquals(1, blocked.size)
            assertEquals("https://evil.example.com/phish", blocked.single().first)

            // 2. Spoofed IPC without matching secret is ignored; authenticated IPC runs Kotlin JSB and replies.
            val jsbRequest = """{"v":1,"id":"req-1","method":"app.ping","params":{}}"""
            val spoofedIpc =
                buildJsonObject {
                    put("s", "000000000000000000000000000000000000000000000000")
                    put("k", "jsb")
                    put("v", jsbRequest)
                }.toString()
            val validIpc =
                buildJsonObject {
                    put("s", secret)
                    put("k", "jsb")
                    put("v", jsbRequest)
                }.toString()
            val openWindowIpc =
                buildJsonObject {
                    put("s", secret)
                    put("k", "open")
                    put("v", "https://external.example.org/docs")
                }.toString()

            fakeBridge.enqueueSnapshot(
                RaftDesktopWryPollSnapshot(
                    ipcMessages = listOf(spoofedIpc, validIpc, openWindowIpc),
                    isLoading = false,
                    title = "Artifact",
                    canGoBack = false,
                    canGoForward = false,
                ),
            )
            session.pollOnce()
            advanceUntilIdle()

            val responseScripts =
                fakeBridge.evaluatedScripts.filter { it.contains("__dispatchResponse") }
            assertEquals(1, responseScripts.size, "only the secret-authenticated JSB call should receive a reply")
            assertTrue(responseScripts.single().contains("req-1"))
            assertTrue(responseScripts.single().contains("pong"))
            assertTrue(blocked.any { it.first == "https://external.example.org/docs" && it.second == "new_window_blocked" })

            // 3. SPA pushState emits onUrlChange on the trusted origin.
            fakeBridge.nextHref = "\"https://app.raft.build/a/2\""
            session.pollOnce()
            assertTrue(observedUrls.contains("https://app.raft.build/a/2"))

            // 4. Deferred finishLoad waits for HREF_SCRIPT verification before firing onLoadSuccess.
            val countBeforeLoad = loadSuccessCount
            fakeBridge.deferHrefCallback = true
            fakeBridge.enqueueSnapshot(
                RaftDesktopWryPollSnapshot(emptyList(), isLoading = true, title = "Loading", canGoBack = false, canGoForward = false),
            )
            session.pollOnce()
            fakeBridge.pendingHrefCallback?.invoke("\"https://app.raft.build/a/2\"")
            fakeBridge.enqueueSnapshot(
                RaftDesktopWryPollSnapshot(emptyList(), isLoading = false, title = "Loaded", canGoBack = false, canGoForward = false),
            )
            session.pollOnce()
            assertEquals(countBeforeLoad, loadSuccessCount, "finishLoad must wait for HREF_SCRIPT verification")
            fakeBridge.pendingHrefCallback?.invoke("\"https://app.raft.build/a/3\"")
            assertEquals(countBeforeLoad + 1, loadSuccessCount)

            controller.dispose()
            assertTrue(fakeBridge.disposed)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
