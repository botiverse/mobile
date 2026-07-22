package build.raft.app.core.data.auth

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
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
            finish()
            return
        }
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val expectedEnvelopeDigest = intent.getStringExtra(EXTRA_ENVELOPE_DIGEST)
        setIntent(Intent(this, QaSessionBootstrapActivity::class.java))
        lifecycleScope.launch {
            if (!requestId.isNullOrBlank() && !expectedEnvelopeDigest.isNullOrBlank()) {
                consumeLoginEnvelope(requestId, expectedEnvelopeDigest)
            } else {
                recoverExistingSession()
            }
        }
    }

    private suspend fun consumeLoginEnvelope(requestId: String, expectedDigest: String) {
        val rawBytes = withContext(Dispatchers.IO) {
            QaSessionBootstrapInbox.consume(applicationContext, requestId, "session")
        } ?: return finish()
        try {
            if ("sha256:${sha256(rawBytes)}" != expectedDigest) return finish()
            when (
                withContext(Dispatchers.IO) {
                    authDataSource().consumeQaLoginBootstrap(
                        envelopeJson = rawBytes.decodeToString(),
                        envelopeDigest = expectedDigest,
                        applicationId = packageName,
                        signingCertSha256 = signingCertDigest(),
                        deviceBootGeneration = bootGeneration(),
                    )
                }
            ) {
                is SharedAuthResult.Success -> openShell()
                else -> finish()
            }
        } finally {
            rawBytes.fill(0)
        }
    }

    private suspend fun recoverExistingSession() {
        val source = authDataSource()
        val session = withContext(Dispatchers.IO) { source.readStoredSession() }
        if (session?.qaBootstrap == null) return finish()
        when (val status = withContext(Dispatchers.IO) { source.statusQaBootstrap() }) {
            is SharedAuthResult.Success -> {
                if (status.data.status == "consumed_pending_ready" || status.data.status == "ready") {
                    val updated = withContext(Dispatchers.IO) {
                        val applied = source.applyQaBootstrapStatus(status.data) ?: return@withContext null
                        source.authorizeQaBootstrapLaunch(applied)
                    }
                    if (updated?.qaBootstrap != null) openShell() else finish()
                } else {
                    withContext(Dispatchers.IO) {
                        KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(applicationContext))
                            .clearSession(reason = "qa_bootstrap_terminal", source = "qa_bootstrap_recovery")
                    }
                    finish()
                }
            }
            else -> finish()
        }
    }

    private fun authDataSource(): SharedAuthDataSource =
        SharedAuthDataSource(
            api = AndroidAuthApiFactory.createAuthApi(applicationContext),
            sessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(applicationContext))
        )

    private fun openShell() {
        SlockKuiklyActivity.startAppShell(this, pageData = postLoginAppShellPageData(), clearTask = true)
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
        const val EXTRA_REQUEST_ID = "qaBootstrapRequestId"
        const val EXTRA_ENVELOPE_DIGEST = "qaBootstrapEnvelopeDigest"
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
