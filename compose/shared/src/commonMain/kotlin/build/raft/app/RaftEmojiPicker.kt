package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One tab of the emoji picker. [icon] is the emoji shown on the tab itself, so the picker needs
 * no localized category names.
 */
internal data class RaftEmojiPickerCategory(
    val id: String,
    val icon: String,
    val emojis: List<String>
)

/**
 * A curated, offline emoji set for the Add Reaction picker. Web uses a full emoji-mart style
 * picker; mobile had none (the section dialog takes typed emoji), so this is a self-contained
 * grid of common single-codepoint and simple-sequence emoji that every platform font renders.
 * TODO(cmp-switch): search, skin tones, recents and the full Unicode set like web's picker.
 */
internal val RaftEmojiPickerCategories: List<RaftEmojiPickerCategory> =
    listOf(
        RaftEmojiPickerCategory(
            id = "smileys",
            icon = "😀",
            emojis =
                listOf(
                    "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊",
                    "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙", "😋", "😛", "😜", "🤪",
                    "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐", "😑", "😶", "😏",
                    "😒", "🙄", "😬", "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕",
                    "🤢", "🤮", "🤧", "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "😎", "🤓",
                    "🧐", "😕", "😟", "🙁", "😮", "😯", "😲", "😳", "🥺", "😦", "😧", "😨",
                    "😰", "😥", "😢", "😭", "😱", "😖", "😣", "😞", "😓", "😩", "😫", "🥱",
                    "😤", "😡", "😠", "🤬", "😈", "👿", "💀", "💩", "🤡", "👻", "👽", "🤖"
                )
        ),
        RaftEmojiPickerCategory(
            id = "people",
            icon = "👍",
            emojis =
                listOf(
                    "👍", "👎", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉",
                    "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "👏", "🙌", "👐", "🤲",
                    "🤝", "🙏", "✍️", "💪", "🦾", "🧠", "👀", "👁️", "👂", "👃", "👄", "🫶",
                    "👶", "🧒", "👦", "👧", "🧑", "👨", "👩", "🧓", "👴", "👵", "🙋", "🙇",
                    "🤦", "🤷", "🙆", "🙅", "💁", "🧑‍💻", "👨‍💻", "👩‍💻", "🕵️", "👷", "🥷", "🦸"
                )
        ),
        RaftEmojiPickerCategory(
            id = "nature",
            icon = "🐶",
            emojis =
                listOf(
                    "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮",
                    "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐔", "🐧", "🐦", "🐤", "🦆", "🦅",
                    "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🐝", "🐛", "🦋", "🐌", "🐞", "🐜",
                    "🐢", "🐍", "🦖", "🐙", "🦑", "🦀", "🐠", "🐟", "🐬", "🐳", "🦈", "🐊",
                    "🌵", "🎄", "🌲", "🌳", "🌴", "🌱", "🌿", "🍀", "🍁", "🍂", "🌷", "🌹",
                    "🌻", "🌼", "🌸", "💐", "🍄", "🌍", "🌙", "⭐", "🌟", "✨", "⚡", "🔥",
                    "🌈", "☀️", "⛅", "☁️", "🌧️", "⛈️", "❄️", "☃️", "💧", "🌊"
                )
        ),
        RaftEmojiPickerCategory(
            id = "food",
            icon = "🍎",
            emojis =
                listOf(
                    "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍒", "🍑", "🥭",
                    "🍍", "🥥", "🥝", "🍅", "🥑", "🥦", "🌽", "🥕", "🧄", "🧅", "🥔", "🍞",
                    "🥐", "🥖", "🧀", "🥚", "🍳", "🥞", "🥓", "🍗", "🍖", "🌭", "🍔", "🍟",
                    "🍕", "🌮", "🌯", "🥗", "🍝", "🍜", "🍲", "🍛", "🍣", "🍱", "🥟", "🍤",
                    "🍙", "🍚", "🍦", "🍩", "🍪", "🎂", "🍰", "🧁", "🍫", "🍬", "🍭", "🍿",
                    "☕", "🍵", "🧋", "🥤", "🍺", "🍻", "🥂", "🍷", "🍸", "🍹", "🧉", "🧊"
                )
        ),
        RaftEmojiPickerCategory(
            id = "activities",
            icon = "⚽",
            emojis =
                listOf(
                    "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏉", "🎱", "🏓", "🏸", "🥊", "⛳",
                    "🏹", "🎣", "🥅", "⛸️", "🎿", "🏂", "🏋️", "🚴", "🏆", "🥇", "🥈", "🥉",
                    "🏅", "🎖️", "🎗️", "🎫", "🎟️", "🎪", "🎭", "🎨", "🎬", "🎤", "🎧", "🎼",
                    "🎹", "🥁", "🎷", "🎺", "🎸", "🎻", "🎲", "♟️", "🎯", "🎳", "🎮", "🧩",
                    "🎉", "🎊", "🎈", "🎁", "🎀", "🪄"
                )
        ),
        RaftEmojiPickerCategory(
            id = "travel",
            icon = "🚀",
            emojis =
                listOf(
                    "🚗", "🚕", "🚙", "🚌", "🏎️", "🚓", "🚑", "🚒", "🚚", "🚜", "🛵", "🚲",
                    "🛴", "🚨", "🚦", "🚧", "⚓", "⛵", "🚤", "🛳️", "🚢", "✈️", "🛫", "🛬",
                    "🚁", "🚀", "🛸", "🛰️", "🚉", "🚄", "🚅", "🚇", "🗺️", "🗽", "🗼", "🏰",
                    "🏯", "🏟️", "🎡", "🎢", "⛲", "🏖️", "🏝️", "🏜️", "🌋", "⛰️", "🏔️", "🏕️",
                    "🏠", "🏡", "🏢", "🏬", "🏭", "🏥", "🏦", "🏫", "⛪", "🕌", "🌃", "🌉"
                )
        ),
        RaftEmojiPickerCategory(
            id = "objects",
            icon = "💡",
            emojis =
                listOf(
                    "⌚", "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "💾", "💿", "📷", "📹", "🎥",
                    "📞", "☎️", "📺", "📻", "⏰", "⏳", "⌛", "🔋", "🔌", "💡", "🔦", "🕯️",
                    "💸", "💵", "💰", "💳", "💎", "⚖️", "🧰", "🔧", "🔨", "⚒️", "🛠️", "⚙️",
                    "🧱", "🔩", "🧲", "🔫", "💣", "🔪", "🛡️", "🔮", "🧿", "💊", "💉", "🧬",
                    "🔬", "🔭", "🧹", "🧺", "🧻", "🚽", "🛁", "🔑", "🗝️", "🚪", "🛋️", "🛏️",
                    "📦", "📫", "📮", "📝", "📁", "📂", "📅", "📆", "📌", "📍", "📎", "🖇️",
                    "📏", "📐", "✂️", "🗑️", "🔒", "🔓", "📚", "📖", "🔖", "🏷️", "📊", "📈",
                    "📉", "🧾", "✉️", "📧", "📣", "📢", "🔔", "🔕"
                )
        ),
        RaftEmojiPickerCategory(
            id = "symbols",
            icon = "❤️",
            emojis =
                listOf(
                    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕",
                    "💞", "💓", "💗", "💖", "💘", "💝", "💯", "💢", "💥", "💫", "💦", "💨",
                    "💬", "💭", "💤", "✅", "☑️", "✔️", "❌", "❎", "➕", "➖", "➗", "✖️",
                    "❓", "❔", "❗", "❕", "‼️", "⁉️", "⚠️", "🚫", "⛔", "🔴", "🟠", "🟡",
                    "🟢", "🔵", "🟣", "⚫", "⚪", "🟤", "🔺", "🔻", "🔶", "🔷", "⬆️", "⬇️",
                    "⬅️", "➡️", "↩️", "↪️", "🔄", "🔁", "🔀", "▶️", "⏸️", "⏹️", "⏺️", "⏭️",
                    "🆗", "🆕", "🆒", "🆓", "🆘", "🔝", "🔜", "♻️", "⚜️", "🔰", "©️", "®️",
                    "™️", "#️⃣", "0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "🏁", "🚩", "🏳️", "🏴"
                )
        )
    )

