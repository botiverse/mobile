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
    ) : SlockAuthTransport by AuthDataSourceTest.FakeAuthApi() {
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
    fun unreachableOnHttpErrorOrNetworkFailure() {
        assertEquals(ServerVersionProbe.Unreachable, probe("https://private.example.com/api/", AuthNetworkResponse(404, null)))
        assertEquals(ServerVersionProbe.Unreachable, probe("https://private.example.com/api/", null))
        assertEquals(ServerVersionProbe.Unreachable, probe(" ", AuthNetworkResponse(200, """{"version":"9.9.9"}""")))
    }

    @Test
    fun customEnvironmentSaveRejectedWhenServerUnreachable() = runBlocking {
        val kv = InMemorySlockKeyValueStore()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(),
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
        val kv = InMemorySlockKeyValueStore()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(),
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
                sessionStore = InMemorySlockAuthSessionStore(),
                apiEnvironmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore()),
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
