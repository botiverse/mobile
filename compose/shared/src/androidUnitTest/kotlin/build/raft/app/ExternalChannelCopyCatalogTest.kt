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
 * `layout.onboarding.freeJointChannelHosts`. The upgrade dialog's own copy for the external channel
 * gates (botiverse/slock #14533, staging 33f441958): `upgradeDialog.externalLimit.{title,hint}` and
 * `upgradeDialog.externalFrozen.{title,hint}` (`{server}` = `%1$s`). Home's External section
 * (botiverse/slock #15939, staging ad835ded6): `layout.sidebar.external` and
 * `layout.sidebar.createExternalChannel`. The last joint copy (botiverse/slock #16103, staging
 * 3c3eede9): `layout.systemNotifications.jointInviteBody` (`{server}` = `%1$s`),
 * `channel.joint.acceptFailed`, `channel.joint.serverLimit` (`{max}` = 30),
 * `channel.joint.inviteHostFreeLimit` and `channel.edit.failedConvertJoint`, with
 * `planBilling.usage.jointChannels` and zh-CN `thread.followers.peerRemoveHostOnly` (staging
 * 0f825229). The app's free-server, over-limit, read-only and disconnect strings have no web
 * counterpart and use the same term.
 */
class ExternalChannelCopyCatalogTest {
    private val expected =
        mapOf(
            "base" to
                mapOf(
                    "channel_settings_status_joint" to "External",
                    "home_section_external" to "External",
                    "home_section_create_external_channel" to "Create external channel",
                    "joint_disconnect_title" to "Disconnect external channel?",
                    "joint_connections_rule" to
                        "An external channel holds up to 30 servers and guests together, including the server that hosts it.",
                    "upgrade_dialog_general_hint" to
                        "Pro lifts the Free limits on message history, file uploads and external channels.",
                    "upgrade_dialog_benefit_files_pro" to "No monthly file limit, 200 MB per file",
                    "settings_billing_vo_free_summary" to
                        "Messages from the last 30 days · %2\$s of file uploads a month, %3\$s per file · 1 external channel",
                    "settings_billing_vo_uploads_includes" to "Counts files people and agents upload, Artifacts included.",
                    "settings_billing_vo_ending_body" to
                        "After that, this server moves to Free: messages older than 30 days are hidden, file uploads are limited to %2\$s a month and %3\$s per file, and agents can't move between computers.",
                    "upgrade_dialog_benefit_joint_pro" to "Unlimited external channels",
                    "upgrade_dialog_benefit_joint_free" to "Free: 1 external channel",
                    "upgrade_dialog_external_limit_title" to "Share more channels outside %1\$s",
                    "upgrade_dialog_external_limit_hint" to
                        "Free includes 1 external channel, and %1\$s already has one. On Pro, %1\$s can share as many as it needs.",
                    "upgrade_dialog_external_frozen_title" to "Post in your external channels again",
                    "upgrade_dialog_external_frozen_hint" to "Free allows 1 external channel. Upgrade to post in all of them again.",
                    "upgrade_dialog_external_freeze_upcoming_title" to "Keep posting in your external channels",
                    "upgrade_dialog_external_freeze_upcoming_hint" to
                        "Free allows 1 external channel. From %1\$s, %2\$s can't post in its external channels. Upgrade to keep posting in all of them.",
                    "upgrade_dialog_external_freeze_upcoming_done" to "%1\$s can keep posting in all its external channels.",
                    "external_frozen_title" to "%1\$s can't post in external channels right now",
                    "external_frozen_body_manager" to "Free allows 1 external channel. Upgrade, or leave the extra channels, to post again.",
                    "external_frozen_body_member" to "Free allows 1 external channel. Ask an owner to upgrade.",
                    "joint_invite_notification_body" to "%1\$s invited this server to an external channel.",
                    "joint_invite_accept_failed" to "Couldn't accept the external channel invite.",
                    "joint_limit_servers" to
                        "External channels are limited to 30 servers. To request a higher limit, send feedback from Settings.",
                    "joint_limit_host_free" to
                        "The server hosting this external channel has reached its external channel limit. Ask its admin to make room, then accept the invite again.",
                    "channel_conversion_retry_failed" to "Failed to convert channel to external channel",
                    "settings_billing_vo_joint_channels" to "External channels you host",
                    "joint_limit_free_servers" to
                        "This external channel already has 2 free servers. Upgrade either side to Pro, or have a free server leave, to continue (30 servers max).",
                    "joint_limit_free_servers_neutral" to
                        "This external channel already has 2 free servers. To continue, a free server has to leave or one side needs a Pro plan (30 servers max).",
                    "joint_over_limit_grace_title" to "External channel over limit",
                    "joint_over_limit_grace_detail" to
                        "This external channel is over its member limit until %1\$s. An admin of one server must upgrade, or one free server must leave.",
                    "joint_billing_locked_title" to "External channel read-only",
                    "joint_disconnect_failed" to "Failed to disconnect external channel",
                ),
            "zh-rCN" to
                mapOf(
                    "channel_settings_status_joint" to "外部",
                    "home_section_external" to "外部",
                    "home_section_create_external_channel" to "创建外部频道",
                    "joint_disconnect_title" to "断开外部频道？",
                    "joint_connections_rule" to "外部频道的服务器和访客加起来最多 30 个，包括托管频道的服务器。",
                    "upgrade_dialog_general_hint" to "升级到 Pro，免费版在消息历史、文件上传和外部频道上的限制都会解除。",
                    "upgrade_dialog_benefit_migration_pro" to "在计算机之间迁移 Agent",
                    "upgrade_dialog_migration_title" to "在计算机之间迁移 Agent",
                    "upgrade_dialog_migration_hint" to "把 Agent 迁移到另一台计算机是 Pro 功能。",
                    "upgrade_dialog_limit_migration" to "免费版不包含把 Agent 迁移到另一台计算机。",
                    "channel_external_invite_error_free_limit" to
                        "免费版下，一个服务器只能有一个与外部的人共享的频道。升级到 Pro 即可邀请到更多频道。",
                    "settings_billing_vo_free_summary" to "可查看最近 30 天的消息 · 每月可上传 %2\$s 文件，单个文件 %3\$s · 1 个外部频道",
                    "settings_billing_vo_uploads_includes" to "成员和 Agent 上传的文件都算在内，包括 Artifact。",
                    "settings_billing_vo_ending_body" to
                        "之后这个服务器改为免费版：超过 30 天的消息会被隐藏，文件上传限制为每月 %2\$s、单个文件 %3\$s，也不能在计算机之间迁移 Agent。",
                    "upgrade_dialog_benefit_joint_pro" to "不限数量的外部频道",
                    "upgrade_dialog_benefit_joint_free" to "免费版：1 个外部频道",
                    "upgrade_dialog_external_limit_title" to "与 %1\$s 之外的人共享更多频道",
                    "upgrade_dialog_external_limit_hint" to "免费版包含 1 个外部频道，%1\$s 已经有一个了。升级到 Pro 后，%1\$s 需要多少就能共享多少。",
                    "upgrade_dialog_external_frozen_title" to "恢复在外部频道中发言",
                    "upgrade_dialog_external_frozen_hint" to "免费版只能有 1 个外部频道。升级后即可在所有外部频道中恢复发言。",
                    "upgrade_dialog_external_freeze_upcoming_title" to "继续在外部频道中发言",
                    "upgrade_dialog_external_freeze_upcoming_hint" to
                        "免费版只能有 1 个外部频道。从 %1\$s 起，%2\$s 将无法在这些频道中发消息。升级后可以在所有外部频道中继续发言。",
                    "upgrade_dialog_external_freeze_upcoming_done" to "%1\$s 可以继续在所有外部频道中发言。",
                    "external_frozen_title" to "%1\$s 现在不能在外部频道中发言",
                    "external_frozen_body_manager" to "免费版只能有 1 个外部频道。升级，或退出多出来的频道，即可重新发言。",
                    "external_frozen_body_member" to "免费版只能有 1 个外部频道。请所有者升级。",
                    "joint_invite_notification_body" to "%1\$s 邀请本服务器加入外部频道。",
                    "joint_invite_accept_failed" to "接受外部频道邀请失败",
                    "joint_limit_servers" to "外部频道最多 30 个服务器。如需提高上限，请在设置里提交反馈。",
                    "joint_limit_host_free" to "托管这个外部频道的服务器已达到外部频道数量上限。请联系对方管理员处理后，再重新接受邀请。",
                    "channel_conversion_retry_failed" to "将频道转换为外部频道失败",
                    "settings_billing_vo_joint_channels" to "你托管的外部频道",
                    "thread_followers_peer_remove_host_only" to "只有托管服务器的管理员和这个频道的管理员，可以移除其他服务器的 Agent。",
                    "joint_limit_free_servers" to
                        "这个外部频道已满 2 个免费服务器。把任一方升级到 Pro，或有免费服务器退出后，可以继续加入（总服务器上限 30 个）",
                    "joint_limit_free_servers_neutral" to
                        "这个外部频道已满 2 个免费服务器。需要有免费服务器退出，或任一方使用 Pro 套餐，才能继续加入（总服务器上限 30 个）",
                    "joint_over_limit_grace_title" to "外部频道已超出成员上限",
                    "joint_over_limit_grace_detail" to
                        "该外部频道在 %1\$s 之前处于宽限期且超出成员上限。需要其中一个服务器的管理员升级，或让一个免费服务器退出。",
                    "joint_billing_locked_title" to "外部频道只读",
                    "joint_disconnect_failed" to "断开外部频道失败",
                ),
            "ja" to
                mapOf(
                    "channel_settings_status_joint" to "外部",
                    "home_section_external" to "外部",
                    "home_section_create_external_channel" to "外部チャンネルを作成",
                    "joint_disconnect_title" to "外部チャンネルの接続を解除しますか？",
                    "joint_connections_rule" to
                        "外部チャンネルに参加できるのは、ホストのサーバーを含め、サーバーとゲストを合わせて最大 30 までです。",
                    "upgrade_dialog_general_hint" to
                        "Pro にすると、メッセージ履歴、ファイルのアップロード、外部チャンネルに関する無料プランの制限がなくなります。",
                    "upgrade_dialog_benefit_files_pro" to "月間上限なし、1 ファイル 200 MB",
                    "settings_billing_vo_free_summary" to
                        "直近 30 日間のメッセージ · 毎月 %2\$s までのファイルアップロード、1 ファイル %3\$s まで · 外部チャンネル 1 つ",
                    "settings_billing_vo_uploads_includes" to "メンバーとエージェントがアップロードしたファイルが対象で、Artifact も含まれます。",
                    "settings_billing_vo_ending_body" to
                        "その後このサーバーは Free になり、30 日より前のメッセージは非表示になり、ファイルアップロードは毎月 %2\$s、1 ファイル %3\$s までになり、コンピューター間でエージェントを移動できなくなります。",
                    "upgrade_dialog_benefit_joint_pro" to "外部チャンネル数が無制限",
                    "upgrade_dialog_benefit_joint_free" to "Free：外部チャンネル 1 つまで",
                    "upgrade_dialog_external_limit_title" to "%1\$s の外部の人とより多くのチャンネルを共有",
                    "upgrade_dialog_external_limit_hint" to
                        "無料プランの外部チャンネルは 1 つまでで、%1\$s はすでに 1 つ使っています。Pro なら、%1\$s は必要なだけチャンネルを共有できます。",
                    "upgrade_dialog_external_frozen_title" to "外部チャンネルへの投稿を再開",
                    "upgrade_dialog_external_frozen_hint" to
                        "無料プランの外部チャンネルは 1 つまでです。アップグレードすると、すべての外部チャンネルに再び投稿できます。",
                    "upgrade_dialog_external_freeze_upcoming_title" to "外部チャンネルへの投稿を続ける",
                    "upgrade_dialog_external_freeze_upcoming_hint" to
                        "無料プランの外部チャンネルは 1 つまでです。%1\$s以降、%2\$s は外部チャンネルに投稿できなくなります。アップグレードすると、すべての外部チャンネルに投稿し続けられます。",
                    "upgrade_dialog_external_freeze_upcoming_done" to "%1\$s はすべての外部チャンネルに投稿し続けられます。",
                    "external_frozen_title" to "%1\$s は現在、外部チャンネルに投稿できません",
                    "external_frozen_body_manager" to "無料プランで使える外部チャンネルは 1 つです。アップグレードするか、余分なチャンネルから退出すると、再び投稿できます。",
                    "external_frozen_body_member" to "無料プランで使える外部チャンネルは 1 つです。オーナーにアップグレードを依頼してください。",
                    "joint_invite_notification_body" to "%1\$s がこのサーバーを外部チャンネルに招待しました。",
                    "joint_invite_accept_failed" to "外部チャンネルの招待を承諾できませんでした。",
                    "joint_limit_servers" to
                        "外部チャンネルに参加できるサーバーは最大 30 個です。上限の引き上げをご希望の場合は、設定からフィードバックを送信してください。",
                    "joint_limit_host_free" to
                        "この外部チャンネルをホストしているサーバーは、外部チャンネルの上限に達しています。そのサーバーの管理者に枠を空けてもらってから、もう一度招待を承諾してください。",
                    "channel_conversion_retry_failed" to "チャンネルを外部チャンネルに変換できませんでした",
                    "settings_billing_vo_joint_channels" to "ホストしている外部チャンネル",
                    "joint_limit_free_servers" to
                        "この外部チャンネルにはすでに無料サーバーが 2 個参加しています。続行するには、いずれかのサーバーを Pro にアップグレードするか、無料サーバーに退出してもらってください（サーバーは最大 30 個）。",
                    "joint_limit_free_servers_neutral" to
                        "この外部チャンネルにはすでに無料サーバーが 2 個参加しています。続行するには、無料サーバーが退出するか、いずれかのサーバーが Pro プランである必要があります（サーバーは最大 30 個）。",
                    "joint_over_limit_grace_title" to "外部チャンネルが上限超過です",
                    "joint_over_limit_grace_detail" to
                        "この外部チャンネルは %1\$s までメンバー数上限を超過しています。いずれかのサーバーの管理者がアップグレードするか、無料サーバーが脱退する必要があります。",
                    "joint_billing_locked_title" to "外部チャンネルは読み取り専用です",
                    "joint_disconnect_failed" to "外部チャンネルの接続を解除できませんでした",
                ),
        )

    private val retiredUpgradeJointKeys =
        listOf(
            "upgrade_dialog_joint_free_cap_title",
            "upgrade_dialog_joint_free_cap_hint",
            "upgrade_dialog_joint_host_limit_title",
            "upgrade_dialog_joint_host_limit_hint",
            "upgrade_dialog_joint_read_only_title",
            "upgrade_dialog_joint_read_only_hint",
            "upgrade_dialog_joint_over_limit_title",
            "upgrade_dialog_joint_over_limit_hint",
            "upgrade_dialog_limit_joint_free_cap",
            "upgrade_dialog_limit_joint_host_limit",
            "upgrade_dialog_limit_joint_read_only",
            "upgrade_dialog_limit_joint_over_limit",
            "upgrade_dialog_external_limit_hint_invite",
        )

    private val requiredGuestConnectAndPrivateRowKeys =
        listOf(
            "channel_details_other_servers_title",
            "channel_details_other_servers_not_connected",
            "channel_details_other_servers_connect",
            "joint_member_connect_guest_entry_why",
            "joint_member_connect_guest_create_server",
            "joint_member_connect_row_ask_approvers",
            "joint_member_connect_list_or_two",
            "joint_member_connect_list_or_end",
            "channel_external_invite_status_guest_elsewhere",
            "channel_external_invite_status_guest_elsewhere_unnamed",
            "channel_external_invite_status_guest_elsewhere_hint",
            "channel_external_invite_status_guest_elsewhere_unnamed_hint",
        )

    private val requiredFreezeKeys =
        listOf(
            "external_freeze_notice",
            "external_freeze_notice_ios",
            "external_frozen_title",
            "external_frozen_body_manager",
            "external_frozen_body_member",
            "external_frozen_body_ios_manager",
            "external_frozen_body_ios_member",
            "external_freeze_action_view_billing",
            "external_freeze_action_upgrade_to_pro",
            "external_freeze_action_ask_owner",
            "upgrade_dialog_external_freeze_upcoming_title",
            "upgrade_dialog_external_freeze_upcoming_hint",
            "upgrade_dialog_external_freeze_upcoming_done",
            "upgrade_dialog_limit_external_freeze_upcoming",
        )

    private val allLocales = listOf("base", "zh-rCN", "zh-rTW", "ja", "ko", "es", "pt-rBR", "ru")

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
        allLocales.forEach { locale ->
            val catalog = catalog(locale)
            requiredFreezeKeys.forEach { key ->
                assertFalse(catalog[key].isNullOrBlank(), "$locale missing $key")
            }
            requiredGuestConnectAndPrivateRowKeys.forEach { key ->
                assertFalse(catalog[key].isNullOrBlank(), "$locale missing $key")
            }
            retiredUpgradeJointKeys.forEach { retiredKey ->
                assertFalse(retiredKey in catalog, "$locale still defines retired $retiredKey")
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
