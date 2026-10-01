package build.raft.app.core.data.auth

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import build.raft.app.BuildConfig
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.postLoginAppShellPageData
import build.raft.app.shared.SlockKuiklyActivity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QaSessionBootstrapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!qaBootstrapRuntimeEnabled()) {
            abort("runtime_gate", "disabled")
            return
        }
        stage("activity_created")
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val expectedEnvelopeDigest = intent.getStringExtra(EXTRA_ENVELOPE_DIGEST)
        setIntent(Intent(this, QaSessionBootstrapActivity::class.java))
        // Reader-side coordinates for the private inbox. The hosted emulator reports
        // inbox_consume code=missing right after the harness verified the file
        // through run-as (task #188); only the path THIS process resolves can be
        // compared with the path the writer used. Paths and ids only, no contents.
        if (!requestId.isNullOrBlank()) {
            val inbox = java.io.File(applicationContext.filesDir, "qa-bootstrap/inbox")
            Log.i(
                LOG_TAG,
                "QA_BOOTSTRAP_DIAG filesDir=${applicationContext.filesDir.absolutePath}" +
                    " dataDir=${applicationContext.dataDir.absolutePath}" +
                    " uid=${android.os.Process.myUid()}" +
                    " user=${android.os.Process.myUserHandle().hashCode()}" +
                    " path=${java.io.File(inbox, "$requestId.session").absolutePath}" +
                    " inboxExists=${inbox.exists()}" +
                    // Names only (request ids), never contents: is the file the harness
                    // verified through run-as visible to THIS process before consume?
                    " inboxEntries=${inbox.list()?.joinToString(",") ?: "null"}",
            )
        }
        lifecycleScope.launch {
            if (!requestId.isNullOrBlank() && !expectedEnvelopeDigest.isNullOrBlank()) {
                consumeLoginEnvelope(requestId, expectedEnvelopeDigest)
            } else {
                recoverExistingSession()
            }
        }
    }

    private suspend fun consumeLoginEnvelope(requestId: String, expectedDigest: String) {
        val consumed = withContext(Dispatchers.IO) {
            QaSessionBootstrapInbox.consumeDetailed(applicationContext, requestId, "session")
        }
        val rawBytes = when (consumed) {
            is QaSessionBootstrapInbox.ConsumeResult.Consumed -> consumed.bytes
            is QaSessionBootstrapInbox.ConsumeResult.Rejected -> {
                abort("inbox_consume", consumed.code.wire)
                return
            }
        }
        stage("inbox_consumed")
        try {
            if ("sha256:${sha256(rawBytes)}" != expectedDigest) {
                abort("envelope_digest", "mismatch")
                return
            }
            stage("envelope_validated")
            when (
                val result = withContext(Dispatchers.IO) {
                    authDataSource().consumeQaLoginBootstrap(
                        envelopeJson = rawBytes.decodeToString(),
                        envelopeDigest = expectedDigest,
                        applicationId = packageName,
                        signingCertSha256 = signingCertDigest(),
                        deviceBootGeneration = bootGeneration(),
                    )
                }
            ) {
                is SharedAuthResult.Success -> {
                    stage("session_persisted")
                    openShell()
                }
                SharedAuthResult.Unauthenticated -> abort("login_consume", "unauthenticated")
                is SharedAuthResult.Failure -> abort("login_consume", result.code.name.lowercase())
            }
        } finally {
            rawBytes.fill(0)
        }
    }

    private suspend fun recoverExistingSession() {
        val source = authDataSource()
        val session = withContext(Dispatchers.IO) { source.readStoredSession() }
        if (session?.qaBootstrap == null) return abort("recovery_session", "missing")
        stage("recovery_session_loaded")
        when (val status = withContext(Dispatchers.IO) { source.statusQaBootstrap() }) {
            is SharedAuthResult.Success -> {
                if (status.data.status == "consumed_pending_ready" || status.data.status == "ready") {
                    val updated = withContext(Dispatchers.IO) {
                        val applied = source.applyQaBootstrapStatus(status.data) ?: return@withContext null
                        source.authorizeQaBootstrapLaunch(applied)
                    }
                    if (updated?.qaBootstrap != null) {
                        stage("recovery_authorized")
                        openShell()
                    } else {
                        abort("recovery_authorize", "binding_changed")
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(applicationContext))
                            .clearSession(reason = "qa_bootstrap_terminal", source = "qa_bootstrap_recovery")
                    }
                    abort("recovery_status", "terminal")
                }
            }
            SharedAuthResult.Unauthenticated -> abort("recovery_status", "unauthenticated")
            is SharedAuthResult.Failure -> abort("recovery_status", status.code.name.lowercase())
        }
    }

    private fun authDataSource(): SharedAuthDataSource =
        SharedAuthDataSource(
            api = AndroidAuthApiFactory.createAuthApi(applicationContext),
            sessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(applicationContext))
        )

    private fun openShell() {
        stage("shell_launch_requested")
        SlockKuiklyActivity.startAppShell(this, pageData = postLoginAppShellPageData(), clearTask = true)
        finish()
    }

    private fun stage(stage: String) {
        Log.i(LOG_TAG, "QA_BOOTSTRAP_STAGE stage=$stage")
    }

    private fun abort(stage: String, code: String) {
        Log.i(LOG_TAG, "QA_BOOTSTRAP_FAILURE stage=$stage code=$code")
        finish()
    }

    private fun qaBootstrapRuntimeEnabled(): Boolean =
        BuildConfig.QA_SESSION_BOOTSTRAP_ENABLED &&
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun signingCertDigest(): String {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.singleOrNull()
            ?: error("QA bootstrap requires exactly one signing certificate")
        return "sha256:${sha256(signer.toByteArray())}"
    }

    private fun bootGeneration(): String {
        val bootId = File("/proc/sys/kernel/random/boot_id").readText().trim()
        return "sha256:${sha256(bootId.encodeToByteArray())}"
    }

    companion object {
        private const val LOG_TAG = "SlockQaBootstrap"
        const val EXTRA_REQUEST_ID = "qaBootstrapRequestId"
        const val EXTRA_ENVELOPE_DIGEST = "qaBootstrapEnvelopeDigest"
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
