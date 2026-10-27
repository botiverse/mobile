package build.raft.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftDesktopLoginCallbacksTest {
    @Test fun `the Mac app returns to its own raft-mac scheme`() {
        // The shared allowlist and parser are covered by AppLoginCallbacksTest (commonTest).
        assertEquals("raft-mac://login/callback", RaftDesktopSettingsCapability.APP_LOGIN_RETURN_URI)
    }

    @Test fun `only raft-mac arguments are callbacks and raft links belong to Raft Desktop`() {
        val args =
            listOf(
                "raft-mac://login/callback?requestId=r&code=c",
                "raft://login/callback?requestId=r&code=c",
                "raft://v1/channels/abc",
                "-psn_0_12345",
                "raft-macx://login/callback?requestId=r&code=c",
            )
        assertEquals(listOf("raft-mac://login/callback?requestId=r&code=c"), RaftDesktopLoginCallbacks.callbackArguments(args))
    }

    @Test fun `the packaged app registers raft-mac and not raft`() {
        val buildScript = java.io.File("build.gradle.kts").readText()
        val schemes = Regex("<string>([a-z-]+)</string></array>").findAll(buildScript).map { it.groupValues[1] }.toList()
        assertEquals(listOf(RaftDesktopLoginCallbacks.SCHEME), schemes)
    }
}
