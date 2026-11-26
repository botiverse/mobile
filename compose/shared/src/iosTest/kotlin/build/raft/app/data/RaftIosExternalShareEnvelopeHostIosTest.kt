@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close as posixClose
import platform.posix.creat as posixCreat
import platform.posix.write as posixWrite
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RaftIosExternalShareEnvelopeHostIosTest {
    @Test
    fun zipEnvelopeRoundTripLoadsConsumesReadsViaPlatformFileResolverAndDeletes() = runTest {
        withTempInbox { inboxPath ->
            val token = "ios-share-zip-token_123456"
            val tokenDir = "$inboxPath/$token"
            val files = NSFileManager.defaultManager
            assertTrue(files.createDirectoryAtPath(tokenDir, withIntermediateDirectories = true, attributes = null, error = null))

            val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x01, 0x02)
            val zipPath = "$tokenDir/item-1-ChatHistory.zip"
            writeBytes(zipPath, zipBytes)
            val fileRef = NSURL.fileURLWithPath(zipPath).absoluteString ?: error("Missing file URL")
            val nowMillis = 1_700_000_000_000L
            writeText(
                "$tokenDir/envelope.json",
                """
                {
                  "token": "$token",
                  "createdAtEpochMillis": $nowMillis,
                  "sourcePlatform": "ios",
                  "text": "Forwarded chat archive",
                  "subject": "",
                  "items": [
                    {
                      "localRef": "$fileRef",
                      "displayName": "微信聊天记录.zip",
                      "mimeType": "application/zip",
                      "sizeBytes": ${zipBytes.size}
                    }
                  ]
                }
                """.trimIndent()
            )
            writeText("$inboxPath/pending.txt", token)

            val host = createIosExternalShareEnvelopeHostForRoot(inboxPath) { nowMillis + 1_000L }
            val loaded = assertIs<RaftExternalShareEnvelopeHostReadResult.Content>(host.load(token))
            assertEquals("ios", loaded.record.sourcePlatform)
            assertEquals("Forwarded chat archive", loaded.record.text)
            assertEquals(1, loaded.record.items.size)
            val item = loaded.record.items.single()
            assertEquals("微信聊天记录.zip", item.displayName)
            assertEquals("application/zip", item.mimeType)
            assertEquals(zipBytes.size.toLong(), item.sizeBytes)

            // Verify PlatformFileResolver on iOS can stat and read the handed-off fileRef directly.
            val resolver = createDefaultPlatformFileResolver() ?: error("Missing iOS file resolver")
            val stat = resolver.statFile(item.localRef)
            assertEquals(200, stat.code)
            assertEquals(zipBytes.size.toLong(), stat.sizeBytes)
            val read = resolver.readFile(item.localRef)
            assertEquals(200, read.code)
            assertContentEquals(zipBytes, read.bytes)

            val consumptionId = "external-share-consume:$token"
            assertEquals(RaftExternalShareEnvelopeHostMutationResult.Success, host.consume(token, consumptionId))
            // Idempotent retry with the same consumptionId succeeds.
            assertEquals(RaftExternalShareEnvelopeHostMutationResult.Success, host.consume(token, consumptionId))
            assertFalse(files.fileExistsAtPath("$inboxPath/pending.txt"))
            // Once consumed, load reports Missing so redelivered tokens do not reopen the flow.
            assertEquals(RaftExternalShareEnvelopeHostReadResult.Missing, host.load(token))

            host.delete(token)
            assertFalse(files.fileExistsAtPath(tokenDir))
        }
    }

    @Test
    fun rejectsEscapedPathSizeMismatchExpiredEnvelopeAndInvalidConsumptionIdentity() = runTest {
        withTempInbox { inboxPath ->
            val files = NSFileManager.defaultManager
            val nowMillis = 1_700_000_000_000L
            val host = createIosExternalShareEnvelopeHostForRoot(inboxPath) { nowMillis }

            // 1. Escaped file outside token directory is rejected.
            val escapedFile = "$inboxPath/outside.zip"
            writeBytes(escapedFile, byteArrayOf(1, 2, 3, 4))
            val escapedToken = "ios-share-escaped_123456"
            val escapedDir = "$inboxPath/$escapedToken"
            assertTrue(files.createDirectoryAtPath(escapedDir, withIntermediateDirectories = true, attributes = null, error = null))
            val escapedRef = NSURL.fileURLWithPath(escapedFile).absoluteString ?: error("Missing escaped URL")
            writeText(
                "$escapedDir/envelope.json",
                """
                {
                  "token": "$escapedToken",
                  "createdAtEpochMillis": $nowMillis,
                  "sourcePlatform": "ios",
                  "text": "",
                  "subject": "",
                  "items": [
                    {
                      "localRef": "$escapedRef",
                      "displayName": "outside.zip",
                      "mimeType": "application/zip",
                      "sizeBytes": 4
                    }
                  ]
                }
                """.trimIndent()
            )
            val escapedResult = assertIs<RaftExternalShareEnvelopeHostReadResult.Failure>(host.load(escapedToken))
            assertEquals("external_share_envelope_invalid", escapedResult.code)

            // 2. Size mismatch between envelope metadata and disk bytes is rejected.
            val mismatchToken = "ios-share-mismatch_123456"
            val mismatchDir = "$inboxPath/$mismatchToken"
            assertTrue(files.createDirectoryAtPath(mismatchDir, withIntermediateDirectories = true, attributes = null, error = null))
            val mismatchFile = "$mismatchDir/item-1-Archive.zip"
            writeBytes(mismatchFile, byteArrayOf(1, 2, 3, 4))
            val mismatchRef = NSURL.fileURLWithPath(mismatchFile).absoluteString ?: error("Missing mismatch URL")
            writeText(
                "$mismatchDir/envelope.json",
                """
                {
                  "token": "$mismatchToken",
                  "createdAtEpochMillis": $nowMillis,
                  "sourcePlatform": "ios",
                  "text": "",
                  "subject": "",
                  "items": [
                    {
                      "localRef": "$mismatchRef",
                      "displayName": "Archive.zip",
                      "mimeType": "application/zip",
                      "sizeBytes": 99
                    }
                  ]
                }
                """.trimIndent()
            )
            val mismatchResult = assertIs<RaftExternalShareEnvelopeHostReadResult.Failure>(host.load(mismatchToken))
            assertEquals("external_share_envelope_invalid", mismatchResult.code)

            // 3. Whitespace-only text envelope is rejected.
            val blankToken = "ios-share-blank-tok_123456"
            val blankDir = "$inboxPath/$blankToken"
            assertTrue(files.createDirectoryAtPath(blankDir, withIntermediateDirectories = true, attributes = null, error = null))
            writeText(
                "$blankDir/envelope.json",
                """
                {
                  "token": "$blankToken",
                  "createdAtEpochMillis": $nowMillis,
                  "sourcePlatform": "ios",
                  "text": "   \n\t  ",
                  "subject": "",
                  "items": []
                }
                """.trimIndent()
            )
            val blankResult = assertIs<RaftExternalShareEnvelopeHostReadResult.Failure>(host.load(blankToken))
            assertEquals("external_share_envelope_invalid", blankResult.code)

            // 4. Expired envelope (>24h) is rejected.
            val expiredToken = "ios-share-expired_123456"
            val expiredDir = "$inboxPath/$expiredToken"
            assertTrue(files.createDirectoryAtPath(expiredDir, withIntermediateDirectories = true, attributes = null, error = null))
            writeText(
                "$expiredDir/envelope.json",
                """
                {
                  "token": "$expiredToken",
                  "createdAtEpochMillis": ${nowMillis - (25L * 60L * 60L * 1000L)},
                  "sourcePlatform": "ios",
                  "text": "stale",
                  "subject": "",
                  "items": []
                }
                """.trimIndent()
            )
            val expiredResult = assertIs<RaftExternalShareEnvelopeHostReadResult.Failure>(host.load(expiredToken))
            assertEquals("external_share_envelope_invalid", expiredResult.code)

            // 3. Mismatched consumption identity is rejected.
            val badConsume = assertIs<RaftExternalShareEnvelopeHostMutationResult.Failure>(
                host.consume(expiredToken, "wrong-consumption-id")
            )
            assertEquals("external_share_consumption_identity_invalid", badConsume.code)
        }
    }

    @Test
    fun preselectedTargetHintRoundTripsCanonicalHintAndDropsMalformedHint() = runTest {
        withTempInbox { inboxPath ->
            val files = NSFileManager.defaultManager
            val nowMillis = 1_700_000_000_000L
            val host = createIosExternalShareEnvelopeHostForRoot(inboxPath) { nowMillis }

            val validToken = "ios-share-hint-ok_123456"
            val validDir = "$inboxPath/$validToken"
            assertTrue(files.createDirectoryAtPath(validDir, withIntermediateDirectories = true, attributes = null, error = null))
            writeText(
                "$validDir/envelope.json",
                """
                {
                  "token": "$validToken",
                  "createdAtEpochMillis": $nowMillis,
                  "sourcePlatform": "ios",
                  "text": "hello direct share",
                  "subject": "",
                  "items": [],
                  "preselectedTargetHint": "  raft_share:v1:srv-1:DM:dm-agent-1  "
                }
                """.trimIndent()
            )
            val validLoaded = assertIs<RaftExternalShareEnvelopeHostReadResult.Content>(host.load(validToken))
            assertEquals("raft_share:v1:srv-1:dm:dm-agent-1", validLoaded.record.preselectedTargetHint)

            val badHintToken = "ios-share-hint-bad_123456"
            val badHintDir = "$inboxPath/$badHintToken"
            assertTrue(files.createDirectoryAtPath(badHintDir, withIntermediateDirectories = true, attributes = null, error = null))
            writeText(
                "$badHintDir/envelope.json",
                """
                {
                  "token": "$badHintToken",
                  "createdAtEpochMillis": $nowMillis,
                  "sourcePlatform": "ios",
                  "text": "fallback to picker",
                  "subject": "",
                  "items": [],
                  "preselectedTargetHint": "raft_share:v1:srv-1:channel:../escape"
                }
                """.trimIndent()
            )
            val badHintLoaded = assertIs<RaftExternalShareEnvelopeHostReadResult.Content>(host.load(badHintToken))
            assertEquals(null, badHintLoaded.record.preselectedTargetHint)
            assertEquals("fallback to picker", badHintLoaded.record.text)
        }
    }

    @Test
    fun iosSharePixelAvatarRgbaRendersBundledAndRandomAndRejectsNonPixelWire() {
        val cat = raftIosRenderPixelAvatarRgba("pixel:cat")
        assertTrue(cat != null)
        assertTrue(cat.size >= 48)
        assertEquals((cat.size * cat.size * 4).toULong(), cat.rgbaData.length)

        val random = raftIosRenderPixelAvatarRgba("random:agent-seed")
        assertTrue(random != null)
        assertEquals((random.size * random.size * 4).toULong(), random.rgbaData.length)

        assertEquals(null, raftIosRenderPixelAvatarRgba("https://example.com/avatar.png"))
        assertEquals(null, raftIosRenderPixelAvatarRgba(""))

        // Zero-network-I/O cached server avatar helper returns null cleanly on blank or uncached URLs.
        assertEquals(null, raftIosReadCachedAvatarImageData(null))
        assertEquals(null, raftIosReadCachedAvatarImageData("   "))
        assertEquals(null, raftIosReadCachedAvatarImageData("https://example.com/uncached-server-icon.png"))
    }

    private inline fun withTempInbox(block: (String) -> Unit) {
        val path = "${NSTemporaryDirectory().trimEnd('/')}/raft-ext-share-test-${NSUUID().UUIDString}"
        assertTrue(
            NSFileManager.defaultManager.createDirectoryAtPath(
                path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
        )
        try {
            block(path)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    private fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())

    private fun writeBytes(path: String, bytes: ByteArray) {
        val descriptor = posixCreat(path, (S_IRUSR or S_IWUSR).convert())
        assertTrue(descriptor >= 0)
        try {
            var offset = 0
            while (offset < bytes.size) {
                val count = bytes.usePinned { pinned ->
                    posixWrite(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                }
                assertTrue(count > 0)
                offset += count
            }
        } finally {
            posixClose(descriptor)
        }
    }
}
