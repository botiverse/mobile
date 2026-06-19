package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.components.SlockBrutalActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable

const val SETTINGS_COMPUTERS_PAGE = "compose_settings_computers"
const val SETTINGS_COMPUTER_DETAIL_PAGE = "compose_settings_computer_detail"

@Page(SETTINGS_COMPUTERS_PAGE, supportInLocal = true)
internal class ComposeSettingsComputersPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsComputersScreen() }
    }
}

@Page(SETTINGS_COMPUTER_DETAIL_PAGE, supportInLocal = true)
internal class ComposeSettingsComputerDetailPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsComputerDetailScreen() }
    }
}

@Serializable
internal data class KuiklySettingsComputersPayload(
    val instanceId: String = "",
    val status: String = "content",
    val computers: List<KuiklySettingsComputerItem> = emptyList(),
    val canAddComputer: Boolean = false,
    val addComputerUnavailableReason: String? = null,
    val latestDaemonVersion: String? = null,
    val emptyTitle: String = "No computers yet",
    val emptyDetail: String = "Connect a desktop daemon to see it here."
)

@Serializable
internal data class KuiklySettingsComputerDetailPayload(
    val instanceId: String = "",
    val computer: KuiklySettingsComputerItem = KuiklySettingsComputerItem(),
    val workspaces: List<KuiklySettingsWorkspaceItem> = emptyList(),
    val agents: List<KuiklySettingsAgentItem> = emptyList(),
    val canOpenAgents: Boolean = false,
    val canManageMachines: Boolean = false,
    val workspacesErrorCode: String? = null,
    val workspacesRetryable: Boolean = false,
    val agentsErrorCode: String? = null,
    val agentsRetryable: Boolean = false
)

@Serializable
internal data class KuiklySettingsComputerItem(
    val id: String = "",
    val name: String = "Computer",
    val daemonVersion: String = "",
    val online: Boolean = false,
    val hostname: String? = null,
    val platform: String? = null,
    val lastSeenLabel: String? = null,
    val runtimes: List<String> = emptyList(),
    val createdAt: String? = null,
    val apiKeyPrefix: String? = null,
    val statusVersion: Long = 0L
)

@Serializable
internal data class KuiklySettingsWorkspaceItem(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val statusLabel: String? = null,
    val lastActiveLabel: String? = null
)

@Serializable
internal data class KuiklySettingsAgentItem(
    val id: String = "",
    val name: String = "",
    val statusLabel: String? = null,
    val ownerLabel: String? = null,
    val displayName: String? = null,
    val handle: String? = null,
    val description: String? = null,
    val presenceLabel: String? = null,
    val roleLabel: String? = null
)

@Composable
private fun SettingsComputersScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberComputersPayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.toString(),
        pager.pageData.params.optString("instanceId")
    )
    LaunchedEffect(payload) {
        bridge.log("settings_computers_render instanceId=${payload.instanceId} computers=${payload.computers.size}")
    }
    SettingsComputersContent(payload) { action, itemId ->
        bridge.settingsEvent(payload.instanceId, action, itemId)
    }
}

@Composable
private fun SettingsComputerDetailScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberComputerDetailPayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.toString(),
        pager.pageData.params.optString("instanceId")
    )
    LaunchedEffect(payload) {
        bridge.log("settings_computer_detail_render instanceId=${payload.instanceId} workspaces=${payload.workspaces.size} agents=${payload.agents.size}")
    }
    SettingsComputerDetailContent(payload) { action, itemId ->
        bridge.settingsEvent(payload.instanceId, action, itemId)
    }
}

@Composable
private fun rememberComputersPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsComputersPayload =
    androidx.compose.runtime.remember(payload, fallbackPageData, instanceId) {
        parseSettingsComputersPayload(payload, fallbackPageData, instanceId)
    }

@Composable
private fun rememberComputerDetailPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsComputerDetailPayload =
    androidx.compose.runtime.remember(payload, fallbackPageData, instanceId) {
        parseSettingsComputerDetailPayload(payload, fallbackPageData, instanceId)
    }

