package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import build.raft.app.components.RaftAttentionDot
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.isAppUpdateHomeNotificationId
import build.raft.app.data.isMachineHomeNotificationId
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.StringResource

/**
 * The More tab (xxchan 2026-10-20): a plain list of pages that left the bottom bar. To add a
 * row, add an entry here and handle it where the shell opens More rows.
 *
 * [key] is the shell child-tab key for rows that open a shell page (Members, Settings), or a
 * stable row id for rows that push a standalone page (Computers).
 */
internal enum class RaftMoreRow(
    val key: String,
    val iconName: RaftLucideIconName,
    val title: StringResource,
    val subtitle: StringResource,
) {
    Members("members", RaftLucideIconName.Users, MR.strings.tab_members, MR.strings.more_hub_members_subtitle),
    Computers("computers", RaftLucideIconName.Monitor, MR.strings.settings_computers_title, MR.strings.settings_computers_subtitle),
    Settings("settings", RaftLucideIconName.Settings, MR.strings.tab_settings, MR.strings.more_hub_settings_subtitle),
}

/** Rows in display order. */
internal val RaftMoreRows: List<RaftMoreRow> = RaftMoreRow.entries

/**
 * Rows that have something new (Jasper 2026-10-20): each dots its own row on the More page and
 * the More tab. Driven by the Home notices the shell already loads (they clear when dismissed):
 * - Settings: the app-update notice (an update is available; Settings > About checks/installs).
 * - Computers: the offline-computer or outdated-daemon notice.
 * Members has no unread signal today.
 */
internal fun raftMoreAttentionRows(homeNotificationIds: List<String>): Set<RaftMoreRow> =
    buildSet {
        if (homeNotificationIds.any { it.isAppUpdateHomeNotificationId() }) add(RaftMoreRow.Settings)
        if (homeNotificationIds.any { it.isMachineHomeNotificationId() }) add(RaftMoreRow.Computers)
    }

@Composable
internal fun RaftMorePage(
    bottomContentPadding: Dp,
    onSelect: (RaftMoreRow) -> Unit,
    rows: List<RaftMoreRow> = RaftMoreRows,
    attentionRows: Set<RaftMoreRow> = emptySet(),
) {
    val attentionDescription = stringResource(MR.strings.more_row_attention_dot)
    RaftDesignSystemTheme {
        Column(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
            RaftMainTabPageHeader(
                surface = RaftMainTabHeaderSurface.More,
                title = stringResource(MR.strings.tab_more),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "more_top") { Spacer(Modifier.height(RaftAppSpacing.Md)) }
                item(key = "more_rows") {
                    RaftListRowGroup(Modifier.fillMaxWidth().padding(horizontal = RaftAppSpacing.Md)) {
                        rows.forEachIndexed { index, row ->
                            RaftListRow(
                                title = stringResource(row.title),
                                subtitle = stringResource(row.subtitle),
                                onClick = { onSelect(row) },
                                divider = index > 0,
                                leading = { RaftMoreRowIcon(row.iconName) },
                                trailing = {
                                    if (row in attentionRows) {
                                        RaftAttentionDot(
                                            modifier = Modifier.semantics { contentDescription = attentionDescription }
                                        )
                                    }
                                    RaftListRowChevron()
                                },
                            )
                        }
                    }
                }
                item(key = "more_bottom") { Spacer(Modifier.height(RaftAppSpacing.Md + bottomContentPadding)) }
            }
        }
    }
}

@Composable
private fun RaftMoreRowIcon(name: RaftLucideIconName) {
    RaftIconTile(size = RaftIconTileSize.Md) {
        val ink = LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
        RaftLucideIcon(name, modifier = Modifier.size(RaftIconTileSize.Md.icon), color = raftArgbHex(ink))
    }
}
