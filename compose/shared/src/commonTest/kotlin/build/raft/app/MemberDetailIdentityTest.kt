package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MemberDetailIdentityTest {
    @Test
    fun memberKindOwnsRepositoryDispatchAcrossLocalAndRemoteAccessModes() = runBlocking {
        data class Case(
            val kind: MemberKind,
            val sourceServerId: String,
            val expectedRepository: String,
            val expectedAccessMode: MemberDetailAccessMode
        )

        listOf(
            Case(MemberKind.Human, "server-a", "human", MemberDetailAccessMode.LocalFull),
            Case(MemberKind.Human, "server-b", "human", MemberDetailAccessMode.RemoteProfileOnly),
            Case(MemberKind.Agent, "server-a", "agent", MemberDetailAccessMode.LocalFull),
            Case(MemberKind.Agent, "server-b", "agent", MemberDetailAccessMode.RemoteProfileOnly)
        ).forEach { case ->
            var humanLookups = 0
            var agentLookups = 0
            val identity =
                MemberDetailIdentity(
                    memberId = MemberId("member-1"),
                    kind = case.kind,
                    sourceServerId = ServerId(case.sourceServerId)
                )

            val result =
                identity.dispatchMemberDetailLookup(
                    currentServerId = "server-a",
                    loadHuman = { _, _, accessMode ->
                        humanLookups += 1
                        "human:$accessMode"
                    },
                    loadAgent = { _, accessMode ->
                        agentLookups += 1
                        "agent:$accessMode"
                    },
                    unsupported = { _, kind -> "unsupported:${kind.name}" }
                )

            assertEquals("${case.expectedRepository}:${case.expectedAccessMode}", result)
            assertEquals(if (case.expectedRepository == "human") 1 else 0, humanLookups)
            assertEquals(if (case.expectedRepository == "agent") 1 else 0, agentLookups)
        }
    }

    @Test
    fun legacyLaunchWithoutKindDoesNotCreateGuessableIdentity() {
        val payload =
            JSONObject().apply {
                put("memberId", "human-artin")
            }
                .toRaftMemberDetailPayload()

        assertEquals(null, payload.identity)
    }

    @Test
    fun unknownThreadMemberKindCannotBePromotedToHumanIdentity() {
        val diagnostics = mutableListOf<String>()
        assertEquals(null, "".parsedMemberKindOrNull())
        assertEquals(null, "unknown".parsedMemberKindOrNull())
        assertEquals(
            null,
            memberDetailIdentityFromWire(
                memberId = "secret-member-id",
                kindWireValue = "workflow",
                source = MemberDetailIdentitySource.ThreadMessageAuthor,
                diagnostics = diagnostics::add
            )
        )
        assertEquals(
            listOf(
                "projection_value_rejected source=thread_message_author " +
                    "field=member_kind value=workflow",
            ),
            diagnostics
        )
        assertEquals(false, diagnostics.single().contains("secret-member-id"))
    }

    @Test
    fun systemIdentityDispatchesToUnsupportedWithoutHumanOrAgentLookup() = runBlocking {
        var humanLookups = 0
        var agentLookups = 0
        var unsupportedLookups = 0
        val identity =
            MemberDetailIdentity(
                memberId = MemberId("system-1"),
                kind = "system".parsedMemberKindOrNull()!!
            )

        val result =
            identity.dispatchMemberDetailLookup(
                currentServerId = "server-1",
                loadHuman = { _, _, _ ->
                    humanLookups += 1
                    "human"
                },
                loadAgent = { _, _ ->
                    agentLookups += 1
                    "agent"
                },
                unsupported = { _, kind ->
                    unsupportedLookups += 1
                    "unsupported:${kind.name}"
                }
            )

        assertEquals("unsupported:System", result)
        assertEquals(0, humanLookups)
        assertEquals(0, agentLookups)
        assertEquals(1, unsupportedLookups)
    }
}
