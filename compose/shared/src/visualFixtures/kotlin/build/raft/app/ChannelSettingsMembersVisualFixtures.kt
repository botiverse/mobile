package build.raft.app

import build.raft.app.base.BasePager
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page

internal const val CHANNEL_SETTINGS_MEMBERS_VISUAL_PAGE = "compose_channel_settings_members_grid_visual"

@Page(CHANNEL_SETTINGS_MEMBERS_VISUAL_PAGE, supportInLocal = true)
internal class ComposeChannelSettingsMembersVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val memberCount = pageData.params.optInt("memberCount").coerceAtLeast(0)
        val widthDp = pageData.params.optInt("widthDp").takeIf { it > 0 } ?: 390
        val canAddMembers = pageData.params.optBoolean("canAddMembers", true)
        val variant = pageData.params.optString("variant").trim()
        setContent {
            ChannelSettingsMembersVisualContent(
                memberCount = memberCount,
                widthDp = widthDp,
                canAddMembers = canAddMembers,
                variant = variant
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun ChannelSettingsMembersVisualContent(
    memberCount: Int,
    widthDp: Int,
    canAddMembers: Boolean,
    variant: String
) {
    val serverId = ServerId(VisualFixtureData.Server.ID)
    val channelId = ChannelId("visual-channel-settings-members")
    val members = visualChannelSettingsMembers(memberCount, serverId, variant)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.width(widthDp.dp).fillMaxHeight()) {
            ChannelSettingsPageScaffold(refreshing = false, onBack = {}) {
                ChannelSettingsContent(
                    channel =
                        HomeChannelRow(
                            threadId = ThreadId(channelId.value),
                            channelId = channelId,
                            channelType = "public",
                            name = VisualFixtureData.Server.SLUG,
                            subtitle = VisualFixtureData.Server.NAME,
                            memberCount = memberCount
                        ),
                    memberSlice =
                        SharedServerChannelMembersSlice(
                            serverId = serverId.value,
                            channelId = channelId.value,
                            members = members,
                            canAddMembers = canAddMembers,
                            status = SharedServerMembersLoadStatus.Loaded
                        ),
                    pinned = false,
                    canManage = false,
                    onEdit = {},
                    onOpenMembers = {},
                    onToggleMute = {},
                    onTogglePin = {},
                    onMakePrivate = {},
                    onArchive = {},
                    onDelete = {}
                )
            }
        }
    }
}

private data class VisualChannelMemberSeed(
    val id: String,
    val displayName: String,
    val handle: String,
    val kind: MemberKind,
    val avatar: String?
)

private fun visualChannelSettingsMembers(
    count: Int,
    serverId: ServerId,
    variant: String
): List<MemberProfile> {
    val seeds =
        listOf(
            VisualChannelMemberSeed(
                id = VisualFixtureData.Humans.Owner.MEMBER_ID,
                displayName = VisualFixtureData.Humans.Owner.DISPLAY_NAME,
                handle = VisualFixtureData.Humans.Owner.HANDLE,
                kind = MemberKind.Human,
                avatar = null
            ),
            VisualChannelMemberSeed(
                id = VisualFixtureData.Humans.Designer.ID,
                displayName = VisualFixtureData.Humans.Designer.DISPLAY_NAME,
                handle = VisualFixtureData.Humans.Designer.HANDLE,
                kind = MemberKind.Human,
                avatar = null
            ),
            VisualChannelMemberSeed(
                id = VisualFixtureData.Humans.Jiacheng.ID,
                displayName = VisualFixtureData.Humans.Jiacheng.DISPLAY_NAME,
                handle = VisualFixtureData.Humans.Jiacheng.HANDLE,
                kind = MemberKind.Human,
                avatar = null
            ),
            visualAgentSeed(
                id = VisualFixtureData.Agents.Cindy.ID,
                displayName = VisualFixtureData.Agents.Cindy.DISPLAY_NAME,
                avatar = VisualFixtureData.Agents.Cindy.AVATAR
            ),
            visualAgentSeed(
                id = VisualFixtureData.Agents.ProductUx.ID,
                displayName = VisualFixtureData.Agents.ProductUx.DISPLAY_NAME,
                avatar = VisualFixtureData.Agents.ProductUx.AVATAR
            ),
            visualAgentSeed(
                id = VisualFixtureData.Agents.AndroidDev4.ID,
                displayName = VisualFixtureData.Agents.AndroidDev4.DISPLAY_NAME,
                avatar = VisualFixtureData.Agents.AndroidDev4.AVATAR
            ),
            visualAgentSeed(
                id = VisualFixtureData.Agents.Builder.ID,
                displayName = VisualFixtureData.Agents.Builder.DISPLAY_NAME,
                avatar = VisualFixtureData.Agents.Builder.AVATAR
            ),
            visualAgentSeed(
                id = VisualFixtureData.Agents.KmpExpert.ID,
                displayName = VisualFixtureData.Agents.KmpExpert.DISPLAY_NAME,
                avatar = VisualFixtureData.Agents.KmpExpert.AVATAR
            ),
            visualAgentSeed(
                id = VisualFixtureData.Agents.CcWow.ID,
                displayName = VisualFixtureData.Agents.CcWow.DISPLAY_NAME,
                avatar = VisualFixtureData.Agents.CcWow.AVATAR
            )
        )
    val longNames =
        listOf(
            "Product Experience Designer",
            "跨端界面体验负责人",
            "高橋モバイルデザイン担当",
            "Android Rendering Specialist",
            "视觉回归与可访问性",
            "プロダクト品質エンジニア"
        )
    return List(count) { index ->
        val seed = seeds[index % seeds.size]
        val cycle = index / seeds.size
        val displayName =
            when (variant) {
                "long-names" -> longNames[index % longNames.size]
                else -> if (cycle == 0) seed.displayName else "${seed.displayName} ${cycle + 1}"
            }
        val avatar =
            when {
                variant == "mixed-avatars" && index % 2 == 0 -> null
                seed.avatar != null -> AvatarReference.fromWireValue(seed.avatar)
                variant == "mixed-avatars" -> AvatarReference.Random(seed.id)
                else -> AvatarReference.Random("${seed.id}-$cycle")
            }
        MemberProfile(
            id = MemberId("${seed.id}-$index"),
            displayName = displayName,
            handle = "${seed.handle}-$index",
            kind = seed.kind,
            avatar = avatar,
            avatarLabel = displayName.take(2).uppercase(),
            serverId = serverId,
            serverName = VisualFixtureData.Server.NAME,
            serverSlug = VisualFixtureData.Server.SLUG
        )
    }
}

private fun visualAgentSeed(id: String, displayName: String, avatar: String): VisualChannelMemberSeed =
    VisualChannelMemberSeed(
        id = id,
        displayName = displayName,
        handle = "@${displayName.lowercase().replace(' ', '-')}",
        kind = MemberKind.Agent,
        avatar = avatar
    )
