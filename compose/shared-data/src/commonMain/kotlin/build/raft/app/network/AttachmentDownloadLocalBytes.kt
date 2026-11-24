package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A file a hosted web page saved (`<a download>` of a `blob:` URL, an iOS WKDownload) whose bytes
 * the app already holds: handed to the host's ordinary save path ([build.raft.app.base.BridgeModule.saveAttachment],
 * Android Downloads, the iOS save sheet) under a `raft-local:` address. The download transport
 * serves these bytes for that one download id and address instead of the network, as a response
 * with the page's file name as its `Content-Disposition`. Taken once; dropped after [ttlMillis].
 */
object AttachmentDownloadLocalBytes {
    @RaftCoreInternalApi const val SCHEME: String = "raft-local"

    class Source(val bytes: ByteArray, val fileName: String, val contentType: String?)

    private class Entry(val url: String, val source: Source, val at: TimeMark)

    private val lock = SynchronizedObject()
    private val entries = HashMap<String, Entry>()
    @RaftCoreInternalApi var timeSource: TimeSource = TimeSource.Monotonic
    @RaftCoreInternalApi const val ttlMillis: Long = 60_000L

    fun isLocal(url: String): Boolean = url.startsWith("$SCHEME:")

    fun register(downloadId: String, url: String, source: Source) {
        if (downloadId.isBlank() || !isLocal(url)) return
        synchronized(lock) {
            prune()
            entries[downloadId] = Entry(url, source, timeSource.markNow())
        }
    }

    fun take(downloadId: String, url: String): Source? =
        synchronized(lock) {
            prune()
            val entry = entries.remove(downloadId) ?: return@synchronized null
            entry.source.takeIf { entry.url == url }
        }

    fun forget(downloadId: String) {
        synchronized(lock) { entries.remove(downloadId) }
    }

    @RaftCoreInternalApi fun clearForTest() {
        synchronized(lock) { entries.clear() }
    }

    private fun prune() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.at.elapsedNow().inWholeMilliseconds > ttlMillis) iterator.remove()
        }
    }
}

/** `attachment; filename="<ascii>"; filename*=UTF-8''<encoded>`, as the server writes one (RFC 6266 / 5987). */
@RaftCoreInternalApi fun raftAttachmentContentDisposition(fileName: String): String {
    val ascii = fileName.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
    val encoded =
        fileName.encodeToByteArray().joinToString("") { byte ->
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            if (char.isLetterOrDigit() && code < 0x80 || char in "-._~") char.toString()
            else "%" + code.toString(16).uppercase().padStart(2, '0')
        }
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}
