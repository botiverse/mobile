package build.raft.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * Builds Compose [ImageVector]s from the generated Lucide path table ([RaftLucideIconPaths]).
 *
 * Lucide stroke model: 24x24 viewBox, `currentColor` stroke of width 2 (viewBox units, so it
 * scales with the rendered size), round caps and joins, no fill; per-path overrides from the
 * SVG source (fills, explicit stroke widths, opacities) are carried by [RaftLucidePathSpec].
 *
 * `currentColor` paints are built as opaque black so a single cached vector can be tinted with
 * [androidx.compose.ui.graphics.ColorFilter.tint] (SrcIn keeps per-path alpha). Icons with direct
 * colour paints are built with the actual tint instead (see [RaftLucideImageVectors.vector]).
 */
internal object RaftLucideImageVectors {
    private data class Key(val name: SlockLucideIconName, val filled: Boolean, val strokeWidth: Float?)

    // Composition runs on the main thread; the cache is only touched from there and from tests.
    private val cache = HashMap<Key, ImageVector>()

    /** The spec drawn for [name]; [filled] picks the filled variant when one exists. */
    fun spec(name: SlockLucideIconName, filled: Boolean): RaftLucideIconSpec =
        (if (filled) RaftLucideIconPaths.filledSpec(name) else null) ?: RaftLucideIconPaths.spec(name)

    /** True when every paint of [spec] is `currentColor`, i.e. the vector can be tinted. */
    fun isTintable(spec: RaftLucideIconSpec): Boolean =
        spec.paths.all { path ->
            (path.fillPaint == null || path.fillPaint == CurrentColor) &&
                (path.strokePaint == null || path.strokePaint == CurrentColor)
        }

    /**
     * Cached vector for [name]. [strokeWidthOverride] (viewBox units, > 0) replaces the stroke
     * width of every stroked path. When the icon is not [isTintable], pass the [currentColor] to
     * bake in; such vectors are not cached.
     */
    fun vector(
        name: SlockLucideIconName,
        filled: Boolean = false,
        strokeWidthOverride: Float? = null,
        currentColor: Color = Color.Black
    ): ImageVector {
        val override = strokeWidthOverride?.takeIf { it > 0f }
        val spec = spec(name, filled)
        if (!isTintable(spec)) return build(spec, override, currentColor)
        val key = Key(name, filled, override)
        return cache.getOrPut(key) { build(spec, override, Color.Black) }
    }

    fun build(spec: RaftLucideIconSpec, strokeWidthOverride: Float?, currentColor: Color): ImageVector =
        ImageVector.Builder(
            name = "lucide-${spec.name}",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = spec.viewBoxWidth,
            viewportHeight = spec.viewBoxHeight
        ).apply {
            group(translationX = -spec.viewBoxMinX, translationY = -spec.viewBoxMinY) {
                spec.paths.forEach { path ->
                    addPath(
                        pathData = addPathNodes(path.pathData),
                        pathFillType =
                            when (path.fillRule) {
                                RaftLucideFillRule.NonZero -> PathFillType.NonZero
                                RaftLucideFillRule.EvenOdd -> PathFillType.EvenOdd
                            },
                        fill = path.fillPaint?.let { SolidColor(resolvePaint(it, currentColor)) },
                        fillAlpha = path.fillOpacity,
                        stroke = path.strokePaint?.let { SolidColor(resolvePaint(it, currentColor)) },
                        strokeAlpha = path.strokeOpacity,
                        strokeLineWidth = strokeWidthOverride ?: path.strokeWidth,
                        strokeLineCap =
                            when (path.strokeLineCap) {
                                RaftLucideStrokeLineCap.Butt -> StrokeCap.Butt
                                RaftLucideStrokeLineCap.Round -> StrokeCap.Round
                                RaftLucideStrokeLineCap.Square -> StrokeCap.Square
                            },
                        strokeLineJoin =
                            when (path.strokeLineJoin) {
                                RaftLucideStrokeLineJoin.Miter -> StrokeJoin.Miter
                                RaftLucideStrokeLineJoin.Round -> StrokeJoin.Round
                                RaftLucideStrokeLineJoin.Bevel -> StrokeJoin.Bevel
                            },
                        strokeLineMiter = 4f
                    )
                }
            }
        }.build()

    /** Resolves `currentColor` / `#rgb` / `#rrggbb` paints from the generated table. */
    fun resolvePaint(paint: String, currentColor: Color): Color {
        if (paint == CurrentColor) return currentColor
        val hex = paint.removePrefix("#")
        val expanded = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
        val rgb = expanded.toLongOrNull(16) ?: return currentColor
        return Color(0xFF000000L or rgb)
    }

    /** Lucide/SVG names are kebab-case; also accept camelCase and snake_case like the old renderers. */
    fun normalizeName(name: String): String =
        name.trim()
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .replace('_', '-')
            .lowercase()

    private const val CurrentColor = "currentColor"
}
