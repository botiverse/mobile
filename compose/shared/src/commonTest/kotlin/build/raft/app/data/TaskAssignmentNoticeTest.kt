package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A task's assignment line (`actionMetadata.kind: "task-assignment"`), read as botiverse/slock
 * #15631 (`4635757f2`) reads it: `packages/shared/src/taskAssignmentNotice.ts`, the cases of
 * `packages/web/tests/taskHandoffNotice.test.ts`, and the shape `buildTaskAssignmentNotice` /
 * `buildTaskReleaseNoticeMetadata` (`packages/server/src/services/taskConversationNotice.ts`)
 * write.
 */
class TaskAssignmentNoticeTest {
    private val otter = TaskAssignmentNoticeParty(TaskAssignmentPartyType.Agent, "agent-otter", "otter", "Otter")
    private val xx = TaskAssignmentNoticeParty(TaskAssignmentPartyType.User, "user-xx", "xxchan", "xxchan")
    private val bob = TaskAssignmentNoticeParty(TaskAssignmentPartyType.User, "user-bob", "bob", "Bob")

    private fun partyJson(party: TaskAssignmentNoticeParty?): String =
        party?.let { """{"type":"${it.type.wireValue}","id":"${it.id}","name":"${it.name}","displayName":"${it.displayName}"}""" } ?: "null"

