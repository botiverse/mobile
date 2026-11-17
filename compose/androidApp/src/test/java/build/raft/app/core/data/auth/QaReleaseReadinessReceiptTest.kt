package build.raft.app.core.data.auth

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Fake values only: no real account, server or token. */
class QaReleaseReadinessReceiptTest {
    private val attemptId = "pla_" + "0a".repeat(16)
    private val nonce = "ab".repeat(32)
    private val requestId = "cd".repeat(16)
    private val request = requireNotNull(QaReleaseReadinessRequest.parse(attemptId, nonce, requestId))

    // Shared fixed vector with scripts/qa/release_prelogin.test.mjs (userRef):
    // sha256("raft-qa-readiness-user/v1" NUL ("ab" x 32) NUL "usr-fake-1").
    private val userRefVector = "1b12673dbe0d552e3de10c8725bc8caaff65a00216c29226f2ead7aaa856f225"

    private fun session(selectedServerId: String? = "srv-fake-1", selectedServerSlug: String? = null) =
        StoredAuthSession(
            user = StoredAuthUser(id = "usr-fake-1", email = "fake@example.invalid", displayName = "Fake Person"),
            accessToken = "fake-access-token-value",
            refreshToken = "fake-refresh-token-value",
            selectedServerId = selectedServerId,
            selectedServerSlug = selectedServerSlug,
            servers =
                listOf(
                    StoredAuthServer(id = "srv-fake-0", name = "Other", slug = "other-fake"),
                    StoredAuthServer(id = "srv-fake-1", name = "Fake", slug = "fake-slug")
                )
        )

    private fun receipt(qaSession: QaReadinessSession?) =
        buildQaReleaseReadinessReceipt(
            request = request,
            session = qaSession,
            packageName = "build.raft.app",
            versionName = "1.27.0",
            versionCode = 1270001,
            pid = 4242,
            processStartTicks = "987654"
        )

    @Test
    fun userRefIsTheNonceBoundSha256OfTheUserId() {
        assertEquals(userRefVector, qaReadinessUserRef(nonce, "usr-fake-1"))
        assertFalse(qaReadinessUserRef("cd".repeat(32), "usr-fake-1") == userRefVector)
    }

    @Test
    fun receiptCarriesAttemptRequestBindingUserRefServerBuildAndProcess() {
        val text = receipt(session().toQaReadinessSession())
        assertEquals(
            "{\"contract\":\"raft-qa-release-readiness/v2\",\"attempt_id\":\"$attemptId\",\"nonce\":\"$nonce\"," +
                "\"request_id\":\"$requestId\",\"package\":\"build.raft.app\",\"version_name\":\"1.27.0\",\"version_code\":1270001," +
                "\"pid\":4242,\"process_start_ticks\":\"987654\",\"signed_in\":true,\"user_ref\":\"$userRefVector\"," +
                "\"selected_server_id\":\"srv-fake-1\",\"selected_server_slug\":\"fake-slug\"}",
            text
        )
    }

    @Test
    fun receiptNeverCarriesTokensEmailNameOrTheRawUserId() {
        val text = receipt(session().toQaReadinessSession())
        listOf("fake-access-token-value", "fake-refresh-token-value", "fake@example.invalid", "Fake Person", "usr-fake-1", "token", "cookie", "email")
            .forEach { assertFalse("receipt must not contain $it", text.contains(it, ignoreCase = true)) }
    }

    @Test
    fun onlyTheStoredSelectionCountsNotTheFirstServerFallback() {
        val chooser = session(selectedServerId = null).toQaReadinessSession()
        assertNull(chooser.selectedServerId)
        assertNull(chooser.selectedServerSlug)
        assertTrue(receipt(chooser).contains("\"selected_server_id\":null"))
        assertEquals("stored-slug", session(selectedServerSlug = "stored-slug").toQaReadinessSession().selectedServerSlug)
    }

    @Test
    fun signedOutReportsNoIds() {
        assertTrue(receipt(null).contains("\"signed_in\":false,\"user_ref\":null,\"selected_server_id\":null"))
    }

    @Test
    fun requestNeedsTestbedAttemptIdNonceAndAFreshRequestId() {
        assertNotNull(QaReleaseReadinessRequest.parse(attemptId, nonce, requestId))
        assertNull(QaReleaseReadinessRequest.parse(null, nonce, requestId))
        assertNull(QaReleaseReadinessRequest.parse(attemptId, null, requestId))
        assertNull(QaReleaseReadinessRequest.parse(attemptId, nonce, null))
        assertNull(QaReleaseReadinessRequest.parse("run-1", nonce, requestId))
        assertNull(QaReleaseReadinessRequest.parse(attemptId, "short", requestId))
        assertNull(QaReleaseReadinessRequest.parse(attemptId, nonce, "x"))
        assertNull(QaReleaseReadinessRequest.parse(attemptId, "\"},\"user_ref\":\"x" + "a".repeat(60), requestId))
    }

    @Test
    fun procStatStartTicksMatchesTheScriptsField22() {
        val stat = "4242 (build.raft) app) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20 21"
        assertEquals("987654", parseProcStatStartTicks(stat))
        assertNull(parseProcStatStartTicks("garbage"))
    }

    @Test
    fun storeSourceSetsDeclareNoReceiverOnlyTheQaCandidateDoes() {
        for (store in listOf("src/main/AndroidManifest.xml", "src/play/AndroidManifest.xml")) {
            assertFalse("$store must not name the receiver", locate(store).readText().contains("QaReleaseReadinessReceiver"))
        }
        assertFalse(File(locate("src/main/AndroidManifest.xml").parentFile.parentFile, "release/AndroidManifest.xml").let { it.isFile && it.readText().contains("QaReleaseReadinessReceiver") })
        val receivers = manifest("src/qaRelease/AndroidManifest.xml").getElementsByTagName("receiver")
        val receiver =
            (0 until receivers.length)
                .map { receivers.item(it) as Element }
                .single { it.getAttribute("android:name") == "build.raft.app.core.data.auth.QaReleaseReadinessReceiver" }
        assertEquals("false", receiver.getAttribute("android:enabled"))
        assertEquals("android.permission.DUMP", receiver.getAttribute("android:permission"))
        assertEquals(0, receiver.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun receiverSourceIsCompiledOnlyByDebugAndTheQaCandidate() {
        val src = locate("src/main/AndroidManifest.xml").parentFile.parentFile
        val holders =
            src.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filterNot { it.relativeTo(src).path.replace('\\', '/').startsWith("test/") }
                .filter { it.readText().contains("class QaReleaseReadinessReceiver") }
                .map { it.relativeTo(src).path.replace('\\', '/') }
                .toList()
        assertEquals(listOf("qaReadiness/kotlin/build/raft/app/core/data/auth/QaReleaseReadinessReceiver.kt"), holders)
        val gradle = File(src.parentFile, "build.gradle.kts").readText()
        val users =
            Regex("withBuildType\\(\"(\\w+)\"\\)\\) \\{ variant ->\\s*variant\\.sources\\.kotlin\\?\\.addStaticSourceDirectory\\(file\\(\"src/qaReadiness/kotlin\"\\)")
                .findAll(gradle).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("debug", "qaRelease"), users)
        assertEquals(2, Regex("addStaticSourceDirectory\\(file\\(\"src/qaReadiness/kotlin\"\\)").findAll(gradle).count())
    }

    private fun manifest(relativePath: String): Element =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(locate(relativePath))
            .documentElement

    private fun locate(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            File(current, "androidApp/$relativePath").takeIf { it.isFile }?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
