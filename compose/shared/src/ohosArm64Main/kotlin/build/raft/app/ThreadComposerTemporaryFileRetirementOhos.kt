package build.raft.app

import com.tencent.tmm.knoi.annotation.KNExport
import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI

/**
 * The ArkTS side exposes arrays/maps rather than Kotlin sets/data classes.  Keep that transport
 * shape at this boundary and convert it to the common ownership result immediately.
 */
private const val SLOCK_OHOS_THREAD_COMPOSER_TEMPORARY_FILE_RETIREMENT_SERVICE =
    "slockOhosThreadComposerTemporaryFileRetirement"

@ServiceConsumer(name = SLOCK_OHOS_THREAD_COMPOSER_TEMPORARY_FILE_RETIREMENT_SERVICE)
interface SlockOhosThreadComposerTemporaryFileRetirementService {
    /** KNOI maps Kotlin Array to an ArkTS Array; List is not representable in its TS generator. */
    fun retire(sessionId: String, fileRefs: Array<String>): Map<String, Any?>
}

private var slockOhosThreadComposerTemporaryFileRetirementInstalled = false

/**
 * Called by EntryAbility after KNOI setup/init and provider registration, before the first page.
 * Installing the handler explicitly avoids a first-frame race where the common persistence
 * owner could otherwise observe a missing platform handler.
 */
@KNExport
fun slockInstallOhosThreadComposerTemporaryFileRetirement(): Boolean {
    if (slockOhosThreadComposerTemporaryFileRetirementInstalled) return true
    ThreadComposerTemporaryFileRetirement.install(
        ThreadComposerTemporaryFileRetirementHandler { sessionId, fileRefs ->
            retireOhosThreadComposerTemporaryFileRefs(sessionId, fileRefs)
        }
    )
    slockOhosThreadComposerTemporaryFileRetirementInstalled = true
    return true
}

private fun retireOhosThreadComposerTemporaryFileRefs(
    sessionId: String,
    fileRefs: Set<String>
): ThreadComposerTemporaryFileRetirementResult {
    val cleanSessionId = sessionId.trim()
    val cleanRefs = fileRefs.map(String::trim).filter(String::isNotBlank).toCollection(linkedSetOf())
    if (cleanSessionId.isBlank()) {
        return ThreadComposerTemporaryFileRetirementResult(
            sessionId = cleanSessionId,
            requestedFileRefs = cleanRefs,
            rejectedFileRefs = cleanRefs,
            errorCode = ThreadComposerTemporaryFileRetirementResult.InvalidSessionCode
        )
    }
    if (cleanRefs.isEmpty()) return ThreadComposerTemporaryFileRetirementResult.empty(cleanSessionId)

    val response =
        runCatching {
            KNOI.get<SlockOhosThreadComposerTemporaryFileRetirementService>(
                SLOCK_OHOS_THREAD_COMPOSER_TEMPORARY_FILE_RETIREMENT_SERVICE
            ).retire(cleanSessionId, cleanRefs.toTypedArray())
        }.getOrElse {
            return ThreadComposerTemporaryFileRetirementResult(
                sessionId = cleanSessionId,
                requestedFileRefs = cleanRefs,
                failedFileRefs = cleanRefs,
                errorCode = ThreadComposerTemporaryFileRetirementResult.HandlerFailureCode
            )
        }
    return response.toRetirementResult(cleanSessionId, cleanRefs)
}

