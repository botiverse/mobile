package build.raft.app.data

import build.raft.app.applyServerAttachmentUploadLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression for task #380: the legacy multipart transport is capped independently of the plan
 * limit, so a `maxBytes`-only pre-check admitted 90-200MiB files that could never upload.
 */
class AttachmentUploadCapabilitiesMultipartCapTest {

    private val mib = 1024L * 1024L

    @Test
    fun unadvertisedLegacyCapFallsBackToTheDocumentedDefault() {
        val capabilities = AttachmentUploadCapabilities(maxBytes = 200L * mib)

        assertEquals(90L * mib, capabilities.effectiveMultipartMaxBytes)
    }

    @Test
    fun planLimitStillWinsWhenItIsBelowTheLegacyDefault() {
        val capabilities = AttachmentUploadCapabilities(maxBytes = 50L * mib)

        assertEquals(50L * mib, capabilities.effectiveMultipartMaxBytes)
    }

    @Test
    fun anAdvertisedLegacyCapIsUsedInsteadOfTheDefault() {
        val capabilities =
            AttachmentUploadCapabilities(maxBytes = 200L * mib, legacyMultipartMaxBytes = 150L * mib)

        assertEquals(150L * mib, capabilities.effectiveMultipartMaxBytes)
    }

    @Test
    fun anAdvertisedLegacyCapCannotRaiseThePlanLimit() {
        val capabilities =
            AttachmentUploadCapabilities(maxBytes = 50L * mib, legacyMultipartMaxBytes = 150L * mib)

        assertEquals(50L * mib, capabilities.effectiveMultipartMaxBytes)
    }

    @Test
    fun theReportedCaseIsRejectedBeforeAnyUploadStarts() {
        // artin's report: 91.8MiB over multipart, plan limit advertising 200MiB.
        val capabilities = AttachmentUploadCapabilities(maxBytes = 200L * mib)
        val file = PlatformFile(id = "apk", fileName = "app.apk", sizeBytes = 96_275_401L)

        val decision =
            applyServerAttachmentUploadLimit(
                files = listOf(file),
                maxBytes = capabilities.effectiveMultipartMaxBytes,
            )

        assertEquals(emptyList(), decision.accepted)
        assertEquals(96_275_401L, decision.largestRejectedBytes)
    }

    @Test
    fun aFileJustUnderTheLegacyCapIsStillAdmitted() {
        val capabilities = AttachmentUploadCapabilities(maxBytes = 200L * mib)
        val file = PlatformFile(id = "ok", fileName = "ok.bin", sizeBytes = 90L * mib)

        val decision =
            applyServerAttachmentUploadLimit(
                files = listOf(file),
                maxBytes = capabilities.effectiveMultipartMaxBytes,
            )

        assertEquals(listOf("ok"), decision.accepted.map { it.id })
    }

    @Test
    fun anAdvertisedLegacyCapRaisesTheBoundaryOnlyUpToThePlanLimit() {
        val capabilities =
            AttachmentUploadCapabilities(maxBytes = 200L * mib, legacyMultipartMaxBytes = 150L * mib)
        val within = PlatformFile(id = "within", fileName = "within.bin", sizeBytes = 150L * mib)
        val over = PlatformFile(id = "over", fileName = "over.bin", sizeBytes = 150L * mib + 1)

        val decision =
            applyServerAttachmentUploadLimit(
                files = listOf(within, over),
                maxBytes = capabilities.effectiveMultipartMaxBytes,
            )

        assertEquals(listOf("within"), decision.accepted.map { it.id })
        assertEquals(listOf("over"), decision.rejected.map { it.id })
    }

    @Test
    fun anAdvertisedCapAboveTheContractLimitFailsTheWholeDocumentClosed() {
        val body =
            """
            {"maxBytes": 209715200, "legacyMultipartMaxBytes": 209715201}
            """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            parseSharedAttachmentUploadCapabilities(body)
        }
    }

    @Test
    fun anAbsentLegacyCapKeepsParsingBackwardCompatible() {
        val body = """{"maxBytes": 209715200}""".trimIndent()

        val capabilities = parseSharedAttachmentUploadCapabilities(body)

        assertEquals(null, capabilities.legacyMultipartMaxBytes)
        assertEquals(90L * mib, capabilities.effectiveMultipartMaxBytes)
    }

    @Test
    fun anAdvertisedLegacyCapIsParsedThrough() {
        val body = """{"maxBytes": 209715200, "legacyMultipartMaxBytes": 157286400}""".trimIndent()

        val capabilities = parseSharedAttachmentUploadCapabilities(body)

        assertEquals(150L * mib, capabilities.legacyMultipartMaxBytes)
        assertEquals(150L * mib, capabilities.effectiveMultipartMaxBytes)
    }
}
