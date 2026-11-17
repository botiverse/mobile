package build.raft.app.core.data.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import build.raft.app.BuildConfig
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.StoredAuthSession
import java.io.File

/**
 * Release pre-login readiness receipt (docs/qa-acceptance/release-prelogin-contract.md).
 *
 * After `scripts/qa/release_prelogin.mjs` has signed a release build in for ONE Testbed attempt,
 * it asks the app itself what it is signed in to. The app answers with one non-sensitive logcat
 * line (tag [LOG_TAG]): the session's user id, the server actually selected (id + slug), the build
 * version, the attempt id + nonce it was asked with, and its own process identity. Never a token,
 * cookie, email or name.
 *
 * Gated OFF by default, in every build type:
 *  - the manifest declares this receiver `android:enabled="false"`: nothing reaches it until the
 *    QA runner enables the component over adb (`pm enable`), and the runner disables it again in
 *    the attempt's cleanup;
 *  - it is protected by `android.permission.DUMP` (signature|privileged|development): adb's shell
 *    holds it, ordinary apps cannot, so another app cannot trigger it even while it is enabled;
 *  - it has no intent filter: only an explicit component broadcast reaches it.
 * The line is only readable through logcat (adb or a READ_LOGS holder), not by other apps.
 */
class QaReleaseReadinessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request =
            QaReleaseReadinessRequest.parse(
                attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID),
                nonce = intent.getStringExtra(EXTRA_NONCE)
            )
        intent.replaceExtras(null)
        if (request == null) {
            Log.w(LOG_TAG, "$REJECTED_MARKER code=bad_request")
            return
        }
        // onReceive runs on the main thread: never wait on the session lock there (#3823).
        val session =
            KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(context)).readCommittedSessionSnapshot()
        val receipt =
            buildQaReleaseReadinessReceipt(
                request = request,
                session = session?.toQaReadinessSession(),
                packageName = context.packageName,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                pid = Process.myPid(),
                processStartTicks = runCatching { parseProcStatStartTicks(File("/proc/self/stat").readText()) }.getOrNull()
            )
        Log.i(LOG_TAG, "$RECEIPT_MARKER $receipt")
    }

    companion object {
        const val LOG_TAG = "RaftQaReadiness"
        const val RECEIPT_MARKER = "RAFT_QA_READINESS"
        const val REJECTED_MARKER = "RAFT_QA_READINESS_REJECTED"
        const val EXTRA_ATTEMPT_ID = "qaAttemptId"
        const val EXTRA_NONCE = "qaNonce"
        const val CONTRACT = "raft-qa-release-readiness/v1"
    }
}

/** What the runner sent: a Testbed attempt id and the fresh per-attempt nonce. */
internal data class QaReleaseReadinessRequest(val attemptId: String, val nonce: String) {
    companion object {
        private val AttemptIdRegex = Regex("^pla_[0-9a-f]{16,64}$")
        private val NonceRegex = Regex("^[0-9a-f]{32,128}$")

        fun parse(attemptId: String?, nonce: String?): QaReleaseReadinessRequest? {
            if (attemptId == null || nonce == null) return null
            if (!AttemptIdRegex.matches(attemptId) || !NonceRegex.matches(nonce)) return null
            return QaReleaseReadinessRequest(attemptId, nonce)
        }
    }
}

/** The only session facts the receipt may carry. */
internal data class QaReadinessSession(
    val userId: String,
    val selectedServerId: String?,
    val selectedServerSlug: String?
)

/**
 * The server the user ACTUALLY selected: the stored selection only, never the implicit
 * first-server fallback, so "signed in but still on Choose server" reads as no server.
 */
internal fun StoredAuthSession.toQaReadinessSession(): QaReadinessSession {
    val selected = selectedServerId?.takeIf { it.isNotBlank() }
    val slug =
        selected?.let { id ->
            selectedServerSlug?.takeIf { it.isNotBlank() } ?: servers.firstOrNull { it.id == id }?.slug
        }
    return QaReadinessSession(userId = user.id, selectedServerId = selected, selectedServerSlug = slug)
}

/** `/proc/<pid>/stat` field 22 (start time in clock ticks since boot), as the QA scripts read it. */
internal fun parseProcStatStartTicks(stat: String): String? {
    val close = stat.lastIndexOf(')')
    if (close < 0 || close + 2 > stat.length) return null
    val fields = stat.substring(close + 2).trim().split(' ')
    return fields.getOrNull(19)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

/**
 * One JSON object, flat, every value a string / number / null. `session == null` (signed out)
 * reports `signed_in: false` with no ids. No token, cookie, email or display name ever.
 */
internal fun buildQaReleaseReadinessReceipt(
    request: QaReleaseReadinessRequest,
    session: QaReadinessSession?,
    packageName: String,
    versionName: String,
    versionCode: Long,
    pid: Int,
    processStartTicks: String?
): String {
    val fields =
        linkedMapOf<String, Any?>(
            "contract" to QaReleaseReadinessReceiver.CONTRACT,
            "attempt_id" to request.attemptId,
            "nonce" to request.nonce,
            "package" to packageName,
            "version_name" to versionName,
            "version_code" to versionCode,
            "pid" to pid,
            "process_start_ticks" to processStartTicks,
            "signed_in" to (session != null),
            "user_id" to session?.userId,
            "selected_server_id" to session?.selectedServerId,
            "selected_server_slug" to session?.selectedServerSlug
        )
    return fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
        "\"$key\":${jsonValue(value)}"
    }
}

private fun jsonValue(value: Any?): String =
    when (value) {
        null -> "null"
        is Boolean, is Int, is Long -> value.toString()
        else -> jsonString(value.toString())
    }

private fun jsonString(text: String): String =
    buildString {
        append('"')
        text.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
