package build.raft.app

/**
 * Whether this desktop can play attachment video (RaftVideoPlayer.desktop.kt).
 *
 * The player is ComposeMediaPlayer's macOS backend: a JNI bridge to AVFoundation
 * (libNativeVideoPlayer.dylib, built for macOS 14+, arm64 and x86_64) shipped inside its jar. The
 * app is packaged for macOS only, so other systems, older macOS and a bridge that fails to load all
 * keep video attachments as download cards instead of a player that cannot start.
 */
object RaftDesktopVideoSupport {
    /** The bridge's minimum macOS (LC_BUILD_VERSION minos of the shipped dylibs). */
    const val MIN_MACOS_MAJOR = 14

    private const val NATIVE_BRIDGE_CLASS = "io.github.kdroidfilter.composemediaplayer.mac.MacNativeBridge"

    val available: Boolean by lazy {
        platformSupported(System.getProperty("os.name"), System.getProperty("os.version")) && loadNativeBridge()
    }

    fun platformSupported(osName: String?, osVersion: String?): Boolean {
        if (osName == null || !osName.lowercase().startsWith("mac")) return false
        val major = osVersion?.substringBefore('.')?.trim()?.toIntOrNull() ?: return false
        return major >= MIN_MACOS_MAJOR
    }

    /**
     * What RaftVideoPlayer shows for [source]: the player only for video on a Mac that runs it.
     * Audio-only media ([audioOnly], from the preview's audio panel, or an audio file extension /
     * MIME in the URL) keeps desktop's "not available" notice: the player cannot play it.
     */
    fun route(source: String, audioOnly: Boolean, available: Boolean = this.available): RaftDesktopMediaRoute =
        when {
            !available || audioOnly || sourceLooksAudioOnly(source) -> RaftDesktopMediaRoute.NotAvailableNotice
            else -> RaftDesktopMediaRoute.Player
        }

    fun sourceLooksAudioOnly(source: String): Boolean {
        val path = source.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val mime =
            source.substringAfter('?', missingDelimiterValue = "").split('&')
                .firstOrNull { it.startsWith("response-content-type=", ignoreCase = true) || it.startsWith("mime=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.replace("%2F", "/", ignoreCase = true)
                .orEmpty()
        return raftFilePreviewIsAudioAttachment(fileName = path, mimeType = mime)
    }

    /** Whether a video attachment ([fileName], [mimeType]) opens in the player here. */
    fun supportsAttachment(fileName: String, mimeType: String, available: Boolean = this.available): Boolean =
        raftFilePreviewVideoContainer(fileName = fileName, mimeType = mimeType)
            ?.let { supportsContainer(it, available) } == true

    internal fun supportsContainer(container: RaftPreviewableVideoContainer, available: Boolean): Boolean =
        available &&
            raftVideoPlayerFormatProfileSupports(
                profile = RaftVideoPlayerFormatProfile.DesktopMacAvFoundation,
                container = container,
            )

    // Initialising the bridge class loads (and on first run extracts) the dylib, on this thread, so
    // a failure is caught here rather than inside the player's own coroutine.
    private fun loadNativeBridge(): Boolean =
        try {
            Class.forName(NATIVE_BRIDGE_CLASS, true, RaftDesktopVideoSupport::class.java.classLoader)
            true
        } catch (error: Throwable) {
            raftLogWarn("RaftDesktopVideo", "video player unavailable: ${error::class.simpleName}: ${error.message}")
            false
        }
}

enum class RaftDesktopMediaRoute {
    /** ComposeMediaPlayer with Raft's controller. */
    Player,

    /** Desktop's notice from before it had a player (audio-only media, or no player here). */
    NotAvailableNotice,
}
