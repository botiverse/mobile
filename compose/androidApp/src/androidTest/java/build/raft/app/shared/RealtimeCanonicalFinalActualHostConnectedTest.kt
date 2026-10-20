package build.raft.app.shared

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealtimeCanonicalFinalActualHostConnectedTest {
    @Test
    fun actualAppShellAndThreadUseTheBoundProductionProviderWithoutEgress() {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        LoopbackBlackhole().use { blackhole ->
            clearOwnLogcat()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val pageData =
                JSONObject()
                    .put("runNonce", nonce)
                    .put("blackholePort", blackhole.port)
                    .put("slockShellInstanceId", "canonical-final-app-$nonce")
                    .put("slockShellServerId", ServerA)
            val intent =
                Intent(context, RaftCmpActivity::class.java)
                    .putExtra(RaftCmpActivity.KEY_PAGE_NAME, PageName)
                    .putExtra(RaftCmpActivity.KEY_PAGE_DATA, pageData.toString())
                    .putExtra(RaftCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

            ActivityScenario.launch<RaftCmpActivity>(intent).use {
                val evidence = waitForTerminalEvidence(nonce)
                verifyEvidence(evidence, nonce)
                assertTrue(
                    "Expected the real provider network stack to reach the instrumentation-owned loopback blackhole.",
                    blackhole.acceptedConnections() > 0,
                )
            }
        }
    }

    @Test
    fun terminalPayloadBudgetSurvivesLongErrorAndMaxTrace() {
        val maxTrace =
            (1..8).joinToString(";") { index ->
                "ThreadCore:actual-host-thread-0123456789abcdef0123456789abcdef," +
                    "mobile-1784822740821-3869354940999758981:lease:$index,host-server-a,registration-$index,ref-$index"
            }
        val longError = buildString { repeat(500) { index -> append("boom \"quoted\" \\path segment $index ") } }
        val payload =
            build.raft.app.buildCanonicalFinalTerminalPayload(
                runNonce = "0123456789abcdef0123456789abcdef",
                terminalSeq = 18,
                phaseCount = 17,
                lastSeq = 17,
                chainSha256 = "f".repeat(64),
                success = false,
                error = longError,
                maxBytes = 1_800,
            ) { maxTrace }
        assertTrue("terminal payload must fit the producer budget", payload.toByteArray(Charsets.UTF_8).size <= 1_800)
        val json = JSONObject(payload)
        assertTrue(json.getBoolean("terminal"))
        assertFalse(json.getBoolean("success"))
        assertTrue("truncated error must preserve the original failure prefix", json.getString("error").isNotEmpty())
        assertTrue(longError.startsWith(json.getString("error")))
        assertEquals(17, json.getInt("phaseCount"))
        assertEquals(17, json.getInt("lastSeq"))
    }

    @Test
    fun terminalPayloadBudgetSurvivesRegistrationCaptureFailure() {
        val payload =
            build.raft.app.buildCanonicalFinalTerminalPayload(
                runNonce = "0123456789abcdef0123456789abcdef",
                terminalSeq = 2,
                phaseCount = 1,
                lastSeq = 1,
                chainSha256 = "a".repeat(64),
                success = false,
                error = "original failure",
                maxBytes = 1_800,
            ) { throw IllegalStateException("registration trace exceeded bound") }
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 1_800)
        val json = JSONObject(payload)
        assertEquals("diagnostics capture failure must not displace the original error", "original failure", json.getString("error"))
        assertTrue(json.getString("registrations").startsWith("capture-failed:"))
    }

    @Test
    fun successorRetentionFailsImmediatelyOnRestoredOldIdEvenWhileIncomplete() {
        val error = assertRetentionThrows(
            samples = listOf(
                // First sample: slot set incomplete AND AppShellDomain carries the old-generation
                // id. Must fail on this very sample — a later fresh id must not launder it.
                "AppShellDomain,lease-1,server-a,registration-7,ref-1",
                "AppShellDomain,lease-1,server-a,registration-12,ref-2;AppShellHomeUnread,lease-1,server-b,registration-13,ref-3",
            ),
        )
        assertTrue(error.contains("restored old-generation registration registration-7"))
    }

    @Test
    fun successorRetentionSettlesFromIncompleteToComplete() {
        val settled = kotlinx.coroutines.runBlocking {
            build.raft.app.awaitCanonicalFinalSuccessorRetention(
                expectedSurfaces = RETENTION_EXPECTED,
                forbiddenIdsBySlot = RETENTION_FORBIDDEN,
                label = "tooth",
                timeoutMs = 500,
                delayMs = 1,
                sample = scriptedSampler(
                    "AppShellDomain,lease-1,server-a,registration-12,ref-2",
                    "AppShellDomain,lease-1,server-a,registration-12,ref-2;AppShellHomeUnread,lease-1,server-b,registration-13,ref-3",
                ),
            )
        }
        assertTrue(settled.contains("registration-13"))
    }

    @Test
    fun successorRetentionTimesOutOnPersistentGap() {
        val error = assertRetentionThrows(
            samples = listOf("AppShellDomain,lease-1,server-a,registration-12,ref-2"),
            timeoutMs = 100,
        )
        assertTrue(error.contains("successor slots incomplete after"))
    }

    @Test
    fun successorRetentionFailsOnRestoredOldIdInCompleteSet() {
        val error = assertRetentionThrows(
            samples = listOf(
                "AppShellDomain,lease-1,server-a,registration-12,ref-2;AppShellHomeUnread,lease-1,server-b,registration-8,ref-3",
            ),
        )
        assertTrue(error.contains("restored old-generation registration registration-8"))
    }

    private fun assertRetentionThrows(samples: List<String>, timeoutMs: Long = 500): String {
        try {
            kotlinx.coroutines.runBlocking {
                build.raft.app.awaitCanonicalFinalSuccessorRetention(
                    expectedSurfaces = RETENTION_EXPECTED,
                    forbiddenIdsBySlot = RETENTION_FORBIDDEN,
                    label = "tooth",
                    timeoutMs = timeoutMs,
                    delayMs = 1,
                    sample = scriptedSampler(*samples.toTypedArray()),
                )
            }
        } catch (error: IllegalStateException) {
            return error.message.orEmpty()
        }
        fail("expected retention failure")
        error("unreachable")
    }

    private fun scriptedSampler(vararg samples: String): suspend () -> String {
        val index = AtomicInteger(0)
        return { samples[minOf(index.getAndIncrement(), samples.size - 1)] }
    }

    private fun verifyEvidence(evidence: TraceEvidence, nonce: String) {
        val entries = evidence.entries
        assertTrue("Expected non-terminal trace phases.", entries.size > 2)
        entries.forEachIndexed { index, entry ->
            assertEquals("Trace sequence must be contiguous.", index + 1, entry.seq)
            assertEquals(nonce, entry.payload.getString("runNonce"))
            assertEquals(1, entry.payload.getInt("providerFactoryCount"))
            assertEquals(1, entry.payload.getInt("integrationFactoryCount"))
            assertLoopback(entry.payload.getString("apiUrl"))
            assertLoopback(entry.payload.getString("socketUrl"))
            assertLoopback(entry.payload.getString("webUrl"))
            assertFalse("Trace leaked a synthetic access token.", entry.raw.contains("host-access-"))
            assertFalse("Trace leaked a synthetic refresh token.", entry.raw.contains("host-refresh-"))
            assertFalse("Trace leaked an authorization header.", entry.raw.contains("Authorization", ignoreCase = true))
            assertFalse("Auth ledger recorded a clear.", entry.payload.getString("authEvents").contains("Cleared:"))
        }
        assertEquals(entries.size + 1, evidence.terminal.seq)
        assertFalse("Terminal trace leaked a synthetic access token.", evidence.terminal.raw.contains("host-access-"))
        assertFalse("Terminal trace leaked a synthetic refresh token.", evidence.terminal.raw.contains("host-refresh-"))
        assertFalse("Terminal trace leaked an authorization header.", evidence.terminal.raw.contains("Authorization", ignoreCase = true))
        assertTrue(
            "Terminal sentinel reported failure: ${evidence.terminal.payload.optString("error")}; " +
                "terminal registrations=${evidence.terminal.payload.optString("registrations")}",
            evidence.terminal.payload.getBoolean("success"),
        )
        assertEquals(entries.size, evidence.terminal.payload.getInt("phaseCount"))
        assertEquals(entries.last().seq, evidence.terminal.payload.getInt("lastSeq"))
        assertEquals(sha256(entries.joinToString("|") { it.hash }), evidence.terminal.payload.getString("chainSha256"))

        val byPhase = entries.associateBy { "${it.payload.getString("component")}:${it.payload.getString("phase")}" }
        assertEquals(entries.size, byPhase.size)
        val environment = byPhase.requirePhase("host:environment-ready")
        byPhase.requirePhase("host:session-authenticated")
        val standaloneBefore = byPhase.requirePhase("standalone-thread:before-mount")
        val standaloneFirst = byPhase.requirePhase("standalone-thread:first-mounted")
        val standaloneRotated = byPhase.requirePhase("standalone-thread:auth-rotated")
        val standaloneReplacement = byPhase.requirePhase("standalone-thread:replacement-mounted")
        val standaloneOldDisposed = byPhase.requirePhase("standalone-thread:old-disposed")
        val standaloneFinalComponent = byPhase.requirePhase("standalone-thread:final-component-disposed")
        val standaloneOuterReleased = byPhase.requirePhase("standalone-thread:outer-shell-released")
        val sharedBefore = byPhase.requirePhase("shared:before-mount")
        val sharedApp = byPhase.requirePhase("shared:app-mounted")
        val sharedThread = byPhase.requirePhase("shared:thread-mounted")
        val preReadySelected = byPhase.requirePhase("shared:pre-ready-selected")
        val preReadyUnread = byPhase.requirePhase("shared:pre-ready-unread")
        val readyRepaired = byPhase.requirePhase("shared:ready-repaired")
        val readyMask = byPhase.requirePhase("shared:ready-mask")
        val sharedRotated = byPhase.requirePhase("shared:auth-rotated")
        val overlapPreReady = byPhase.requirePhase("shared:overlap-pre-ready")
        val staleExactHandleReplay = byPhase.requirePhase("shared:stale_exact_handle_replay")
        val realOldAppDispose = byPhase.requirePhase("shared:real_old_app_dispose")
        val overlapRepaired = byPhase.requirePhase("shared:overlap-repaired")
        val sharedReplacement = byPhase.requirePhase("shared:replacements-mounted")
        val sharedOldDisposed = byPhase.requirePhase("shared:old-components-disposed")
        val sharedThreadFinal = byPhase.requirePhase("shared:thread-final-disposed")
        val sharedAppFinal = byPhase.requirePhase("shared:app-final-disposed")
        val sharedOuterReleased = byPhase.requirePhase("shared:outer-shell-released")

        val providerToken = environment.getString("providerToken")
        val authStoreToken = environment.getString("authStoreToken")
        val ownerToken = environment.getString("ownerToken")
        entries.forEach { entry ->
            assertEquals(providerToken, entry.payload.getString("providerToken"))
            assertEquals(authStoreToken, entry.payload.getString("authStoreToken"))
            assertEquals(ownerToken, entry.payload.getString("ownerToken"))
        }
        assertAuthorityAbsent(standaloneBefore)
        assertRegistrations(standaloneBefore, emptyList())
        assertAuthorityPresent(standaloneFirst)
        assertTrue(standaloneFirst.getString("authEvents").contains("Authenticated:1"))
        assertRegistrations(standaloneFirst, standaloneThreadSlots(nonce))
        assertRotationStable(standaloneFirst, standaloneRotated, expectedTokenEvent = "TokensRotated:2")
        assertReplacementChanged(standaloneFirst, standaloneReplacement)
        assertSuccessorRetainedAfterOldDispose(standaloneFirst, standaloneReplacement, standaloneOldDisposed)
        val standaloneDisposeWitness = compactFields(standaloneOldDisposed, "standaloneDisposeWitness")
        assertNotEquals(standaloneDisposeWitness.getValue("oc"), standaloneDisposeWitness.getValue("cc"))
        assertNotEquals(standaloneDisposeWitness.getValue("of"), standaloneDisposeWitness.getValue("cf"))
        assertAuthorityPresent(standaloneFinalComponent)
        assertRegistrations(standaloneFinalComponent, emptyList())
        assertAuthorityAbsent(standaloneOuterReleased)
        assertRegistrations(standaloneOuterReleased, emptyList())

        assertAuthorityAbsent(sharedBefore)
        assertRegistrations(sharedBefore, emptyList())
        assertAuthorityPresent(sharedApp)
        assertRegistrations(sharedApp, sharedAppSlots())
        assertAuthorityPresent(sharedThread)
        assertEquals(sharedApp.getString("lease"), sharedThread.getString("lease"))
        assertRegistrations(sharedThread, sharedComposedSlots(nonce))
        val preSelectedProbe = probe(preReadySelected)
        val preUnreadProbe = probe(preReadyUnread)
        val repairedProbe = probe(readyRepaired)
        val readyMaskProbe = probe(readyMask)
        assertEquals(1, preSelectedProbe.int("c"))
        assertEquals(1, preSelectedProbe.int("f"))
        assertEquals(0, preSelectedProbe.int("d"))
        assertEquals(0, preSelectedProbe.int("u"))
        assertEquals(preSelectedProbe.int("c"), preUnreadProbe.int("c"))
        assertEquals(preSelectedProbe.int("f"), preUnreadProbe.int("f"))
        assertEquals(preSelectedProbe.int("d"), preUnreadProbe.int("d"))
        assertEquals(preSelectedProbe.int("u"), preUnreadProbe.int("u"))
        assertEquals(preSelectedProbe.long("a"), preUnreadProbe.long("a"))
        assertEquals(preSelectedProbe.int("b"), preUnreadProbe.int("b"))
        assertTrue(preUnreadProbe.getValue("uh").isNotBlank())
        assertEquals(preUnreadProbe.getValue("uh"), preUnreadProbe.getValue("xu"))
        assertEquals(2, repairedProbe.int("c"))
        assertEquals(2, repairedProbe.int("f"))
        assertEquals(1, repairedProbe.int("d"))
        assertEquals(1, repairedProbe.int("u"))
        assertTrue(repairedProbe.long("a") > preSelectedProbe.long("a"))
        assertEquals(preUnreadProbe.int("b") + 1, repairedProbe.int("b"))
        val overlapPreReadyProbe = probe(overlapPreReady)
        val overlapRepairedProbe = probe(overlapRepaired)
        assertNotEquals(preSelectedProbe.getValue("dh"), overlapPreReadyProbe.getValue("dh"))
        assertNotEquals(preUnreadProbe.getValue("uh"), overlapPreReadyProbe.getValue("uh"))
        assertEquals(overlapPreReadyProbe.getValue("dh"), overlapPreReadyProbe.getValue("xd"))
        assertEquals(overlapPreReadyProbe.getValue("uh"), overlapPreReadyProbe.getValue("xu"))
        assertEquals(2, overlapPreReadyProbe.int("c"))
        assertEquals(2, overlapPreReadyProbe.int("f"))
        assertEquals(0, overlapPreReadyProbe.int("d"))
        assertEquals(0, overlapPreReadyProbe.int("u"))
        assertEquals(preSelectedProbe.long("a"), overlapPreReadyProbe.long("a"))
        assertEquals(preUnreadProbe.int("b"), overlapPreReadyProbe.int("b"))
        assertEquals(overlapPreReadyProbe, probe(staleExactHandleReplay))
        assertEquals(overlapPreReadyProbe, probe(realOldAppDispose))
        assertEquals(overlapPreReadyProbe.getValue("dh"), overlapRepairedProbe.getValue("dh"))
        assertEquals(overlapRepairedProbe.getValue("dh"), overlapRepairedProbe.getValue("xd"))
        assertEquals(overlapRepairedProbe.getValue("uh"), overlapRepairedProbe.getValue("xu"))
        assertEquals(2, overlapRepairedProbe.int("c"))
        assertEquals(2, overlapRepairedProbe.int("f"))
        assertEquals(2, overlapRepairedProbe.int("d"))
        assertEquals(2, overlapRepairedProbe.int("u"))
        assertTrue(overlapRepairedProbe.long("a") > repairedProbe.long("a"))
        assertEquals(repairedProbe.int("b") + 1, overlapRepairedProbe.int("b"))
        assertEquals(3, readyMaskProbe.int("c"))
        assertEquals(3, readyMaskProbe.int("f"))
        assertEquals(3, readyMaskProbe.int("d"))
        assertEquals(3, readyMaskProbe.int("u"))
        assertTrue(readyMaskProbe.long("a") > overlapRepairedProbe.long("a"))
        assertEquals(overlapRepairedProbe.int("b") + 1, readyMaskProbe.int("b"))
        assertReplacementChanged(sharedThread, sharedReplacement)
        assertRotationStable(readyMask, sharedRotated, expectedTokenEvent = "TokensRotated:3", requireStableHandles = false)
        assertEquals(sharedReplacement.getString("lease"), sharedOldDisposed.getString("lease"))
        assertEquals(
            registrations(sharedReplacement).map(RegistrationRecord::slot),
            registrations(sharedOldDisposed).map(RegistrationRecord::slot),
        )
        val oldThreadDisposedProbe = probe(sharedOldDisposed)
        listOf("c", "f", "d", "u", "b").forEach { key ->
            assertEquals(readyMaskProbe.getValue(key), oldThreadDisposedProbe.getValue(key))
        }
        assertEquals(oldThreadDisposedProbe.getValue("dh"), oldThreadDisposedProbe.getValue("xd"))
        assertEquals(oldThreadDisposedProbe.getValue("uh"), oldThreadDisposedProbe.getValue("xu"))
        assertAuthorityPresent(sharedThreadFinal)
        assertRegistrations(sharedThreadFinal, sharedAppSlots())
        assertAuthorityPresent(sharedAppFinal)
        assertRegistrations(sharedAppFinal, emptyList())
        assertAuthorityAbsent(sharedOuterReleased)
        assertRegistrations(sharedOuterReleased, emptyList())

        assertNotEquals(standaloneFirst.getString("lease"), sharedThread.getString("lease"))
        assertTrue(standaloneFirst.getString("threadParameters").startsWith("$providerToken,"))
        assertTrue("Actual SharedThreadRouteContent did not request its initial payload.", sharedThread.getInt("threadDataRequests") > 0)

        assertTrue(
            "Missing a real RaftAppShellContent runtime marker.",
            evidence.fullLog.contains("app_shell_auth_gate_state") || evidence.fullLog.contains("app_shell_parent_home_start_check"),
        )
        assertTrue(
            "Missing a real SharedThreadRouteContent runtime marker.",
            evidence.fullLog.contains("thread_first_render") || evidence.fullLog.contains("thread_render"),
        )
    }

    private fun assertAuthorityPresent(payload: JSONObject) {
        assertTrue(payload.getString("lease").isNotBlank())
        assertTrue(payload.getString("family").isNotBlank())
        val grants = payload.getString("grants")
        assertTrue(grants.contains(ServerA))
        assertTrue(grants.contains(ServerB))
    }

    private fun assertAuthorityAbsent(payload: JSONObject) {
        assertTrue(payload.getString("lease").isEmpty())
        assertTrue(payload.getString("family").isEmpty())
        assertTrue(payload.getString("grants").isEmpty())
    }

    private fun assertRotationStable(
        before: JSONObject,
        after: JSONObject,
        expectedTokenEvent: String,
        requireStableHandles: Boolean = true,
    ) {
        assertAuthorityPresent(after)
        assertTrue(after.getString("authEvents").contains(expectedTokenEvent))
        assertEquals(before.getString("lease"), after.getString("lease"))
        assertNotEquals(before.getString("family"), after.getString("family"))
        if (requireStableHandles) {
            // Fixture-owned quiescent components: rotation must not disturb exact handles.
            assertEquals(registrations(before), registrations(after))
        } else {
            // Real components may legitimately re-register their own slot while async loading; rotation
            // must still preserve the slot set.
            assertEquals(
                registrations(before).map(RegistrationRecord::slot),
                registrations(after).map(RegistrationRecord::slot),
            )
        }
    }

    private fun assertReplacementChanged(before: JSONObject, replacement: JSONObject) {
        assertAuthorityPresent(replacement)
        assertEquals(before.getString("lease"), replacement.getString("lease"))
        val oldRecords = registrations(before)
        val newRecords = registrations(replacement)
        assertEquals(oldRecords.map(RegistrationRecord::slot), newRecords.map(RegistrationRecord::slot))
        oldRecords.zip(newRecords).forEach { (old, new) ->
            assertNotEquals("replacement must mint a new exact registration handle", old.registrationId, new.registrationId)
            assertNotEquals("replacement must capture the successor component sink", old.sinkRef, new.sinkRef)
        }
    }

    private fun assertSuccessorRetainedAfterOldDispose(
        old: JSONObject,
        replacement: JSONObject,
        afterOldDispose: JSONObject,
    ) {
        assertEquals(replacement.getString("lease"), afterOldDispose.getString("lease"))
        val oldRecords = registrations(old)
        val replacementRecords = registrations(replacement)
        val currentRecords = registrations(afterOldDispose)
        assertEquals(replacementRecords.map(RegistrationRecord::slot), currentRecords.map(RegistrationRecord::slot))
        val oldBySlot = oldRecords.associateBy(RegistrationRecord::slot)
        currentRecords.forEach { current ->
            assertNotEquals(
                "old component dispose must not restore or retain its captured exact handle",
                oldBySlot.getValue(current.slot).registrationId,
                current.registrationId,
            )
        }
    }

    private fun assertRegistrations(payload: JSONObject, expected: List<RegistrationSlot>) {
        val records = registrations(payload)
        assertEquals(expected, records.map(RegistrationRecord::slot))
        records.forEach { record ->
            assertEquals(payload.getString("lease"), record.leaseId)
            assertTrue(record.registrationId.isNotBlank())
            assertTrue(record.sinkRef.startsWith("ref-"))
        }
    }

    private fun registrations(payload: JSONObject): List<RegistrationRecord> {
        val raw = payload.getString("registrations")
        if (raw.isBlank()) return emptyList()
        return raw.split(';').map { entry ->
            val fields = entry.split(',')
            assertEquals("Malformed registration trace: $entry", 5, fields.size)
            RegistrationRecord(
                surface = fields[0],
                leaseId = fields[1],
                serverId = fields[2],
                registrationId = fields[3],
                sinkRef = fields[4],
            )
        }
    }

    private fun probe(payload: JSONObject): Map<String, String> {
        return compactFields(payload, "probe")
    }

    private fun compactFields(payload: JSONObject, key: String): Map<String, String> {
        val raw = payload.getString(key)
        assertTrue("Missing $key in phase payload.", raw.isNotBlank())
        return raw.split(',').associate { field ->
            val separator = field.indexOf('=')
            assertTrue("Malformed $key field: $field", separator > 0)
            field.substring(0, separator) to field.substring(separator + 1)
        }
    }

    private fun Map<String, String>.int(key: String): Int = getValue(key).toInt()

    private fun Map<String, String>.long(key: String): Long = getValue(key).toLong()

    private fun standaloneThreadSlots(nonce: String): List<RegistrationSlot> =
        listOf(
            RegistrationSlot("ThreadCore:actual-host-thread-$nonce", ServerA),
            RegistrationSlot("ThreadFeature:actual-host-thread-$nonce", ServerA),
        )

    private fun sharedAppSlots(): List<RegistrationSlot> =
        listOf(
            RegistrationSlot("AppShellDomain", ServerA),
            RegistrationSlot("AppShellHomeUnread", ServerB),
        )

    private fun sharedComposedSlots(nonce: String): List<RegistrationSlot> =
        standaloneThreadSlots(nonce) + sharedAppSlots()

    private fun assertLoopback(url: String) {
        assertTrue("Expected TLS loopback URL, got $url", url.startsWith("https://127.0.0.1:"))
    }

    private fun Map<String, TraceEntry>.requirePhase(key: String): JSONObject =
        get(key)?.payload ?: throw AssertionError("Missing trace phase $key")

    private fun waitForTerminalEvidence(nonce: String): TraceEvidence {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        var lastLog = ""
        while (System.nanoTime() < deadline) {
            lastLog = readOwnLogcat()
            val parsed = parseTrace(lastLog, nonce)
            if (parsed != null) return parsed
            Thread.sleep(100)
        }
        fail("Timed out waiting for the unique terminal sentinel for run $nonce. Last log:\n${lastLog.takeLast(12_000)}")
        error("unreachable")
    }

    private fun parseTrace(log: String, nonce: String): TraceEvidence? {
        val candidateLines = log.lineSequence().filter { it.contains(TraceMarker) && it.contains(nonce) }.toList()
        if (candidateLines.isEmpty()) return null
        val parsed = candidateLines.map(::parseTraceLine)
        val terminalEntries = parsed.filter { it.payload.getBoolean("terminal") }
        if (terminalEntries.isEmpty()) return null
        assertEquals("Terminal sentinel must be unique.", 1, terminalEntries.size)
        val terminal = terminalEntries.single()
        assertEquals("Terminal sentinel must be the last current-run trace.", terminal, parsed.last())
        return TraceEvidence(entries = parsed.dropLast(1), terminal = terminal, fullLog = log)
    }

    private fun parseTraceLine(line: String): TraceEntry {
        val payloadStart = line.indexOf("$TraceMarker payload=")
        val hashStart = line.lastIndexOf(" sha256=")
        assertTrue("Malformed or truncated trace line: $line", payloadStart >= 0 && hashStart > payloadStart)
        val payload = line.substring(payloadStart + TraceMarker.length + " payload=".length, hashStart)
        val hash = line.substring(hashStart + " sha256=".length).trim()
        assertTrue("Trace payload exceeded 3000 UTF-8 bytes.", payload.toByteArray(Charsets.UTF_8).size <= 3_000)
        assertEquals("Trace payload SHA mismatch.", sha256(payload), hash)
        val json = JSONObject(payload)
        return TraceEntry(raw = line, payload = json, hash = hash, seq = json.getInt("seq"))
    }

    private fun clearOwnLogcat() {
        // Best-effort ring enlargement: trace evidence rides the logcat main buffer, and a busy CI emulator
        // can evict lines from the default ring (previously observed as a seq-contiguity gap).
        runCatching { runLogcat("-G", "16M") }
        runLogcat("-c")
    }

    private fun readOwnLogcat(): String =
        runLogcat("-d", "-v", "raw", "CanonicalFinalHost:V", "SlockKuikly:V", "*:S")

    private fun runLogcat(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("logcat") + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(10, TimeUnit.SECONDS)) { "logcat did not terminate" }
        check(process.exitValue() == 0) { "logcat failed: $output" }
        return output
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class TraceEntry(
        val raw: String,
        val payload: JSONObject,
        val hash: String,
        val seq: Int,
    )

    private data class TraceEvidence(
        val entries: List<TraceEntry>,
        val terminal: TraceEntry,
        val fullLog: String,
    )

    private data class RegistrationSlot(
        val surface: String,
        val serverId: String,
    )

    private data class RegistrationRecord(
        val surface: String,
        val leaseId: String,
        val serverId: String,
        val registrationId: String,
        val sinkRef: String,
    ) {
        val slot: RegistrationSlot = RegistrationSlot(surface, serverId)
    }

    private class LoopbackBlackhole : Closeable {
        private val running = AtomicBoolean(true)
        private val accepted = AtomicInteger(0)
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val acceptThread =
            thread(name = "canonical-final-loopback-blackhole", isDaemon = true) {
                while (running.get()) {
                    try {
                        val socket = server.accept()
                        accepted.incrementAndGet()
                        socket.setSoLinger(true, 0)
                        socket.close()
                    } catch (error: SocketException) {
                        if (running.get()) throw error
                    }
                }
            }

        val port: Int = server.localPort

        fun acceptedConnections(): Int = accepted.get()

        override fun close() {
            running.set(false)
            server.close()
            acceptThread.join(2_000)
        }
    }

    private companion object {
        const val PageName = "compose_realtime_canonical_final_actual_host"
        const val TraceMarker = "canonical_final_actual_host_trace"
        const val ServerA = "host-server-a"
        const val ServerB = "host-server-b"
        val RETENTION_EXPECTED = listOf("AppShellDomain@server-a", "AppShellHomeUnread@server-b")
        val RETENTION_FORBIDDEN = mapOf(
            "AppShellDomain@server-a" to "registration-7",
            "AppShellHomeUnread@server-b" to "registration-8",
        )
    }
}
