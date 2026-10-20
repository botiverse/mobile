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
}
