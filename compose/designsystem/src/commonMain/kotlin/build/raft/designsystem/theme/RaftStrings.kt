package build.raft.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The few words the components write themselves. The module has no string resources: these
 * are raft-ui's English defaults, and an app provides its translations once, round the
 * screens that host the components (with its own resources, e.g. `Res.string` in :shared):
 *
 * ```
 * CompositionLocalProvider(
 *     LocalRaftStrings provides RaftStrings(
 *         optional = stringResource(Res.string.common_optional_suffix),
 *         close = stringResource(Res.string.common_close),
 *         clear = stringResource(Res.string.common_clear),
 *         loading = stringResource(Res.string.common_loading),
 *         cancel = stringResource(Res.string.common_cancel),
 *     ),
 * ) { content() }
 * ```
 *
 * A parameter that takes one of them on a component (`closeLabel`, `loadingLabel`, ...)
 * defaults to it, so a call can still pass its own words.
 */
@Immutable
data class RaftStrings(
    /** The marker after an optional field's label (raft-ui `LabelOptional`), drawn after a space. */
    val optional: String = "(optional)",
    /** The dialog's and popover's × button (raft-ui `DialogClose` `aria-label`). */
    val close: String = "Close",
    /** A text field's clear action ([build.raft.designsystem.components.RaftTextFieldClearAction]). */
    val clear: String = "Clear",
    /** A busy button's and a spinner's name (raft-ui `loadingLabel`). */
    val loading: String = "Loading",
    /** The ghost Cancel of a dialog's footer ([build.raft.designsystem.components.RaftDialogSheet] centred). */
    val cancel: String = "Cancel",
    /** A banner's × ([build.raft.designsystem.components.RaftBanner] `onDismiss`, raft-ui `BannerClose`). */
    val dismiss: String = "Dismiss",
    /** The long-press copy's screen-reader action ([build.raft.designsystem.components.raftCopyable]). */
    val copy: String = "Copy",
    /** The ⋯ that holds the actions a side column has no room for ([build.raft.designsystem.components.RaftSideActionColumn]). */
    val moreActions: String = "More Actions",
)

/** The enclosing [RaftStrings]; English outside any provider (so outside [RaftTheme] too). */
val LocalRaftStrings = staticCompositionLocalOf { RaftStrings() }
