package build.raft.app.shared

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ThreadComposerSuggestionPopoverConnectedTest {
    @Test
    fun openSuggestionsKeepTheEditorAndImageActionInteractive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        clearInteractionFailureEvidence(instrumentation)
        instrumentation.confirmImmersiveEducationDismissedForSuggestionTest()
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.THREAD_COMPOSER_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "composer-suggestion-interaction-oracle")
                        .put("visualFixture", InteractionOracleFixture)
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            try {
                scenario.onActivity { activity -> activity.hideSystemBarsForSuggestionTest() }
                // Visual pages clear native focus when their window first gains focus, then post
                // the same cleanup once more. Requesting editor focus while that initial window
                // transition is still pending makes the harness dismiss its own suggestion panel.
                scenario.awaitVisualInputHostReadyForEditorFocus(
                    "interaction fixture composer"
                ) { activity -> activity.window.decorView.findComposerEditText() != null }
                scenario.onActivity { activity ->
                    val editor = requireNotNull(activity.window.decorView.findComposerEditText())
                    assertTrue("composer should accept focus", editor.requestFocus())
                    editor.setSelection(editor.text.length)
                }
                waitUntil("interaction fixture composer should expose its seeded channel trigger") {
                    scenario.composerText() == "#"
                }
                val initialPanelUnderlayRatio =
                    waitForInteractionPanelUnderlayRatio(
                        scenario = scenario,
                        instrumentation = instrumentation,
                        visible = true
                    )

                val editorBounds = scenario.composerEditTextBounds()
                tapPx(instrumentation, editorBounds.centerX().toFloat(), editorBounds.centerY().toFloat())
                waitUntil("tapping the editor while suggestions are open must preserve focus") {
                    scenario.composerHasFocus()
                }
                val editorFocusedAfterTap = scenario.composerHasFocus()
                val editorTapPanelUnderlayRatio =
                    waitForInteractionPanelUnderlayRatio(
                        scenario = scenario,
                        instrumentation = instrumentation,
                        visible = true
                    )

                scenario.onActivity { activity ->
                    val editor = requireNotNull(activity.window.decorView.findComposerEditText())
                    val inputConnection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
                    assertTrue("focused editor should accept another character", inputConnection.commitText("d", 1))
                }
                waitUntil("typing should continue to update the composer under the open suggestions") {
                    scenario.composerText() == "#d"
                }
                val typedPanelUnderlayRatio =
                    waitForInteractionPanelUnderlayRatio(
                        scenario = scenario,
                        instrumentation = instrumentation,
                        visible = true
                    )

                val density = context.resources.displayMetrics.density
                tapPx(
                    instrumentation = instrumentation,
                    x = editorBounds.left + InteractionImageButtonCenterXDp * density,
                    y = editorBounds.bottom + InteractionToolbarCenterBelowEditorDp * density
                )
                val imageActionGreenRatio =
                    waitForInteractionActionGreenRatio(
                        scenario = scenario,
                        instrumentation = instrumentation
                    )
                val imageActionReceived = imageActionGreenRatio >= InteractionActionGreenMinRatio
                val imageTapPanelUnderlayRatio =
                    waitForInteractionPanelUnderlayRatio(
                        scenario = scenario,
                        instrumentation = instrumentation,
                        visible = true
                    )

                tapPx(
                    instrumentation = instrumentation,
                    x = context.resources.displayMetrics.widthPixels / 2f,
                    y = InteractionOutsideTapYDp * density
                )
                waitUntil("a true outside tap should clear composer focus") {
                    !scenario.composerHasFocus()
                }
                val outsidePanelUnderlayRatio =
                    waitForInteractionPanelUnderlayRatio(
                        scenario = scenario,
                        instrumentation = instrumentation,
                        visible = false
                    )
                val outsideDismissed = outsidePanelUnderlayRatio >= InteractionPanelDismissedMinUnderlayRatio
                val receipt =
                    "THREAD_COMPOSER_SUGGESTION_INTERACTION_PASS " +
                        "editorFocusedAfterTap=$editorFocusedAfterTap " +
                        "text=${scenario.composerText()} imageAction=$imageActionReceived " +
                        "outsideDismissed=$outsideDismissed " +
                        "panelRatios=$initialPanelUnderlayRatio,$editorTapPanelUnderlayRatio," +
                        "$typedPanelUnderlayRatio,$imageTapPanelUnderlayRatio,$outsidePanelUnderlayRatio " +
                        "actionGreenRatio=$imageActionGreenRatio"
                instrumentation.sendStatus(
                    0,
                    Bundle().apply { putString("composerSuggestionInteraction", receipt) }
                )
                println(receipt)
            } catch (failure: Throwable) {
                captureInteractionFailureEvidence(
                    scenario = scenario,
                    instrumentation = instrumentation,
                    failure = failure
                )
                throw failure
            }
        }
    }

    @Test
    fun lateTypedMemberAndChannelCandidatesUseCurrentComposerDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val cases =
            listOf(
                LateTypedSuggestionCase(trigger = "@", candidateLabel = "Cindy", expectedText = "@Cindy "),
                LateTypedSuggestionCase(trigger = "#", candidateLabel = "design", expectedText = "#design ")
            )

        cases.forEachIndexed { index, case ->
            val intent =
                Intent(context, SlockKuiklyActivity::class.java)
                    .putExtra("pageName", SlockKuiklyPages.THREAD_COMPOSER_VISUAL)
                    .putExtra(
                        "pageData",
                        JSONObject()
                            .put("instanceId", "composer-late-suggestion-$index")
                            .put("visualFixture", "empty")
                            .toString()
                    )
                    .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

            ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
                waitUntil("empty composer should attach before '${case.trigger}'") {
                    scenario.composerText() == ""
                }
                scenario.awaitVisualInputHostReadyForEditorFocus(
                    "late '${case.trigger}' fixture composer"
                ) { activity -> activity.window.decorView.findComposerEditText() != null }
                scenario.onActivity { activity ->
                    val editor = requireNotNull(activity.window.decorView.findComposerEditText())
                    assertTrue("composer should accept focus", editor.requestFocus())
                    val inputConnection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
                    assertTrue("IME should commit '${case.trigger}'", inputConnection.commitText(case.trigger, 1))
                }

                val candidate =
                    requireNotNull(device.wait(Until.findObject(By.text(case.candidateLabel)), CandidateWaitMs)) {
                        "${case.candidateLabel} should appear after late '${case.trigger}' input"
                    }
                candidate.click()

                waitUntil("${case.candidateLabel} should update the current composer draft") {
                    scenario.composerText() == case.expectedText
                }
                assertTrue(
                    "${case.candidateLabel} selection should close its popover",
                    device.wait(Until.gone(By.text(case.candidateLabel)), CandidateTapWaitMs)
                )
                scenario.onActivity { activity ->
                    assertTrue("candidate selection should preserve composer focus", activity.window.decorView.findComposerEditText()?.hasFocus() == true)
                }
            }
        }
    }

    @Test
    fun openSuggestionCandidateMovesAndRemainsHittableAfterAncestorRelayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.THREAD_COMPOSER_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "composer-suggestion-reposition-oracle")
                        .put("visualFixture", RepositionOracleFixture)
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> activity.hideSystemBarsForSuggestionTest() }
            instrumentation.waitForIdleSync()

            val initialCandidate =
                requireNotNull(device.wait(Until.findObject(By.text(CandidateLabel)), CandidateWaitMs)) {
                    "Initial suggestion candidate was not exposed before the relayout oracle"
                }
            val initialBounds = Rect(initialCandidate.visibleBounds)
            assertValidCandidateBounds("initial", initialBounds)
            val beforeRelayout = captureActivityBitmap(scenario)

            val movedCandidate = waitForMovedCandidate(device, initialBounds, instrumentation)
            val movedBounds = Rect(movedCandidate.visibleBounds)
            assertValidCandidateBounds("moved", movedBounds)
            val afterRelayout = captureActivityBitmap(scenario)
            val expectedShiftPx = RepositionShiftDp * context.resources.displayMetrics.density
            val actualShiftPx = movedBounds.centerY() - initialBounds.centerY()
            assertTrue(
                "Candidate hit bounds did not follow the visual anchor relayout; expectedShift=$expectedShiftPx actualShift=$actualShiftPx initial=$initialBounds moved=$movedBounds",
                abs(actualShiftPx - expectedShiftPx) <= MaxRepositionErrorPx
            )

            val oldVisualDiff =
                beforeRelayout.changedPixelsComparedWith(
                    afterRelayout,
                    initialBounds.toPixelRegion(beforeRelayout.width, beforeRelayout.height)
                )
            val newVisualDiff =
                beforeRelayout.changedPixelsComparedWith(
                    afterRelayout,
                    movedBounds.toPixelRegion(beforeRelayout.width, beforeRelayout.height)
                )
            assertTrue(
                "Suggestion visuals did not leave the old candidate bounds; changedPixels=$oldVisualDiff bounds=$initialBounds",
                oldVisualDiff >= MinCandidateRelayoutChangedPixels
            )
            assertTrue(
                "Suggestion visuals did not enter the moved candidate bounds; changedPixels=$newVisualDiff bounds=$movedBounds",
                newVisualDiff >= MinCandidateRelayoutChangedPixels
            )

            movedCandidate.click()
            instrumentation.waitForIdleSync()
            assertTrue(
                "Candidate tap at moved hit bounds did not update the composer input",
                device.wait(Until.hasObject(By.textContains("@scroll-candidate-1")), CandidateTapWaitMs)
            )
            assertTrue(
                "Candidate tap at moved hit bounds did not close the popover",
                device.wait(Until.gone(By.text(CandidateLabel)), CandidateTapWaitMs)
            )
            val afterTap = captureActivityBitmap(scenario)
            val uncoveredUnderlay =
                afterTap.countNearColor(
                    UnderlayCyan,
                    movedBounds.toPixelRegion(afterTap.width, afterTap.height)
                )
            val leakAfterTap = afterTap.countNearColor(LeakRed, StatusRegionDp, instrumentation)
            assertTrue(
                "Popover did not uncover its underlay after moved candidate tap; cyanPixels=$uncoveredUnderlay",
                uncoveredUnderlay >= MinMovedCandidateUnderlayPixels
            )
            assertTrue("Moved candidate tap reached the underlying message; leakPixels=$leakAfterTap", leakAfterTap <= MaxLeakPixels)

            beforeRelayout.recycle()
            afterRelayout.recycle()
            afterTap.recycle()
        }
    }

    @Test
    fun overflowingSuggestionsScrollAndCandidateTapDoesNotReachUnderlyingMessage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.THREAD_COMPOSER_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "composer-suggestion-scroll-oracle")
                        .put("visualFixture", "member_suggestions_scroll_oracle")
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> activity.hideSystemBarsForSuggestionTest() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            val beforeDrag = captureActivityBitmap(scenario)
            swipeDp(instrumentation, xDp = 190f, startYDp = 188f, endYDp = 62f)
            SystemClock.sleep(GestureSettleMs)
            val afterDrag = captureActivityBitmap(scenario)

            val panelDiff = beforeDrag.changedPixelsComparedWith(afterDrag, PanelRegionDp, instrumentation)
            val underlayBeforeDrag = beforeDrag.countNearColor(UnderlayCyan, PanelRegionDp, instrumentation)
            val underlayAfterDrag = afterDrag.countNearColor(UnderlayCyan, PanelRegionDp, instrumentation)
            val leakAfterDrag = afterDrag.countNearColor(LeakRed, StatusRegionDp, instrumentation)
            assertTrue("Suggestion rows did not move after vertical drag; changedPixels=$panelDiff", panelDiff >= MinPanelChangedPixels)
            assertTrue(
                "Suggestion popover dismissed during drag; underlayBefore=$underlayBeforeDrag underlayAfter=$underlayAfterDrag",
                underlayAfterDrag <= underlayBeforeDrag + MaxUnderlayPixelIncreaseDuringDrag
            )
            assertTrue("Underlying message activated during drag; leakPixels=$leakAfterDrag", leakAfterDrag <= MaxLeakPixels)

            tapDp(instrumentation, xDp = 190f, yDp = 154f)
            SystemClock.sleep(GestureSettleMs)
            val afterTap = captureActivityBitmap(scenario)

            val inputDiff = afterDrag.changedPixelsComparedWith(afterTap, ComposerInputRegionDp, instrumentation)
            val uncoveredUnderlay = afterTap.countNearColor(UnderlayCyan, PanelRegionDp, instrumentation)
            val leakAfterTap = afterTap.countNearColor(LeakRed, StatusRegionDp, instrumentation)
            assertTrue("Candidate tap did not update composer input; changedPixels=$inputDiff", inputDiff >= MinInputChangedPixels)
            assertTrue("Candidate tap did not close the suggestion popover; underlayPixels=$uncoveredUnderlay", uncoveredUnderlay >= MinUncoveredUnderlayPixels)
            assertTrue("Candidate tap reached the underlying message; leakPixels=$leakAfterTap", leakAfterTap <= MaxLeakPixels)

            beforeDrag.recycle()
            afterDrag.recycle()
            afterTap.recycle()
        }
    }

    private fun swipeDp(
        instrumentation: Instrumentation,
        xDp: Float,
        startYDp: Float,
        endYDp: Float
    ) {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val x = xDp * density
        val startY = startYDp * density
        val endY = endYDp * density
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0))
        repeat(SwipeSteps) { step ->
            val fraction = (step + 1f) / SwipeSteps
            val eventTime = downTime + ((SwipeDurationMs * fraction).toLong())
            val y = startY + ((endY - startY) * fraction)
            instrumentation.sendPointerSync(MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime + SwipeDurationMs, MotionEvent.ACTION_UP, x, endY, 0)
        )
        instrumentation.waitForIdleSync()
    }

    private fun tapDp(instrumentation: Instrumentation, xDp: Float, yDp: Float) {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        tapPx(instrumentation, xDp * density, yDp * density)
    }

    private fun tapPx(instrumentation: Instrumentation, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime + TapDurationMs, MotionEvent.ACTION_UP, x, y, 0))
        instrumentation.waitForIdleSync()
    }

    private fun captureActivityBitmap(scenario: ActivityScenario<SlockKuiklyActivity>): Bitmap {
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            require(root.width > 0 && root.height > 0)
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                root.draw(Canvas(output))
            }
        }
        return requireNotNull(bitmap)
    }

    private fun Bitmap.changedPixelsComparedWith(
        other: Bitmap,
        regionDp: DpRegion,
        instrumentation: Instrumentation
    ): Int = changedPixelsComparedWith(other, regionDp.toPixelRegion(instrumentation, width, height))

    private fun Bitmap.changedPixelsComparedWith(
        other: Bitmap,
        region: PixelRegion
    ): Int {
        var changed = 0
        for (y in region.top until region.bottom) {
            for (x in region.left until region.right) {
                val first = getPixel(x, y)
                val second = other.getPixel(x, y)
                if (
                    abs(Color.red(first) - Color.red(second)) > PixelDifferenceTolerance ||
                    abs(Color.green(first) - Color.green(second)) > PixelDifferenceTolerance ||
                    abs(Color.blue(first) - Color.blue(second)) > PixelDifferenceTolerance
                ) {
                    changed++
                }
            }
        }
        return changed
    }

    private fun Bitmap.countNearColor(
        target: Int,
        regionDp: DpRegion,
        instrumentation: Instrumentation
    ): Int = countNearColor(target, regionDp.toPixelRegion(instrumentation, width, height))

    private fun Bitmap.countNearColor(
        target: Int,
        region: PixelRegion
    ): Int {
        var count = 0
        for (y in region.top until region.bottom) {
            for (x in region.left until region.right) {
                if (getPixel(x, y).isNear(target, ColorTolerance)) count++
            }
        }
        return count
    }

    private fun waitForInteractionPanelUnderlayRatio(
        scenario: ActivityScenario<SlockKuiklyActivity>,
        instrumentation: Instrumentation,
        visible: Boolean
    ): Double =
        waitForColorRatio(
            scenario = scenario,
            instrumentation = instrumentation,
            target = UnderlayCyan,
            regionDp = PanelRegionDp,
            description = if (visible) "suggestion panel visible" else "suggestion panel dismissed"
        ) { ratio ->
            if (visible) {
                ratio in InteractionPanelVisibleMinUnderlayRatio..InteractionPanelVisibleMaxUnderlayRatio
            } else {
                ratio >= InteractionPanelDismissedMinUnderlayRatio
            }
        }

    private fun captureInteractionFailureEvidence(
        scenario: ActivityScenario<SlockKuiklyActivity>,
        instrumentation: Instrumentation,
        failure: Throwable
    ) {
        val filesDir = instrumentation.targetContext.filesDir
        val prefix = InteractionFailureEvidencePrefix
        runCatching {
            val bitmap = captureActivityBitmap(scenario)
            File(filesDir, "$prefix-root.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
        }.onFailure { evidenceFailure ->
            println("THREAD_COMPOSER_SUGGESTION_EVIDENCE_WARN kind=root error=${evidenceFailure.message}")
        }
        runCatching {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(filesDir, "$prefix-screen.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
        }.onFailure { evidenceFailure ->
            println("THREAD_COMPOSER_SUGGESTION_EVIDENCE_WARN kind=screen error=${evidenceFailure.message}")
        }
        runCatching {
            UiDevice.getInstance(instrumentation).dumpWindowHierarchy(File(filesDir, "$prefix-hierarchy.xml"))
        }.onFailure { evidenceFailure ->
            println("THREAD_COMPOSER_SUGGESTION_EVIDENCE_WARN kind=hierarchy error=${evidenceFailure.message}")
        }
        writeInteractionShellEvidence(instrumentation, "dumpsys window", File(filesDir, "$prefix-window.txt"))
        writeInteractionShellEvidence(instrumentation, "dumpsys activity activities", File(filesDir, "$prefix-activity.txt"))
        writeInteractionShellEvidence(instrumentation, "logcat -d -v threadtime -t 2000", File(filesDir, "$prefix-logcat.txt"))
        runCatching {
            File(filesDir, "$prefix-failure.txt").writeText(
                buildString {
                    appendLine("failure=${failure.javaClass.name}: ${failure.message}")
                    appendLine("composerText=${runCatching { scenario.composerText() }.getOrNull()}")
                    appendLine("composerFocused=${runCatching { scenario.composerHasFocus() }.getOrNull()}")
                    append(failure.stackTraceToString())
                }
            )
        }.onFailure { evidenceFailure ->
            println("THREAD_COMPOSER_SUGGESTION_EVIDENCE_WARN kind=failure error=${evidenceFailure.message}")
        }
        println("THREAD_COMPOSER_SUGGESTION_FAILURE_EVIDENCE prefix=$prefix")
    }

    private fun clearInteractionFailureEvidence(instrumentation: Instrumentation) {
        val filesDir = instrumentation.targetContext.filesDir
        InteractionFailureEvidenceSuffixes.forEach { suffix ->
            File(filesDir, "$InteractionFailureEvidencePrefix-$suffix").delete()
        }
    }

    private fun Instrumentation.confirmImmersiveEducationDismissedForSuggestionTest() {
        executeShellCommandText("settings put secure immersive_mode_confirmations confirmed")
        val current = executeShellCommandText("settings get secure immersive_mode_confirmations").trim()
        check(current == "confirmed") {
            "Failed to suppress the immersive-mode education overlay; current=$current"
        }
    }

    private fun Instrumentation.executeShellCommandText(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { input ->
            input.bufferedReader().readText()
        }

    private fun writeInteractionShellEvidence(
        instrumentation: Instrumentation,
        command: String,
        outputFile: File
    ) {
        runCatching {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { evidenceFailure ->
            println("THREAD_COMPOSER_SUGGESTION_EVIDENCE_WARN command=$command error=${evidenceFailure.message}")
        }
    }

    private fun waitForInteractionActionGreenRatio(
        scenario: ActivityScenario<SlockKuiklyActivity>,
        instrumentation: Instrumentation
    ): Double =
        waitForColorRatio(
            scenario = scenario,
            instrumentation = instrumentation,
            target = InteractionActionGreen,
            regionDp = StatusRegionDp,
            description = "image action marker"
        ) { ratio ->
            ratio >= InteractionActionGreenMinRatio
        }

    private fun waitForColorRatio(
        scenario: ActivityScenario<SlockKuiklyActivity>,
        instrumentation: Instrumentation,
        target: Int,
        regionDp: DpRegion,
        description: String,
        accepted: (Double) -> Boolean
    ): Double {
        val deadline = SystemClock.uptimeMillis() + InteractionVisualWaitMs
        var lastRatio = Double.NaN
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val bitmap = captureActivityBitmap(scenario)
            val region = regionDp.toPixelRegion(instrumentation, bitmap.width, bitmap.height)
            val area = ((region.right - region.left) * (region.bottom - region.top)).coerceAtLeast(1)
            lastRatio = bitmap.countNearColor(target, region).toDouble() / area.toDouble()
            bitmap.recycle()
            if (accepted(lastRatio)) return lastRatio
            SystemClock.sleep(InteractionVisualPollMs)
        }
        throw AssertionError("Timed out waiting for $description; lastColorRatio=$lastRatio")
    }

    private fun Int.isNear(target: Int, tolerance: Int): Boolean =
        abs(Color.red(this) - Color.red(target)) <= tolerance &&
            abs(Color.green(this) - Color.green(target)) <= tolerance &&
            abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private fun DpRegion.toPixelRegion(
        instrumentation: Instrumentation,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): PixelRegion {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        return PixelRegion(
            left = (left * density).toInt().coerceIn(0, bitmapWidth),
            top = (top * density).toInt().coerceIn(0, bitmapHeight),
            right = (right * density).toInt().coerceIn(0, bitmapWidth),
            bottom = (bottom * density).toInt().coerceIn(0, bitmapHeight)
        )
    }

    private fun Rect.toPixelRegion(bitmapWidth: Int, bitmapHeight: Int): PixelRegion =
        PixelRegion(
            left = left.coerceIn(0, bitmapWidth),
            top = top.coerceIn(0, bitmapHeight),
            right = right.coerceIn(0, bitmapWidth),
            bottom = bottom.coerceIn(0, bitmapHeight)
        )

    private fun waitForMovedCandidate(
        device: UiDevice,
        initialBounds: Rect,
        instrumentation: Instrumentation
    ): androidx.test.uiautomator.UiObject2 {
        val deadline = SystemClock.uptimeMillis() + CandidateMoveWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val candidate = device.findObject(By.text(CandidateLabel))
            if (candidate != null && abs(candidate.visibleBounds.centerY() - initialBounds.centerY()) >= MinCandidateMovePx) {
                return candidate
            }
            SystemClock.sleep(CandidateMovePollMs)
        }
        error("Suggestion candidate did not move after the relayout oracle; initial=$initialBounds")
    }

    private fun assertValidCandidateBounds(label: String, bounds: Rect) {
        assertTrue("$label candidate bounds are empty or inverted: $bounds", bounds.left < bounds.right && bounds.top < bounds.bottom)
    }

    private fun ActivityScenario<SlockKuiklyActivity>.composerText(): String? {
        var value: String? = null
        onActivity { activity -> value = activity.window.decorView.findComposerEditText()?.text?.toString() }
        return value
    }

    private fun ActivityScenario<SlockKuiklyActivity>.composerHasFocus(): Boolean {
        var focused = false
        onActivity { activity -> focused = activity.window.decorView.findComposerEditText()?.hasFocus() == true }
        return focused
    }

    private fun ActivityScenario<SlockKuiklyActivity>.composerEditTextBounds(): Rect {
        var bounds: Rect? = null
        onActivity { activity ->
            val editor = requireNotNull(activity.window.decorView.findComposerEditText())
            val location = IntArray(2)
            editor.getLocationOnScreen(location)
            bounds = Rect(location[0], location[1], location[0] + editor.width, location[1] + editor.height)
        }
        return requireNotNull(bounds)
    }

    private fun View.findComposerEditText(): EditText? {
        if (this is EditText) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findComposerEditText()?.let { return it }
            }
        }
        return null
    }

    private fun waitUntil(
        message: String,
        timeoutMs: Long = 12_000L,
        stepMs: Long = 100L,
        condition: () -> Boolean
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(stepMs)
        }
        throw AssertionError(message)
    }

    private fun SlockCmpActivity.hideSystemBarsForSuggestionTest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private data class DpRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)
    private data class PixelRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class LateTypedSuggestionCase(val trigger: String, val candidateLabel: String, val expectedText: String)

    private companion object {
        private const val RenderSettleMs = 2_000L
        private const val GestureSettleMs = 700L
        private const val SwipeDurationMs = 600L
        private const val SwipeSteps = 12
        private const val TapDurationMs = 80L
        private const val CandidateWaitMs = 5_000L
        private const val CandidateMoveWaitMs = 10_000L
        private const val CandidateMovePollMs = 100L
        private const val CandidateTapWaitMs = 3_000L
        private const val PixelDifferenceTolerance = 18
        private const val ColorTolerance = 16
        private const val MinPanelChangedPixels = 8_000
        private const val MinInputChangedPixels = 120
        private const val MinCandidateMovePx = 80
        private const val MaxRepositionErrorPx = 12f
        private const val MinCandidateRelayoutChangedPixels = 600
        private const val MinMovedCandidateUnderlayPixels = 600
        private const val MaxUnderlayPixelIncreaseDuringDrag = 600
        private const val MinUncoveredUnderlayPixels = 20_000
        private const val MaxLeakPixels = 100
        private const val InteractionVisualWaitMs = 8_000L
        private const val InteractionVisualPollMs = 100L
        private const val InteractionPanelVisibleMinUnderlayRatio = 0.05
        private const val InteractionPanelVisibleMaxUnderlayRatio = 0.88
        private const val InteractionPanelDismissedMinUnderlayRatio = 0.92
        private const val InteractionActionGreenMinRatio = 0.30
        private const val InteractionFailureEvidencePrefix = "thread-composer-suggestion-interaction-failure"
        private val InteractionFailureEvidenceSuffixes =
            listOf(
                "root.png",
                "screen.png",
                "hierarchy.xml",
                "window.txt",
                "activity.txt",
                "logcat.txt",
                "failure.txt"
            )
        private val PanelRegionDp = DpRegion(left = 34f, top = 8f, right = 352f, bottom = 216f)
        private val ComposerInputRegionDp = DpRegion(left = 42f, top = 228f, right = 330f, bottom = 278f)
        private val StatusRegionDp = DpRegion(left = 0f, top = 720f, right = 412f, bottom = 915f)
        private val UnderlayCyan = Color.rgb(34, 211, 238)
        private val LeakRed = Color.rgb(239, 68, 68)
        private val InteractionActionGreen = Color.rgb(34, 197, 94)
        private const val RepositionOracleFixture = "member_suggestions_reposition_oracle"
        private const val CandidateLabel = "Scrollable candidate 1"
        private const val RepositionShiftDp = 120f
        private const val InteractionOracleFixture = "suggestion_interaction_hit_test_oracle"
        private const val InteractionImageButtonCenterXDp = 13f
        private const val InteractionToolbarCenterBelowEditorDp = 21f
        private const val InteractionOutsideTapYDp = 20f
    }
}