private val RaftEmojiPickerCellSize = 40.dp
private val RaftEmojiPickerTabSize = 34.dp
private val RaftEmojiPickerGridHeight = 280.dp

/**
 * Full emoji picker for Add Reaction: category tabs over a scrollable grid. Selecting an emoji
 * reports it through [onSelect]; the caller closes the dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RaftEmojiPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    categories: List<RaftEmojiPickerCategory> = RaftEmojiPickerCategories
) {
    var selectedCategoryId by remember { mutableStateOf(categories.firstOrNull()?.id) }
    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId } ?: categories.firstOrNull()
    RaftUnifiedDialog(
        title = title,
        onDismiss = onDismiss,
        contentSpacing = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { category ->
                val selected = category.id == selectedCategory?.id
                Box(
                    modifier =
                        Modifier
                            .size(RaftEmojiPickerTabSize)
                            .background(
                                if (selected) RaftColor.FillPressed else Color.Transparent,
                                RoundedCornerShape(RaftAppRadius.Control)
                            )
                            .clickable { selectedCategoryId = category.id }
                            .semantics { contentDescription = category.id },
                    contentAlignment = Alignment.Center
                ) {
                    RaftEmojiPickerGlyph(category.icon, fontSize = 18)
                }
            }
        }
        // Keyed by category so switching tabs starts the grid from the top.
        val scrollState = remember(selectedCategory?.id) { ScrollState(initial = 0) }
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftEmojiPickerGridHeight)
                    .verticalScroll(scrollState),
            horizontalArrangement = Arrangement.Start
        ) {
            selectedCategory?.emojis?.forEach { emoji ->
                Box(
                    modifier =
                        Modifier
                            .size(RaftEmojiPickerCellSize)
                            .clickable { onSelect(emoji) }
                            .semantics { contentDescription = emoji },
                    contentAlignment = Alignment.Center
                ) {
                    RaftEmojiPickerGlyph(emoji, fontSize = 24)
                }
            }
        }
    }
}

@Composable
private fun RaftEmojiPickerGlyph(emoji: String, fontSize: Int) {
    Text(
        text = emoji,
        fontSize = fontSize.sp,
        lineHeight = (fontSize + 4).sp,
        textAlign = TextAlign.Center,
        maxLines = 1
    )
}
