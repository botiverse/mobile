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

    private companion object {
        /** 2026-11-16T08:00:30Z: half a minute after the fixtures' `sourceQuiescedAt`. */
        const val NOW = 1_794_816_030_000L
    }

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
        assertEquals(AgentMigrationPackingLine.Progress(files = 1203, bytes = 812 * mib), notice.packingLine(NOW))
        assertEquals("812 MiB", formatAgentMigrationSize(812 * mib))
        assertEquals("1,203", agentMigrationCount(1203))
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

    /*
     * The times on the line (botiverse/slock staging 7a631c0ffe, presentation.ts
     * `migrationSourceStageMessage`), against an injected clock. Payload shape: the owner status of
     * routes/agents.ts `serializeOwnerMigrationStatus` as agents.api.test.ts "gives a draining
     * source the wait's prep window and sourceDrainUntil" reads it: created 14:00, the wait until
     * 14:10 (`sourceDrainUntil`), the prep deadline 14:20; `sourceDrainUntil` null once quiesced.
     */
    private fun stopping(extra: String): AgentMigrationNotice =
        parseSharedAgentMigrationStatus(
            """{"migration":{"migrationRef":"mig_drain","agentId":"agent-1","state":"provisioning","revision":1,""" +
                """"sourceMachineId":"source-machine","targetKind":"computer","targetMachineId":"target-machine",""" +
                """"sourceQuiescedAt":null,"sourceBuildProgress":null,"sourceDrain":null,"transferProgress":null,""" +
                """"prepDeadlineAt":"2026-07-05T14:20:00.000Z","createdAt":"2026-07-05T14:00:00.000Z",$extra}}""",
        )!!.migration!!

    private fun at(minute: Int, second: Int = 0): Long = 1_783_260_000_000L + minute * 60_000L + second * 1_000L

    @Test
    fun aWaitingSourceSaysAtMostHowManyMoreMinutesRoundedUp() {
        val notice = stopping(""""sourceDrainUntil":"2026-07-05T14:10:00.000Z"""")
        assertEquals("2026-07-05T14:10:00.000Z", notice.sourceDrainUntil)
        // Web `Math.max(1, Math.ceil(remainingMs / 60_000))`.
        assertEquals(AgentMigrationStoppingLine.Waiting(10), notice.stoppingLine(at(0)))
        assertEquals(AgentMigrationStoppingLine.Waiting(8), notice.stoppingLine(at(2, 1)))
        assertEquals(AgentMigrationStoppingLine.Waiting(1), notice.stoppingLine(at(9, 59)))
        // At or past the deadline the source stops the agent at once.
        assertEquals(AgentMigrationStoppingLine.Stopping, notice.stoppingLine(at(10)))
        assertEquals(AgentMigrationStoppingLine.Stopping, notice.stoppingLine(at(12)))
    }
}
