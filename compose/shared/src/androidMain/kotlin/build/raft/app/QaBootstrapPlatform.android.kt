package build.raft.app

import android.os.Process
import android.os.SystemClock
import android.util.Log
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.QA_BOOTSTRAP_LOGIN_PROTOCOL
import build.raft.app.data.StoredQaBootstrapMetadata
import build.raft.app.data.StoredQaBootstrapReadyReceipt
import build.raft.app.data.StoredQaBootstrapRuntimeEnvelope
import java.security.MessageDigest
import java.util.UUID

private const val QaBootstrapLogTag = "SlockQaBootstrap"
private val qaBootstrapProcessInstanceId by lazy { UUID.randomUUID().toString() }
private val qaBootstrapProcessGeneration by lazy { Process.getStartUptimeMillis().toString() }

internal actual suspend fun slockPlatformCompleteQaBootstrapRouteReady(authDataSource: SharedAuthDataSource) {
    var session = authDataSource.readStoredSession() ?: return
    var metadata = session.qaBootstrap ?: return
    if (metadata.readyReceipt == null) {
        when (authDataSource.readyQaBootstrap()) {
            is SharedAuthResult.Success -> Unit
            else -> return
        }
        session = authDataSource.readStoredSession() ?: return
        metadata = session.qaBootstrap ?: return
    }
    val receipt = metadata.readyReceipt ?: return
    val envelope = resolveQaBootstrapRuntimeEnvelope(
        metadata = metadata,
        receipt = receipt,
        pid = Process.myPid(),
        processInstanceId = qaBootstrapProcessInstanceId,
        processGeneration = qaBootstrapProcessGeneration,
        readyNanos = SystemClock.elapsedRealtimeNanos().toString(),
        existing = metadata.runtimeEnvelope
    )
    val persisted = authDataSource.saveQaBootstrapRuntimeEnvelope(metadata, envelope) ?: return
    metadata = persisted.qaBootstrap ?: return
    val persistedEnvelope = metadata.runtimeEnvelope ?: return
    Log.i(
        QaBootstrapLogTag,
        "QA_BOOTSTRAP_READY " +
            (if (metadata.protocol == QA_BOOTSTRAP_LOGIN_PROTOCOL) {
                "envelopeDigest=${metadata.ticketDigest} "
            } else {
                "ticketDigest=${metadata.ticketDigest} "
            }) +
            "runCaseId=${metadata.runCaseId} " +
            "attempt=${metadata.attempt} ciJobRef=${metadata.ciJobRef} principalId=${metadata.principalId} " +
            "serverId=${metadata.serverId} sessionGeneration=${metadata.sessionGeneration} " +
            "route=${metadata.targetRoute} applicationId=${metadata.applicationId} " +
            "bootGeneration=${metadata.deviceBootGeneration} pid=${persistedEnvelope.pid} " +
            "processInstanceId=${persistedEnvelope.processInstanceId} processGeneration=${persistedEnvelope.processGeneration} " +
            "deviceReadyElapsedRealtimeNanos=${persistedEnvelope.deviceReadyElapsedRealtimeNanos} " +
            "readyReceiptDigest=${persistedEnvelope.readyReceiptDigest} runtimeEnvelopeDigest=${persistedEnvelope.envelopeDigest} " +
            "routeReadyProofDigest=${persistedEnvelope.routeReadyProofDigest}" +
            (persistedEnvelope.recoveryOf?.let { " recoveryOf=$it" } ?: "")
    )
}

internal fun resolveQaBootstrapRuntimeEnvelope(
    metadata: StoredQaBootstrapMetadata,
    receipt: StoredQaBootstrapReadyReceipt,
    pid: Int,
    processInstanceId: String,
    processGeneration: String,
    readyNanos: String,
    existing: StoredQaBootstrapRuntimeEnvelope?
): StoredQaBootstrapRuntimeEnvelope {
    if (existing != null && existing.pid == pid && existing.processInstanceId == processInstanceId) return existing
    val routeProof = sha256("${metadata.targetRoute}|${metadata.serverId}|${metadata.sessionGeneration}")
    val canonical =
        listOf(
            receipt.receiptDigest,
            pid.toString(),
            processInstanceId,
            processGeneration,
            metadata.deviceBootGeneration,
            readyNanos,
            routeProof
        ).joinToString("|")
    return StoredQaBootstrapRuntimeEnvelope(
        envelopeDigest = "sha256:${sha256(canonical)}",
        readyReceiptDigest = receipt.receiptDigest,
        pid = pid,
        processInstanceId = processInstanceId,
        processGeneration = processGeneration,
        deviceBootGeneration = metadata.deviceBootGeneration,
        deviceReadyElapsedRealtimeNanos = readyNanos,
        routeReadyProofDigest = "sha256:$routeProof",
        recoveryOf = existing?.envelopeDigest
    )
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
