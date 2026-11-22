package build.raft.app.core.data.auth

import build.raft.app.data.InMemoryAuthSessionSnapshot
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.StoredQaBootstrapMetadata
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receipt against the vector shared with Testbed and scripts/qa/app_identity_receipt.test.mjs
 * (scripts/qa/fixtures/app-identity-receipt-vector.json). Fake values only.
 */
class AppIdentityReceiptVectorTest {
    private val vector = JSONObject(locateRepoFile("scripts/qa/fixtures/app-identity-receipt-vector.json").readText())
    private val fixture = vector.getJSONObject("fixture")
    private val sample = vector.getJSONObject("sample_receipt")
    private val nonce = fixture.getString("nonce")

    private val request =
        AppIdentityReceiptRequest(
            attemptId = sample.getString("attempt_id"),
            nonce = nonce,
            requestId = sample.getString("request_id")
        )

    private val facts =
        AppIdentityFacts(
            packageName = "build.raft.app",
            versionName = "1.26.0",
            versionCode = 12600,
            signerSha256 = sha256("FAKE-CERT-DER-NOT-A-REAL-CERT".encodeToByteArray()),
            debuggable = false,
            pid = 4242,
            processStartTicks = "987654",
            session = null
        )

    private fun session(
        selectedServerId: String? = fixture.getString("server_id"),
        selectedServerSlug: String? = fixture.getString("server_slug"),
        servers: List<StoredAuthServer> = emptyList(),
        qaBootstrap: StoredQaBootstrapMetadata? = null
    ) = StoredAuthSession(
        user = StoredAuthUser(id = fixture.getString("subject"), email = "fake@example.invalid", displayName = "Fake Person"),
        accessToken = "fake-access-token-value",
        refreshToken = "fake-refresh-token-value",
        selectedServerId = selectedServerId,
        selectedServerSlug = selectedServerSlug,
        servers = servers,
        qaBootstrap = qaBootstrap
    )

    @Test
    fun refsMatchTheSharedVector() {
        assertEquals(vector.getString("user_ref_domain"), APP_IDENTITY_USER_REF_DOMAIN)
        assertEquals(vector.getString("server_ref_domain"), APP_IDENTITY_SERVER_REF_DOMAIN)
        assertEquals(fixture.getString("user_ref"), appIdentityUserRef(nonce, fixture.getString("subject")))
        assertEquals(
            fixture.getString("server_ref"),
            appIdentityServerRef(nonce, fixture.getString("server_id"), fixture.getString("server_slug"))
        )
    }

    // Second shared vector (scripts/qa/fixtures/app-identity-receipt-pairing-vector.json).
    private val pairing = JSONObject(locateRepoFile("scripts/qa/fixtures/app-identity-receipt-pairing-vector.json").readText())

    private fun pairedRequest(salt: String = pairing.getString("pairing_salt"), withNonce: String = nonce) =
        request.copy(nonce = withNonce, pairingSalt = salt)

    @Test
    fun pairingReceiptIsThePairingVectorByteForByteLength() {
        val expected = pairing.getJSONObject("receipt")
        val text = buildAppIdentityReceipt(pairedRequest(), facts, session())
        val parsed = JSONObject(text)
        assertEquals(expected.keys().asSequence().toList(), parsed.keys().asSequence().toList())
        for (key in expected.keys()) assertEquals(key, expected.get(key), parsed.get(key))
        assertEquals(pairing.getInt("extended_bytes"), text.encodeToByteArray().size)
        assertEquals(pairing.getInt("original_bytes"), buildAppIdentityReceipt(request, facts, session()).encodeToByteArray().size)
        assertFalse("the salt is never echoed", text.contains(pairing.getString("pairing_salt")))
    }

    @Test
    fun receiptNeverCarriesEmailTokensNamesRawUserIdOrPlainServer() {
        val text = buildAppIdentityReceipt(request, facts, session())
        val forbiddenValues =
            listOf(
                "fake@example.invalid", "Fake Person", "fake-access-token-value", "fake-refresh-token-value",
                "\"qa-user\"", "server-1", "\"test\""
            )
        forbiddenValues.forEach { assertFalse("receipt must not contain $it", text.contains(it)) }
        val keys = JSONObject(text).keys().asSequence().toList()
        // No key names an email, token, cookie, person/server name, raw id or slug ("version_name" is the build's).
        val forbiddenKeyWords = listOf("email", "token", "cookie", "display", "user_id", "server_id", "slug")
        forbiddenKeyWords.forEach { word -> assertTrue("no key may name $word", keys.none { it.contains(word) }) }
        assertTrue("only version_name may be a name", keys.filter { it.endsWith("name") } == listOf("version_name"))
    }

    @Test
    fun malformedRequestsAreRefused() {
        val bad =
            listOf(
                validExtras() + ("extra" to "x"),
                validExtras() - "nonce",
                validExtras() + ("attempt_id" to "run-1"),
                validExtras() + ("nonce" to "AB".repeat(32)),
                validExtras() + ("nonce" to "\"},\"user_ref\":\"x" + "a".repeat(48)),
                validExtras() + ("request_id" to "x"),
                emptyMap()
            )
        for (extras in bad) {
            val result = AppIdentityReceiptResponder().respond(2000, "receipt_v1", null, extras) { error("must not read") }
            assertEquals(extras.toString(), AppIdentityReceiptResult.Rejected("bad_request"), result)
        }
        assertEquals(AppIdentityReceiptResult.Rejected("bad_request"), AppIdentityReceiptResponder().respond(2000, "receipt_v1", null, null) { error("must not read") })
        assertEquals(AppIdentityReceiptResult.Rejected("bad_request"), AppIdentityReceiptResponder().respond(2000, "receipt_v1", "arg", validExtras()) { error("must not read") })
        assertEquals(AppIdentityReceiptResult.Rejected("unknown_method"), AppIdentityReceiptResponder().respond(2000, "receipt_v2", null, validExtras()) { error("must not read") })
    }

    @Test
    fun eachRequestIdIsAnsweredOnceAndAProcessAnswersAtMostSixteen() {
        val responder = AppIdentityReceiptResponder()
        val read = { facts.copy(session = InMemoryAuthSessionSnapshot(null)) }
        assertTrue(responder.respond(2000, "receipt_v1", null, validExtras(), read) is AppIdentityReceiptResult.Receipt)
        assertEquals(AppIdentityReceiptResult.Rejected("request_replayed"), responder.respond(2000, "receipt_v1", null, validExtras(), read))
        for (i in 1 until AppIdentityReceiptResponder.MAX_ANSWERS_PER_PROCESS) {
            assertTrue(responder.respond(2000, "receipt_v1", null, validExtras(requestId = "%032x".format(i)), read) is AppIdentityReceiptResult.Receipt)
        }
        assertEquals(AppIdentityReceiptResult.Rejected("rate_limited"), responder.respond(2000, "receipt_v1", null, validExtras(requestId = "%032x".format(99)), read))
    }

    private fun validExtras(requestId: String = sample.getString("request_id")) =
        mapOf("attempt_id" to sample.getString("attempt_id"), "nonce" to nonce, "request_id" to requestId)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** A file by its repository-relative path, found by walking up from the test's working directory. */
internal fun locateRepoFile(relativePath: String): File {
    var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    while (current != null) {
        val candidate = File(current, relativePath)
        if (candidate.isFile) return candidate
        current = current.parentFile
    }
    error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
}
