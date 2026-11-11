package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * GET /api/auth/second-factor/status against the server's shape, botiverse/slock staging
 * a26a8ea487bd19e196e04684f0183f71c379cc5e: routes/secondFactor.ts (`GET /status`, 404 for every
 * route while `auth_second_factor_v1` is off), services/secondFactor/management.ts
 * `getSecondFactorStatus` / `getSessionSecondFactorStatus`; the bodies are the ones
 * routes/secondFactor.api.test.ts and routes/secondFactorPasskeys.api.test.ts assert.
 */
class AccountLoginSecurityApiTest {
    private val traceId = "0af7651916cd43dd8448eb211c80319c"

    @Test
    fun parsesTheServersStatusBodies() {
        for ((body, expected) in listOf(
            // secondFactor.api.test.ts: off, nothing set up.
            """{"enabled":false,"methods":{"totp":false,"passkeys":0,"backupCodesRemaining":0},"sessionVerified":false,"recovery":null}"""
                to AccountLoginSecurity(twoStepEnabled = false, passkeyCount = 0),
            // secondFactor.api.test.ts: on with an authenticator app.
            """{"enabled":true,"methods":{"totp":true,"passkeys":0,"backupCodesRemaining":10},"sessionVerified":true,"recovery":null}"""
                to AccountLoginSecurity(twoStepEnabled = true, passkeyCount = 0),
            // secondFactorPasskeys.api.test.ts: a passkey, two-step verification off, then on.
            """{"enabled":false,"methods":{"totp":false,"passkeys":1,"backupCodesRemaining":0},"sessionVerified":false,"recovery":null}"""
                to AccountLoginSecurity(twoStepEnabled = false, passkeyCount = 1),
            """{"enabled":true,"methods":{"totp":false,"passkeys":1,"backupCodesRemaining":10},"sessionVerified":true,"recovery":null}"""
                to AccountLoginSecurity(twoStepEnabled = true, passkeyCount = 1),
            // Web behaviour tests' fixture: an older server without sessionVerified / recovery.
            """{"enabled":true,"methods":{"totp":true,"passkeys":3,"backupCodesRemaining":8}}"""
                to AccountLoginSecurity(twoStepEnabled = true, passkeyCount = 3),
        )) {
            assertEquals(expected, assertIs<DataResult.Content<AccountLoginSecurity?>>(parseSharedSecondFactorStatusResponse(200, body)).data, body)
        }
    }

    @Test
    fun theFlagOffIsNoGroupNotAnError() {
        val off = parseSharedSecondFactorStatusResponse(404, """{"error":"Not found","code":"not_found","path":"/api/auth/second-factor/status"}""")
        assertNull(assertIs<DataResult.Content<AccountLoginSecurity?>>(off).data)
    }

    @Test
    fun failuresAndGuessedShapesAreErrorsWithTheTraceId() {
        for ((code, body) in listOf(
            500 to """{"error":"Failed to load two-step verification"}""",
            403 to """{"error":"Sign in again to manage two-step verification.","code":"session_family_required"}""",
            200 to "not json",
            200 to "",
            // Guessed wrappers / aliases are not the contract.
            200 to """{"data":{"enabled":true,"methods":{"totp":true,"passkeys":1,"backupCodesRemaining":10}}}""",
            200 to """{"enabled":true,"passkeys":1}""",
            200 to """{"two_factor_enabled":true,"methods":{"totp":true,"passkeys":1,"backupCodesRemaining":10}}""",
        )) {
            val error = assertIs<DataResult.Error>(parseSharedSecondFactorStatusResponse(code, body, traceId), "code=$code body=$body").error
            assertEquals(traceId, error.traceId, "code=$code body=$body")
        }
        assertEquals(
            "second_factor_status_http_500",
            assertIs<DataResult.Error>(parseSharedSecondFactorStatusResponse(500, "{}", traceId)).error.code,
        )
    }
}
