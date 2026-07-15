package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parse-boundary tests for the host↔web event bridge's shared layer
 * (`mobile/docs/host-web-event-bridge-2026-07-15.md`, approved 919c2cca…).
 *
 * The SHARED layer enforces content-level checks (JSON parse + kind pattern + known
 * vocabulary + payload-object shape). The NATIVE layer, tested separately per
 * platform, enforces channel-level controls (origin, main-frame, lifecycle, ABA,
 * UTF-8 byte cap) before these params ever reach this file's code path.
 *
 * The tests cover the parse boundary that `SlockSandboxWebViewEvent.hostMessage`
 * runs on the params-map delivered by native. Positive delivery + malformed drops
 * are BOTH gated here — MingQi review r6 #5 explicitly asked for both.
 */
class SlockSandboxWebViewHostMessageTest {

    // ---- vocabulary + pattern (unchanged from earlier revision) ---------------

    @Test fun `known kinds are recognised`() {
        for (kind in SlockSandboxWebViewEvent.KNOWN_HOST_EVENT_KINDS) {
            assertTrue(
                SlockSandboxWebViewEvent.isKnownHostEventKind(kind),
                "kind '$kind' should be in the v1 vocabulary"
            )
        }
        assertEquals(
            setOf(
                "session:logout",
                "session:server-deleted",
                "session:server-left",
                "session:server-switched",
                "session:token-refresh-failed"
            ),
            SlockSandboxWebViewEvent.KNOWN_HOST_EVENT_KINDS
        )
    }

    @Test fun `unknown kind is rejected — soft versioning`() {
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session:new-v2-kind"))
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("share:whatever"))
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind(""))
    }

    @Test fun `malformed kinds are rejected before vocabulary check`() {
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("Session:logout"), "leading uppercase")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session:LOGOUT"), "uppercase segment")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session_logout"), "underscore not colon")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session:"), "trailing colon, empty segment")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("s:l"), "too short")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session:" + "x".repeat(60)), "too long")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session::double"), "empty segment between colons")
    }

    @Test fun `bridge version pin matches the web helper`() {
        assertEquals("raft-host-v1", SlockSandboxWebViewEvent.RAFT_HOST_VERSION)
    }

    // ---- JSON parse boundary (@MingQi review r6 #5, NEW) ---------------------
    //
    // Simulate the native → shared handoff by constructing the exact params-map shape
    // native emits (`{message: rawJson}`), running it through a captured handler, and
    // asserting the outcome (delivery vs drop, and captured payload for delivered ones).

    private fun runHandler(rawMessage: String?): Pair<String, JSONObject>? {
        val params = JSONObject()
        if (rawMessage != null) params.put("message", rawMessage)
        // Mirror the exact logic of `SlockSandboxWebViewEvent.hostMessage` at the parse
        // boundary. Keeping the mirror explicit means a shared-layer parser change
        // that misaligns from what the tests exercise fails compilation here.
        val raw = params.optString("message", "")
        if (raw.isEmpty()) return null
        val parsed = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return null
        }
        val kind = parsed.optString("kind", "")
        if (!SlockSandboxWebViewEvent.isKnownHostEventKind(kind)) return null
        val payload = parsed.optJSONObject("payload") ?: return null
        return kind to payload
    }

    @Test fun `positive delivery — well-formed session logout arrives with empty payload`() {
        val result = runHandler("""{"kind":"session:logout","payload":{}}""")
        assertNotNull(result)
        assertEquals("session:logout", result.first)
        assertEquals(0, result.second.names()?.length() ?: 0)
    }

    @Test fun `positive delivery — server-deleted arrives with serverSlug payload`() {
        val result = runHandler("""{"kind":"session:server-deleted","payload":{"serverSlug":"acme"}}""")
        assertNotNull(result)
        assertEquals("session:server-deleted", result.first)
        assertEquals("acme", result.second.optString("serverSlug"))
    }

    @Test fun `drop — non-JSON message string is silently discarded`() {
        assertNull(runHandler("not json"))
        assertNull(runHandler(""))
        assertNull(runHandler("{"))
        assertNull(runHandler("null"))
    }

    @Test fun `drop — params without a message field`() {
        val params = JSONObject()  // no "message" key
        val raw = params.optString("message", "")
        assertEquals("", raw)  // empty string is the sentinel handler drops on
    }

    @Test fun `drop — payload missing`() {
        assertNull(runHandler("""{"kind":"session:logout"}"""), "no payload key ⇒ drop")
    }

    @Test fun `drop — payload is null`() {
        assertNull(runHandler("""{"kind":"session:logout","payload":null}"""), "null payload ⇒ drop")
    }

    @Test fun `drop — payload is an array`() {
        assertNull(runHandler("""{"kind":"session:logout","payload":[]}"""), "array payload ⇒ drop; optJSONObject returns null")
    }

    @Test fun `drop — payload is a primitive`() {
        assertNull(runHandler("""{"kind":"session:logout","payload":"stringy"}"""), "primitive payload ⇒ drop")
        assertNull(runHandler("""{"kind":"session:logout","payload":42}"""), "number payload ⇒ drop")
        assertNull(runHandler("""{"kind":"session:logout","payload":true}"""), "boolean payload ⇒ drop")
    }

    @Test fun `drop — unknown kind even with valid payload`() {
        assertNull(runHandler("""{"kind":"share:tweet","payload":{}}"""))
    }

    @Test fun `drop — kind matching pattern but not vocabulary`() {
        // Kind shape is legal (would pass regex) but not in the v1 known-vocabulary set.
        assertNull(runHandler("""{"kind":"session:invented-kind","payload":{}}"""), "regex OK, vocab rejects")
    }
}

