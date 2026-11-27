package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftShellAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.childNavigationStoreScope
import build.raft.app.thread.THREAD_PAGE
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RaftShellStoreLaunchTest {
    @Test
    fun authenticatedChildOwnerSerializesOnlyEnvironmentAccountAndServerIdentity() {
        val pageData =
            JSONObject()
                .put("pageName", CHANNEL_SETTINGS_PAGE)
                .put("channelId", "channel-1")
                .withAuthenticatedRouteOwner(
                    AuthenticatedRouteOwner(
                        environmentId = " https://api.raft.build/api/ ",
                        accountId = " account-a ",
                        serverId = " server-a ",
                    )
                )

        assertEquals(
            AuthenticatedRouteOwner(
                environmentId = "https://api.raft.build/api",
                accountId = "account-a",
                serverId = "server-a",
            ),
            pageData.authenticatedRouteOwnerOrNull(),
        )
        val serialized = pageData.toString()
        assertFalse(serialized.contains("accessToken"))
        assertFalse(serialized.contains("refreshToken"))
        assertFalse(serialized.contains("authGeneration"))
        assertFalse(serialized.contains("selectionGeneration"))
    }

    @Test
    fun authenticatedChildOwnerRejectsMissingIdentity() {
        assertNull(JSONObject().authenticatedRouteOwnerOrNull())
        assertNull(
            JSONObject()
                .put(RAFT_ROUTE_ENVIRONMENT_ID, "https://api.raft.build/api")
                .put(RAFT_ROUTE_ACCOUNT_ID, "account-a")
                .authenticatedRouteOwnerOrNull()
        )
    }

    @Test
    fun dedicatedOwnerServerIdentityTakesPrecedenceOverLegacyRouteServer() {
        val scope =
            JSONObject()
                .put(RAFT_SHELL_INSTANCE_ID, "shell-a")
                .put(RAFT_SHELL_SERVER_ID, "server-owner")
                .put("serverId", "server-target")
                .raftShellStoreScope("fallback")

        assertEquals("server-owner", scope.initialServerId)
    }

    @Test
    fun childThreadRestoreSerializesLiveServerAndMakesNoRequestToBootstrapServer() = runBlocking {
        val sourceScope = RaftShellStoreScope.create("shell-thread-restore", "server-a")
        val liveSelection = SelectedServerStore(sourceScope)
        liveSelection.select("server-b", "two")

        val childPageData =
            JSONObject()
                .put("pageName", THREAD_PAGE)
                .withRaftShellStoreScope(childNavigationStoreScope(sourceScope, liveSelection))
        val restoredScope = childPageData.raftShellStoreScopeOrNull()
        assertEquals("server-b", restoredScope?.initialServerId)

        val processAuth =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@example.com"),
                    accessToken = "access",
                    refreshToken = "refresh",
                    selectedServerId = "server-b",
                    selectedServerSlug = "two",
                    servers =
                        listOf(
                            StoredAuthServer(id = "server-a", name = "A", slug = "one"),
                            StoredAuthServer(id = "server-b", name = "B", slug = "two")
                        )
                )
            )
        val restoredAuth =
            RaftShellAuthSessionStore(
                delegate = processAuth,
                selectedServerStore = SelectedServerStore(requireNotNull(restoredScope))
            )
        val requestedServers = mutableListOf<String?>()
        val network =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: build.raft.app.model.ServerId?,
                    bodyText: String?
                ): HomeNetworkResponse {
                    requestedServers += serverIdOverride?.value ?: restoredAuth.readSession()?.currentServerId
                    return HomeNetworkResponse(code = 404, bodyText = null)
                }
            }

        NetworkRaftThreadApi(network).messageContext("parent-message", "thread-stream")

        assertEquals(listOf<String?>("server-b"), requestedServers)
        assertFalse(requestedServers.contains("server-a"))
    }
}
