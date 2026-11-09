package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardQuestion
import build.raft.app.data.expiredAsOf
import build.raft.app.data.parseActionCardSpec
import build.raft.app.data.stripActionCardSpec
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An agent's action cards in a conversation (web ActionCard on raft-ui MessageActionCard), light
 * and dark: a channel to create, waiting with the agent's reason; an agent created by RC, folded
 * to its done line; an app login with its permissions; members to add that this reader can't
 * run (View only); a frozen card; a card to confirm again; an agent card prepared on another
 * server (View only, folded, saying where it runs). Fixed fixture data. Reports land in `build/reports/home-screenshots/`;
 * verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h2000dp-xxhdpi")
class ThreadActionCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun threadActionCardsLight() = capture("thread.action-cards", RaftAppAppearance.Light)

    @Test
    fun threadActionCardsDark() = capture("thread.action-cards.dark", RaftAppAppearance.Dark)

    /** Expired cards: a typed one the server expired, one past its `expiresAt` on the clock, a spec card, a seat request. */
    @Test
    fun threadActionCardsExpiredLight() = capture("thread.action-cards.expired", RaftAppAppearance.Light) { ThreadActionCardsExpiredVisualContent() }

    @Test
    fun threadActionCardsExpiredDark() = capture("thread.action-cards.expired.dark", RaftAppAppearance.Dark) { ThreadActionCardsExpiredVisualContent() }

    /**
     * The redesigned question cards waiting (2026-11): C, a button row, for Maya (enabled, "Fixer is
     * waiting for your answer · 7 days left") and for Ada (disabled, "Waiting for Maya to answer");
     * B, a slim card, with five options in it, then one answered in the sheet for someone else.
     */
    @Test
    fun questionCardsLight() = capture("thread.action-cards.question", RaftAppAppearance.Light) { ThreadQuestionCardsVisualContent(answered = false) }

    @Test
    fun questionCardsDark() = capture("thread.action-cards.question.dark", RaftAppAppearance.Dark) { ThreadQuestionCardsVisualContent(answered = false) }

    /** The same cards answered: "✓ Maya answered **…**" where the controls were, on C and on B (still a card, unfolded, no tag). */
    @Test
    fun questionCardsAnsweredLight() = capture("thread.action-cards.question.answered", RaftAppAppearance.Light) { ThreadQuestionCardsVisualContent(answered = true) }

    @Test
    fun questionCardsAnsweredDark() = capture("thread.action-cards.question.answered.dark", RaftAppAppearance.Dark) { ThreadQuestionCardsVisualContent(answered = true) }

    /**
     * The same cards ended unanswered, each keeping its shape (raft-design "When it ends
     * unanswered"): C expired / withdrawn, the question over its one status line, no buttons; B
     * expired (Maya) / withdrawn, the same card with that line where its controls were, unfolded.
     */
    @Test
    fun questionCardsEndedLight() = capture("thread.action-cards.question.ended", RaftAppAppearance.Light) { ThreadQuestionCardsVisualContent(answered = false, ended = true) }

    @Test
    fun questionCardsEndedDark() = capture("thread.action-cards.question.ended.dark", RaftAppAppearance.Dark) { ThreadQuestionCardsVisualContent(answered = false, ended = true) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit = { ThreadActionCardsVisualContent() }) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { content() }
        }
}

