package build.raft.designsystem.vcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftChoiceListGroup
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftChoiceListSearch
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftMultiChoiceList
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import dev.vcase.VCase

// [RaftChoiceList] (raft-ui ChoiceList, single) and [RaftMultiChoiceList], three 358dp columns
// side by side (wider than the 1200dp gallery bound: maxWidth = 1600).
// These replace ChoiceListScreenshotTest (choice_list, choice_list_grouped and
// choice_list_multiple, each _light/_dark).

/**
 * Three rows with two description lines (a used-up window in `warning-strong`), an info action
 * and a disabled row; eight rows with the search field, the frame scrolling past 288dp; a
 * search keeping the chosen row first above the matches; a search matching nothing else; and
 * no items at all.
 */
@VCase("ds.choice-list.gallery", maxWidth = 1600)
@Composable
fun ChoiceListGallery() {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("3 rows: two lines, an action, one disabled")
            SingleCase(computers(3), value = "c0")
            Caption("No items")
            SingleCase(emptyList(), value = null)
        }
        Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("8 rows: search, the frame scrolls past 288dp")
            SingleCase(computers(8), value = "c1")
        }
        Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("Searching \"tyz\": the chosen row stays first")
            SingleCase(computers(8), value = "c3", query = "tyz")
            Caption("Nothing else matches")
            SingleCase(computers(8), value = "c3", query = "zzz", action = false)
        }
    }
}

/** Single choice under group headings (Pi's Add Provider: an account, or an API key). */
@VCase("ds.choice-list.grouped", maxWidth = 1600)
@Composable
fun ChoiceListGrouped() {
    val items =
        listOf(
            RaftChoiceListItem("account:anthropic", "Anthropic (Claude Pro/Max)", listOf(AnnotatedString("Sign in, then paste a code")), group = "account"),
            RaftChoiceListItem("account:openai-codex", "ChatGPT Plus/Pro (Codex)", listOf(AnnotatedString("Sign in with a device code")), group = "account"),
            RaftChoiceListItem("key:deepseek", "DeepSeek", group = "key"),
            RaftChoiceListItem("key:amazon-bedrock", "Amazon Bedrock", listOf(AnnotatedString("Asks for more than a key: set it up on the Computer")), enabled = false, group = "key"),
        )
    Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RaftChoiceList(
            items = items,
            value = "account:openai-codex",
            onValueChange = {},
            searchPlaceholder = "Search providers",
            clearSearchLabel = "Clear search",
            noMatchLabel = "No matching providers",
            emptyLabel = "No providers",
            groups = listOf(RaftChoiceListGroup("account", "Sign in with an account"), RaftChoiceListGroup("key", "Use an API key")),
        )
    }
}

/**
 * Checkboxes under Agents / Humans headings with avatars and a footer, a search keeping the
 * chosen row first, nothing matching, and no items.
 */
@VCase("ds.choice-list.multiple", maxWidth = 1600)
@Composable
fun ChoiceListMultiple() {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("multiple: groups, media, footer; two chosen")
            MultiCase(values = setOf("agent:cindy", "human:designer"))
        }
        Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("Searching \"art\": the chosen row stays first in its group")
            MultiCase(values = setOf("human:designer"), query = "art")
            Caption("Nothing else matches")
            MultiCase(values = setOf("human:designer"), query = "zzz")
        }
        Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("No items")
            MultiCase(values = emptySet(), items = emptyList())
            Caption("A disabled row: its avatar fades with its text")
            MultiCase(values = emptySet(), items = people(disabled = "agent:cindy"))
        }
    }
}

/**
 * A question card's options in a form (web ActionCardChoiceField): single and multiple with
 * `search="auto"` as plain columns (no search field at 5 or fewer, no frame of their own to
 * scroll), and both invalid, their frames in `danger-strong`.
 */
@VCase("ds.choice-list.form", maxWidth = 1600)
@Composable
fun ChoiceListForm() {
    val answers =
        listOf(
            RaftChoiceListItem("utc", "UTC", listOf(AnnotatedString("All times in UTC."))),
            RaftChoiceListItem("local", "My time zone"),
            RaftChoiceListItem("both", "Both, side by side"),
            RaftChoiceListItem("other", "Other…"),
        )
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("Single, 4 options: no search")
            QuestionSingle(answers, value = "local")
            Caption("Single, invalid")
            QuestionSingle(answers, value = null, invalid = true)
        }
        Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("Multiple, search=Auto, plain column: no search")
            QuestionMultiple(answers, values = setOf("utc", "both"))
            Caption("Multiple, invalid")
            QuestionMultiple(answers, values = emptySet(), invalid = true)
        }
        Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("Multiple, search=Auto, 8 options: search, scrolls past 288dp")
            QuestionMultiple(computers(8), values = setOf("c1"))
            Caption("Invalid but disabled: disabled wins")
            QuestionMultiple(answers, values = emptySet(), invalid = true, enabled = false)
        }
    }
}

