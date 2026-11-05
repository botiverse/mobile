package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ArtifactKind
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoicePickerSheetSurface
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** THROWAWAY mock (agent/lagoon/artifact-one-bar-mock, never pushed): Artifact viewer one-bar proposal. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h760dp-xxhdpi")
class ArtifactOneBarMockScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun before() = capture("mock.artbar.before", RaftAppAppearance.Light, MockState.Before)
    @Test fun after() = capture("mock.artbar.after", RaftAppAppearance.Light, MockState.After)
    @Test fun afterMenu() = capture("mock.artbar.after-menu", RaftAppAppearance.Light, MockState.AfterMenu)
    @Test fun afterVersions() = capture("mock.artbar.after-versions", RaftAppAppearance.Light, MockState.AfterVersions)
    @Test fun beforeDark() = capture("mock.artbar.before.dark", RaftAppAppearance.Dark, MockState.Before)
    @Test fun afterDark() = capture("mock.artbar.after.dark", RaftAppAppearance.Dark, MockState.After)
    @Test fun afterMenuDark() = capture("mock.artbar.after-menu.dark", RaftAppAppearance.Dark, MockState.AfterMenu)
    @Test fun afterVersionsDark() = capture("mock.artbar.after-versions.dark", RaftAppAppearance.Dark, MockState.AfterVersions)

    private fun capture(name: String, appearance: RaftAppAppearance, state: MockState) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/artbar-mock", activities = activities) {
            RaftDesignSystemTheme { MockScreen(state) }
        }
}

private enum class MockState { Before, After, AfterMenu, AfterVersions }

private const val Title = "q4-launch-plan.html"

@Composable
private fun MockScreen(state: MockState) {
    if (state == MockState.Before) {
        // The real app frame (#4068): back, title, version, open in browser; the web toolbar below it.
        RaftArtifactViewerFrame(
            title = Title,
            subtitle = "v3",
            kind = ArtifactKind.Html,
            onBack = {},
            onOpenInBrowser = {},
            topSafeAreaOverride = 0.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                WebToolbarStandIn()
                ArtifactBody()
            }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        Column(Modifier.fillMaxSize()) {
            OneBarHeader()
            ArtifactBody()
        }
        when (state) {
            MockState.AfterMenu ->
                Box(Modifier.fillMaxSize().padding(top = 56.dp + 4.dp, end = 8.dp), contentAlignment = Alignment.TopEnd) {
                    RaftMenuSurface(minWidth = 220.dp) {
                        RaftMenuItem("Copy Link", onClick = {}, leadingIcon = { RaftSlotIcon(RaftLucideIconName.Link) })
                        RaftMenuItem("Follow", onClick = {}, leadingIcon = { RaftSlotIcon(RaftLucideIconName.Bell) })
                        RaftMenuSeparator()
                        RaftMenuItem("Open in Browser", onClick = {}, leadingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowUpRight) })
                    }
                }
            MockState.AfterVersions ->
                Box(Modifier.fillMaxSize().background(RaftTheme.scrim), contentAlignment = Alignment.BottomCenter) {
                    RaftChoicePickerSheetSurface(
                        value = 3,
                        options =
                            listOf(
                                RaftComboboxOption(3, "v3", description = "Kirby · 2h ago", badge = "Latest"),
                                RaftComboboxOption(2, "v2", description = "Artea · Yesterday"),
                                RaftComboboxOption(1, "v1", description = "Kirby · Oct 28"),
                            ),
                        onPick = {},
                        title = "Version History",
                        emptyText = { "" },
                        searchable = false,
                        modifier = Modifier.height(290.dp),
                    )
                }
            else -> Unit
        }
    }
}

