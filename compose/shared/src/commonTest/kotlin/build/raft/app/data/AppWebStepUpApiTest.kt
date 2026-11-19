package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web step-up's wire shapes (design §4; pending contract verification against the server PR)
 * and its PKCE.
 */
class AppWebStepUpApiTest {
    private val trace = "4bf92f3577b34da6a3ce929d0e0e4736"

    @Test
    fun startSendsS256AndLeavesAnUnknownActionOut() {
        assertEquals(
            """{"returnUri":"raft://login/callback","codeChallenge":"c","codeChallengeMethod":"S256","action":"password_change"}""",
            buildAppWebStepUpStartBody(AppWebStepUpStartBodyPending("raft://login/callback", "c", action = "password_change")),
        )
        assertEquals(
            """{"returnUri":"raft://login/callback","codeChallenge":"c","codeChallengeMethod":"S256"}""",
            buildAppWebStepUpStartBody(AppWebStepUpStartBodyPending("raft://login/callback", "c")),
        )
        assertEquals(
            """{"code":"k","codeVerifier":"v","returnUri":"raft://login/callback"}""",
            buildAppWebStepUpCompleteBody(AppWebStepUpCompleteBodyPending("k", "v", "raft://login/callback")),
        )
    }

    @Test
    fun startAnswers() {
        assertEquals(
            AppWebStepUpStartResult.Started("r-1", "https://app.raft.build/login/app?request=r-1&purpose=step_up", "2026-11-19T10:05:00Z"),
            parseAppWebStepUpStart(
                201,
                """{"requestId":"r-1","stepUpUrl":"https://app.raft.build/login/app?request=r-1&purpose=step_up","expiresAt":"2026-11-19T10:05:00Z"}""",
            ),
        )
        // The flag off, or no legacy passkey: Open on the web instead.
        assertEquals(AppWebStepUpStartResult.Unavailable, parseAppWebStepUpStart(404, """{"error":"Not found","code":"not_enabled"}"""))
        assertEquals(AppWebStepUpStartResult.Unavailable, parseAppWebStepUpStart(404, null))
        assertEquals(
            AppWebStepUpStartResult.NotEnabled,
            parseAppWebStepUpStart(409, """{"error":"Off","code":"second_factor_not_enabled"}"""),
        )
        assertEquals(
            AppWebStepUpStartResult.Failed("Bad return URI", "invalid_return_uri", trace),
            parseAppWebStepUpStart(400, """{"error":"Bad return URI","code":"invalid_return_uri"}""", trace),
        )
        assertEquals(AppWebStepUpStartResult.Failed(null, "http_502", trace), parseAppWebStepUpStart(502, "", trace))
        assertEquals(
            AppWebStepUpStartResult.Failed(serverCode = "app_web_step_up_contract_mismatch", traceId = trace),
            parseAppWebStepUpStart(201, """{"requestId":"r-1"}""", trace),
        )
    }

    @Test
    fun completeAnswers() {
        assertEquals(AppWebStepUpCompleteResult.Verified, parseAppWebStepUpComplete(200, """{"verified":true}"""))
        assertEquals(
            AppWebStepUpCompleteResult.Failed(serverCode = "app_web_step_up_contract_mismatch", traceId = trace),
            parseAppWebStepUpComplete(200, """{"verified":false}""", trace),
        )
        assertEquals(AppWebStepUpCompleteResult.Expired(trace), parseAppWebStepUpComplete(410, """{"code":"code_consumed"}""", trace))
        assertEquals(
            AppWebStepUpCompleteResult.NotEnabled,
            parseAppWebStepUpComplete(409, """{"code":"second_factor_not_enabled"}"""),
        )
        assertEquals(
            AppWebStepUpCompleteResult.Failed("Not found", "code_not_found", trace),
            parseAppWebStepUpComplete(404, """{"error":"Not found","code":"code_not_found"}""", trace),
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
        val start: suspend (AppWebStepUpStartBodyPending) -> AppWebStepUpStartResult = { AppWebStepUpStartResult.Unavailable }
        val complete: suspend (AppWebStepUpCompleteBodyPending) -> AppWebStepUpCompleteResult = { AppWebStepUpCompleteResult.Verified }
        assertEquals(null, secondFactorWebStepUp(start, complete, returnUri = null))
        assertEquals(null, secondFactorWebStepUp(start, complete, returnUri = "raft-mac://login/callback"))
        assertEquals("raft-beta://login/callback", secondFactorWebStepUp(start, complete, returnUri = "raft-beta://login/callback")?.returnUri)
    }
}
