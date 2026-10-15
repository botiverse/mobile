package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class WebLoginHintHostTest {
    @Test fun showsTheCurrentWebHost() {
        assertEquals("app.raft.build", webLoginHintHost("https://app.raft.build"))
        assertEquals("raft.company.com:8443", webLoginHintHost("https://raft.company.com:8443/app/?x=1"))
        assertEquals("app.raft.build", webLoginHintHost(""), "no web address → the official web app")
    }
}
