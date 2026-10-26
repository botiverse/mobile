package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.JOINT_INVITE_RATE_LIMITED
import build.raft.app.data.ScreenError
import build.raft.app.data.jointInviteRateLimitMinutes
import build.raft.app.data.parseSharedJointErrorDetails
import build.raft.app.data.toJointDataError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `joint_invite_rate_limited` (429) from `POST channels` (joint) and
 * `POST channels/:id/joint-invites`. Pending server PR: the payload is copied from botiverse/slock
 * branch `agent/joint-invite-rate-limit` at 4ab05c6e3 (`routes/channels.ts`
 * `admitJointInviteOrRefuse`), not yet on `staging`; re-check against the merged route.
 */
class JointInviteRateLimitPendingServerPrTest {
    private val upstreamBody =
        """{"error":"Too many joint channel invites. Try again later.","code":"joint_invite_rate_limited","retryAfterSeconds":61}"""

    private fun rateLimited(body: String?, headers: Map<String, List<String>> = emptyMap()): ScreenError =
        (HomeNetworkResponse(code = 429, bodyText = null, errorBodyText = body, responseHeaders = headers).toJointDataError<Unit>() as DataResult.Error).error

    @Test
    fun theWaitComesFromTheBodyThenTheRetryAfterHeader() {
        val fromBody = rateLimited(upstreamBody, mapOf("Retry-After" to listOf("3600")))
        assertEquals(JOINT_INVITE_RATE_LIMITED, fromBody.code)
        assertEquals("Too many joint channel invites. Try again later.", fromBody.serverMessage)
        assertEquals(61L, fromBody.retryAfterSeconds)

        val withoutField = """{"error":"Too many joint channel invites. Try again later.","code":"joint_invite_rate_limited"}"""
        assertEquals(120L, rateLimited(withoutField, mapOf("retry-after" to listOf(" 120 "))).retryAfterSeconds)
        // An HTTP-date or nothing at all: no wait to name.
        assertNull(rateLimited(withoutField, mapOf("Retry-After" to listOf("Fri, 23 Oct 2026 10:00:00 GMT"))).retryAfterSeconds)
        assertNull(rateLimited(withoutField).retryAfterSeconds)
        // Only a 429 carries a wait.
        val conflict = HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = upstreamBody).toJointDataError<Unit>() as DataResult.Error
        assertNull(conflict.error.retryAfterSeconds)
        assertEquals(JOINT_INVITE_RATE_LIMITED, parseSharedJointErrorDetails(upstreamBody)?.code)
    }

    @Test
    fun theWaitIsWholeMinutesRoundedUpAndAtLeastOne() {
        assertEquals(
            listOf(1, 1, 1, 2, 59, 60),
            listOf(0L, 1L, 60L, 61L, 3540L, 3600L).map { jointInviteRateLimitMinutes(it) },
        )
        assertNull(jointInviteRateLimitMinutes(null))
    }

    @Test
    fun createAndInviteServerFailuresSayHowLongToWait() {
        val createCopy =
            CreateJointErrorCopy(
                nameInvalid = "name",
                slugRequired = "slug",
                peopleRequired = "people",
                sameAdminConfirmRequired = "confirm",
                freeServerLimit = "free",
                serverLimit = "max",
                freeHostLimit = { "host:$it" },
                freeHostLimitUnnamed = "host",
                inviteeNotFound = { _, _ -> "notfound" },
                inviteeNotAdmin = { _, _ -> "notadmin" },
                serverNotFound = { "noserver" },
                cannotInviteSelf = "self",
                failedCreate = "failed",
                inviteRateLimited = { "wait:$it" },
            )
        fun create(error: ScreenError) =
            createJointChannelErrorText(
                CreateJointChannelError.Server(DataResult.Error(error), parseSharedJointErrorDetails(upstreamBody)),
                createCopy,
                currentServerSlug = "host-co",
            ) { "pipeline" }
        assertEquals("wait:2", create(rateLimited(upstreamBody)))
        assertEquals("wait:null", create(ScreenError(JOINT_INVITE_RATE_LIMITED)))

        val inviteCopy = JointFailureCopy(freeServerLimit = "free", serverLimit = "max", hostFreeLimit = "host", fallback = "fallback", inviteRateLimited = { "wait:$it" })
        fun invite(error: ScreenError) = jointChannelFailureText(JointChannelFailure.Error(error), inviteCopy) { "pipeline" }
        assertEquals("wait:59", invite(ScreenError(JOINT_INVITE_RATE_LIMITED, retryAfterSeconds = 3540)))
        assertEquals("wait:null", invite(ScreenError(JOINT_INVITE_RATE_LIMITED)))
        // The banner, not a field: the whole invite is refused.
        assertEquals(JointInviteField.Form, jointInviteFailureField(JOINT_INVITE_RATE_LIMITED))
    }
}
