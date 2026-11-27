package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsWebViewAuthBootstrapTest {
    @Test
    fun authenticatedBootstrapRemovesPersistedRefreshOwnerWithoutExposingNativeToken() {
        val session = session()
        val binding = hostBinding(session)
        val storage =
            mutableMapOf(
                "slock_access_token" to "stale-access",
                "slock_refresh_token" to "stale-web-refresh",
                "slock_last_server_slug" to "stale-server",
            )

        settingsWebViewAuthStorageMutations(session, binding).forEach { mutation ->
            mutation.value?.let { storage[mutation.key] = it } ?: storage.remove(mutation.key)
        }

        assertEquals(session.accessToken, storage["slock_access_token"])
        assertEquals(session.selectedServerSlug, storage["slock_last_server_slug"])
        assertFalse(storage.containsKey("slock_refresh_token"))
        assertEquals(
            "{\"accountId\":\"user-1\",\"serverId\":\"server-1\",\"sessionGeneration\":42,\"webViewGeneration\":7}",
            storage[RAFT_HOST_AUTH_BINDING_STORAGE_KEY],
        )
        assertEquals("native-refresh-secret", session.refreshToken)
    }

    @Test
    fun missingSessionOrBindingRemovesStaleHostBinding() {
        val storage =
            mutableMapOf(
                RAFT_HOST_AUTH_BINDING_STORAGE_KEY to "stale",
                "slock_access_token" to "stale-access",
            )

        settingsWebViewAuthStorageMutations(session = null, hostBinding = null).forEach { mutation ->
            mutation.value?.let { storage[mutation.key] = it } ?: storage.remove(mutation.key)
        }

        assertFalse(storage.containsKey(RAFT_HOST_AUTH_BINDING_STORAGE_KEY))
        assertFalse(storage.containsKey("slock_access_token"))
    }

    @Test
    fun standaloneSettingsInjectedScriptGuardsTopLevelAndNormalizedOriginAcrossAllSurfaces() {
        val session = session()
        val binding = hostBinding(session)
        val surfaces =
            listOf(
                SettingsWebViewSurface.Billing,
                SettingsWebViewSurface.Marketplace,
                SettingsWebViewSurface.Administration,
                SettingsWebViewSurface.ImBridges,
                SettingsWebViewSurface.AccountDeletion,
                SettingsWebViewSurface.DataPrivacy,
                SettingsWebViewSurface.Computers,
            )
        surfaces.forEach { surface ->
            val script =
                standaloneSettingsWebViewInjectedJavaScript(
                    session = session,
                    hostBinding = binding,
                    surface = surface,
                    webOrigin = "https://APP.RAFT.BUILD:443/s/workspace/settings/${surface.diagnosticsName}?embed=raft-settings-v1",
                )
            val guard = "if (window.top !== window || window.location.origin !== \"https://app.raft.build\") return;"
            val guardIndex = script.indexOf(guard)
            val tokenIndex = script.indexOf("slock_access_token")
            assertTrue(guardIndex >= 0, "missing guard for $surface: $script")
            assertTrue(tokenIndex > guardIndex, "token write must follow guard for $surface: $script")
            assertFalse(script.contains(session.refreshToken))
        }

        val customPortScript =
            standaloneSettingsWebViewInjectedJavaScript(
                session = session,
                hostBinding = binding,
                surface = SettingsWebViewSurface.Billing,
                webOrigin = "http://127.0.0.1:3000/s/workspace/settings/billing",
            )
        assertContains(
            customPortScript,
            "if (window.top !== window || window.location.origin !== \"http://127.0.0.1:3000\") return;",
        )
    }

    @Test
    fun standaloneSettingsAndServerSetupInjectedScriptsFailClosedWithoutValidOrigin() {
        val session = session()
        val binding = hostBinding(session)
        val identity =
            ServerSetupAdmissionIdentity(
                environmentId = "https://api.raft.build",
                accountId = "user-1",
                serverId = "server-1",
                generation = "native:1",
            )
        listOf("", "   ", "about:blank", "javascript:alert(1)", "file:///etc/passwd", "not-a-url").forEach { badOrigin ->
            assertEquals(
                "",
                standaloneSettingsWebViewInjectedJavaScript(session, binding, SettingsWebViewSurface.Marketplace, badOrigin),
                "expected fail-closed for $badOrigin",
            )
            assertEquals(
                "",
                serverSetupInjectedJavaScript(session, binding, identity, badOrigin),
                "expected server-setup fail-closed for $badOrigin",
            )
            assertEquals(
                "",
                settingsWebViewRuntimeAccessTokenJavaScript(
                    accessToken = "access-next",
                    accountId = "user-1",
                    serverId = "server-1",
                    sessionGeneration = 42L,
                    webViewGeneration = 7L,
                    sequence = 1L,
                    webOrigin = badOrigin,
                ),
                "expected runtime token sync fail-closed for $badOrigin",
            )
        }

        val setupScript =
            serverSetupInjectedJavaScript(
                session = session,
                binding = binding,
                identity = identity,
                webOrigin = "https://app.raft.build/s/workspace/setup",
            )
        val guard = "if (window.top !== window || window.location.origin !== \"https://app.raft.build\") return;"
        assertTrue(setupScript.indexOf(guard) >= 0, setupScript)
        assertTrue(setupScript.indexOf("slock_access_token") > setupScript.indexOf(guard), setupScript)

        val runtimeScript =
            settingsWebViewRuntimeAccessTokenJavaScript(
                accessToken = "access-next",
                accountId = "user-1",
                serverId = "server-1",
                sessionGeneration = 42L,
                webViewGeneration = 7L,
                sequence = 2L,
                webOrigin = "https://app.raft.build/s/workspace/settings/billing",
            )
        assertTrue(runtimeScript.indexOf(guard) >= 0, runtimeScript)
        assertTrue(runtimeScript.indexOf("slock_access_token") > runtimeScript.indexOf(guard), runtimeScript)
    }

    private fun session() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test"),
            accessToken = "native-access",
            refreshToken = "native-refresh-secret",
            selectedServerId = "server-1",
            selectedServerSlug = "workspace",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = "workspace")),
        )

    private fun hostBinding(session: StoredAuthSession): SettingsWebViewHostAuthBinding {
        val fence =
            SettingsWebViewAccessTokenFence(
                initialSession = session,
                sessionGeneration = 42L,
                webViewGeneration = 7L,
            )
        return SettingsWebViewHostAuthBinding(
            accountId = "user-1",
            serverId = "server-1",
            sessionGeneration = 42L,
            webViewGeneration = 7L,
            fence = fence,
        )
    }
}
