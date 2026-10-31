package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.app.network.RaftAppIdentity
import build.raft.app.network.RaftAppIdentityRegistry
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant

/**
 * The Android `alpha` build type (applicationId build.raft.app.alpha, launcher "Raft Alpha"):
 * the head shows an "Alpha" badge beside the server so the co-installed test build never reads
 * as production (artin 2026-10-31). Every other build (release, play, debug, iOS, desktop) is false.
 */
internal fun raftIsAlphaBuild(identity: RaftAppIdentity? = RaftAppIdentityRegistry.current()): Boolean =
    identity?.buildType == RaftAlphaBuildType

internal const val RaftAlphaBuildType = "alpha"

/** The design-system badge marking the alpha build; draws nothing on any other build. */
@Composable
internal fun RaftAlphaBuildBadge(modifier: Modifier = Modifier) {
    if (!raftIsAlphaBuild()) return
    RaftBadge(
        text = stringResource(Res.string.home_alpha_build_badge),
        modifier = modifier,
        appearance = RaftBadgeAppearance.Soft,
        variant = RaftBadgeVariant.Warning,
    )
}
