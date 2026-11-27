package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentMigrationLeaveOutTest {
    private fun failedBundleTooLarge(
        sourceMachineId: String? = "m-source",
        targetKind: String = "computer",
        state: String = "failed",
        transportErrorCode: String? = "MIGRATION_OBJECT_STORE_BUNDLE_TOO_LARGE",
        failureReason: String? = null,
        transportErrorMessage: String? = "maxBytes=10737418240:topEntries=work%2F,6442450944;cache%2F,1610612736;other%2F,512",
    ) = AgentMigrationNotice(
        agentId = "agent-1",
        migrationRef = "mig_1",
        state = state,
        sourceMachineId = sourceMachineId,
        targetKind = targetKind,
        failureReason = failureReason,
        transportErrorCode = transportErrorCode,
        transportErrorMessage = transportErrorMessage,
    )

    @Test
    fun normalizesWhitespaceRepeatedSlashesAndLeadingDotSlash() {
        assertEquals("work/build", normalizeAgentMigrationLeaveOutPath("  ./work//build  "))
        assertEquals("work/build/", normalizeAgentMigrationLeaveOutPath("././work///build/"))
        assertEquals("**/node_modules", normalizeAgentMigrationLeaveOutPath(" **/node_modules "))
    }

    @Test
    fun acceptsValidWorkspaceRelativePathsAndRecursiveFolderNames() {
        val valid =
            listOf(
                "work/",
                "work/build",
                "./work/build/",
                "work/MEMORY.md",
                "sub/notes/",
                "**/node_modules",
                "**/drizzle/",
                "datasets/model.bin",
            )
        valid.forEach { path ->
            assertNull(agentMigrationLeaveOutProblem(path), "Expected valid path: $path")
            assertEquals(
                AgentMigrationTypedLeaveOutResult.Valid(normalizeAgentMigrationLeaveOutPath(path)),
                readTypedAgentMigrationLeaveOutPath(path),
                path,
            )
        }
    }

    @Test
    fun rejectsAlwaysMovedProtectedPathsInEverySpelling() {
        val alwaysMoved =
            listOf(
                "MEMORY.md",
                "memory.md",
                "./MEMORY.md",
                "notes",
                "notes/",
                "Notes/2026.md",
                ".raft",
                ".raft/",
                ".raft/activity",
                ".raftbackupignore",
                ".raftbackupignore.user",
                ".raftmigrateignore",
                "**/MEMORY.md",
                "**/notes",
                "**/notes/",
                "**/.raft",
                "**/.raftbackupignore.user",
            )
        alwaysMoved.forEach { path ->
            assertEquals(AgentMigrationLeaveOutProblem.AlwaysMoved, agentMigrationLeaveOutProblem(path), path)
            assertEquals(
                AgentMigrationTypedLeaveOutResult.Invalid(AgentMigrationLeaveOutProblem.AlwaysMoved),
                readTypedAgentMigrationLeaveOutPath(path),
                path,
            )
        }
    }

    @Test
    fun rejectsInvalidPathsAndOverByteLimit() {
        val overLimit = "a".repeat(MAX_AGENT_MIGRATION_USER_IGNORE_PATH_BYTES + 1)
        val multiByteOverLimit = "文".repeat(342) // 342 * 3 = 1026 UTF-8 bytes
        val invalid =
            listOf(
                "",
                "   ",
                "./",
                "/etc/passwd",
                "C:/Users/me",
                "#comment",
                "!negate",
                "../escape",
                "work/../secret",
                "work/./sub",
                "wt-*/",
                "work/*.log",
                "work/?tmp",
                "work/[0-9]",
                "work/{a,b}",
                "work\\sub",
                "**/",
                "**/.",
                "**/..",
                "**/a/b",
                "**/foo*",
                "bad\nline",
                "bad\u0000null",
                "bad\u2028sep",
                overLimit,
                multiByteOverLimit,
            )
        invalid.forEach { path ->
            assertEquals(AgentMigrationLeaveOutProblem.Invalid, agentMigrationLeaveOutProblem(path), path)
            assertEquals(
                AgentMigrationTypedLeaveOutResult.Invalid(AgentMigrationLeaveOutProblem.Invalid),
                readTypedAgentMigrationLeaveOutPath(path),
                path,
            )
        }
    }

    @Test
    fun candidatesFilterOtherAndAlwaysMovedAndReturnEmptyListWhenNoneQualify() {
        val filtered =
            migrationLeaveOutCandidates(
                failedBundleTooLarge(
                    transportErrorMessage =
                        "maxBytes=10737418240:topEntries=work%2F,6442450944;notes%2F,1024;other%2F,512",
                ),
                sourceMachineId = "m-source",
            )
        assertEquals(
            listOf(AgentMigrationLeaveOutCandidate("work/", 6_442_450_944L)),
            filtered,
        )

        val emptyQualified =
            migrationLeaveOutCandidates(
                failedBundleTooLarge(
                    transportErrorMessage = "maxBytes=10737418240:topEntries=MEMORY.md,1024;other%2F,512",
                ),
                sourceMachineId = "m-source",
            )
        assertNotNull(emptyQualified)
        assertEquals(emptyList(), emptyQualified)
    }

    @Test
    fun candidatesReturnNullWhenFailureDoesNotQualify() {
        assertNull(migrationLeaveOutCandidates(null, "m-source"))
        assertNull(migrationLeaveOutCandidates(failedBundleTooLarge(), null))
        assertNull(migrationLeaveOutCandidates(failedBundleTooLarge(), ""))
        assertNull(migrationLeaveOutCandidates(failedBundleTooLarge(targetKind = "hosted"), "m-source"))
        assertNull(migrationLeaveOutCandidates(failedBundleTooLarge(state = "completed"), "m-source"))
        assertNull(migrationLeaveOutCandidates(failedBundleTooLarge(sourceMachineId = "m-other"), "m-source"))
        assertNull(
            migrationLeaveOutCandidates(
                failedBundleTooLarge(transportErrorCode = "MIGRATION_TRANSPORT_LOST"),
                "m-source",
            ),
        )
    }
}
