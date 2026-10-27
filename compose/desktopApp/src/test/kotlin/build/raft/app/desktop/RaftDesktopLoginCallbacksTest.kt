package build.raft.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftDesktopLoginCallbacksTest {
    @Test fun `the Compose desktop app returns to the raft scheme`() {
        // The shared allowlist and parser are covered by AppLoginCallbacksTest (commonTest).
        assertEquals("raft://login/callback", RaftDesktopSettingsCapability.APP_LOGIN_RETURN_URI)
    }

    @Test fun `only raft login callbacks are callback arguments`() {
        val args =
            listOf(
                "raft://login/callback?requestId=r&code=c",
                "raft-mac://login/callback?requestId=r&code=c",
                "raft://v1/channels/abc",
                "-psn_0_12345",
                "raftx://login/callback?requestId=r&code=c",
            )
        assertEquals(listOf("raft://login/callback?requestId=r&code=c"), RaftDesktopLoginCallbacks.callbackArguments(args))
    }

    @Test fun `the packaged app registers only the raft scheme`() {
        val buildScript = java.io.File("build.gradle.kts").readText()
        val schemes = Regex("<string>([a-z-]+)</string></array>").findAll(buildScript).map { it.groupValues[1] }.toList()
        assertEquals(listOf(RaftDesktopLoginCallbacks.SCHEME), schemes)
    }
}
