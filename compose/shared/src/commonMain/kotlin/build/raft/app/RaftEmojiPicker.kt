package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.emoji.RaftEmoji
import build.raft.app.emoji.RaftEmojiCatalog
import build.raft.app.emoji.RaftEmojiCategory
import build.raft.app.emoji.RaftEmojiPickerPreferences
import build.raft.app.emoji.RaftEmojiSkinTone
import build.raft.app.emoji.raftEmojiFrequentlyUsed
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldClearAction
import build.raft.designsystem.components.raftPointerHover
import build.raft.designsystem.components.raftStateOverlay
import build.raft.designsystem.components.rememberRaftStateOverlay
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * Whose picker choices (skin tone, frequently used emoji) the emoji picker reads and writes: the
 * signed-in owner key ([build.raft.app.data.messageBodyFontSizeOwnerKey], environment and account).
 * The thread provides it; without one the choices last as long as the picker.
 */
internal val LocalRaftEmojiPickerOwnerKey = compositionLocalOf<String?> { null }

/** The picker's stored choices for the owner in [LocalRaftEmojiPickerOwnerKey]. */
@Composable
internal fun rememberRaftEmojiPickerPreferences(): RaftEmojiPickerPreferences {
    val ownerKey = LocalRaftEmojiPickerOwnerKey.current
    return remember(ownerKey) {
        RaftEmojiPickerPreferences(store = ownerKey?.let { raftSharedPreferenceStore() }, ownerKey = ownerKey)
    }
}

private val RaftEmojiPickerCellSize = 40.dp
private val RaftEmojiPickerTabHeight = 34.dp
private val RaftEmojiPickerGridHeight = 280.dp
private val RaftEmojiPickerToneButtonSize = 44.dp

/** A row of the grid: "Frequently used" or one category. [key] keeps grid item keys unique. */
internal class RaftEmojiPickerSection(
    val key: String,
    val category: RaftEmojiCategory?,
    val icon: String,
    val emojis: List<RaftEmoji>,
)

/** The tab icon of each category (an emoji, as before), and of "Frequently used". */
private const val RaftEmojiPickerFrequentIcon = "🕘"

private fun RaftEmojiCategory.tabIcon(): String =
    when (this) {
        RaftEmojiCategory.SmileysPeople -> "😀"
        RaftEmojiCategory.AnimalsNature -> "🐶"
        RaftEmojiCategory.FoodDrink -> "🍎"
        RaftEmojiCategory.TravelPlaces -> "🚀"
        RaftEmojiCategory.Activities -> "⚽"
        RaftEmojiCategory.Objects -> "💡"
        RaftEmojiCategory.Symbols -> "❤️"
        RaftEmojiCategory.Flags -> "🏳️"
    }

/** "Frequently used" first when there is any, then every category in the picker's order. */
internal fun raftEmojiPickerSections(catalog: RaftEmojiCatalog, frequentlyUsed: List<RaftEmoji>): List<RaftEmojiPickerSection> =
    buildList {
        if (frequentlyUsed.isNotEmpty()) {
            add(RaftEmojiPickerSection("frequent", null, RaftEmojiPickerFrequentIcon, frequentlyUsed))
        }
        RaftEmojiCategory.entries.forEach { category ->
            val emojis = catalog.byCategory[category].orEmpty()
            if (emojis.isNotEmpty()) add(RaftEmojiPickerSection(category.id, category, category.tabIcon(), emojis))
        }
    }

/** The grid index of each section's header: a header, then its emoji, section after section. */
internal fun raftEmojiPickerHeaderIndices(sections: List<RaftEmojiPickerSection>): List<Int> {
    var next = 0
    return sections.map { section ->
        val header = next
        next += 1 + section.emojis.size
        header
    }
}

/** The section the grid shows at [firstVisibleIndex]: the last one whose header is at or above it. */
internal fun raftEmojiPickerSectionAt(headerIndices: List<Int>, firstVisibleIndex: Int): Int =
    headerIndices.indexOfLast { it <= firstVisibleIndex }.coerceAtLeast(0)

/**
 * The Add Reaction emoji picker, like Web's (emoji-picker-react): the whole emoji set in its
 * categories, a search field, a skin tone and a "Frequently used" row kept per user. Selecting
 * an emoji reports it through [onSelect] as text in the chosen skin tone; the caller closes the
 * dialog.
 *
 * [removeLabel] / [onRemove]: a full-width button above the picker that clears the current
 * emoji (web `SidebarSectionEmojiPicker`'s "Remove Emoji" row); pass them only when there is one.
 */
