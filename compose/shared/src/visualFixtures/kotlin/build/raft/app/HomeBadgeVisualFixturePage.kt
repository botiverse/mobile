package build.raft.app

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.Outline
import com.tencent.kuikly.compose.ui.graphics.Path
import com.tencent.kuikly.compose.ui.graphics.Shape
import com.tencent.kuikly.compose.ui.graphics.StrokeJoin
import com.tencent.kuikly.compose.ui.graphics.drawscope.Fill
import com.tencent.kuikly.compose.ui.graphics.drawscope.Stroke
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.resources.DrawableResource
import com.tencent.kuikly.compose.resources.InternalResourceApi
import com.tencent.kuikly.compose.resources.painterResource
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.attr.ImageUri
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

const val HOME_BADGE_VISUAL_FIXTURE_PAGE = "compose_home_badge_visual_fixture"
const val HOME_BADGE_DESIGN_VISUAL_FIXTURE_PAGE = "compose_home_badge_design_visual_fixture"

@Page(HOME_BADGE_VISUAL_FIXTURE_PAGE, supportInLocal = true)
internal class ComposeHomeBadgeVisualFixturePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeBadgeVisualFixtureScreen() }
    }
}

@Page(HOME_BADGE_DESIGN_VISUAL_FIXTURE_PAGE, supportInLocal = true)
internal class ComposeHomeBadgeDesignVisualFixturePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeBadgeDesignVisualFixtureScreen() }
    }
}

@Composable
internal fun HomeBadgeVisualFixtureScreen() {
    HomeBadgeFixtureFrame(
        title = "Home Badge Render Paths",
        subtitle = "All rows fit in one viewport; compare current render pipelines."
    ) {
        HomeBadgeFixtureRow(
            title = "React CSS reference",
            detail = "rotate(-2deg) + translateZ/backface/will-change model",
            badge = { ReactCssModeledBadge(HomeBadgeFixtureServerName) }
        )
        HomeBadgeFixtureRow(
            title = "KMP current rotate",
            detail = "current production: whole Kuikly layer rotate(-2f)",
            badge = { CurrentKmpRotateBadge(HomeBadgeFixtureServerName) }
        )
        HomeBadgeFixtureRow(
            title = "Shape final path",
            detail = "background/border with rotated Outline.Generic",
            badge = { ShapeTiltedPathBadge(HomeBadgeFixtureServerName) }
        )
        HomeBadgeFixtureRow(
            title = "drawPath final path",
            detail = "fill + fill + stroke, no rotated layer",
            badge = { DrawPathTiltedBadge(HomeBadgeFixtureServerName) }
        )
        HomeBadgeFixtureRow(
            title = "Android native 3x path",
            detail = "KRView Paint path + supersample, no rotate",
            badge = { NativeTiltedPathBadge(HomeBadgeFixtureServerName) }
        )
        HomeBadgeFixtureRow(
            title = "Kuikly Image PNG 4x",
            detail = "painterResource common asset pre-antialiased bitmap",
            badge = { ImageTiltedBadge(HomeBadgeFixtureServerName) }
        )
        HomeBadgeFixtureRow(
            title = "Path star diagnostic",
            detail = "control: vector path sanity check",
            badge = { VectorStarDiagnostic() }
        )
    }
}

