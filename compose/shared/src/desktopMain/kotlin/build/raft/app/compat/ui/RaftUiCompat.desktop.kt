package build.raft.app.compat.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.SystemFont
import coil3.toBitmap

internal actual fun coil3.Image.toRaftImageBitmap(): ImageBitmap = toBitmap().asComposeImageBitmap()

internal actual val raftKeyboardHeightExcludesNavigationBars: Boolean = false

/**
 * The Raft faces from :designsystem's Compose resources on the classpath; Regular for a face it
 * does not ship.
 */
@OptIn(ExperimentalTextApi::class)
actual fun KuiklyFont(name: String, weight: FontWeight, style: FontStyle): Font {
    val resource = raftFontResourceName(name)
    val bytes = raftReadDesignSystemFont(resource) ?: raftReadDesignSystemFont("space_grotesk_regular")
    return if (bytes != null) {
        Font(identity = resource, data = bytes, weight = weight, style = style)
    } else {
        SystemFont(name, weight, style)
    }
}

private fun raftReadDesignSystemFont(resource: String): ByteArray? =
    Thread.currentThread().contextClassLoader
        ?.getResourceAsStream("composeResources/build.raft.designsystem.resources/font/$resource.ttf")
        ?.use { it.readBytes() }
