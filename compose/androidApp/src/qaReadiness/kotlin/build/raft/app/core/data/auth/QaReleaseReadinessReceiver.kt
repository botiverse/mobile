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
import java.security.MessageDigest

/**
 * Release pre-login readiness receipt (docs/qa-acceptance/release-prelogin-contract.md).
 *
 * COMPILED OUT of the store build. This file sits in `src/qaReadiness/kotlin`, a source directory
 * that only the `debug` and the QA-instrumented `qaRelease` build types compile. Only
 * `src/qaRelease/AndroidManifest.xml` declares the receiver. `release` and `play` have neither the
 * class nor the manifest entry; `verifyReleaseHasNoQaReadinessReceiver` /
 * `verifyPlayHasNoQaReadinessReceiver` check their merged manifests on every build.
 *
 * In the qaRelease candidate it is still off by default. The manifest declares it disabled until
 * the QA runner enables it over adb for one attempt, and the runner disables it again in cleanup.
 * It needs `android.permission.DUMP` (adb's shell holds it; ordinary apps cannot get it) and has
 * no intent filter (explicit component only).
 *
 * After the runner signed the app in for ONE Testbed attempt, the app answers one request with one
 * logcat line (tag [LOG_TAG]). The line carries:
 *  - the attempt id, nonce and per-request id it was asked with;
 *  - `user_ref`, a nonce-bound sha256 of the session's user id (minimisation, not anonymisation
 *    and not a signature: the runner recomputes it from the frozen account id);
 *  - the server actually selected (id + slug);
 *  - the build version;
 *  - its own process identity.
 * Never a token, cookie, email, name or the raw user id.
 */
class QaReleaseReadinessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request =
            QaReleaseReadinessRequest.parse(
                attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID),
                nonce = intent.getStringExtra(EXTRA_NONCE),
                requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
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
        const val EXTRA_REQUEST_ID = "qaRequestId"
        const val CONTRACT = "raft-qa-release-readiness/v2"
        const val USER_REF_DOMAIN = "raft-qa-readiness-user/v1"
    }
}

/**
 * What the runner sent: the Testbed attempt id, its nonce (both minted by Testbed before dispatch)
 * and a fresh id for this one request, so a line written for an earlier request never answers it.
 */
internal data class QaReleaseReadinessRequest(val attemptId: String, val nonce: String, val requestId: String) {
    companion object {
        private val AttemptIdRegex = Regex("^pla_[0-9a-f]{32}$")
        private val NonceRegex = Regex("^[0-9a-f]{64}$")
        private val RequestIdRegex = Regex("^[0-9a-f]{32}$")

        fun parse(attemptId: String?, nonce: String?, requestId: String?): QaReleaseReadinessRequest? {
            if (attemptId == null || nonce == null || requestId == null) return null
            if (!AttemptIdRegex.matches(attemptId) || !NonceRegex.matches(nonce) || !RequestIdRegex.matches(requestId)) return null
            return QaReleaseReadinessRequest(attemptId, nonce, requestId)
        }
    }
}

/** The only session facts the receipt may use. */
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

/**
 * sha256(UTF-8 of "raft-qa-readiness-user/v1" NUL nonce NUL userId), lowercase hex. The same bytes
 * as `userRef` in scripts/qa/release_prelogin_lib.mjs (shared fixed test vector).
 */
internal fun qaReadinessUserRef(nonce: String, userId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("${QaReleaseReadinessReceiver.USER_REF_DOMAIN}\u0000$nonce\u0000$userId".encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

/** `/proc/<pid>/stat` field 22 (start time in clock ticks since boot), as the QA scripts read it. */
internal fun parseProcStatStartTicks(stat: String): String? {
    val close = stat.lastIndexOf(')')
    if (close < 0 || close + 2 > stat.length) return null
    val fields = stat.substring(close + 2).trim().split(' ')
    return fields.getOrNull(19)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

/**
 * One flat JSON object; every value is a string, number, boolean or null. `session == null`
 * (signed out) reports `signed_in: false` with no ids.
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
            "request_id" to request.requestId,
            "package" to packageName,
            "version_name" to versionName,
            "version_code" to versionCode,
            "pid" to pid,
            "process_start_ticks" to processStartTicks,
            "signed_in" to (session != null),
            "user_ref" to session?.let { qaReadinessUserRef(request.nonce, it.userId) },
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
