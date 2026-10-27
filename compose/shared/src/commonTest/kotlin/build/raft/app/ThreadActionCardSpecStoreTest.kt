package build.raft.app

import build.raft.app.data.ACTION_CARD_OPEN_IN_WEB_LABEL
import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.ActionCardFormIssueCode
import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecIntent
import build.raft.app.data.ActionCardSpecNavigateTarget
import build.raft.app.data.DataResult
import build.raft.app.data.ExecuteActionFormBody
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.actionCardFormIssue
import build.raft.app.data.parseActionCardSpec
import build.raft.app.data.raftAppActionCardCapabilities
import build.raft.app.data.stripActionCardSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** A4: which card renders, where a Button goes, and the store that runs execute / confirm / form. */
class ThreadActionCardSpecStoreTest {
    private fun msg(text: String) = """{"key":"actionCard.test.${text.filter(Char::isLetter).lowercase()}","text":{"en":"$text"}}"""

    /** A card with up to two Buttons (the catalog's limit), a confirm `sure` and a form `details`. */
    private fun cardJson(vararg buttons: Pair<String, String>): String {
        val elements =
            buttons.joinToString(",") { (id, action) ->
                val disabled = if (id.startsWith("off")) ""","disabledReason":${msg("Not now")}""" else ""
                """"$id":{"type":"Button","props":{"label":${msg(id)},"emphasis":"${if (id == "danger") "destructive" else "secondary"}","action":$action$disabled}}"""
            }
        return """
            {"catalog":"action-card.v1","title":${msg("Card")},
             "spec":{"root":"root","elements":{"root":{"type":"Stack","props":{},"children":[${buttons.joinToString(",") { "\"${it.first}\"" }}]},$elements}},
             "confirms":{"sure":{"title":${msg("Sure")},"confirmLabel":${msg("Yes")},"spec":{"root":"t","elements":{"t":{"type":"Text","props":{"text":"It is public."}}}}}},
             "forms":{"details":{"title":${msg("Details")},"submitLabel":${msg("Send")},
               "fields":[{"type":"TextInput","name":"title","label":${msg("Title")},"required":true,"maxLength":50,"default":"Hello"},
                         {"type":"Toggle","name":"urgent","label":${msg("Urgent")}}]}}}
        """.trimIndent()
    }

    private fun spec(vararg buttons: Pair<String, String>): ActionCardSpecCard =
        stripActionCardSpec(assertNotNull(parseActionCardSpec(Json.parseToJsonElement(cardJson(*buttons)))), RAFT_APP_ACTION_CARD_CAPABILITIES)!!

    private val execute = """{"kind":"execute"}"""
    private val confirmed = """{"kind":"execute","confirm":"sure"}"""
    private val form = """{"kind":"open_form","form":"details"}"""

    private fun card(type: String = "channel:create", state: String = "prepared", version: Int = 3, spec: String? = null) =
        ThreadActionCard(type = type, state = state, confirmationVersion = version, presentationCardJson = spec)

    private class Harness(scope: TestScope) {
        val calls = mutableListOf<Triple<String, Int?, ExecuteActionFormBody?>>()
        val answers = ArrayDeque<CompletableDeferred<DataResult<ActionCardExecuteOutcome>>>()
        val applied = mutableListOf<Pair<String, ThreadActionCard>>()
        val opened = mutableListOf<String>()
        val store =
            ThreadActionCardSpecStore(
                scope = scope.backgroundScope,
                execute = { messageId, card, form ->
                    calls += Triple(messageId, card.confirmationVersion, form)
                    answers.removeFirst().await()
                },
                applyCard = { messageId, card -> applied += messageId to card },
                loadWebUrl = { "https://staging.raft.example" },
                openInBrowser = { url -> opened += url },
            )

        fun answer(result: DataResult<ActionCardExecuteOutcome>) = CompletableDeferred<DataResult<ActionCardExecuteOutcome>>().also {
            it.complete(result)
            answers += it
        }
    }

