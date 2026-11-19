package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The passkey step-up's wire shapes, botiverse/slock staging e21bf9b978ac8061dfbdf9dfc3770b592c642ee6:
 *  - options: routes/secondFactor.ts `/passkeys/assertion/options` `{ rp? }` → passkeys.ts
 *    `startPasskeyAssertion` `{ options, rp?, otherRps? }` (rp / otherRps only with WEBAUTHN_LEGACY_RP_ID set) → webauthn.ts `passkeyAssertionOptions` (@simplewebauthn/server 14.0.3
 *    `generateAuthenticationOptions`: `{ rpId, challenge, allowCredentials: [{ ...cred, id, type }],
 *    timeout, userVerification }`), as read by routes/secondFactorPasskeys.api.test.ts `stepUpOptions`;
 *  - the assertion: test/integration/softwareAuthenticator.ts `assert` (the browser's
 *    `PublicKeyCredential.toJSON()`, which Android's `authenticationResponseJson` matches), checked by
 *    webauthn.ts `parsePasskeyAssertionResponse`;
 *  - refusals: passkeyManagement.ts `passkeyNotFound` / `startStepUpPasskeyAssertion`
 *    (`passkey_rp_unavailable` `{ rp, availableRps }`) / `relyingParty`, routes `requireWebSessionFamily`.
 */
class SecondFactorPasskeyStepUpApiTest {
    private val optionsBody =
        """{"options":{"rpId":"raft.test","challenge":"Y2hhbGxlbmdlLXRva2Vu","allowCredentials":[{"id":"q83vEjRWeJA","transports":["internal","hybrid"],"type":"public-key"}],"timeout":300000,"userVerification":"required"}}"""

    private val assertion =
        """{"id":"q83vEjRWeJA","rawId":"q83vEjRWeJA","type":"public-key","response":{"clientDataJSON":"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0In0","authenticatorData":"SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2MFAAAAAQ","signature":"MEUCIQDx","userHandle":"dXNlci1oYW5kbGU"},"clientExtensionResults":{},"authenticatorAttachment":"platform"}"""

    @Test
    fun optionsAreHandedOnVerbatimWithTheirCheckedFields() {
        val ready = assertIs<SecondFactorPasskeyOptionsResult.Ready>(parseSharedSecondFactorPasskeyAssertionOptions(200, optionsBody))
        assertEquals("raft.test", ready.options.rpId)
        assertEquals("Y2hhbGxlbmdlLXRva2Vu", ready.options.challenge)
        assertEquals(listOf("q83vEjRWeJA"), ready.options.allowCredentials.map { it.id })
        assertEquals(listOf("internal", "hybrid"), ready.options.allowCredentials.single().transports)
        assertEquals(300000L, ready.options.timeout)
        assertEquals("required", ready.options.userVerification)
        // The platform gets the server's own object: the RP ID is never the app's.
        val handedOn = Json.parseToJsonElement(ready.requestJson).jsonObject
        assertEquals(Json.parseToJsonElement(optionsBody).jsonObject["options"], handedOn)
    }

    @Test
    fun optionsRefusalsAndGuessedShapes() {
        val trace = "0af7651916cd43dd8448eb211c80319c"
        assertEquals(
            SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.PasskeyNotFound(trace)),
            parseSharedSecondFactorPasskeyAssertionOptions(404, """{"error":"That passkey was not found.","code":"passkey_not_found"}""", trace)
        )
        assertEquals(
            SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.PasskeysUnavailable(trace)),
            parseSharedSecondFactorPasskeyAssertionOptions(503, """{"error":"Passkeys are not available right now.","code":"passkeys_unavailable"}""", trace)
        )
        assertEquals(
            SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.SessionFamilyRequired),
            parseSharedSecondFactorPasskeyAssertionOptions(403, """{"error":"Sign in again to manage two-step verification.","code":"session_family_required"}""")
        )
        // No `options` envelope (a guessed `publicKey` wrapper, the bare options) or no challenge: refused.
        listOf(
            """{"publicKey":{"rpId":"raft.test","challenge":"abc"}}""",
            """{"rpId":"raft.test","challenge":"abc"}""",
            """{"options":{"rpId":"raft.test"}}""",
            "",
        ).forEach { body ->
            val refused = assertIs<SecondFactorPasskeyOptionsResult.Refused>(parseSharedSecondFactorPasskeyAssertionOptions(200, body, trace), body)
            assertEquals(SecondFactorStepUpResult.Failed(serverCode = "passkey_options_contract_mismatch", traceId = trace), refused.result)
        }
    }

    @Test
    fun theOptionsRequestIsEmptyUnlessAGroupIsAsked() {
        assertEquals(emptySet<String>(), Json.parseToJsonElement(buildSharedSecondFactorPasskeyAssertionOptionsBody()).jsonObject.keys)
        val current = Json.parseToJsonElement(buildSharedSecondFactorPasskeyAssertionOptionsBody(SECOND_FACTOR_PASSKEY_RP_CURRENT)).jsonObject
        assertEquals(mapOf("rp" to "current"), current.mapValues { it.value.jsonPrimitive.content })
        val legacy = Json.parseToJsonElement(buildSharedSecondFactorPasskeyAssertionOptionsBody(SECOND_FACTOR_PASSKEY_RP_LEGACY)).jsonObject
        assertEquals(mapOf("rp" to "legacy"), legacy.mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun optionsSayTheirRpGroupAndThePersonsOtherGroups() {
        // Without a legacy RP ID: `{ options }` only.
        val plain = assertIs<SecondFactorPasskeyOptionsResult.Ready>(parseSharedSecondFactorPasskeyAssertionOptions(200, optionsBody))
        assertNull(plain.rp)
        assertEquals(emptyList<String>(), plain.otherRps)
        // With one: the group asked (or defaulted) and the other groups with a passkey; unknown names dropped.
        val withGroups = optionsBody.dropLast(1) + ""","rp":"current","otherRps":["legacy","staging"]}"""
        val ready = assertIs<SecondFactorPasskeyOptionsResult.Ready>(parseSharedSecondFactorPasskeyAssertionOptions(200, withGroups))
        assertEquals("current", ready.rp)
        assertEquals(listOf("legacy"), ready.otherRps)
        // The platform still gets only the `options` object.
        assertEquals(Json.parseToJsonElement(optionsBody).jsonObject["options"], Json.parseToJsonElement(ready.requestJson))
    }

    @Test
    fun noPasskeyInTheGroupAskedForSaysWhichGroupsHaveOne() {
        val trace = "0af7651916cd43dd8448eb211c80319c"
        assertEquals(
            SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.PasskeyRpUnavailable("current", listOf("legacy"), trace)),
            parseSharedSecondFactorPasskeyAssertionOptions(
                404,
                """{"error":"None of your passkeys can be used here.","code":"passkey_rp_unavailable","rp":"current","availableRps":["legacy"]}""",
                trace,
            )
        )
        // An `rp` the server does not know: its 400 is a plain refusal.
        assertEquals(
            SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.Failed("rp must be \"current\" or \"legacy\".", "invalid_request", trace)),
            parseSharedSecondFactorPasskeyAssertionOptions(400, """{"error":"rp must be \"current\" or \"legacy\".","code":"invalid_request"}""", trace)
        )
    }

    @Test
    fun theAssertionGoesAsWebauthn() {
        val parsed = assertNotNull(parseSharedPasskeyAssertionResponse(assertion))
        assertEquals("q83vEjRWeJA", parsed.id)
        assertEquals("dXNlci1oYW5kbGU", parsed.response.userHandle)
        val body = Json.parseToJsonElement(buildSharedSecondFactorStepUpBody(SecondFactorStepUpAnswer.Passkey(parsed))).jsonObject
        assertEquals(setOf("webauthn"), body.keys)
        val webauthn = assertNotNull(body["webauthn"]).jsonObject
        assertEquals("public-key", webauthn["type"]?.jsonPrimitive?.content)
        assertEquals(webauthn["id"], webauthn["rawId"])
        assertEquals(0, assertNotNull(webauthn["clientExtensionResults"]).jsonObject.size)
        val response = assertNotNull(webauthn["response"]).jsonObject
        assertEquals(setOf("clientDataJSON", "authenticatorData", "signature", "userHandle"), response.keys)
        assertEquals("MEUCIQDx", response["signature"]?.jsonPrimitive?.content)
        // Without a user handle the field is left out, as the server allows.
        val noHandle = assertNotNull(parseSharedPasskeyAssertionResponse(assertion.replace(""","userHandle":"dXNlci1oYW5kbGU"""", "")))
        assertNull(noHandle.response.userHandle)
        val noHandleBody = Json.parseToJsonElement(buildSharedSecondFactorStepUpBody(SecondFactorStepUpAnswer.Passkey(noHandle))).jsonObject
        assertEquals(setOf("clientDataJSON", "authenticatorData", "signature"), noHandleBody["webauthn"]!!.jsonObject["response"]!!.jsonObject.keys)
    }

    @Test
    fun anAssertionTheServerWouldRefuseForItsShapeIsNotSent() {
        listOf(
            assertion.replace(""""rawId":"q83vEjRWeJA"""", """"rawId":"other"""),
            assertion.replace(""""type":"public-key"""", """"type":"password""""),
            assertion.replace(""""signature":"MEUCIQDx"""", """"signature":"not base64url!""""),
            assertion.replace(""""signature":"MEUCIQDx"""", """"signature":"""""),
            """{"credential":$assertion}""",
            "not json",
        ).forEach { json -> assertNull(parseSharedPasskeyAssertionResponse(json), json) }
    }

    @Test
    fun aStepUpRefusalCarriesItsTraceId() {
        val trace = "4bf92f3577b34da6a3ce929d0e0e4736"
        assertEquals(
            SecondFactorStepUpResult.CodeInvalid(trace),
            parseSharedSecondFactorStepUpResult(400, """{"error":"That passkey didn't work. Try again.","code":"second_factor_code_invalid"}""", trace)
        )
        assertEquals(
            SecondFactorStepUpResult.PasskeyNotFound(trace),
            parseSharedSecondFactorStepUpResult(404, """{"error":"That passkey was not found.","code":"passkey_not_found"}""", trace)
        )
        assertEquals(
            SecondFactorStepUpResult.Failed("Failed to verify this sign-in", "internal_error", trace),
            parseSharedSecondFactorStepUpResult(500, """{"error":"Failed to verify this sign-in","code":"internal_error"}""", trace)
        )
    }
}
