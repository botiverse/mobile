package build.raft.app.network

internal actual fun createAttachmentDownloadAtomicSink(
    targetPath: String,
    attemptToken: String
): AttachmentDownloadAtomicSink = object : AttachmentDownloadAtomicSink {
    override fun begin(): Boolean = false

    override fun write(bytes: ByteArray): Boolean = false

    override fun commit(): Boolean = false

    override fun abort() = Unit
}

internal actual fun attachmentDownloadPauseMillis(durationMillis: Long) = Unit
