package build.raft.app

import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.RaftAppScopeApiEnvironment
import build.raft.app.data.RaftSharedAuthSessionKeyValueStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.createSharedAuthDataSourceForCurrentEnvironment
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSProcessInfo

/**
 * Kotlin half of the iOS Simulator QA session bootstrap (qa-bootstrap-login/v1): the step Android's
 * QaSessionBootstrapActivity runs after its inbox and digest checks. It hands the already validated
 * envelope to the shared [SharedAuthDataSource.consumeQaLoginBootstrap], which re-checks the binding,
 * resolves the server and persists the session through the shared auth session store.
 *
 * The only caller is RaftIosQaSessionBootstrapHost.swift, compiled only into the Debug configuration
 * (`RAFT_QA_SESSION_BOOTSTRAP`). Release builds have no caller, and this object refuses on its own
 * outside a Kotlin debug binary or outside the Simulator. Contract: docs/qa-acceptance/session-bootstrap.md.
 */
object RaftIosQaSessionBootstrapBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * [completion] runs on the main thread with `null` once the session is persisted, else a
     * lowercase failure code (`runtime_gate_*`, `unauthenticated`, a SharedAuthErrorCode name, `exception`).
     */
    @OptIn(ExperimentalNativeApi::class)
    fun consumeLoginEnvelope(
        envelopeJson: String,
        envelopeDigest: String,
        applicationId: String,
        signingCertSha256: String,
        deviceBootGeneration: String,
        completion: (String?) -> Unit,
    ) {
        val gate =
            when {
                !Platform.isDebugBinary -> "runtime_gate_release_binary"
                NSProcessInfo.processInfo.environment["SIMULATOR_UDID"] == null -> "runtime_gate_not_simulator"
                else -> null
            }
        if (gate != null) {
            completion(gate)
            return
        }
        scope.launch {
            val code =
                try {
                    consume(envelopeJson, envelopeDigest, applicationId, signingCertSha256, deviceBootGeneration)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    "exception"
                }
            withContext(Dispatchers.Main) { completion(code) }
        }
    }

    private suspend fun consume(
        envelopeJson: String,
        envelopeDigest: String,
        applicationId: String,
        signingCertSha256: String,
        deviceBootGeneration: String,
    ): String? {
        // Prefer the process store provider when the shell already created it, so the shell keeps the
        // in-process launch authorization; before that, the same app-scope stores the shell will open.
        val authDataSource =
            RaftSharedStoreRegistry.currentOrNull()?.createAuthDataSourceForCurrentEnvironment()
                ?: createSharedAuthDataSourceForCurrentEnvironment(
                    apiEnvironmentStore = RaftAppScopeApiEnvironment.store(),
                    authStore = KeyValueAuthSessionStore(RaftSharedAuthSessionKeyValueStoreProvider.create().store),
                )
        return when (
            val result =
                authDataSource.consumeQaLoginBootstrap(
                    envelopeJson = envelopeJson,
                    envelopeDigest = envelopeDigest,
                    applicationId = applicationId,
                    signingCertSha256 = signingCertSha256,
                    deviceBootGeneration = deviceBootGeneration,
                )
        ) {
            is SharedAuthResult.Success -> null
            SharedAuthResult.Unauthenticated -> "unauthenticated"
            is SharedAuthResult.Failure -> result.code.name.lowercase()
        }
    }
}
