package build.raft.app.components

import build.raft.app.SlockColor
import build.raft.app.SlockShadow
import build.raft.app.SlockStroke
import build.raft.app.slockSquareCorners
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp

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
    shadowOffset: Dp = SlockShadow.Brutal,
    contentPadding: Dp = 14.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxWidth().padding(end = shadowOffset, bottom = shadowOffset)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .slockSquareCorners()
                .background(SlockColor.ShadowBlack)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .slockSquareCorners()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
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
    shadowOffset: Dp = SlockShadow.Brutal,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .padding(end = shadowOffset, bottom = shadowOffset)
            .widthIn(min = minWidth, max = maxWidth)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .slockSquareCorners()
                .background(SlockColor.ShadowBlack)
        )
        Column(
            modifier = Modifier
                .widthIn(min = minWidth, max = maxWidth)
                .then(contentModifier)
                .fillMaxWidth()
                .slockSquareCorners()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
            content = content
        )
    }
}
