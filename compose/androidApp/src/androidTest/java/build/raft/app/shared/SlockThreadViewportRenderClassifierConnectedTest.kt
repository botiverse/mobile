package build.raft.app.shared

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.R
import build.raft.app.ThreadNewerAutoPaginationVisualFixtureProbe
import build.raft.app.threadNewerAutoMessageLabel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlockThreadViewportRenderClassifierConnectedTest {
    @Test
    fun productionCaptureSeparatesWindowFromLocalDrawAndCensusesRealViewHierarchy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runNonce = UUID.randomUUID().toString().replace("-", "")
        val intent =
            Intent(instrumentation.targetContext, SlockKuiklyActivity::class.java)
                .putExtra(SlockKuiklyActivity.KEY_PAGE_NAME, PageName)
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)
                .putExtra(
                    SlockKuiklyActivity.KEY_PAGE_DATA,
                    JSONObject()
                        .put("runNonce", runNonce)
                        .put("showComposer", false)
                        .toString(),
                )

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            waitUntil(RenderTimeoutMs, "real Thread fixture rows") {
                runCatching {
                    ThreadNewerAutoPaginationVisualFixtureProbe.snapshot(runNonce)
                        .initialRequestCount >= 1 &&
                        (1L..16L).any { sequence ->
                            instrumentation.uiAutomation.rootInActiveWindow
                                ?.findAccessibilityNodeInfosByText(
                                    threadNewerAutoMessageLabel(sequence)
                                )
                                ?.isNotEmpty() == true
                        }
                }.getOrDefault(false)
            }
            val productionTreeResultRef = AtomicReference<ThreadViewportRenderCaptureResult?>()
            val productionTreeLatch = CountDownLatch(1)
            scenario.onActivity { activity ->
                val container =
                    requireNotNull(activity.findViewById<ViewGroup?>(R.id.slock_kuikly_container))
                captureThreadViewportRenderEvidence(
                    window = activity.window,
                    container = container,
                    request =
                        ThreadViewportCaptureRequest(
                            left = 0,
                            top = 0,
                            width = container.width,
                            height = container.height,
                            generation = ProductionTreeGeneration,
                        ),
                ) { result ->
                    productionTreeResultRef.set(result)
                    productionTreeLatch.countDown()
                }
            }
            assertTrue(
                "timed out waiting for the real Kuikly host hierarchy capture",
                productionTreeLatch.await(CaptureTimeoutSeconds, TimeUnit.SECONDS),
            )
            val productionTreeCapture = productionTreeResultRef.get()
            assertTrue(
                "real Kuikly host hierarchy capture failed: $productionTreeCapture",
                productionTreeCapture is ThreadViewportRenderCaptureResult.Success,
            )
            val productionTreeEvidence =
                (productionTreeCapture as ThreadViewportRenderCaptureResult.Success).evidence
            assertEquals(ProductionTreeGeneration, productionTreeEvidence.generation)
            assertEquals("window_pixel_copy_and_local_draw", productionTreeEvidence.captureMode)
            assertTrue(
                "real Thread evidence must compare one stable host geometry",
                productionTreeEvidence.geometryStable,
            )
            assertTrue(
                "real Kuikly host hierarchy must expose attached native descendants",
                productionTreeEvidence.descendants.attachedDescendants > 0,
            )
            assertTrue(
                "real Kuikly host hierarchy must expose viewport intersections",
                productionTreeEvidence.descendants.viewportIntersectingDescendants > 0,
            )
            assertTrue(
                "real Thread fixture must materialize viewport-intersecting leaves",
                productionTreeEvidence.descendants.viewportIntersectingLeafDescendants > 0,
            )
            assertEquals(
                "ready Thread fixture should be the healthy classifier control",
                ThreadViewportRenderStage.HEALTHY_CONTROL,
                productionTreeEvidence.stage,
            )

            val resultRef = AtomicReference<ThreadViewportRenderCaptureResult?>()
            val resultLatch = CountDownLatch(1)
            scenario.onActivity { activity ->
                val overlay = HardwareWhiteSoftwareBlueViewGroup(activity)
                overlay.addView(
                    View(activity).apply { setBackgroundColor(Color.TRANSPARENT) },
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                activity.addContentView(
                    overlay,
                    FrameLayout.LayoutParams(ProbeWidthPx, ProbeHeightPx).apply {
                        gravity = Gravity.TOP or Gravity.START
                        leftMargin = ProbeMarginPx
                        topMargin = ProbeMarginPx
                    },
                )
                overlay.post {
                    assertTrue("connected classifier requires an attached overlay", overlay.isAttachedToWindow)
                    assertTrue("connected classifier requires hardware Window drawing", overlay.isHardwareAccelerated)
                    captureThreadViewportRenderEvidence(
                        window = activity.window,
                        container = overlay,
                        request =
                            ThreadViewportCaptureRequest(
                                left = 0,
                                top = 0,
                                width = ProbeWidthPx,
                                height = ProbeHeightPx,
                                generation = ProbeGeneration,
                            ),
                    ) { result ->
                        resultRef.set(result)
                        resultLatch.countDown()
                    }
                }
            }

            assertTrue(
                "timed out waiting for production Window/local render evidence",
                resultLatch.await(CaptureTimeoutSeconds, TimeUnit.SECONDS),
            )
            val capture = resultRef.get()
            assertNotNull("production capture returned no result", capture)
            assertTrue(
                "production capture failed: $capture",
                capture is ThreadViewportRenderCaptureResult.Success,
            )
            val evidence = (capture as ThreadViewportRenderCaptureResult.Success).evidence
            assertEquals(ProbeGeneration, evidence.generation)
            assertEquals("window_pixel_copy_and_local_draw", evidence.captureMode)
            assertEquals("window_then_local", evidence.captureOrder)
            assertTrue("synthetic control geometry must stay stable", evidence.geometryStable)
            assertEquals(ProbeWidthPx, evidence.sourceRectInWindow.width())
            assertEquals(ProbeHeightPx, evidence.sourceRectInWindow.height())
            assertTrue("Window hardware branch must remain blank-like", evidence.windowPixels.isBlankLike)
            assertTrue("local software draw must contain the blue control", !evidence.localPixels.isBlankLike)
            assertTrue(
                "real descendant census must find the attached viewport leaf",
                evidence.descendants.viewportIntersectingLeafDescendants >= 1,
            )
            assertTrue(
                "real descendant census must expose a safe class/identity summary",
                evidence.descendants.intersectingSummary.contains("View@"),
            )
            assertEquals(
                ThreadViewportRenderStage.WINDOW_OR_SURFACE_PRESENTATION,
                evidence.stage,
            )

            val receipt =
                "THREAD_VIEWPORT_RENDER_CLASSIFIER_PASS " +
                    "stage=${evidence.stage.wireValue} " +
                    "windowWhite=${evidence.windowPixels.whitePermille} " +
                    "localWhite=${evidence.localPixels.whitePermille} " +
                    "intersectingLeaves=${evidence.descendants.viewportIntersectingLeafDescendants} " +
                    "productionStage=${productionTreeEvidence.stage.wireValue} " +
                    "productionWindowWhite=${productionTreeEvidence.windowPixels.whitePermille} " +
                    "productionLocalWhite=${productionTreeEvidence.localPixels.whitePermille} " +
                    "productionIntersections=${productionTreeEvidence.descendants.viewportIntersectingDescendants} " +
                    "productionLeaves=${productionTreeEvidence.descendants.viewportIntersectingLeafDescendants}"
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("threadViewportRenderClassifier", receipt) },
            )
            println(receipt)
        }
    }

    private fun waitUntil(timeoutMs: Long, description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(PollMs)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private class HardwareWhiteSoftwareBlueViewGroup(context: Context) : ViewGroup(context) {
        init {
            setWillNotDraw(false)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(width, height)
            for (index in 0 until childCount) {
                getChildAt(index).measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (index in 0 until childCount) {
                getChildAt(index).layout(0, 0, right - left, bottom - top)
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(
                if (canvas.isHardwareAccelerated) {
                    Color.WHITE
                } else {
                    Color.rgb(24, 96, 216)
                }
            )
        }
    }

    private companion object {
        const val PageName = "compose_thread_newer_auto_pagination_visual"
        const val ProbeWidthPx = 256
        const val ProbeHeightPx = 192
        const val ProbeMarginPx = 32
        const val ProbeGeneration = 10_101L
        const val ProductionTreeGeneration = 10_100L
        const val RenderTimeoutMs = 20_000L
        const val PollMs = 100L
        const val CaptureTimeoutSeconds = 10L
    }
}
