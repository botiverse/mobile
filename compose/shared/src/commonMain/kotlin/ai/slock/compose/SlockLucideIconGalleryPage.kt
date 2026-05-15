package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ComposeContainer
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
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page

const val SLOCK_LUCIDE_ICON_GALLERY_PAGE = "compose_icon_gallery"

@Page(SLOCK_LUCIDE_ICON_GALLERY_PAGE, supportInLocal = true)
internal class SlockLucideIconGalleryPage : ComposeContainer() {
    override fun willInit() {
        super.willInit()
        setContent { SlockLucideIconGalleryScreen() }
    }
}

@Composable
private fun SlockLucideIconGalleryScreen() {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    val rows = SlockLucideIcons.Names.chunked(4)
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
                    text = "${SlockLucideIcons.Names.size} icons",
                    style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
        }
        items(rows.size, key = { rows[it].joinToString("|") }) { rowIndex ->
            IconGalleryRow(rows[rowIndex])
        }
    }
}

@Composable
private fun IconGalleryRow(names: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(4) { index ->
            if (index < names.size) {
                IconGalleryTile(name = names[index], modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IconGalleryTile(name: String, modifier: Modifier = Modifier) {
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
            SlockLucideIcon(name = name, modifier = Modifier.fillMaxSize())
        }
        Text(
            text = name,
            style = SlockType.Body.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
