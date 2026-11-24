package build.raft.app.desktop

import build.raft.app.RaftDesktopWebViewPolicy
import build.raft.app.RaftDesktopWryGateway
import build.raft.app.RaftDesktopWryNativeOps
import build.raft.app.RaftDesktopWryNativeView
import build.raft.app.RaftDesktopWryPanelAccess
import build.raft.app.RaftDesktopWryPollSnapshot
import java.awt.Container
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftDesktopWryGatewayTest {
    @Test fun `pinned wrywebview reflection members are available`() {
        assertTrue(RaftDesktopWryPanelAccess.available)
    }

    @Test fun `blocking Wry focus mouse listener class exists on pinned jar and host replacement strips it`() {
        // Verify WryWebViewPanel$2 (which invokes requestWebViewFocus() -> _dispatch_sync_f on EDT)
        // is still present on the pinned jar without triggering native GTK/WebKit <clinit> on Linux.
        val blockingListenerClass =
            Class.forName(
                "io.github.kdroidfilter.webview.wry.WryWebViewPanel$2",
                false,
                RaftDesktopWryPanelAccess::class.java.classLoader,
            )
        assertTrue(java.awt.event.MouseAdapter::class.java.isAssignableFrom(blockingListenerClass))

        val host = java.awt.Canvas()
        var blockedPressed = 0
        val fakeBlocking =
            object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    blockedPressed += 1
                }
            }
        host.addMouseListener(fakeBlocking)
        var offEdtFocusRequests = 0
        val removed =
            RaftDesktopWryPanelAccess.replaceBlockingFocusMouseListenersOnHost(
                host = host,
                blockedListenerPrefix = fakeBlocking.javaClass.name,
                onFocusRequested = { offEdtFocusRequests += 1 },
            )
        assertEquals(1, removed)
        assertEquals(1, host.mouseListeners.size)
        host.mouseListeners.single().mousePressed(
            java.awt.event.MouseEvent(host, java.awt.event.MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false),
        )
        assertEquals(0, blockedPressed)
        assertEquals(1, offEdtFocusRequests)

        if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
            val policy =
                RaftDesktopWebViewPolicy(
                    url = "https://raft.example/s/alpha/settings/billing",
                    allowNavigation = true,
                    allowedNavigationSchemes = setOf("https"),
                    mainFrameOrigin = "https://raft.example",
                    hostEventsEnabled = false,
                    injectedJavaScript = "",
                )
            val gateway = RaftDesktopWryGateway.create(policy = policy, ephemeral = true, dataDirectory = null)
            try {
                val nativeView = gateway.component as Container
                val wryPanel = nativeView.components.single() as Container
                val hostCanvas = wryPanel.components.single()
                assertFalse(
                    hostCanvas.mouseListeners.any {
                        it.javaClass.name.startsWith(RaftDesktopWryPanelAccess.WryBlockingFocusListenerPrefix)
                    },
                    "WryWebViewPanel's EDT-blocking mouse listener (WryWebViewPanel\$2) must be stripped on macOS",
                )
                assertEquals(1, hostCanvas.mouseListeners.size)
            } finally {
                gateway.dispose()
            }
        }
    }

    @Test fun `every gateway native call runs off the AWT EDT and returns callbacks onto the EDT without blocking EDT`() {
        val workerExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-wry-worker").apply { isDaemon = true }
            }
        try {
            val workerBlockedInNative = CountDownLatch(1)
            val releaseNativeMainThread = CountDownLatch(1)
            val nativeCalls = CopyOnWriteArrayList<Pair<String, Boolean>>()
            val ops =
                object : RaftDesktopWryNativeOps {
                    private fun record(name: String) {
                        nativeCalls += name to SwingUtilities.isEventDispatchThread()
                    }

                    override fun drainIpcMessages(id: ULong): List<String> {
                        record("drainIpcMessages")
                        workerBlockedInNative.countDown()
                        // Simulate macOS Thread 0 held inside Chinese IME / live-resize while waiting on EDT.
                        assertTrue(releaseNativeMainThread.await(5, TimeUnit.SECONDS))
                        return listOf("msg-1")
                    }

                    override fun isLoading(id: ULong): Boolean {
                        record("isLoading")
                        return false
                    }

                    override fun getTitle(id: ULong): String {
                        record("getTitle")
                        return "Billing"
                    }

                    override fun canGoBack(id: ULong): Boolean {
                        record("canGoBack")
                        return true
                    }

                    override fun canGoForward(id: ULong): Boolean {
                        record("canGoForward")
                        return true
                    }

                    override fun evaluateJavascript(id: ULong, script: String, onResult: (String) -> Unit) {
                        record("evaluateJavascript")
                        onResult("\"https://raft.example/s/srv/settings/billing\"")
                    }

                    override fun loadUrl(id: ULong, url: String) = record("loadUrl")

                    override fun goBack(id: ULong) = record("goBack")

                    override fun goForward(id: ULong) = record("goForward")

                    override fun reload(id: ULong) = record("reload")

                    override fun stopLoading(id: ULong) = record("stopLoading")

                    override fun focus(id: ULong) = record("focus")

                    override fun setBounds(id: ULong, x: Int, y: Int, width: Int, height: Int) = record("setBounds")
                }

            val gateway =
                RaftDesktopWryGateway(
                    component = JPanel(),
                    readWebviewId = { 42uL },
                    registerNavigateListener = {},
                    revealComponent = {},
                    nativeOps = ops,
                    worker = { task -> workerExecutor.execute(task) },
                    postToEdt = { SwingUtilities.invokeLater(it) },
                )

            val pollDeliveredOnEdt = CountDownLatch(1)
            val evalDeliveredOnEdt = CountDownLatch(1)
            val reloadCompletedOnEdt = CountDownLatch(1)
            val callbackThreads = CopyOnWriteArrayList<Pair<String, Boolean>>()
            var receivedSnapshot: RaftDesktopWryPollSnapshot? = null
            var receivedEval: String? = null

            // Submit every operation from the real AWT EDT. Because the worker stalls inside
            // drainIpcMessages until releaseNativeMainThread is counted down, ANY synchronous wait
            // from EDT onto the worker would deadlock right here.
            SwingUtilities.invokeAndWait {
                assertTrue(
                    gateway.poll { snapshot ->
                        callbackThreads += "poll" to SwingUtilities.isEventDispatchThread()
                        receivedSnapshot = snapshot
                        pollDeliveredOnEdt.countDown()
                    },
                )
                // Overlapping polls while the first poll is in flight must coalesce (return false).
                assertFalse(gateway.poll { error("coalesced poll must not run") })
                gateway.evaluateJavaScript("1+1") { result ->
                    callbackThreads += "eval" to SwingUtilities.isEventDispatchThread()
                    receivedEval = result
                    evalDeliveredOnEdt.countDown()
                }
                gateway.loadUrl("https://raft.example/")
                gateway.goBackIfPossible()
                gateway.goForwardIfPossible()
                gateway.reload {
                    callbackThreads += "reload" to SwingUtilities.isEventDispatchThread()
                    reloadCompletedOnEdt.countDown()
                }
                gateway.stopLoading()
                gateway.requestFocus()
                gateway.moveOffScreen(800, 600)
            }

            // Prove EDT returned immediately while the worker is still blocked in native code.
            assertTrue(workerBlockedInNative.await(5, TimeUnit.SECONDS))
            var edtStillResponsive = false
            SwingUtilities.invokeAndWait { edtStillResponsive = SwingUtilities.isEventDispatchThread() }
            assertTrue(edtStillResponsive, "AWT EDT must stay responsive while Wry worker waits on macOS main thread")

            // Now release the simulated macOS main thread and wait for callbacks on EDT.
            releaseNativeMainThread.countDown()
            assertTrue(pollDeliveredOnEdt.await(5, TimeUnit.SECONDS))
            assertTrue(evalDeliveredOnEdt.await(5, TimeUnit.SECONDS))
            assertTrue(reloadCompletedOnEdt.await(5, TimeUnit.SECONDS))

            // Drain remaining fire-and-forget native ops on the FIFO worker.
            val workerDrained = CountDownLatch(1)
            workerExecutor.execute { workerDrained.countDown() }
            assertTrue(workerDrained.await(5, TimeUnit.SECONDS))

            val expectedOps =
                setOf(
                    "drainIpcMessages",
                    "isLoading",
                    "getTitle",
                    "canGoBack",
                    "canGoForward",
                    "evaluateJavascript",
                    "loadUrl",
                    "goBack",
                    "goForward",
                    "reload",
                    "stopLoading",
                    "focus",
                    "setBounds",
                )
            assertEquals(expectedOps, nativeCalls.map { it.first }.toSet())
            assertTrue(nativeCalls.none { it.second }, "No native Wry op may run on AWT EDT: $nativeCalls")
            assertTrue(callbackThreads.all { it.second }, "All gateway callbacks must land on AWT EDT: $callbackThreads")
            assertEquals(
                RaftDesktopWryPollSnapshot(
                    ipcMessages = listOf("msg-1"),
                    isLoading = false,
                    title = "Billing",
                    canGoBack = true,
                    canGoForward = true,
                ),
                receivedSnapshot,
            )
            assertEquals("\"https://raft.example/s/srv/settings/billing\"", receivedEval)
        } finally {
            workerExecutor.shutdownNow()
        }
    }

    @Test fun `gateway coalesces overlapping polls and drops stale callbacks after dispose`() {
        val pendingTasks = mutableListOf<() -> Unit>()
        val pendingEdt = mutableListOf<() -> Unit>()
        val ops =
            object : RaftDesktopWryNativeOps {
                override fun drainIpcMessages(id: ULong) = emptyList<String>()
                override fun isLoading(id: ULong) = false
                override fun getTitle(id: ULong) = "Title"
                override fun canGoBack(id: ULong) = false
                override fun canGoForward(id: ULong) = false
                override fun evaluateJavascript(id: ULong, script: String, onResult: (String) -> Unit) = onResult("\"ok\"")
                override fun loadUrl(id: ULong, url: String) = Unit
                override fun goBack(id: ULong) = Unit
                override fun goForward(id: ULong) = Unit
                override fun reload(id: ULong) = Unit
                override fun stopLoading(id: ULong) = Unit
                override fun focus(id: ULong) = Unit
                override fun setBounds(id: ULong, x: Int, y: Int, width: Int, height: Int) = Unit
            }
        val gateway =
            RaftDesktopWryGateway(
                component = JPanel(),
                readWebviewId = { 7uL },
                registerNavigateListener = {},
                revealComponent = {},
                nativeOps = ops,
                worker = { pendingTasks += it },
                postToEdt = { pendingEdt += it },
            )
        var delivered = 0
        assertTrue(gateway.poll { delivered += 1 })
        assertFalse(gateway.poll { delivered += 1 })
        assertEquals(1, pendingTasks.size)

        // Complete the poll; next poll should be admitted again.
        pendingTasks.removeAt(0).invoke()
        pendingEdt.removeAt(0).invoke()
        assertEquals(1, delivered)

        assertTrue(gateway.poll { delivered += 1 })
        gateway.evaluateJavaScript("1") { delivered += 1 }
        // Worker runs, queues EDT callbacks, then dispose() happens before EDT drains.
        pendingTasks.forEach { it() }
        pendingTasks.clear()
        gateway.dispose()
        pendingEdt.forEach { it() }
        assertEquals(1, delivered)
        assertFalse(gateway.isReady())
        assertFalse(gateway.poll { delivered += 1 })
    }

    @Test fun `reveal supersedes earlier queued moveOffScreen while post-reveal hide still runs across dispose`() {
        val pendingTasks = mutableListOf<() -> Unit>()
        val setBoundsCalls = mutableListOf<List<Int>>()
        var revealedCount = 0
        val ops =
            object : RaftDesktopWryNativeOps {
                override fun drainIpcMessages(id: ULong) = emptyList<String>()
                override fun isLoading(id: ULong) = false
                override fun getTitle(id: ULong) = ""
                override fun canGoBack(id: ULong) = false
                override fun canGoForward(id: ULong) = false
                override fun evaluateJavascript(id: ULong, script: String, onResult: (String) -> Unit) = Unit
                override fun loadUrl(id: ULong, url: String) = Unit
                override fun goBack(id: ULong) = Unit
                override fun goForward(id: ULong) = Unit
                override fun reload(id: ULong) = Unit
                override fun stopLoading(id: ULong) = Unit
                override fun focus(id: ULong) = Unit
                override fun setBounds(id: ULong, x: Int, y: Int, width: Int, height: Int) {
                    setBoundsCalls += listOf(x, y, width, height)
                }
            }
        val gateway =
            RaftDesktopWryGateway(
                component = JPanel(),
                readWebviewId = { 9uL },
                registerNavigateListener = {},
                revealComponent = { revealedCount += 1 },
                nativeOps = ops,
                worker = { pendingTasks += it },
                postToEdt = { it() },
            )

        // Hidden-phase moveOffScreen queued while worker is busy, then reveal() happens before it runs.
        gateway.moveOffScreen(900, 700)
        gateway.reveal()
        assertEquals(1, revealedCount)
        pendingTasks.removeAt(0).invoke()
        assertTrue(setBoundsCalls.isEmpty(), "Stale pre-reveal moveOffScreen must not push a revealed WebView off-screen")

        // Leaving composition (removeNotify) queues moveOffScreen and immediately disposes the gateway:
        // the native view must still move off-screen during Wry's 400 ms deferred destroy window.
        gateway.moveOffScreen(900, 700)
        gateway.dispose()
        pendingTasks.removeAt(0).invoke()
        assertEquals(listOf(listOf(-32_000, -32_000, 900, 700)), setBoundsCalls)
    }

    @Test fun `native view stays transparent and off-screen until attached and revealed then hides cleanly`() {
        val content = JPanel()
        val offScreenCalls = mutableListOf<Pair<Int, Int>>()
        var resyncCalls = 0
        val nativeView =
            RaftDesktopWryNativeView(
                content = content,
                onMoveOffScreen = { w, h -> offScreenCalls += w to h },
                onResyncBounds = { resyncCalls += 1 },
            )
        nativeView.setBounds(0, 0, 800, 600)
        nativeView.doLayout()

        // Before reveal: non-opaque transparent backdrop and content laid out off-screen at full size.
        assertFalse(nativeView.isOpaque)
        assertEquals(0, nativeView.background.alpha)
        assertEquals(800, content.width)
        assertEquals(600, content.height)
        assertTrue(content.x <= -20_800, "expected off-screen x, got ${content.x}")

        // Attach while still unrevealed: remains transparent and off-screen.
        nativeView.addNotify()
        assertFalse(nativeView.isOpaque)
        assertEquals(0, resyncCalls)
        assertTrue(offScreenCalls.isNotEmpty())
        assertTrue(content.x <= -20_800)

        // Reveal: turns opaque white, moves content to (0, 0), and resyncs native bounds.
        nativeView.setRevealed(true)
        assertTrue(nativeView.isOpaque)
        assertEquals(java.awt.Color.WHITE, nativeView.background)
        assertEquals(0, content.x)
        assertEquals(0, content.y)
        assertEquals(800, content.width)
        assertEquals(600, content.height)
        assertTrue(resyncCalls >= 1, "expected at least one bounds resync on reveal, got $resyncCalls")

        // Hide while a cover returns: drops white backdrop and parks content off-screen again.
        val offScreenBeforeHide = offScreenCalls.size
        nativeView.setRevealed(false)
        assertFalse(nativeView.isOpaque)
        assertEquals(0, nativeView.background.alpha)
        assertTrue(content.x <= -20_800)
        assertTrue(offScreenCalls.size > offScreenBeforeHide)
    }

    @Test fun `gateway hide delegates to hideComponent until disposed`() {
        var revealCount = 0
        var hideCount = 0
        val ops =
            object : RaftDesktopWryNativeOps {
                override fun drainIpcMessages(id: ULong) = emptyList<String>()
                override fun isLoading(id: ULong) = false
                override fun getTitle(id: ULong) = ""
                override fun canGoBack(id: ULong) = false
                override fun canGoForward(id: ULong) = false
                override fun evaluateJavascript(id: ULong, script: String, onResult: (String) -> Unit) = Unit
                override fun loadUrl(id: ULong, url: String) = Unit
                override fun goBack(id: ULong) = Unit
                override fun goForward(id: ULong) = Unit
                override fun reload(id: ULong) = Unit
                override fun stopLoading(id: ULong) = Unit
                override fun focus(id: ULong) = Unit
                override fun setBounds(id: ULong, x: Int, y: Int, width: Int, height: Int) = Unit
            }
        val gateway =
            RaftDesktopWryGateway(
                component = JPanel(),
                readWebviewId = { 11uL },
                registerNavigateListener = {},
                revealComponent = { revealCount += 1 },
                hideComponent = { hideCount += 1 },
                nativeOps = ops,
                worker = { it() },
                postToEdt = { it() },
            )
        gateway.reveal()
        gateway.hide()
        assertEquals(1, revealCount)
        assertEquals(1, hideCount)
        gateway.dispose()
        gateway.reveal()
        gateway.hide()
        assertEquals(1, revealCount)
        assertEquals(1, hideCount)
    }
}
