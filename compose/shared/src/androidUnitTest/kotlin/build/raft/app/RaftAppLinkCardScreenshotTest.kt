package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkInline
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardBadgeTone
import build.raft.app.data.CardDuration
import build.raft.app.data.CardStack
import build.raft.app.data.CardStackDirection
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardTextTone
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.data.RaftAppLinkCard
import build.raft.app.data.RaftAppLinkCardElement.Badge
import build.raft.app.data.RaftAppLinkCardElement.Stack
import build.raft.app.data.RaftAppLinkCardElement.Text
import build.raft.app.data.RaftAppLinkTextTone
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardDetail
import build.raft.designsystem.components.RaftPreviewCardDetailTone
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #proj-mobile task #34: App link item cards ([RaftAppLinkCardView]) as a phone shows them (the
 * window's width; 400dp, raft-ui `size="item"`, where there is room), light and dark: a Linear
 * issue (a column of a Badge, a default and a muted Text) with Open Link ↗ alone at the card's
 * end, a Stamp pull request with its id line, state chip and "·" details and the App's buttons
 * (raft-design #939: the ⋯ with Refresh and the links, then frameless Approve and the bordered
 * Merge at the end), a pull request with only its title and a disabled action whose reason is a
 * caption under the row, and a long title cut at 2 lines over a long App name and domain. The
 * footer has no App icon (raft-ui keeps it on the chip). Reports land in
 * `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1000dp-xxhdpi")
class RaftAppLinkCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun cardsLight() = capture("thread.app-link-card", RaftAppAppearance.Light) { Cards() }

    @Test
    fun cardsDark() = capture("thread.app-link-card.dark", RaftAppAppearance.Dark) { Cards() }

    /** A v2 card from the batched read (the web's `prCard`, read-only: no Button drawn) beside its v1 neighbour. */
    @Test
    fun cardV2Light() = capture("thread.app-link-card-v2", RaftAppAppearance.Light) { CardV2() }

    @Test
    fun cardV2Dark() = capture("thread.app-link-card-v2.dark", RaftAppAppearance.Dark) { CardV2() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(
                        Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { content() }
                }
            }
        }
    }
}

@Composable
private fun FixtureCard(
    url: String,
    appName: String,
    title: String,
    card: RaftAppLinkCard?,
    appButtons: List<RaftPreviewCardButton> = emptyList(),
    eyebrow: String? = null,
    details: List<RaftPreviewCardDetail> = emptyList(),
) {
    RaftAppLinkCardView(
        card = AppLinkResult.Card(url = url, app = AppLinkApp(appName, null), title = title, card = card),
        onAction = {},
        modifier = Modifier.fillMaxWidth().widthIn(max = RaftPreviewCardDefaults.ItemWidth),
        buttons = appButtons + raftAppLinkOpenLinkButton {},
        eyebrow = eyebrow,
        details = details,
    )
}

/**
 * The web's `prCard` (botiverse/slock 8de484350 `packages/web/tests/appLinkCardsBatchedRead.behavior.test.tsx`):
 * a wrapping row of three state Badges (drawn neutral whatever their tone, no "·" between chips)
 * then a Duration (here finished, so the golden holds still), a mono branch, a warning Text, and a
 * navigate Button that step 1 does not draw (Open Link ↗ stays alone at the end). The RelativeTime
 * is left out: its words move with the clock. A second row of plain details shows the "·".
 */
@Composable
private fun CardV2() {
    RaftAppLinkCardView(
        card =
            AppLinkResult.Card(
                url = "https://stamp.build/botiverse/slock/pull/12361",
                app = AppLinkApp("Stamp", null),
                title = "#12361 feat(web): an App link card's buttons in one row, the main action at its end",
                cardV2 =
                    RaftAppLinkCardV2(
                        root = "root",
                        elements =
                            mapOf(
                                "root" to CardStack(children = listOf("states", "people", "branch", "checks", "open")),
                                "states" to CardStack(CardStackDirection.Row, wrap = true, children = listOf("review", "ci", "conflict", "running")),
                                "review" to RaftLinkCardV2Element.Badge("In review", CardBadgeTone.Review),
                                "ci" to RaftLinkCardV2Element.Badge("CI running", CardBadgeTone.Progress),
                                "conflict" to RaftLinkCardV2Element.Badge("Conflicts", CardBadgeTone.Warning),
                                "running" to CardDuration("2026-10-23T09:58:55Z", "2026-10-23T10:00:00Z"),
                                "people" to CardStack(CardStackDirection.Row, children = listOf("author", "lines")),
                                "author" to RaftLinkCardV2Element.Text("AppFather", RaftLinkCardTextTone.Muted),
                                "lines" to RaftLinkCardV2Element.Text("+322 -94", RaftLinkCardTextTone.Muted),
                                "branch" to RaftLinkCardV2Element.Text("agent/link-cards-pr-d-read", mono = true),
                                "checks" to RaftLinkCardV2Element.Text("2 checks failing", RaftLinkCardTextTone.Warning),
                                "open" to
                                    RaftLinkCardV2Element.Button(
                                        "Open in Stamp",
                                        RaftLinkCardButtonVariant.Default,
                                        RaftLinkCardButtonAction.Navigate("https://stamp.build/botiverse/slock/pull/12361"),
                                    ),
                            ),
                    ),
                inline = AppLinkInline("botiverse/slock#12361"),
            ),
        onAction = {},
        modifier = Modifier.fillMaxWidth().widthIn(max = RaftPreviewCardDefaults.ItemWidth),
        buttons = listOf(raftAppLinkOpenLinkButton {}),
    )
}

@Composable
private fun Cards() {
    FixtureCard(
        url = "https://linear.app/raft/issue/RAFT-812",
        appName = "Linear",
        title = "RAFT-812 Render App link cards natively",
        card =
            RaftAppLinkCard(
                root = "root",
                elements =
                    mapOf(
                        "root" to Stack(listOf("state", "assignee", "updated")),
                        "state" to Badge("In Progress"),
                        "assignee" to Text("Assigned to Niko, due Friday"),
                        "updated" to Text("Updated by Lagoon in Raft Mobile", RaftAppLinkTextTone.Muted),
                    ),
            ),
    )
    FixtureCard(
        url = "https://stamp.build/botiverse/slock/pull/10449",
        appName = "Stamp",
        title = "refactor(i18n): one display-locale source for web and desktop",
        card = null,
        eyebrow = "botiverse/slock#10449",
        details =
            listOf(
                RaftPreviewCardDetail.State("Open"),
                RaftPreviewCardDetail.Text("Martin"),
                RaftPreviewCardDetail.Text("+803 −498"),
                RaftPreviewCardDetail.Text("2 checks failing", RaftPreviewCardDetailTone.Warning),
            ),
        appButtons =
            listOf(
                RaftPreviewCardButton("Merge", RaftPreviewCardButtonKind.Execute, onClick = {}),
                RaftPreviewCardButton("Approve", RaftPreviewCardButtonKind.Execute, onClick = {}, frameless = true),
                RaftPreviewCardButton("Refresh", RaftPreviewCardButtonKind.Read, onClick = {}, frameless = true),
                RaftPreviewCardButton("Open in Stamp", RaftPreviewCardButtonKind.Link, onClick = {}, frameless = true),
            ),
    )
    FixtureCard(
        url = "https://stamp.build/botiverse/mobile/pull/3654",
        appName = "Stamp",
        title = "Fix the composer tray (#3654)",
        card = null,
        appButtons =
            listOf(
                RaftPreviewCardButton(
                    "Close",
                    RaftPreviewCardButtonKind.Execute,
                    onClick = {},
                    disabledReason = "Only the author can close it",
                ),
            ),
    )
    FixtureCard(
        url = "https://review.preview.example-apps.build/items/42",
        appName = "An App with a rather long name for its footer",
        title = "A pull request whose title is far too long for one line, so it wraps, and keeps wrapping until the card cuts it at two lines with an ellipsis",
        card =
            RaftAppLinkCard(
                root = "summary",
                elements = mapOf("summary" to Text("A muted line long enough to wrap onto a second line in the body.", RaftAppLinkTextTone.Muted)),
            ),
    )
}
