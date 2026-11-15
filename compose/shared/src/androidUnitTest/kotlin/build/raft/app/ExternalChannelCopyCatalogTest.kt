package build.raft.app

import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The copy botiverse/slock #14932 moved from "Joint Channel" to "external channel", as staging
 * `packages/web/src/i18n/messages/{en,zh-cn,ja}.ts` writes it (pinned at slock 8600b62c6):
 * `channel.edit.jointChannelBadge`, `channel.edit.disconnectJointChannel`,
 * `channel.edit.connectedServersRule` (`{max}` = `MAX_JOINT_CHANNEL_SERVERS`, 30),
 * `upgradeDialog.general.hint`, `layout.onboarding.proUnlimitedJointChannels` and
 * `layout.onboarding.freeJointChannelHosts`.
 */
class ExternalChannelCopyCatalogTest {
    private val expected =
        mapOf(
            "base" to
                mapOf(
                    "channel_settings_status_joint" to "External",
                    "joint_disconnect_title" to "Disconnect external channel?",
                    "joint_connections_rule" to
                        "An external channel holds up to 30 servers and guests together, including the server that hosts it.",
                    "upgrade_dialog_general_hint" to
                        "Pro lifts the Free limits on chat history, file uploads and external channels.",
                    "upgrade_dialog_benefit_joint_pro" to "Unlimited external channels",
                    "upgrade_dialog_benefit_joint_free" to "Free: 1 external channel",
                ),
            "zh-rCN" to
                mapOf(
                    "channel_settings_status_joint" to "外部",
                    "joint_disconnect_title" to "断开外部频道？",
                    "joint_connections_rule" to "外部频道的服务器和访客加起来最多 30 个，包括托管频道的服务器。",
                    "upgrade_dialog_general_hint" to "升级到 Pro，免费版在聊天记录、文件上传和外部频道上的限制都会解除。",
                    "upgrade_dialog_benefit_joint_pro" to "不限数量的外部频道",
                    "upgrade_dialog_benefit_joint_free" to "免费版：1 个外部频道",
                ),
            "ja" to
                mapOf(
                    "channel_settings_status_joint" to "外部",
                    "joint_disconnect_title" to "外部チャンネルの接続を解除しますか？",
                    "joint_connections_rule" to
                        "外部チャンネルに参加できるのは、ホストのサーバーを含め、サーバーとゲストを合わせて最大 30 までです。",
                    "upgrade_dialog_general_hint" to
                        "Pro にすると、チャット履歴、ファイルのアップロード、外部チャンネルに関する無料プランの制限がなくなります。",
                    "upgrade_dialog_benefit_joint_pro" to "外部チャンネル数が無制限",
                    "upgrade_dialog_benefit_joint_free" to "Free：外部チャンネル 1 つまで",
                ),
        )

    @Test
    fun everyLocaleWritesTheWebExternalChannelCopy() {
        expected.forEach { (locale, strings) ->
            val catalog = catalog(locale)
            strings.forEach { (key, text) ->
                val shown = catalog.getValue(key).replace("%1\$d", JOINT_CHANNEL_MAX_SERVERS.toString())
                assertEquals(text, shown, "$locale/$key")
                listOf("Joint", "joint", "联合", "共同").forEach { retired ->
                    assertFalse(retired in shown, "$locale/$key still says $retired")
                }
            }
        }
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
