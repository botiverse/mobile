package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * schemaVersion 8 (the question card) on the App side beyond the shared vector: a Choice field's
 * parse and bounds, a form's steps and placement, the Choice value's client checks (`too_few`
 * included), its wire shape, and the raw Msg staying plain text.
 */
class ActionCardSpecChoiceTest {
    private fun raw(text: String) = """{"key":"actionCard.text.raw","text":{"en":"{t}"},"params":{"t":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}}"""

    private fun option(value: String, description: String? = null) =
        """{"value":"$value","label":${raw("Option $value")}${description?.let { ""","description":${raw(it)}""" } ?: ""}}"""

    private fun choice(
        name: String = "pick",
        multiple: Boolean = false,
        extra: String = "",
        options: List<String> = listOf("a", "b", "c"),
        other: String? = """{"label":{"key":"actionCard.question.other","text":{"en":"Other…"}},"maxLength":5}""",
    ) = """{"type":"Choice","name":"$name","label":${raw("Question $name?")},"multiple":$multiple,"options":[${options.joinToString(",") { option(it) }}]${other?.let { ""","other":$it""" } ?: ""}$extra}"""

    private fun form(fields: List<String>, extra: String = "") =
        parseActionCardSpecForm(
            Json.parseToJsonElement(
                """{"title":{"key":"actionCard.t.form","text":{"en":"Form"}},"submitLabel":{"key":"actionCard.t.save","text":{"en":"Save"}}$extra,"fields":[${fields.joinToString(",")}]}""",
            ),
        )

    private fun choiceField(json: String): ActionCardSpecChoiceField = assertIs(assertNotNull(form(listOf(json))).fields.single())

    private fun issues(field: String, value: ActionCardFormValue?): List<Pair<ActionCardFormIssueCode, Map<String, ActionCardSpecMsgParam>?>> =
        checkActionCardFormValues(assertNotNull(form(listOf(field))), if (value == null) emptyMap() else mapOf("pick" to value))
            .map { it.code to it.message.params }

