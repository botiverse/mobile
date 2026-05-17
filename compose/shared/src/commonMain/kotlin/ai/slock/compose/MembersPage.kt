package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Presence
import ai.slock.compose.model.toGravatarAvatarUrl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.Image
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable

const val MEMBERS_PAGE = "compose_members"
const val MEMBERS_SMOKE_PAGE = "compose_members_smoke"
private const val MEMBERS_PAGE_DATA_EVENT = "slockPageData"

private val MembersPageInset = SlockSpacing.Md
private val MemberAvatarSize = 34.dp
private val MemberAvatarFontSize = 12.sp
private val MemberRowMinHeight = 48.dp

@Page(MEMBERS_SMOKE_PAGE, supportInLocal = true)
internal class ComposeMembersSmokePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            MembersListContent(
                payload = membersSmokePayload,
                onBack = {},
                onAddHuman = {},
                onOpenMember = {}
            )
        }
    }
}

@Page(MEMBERS_PAGE, supportInLocal = true)
internal class ComposeMembersPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { MembersPageContent() }
    }
}

@Serializable
data class KuiklyMembersPayload(
    val instanceId: String = "",
    val title: String = "Members",
    val labels: KuiklyMembersLabels = KuiklyMembersLabels(),
    val members: List<MemberProfile> = emptyList()
)

@Serializable
data class KuiklyMembersLabels(
    val agents: String = "AGENTS",
    val humans: String = "HUMANS",
    val addHuman: String = "Add human"
)

private val membersSmokePayload = KuiklyMembersPayload(
    title = "Members",
    members = listOf(
        MemberProfile(
            id = ai.slock.compose.model.MemberId("cindy"),
            displayName = "Cindy",
            handle = "Onboarding Assistant",
            kind = MemberKind.Agent,
            description = "Onboarding Assistant",
            roleDescription = "agent",
            avatar = ai.slock.compose.model.AvatarReference.Pixel("heart"),
            avatarLabel = "C",
            presence = ai.slock.compose.model.Presence.Active,
            agentStatus = ai.slock.compose.model.AgentStatus.Online,
            permissionBadges = listOf("computer:MacBook Pro")
        ),
        MemberProfile(
            id = ai.slock.compose.model.MemberId("builder"),
            displayName = "Builder",
            handle = "@builder",
            kind = MemberKind.Agent,
            description = "File and repo automation",
            avatar = ai.slock.compose.model.AvatarReference.Pixel("robot"),
            avatarLabel = "B",
            presence = ai.slock.compose.model.Presence.Away,
            agentStatus = ai.slock.compose.model.AgentStatus.Working,
            permissionBadges = listOf("computer:MacBook Pro")
        ),
        MemberProfile(
            id = ai.slock.compose.model.MemberId("artin"),
            displayName = "Artin",
            handle = "@artin",
            kind = MemberKind.Human,
            description = "Owner",
            avatarLabel = "A",
            presence = ai.slock.compose.model.Presence.Active
        ),
        MemberProfile(
            id = ai.slock.compose.model.MemberId("jiacheng"),
            displayName = "Jiacheng",
            handle = "@jiacheng",
            kind = MemberKind.Human,
            description = "Android",
            avatarLabel = "J",
            presence = ai.slock.compose.model.Presence.Away
        )
    )
)

@Composable
internal fun MembersPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    var payloadText by remember { mutableStateOf(pager.pageData.params.optString("payload")) }
    var instanceId by remember { mutableStateOf(pager.pageData.params.optString("instanceId")) }
    DisposableEffect(pager) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != MEMBERS_PAGE_DATA_EVENT) return
                    eventData.optString("payload").takeIf { it.isNotBlank() }?.let {
                        payloadText = it
                    }
                    eventData.optString("instanceId").takeIf { it.isNotBlank() }?.let {
                        instanceId = it
                    }
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val payload = remember(payloadText, instanceId) {
        val decodedPayload = payloadText.toKuiklyMembersPayload()
        if (instanceId.isBlank() || decodedPayload.instanceId == instanceId) {
            decodedPayload
        } else {
            decodedPayload.copy(instanceId = instanceId)
        }
    }

    MembersListContent(
        payload = payload,
        onBack = { bridge.settingsEvent(payload.instanceId, "back", null) },
        onAddHuman = { bridge.settingsEvent(payload.instanceId, "addHuman", null) },
        onOpenMember = { bridge.settingsEvent(payload.instanceId, "openMember", it.id.value) }
    )
}

