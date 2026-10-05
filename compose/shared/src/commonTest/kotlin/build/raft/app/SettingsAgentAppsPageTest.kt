package build.raft.app

import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsAgentAppsPageTest {
    private val target = SettingsAgentAppsRouteIdentity("user-a", "server-b", "agent-1")
    private fun session() = StoredAuthSession(
        user = StoredAuthUser("user-a", "a@example.test"),
        accessToken = "test-access", refreshToken = "test-refresh",
        selectedServerId = "server-a", selectedServerSlug = "alpha",
        servers = listOf(
            StoredAuthServer(id = "server-a", slug = "alpha", name = "Alpha"),
            StoredAuthServer(id = "server-b", slug = "beta", name = "Beta"),
        ),
    )

    @Test fun opensTargetServerInsteadOfCurrentServerWithCanonicalWebTab() {
        assertEquals(
            "https://staging.raft.build/s/beta/agent/agent-1?embed=raft-settings-v1&shell=host&agentTab=integrations",
            settingsAgentAppsWebViewUrl("https://staging.raft.build/", session(), target),
        )
        assertEquals(
            settingsAgentAppsWebViewUrl(null, session(), target),
            settingsAgentAppsWebViewUrl(null, session().copy(accessToken = "rotated"), target),
        )
    }

    @Test fun changedAccountOrMissingServerCannotOpenOldTarget() {
        val spec = settingsAgentAppsWebViewSpec(target)
        assertEquals("server-b", spec.resolveSession(session())?.currentServerId)
        val other = session().copy(user = StoredAuthUser("user-b", "b@example.test"))
        assertNull(spec.resolveSession(other))
        assertNull(settingsAgentAppsWebViewUrl(null, other, target))
        assertNull(spec.resolveSession(session().copy(servers = emptyList())))
        assertNull(settingsAgentAppsWebViewUrl(null, null, target))
    }

    @Test fun pageBindingUsesTargetServerWithoutChangingNativeSelection() {
        val native = session()
        val bound = settingsAgentAppsBoundSession(native, target)!!
        assertEquals("server-b", bound.currentServerId)
        assertEquals("beta", bound.selectedServerSlug)
        assertEquals("server-a", native.currentServerId)
        assertEquals("test-access", bound.accessToken)
        val rotated = settingsAgentAppsBoundSession(native.copy(accessToken = "new-token"), target)!!
        assertEquals(bound.currentServerId, rotated.currentServerId)
        assertEquals("new-token", rotated.accessToken)
    }

    @Test fun routeCarriesIdentityOnlyAndRejectsIncompleteOrUnsafeTargets() {
        val params = settingsAgentAppsPageData(SlockShellStoreScope.create("apps-test"), target)
        assertEquals(target, settingsAgentAppsRouteIdentityFromPageData(params))
        assertFalse(params.toString().contains("test-access"))
        assertFalse(params.toString().contains("test-refresh"))
        assertNull(settingsAgentAppsRouteIdentityFromPageData(JSONObject()))
        for (agent in listOf("", "..", "a/b", "a\\b", "a b")) {
            assertNull(settingsAgentAppsWebViewUrl(null, session(), target.copy(agentId = agent)))
        }
        assertTrue(settingsAgentAppsWebViewUrl(null, session(), target.copy(agentId = "a?b#c"))!!.contains("a%3Fb%23c?"))
    }
}
