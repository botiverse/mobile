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
import kotlin.test.assertIs

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
        val url = serverSetupOnboardingUrl("https://raft.test/", "my server", "gen:1")

        assertEquals("https://raft.test/s/my%20server/onboarding?generation=gen%3A1", url)
    }

    @Test
    fun sharedEncoderHandlesUtf8AndBlankSlugNeverBuildsAUrl() {
        assertEquals(
            "https://raft.test/s/%E7%AD%8F/onboarding?generation=native%3A1",
            serverSetupOnboardingUrl("https://raft.test", "筏", "native:1"),
        )
        assertNull(serverSetupOnboardingUrl("https://raft.test", "", "native:1"))
    }

    @Test
    fun tenantPathQueryAndFragmentAreDiscardedBeforeBuildingDedicatedRoute() {
        val url = serverSetupOnboardingUrl(
            "https://raft-app-staging.botiverse.dev/s/botiverse/?old=1#fragment",
            "ysbbeiqoqjbde",
            "native:7",
        )

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

    @Test
    fun malformedPercentEncodingFailsClosedWithoutEscapingDecoderException() {
        val malformed = "$SERVER_SETUP_BRIDGE_SCHEME://emit?kind=$SERVER_SETUP_COMPLETION_KIND&payload=%"
        assertNull(
            decodeServerSetupHostBridgeNavigation(malformed) { value ->
                if (value == "%") error("malformed percent encoding") else value
            }
        )
    }

    @Test
    fun switchIntentRequiresExactSourceTargetGenerationAndSlug() {
        val payload = JSONObject()
            .put("contractVersion", SERVER_SETUP_CONTRACT_VERSION)
            .put("generation", "gen:1")
            .put("sourceServerId", "server-1")
            .put("targetServerId", "server-2")
        val event = assertIs<ServerSetupHostEvent.SwitchServer>(
            decodeServerSetupHostEvent(SERVER_SETUP_SERVER_SWITCH_KIND, payload)
        )

        assertTrue(event.intent.matchesSource(identity))
        assertFalse(event.intent.matchesSource(identity.copy(generation = "gen:2")))
        assertNull(
            decodeServerSetupHostEvent(
                SERVER_SETUP_SERVER_SWITCH_KIND,
                JSONObject(payload.toString()).put("sourceServerId", "server-2"),
            )
        )
    }

    @Test
    fun hostContextIsImmutableAndControlledDocumentMustStayOnConfiguredOrigin() {
        val script = serverSetupHostBridgeJavaScript(identity)
        assertTrue(script.contains("onboarding: Object.freeze"))
        assertTrue(script.contains("generation: \"gen:1\""))
        assertTrue(script.contains("sourceServerId: \"server-1\""))
        assertTrue(
            serverSetupControlledDocumentMatchesOrigin(
                "https://raft.test/s/other/onboarding",
                "https://raft.test/s/tenant/",
            )
        )
        assertFalse(
            serverSetupControlledDocumentMatchesOrigin(
                "https://evil.test/s/other/onboarding",
                "https://raft.test/s/tenant/",
            )
        )
    }

    @Test
    fun switchTargetRequiresActiveControlledCurrentAccountMembership() {
        val session = StoredAuthSession(
            user = StoredAuthUser(id = "account", email = "a@raft.test"),
            accessToken = "access",
            refreshToken = "refresh",
            servers = listOf(
                StoredAuthServer(id = "server-1", name = "One", slug = "one"),
                StoredAuthServer(id = "server-2", name = "Two", slug = "two"),
            ),
            selectedServerId = "server-1",
            selectedServerSlug = "one",
        )
        val current = ServerSetupSwitchIntent("server-1", "server-2", "gen:1")

        assertEquals("server-2", serverSetupSwitchTargetId(session, identity, current, true, true))
        assertNull(serverSetupSwitchTargetId(session, identity, current.copy(generation = "old"), true, true))
        assertNull(serverSetupSwitchTargetId(session, identity, current.copy(targetServerId = "unknown"), true, true))
        assertNull(serverSetupSwitchTargetId(session, identity, current, activeOwner = false, controlledDocument = true))
        assertNull(serverSetupSwitchTargetId(session, identity, current, activeOwner = true, controlledDocument = false))
        assertNull(
            serverSetupSwitchTargetId(
                session.copy(user = session.user.copy(id = "other")),
                identity,
                current,
                true,
                true,
            )
        )
    }

}
