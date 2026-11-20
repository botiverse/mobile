package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #proj-mobile:#202: the saved file takes the name the server gives (RFC 6266 / 5987). */
class RaftContentDispositionTest {
    @Test
    fun extendedUtf8NameWinsAndKeepsChinese() {
        // As slock blobStore.ts attachmentDisposition writes it: ASCII stand-in, then filename*.
        val header = "attachment; filename=\"____ v3.zip\"; filename*=UTF-8''%E5%91%A8%E6%8A%A5%20v3.zip"
        assertEquals("周报 v3.zip", raftContentDispositionFileName(header))
        assertEquals("周报 v3.zip", raftDownloadFileName(header, "https://app.raft.build/api/artifacts/pt_1/download.zip"))
    }

    @Test
    fun charsetAndParameterNamesAreCaseInsensitive() {
        assertEquals("报告.md", raftContentDispositionFileName("Attachment; FILENAME*=utf-8'zh'%E6%8A%A5%E5%91%8A.md"))
    }

    @Test
    fun plainFilenameQuotedOrToken() {
        assertEquals("index.html", raftContentDispositionFileName("attachment; filename=\"index.html\""))
        assertEquals("data.csv", raftContentDispositionFileName("attachment; filename=data.csv"))
        assertEquals("a \"b\".txt", raftContentDispositionFileName("attachment; filename=\"a \\\"b\\\".txt\""))
    }

    @Test
    fun malformedExtendedValueFallsBackToFilename() {
        assertEquals("plain.txt", raftContentDispositionFileName("attachment; filename*=UTF-8''%E5%91; filename=\"plain.txt\""))
        assertEquals("plain.txt", raftContentDispositionFileName("attachment; filename*=koi8-r''x; filename=plain.txt"))
    }

    @Test
    fun aNameIsNeverAPath() {
        assertEquals("passwd", raftContentDispositionFileName("attachment; filename=\"../../etc/passwd\""))
        assertEquals("b.txt", raftContentDispositionFileName("attachment; filename*=UTF-8''a%2Fb.txt"))
        assertNull(raftContentDispositionFileName("attachment; filename=\"..\""))
    }

    @Test
    fun noNameInHeaderUsesUrlLastSegmentThenFallback() {
        assertNull(raftContentDispositionFileName("attachment"))
        assertNull(raftContentDispositionFileName(null))
        assertEquals(
            "图表.png",
            raftDownloadFileName("attachment", "https://app.raft.build/api/artifacts/pt_1/files/img%2F%E5%9B%BE%E8%A1%A8.png?version=2"),
        )
        assertEquals("download.zip", raftDownloadFileName(null, "https://app.raft.build/api/artifacts/pt_1/download.zip?version=3#x"))
        assertEquals("download", raftDownloadFileName(null, "https://app.raft.build/"))
        assertEquals("fallback.bin", raftDownloadFileName(null, "https://app.raft.build", fallback = "fallback.bin"))
    }
}
