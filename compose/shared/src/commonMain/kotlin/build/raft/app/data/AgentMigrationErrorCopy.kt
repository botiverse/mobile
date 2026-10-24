package build.raft.app.data

/**
 * Port of the web's migration error copy (botiverse/slock `agentMigration/errors.ts` and
 * `presentation.ts`, `migrationFailurePresentation`): a migration failure or refusal becomes a
 * placement line, a friendly message and, only inside a collapsed "Technical details", the error
 * code. Raw server text (a JSON zod issue array, a stack) is never part of the body.
 *
 * Pure: it returns [MigrationCopy] keys plus their arguments; the UI resolves them to MR strings.
 * Not ported: computer-capability and resumable-capability per-side details (the app does not read
 * the `details` payload), `TARGET_RUNTIME_DETECTION_*` with a runtime name, and the diagnostic ref.
 */
internal enum class MigrationCopy {
    ErrorUiDisabled,
    ErrorNotSupported,
    ErrorAgentNotFound,
    ErrorTargetRequired,
    ErrorNoSourceMachine,
    ErrorTargetNotInServer,
    ErrorTargetMatchesSource,
    ErrorCapabilityInsufficient,
    ErrorTargetOffline,
    ErrorHostedUnavailable,
    ErrorHostedAlreadyUsed,
    ErrorHostedModelInvalid,
    ErrorHostedReviewNotReady,
    ErrorHostedMemoryUnimportable,
    ErrorNotCancelable,
    ErrorAlreadyInProgress,
    ErrorProPlanRequired,
    ErrorTransportNotProvisioned,
    ErrorTransportProvisionFailed,
    ErrorWorkspaceAlreadyExists,
    ErrorWorkspaceAlreadyExistsAt,
    ErrorWorkspaceCompleteOldCopy,
    ErrorWorkspaceCompleteOldCopyAt,
    ErrorObjectStoreUnreachable,
    ErrorFailedGeneric,
    ErrorControlManifestInvalid,
    ErrorEntryCountLimitExceeded,
    ErrorEntryCountLimitExceededWithDetails,
    ErrorEntryCountLimitExceededWithDetailsWithPaths,
    ErrorRefInvalid,
    ErrorRevisionInvalid,
    ErrorNotFound,
    ErrorRevisionStale,
    ErrorConcurrentUpdate,
    ErrorCancelFailed,
    ErrorStartFailed,
    ErrorStatusFailed,
    ErrorTargetRuntimeDetectionPending,
    ErrorTargetRuntimeDetectionIncomplete,
    ErrorTransportLost,
    ErrorTransportLostEvent,
    ErrorTransportLostAt,
    ErrorTransportLostRetryReady,
    ErrorTransportLostRetryOneOffline,
    ErrorTransportLostRetryBothOffline,
    ErrorTransportLostRetryUnknown,
    ErrorBundleTooLargeWithLimit,
    ErrorBundleTooLargeWithLimitWithItems,
    ErrorBundleTooLarge,
    ErrorBundleTooLargeWithItems,
    ErrorManifestTooLargeWithCount,
    ErrorManifestTooLargeWithCountWithPaths,
    ErrorManifestTooLarge,
    ErrorManifestTooLargeWithPaths,
    ErrorInsufficientDiskWithSizes,
    ErrorInsufficientDisk,
    AbortPrepDeadline,
    AbortTransferDeadline,
    AbortArrivalDeadline,
    AbortedDetailPrepDeadline,
    AbortedDetailPrepDeadlineAt,
    AbortedDetailTransferDeadline,
    AbortedDetailArrivalDeadline,
    AbortedDetailFallback,
    ErrorResumableGeneric,
    HostedAgentNotCreated,
    HostedTransportLost,
    TechnicalDetails,
    TechnicalErrorCode,
    StillOnSource,
    FailedAfterMove,
    HostedFailedAfterMove;
}

