package build.raft.app.shared

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.threadNewerAutoMessageLabel
import build.raft.app.ThreadNewerAutoPaginationVisualFixtureProbe
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RaftThreadImePaintConnectedTest {
    @Test
    fun sendRetiresEditorSessionWithoutLosingFocusImeOrFirstComposition() {
        val imeSetupFailureDiagnosticsVerified = assertImeSetupFailureDiagnosticsContract()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val intent =
            Intent(instrumentation.targetContext, RaftCmpActivity::class.java)
                .putExtra(RaftCmpActivity.KEY_PAGE_NAME, PageName)
                .putExtra(
                    RaftCmpActivity.KEY_PAGE_DATA,
                    JSONObject()
                        .put("runNonce", nonce)
                        .put("showComposer", true)
                        .toString(),
                )

        ActivityScenario.launch<RaftCmpActivity>(intent).use { scenario ->
            dismissAndroidCompatibilityDialog()
            waitUntil(RenderTimeoutMs, "initial editor") { scenario.raftFirstTextField() != null }

            // CMP: the composer is a Compose BasicTextField. Its identity is the semantics node
            // id of that text field, which survives recomposition and changes only if the editor
            // is re-created - the counterpart of the Kuikly-era EditText instance.
            val retiredEditorId = requireNotNull(scenario.raftFirstTextField()).id
            // Drive focus through the same bounded wait as the rest of the suite:
            // a present-but-not-yet-focusable editor (window focus still settling on
            // a slow CI emulator) makes a one-shot focus request flake.
            waitUntil(RenderTimeoutMs, "retired editor focused") {
                scenario.raftRequestTextFieldFocus(retiredEditorId)
                scenario.raftFirstTextField()?.let { it.id == retiredEditorId && it.focused } == true
            }
            scenario.raftAwaitTextInputSession("retired editor")
            scenario.onActivity { activity ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    activity.window.insetsController?.show(WindowInsets.Type.ime())
                }
                val connection = requireNotNull(activity.raftFocusedInputConnection())
                assertTrue("initial IME commit should succeed", connection.commitText("sent", 1))
            }
            scenario.waitForRetiredEditorImeSetup(retiredEditorId)

            val imeHiddenDuringReplacement = AtomicBoolean(false)
            scenario.onActivity { activity ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val decorView = activity.window.decorView
                    decorView.viewTreeObserver.addOnGlobalLayoutListener {
                        if (decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false) {
                            imeHiddenDuringReplacement.set(true)
                        }
                    }
                    decorView.setWindowInsetsAnimationCallback(
                        object : WindowInsetsAnimation.Callback(
                            WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
                        ) {
                            override fun onProgress(
                                insets: WindowInsets,
                                runningAnimations: MutableList<WindowInsetsAnimation>,
                            ): WindowInsets {
                                if (!insets.isVisible(WindowInsets.Type.ime())) {
                                    imeHiddenDuringReplacement.set(true)
                                }
                                return insets
                            }
                        }
                    )
                }
            }

            try {
                tapEnabledSendActionUntilRequested(nonce)
            } catch (failure: AssertionError) {
                throw AssertionError(
                    "${failure.message}; " +
                        scenario.composerRetirementDiagnostics(nonce, retiredEditorId),
                    failure,
                )
            }
            // task #465 (artin ruling: keep the current product contract — send
            // retires the logical editor session but REUSES the same editor; there
            // is no replacement editor). Assert the retained editor is cleared
            // (session advanced in place) rather than a new instance.
            // finishComposingBeforeStateRead drains any in-flight IME composition
            // before the send reads state, so no real stale-session write can queue
            // past send — the contract to verify is "same editor, cleared, next real
            // composition accepted".
            val editorRetained = AtomicBoolean(false)
            val draftClearedAfterSend = AtomicBoolean(false)
            val imeVisibleAfterSend = AtomicBoolean(false)
            try {
                var lastEdgeDiagnostic: String? = null
                waitUntil(SendTimeoutMs, "same editor cleared after send") {
                    val candidate = scenario.raftFirstTextField()
                    val captured =
                        candidate != null && candidate.id == retiredEditorId && candidate.text.isEmpty()
                    if (captured) {
                        editorRetained.set(true)
                        draftClearedAfterSend.set(true)
                        imeVisibleAfterSend.set(scenario.isImeVisible())
                    } else {
                        val diagnostic =
                            scenario.composerRetirementDiagnostics(nonce, retiredEditorId)
                        if (diagnostic != lastEdgeDiagnostic) {
                            lastEdgeDiagnostic = diagnostic
                            println("retainedEditorWait edge: $diagnostic")
                        }
                    }
                    captured
                }
            } catch (failure: AssertionError) {
                throw AssertionError(
                    "${failure.message}; " +
                        scenario.composerRetirementDiagnostics(nonce, retiredEditorId),
                    failure,
                )
            }
            // The IME stayed up through Send; sample that before the composition probe below
            // deliberately parks it.
            val imeNeverHiddenThroughSend = !imeHiddenDuringReplacement.get()
            // A REAL first composition on the retained editor must still be accepted, and its
            // composing region must survive the composer's controlled-value round trip
            // (onValueChange -> editor owner -> recomposition) - same editor, no focus loss.
            //
            // The test plays the IME through the focused view's InputConnection. A visible
            // soft keyboard "repairs" composing regions it did not create (recent Gboard
            // finishes them), which races the probe, so the keyboard is parked for the probe
            // and shown again afterwards to prove the retained editor is still the IME target.
            scenario.hideSoftKeyboardAndAwaitHidden("first-composition probe")
            scenario.raftAwaitTextInputSession("retained editor")
            val firstCompositionAccepted = AtomicBoolean(false)
            scenario.onActivity { activity ->
                val connection = requireNotNull(activity.raftFocusedInputConnection())
                firstCompositionAccepted.set(connection.setComposingText("sent", 1))
            }
            assertTrue("first composition on retained editor must be accepted", firstCompositionAccepted.get())
            waitUntil(ImeTransitionTimeoutMs, "sent row and same-text first composition") {
                ThreadNewerAutoPaginationVisualFixtureProbe.sentRequestCount(nonce) >= 1 &&
                    scenario.raftFirstTextField()?.let { editor ->
                        editor.id == retiredEditorId &&
                            editor.text == "sent" &&
                            editor.selection == TextRange(4, 4) &&
                            editor.focused
                    } == true
            }
            val firstSelectionPreserved = scenario.raftFirstTextField()?.selection == TextRange(4, 4)
            // Compose does not expose the composing region through semantics. Prove it still
            // spans [0, 4) after the composer's round trip the way an IME observes it: updating
            // the composing text replaces exactly the composing region, so "sent" becomes
            // "sends" only if that region is intact (a dropped composition would append, giving
            // "sentsends").
            scenario.onActivity { activity ->
                val connection = requireNotNull(activity.raftFocusedInputConnection())
                assertTrue(
                    "updating the open first composition must be accepted",
                    connection.setComposingText("sends", 1),
                )
            }
            waitUntil(ImeTransitionTimeoutMs, "composition update to reach the retained editor") {
                scenario.raftFirstTextField()?.text != "sent"
            }
            val recomposed = scenario.raftFirstTextField()
            assertEquals(
                "the first composition must still be open over [0, 4) after the composer round " +
                    "trip; ${scenario.composerRetirementDiagnostics(nonce, retiredEditorId)}",
                "sends" to TextRange(5, 5),
                recomposed?.text to recomposed?.selection,
            )
            // Then bring the keyboard back: the same, still-focused editor must take it.
            scenario.onActivity { activity -> activity.window.insetsController?.show(WindowInsets.Type.ime()) }
            try {
                waitUntil(ImeTransitionTimeoutMs, "retained editor focused with the IME back") {
                    scenario.raftFirstTextField()?.let { editor ->
                        editor.id == retiredEditorId &&
                            editor.text == "sends" &&
                            editor.focused && scenario.isImeVisible()
                    } == true
                }
            } catch (failure: AssertionError) {
                throw AssertionError(
                    "${failure.message}; " +
                        scenario.composerRetirementDiagnostics(nonce, retiredEditorId),
                    failure,
                )
            }
            val sentRequestCount = ThreadNewerAutoPaginationVisualFixtureProbe.sentRequestCount(nonce)
            assertEquals("session retirement send must issue exactly one request", 1, sentRequestCount)
            val retainedEditor = requireNotNull(scenario.raftFirstTextField())
            val receipt =
                "SLOCK_COMPOSER_SESSION_RETIREMENT_PASS " +
                    "editorRetained=${editorRetained.get()} " +
                    "retainedFocused=${retainedEditor.id == retiredEditorId && retainedEditor.focused} " +
                    "draftClearedAfterSend=${draftClearedAfterSend.get()} " +
                    "firstComposition=${firstCompositionAccepted.get()} " +
                    "firstSelection=$firstSelectionPreserved " +
                    "requestCount=$sentRequestCount " +
                    "imeSetupFailureDiagnostics=$imeSetupFailureDiagnosticsVerified " +
                    "imeVisibleAfterSend=${imeVisibleAfterSend.get()} " +
                    "imeNeverHiddenDuringSample=$imeNeverHiddenThroughSend"
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("composerSessionRetirement", receipt) },
            )
            println(receipt)
        }
    }

    @Test
    fun repeatedImeResizeAndSendKeepVisibleThreadRowsPainted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val intent =
            Intent(instrumentation.targetContext, RaftCmpActivity::class.java)
                .putExtra(RaftCmpActivity.KEY_PAGE_NAME, PageName)
                .putExtra(
                    RaftCmpActivity.KEY_PAGE_DATA,
                    JSONObject()
                        .put("runNonce", nonce)
                        .put("showComposer", true)
                        .toString(),
                )

        ActivityScenario.launch<RaftCmpActivity>(intent).use { scenario ->
            dismissAndroidCompatibilityDialog()
            try {
                waitUntil(RenderTimeoutMs, "initial Thread rows and editable composer") {
                    visibleMessageNodes().isNotEmpty() && editableNode() != null
                }
            } catch (failure: AssertionError) {
                throw AssertionError("${failure.message}; accessibility=${accessibilityTreeSummary()}", failure)
            }

            repeat(ImeCycles) { cycle ->
                val editor = requireNotNull(editableNode())
                assertTrue(
                    "cycle=$cycle composer click failed",
                    editor.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                )
                instrumentation.waitForIdleSync()
                SystemClock.sleep(ImeSettleMs)
                assertVisibleMessagesPainted(cycle, "shown")

                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(ImeSettleMs)
                assertVisibleMessagesPainted(cycle, "hidden")
            }

            repeat(SendCycles) { cycle ->
                val body = "IME-SEND-$cycle"
                val editor = requireNotNull(editableNode())
                assertTrue(
                    "cycle=$cycle set composer text failed",
                    editor.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                body,
                            )
                        },
                    ),
                )
                instrumentation.waitForIdleSync()
                SystemClock.sleep(InputSettleMs)
                tapSendButton(scenario, editor)
                waitUntil(SendTimeoutMs, "sent row $body") {
                    ThreadNewerAutoPaginationVisualFixtureProbe.sentRequestCount(nonce) >= cycle + 1
                }
                SystemClock.sleep(SendSettleMs)
                assertVisibleMessagesPainted(cycle, "sent")
            }
        }
    }

    private fun assertVisibleMessagesPainted(cycle: Int, phase: String) {
        val nodes = visibleMessageNodes()
        assertTrue("cycle=$cycle phase=$phase has no visible message nodes", nodes.isNotEmpty())
        val screenshot =
            requireNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
            )
        val painted = nodes.count { node -> screenshot.hasNonBackgroundPixels(node.bounds) }
        assertTrue(
            "cycle=$cycle phase=$phase visible=${nodes.size} painted=$painted",
            painted > 0,
        )
    }

    private fun visibleMessageNodes(): List<VisibleNode> {
        val root =
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: return emptyList()
        val labels =
            (1L..16L).map(::threadNewerAutoMessageLabel) +
                (0 until SendCycles).map { cycle -> "IME-SEND-$cycle" }
        return labels.mapNotNull { label ->
            root.raftFindNodesByText(label)
                .mapNotNull { node ->
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    bounds.takeIf { it.width() > 0 && it.height() > 0 }?.let(::VisibleNode)
                }
                .firstOrNull()
        }
    }

    private fun editableNode(): AccessibilityNodeInfo? {
        val root =
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return null
    }

    private fun tapEnabledSendActionUntilRequested(nonce: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        waitUntil(SendTimeoutMs, "fresh enabled composer send bounds and admitted request") {
            if (ThreadNewerAutoPaginationVisualFixtureProbe.sentRequestCount(nonce) >= 1) {
                true
            } else {
                val node = sendActionNode()
                val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
                if (
                    node != null &&
                    bounds != null &&
                    node.isEnabled &&
                    node.isClickable &&
                    node.isFocusable &&
                    node.hasClickAction() &&
                    bounds.width() > 0 &&
                    bounds.height() > 0
                ) {
                    ParcelFileDescriptor.AutoCloseInputStream(
                        instrumentation.uiAutomation.executeShellCommand(
                            "input touchscreen tap ${bounds.centerX()} ${bounds.centerY()}",
                        ),
                    ).bufferedReader().use { it.readText() }
                    ThreadNewerAutoPaginationVisualFixtureProbe.sentRequestCount(nonce) >= 1
                } else {
                    false
                }
            }
        }
    }

    /**
     * The composer's Send control as an accessibility service sees it: the focusable node that
     * owns the click action and is named "Send".
     *
     * Compose does not put the name on that node itself. The design-system icon button merges its
     * descendants, and Compose moves a merging node's own content description onto a synthetic,
     * non-focusable child (`SemanticsNode.emitFakeNodes`), so the tree reads
     * `View[clickable, focusable, ACTION_CLICK]` > `View[description=Send]`. TalkBack and Switch
     * Access focus the parent and announce the child's name, the same pairing this resolves.
     * Matching the first "Send" description alone (the Kuikly-era tree) finds the unclickable
     * child and never taps.
     */
    private fun sendActionNode(): AccessibilityNodeInfo? {
        val root =
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.contentDescription?.toString() == SendActionDescription) {
                return node.accessibilityClickOwner()
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return null
    }

    /** This node if an accessibility service can click it, else its nearest clickable ancestor. */
    private fun AccessibilityNodeInfo.accessibilityClickOwner(): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = this
        while (candidate != null) {
            if (candidate.isClickable) return candidate
            if (candidate !== this && candidate.isFocusable) return null
            candidate = candidate.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.hasClickAction(): Boolean =
        actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    /** Every node named [SendActionDescription] in the active window, with its click owner. */
    private fun sendActionCandidatesSummary(): String {
        val root =
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: return "<no-root>"
        val entries = mutableListOf<String>()
        fun describe(node: AccessibilityNodeInfo): String {
            val bounds = Rect().also(node::getBoundsInScreen)
            return "class=${node.className} window=${node.windowId} enabled=${node.isEnabled} " +
                "clickable=${node.isClickable} focusable=${node.isFocusable} " +
                "description=${node.contentDescription} bounds=$bounds " +
                "actions=${node.actionList.map { it.id }} children=${node.childCount}"
        }
        fun visit(node: AccessibilityNodeInfo, path: String) {
            if (node.contentDescription?.toString() == SendActionDescription) {
                entries +=
                    "path=$path ${describe(node)} " +
                        "clickOwner=[${node.accessibilityClickOwner()?.let(::describe) ?: "none"}]"
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let { visit(it, "$path/$index") } }
        }
        visit(root, "r")
        return entries.joinToString(" | ").ifEmpty { "none" }
    }

    private fun tapSendButton(
        scenario: ActivityScenario<RaftCmpActivity>,
        editor: AccessibilityNodeInfo,
    ) {
        val editorBounds = Rect().also(editor::getBoundsInScreen)
        var tapX = 0f
        var tapY = 0f
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val density = activity.resources.displayMetrics.density
            tapX = root.width - SendButtonRightInsetDp * density
            tapY = editorBounds.bottom + SendButtonBelowEditorDp * density
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "input touchscreen tap ${tapX.toInt()} ${tapY.toInt()}",
            ),
        ).bufferedReader().use { it.readText() }
        instrumentation.waitForIdleSync()
    }

    private fun dismissAndroidCompatibilityDialog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + CompatibilityDialogWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val button =
                root?.findAccessibilityNodeInfosByText("Don't Show Again")?.firstOrNull()
                    ?: root?.findAccessibilityNodeInfosByText("OK")?.firstOrNull()
            if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(CompatibilityDialogSettleMs)
                return
            }
            SystemClock.sleep(PollMs)
        }
    }

    private fun accessibilityTreeSummary(): String {
        val root =
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: return "<no-root>"
        val entries = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || entries.size >= MaxAccessibilitySummaryEntries) return
            entries +=
                "d=$depth text=${node.text?.toString().orEmpty()} " +
                    "description=${node.contentDescription?.toString().orEmpty()} " +
                    "class=${node.className} editable=${node.isEditable}"
            repeat(node.childCount) { index -> visit(node.getChild(index), depth + 1) }
        }
        visit(root, 0)
        return entries.joinToString(" | ").take(MaxAccessibilitySummaryChars)
    }

    private fun Bitmap.hasNonBackgroundPixels(bounds: Rect): Boolean {
        val left = bounds.left.coerceIn(0, width)
        val top = bounds.top.coerceIn(0, height)
        val right = bounds.right.coerceIn(left, width)
        val bottom = bounds.bottom.coerceIn(top, height)
        var sampled = 0
        for (y in top until bottom step PixelStride) {
            for (x in left until right step PixelStride) {
                val color = getPixel(x, y)
                if (
                    Color.alpha(color) > 0 &&
                    Color.red(color) < BackgroundThreshold &&
                    Color.green(color) < BackgroundThreshold &&
                    Color.blue(color) < BackgroundThreshold
                ) {
                    sampled += 1
                    if (sampled >= MinimumPaintedSamples) return true
                }
            }
        }
        return false
    }

    private fun waitUntil(timeoutMs: Long, description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            // Synchronize each poll on real view-system progress instead of a
            // bare fixed quantum. Draining the instrumentation main looper runs
            // queued attach/composition work to completion; the Choreographer
            // frame boundary is a deterministic "the view system advanced" edge.
            // Bounded by [timeoutMs] like the cadence poll, so the task #76
            // bounded-wait contract still holds.
            awaitEditorIdleAndFrame(description, deadline)
            if (condition()) return
        }
        throw AssertionError("Timed out waiting for $description")
    }

    /**
     * Blocks until the next [Choreographer] frame boundary (or [deadline]),
     * after first running one turn on the main thread.
     *
     * Used by the frame-synchronized [waitUntil] path to turn readiness polling
     * into event-driven waiting: each re-check happens only after the view
     * system has actually advanced, so a healthy-but-slow CI host converges on
     * real progress edges instead of racing a fixed sleep cadence.
     *
     * It deliberately does NOT call `waitForIdleSync`: that has no timeout and a
     * live focus/IME/recomposition stream can block the decisive retirement case
     * before its bounded replacement wait runs (task #76). `runOnMainSync` waits
     * only for a single posted runnable — one real main-thread edge, not a
     * queue-empty drain — and the `Choreographer` await is capped by [deadline].
     */
    private fun awaitEditorIdleAndFrame(description: String, deadline: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback { frame.countDown() }
        }
        val remaining = deadline - SystemClock.uptimeMillis()
        if (remaining <= 0) {
            throw AssertionError("Timed out waiting for $description")
        }
        frame.await(remaining, TimeUnit.MILLISECONDS)
    }

    private fun ActivityScenario<RaftCmpActivity>.waitForRetiredEditorImeSetup(
        retiredEditorId: Int,
    ) {
        waitForComposerImeSetup(ImeTransitionTimeoutMs) {
            composerImeSetupSnapshot(retiredEditorId)
        }
    }

    private fun assertImeSetupFailureDiagnosticsContract(): Boolean {
        val blockedSnapshot =
            ComposerImeSetupSnapshot(
                currentIdentity = 101,
                sameInstance = false,
                focused = true,
                text = "sent",
                imeVisible = true,
                treeIdentity = 202,
                treeSameAsRetired = true,
                treeAttached = true,
            )

        val failure =
            assertThrows(AssertionError::class.java) {
                waitForComposerImeSetup(timeoutMs = 0L) { blockedSnapshot }
            }
        assertEquals(
            "Timed out waiting for focused editor with visible IME and sent text; " +
                "currentIdentity=101 sameInstance=false focused=true text=\"sent\" " +
                "imeVisible=true treeIdentity=202 treeSameAsRetired=true treeAttached=true",
            failure.message,
        )

        val missingEditorFailure =
            assertThrows(AssertionError::class.java) {
                waitForComposerImeSetup(timeoutMs = 0L) {
                    ComposerImeSetupSnapshot.missingEditor()
                }
            }
        assertEquals(
            "Timed out waiting for focused editor with visible IME and sent text; " +
                "currentIdentity=none sameInstance=false focused=false " +
                "text=<no-current-editor> imeVisible=false treeIdentity=none " +
                "treeSameAsRetired=false treeAttached=false",
            missingEditorFailure.message,
        )

        val readySnapshot =
            ComposerImeSetupSnapshot(
                currentIdentity = 303,
                sameInstance = true,
                focused = true,
                text = "sent",
                imeVisible = true,
                treeIdentity = null,
                treeSameAsRetired = false,
                treeAttached = false,
            )
        assertEquals(
            readySnapshot,
            waitForComposerImeSetup(timeoutMs = 0L) { readySnapshot },
        )
        return true
    }

    private fun waitForComposerImeSetup(
        timeoutMs: Long,
        snapshotProvider: () -> ComposerImeSetupSnapshot,
    ): ComposerImeSetupSnapshot {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var latest = snapshotProvider()
        while (!latest.isReady && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(PollMs)
            if (SystemClock.uptimeMillis() < deadline) {
                latest = snapshotProvider()
            }
        }
        if (latest.isReady) return latest
        throw AssertionError(
            "Timed out waiting for focused editor with visible IME and sent text; $latest",
        )
    }

    private fun ActivityScenario<RaftCmpActivity>.composerImeSetupSnapshot(
        retiredEditorId: Int,
    ): ComposerImeSetupSnapshot {
        val editors = raftTextFields()
        val current = editors.firstOrNull()
        val retained = editors.firstOrNull { it.id == retiredEditorId }
        return ComposerImeSetupSnapshot(
            currentIdentity = current?.id,
            sameInstance = current != null && current.id == retiredEditorId,
            focused = current?.focused == true,
            text = current?.text,
            imeVisible = isImeVisible(),
            treeIdentity = retained?.id,
            treeSameAsRetired = retained != null,
            treeAttached = retained != null,
        )
    }

    private fun ActivityScenario<RaftCmpActivity>.isImeVisible(): Boolean {
        var visible = false
        onActivity { activity ->
            visible =
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                } else {
                    activity.getSystemService(InputMethodManager::class.java).isAcceptingText
                }
        }
        return visible
    }

    private fun ActivityScenario<RaftCmpActivity>.composerRetirementDiagnostics(
        nonce: String,
        retiredEditorId: Int,
    ): String {
        val editors = raftTextFields()
        val currentSummary =
            editors.firstOrNull()?.composerEditorDiagnostics(retiredEditorId) ?: "none"
        val retainedSummary =
            editors.firstOrNull { it.id == retiredEditorId }?.composerEditorDiagnostics(retiredEditorId) ?: "none"
        val imeVisible = isImeVisible()
        val sendActionSummary =
            sendActionNode()?.let { node ->
                "enabled=${node.isEnabled} clickable=${node.isClickable} " +
                    "focusable=${node.isFocusable} clickAction=${node.hasClickAction()} " +
                    "class=${node.className} window=${node.windowId}"
            } ?: "none"
        return "requestCount=${ThreadNewerAutoPaginationVisualFixtureProbe.sentRequestCount(nonce)} " +
            "retiredIdentity=$retiredEditorId editorCount=${editors.size} " +
            "current=[$currentSummary] retained=[$retainedSummary] " +
            "imeVisible=$imeVisible sendAction=[$sendActionSummary] " +
            "sendCandidates=[${sendActionCandidatesSummary()}]"
    }

    private fun RaftTextFieldSnapshot.composerEditorDiagnostics(retiredEditorId: Int): String =
        "identity=$id sameAsRetired=${id == retiredEditorId} focused=$focused " +
            "text=$text selection=$selection"

    private data class VisibleNode(val bounds: Rect)

    private data class ComposerImeSetupSnapshot(
        val currentIdentity: Int?,
        val sameInstance: Boolean,
        val focused: Boolean,
        val text: String?,
        val imeVisible: Boolean,
        val treeIdentity: Int?,
        val treeSameAsRetired: Boolean,
        val treeAttached: Boolean,
    ) {
        val isReady: Boolean
            get() = sameInstance && focused && text == "sent" && imeVisible

        override fun toString(): String =
            "currentIdentity=${currentIdentity ?: "none"} " +
                "sameInstance=$sameInstance focused=$focused " +
                "text=${text?.let { JSONObject.quote(it) } ?: "<no-current-editor>"} " +
                "imeVisible=$imeVisible " +
                "treeIdentity=${treeIdentity ?: "none"} " +
                "treeSameAsRetired=$treeSameAsRetired treeAttached=$treeAttached"

        companion object {
            fun missingEditor(): ComposerImeSetupSnapshot =
                ComposerImeSetupSnapshot(
                    currentIdentity = null,
                    sameInstance = false,
                    focused = false,
                    text = null,
                    imeVisible = false,
                    treeIdentity = null,
                    treeSameAsRetired = false,
                    treeAttached = false,
                )
        }
    }

    private companion object {
        const val PageName = "compose_thread_newer_auto_pagination_visual"
        const val SendActionDescription = "Send"
        const val ImeCycles = 20
        const val SendCycles = 6
        const val ImeSettleMs = 250L
        const val InputSettleMs = 150L
        const val ImeTransitionTimeoutMs = 5_000L
        const val RenderTimeoutMs = 20_000L
        const val SendTimeoutMs = 8_000L
        const val SendSettleMs = 500L
        const val CompatibilityDialogWaitMs = 2_000L
        const val CompatibilityDialogSettleMs = 250L
        const val PollMs = 100L
        const val PixelStride = 2
        const val BackgroundThreshold = 235
        const val MinimumPaintedSamples = 3
        const val SendButtonRightInsetDp = 28f
        const val SendButtonBelowEditorDp = 24f
        const val MaxAccessibilitySummaryEntries = 80
        const val MaxAccessibilitySummaryChars = 8_000
    }
}
