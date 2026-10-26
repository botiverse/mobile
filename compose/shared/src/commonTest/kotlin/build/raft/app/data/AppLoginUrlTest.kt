package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLoginUrlTest {
    @Test
    fun httpsAndLoopbackHttpLoginPagesAreAllowed() {
        assertTrue(isAllowedAppLoginUrl("https://app.raft.build/login/app?request=r"))
        assertTrue(isAllowedAppLoginUrl("http://localhost:15253/login/app?request=r"))
        assertTrue(isAllowedAppLoginUrl("http://127.0.0.1:8080/login/app?request=r"))
    }

    @Test
    fun otherSchemesAndRemoteHttpAreRejected() {
        assertFalse(isAllowedAppLoginUrl("http://app.raft.build/login/app?request=r"))
        assertFalse(isAllowedAppLoginUrl("http://localhost.evil.example/login/app"))
        assertFalse(isAllowedAppLoginUrl("javascript:alert(1)"))
        assertFalse(isAllowedAppLoginUrl("raft://login/callback"))
    }
}
