package ai.slock.android.ui.components

import ai.slock.android.shared.SlockKuiklyActivity
import ai.slock.android.shared.SlockKuiklyPageIsland
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun SlockHomeHeaderKuikly(
        serverName: String,
        serverUnreadCount: Int,
        modifier: Modifier = Modifier,
        onServerBadgeClick: () -> Unit
) {
    SlockKuiklyPageIsland(
            pageName = SlockKuiklyActivity.PAGE_HOME_HEADER,
            pageData =
                    JSONObject()
                            .put(
                                    "payload",
                                    JSONObject()
                                            .put("serverName", serverName)
                                            .put("unreadCount", serverUnreadCount)
                                            .toString()
                            ),
            modifier = modifier,
            onEvent = { event ->
                if (event.optString("action") == "openServerSwitcher") {
                    onServerBadgeClick()
                }
            }
    )
}

@Composable
internal fun SlockHomeServerSwitcherKuikly(
        servers: List<SlockServerSwitcherItem>,
        currentServerId: String,
        labels: SlockHomeShellLabels,
        modifier: Modifier = Modifier,
        onServerSelected: (String) -> Unit,
        onCreateServerClick: () -> Unit
) {
    SlockKuiklyPageIsland(
            pageName = SlockKuiklyActivity.PAGE_HOME_SERVER_SWITCHER,
            pageData =
                    JSONObject()
                            .put(
                                    "payload",
                                    JSONObject()
                                            .put("currentServerId", currentServerId)
                                            .put("noServersLabel", labels.noServersAvailable)
                                            .put("createServerLabel", labels.switchOrCreateServer)
                                            .put("servers", servers.toJsonArray())
                                            .toString()
                            ),
            modifier =
                    modifier.height(
                            ((servers.size.coerceAtLeast(1) * 48) + 48)
                                    .coerceAtMost(384)
                                    .dp
                    ),
            onEvent = { event ->
                when (event.optString("action")) {
                    "selectServer" ->
                            event.optString("itemId")
                                    .takeIf { it.isNotBlank() }
                                    ?.let(onServerSelected)
                    "createServer" -> onCreateServerClick()
                }
            }
    )
}

@Composable
internal fun SlockHomeSectionHeaderKuikly(
        label: String,
        count: Int,
        expanded: Boolean,
        showAdd: Boolean,
        addLabel: String,
        modifier: Modifier = Modifier,
        onExpandedChange: (Boolean) -> Unit,
        onSortClick: () -> Unit,
        onAddClick: () -> Unit
) {
    val toggle = { onExpandedChange(!expanded) }
    Box(modifier = modifier) {
        SlockKuiklyPageIsland(
                pageName = SlockKuiklyActivity.PAGE_HOME_SECTION_HEADER,
                pageData =
                        JSONObject()
                                .put(
                                        "payload",
                                        JSONObject()
                                                .put("label", label)
                                                .put("count", count)
                                                .put("expanded", expanded)
                                                .put("showAdd", showAdd)
                                                .put("addLabel", addLabel)
                                                .toString()
                                ),
                modifier = Modifier.fillMaxSize(),
                onEvent = { event ->
                    when (event.optString("action")) {
                        "toggle" -> toggle()
                        "sort" -> onSortClick()
                        "add" -> onAddClick()
                    }
                }
        )
        Row(modifier = Modifier.fillMaxSize()) {
            TransparentHomeAction(
                    modifier =
                            Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .semantics {
                                        text = AnnotatedString(label)
                                        role = Role.Button
                                    },
                    onClick = toggle
            )
            TransparentHomeAction(
                    modifier =
                            Modifier
                                    .size(30.dp)
                                    .semantics {
                                        contentDescription = "Sort $label"
                                        role = Role.Button
                                    },
                    onClick = onSortClick
            )
            if (showAdd) {
                TransparentHomeAction(
                        modifier =
                                Modifier
                                        .size(30.dp)
                                        .semantics {
                                            contentDescription = addLabel
                                            role = Role.Button
                                        },
                        onClick = onAddClick
                )
            }
        }
    }
}

private fun List<SlockServerSwitcherItem>.toJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { server ->
        array.put(
                JSONObject()
                        .put("id", server.id)
                        .put("name", server.name)
                        .put("slug", server.slug)
                        .put("unreadCount", server.unreadCount)
        )
    }
    return array
}

@Composable
private fun TransparentHomeAction(
        modifier: Modifier,
        onClick: () -> Unit
) {
    Box(
            modifier =
                    modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick
                    )
    )
}
