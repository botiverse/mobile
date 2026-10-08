package build.raft.app.markdown

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.tencent.kuikly.core.render.android.expand.component.list.KRRecyclerView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the shipped Android renderer, not a reimplementation of its edge policy.
 * The interceptor is internal to the renderer, so reflection is confined to this test.
 * This does not replace an Android app check of the Compose/native wiring.
 */
@RunWith(RobolectricTestRunner::class)
class SlockMermaidNativeScrollOwnershipTest {
    @Test
    fun horizontalParentCannotTakeEitherEdgeOrNarrowDiagram() {
        withDiagram { view, intercept ->
            // A diagram narrower than its viewport is at both edges. The old default
            // yields the gesture to the outer horizontal pager.
            assertFalse(drag(intercept, 100f, 40f))
            assertFalse(drag(intercept, 100f, -40f))
            view.setProp("scrollWithParent", 0)
            assertTrue(drag(intercept, 100f, 40f))
            assertTrue(drag(intercept, 100f, -40f))
        }
    }

    @Test
    fun outsideOrDisabledDiagramDoesNotReserveParentGestures() {
        withDiagram { view, intercept ->
            view.setProp("scrollWithParent", 0)
            assertFalse(drag(intercept, 350f, -40f))
            view.setProp("scrollEnabled", 0)
            assertFalse(drag(intercept, 100f, -40f))
        }
    }

    @Test
    fun horizontalDiagramDoesNotInterceptVerticalMessageDrag() {
        withDiagram { view, _ ->
            view.setProp("scrollWithParent", 0)
            event(MotionEvent.ACTION_DOWN, 100f, 80f) { view.onInterceptTouchEvent(it) }
            assertFalse(event(MotionEvent.ACTION_MOVE, 102f, 140f) { view.onInterceptTouchEvent(it) })
        }
    }

    private fun withDiagram(test: (KRRecyclerView, (MotionEvent) -> Boolean) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = KRRecyclerView(activity).apply {
                setProp("directionRow", 1)
                layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            }
            val root = FrameLayout(activity)
            root.addView(view, FrameLayout.LayoutParams(300, 200))
            activity.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 400)
            val type = Class.forName("com.tencent.kuikly.core.render.android.expand.component.list.NestedHorizontalChildInterceptor")
            val constructor = type.getDeclaredConstructor(KRRecyclerView::class.java).apply { isAccessible = true }
            val interceptor = constructor.newInstance(view)
            val method = type.getDeclaredMethod("onInterceptTouchEvent", MotionEvent::class.java).apply { isAccessible = true }
            test(view) { method.invoke(interceptor, it) as Boolean }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun drag(intercept: (MotionEvent) -> Boolean, x: Float, dx: Float): Boolean {
        event(MotionEvent.ACTION_DOWN, x, 100f, intercept)
        val result = event(MotionEvent.ACTION_MOVE, x + dx, 100f, intercept)
        event(MotionEvent.ACTION_UP, x + dx, 100f, intercept)
        return result
    }

    private fun event(action: Int, x: Float, y: Float, block: (MotionEvent) -> Boolean): Boolean {
        val event = MotionEvent.obtain(0L, if (action == MotionEvent.ACTION_DOWN) 0L else 16L, action, x, y, 0)
        return try { block(event) } finally { event.recycle() }
    }
}
