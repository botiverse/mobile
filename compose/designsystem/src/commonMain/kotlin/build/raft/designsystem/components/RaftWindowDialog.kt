package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import build.raft.designsystem.gesture.RaftBlockBackSwipe
import build.raft.designsystem.theme.RaftTheme

/**
 * How a [RaftWindowDialog] behaves.
 *
 * - [dismissOnBackPress]: Back (and Escape) calls `onDismissRequest`.
 * - [drawScrim]: the window draws [RaftTheme.scrim] behind the content. Pass false only when the
 *   content draws its own veil (the app's `Modal`, whose pages draw `RaftModalDismissBarrier`).
 * - [contentHandlesKeyboard]: the content pads for the keyboard itself, so iOS must not shrink
 *   the window for it (the app's `Modal` pages). Android always hands the keyboard to the content
 *   as `WindowInsets.ime` (the window is edge to edge).
 *
 * A tap on the scrim is the content's to handle: the content fills the window, so nothing is
 * ever "outside" it.
 */
@Immutable
data class RaftWindowDialogProperties(
    val dismissOnBackPress: Boolean = true,
    val drawScrim: Boolean = true,
    val contentHandlesKeyboard: Boolean = false,
)

/**
 * The one platform window every Raft dialog, sheet and modal opens ([RaftDialog], [RaftSheet],
 * [RaftDialogSheet], the combobox's sheet, the app's `Modal`). Nothing else calls Compose's
 * `Dialog` or Material's `ModalBottomSheet`.
 *
 * Every window gets the same policy, so none of them can miss it:
 * - a right swipe does not pop the page underneath ([RaftBlockBackSwipe]);
 * - the window is edge to edge and full size: [content] fills it and pads for the system bars,
 *   the cutout and the keyboard itself (`WindowInsets.safeDrawing`);
 * - no platform dim: the window draws the one scrim, [RaftTheme.scrim];
 * - its system bars follow the theme the dialog is composed in ([RaftTheme.isDark], read
 *   reactively, so switching the appearance with a dialog open updates it too), through
 *   [RaftDialogWindowPolicy], the same rule the host activity uses for its own bars.
 */
@Composable
fun RaftWindowDialog(
    onDismissRequest: () -> Unit,
    properties: RaftWindowDialogProperties = RaftWindowDialogProperties(),
    content: @Composable () -> Unit,
) {
    // A right swipe must not pop the page underneath an open dialog.
    RaftBlockBackSwipe()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = raftWindowDialogPlatformProperties(properties),
    ) {
        RaftDialogWindowPolicy(isDark = RaftTheme.isDark)
        Box(
            Modifier
                .fillMaxSize()
                .then(if (properties.drawScrim) Modifier.background(RaftTheme.scrim) else Modifier),
        ) {
            content()
        }
    }
}

/**
 * The platform [DialogProperties] for [properties]: no framework outside-click wrapper (the
 * content handles the scrim; on iOS the wrapper also made the window one accessibility leaf),
 * full width, edge to edge, and no framework scrim (Android: no window dim; iOS and desktop: a
 * transparent scrim), since [RaftWindowDialog] draws its own.
 */
internal expect fun raftWindowDialogPlatformProperties(properties: RaftWindowDialogProperties): DialogProperties

/**
 * Applies the window part of [RaftWindowDialog]'s policy from inside the dialog content: on
 * Android, no window dim, transparent system bars without contrast scrims, and bar icons for
 * [isDark] ([raftSystemBarsAppearance]). iOS dialogs are layers in the host's window, which
 * already follows the theme, so there is nothing to do there.
 */
@Composable
internal expect fun RaftDialogWindowPolicy(isDark: Boolean)

/** Which system bars draw dark icons: light ones on a dark theme, dark ones on a light theme. */
@Immutable
data class RaftSystemBarsAppearance(
    /** `isAppearanceLightStatusBars`: dark status bar icons. */
    val lightStatusBars: Boolean,
    /** `isAppearanceLightNavigationBars`: dark navigation bar icons. */
    val lightNavigationBars: Boolean,
)

/**
 * The one "bars follow the theme" rule, for the host activity and every dialog window: a light
 * theme gets dark icons, a dark theme light ones. There is no fallback appearance: the caller
 * always knows the theme it is drawn in.
 */
fun raftSystemBarsAppearance(isDark: Boolean): RaftSystemBarsAppearance =
    RaftSystemBarsAppearance(lightStatusBars = !isDark, lightNavigationBars = !isDark)

/**
 * The bar icons for what a page actually paints behind each bar: dark icons on a light colour,
 * light icons on a dark one, by the colour's luminance rather than the dark-mode flag. The two
 * differ on a themed frame: Space and Quest put a dark frame under the status bar in light mode
 * (light icons), while Raft Yellow and Bots light blue are light frames (dark icons).
 */
fun raftSystemBarsAppearance(statusBarBackdrop: Color, navigationBarBackdrop: Color): RaftSystemBarsAppearance =
    RaftSystemBarsAppearance(
        lightStatusBars = raftWantsDarkSystemBarIcons(statusBarBackdrop),
        lightNavigationBars = raftWantsDarkSystemBarIcons(navigationBarBackdrop),
    )

/**
 * Whether dark (black) system bar icons read better than light (white) ones on [backdrop]: the
 * WCAG contrast of black beats white's above a relative luminance of about 0.179, so the split
 * is there. A translucent colour is judged as if opaque.
 */
fun raftWantsDarkSystemBarIcons(backdrop: Color): Boolean =
    backdrop.copy(alpha = 1f).luminance() > RaftSystemBarIconLuminanceSplit

/** sqrt(1.05 * 0.05) - 0.05: the luminance where black and white text have equal WCAG contrast. */
private const val RaftSystemBarIconLuminanceSplit = 0.179f