@Composable
internal fun SettingsComputersContent(
    payload: KuiklySettingsComputersPayload,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onEvent: (String, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (payload.canAddComputer) {
            item { SettingsPrimaryButton("Add computer") { onEvent("addComputer", null) } }
        }
        payload.latestDaemonVersion?.takeIf { it.isNotBlank() }?.let { version ->
            item { SettingsInfoStrip("Latest daemon", version) }
        }
        if (payload.computers.isEmpty()) {
            item { SettingsEmptyMessage(payload.emptyTitle, payload.emptyDetail) }
        } else {
            items(payload.computers.size, key = { index -> "computer-$index-${payload.computers[index].id}" }) { index ->
                SettingsComputerCard(payload.computers[index]) {
                    onEvent("openComputer", payload.computers[index].id)
                }
            }
        }
        payload.addComputerUnavailableReason?.takeIf { it.isNotBlank() }?.let { reason ->
            item { SettingsInfoStrip("Add computer", reason) }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
internal fun SettingsComputerDetailContent(
    payload: KuiklySettingsComputerDetailPayload,
    actionState: KuiklySettingsComputerActionState? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onEvent: (String, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsComputerCard(payload.computer, onClick = null) }
        item { SettingsSectionTitle("INFO") }
        item { SettingsDetailRow("Platform", payload.computer.platform ?: "Unknown") }
        item { SettingsDetailRow("Hostname", payload.computer.hostname ?: payload.computer.name) }
        item { SettingsDetailRow("Last seen", payload.computer.lastSeenLabel ?: "Unknown") }
        item { SettingsDetailRow("Created", payload.computer.createdAt ?: "Unknown") }
        if (payload.computer.runtimes.isNotEmpty()) {
            item { SettingsDetailRow("Detected runtimes", payload.computer.runtimes.joinToString(", ")) }
        }
        if (!payload.computer.apiKeyPrefix.isNullOrBlank()) {
            item { SettingsDetailRow("API key prefix", payload.computer.apiKeyPrefix) }
        }
        item { SettingsSectionTitle("WORKSPACES") }
        payload.workspacesErrorCode?.let { error ->
            item { SettingsInlineError("Unable to load workspaces", error, payload.workspacesRetryable) { onEvent("retryWorkspaces", null) } }
        } ?: if (payload.workspaces.isEmpty()) {
            item { SettingsEmptyMessage("No workspaces", "Workspaces opened by this computer will show up here.") }
        } else {
            items(payload.workspaces.size, key = { index -> "workspace-$index-${payload.workspaces[index].id}" }) { index ->
                SettingsWorkspaceCard(payload.workspaces[index])
            }
        }
        item { SettingsSectionTitle("AGENTS") }
        payload.agentsErrorCode?.let { error ->
            item { SettingsInlineError("Unable to load agents", error, payload.agentsRetryable) { onEvent("retryAgents", null) } }
        } ?: if (payload.agents.isEmpty()) {
            item { SettingsEmptyMessage("No agents", "Agents running on this computer will show up here.") }
        } else {
            items(payload.agents.size, key = { index -> "agent-$index-${payload.agents[index].id}" }) { index ->
                SettingsAgentCard(
                    item = payload.agents[index],
                    onClick =
                        if (payload.canOpenAgents) {
                            { onEvent("openAgent", payload.agents[index].id) }
                        } else {
                            null
                        }
                )
            }
        }
        if (payload.canManageMachines) {
            item { SettingsSectionTitle("ACTIONS") }
            item {
                // #78: rename / delete / rotate-key UI surface.
                // Each button dispatches via onEvent(...) which the host
                // translates to SharedSettingsIntent.RenameComputer /
                // .DeleteComputer / .RotateComputerKey. The actionState
                // disables buttons + swaps the label while the request is
                // in flight. Errors surface via the existing
                // settingsComputerErrorCopy mapping (see #74 commit
                // cf01a0a4) for codes like computer_has_agents / 409.
                val computerId = payload.computer.id
                val renameBusy = actionState?.action == "renameComputer" && actionState.inProgress && actionState.itemId == computerId
                val deleteBusy = actionState?.action == "deleteComputer" && actionState.inProgress && actionState.itemId == computerId
                val rotateBusy = actionState?.action == "rotateComputerKey" && actionState.inProgress && actionState.itemId == computerId
                val anyBusy = renameBusy || deleteBusy || rotateBusy
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsActionRow(
                        title = "Rename computer",
                        description = "Change this computer's display name.",
                        actionLabel = if (renameBusy) "Renaming..." else "Rename",
                        enabled = !anyBusy,
                        onClick = { onEvent("renameComputer", computerId) }
                    )
                    SettingsActionRow(
                        title = "Rotate API key",
                        description = "Generate a new key for the daemon to reconnect. The old key stops working immediately.",
                        actionLabel = if (rotateBusy) "Rotating..." else "Rotate key",
                        enabled = !anyBusy,
                        onClick = { onEvent("rotateComputerKey", computerId) }
                    )
                    SettingsActionRow(
                        title = "Delete computer",
                        description = "Permanently remove this computer. All agents must be deleted first.",
                        actionLabel = if (deleteBusy) "Deleting..." else "Delete",
                        enabled = !anyBusy,
                        destructive = true,
                        onClick = { onEvent("deleteComputer", computerId) }
                    )
                    actionState?.errorCode?.takeIf { it.isNotBlank() && actionState.itemId == computerId }?.let { code ->
                        SettingsInlineError(
                            title = settingsComputerErrorTitle(code),
                            errorCode = code,
                            retryable = false
                        ) { onEvent("retryComputerAction", actionState.action) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Serializable
internal data class KuiklySettingsComputerActionState(
    val action: String = "",
    val itemId: String? = null,
    val inProgress: Boolean = false,
    val errorCode: String? = null
)

private fun settingsComputerErrorTitle(code: String): String = when (code) {
    "computer_has_agents" -> "Cannot delete computer"
    "computer_bad_request" -> "Computer request rejected"
    "computer_offline_replay" -> "Computer offline"
    "machine_offline_replay" -> "Computer offline"
    "machine_offline" -> "Computer offline"
    "server_forbidden" -> "Permission denied"
    "auth_required" -> "Session expired"
    else -> "Action failed"
}

@Composable
private fun SettingsActionRow(
    title: String,
    description: String,
    actionLabel: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        Text(
            description,
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        SlockBrutalActionButton(
            label = actionLabel,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = if (destructive) SlockColor.AccentRed else SlockColor.BrandYellow,
            minWidth = 0.dp,
            minHeight = 40.dp,
            shadowOffset = 2.dp,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingsComputerCard(item: KuiklySettingsComputerItem, onClick: (() -> Unit)?) {
    // #77: align the Settings Computers list card with React's
    // `MobileComputersPanel.tsx` (107 行) and `Sidebar.tsx` line 1248-1290 list
    // pattern — each card shows name + status dot + daemon version line.
    // host/platform/createdAt/lastHeartbeat/runtimes are intentionally NOT
    // rendered here; they live on the ComputerDetail page (see #74 commit
    // `cf01a0a4`) and `MachineDetailPanel.tsx` Info / Detected Runtimes
    // sections. This matches the user's "align to React" + "do not fabricate
    // server-side fields" guard rails.
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconBox(SlockLucideIconName.Monitor, SlockColor.BrandYellow)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                OnlineDot(item.online)
            }
            val daemonVersion = item.daemonVersion.takeIf { it.isNotBlank() }
            Text(
                text = if (daemonVersion != null) "daemon v$daemonVersion" else "daemon offline",
                style = SlockType.Input.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (daemonVersion != null) SlockColor.TextMutedGray else SlockColor.EmptyStateGray,
                    fontStyle = if (daemonVersion != null) null else FontStyle.Italic
                ),
                color = if (daemonVersion != null) SlockColor.TextMutedGray else SlockColor.EmptyStateGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsWorkspaceCard(item: KuiklySettingsWorkspaceItem) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(item.name, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.path, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        listOf(item.statusLabel, item.lastActiveLabel).filterNotNull().filter { it.isNotBlank() }.joinToString("  ").takeIf { it.isNotBlank() }?.let {
            Text(it, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        }
    }
}

@Composable
private fun SettingsAgentCard(item: KuiklySettingsAgentItem, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsAgentAvatar(item)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.displayName?.takeIf { it.isNotBlank() } ?: item.name, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                item.statusLabel?.takeIf { it.isNotBlank() }?.let { SmallPill(it) }
            }
            listOf(item.handle, item.ownerLabel, item.presenceLabel).filterNotNull().filter { it.isNotBlank() }.joinToString("  ").takeIf { it.isNotBlank() }?.let {
                Text(it, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
}

@Composable
private fun SettingsDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, modifier = Modifier.width(92.dp))
        Text(value, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsInlineError(title: String, errorCode: String, retryable: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        Text(settingsComputerErrorCopy(errorCode), style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        if (retryable) {
            SettingsPrimaryButton("Retry", onRetry)
        }
    }
}

private fun settingsComputerErrorCopy(code: String): String = when (code) {
    "machine_offline" -> "This computer is offline. The daemon is not connected to the server, so workspaces and agents can't be loaded right now."
    "machine_offline_replay" -> "This computer is being served by a different server replica. Try again in a moment."
    "computer_has_agents" -> "This computer still has agents assigned. Remove all agents before deleting the computer."
    "computer_bad_request" -> "The request was rejected by the server. Check the name / value and try again."
    "server_forbidden" -> "You don't have permission to manage this computer."
    "auth_required" -> "Your session expired. Sign in again to continue."
    "server_unavailable" -> "The server is temporarily unavailable. Try again in a moment."
    else -> "Error: $code"
}

@Composable
private fun SettingsEmptyMessage(title: String, detail: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        }
    }
}

@Composable
private fun SettingsInfoStrip(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(label, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        Text(value, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingsPrimaryButton(text: String, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = SlockColor.BrandYellow,
        minWidth = 0.dp,
        minHeight = 44.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.Button.copy(color = SlockColor.TextBlack)
    )
}

@Composable
private fun IconBox(iconName: SlockLucideIconName, color: Color) {
    Box(
        modifier = Modifier.size(42.dp).background(color).border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(name = iconName, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun OnlineDot(online: Boolean) {
    SlockStatusIndicator(color = if (online) SlockColor.AccentCyan else SlockColor.EmptyStateGray)
}

@Composable
private fun SettingsAgentAvatar(item: KuiklySettingsAgentItem) {
    SlockAvatarWithStatus(
        statusColor = item.settingsAgentStatusColor(),
        avatarSize = 42.dp,
        statusSize = 10.dp
    ) {
        IconBox(SlockLucideIconName.Bot, SlockColor.AccentCyan)
    }
}

private fun KuiklySettingsAgentItem.settingsAgentStatusColor(): Color =
    when ((statusLabel ?: presenceLabel).orEmpty().trim().lowercase()) {
        "online", "active", "connected", "working", "running", "thinking" -> SlockColor.AccentLime
        "idle", "away", "busy" -> SlockColor.BrandYellow
        "error", "failed", "failure" -> SlockColor.AccentRed
        "offline", "disconnected", "not connected", "inactive" -> SlockColor.EmptyStateGray
        else -> SlockColor.BrandYellow
    }

@Composable
private fun SmallPill(text: String) {
    Box(
        modifier = Modifier.background(SlockColor.BrandYellow).border(1.dp, SlockColor.BorderBlack).padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = SlockType.Input.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1)
    }
}
