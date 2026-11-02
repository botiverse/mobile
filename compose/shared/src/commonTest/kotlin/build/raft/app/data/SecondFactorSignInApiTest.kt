package build.raft.app.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Payloads: SecondFactorSignInTestFixtures.kt (slock v1.21.44, a5f6a26765). */
class SecondFactorSignInApiTest {
    @Test
    fun parsesTheChallengeAFirstStepAnswersWith() {
        val challenge = assertNotNull(parseSharedSecondFactorSignInChallenge(secondFactorChallengeBody()))

        assertEquals(SECOND_FACTOR_CHALLENGE_FIXTURE, challenge.challenge)
        assertEquals(SECOND_FACTOR_EXPIRES_AT_MILLIS_FIXTURE, challenge.expiresAtEpochMillis)
        assertEquals("a***@example.com", challenge.maskedEmail)
        assertEquals(listOf("totp", "backup_code"), challenge.methods)
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), challenge.usableMethods)
    }

    @Test
    fun usableMethodsKeepOnlyWhatTheAppCanAnswerWith() {
        val cases =
            mapOf(
                """["backup_code"]""" to listOf(SecondFactorStepUpMethod.BackupCode),
                """["passkey"]""" to emptyList(),
                """["totp","passkey","backup_code","recovery"]""" to
                    listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode),
            )
        cases.forEach { (methods, usable) ->
            val challenge = assertNotNull(parseSharedSecondFactorSignInChallenge(secondFactorChallengeBody(methods)), methods)
            assertEquals(usable, challenge.usableMethods, methods)
        }
    }

    @Test
    fun anythingButAWellFormedChallengeIsNotOne() {
        listOf(
            SECOND_FACTOR_PASSWORD_VERIFIED_BODY,
            SECOND_FACTOR_SOCIAL_VERIFIED_BODY,
            secondFactorChallengeBody().replace("\"secondFactorRequired\":true", "\"secondFactorRequired\":false"),
            secondFactorChallengeBody().replace(SECOND_FACTOR_CHALLENGE_FIXTURE, "nope"),
            secondFactorChallengeBody().replace(SECOND_FACTOR_EXPIRES_AT_FIXTURE, "soon"),
            secondFactorChallengeBody().replace(",\"maskedEmail\":\"a***@example.com\"", ""),
            // Guessed wrappers and snake_case are not the contract.
            """{"data":${secondFactorChallengeBody()}}""",
            """{"second_factor_required":true,"challenge":"$SECOND_FACTOR_CHALLENGE_FIXTURE","expires_at":"$SECOND_FACTOR_EXPIRES_AT_FIXTURE","methods":["totp"],"masked_email":"a***@example.com"}""",
            "",
            null,
        ).forEach { body -> assertNull(parseSharedSecondFactorSignInChallenge(body), body.toString()) }
    }

    @Test
    fun verifyBodyCarriesTheChallengeAndOneAnswer() {
        val totp =
            Json.parseToJsonElement(
                buildSharedSecondFactorSignInVerifyBody(SECOND_FACTOR_CHALLENGE_FIXTURE, SecondFactorStepUpAnswer.AuthenticatorCode("123456"))
            ).jsonObject
        assertEquals(setOf("challenge", "code"), totp.keys)
        assertEquals(SECOND_FACTOR_CHALLENGE_FIXTURE, totp["challenge"]?.jsonPrimitive?.content)
        assertEquals("123456", totp["code"]?.jsonPrimitive?.content)

        val backup =
            Json.parseToJsonElement(
                buildSharedSecondFactorSignInVerifyBody(
                    SECOND_FACTOR_CHALLENGE_FIXTURE,
                    SecondFactorStepUpAnswer.BackupCode("abcde-fghij"),
                    codeVerifier = "verifier",
                )
            ).jsonObject
        assertEquals(setOf("challenge", "backupCode", "codeVerifier"), backup.keys)
        assertEquals("abcde-fghij", backup["backupCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun verifyRefusalsMapToTheirOutcomes() {
        val cases =
            listOf(
                400 to SECOND_FACTOR_CODE_INVALID_BODY to SecondFactorSignInResult.CodeInvalid,
                400 to """{"error":"That backup code was already used.","code":"backup_code_used"}""" to
                    SecondFactorSignInResult.BackupCodeUsed,
                410 to SECOND_FACTOR_EXPIRED_BODY to SecondFactorSignInResult.Expired,
                429 to """{"error":"Too many wrong codes for this sign-in. Sign in again.","code":"challenge_attempts_exhausted"}""" to
                    SecondFactorSignInResult.AttemptsExhausted,
                429 to """{"error":"Too many wrong codes. Try again later.","code":"second_factor_locked","lockedUntil":"2026-11-02T08:20:00.000Z","methods":["totp","backup_code"]}""" to
                    SecondFactorSignInResult.Locked(1_793_607_600_000L),
                503 to """{"error":"Two-step verification is not available right now.","code":"second_factor_unavailable"}""" to
                    SecondFactorSignInResult.Unavailable,
                400 to """{"code":"invalid_request","error":"Send the sign-in challenge with a code from your authenticator app, a backup code or a passkey."}""" to
                    SecondFactorSignInResult.Failed(
                        serverMessage = "Send the sign-in challenge with a code from your authenticator app, a backup code or a passkey.",
                        serverCode = "invalid_request",
                    ),
                502 to "" to SecondFactorSignInResult.Failed(serverCode = "http_502"),
            )
        cases.forEach { (response, expected) ->
            val (status, body) = response
            assertEquals(expected, parseSharedSecondFactorSignInRefusal(status, body), body)
        }
    }

    @Test
    fun passwordLoginChallengeThenVerifySignsInLikeATokenAnswer() = runTest {
        val store = InMemoryRaftAuthSessionStore()
        val transport =
            SecondFactorSignInScriptedTransport(
                firstStep = AuthNetworkResponse(200, secondFactorChallengeBody()),
                verifyResponses =
                    ArrayDeque(
                        listOf(
                            AuthNetworkResponse(400, null, SECOND_FACTOR_CODE_INVALID_BODY),
                            AuthNetworkResponse(200, SECOND_FACTOR_PASSWORD_VERIFIED_BODY),
                        )
                    ),
            )
        val source = SharedAuthDataSource(transport = transport, sessionStore = store)

        val first = assertIs<SharedAuthResult.Failure>(source.login("alice@example.com", "password123"))
        val pending = assertNotNull(first.secondFactorSignIn)
        assertEquals(SecondFactorSignInFlow.Password, pending.flow)
        assertEquals(SECOND_FACTOR_CHALLENGE_FIXTURE, pending.challenge.challenge)
        assertNull(store.readSession(), "no session from the first step")
        assertTrue(transport.serversAuthorizations.isEmpty())

        val wrong = source.verifySecondFactorSignIn(pending, SecondFactorStepUpAnswer.AuthenticatorCode("000000"))
        assertEquals(SecondFactorSignInResult.CodeInvalid, wrong)
        assertNull(store.readSession(), "a refused answer writes nothing")

        val right = assertIs<SecondFactorSignInResult.SignedIn>(
            source.verifySecondFactorSignIn(pending, SecondFactorStepUpAnswer.BackupCode("abcde-fghij"))
        )
        assertEquals(9, right.backupCodesRemaining)
        assertEquals("user-1", right.session.user.id)
        assertEquals("access-2fa", store.readSession()?.accessToken)
        assertEquals("refresh-2fa", store.readSession()?.refreshToken)
        assertEquals(listOf("server-1"), store.readSession()?.servers?.map { it.id })
        assertEquals(listOf("Bearer access-2fa"), transport.serversAuthorizations)
        val sent = Json.parseToJsonElement(transport.verifyBodies.last()).jsonObject
        assertEquals("abcde-fghij", sent["backupCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun socialCompleteChallengeThenVerifySignsInWithTheSocialBody() = runTest {
        val store = InMemoryRaftAuthSessionStore()
        val transport =
            SecondFactorSignInScriptedTransport(
                firstStep = AuthNetworkResponse(200, secondFactorChallengeBody()),
                verifyResponses = ArrayDeque(listOf(AuthNetworkResponse(200, SECOND_FACTOR_SOCIAL_VERIFIED_BODY))),
            )
        val source = SharedAuthDataSource(transport = transport, sessionStore = store)

        val first = assertIs<SharedAuthResult.Failure>(source.completeSocialLogin("google", "completion-code"))
        val pending = assertNotNull(first.secondFactorSignIn)
        assertEquals(SecondFactorSignInFlow.Social("google"), pending.flow)

        val right = assertIs<SecondFactorSignInResult.SignedIn>(
            source.verifySecondFactorSignIn(pending, SecondFactorStepUpAnswer.AuthenticatorCode("123456"))
        )
        assertNull(right.backupCodesRemaining)
        assertEquals("user-1", store.readSession()?.user?.id)
        assertEquals("access-2fa", store.readSession()?.accessToken)
    }

    @Test
    fun aTokenAnswerToTheFirstStepSignsInAsBefore() = runTest {
        val store = InMemoryRaftAuthSessionStore()
        val transport = SecondFactorSignInScriptedTransport(firstStep = AuthNetworkResponse(200, SECOND_FACTOR_PASSWORD_VERIFIED_BODY))
        val source = SharedAuthDataSource(transport = transport, sessionStore = store)

        assertIs<SharedAuthResult.Success<StoredAuthSession>>(source.login("alice@example.com", "password123"))
        assertEquals("access-2fa", store.readSession()?.accessToken)
        assertTrue(transport.verifyBodies.isEmpty())
    }

    @Test
    fun aVerifySuccessWithoutTokensIsAContractMismatch() = runTest {
        val transport =
            SecondFactorSignInScriptedTransport(
                firstStep = AuthNetworkResponse(200, secondFactorChallengeBody()),
                verifyResponses = ArrayDeque(listOf(AuthNetworkResponse(200, """{"ok":true}"""))),
            )
        val store = InMemoryRaftAuthSessionStore()
        val source = SharedAuthDataSource(transport = transport, sessionStore = store)

        val result = source.verifySecondFactorSignIn(secondFactorPending(), SecondFactorStepUpAnswer.AuthenticatorCode("123456"))

        assertEquals(SecondFactorSignInResult.Failed(serverCode = "auth_second_factor_verify_contract_mismatch"), result)
        assertNull(store.readSession())
    }
}
