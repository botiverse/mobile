package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedServerVersionGateTest {
    private class FakeVersionTransport(
        private val response: AuthNetworkResponse?,
        var requestedBaseUrl: String? = null
    ) : RaftAuthTransport by AuthDataSourceTest.FakeAuthApi() {
        override suspend fun serverVersion(): AuthNetworkResponse {
            return response ?: throw RuntimeException("network down")
        }
    }

    private fun probe(
        apiBaseUrl: String,
        response: AuthNetworkResponse?
    ): ServerVersionProbe {
        val transport = FakeVersionTransport(response)
        return runBlocking { SharedServerVersionGate.probe(apiBaseUrl) { transport } }
    }

    @Test
    fun parseVersionAcceptsNumericTriples() {
        assertEquals(listOf(1, 13, 1), SharedServerVersionGate.parseVersion("1.13.1"))
        assertEquals(listOf(2, 0), SharedServerVersionGate.parseVersion("v2.0"))
        assertNull(SharedServerVersionGate.parseVersion(""))
        assertNull(SharedServerVersionGate.parseVersion("1.x.0"))
        assertNull(SharedServerVersionGate.parseVersion("5c6f01bd"))
        assertNull(SharedServerVersionGate.parseVersion("999999999999999999999.0.0"))
    }

    @Test
    fun versionComparePadsMissingSegments() {
        assertTrue(SharedServerVersionGate.isVersionAtLeast("1.13.0", "1.13.0"))
        assertTrue(SharedServerVersionGate.isVersionAtLeast("1.13.1", "1.13.0"))
        assertTrue(SharedServerVersionGate.isVersionAtLeast("1.13", "1.13.0"))
        assertTrue(SharedServerVersionGate.isVersionAtLeast("2.0.0", "1.13.9"))
        assertFalse(SharedServerVersionGate.isVersionAtLeast("1.12.9", "1.13.0"))
        assertFalse(SharedServerVersionGate.isVersionAtLeast("unknown", "1.13.0"))
        assertFalse(SharedServerVersionGate.isVersionAtLeast("999999999999999999999.0.0", "1.13.0"))
    }

    @Test
    fun compatibleWhenServerAtBaseline() {
        val probe = probe("https://private.example.com/api/", AuthNetworkResponse(200, """{"service":"raft","version":"1.13.0"}"""))
        assertEquals(ServerVersionProbe.Compatible("1.13.0"), probe)
    }

    @Test
    fun incompatibleWhenServerBelowBaseline() {
        val probe = probe("https://private.example.com/api/", AuthNetworkResponse(200, """{"version":"1.12.0"}"""))
        assertEquals(ServerVersionProbe.Incompatible("1.12.0"), probe)
    }

    @Test
    fun missingVersionFieldRejected() {
        assertEquals(
            ServerVersionProbe.MissingVersion,
            probe("https://private.example.com/api/", AuthNetworkResponse(200, """{"service":"raft","revision":"abc"}"""))
        )
        assertEquals(
            ServerVersionProbe.MissingVersion,
            probe("https://private.example.com/api/", AuthNetworkResponse(200, "not json"))
        )
    }

    @Test
    fun raftSourceBuildIdentityWithoutVersionAccepted() {
        // raft-source packages/server/src/app.ts: `/api/version` answers the build identity only.
        assertEquals(
            ServerVersionProbe.Unversioned,
            probe(
                "https://private.example.com/api/",
                AuthNetworkResponse(200, """{"sha":"98ad0d78bb17dcced299c5217a44fc9cabacbf67","builtAt":"2026-10-15T13:03:00Z","branch":"main"}""")
            )
        )
        // Same server without RAFT_RELEASE_SHA / RAFT_BUILD_AT configured.
        assertEquals(
            ServerVersionProbe.Unversioned,
            probe(
                "https://private.example.com/api/",
                AuthNetworkResponse(503, null, """{"error":"Build identity unavailable","code":"build_identity_unavailable","sha":null}""")
            )
        )
        // A reported version decides even next to a build identity: garbage or overflow is refused, a low one too.
        assertEquals(
            ServerVersionProbe.MissingVersion,
            probe("https://private.example.com/api/", AuthNetworkResponse(200, """{"version":"garbage","sha":"98ad0d78bb17dcced299c5217a44fc9cabacbf67"}"""))
        )
        assertEquals(
            ServerVersionProbe.MissingVersion,
            probe("https://private.example.com/api/", AuthNetworkResponse(200, """{"version":"999999999999999999999.0.0","sha":"98ad0d78"}"""))
        )
        assertEquals(
            ServerVersionProbe.Incompatible("1.12.0"),
            probe("https://private.example.com/api/", AuthNetworkResponse(503, null, """{"code":"build_identity_unavailable","version":"1.12.0"}"""))
        )
        // A web app's catch-all HTML is not a Raft server; any other 503 is not either.
        assertEquals(ServerVersionProbe.MissingVersion, probe("https://private.example.com/api/", AuthNetworkResponse(200, "<html></html>")))
        assertEquals(ServerVersionProbe.MissingVersion, probe("https://private.example.com/api/", AuthNetworkResponse(200, """{"sha":"not-a-hash"}""")))
        // Only a string sha / version counts: Kuikly's optString would turn these into "1234567", "true", "{}", "[]".
        for (sha in listOf("1234567", "true", "{}", "[]")) {
            assertEquals(
                ServerVersionProbe.MissingVersion,
                probe("https://private.example.com/api/", AuthNetworkResponse(200, """{"sha":$sha}""")),
                "sha=$sha",
            )
        }
        assertEquals(
            ServerVersionProbe.MissingVersion,
            probe("https://private.example.com/api/", AuthNetworkResponse(200, """{"version":2,"sha":"98ad0d78"}"""))
        )
        assertEquals(ServerVersionProbe.Unreachable, probe("https://private.example.com/api/", AuthNetworkResponse(503, "{}")))
    }

    @Test
    fun unreachableOnHttpErrorOrNetworkFailure() {
        assertEquals(ServerVersionProbe.Unreachable, probe("https://private.example.com/api/", AuthNetworkResponse(404, null)))
        assertEquals(ServerVersionProbe.Unreachable, probe("https://private.example.com/api/", null))
        assertEquals(ServerVersionProbe.Unreachable, probe(" ", AuthNetworkResponse(200, """{"version":"9.9.9"}""")))
    }

    @Test
    fun customEnvironmentSaveRejectedWhenServerUnreachable() = runBlocking {
        val kv = InMemoryRaftKeyValueStore()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(),
                apiEnvironmentStore = SharedApiEnvironmentStore(kv),
                serverVersionProbe = { ServerVersionProbe.Unreachable }
            )
        val result =
            dataSource.saveApiEnvironment(
                SharedSettingsApiEnvironmentRequest("custom", "Private", "https://private.example.com/api/", "https://private.example.com", "https://private.example.com/web")
            )
        val error = assertIs<DataResult.Error>(result)
        assertEquals("settings_environment_server_unreachable", error.error.code)
        // The rejected environment must not be persisted.
        assertEquals("production", SharedApiEnvironmentStore(kv).current().key)
    }

    @Test
    fun customEnvironmentSaveRejectedWhenServerOutdated() = runBlocking {
        val kv = InMemoryRaftKeyValueStore()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(),
                apiEnvironmentStore = SharedApiEnvironmentStore(kv),
                serverVersionProbe = { ServerVersionProbe.Incompatible("1.12.0") }
            )
        val result =
            dataSource.saveApiEnvironment(
                SharedSettingsApiEnvironmentRequest("custom", "Private", "https://private.example.com/api/", "https://private.example.com", "https://private.example.com/web")
            )
        val error = assertIs<DataResult.Error>(result)
        assertEquals("settings_environment_server_outdated", error.error.code)
        assertEquals("production", SharedApiEnvironmentStore(kv).current().key)
    }

    @Test
    fun predefinedEnvironmentSkipsProbe() = runBlocking {
        var probed = false
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(),
                apiEnvironmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore()),
                serverVersionProbe = {
                    probed = true
                    ServerVersionProbe.Unreachable
                }
            )
        val result =
            dataSource.saveApiEnvironment(
                SharedSettingsApiEnvironmentRequest("staging", "Staging", "", "", "")
            )
        assertIs<DataResult.Content<*>>(result)
        assertFalse(probed)
    }
}