@Composable
internal fun HomeBadgeDesignVisualFixtureScreen() {
    HomeBadgeFixtureFrame(
        title = "Home Badge Design Variables",
        subtitle = "Same content; compare angle, edge contrast, and what stays tilted."
    ) {
        HomeBadgeFixtureRow(
            title = "Current 2deg hard edge",
            detail = "production shape: black fill, hard edge, tilted layer",
            badge = { RotatedDesignBadge(HomeBadgeFixtureServerName, tiltDegrees = -2f) }
        )
        HomeBadgeFixtureRow(
            title = "4deg hard edge",
            detail = "more visible slope; shorter horizontal stair runs",
            badge = { RotatedDesignBadge(HomeBadgeFixtureServerName, tiltDegrees = -4f) }
        )
        HomeBadgeFixtureRow(
            title = "6deg hard edge",
            detail = "stronger design gesture; stair pattern breaks up more",
            badge = { RotatedDesignBadge(HomeBadgeFixtureServerName, tiltDegrees = -6f) }
        )
        HomeBadgeFixtureRow(
            title = "2deg softer fill",
            detail = "reduce black/cream contrast at the slanted edge",
            badge = { RotatedDesignBadge(HomeBadgeFixtureServerName, tiltDegrees = -2f, fill = Color(0xFF171717)) }
        )
        HomeBadgeFixtureRow(
            title = "2deg no stroke",
            detail = "drop explicit border; keep fill + brutal shadow",
            badge = { RotatedDesignBadge(HomeBadgeFixtureServerName, tiltDegrees = -2f, border = Color.Transparent) }
        )
        HomeBadgeFixtureRow(
            title = "Tilted shadow only",
            detail = "main badge stays rectangular; tilt becomes decoration",
            badge = { TiltedShadowOnlyBadge(HomeBadgeFixtureServerName) }
        )
        HomeBadgeFixtureRow(
            title = "Flat fallback",
            detail = "React already strips tilt on short viewports",
            badge = { FlatBadge(HomeBadgeFixtureServerName) }
        )
    }
}

@Composable
private fun HomeBadgeFixtureFrame(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceCream)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = title,
            style = SlockType.PageTitle.copy(fontSize = 22.sp, lineHeight = 26.sp),
            color = SlockColor.TextBlack
        )
        Text(
            text = subtitle,
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = SlockColor.TextBlack.copy(alpha = 0.65f)
        )
        content()
    }
}

@Composable
private fun HomeBadgeFixtureRow(
    title: String,
    detail: String,
    badge: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().height(88.dp)) {
        Box(Modifier.matchParentSize().offset(x = 3.dp, y = 3.dp).background(SlockColor.ShadowBlack))
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = detail,
                        style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 10.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.55f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .width(HomeBadgeFixtureBadgeWidth + HomeBadgeFixtureShadowOffset + 8.dp)
                            .height(HomeBadgeFixtureBadgeHeight + HomeBadgeFixtureShadowOffset + 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    badge()
                }
            }
            PixelGuideStrip()
        }
    }
}

@Composable
private fun ReactCssModeledBadge(text: String) {
    Box(modifier = Modifier.padding(end = HomeBadgeFixtureShadowOffset, bottom = HomeBadgeFixtureShadowOffset).rotate(-2f)) {
        Box(Modifier.matchParentSize().offset(x = HomeBadgeFixtureShadowOffset, y = HomeBadgeFixtureShadowOffset).background(SlockColor.ShadowBlack))
        HomeBadgeFixtureContent(
            text = text,
            modifier =
                Modifier
                    .background(SlockColor.TextBlack)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            chevronModifier = Modifier.rotate(2f)
        )
    }
}

@Composable
private fun CurrentKmpRotateBadge(text: String) {
    Box(modifier = Modifier.padding(end = HomeBadgeFixtureShadowOffset, bottom = HomeBadgeFixtureShadowOffset).rotate(-2f)) {
        RectBadgeChrome(
            color = SlockColor.ShadowBlack,
            borderColor = Color.Transparent,
            modifier = Modifier.matchParentSize().offset(x = HomeBadgeFixtureShadowOffset, y = HomeBadgeFixtureShadowOffset)
        )
        RectBadgeChrome(
            color = SlockColor.TextBlack,
            borderColor = SlockColor.BorderBlack,
            modifier = Modifier.matchParentSize()
        )
        HomeBadgeFixtureContent(
            text = text,
            modifier = Modifier,
            chevronModifier = Modifier.rotate(2f)
        )
    }
}

@Composable
private fun RotatedDesignBadge(
    text: String,
    tiltDegrees: Float,
    fill: Color = SlockColor.TextBlack,
    border: Color = SlockColor.BorderBlack
) {
    Box(modifier = Modifier.padding(end = HomeBadgeFixtureShadowOffset, bottom = HomeBadgeFixtureShadowOffset).rotate(tiltDegrees)) {
        RectBadgeChrome(
            color = SlockColor.ShadowBlack,
            borderColor = Color.Transparent,
            modifier = Modifier.matchParentSize().offset(x = HomeBadgeFixtureShadowOffset, y = HomeBadgeFixtureShadowOffset)
        )
        RectBadgeChrome(
            color = fill,
            borderColor = border,
            modifier = Modifier.matchParentSize()
        )
        HomeBadgeFixtureContent(
            text = text,
            modifier = Modifier,
            chevronModifier = Modifier.rotate(-tiltDegrees)
        )
    }
}

