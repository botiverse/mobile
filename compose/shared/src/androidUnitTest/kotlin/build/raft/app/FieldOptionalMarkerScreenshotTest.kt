package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.components.RaftTextField
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * raft-ui 0.71 FieldLabel: the app's optional marker in Chinese and Japanese opens with a
 * full-width bracket (（可选）, （選填）, （任意）), which carries its own space, so it follows
 * the label with no space before it. A label alone and a field's label, in each locale. Reports
 * land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
private fun captureOptionalMarker(name: String, label: String, field: String, activities: RobolectricComposeActivityRule) =
    RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
        RaftDesignSystemTheme {
            Column(
                Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RaftFieldLabel(label, optional = true)
                RaftField(label = field, optional = true) { RaftTextField("", {}) }
            }
        }
    }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w390dp-h200dp-xxhdpi")
class FieldOptionalMarkerZhCnScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun optionalMarker() = captureOptionalMarker("field.optional-marker.zh-CN", "描述", "成员", activities)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rTW-w390dp-h200dp-xxhdpi")
class FieldOptionalMarkerZhTwScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun optionalMarker() = captureOptionalMarker("field.optional-marker.zh-TW", "描述", "成員", activities)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w390dp-h200dp-xxhdpi")
class FieldOptionalMarkerJaScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun optionalMarker() = captureOptionalMarker("field.optional-marker.ja", "説明", "メンバー", activities)
}
