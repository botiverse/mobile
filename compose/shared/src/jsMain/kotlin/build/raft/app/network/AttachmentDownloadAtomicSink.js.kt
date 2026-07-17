package build.raft.app.network

internal actual fun createAttachmentDownloadAtomicSink(
    targetPath: String,
    attemptToken: String
): AttachmentDownloadAtomicSink = object : AttachmentDownloadAtomicSink {
    override fun begin(): Boolean = false

    override fun write(bytes: ByteArray): Boolean = false

    override fun prepare(): Boolean = false

    override fun publish(): Boolean = false

    override fun abort() = Unit
}

internal actual fun attachmentDownloadPauseMillis(durationMillis: Long) = Unit
