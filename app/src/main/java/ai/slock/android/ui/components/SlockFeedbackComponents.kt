package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

@Composable
fun SlockEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite, androidx.compose.foundation.shape.RoundedCornerShape(SlockRadius.Xs))
            .drawBehind {
                drawRoundRect(
                    color = SlockColor.TextMutedGray,
                    style = Stroke(
                        width = SlockStroke.Thin.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(SlockRadius.Xs.toPx())
                )
            }
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = title,
            style = SlockType.Button,
            color = SlockColor.TextBlack
        )
        if (detail != null) {
            Text(
                text = detail,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
        if (action != null) {
            action()
        }
    }
}

@Composable
fun SlockErrorState(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    retryLabel: String = "Retry",
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite, androidx.compose.foundation.shape.RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, androidx.compose.foundation.shape.RoundedCornerShape(SlockRadius.Xs))
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = title,
            style = SlockType.Button,
            color = SlockColor.AccentRed
        )
        if (detail != null) {
            Text(
                text = detail,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
        if (onRetry != null) {
            SlockButton(
                text = retryLabel,
                onClick = onRetry,
                variant = SlockButtonVariant.Yellow
            )
        }
    }
}
