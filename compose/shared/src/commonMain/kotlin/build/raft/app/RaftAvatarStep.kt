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
 * The 1–2 initials raft-ui writes for a person without a photo at [step]: the first
 * character of the label's first words ([raftAvatarInitials]: one at `sm` and below, two
 * above). Characters that cannot stand as an initial (emoji, punctuation such as `@` or
 * `?`, unpaired surrogates) are skipped at the start of each word; a label with no letter
 * or digit left yields "", so the fallback shows the placeholder icon.
 */
internal fun raftAppAvatarInitials(label: String, step: RaftAvatarStep): String =
    raftAvatarInitials(raftAvatarInitialWords(label), step.designSize)

/**
 * The single-character `avatarLabel` the models carry (widgets and caches read it): the
 * first initial of [this] by the same rule as [raftAppAvatarInitials], never half of a
 * surrogate pair; "" when the name has no letter or digit.
 */
internal fun String.toRaftAvatarLabel(): String = raftAppAvatarInitials(this, RaftAvatarStep.Sm)

/**
 * The words of [label] with what precedes their first letter or digit removed, joined by
 * single spaces. Supplementary-plane characters (emoji, and the rare letters outside the
 * BMP) are surrogate pairs that [Char.isLetterOrDigit] rejects, so they are skipped too.
 */
private fun raftAvatarInitialWords(label: String): String =
    label.split(RaftAvatarWordBreak)
        .map { word -> word.dropWhile { char -> !char.isLetterOrDigit() } }
        .filter(String::isNotEmpty)
        .joinToString(" ")

private val RaftAvatarWordBreak = Regex("\\s+")
