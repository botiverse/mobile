package build.raft.app.components

import build.raft.app.isAmbient
import build.raft.app.LocalSlockTheme
import build.raft.app.SlockThemeFamily
import build.raft.app.slockThemedCorners
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.ui.draw.shadow
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
    shadowOffset: Dp? = null,
    contentPadding: Dp = 14.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    // Reads the brutal|elegant register from LocalSlockTheme. brutalTheme keeps the shipping
    // look byte-identical (ShadowBlack@4dp offset-box, square corners, 2dp black border, white
    // surface). elegantTheme renders the ambient register: real 8dp rounded corners + a soft
    // elevation shadow via Kuikly Modifier.shadow(clip = true) — the same fix the button used,
    // because the borderRadius-prop slockThemedCorners does not round at runtime and the
    // offset-box cannot blur (verified via #1894 captures; owner-accepted shadow profile).
    val theme = LocalSlockTheme.current
    if (theme.isAmbient) {
        val shape = RoundedCornerShape(theme.cornerRadius)
        Column(
            modifier = modifier
                .fillMaxWidth()
                // Card-specific shadow tuning (XiShi actual-capture audit, run 30820954442):
                // at 3dp the Kuikly elevation shadow was materially darker at contact (bottom
                // peak 37 vs React 22) and much shorter/more bottom-concentrated (bottom tail
                // 16px vs 26, right tail 11px vs 23, right integrated -44%) than the React
                // `0 1px 8px rgba(0,0,0,.15)` halo. 5dp widens the blur so both tails lengthen
                // and the bottom peak drops (same ink spread over more rows), and the larger
                // ambient component fills the offset-starved right side. Kuikly couples the
                // spot Y-offset to elevation, so a small bottom bias remains (the fundamental
                // floor vs React's near-symmetric CSS halo).
                .shadow(
                    elevation = 5.dp,
                    shape = shape,
                    clip = true,
                    ambientColor = theme.shadow.color,
                    spotColor = theme.shadow.color,
                )
                .background(theme.surface, shape)
                .border(theme.lineWidth, theme.line, shape)
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
    // Same register read as SlockBrutalCard: brutal byte-identical, elegant rounds +
    // softens + thin gray hairline. shadowOffset==null defers to the register.
    val theme = LocalSlockTheme.current
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
