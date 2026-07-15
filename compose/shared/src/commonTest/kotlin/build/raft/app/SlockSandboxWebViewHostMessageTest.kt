package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parse-boundary tests for the host↔web event bridge's shared layer
 * (mobile/docs/host-web-event-bridge-2026-07-15.md, approved 919c2cca…).
 *
 * The SHARED layer enforces content-level checks (kind pattern + known vocabulary +
 * payload-object shape). The NATIVE layer, tested separately per-platform, enforces
 * channel-level controls (origin, main-frame, lifecycle, ABA, UTF-8 byte cap) before
 * these params ever reach here.
 *
 * The shared handler is `SlockSandboxWebViewEvent.hostMessage(handler)`. It drops
 * malformed input silently — the caller only sees well-formed (kind, payload) pairs.
 */
class SlockSandboxWebViewHostMessageTest {

    @Test fun `known kinds are recognised`() {
        for (kind in SlockSandboxWebViewEvent.KNOWN_HOST_EVENT_KINDS) {
            assertTrue(
                SlockSandboxWebViewEvent.isKnownHostEventKind(kind),
                "kind '$kind' should be in the v1 vocabulary"
            )
        }
        // Sanity: the vocabulary is exactly what the design promised.
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
        // Pattern floor: `^[a-z][a-z0-9]*(:[a-z][a-z0-9-]*)+$`, length 4–64.
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("Session:logout"), "leading uppercase")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session:LOGOUT"), "uppercase segment")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session_logout"), "underscore not colon")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session:"), "trailing colon, empty segment")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("s:l"), "too short")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session:" + "x".repeat(60)), "too long")
        assertFalse(SlockSandboxWebViewEvent.isKnownHostEventKind("session::double"), "empty segment between colons")
    }

    @Test fun `bridge version pin matches the web helper`() {
        // Web checks `window.RaftHost.version === "raft-host-v1"`. If this drifts, web
        // helpers treat the bridge as absent and every emit becomes a no-op. Pinning
        // the version string here means a rename requires touching both sides.
        assertEquals("raft-host-v1", SlockSandboxWebViewEvent.RAFT_HOST_VERSION)
    }

    // The `hostMessage` register-path test lives on each platform's integration test —
    // it exercises the Kuikly event-bus registration + params-map delivery end-to-end.
    // The shared unit tests above are enough to pin the vocabulary and pattern floor
    // without a KMP-only mock of the register machinery.
}
