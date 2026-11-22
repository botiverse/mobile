package build.raft.designsystem.components

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import build.raft.designsystem.tokens.RaftRadius

private val LocalRaftAvatarTrigger = staticCompositionLocalOf<InteractionSource?> { null }

/**
 * raft-ui `group/avatar-trigger` (0.102.15): the avatars in [content] open something (a profile, a
 * profile card), and answer hover and press from [interactionSource] like media, by laying `ink-8`
 * over the face, at once. The edge, the presence light and any corner mark keep their color.
 */
@Composable
fun RaftAvatarTrigger(interactionSource: InteractionSource, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRaftAvatarTrigger provides interactionSource, content = content)
}

/**
 * The [RaftAvatarTrigger] overlay of an avatar's face (inside its ink edge, 2dp corners): nothing
 * outside a trigger. [RaftAvatar] has it; an avatar that draws its own frame adds it to its face.
 */
@Composable
fun raftAvatarTriggerFace(): Modifier {
    val source = LocalRaftAvatarTrigger.current ?: return Modifier
    val overlay = rememberRaftStateOverlay(source, RaftHoverTier.Media)
    return Modifier.drawWithCache {
        val outline = RaftAvatarFaceShape.createOutline(size, layoutDirection, this)
        onDrawWithContent {
            drawContent()
            val color = overlay.value
            if (color.alpha > 0f) drawOutline(outline, color)
        }
    }
}

private val RaftAvatarFaceShape = RoundedCornerShape(RaftRadius.Xs)
