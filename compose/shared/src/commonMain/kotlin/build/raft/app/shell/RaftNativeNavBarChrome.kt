package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.thread.RaftChannelIdentityTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectLatest

/*
 * iOS native navigation polish (artin, task #98, 1.22.0): the host bar draws what the Compose head
 * drew, from the design system, not UIKit stand-ins:
 * - bar buttons: the Lucide glyph ([RaftNativeNavBarAction.icon], `"imageAsset"` on the wire);
 * - the title's channel tile / DM avatar: drawn here by the same composables as the Compose head
 *   and handed over as pixels ([RaftNativeNavBarTitleImageCapture]);
 * - the title / subtitle type: the head's tokens as UIFonts ([RaftNativeNavBarTypography]).
 */

/** The host title view's leading tile / avatar, in points (RaftNativeNavTitleView; 22 in a compact bar). */
val RaftNativeNavBarTitleImageSize = 28.dp

/** The glyph inside the channel tile at [RaftNativeNavBarTitleImageSize] (the head's 14 in 27). */
private val RaftNativeNavBarChannelGlyphSize = 14.dp

/**
 * Re-captures after a change, for content that settles without redrawing this node (an avatar
 * photo decoding inside its own clipped layer): about 25 s in all, then only draws capture again.
 */
internal val RaftNativeNavBarTitleImageRetryMillis: List<Long> = listOf(0L, 150L, 400L, 1_000L, 2_500L, 6_000L, 15_000L)

/**
 * Draws [leading] offscreen as the Compose head does (the channel identity tile, [RaftAppAvatar]
 * with its photo / pixel face / initials) and reports the pixels to [onImage] whenever they change
 * (never twice the same). Nothing is drawn on screen: the content is only recorded into a graphics
 * layer, laid out in no space ([ThreadShareImageOffscreen] does the same for share images).
 */
@Composable
internal fun RaftNativeNavBarTitleImageCapture(
    leading: RaftNativeNavBarTitleLeading,
    onImage: (RaftNativeTabBarAvatar) -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val drawn = remember(layer) { Channel<Unit>(Channel.CONFLATED) }
    val currentOnImage by rememberUpdatedState(onImage)
    LaunchedEffect(layer, leading) {
        var last: IntArray? = null
        suspend fun capture() {
            if (layer.isReleased || layer.size == IntSize.Zero) return
            val bitmap =
                try {
                    layer.toImageBitmap()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return
                }
            val image = raftNativeTabBarAvatar(bitmap) ?: return
            if (last?.contentEquals(image.argb) == true) return
            last = image.argb
            currentOnImage(image)
        }
        drawn.receiveAsFlow().collectLatest {
            var waited = 0L
            for (at in RaftNativeNavBarTitleImageRetryMillis) {
                delay(at - waited)
                waited = at
                capture()
            }
        }
    }
    Box(
        Modifier
            .clearAndSetSemantics {}
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) { placeable.place(0, 0) }
            }
    ) {
        Box(
            Modifier.drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawn.trySend(Unit)
            }
        ) {
            when (leading) {
                is RaftNativeNavBarTitleLeading.Channel ->
                    RaftChannelIdentityTile(
                        glyph = leading.glyph,
                        tileSize = RaftNativeNavBarTitleImageSize,
                        iconSize = RaftNativeNavBarChannelGlyphSize,
                    )
                is RaftNativeNavBarTitleLeading.Avatar ->
                    RaftAppAvatar(
                        kind = leading.kind,
                        avatarWireValue = leading.avatarWireValue,
                        label = leading.label,
                        avatarSize = RaftNativeNavBarTitleImageSize,
                    )
                is RaftNativeNavBarTitleLeading.Server ->
                    RaftNativeNavBarServerTile(
                        label = leading.label,
                        imageUrl = leading.imageUrl,
                        tileSize = RaftNativeNavBarTitleImageSize,
                    )
                is RaftNativeNavBarTitleLeading.AgentConversation ->
                    RaftNativeNavBarAgentConversationAvatar(
                        agentId = leading.agentId,
                        avatarWireValue = leading.avatarWireValue,
                        label = leading.label,
                        cacheServerId = leading.cacheServerId,
                        side = if (leading.isLounge) AgentLoungeConversationSide.Lounge else AgentLoungeConversationSide.Private,
                        attention = leading.attention,
                        captureSize = RaftNativeNavBarTitleImageSize,
                    )
            }
        }
    }
}

