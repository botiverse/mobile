package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftOpacity
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/**
 * raft-ui `AvatarBadge` with a sticker of the caller's (`render`): [badge] sits in the
 * corner the presence light takes on an avatar of [size] — bottom-right, overhanging the
 * outer edge by [RaftAvatarSize.badgeOverhang] (raft-ui `-right-1 -bottom-1` on `md`,
 * measured inside the 1.5dp ink border). [avatar] decides the box's size; the badge takes
 * no layout space.
 */
@Composable
fun RaftAvatarBadgeBox(
    size: RaftAvatarSize,
    badge: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    avatar: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        avatar()
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = size.badgeOverhang, y = size.badgeOverhang),
        ) { badge() }
    }
}

/** raft-ui `MessageItemDelegatedAvatar` metrics (`message-item.recipe.ts` `delegatedAvatar`). */
object RaftMessageDelegatedAvatarDefaults {
    /** `size-[19px]`: the assistant's face is a `2xs` avatar. */
    val Size: Dp = 19.dp

    /** The surface ring past the face's edge: `0 0 0 1.5px` in light. */
    val Ring: Dp = RaftStroke.Control

    /**
     * Dark: `0 0 0 3px`, outside the face's own 1.5dp die-cut edge, so 1.5dp of surface
     * still shows past the die-cut in both modes.
     */
    val RingDark: Dp = RaftStroke.Control * 2

    /** A deleted assistant's face: `grayscale opacity-dimmed`. */
    const val DeletedAlpha: Float = RaftOpacity.Dimmed
}

/**
 * raft-ui `MessageItemDelegatedAvatar`: the assistant's face on its owner's avatar, for a
 * message the assistant sent for its owner (RFC 077). Put it in [RaftAvatarBadgeBox]'s
 * `badge` on the owner's `md` avatar with a `2xs` ([RaftAvatarSize.Xxs]) agent avatar as
 * [face]. Only on a person's avatar: an agent's corner is its presence light.
 *
 * The face is ringed with, and backed by, [surfaceColor] — the row's surface (the
 * highlight over the canvas when the row has one), never the owner's avatar — so a faded
 * face never shows the owner through it. [deleted] grays and fades the face. Hidden from
 * accessibility: the "sent by" words beside the name say who sent the message.
 */
@Composable
fun RaftMessageDelegatedAvatar(
    modifier: Modifier = Modifier,
    deleted: Boolean = false,
    surfaceColor: Color = RaftTheme.surfaceColor,
    dark: Boolean = RaftTheme.isDark,
    face: @Composable BoxScope.() -> Unit,
) {
    val ring = if (dark) RaftMessageDelegatedAvatarDefaults.RingDark else RaftMessageDelegatedAvatarDefaults.Ring
    Box(
        modifier
            .size(RaftMessageDelegatedAvatarDefaults.Size)
            .clearAndSetSemantics { }
            .raftOuterRing(surfaceColor, ring, RaftRadius.Sticker)
            .background(surfaceColor, RoundedCornerShape(RaftRadius.Sticker))
            .then(if (deleted) Modifier.raftGrayscale(RaftMessageDelegatedAvatarDefaults.DeletedAlpha) else Modifier),
        contentAlignment = Alignment.Center,
        content = face,
    )
}

/** CSS `grayscale` + `opacity-[alpha]` on everything drawn inside, as one layer. */
private fun Modifier.raftGrayscale(alpha: Float): Modifier =
    drawWithContent {
        val paint =
            Paint().apply {
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                this.alpha = alpha
            }
        drawIntoCanvas { canvas ->
            // Leave room for the face's own die-cut ring, drawn just outside its box.
            val outset = RaftStroke.Control.toPx()
            canvas.saveLayer(Rect(-outset, -outset, size.width + outset, size.height + outset), paint)
            drawContent()
            canvas.restore()
        }
    }
