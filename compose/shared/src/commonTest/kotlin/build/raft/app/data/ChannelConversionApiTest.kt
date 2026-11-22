package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.RealtimeRefreshRequired
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A channel's conversion as the server sends it (botiverse/slock origin/staging 23ae82825f:
 * channelConversionService.ts `conversionJobView`, `conversionCommandView` and
 * `attachLatestChannelConversionJobs`, which puts `conversionJob` (active only),
 * `conversionCommand` and `conversionState` on every channel row; routes/channels.ts
 * `GET /channels/conversion-jobs/:jobId` → `{ channel, conversionJob }` and the `channel:updated`
 * `{ channel }` of `emitChannelConversionState`), read as web `channelConversionState` and
 * `conversionResponseState` read it.
 */
class ChannelConversionApiTest {
    private fun job(
        status: String = "running",
        phase: String = "move_parent_messages",
        canCancel: Boolean = false,
        error: String? = null,
        progress: String = "{}",
    ) = """{"id":"job-1","status":"$status","phase":"$phase","canCancel":$canCancel,"blocksSending":true,"progress":$progress,"error":${error?.let { "\"$it\"" } ?: "null"}}"""

    private fun command(kind: String = "start", status: String = "completed", jobId: String? = "job-1") =
        """{"id":"8d2c0f8e-3c6b-4d0e-9a51-0a8f3e7c1b2d","kind":"$kind","status":"$status","jobId":${jobId?.let { "\"$it\"" } ?: "null"},"error":null,"createdAt":"2026-11-19T09:00:00.000Z"}"""

    private fun state(status: String, command: String?, job: String?) =
        """{"status":"$status","command":${command ?: "null"},"job":${job ?: "null"}}"""

    private fun row(fields: String) = """{"id":"ch-ops","serverId":"srv-1","name":"ops","type":"channel","archivedAt":null,$fields}"""

    @Test
    fun aChannelRowReadsItsConversionStateWithTheProgressTheStripUses() {
        val running =
            job(
                canCancel = true,
                progress = """{"canonicalCopyStarted":true,"uploadCount":2,"errorCode":"channel_conversion_uploads_in_flight","failedAt":"2026-11-19T09:01:00.000Z","rollbackState":"none"}""",
            )
        val conversion =
            parseSharedChannelConversionRead(
                row(""""conversionJob":$running,"conversionCommand":${command()},"conversionState":${state("running", command(), running)}"""),
            )
        assertEquals(
            RaftChannelConversion(
                commandPending = false,
                job =
                    RaftChannelConversionJob(
                        id = "job-1",
                        status = RaftChannelConversionJobStatus.Running,
                        phase = "move_parent_messages",
                        canCancel = true,
                        canonicalCopyStarted = true,
                        uploadCount = 2,
                        errorCode = "channel_conversion_uploads_in_flight",
                    ),
            ),
            conversion,
        )
    }

    @Test
    fun theStateWinsOverTheOlderFieldsWhichKeepOnlyAnActiveJob() {
        // A job that ended: the older `conversionJob` is null, the state still names it, so the strip knows it is done.
        val done = job(status = "done", phase = "done")
        val conversion = parseSharedChannelConversionRead(row(""""conversionJob":null,"conversionCommand":${command()},"conversionState":${state("done", command(), done)}"""))
        assertEquals(RaftChannelConversionJobStatus.Done, conversion?.job?.status)
    }

    @Test
    fun withoutTheStateTheOlderFieldsAreProjectedAsTheServerWould() {
        val conversion =
            parseSharedChannelConversionRead(row(""""conversionJob":${job(status = "failed", error = "Verification failed")},"conversionCommand":${command(kind = "retry", status = "pending")}"""))
        assertEquals(true, conversion?.commandPending)
        assertEquals(RaftChannelConversionCommandKind.Retry, conversion?.pendingCommandKind)
        assertEquals("Verification failed", conversion?.job?.error)
    }

    @Test
    fun aCommandAdmittedBeforeItsJobExistsIsPending() {
        val pendingStart = command(status = "pending", jobId = null)
        val conversion = parseSharedChannelConversionRead(row(""""conversionJob":null,"conversionCommand":$pendingStart,"conversionState":${state("pending", pendingStart, null)}"""))
        assertEquals(RaftChannelConversion(commandPending = true, pendingCommandKind = RaftChannelConversionCommandKind.Start, job = null), conversion)
    }

    @Test
    fun aRowWithoutTheConversionFieldsSaysNothingAboutIt() {
        val bare = Json.parseToJsonElement(row(""""description":"Ops"""")) as JsonObject
        assertNull(parseRaftChannelConversionRow(bare), "an edit's or archive's row is no news of the conversion")
        assertEquals(RaftChannelConversion.None, parseSharedChannelConversionRead(row(""""description":"Ops"""")))
        assertNull(parseSharedChannelConversionRead("not json"))
    }

    @Test
    fun theJobAnswerReadsTheChannelsStateElseTheAnswersJob() {
        val verify = job(phase = "verify")
        val withChannel =
            parseSharedChannelConversionJobAnswer(
                """{"channel":${row(""""conversionState":${state("running", command(), verify)}""")},"conversionJob":${job(phase = "move_parent_messages")}}""",
            )
        assertEquals("verify", withChannel?.job?.phase)
        // The source channel is gone (`channel: null`): the answer's own job.
        val withoutChannel = parseSharedChannelConversionJobAnswer("""{"channel":null,"conversionJob":${job(status = "canceled", phase = "prepare")}}""")
        assertEquals(RaftChannelConversionJobStatus.Canceled, withoutChannel?.job?.status)
    }

    @Test
    fun theWorkersChannelUpdatedRowCarriesTheConversionToTheOpenPage() {
        val verify = job(phase = "verify")
        val frame = """{"channel":${row(""""conversionJob":$verify,"conversionCommand":${command()},"conversionState":${state("running", command(), verify)}""")}}"""
        val event = assertIs<RealtimeRefreshRequired>(RealtimeEventMapper().map(RealtimeSocketEvents.ChannelUpdated, frame))
        assertEquals(ChannelId("ch-ops"), event.channelId)
        assertEquals("verify", event.channelConversion?.job?.phase)
        assertFalse(event.channelConversion?.commandPending ?: true)

        val archived = assertIs<RealtimeRefreshRequired>(RealtimeEventMapper().map(RealtimeSocketEvents.ChannelUpdated, """{"channel":${row(""""description":"Ops"""")}}"""))
        assertNull(archived.channelConversion)
        val idOnly = assertIs<RealtimeRefreshRequired>(RealtimeEventMapper().map(RealtimeSocketEvents.ChannelUpdated, """{"channelId":"ch-ops"}"""))
        assertNull(idOnly.channelConversion)
        assertTrue(idOnly.channelId == ChannelId("ch-ops"))
    }
}