@Composable
private fun ThreadActionCardsVisualContent() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThreadActionCardView(
            card =
                ThreadActionCard(
                    type = "channel:create",
                    name = "release-review",
                    visibility = "private",
                    description = "Coordinate the final release review.",
                    initialHumans = listOf("Ada", "Will"),
                    initialAgents = listOf("Atlas"),
                    draftHint = "You asked for a private space for the release review.",
                ),
            pending = false,
            onCommit = {},
        )
        ThreadActionCardView(
            card =
                ThreadActionCard(
                    type = "agent:create",
                    state = "executed",
                    name = "Folio",
                    description = "Documentation DRI: owns how Raft's written docs are shaped and kept current.",
                    draftHint = "For stdrc: a dedicated agent for writing docs and designing doc frameworks.",
                    executedByUserName = "RC",
                    resultKind = "agent",
                    resultName = "Folio",
                ),
            pending = false,
            executedTimeLabel = "10/20 23:09",
            onCommit = {},
        )
        ThreadActionCardView(
            card =
                ThreadActionCard(
                    type = "integration:approve_agent_login",
                    clientName = "Linear",
                    agentName = "lumen",
                    scopes = listOf("openid", "profile", "identity"),
                ),
            pending = false,
            onCommit = {},
        )
        ThreadActionCardView(
            card =
                ThreadActionCard(
                    type = "channel:add_member",
                    channel = "general",
                    humans = listOf("Ada", "Will"),
                    agents = listOf("Lumen"),
                ),
            pending = false,
            commitEnabled = false,
            onCommit = {},
        )
        ThreadActionCardView(
            card = ThreadActionCard(type = "channel:create", name = "launch", visibility = "public", state = "frozen"),
            pending = false,
            onCommit = {},
        )
        ThreadActionCardView(
            card = ThreadActionCard(type = "channel:create", name = "launch", visibility = "public", state = "reconfirm_required"),
            pending = false,
            onCommit = {},
        )
        // Prepared on a joint channel's peer server: read-only here, says where it runs.
        CompositionLocalProvider(LocalThreadViewerServerId provides "server-b") {
            ThreadActionCardView(
                card =
                    ThreadActionCard(
                        type = "agent:create",
                        name = "Scout",
                        sourceServerId = "server-a",
                        targetServerName = "Raft HQ",
                    ),
                pending = false,
                onCommit = {},
            )
        }
    }
}

@Composable
private fun ThreadActionCardsExpiredVisualContent() {
    // 2026-11-05 14:00 in UTC+8; the cards expired on 2026-10-30 09:30 and today at 13:10.
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 11, 5),
            nowEpochMillis = 1_793_858_400_000L,
        )
    val spec =
        stripActionCardSpec(
            requireNotNull(
                parseActionCardSpec(
                    Json.parseToJsonElement(
                        """
                        {"catalog":"action-card.v1","icon":"hash",
                         "title":{"key":"actionCard.title.createChannel","text":{"en":"Create #{channel}"},"params":{"channel":"launch-review"}},
                         "spec":{"root":"root","elements":{
                           "root":{"type":"Stack","props":{},"children":["name","create"]},
                           "name":{"type":"Field","props":{"label":{"key":"actionCard.field.name","text":{"en":"Name"}},"value":"#launch-review"}},
                           "create":{"type":"Button","props":{"label":{"key":"actionCard.createChannel.create","text":{"en":"Create Channel"}},"emphasis":"primary","action":{"kind":"open","intent":"create_channel"}}}
                         }}}
                        """.trimIndent(),
                    ),
                ),
            ),
            RAFT_APP_ACTION_CARD_CAPABILITIES,
        )!!
    CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ThreadActionCardView(
                card =
                    ThreadActionCard(
                        type = "channel:create",
                        state = "expired",
                        name = "release-review",
                        visibility = "private",
                        description = "Coordinate the final release review.",
                        expiresAt = "2026-10-30T01:30:00.000Z",
                    ),
                pending = false,
                onCommit = {},
            )
            // Still `prepared` on the wire, past its `expiresAt` on this clock.
            ThreadActionCardView(
                card =
                    ThreadActionCard(type = "agent:create", name = "Scout", expiresAt = "2026-11-05T05:10:00.000Z")
                        .expiredAsOf(timeContext.nowEpochMillis!!),
                pending = false,
                onCommit = {},
            )
            RaftActionCardView(
                card = spec,
                state =
                    RaftActionCardFrameState(
                        status = RaftActionCardStatus.Expired,
                        endedLine = "Expired on 10-30 09:30. Ask the agent to send a new request.",
                    ),
                entityName = { _, _ -> null },
                onAction = {},
                displayLocale = "en",
            )
            ThreadActionCardView(
                card = ThreadActionCard(type = "seats:add", state = "expired", presentationTitle = "Add 1 seat", requesterUserId = "u-ada"),
                pending = false,
                onCommit = {},
            )
        }
    }
}

