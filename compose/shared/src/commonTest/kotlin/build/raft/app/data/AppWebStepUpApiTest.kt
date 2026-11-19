package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web step-up's wire shapes and its PKCE. Answers are the server's verbatim (botiverse/slock
 * #15593 @ 7daeae2d: docs/contracts/app-web-step-up.md, routes/appLogin.ts `/step-up/start` and
 * `/step-up/complete`, refusals through lib/refusal.ts `refuse()`, which adds `errorCode`).
 */
class AppWebStepUpApiTest {
    private val trace = "4bf92f3577b34da6a3ce929d0e0e4736"

    /** `refuse()`'s envelope: `{ error, code, errorCode }`. */
    private fun refusal(error: String, code: String) = """{"error":"$error","code":"$code","errorCode":"$code"}"""

    private companion object {
        const val UNUSABLE_CODE = "The verification code could not be used"
    }

    @Test
    fun startSendsS256AndTheAction() {
        assertEquals(
            """{"returnUri":"raft://login/callback","codeChallenge":"c","codeChallengeMethod":"S256","action":"password_change"}""",
            buildAppWebStepUpStartBody(AppWebStepUpStartBody("raft://login/callback", "c", action = "password_change")),
        )
        assertEquals(
            """{"returnUri":"raft://login/callback","codeChallenge":"c","codeChallengeMethod":"S256","clientId":"raft-app","action":"computer_attach"}""",
            buildAppWebStepUpStartBody(AppWebStepUpStartBody("raft://login/callback", "c", clientId = "raft-app", action = "computer_attach")),
        )
        assertEquals(
            """{"code":"k","codeVerifier":"v","returnUri":"raft://login/callback"}""",
            buildAppWebStepUpCompleteBody(AppWebStepUpCompleteBody("k", "v", "raft://login/callback")),
        )
    }

    @Test
    fun startAnswers() {
        assertEquals(
            AppWebStepUpStartResult.Started("r-1", "https://app.raft.build/login/app/verify?request=r-1", "2026-11-19T10:05:00Z"),
            parseAppWebStepUpStart(
                201,
                """{"requestId":"r-1","stepUpUrl":"https://app.raft.build/login/app/verify?request=r-1","expiresAt":"2026-11-19T10:05:00Z","client":{"id":"raft-app","name":"Raft"}}""",
            ),
        )
        assertEquals(
            AppWebStepUpStartResult.Unavailable,
            parseAppWebStepUpStart(404, refusal("App step-up is not available", "passkey_not_found")),
        )
        assertEquals(
            AppWebStepUpStartResult.SessionFamilyRequired,
            parseAppWebStepUpStart(403, refusal("Sign in again to continue.", "session_family_required")),
        )
        assertEquals(
            AppWebStepUpStartResult.Failed("App step-up is not available", "invalid_action", trace),
            parseAppWebStepUpStart(400, refusal("App step-up is not available", "invalid_action"), trace),
        )
        // The flag off, or no legacy passkey: Open on the web instead.
        assertEquals(AppWebStepUpStartResult.Unavailable, parseAppWebStepUpStart(404, refusal("App step-up is not available", "not_enabled")))
        assertEquals(AppWebStepUpStartResult.Unavailable, parseAppWebStepUpStart(404, null))
        assertEquals(
            AppWebStepUpStartResult.NotEnabled,
            parseAppWebStepUpStart(409, refusal("App step-up is not available", "second_factor_not_enabled")),
        )
        assertEquals(
            AppWebStepUpStartResult.Failed("App step-up is not available", "return_uri_not_allowed", trace),
            parseAppWebStepUpStart(400, refusal("App step-up is not available", "return_uri_not_allowed"), trace),
        )
        assertEquals(
            AppWebStepUpStartResult.Failed("returnUri, codeChallenge and action are required", "invalid_request", trace),
            parseAppWebStepUpStart(400, refusal("returnUri, codeChallenge and action are required", "invalid_request"), trace),
        )
        // Not through refuse(): no errorCode.
        assertEquals(
            AppWebStepUpStartResult.Failed("App login URL is not configured", "app_login_url_unavailable", trace),
            parseAppWebStepUpStart(503, """{"error":"App login URL is not configured","code":"app_login_url_unavailable"}""", trace),
        )
        assertEquals(AppWebStepUpStartResult.Failed(null, "http_502", trace), parseAppWebStepUpStart(502, "", trace))
        assertEquals(
            AppWebStepUpStartResult.Failed(serverCode = "app_web_step_up_contract_mismatch", traceId = trace),
            parseAppWebStepUpStart(201, """{"requestId":"r-1"}""", trace),
        )
    }