    @Test
    fun theSpecCardRendersOnlyWithTheFlagOnForAReadableCardWithoutATypedOnlyType() {
        val withSpec = card(spec = cardJson("go" to execute, "up" to """{"kind":"open","intent":"upgrade_dialog"}"""))
        // Flag off: the legacy card, whatever the card carries.
        assertNull(withSpec.actionCardSpecToRender(specEnabled = false, capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
        // Flag on: the stripped spec; the App has no upgrade dialog, so that Button opens in web.
        val rendered = assertNotNull(withSpec.actionCardSpecToRender(specEnabled = true, capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES))
        val up = rendered.spec.element("up") as ActionCardSpecButton
        assertEquals(ActionCardSpecAction.OpenInWeb to ACTION_CARD_OPEN_IN_WEB_LABEL, up.action to up.label)
        assertEquals(ActionCardSpecAction.Execute(), (rendered.spec.element("go") as ActionCardSpecButton).action)
        // No spec, an unreadable one, or a type whose typed card shows more: the legacy card.
        assertNull(card().actionCardSpecToRender(true, RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertNull(card(spec = cardJson("go" to execute).replace("action-card.v1", "action-card.v9")).actionCardSpecToRender(true, RAFT_APP_ACTION_CARD_CAPABILITIES))
        for (type in ThreadActionCardTypedOnlyTypes) {
            assertNull(card(type = type, spec = cardJson("go" to execute)).actionCardSpecToRender(true, RAFT_APP_ACTION_CARD_CAPABILITIES), type)
        }
        // The flag is the card's own server's, else the viewer's.
        assertEquals("card-server", card().copy(sourceServerId = "card-server").actionCardSpecFlagServerId("viewer"))
        assertEquals("viewer", card().actionCardSpecFlagServerId("viewer"))
    }

    @Test
    fun theAppDeclaresWhatItWiresAndStripsTheRest() {
        assertEquals(
            listOf(
                "card.v1", "confirm.v1", "form.v1", "field.text_input", "field.text_area", "field.select", "field.toggle",
                "field.member_picker", "field.channel_picker", "field.agent_picker",
                "intent.create_channel", "intent.add_members", "intent.create_agent", "intent.feedback_form",
                "intent.feedback_ticket",
            ),
            RAFT_APP_ACTION_CARD_CAPABILITIES,
        )
        val feedback = """{"kind":"open","intent":"feedback_form"}"""
        val read = assertNotNull(parseActionCardSpec(Json.parseToJsonElement(cardJson("fb" to feedback, "fill" to form))))
        val on = stripActionCardSpec(read, raftAppActionCardCapabilities(feedbackFormAvailable = true))!!
        assertTrue((on.spec.element("fb") as ActionCardSpecButton).action is ActionCardSpecAction.Open)
        assertEquals(ActionCardSpecAction.OpenForm("details"), (on.spec.element("fill") as ActionCardSpecButton).action)
        // Where the Feedback page is off, the feedback Button opens in web instead.
        val off = stripActionCardSpec(read, raftAppActionCardCapabilities(feedbackFormAvailable = false))!!
        assertEquals(ActionCardSpecAction.OpenInWeb, (off.spec.element("fb") as ActionCardSpecButton).action)
    }

    @Test
    fun aPressGoesToTheStoreTheTypedPathOrALink() {
        val navigate = """{"kind":"navigate","target":{"kind":"agent","id":"7b2e8f3a-0c5d-4e4f-9a3b-2c3d4e5f6071"}}"""
        val url = """{"kind":"navigate","url":"https://example.com/docs"}"""
        val createChannel = """{"kind":"open","intent":"create_channel"}"""
        val cases =
            listOf(
                Triple(card(), spec("sure" to confirmed), "sure") to ThreadActionCardSpecPress.Store,
                Triple(card(), spec("go" to execute, "fill" to form), "fill") to ThreadActionCardSpecPress.Store,
                Triple(card(), spec("open" to createChannel), "open") to ThreadActionCardSpecPress.Typed,
                // An intent for another type's dialog: opened in web, as strip would.
                Triple(card(type = "agent:create"), spec("open" to createChannel), "open") to ThreadActionCardSpecPress.OpenInWeb,
                Triple(card(state = "reconfirm_required"), spec("go" to execute), "go") to ThreadActionCardSpecPress.Typed,
                Triple(card(state = "executed"), spec("go" to execute), "go") to ThreadActionCardSpecPress.Ignore,
                Triple(card(state = "frozen"), spec("go" to execute), "go") to ThreadActionCardSpecPress.Ignore,
                Triple(card(), spec("off" to execute), "off") to ThreadActionCardSpecPress.Ignore,
                Triple(card(), spec("go" to execute), "missing") to ThreadActionCardSpecPress.Ignore,
                Triple(card(state = "frozen"), spec("site" to url), "site") to ThreadActionCardSpecPress.OpenUrl("https://example.com/docs"),
                Triple(card(), spec("up" to """{"kind":"open","intent":"upgrade_dialog"}"""), "up") to ThreadActionCardSpecPress.OpenInWeb,
                Triple(card(state = "executed"), spec("nav" to navigate), "nav") to
                    ThreadActionCardSpecPress.Navigate(
                        ActionCardSpecNavigateTarget(ActionCardSpecNavigateTarget.Kind.Agent, "7b2e8f3a-0c5d-4e4f-9a3b-2c3d4e5f6071"),
                    ),
            )
        for ((input, expected) in cases) {
            val (card, spec, id) = input
            assertEquals(expected, threadActionCardSpecPress(card, spec, id, viewerServerId = "s-1"), "${card.state} $id")
        }
        // web isOnViewerServer: no viewer server, or a target on another server, opens nothing (its Button is disabled).
        val elsewhere = """{"kind":"navigate","target":{"kind":"agent","id":"7b2e8f3a-0c5d-4e4f-9a3b-2c3d4e5f6071","serverId":"c07d3e8f-5bac-4d9e-8f8a-718293a4b5c6"}}"""
        assertEquals(ThreadActionCardSpecPress.Ignore, threadActionCardSpecPress(card(), spec("nav" to navigate), "nav", viewerServerId = null))
        assertEquals(ThreadActionCardSpecPress.Ignore, threadActionCardSpecPress(card(), spec("nav" to elsewhere), "nav", viewerServerId = "s-1"))
        val farButton = spec("nav" to elsewhere).spec.element("nav") as ActionCardSpecButton
        assertEquals(false, farButton.actionCardSpecLinkNavigable(cardSourceServerId = null, viewerServerId = "s-1"))
        assertEquals(true, farButton.actionCardSpecLinkNavigable(cardSourceServerId = null, viewerServerId = "c07d3e8f-5bac-4d9e-8f8a-718293a4b5c6"))
        // A card from another server: its targets without a serverId live there too.
        assertEquals(false, actionCardTargetOnViewerServer(ActionCardSpecNavigateTarget(ActionCardSpecNavigateTarget.Kind.Channel, "c"), "other", "s-1"))
    }

    @Test
    fun anIntentIsKeptOnlyForTheCardTypeItsNativePathBelongsTo() {
        val createChannel = """{"kind":"open","intent":"create_channel"}"""
        val createAgent = """{"kind":"open","intent":"create_agent"}"""
        val json = cardJson("chan" to createChannel, "agent" to createAgent)
        val onChannelCard = assertNotNull(card(type = "channel:create", spec = json).actionCardSpecToRender(true, RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertEquals(ActionCardSpecAction.Open(ActionCardSpecIntent.CreateChannel), (onChannelCard.spec.element("chan") as ActionCardSpecButton).action)
        // Another type's dialog: "Open in web", with that label, not the spec's.
        val other = onChannelCard.spec.element("agent") as ActionCardSpecButton
        assertEquals(ActionCardSpecAction.OpenInWeb to ACTION_CARD_OPEN_IN_WEB_LABEL, other.action to other.label)
        val onAgentCard = assertNotNull(card(type = "agent:create", spec = json).actionCardSpecToRender(true, RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertEquals(ActionCardSpecAction.OpenInWeb, (onAgentCard.spec.element("chan") as ActionCardSpecButton).action)
        assertEquals(ActionCardSpecAction.Open(ActionCardSpecIntent.CreateAgent), (onAgentCard.spec.element("agent") as ActionCardSpecButton).action)
        assertEquals(
            RAFT_APP_ACTION_CARD_CAPABILITIES.filterNot { it.startsWith("intent.") } + "intent.create_agent",
            card(type = "agent:create").actionCardSpecCapabilitiesFor(RAFT_APP_ACTION_CARD_CAPABILITIES),
        )
    }

    @Test
    fun aCardDoneOrFrozenElsewhereClosesItsOpenSheetAndSaysSo() = runTest {
        val h = Harness(this)
        h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "fill", card(), spec("fill" to form)))
        // A newer version of the same prepared card just follows along.
        h.store.dispatch(ThreadActionCardSpecIntent.CardUpdated("m-1", card(version = 4)))
        assertEquals(4, h.store.state.value.form?.card?.confirmationVersion)
        h.store.dispatch(ThreadActionCardSpecIntent.CardUpdated("m-1", card(state = "executed").copy(executedByUserName = "Ada")))
        assertNull(h.store.state.value.form)
        assertEquals(ThreadActionCardSpecEffect.AlreadyExecuted("Ada"), h.store.effects.first())

        h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "sure", card(), spec("sure" to confirmed)))
        h.store.dispatch(ThreadActionCardSpecIntent.CardUpdated("m-1", card(state = "frozen")))
        assertNull(h.store.state.value.confirm)
        assertEquals(ThreadActionCardSpecEffect.AlreadyExecuted(null, frozen = true), h.store.effects.first())

        // While its own submit runs, the answer decides: the sheet stays.
        val pending = CompletableDeferred<DataResult<ActionCardExecuteOutcome>>().also { h.answers += it }
        h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "fill", card(), spec("fill" to form)))
        h.store.dispatch(ThreadActionCardSpecIntent.SubmitForm(mapOf("title" to ActionCardFormValue.Text("Hi"))))
        runCurrent()
        h.store.dispatch(ThreadActionCardSpecIntent.CardUpdated("m-1", card(state = "executed")))
        assertEquals(true, h.store.state.value.form?.submitting)
        pending.complete(DataResult.Content(ActionCardExecuteOutcome.Executed(card(state = "executed"))))
        runCurrent()
        assertNull(h.store.state.value.form)
    }

    @Test
    fun openInWebOpensTheMessageLinkOnTheEnvironmentsWebUrlInTheBrowser() = runTest {
        val h = Harness(this)
        val link = RaftMessageLink("acme", build.raft.app.model.ConversationKind.Channel, "c-1", messageId = "m-1")
        h.store.dispatch(ThreadActionCardSpecIntent.OpenInWeb("m-1", link))
        runCurrent()
        assertEquals(listOf("https://staging.raft.example/s/acme/channel/c-1?msg=m-1"), h.opened)
        assertEquals(link, parseRaftMessageLink(h.opened.single(), "https://staging.raft.example"))
        // No link for this message (no server slug): it says so and opens nothing.
        h.store.dispatch(ThreadActionCardSpecIntent.OpenInWeb("m-2", null))
        assertEquals(ThreadActionCardSpecEffect.OpenInWebFailed, h.store.effects.first())
        assertEquals(1, h.opened.size)
    }

    @Test
    fun anExecuteRunsOncePerCardAndAppliesTheAnswer() = runTest {
        val h = Harness(this)
        val spec = spec("go" to execute)
        val executed = card(state = "executed", version = 4)
        val pending = CompletableDeferred<DataResult<ActionCardExecuteOutcome>>().also { h.answers += it }
        h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "go", card(), spec))
        runCurrent()
        assertEquals("go", h.store.state.value.busyElement("m-1", typedPending = false))
        // A second tap while it runs does nothing.
        h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "go", card(), spec))
        runCurrent()
        assertEquals(listOf("m-1" to 3), h.calls.map { it.first to it.second })
        assertNull(h.calls.single().third)
        pending.complete(DataResult.Content(ActionCardExecuteOutcome.Executed(executed)))
        runCurrent()
        assertEquals(listOf("m-1" to executed), h.applied)
        assertNull(h.store.state.value.busyElement("m-1", typedPending = false))
    }

    @Test
    fun aFailedExecuteSaysWhyUnderItsCardUntilTheNextAction() = runTest {
        val h = Harness(this)
        val spec = spec("go" to execute)
        val cases =
            listOf(
                DataResult.Error(ScreenError(code = "x", serverMessage = "Channel name taken")) to ThreadActionCardSpecError.Failed("Channel name taken"),
                DataResult.Error(ScreenError(code = "x")) to ThreadActionCardSpecError.Failed(null),
                DataResult.Content(ActionCardExecuteOutcome.FormRequired("needs form")) to ThreadActionCardSpecError.FormRequired,
            )
        for ((answer, expected) in cases) {
            h.answer(answer)
            h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "go", card(), spec))
            runCurrent()
            assertEquals(expected, h.store.state.value.errors["m-1"])
        }
        // The typed path's press clears it and is the one the frame shows busy while it is pending.
        h.store.dispatch(ThreadActionCardSpecIntent.TypedPress("m-1", "open"))
        assertNull(h.store.state.value.errors["m-1"])
        assertEquals("open", h.store.state.value.busyElement("m-1", typedPending = true))
        assertNull(h.store.state.value.busyElement("m-1", typedPending = false))
        assertTrue(h.applied.isEmpty())
    }

    @Test
    fun aConfirmExecutesOnlyFromItsSheetWithTheLiveCard() = runTest {
        val h = Harness(this)
        val spec = spec("danger" to confirmed)
        h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "danger", card(), spec))
        runCurrent()
        val open = assertNotNull(h.store.state.value.confirm)
        assertEquals(true to "Sure", open.destructive to open.confirm.title.text["en"])
        assertTrue(h.calls.isEmpty())
        // Closing runs nothing.
        h.store.dispatch(ThreadActionCardSpecIntent.DismissConfirm)
        assertNull(h.store.state.value.confirm)
        h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "danger", card(), spec))
        // The card moved on (realtime): the confirm executes against the new version.
        h.store.dispatch(ThreadActionCardSpecIntent.CardUpdated("m-1", card(version = 5)))
        h.answer(DataResult.Error(ScreenError(code = "x", serverMessage = "Try later")))
        h.store.dispatch(ThreadActionCardSpecIntent.ConfirmExecute)
        runCurrent()
        assertEquals(5, h.calls.single().second)
        val failed = assertNotNull(h.store.state.value.confirm)
        assertEquals(false to ThreadActionCardSpecError.Failed("Try later"), failed.submitting to failed.error)
        val executed = card(state = "executed")
        h.answer(DataResult.Content(ActionCardExecuteOutcome.Executed(executed)))
        h.store.dispatch(ThreadActionCardSpecIntent.ConfirmExecute)
        runCurrent()
        assertNull(h.store.state.value.confirm)
        assertEquals(listOf("m-1" to executed), h.applied)
    }

    @Test
    fun aFormSubmitsItsValuesAndShowsWhatTheServerSays() = runTest {
        val h = Harness(this)
        val spec = spec("fill" to form)
        h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "fill", card(), spec))
        val opened = assertNotNull(h.store.state.value.form)
        assertEquals(
            mapOf("title" to ActionCardFormValue.Text("Hello"), "urgent" to ActionCardFormValue.Toggle(false)),
            opened.values,
        )
        val values = mapOf("title" to ActionCardFormValue.Text("Hi"), "urgent" to ActionCardFormValue.Toggle(true))

        // 422 FORM_INVALID: the issues under their fields, the sheet stays open with the values.
        val issue = actionCardFormIssue("title", ActionCardFormIssueCode.Conflict)
        h.answer(DataResult.Content(ActionCardExecuteOutcome.FormInvalid(listOf(issue), "Invalid")))
        h.store.dispatch(ThreadActionCardSpecIntent.SubmitForm(values))
        runCurrent()
        assertEquals(ExecuteActionFormBody(id = "details", values = values), h.calls.single().third)
        val invalid = assertNotNull(h.store.state.value.form)
        assertEquals(Triple(listOf(issue), values, false), Triple(invalid.issues, invalid.values, invalid.submitting))

        // 422 FORM_REQUIRED: the refresh hint in the banner.
        h.answer(DataResult.Content(ActionCardExecuteOutcome.FormRequired(null)))
        h.store.dispatch(ThreadActionCardSpecIntent.SubmitForm(values))
        runCurrent()
        assertEquals(ThreadActionCardSpecError.FormRequired, h.store.state.value.form?.error)

        // 409 ALREADY_EXECUTED: the card as it now is, the sheet closes and says who.
        val done = card(state = "executed").copy(executedByUserName = "Ada")
        h.answer(DataResult.Content(ActionCardExecuteOutcome.AlreadyExecuted(done, "Already")))
        h.store.dispatch(ThreadActionCardSpecIntent.SubmitForm(values))
        runCurrent()
        assertNull(h.store.state.value.form)
        assertEquals(listOf("m-1" to done), h.applied)
        assertEquals(ThreadActionCardSpecEffect.AlreadyExecuted("Ada"), h.store.effects.first())
    }

    /** botiverse/slock 7799cc436: the feedback limit's 429 keeps the sheet, its values and a prepared card. */
    @Test
    fun aFeedbackRateLimitKeepsTheSheetAndItsValuesAndLetsTheSubmitRunAgain() = runTest {
        val h = Harness(this)
        val spec = spec("fill" to form)
        h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "fill", card(type = "feedback:submit"), spec))
        val values = mapOf("title" to ActionCardFormValue.Text("The export button does nothing"), "urgent" to ActionCardFormValue.Toggle(true))
        h.answer(
            DataResult.Error(
                ScreenError(
                    code = "action_execute_contract_mismatch",
                    retryable = false,
                    serverMessage = "Too many feedback submissions. Please try again later.",
                    serverCode = "feedback_rate_limited",
                    retryAfterSeconds = 1800,
                ),
            ),
        )
        h.store.dispatch(ThreadActionCardSpecIntent.SubmitForm(values))
        runCurrent()
        val limited = assertNotNull(h.store.state.value.form)
        assertEquals(values, limited.values)
        assertEquals(false, limited.submitting)
        assertEquals(ThreadActionCardSpecError.FeedbackRateLimited(1800), limited.error)
        assertEquals("prepared", limited.card.state)
        assertTrue(h.applied.isEmpty())

        // Any other refusal keeps the sheet and values too, with the server's words.
        h.answer(DataResult.Error(ScreenError(code = "x", serverMessage = "Hands is down")))
        h.store.dispatch(ThreadActionCardSpecIntent.SubmitForm(values))
        runCurrent()
        val failed = assertNotNull(h.store.state.value.form)
        assertEquals(Triple(values, false, ThreadActionCardSpecError.Failed("Hands is down")), Triple(failed.values, failed.submitting, failed.error))

        // The same Submit runs again once the wait is over.
        val executed = card(type = "feedback:submit", state = "executed")
        h.answer(DataResult.Content(ActionCardExecuteOutcome.Executed(executed)))
        h.store.dispatch(ThreadActionCardSpecIntent.SubmitForm(values))
        runCurrent()
        assertEquals(3, h.calls.size)
        assertNull(h.store.state.value.form)
        assertEquals(listOf("m-1" to executed), h.applied)
    }

    @Test
    fun aFeedbackRateLimitOnAPlainExecuteSaysSoUnderItsCard() = runTest {
        val h = Harness(this)
        h.answer(DataResult.Error(ScreenError(code = "x", serverMessage = "Too many", serverCode = "feedback_rate_limited")))
        h.store.dispatch(ThreadActionCardSpecIntent.Press("m-1", "go", card(), spec("go" to execute)))
        runCurrent()
        assertEquals(ThreadActionCardSpecError.FeedbackRateLimited(null), h.store.state.value.errors["m-1"])
    }

    @Test
    fun aMessageLinkBuildsBackToTheSameMessage() {
        val links =
            listOf(
                RaftMessageLink("acme", build.raft.app.model.ConversationKind.Channel, "c-1", messageId = "m-1"),
                RaftMessageLink("acme", build.raft.app.model.ConversationKind.DirectMessage, "d_2", messageId = "m-2"),
                RaftMessageLink("acme", build.raft.app.model.ConversationKind.Channel, "c-1", messageId = "r-3", threadParentMessageId = "p-4"),
                RaftMessageLink("acme", build.raft.app.model.ConversationKind.Channel, "c-1"),
            )
        for (webUrl in listOf(null, "https://staging.raft.example", "http://localhost:3000/")) {
            for (link in links) {
                val url = assertNotNull(buildRaftMessageLink(link, webUrl), "$link")
                assertEquals(link, parseRaftMessageLink(url, webUrl), url)
            }
        }
        assertEquals("https://app.raft.build/s/acme/channel/c-1?msg=r-3&thread=c-1%3Ap-4", buildRaftMessageLink(links[2], null))
        // An id the parser would refuse builds nothing.
        assertNull(buildRaftMessageLink(links[0].copy(messageId = "m/1"), null))
    }
}
