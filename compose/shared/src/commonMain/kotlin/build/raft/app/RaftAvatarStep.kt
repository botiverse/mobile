package build.raft.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.raftAvatarInitials

/**
 * raft-ui 0.8.0 Avatar size steps (`avatar.tsx`), for app avatars whose footprint is a
 * free [Dp]: [nearest] maps any avatar size onto the closest web step, and the step
 * supplies the chrome that raft-ui scales per `data-size`: the presence light, its ink
 * edge, cut-out and overhang, and the fallback's initials and icon size.
 *
 * [badgeOverhang] is how far the light's box passes the avatar's outer edge. raft-ui
 * places the badge with `right` / `bottom` offsets (-8 / -5 / -4 / -3 / -2 px), which CSS
 * measures from the avatar's padding edge, inside its 1.5px ink border, so the light
 * overhangs the outer edge by the offset minus that border.
 */
internal enum class RaftAvatarStep(
    val edge: Dp,
    val badgeSize: Dp,
    val badgeOverhang: Dp,
    val badgeBorder: Dp,
    val badgeCutout: Dp,
    val initialsSize: TextUnit,
    val iconSize: Dp,
    val designSize: RaftAvatarSize
) {
    Xl(99.dp, 20.dp, 6.5.dp, 2.dp, 2.dp, 40.sp, 48.dp, RaftAvatarSize.Xl),
    Lg(51.dp, 14.dp, 3.5.dp, 1.5.dp, 2.dp, 22.sp, 24.dp, RaftAvatarSize.Lg),
    Md(35.dp, 11.dp, 2.5.dp, 1.5.dp, 2.dp, 15.sp, 18.dp, RaftAvatarSize.Md),
    Sm(27.dp, 9.dp, 1.5.dp, 1.5.dp, 2.dp, 13.sp, 14.dp, RaftAvatarSize.Sm),

    /** raft `xs` / `2xs` (both 19px). */
    Xs(19.dp, 7.dp, 0.5.dp, 1.dp, 1.dp, 11.sp, 12.dp, RaftAvatarSize.Xxs),

    /** raft `3xs` (14px, outside the scale). */
    Xxxs(14.dp, 6.dp, 0.5.dp, 1.dp, 1.dp, 8.sp, 8.dp, RaftAvatarSize.Xxxs);

    /** The light's corner radius: `rounded-[2px]`, the outline following it. */
    val badgeRadius: Dp get() = 2.dp

    companion object {
        /** The web step whose edge is closest to [avatarSize] (ties go to the larger step). */
        fun nearest(avatarSize: Dp): RaftAvatarStep =
            entries.minBy { step -> kotlin.math.abs(step.edge.value - avatarSize.value) - step.edge.value * 1e-4f }
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
