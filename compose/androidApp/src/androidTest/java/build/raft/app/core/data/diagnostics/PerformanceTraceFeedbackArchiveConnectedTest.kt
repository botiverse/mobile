package build.raft.app.core.data.diagnostics

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceTraceFeedbackArchiveConnectedTest {
    @Test
    fun feedbackArchiveIncludesRecentPerformanceTraceWithClockAnchor() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SlockPerformanceTraceRing.configure(true)
        try {
            SlockPerformanceTraceRing.recordThreadMarker(
                phase = "thread_reconcile_generation",
                metadata = "generation=7 messages=3"
            )

            val archive =
                SlockAndroidLocalDiagnostics.prepareDiagnosticsFeedbackArchive(
                    context = context,
                    summaryText = "performance trace connected test"
                )

            ZipFile(archive).use { zip ->
                val entry = checkNotNull(zip.getEntry("performance/recent-15s.jsonl"))
                val jsonl = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                assertTrue(jsonl.contains("\"category\":\"clock\""))
                assertTrue(jsonl.contains("\"elapsedRealtimeNanos\""))
                assertTrue(jsonl.contains("\"wallTimeMillis\""))
                assertTrue(jsonl.contains("\"name\":\"thread_reconcile_generation\""))
                assertTrue(jsonl.contains("generation=7 messages=3"))
            }
            archive.delete()
        } finally {
            SlockPerformanceTraceRing.configure(false)
        }
    }
}
