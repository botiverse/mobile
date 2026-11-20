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

    @Test
    fun everyEqualityControlFromTheVectorDoesNotMatch() {
        val controls = vector.getJSONArray("controls")
        assertEquals(6, controls.length())
        for (i in 0 until controls.length()) {
            val control = controls.getJSONObject(i)
            val name = control.getString("name")
            if (control.getString("ref") == "user") {
                assertNotEquals(name, fixture.getString("user_ref"), appIdentityUserRef(control.getString("nonce"), control.getString("subject")))
            } else {
                val ref = appIdentityServerRef(control.getString("nonce"), control.getString("server_id"), control.getString("server_slug"))
                assertNotEquals(name, fixture.getString("server_ref"), ref)
            }
        }
    }

    @Test
    fun selectedServerWithoutAnySlugGivesNoServerRef() {
        // Neither a stored slug nor one on the selected server: the receipt marks it (null), it
        // never hashes "" (the vector's "missing slug" control), so the runner cannot pass.
        val noSlug = listOf(StoredAuthServer(id = "server-1", name = "Picked", slug = ""))
        val parsed = JSONObject(buildAppIdentityReceipt(pairedRequest(), facts, session(selectedServerSlug = null, servers = noSlug)))
        assertTrue(parsed.isNull("server_ref"))
        assertTrue(parsed.isNull("server_pair_ref"))
        assertEquals(fixture.getString("user_ref"), parsed.getString("user_ref"))
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
    fun pairRefsStayAcrossAttemptNoncesAndChangeWithTheSalt() {
        val other = pairing.getJSONObject("different_attempt_same_secret_version")
        val again = JSONObject(buildAppIdentityReceipt(pairedRequest(withNonce = other.getString("nonce")), facts, session()))
        for (key in listOf("user_ref", "server_ref", "user_pair_ref", "server_pair_ref")) assertEquals(key, other.getString(key), again.getString(key))

        val rotated = pairing.getJSONObject("same_target_after_rotation")
        val after = JSONObject(buildAppIdentityReceipt(pairedRequest(salt = rotated.getString("pairing_salt")), facts, session()))
        assertEquals(rotated.getString("user_pair_ref"), after.getString("user_pair_ref"))
        assertEquals(rotated.getString("server_pair_ref"), after.getString("server_pair_ref"))
        assertNotEquals(pairing.getJSONObject("receipt").getString("user_pair_ref"), after.getString("user_pair_ref"))
    }

    @Test
    fun pairRefsAreNullWhenSignedOutAndAbsentWithoutASalt() {
        val signedOut = JSONObject(buildAppIdentityReceipt(pairedRequest(), facts, null))
        assertTrue(signedOut.isNull("user_pair_ref"))
        assertTrue(signedOut.isNull("server_pair_ref"))
        val unpaired = JSONObject(buildAppIdentityReceipt(request, facts, session()))
        assertFalse(unpaired.has("user_pair_ref"))
        assertFalse(unpaired.has("server_pair_ref"))
    }

    @Test
    fun pairingSaltMustBe64HexAndDifferFromTheNonce() {
        val salt = pairing.getString("pairing_salt")
        assertEquals(salt, parseAppIdentityReceiptRequest(null, validExtras() + ("pairing_salt" to salt))?.pairingSalt)
        for (bad in listOf(nonce, "CD".repeat(32), "cd".repeat(31), "cd".repeat(33), "")) {
            val result = AppIdentityReceiptResponder().respond(2000, "receipt_v1", null, validExtras() + ("pairing_salt" to bad)) { error("must not read") }
            assertEquals(bad, AppIdentityReceiptResult.Rejected("bad_request"), result)
        }
        // The salt never authorizes: a non-shell caller with a valid salt is refused all the same.
        assertEquals(
            AppIdentityReceiptResult.Rejected("caller_not_allowed"),
            AppIdentityReceiptResponder().respond(10_123, "receipt_v1", null, validExtras() + ("pairing_salt" to salt)) { error("must not read") }
        )
    }

    @Test
    fun receiptIsTheDesignSampleWithinTheSizeCap() {
        val text = buildAppIdentityReceipt(request, facts, session())
        val parsed = JSONObject(text)
        // Same keys in the same order, same values as the design's fake sample.
        assertEquals(sample.keys().asSequence().toList(), parsed.keys().asSequence().toList())
        for (key in sample.keys()) assertEquals(key, sample.get(key), parsed.get(key))
        assertTrue(text.encodeToByteArray().size <= AppIdentityReceiptResponder.MAX_RECEIPT_BYTES)
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
    fun signedOutReportsNoOriginAndNoRefs() {
        val parsed = JSONObject(buildAppIdentityReceipt(request, facts, null))
        assertEquals(false, parsed.getBoolean("signed_in"))
        listOf("session_origin", "user_ref", "server_ref").forEach { assertTrue(it, parsed.isNull(it)) }
    }

    @Test
    fun serverRefUsesOnlyTheStoredSelectionAndOriginTellsQaBootstrapApart() {
        val servers = listOf(StoredAuthServer(id = "server-0", name = "First", slug = "first"), StoredAuthServer(id = "server-1", name = "Picked", slug = "test"))
        // Signed in but still on Choose server: no first-server fallback.
        val chooser = JSONObject(buildAppIdentityReceipt(request, facts, session(selectedServerId = null, selectedServerSlug = null, servers = servers)))
        assertTrue(chooser.getBoolean("signed_in"))
        assertTrue(chooser.isNull("server_ref"))
        // Selected without a stored slug: the selected server's own slug.
        val noStoredSlug = JSONObject(buildAppIdentityReceipt(request, facts, session(selectedServerSlug = null, servers = servers)))
        assertEquals(fixture.getString("server_ref"), noStoredSlug.getString("server_ref"))
        assertEquals("app_login", noStoredSlug.getString("session_origin"))

        val qa = session(qaBootstrap = StoredQaBootstrapMetadata(ticketDigest = "sha256:t", runCaseId = "r", attempt = 1, ciJobRef = "c", principalId = "p", serverId = "s", sessionGeneration = "g", targetRoute = "t", applicationId = "a", signingCertSha256 = "x", deviceBootGeneration = "b", state = "ready"))
        assertEquals("qa_bootstrap", JSONObject(buildAppIdentityReceipt(request, facts, qa)).getString("session_origin"))
    }

    @Test
    fun versionNameOutsideItsFormatIsNull() {
        val parsed = JSONObject(buildAppIdentityReceipt(request, facts.copy(versionName = "1.27.0 \"x\""), null))
        assertTrue(parsed.isNull("version_name"))
    }

    @Test
    fun nonShellOrRootCallerIsRefusedWithoutReadingAnything() {
        for (uid in listOf(1, 1000, 1001, 1999, 2001, 9999, 10_000, 10_123, 19_999, 99_000, 99_999, 1_010_123, -1)) {
            val responder = AppIdentityReceiptResponder()
            var reads = 0
            val result = responder.respond(uid, "receipt_v1", null, validExtras()) { reads++; facts.copy(session = InMemoryAuthSessionSnapshot(session())) }
            assertEquals("uid $uid", AppIdentityReceiptResult.Rejected("caller_not_allowed"), result)
            assertEquals("uid $uid must not read any state", 0, reads)
        }
    }

    @Test
    fun shellAndRootGetTheReceipt() {
        for (uid in listOf(AppIdentityReceiptResponder.SHELL_UID, AppIdentityReceiptResponder.ROOT_UID)) {
            val result = AppIdentityReceiptResponder().respond(uid, "receipt_v1", null, validExtras()) { facts.copy(session = InMemoryAuthSessionSnapshot(session())) }
            val json = (result as AppIdentityReceiptResult.Receipt).json
            assertEquals(fixture.getString("user_ref"), JSONObject(json).getString("user_ref"))
        }
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

    @Test
    fun aSessionThisProcessHasNotLoadedIsNotReadFromStorage() {
        val result = AppIdentityReceiptResponder().respond(2000, "receipt_v1", null, validExtras()) { facts.copy(session = null) }
        assertEquals(AppIdentityReceiptResult.Rejected("session_not_loaded"), result)
        val failing = AppIdentityReceiptResponder().respond(2000, "receipt_v1", null, validExtras()) { throw IllegalStateException("pm") }
        assertEquals(AppIdentityReceiptResult.Rejected("unavailable"), failing)
    }

    @Test
    fun procStatStartTicksIsField22() {
        assertEquals("987654", parseAppIdentityProcStartTicks("4242 (build.raft) app) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20 21"))
        assertNull(parseAppIdentityProcStartTicks("garbage"))
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