    private fun json(
        actor: TaskAssignmentNoticeParty,
        assignee: TaskAssignmentNoticeParty?,
        reason: String? = null,
        includePreviousAssignee: Boolean = false,
        previousAssignee: TaskAssignmentNoticeParty? = null,
        rawPreviousAssignee: String? = null,
    ): String {
        val prevField =
            when {
                rawPreviousAssignee != null -> ""","previousAssignee":$rawPreviousAssignee"""
                includePreviousAssignee -> ""","previousAssignee":${partyJson(previousAssignee)}"""
                else -> ""
            }
        val reasonField = reason?.let { ""","reason":"$it"""" }.orEmpty()
        return """{"kind":"task-assignment","taskNumber":51,"actor":${partyJson(actor)},"assignee":${partyJson(assignee)}$prevField$reasonField}"""
    }

    private fun read(raw: String): TaskAssignmentNotice? = readTaskAssignmentNotice(Json.parseToJsonElement(raw))

    /** The line as the App projects it from a message's `actionMetadata` and reads it back where it draws the line. */
    private fun projected(raw: String, coerceInputValues: Boolean = true): TaskAssignmentNotice? {
        val wire =
            Json {
                ignoreUnknownKeys = true
                this.coerceInputValues = coerceInputValues
            }.decodeFromString(WireActionCardMetadata.serializer(), raw)
        return taskAssignmentNoticeOf(wire.toMessageItemMetadata())
    }

    @Test
    fun aHandOffToSomeoneElseWithOrWithoutAReasonReadsAsAHandOffCard() {
        val withReason = json(otter, xx, "Your call: Friday or Monday?")
        assertEquals("Your call: Friday or Monday?", read(withReason).asTaskHandoff()?.reason)
        val withoutReason = read(json(otter, xx)).asTaskHandoff()
        assertEquals(xx, withoutReason?.assignee, "no reason: still a hand-off")
        assertNull(withoutReason?.reason)
        assertNull(read(json(otter, otter)).asTaskHandoff(), "taking it yourself")
        assertNull(read(json(otter, otter, "mine")).asTaskHandoff(), "taking it yourself, with a reason")
        assertNull(read(json(otter, null)).asTaskHandoff(), "clearing the assignee")
        val blankReason = read(json(otter, xx, "   ")).asTaskHandoff()
        assertEquals(xx, blankReason?.assignee, "blank reason is still a hand-off without reason")
        assertNull(blankReason?.reason, "a blank reason is none")
        for (raw in listOf(
            withReason,
            json(otter, xx),
            json(otter, null),
            json(otter, xx, "Bob is out", includePreviousAssignee = true, previousAssignee = bob),
            json(otter, xx, "First pass", includePreviousAssignee = true, previousAssignee = null),
            json(otter, null, includePreviousAssignee = true, previousAssignee = xx),
        )) {
            assertEquals(read(raw), projected(raw, coerceInputValues = true), "coerce=true: $raw")
            assertEquals(read(raw), projected(raw, coerceInputValues = false), "coerce=false: $raw")
        }
    }

    @Test
    fun previousAssigneeDistinguishesPartyExplicitNullAbsentAndMalformed() {
        val withParty = checkNotNull(read(json(otter, xx, "Bob is out", includePreviousAssignee = true, previousAssignee = bob)))
        assertEquals(bob, withParty.previousAssignee)
        assertTrue(withParty.previousAssigneeRecorded)

        val explicitNull = checkNotNull(read(json(otter, xx, includePreviousAssignee = true, previousAssignee = null)))
        assertNull(explicitNull.previousAssignee)
        assertTrue(explicitNull.previousAssigneeRecorded, "explicit null stays apart from absent")

        val absent = checkNotNull(read(json(otter, xx)))
        assertNull(absent.previousAssignee)
        assertFalse(absent.previousAssigneeRecorded, "older line without previousAssignee")

        val malformed = checkNotNull(read(json(otter, xx, rawPreviousAssignee = """{"type":"bot","id":"x"}""")))
        assertNull(malformed.previousAssignee)
        assertFalse(malformed.previousAssigneeRecorded, "malformed previousAssignee falls back to unrecorded")
        assertEquals(malformed, projected(json(otter, xx, rawPreviousAssignee = """"not-a-party""""), coerceInputValues = true))
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
    fun aHandOffGoesBackWhenLatestLineGivingTaskToActorWasMadeByNewAssignee() {
        fun line(id: String, notice: TaskAssignmentNotice?) = TaskAssignmentTimelineLine(id, notice)
        val first = line("h1", TaskAssignmentNotice(51, xx, otter, "Can you draft it?"))
        val status = line("s1", null)
        val back = line("h2", TaskAssignmentNotice(51, otter, xx, "Draft is up"))
        val timeline = taskHandoffTimeline(listOf(first, status, back))
        assertEquals(false, timeline["h1"]?.back, "nobody gave Otter the task before")
        assertEquals(true, timeline["h2"]?.back, "back to xxchan, who gave it to Otter")

        val onward = line("h3", TaskAssignmentNotice(51, otter, bob, "Bob reviews"))
        assertEquals(false, taskHandoffTimeline(listOf(first, onward))["h3"]?.back, "on to someone new")

        // Otter later took it themselves: the latest line giving it to Otter is Otter's own.
        val took = line("t1", TaskAssignmentNotice(51, otter, otter))
        val afterTake = line("h4", TaskAssignmentNotice(51, otter, xx, "Done"))
        assertEquals(false, taskHandoffTimeline(listOf(first, took, afterTake))["h4"]?.back, "Otter took it, nobody handed it over")
    }

    @Test
    fun previousHolderUsesRecordedPreviousAssigneeElseLatestEarlierAssignmentLine() {
        fun line(id: String, notice: TaskAssignmentNotice) = TaskAssignmentTimelineLine(id, notice)
        val first = line("h1", TaskAssignmentNotice(51, otter, xx, "Your call"))
        val reassigned = line("h2", TaskAssignmentNotice(51, otter, bob, "Bob instead"))
        // Older lines carry no previousAssignee: xxchan held it, from the line before.
        val olderTimeline = taskHandoffTimeline(listOf(first, reassigned))
        assertNull(olderTimeline["h1"]?.previous, "nobody held it before the first line here")
        assertEquals(xx, olderTimeline["h2"]?.previous)

        // A line that records nobody held it is not read from the line before (a release left no holder).
        val none = line("h3", TaskAssignmentNotice(51, otter, bob, "Bob instead", previousAssignee = null, previousAssigneeRecorded = true))
        assertNull(taskHandoffTimeline(listOf(first, none))["h3"]?.previous)

        val released = line("r1", TaskAssignmentNotice(51, xx, null, previousAssignee = xx, previousAssigneeRecorded = true))
        val again = line("h4", TaskAssignmentNotice(51, otter, xx, "Back to you"))
        val afterRelease = taskHandoffTimeline(listOf(first, released, again))
        assertNull(afterRelease["h4"]?.previous, "released: nobody held it")

        // The line's own record works even without an earlier line in the timeline.
        val recorded = line("h5", TaskAssignmentNotice(51, otter, bob, "Bob instead", previousAssignee = xx))
        assertEquals(xx, taskHandoffTimeline(listOf(recorded))["h5"]?.previous)
    }

    @Test
    fun theProjectedKeysStayOutOfTheLinesDetailsAndStayBackwardCompatible() {
        val metadata =
            checkNotNull(read(json(otter, xx, "Your call", includePreviousAssignee = true, previousAssignee = bob)))
                .toMessageMetadata()
        assertTrue(metadata.keys.all(::isTaskAssignmentNoticeMetadataKey))
        assertTrue(metadata.keys.any { it.startsWith("taskAssignment.assignee") })
        assertTrue(metadata.keys.any { it.startsWith("taskAssignment.previousAssignee") })

        val explicitNullMetadata =
            checkNotNull(read(json(otter, xx, includePreviousAssignee = true, previousAssignee = null)))
                .toMessageMetadata()
        assertTrue(explicitNullMetadata.keys.all(::isTaskAssignmentNoticeMetadataKey))
        val roundTrippedNull = checkNotNull(taskAssignmentNoticeOf(explicitNullMetadata))
        assertNull(roundTrippedNull.previousAssignee)
        assertTrue(roundTrippedNull.previousAssigneeRecorded)

        // Cached metadata stored by older builds (before previousAssignee existed) decodes cleanly as unrecorded.
        val legacyMetadata =
            mapOf(
                "taskAssignment.taskNumber" to "51",
                "taskAssignment.actorType" to "agent",
                "taskAssignment.actorId" to "agent-otter",
                "taskAssignment.actorName" to "otter",
                "taskAssignment.actorDisplayName" to "Otter",
                "taskAssignment.assigneeType" to "user",
                "taskAssignment.assigneeId" to "user-xx",
                "taskAssignment.assigneeName" to "xxchan",
                "taskAssignment.assigneeDisplayName" to "xxchan",
                "taskAssignment.reason" to "Your call",
            )
        val legacyNotice = checkNotNull(taskAssignmentNoticeOf(legacyMetadata))
        assertEquals(TaskAssignmentNotice(51, otter, xx, "Your call", previousAssignee = null, previousAssigneeRecorded = false), legacyNotice)
        assertFalse(checkNotNull(read(json(otter, null))).toMessageMetadata().keys.any { it.startsWith("taskAssignment.assignee") }, "cleared: no assignee keys")
    }
}
