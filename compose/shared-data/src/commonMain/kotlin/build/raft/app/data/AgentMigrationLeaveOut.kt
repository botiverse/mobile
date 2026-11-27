package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

// Port of botiverse/slock #17048:
// - packages/shared/src/agentMigrationUserIgnore.ts
// - packages/web/src/components/agentMigration/leaveOut.ts

@RaftCoreInternalApi const val MAX_AGENT_MIGRATION_USER_IGNORE_PATHS: Int = 200
@RaftCoreInternalApi const val MAX_AGENT_MIGRATION_USER_IGNORE_PATH_BYTES: Int = 1024

private const val UNNAMED_ACCOUNTING_PATH = "other/"

private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
private val REPEATED_SLASHES = Regex("/{2,}")
private val PATTERN_CHARS = Regex("[*?\\[\\]{}\\\\]")

@RaftCoreInternalApi enum class AgentMigrationLeaveOutProblem {
    Invalid,
    AlwaysMoved,
}

@RaftCoreInternalApi data class AgentMigrationLeaveOutCandidate(
    val path: String,
    val sizeBytes: Long,
)

@RaftCoreInternalApi sealed interface AgentMigrationTypedLeaveOutResult {
    data class Valid(val path: String) : AgentMigrationTypedLeaveOutResult
    data class Invalid(val problem: AgentMigrationLeaveOutProblem) : AgentMigrationTypedLeaveOutResult
}

private fun hasLeaveOutControlChars(value: String): Boolean {
    for (ch in value) {
        val code = ch.code
        if (code in 0x00..0x1f || code in 0x7f..0x9f || code == 0x2028 || code == 0x2029) {
            return true
        }
    }
    return false
}

/**
 * Normalizes a workspace-relative leave-out path (web `normalizeAgentMigrationLeaveOutPath`,
 * slock #17048): trims surrounding whitespace, collapses repeated `/`, and strips leading `./`.
 */
@RaftCoreInternalApi fun normalizeAgentMigrationLeaveOutPath(value: String): String {
    var path = REPEATED_SLASHES.replace(value.trim(), "/")
    while (path.startsWith("./")) {
        path = path.removePrefix("./")
    }
    return path
}

private fun isAlwaysMovedLeaveOutSegment(first: String, isOnlySegment: Boolean): Boolean {
    val name = first.lowercase()
    if (name == "notes" || name == ".raft") return true
    if (!isOnlySegment) return false
    return name == "memory.md" ||
        name.startsWith(".raftbackupignore") ||
        name.startsWith(".raftmigrateignore")
}

/**
 * Validates one leave-out path (web `agentMigrationLeaveOutProblem`, slock #17048):
 * returns `null` if acceptable, [AgentMigrationLeaveOutProblem.AlwaysMoved] for protected paths
 * (`MEMORY.md`, `notes/`, `.raft/`, `.raftbackupignore*`, `.raftmigrateignore*`), or
 * [AgentMigrationLeaveOutProblem.Invalid] for empty/control/over-1024-byte/escaped/glob paths.
 */
@RaftCoreInternalApi fun agentMigrationLeaveOutProblem(value: String): AgentMigrationLeaveOutProblem? {
    val path = normalizeAgentMigrationLeaveOutPath(value)
    if (path.isEmpty() || hasLeaveOutControlChars(path)) {
        return AgentMigrationLeaveOutProblem.Invalid
    }
    if (path.encodeToByteArray().size > MAX_AGENT_MIGRATION_USER_IGNORE_PATH_BYTES) {
        return AgentMigrationLeaveOutProblem.Invalid
    }
    if (
        path.startsWith("#") ||
        path.startsWith("!") ||
        path.startsWith("/") ||
        WINDOWS_DRIVE_PREFIX.containsMatchIn(path)
    ) {
        return AgentMigrationLeaveOutProblem.Invalid
    }
    if (path.startsWith("**/")) {
        val name = path.removePrefix("**/").removeSuffix("/")
        if (name.isEmpty() || name.contains("/") || name == "." || name == ".." || PATTERN_CHARS.containsMatchIn(name)) {
            return AgentMigrationLeaveOutProblem.Invalid
        }
        return if (isAlwaysMovedLeaveOutSegment(name, isOnlySegment = true)) {
            AgentMigrationLeaveOutProblem.AlwaysMoved
        } else {
            null
        }
    }
    if (PATTERN_CHARS.containsMatchIn(path)) {
        return AgentMigrationLeaveOutProblem.Invalid
    }
    val segments = path.removeSuffix("/").split("/")
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
        return AgentMigrationLeaveOutProblem.Invalid
    }
    return if (isAlwaysMovedLeaveOutSegment(segments[0], isOnlySegment = segments.size == 1)) {
        AgentMigrationLeaveOutProblem.AlwaysMoved
    } else {
        null
    }
}

/** Validates and normalizes a user-typed custom leave-out path (web `readTypedLeaveOutPath`). */
@RaftCoreInternalApi fun readTypedAgentMigrationLeaveOutPath(raw: String): AgentMigrationTypedLeaveOutResult {
    val problem = agentMigrationLeaveOutProblem(raw)
    if (problem != null) return AgentMigrationTypedLeaveOutResult.Invalid(problem)
    return AgentMigrationTypedLeaveOutResult.Valid(normalizeAgentMigrationLeaveOutPath(raw))
}

/**
 * Extracts leave-out candidates from a failed bundle-too-large migration on the agent's current
 * source computer (web `migrationLeaveOutCandidates`, slock #17048).
 *
 * Returns `null` when the leave-out picker should not be offered; returns an empty list (NOT null)
 * when the picker should still be shown with its custom-path field even though no measured
 * `topEntries` qualified.
 */
@RaftCoreInternalApi fun migrationLeaveOutCandidates(
    notice: AgentMigrationNotice?,
    sourceMachineId: String?,
): List<AgentMigrationLeaveOutCandidate>? {
    if (notice == null || sourceMachineId.isNullOrEmpty() || notice.isHosted) return null
    if (notice.endedComputerMigration() != AgentMigrationEnding.Failed) return null
    if (notice.sourceMachineId != sourceMachineId) return null
    val copyCode = migrationFailureCopyCode(notice.transportErrorCode, notice.failureReason)
    if (copyCode != "MIGRATION_OBJECT_STORE_BUNDLE_TOO_LARGE") return null
    return migrationLargestEntries(notice.transportErrorMessage)
        .filter { entry ->
            entry.path != UNNAMED_ACCOUNTING_PATH &&
                agentMigrationLeaveOutProblem(entry.path) == null
        }
        .map { entry ->
            AgentMigrationLeaveOutCandidate(
                path = normalizeAgentMigrationLeaveOutPath(entry.path),
                sizeBytes = entry.sizeBytes,
            )
        }
        .distinctBy { it.path }
}
