package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A task's assignment line (`actionMetadata.kind: "task-assignment"`), read as botiverse/slock
 * v1.21.64 (0d4ec9fd) reads it: `packages/shared/src/taskAssignmentNotice.ts`, the cases of
 * `packages/web/tests/taskHandoffNotice.test.ts`, and the shape `buildTaskAssignmentNotice`
 * (`packages/server/src/services/taskConversationNotice.ts`) writes.
 */
class TaskAssignmentNoticeTest {
    private val otter = TaskAssignmentNoticeParty(TaskAssignmentPartyType.Agent, "agent-otter", "otter", "Otter")
    private val xx = TaskAssignmentNoticeParty(TaskAssignmentPartyType.User, "user-xx", "xxchan", "xxchan")
    private val bob = TaskAssignmentNoticeParty(TaskAssignmentPartyType.User, "user-bob", "bob", "Bob")

    private fun partyJson(party: TaskAssignmentNoticeParty?): String =
        party?.let { """{"type":"${it.type.wireValue}","id":"${it.id}","name":"${it.name}","displayName":"${it.displayName}"}""" } ?: "null"

    private fun json(actor: TaskAssignmentNoticeParty, assignee: TaskAssignmentNoticeParty?, reason: String? = null): String =
        """{"kind":"task-assignment","taskNumber":51,"actor":${partyJson(actor)},"assignee":${partyJson(assignee)}${reason?.let { ",\"reason\":\"$it\"" } ?: ""}}"""

    private fun read(raw: String): TaskAssignmentNotice? = readTaskAssignmentNotice(Json.parseToJsonElement(raw))

    /** The line as the App projects it from a message's `actionMetadata` and reads it back where it draws the line. */
    private fun projected(raw: String): TaskAssignmentNotice? {
        val wire = Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), raw)
        return taskAssignmentNoticeOf(wire.toMessageItemMetadata())
    }

    @Test
    fun onlyAHandOffToSomeoneElseWithAReasonReadsAsAHandOffCard() {
        val handoff = json(otter, xx, "Your call: Friday or Monday?")
        assertEquals("Your call: Friday or Monday?", read(handoff).asTaskHandoff()?.reason)
        assertNull(read(json(otter, xx)).asTaskHandoff(), "no reason: the one-line notice")
        assertNull(read(json(otter, otter, "mine")).asTaskHandoff(), "taking it yourself")
        assertNull(read(json(otter, null)).asTaskHandoff(), "clearing the assignee")
        assertNull(read(json(otter, xx, "   ")).asTaskHandoff(), "a blank reason is none")
        for (raw in listOf(handoff, json(otter, xx), json(otter, null))) assertEquals(read(raw), projected(raw), raw)
    }

    @Test
    fun theReadIsTheSharedOneRuleForRule() {
        val notice = checkNotNull(read(json(otter, xx, "Your call")))
        assertEquals(TaskAssignmentNotice(51, otter, xx, "Your call"), notice)
        // A party without a handle or display name keeps nulls (empty strings are none).
        assertEquals(
            TaskAssignmentNoticeParty(TaskAssignmentPartyType.User, "u1", null, null),
            read("""{"kind":"task-assignment","taskNumber":3,"actor":{"type":"user","id":"u1","name":"","displayName":null},"assignee":null}""")?.actor,
        )
        listOf(
            """{"kind":"channel-announcement","taskNumber":51,"actor":${partyJson(otter)},"assignee":null}""",
            """{"kind":"task-assignment","taskNumber":"51","actor":${partyJson(otter)},"assignee":null}""",
            """{"kind":"task-assignment","actor":${partyJson(otter)},"assignee":null}""",
            """{"kind":"task-assignment","taskNumber":51,"actor":{"type":"bot","id":"x"},"assignee":null}""",
            """{"kind":"task-assignment","taskNumber":51,"actor":{"type":"user","id":""},"assignee":null}""",
            """{"kind":"task-assignment","taskNumber":51,"actor":${partyJson(otter)},"assignee":{"type":"user"}}""",
            // An absent assignee is not a cleared one (`assignee === null`).
            """{"kind":"task-assignment","taskNumber":51,"actor":${partyJson(otter)}}""",
            "null",
            "[]",
        ).forEach { assertNull(read(it), it) }
    }

    @Test
    fun aHandOffComesBackWhenAssignedBackToItsActorOrItsActorTakesIt() {
        fun line(id: String, at: String, notice: TaskAssignmentNotice?) = TaskAssignmentTimelineLine(id, at, notice)
        val handoff = line("h1", "2026-11-10T08:00:00.000Z", TaskAssignmentNotice(51, otter, xx, "Your call"))
        val status = line("s1", "2026-11-10T08:00:00.000Z", null)
        val back = line("b1", "2026-11-10T09:30:00.000Z", TaskAssignmentNotice(51, xx, otter))
        assertEquals(mapOf("h1" to TaskHandoffReturn("2026-11-10T09:30:00.000Z", xx)), taskHandoffReturns(listOf(handoff, status, back)), "xxchan handed it back")

        val second = line("h2", "2026-11-10T10:00:00.000Z", TaskAssignmentNotice(51, otter, xx, "Review the copy"))
        val took = line("t2", "2026-11-10T11:00:00.000Z", TaskAssignmentNotice(51, otter, otter))
        assertEquals(TaskHandoffReturn("2026-11-10T11:00:00.000Z", otter), taskHandoffReturns(listOf(second, took))["h2"], "Otter took it back")
    }

    @Test
    fun aHandOffPassedOnIsStillWaitingUntilItReachesItsActor() {
        fun line(id: String, at: String, notice: TaskAssignmentNotice) = TaskAssignmentTimelineLine(id, at, notice)
        val handoff = line("h1", "2026-11-10T08:00:00.000Z", TaskAssignmentNotice(51, otter, xx, "Your call"))
        val passedOn = line("p1", "2026-11-10T08:00:00.000Z", TaskAssignmentNotice(51, xx, bob, "Bob knows the numbers"))
        assertTrue(taskHandoffReturns(listOf(handoff, passedOn)).isEmpty())
        val backToXx = line("x1", "2026-11-10T12:00:00.000Z", TaskAssignmentNotice(51, bob, xx, "Done, numbers attached"))
        val all = taskHandoffReturns(listOf(handoff, passedOn, backToXx))
        assertEquals("2026-11-10T12:00:00.000Z", all["p1"]?.at, "Bob gave it back to xxchan")
        assertFalse("h1" in all, "Otter's hand-off is still waiting")
    }

    @Test
    fun theProjectedKeysStayOutOfTheLinesDetails() {
        val metadata = checkNotNull(read(json(otter, xx, "Your call"))).toMessageMetadata()
        assertTrue(metadata.keys.all(::isTaskAssignmentNoticeMetadataKey))
        assertTrue(metadata.keys.any { it.startsWith("taskAssignment.assignee") })
        assertFalse(checkNotNull(read(json(otter, null))).toMessageMetadata().keys.any { it.startsWith("taskAssignment.assignee") }, "cleared: no assignee keys")
    }
}
