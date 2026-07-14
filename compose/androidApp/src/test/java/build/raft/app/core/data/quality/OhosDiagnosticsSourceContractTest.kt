package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OhosDiagnosticsSourceContractTest {
    @Test
    fun productionCallsitesCannotBypassAsyncDiagnosticsAdmission() {
        val root = findRepoRoot()
        val sourceRoot = File(root, "compose/ohosApp/entry/src/main/ets")
        val diagnosticsFile = File(sourceRoot, "kuikly/SlockOhosDiagnostics.ets")
        val offenders =
            sourceRoot
                .walkTopDown()
                .filter { file -> file.isFile && file.extension == "ets" && file != diagnosticsFile }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (line.contains("SlockOhosDiagnostics.recordPrepared(")) {
                            "${file.relativeTo(root).path}:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }
                .toList()

        assertTrue(
            "Production OHOS callers must use record()/recordAsync()/recordPreparedAsync(); " +
                "synchronous prepared commit is internal to the single diagnostics owner.\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
        val diagnosticsSource = diagnosticsFile.readText()
        assertTrue(
            "record() must remain an async compatibility facade for production callsites",
            diagnosticsSource.contains(
                "SlockOhosDiagnostics.recordAsync(tag, message, timestampMillis, level);"
            )
        )
        assertTrue(diagnosticsSource.contains("private static recordPrepared("))
    }

    @Test
    fun incompleteSnapshotUsesNonStreamSentinelSequence() {
        val root = findRepoRoot()
        val diagnostics =
            File(root, "compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosDiagnostics.ets")
                .readText()
        assertTrue(diagnostics.contains("event: 'diagnostics_snapshot_incomplete'"))
        assertTrue(diagnostics.contains("seq: -1"))
    }

    @Test
    fun canonicalCriticalBitCrossesKmpPayloadAndHostAdmission() {
        val root = findRepoRoot()
        val producer =
            File(root, "compose/shared/src/ohosArm64Main/kotlin/build/raft/app/SlockPlatformLog.ohos.kt")
                .readText()
        val entryAbility =
            File(root, "compose/ohosApp/entry/src/main/ets/entryability/EntryAbility.ets").readText()
        val diagnostics =
            File(root, "compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosDiagnostics.ets").readText()
        val queue =
            File(root, "compose/ohosApp/entry/src/main/ets/kuikly/SlockOhosDiagnosticsQueue.ets")
                .readText()

        assertTrue(producer.contains("put(\"critical\", entry.critical)"))
        assertTrue(producer.contains("critical = hint == SlockPlatformLogHint.AuthCritical"))
        assertTrue(producer.contains("fun recordBatch(payload: String): Boolean"))
        assertTrue(producer.contains("}.getOrDefault(false)"))
        assertTrue(producer.contains("fun producerState(pending: Boolean, healthy: Boolean)"))
        assertTrue(entryAbility.contains("recordBatch(payload: string): boolean"))
        assertTrue(entryAbility.contains("admitSlockOhosPreparedBatch("))
        assertTrue(queue.contains("admit(entries[index])"))
        assertTrue(diagnostics.contains("static recordPreparedAsync(entry: SlockOhosPreparedDiagnosticEntry): boolean"))
        assertTrue(entryAbility.contains("SlockOhosDiagnostics.setUpstreamState(pending, healthy)"))
        assertTrue(diagnostics.contains("critical: normalizedLevel === 'error' || entry.critical === true"))
        assertTrue(diagnostics.contains("SlockOhosDiagnostics.upstreamPending ? 1 : 0"))
        assertTrue(diagnostics.contains("SlockOhosDiagnostics.upstreamHealthy ? 0 : 1"))
        assertTrue(queue.contains("const normalQueueFull = !work.critical"))
    }

    @Test
    fun diagnosticsOperationsFlushAcceptedUpstreamLogsBeforeHostBarrier() {
        val root = findRepoRoot()
        val commonLog =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/SlockPlatformLog.kt").readText()
        val ohosLog =
            File(root, "compose/shared/src/ohosArm64Main/kotlin/build/raft/app/SlockPlatformLog.ohos.kt")
                .readText()
        val bridgeAuth =
            File(root, "compose/shared/src/commonMain/kotlin/build/raft/app/data/BridgeAuth.kt").readText()

        assertTrue(commonLog.contains("private const val NormalCapacity = 496"))
        assertTrue(commonLog.contains("private const val AuthCriticalCapacity = 16"))
        assertTrue(commonLog.contains("internal suspend fun flushSlockPlatformLogs()"))
        assertTrue(commonLog.contains("internal expect suspend fun flushSlockPlatformDiagnosticsDownstream(): Boolean"))
        assertTrue(ohosLog.contains("internal actual suspend fun flushSlockPlatformDiagnosticsDownstream(): Boolean"))
        assertTrue(ohosLog.contains("flushBarrier.completeIfIdle(hasPending)"))
        assertTrue(bridgeAuth.contains("diagnosticsSettingsCapability(\"loadDiagnostics\")"))
        assertTrue(bridgeAuth.contains("diagnosticsSettingsCapability(\"copyDiagnostics\")"))
        assertTrue(bridgeAuth.contains("diagnosticsSettingsCapability(\"shareDiagnostics\")"))
        assertTrue(bridgeAuth.contains("diagnosticsSettingsCapability(\"clearDiagnostics\")"))
        assertTrue(bridgeAuth.contains("diagnosticsSettingsCapability(\"sendFeedback\")"))
        assertDiagnosticsHelperFlushesBeforeHostCall(bridgeAuth)
        assertSubmitFeedbackFlushesBeforeHostCall(bridgeAuth)
    }

    private fun assertDiagnosticsHelperFlushesBeforeHostCall(source: String) {
        val start = source.indexOf("private suspend fun diagnosticsSettingsCapability")
        assertTrue("Missing diagnostics settings helper", start >= 0)
        val window = source.substring(start, minOf(source.length, start + 600))
        val commonFlush = window.indexOf("flushSlockPlatformLogs()")
        val downstreamFlush = window.indexOf("flushSlockPlatformDiagnosticsDownstream()")
        val hostCall = window.indexOf("settingsCapability(command)")
        assertTrue(commonFlush >= 0 && downstreamFlush > commonFlush && hostCall > downstreamFlush)
    }

    private fun assertSubmitFeedbackFlushesBeforeHostCall(source: String) {
        val start = source.indexOf("suspend fun submitQuiverFeedback")
        assertTrue("Missing feedback submit method", start >= 0)
        val window = source.substring(start, minOf(source.length, start + 2_000))
        val commonFlush = window.indexOf("flushSlockPlatformLogs()")
        val downstreamFlush = window.indexOf("flushSlockPlatformDiagnosticsDownstream()")
        val hostCall = window.indexOf("\"action\", \"submitFeedback\"")
        assertTrue(commonFlush >= 0 && downstreamFlush > commonFlush && hostCall > downstreamFlush)
        assertTrue(window.contains("settings_diagnostics_flush_failed"))
    }

    private fun findRepoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).canonicalFile
        repeat(8) {
            if (File(current, "compose/settings.gradle.kts").isFile) {
                return current
            }
            current = current.parentFile ?: return@repeat
        }
        assertEquals("Unable to locate repository root from $userDir", true, false)
        error("unreachable")
    }
}