@Composable
private fun QuestionSingle(items: List<RaftChoiceListItem<String>>, value: String?, invalid: Boolean = false) {
    RaftChoiceList(
        items = items,
        value = value,
        onValueChange = {},
        searchPlaceholder = "Search",
        clearSearchLabel = "Clear search",
        noMatchLabel = "No matches",
        emptyLabel = "No options",
        onItemClick = {},
        invalid = invalid,
    )
}

@Composable
private fun QuestionMultiple(items: List<RaftChoiceListItem<String>>, values: Set<String>, invalid: Boolean = false, enabled: Boolean = true) {
    RaftMultiChoiceList(
        items = items,
        values = values,
        onValuesChange = {},
        query = "",
        onQueryChange = {},
        searchPlaceholder = "Search",
        clearSearchLabel = "Clear search",
        noMatchLabel = "No matches",
        emptyLabel = "No options",
        enabled = enabled,
        search = RaftChoiceListSearch.Auto,
        lazy = false,
        invalid = invalid,
    )
}

@Composable
private fun quota(usedUp: Boolean): AnnotatedString {
    val warning = RaftTheme.colors.warningStrong
    return buildAnnotatedString {
        append("Claude Max · ")
        if (usedUp) withStyle(SpanStyle(color = warning)) { append("5h used up, resets in 3 hours") } else append("5h 28% used")
        append(" · Week 60% used")
    }
}

@Composable
private fun computers(count: Int): List<RaftChoiceListItem<String>> {
    val names = listOf("Studio Mac", "Lab Box", "Build Box", "KopiC", "tyz mini", "xxair", "tyz-win", "Shared-EC2")
    return (0 until count).map { index ->
        RaftChoiceListItem(
            value = "c$index",
            label = names[index],
            descriptions =
                listOfNotNull(
                    if (index % 3 == 2) null else quota(usedUp = index == 1),
                    AnnotatedString("${index + 2} agents · RAM 63% used · Disk 76% used"),
                ),
            enabled = index != 2 || count > 3,
        )
    }
}

private val info: @Composable (RaftChoiceListItem<String>) -> Unit = { item ->
    RaftIconButton(onClick = {}, tooltip = "Details for ${item.label}", variant = RaftButtonVariant.Ghost) {
        RaftGlyphIcon(RaftGlyph.Info, size = 18.dp)
    }
}

@Composable
private fun SingleCase(items: List<RaftChoiceListItem<String>>, value: String?, query: String = "", action: Boolean = true) {
    RaftChoiceList(
        items = items,
        value = value,
        onValueChange = {},
        searchPlaceholder = "Search computers",
        clearSearchLabel = "Clear search",
        noMatchLabel = "No matches",
        emptyLabel = "No other computers available",
        initialQuery = query,
        action = if (action) info else null,
    )
}

@Composable
private fun people(disabled: String? = null): List<RaftChoiceListItem<String>> =
    listOf(
        Triple("agent:cindy", "Cindy", "Chief of Staff"),
        Triple("agent:ux", "Product UX Designer", "Turns product feedback, screenshots, and release notes into actionable UX specs."),
        Triple("human:artin", "artin", "Owner"),
        Triple("human:designer", "Designer", "Product design"),
    ).map { (value, name, description) ->
        val agent = value.startsWith("agent:")
        RaftChoiceListItem(
            value = value,
            label = name,
            descriptions = listOf(AnnotatedString(description)),
            searchText = "$name $description",
            enabled = value != disabled,
            group = if (agent) "agent" else "human",
            media = {
                RaftAvatar(
                    type = if (agent) RaftAvatarType.Agent else RaftAvatarType.Human,
                    size = RaftAvatarSize.Xs,
                    initials = name.take(1).uppercase(),
                )
            },
        )
    }

@Composable
private fun MultiCase(values: Set<String>, query: String = "", items: List<RaftChoiceListItem<String>> = people()) {
    RaftMultiChoiceList(
        items = items,
        values = values,
        onValuesChange = {},
        query = query,
        onQueryChange = {},
        searchPlaceholder = "Name",
        clearSearchLabel = "Clear search",
        noMatchLabel = "No members match \"$query\"",
        emptyLabel = "All members added",
        groups = listOf(RaftChoiceListGroup("agent", "Agents"), RaftChoiceListGroup("human", "Humans")),
        footer = { RaftText("Create Agent", style = RaftTheme.typography.callout, modifier = Modifier.width(300.dp)) },
    )
}
