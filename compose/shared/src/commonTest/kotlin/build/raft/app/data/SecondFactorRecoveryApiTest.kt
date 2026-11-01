package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * GET / DELETE /api/auth/second-factor/recovery against the server's shape, botiverse/slock
 * staging c3e62c2ac990765dd26d72cb3c6450feb54f60f8: services/secondFactor/recovery.ts `viewOf`
 * (RecoveryView, ISO times from `toISOString()`), routes/secondFactorRecovery.api.test.ts (the
 * view's keys `eligibleAt, expiresAt, firstFactorRequired, requestedAt, status`; DELETE
 * `{ cancelled: true, recovery: null }`, again `{ cancelled: false, recovery: null }`; 404 with
 * the flag off).
 */
class SecondFactorRecoveryApiTest {
    private val waiting =
        """{"recovery":{"status":"waiting","requestedAt":"2026-11-01T00:10:00.000Z","eligibleAt":"2026-11-04T00:10:00.000Z","expiresAt":"2026-11-11T00:10:00.000Z","firstFactorRequired":null}}"""

    @Test
    fun parsesTheServersRecoveryView() {
        val recovery = assertIs<DataResult.Content<SecondFactorRecovery?>>(parseSharedSecondFactorRecoveryResponse(200, waiting)).data
        assertEquals(
            SecondFactorRecovery(
                status = SecondFactorRecoveryStatus.Waiting,
                requestedAt = "2026-11-01T00:10:00.000Z",
                eligibleAt = "2026-11-04T00:10:00.000Z",
                expiresAt = "2026-11-11T00:10:00.000Z",
                setPasswordFirst = false,
            ),
            recovery
        )
        val ready =
            """{"recovery":{"status":"ready","requestedAt":"2026-11-01T00:10:00.000Z","eligibleAt":"2026-11-04T00:10:00.000Z","expiresAt":"2026-11-11T00:10:00.000Z","firstFactorRequired":"set_password"}}"""
        val readyRecovery = assertIs<DataResult.Content<SecondFactorRecovery?>>(parseSharedSecondFactorRecoveryResponse(200, ready)).data
        assertEquals(SecondFactorRecoveryStatus.Ready, readyRecovery?.status)
        assertEquals(true, readyRecovery?.setPasswordFirst)
    }

    @Test
    fun noRequestAndTheFlagOffBothShowNothing() {
        assertNull(assertIs<DataResult.Content<SecondFactorRecovery?>>(parseSharedSecondFactorRecoveryResponse(200, """{"recovery":null}""")).data)
        assertNull(assertIs<DataResult.Content<SecondFactorRecovery?>>(parseSharedSecondFactorRecoveryResponse(404, """{"error":"Not found"}""")).data)
    }

    @Test
    fun anythingElseIsAnErrorSoTheShownNoticeStays() {
        for ((code, body) in listOf(
            500 to """{"error":"Internal error"}""",
            200 to "not json",
            // A status this build does not know, or a guessed wrapper.
            200 to """{"recovery":{"status":"pending","requestedAt":"a","eligibleAt":"b","expiresAt":"c","firstFactorRequired":null}}""",
            200 to """{"recovery":{"state":"waiting"}}""",
            200 to """{"data":{"recovery":null}}""",
        )) {
            assertIs<DataResult.Error>(parseSharedSecondFactorRecoveryResponse(code, body), "code=$code body=$body")
        }
    }

    @Test
    fun parsesTheCancelAnswer() {
        assertEquals(true, assertIs<DataResult.Content<Boolean>>(parseSharedSecondFactorRecoveryCancelResponse(200, """{"cancelled":true,"recovery":null}""")).data)
        assertEquals(false, assertIs<DataResult.Content<Boolean>>(parseSharedSecondFactorRecoveryCancelResponse(200, """{"cancelled":false,"recovery":null}""")).data)
        assertIs<DataResult.Error>(parseSharedSecondFactorRecoveryCancelResponse(404, """{"error":"Not found"}"""))
        assertIs<DataResult.Error>(parseSharedSecondFactorRecoveryCancelResponse(200, """{"ok":true}"""))
    }
}