/** AFTER: the one native bar. Row: back, title / meta line, Comments (count), ⋮. */
@Composable
private fun OneBarHeader() {
    val colors = RaftTheme.colors
    RaftTopPageHeader(
        title = Title,
        onBack = {},
        backgroundColor = RaftColor.SurfaceWhite,
        topSafeAreaOverride = 0.dp,
        titleContent = {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(
                    Title,
                    style = RaftType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val caption = RaftTheme.typography.caption
                    val mono = RaftTheme.typography.monoMeta
                    RaftText("#q4-launch", style = caption, color = colors.foregroundHint, maxLines = 1)
                    RaftText("·", style = caption, color = colors.foregroundHint)
                    // The version: tap opens the native version list.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        RaftText("v3", style = mono.copy(fontWeight = FontWeight.Bold), color = colors.foregroundMuted)
                        RaftSlotIcon(RaftLucideIconName.ChevronDown, size = 12.dp, color = colors.foregroundMuted)
                    }
                    RaftText("·", style = caption, color = colors.foregroundHint)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RaftText("5", style = mono, color = colors.foregroundHint)
                        RaftText(" read", style = caption, color = colors.foregroundHint)
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RaftButton(
                    text = "3",
                    onClick = {},
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.MessageSquare) },
                )
                RaftIconButton(onClick = {}, tooltip = "More Actions", variant = RaftButtonVariant.Ghost, size = RaftIconButtonSize.Lg) {
                    RaftSlotIcon(RaftLucideIconName.EllipsisVertical)
                }
            }
        },
    )
}

/** BEFORE: a stand-in for the embedded web page's own toolbar row (host-shell PanelHeader). */
@Composable
private fun WebToolbarStandIn() {
    val colors = RaftTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.layerPanel)) {
        Row(
            Modifier.fillMaxWidth().height(55.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RaftBadge("q4-launch", appearance = RaftBadgeAppearance.Outline, icon = { RaftSlotIcon(RaftLucideIconName.Hash) })
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f).clipToBounds()) {
                    RaftTextLink("Original message", onClick = {}, style = RaftTheme.typography.caption, maxLines = 1)
                }
                RaftText("· 5 read", style = RaftTheme.typography.caption, color = colors.foregroundHint, maxLines = 1, softWrap = false)
            }
            RaftButton("3", onClick = {}, variant = RaftButtonVariant.Ghost, leadingIcon = { RaftSlotIcon(RaftLucideIconName.MessageSquare) })
            RaftButton("v3", onClick = {}, variant = RaftButtonVariant.Ghost, trailingIcon = { RaftSlotIcon(RaftLucideIconName.ChevronDown) })
            RaftIconButton(onClick = {}, tooltip = "More", variant = RaftButtonVariant.Ghost) {
                RaftSlotIcon(RaftLucideIconName.EllipsisVertical)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSeam))
    }
}

/** Placeholder artifact content. */
@Composable
private fun ArtifactBody() {
    val colors = RaftTheme.colors
    val type = RaftTheme.typography
    Column(
        Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RaftText("Q4 Launch Plan", style = type.title, color = colors.foreground)
        RaftText(
            "We ship the new onboarding to every workspace in three waves. Each wave opens once the previous one has held for a week without regressions.",
            style = type.body,
            color = colors.foregroundMuted,
        )
        RaftText("Milestones", style = type.headline, color = colors.foreground)
        listOf("Wave 1 — internal teams, Nov 4", "Wave 2 — 10% of workspaces, Nov 11", "Wave 3 — everyone, Nov 18").forEach {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).background(colors.foregroundHint, RoundedCornerShape(3.dp)))
                RaftText(it, style = type.body, color = colors.foreground)
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(150.dp)
                .background(colors.layerInset, RoundedCornerShape(8.dp))
                .border(1.dp, colors.lineHairline, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(40, 70, 55, 95, 120).forEach { h ->
                    Box(Modifier.width(26.dp).height(h.dp).background(colors.lineMuted, RoundedCornerShape(4.dp)))
                }
            }
        }
        RaftText(
            "Risks: the import step still times out on very large workspaces; the fix lands before wave 2.",
            style = type.body,
            color = colors.foregroundMuted,
        )
    }
}
