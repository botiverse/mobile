package build.raft.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.raftAvatarInitials
import kotlin.math.abs

/**
 * raft-ui 0.8.0 Avatar size steps (`avatar.tsx`), for app avatars whose footprint is a
 * free [Dp]: [nearest] maps any avatar size onto the closest web step, and the step
 * supplies the chrome that raft-ui scales per `data-size`: the presence light, its ink
 * edge, cut-out and overhang, and the fallback's initials and icon size.
 *
 * The values are the design system's [RaftAvatarSize] table ([designSize]); this enum only
 * folds raft `xs` / `2xs` (both 19px) into one step, so the app can pick a step by size.
 *
 * [badgeOverhang] is how far the light's box passes the avatar's outer edge. raft-ui
 * places the badge with `right` / `bottom` offsets (-8 / -5 / -4 / -3 / -2 px), which CSS
 * measures from the avatar's padding edge, inside its 1.5px ink border, so the light
 * overhangs the outer edge by the offset minus that border.
 */
internal enum class RaftAvatarStep(val designSize: RaftAvatarSize) {
    Xl(RaftAvatarSize.Xl),
    Lg(RaftAvatarSize.Lg),
    Md(RaftAvatarSize.Md),
    Sm(RaftAvatarSize.Sm),

    /** raft `xs` / `2xs` (both 19px). */
    Xs(RaftAvatarSize.Xxs),

    /** raft `3xs` (14px, outside the scale). */
    Xxxs(RaftAvatarSize.Xxxs);

    val edge: Dp get() = designSize.edge
    val badgeSize: Dp get() = designSize.badgeEdge
    val badgeOverhang: Dp get() = designSize.badgeOverhang
    val badgeBorder: Dp get() = designSize.badgeBorder
    val badgeCutout: Dp get() = designSize.badgeCutout
    val initialsSize: TextUnit get() = designSize.initialsSize
    val iconSize: Dp get() = designSize.iconSize

    /** The light's corner radius: `rounded-[2px]`, the outline following it. */
    val badgeRadius: Dp get() = 2.dp

    companion object {
        /** The web step whose edge is closest to [avatarSize]; a size halfway between two steps takes the larger. */
        fun nearest(avatarSize: Dp): RaftAvatarStep =
            entries.minWith(
                compareBy<RaftAvatarStep> { step -> abs(step.edge.value - avatarSize.value) }
                    .thenByDescending { step -> step.edge.value }
            )
    }
}

/**
 * The 1–2 initials raft-ui writes for a person without a photo at [step]: one at
 * `sm` and below, two above. A label that already is initials (one short word, as
 * the `avatarLabel` fields carry: "AD") is kept as written; a name is reduced to the
 * first letters of its words.
 */
internal fun raftAppAvatarInitials(label: String, step: RaftAvatarStep): String {
    val clean = label.trim().removePrefix("@").removePrefix("#").trim()
    if (clean.isEmpty()) return ""
    val maxInitials = step.designSize.maxInitials
    val words = clean.split(Regex("\\s+"))
    return if (words.size == 1 && clean.length <= 2 && clean.all { it.isLetterOrDigit() }) {
        clean.take(maxInitials).uppercase()
    } else {
        raftAvatarInitials(clean, step.designSize)
    }
}
