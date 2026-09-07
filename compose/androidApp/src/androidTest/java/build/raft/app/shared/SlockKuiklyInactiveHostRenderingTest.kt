package build.raft.app.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.R
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlockKuiklyInactiveHostRenderingTest {
    @Test
    fun inactiveRetainedHostDoesNotPaintOverTheActiveSibling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val stack = FrameLayout(context)
            val activeContent = View(context).apply { setBackgroundColor(Color.MAGENTA) }
            val retainedHost = SlockKuiklyPageRenderView(
                context,
                object : KuiklyRenderViewBaseDelegatorDelegate {}
            )
            stack.addView(activeContent, FrameLayout.LayoutParams(100, 100))
            stack.addView(retainedHost, FrameLayout.LayoutParams(100, 100))
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            fun centerPixel(): Int {
                val exact = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
                stack.measure(exact, exact)
                stack.layout(0, 0, 100, 100)
                stack.draw(Canvas(bitmap))
                return bitmap.getPixel(50, 50)
            }
            try {
                // Positive control: the same host genuinely paints while active.
                assertEquals(ContextCompat.getColor(context, R.color.slock_surface_cream), centerPixel())
                retainedHost.setProp("active", false)
                assertEquals("Inactive host must expose the active sibling's pixels", Color.MAGENTA, centerPixel())
                assertEquals(View.INVISIBLE, retainedHost.visibility)
                assertEquals("INVISIBLE preserves prewarm layout", 100, retainedHost.height)
                retainedHost.setProp("active", true)
                assertEquals(ContextCompat.getColor(context, R.color.slock_surface_cream), centerPixel())
                retainedHost.setProp("active", false)
                assertEquals(Color.MAGENTA, centerPixel())
            } finally {
                retainedHost.onDestroy()
                bitmap.recycle()
            }
        }
    }
}
