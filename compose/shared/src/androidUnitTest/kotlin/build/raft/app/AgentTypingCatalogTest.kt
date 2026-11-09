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

    /**
     * The line above a channel's composer (slock #13551) in every locale, from the web catalogs at
     * staging: `message.agentTyping.workingHere` (1 and 3), `typingAndOthers` (1 and 2 others) and
     * `typingRow`. The app has no plural rules beyond one/other, so Russian's `other` is
     * count-neutral, as `layout_working_now_summary_other` already is.
     */
    @Test
    fun everyLocaleWritesTheWebWorkingLine() {
        val expected =
            mapOf(
                "base" to listOf("1 agent working here", "3 agents working here", "Kirby is typing… · 1 other working", "Kirby is typing… · 2 others working", "Typing…"),
                "zh-rCN" to listOf("1 个 Agent 正在这里工作", "3 个 Agent 正在这里工作", "Kirby is typing… · 另外 1 个在工作", "Kirby is typing… · 另外 2 个在工作", "正在输入…"),
                "zh-rTW" to listOf("1 個 Agent 正在這裡工作", "3 個 Agent 正在這裡工作", "Kirby is typing… · 另有 1 個正在工作", "Kirby is typing… · 另有 2 個正在工作", "正在輸入…"),
                "ja" to listOf("エージェント 1 個がここで作業中", "エージェント 3 個がここで作業中", "Kirby is typing… · ほか 1 個が作業中", "Kirby is typing… · ほか 2 個が作業中", "入力中…"),
                "ko" to listOf("여기서 에이전트 1개 작업 중", "여기서 에이전트 3개 작업 중", "Kirby is typing… · 그 외 1개 작업 중", "Kirby is typing… · 그 외 2개 작업 중", "입력 중…"),
                "es" to listOf("1 agente trabajando aquí", "3 agentes trabajando aquí", "Kirby is typing… · 1 más trabajando", "Kirby is typing… · 2 más trabajando", "Escribiendo…"),
                "pt-rBR" to listOf("1 agente trabalhando aqui", "3 agentes trabalhando aqui", "Kirby is typing… · mais 1 trabalhando", "Kirby is typing… · mais 2 trabalhando", "Digitando…"),
                "ru" to listOf("1 агент работает здесь", "Работающих здесь агентов: 3", "Kirby is typing… · ещё 1 работает", "Kirby is typing… · ещё 2 работают", "Печатает…"),
            )
        expected.forEach { (locale, sentences) ->
            val catalog = catalog(locale)
            val typing = "Kirby is typing…"
            assertEquals(
                sentences,
                listOf(
                    catalog.getValue("thread_agent_working_here_one").format(1),
                    catalog.getValue("thread_agent_working_here_other").format(3),
                    catalog.getValue("thread_agent_typing_and_others_one").format(typing, 1),
                    catalog.getValue("thread_agent_typing_and_others_other").format(typing, 2),
                    catalog.getValue("thread_agent_typing_row"),
                ),
                locale,
            )
        }
    }

    @Test
    fun aNameThatLooksLikeAPlaceholderStaysAsWritten() {
        val templates = AgentTypingSentenceTemplates("{first} is typing…", "{first} and {second}", "", "")
        assertEquals("{second} and Kirby", formatAgentTypingSentence(templates, listOf("{second}", "Kirby")))
    }

    private fun catalog(locale: String): Map<String, String> {
        val file = File("src/commonMain/composeResources/${raftComposeValuesDir(locale)}/strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }
}
