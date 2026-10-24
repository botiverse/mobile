package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AgentMigrationErrorCopyTest {
    private val zodJson =
        """[{"origin":"number","code":"too_big","maximum":10737418240,"inclusive":true,"path":["includedBytes"],"message":"Too big: expected number to be <=10737418240"}]"""

    private val computers = MigrationFailureComputers(sourceName = "armbian-gx", targetName = "studio-mac")

    private fun failed(
        failureReason: String? = null,
        transportErrorCode: String? = null,
        transportErrorMessage: String? = null,
        flippedAt: String? = null,
        targetKind: String = "machine",
        state: String = "failed",
        abortReason: String? = null,
        prepDeadlineAt: String? = null
    ) = AgentMigrationNotice(
        agentId = "agent-1",
        migrationRef = "mig_1",
        state = state,
        targetKind = targetKind,
        failureReason = failureReason,
        transportErrorCode = transportErrorCode,
        transportErrorMessage = transportErrorMessage,
        flippedAt = flippedAt,
        abortReason = abortReason,
        prepDeadlineAt = prepDeadlineAt
    )

    @Test
    fun unknownCodeWithRawJsonShowsGenericCopyAndNoJson() {
        val result = migrationFailurePresentation(failed(failureReason = "SOME_NEW_CODE", transportErrorMessage = zodJson), computers)
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorFailedGeneric)), result.messageParts)
        assertEquals(MigrationText(MigrationCopy.StillOnSource, listOf("armbian-gx")), result.placement)
        assertEquals("SOME_NEW_CODE", result.technicalCode)
        assertFalse(result.toString().contains("too_big"))
        assertFalse(result.toString().contains("10737418240"))
    }

    @Test
    fun noCodeStillGeneric() {
        val result = migrationFailurePresentation(failed(transportErrorMessage = zodJson), computers)
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorFailedGeneric)), result.messageParts)
        assertNull(result.technicalCode)
    }

    @Test
    fun unsafeCodeNeverBecomesTechnicalCode() {
        val result = migrationErrorPresentation(MigrationErrorInput(code = "[{\"code\": 1}]", context = MigrationErrorContext.Failed))
        assertNull(result.technicalCode)
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorFailedGeneric)), result.messageParts)
    }

    @Test
    fun startAndStatusFallbacks() {
        assertEquals(
            MigrationCopy.ErrorStartFailed,
            migrationErrorPresentation(MigrationErrorInput(code = "x", rawMessage = zodJson, context = MigrationErrorContext.Start)).messageParts.single().copy
        )
        assertEquals(
            MigrationCopy.ErrorStatusFailed,
            migrationErrorPresentation(MigrationErrorInput(rawMessage = zodJson, context = MigrationErrorContext.Status)).messageParts.single().copy
        )
    }

    @Test
    fun bundleTooLargeFormatsLimitAndLargestEntries() {
        val raw = "maxBytes=10737418240:topEntries=models%2F,6442450944;cache%2F,1610612736;a,5"
        val result = migrationErrorPresentation(
            MigrationErrorInput(code = "MIGRATION_OBJECT_STORE_BUNDLE_TOO_LARGE", rawMessage = raw, context = MigrationErrorContext.Failed)
        )
        assertEquals(
            listOf(MigrationText(MigrationCopy.ErrorBundleTooLargeWithLimitWithItems, listOf("10 GiB", "models/ (6 GiB), cache/ (1.5 GiB), a (0.0 GiB)"))),
            result.messageParts
        )
        assertEquals("MIGRATION_OBJECT_STORE_BUNDLE_TOO_LARGE", result.technicalCode)
    }

    @Test
    fun bundleTooLargeWithoutWireDataDropsTheClauses() {
        val result = migrationErrorPresentation(
            MigrationErrorInput(code = "MIGRATION_OBJECT_STORE_BUNDLE_TOO_LARGE", rawMessage = zodJson, context = MigrationErrorContext.Failed)
        )
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorBundleTooLarge)), result.messageParts)
    }

    @Test
    fun wireIntegerRejectsNonFieldsAndNegativeLooks() {
        assertEquals(5L, migrationWireInteger("a=1:maxBytes=5", "maxBytes"))
        assertNull(migrationWireInteger("xmaxBytes=5", "maxBytes"))
        assertNull(migrationWireInteger("maxBytes=5x", "maxBytes"))
        assertNull(migrationWireInteger(null, "maxBytes"))
    }

    @Test
    fun entryCountLimitUsesDetailsOnlyWhenConsistent() {
        val ok = migrationErrorPresentation(
            MigrationErrorInput(
                code = "MIGRATION_OBJECT_STORE_ENTRY_COUNT_LIMIT_EXCEEDED",
                rawMessage = "entryCount=250000:maxEntries=200000:topPathCounts=node_modules%2F,180000;src%2F,500",
                context = MigrationErrorContext.Failed
            )
        )
        assertEquals(
            listOf(
                MigrationText(
                    MigrationCopy.ErrorEntryCountLimitExceededWithDetailsWithPaths,
                    listOf("250,000", "200,000", "node_modules/ (180,000), src/ (500)")
                )
            ),
            ok.messageParts
        )
        val bad = migrationErrorPresentation(
            MigrationErrorInput(
                code = "MIGRATION_OBJECT_STORE_ENTRY_COUNT_LIMIT_EXCEEDED",
                rawMessage = "entryCount=10:maxEntries=200000:topPathCounts=a%2F,1",
                context = MigrationErrorContext.Failed
            )
        )
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorEntryCountLimitExceeded)), bad.messageParts)
    }

    @Test
    fun manifestTooLargeSkipsUnsafePaths() {
        val result = migrationErrorPresentation(
            MigrationErrorInput(
                code = "MIGRATION_OBJECT_STORE_MANIFEST_TOO_LARGE",
                rawMessage = "entryCount=1234567:topPaths=src%2F,1000;.env%2F,5",
                context = MigrationErrorContext.Failed
            )
        )
        assertEquals(
            listOf(MigrationText(MigrationCopy.ErrorManifestTooLargeWithCountWithPaths, listOf("1,234,567", "src/ (1,000)"))),
            result.messageParts
        )
    }

    @Test
    fun insufficientDiskFormatsSizes() {
        val result = migrationErrorPresentation(
            MigrationErrorInput(
                code = "MIGRATION_OBJECT_STORE_INSUFFICIENT_DISK",
                rawMessage = "requiredBytes=5368709120:availableBytes=1073741824",
                context = MigrationErrorContext.Failed
            )
        )
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorInsufficientDiskWithSizes, listOf("5 GiB", "1 GiB"))), result.messageParts)
    }

    @Test
    fun transportLostNamesTheOfflineComputer() {
        val result = migrationFailurePresentation(
            failed(failureReason = "MIGRATION_TRANSPORT_LOST").let { it.copy(transportLostAt = "2026-10-22T09:03:00Z") },
            MigrationFailureComputers("armbian-gx", "studio-mac", sourceOnline = true, targetOnline = false),
            formatTimestamp = { "Oct 22, 9:03 AM" }
        )
        assertEquals(
            listOf(
                MigrationText(MigrationCopy.ErrorTransportLostAt, listOf("Oct 22, 9:03 AM")),
                MigrationText(MigrationCopy.ErrorTransportLostRetryOneOffline, listOf("studio-mac"))
            ),
            result.messageParts
        )
    }

    @Test
    fun transportLostWithoutComputerStatusAsksToCheck() {
        val result = migrationFailurePresentation(failed(failureReason = "MIGRATION_TRANSPORT_LOST"), computers)
        assertEquals(MigrationCopy.ErrorTransportLostEvent, result.messageParts[0].copy)
        assertEquals(MigrationCopy.ErrorTransportLostRetryUnknown, result.messageParts[1].copy)
    }

    @Test
    fun hostedTransportLostNamesOnlyTheSource() {
        val result = migrationFailurePresentation(failed(failureReason = "MIGRATION_TRANSPORT_LOST", targetKind = "hosted"), computers)
        assertEquals(listOf(MigrationText(MigrationCopy.HostedTransportLost, listOf("armbian-gx"))), result.messageParts)
    }

    @Test
    fun detailedDaemonCauseWithoutCopyFallsBackToFailureReason() {
        assertEquals("MIGRATION_LEASE_EXPIRED", migrationFailureCopyCode("MIGRATION_TARGET_IMPORT_ARRIVED_FAILED:503:x", "MIGRATION_LEASE_EXPIRED"))
        assertEquals("MIGRATION_TRANSPORT_LOST", migrationFailureCopyCode("MIGRATION_TRANSPORT_LOST", "X"))
        assertEquals("ONLY_CAUSE", migrationFailureCopyCode("ONLY_CAUSE", null))
    }

    @Test
    fun workspaceConflictPointsAtTheConventionalPath() {
        val result = migrationFailurePresentation(failed(failureReason = "MIGRATION_WORKSPACE_ALREADY_EXISTS"), computers)
        assertEquals(
            listOf(MigrationText(MigrationCopy.ErrorWorkspaceAlreadyExistsAt, listOf("~/.slock/agents/agent-1"))),
            result.messageParts
        )
    }

    @Test
    fun abortedUsesDeadlineCopyAndTheAbortReasonAsTechnicalCode() {
        val result = migrationFailurePresentation(
            failed(state = "aborted", abortReason = "prep-deadline", prepDeadlineAt = "2026-10-22T09:30:00Z"),
            computers,
            formatTimestamp = { "9:30 AM" }
        )
        assertEquals(listOf(MigrationText(MigrationCopy.AbortedDetailPrepDeadlineAt, listOf("armbian-gx", "9:30 AM"))), result.messageParts)
        assertEquals("prep-deadline", result.technicalCode)
        val unknown = migrationFailurePresentation(failed(state = "aborted", abortReason = "weird"), computers)
        assertEquals(listOf(MigrationText(MigrationCopy.AbortedDetailFallback)), unknown.messageParts)
    }

    @Test
    fun failureAfterTheFlipHasNoStillOnSourcePlacement() {
        val result = migrationFailurePresentation(failed(failureReason = "X_FAILED", flippedAt = "2026-10-22T09:04:00Z"), computers)
        assertNull(result.placement)
        assertEquals(listOf(MigrationText(MigrationCopy.FailedAfterMove, listOf("armbian-gx", "studio-mac"))), result.messageParts)
        assertEquals("X_FAILED", result.technicalCode)
    }

    @Test
    fun hostedAgentNotCreatedKeepsTheAgentOnSource() {
        val result = migrationFailurePresentation(failed(failureReason = "HOSTED_AGENT_NOT_CREATED", targetKind = "hosted"), computers)
        assertEquals(MigrationText(MigrationCopy.StillOnSource, listOf("armbian-gx")), result.placement)
        assertEquals(listOf(MigrationText(MigrationCopy.HostedAgentNotCreated, listOf("armbian-gx"))), result.messageParts)
    }

    @Test
    fun percentDecodingMatchesDecodeUriComponent() {
        assertEquals("a b/é", migrationDecodeUriComponent("a%20b%2F%C3%A9"))
        assertNull(migrationDecodeUriComponent("%zz"))
        assertNull(migrationDecodeUriComponent("%C3"))
    }
}
