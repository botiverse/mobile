package build.raft.app

import build.raft.app.data.AgentCreatorSummary
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageType
import build.raft.app.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrictProjectionParsersTest {

    @Test
    fun projectedMemberKindConsumerFailsClosedWithoutDiagnostics() {
        assertEquals(MemberKind.Human, "human".projectedMemberKindOrNull())
        assertEquals(MemberKind.Agent, "agent".projectedMemberKindOrNull())
        assertEquals(MemberKind.System, "system".projectedMemberKindOrNull())
        assertEquals(null, "user".projectedMemberKindOrNull())
        assertEquals(null, "future-kind".projectedMemberKindOrNull())
    }

    @Test
    fun memberKindParserKeepsWireDialectsDisjoint() {
        val identityExpected =
            mapOf(
                "agent" to MemberKind.Agent,
                "human" to MemberKind.Human,
                "user" to MemberKind.Human,
                "member" to MemberKind.Human,
                "system" to MemberKind.System,
            )

        identityExpected.forEach { (wire, kind) ->
            assertEquals(kind, wire.parsedMemberKindOrNull(), wire)
            assertEquals(kind, "  ${wire.uppercase()}  ".parsedMemberKindOrNull(), wire)
        }
        listOf(null, "", "bot", "administrator").forEach { wire ->
            assertNull(wire.parsedMemberKindOrNull(), wire)
        }

        assertEquals(
            MemberKind.Human,
            "user".parsedMemberKindOrNull(MemberKindWireContract.InternalActor),
        )
        assertEquals(
            MemberKind.Agent,
            "agent".parsedMemberKindOrNull(MemberKindWireContract.InternalActor),
        )
        listOf("human", "member", "system").forEach { wire ->
            assertNull(wire.parsedMemberKindOrNull(MemberKindWireContract.InternalActor), wire)
        }

        assertEquals(
            MemberKind.System,
            "system".parsedMemberKindOrNull(MemberKindWireContract.InboxActor),
        )
        assertEquals(
            MemberKind.Human,
            "user".parsedMemberKindOrNull(MemberKindWireContract.InboxActor),
        )
        assertEquals(
            MemberKind.Agent,
            "agent".parsedMemberKindOrNull(MemberKindWireContract.InboxActor),
        )
        listOf("human", "member").forEach { wire ->
            assertNull(wire.parsedMemberKindOrNull(MemberKindWireContract.InboxActor), wire)
        }

        assertEquals(
            MemberKind.Human,
            "human".parsedMemberKindOrNull(MemberKindWireContract.AgentCreator),
        )
        assertEquals(
            MemberKind.Agent,
            "agent".parsedMemberKindOrNull(MemberKindWireContract.AgentCreator),
        )
        listOf("user", "member", "system").forEach { wire ->
            assertNull(wire.parsedMemberKindOrNull(MemberKindWireContract.AgentCreator), wire)
        }

        assertEquals(
            MemberKind.Human,
            "human".parsedMemberKindOrNull(MemberKindWireContract.Projected),
        )
        assertEquals(
            MemberKind.Agent,
            "agent".parsedMemberKindOrNull(MemberKindWireContract.Projected),
        )
        assertEquals(
            MemberKind.System,
            "system".parsedMemberKindOrNull(MemberKindWireContract.Projected),
        )
        listOf("user", "member", "external").forEach { wire ->
            assertNull(wire.parsedMemberKindOrNull(MemberKindWireContract.Projected), wire)
        }

        assertEquals(
            MemberKind.Human,
            "human".parsedMemberKindOrNull(MemberKindWireContract.ProjectedMessageAuthor),
        )
        assertEquals(
            MemberKind.Agent,
            "agent".parsedMemberKindOrNull(MemberKindWireContract.ProjectedMessageAuthor),
        )
        assertEquals(
            MemberKind.System,
            "system".parsedMemberKindOrNull(MemberKindWireContract.ProjectedMessageAuthor),
        )
        assertEquals(
            MemberKind.External,
            "external".parsedMemberKindOrNull(MemberKindWireContract.ProjectedMessageAuthor),
        )
        listOf("user", "member", "unknown").forEach { wire ->
            assertNull(wire.parsedMemberKindOrNull(MemberKindWireContract.ProjectedMessageAuthor), wire)
        }
    }

    @Test
    fun messageTypeParserAcceptsOnlyDeclaredWireValues() {
        assertEquals(MessageType.Chat, "chat".parsedMessageTypeOrNull())
        assertEquals(MessageType.System, " SYSTEM ".parsedMessageTypeOrNull())
        listOf(null, "", "message", "notification").forEach { wire ->
            assertNull(wire.parsedMessageTypeOrNull(), wire)
        }
    }

    @Test
    fun taskStatusParserAcceptsOnlyCanonicalStatusValues() {
        val expected =
            mapOf(
                "todo" to TaskStatus.Todo,
                "in_progress" to TaskStatus.InProgress,
                "in_review" to TaskStatus.InReview,
                "done" to TaskStatus.Done,
                "closed" to TaskStatus.Closed,
            )

        expected.forEach { (wire, status) ->
            assertEquals(wire, status.toWireValue(), status.name)
            assertEquals(status, wire.parsedTaskStatusOrNull(), wire)
            assertEquals(status, "  ${wire.uppercase()}  ".parsedTaskStatusOrNull(), wire)
        }
        listOf(null, "", "in-review", "pending", "complete").forEach { wire ->
            assertNull(wire.parsedTaskStatusOrNull(), wire)
        }
    }

    @Test
    fun strictParserEmitsBoundedSanitizedDiagnosticForRejectedValue() {
        val logs = mutableListOf<String>()

        val result = "future/agent?kind".strictMemberKindOrNull("REST Message", logs::add)

        assertNull(result)
        assertEquals(
            listOf(
                "projection_value_rejected source=rest_message field=member_kind " +
                    "value=future_agent_kind",
            ),
            logs,
        )
    }

    @Test
    fun strictParserBoundsTokensAndDoesNotReceivePayloadContent() {
        val logs = mutableListOf<String>()
        val longUnknownValue = "x".repeat(80)

        longUnknownValue.strictMessageTypeOrNull("realtime-message", logs::add)

        val diagnostic = logs.single()
        assertEquals(32, diagnostic.substringAfter("value=").length)
        assertTrue("secret-message-id" !in diagnostic)
        assertTrue("private body" !in diagnostic)
    }

    @Test
    fun projectedThreadConsumersDoNotInventHumanProfilesOrAvatarKinds() {
        val unknownAuthor =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "member-1",
                authorName = "Unknown",
                authorKind = "member",
            )
        val timeline = KuiklyThreadScreenPayload(messages = listOf(unknownAuthor)).toThreadTimelineSlice()

        assertNull(timeline.findThreadAuthorProfile("member-1"))
        assertNull(unknownAuthor.resolvedThreadAvatarWireValue())

        val humanAuthor = unknownAuthor.copy(authorKind = "human")
        val humanProfile =
            assertNotNull(
                KuiklyThreadScreenPayload(messages = listOf(humanAuthor))
                    .toThreadTimelineSlice()
                    .findThreadAuthorProfile("member-1"),
            )
        assertEquals(MemberKind.Human, humanProfile.kind)
    }

    @Test
    fun agentCreatorConsumerUsesInternalActorContract() {
        val diagnostics = mutableListOf<String>()
        val unknown =
            AgentCreatorSummary(
                type = "user",
                id = "creator-1",
                name = "Creator",
            ).toStrictMemberProfile(diagnostics::add)

        assertNull(unknown)
        assertEquals(
            listOf(
                "projection_value_rejected source=agent_creator field=member_kind value=user",
            ),
            diagnostics,
        )
        assertEquals(
            MemberKind.Human,
            assertNotNull(
                AgentCreatorSummary(
                    type = "human",
                    id = "creator-1",
                    name = "Creator",
                ).toStrictMemberProfile(),
            ).kind,
        )
    }
}
