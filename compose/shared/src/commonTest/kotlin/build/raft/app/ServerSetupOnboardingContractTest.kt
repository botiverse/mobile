package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
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
}