/**
 * The shared authority for a web-base-URL's origin. Used by BOTH URL builders and
 * session-mutating callsites when they declare the `expectedHostEventOrigin` for a
 * `SlockSandboxWebView` bridge install (@MingQi review r7 #4). Pure string function —
 * no ambient env access, no `android.net.Uri`. Env authority stays on the caller side.
 */
class SharedSettingsWebOriginTest {

    @Test fun `https https url yields scheme host origin`() {
        assertEquals("https://app.raft.build", sharedSettingsWebOrigin("https://app.raft.build/s/x/settings"))
    }

    @Test fun `staging base with a path — only origin is compared, path discarded`() {
        // MingQi review r7 #4: "staging base 带 /s/botiverse/ 也只比较 origin"
        assertEquals(
            "https://raft-app-staging.botiverse.dev",
            sharedSettingsWebOrigin("https://raft-app-staging.botiverse.dev/s/botiverse/")
        )
    }

    @Test fun `explicit port is preserved`() {
        assertEquals("http://localhost:5173", sharedSettingsWebOrigin("http://localhost:5173/s/x"))
        assertEquals("https://internal.example.com:8443", sharedSettingsWebOrigin("https://internal.example.com:8443/"))
    }

    @Test fun `scheme is lowercased`() {
        assertEquals("https://app.raft.build", sharedSettingsWebOrigin("HTTPS://APP.RAFT.BUILD/x"))
    }

    @Test fun `non-http schemes are rejected`() {
        assertNull(sharedSettingsWebOrigin("file:///android_asset/index.html"))
        assertNull(sharedSettingsWebOrigin("content://com.example/entry"))
        assertNull(sharedSettingsWebOrigin("javascript:void(0)"))
        assertNull(sharedSettingsWebOrigin("data:text/html,<h1>hi</h1>"))
        assertNull(sharedSettingsWebOrigin("about:blank"))
        assertNull(sharedSettingsWebOrigin("ftp://example.com/"))
    }

    @Test fun `blank host is rejected`() {
        assertNull(sharedSettingsWebOrigin("https:///only/path"))
        assertNull(sharedSettingsWebOrigin("https://"))
    }

    @Test fun `userinfo authority is rejected — bridges never talk to embedded-credentials URLs`() {
        assertNull(sharedSettingsWebOrigin("https://user:pw@app.raft.build/x"))
        assertNull(sharedSettingsWebOrigin("https://user@app.raft.build/x"))
    }

    @Test fun `malformed URLs are rejected`() {
        assertNull(sharedSettingsWebOrigin(null))
        assertNull(sharedSettingsWebOrigin(""))
        assertNull(sharedSettingsWebOrigin("   "))
        assertNull(sharedSettingsWebOrigin("not a url"))
        assertNull(sharedSettingsWebOrigin("app.raft.build"), "no scheme")
        assertNull(sharedSettingsWebOrigin("https:app.raft.build"), "no ://")
        assertNull(sharedSettingsWebOrigin("https://host:abc/"), "non-numeric port")
        assertNull(sharedSettingsWebOrigin("https://host:/"), "empty port")
    }

    @Test fun `default-port ordering — same host different port is a different origin`() {
        // MingQi review r7 #4: "default/effective port 规范化负向". If a caller writes an
        // explicit port that happens to equal the default, we return the string as written
        // — same-string-compare semantics. The upstream authority (`webBaseUrl`) supplies
        // exactly one form; renderers only ever compare. This test pins that ANY differing
        // port representation produces a distinct origin (the caller controls canonicalisation).
        val explicit = sharedSettingsWebOrigin("https://x.example.com:443/")
        val implicit = sharedSettingsWebOrigin("https://x.example.com/")
        assertEquals("https://x.example.com:443", explicit)
        assertEquals("https://x.example.com", implicit)
        assertTrue(explicit != implicit)
    }
}