/** One line of copy: the [copy] key and its positional `%N$s` [args]. */
internal data class MigrationText(val copy: MigrationCopy, val args: List<String> = emptyList())

internal data class MigrationErrorPresentation(
    /** Shown first, on its own line: where the agent is now. */
    val placement: MigrationText? = null,
    /** One or more sentences, joined by a space. */
    val messageParts: List<MigrationText>,
    val issues: List<MigrationText> = emptyList(),
    /** A safe error code for the collapsed "Technical details"; null hides the disclosure. */
    val technicalCode: String? = null
)

internal enum class MigrationErrorContext { Start, Status, Failed, Aborted }

internal class MigrationErrorInput(
    val code: String? = null,
    val rawMessage: String? = null,
    val context: MigrationErrorContext,
    val reason: String? = null,
    val sourceComputerName: String? = null,
    val targetComputerName: String? = null,
    val sourceComputerOnline: Boolean? = null,
    val targetComputerOnline: Boolean? = null,
    val agentWorkspacePath: String? = null,
    val transportLostAt: String? = null,
    val formatTimestamp: ((String) -> String?)? = null
)

private const val GIB = 1024L * 1024L * 1024L
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private val SAFE_TECHNICAL_CODE = Regex("^[A-Za-z][A-Za-z0-9_-]{1,127}$")
private const val HOSTED_AGENT_NOT_CREATED = "HOSTED_AGENT_NOT_CREATED"

/** Web `wireInteger`: `key=123` as a `:`-separated field of the raw message. */
internal fun migrationWireInteger(raw: String?, key: String): Long? {
    val match = raw?.let { Regex("(?:^|:)${Regex.escape(key)}=(\\d+)(?=:|$)").find(it) } ?: return null
    val value = match.groupValues[1].toLongOrNull() ?: return null
    return value.takeIf { it in 0..MAX_SAFE_INTEGER }
}

/** Web `wireString`. */
internal fun migrationWireString(raw: String?, key: String): String? =
    raw?.let { Regex("(?:^|:)${Regex.escape(key)}=([^:]*)").find(it) }?.groupValues?.get(1)

/** Web `formatGib`: whole GiB without a decimal, otherwise one. */
internal fun migrationFormatGib(bytes: Long): String =
    if (bytes % GIB == 0L) {
        "${bytes / GIB} GiB"
    } else {
        val tenths = kotlin.math.round(bytes * 10.0 / GIB).toLong()
        "${tenths / 10}.${tenths % 10} GiB"
    }

private fun groupDigits(value: Long): String = value.toString().reversed().chunked(3).joinToString(",").reversed()

private fun hasControlCharacters(value: String): Boolean = value.any { it.code <= 31 || it.code == 127 }