private fun String.toKuiklyMembersPayload(): KuiklyMembersPayload {
    if (isBlank()) return KuiklyMembersPayload()
    val payload = JSONObject(this)
    val labelsJson = payload.optJSONObject("labels")
    val membersJson = payload.optJSONArray("members")
    return KuiklyMembersPayload(
        instanceId = payload.optString("instanceId"),
        title = payload.optString("title").ifBlank { "Members" },
        labels = KuiklyMembersLabels(
            agents = labelsJson?.optString("agents").orEmpty().ifBlank { "AGENTS" },
            humans = labelsJson?.optString("humans").orEmpty().ifBlank { "HUMANS" },
            addHuman = labelsJson?.optString("addHuman").orEmpty().ifBlank { "Add human" }
        ),
        members =
            buildList {
                if (membersJson == null) return@buildList
                for (index in 0 until membersJson.length()) {
                    add(membersJson.optJSONObject(index).toMemberProfile())
                }
            }
    )
}

private fun JSONObject?.toMemberProfile(): MemberProfile {
    if (this == null) return KuiklyMembersPayload().members.firstOrNull() ?: MemberProfile(
        id = MemberId("unknown"),
        displayName = "Unknown",
        handle = "@unknown",
        kind = MemberKind.Human
    )
    val avatarJson = optJSONObject("avatar")
    val avatarWireValue =
        avatarJson?.optString("wireValue")
            ?: optString("avatarWireValue").takeIf { it.isNotBlank() }
            ?: optString("avatarUrl").takeIf { it.isNotBlank() }
    return MemberProfile(
        id = MemberId(optString("id").ifBlank { "unknown" }),
        displayName = optString("displayName").ifBlank { "Unknown" },
        handle = optString("handle").ifBlank { "@unknown" },
        kind = optString("kind").toMemberKind(),
        description = optNullableString("description"),
        roleDescription = optNullableString("roleDescription"),
        avatarUrl = optNullableString("avatarUrl"),
        gravatarHash = optNullableString("gravatarHash"),
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        avatarLabel = optString("avatarLabel").ifBlank { optString("displayName").take(1).uppercase() },
        presence = optString("presence").toPresence(),
        agentStatus = AgentStatus.fromWireValue(optNullableString("agentStatus")),
        permissionBadges =
            buildList {
                val badges = this@toMemberProfile.optJSONArray("permissionBadges") ?: return@buildList
                for (index in 0 until badges.length()) {
                    badges.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
    )
}

private fun String.toMemberKind(): MemberKind =
    MemberKind.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: MemberKind.Human

private fun String.toPresence(): Presence =
    Presence.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: Presence.Offline

private fun JSONObject.optNullableString(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

@Composable
internal fun MembersListContent(
    payload: KuiklyMembersPayload,
    onBack: () -> Unit,
    onAddHuman: () -> Unit,
    onOpenMember: (MemberProfile) -> Unit
) {
    val groupedMembers = remember(payload.members) {
        payload.members.groupBy {
            when (it.kind) {
                MemberKind.Human -> payload.labels.humans
                MemberKind.Agent, MemberKind.System -> payload.labels.agents
            }
        }
    }
    val agents = groupedMembers[payload.labels.agents].orEmpty()
    val humans = groupedMembers[payload.labels.humans].orEmpty()
    var agentsExpanded by remember(payload.instanceId, agents.size) { mutableStateOf(true) }
    var humansExpanded by remember(payload.instanceId, humans.size) { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        MembersPageHeader(title = payload.title, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            item { Spacer(Modifier.height(SlockSpacing.Md)) }
            item {
                SlockCollapsibleSectionHeader(
                    label = payload.labels.agents,
                    count = agents.size,
                    expanded = agentsExpanded,
                    onExpandedChange = { agentsExpanded = it },
                    modifier = Modifier.padding(horizontal = MembersPageInset),
                    trailingContent = {
                        MembersAddButton(
                            label = payload.labels.addHuman,
                            onClick = onAddHuman
                        )
                    }
                )
            }
            if (agentsExpanded) {
                groupedAgentMembers(agents).forEach { group ->
                    item("group_${group.label}") {
                        Text(
                            text = group.label,
                            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                            color = SlockColor.TextMutedGray,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MembersPageInset)
                                .padding(top = SlockSpacing.Xs)
                        )
                    }
                    items(group.members.size) { index ->
                        val member = group.members[index]
                        MembersListRow(
                            member = member,
                            modifier = Modifier.padding(horizontal = MembersPageInset),
                            onClick = { onOpenMember(member) }
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(SlockSpacing.Sm))
                SlockCollapsibleSectionHeader(
                    label = payload.labels.humans,
                    count = humans.size,
                    expanded = humansExpanded,
                    onExpandedChange = { humansExpanded = it },
                    modifier = Modifier.padding(horizontal = MembersPageInset),
                    trailingContent = {
                        MembersAddButton(
                            label = payload.labels.addHuman,
                            onClick = onAddHuman
                        )
                    }
                )
            }
            if (humansExpanded) {
                items(humans.size) { index ->
                    val member = humans[index]
                    MembersListRow(
                        member = member,
                        modifier = Modifier.padding(horizontal = MembersPageInset),
                        onClick = { onOpenMember(member) }
                    )
                }
            }
            item { Spacer(Modifier.height(180.dp)) }
        }
    }
}

@Composable
private fun MembersPageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .padding(top = slockTopSafeAreaPadding())
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockHeaderIconAction("arrow-left", "Back to members", onBack)
        Text(
            text = title,
            style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SlockStroke.Default)
            .background(SlockColor.BorderBlack)
    )
}

@Composable
private fun MembersAddButton(label: String, onClick: () -> Unit) {
    SlockHeaderIconAction(icon = "plus", contentDescription = label, onClick = onClick)
}

@Composable
private fun MembersListRow(
    member: MemberProfile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MemberRowMinHeight)
            .clickable(onClick = onClick)
            .padding(vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersAvatar(member = member)
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = member.displayName,
                style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.5f, fill = false)
            )
            Text(
                text = member.description ?: member.handle,
                style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MembersAvatar(member: MemberProfile) {
    val avatarWireValue = remember(member.avatar, member.avatarUrl, member.gravatarHash) {
        member.avatar?.wireValue ?: member.avatarUrl ?: member.gravatarHash?.toGravatarAvatarUrl()
    }
    val pixelSpec = remember(avatarWireValue) { avatarWireValue?.toMembersPixelAvatarSpec() }
    Box(modifier = Modifier.size(MemberAvatarSize)) {
        when {
            pixelSpec != null -> MembersPixelAvatar(pixelSpec)
            avatarWireValue?.startsWith("http://") == true || avatarWireValue?.startsWith("https://") == true ->
                Box(
                    modifier = Modifier
                        .size(MemberAvatarSize)
                        .background(SlockColor.AccentLavender)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(avatarWireValue),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            else ->
                Box(
                    modifier = Modifier
                        .size(MemberAvatarSize)
                        .background(SlockColor.AccentLavender)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = member.avatarLabel,
                        style = SlockType.Button.copy(fontSize = MemberAvatarFontSize),
                        color = SlockColor.TextBlack,
                        maxLines = 1
                    )
                }
        }
    }
}

@Composable
private fun MembersPixelAvatar(spec: MembersPixelAvatarSpec) {
    Canvas(
        modifier = Modifier
            .size(MemberAvatarSize)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
    ) {
        drawRect(spec.background)
        val cellSize = size.width / PixelAvatarGridSize
        spec.grid.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, key ->
                val color = PixelAvatarPalette[key] ?: Color.Transparent
                if (color != Color.Transparent) {
                    drawRect(
                        color = color,
                        topLeft = Offset(columnIndex * cellSize, rowIndex * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}

private data class MembersAgentGroup(
    val label: String,
    val members: List<MemberProfile>
)

private fun groupedAgentMembers(members: List<MemberProfile>): List<MembersAgentGroup> =
    members
        .groupBy { member ->
            member.permissionBadges
                .firstNotNullOfOrNull { badge ->
                    badge.removePrefix("computer:")
                        .takeIf { badge.startsWith("computer:") && it.isNotBlank() }
                } ?: "Unknown computer"
        }
        .map { (label, groupedMembers) -> MembersAgentGroup(label, groupedMembers) }

private data class MembersPixelAvatarSpec(val background: Color, val grid: List<String>)

private const val PixelAvatarGridSize = 8f
private const val PixelAvatarProtocolPrefix = "pixel:"
private const val PixelAvatarRandomPrefix = "random:"
private const val PixelAvatarDefaultKey = "robot"

private val PixelAvatarPalette = mapOf(
    '_' to Color.Transparent,
    'K' to Color(0xFF141111),
    'W' to Color(0xFFFFFFFF),
    'P' to Color(0xFFFE7DA8),
    'O' to Color(0xFFF8A16F),
    'Y' to Color(0xFFFFD440),
    'L' to Color(0xFFA9D877),
    'C' to Color(0xFF27CCF3),
    'V' to Color(0xFFBBAFE6),
    'B' to Color(0xFF576574),
    'R' to Color(0xFFF97264),
    'G' to Color(0xFF9DCAAA)
)

private val MembersPixelAvatarFixedSpecs = mapOf(
    "robot" to MembersPixelAvatarSpec(PixelAvatarPalette.getValue('C'), listOf("_KKKKKK_", "KCKWWKCK", "KWKWWKWK", "KKKKKKKK", "_KWKKWK_", "_KKKKKK_", "_KK__KK_", "_KK__KK_")),
    "heart" to MembersPixelAvatarSpec(PixelAvatarPalette.getValue('P'), listOf("________", "_KK__KK_", "KRRKKRRK", "KRRRRRRK", "KRRRRRRK", "_KRRRRK_", "__KRRK__", "___KK___"))
)

private val PixelAvatarRandomOptions = listOf(
    PixelAvatarPalette.getValue('C') to 'K',
    PixelAvatarPalette.getValue('Y') to 'K',
    PixelAvatarPalette.getValue('L') to 'K',
    PixelAvatarPalette.getValue('P') to 'W',
    PixelAvatarPalette.getValue('V') to 'K',
    PixelAvatarPalette.getValue('O') to 'K',
    PixelAvatarPalette.getValue('C') to 'W'
)

private fun String.toMembersPixelAvatarSpec(): MembersPixelAvatarSpec? =
    removePrefixOrNull(PixelAvatarProtocolPrefix)?.let {
        MembersPixelAvatarFixedSpecs[it] ?: MembersPixelAvatarFixedSpecs.getValue(PixelAvatarDefaultKey)
    } ?: removePrefixOrNull(PixelAvatarRandomPrefix)?.let(::randomMembersPixelAvatar)

private fun String.removePrefixOrNull(prefix: String): String? =
    takeIf { startsWith(prefix) }?.removePrefix(prefix)?.ifBlank { null }

private fun randomMembersPixelAvatar(seed: String): MembersPixelAvatarSpec {
    val random = MembersPixelAvatarRandom(pixelHash(seed))
    val (background, fill) = PixelAvatarRandomOptions[(random.next() * PixelAvatarRandomOptions.size).toInt()]
    val grid = List(PixelAvatarGridSize.toInt()) { row ->
        buildString {
            repeat(PixelAvatarGridSize.toInt()) { column ->
                val mirroredColumn = if (column < 4) column else 7 - column
                append(
                    if (row in 1..6 && mirroredColumn in 1..3 && random.next() > 0.42) fill
                    else '_'
                )
            }
        }
    }
    return MembersPixelAvatarSpec(background, grid)
}

private fun pixelHash(input: String): Int =
    input.fold(0) { hash, character -> (hash * 31) + character.code }

private class MembersPixelAvatarRandom(seed: Int) {
    private var value = seed

    fun next(): Double {
        value += 1831565813
        var mixed = (value xor (value ushr 15)) * (1 or value)
        mixed = (mixed + ((mixed xor (mixed ushr 7)) * (61 or mixed))) xor mixed
        val unsigned = (mixed xor (mixed ushr 14)).toLong() and 0xffffffffL
        return unsigned.toDouble() / 4294967296.0
    }
}
