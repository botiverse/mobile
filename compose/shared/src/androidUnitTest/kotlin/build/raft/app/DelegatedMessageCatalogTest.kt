package build.raft.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC 077 "sent by" words in every locale. en and zh-CN are the web catalog's
 * `message.delegated.*` (botiverse/slock staging dcb95d0ac330414b77e8c6421cf96be114c9c52d,
 * `packages/web/src/i18n/messages/{en,zh-cn}.ts`) with `{name}` / `{sender}` as positional
 * placeholders; the web has no ja entries yet (ja falls back to en there), so ja follows
 * `docs/i18n-ja-glossary.md`.
 */
class DelegatedMessageCatalogTest {
    private val expected =
        mapOf(
            "base" to
                mapOf(
                    "message_delegated_sent_by" to "sent by %1\$s",
                    "message_delegated_sent_by_in_body" to "(sent by %1\$s)",
                    "message_delegated_sent_by_deleted" to "sent by a deleted assistant",
                    "message_delegated_sent_by_deleted_in_body" to "(sent by a deleted assistant)",
                    "message_delegated_preview_sender" to "%1\$s (sent by %2\$s)",
                    "message_delegated_preview_sender_deleted" to "%1\$s (sent by a deleted assistant)",
                ),
            "zh-rCN" to
                mapOf(
                    "message_delegated_sent_by" to "由 %1\$s 代发",
                    "message_delegated_sent_by_in_body" to "（由 %1\$s 代发）",
                    "message_delegated_sent_by_deleted" to "由已删除的助理代发",
                    "message_delegated_sent_by_deleted_in_body" to "（由已删除的助理代发）",
                    "message_delegated_preview_sender" to "%1\$s（由 %2\$s 代发）",
                    "message_delegated_preview_sender_deleted" to "%1\$s（由已删除的助理代发）",
                ),
            "ja" to
                mapOf(
                    "message_delegated_sent_by" to "%1\$s が代理送信",
                    "message_delegated_sent_by_in_body" to "（%1\$s が代理送信）",
                    "message_delegated_sent_by_deleted" to "削除済みのアシスタントが代理送信",
                    "message_delegated_sent_by_deleted_in_body" to "（削除済みのアシスタントが代理送信）",
                    "message_delegated_preview_sender" to "%1\$s（%2\$s が代理送信）",
                    "message_delegated_preview_sender_deleted" to "%1\$s（削除済みのアシスタントが代理送信）",
                ),
        )

    @Test
    fun everyLocaleCarriesTheWebWords() {
        expected.forEach { (locale, strings) ->
            val catalog = catalog(locale)
            strings.forEach { (key, value) -> assertEquals(value, catalog[key], "$locale/$key") }
        }
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
