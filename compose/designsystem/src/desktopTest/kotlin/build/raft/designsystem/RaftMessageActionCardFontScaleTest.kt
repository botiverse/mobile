package build.raft.designsystem

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftMessageActionCard
import build.raft.designsystem.components.RaftMessageActionCardHeaderDescription
import build.raft.designsystem.components.RaftMessageActionCardStatusTone
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * The header's offsets centre a glyph or a status on a text line: line height minus a fixed size.
 * At iOS's smallest text sizes the line is shorter than the 16dp check glyph, and a negative
 * padding threw `Padding must be non-negative` (Hands b1c4b072, 1.17.0 on iOS at text size XS).
 */
@OptIn(ExperimentalTestApi::class)
class RaftMessageActionCardFontScaleTest {
    @Test
    fun doneCardComposesAtSmallAndLargeTextSizes() {
        for (fontScale in listOf(0.75f, 0.8f, 0.85f, 1f, 1.5f, 2f)) {
            runComposeUiTest {
                setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                            RaftMessageActionCard(
                                title = "Create channel #release-review",
                                modifier = Modifier.width(360.dp),
                                description = { RaftMessageActionCardHeaderDescription("Created by Ada", check = true) },
                                status = "Done",
                                statusTone = RaftMessageActionCardStatusTone.Success,
                                expanded = false,
                            ) {}
                        }
                    }
                }
                onNodeWithText("Created by Ada").assertExists()
            }
        }
    }
}
