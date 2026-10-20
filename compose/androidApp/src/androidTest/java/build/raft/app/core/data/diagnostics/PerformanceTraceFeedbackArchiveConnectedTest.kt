package build.raft.app.core.data.diagnostics

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceTraceFeedbackArchiveConnectedTest {
    @Test
    fun feedbackArchiveIncludesRecentPerformanceTraceWithClockAnchor() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        RaftPerformanceTraceRing.configure(true)
        try {
            RaftPerformanceTraceRing.recordThreadMarker(
                phase = "thread_reconcile_generation",
                metadataFields = mapOf("generation" to "7", "messages" to "3")
            )
            RaftPerformanceTraceRing.recordThreadMarker(
                phase = "composer_send",
                metadataFields =
                    mapOf(
                        "result" to "failed user@example.com Bearer secret-token",
                        "attempt" to "2",
                        "localMessageId" to "local-sensitive-id",
                        "parentMessageId" to "parent-sensitive-id",
                        "body" to "private body"
                    )
            )
            repeat(PerformanceTraceRingCapacity - 2) {
                RaftPerformanceTraceRing.recordThreadMarker(
                    phase = "composer_send",
                    metadataFields =
                        mapOf(
                            "code" to "\u0000".repeat(PerformanceTraceMetadataCharLimit),
                            "errorType" to "\u0000".repeat(PerformanceTraceMetadataCharLimit),
                            "result" to "\u0000".repeat(PerformanceTraceMetadataCharLimit)
                        )
                )
            }

            val archive =
                RaftAndroidLocalDiagnostics.prepareDiagnosticsFeedbackArchive(
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
                assertTrue(jsonl.contains("generation:7"))
                assertTrue(jsonl.contains("messages:3"))
                assertTrue(jsonl.contains("[redacted]"))
                assertTrue(!jsonl.contains("secret-token"))
                assertTrue(!jsonl.contains("local-sensitive-id"))
                assertTrue(!jsonl.contains("parent-sensitive-id"))
                assertTrue(!jsonl.contains("private body"))
                assertTrue(jsonl.toByteArray(StandardCharsets.UTF_8).size <= 1024 * 1024)
            }
            archive.delete()
        } finally {
            RaftPerformanceTraceRing.configure(false)
        }
    }
}