/**
 * #proj-mobile:#290: draws the iOS native Home title bar's server tile through the same Coil
 * memory+disk cache ([RaftAvatarHttpImage]) as every Compose server icon instead of a separate
 * UIKit `URLSession.shared` fetch. Never flashes the initial letter while a configured server
 * image is decoding.
 */
@Composable
private fun RaftNativeNavBarServerTile(
    label: String,
    imageUrl: String?,
    tileSize: Dp,
) {
    val shape = RoundedCornerShape(RaftAppRadius.Sticker)
    val initial = label.trim().take(1).uppercase()
    val fallbackLabel: @Composable () -> Unit = {
        Box(
            modifier = Modifier.fillMaxSize().background(RaftColor.StickerInk, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style =
                    RaftType.Button.copy(
                        fontFamily = raftMonoFamilyForWeight(FontWeight.Bold),
                        fontSize = if (tileSize < 32.dp) 13.sp else 17.sp,
                        lineHeight = if (tileSize < 32.dp) 16.sp else 20.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                color = RaftColor.BrandYellow,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
    val source = imageUrl?.trim()?.takeIf { it.isNotEmpty() }
    Box(
        modifier =
            Modifier
                .size(tileSize)
                .clip(shape)
                .border(RaftAppStroke.Control, RaftColor.StickerInk, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (source == null) {
            fallbackLabel()
        } else {
            RaftAvatarHttpImage(
                source = source,
                modifier = Modifier.fillMaxSize(),
                fallback = fallbackLabel,
                placeholder = {
                    Box(Modifier.fillMaxSize().background(RaftColor.StickerInk, shape))
                },
            )
        }
    }
}

/**
 * A text token as the host's UIFont: [fontName] is the face's PostScript name (registered through
 * UIAppFonts; null: the system font), [size] and [kern] in points. Exported to Swift.
 */
class RaftNativeNavBarFont(val fontName: String?, val size: Double, val kern: Double)

internal fun raftNativeNavBarFont(style: TextStyle): RaftNativeNavBarFont {
    val size = style.fontSize.takeIf { it.isSp }?.value?.toDouble() ?: 17.0
    val spacing = style.letterSpacing
    val kern =
        when {
            spacing.isEm -> spacing.value * size
            spacing.isSp -> spacing.value.toDouble()
            else -> 0.0
        }
    return RaftNativeNavBarFont(style.raftSpaceGroteskFaceName(), size, kern)
}

/**
 * The bar's type, the Compose head's tokens (artin, task #98: the system's 17pt semibold title read
 * too small next to the app's heads). Swift: `RaftNativeNavBarTypography.shared.title()`.
 */
object RaftNativeNavBarTypography {
    /** The title: [RaftTopPageHeader]'s `text-headline` (17/22 700, Space Grotesk Bold). */
    fun title(): RaftNativeNavBarFont = raftNativeNavBarFont(RaftType.Headline)

    /** The line under the title: the conversation head's meta line, `text-caption` (12/16 500). */
    fun subtitle(): RaftNativeNavBarFont = raftNativeNavBarFont(RaftType.Caption)

    /**
     * A bar button's [RaftNativeNavBarAction.count] (the members count beside the glyph): web's
     * header member count, `text-mono-meta` in Space Mono Bold with tabular figures, muted ink.
     */
    fun barCount(): RaftNativeNavBarFont = RaftNativeNavBarFont(RaftSpaceMonoBoldFaceName, RaftMonoMeta.fontSize.value.toDouble(), 0.0)
}
