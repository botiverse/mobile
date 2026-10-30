package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.RaftShellStoreScope
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.components.RaftSidebarGroupLabel
import build.raft.designsystem.components.RaftSidebarItem
import build.raft.designsystem.components.raftSidebarItemIconSize
import androidx.compose.foundation.layout.size

/*
 * artin 2026-10-29 ("大屏模式下的设置页是不对的，设置页应该也是侧边栏状态，然后设置在右侧打开"): on
 * the wide layout Settings is list + detail like the web's (botiverse/slock MainLayout
 * `settings/:tab?` + Sidebar SettingsSidebarList): the Settings list stays in the sidebar column,
 * the picked section opens beside it, and with nothing picked the detail is Account, highlighted
 * (web `normalizeSettingsTab` -> "account"). The phone keeps its list -> pushed page.
 */

/**
 * True inside the wide Settings detail pane: the section's page is the pane's content, so its
 * head has no Back (the list beside it is the way out, as the wide Search since #3943) and sits
 * under the wide top bar (no status-bar inset).
 */
internal val LocalRaftSettingsWideDetail = staticCompositionLocalOf { false }

/**
 * Inside the wide Settings detail pane: what "close this page" means for a section page there (it
 * is the pane's content, not a pushed page): back to the default section. Null elsewhere, where a
 * page closes itself through the router ([settingsPageClose]).
 */
internal val LocalRaftSettingsWideDetailClose = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Closes a Settings section page: the wide pane's close when it is the pane's content, else the router's. */
internal fun settingsPageClose(wideDetailClose: (() -> Unit)?, routerClose: () -> Unit) {
    if (wideDetailClose != null) wideDetailClose() else routerClose()
}

/** The native Settings section pages that open in the wide detail pane. */
internal val SettingsWideNativeDetailKeys: Set<String> =
    setOf(
        SettingsDestinationAccount,
        SettingsNativeDetailPage.LanguageRegion.key,
        SettingsNativeDetailPage.Appearance.key,
        SettingsNativeDetailPage.Notifications.key,
        SettingsNativeDetailPage.Mobile.key,
        SettingsDestinationStorage,
        SettingsNativeDetailPage.Theme.key,
        SettingsNativeDetailPage.Server.key,
        SettingsDestinationComputers,
        SettingsNativeDetailPage.About.key,
    )

/**
 * The web-backed Settings sections that open in the wide detail pane too (artin 2026-10-29: like
 * the native ones, not a page over the rail). Feedback joins them only when it is the web page
 * (the product-feedback flag); the older native Hands feedback page keeps being pushed.
 */
internal val SettingsWideWebDetailKeys: Set<String> =
    setOf(
        SettingsDestinationBilling,
        SettingsDestinationAdministration,
        SettingsDestinationImBridges,
        SettingsDestinationConnectedApps,
        SettingsDestinationReleaseNotes,
    )

/** Every Settings row that opens in the wide detail pane. */
internal fun settingsWideDetailKeys(feedbackWeb: Boolean): Set<String> =
    SettingsWideNativeDetailKeys + SettingsWideWebDetailKeys +
        (if (feedbackWeb) setOf(SettingsDestinationHandsFeedback) else emptySet())

internal val SettingsWideDetailKeys: Set<String> = settingsWideDetailKeys(feedbackWeb = false)

/**
 * The section the wide detail shows for [picked] among the rows [rowKeys]: the picked one while it
 * is a row that opens in the pane, else the first such row (Account, the web's default).
 */
internal fun settingsWideDetailKey(picked: String?, rowKeys: List<String>, feedbackWeb: Boolean = false): String? {
    val paneKeys = settingsWideDetailKeys(feedbackWeb)
    return picked?.takeIf { it in rowKeys && it in paneKeys } ?: rowKeys.firstOrNull { it in paneKeys }
}

/** What picking [key] in the wide Settings list does. */
internal sealed interface SettingsWidePick {
    /** It opens in the detail pane: [key] becomes the selection. */
    data class Detail(val key: String) : SettingsWidePick

    /** It is not a pane section: open it as before (a page over the shell). */
    data object Push : SettingsWidePick
}

internal fun settingsWidePick(key: String, feedbackWeb: Boolean = false): SettingsWidePick =
    if (key in settingsWideDetailKeys(feedbackWeb)) SettingsWidePick.Detail(key) else SettingsWidePick.Push

/**
 * Whether system Back / Esc goes back to the default section: only while another section than the
 * default ([settingsWideDetailKey] of nothing) is showing. On the default, Back is the shell's.
 */
internal fun settingsWideBackReturnsToDefault(picked: String?, rowKeys: List<String>, feedbackWeb: Boolean = false): Boolean {
    val showing = settingsWideDetailKey(picked, rowKeys, feedbackWeb) ?: return false
    return showing != settingsWideDetailKey(null, rowKeys, feedbackWeb)
}