@Composable
private fun TiltedShadowOnlyBadge(text: String) {
    Box(modifier = Modifier.padding(end = HomeBadgeFixtureShadowOffset, bottom = HomeBadgeFixtureShadowOffset)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = HomeBadgeFixtureShadowOffset, y = HomeBadgeFixtureShadowOffset)
                .rotate(-2f)
                .background(SlockColor.ShadowBlack)
        )
        HomeBadgeFixtureContent(
            text = text,
            modifier =
                Modifier
                    .background(SlockColor.TextBlack)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            chevronModifier = Modifier
        )
    }
}

@Composable
private fun FlatBadge(text: String) {
    Box(modifier = Modifier.padding(end = HomeBadgeFixtureShadowOffset, bottom = HomeBadgeFixtureShadowOffset)) {
        Box(Modifier.matchParentSize().offset(x = HomeBadgeFixtureShadowOffset, y = HomeBadgeFixtureShadowOffset).background(SlockColor.ShadowBlack))
        HomeBadgeFixtureContent(
            text = text,
            modifier =
                Modifier
                    .background(SlockColor.TextBlack)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            chevronModifier = Modifier
        )
    }
}

@Composable
private fun ShapeTiltedPathBadge(text: String) {
    val shape = TiltedBadgeShape()
    Box(modifier = Modifier.padding(end = HomeBadgeFixtureShadowOffset, bottom = HomeBadgeFixtureShadowOffset)) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .offset(x = HomeBadgeFixtureShadowOffset, y = HomeBadgeFixtureShadowOffset)
                    .background(SlockColor.ShadowBlack, shape)
        )
        HomeBadgeFixtureContent(
            text = text,
            modifier =
                Modifier
                    .background(SlockColor.TextBlack, shape)
                    .border(SlockStroke.Default, SlockColor.BorderBlack, shape),
            chevronModifier = Modifier
        )
    }
}

@Composable
private fun DrawPathTiltedBadge(text: String) {
    Box(modifier = Modifier.padding(end = HomeBadgeFixtureShadowOffset, bottom = HomeBadgeFixtureShadowOffset)) {
        Box(modifier = Modifier.width(HomeBadgeFixtureBadgeWidth).height(HomeBadgeFixtureBadgeHeight)) {
            Canvas(modifier = Modifier.width(HomeBadgeFixtureBadgeWidth).height(HomeBadgeFixtureBadgeHeight)) {
                val strokeWidth = SlockStroke.Default.toPx()
                val shadowOffset = HomeBadgeFixtureShadowOffset.toPx()
                val shadowPath = tiltedBadgePath(
                    width = size.width,
                    height = size.height,
                    inset = strokeWidth / 2f,
                    offsetX = shadowOffset,
                    offsetY = shadowOffset
                )
                val bodyPath = tiltedBadgePath(
                    width = size.width,
                    height = size.height,
                    inset = strokeWidth / 2f
                )
                drawPath(shadowPath, SlockColor.ShadowBlack, style = Fill)
                drawPath(bodyPath, SlockColor.TextBlack, style = Fill)
                drawPath(
                    bodyPath,
                    SlockColor.BorderBlack,
                    style = Stroke(width = strokeWidth, join = StrokeJoin.Miter)
                )
            }
            HomeBadgeFixtureForeground(
                text = text,
                modifier = Modifier.matchParentSize().padding(horizontal = 12.dp),
                chevronModifier = Modifier
            )
        }
    }
}

