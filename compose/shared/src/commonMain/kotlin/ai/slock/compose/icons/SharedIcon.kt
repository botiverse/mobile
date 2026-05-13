package ai.slock.compose.icons

data class SharedIcon(
    val name: String,
    val displayName: String,
    val rawSvg: String,
    val nodes: List<SharedIconNode>,
    val viewBoxWidth: Float = 24f,
    val viewBoxHeight: Float = 24f
)

data class SharedIconNode(
    val commands: List<SharedIconCommand>,
    val fill: String? = null,
    val stroke: String? = "currentColor",
    val strokeWidth: Float = 2f,
    val strokeLineCap: SharedIconLineCap = SharedIconLineCap.Round,
    val strokeLineJoin: SharedIconLineJoin = SharedIconLineJoin.Round
)

sealed interface SharedIconCommand {
    data class MoveTo(val x: Float, val y: Float) : SharedIconCommand
    data class LineTo(val x: Float, val y: Float) : SharedIconCommand
    data class QuadraticTo(val controlX: Float, val controlY: Float, val x: Float, val y: Float) : SharedIconCommand
    data class CubicTo(
        val control1X: Float,
        val control1Y: Float,
        val control2X: Float,
        val control2Y: Float,
        val x: Float,
        val y: Float
    ) : SharedIconCommand
    data class Circle(val centerX: Float, val centerY: Float, val radius: Float) : SharedIconCommand
    data object Close : SharedIconCommand
}

enum class SharedIconLineCap {
    Butt,
    Round,
    Square
}

enum class SharedIconLineJoin {
    Miter,
    Round,
    Bevel
}
