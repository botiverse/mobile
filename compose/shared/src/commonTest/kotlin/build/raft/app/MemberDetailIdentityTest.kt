package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MemberDetailIdentityTest {
    @Test
    fun humanIdentityDispatchesDirectlyToHumanLookupWithoutAgentProbe() = runBlocking {
        var humanLookups = 0
        var agentLookups = 0
        val identity =
            MemberDetailIdentity(
                memberId = MemberId("human-artin"),
                kind = MemberKind.Human,
                sourceServerId = ServerId("server-1")
            )

        val result =
            identity.dispatchMemberDetailLookup(
                loadHuman = { memberId, serverId ->
                    humanLookups += 1
                    "human:${memberId.value}@${serverId?.value}"
                },
                loadAgent = { memberId ->
                    agentLookups += 1
                    "agent:${memberId.value}"
                },
                unsupported = { memberId, kind -> "unsupported:${kind.name}:${memberId.value}" }
            )

        assertEquals("human:human-artin@server-1", result)
        assertEquals(1, humanLookups)
        assertEquals(0, agentLookups)
    }

    @Test
    fun legacyLaunchWithoutKindDoesNotCreateGuessableIdentity() {
        val payload =
            JSONObject().apply {
                put("memberId", "human-artin")
            }
                .toKuiklyMemberDetailPayload()

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
            listOf("member_detail_identity_rejected source=thread_message_author kind=workflow"),
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
                loadHuman = { _, _ ->
                    humanLookups += 1
                    "human"
                },
                loadAgent = {
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
