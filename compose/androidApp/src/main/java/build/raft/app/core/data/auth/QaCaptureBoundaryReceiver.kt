package build.raft.app.core.data.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.util.Log
import build.raft.app.BuildConfig
import build.raft.app.data.KeyValueAuthSessionStore
import java.security.MessageDigest
import org.json.JSONObject

class QaCaptureBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            !BuildConfig.QA_SESSION_BOOTSTRAP_ENABLED ||
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0
        ) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val requestDigest = intent.getStringExtra(EXTRA_REQUEST_DIGEST) ?: return
        intent.replaceExtras(null)
        val bytes = QaSessionBootstrapInbox.consume(context, requestId, "capture") ?: return
        try {
            if ("sha256:${sha256(bytes)}" != requestDigest) return
            val request = JSONObject(bytes.decodeToString())
            // onReceive runs on the main thread: never wait on the session lock there (#3823).
            val session =
                KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(context)).readCommittedSessionSnapshot()
                    ?: return
            val metadata = session.qaBootstrap ?: return
            val envelope = metadata.runtimeEnvelope ?: return
            val proofRequest =
                QaCaptureProofRequest(
                    readyReceiptDigest = request.optString("readyReceiptDigest"),
                    runCaseId = request.optString("runCaseId"),
                    attempt = request.optInt("attempt"),
                    captureWindowNonce = request.optString("captureWindowNonce"),
                    scopeGeneration = request.optString("scopeGeneration"),
                    producerCommandGeneration = request.optString("producerCommandGeneration"),
                    imageSha256 = request.optString("imageSha256")
                )
            if (!qaCaptureRequestMatches(proofRequest, metadata, envelope, Process.myPid(), Process.getStartUptimeMillis().toString())) {
                Log.w(LOG_TAG, "QA_CAPTURE_BOUNDARY_REJECTED code=scope_mismatch requestId=$requestId")
                return
            }
            val capturedNanos = SystemClock.elapsedRealtimeNanos().toString()
            Log.i(
                LOG_TAG,
                    "QA_CAPTURE_BOUNDARY requestId=$requestId nonceDigest=sha256:${sha256(proofRequest.captureWindowNonce.encodeToByteArray())} " +
                    "scopeGeneration=${proofRequest.scopeGeneration} producerCommandGeneration=${proofRequest.producerCommandGeneration} " +
                    "imageSha256=${proofRequest.imageSha256} " +
                    "runCaseId=${metadata.runCaseId} attempt=${metadata.attempt} " +
                    "readyReceiptDigest=${envelope.readyReceiptDigest} pid=${envelope.pid} " +
                    "processInstanceId=${envelope.processInstanceId} processGeneration=${envelope.processGeneration} " +
                    "bootGeneration=${metadata.deviceBootGeneration} " +
                    "deviceCapturedElapsedRealtimeNanos=$capturedNanos"
            )
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        private const val LOG_TAG = "SlockQaBootstrap"
        const val EXTRA_REQUEST_ID = "qaCaptureRequestId"
        const val EXTRA_REQUEST_DIGEST = "qaCaptureRequestDigest"
    }
}

internal data class QaCaptureProofRequest(
    val readyReceiptDigest: String,
    val runCaseId: String,
    val attempt: Int,
    val captureWindowNonce: String,
    val scopeGeneration: String,
    val producerCommandGeneration: String,
    val imageSha256: String
)

internal fun qaCaptureRequestMatches(
    request: QaCaptureProofRequest,
    metadata: build.raft.app.data.StoredQaBootstrapMetadata,
    envelope: build.raft.app.data.StoredQaBootstrapRuntimeEnvelope,
    currentPid: Int,
    currentProcessGeneration: String
): Boolean =
    metadata.state == "ready" &&
        request.readyReceiptDigest == envelope.readyReceiptDigest &&
        request.runCaseId == metadata.runCaseId &&
        request.attempt == metadata.attempt &&
        request.scopeGeneration == envelope.processGeneration &&
        request.captureWindowNonce.isNotBlank() &&
        request.producerCommandGeneration.isNotBlank() &&
        request.imageSha256.matches(Regex("^[0-9a-f]{64}$")) &&
        envelope.pid == currentPid &&
        envelope.processGeneration == currentProcessGeneration &&
        envelope.deviceBootGeneration == metadata.deviceBootGeneration

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
