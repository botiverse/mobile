package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sign-up email link iOS reopens in the web sign-in sheet ([isRaftAppLoginVerificationLink]). */
class RaftAppLoginVerificationLinkTest {
    // slock `verificationEmailUrl` for a sign-up started at /login/app?requestId=req_123 (URLSearchParams encoding).
    private val appReturnTo = "returnTo=%2Flogin%2Fapp%3FrequestId%3Dreq_123"

    private fun accepts(url: String, configuredWebUrl: String? = null) =
        isRaftAppLoginVerificationLink(url, configuredWebUrl, appHosts = emptySet())

    @Test
    fun acceptsTheEmailLinkOfAnAppSignUpOnProductionHosts() {
        assertTrue(accepts("https://app.raft.build/?verify=tok_abc&$appReturnTo"))
        assertTrue(accepts("https://app.raft.build?verify=tok_abc&$appReturnTo"))
        assertTrue(accepts("https://open.raft.build/?$appReturnTo&verify=tok_abc"))
        assertTrue(accepts("https://APP.RAFT.BUILD/?verify=tok_abc&returnTo=/login/app?requestId=req_123"))
    }

    @Test
    fun aWebSignUpLinkIsNotAnAppSignInToResume() {
        assertFalse(accepts("https://app.raft.build/?verify=tok_abc"))
        assertFalse(accepts("https://app.raft.build/?verify=tok_abc&returnTo=%2Fartifacts%2Fpt_abc%2Fv%2F2"))
        assertFalse(accepts("https://app.raft.build/?verify=tok_abc&returnTo=%2Flogin%2Fapp"))
        assertFalse(accepts("https://app.raft.build/?verify=tok_abc&returnTo=%2Flogin%2Fapp%3FrequestId%3D"))
        assertFalse(accepts("https://app.raft.build/?verify=tok_abc&returnTo=%2Flogin%2Fapp%2Fx%3FrequestId%3Dreq_123"))
    }

    @Test
    fun needsAVerifyTokenOnTheRootPath() {
        assertFalse(accepts("https://app.raft.build/?$appReturnTo"))
        assertFalse(accepts("https://app.raft.build/?verify=&$appReturnTo"))
        assertFalse(accepts("https://app.raft.build/login/app?verify=tok_abc&$appReturnTo"))
        assertFalse(accepts("https://app.raft.build/s/x/channel/y?verify=tok_abc&$appReturnTo"))
        assertFalse(accepts("https://app.raft.build/?verify=%zz&$appReturnTo"))
    }

    @Test
    fun otherOriginsOnlyAsTheConfiguredWebUrl() {
        val staging = "https://raft-app-staging.botiverse.dev/?verify=tok_abc&$appReturnTo"
        assertFalse(accepts(staging))
        assertTrue(accepts(staging, configuredWebUrl = "https://raft-app-staging.botiverse.dev"))
        assertFalse(accepts("http://app.raft.build/?verify=tok_abc&$appReturnTo"))
        assertFalse(accepts("https://evil.example/?verify=tok_abc&$appReturnTo"))
        assertFalse(accepts("https://user@app.raft.build/?verify=tok_abc&$appReturnTo"))
    }
}
