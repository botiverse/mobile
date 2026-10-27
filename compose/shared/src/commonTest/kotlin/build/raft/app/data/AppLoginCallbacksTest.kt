package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLoginCallbacksTest {
    @Test fun parsesCodeAndErrorCallbacksForEveryAllowedScheme() {
        for (returnUri in APP_LOGIN_RETURN_URIS) {
            assertEquals(
                AppLoginCallback(returnUri, "req-1", "abc", ""),
                parseAppLoginCallback("$returnUri?requestId=req-1&code=abc"),
            )
            assertEquals(
                AppLoginCallback(returnUri, "req-1", "", "access_denied"),
                parseAppLoginCallback("$returnUri?error=access_denied&requestId=req-1"),
            )
        }
    }

    @Test fun theOldComposeDesktopSchemeIsNotACallback() {
        assertNull(parseAppLoginCallback("raft-mac://login/callback?requestId=req-1&code=abc"))
        assertNull(parseAppLoginCallback("raft://oauth/callback?requestId=req-1&code=abc"))
    }

    @Test fun decodesPercentEscapesAndKeepsPlusLiteral() {
        val callback = parseAppLoginCallback("raft://login/callback?requestId=a%2Fb&code=x+y%E4%BD%A0")
        assertEquals("a/b", callback?.requestId)
        assertEquals("x+y你", callback?.code)
    }

    @Test fun rejectsAnythingThatIsNotExactlyOneLoginCallback() {
        val rejected =
            listOf(
                null,
                "raft://login/callback",
                "raft://login/callback?requestId=r",
                "raft://login/callback?requestId=r&code=c&error=e",
                "raft://login/callback?requestId=r&requestId=s&code=c",
                "raft://login/callback?requestId=r&code=",
                "raft://login/callback?requestId=r&code=c&state=s",
                "raft://login/callback?requestId=r&code=c#frag",
                "raft://login/callback?requestId=r&code=%2",
                "raft://login/callback?requestId=r&code=%zz",
                "raft://login/callback?requestId=r&code=%FF",
                "raft://login/callback?requestId=r&code",
                "raft://oauth/callback?requestId=r&code=c",
                "raft-evil://login/callback?requestId=r&code=c",
                "https://login/callback?requestId=r&code=c",
                "raft://login/callbackx?requestId=r&code=c",
            )
        for (uri in rejected) assertNull(parseAppLoginCallback(uri), "$uri")
    }

    @Test fun bridgeRejectsMalformedCallbacksWithoutStartingAnExchange() {
        assertEquals(false, RaftAppLoginBridge.completeCallbackUri("raft://oauth/callback?requestId=r&code=c"))
        assertEquals(false, RaftAppLoginBridge.completeCallback("raft-evil://login/callback", "r", "c", ""))
        assertEquals(false, RaftAppLoginBridge.completeCallback("raft://login/callback", "r", "c", "e"))
        assertEquals(false, RaftAppLoginBridge.completeCallback("raft://login/callback", " ", "c", ""))
        assertTrue(APP_LOGIN_RETURN_URIS.all { it.endsWith("://login/callback") })
    }

    private class CompletionStatus {
        var value: String? = null
        val writes = mutableListOf<String?>()
        val marker = AppLoginCompletionMarker({ value }, { value = it; writes += it }, { value = null; writes += null })
    }

    @Test fun aDuplicateCallbackNeverOverwritesASuccessfulSignIn() {
        // sindresorhus #3820: the first callback signed in; "back to app" tapped again sends a second.
        val status = CompletionStatus()
        status.marker.callbackAccepted()
        status.marker.callbackFinished("app_shell")
        status.marker.callbackAccepted()
        assertEquals("app_shell", status.value, "the duplicate must not replace the success")
        status.marker.callbackFinished("ignored")
        assertEquals("app_shell", status.value)
        assertEquals(listOf<String?>(APP_LOGIN_CALLBACK_RECEIVED, "app_shell"), status.writes)
    }

    @Test fun anIgnoredDuplicateWhileTheFirstIsStillExchangingKeepsTheMarker() {
        val status = CompletionStatus()
        status.marker.callbackAccepted()
        status.marker.callbackAccepted()
        status.marker.callbackFinished("ignored") // the duplicate found no request on file
        assertEquals(APP_LOGIN_CALLBACK_RECEIVED, status.value, "the first is still being exchanged")
        status.marker.callbackFinished("choose_server")
        assertEquals("choose_server", status.value)

        val lone = CompletionStatus()
        lone.marker.callbackAccepted()
        lone.marker.callbackFinished("ignored")
        assertNull(lone.value, "a lone ignored callback clears its marker")
    }

    @Test fun aCallbackAfterTheBrowserClosedStillMarksItself() {
        val status = CompletionStatus()
        status.marker.browserClosed()
        assertEquals(APP_LOGIN_BROWSER_CLOSED, status.value)
        status.marker.callbackAccepted()
        assertEquals(APP_LOGIN_CALLBACK_RECEIVED, status.value)
        status.marker.browserClosed()
        assertEquals(APP_LOGIN_CALLBACK_RECEIVED, status.value, "browser closed never overwrites a callback")
    }
}