private fun Map<String, Any?>.toRetirementResult(
    sessionId: String,
    requested: Set<String>
): ThreadComposerTemporaryFileRetirementResult {
    val responseSessionId = (this["sessionId"] as? String)?.trim()
    val responseRequested = stringSet("requested")
    val admitted = stringSet("admitted")
    val quarantined = stringSet("quarantined")
    val rejected = stringSet("rejected")
    val failed = stringSet("failed")
    val tombstones = stringMap("tombstone")
    val responseError = (this["errorCode"] as? String)?.trim().orEmpty()

    val allSets = listOf(responseRequested, admitted, quarantined, rejected, failed)
    val malformed =
        responseSessionId == null ||
            responseSessionId != sessionId ||
            allSets.any { it == null } ||
            tombstones == null
    if (malformed) return contractFailure(sessionId, requested)

    val requestedFromHost = responseRequested ?: return contractFailure(sessionId, requested)
    val admittedFromHost = admitted ?: return contractFailure(sessionId, requested)
    val quarantinedFromHost = quarantined ?: return contractFailure(sessionId, requested)
    val rejectedFromHost = rejected ?: return contractFailure(sessionId, requested)
    val failedFromHost = failed ?: return contractFailure(sessionId, requested)
    val tombstonesFromHost = tombstones ?: return contractFailure(sessionId, requested)
    val union = admittedFromHost + quarantinedFromHost + rejectedFromHost + failedFromHost
    val disjoint =
        quarantinedFromHost.intersect(rejectedFromHost).isEmpty() &&
            quarantinedFromHost.intersect(failedFromHost).isEmpty() &&
            rejectedFromHost.intersect(failedFromHost).isEmpty()
    val shapeValid =
        requestedFromHost == requested &&
            union.all { it in requested } &&
            quarantinedFromHost.all { it in admittedFromHost } &&
            failedFromHost.all { it in admittedFromHost } &&
            tombstonesFromHost.keys == quarantinedFromHost &&
            tombstonesFromHost.values.all(String::isNotBlank) &&
            disjoint
    if (!shapeValid) return contractFailure(sessionId, requested)

    val effectiveError =
        responseError.ifBlank {
            if (quarantinedFromHost == requested && rejectedFromHost.isEmpty() && failedFromHost.isEmpty()) {
                ""
            } else {
                ThreadComposerTemporaryFileRetirementResult.HandlerFailureCode
            }
        }.ifBlank { null }
    return ThreadComposerTemporaryFileRetirementResult(
        sessionId = sessionId,
        requestedFileRefs = requested,
        admittedFileRefs = admittedFromHost,
        quarantinedFileRefs = quarantinedFromHost,
        rejectedFileRefs = rejectedFromHost,
        failedFileRefs = failedFromHost,
        tombstoneByFileRef = tombstonesFromHost,
        errorCode = effectiveError
    )
}

private fun contractFailure(
    sessionId: String,
    requested: Set<String>
): ThreadComposerTemporaryFileRetirementResult =
    ThreadComposerTemporaryFileRetirementResult(
        sessionId = sessionId,
        requestedFileRefs = requested,
        failedFileRefs = requested,
        errorCode = ThreadComposerTemporaryFileRetirementResult.HandlerContractCode
    )

/**
 * KNOI's dynamic Map return path may materialize a JavaScript Array as either
 * Kotlin `Array` or `List` depending on the generated call site's erased
 * return type.  Keep the boundary strict while accepting both legal shapes:
 * every element must be a String, and blank values are discarded after trim.
 */
internal fun Map<String, Any?>.stringSet(key: String): Set<String>? {
    val value = this[key] ?: return null
    val values: Iterable<*> = when (value) {
        is Array<*> -> value.asIterable()
        is List<*> -> value
        else -> return null
    }
    if (values.any { it !is String }) return null
    return values.map { (it as String).trim() }.filter(String::isNotBlank).toCollection(linkedSetOf())
}

private fun Map<String, Any?>.stringMap(key: String): Map<String, String>? {
    val value = this[key] ?: return null
    val values = value as? Map<*, *> ?: return null
    val result = linkedMapOf<String, String>()
    values.forEach { (rawKey, rawValue) ->
        if (rawKey !is String || rawValue !is String) return null
        val cleanKey = rawKey.trim()
        val cleanValue = rawValue.trim()
        if (cleanKey.isBlank() || cleanValue.isBlank()) return null
        result[cleanKey] = cleanValue
    }
    return result
}
