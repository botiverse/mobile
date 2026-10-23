package build.raft.app.core.data.diagnostics

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftAuthCriticalJournalTest {
    @Test
    fun authJournalLivesInsideTheSharedLaunchDirectory() {
        val launchDir = Files.createTempDirectory("slock-launch-auth").resolve("launch-1800000000000-a").toFile()

        assertEquals(
            launchDir.resolve("raft-auth-critical.jsonl"),
            authJournalFileIn(launchDir)
        )
    }

    @Test
    fun classifiesOnlyAuthCriticalMarkers() {
        assertEquals(
            "auth_session_clear",
            classifyAuthCriticalEvent("shared_auth auth_session_clear reason=unauthorized source=token_provider")
        )
        assertEquals(
            "refresh_success",
            classifyAuthCriticalEvent("shared_auth refresh_success reason=api caller=middleware accessRotated=true")
        )
        assertEquals(
            "auth_realtime_refresh_result",
            classifyAuthCriticalEvent("shared_auth auth_realtime_refresh_result result=failure code=403")
        )
        assertEquals(
            "shared_realtime_auth_refresh_failed",
            classifyAuthCriticalEvent("shared_realtime_auth_refresh_failed reason=resume code=403")
        )
        assertEquals(
            "auth_http_401",
            classifyAuthCriticalEvent("endpoint category=raw_absolute_url path=/api/auth/me code=401")
        )
        assertNull(classifyAuthCriticalEvent("tasks_refresh_start reason=user"))
        assertNull(classifyAuthCriticalEvent("endpoint category=channels path=/api/channels code=200"))
    }

    @Test
    fun journalsRefreshReplayAndSocketLifecycleEvents() {
        // Lines as SharedAuthRefreshCoordinator emits them (feedback 6feec67c), including the
        // android_socket lifecycle lines that the socket path logs through RaftAppLog.auth.
        val expected =
            mapOf(
                "refresh_start reason=connect_error caller=android_socket access=len=276:tail4=abcd:hash=1 " +
                    "refresh=len=64:tail4=ef01:hash=2 attempt=len=20:tail4=41c8:hash=3 replayed=true" to "refresh_start",
                "refresh_success reason=connect_error caller=android_socket accessRotated=true refreshRotated=true" to
                    "refresh_success",
                "refresh_attempt_reused reason=connect_error caller=android_socket attempt=len=20:tail4=41c8:hash=3 " +
                    "attemptAgeMs=1441516 replayReceiptTtlMs=900000 replayReceiptLikelyExpired=true " +
                    "diagnosis=likely_expired_server_replay_receipt" to "refresh_attempt_reused",
                "refresh_replay_rejected status=401 reason=connect_error caller=android_socket " +
                    "attemptAgeMs=1441516 replayReceiptLikelyExpired=true outcome=lost_rotation_after_freeze" to
                    "refresh_replay_rejected",
                "shared_auth refresh_rotation_superseded status=401 reason=auth_restore_401 caller=auth_restore " +
                    "replayed=false attemptAgeMs=1440000" to "refresh_rotation_superseded",
                "refresh_unauthorized_ignored status=401 decision=newer_session_held " +
                    "cause=refresh_rotation_superseded" to "refresh_unauthorized_ignored",
                "shared_network refresh_attempt_ambiguous action=preserve_session reason=replay_window_elapsed " +
                    "elapsedMs=1441516" to "refresh_attempt_ambiguous",
                "refresh_failed status=401 reason=connect_error caller=android_socket " +
                    "cause=refresh_replay_rejected replayed=true attemptAgeMs=1441516" to "refresh_failed",
            )

        expected.forEach { (message, event) ->
            assertEquals(message, event, classifyAuthCriticalEvent(message))
            assertTrue(message, hasFixedCostAuthCriticalHint(message))
        }
    }

    @Test
    fun retainsOnlyLatestFiftyEntries() {
        val retained = retainLatestAuthJournalLines((1..60).map { "line-$it" }, "line-61")

        assertEquals(50, retained.size)
        assertEquals("line-12", retained.first())
        assertEquals("line-61", retained.last())
    }

    @Test
    fun serializedEntryRedactsCredentialsButKeepsFingerprint() {
        val line =
            buildAuthCriticalJournalLine(
                timestamp = "2026-07-10T02:00:00.000+08:00",
                level = "warn",
                tag = "SlockAuth",
                event = "refresh_failed",
                message =
                    "refresh_failed status=401 refreshToken=secret-token " +
                        "fingerprint=len:32:tail4:abcd:hash:1234",
                thread = "test",
                seq = 1L,
                dropped = 0L
            )

        val json = JSONObject(line)
        val message = json.getString("message")
        assertFalse(message.contains("secret-token"))
        assertTrue(message.contains("refreshToken=[redacted]"))
        assertTrue(message.contains("fingerprint=len:32:tail4:abcd:hash:1234"))
    }

    @Test
    fun parsesRecentEntriesForSignedOutDiagnosticsScreen() {
        val valid =
            buildAuthCriticalJournalLine(
                timestamp = "2026-07-10T02:00:00.000+08:00",
                level = "warn",
                tag = "SlockKuikly",
                event = "auth_session_clear",
                message = "auth_session_clear reason=unauthorized source=current_user",
                thread = "test",
                seq = 1L,
                dropped = 0L
            )

        val entries = parseAuthCriticalJournalEntries(listOf("not-json", valid))

        assertEquals(1, entries.size)
        assertEquals("auth_session_clear", entries.single().event)
        assertEquals("SlockKuikly", entries.single().tag)
        assertTrue(entries.single().message.contains("source=current_user"))
    }
}