@Composable
internal fun RaftEmojiPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    // Read here, not in the dialog content: the dialog host composes that outside the page.
    preferences: RaftEmojiPickerPreferences = rememberRaftEmojiPickerPreferences(),
    catalog: RaftEmojiCatalog = RaftEmojiCatalog.Default,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    RaftUnifiedDialog(
        title = title,
        onDismiss = onDismiss,
        contentSpacing = 12.dp,
        keyboardAvoidanceEnabled = true,
    ) {
        if (removeLabel != null && onRemove != null) {
            RaftButton(
                text = removeLabel,
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.X) },
            )
        }
        RaftEmojiPickerPanel(preferences = preferences, catalog = catalog, onSelect = onSelect)
    }
}

/** The picker itself: search and skin tone, category tabs, and the emoji grid. */
@Composable
internal fun RaftEmojiPickerPanel(
    preferences: RaftEmojiPickerPreferences,
    catalog: RaftEmojiCatalog,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
    initialToneMenuOpen: Boolean = false,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var skinTone by remember(preferences) { mutableStateOf(preferences.readSkinTone()) }
    var toneMenuOpen by remember { mutableStateOf(initialToneMenuOpen) }
    // Read once per picker: a pick is stored but the row stays as it opened, so nothing moves
    // under the finger (emoji-picker-react does the same).
    val frequentlyUsed = remember(preferences, catalog) { raftEmojiFrequentlyUsed(catalog, preferences.readRecents()) }
    val sections = remember(catalog, frequentlyUsed) { raftEmojiPickerSections(catalog, frequentlyUsed) }
    val headerIndices = remember(sections) { raftEmojiPickerHeaderIndices(sections) }
    val searching = query.isNotBlank()
    val results = remember(catalog, query) { catalog.search(query) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // A tab tapped while searching: the search clears first, then the grid scrolls once it shows
    // the categories again.
    var pendingSection by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingSection, searching, headerIndices) {
        val section = pendingSection ?: return@LaunchedEffect
        if (searching) return@LaunchedEffect
        headerIndices.getOrNull(section)?.let { gridState.scrollToItem(it) }
        pendingSection = null
    }
    val currentSection by remember(headerIndices) {
        derivedStateOf { raftEmojiPickerSectionAt(headerIndices, gridState.firstVisibleItemIndex) }
    }
    val chineseNames = stringResource(Res.string.emoji_picker_names_language) == "zh"
    val labelOf: (RaftEmoji) -> String = { if (chineseNames) it.chineseName else it.englishName }
    val pick: (RaftEmoji) -> Unit = { emoji ->
        preferences.recordPick(emoji)
        onSelect(emoji.withSkinTone(skinTone))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftTextField(
                value = query,
                onValueChange = { next ->
                    query = next
                    scope.launch { gridState.scrollToItem(0) }
                },
                modifier = Modifier.weight(1f),
                placeholder = stringResource(Res.string.emoji_picker_search_placeholder),
                leading = { RaftSlotIcon(RaftLucideIconName.Search) },
                trailingAction =
                    if (query.isNotEmpty()) ({
                        RaftTextFieldClearAction(onClear = {
                            query = ""
                            scope.launch { gridState.scrollToItem(0) }
                        })
                    }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            RaftEmojiPickerSquare(
                text = raftEmojiSkinToneSample(catalog, skinTone),
                label = stringResource(Res.string.emoji_picker_skin_tone),
                selected = toneMenuOpen,
                size = RaftEmojiPickerToneButtonSize,
                fontSize = 22,
                onClick = { toneMenuOpen = !toneMenuOpen },
            )
        }
        if (toneMenuOpen) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RaftEmojiSkinTone.entries.forEach { tone ->
                    RaftEmojiPickerSquare(
                        text = raftEmojiSkinToneSample(catalog, tone),
                        label = stringResource(tone.labelResource()),
                        selected = tone == skinTone,
                        size = RaftEmojiPickerCellSize,
                        fontSize = 22,
                        onClick = {
                            skinTone = tone
                            preferences.writeSkinTone(tone)
                            toneMenuOpen = false
                        },
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            sections.forEachIndexed { index, section ->
                RaftEmojiPickerTab(
                    icon = section.icon,
                    label = stringResource(section.category.labelResource()),
                    selected = !searching && index == currentSection,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (searching) {
                            query = ""
                            pendingSection = index
                        } else {
                            scope.launch { gridState.scrollToItem(headerIndices[index]) }
                        }
                    },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(RaftEmojiPickerCellSize),
            state = gridState,
            modifier = Modifier.fillMaxWidth().height(RaftEmojiPickerGridHeight),
        ) {
            if (searching) {
                if (results.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                        RaftText(
                            text = stringResource(Res.string.emoji_picker_no_results),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foregroundHint,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    items(results, key = { "search:${it.unified}" }, contentType = { "emoji" }) { emoji ->
                        RaftEmojiPickerCell(emoji.withSkinTone(skinTone), labelOf(emoji)) { pick(emoji) }
                    }
                }
            } else {
                sections.forEach { section ->
                    item(key = "header:${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        RaftText(
                            text = stringResource(section.category.labelResource()),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
                            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
                            color = RaftTheme.colors.foregroundHint,
                            maxLines = 1,
                        )
                    }
                    items(section.emojis, key = { "${section.key}:${it.unified}" }, contentType = { "emoji" }) { emoji ->
                        RaftEmojiPickerCell(emoji.withSkinTone(skinTone), labelOf(emoji)) { pick(emoji) }
                    }
                }
            }
        }
    }
}

/** The raised hand in [tone]: what the skin tone button and its choices show. */
private fun raftEmojiSkinToneSample(catalog: RaftEmojiCatalog, tone: RaftEmojiSkinTone): String =
    catalog.byUnified("270b")?.withSkinTone(tone) ?: "✋"

private fun RaftEmojiCategory?.labelResource(): StringResource =
    when (this) {
        null -> Res.string.emoji_picker_frequently_used
        RaftEmojiCategory.SmileysPeople -> Res.string.emoji_picker_category_smileys_people
        RaftEmojiCategory.AnimalsNature -> Res.string.emoji_picker_category_animals_nature
        RaftEmojiCategory.FoodDrink -> Res.string.emoji_picker_category_food_drink
        RaftEmojiCategory.TravelPlaces -> Res.string.emoji_picker_category_travel_places
        RaftEmojiCategory.Activities -> Res.string.emoji_picker_category_activities
        RaftEmojiCategory.Objects -> Res.string.emoji_picker_category_objects
        RaftEmojiCategory.Symbols -> Res.string.emoji_picker_category_symbols
        RaftEmojiCategory.Flags -> Res.string.emoji_picker_category_flags
    }

private fun RaftEmojiSkinTone.labelResource(): StringResource =
    when (this) {
        RaftEmojiSkinTone.Neutral -> Res.string.emoji_picker_skin_tone_neutral
        RaftEmojiSkinTone.Light -> Res.string.emoji_picker_skin_tone_light
        RaftEmojiSkinTone.MediumLight -> Res.string.emoji_picker_skin_tone_medium_light
        RaftEmojiSkinTone.Medium -> Res.string.emoji_picker_skin_tone_medium
        RaftEmojiSkinTone.MediumDark -> Res.string.emoji_picker_skin_tone_medium_dark
        RaftEmojiSkinTone.Dark -> Res.string.emoji_picker_skin_tone_dark
    }

/** One emoji of the grid: brand-soft on hover and press, like Web's picker cells. */
@Composable
private fun RaftEmojiPickerCell(text: String, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val overlay = rememberRaftStateOverlay(source, RaftHoverTier.Highlight)
    Box(
        modifier =
            Modifier
                .size(RaftEmojiPickerCellSize)
                .raftPointerHover(source)
                .raftStateOverlay(overlay, RaftRadius.Control)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                },
        contentAlignment = Alignment.Center,
    ) {
        RaftEmojiPickerGlyph(text, fontSize = 24)
    }
}

/** A square button with an emoji: the skin tone button and its choices. */
@Composable
private fun RaftEmojiPickerSquare(
    text: String,
    label: String,
    selected: Boolean,
    size: Dp,
    fontSize: Int,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val overlay = rememberRaftStateOverlay(source, RaftHoverTier.Medium, selected = selected)
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        modifier =
            Modifier
                .size(size)
                .background(if (selected) RaftTheme.colors.brandSoft else Color.Transparent, shape)
                .raftPointerHover(source)
                .raftStateOverlay(overlay, shape)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    this.selected = selected
                },
        contentAlignment = Alignment.Center,
    ) {
        RaftEmojiPickerGlyph(text, fontSize = fontSize)
    }
}

/** A category tab: its emoji, faded unless it is the category in view (Web greys its icons). */
@Composable
private fun RaftEmojiPickerTab(
    icon: String,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val overlay = rememberRaftStateOverlay(source, RaftHoverTier.Medium, selected = selected)
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        modifier =
            modifier
                .height(RaftEmojiPickerTabHeight)
                .background(if (selected) RaftTheme.colors.brandSoft else Color.Transparent, shape)
                .raftPointerHover(source)
                .raftStateOverlay(overlay, shape)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .semantics {
                    contentDescription = label
                    role = Role.Tab
                    this.selected = selected
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.alpha(if (selected) 1f else 0.6f)) {
            RaftEmojiPickerGlyph(icon, fontSize = 18)
        }
    }
}

@Composable
private fun RaftEmojiPickerGlyph(emoji: String, fontSize: Int) {
    RaftText(
        text = emoji,
        style = RaftTheme.typography.body.copy(fontSize = fontSize.sp, lineHeight = (fontSize + 4).sp),
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}
