package build.raft.app

import build.raft.app.data.AgentMigrationNotice
import build.raft.app.data.parseSharedAgentMigrationStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sizes on the status line while a move runs (botiverse/slock staging 6935917a0f, web
 * presentation.ts `migrationSourceStageMessage` / `migrationDownloadStageMessage`): what the
 * source packed so far (`sourceBuildProgress`) and how much of the compressed bundle the target
 * downloaded (`transferProgress`), read from the owner status as routes/agents.ts sends them.
 */
class AgentMigrationStageProgressTest {
    private val mib = 1024L * 1024L

    private fun status(state: String, extra: String): AgentMigrationNotice =
        parseSharedAgentMigrationStatus(
            """{"migration":{"migrationRef":"mig_sizes","agentId":"agent-1","state":"$state","revision":4,""" +
                """"sourceMachineId":"source-machine","targetKind":"computer","targetMachineId":"target-machine",""" +
                """"sourceQuiescedAt":"2026-11-16T08:00:00.000Z",$extra}}""",
        )!!.migration!!

    @Test
    fun packingReadsTheFilesAndBytesTheSourceReported() {
        val notice =
            status(
                "provisioning",
                """"sourceBuildProgress":{"phase":"packing","files":1203,"bytes":851443712,"reportedAt":"2026-11-16T08:01:00.000Z"},"transferProgress":null""",
            )
        assertEquals(AgentMigrationPackingLine.Progress(files = 1203, bytes = 812 * mib), notice.packingLine())
        assertEquals("812 MiB", formatAgentMigrationSize(812 * mib))
        assertEquals("1,203", agentMigrationCount(1203))
    }

    @Test
    fun packingWithNothingReportedYetKeepsThePlainLine() {
        assertEquals(AgentMigrationPackingLine.Plain, status("provisioning", """"sourceBuildProgress":null""").packingLine())
        // Web: shown only once `files > 0 || bytes > 0`.
        assertEquals(
            AgentMigrationPackingLine.Plain,
            status("provisioning", """"sourceBuildProgress":{"phase":"scanning","files":0,"bytes":0,"reportedAt":"2026-11-16T08:00:01.000Z"}""").packingLine(),
        )
        // Bytes before any file counts (a first large file).
        assertEquals(
            AgentMigrationPackingLine.Progress(files = 0, bytes = 4096),
            status("provisioning", """"sourceBuildProgress":{"phase":"packing","files":0,"bytes":4096,"reportedAt":"2026-11-16T08:00:01.000Z"}""").packingLine(),
        )
    }

    @Test
    fun downloadingReadsTheCompressedBundleShareFlooredUnderOneHundred() {
        val notice =
            status(
                "ready",
                """"transferProgress":{"receivedBytes":125829120,"totalBytes":335544320,"receivedChunks":15,"totalChunks":40}""",
            )
        // 120 MiB of 320 MiB is 37.5%: floored.
        assertEquals(AgentMigrationDownloadLine.Downloading(percent = 37, receivedBytes = 120 * mib, totalBytes = 320 * mib), notice.downloadLine())
        assertEquals("320 MiB", formatAgentMigrationSize(320 * mib))
        // Every byte in but a chunk still unconfirmed: 99, never 100.
        val almost = status("ready", """"transferProgress":{"receivedBytes":1000,"totalBytes":1000,"receivedChunks":3,"totalChunks":4}""")
        assertEquals(AgentMigrationDownloadLine.Downloading(percent = 99, receivedBytes = 1000, totalBytes = 1000), almost.downloadLine())
    }

    @Test
    fun downloadWithoutProgressSaysReadyAndOnceAllChunksAreInSaysUnpacking() {
        assertEquals(AgentMigrationDownloadLine.Ready, status("ready", """"transferProgress":null""").downloadLine())
        assertEquals(
            AgentMigrationDownloadLine.Ready,
            status("ready", """"transferProgress":{"receivedBytes":0,"totalBytes":0,"receivedChunks":0,"totalChunks":0}""").downloadLine(),
        )
        assertEquals(
            AgentMigrationDownloadLine.Unpacking,
            status("ready", """"transferProgress":{"receivedBytes":2048,"totalBytes":2048,"receivedChunks":2,"totalChunks":2}""").downloadLine(),
        )
    }
}
