package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentUploadStreamingSourceContractTest {
    @Test
    fun platformFileRefsStayStreamingAcrossEveryProductionTarget() {
        val root = findRepoRoot()
        val sharedClient =
            source(root, "compose/shared/src/commonMain/kotlin/build/raft/app/data/SharedSlockNetworkClient.kt")
        val multipartExecutor =
            source(root, "compose/shared/src/commonMain/kotlin/build/raft/app/network/NetworkKmmSlockHttpExecutor.kt")
        val resolverContract =
            source(root, "compose/shared/src/commonMain/kotlin/build/raft/app/data/PlatformFileResolver.kt")
        val androidResolver =
            source(root, "compose/androidApp/src/main/java/build/raft/app/core/data/AndroidPlatformFileResolver.kt")
        val iosResolver =
            source(root, "compose/shared/src/iosMain/kotlin/build/raft/app/data/PlatformFileResolver.ios.kt")
        val ohosResolver =
            source(root, "compose/shared/src/ohosArm64Main/kotlin/build/raft/app/data/PlatformFileResolver.ohos.kt")
        val ohosReader =
            source(root, "compose/ohosApp/entry/src/main/ets/entryability/SlockOhosPlatformFileReader.ets")

        val attachmentUpload = sourceWindow(sharedClient, "override suspend fun uploadAttachments", "override suspend fun uploadAccountAvatar")
        val streamingAdmission = sourceWindow(sharedClient, "private suspend fun List<MultipartFile>.preparePlatformFileStreams", "private suspend fun List<MultipartFile>.resolvePlatformFileRefs")
        assertTrue(attachmentUpload.contains("preparePlatformFileStreams"))
        assertFalse(attachmentUpload.contains("resolvePlatformFileRefs"))
        assertFalse(streamingAdmission.contains("resolver.readFile("))
        assertFalse(streamingAdmission.contains("bytes = response.bytes"))
        assertTrue(multipartExecutor.contains("NetworkBody.FileRef("))
        assertTrue(multipartExecutor.contains("openStreamBlock"))

        assertTrue(resolverContract.contains("suspend fun statFile("))
        assertTrue(resolverContract.contains("suspend fun openFileStream("))
        assertTrue(resolverContract.contains("boundedPlatformFileNetworkStream("))
        assertTrue(resolverContract.contains("NetworkByteStream("))

        assertTrue(androidResolver.contains("toSlockPlatformFileChunkSource("))
        assertFalse(androidResolver.contains("NetworkByteStream"))

        assertTrue(iosResolver.contains("posixOpen"))
        assertTrue(iosResolver.contains("posixRead"))
        assertTrue(iosResolver.contains("SlockPlatformFileChunkSource"))
        assertFalse(iosResolver.contains("NetworkByteStream"))

        assertTrue(ohosResolver.contains("readFileChunk("))
        assertTrue(ohosResolver.contains("SlockPlatformFileChunkSource"))
        assertFalse(ohosResolver.contains("NetworkByteStream"))
        assertTrue(ohosReader.contains("MAX_STREAM_CHUNK_BYTES = 64 * 1024"))
        assertTrue(ohosReader.contains("readFileChunk("))
        val ohosChunkReader = sourceWindow(ohosReader, "readFileChunk(streamId", "closeFileStream(streamId")
        assertFalse(ohosChunkReader.contains("stat.size"))
        assertFalse(ohosChunkReader.contains("openSync(fileRef"))
    }

    private fun source(root: File, path: String): String =
        File(root, path).readText().replace("\r\n", "\n")

    private fun sourceWindow(source: String, startNeedle: String, endNeedle: String): String {
        val start = source.indexOf(startNeedle)
        val end = source.indexOf(endNeedle, start + startNeedle.length)
        assertTrue("Missing source window start: $startNeedle", start >= 0)
        assertTrue("Missing source window end: $endNeedle", end > start)
        return source.substring(start, end)
    }

    private fun findRepoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).canonicalFile
        repeat(8) {
            if (File(current, "compose/settings.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        assertEquals("Unable to locate repository root from $userDir", true, false)
        error("unreachable")
    }
}
