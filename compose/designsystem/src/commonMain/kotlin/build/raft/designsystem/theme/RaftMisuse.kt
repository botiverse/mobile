package build.raft.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Told when a component is used in a way raft-ui doesn't allow but that must not crash a
 * release build, e.g. a [build.raft.designsystem.components.RaftIconButton] with neither a
 * tooltip nor a content description (it has no name for a screen reader). Silent by default;
 * tests and debug builds provide one that fails, so a missing name still shows up:
 *
 * ```
 * CompositionLocalProvider(LocalRaftMisuse provides { error(it) }) { content() }
 * ```
 */
val LocalRaftMisuse = staticCompositionLocalOf<(String) -> Unit> { {} }
