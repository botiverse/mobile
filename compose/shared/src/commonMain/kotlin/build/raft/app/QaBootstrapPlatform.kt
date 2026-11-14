package build.raft.app

import build.raft.app.data.QA_BOOTSTRAP_LOGIN_PROTOCOL
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.StoredQaBootstrapMetadata
import build.raft.app.data.StoredQaBootstrapReadyReceipt
import build.raft.app.data.StoredQaBootstrapRuntimeEnvelope

/** Emits QA_BOOTSTRAP_READY once the QA-bootstrapped shell is on its route; true when READY was logged. */
internal expect suspend fun raftPlatformCompleteQaBootstrapRouteReady(authDataSource: SharedAuthDataSource): Boolean

/**
 * The READY handshake shared by Android and the iOS Simulator: persist the ready receipt, bind the
 * runtime envelope to this process and log the exact `QA_BOOTSTRAP_READY` line the harness parses
 * (scripts/qa/mobile_login_bootstrap_lib.mjs `parseReadyLine`). [log] receives whole lines and must
 * write them synchronously to the platform log the harness reads (logcat / the unified log).
 */
internal suspend fun completeQaBootstrapRouteReady(
    authDataSource: SharedAuthDataSource,
    pid: Int,
    processInstanceId: String,
    processGeneration: String,
    readyNanos: () -> String,
    log: (String) -> Unit,
): Boolean {
    val stage = { name: String -> log("QA_BOOTSTRAP_STAGE stage=$name") }
    val failure = { name: String, code: String -> log("QA_BOOTSTRAP_FAILURE stage=$name code=$code") }
    var session = authDataSource.readStoredSession() ?: return false
    var metadata = session.qaBootstrap ?: return false
    stage("route_ready_started")
    if (metadata.readyReceipt == null) {
        when (val result = authDataSource.readyQaBootstrap()) {
            is SharedAuthResult.Success -> stage("ready_receipt_persisted")
            SharedAuthResult.Unauthenticated -> {
                failure("ready_receipt", "unauthenticated")
                return false
            }
            is SharedAuthResult.Failure -> {
                failure("ready_receipt", result.code.name.lowercase())
                return false
            }
        }
        session = authDataSource.readStoredSession() ?: run {
            failure("ready_session_reload", "missing")
            return false
        }
        metadata = session.qaBootstrap ?: run {
            failure("ready_metadata_reload", "missing")
            return false
        }
    }
    val receipt = metadata.readyReceipt ?: run {
        failure("ready_receipt_reload", "missing")
        return false
    }
    val envelope = resolveQaBootstrapRuntimeEnvelope(
        metadata = metadata,
        receipt = receipt,
        pid = pid,
        processInstanceId = processInstanceId,
        processGeneration = processGeneration,
        readyNanos = readyNanos(),
        existing = metadata.runtimeEnvelope
    )
    val persisted = authDataSource.saveQaBootstrapRuntimeEnvelope(metadata, envelope) ?: run {
        failure("runtime_envelope_persist", "binding_changed")
        return false
    }
    metadata = persisted.qaBootstrap ?: run {
        failure("runtime_metadata_reload", "missing")
        return false
    }
    val persistedEnvelope = metadata.runtimeEnvelope ?: run {
        failure("runtime_envelope_reload", "missing")
        return false
    }
    stage("runtime_envelope_persisted")
    log(
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
    return true
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
    val routeProof = qaBootstrapSha256Hex("${metadata.targetRoute}|${metadata.serverId}|${metadata.sessionGeneration}")
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
        envelopeDigest = "sha256:${qaBootstrapSha256Hex(canonical)}",
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

private fun qaBootstrapSha256Hex(value: String): String = raftSha256Hex(value.encodeToByteArray())
