package build.raft.app

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.Canvas
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
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.LayoutDirection
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

const val HOME_BADGE_VISUAL_FIXTURE_PAGE = "compose_home_badge_visual_fixture"

@Page(HOME_BADGE_VISUAL_FIXTURE_PAGE, supportInLocal = true)
internal class ComposeHomeBadgeVisualFixturePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeBadgeVisualFixtureScreen() }
    }
}

@Composable
internal fun HomeBadgeVisualFixtureScreen() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceCream)
                .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Home Badge Fixture",
            style = SlockType.PageTitle.copy(fontSize = 24.sp, lineHeight = 28.sp),
            color = SlockColor.TextBlack
        )
        Text(
            text = "Same size/background; compare diagonal edges, 2px border, and shadow.",
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = SlockColor.TextBlack.copy(alpha = 0.65f)
        )
        HomeBadgeFixtureRow(
            title = "React CSS reference",
            detail = "rotate(-2deg) + translateZ/will-change model",
            badge = { ReactCssModeledBadge(HomeBadgeFixtureServerName) }
        )
        HomeBadgeFixtureRow(
            title = "KMP current rotate",
            detail = "whole Kuikly layer rotate(-2f)",
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
    }
}

@Composable
private fun HomeBadgeFixtureRow(
    title: String,
    detail: String,
    badge: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().height(118.dp)) {
        Box(Modifier.matchParentSize().offset(x = 3.dp, y = 3.dp).background(SlockColor.ShadowBlack))
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = detail,
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
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
                .height(18.dp)
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
                        .height(if (index % 2 == 0) 12.dp else 6.dp)
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
private const val HomeBadgeFixtureServerName = "Jiachengs-MacBook"
private val HomeBadgeFixtureGuideBg = Color(0xFFF2F2F2)