/** JS `decodeURIComponent`; null for malformed input. */
internal fun migrationDecodeUriComponent(value: String): String? {
    val out = ArrayList<Byte>()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%') {
            val hi = value.getOrNull(i + 1)?.digitToIntOrNull(16) ?: return null
            val lo = value.getOrNull(i + 2)?.digitToIntOrNull(16) ?: return null
            out.add((hi * 16 + lo).toByte())
            i += 3
        } else {
            var end = i
            while (end < value.length && value[end] != '%') end++
            out.addAll(value.substring(i, end).encodeToByteArray().toList())
            i = end
        }
    }
    return try {
        out.toByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

internal data class MigrationLargestEntry(val path: String, val sizeBytes: Long)
internal data class MigrationPathCount(val path: String, val entryCount: Long)

internal fun migrationLargestEntries(raw: String?): List<MigrationLargestEntry> {
    val encoded = migrationWireString(raw, "topEntries")?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val entries = ArrayList<MigrationLargestEntry>()
    for (item in encoded.split(";").take(3)) {
        val separator = item.lastIndexOf(',')
        if (separator <= 0) continue
        val path = migrationDecodeUriComponent(item.substring(0, separator)) ?: continue
        val size = item.substring(separator + 1).toLongOrNull() ?: continue
        if (path.isEmpty() || path.length > 128 || hasControlCharacters(path) || size < 0 || size > MAX_SAFE_INTEGER) continue
        entries.add(MigrationLargestEntry(path, size))
    }
    return entries
}

private fun isSafeAccountingPath(value: String): Boolean {
    val basename = value.removeSuffix("/")
    return basename.isNotEmpty() &&
        basename != "." && basename != ".." &&
        !value.startsWith("/") && !value.startsWith("\\") && !value.contains("\\") &&
        !basename.contains("/") &&
        !Regex("^[A-Za-z]:").containsMatchIn(value) &&
        !Regex("^\\.env(?:\\.|$)", RegexOption.IGNORE_CASE).containsMatchIn(basename) &&
        !Regex("(?:secret|token|credential|api[-_]?key)", RegexOption.IGNORE_CASE).containsMatchIn(basename)
}

/** Web `parseTopPathCounts`; [strict] returns null on any malformed item. */
internal fun migrationTopPathCounts(raw: String?, key: String = "topPaths", strict: Boolean = false): List<MigrationPathCount>? {
    val encoded = migrationWireString(raw, key) ?: return if (strict) null else emptyList()
    if (encoded.isEmpty()) return emptyList()
    val items = encoded.split(";")
    if (strict && items.size > 3) return null
    val entries = ArrayList<MigrationPathCount>()
    for (item in items.take(3)) {
        val separator = item.lastIndexOf(',')
        if (separator <= 0) {
            if (strict) return null else continue
        }
        val path = migrationDecodeUriComponent(item.substring(0, separator))
        val count = item.substring(separator + 1).toLongOrNull()
        if (path == null || count == null || path.isEmpty() || path.length > 128 || hasControlCharacters(path) ||
            !isSafeAccountingPath(path) || count <= 0 || count > MAX_SAFE_INTEGER
        ) {
            if (strict) return null else continue
        }
        entries.add(MigrationPathCount(path, count))
    }
    return entries
}

private fun pathList(entries: List<MigrationPathCount>): String =
    entries.joinToString(", ") { "${it.path} (${groupDigits(it.entryCount)})" }

private fun bundleTooLarge(raw: String?): MigrationText {
    val maxBytes = migrationWireInteger(raw, "maxBytes")
    val items = migrationLargestEntries(raw).joinToString(", ") { "${it.path} (${migrationFormatGib(it.sizeBytes)})" }
    val limit = maxBytes?.let(::migrationFormatGib)
    return when {
        limit != null && items.isNotEmpty() -> MigrationText(MigrationCopy.ErrorBundleTooLargeWithLimitWithItems, listOf(limit, items))
        limit != null -> MigrationText(MigrationCopy.ErrorBundleTooLargeWithLimit, listOf(limit))
        items.isNotEmpty() -> MigrationText(MigrationCopy.ErrorBundleTooLargeWithItems, listOf(items))
        else -> MigrationText(MigrationCopy.ErrorBundleTooLarge)
    }
}

private fun manifestTooLarge(raw: String?): MigrationText {
    val count = migrationWireInteger(raw, "entryCount")
    val paths = pathList(migrationTopPathCounts(raw).orEmpty())
    return when {
        count != null && paths.isNotEmpty() -> MigrationText(MigrationCopy.ErrorManifestTooLargeWithCountWithPaths, listOf(groupDigits(count), paths))
        count != null -> MigrationText(MigrationCopy.ErrorManifestTooLargeWithCount, listOf(groupDigits(count)))
        paths.isNotEmpty() -> MigrationText(MigrationCopy.ErrorManifestTooLargeWithPaths, listOf(paths))
        else -> MigrationText(MigrationCopy.ErrorManifestTooLarge)
    }
}

private fun entryCountLimit(raw: String?): MigrationText {
    val count = migrationWireInteger(raw, "entryCount")
    val max = migrationWireInteger(raw, "maxEntries")
    val top = migrationTopPathCounts(raw, "topPathCounts", strict = true)
    if (count == null || max == null || max <= 0 || count <= max || top == null || top.any { it.entryCount > count }) {
        return MigrationText(MigrationCopy.ErrorEntryCountLimitExceeded)
    }
    val paths = pathList(top)
    val base = listOf(groupDigits(count), groupDigits(max))
    return if (paths.isEmpty()) {
        MigrationText(MigrationCopy.ErrorEntryCountLimitExceededWithDetails, base)
    } else {
        MigrationText(MigrationCopy.ErrorEntryCountLimitExceededWithDetailsWithPaths, base + paths)
    }
}

private fun insufficientDisk(raw: String?): MigrationText {
    val required = migrationWireInteger(raw, "requiredBytes")
    val available = migrationWireInteger(raw, "availableBytes")
    return if (required != null && available != null) {
        MigrationText(MigrationCopy.ErrorInsufficientDiskWithSizes, listOf(migrationFormatGib(required), migrationFormatGib(available)))
    } else {
        MigrationText(MigrationCopy.ErrorInsufficientDisk)
    }
}

/** Two sentences, as the web joins them with one space. */
private fun transportLost(input: MigrationErrorInput): List<MigrationText> {
    // Only a failed migration has a place and time for the drop; refusals use the short line.
    if (input.context != MigrationErrorContext.Failed) return listOf(MigrationText(MigrationCopy.ErrorTransportLost))
    val lostAt = input.transportLostAt?.takeIf { it.isNotBlank() }?.let { input.formatTimestamp?.invoke(it) }
    val event =
        if (lostAt != null) MigrationText(MigrationCopy.ErrorTransportLostAt, listOf(lostAt)) else MigrationText(MigrationCopy.ErrorTransportLostEvent)
    val source = input.sourceComputerName?.takeIf { it.isNotEmpty() && input.sourceComputerOnline != null }
    val target = input.targetComputerName?.takeIf { it.isNotEmpty() && input.targetComputerOnline != null }
    val sourceOffline = input.sourceComputerOnline == false
    val targetOffline = input.targetComputerOnline == false
    val retry =
        when {
            source == null || target == null -> MigrationText(MigrationCopy.ErrorTransportLostRetryUnknown)
            !sourceOffline && !targetOffline -> MigrationText(MigrationCopy.ErrorTransportLostRetryReady)
            sourceOffline && targetOffline -> MigrationText(MigrationCopy.ErrorTransportLostRetryBothOffline, listOf(source, target))
            else -> MigrationText(MigrationCopy.ErrorTransportLostRetryOneOffline, listOf(if (sourceOffline) source else target))
        }
    return listOf(event, retry)
}

private fun simple(copy: MigrationCopy) = listOf(MigrationText(copy))

/** Web `MIGRATION_ERROR_IDS`. */
private val MIGRATION_ERROR_COPY: Map<String, MigrationCopy> =
    mapOf(
        "agent_migration_ui_disabled" to MigrationCopy.ErrorUiDisabled,
        "not_supported" to MigrationCopy.ErrorNotSupported,
        "AGENT_NOT_FOUND" to MigrationCopy.ErrorAgentNotFound,
        "TARGET_COMPUTER_REQUIRED" to MigrationCopy.ErrorTargetRequired,
        "AGENT_HAS_NO_SOURCE_MACHINE" to MigrationCopy.ErrorNoSourceMachine,
        "TARGET_COMPUTER_NOT_IN_SERVER" to MigrationCopy.ErrorTargetNotInServer,
        "TARGET_COMPUTER_MATCHES_SOURCE" to MigrationCopy.ErrorTargetMatchesSource,
        "COMPUTER_CAPABILITY_INSUFFICIENT" to MigrationCopy.ErrorCapabilityInsufficient,
        "MIGRATION_RESUMABLE_CAPABILITY_REQUIRED" to MigrationCopy.ErrorResumableGeneric,
        "TARGET_COMPUTER_OFFLINE" to MigrationCopy.ErrorTargetOffline,
        "TARGET_RUNTIME_DETECTION_PENDING" to MigrationCopy.ErrorTargetRuntimeDetectionPending,
        "HOSTED_TARGET_UNAVAILABLE" to MigrationCopy.ErrorHostedUnavailable,
        "HOSTED_TARGET_ALREADY_USED" to MigrationCopy.ErrorHostedAlreadyUsed,
        "HOSTED_TARGET_MODEL_INVALID" to MigrationCopy.ErrorHostedModelInvalid,
        "HOSTED_REVIEW_NOT_READY" to MigrationCopy.ErrorHostedReviewNotReady,
        "HOSTED_IMPORT_MEMORY_UNIMPORTABLE" to MigrationCopy.ErrorHostedMemoryUnimportable,
        "MIGRATION_NOT_CANCELABLE" to MigrationCopy.ErrorNotCancelable,
        "MIGRATION_ALREADY_IN_PROGRESS" to MigrationCopy.ErrorAlreadyInProgress,
        "MIGRATION_PRO_PLAN_REQUIRED" to MigrationCopy.ErrorProPlanRequired,
        "MIGRATION_TRANSPORT_NOT_PROVISIONED" to MigrationCopy.ErrorTransportNotProvisioned,
        "MIGRATION_TRANSPORT_PROVISION_FAILED" to MigrationCopy.ErrorTransportProvisionFailed,
        "MIGRATION_OBJECT_STORE_UNREACHABLE" to MigrationCopy.ErrorObjectStoreUnreachable,
        // Transient transfer faults: the user can only retry, so they share one line.
        "MIGRATION_CHUNK_DIGEST_MISMATCH" to MigrationCopy.ErrorFailedGeneric,
        "MIGRATION_WHOLE_BUNDLE_DIGEST_MISMATCH" to MigrationCopy.ErrorFailedGeneric,
        "MIGRATION_LEASE_EXPIRED" to MigrationCopy.ErrorFailedGeneric,
        "MIGRATION_GENERATION_STALE" to MigrationCopy.ErrorFailedGeneric,
        "MIGRATION_CONTROL_MANIFEST_INVALID" to MigrationCopy.ErrorControlManifestInvalid,
        "MIGRATION_CONTROL_MANIFEST_TOO_LARGE" to MigrationCopy.ErrorEntryCountLimitExceeded,
        "MIGRATION_REF_INVALID" to MigrationCopy.ErrorRefInvalid,
        "MIGRATION_REVISION_INVALID" to MigrationCopy.ErrorRevisionInvalid,
        "MIGRATION_NOT_FOUND" to MigrationCopy.ErrorNotFound,
        "MIGRATION_REVISION_STALE" to MigrationCopy.ErrorRevisionStale,
        "MIGRATION_CONCURRENT_UPDATE" to MigrationCopy.ErrorConcurrentUpdate,
        "MIGRATION_CANCEL_FAILED" to MigrationCopy.ErrorCancelFailed,
        "MIGRATION_START_FAILED" to MigrationCopy.ErrorStartFailed,
        "MIGRATION_STATUS_FAILED" to MigrationCopy.ErrorStatusFailed
    )

/** Codes whose copy is assembled from wire data or the input (web `MIGRATION_ERROR_BUILDERS` / `INPUT_BUILDERS`). */
private fun builtMessage(code: String, input: MigrationErrorInput): List<MigrationText>? =
    when (code) {
        "MIGRATION_OBJECT_STORE_BUNDLE_TOO_LARGE" -> listOf(bundleTooLarge(input.rawMessage))
        "MIGRATION_OBJECT_STORE_MANIFEST_TOO_LARGE" -> listOf(manifestTooLarge(input.rawMessage))
        "MIGRATION_OBJECT_STORE_INSUFFICIENT_DISK" -> listOf(insufficientDisk(input.rawMessage))
        "MIGRATION_OBJECT_STORE_ENTRY_COUNT_LIMIT_EXCEEDED" -> listOf(entryCountLimit(input.rawMessage))
        "MIGRATION_TRANSPORT_LOST" -> transportLost(input)
        "MIGRATION_WORKSPACE_ALREADY_EXISTS" ->
            listOf(workspaceConflict(input, MigrationCopy.ErrorWorkspaceAlreadyExistsAt, MigrationCopy.ErrorWorkspaceAlreadyExists))
        "MIGRATION_WORKSPACE_COMPLETE_OLD_COPY" ->
            listOf(workspaceConflict(input, MigrationCopy.ErrorWorkspaceCompleteOldCopyAt, MigrationCopy.ErrorWorkspaceCompleteOldCopy))
        else -> null
    }

private fun workspaceConflict(input: MigrationErrorInput, dated: MigrationCopy, plain: MigrationCopy): MigrationText {
    val path = input.agentWorkspacePath?.trim()?.takeIf { it.isNotEmpty() }
    return if (path != null) MigrationText(dated, listOf(path)) else MigrationText(plain)
}

internal fun migrationAbortDetail(reason: String?, deadline: String?, source: String, target: String): MigrationText =
    when (reason) {
        "prep-deadline" ->
            if (deadline != null) MigrationText(MigrationCopy.AbortedDetailPrepDeadlineAt, listOf(source, deadline))
            else MigrationText(MigrationCopy.AbortedDetailPrepDeadline, listOf(source))
        "transfer-deadline" -> MigrationText(MigrationCopy.AbortedDetailTransferDeadline)
        "arrival-deadline" -> MigrationText(MigrationCopy.AbortedDetailArrivalDeadline, listOf(target))
        else -> MigrationText(MigrationCopy.AbortedDetailFallback)
    }

private fun fallbackMessage(context: MigrationErrorContext, reason: String?): MigrationText =
    when (context) {
        MigrationErrorContext.Status -> MigrationText(MigrationCopy.ErrorStatusFailed)
        MigrationErrorContext.Aborted ->
            when (reason) {
                "prep-deadline" -> MigrationText(MigrationCopy.AbortPrepDeadline)
                "transfer-deadline" -> MigrationText(MigrationCopy.AbortTransferDeadline)
                "arrival-deadline" -> MigrationText(MigrationCopy.AbortArrivalDeadline)
                else -> MigrationText(MigrationCopy.AbortedDetailFallback)
            }
        MigrationErrorContext.Failed -> MigrationText(MigrationCopy.ErrorFailedGeneric)
        MigrationErrorContext.Start -> MigrationText(MigrationCopy.ErrorStartFailed)
    }

/**
 * Web `migrationFailureCopyCode`: `transportErrorCode` may carry a detailed daemon cause with no
 * copy of its own; use it only when it has copy, otherwise the known `failureReason`.
 */
internal fun migrationFailureCopyCode(transportErrorCode: String?, failureReason: String?): String? {
    if (transportErrorCode.isNullOrEmpty()) return failureReason
    val hasCopy = transportErrorCode in MIGRATION_ERROR_COPY || builtMessage(transportErrorCode, MigrationErrorInput(context = MigrationErrorContext.Failed)) != null
    return if (hasCopy || failureReason.isNullOrEmpty()) transportErrorCode else failureReason
}

/** Web `migrationErrorPresentation`. An unknown code yields the generic copy for [MigrationErrorInput.context], never [MigrationErrorInput.rawMessage]. */
internal fun migrationErrorPresentation(input: MigrationErrorInput): MigrationErrorPresentation {
    val technicalCode =
        input.code?.takeIf { SAFE_TECHNICAL_CODE.matches(it) } ?: input.reason?.takeIf { SAFE_TECHNICAL_CODE.matches(it) }
    val code = input.code.orEmpty()
    val parts =
        builtMessage(code, input)
            ?: MIGRATION_ERROR_COPY[code]?.let(::simple)
            ?: listOf(fallbackMessage(input.context, input.reason))
    return MigrationErrorPresentation(messageParts = parts, technicalCode = technicalCode)
}

/** What [migrationFailurePresentation] needs to know about the computers (names already resolved). */
internal class MigrationFailureComputers(
    val sourceName: String,
    val targetName: String,
    val sourceOnline: Boolean? = null,
    val targetOnline: Boolean? = null
)

private fun failureReasonPresentation(
    notice: AgentMigrationNotice,
    computers: MigrationFailureComputers,
    formatTimestamp: (String) -> String?
): MigrationErrorPresentation {
    if (notice.state == "aborted") {
        val fallback = migrationErrorPresentation(MigrationErrorInput(context = MigrationErrorContext.Aborted, reason = notice.abortReason))
        val deadline = notice.prepDeadlineAt?.let(formatTimestamp)
        return MigrationErrorPresentation(
            messageParts = listOf(migrationAbortDetail(notice.abortReason, deadline, computers.sourceName, computers.targetName)),
            technicalCode = fallback.technicalCode
        )
    }
    val copyCode = migrationFailureCopyCode(notice.transportErrorCode, notice.failureReason)
    // There is no second computer in a move to the cloud: only the upload from the source can drop.
    if (notice.targetKind == "hosted" && copyCode == "MIGRATION_TRANSPORT_LOST") {
        return MigrationErrorPresentation(
            messageParts = listOf(MigrationText(MigrationCopy.HostedTransportLost, listOf(computers.sourceName))),
            technicalCode = copyCode
        )
    }
    return migrationErrorPresentation(
        MigrationErrorInput(
            code = copyCode,
            rawMessage = notice.transportErrorMessage,
            context = MigrationErrorContext.Failed,
            reason = notice.abortReason,
            agentWorkspacePath = notice.agentId.trim().takeIf { it.isNotEmpty() }?.let { "~/.slock/agents/$it" },
            sourceComputerName = computers.sourceName,
            targetComputerName = computers.targetName,
            sourceComputerOnline = computers.sourceOnline,
            targetComputerOnline = computers.targetOnline,
            transportLostAt = notice.transportLostAt,
            formatTimestamp = formatTimestamp
        )
    )
}

/**
 * Web `migrationFailurePresentation`: before the flip the agent never left the source, so the
 * placement says that first; a user's first worry is whether the agent still works.
 */
internal fun migrationFailurePresentation(
    notice: AgentMigrationNotice,
    computers: MigrationFailureComputers,
    formatTimestamp: (String) -> String? = { it }
): MigrationErrorPresentation {
    val hosted = notice.targetKind == "hosted"
    if (hosted && (notice.failureReason == HOSTED_AGENT_NOT_CREATED || notice.transportErrorCode == HOSTED_AGENT_NOT_CREATED)) {
        return MigrationErrorPresentation(
            placement = MigrationText(MigrationCopy.StillOnSource, listOf(computers.sourceName)),
            messageParts = listOf(MigrationText(MigrationCopy.HostedAgentNotCreated, listOf(computers.sourceName))),
            technicalCode = HOSTED_AGENT_NOT_CREATED
        )
    }
    val reason = failureReasonPresentation(notice, computers, formatTimestamp)
    if (notice.flippedAt != null) {
        return reason.copy(
            messageParts =
                listOf(
                    if (hosted) MigrationText(MigrationCopy.HostedFailedAfterMove)
                    else MigrationText(MigrationCopy.FailedAfterMove, listOf(computers.sourceName, computers.targetName))
                )
        )
    }
    return reason.copy(placement = MigrationText(MigrationCopy.StillOnSource, listOf(computers.sourceName)))
}