    @Test
    fun completeAnswers() {
        assertEquals(
            AppWebStepUpCompleteResult.Verified,
            parseAppWebStepUpComplete(200, """{"verified":true,"verifiedAt":"2026-11-19T10:01:00Z"}"""),
        )
        assertEquals(AppWebStepUpCompleteResult.Expired(trace), parseAppWebStepUpComplete(410, refusal(UNUSABLE_CODE, "code_expired"), trace))
        // pkce_mismatch leaves the code unused on the server; here it is a failure with its trace ID.
        assertEquals(
            AppWebStepUpCompleteResult.Failed(UNUSABLE_CODE, "pkce_mismatch", trace),
            parseAppWebStepUpComplete(400, refusal(UNUSABLE_CODE, "pkce_mismatch"), trace),
        )
        assertEquals(
            AppWebStepUpCompleteResult.SessionFamilyRequired,
            parseAppWebStepUpComplete(403, refusal("Sign in again to continue.", "session_family_required")),
        )
        assertEquals(
            AppWebStepUpCompleteResult.Failed(serverCode = "app_web_step_up_contract_mismatch", traceId = trace),
            parseAppWebStepUpComplete(200, """{"verified":false}""", trace),
        )
        assertEquals(AppWebStepUpCompleteResult.Expired(trace), parseAppWebStepUpComplete(410, refusal(UNUSABLE_CODE, "code_consumed"), trace))
        // Another sign-in's or person's code, a login code, or the flag off: all 404, a failure.
        assertEquals(
            AppWebStepUpCompleteResult.Failed(UNUSABLE_CODE, "code_not_found", trace),
            parseAppWebStepUpComplete(404, refusal(UNUSABLE_CODE, "code_not_found"), trace),
        )
        assertEquals(
            AppWebStepUpCompleteResult.Failed(UNUSABLE_CODE, "not_enabled", trace),
            parseAppWebStepUpComplete(404, refusal(UNUSABLE_CODE, "not_enabled"), trace),
        )
    }

    @Test
    fun pkceIsS256() {
        // RFC 7636 appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", appWebStepUpChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val pkce = newAppWebStepUpPkce()
        assertTrue(Regex("^[A-Za-z0-9_-]{43,128}$").matches(pkce.verifier), pkce.verifier)
        assertEquals(appWebStepUpChallenge(pkce.verifier), pkce.challenge)
        assertTrue(newAppWebStepUpPkce().verifier != pkce.verifier)
    }

    @Test
    fun theStepUpCallbackIsTheBrowserLoginsCallback() {
        assertEquals(
            AppLoginCallback("raft://login/callback", "r-1", "k", ""),
            parseAppLoginCallback("raft://login/callback?requestId=r-1&code=k"),
        )
        assertEquals(
            AppLoginCallback("raft-alpha://login/callback", "r-1", "", "expired"),
            parseAppLoginCallback("raft-alpha://login/callback?requestId=r-1&error=expired"),
        )
        assertEquals(
            AppLoginCallback("raft-debug://login/callback", "r-1", "", "access_denied"),
            parseAppLoginCallback("raft-debug://login/callback?requestId=r-1&error=access_denied"),
        )
    }

    @Test
    fun onlyThisBuildsLoginCallbackCanCarryIt() {
        val start: suspend (AppWebStepUpStartBody) -> AppWebStepUpStartResult = { AppWebStepUpStartResult.Unavailable }
        val complete: suspend (AppWebStepUpCompleteBody) -> AppWebStepUpCompleteResult = { AppWebStepUpCompleteResult.Verified }
        assertEquals(null, secondFactorWebStepUp(start, complete, returnUri = null))
        assertEquals(null, secondFactorWebStepUp(start, complete, returnUri = "raft-mac://login/callback"))
        assertEquals("raft-beta://login/callback", secondFactorWebStepUp(start, complete, returnUri = "raft-beta://login/callback")?.returnUri)
    }
}