@Composable
private fun NativeTiltedPathBadge(text: String) {
    if (!isSlockNativeTiltedBadgeChromeSupported()) {
        Box(modifier = Modifier.padding(end = HomeBadgeFixtureShadowOffset, bottom = HomeBadgeFixtureShadowOffset)) {
            ShapeTiltedPathBadge(text)
        }
        return
    }
    Box(modifier = Modifier.padding(end = HomeBadgeFixtureShadowOffset, bottom = HomeBadgeFixtureShadowOffset)) {
        Box(modifier = Modifier.width(HomeBadgeFixtureBadgeWidth).height(HomeBadgeFixtureBadgeHeight)) {
            SlockNativeTiltedBadgeChrome(
                modifier = Modifier.matchParentSize(),
                width = HomeBadgeFixtureBadgeWidth,
                height = HomeBadgeFixtureBadgeHeight,
                fill = SlockColor.TextBlackArgb,
                border = SlockColor.BorderBlackArgb,
                shadow = SlockColor.ShadowBlackArgb,
                strokeWidth = SlockStroke.Default,
                shadowOffset = HomeBadgeFixtureShadowOffset,
                tiltDegrees = HomeBadgeFixtureTiltDegrees,
                supersample = 3
            )
            HomeBadgeFixtureForeground(
                text = text,
                modifier = Modifier.matchParentSize().padding(horizontal = 12.dp),
                chevronModifier = Modifier
            )
        }
    }
}

@Composable
private fun ImageTiltedBadge(text: String) {
    Box(modifier = Modifier.padding(end = HomeBadgeFixtureShadowOffset, bottom = HomeBadgeFixtureShadowOffset)) {
        Box(modifier = Modifier.width(HomeBadgeFixtureImageWidth).height(HomeBadgeFixtureImageHeight)) {
            Image(
                painter = painterResource(HomeBadgeChromeImageResource),
                contentDescription = null,
                modifier = Modifier.width(HomeBadgeFixtureImageWidth).height(HomeBadgeFixtureImageHeight),
                contentScale = ContentScale.FillBounds
            )
            HomeBadgeFixtureForeground(
                text = text,
                modifier =
                    Modifier
                        .width(HomeBadgeFixtureBadgeWidth)
                        .height(HomeBadgeFixtureBadgeHeight)
                        .padding(horizontal = 12.dp),
                chevronModifier = Modifier
            )
        }
    }
}

@Composable
private fun VectorStarDiagnostic() {
    Canvas(modifier = Modifier.size(68.dp)) {
        val strokeWidth = SlockStroke.Default.toPx()
        val star = starPath(size.width, size.height, inset = strokeWidth / 2f)
        drawPath(star, SlockColor.BrandYellow, style = Fill)
        drawPath(
            star,
            SlockColor.BorderBlack,
            style = Stroke(width = strokeWidth, join = StrokeJoin.Miter)
        )
    }
}

@Composable
private fun HomeBadgeFixtureContent(
    text: String,
    modifier: Modifier,
    chevronModifier: Modifier
) {
    Row(
        modifier =
            modifier
                .width(HomeBadgeFixtureBadgeWidth)
                .height(HomeBadgeFixtureBadgeHeight)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = SlockColor.BrandYellow,
            style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 20.sp).withSlockFontWeight(FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 128.dp)
        )
        Spacer(Modifier.width(4.dp))
        SlockLucideIcon(
            SlockLucideIconName.ChevronDown,
            Modifier.size(15.dp).then(chevronModifier),
            color = SlockColor.BrandYellowArgb
        )
    }
}

@Composable
private fun HomeBadgeFixtureForeground(
    text: String,
    modifier: Modifier,
    chevronModifier: Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = SlockColor.BrandYellow,
            style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 20.sp).withSlockFontWeight(FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 128.dp)
        )
        Spacer(Modifier.width(4.dp))
        SlockLucideIcon(
            SlockLucideIconName.ChevronDown,
            Modifier.size(15.dp).then(chevronModifier),
            color = SlockColor.BrandYellowArgb
        )
    }
}

@Composable
private fun RectBadgeChrome(
    color: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    val borderedModifier =
        if (borderColor == Color.Transparent) {
            modifier.background(color)
        } else {
            modifier.background(color).border(SlockStroke.Default, borderColor)
        }
    Box(borderedModifier)
}

@Composable
private fun PixelGuideStrip() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(HomeBadgeFixtureGuideBg)
                .border(1.dp, SlockColor.BorderBlack),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(34) { index ->
            Box(
                modifier =
                    Modifier
                        .width(6.dp)
                        .height(if (index % 2 == 0) 9.dp else 5.dp)
                        .background(if (index % 2 == 0) SlockColor.TextBlack.copy(alpha = 0.2f) else SlockColor.TextBlack.copy(alpha = 0.1f))
            )
        }
    }
}

