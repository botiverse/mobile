package build.raft.app.image

import android.graphics.drawable.Animatable
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import coil3.DrawableImage
import coil3.decode.Decoder
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

/**
 * coil-gif's decoder for this API level: `AnimatedImageDecoder` (framework `ImageDecoder`, GIF and
 * animated WebP, frames decoded on demand by `AnimatedImageDrawable`) on API 28+, else
 * `GifDecoder` (`Movie`, GIF only: an animated WebP stays a still there).
 */
internal fun raftAnimatedImageDecoderFactoryFor(sdkInt: Int): Decoder.Factory =
    if (sdkInt >= Build.VERSION_CODES.P) AnimatedImageDecoder.Factory() else GifDecoder.Factory()

internal actual fun raftPlatformAnimatedImageDecoderFactory(): Decoder.Factory? =
    raftAnimatedImageDecoderFactoryFor(Build.VERSION.SDK_INT)

/**
 * Coil's `DrawablePainter` starts an animatable drawable when it is remembered and stops it when
 * forgotten; this pauses it in between whenever [running] is false (a neighbouring pager page,
 * the app in the background).
 */
@Composable
internal actual fun RaftImageAnimationEffect(image: coil3.Image?, running: Boolean) {
    val animatable = (image as? DrawableImage)?.drawable as? Animatable ?: return
    DisposableEffect(animatable, running) {
        if (running) {
            if (!animatable.isRunning) animatable.start()
        } else {
            animatable.stop()
        }
        onDispose { }
    }
}
