package build.raft.app

import build.raft.app.data.ServerJoinAgreement
import build.raft.app.data.ServerJoinInviteInfo
import build.raft.app.data.ServerJoinRefusal
import build.raft.app.data.parseSharedServerJoinRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerJoinRefusalTest {
    private val sampleInfo =
        ServerJoinInviteInfo(
            kind = "join_link",
            serverName = "Acme",
            memberCount = 3,
            agentCount = 1,
        )

    private val sampleTraceId = "62ac7fa79a3785a6bee414a074f82634"

    @Test
    fun acceptRefusalWithAgreementReturnsReadyWithUpdatedAgreement() {
        val agreement = ServerJoinAgreement(id = "agr-2", title = "Rules", bodyMarkdown = "Be nice", version = 2)
        val view =
            resolveServerJoinAcceptRefusal(
                info = sampleInfo,
                refusal =
                    ServerJoinRefusal(
                        status = 409,
                        error = "agreement_changed",
                        agreement = agreement,
                        traceId = sampleTraceId,
                    ),
            )

        assertEquals(ServerJoinView.Ready(sampleInfo.copy(agreement = agreement), joining = false), view)
    }

    @Test
    fun acceptRefusalMapsKnownLimitCodesAndRateLimit() {
        val humanSeat =
            resolveServerJoinAcceptRefusal(
                sampleInfo,
                ServerJoinRefusal(
                    status = 409,
                    error = "Seat limit reached (4/4).",
                    code = "human_seat_limit_reached",
                    traceId = sampleTraceId,
                ),
            )
        assertEquals(
            ServerJoinView.Failed(ServerJoinFailure.HumanSeatLimit, message = null, traceId = sampleTraceId),
            humanSeat,
        )

        val deploymentLimit =
            resolveServerJoinAcceptRefusal(
                sampleInfo,
                ServerJoinRefusal(
                    status = 403,
                    error = "Deployment user limit reached.",
                    code = "deployment_user_limit_reached",
                    traceId = sampleTraceId,
                ),
            )
        assertEquals(
            ServerJoinView.Failed(ServerJoinFailure.DeploymentUserLimit, message = null, traceId = sampleTraceId),
            deploymentLimit,
        )

        val rateLimited =
            resolveServerJoinAcceptRefusal(
                sampleInfo,
                ServerJoinRefusal(
                    status = 429,
                    error = "Slow down",
                    traceId = sampleTraceId,
                ),
            )
        assertEquals(
            ServerJoinView.Failed(ServerJoinFailure.TooManyAttempts, message = null, traceId = sampleTraceId),
            rateLimited,
        )
    }

    @Test
    fun acceptRefusalSurfacesServerWordsAcross4xxStatusesNotOnly400() {
        val forbidden =
            resolveServerJoinAcceptRefusal(
                sampleInfo,
                ServerJoinRefusal(
                    status = 403,
                    error = "Only members with verified email addresses can join this server.",
                    code = "email_not_verified",
                    traceId = sampleTraceId,
                ),
            )
        assertEquals(
            ServerJoinView.Failed(
                fallback = ServerJoinFailure.FailedToAccept,
                message = "Only members with verified email addresses can join this server.",
                traceId = sampleTraceId,
            ),
            forbidden,
        )

        val conflict =
            resolveServerJoinAcceptRefusal(
                sampleInfo,
                ServerJoinRefusal(
                    status = 409,
                    error = "This invite has already been used.",
                    traceId = sampleTraceId,
                ),
            )
        assertEquals(
            ServerJoinView.Failed(
                fallback = ServerJoinFailure.FailedToAccept,
                message = "This invite has already been used.",
                traceId = sampleTraceId,
            ),
            conflict,
        )
    }

    @Test
    fun acceptAndInfoRefusalsScrubEdgeBlockedHtml401And5xx() {
        val scrubbed =
            listOf(
                ServerJoinRefusal(status = 403, error = "edge_blocked", traceId = sampleTraceId),
                ServerJoinRefusal(status = 403, code = "edge_blocked", error = "Blocked by WAF", traceId = sampleTraceId),
                ServerJoinRefusal(status = 401, error = "jwt expired", traceId = sampleTraceId),
                ServerJoinRefusal(status = 500, error = "database connection pool exhausted", traceId = sampleTraceId),
                ServerJoinRefusal(status = 400, code = "internal_error", error = "panic", traceId = sampleTraceId),
                ServerJoinRefusal(status = 403, error = "<!DOCTYPE html><html>Denied</html>", traceId = sampleTraceId),
                ServerJoinRefusal(status = 0, error = "Connection reset by peer", traceId = sampleTraceId),
            )
        for (refusal in scrubbed) {
            val acceptView = resolveServerJoinAcceptRefusal(sampleInfo, refusal) as ServerJoinView.Failed
            assertEquals(ServerJoinFailure.FailedToAccept, acceptView.fallback)
            assertNull(acceptView.message, "Expected scrubbed accept message for $refusal")
            assertEquals(sampleTraceId, acceptView.traceId)

            val infoView = resolveServerJoinInfoRefusal(refusal)
            assertEquals(ServerJoinFailure.FailedToLoad, infoView.fallback)
            assertNull(infoView.message, "Expected scrubbed info message for $refusal")
            assertEquals(sampleTraceId, infoView.traceId)
        }
    }

    @Test
    fun infoRefusalMaps404And429AndSurfacesOther4xxServerWords() {
        assertEquals(
            ServerJoinView.Failed(ServerJoinFailure.InvalidOrExpired, message = null, traceId = sampleTraceId),
            resolveServerJoinInfoRefusal(ServerJoinRefusal(status = 404, error = "Not found", traceId = sampleTraceId)),
        )
        assertEquals(
            ServerJoinView.Failed(ServerJoinFailure.TooManyAttempts, message = null, traceId = sampleTraceId),
            resolveServerJoinInfoRefusal(ServerJoinRefusal(status = 429, error = "Too many requests", traceId = sampleTraceId)),
        )
        assertEquals(
            ServerJoinView.Failed(
                fallback = ServerJoinFailure.FailedToLoad,
                message = "Invite link was revoked by a workspace admin.",
                traceId = sampleTraceId,
            ),
            resolveServerJoinInfoRefusal(
                ServerJoinRefusal(
                    status = 403,
                    error = "Invite link was revoked by a workspace admin.",
                    traceId = sampleTraceId,
                ),
            ),
        )
    }

    @Test
    fun parseSharedServerJoinRefusalFallsBackToErrorCodeWhenCodeIsAbsent() {
        val refusal =
            parseSharedServerJoinRefusal(
                status = 403,
                bodyText = """{"error":"Deployment user limit reached.","errorCode":"deployment_user_limit_reached"}""",
                traceId = sampleTraceId,
            )
        assertEquals("deployment_user_limit_reached", refusal.code)
        assertEquals(
            ServerJoinView.Failed(ServerJoinFailure.DeploymentUserLimit, message = null, traceId = sampleTraceId),
            resolveServerJoinAcceptRefusal(sampleInfo, refusal),
        )
    }
}