private class TiltedBadgeShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(
            tiltedBadgePath(
                width = size.width,
                height = size.height,
                inset = with(density) { SlockStroke.Default.toPx() / 2f }
            )
        )
}

private fun tiltedBadgePath(
    width: Float,
    height: Float,
    inset: Float,
    offsetX: Float = 0f,
    offsetY: Float = 0f
): Path {
    val safeWidth = (width - offsetX).coerceAtLeast(1f)
    val safeHeight = (height - offsetY).coerceAtLeast(1f)
    val targetWidth = (safeWidth - inset * 2f).coerceAtLeast(1f)
    val targetHeight = (safeHeight - inset * 2f).coerceAtLeast(1f)
    val angle = (HomeBadgeFixtureTiltDegrees * PI / 180.0).toFloat()
    val cosAngle = cos(angle)
    val sinAngle = sin(angle)
    val denominator = cosAngle * cosAngle - abs(sinAngle) * abs(sinAngle)
    val rectWidth = ((targetWidth * cosAngle - targetHeight * abs(sinAngle)) / denominator).coerceAtLeast(1f)
    val rectHeight = ((targetHeight * cosAngle - targetWidth * abs(sinAngle)) / denominator).coerceAtLeast(1f)
    val centerX = offsetX + safeWidth / 2f
    val centerY = offsetY + safeHeight / 2f
    val halfWidth = rectWidth / 2f
    val halfHeight = rectHeight / 2f
    val corners =
        listOf(
            rotateBadgePoint(-halfWidth, -halfHeight, cosAngle, sinAngle, centerX, centerY),
            rotateBadgePoint(halfWidth, -halfHeight, cosAngle, sinAngle, centerX, centerY),
            rotateBadgePoint(halfWidth, halfHeight, cosAngle, sinAngle, centerX, centerY),
            rotateBadgePoint(-halfWidth, halfHeight, cosAngle, sinAngle, centerX, centerY)
        )
    return Path().apply {
        moveTo(corners[0].x, corners[0].y)
        lineTo(corners[1].x, corners[1].y)
        lineTo(corners[2].x, corners[2].y)
        lineTo(corners[3].x, corners[3].y)
        close()
    }
}

private fun starPath(width: Float, height: Float, inset: Float): Path {
    val centerX = width / 2f
    val centerY = height / 2f
    val outerRadius = (min(width, height) / 2f - inset).coerceAtLeast(1f)
    val innerRadius = outerRadius * 0.45f
    return Path().apply {
        repeat(10) { index ->
            val radius = if (index % 2 == 0) outerRadius else innerRadius
            val angle = (-90.0 + index * 36.0) * PI / 180.0
            val x = centerX + (cos(angle).toFloat() * radius)
            val y = centerY + (sin(angle).toFloat() * radius)
            if (index == 0) {
                moveTo(x, y)
            } else {
                lineTo(x, y)
            }
        }
        close()
    }
}

private fun rotateBadgePoint(
    x: Float,
    y: Float,
    cosAngle: Float,
    sinAngle: Float,
    centerX: Float,
    centerY: Float
): BadgeFixturePoint =
    BadgeFixturePoint(
        x = centerX + x * cosAngle - y * sinAngle,
        y = centerY + x * sinAngle + y * cosAngle
    )

private data class BadgeFixturePoint(val x: Float, val y: Float)

private const val HomeBadgeFixtureTiltDegrees = -2f
private val HomeBadgeFixtureBadgeWidth = 176.dp
private val HomeBadgeFixtureBadgeHeight = 44.dp
private val HomeBadgeFixtureShadowOffset = 2.dp
private val HomeBadgeFixtureImageWidth = 178.dp
private val HomeBadgeFixtureImageHeight = 46.dp
private const val HomeBadgeFixtureServerName = "Jiachengs-MacBook"
private val HomeBadgeFixtureGuideBg = Color(0xFFF2F2F2)

@OptIn(InternalResourceApi::class)
private val HomeBadgeChromeImageResource by lazy(LazyThreadSafetyMode.NONE) {
    DrawableResource(ImageUri.commonAssets("home_badge/home_badge_chrome_4x.png").toUrl(""))
}
