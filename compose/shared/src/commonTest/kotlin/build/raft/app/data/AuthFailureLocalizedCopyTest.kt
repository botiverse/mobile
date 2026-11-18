package build.raft.app.data

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sign-in, session restore and server choice failures read in the app language: the client's own
 * copy is a catalog string ([SharedAuthResult.Failure.display]), its English and internal ids
 * ([SharedAuthResult.Failure.message]) stay for logs, and the server's own words show as sent.
 */
class AuthFailureLocalizedCopyTest {
    @Test
    fun setupStageFailureNeverPutsTheStageIdInTheUsersSentence() {
        listOf("session_clear", "servers_parse", "session_persist").forEach { stage ->
            val failure = loginStageFailure(stage, "Could not save session locally.")
            assertTrue(failure.message.contains(stage), "the log message keeps the stage")
            // One plain catalog string, no arguments: nothing about the stage can reach the user.
            assertEquals(ResourceStringDesc(Res.string.auth_error_sign_in_setup_failed), failure.userMessage)
        }
    }

    @Test
    fun httpFallbacksWithoutAServerMessageAreCatalogCopy() {
        assertEquals(Res.string.error_network_connection_failed.desc(), AuthApiResult.Failure(code = 0).toSharedAuthFailure().userMessage)
        assertEquals(Res.string.auth_error_check_fields.desc(), AuthApiResult.Failure(code = 400).toSharedAuthFailure().userMessage)
        assertEquals(Res.string.auth_error_incorrect_credentials.desc(), AuthApiResult.Failure(code = 401).toSharedAuthFailure().userMessage)
        assertEquals(Res.string.error_server_unavailable.desc(), AuthApiResult.Failure(code = 503).toSharedAuthFailure().userMessage)
        assertEquals(Res.string.auth_error_sign_in_failed.desc(), AuthApiResult.Failure(code = 409).toSharedAuthFailure().userMessage)
        assertEquals(
            Res.string.auth_error_sign_in_failed.desc(),
            AuthApiResult.Failure(code = 200, contractCode = "auth_login_contract_mismatch").toSharedAuthFailure().userMessage
        )
        assertEquals(
            Res.string.auth_error_legal_acceptance_required.desc(),
            AuthApiResult.Failure(code = 422, errorBodyText = """{"error":"TERMS_CHANGED"}""").toSharedAuthFailure().userMessage
        )
    }

    @Test
    fun theServersOwnWordsShowAsSentExceptTheCredentialsMessageWebMaps() {
        val server = AuthApiResult.Failure(code = 400, errorBodyText = """{"error":"Slug already taken"}""").toSharedAuthFailure()
        assertNull(server.display)
        assertEquals("Slug already taken".desc(), server.userMessage)

        // web authErrors.ts authServerErrorMessage: by code or by the server's English.
        assertEquals(
            Res.string.auth_error_incorrect_credentials.desc(),
            AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"Invalid email or password"}""")
                .toSharedAuthFailure().userMessage
        )
        assertEquals(
            Res.string.auth_error_incorrect_credentials.desc(),
            AuthApiResult.Failure(code = 403, errorBodyText = """{"error":"Nope","code":"AUTH_INVALID_CREDENTIALS"}""")
                .toSharedAuthFailure().userMessage
        )
        // A non-string code does not cost the server's message.
        assertEquals(
            "Slow down".desc(),
            AuthApiResult.Failure(code = 429, errorBodyText = """{"error":"Slow down","code":429}""").toSharedAuthFailure().userMessage
        )
    }

    @Test
    fun emailVerificationFailureKeepsTheLocalizedCopy() {
        val failure =
            SharedEmailVerificationResult.Failure(
                SharedAuthErrorCode.Network,
                "Network unavailable. Check your connection.",
                Res.string.error_network_connection_failed.desc()
            )
        assertEquals(Res.string.error_network_connection_failed.desc(), failure.userMessage)
        assertEquals("server words".desc(), SharedEmailVerificationResult.Failure(SharedAuthErrorCode.Unknown, "server words").userMessage)
    }
}
