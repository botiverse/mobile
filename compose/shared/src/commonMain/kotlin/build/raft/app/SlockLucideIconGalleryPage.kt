package build.raft.app

import build.raft.app.base.BasePager
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Page

const val SLOCK_LUCIDE_ICON_GALLERY_PAGE = "compose_icon_gallery"

@Page(SLOCK_LUCIDE_ICON_GALLERY_PAGE, supportInLocal = true)
internal class SlockLucideIconGalleryPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockLucideIconGalleryScreen() }
    }
}

@Composable
private fun SlockLucideIconGalleryScreen() {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    val names = SlockLucideIconCompareSet.Names
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceCream)
            .padding(start = 16.dp, top = (statusBarHeight + 16).dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Lucide Icons",
                    style = SlockType.Input.copy(fontSize = 22.sp, lineHeight = 28.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack
                )
                Text(
                    text = "${names.size} icons · generated OHOS paths",
                    style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
        }
        items(names.size, key = { names[it] }) { index ->
            IconCompareRow(names[index])
        }
    }
}

@Composable
private fun IconCompareRow(name: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = SlockType.Body.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.15f)
        )
        IconGalleryTile(label = "ICON", modifier = Modifier.weight(1f)) {
            SlockLucideIcon(name = name, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun IconGalleryTile(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
        Text(
            text = label,
            style = SlockType.Body.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
