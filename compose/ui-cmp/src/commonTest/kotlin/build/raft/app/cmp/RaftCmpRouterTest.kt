package build.raft.app.cmp

import androidx.compose.runtime.Composable
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.designsystem.components.RaftPageCoverSignal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftCmpRouterTest {
    @AfterTest
    fun reset() {
        RaftCmpRouter.configure(debugBuild = false)
        RaftCmpRouter.installPageProvider { null }
    }

    @Test
    fun releaseBuildDoesNotAdmitTheDebugHelloPage() {
        RaftCmpRouter.configure(debugBuild = false)
        assertFalse(RaftCmpRouter.rendersOnCmp(RaftCmpPages.CMP_HELLO))
        assertNull(RaftCmpRouter.page(RaftCmpPages.CMP_HELLO))
    }

    @Test
    fun everyPageOfTheInstalledProviderRendersOnCmp() {
        val provided = setOf("compose_slock_app_shell", "compose_thread", "compose_settings_storage")
        val body: @Composable () -> Unit = {}
        RaftCmpRouter.installPageProvider { name -> if (name in provided) body else null }
        for (pageName in provided) {
            assertTrue(RaftCmpRouter.rendersOnCmp(pageName), pageName)
            assertNotNull(RaftCmpRouter.content(pageName), pageName)
            assertNull(RaftCmpRouter.page(pageName), pageName)
        }
        for (unknown in listOf("", "router", "compose_unknown")) {
            assertFalse(RaftCmpRouter.rendersOnCmp(unknown), unknown)
            assertNull(RaftCmpRouter.content(unknown), unknown)
        }
    }

    @Test
    fun aThrowingProviderCountsAsNoPage() {
        RaftCmpRouter.installPageProvider { error("registry unavailable") }
        assertFalse(RaftCmpRouter.rendersOnCmp("compose_thread"))
    }

    @Test
    fun firingPageCoverForwardsTopPageFromPlatformNavigator() {
        val expected = RaftCmpPageArgs("compose_thread", JsonObject(mapOf("instanceId" to JsonPrimitive("dm:1"))))
        var currentTop: RaftCmpPageArgs? = expected
        val transport =
            object : HostBridgeTransport {
                override fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?) = Unit
                override fun callSync(method: String, paramsJson: String): String? = null
            }
        val platformNavigator =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit
                override fun closePage() = Unit
                override fun topPage(): RaftCmpPageArgs? = currentTop
            }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), platformNavigator, bridgeTransport = transport)
        val wrapped = host.firingPageCover(RaftPageCoverSignal(), guard = null)

        assertEquals(expected, wrapped.navigator.topPage())
        currentTop = null
        assertNull(wrapped.navigator.topPage())
    }
}