    @Test
    fun aChoiceReadsItsOptionsOtherAndDefaultAndNeedsFieldChoice() {
        val field =
            choiceField(
                choice(multiple = true, extra = ""","required":true,"minItems":1,"maxItems":3,"default":{"selected":["a"],"other":"hi","ranked":true},"layout":"grid"""")
                    .replace(option("a"), option("a", "About a")),
            )
        assertEquals("field.choice", field.capability)
        assertEquals(listOf("a", "b", "c"), field.options.map { it.value })
        assertEquals("About a", renderActionCardMsgText(assertNotNull(field.options.first().description), "en"))
        assertEquals("Option a", renderActionCardMsgText(field.options.first().label, "zh-cn"))
        assertEquals(5, field.other?.maxLength)
        assertEquals(ActionCardFormValue.Choice(listOf("a"), "hi"), field.default)
        assertEquals(true, field.required)
        // Four items (three options and Other), three taken at most.
        assertEquals(4, field.itemCount)
        assertEquals(3, field.maxSelectable)
        assertEquals(1, field.minRequired)
    }

    @Test
    fun aFormsStepsPageItsFieldsAndAnInconsistentSetIsDroppedNotRefused() {
        val fields = listOf(choice("one"), choice("two"), """{"type":"TextArea","name":"three","label":${raw("Three?")},"maxLength":1000}""")
        val paged = assertNotNull(form(fields, ""","steps":[{"title":{"key":"actionCard.t.s","text":{"en":"First"}},"fields":["one","two"]},{"fields":["three"],"future":1}]"""))
        assertEquals(listOf(listOf("one", "two"), listOf("three")), paged.steps?.map { it.fields })
        assertEquals("First", renderActionCardMsgText(assertNotNull(paged.steps?.first()?.title), "en"))
        assertNull(paged.steps?.last()?.title)

        // A step set that misses a field, repeats one or names an unknown one is dropped: one page.
        for (steps in listOf("""[{"fields":["one"]},{"fields":["two"]}]""", """[{"fields":["one","two"]},{"fields":["two","three"]}]""", """[{"fields":["one","two","three"]},{"fields":["nope"]}]""")) {
            assertNull(assertNotNull(form(fields, ""","steps":$steps""")).steps, steps)
        }
        // One step, eleven steps or an empty one is no step list at all: the card is refused.
        assertNull(form(fields, ""","steps":[{"fields":["one","two","three"]}]"""))
        assertNull(form(fields, ""","steps":[{"fields":[]},{"fields":["one","two","three"]}]"""))
        val eleven = (1..11).joinToString(",") { """{"fields":["one"]}""" }
        assertNull(form(fields, ""","steps":[$eleven]"""))
    }

    @Test
    fun aPlacementItKnowsIsKeptAndAnotherReadsAsTheSheet() {
        val fields = listOf(choice())
        assertEquals(ActionCardSpecFormPlacement.Inline, assertNotNull(form(fields, ""","placement":"inline"""")).placement)
        assertEquals(ActionCardSpecFormPlacement.Sheet, assertNotNull(form(fields, ""","placement":"sheet"""")).placement)
        assertNull(assertNotNull(form(fields, ""","placement":"drawer"""")).placement)
        assertNull(assertNotNull(form(fields, ""","placement":7""")).placement)
        assertNull(assertNotNull(form(fields)).placement)
    }

    @Test
    fun aRequiredChoiceNeedsAnOptionOrANonBlankOther() {
        val field = choice(extra = ""","required":true""")
        val required = listOf(ActionCardFormIssueCode.Required to null)
        assertEquals(required, issues(field, null))
        assertEquals(required, issues(field, ActionCardFormValue.Choice(emptyList())))
        assertEquals(required, issues(field, ActionCardFormValue.Choice(emptyList(), "")))
        assertEquals(required, issues(field, ActionCardFormValue.Choice(emptyList(), "　")))
        assertEquals(emptyList(), issues(field, ActionCardFormValue.Choice(listOf("b"))))
        assertEquals(emptyList(), issues(field, ActionCardFormValue.Choice(emptyList(), "mine")))
        // Optional: nothing chosen is fine.
        assertEquals(emptyList(), issues(choice(), ActionCardFormValue.Choice(emptyList())))
    }

    @Test
    fun aMultipleChoiceHoldsItsBoundsAndOtherCountsAsAnItem() {
        val field = choice(multiple = true, extra = ""","minItems":2,"maxItems":3""")
        fun max(n: Long) = mapOf("max" to ActionCardSpecMsgParam.Integer(n))
        fun min(n: Long) = mapOf("min" to ActionCardSpecMsgParam.Integer(n))
        assertEquals(listOf(ActionCardFormIssueCode.TooFew to min(2)), issues(field, ActionCardFormValue.Choice(listOf("a"))))
        // Other counts: one option and a typed answer is two.
        assertEquals(emptyList(), issues(field, ActionCardFormValue.Choice(listOf("a"), "x")))
        // A blank Other counts for nothing.
        assertEquals(listOf(ActionCardFormIssueCode.TooFew to min(2)), issues(field, ActionCardFormValue.Choice(listOf("a"), " ")))
        assertEquals(emptyList(), issues(field, ActionCardFormValue.Choice(listOf("a", "b", "c"))))
        assertEquals(listOf(ActionCardFormIssueCode.TooMany to max(3)), issues(field, ActionCardFormValue.Choice(listOf("a", "b", "c"), "x")))
        // Nothing chosen on an optional bounded Choice is not "too few".
        assertEquals(emptyList(), issues(field, ActionCardFormValue.Choice(emptyList())))
        // A single Choice takes one.
        assertEquals(listOf(ActionCardFormIssueCode.TooMany to max(1)), issues(choice(), ActionCardFormValue.Choice(listOf("a"), "x")))
    }

    @Test
    fun otherIsCheckedAgainstItsOwnMaxLengthInCodePoints() {
        val field = choice()
        assertEquals(emptyList(), issues(field, ActionCardFormValue.Choice(emptyList(), "😀😀😀😀😀")))
        assertEquals(
            listOf(ActionCardFormIssueCode.TooLong to mapOf("max" to ActionCardSpecMsgParam.Integer(5))),
            issues(field, ActionCardFormValue.Choice(emptyList(), "😀😀😀😀😀😀")),
        )
        assertEquals("Use 5 characters or fewer.", renderActionCardMsgText(checkActionCardFormValues(assertNotNull(form(listOf(field))), mapOf("pick" to ActionCardFormValue.Choice(emptyList(), "123456"))).single().message, "en"))
        assertEquals("Choose at least 2.", renderActionCardMsgText(actionCardFormIssue("pick", ActionCardFormIssueCode.TooFew, mapOf("min" to ActionCardSpecMsgParam.Integer(2))).message, "en"))
    }

    @Test
    fun theChoiceValueIsTheContractsWireShape() {
        val json = Json
        assertEquals("""{"selected":["a","b"],"other":"x"}""", json.encodeToString(ActionCardFormValueSerializer, ActionCardFormValue.Choice(listOf("a", "b"), "x")))
        assertEquals("""{"selected":[]}""", json.encodeToString(ActionCardFormValueSerializer, ActionCardFormValue.Choice(emptyList())))
        assertEquals(ActionCardFormValue.Choice(listOf("a"), ""), json.decodeFromString(ActionCardFormValueSerializer, """{"selected":["a"],"other":""}"""))
        assertEquals(ActionCardFormValue.Choice(listOf("a")), json.decodeFromString(ActionCardFormValueSerializer, """{"selected":["a"]}"""))
    }

    @Test
    fun aRawMsgIsPlainTextInEveryLocale() {
        val text = "{x} {{y}} } {z <b>bold</b> [link](https://evil.example)"
        val msg = assertNotNull(parseActionCardSpecMsg(Json.parseToJsonElement(raw(text))))
        for (locale in listOf("en", "zh-cn", "ru", "ja")) assertEquals(text, renderActionCardMsgText(msg, locale), locale)
        // The text is 1-500 code points (a Msg's string param limit).
        assertNull(parseActionCardSpecMsg(Json.parseToJsonElement(raw("x".repeat(501)))))
        assertNotNull(parseActionCardSpecMsg(Json.parseToJsonElement(raw("😀".repeat(500)))))
    }

    @Test
    fun aFormWithAChoiceIsOpenInWebWithoutFieldChoice() {
        val card =
            assertNotNull(
                parseActionCardSpec(
                    Json.parseToJsonElement(
                        """{"catalog":"action-card.v1","title":{"key":"actionCard.t.t","text":{"en":"T"}},
                        "spec":{"root":"root","elements":{"root":{"type":"Stack","props":{},"children":["go"]},
                          "go":{"type":"Button","props":{"label":{"key":"actionCard.t.go","text":{"en":"Go"}},"action":{"kind":"open_form","form":"f"}}}}},
                        "forms":{"f":{"title":{"key":"actionCard.t.form","text":{"en":"Form"}},"submitLabel":{"key":"actionCard.t.save","text":{"en":"Save"}},"fields":[${choice()}]}}}""",
                    ),
                ),
            )
        val without = ACTION_CARD_SPEC_CAPABILITIES - "field.choice"
        assertEquals(ActionCardSpecAction.OpenInWeb, (assertNotNull(stripActionCardSpec(card, without)).spec.element("go") as ActionCardSpecButton).action)
        assertEquals(ActionCardSpecAction.OpenForm("f"), (assertNotNull(stripActionCardSpec(card, ACTION_CARD_SPEC_CAPABILITIES)).spec.element("go") as ActionCardSpecButton).action)
    }
}
