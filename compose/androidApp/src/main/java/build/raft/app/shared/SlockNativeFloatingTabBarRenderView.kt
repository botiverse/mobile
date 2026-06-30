package build.raft.app.shared

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.ui.icons.LucideIcons
import com.tencent.kuikly.core.render.android.expand.component.KRView
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

internal class SlockNativeFloatingTabBarRenderView(context: Context) : KRView(context) {
    private val composeView = ComposeView(context)
    private var keysText: String = ""
    private var labelsText: String = ""
    private var iconsText: String = ""
    private var enabledText: String = ""
    private var selectedKey: String = ""
    private var selectCallback: KuiklyRenderCallback? = null
    private var renderedItems by mutableStateOf<List<TabItem>>(emptyList())
    private var renderedSelectedKey by mutableStateOf("")

    init {
        isClickable = true
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        composeView.setContent {
            FloatingTabBar(
                    items = renderedItems,
                    selectedKey = renderedSelectedKey,
                    onSelect = { key, index ->
                        selectedKey = key
                        renderedSelectedKey = key
                        selectCallback?.invoke(mapOf("key" to key, "index" to index))
                    }
            )
        }
        updateItems()
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        when (propKey) {
            PROP_KEYS -> keysText = propValue.toString()
            PROP_LABELS -> labelsText = propValue.toString()
            PROP_ICONS -> iconsText = propValue.toString()
            PROP_ENABLED -> enabledText = propValue.toString()
            PROP_SELECTED_KEY -> selectedKey = propValue.toString()
            PROP_BOTTOM_SAFE_AREA -> Unit
            PROP_SELECT -> selectCallback = propValue as? KuiklyRenderCallback
            else -> return super.setProp(propKey, propValue)
        }
        updateItems()
        return true
    }

    override fun resetProp(propKey: String): Boolean {
        when (propKey) {
            PROP_KEYS -> keysText = ""
            PROP_LABELS -> labelsText = ""
            PROP_ICONS -> iconsText = ""
            PROP_ENABLED -> enabledText = ""
            PROP_SELECTED_KEY -> selectedKey = ""
            PROP_BOTTOM_SAFE_AREA -> Unit
            PROP_SELECT -> selectCallback = null
            else -> return super.resetProp(propKey)
        }
        updateItems()
        return true
    }

    override fun onDestroy() {
        composeView.disposeComposition()
        super.onDestroy()
    }

    private fun updateItems() {
        val keys = keysText.splitPayload()
        val labels = labelsText.splitPayload()
        val icons = iconsText.splitPayload()
        val enabled = enabledText.splitPayload()
        renderedItems =
                keys.mapIndexedNotNull { index, key ->
                    if (key.isBlank()) {
                        null
                    } else {
                        TabItem(
                                key = key,
                                label = labels.getOrNull(index)?.ifBlank { key } ?: key,
                                iconName = icons.getOrNull(index).orEmpty(),
                                enabled = enabled.getOrNull(index) != "0"
                        )
                    }
                }
        renderedSelectedKey = selectedKey
    }

    private fun String.splitPayload(): List<String> =
            if (isEmpty()) emptyList() else split(SEPARATOR)

    @Composable
    private fun FloatingTabBar(
            items: List<TabItem>,
            selectedKey: String,
            onSelect: (String, Int) -> Unit
    ) {
        Row(
                modifier =
                        Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 18.dp, vertical = 0.dp)
                                .shadow(18.dp, RoundedCornerShape(28.dp), clip = false)
                                .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(28.dp))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val selected = item.key == selectedKey
                Column(
                        modifier =
                                Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(
                                                if (selected) Color(0x33FFD440) else Color.Transparent,
                                                RoundedCornerShape(16.dp)
                                        )
                                        .clickable(enabled = item.enabled) { onSelect(item.key, index) }
                                        .padding(horizontal = 2.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                ) {
                    val vector = LucideIcons.byName(item.iconName)
                    if (vector != null) {
                        Icon(
                                imageVector = vector,
                                contentDescription = null,
                                tint = if (item.enabled) Color.Black.copy(alpha = if (selected) 1f else 0.45f) else Color.Black.copy(alpha = 0.26f),
                                modifier = Modifier.size(17.dp)
                        )
                    }
                    Text(
                            text = item.label.uppercase(),
                            color = if (item.enabled) Color.Black.copy(alpha = if (selected) 1f else 0.45f) else Color.Black.copy(alpha = 0.26f),
                            fontSize = 9.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    private data class TabItem(
            val key: String,
            val label: String,
            val iconName: String,
            val enabled: Boolean
    )

    companion object {
        const val VIEW_NAME = "SlockNativeFloatingTabBarView"
        private const val PROP_KEYS = "keysText"
        private const val PROP_LABELS = "labelsText"
        private const val PROP_ICONS = "iconsText"
        private const val PROP_ENABLED = "enabledText"
        private const val PROP_SELECTED_KEY = "selectedKey"
        private const val PROP_BOTTOM_SAFE_AREA = "bottomSafeArea"
        private const val PROP_SELECT = "select"
        private const val SEPARATOR = "\u001F"
    }
}
