package build.raft.app.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.Extras
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import okio.BufferedSource

/**
 * Animated GIF / animated WebP playback, opt-in per request.
 *
 * Only the full-screen image viewer asks for it ([raftAnimatedImage]), for the original file (as
 * the web lightbox does); every other image (message thumbnails, avatars, the viewer's own
 * thumbnail underlay) keeps the static decode path. An
 * opted-in request whose bytes are not an animated format falls through to the static decoders.
 *
 * Android decodes through coil-gif (`AnimatedImageDecoder` on API 28+, `GifDecoder` below; the
 * framework streams frames). iOS has no coil-gif artifact, so it wraps a Skia `Codec` and decodes
 * one frame at a time on the frame clock ([RaftAnimatedFramePlayer]).
 */
enum class RaftAnimatedImageFormat {
    Gif,
    AnimatedWebP,
}

private const val RAFT_ANIMATED_SNIFF_BYTES = 21L

/**
 * The animated format of an encoded image from its first bytes, or null for anything else
 * (still WebP, PNG, JPEG, ...). A GIF counts whatever its frame count: the decoder shows a
 * single-frame GIF as a still.
 */
fun raftSniffAnimatedImageFormat(header: ByteArray): RaftAnimatedImageFormat? {
    fun ascii(offset: Int, text: String): Boolean =
        header.size >= offset + text.length && text.indices.all { header[offset + it] == text[it].code.toByte() }
    if (ascii(0, "GIF87a") || ascii(0, "GIF89a")) return RaftAnimatedImageFormat.Gif
    // RIFF....WEBPVP8X, then the VP8X flags byte: bit 1 is the animation flag.
    if (ascii(0, "RIFF") && ascii(8, "WEBP") && ascii(12, "VP8X") && header.size > 20 &&
        header[20].toInt() and 0b10 != 0
    ) {
        return RaftAnimatedImageFormat.AnimatedWebP
    }
    return null
}

/** [raftSniffAnimatedImageFormat] on a source without consuming it. */
internal fun raftSniffAnimatedImageFormat(source: BufferedSource): RaftAnimatedImageFormat? {
    val peek = source.peek()
    peek.request(RAFT_ANIMATED_SNIFF_BYTES)
    val available = minOf(peek.buffer.size, RAFT_ANIMATED_SNIFF_BYTES)
    return raftSniffAnimatedImageFormat(peek.readByteArray(available))
}

private fun raftImageExtension(name: String?): String =
    name.orEmpty()
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()

private fun raftNormalizedMime(mimeType: String?): String =
    mimeType.orEmpty().substringBefore(';').trim().lowercase()

/**
 * Whether the viewer should ask for animated decoding of this image: its metadata says GIF or
 * WebP (WebP may or may not be animated; the decoder checks the bytes). Another `image/` type
 * keeps today's static path; with no image MIME type (blank, `application/octet-stream`) the
 * file name / URL extension decides.
 */
fun raftImageMayBeAnimated(mimeType: String?, vararg names: String?): Boolean {
    val mime = raftNormalizedMime(mimeType)
    if (mime.startsWith("image/")) return mime == "image/gif" || mime == "image/webp"
    return names.any { raftImageExtension(it) in setOf("gif", "webp") }
}

internal val RaftAnimateImageKey = Extras.Key(default = false)

/**
 * Opts this request into animated decoding. The memory cache key gets its own extra, so an
 * animated request never reads a still that another screen cached for the same URL and size.
 * Animated results themselves are never written to the memory cache: their images are not
 * shareable (coil-gif drawables, the iOS Skia codec image), which Coil's cache refuses.
 */
fun ImageRequest.Builder.raftAnimatedImage(): ImageRequest.Builder =
    apply {
        extras[RaftAnimateImageKey] = true
        memoryCacheKeyExtra("raft.animated", "1")
    }

/**
 * Hands opted-in GIF / animated WebP sources to the platform's animated decoder; returns null
 * (the next decoder, i.e. the static path) for everything else.
 */
internal class RaftAnimatedImageDecoderFactory(
    private val platform: Decoder.Factory,
) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        if (!options.getExtra(RaftAnimateImageKey)) return null
        raftSniffAnimatedImageFormat(result.source.source()) ?: return null
        return platform.create(result, options, imageLoader)
    }
}

/** The platform's animated decoder (coil-gif on Android, Skia `Codec` on iOS), or null. */
internal expect fun raftPlatformAnimatedImageDecoderFactory(): Decoder.Factory?

/**
 * Plays [image] while [running] and pauses it otherwise. A no-op for still images. Callers fold
 * page selection and app foreground into [running]; leaving composition stops it too.
 */
@Composable
internal expect fun RaftImageAnimationEffect(image: coil3.Image?, running: Boolean)

/** Whether the app is visible (lifecycle at least STARTED): animations pause in the background. */
@Composable
internal fun rememberRaftAppInForeground(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}