/** The child page (name and page data) that draws section [key] in the wide detail pane. */
internal fun settingsWideDetailPage(
    key: String,
    storeScope: RaftShellStoreScope,
    feedbackWeb: Boolean = false,
): Pair<String, JSONObject>? =
    when (key) {
        SettingsDestinationBilling -> settingsWideWebPage(SettingsWebViewSurface.Billing, storeScope)
        SettingsDestinationAdministration -> settingsWideWebPage(SettingsWebViewSurface.Administration, storeScope)
        SettingsDestinationImBridges -> settingsWideWebPage(SettingsWebViewSurface.ImBridges, storeScope)
        SettingsDestinationConnectedApps -> settingsWideWebPage(SettingsWebViewSurface.Marketplace, storeScope)
        SettingsDestinationReleaseNotes -> settingsWideWebPage(SettingsWebViewSurface.ReleaseNotes, storeScope)
        SettingsDestinationHandsFeedback ->
            if (feedbackWeb) settingsWideWebPage(SettingsWebViewSurface.Feedback, storeScope) else null
        SettingsDestinationAccount -> SETTINGS_ACCOUNT_PAGE to settingsStandalonePageData(SETTINGS_ACCOUNT_PAGE, storeScope)
        SettingsDestinationStorage -> SETTINGS_STORAGE_PAGE to settingsStandalonePageData(SETTINGS_STORAGE_PAGE, storeScope)
        SettingsDestinationComputers -> SETTINGS_COMPUTERS_PAGE to settingsComputersPageData(storeScope)
        else ->
            SettingsNativeDetailPage.fromKey(key)
                ?.takeIf { key in SettingsWideNativeDetailKeys }
                ?.let { page -> settingsNativeDetailPageData(page, storeScope)?.let { SETTINGS_NATIVE_DETAIL_PAGE to it } }
    }

private fun settingsWideWebPage(surface: SettingsWebViewSurface, storeScope: RaftShellStoreScope): Pair<String, JSONObject> =
    SETTINGS_WEB_VIEW_PAGE to settingsWebViewPageData(surface, storeScope)

/**
 * The wide Settings tab: the Settings list in the sidebar column ([RaftListPaneKind.Sidebar], the
 * Home sidebar's width) with the [selectedKey] row highlighted, and [detailPane] beside it. Back
 * while a non-default section shows returns to the default one ([settingsWideBackReturnsToDefault]).
 */
@Composable
internal fun SettingsWideListDetail(
    payload: RaftSettingsHomePayload,
    widths: RaftListPaneWidths,
    active: Boolean,
    picked: String?,
    /** The product-feedback flag: Feedback is the web page, which opens in the pane. */
    feedbackWeb: Boolean = false,
    onPick: (String) -> Unit,
    onBackToDefault: () -> Unit,
    detailBottomInset: Dp,
    bottomContentPadding: Dp,
    detailPane: @Composable (key: String, modifier: Modifier) -> Unit,
    onResizingChange: (Boolean) -> Unit = {},
) {
    val rowKeys = payload.items.map { it.key }
    val showing = settingsWideDetailKey(picked, rowKeys, feedbackWeb)
    RaftBackHandler(
        enabled = active && settingsWideBackReturnsToDefault(picked, rowKeys, feedbackWeb),
        label = "settings_wide_detail",
        blocksBackSwipe = false,
    ) { onBackToDefault() }
    RaftAdaptiveListDetailSplit(
        kind = RaftListPaneKind.Sidebar,
        widths = widths,
        dualPane = true,
        active = active,
        hasSelection = showing != null,
        detailBottomInset = detailBottomInset,
        listPane = {
            SettingsWideSidebarList(
                payload = payload,
                selectedKey = showing,
                bottomContentPadding = bottomContentPadding,
                onSelect = onPick,
            )
        },
        detailPane = { modifier ->
            if (showing != null) {
                CompositionLocalProvider(
                    LocalRaftSettingsWideDetail provides true,
                    LocalRaftSettingsWideDetailClose provides onBackToDefault,
                ) {
                    detailPane(showing, modifier)
                }
            }
        },
        onResizingChange = onResizingChange,
    )
}

/**
 * The Settings rows as the web's SettingsSidebarList draws them in the sidebar: each group under
 * its [RaftSidebarGroupLabel], rows as [RaftSidebarItem] (the selected one the yellow sticker),
 * 8dp from the column's edges like the Home sidebar.
 */
@Composable
internal fun SettingsWideSidebarList(
    payload: RaftSettingsHomePayload,
    selectedKey: String?,
    bottomContentPadding: Dp,
    onSelect: (String) -> Unit,
) {
    val groups = payload.items.groupConsecutiveBy { it.section }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(horizontal = RaftAppSpacing.Sm),
    ) {
        groups.forEach { (section, rows) ->
            if (section != null) {
                item(key = "section:$section") { RaftSidebarGroupLabel(section) }
            }
            item(key = "gap-top:${rows.first().key}") { Spacer(Modifier.height(RaftAppSpacing.Xs)) }
            items(rows, key = { it.key }) { row ->
                RaftSidebarItem(
                    title = row.title,
                    selected = row.key == selectedKey,
                    onClick = { onSelect(row.key) },
                    leading = {
                        RaftLucideIcon(
                            row.iconName,
                            Modifier.size(raftSidebarItemIconSize()),
                            color = raftArgbHex(LocalRaftContentColor.current),
                        )
                    },
                )
            }
            item(key = "gap-bottom:${rows.first().key}") { Spacer(Modifier.height(RaftAppSpacing.Xs)) }
        }
        item(key = "bottom") { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

private fun <T, K> List<T>.groupConsecutiveBy(keyOf: (T) -> K): List<Pair<K, List<T>>> {
    val groups = mutableListOf<Pair<K, MutableList<T>>>()
    forEach { item ->
        val key = keyOf(item)
        val last = groups.lastOrNull()
        if (last != null && last.first == key) last.second += item else groups += key to mutableListOf(item)
    }
    return groups
}
