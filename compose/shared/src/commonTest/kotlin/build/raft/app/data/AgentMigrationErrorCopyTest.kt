package build.raft.app.data

import kotlinx.serialization.json.Json
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
    fun unsafeCodeNeverBecomesTechnicalCode() {
        val result = migrationErrorPresentation(MigrationErrorInput(code = "[{\"code\": 1}]", context = MigrationErrorContext.Failed))
        assertNull(result.technicalCode)
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorFailedGeneric)), result.messageParts)
    }

    /** Web #15464: MEMORY.md, notes/ or the workspace unreadable on the source has its own sentence. */
    @Test
    fun keyPathUnreadableHasItsOwnSentence() {
        val result = migrationErrorPresentation(
            MigrationErrorInput(
                code = "MIGRATION_WORKSPACE_KEY_PATH_UNREADABLE",
                rawMessage = "MIGRATION_WORKSPACE_KEY_PATH_UNREADABLE:path=notes%2F",
                context = MigrationErrorContext.Failed
            )
        )
        assertEquals(
            listOf(MigrationText(MigrationCopy.ErrorKeyPathUnreadable)),
            result.messageParts
        )
        assertEquals("MIGRATION_WORKSPACE_KEY_PATH_UNREADABLE", result.technicalCode)
    }

    @Test
    fun sourceComputerOfflineUsesNamedOrGenericCopy() {
        val named =
            migrationErrorPresentation(
                MigrationErrorInput(
                    code = "SOURCE_COMPUTER_OFFLINE",
                    context = MigrationErrorContext.Start,
                    sourceComputerName = "studio-mac",
                )
            )
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorSourceOfflineNamed, listOf("studio-mac"))), named.messageParts)
        assertEquals("SOURCE_COMPUTER_OFFLINE", named.technicalCode)

        val unnamed =
            migrationErrorPresentation(
                MigrationErrorInput(
                    code = "SOURCE_COMPUTER_OFFLINE",
                    context = MigrationErrorContext.Start,
                    sourceComputerName = "  ",
                )
            )
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorSourceOffline)), unnamed.messageParts)
        assertEquals("SOURCE_COMPUTER_OFFLINE", unnamed.technicalCode)
    }

    @Test
    fun leaveOutRefusalCodesMapToDedicatedCopy() {
        val invalid =
            migrationErrorPresentation(
                MigrationErrorInput(
                    code = "MIGRATION_LEAVE_OUT_INVALID",
                    context = MigrationErrorContext.Start,
                )
            )
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorLeaveOutInvalid)), invalid.messageParts)
        assertEquals("MIGRATION_LEAVE_OUT_INVALID", invalid.technicalCode)

        val unsupported =
            migrationErrorPresentation(
                MigrationErrorInput(
                    code = "MIGRATION_LEAVE_OUT_UNSUPPORTED",
                    context = MigrationErrorContext.Start,
                )
            )
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorLeaveOutUnsupported)), unsupported.messageParts)
        assertEquals("MIGRATION_LEAVE_OUT_UNSUPPORTED", unsupported.technicalCode)
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
    fun targetRuntimeNotReadyParsesAndFormatsEveryPreflightKind() {
        val expectedCopy =
            mapOf(
                MigrationTargetRuntimeNotReadyKind.NeedsLogin to MigrationCopy.ErrorTargetRuntimeNeedsLogin,
                MigrationTargetRuntimeNotReadyKind.CredentialInvalid to MigrationCopy.ErrorTargetRuntimeCredentialInvalid,
                MigrationTargetRuntimeNotReadyKind.UsageLimit to MigrationCopy.ErrorTargetRuntimeUsageLimit,
                MigrationTargetRuntimeNotReadyKind.NotReady to MigrationCopy.ErrorTargetRuntimeNotReady,
            )
        for ((kind, copy) in expectedCopy) {
            val details =
                Json.parseToJsonElement(
                    """{"side":"target","runtime":"claude","computerName":"Target","preflight":{"kind":"${kind.wire}"}}"""
                )
            val parsed = parseMigrationTargetRuntimeNotReady(details)!!
            assertEquals(MigrationTargetRuntimeNotReady("claude", "Target", kind), parsed)
            assertEquals(parsed, parseMigrationTargetRuntimeNotReadyFromFields(migrationTargetRuntimeNotReadyFields(parsed)))
            val presentation =
                migrationErrorPresentation(
                    MigrationErrorInput(
                        code = "TARGET_RUNTIME_NOT_READY",
                        context = MigrationErrorContext.Start,
                        targetRuntimeNotReady = parsed,
                        runtimeDisplayName = { if (it == "claude") "Claude Code" else it },
                    )
                )
            assertEquals(listOf(MigrationText(copy, listOf("Claude Code", "Target"))), presentation.messageParts)
            assertEquals("TARGET_RUNTIME_NOT_READY", presentation.technicalCode)
        }
        // Rejects unsafe runtime id or unknown preflight kind.
        assertNull(
            parseMigrationTargetRuntimeNotReady(
                Json.parseToJsonElement("""{"runtime":"../bad","computerName":"Target","preflight":{"kind":"needs_login"}}""")
            )
        )
        assertNull(
            parseMigrationTargetRuntimeNotReady(
                Json.parseToJsonElement("""{"runtime":"claude","computerName":"Target","preflight":{"kind":"unverified"}}""")
            )
        )
        // Missing details falls back cleanly to not_ready with targetComputerName.
        val fallback =
            migrationErrorPresentation(
                MigrationErrorInput(
                    code = "TARGET_RUNTIME_NOT_READY",
                    context = MigrationErrorContext.Start,
                    targetComputerName = "Target",
                )
            )
        assertEquals(listOf(MigrationText(MigrationCopy.ErrorTargetRuntimeNotReady, listOf("", "Target"))), fallback.messageParts)
    }
}
