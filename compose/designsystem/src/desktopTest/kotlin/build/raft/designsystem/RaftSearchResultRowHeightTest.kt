package build.raft.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftSearchResultDescription
import build.raft.designsystem.components.RaftSearchResultIcon
import build.raft.designsystem.components.RaftSearchResultRow
import build.raft.designsystem.components.RaftSearchResultTitle
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * A search result row on a phone, by touch, is as tall as the web's (raft-ui 0.102.21
 * search-entity.recipe.mjs:7-9, `pointer-coarse:min-h-14 pointer-coarse:py-2`): 8 + 24 + 2 + 20 + 8
 * = 62dp for a title over its description, 56dp for a title alone.
 */
@OptIn(ExperimentalTestApi::class)
class RaftSearchResultRowHeightTest {
    @Test
    fun byTouchARowIsAsTallAsTheWebs() =
        runComposeUiTest {
            setContent {
                RaftTheme(
                    appearance = RaftAppearance.Light,
                    systemDark = false,
                    scale = RaftScale.Compact,
                    pointerInput = RaftPointerInput.Touch,
                ) {
                    Column(Modifier.width(390.dp)) {
                        RaftSearchResultRow(
                            onClick = {},
                            modifier = Modifier.testTag("described"),
                            leading = { RaftSearchResultIcon(RaftGlyph.Hash) },
                            description = { RaftSearchResultDescription("Channel") },
                        ) { RaftSearchResultTitle("design") }
                        RaftSearchResultRow(
                            onClick = {},
                            modifier = Modifier.testTag("title-only"),
                            leading = { RaftSearchResultIcon(RaftGlyph.Hash) },
                        ) { RaftSearchResultTitle("design") }
                    }
                }
            }
            onNodeWithTag("described").assertHeightIsEqualTo(62.dp)
            onNodeWithTag("title-only").assertHeightIsEqualTo(56.dp)
        }
}
