package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerSetupOnboardingContractTest {
    private val identity = ServerSetupAdmissionIdentity("env", "account", "server-1", "gen:1")

    @Test
    fun exactTwoArgumentPayloadDecodesAndMatchesCurrentIdentity() {
        val wake = decodeServerSetupCompletionWake(
            SERVER_SETUP_COMPLETION_KIND,
            JSONObject()
                .put("contractVersion", SERVER_SETUP_CONTRACT_VERSION)
                .put("serverId", "server-1")
                .put("serverSlug", "raft team")
                .put("generation", "gen:1"),
        )

        assertTrue(wake?.matches(identity, "raft team") == true)
        assertFalse(wake?.matches(identity.copy(generation = "gen:2"), "raft team") == true)
    }

    @Test
    fun wrongKindVersionShapeOrGenerationFailsClosed() {
        val validPayload = JSONObject()
            .put("contractVersion", SERVER_SETUP_CONTRACT_VERSION)
            .put("serverId", "server-1")
            .put("serverSlug", "raft")
            .put("generation", "gen:1")

        assertNull(decodeServerSetupCompletionWake("other", validPayload))
        assertNull(decodeServerSetupCompletionWake(SERVER_SETUP_COMPLETION_KIND, validPayload.toString()))
        assertNull(decodeServerSetupCompletionWake(SERVER_SETUP_COMPLETION_KIND, JSONObject(validPayload.toString()).put("contractVersion", "v2")))
        assertNull(decodeServerSetupCompletionWake(SERVER_SETUP_COMPLETION_KIND, JSONObject(validPayload.toString()).put("generation", "bad/value")))
        assertNull(decodeServerSetupCompletionWake(SERVER_SETUP_COMPLETION_KIND, JSONObject(validPayload.toString()).put("extra", true)))
        assertNull(decodeServerSetupCompletionWake(SERVER_SETUP_COMPLETION_KIND, JSONObject(validPayload.toString()).put("serverId", 1)))
    }

    @Test
    fun urlPercentEncodesPathAndQueryIndependently() {
        val encoded = mutableListOf<String>()
        val url = serverSetupOnboardingUrl("https://raft.test/", "my server", "gen:1", encode = {
            encoded += it
            it.replace(" ", "%20").replace(":", "%3A")
        })

        assertEquals(listOf("my server", "gen:1"), encoded)
        assertEquals("https://raft.test/s/my%20server/onboarding?generation=gen%3A1", url)
    }

    @Test
    fun canonicalSlugStaysUnresolvedUntilSelectedIdAppearsInFreshServerList() {
        val stale = session(selectedSlug = null, servers = emptyList())
        val refreshed =
            session(
                selectedSlug = null,
                servers = listOf(StoredAuthServer(id = "server-1", name = "New", slug = "t124z2143")),
            )
        val committed = refreshed.copy(selectedServerSlug = "t124z2143")

        assertNull(stale.canonicalServerSlugFor("server-1"))
        assertEquals("t124z2143", refreshed.canonicalServerSlugFor("server-1"))
        assertEquals("t124z2143", committed.canonicalServerSlugFor("server-1"))
        assertNull(refreshed.canonicalServerSlugFor("server-old"))
        assertNull(serverSetupOnboardingUrl("https://raft.test", "", "native:1") { it })
    }

    @Test
    fun tenantPathQueryAndFragmentAreDiscardedBeforeBuildingDedicatedRoute() {
        val url = serverSetupOnboardingUrl(
            "https://raft-app-staging.botiverse.dev/s/botiverse/?old=1#fragment",
            "ysbbeiqoqjbde",
            "native:7",
        ) { it.replace(":", "%3A") }

        assertEquals(
            "https://raft-app-staging.botiverse.dev/s/ysbbeiqoqjbde/onboarding?generation=native%3A7",
            url,
        )
    }

    @Test
    fun customHttpOriginsAreSupportedButMalformedOrUnsafeBasesFailClosed() {
        assertEquals("http://localhost:3000", serverSetupCanonicalWebOrigin("http://localhost:3000/dev/path"))
        assertEquals("http://[::1]:5173", serverSetupCanonicalWebOrigin("http://[::1]:5173/s/dev"))
        listOf(
            "file:///tmp/raft",
            "https://user@example.com/s/raft",
            "https://example.com:bad/s/raft",
            "https://example.com:70000/s/raft",
            "https://bad_host.example/s/raft",
            "https://-bad.example/s/raft",
            "https://example.com\\evil/s/raft",
            "not a url",
        ).forEach { assertNull(serverSetupCanonicalWebOrigin(it), it) }
    }

    @Test
    fun syntheticBridgeNavigationDecodesOnlyExactKeysAndPayload() {
        val payload = """{"contractVersion":"raft-onboarding-v1","serverId":"server-1","serverSlug":"raft","generation":"gen:1"}"""
        val encoded = payload.replace(":", "%3A").replace("{", "%7B").replace("}", "%7D").replace("\"", "%22")
        val url = "$SERVER_SETUP_BRIDGE_SCHEME://emit?kind=$SERVER_SETUP_COMPLETION_KIND&payload=$encoded"
        val wake = decodeServerSetupBridgeNavigation(url) { value ->
            value.replace("%3A", ":").replace("%7B", "{").replace("%7D", "}").replace("%22", "\"")
        }
        assertEquals(ServerSetupCompletionWake("server-1", "raft", "gen:1"), wake)
        assertNull(decodeServerSetupBridgeNavigation("$url&extra=1") { it })
    }

    private fun session(
        selectedSlug: String?,
        servers: List<StoredAuthServer>,
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "account", email = "account@raft.test"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            selectedServerSlug = selectedSlug,
            servers = servers,
        )
}
