package build.raft.app.network

import kotlinx.atomicfu.atomic

/** Receives one chunk of a [RaftByteStream]. */
fun interface RaftByteStreamSink {
    suspend fun write(bytes: ByteArray)
}

/**
 * A one-attempt request body source (file uploads): opened once per transport attempt, read either
 * whole or in chunks, and cancelled at most once. Replaces NetworkKMM's RaftByteStream, removed with
 * the Ktor 3 switch; the shape is the same so platform file resolvers did not change.
 */
class RaftByteStream(
    val contentLength: Long?,
    private val readAllBlock: suspend () -> ByteArray,
    private val cancelBlock: () -> Unit = {},
    private val attemptCancelBlock: () -> Unit = cancelBlock,
    private val readChunksBlock: (suspend (RaftByteStreamSink) -> Unit)? = null,
) {
    private val cancelled = atomic(false)

    suspend fun readAll(): ByteArray = readAllBlock()

    /** Streams the body to [sink]; a source without a chunk reader is delivered as one chunk. */
    suspend fun readChunks(sink: RaftByteStreamSink) {
        val chunks = readChunksBlock
        if (chunks != null) chunks(sink) else sink.write(readAll())
    }

    /** Releases the source; idempotent. */
    fun cancel() {
        if (cancelled.compareAndSet(expect = false, update = true)) {
            runCatching(cancelBlock)
        }
    }

    /** Releases only this attempt's handle (the transport gave up on the attempt). */
    fun cancelAttempt() {
        runCatching(attemptCancelBlock)
    }
}
