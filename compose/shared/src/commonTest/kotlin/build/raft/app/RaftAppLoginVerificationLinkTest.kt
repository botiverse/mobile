package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sign-up email link iOS reopens in the web sign-in sheet ([raftAppLoginVerificationLinkResumes]). */
class RaftAppLoginVerificationLinkTest {
    /**
     * slock `verificationEmailUrl` (packages/server/src/services/emailService.ts): `?verify=`, plus
     * `returnTo` (URLSearchParams-encoded) unless the page is `/`. `returnTo` here is already an
     * app-local path, so the server's sanitizer keeps it as is.
     */
    private fun verificationEmailUrl(token: String, returnTo: String? = null, origin: String = "https://app.raft.build"): String {
        val query = "verify=" + formEncode(token) + (returnTo?.takeIf { it != "/" }?.let { "&returnTo=" + formEncode(it) } ?: "")
        return "$origin?$query"
    }

    /** `URLSearchParams.toString()`: application/x-www-form-urlencoded. */
    private fun formEncode(value: String): String =
        value.encodeToByteArray().joinToString("") { byte ->
            val c = byte.toInt().toChar()
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "*-._" -> c.toString()
                c == ' ' -> "+"
                else -> "%" + (byte.toInt() and 0xff).toString(16).uppercase().padStart(2, '0')
            }
        }

    private val appSignUp = "/login/app?request=req_123"

    private fun request(url: String, configuredWebUrl: String? = null) =
        raftAppLoginVerificationLinkRequest(url, configuredWebUrl, appHosts = emptySet())

    @Test
    fun theServerLinkEncodesReturnToTheWayTheWebDoes() {
        assertEquals(
            "https://app.raft.build?verify=tok_abc&returnTo=%2Flogin%2Fapp%3Frequest%3Dreq_123",
            verificationEmailUrl("tok_abc", appSignUp),
        )
    }

    @Test
    fun readsTheRequestOfANativeSignInSignUpOnProductionHosts() {
        assertEquals("req_123", request(verificationEmailUrl("tok_abc", appSignUp)))
        assertEquals("req_123", request(verificationEmailUrl("tok_abc", appSignUp, origin = "https://open.raft.build")))
        assertEquals("req_123", request("https://app.raft.build/?verify=tok_abc&returnTo=/login/app?request=req_123"))
        assertEquals("req_123", request("https://APP.RAFT.BUILD/?returnTo=%2Flogin%2Fapp%3Frequest%3Dreq_123&verify=tok_abc"))
    }

    @Test
    fun aWebSignUpLinkHasNoSignInToResume() {
        assertNull(request(verificationEmailUrl("tok_abc")))
        assertNull(request(verificationEmailUrl("tok_abc", "/")))
        assertNull(request(verificationEmailUrl("tok_abc", "/artifacts/pt_abc/v/2?c=1")))
        assertNull(request(verificationEmailUrl("tok_abc", "/login/app")))
        assertNull(request(verificationEmailUrl("tok_abc", "/login/app?request=")))
        assertNull(request(verificationEmailUrl("tok_abc", "/login/app?requestId=req_123")))
        assertNull(request(verificationEmailUrl("tok_abc", "/login/app/x?request=req_123")))
    }

    @Test
    fun needsAVerifyTokenOnTheRootPath() {
        assertNull(request(verificationEmailUrl("", appSignUp)))
        assertNull(request("https://app.raft.build/?returnTo=%2Flogin%2Fapp%3Frequest%3Dreq_123"))
        assertNull(request("https://app.raft.build/login/app?verify=tok_abc&returnTo=%2Flogin%2Fapp%3Frequest%3Dreq_123"))
        assertNull(request("https://app.raft.build/?verify=%zz&returnTo=%2Flogin%2Fapp%3Frequest%3Dreq_123"))
    }

    @Test
    fun otherOriginsOnlyAsTheConfiguredWebUrl() {
        val staging = verificationEmailUrl("tok_abc", appSignUp, origin = "https://raft-app-staging.botiverse.dev")
        assertNull(request(staging))
        assertEquals("req_123", request(staging, configuredWebUrl = "https://raft-app-staging.botiverse.dev"))
        assertNull(request(verificationEmailUrl("tok_abc", appSignUp, origin = "http://app.raft.build")))
        assertNull(request(verificationEmailUrl("tok_abc", appSignUp, origin = "https://evil.example")))
        assertNull(request(verificationEmailUrl("tok_abc", appSignUp, origin = "https://user@app.raft.build")))
    }

    @Test
    fun reopensOnlyTheRequestTheAppIsWaitingFor() {
        val asked = mutableListOf<String>()
        fun resumes(url: String, waitingFor: String?) =
            raftAppLoginVerificationLinkResumes(url, null, appHosts = emptySet()) { asked += it; it == waitingFor }
        // This app's own sign-up.
        assertTrue(resumes(verificationEmailUrl("tok_abc", appSignUp), waitingFor = "req_123"))
        // A Raft Desktop sign-up (same link shape, another request), or nothing pending / expired.
        assertFalse(resumes(verificationEmailUrl("tok_abc", "/login/app?request=desktop_req"), waitingFor = "req_123"))
        assertFalse(resumes(verificationEmailUrl("tok_abc", appSignUp), waitingFor = null))
        // A web sign-up never reaches the pending-request check.
        assertFalse(resumes(verificationEmailUrl("tok_abc"), waitingFor = "req_123"))
        assertEquals(listOf("req_123", "desktop_req", "req_123"), asked)
    }
}