/** Who the question cards ask and who looks: Maya is asked; Ada only reads. */
@Composable
private fun ThreadQuestionCardsVisualContent(answered: Boolean, ended: Boolean = false) {
    // 2026-11-09 10:03 UTC; the cards expire 7 days later.
    val timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = raftEpochDayForDate(2026, 11, 9), nowEpochMillis = 1_794_218_580_000L)
    val names = ThreadActionCardNameLookup { kind, id -> if (kind == ThreadActionCardEntityKind.Human && id == "u-maya") "Maya" else null }
    val fiveOptions = listOf("Fix all 4 on a branch", "One at a time, ask me each", "Just list them for me", "Skip them for now", "Ask Ada first")
    fun card(json: String, options: Int) =
        ThreadActionCard(
            type = "question:ask",
            state = if (answered) "executed" else "prepared",
            presentationCardJson = if (answered) ActionCardSpecFixtures.questionCardAnsweredJson() else json,
            expiresAt = "2026-11-16T10:03:00.000Z",
            questionAddresseeUserId = "u-maya",
            questionAddresseeHandle = "maya",
            questions = listOf(ThreadActionCardQuestion("single", options)),
            executedByUserName = if (answered) "Maya" else null,
        )

    @Composable
    fun Show(card: ThreadActionCard, viewer: String) {
        val spec = requireNotNull(card.actionCardSpecToRender(specEnabled = true, capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
        CompositionLocalProvider(LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(viewerUserId = viewer)) {
            ThreadActionCardSpecView(
                messageId = "m-${card.hashCode()}-$viewer",
                card = card,
                spec = spec,
                commitEnabled = true,
                typedPending = false,
                executedTimeLabel = null,
                askerName = "Fixer",
                onAction = {},
            )
        }
    }
    CompositionLocalProvider(
        LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
        LocalThreadActionCardNames provides names,
    ) {
        if (ended) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Show(card(ActionCardSpecFixtures.questionCardJson(), 3).copy(state = "expired", questionExpiresInMinutes = 3 * 1_440), viewer = "u-maya")
                Show(card(ActionCardSpecFixtures.questionCardJson(), 3).copy(state = "withdrawn"), viewer = "u-ada")
                // B expired asks Maya by name, as C does ("Nobody answered" is only for a card asked of anyone).
                Show(card(ActionCardSpecFixtures.questionCardJson(options = fiveOptions), 5).copy(state = "expired"), viewer = "u-maya")
                val several = Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), ActionCardSpecFixtures.rawCard("question_card"))
                Show(
                    card(several, 3).copy(state = "withdrawn", questions = listOf(ThreadActionCardQuestion("single", 3), ThreadActionCardQuestion("multiple", 3), ThreadActionCardQuestion("text"))),
                    viewer = "u-maya",
                )
            }
            return@CompositionLocalProvider
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Show(card(ActionCardSpecFixtures.questionCardJson(), 3), viewer = "u-maya")
            Show(card(ActionCardSpecFixtures.questionCardJson(), 3), viewer = "u-ada")
            Show(card(ActionCardSpecFixtures.questionCardJson(options = fiveOptions), 5), viewer = "u-maya")
            // The same B for someone who isn't asked: the same card, its options disabled, no fold.
            if (!answered) Show(card(ActionCardSpecFixtures.questionCardJson(options = fiveOptions), 5), viewer = "u-ada")
            Show(card(ActionCardSpecFixtures.questionCardJson(inline = false), 3).copy(questions = listOf(ThreadActionCardQuestion("single", 6))), viewer = "u-ada")
            // Several questions (the vector's sheet card): "Fixer has 3 questions".
            if (!answered) {
                val several = Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), ActionCardSpecFixtures.rawCard("question_card"))
                Show(
                    card(several, 3).copy(questions = listOf(ThreadActionCardQuestion("single", 3), ThreadActionCardQuestion("multiple", 3), ThreadActionCardQuestion("text"))),
                    viewer = "u-maya",
                )
            }
        }
    }
}
