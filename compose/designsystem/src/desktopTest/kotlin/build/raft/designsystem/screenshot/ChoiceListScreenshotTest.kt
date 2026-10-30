package build.raft.designsystem.screenshot

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
import build.raft.designsystem.components.RaftChoiceListGroup
import build.raft.designsystem.components.RaftMultiChoiceList
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * [RaftChoiceList] (raft-ui ChoiceList, single; conventions in [ComponentScreenshotTest]):
 * three rows with two description lines (a used-up window in `warning-strong`), an info action
 * and a disabled row; eight rows with the search field, the frame scrolling past 288dp; a
 * search keeping the chosen row first above the matches; a search matching nothing else; and
 * no items at all. [RaftMultiChoiceList]: checkboxes under Agents / Humans headings with avatars
 * and a footer, a search keeping the chosen row first, nothing matching, and no items.
 */
class ChoiceListScreenshotTest {
    private fun capture(name: String, content: @Composable () -> Unit) =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, maxWidthDp = 1600, content = content)
        }

    @Composable
    private fun quota(usedUp: Boolean): AnnotatedString {
        val warning = RaftTheme.colors.warningStrong
        return buildAnnotatedString {
            append("Claude Max · ")
            if (usedUp) withStyle(SpanStyle(color = warning)) { append("5h used up, resets in 3 hours") } else append("5h 72% left")
            append(" · Week 40% left")
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
                        AnnotatedString("${index + 2} agents · 12.0 GB RAM free · 120.0 GB disk free")
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
    private fun Case(items: List<RaftChoiceListItem<String>>, value: String?, query: String = "", action: Boolean = true) {
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

    @Test
    fun choiceList() =
        capture("choice_list") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("3 rows: two lines, an action, one disabled")
                    Case(computers(3), value = "c0")
                    Caption("No items")
                    Case(emptyList(), value = null)
                }
                Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("8 rows: search, the frame scrolls past 288dp")
                    Case(computers(8), value = "c1")
                }
                Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("Searching \"tyz\": the chosen row stays first")
                    Case(computers(8), value = "c3", query = "tyz")
                    Caption("Nothing else matches")
                    Case(computers(8), value = "c3", query = "zzz", action = false)
                }
            }
        }

    @Composable
    private fun people(): List<RaftChoiceListItem<String>> =
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

    @Test
    fun choiceListMultiple() =
        capture("choice_list_multiple") {
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
                }
            }
        }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
