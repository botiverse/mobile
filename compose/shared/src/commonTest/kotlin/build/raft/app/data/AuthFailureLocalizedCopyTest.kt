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
    fun refusalEnvelopeReadsLegalAndCredentialCodesFromCodeErrorCodeAndLegacyError() {
        val legalByCode =
            AuthApiResult.Failure(
                code = 422,
                errorBodyText = """{"code":"LEGAL_ACCEPTANCE_REQUIRED","error":"Please accept the updated Terms."}"""
            ).toSharedAuthFailure()
        assertEquals(SharedAuthErrorCode.LegalAcceptanceRequired, legalByCode.code)
        assertEquals(Res.string.auth_error_legal_acceptance_required.desc(), legalByCode.userMessage)

        val termsByErrorCode =
            AuthApiResult.Failure(
                code = 409,
                errorBodyText = """{"errorCode":"TERMS_CHANGED","error":"Terms of Service changed."}"""
            ).toSharedAuthFailure()
        assertEquals(SharedAuthErrorCode.LegalAcceptanceRequired, termsByErrorCode.code)
        assertEquals(Res.string.auth_error_legal_acceptance_required.desc(), termsByErrorCode.userMessage)

        val legalLowercase =
            AuthApiResult.Failure(
                code = 422,
                errorBodyText = """{"code":"legal_acceptance_required","error":"Please accept terms."}"""
            ).toSharedAuthFailure()
        assertEquals(SharedAuthErrorCode.LegalAcceptanceRequired, legalLowercase.code)

        assertEquals(
            Res.string.auth_error_incorrect_credentials.desc(),
            AuthApiResult.Failure(code = 403, errorBodyText = """{"error":"Nope","errorCode":"AUTH_INVALID_CREDENTIALS"}""")
                .toSharedAuthFailure().userMessage
        )

        val unknownForbidden =
            AuthApiResult.Failure(
                code = 403,
                errorBodyText = """{"code":"account_restricted","error":"Your organization paused access for this account."}"""
            ).toSharedAuthFailure()
        assertNull(unknownForbidden.display)
        assertEquals("Your organization paused access for this account.".desc(), unknownForbidden.userMessage)

        val confusableHandle =
            AuthApiResult.Failure(
                code = 409,
                errorBodyText = """{"code":"PROFILE_SETUP_NAME_CONFUSABLE","error":"Username is too similar to an existing name. Choose another name."}"""
            ).toSharedAuthFailure()
        assertEquals(Res.string.auth_validation_username_confusable.desc(), confusableHandle.userMessage)

        val reservedHandle =
            AuthApiResult.Failure(
                code = 400,
                errorBodyText = """{"code":"PROFILE_SETUP_NAME_RESERVED","error":"This username is reserved. Choose another name."}"""
            ).toSharedAuthFailure()
        assertEquals(Res.string.auth_validation_username_reserved.desc(), reservedHandle.userMessage)
    }
}
