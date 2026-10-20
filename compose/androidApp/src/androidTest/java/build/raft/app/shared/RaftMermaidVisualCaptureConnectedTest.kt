package build.raft.app.shared

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class RaftMermaidVisualCaptureConnectedTest {

    private fun captureCase(name: String, mermaidCode: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val outputDir = File("/sdcard/Download/mermaid-visual").apply { mkdirs() }

        val mdContent = "### Diagram: $name\n\n```mermaid\n$mermaidCode\n```\n\nFooter note."
        val intent = Intent(context, RaftCmpActivity::class.java)
            .putExtra("pageName", RaftPages.MARKDOWN_RERENDER_VISUAL)
            .putExtra(
                "pageData",
                JSONObject()
                    .put("instanceId", "mermaid-visual-$name")
                    .put("rawMarkdown", mdContent)
                    .toString()
            )
            .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<RaftCmpActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            SystemClock.sleep(1200) // allow kuikly canvas to measure, layout and draw
            instrumentation.waitForIdleSync()

            var bitmap: Bitmap? = null
            scenario.onActivity { activity ->
                val root = activity.window.decorView.rootView
                if (root.width > 0 && root.height > 0) {
                    val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bmp))
                    bitmap = bmp
                }
            }

            bitmap?.let { bmp ->
                val file = File(outputDir, "mermaid_$name.png")
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bmp.recycle()
                println("CAPTURED_MERMAID_VISUAL: ${file.absolutePath}")
            }
        }
    }

    @Test
    fun captureFlowchart() {
        captureCase(
            name = "flowchart",
            mermaidCode = """
                graph TD
                  A[Start] --> B[Process]
                  B --> C[End]
            """.trimIndent()
        )
    }

    @Test
    fun captureSequence() {
        captureCase(
            name = "sequence",
            mermaidCode = """
                sequenceDiagram
                  Alice->>Bob: Hello Bob!
                  Bob-->>Alice: Hi Alice!
            """.trimIndent()
        )
    }

    @Test
    fun capturePie() {
        captureCase(
            name = "pie",
            mermaidCode = """
                pie showData title Pets adopted
                  "Dogs" : 386
                  "Cats" : 85
                  "Rats" : 15
            """.trimIndent()
        )
    }

    @Test
    fun captureFallback() {
        captureCase(
            name = "fallback_invalid",
            mermaidCode = """
                graph INVALID_SYNTAX
                  ??? --- !!!
            """.trimIndent()
        )
    }
}
