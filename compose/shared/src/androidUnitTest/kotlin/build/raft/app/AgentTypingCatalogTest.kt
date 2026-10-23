package build.raft.app

import build.raft.app.data.AgentTypingSentenceTemplates
import build.raft.app.data.formatAgentTypingSentence
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The typing line's sentence in every locale, as the web writes it: botiverse/slock staging
 * `packages/web/src/i18n/messages/{en,zh-cn,ja}.ts` `message.agentTyping.typing.*` through
 * `formatAgentTypingSentence` (1, 2, 3 names; past 3 the first two and the rest counted). The web's
 * `{others, plural, one {…} other {…}}` only ever sees 2 or more (4+ names), so the catalog keeps its
 * `other` form.
 */
class AgentTypingCatalogTest {
    private val names = listOf("Niko", "Kirby", "Tenny", "Mo", "Lu")

    private val expected =
        mapOf(
            "base" to
                listOf(
                    "Niko is typing…",
                    "Niko and Kirby are typing…",
                    "Niko, Kirby and Tenny are typing…",
                    "Niko, Kirby and 2 other agents are typing…",
                    "Niko, Kirby and 3 other agents are typing…",
                ),
            "zh-rCN" to
                listOf(
                    "Niko 正在输入…",
                    "Niko 和 Kirby 正在输入…",
                    "Niko、Kirby 和 Tenny 正在输入…",
                    "Niko、Kirby 等 4 个 agent 正在输入…",
                    "Niko、Kirby 等 5 个 agent 正在输入…",
                ),
            "ja" to
                listOf(
                    "Niko が入力中…",
                    "Niko と Kirby が入力中…",
                    "Niko、Kirby、Tenny が入力中…",
                    "Niko、Kirby ほか 2 個のエージェントが入力中…",
                    "Niko、Kirby ほか 3 個のエージェントが入力中…",
                ),
        )

    @Test
    fun everyLocaleWritesTheWebSentenceForOneToManyAgents() {
        expected.forEach { (locale, sentences) ->
            val catalog = catalog(locale)
            val templates =
                AgentTypingSentenceTemplates(
                    one = catalog.getValue("thread_agent_typing_one"),
                    two = catalog.getValue("thread_agent_typing_two"),
                    three = catalog.getValue("thread_agent_typing_three"),
                    many = catalog.getValue("thread_agent_typing_many"),
                )
            assertEquals("", formatAgentTypingSentence(templates, emptyList()), locale)
            sentences.forEachIndexed { index, sentence ->
                assertEquals(sentence, formatAgentTypingSentence(templates, names.take(index + 1)), "$locale/${index + 1}")
            }
        }
    }

    @Test
    fun aNameThatLooksLikeAPlaceholderStaysAsWritten() {
        val templates = AgentTypingSentenceTemplates("{first} is typing…", "{first} and {second}", "", "")
        assertEquals("{second} and Kirby", formatAgentTypingSentence(templates, listOf("{second}", "Kirby")))
    }

    private fun catalog(locale: String): Map<String, String> {
        val file = File("src/commonMain/resources/MR/$locale/strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }
}
