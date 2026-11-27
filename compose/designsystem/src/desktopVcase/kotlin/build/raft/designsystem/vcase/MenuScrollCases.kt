package build.raft.designsystem.vcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuLabel
import build.raft.designsystem.components.RaftMenuSurfaceFrame
import dev.vcase.VCase

/**
 * raft-ui 0.106.0 "Long menus scroll inside the room they have": a menu taller than its cap
 * scrolls its rows, with the 4dp padding on the scrolled rows and the cut edges faded over up to
 * 24dp. At the top only the bottom edge fades; scrolled, both do. A menu that fits
 * (`ds.menu.gallery`) draws as before.
 */
@VCase("ds.menu.long-scroll")
@Composable
fun MenuLongScrollGallery() {
    val rows = listOf("General", "Design", "Engineering", "Launch", "Marketing", "Mobile", "Research", "Support", "Visual Testing", "Web")
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Top) {
        for ((label, scrolled) in listOf("At the top: the bottom edge fades" to 0, "Scrolled: both edges fade" to 120)) {
            Column(Modifier.width(260.dp).padding(bottom = 24.dp)) {
                Caption(label, Modifier.padding(bottom = 8.dp))
                RaftMenuSurfaceFrame(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    minWidth = 240.dp,
                    width = null,
                    header = null,
                    scroll = rememberScrollState(scrolled),
                ) {
                    RaftMenuLabel("Move to section")
                    rows.forEachIndexed { i, row -> RaftMenuItem(row, {}, checked = i == 3) }
                }
            }
        }
    }
}
