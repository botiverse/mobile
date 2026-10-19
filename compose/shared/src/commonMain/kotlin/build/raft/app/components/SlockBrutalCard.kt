package build.raft.app.components

import build.raft.app.isAmbient
import build.raft.app.LocalSlockTheme
import build.raft.app.SlockThemeFamily
import build.raft.app.slockThemedCorners
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Unified brutal-style card with a hard-edged offset shadow on the right + bottom.
 *
 * Mirrors the visual language used across the React app (`card-brutal` class,
 * `shadow-brutal` token) and replaces three previously-private duplicates:
 *   - `HardShadowPanel` (SlockHomeComponents.kt) — 4dp shadow, width-bounded
 *   - `SettingsAccountHardShadowCard` (SettingsAccountPage.kt) — 3dp shadow
 *   - `HardShadowCard` (SettingsBillingPage.kt) — 2dp shadow
 *
 * Visual contract:
 *   - Outer Box is sized by the caller (`fillMaxWidth` by default) and reserves
 *     `shadowOffset` extra space on the right + bottom so the shadow can peek
 *     out without clipping the parent's bounds.
 *   - Inner shadow Box uses `matchParentSize()` + `offset(shadowOffset)` so
 *     it sits behind the foreground card and shows a uniform dark slab on
 *     the right + bottom edge.
 *   - Foreground Column has `border(BorderBlack)` + `background(SurfaceWhite)`
 *     so the card surface is visibly bounded and the shadow reads as a
 *     separate "lifted" layer.
 *   - No layout-shifting alignment: the inner Column fills the outer Box's
 *     non-shadow area, so callers can position the card via outer modifier
 *     without worrying about the shadow offset.
 *
 * Mirrors the proven brutal-shadow pattern from
 * `SlockModalFullWidthActionButton` (SlockModalComponents.kt) and
 * `SlockUnifiedDialog` (SlockModalComponents.kt). The earlier
 * `SlockBrutalListShell` used a broken `padding(end, bottom).background(ShadowBlack)`
 * pattern that did not actually reserve outer space for the shadow to peek
 * out — it now uses this same correct geometry.
 */
@Composable
internal fun SlockBrutalCard(
    modifier: Modifier = Modifier,
    shadowOffset: Dp? = null,
    contentPadding: Dp = 14.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    // raft-ui 0.6.0 Card (`variant="default"`): container radius 10dp, `--layer-card` fill,
    // 1dp `--line-hairline` hairline, no shadow (content surfaces never lift). The brutal
    // register below keeps its offset-box shadow.
    val theme = LocalSlockTheme.current
    if (theme.isAmbient) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .slockRaftCardSurface()
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
        return
    }
    // Brutal register (unchanged, byte-identical): a hard structural offset-box shadow.
    val resolvedShadowOffset = shadowOffset ?: theme.shadow.offset
    Box(modifier = modifier.fillMaxWidth().padding(end = resolvedShadowOffset, bottom = resolvedShadowOffset)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = resolvedShadowOffset, y = resolvedShadowOffset)
                .slockThemedCorners(theme.cornerRadius)
                .background(theme.shadow.color)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .slockThemedCorners(theme.cornerRadius)
                .background(theme.surface)
                .border(theme.lineWidth, theme.line)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/**
 * Brutal card variant with explicit width bounds. Used by popover-style
 * surfaces that need to fit within a fixed column (e.g. server switcher).
 *
 * Replaces the previously-private `HardShadowPanel` (SlockHomeComponents.kt).
 * The content Column fills the full bounded width — callers that need a
 * different inner layout can pass [contentModifier].
 */
@Composable
internal fun SlockBrutalPanel(
    minWidth: Dp,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    shadowOffset: Dp? = null,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // raft: the panel is a floating popover surface (server switcher and friends):
    // container radius, `--layer-popover`, 1dp seam ring and the overlay float shadow.
    // Brutal keeps its offset-box shadow; shadowOffset==null defers to the register.
    val theme = LocalSlockTheme.current
    if (theme.isAmbient) {
        Column(
            modifier = modifier
                .widthIn(min = minWidth, max = maxWidth)
                .slockRaftOverlaySurface()
                .then(contentModifier)
                .fillMaxWidth(),
            content = content
        )
        return
    }
    val resolvedShadowOffset = shadowOffset ?: theme.shadow.offset
    Box(
        modifier = modifier
            .padding(end = resolvedShadowOffset, bottom = resolvedShadowOffset)
            .widthIn(min = minWidth, max = maxWidth)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = resolvedShadowOffset, y = resolvedShadowOffset)
                .slockThemedCorners(theme.cornerRadius)
                .background(theme.shadow.color)
        )
        Column(
            modifier = Modifier
                .widthIn(min = minWidth, max = maxWidth)
                .then(contentModifier)
                .fillMaxWidth()
                .slockThemedCorners(theme.cornerRadius)
                .background(theme.surface)
                .border(theme.lineWidth, theme.line),
            content = content
        )
    }
}
