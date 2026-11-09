package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.PUBLIC_SERVER_FEATURE_FLAG_KEY
import build.raft.app.data.SERVER_SSO_FEATURE_FLAG_KEY
import build.raft.app.model.ServerId
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.StoredAuthSession
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftEmptyStateDefaults
import build.raft.designsystem.components.RaftSidebarItemStacked
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldClearAction
import build.raft.designsystem.theme.LocalRaftContentColor
import org.jetbrains.compose.resources.StringResource

/*
 * Settings search's field, results and No match (raft-design settings.md#searching-settings; web
 * features/settings/search/SettingsSearch.tsx). On the phone it is on top of the Settings page
 * (SettingsHomeContent), on the wide layout on top of the Settings list in the sidebar column
 * (SettingsWideSidebarList). While there is text the results take the groups' place.
 */

/**
 * The words the app shows for a setting: its row's own strings (name, description, the section it
 * is under), by index id. [descriptionWithServer]: the description names the current server (`%1$s`).
 */
internal class RaftSettingsSearchCopy(
    val name: StringResource,
    val description: StringResource? = null,
    val section: StringResource? = null,
    val descriptionWithServer: Boolean = false,
)

/** Every entry of [RaftSettingsSearchEntries] has its words here (RaftSettingsSearchIndexTest). */
internal val RaftSettingsSearchCopies: Map<String, RaftSettingsSearchCopy> =
    mapOf(
        "display-name" to RaftSettingsSearchCopy(Res.string.settings_account_display_name, section = Res.string.settings_account_profile_section),
        "username" to RaftSettingsSearchCopy(Res.string.settings_account_name, section = Res.string.settings_account_profile_section),
        "email" to RaftSettingsSearchCopy(Res.string.settings_account_email, section = Res.string.settings_account_profile_section),
        "password" to
            RaftSettingsSearchCopy(
                Res.string.settings_account_change_password,
                Res.string.settings_account_change_password_detail,
                Res.string.settings_account_profile_section,
            ),
        "sign-out" to
            RaftSettingsSearchCopy(Res.string.settings_account_logout, Res.string.settings_account_logout_detail, Res.string.settings_account_session),
        "delete-account" to RaftSettingsSearchCopy(Res.string.settings_account_delete, Res.string.settings_account_delete_detail),
        "display-language" to
            RaftSettingsSearchCopy(Res.string.settings_app_language_title, Res.string.settings_app_language_detail, Res.string.settings_language_title),
        "translation-mode" to
            RaftSettingsSearchCopy(Res.string.settings_translation_mode, Res.string.settings_translation_mode_detail, Res.string.settings_language_title),
        "time-format" to
            RaftSettingsSearchCopy(Res.string.settings_time_format, Res.string.settings_time_format_detail, Res.string.settings_date_time_title),
        "timezone" to RaftSettingsSearchCopy(Res.string.settings_timezone, Res.string.settings_timezone_detail, Res.string.settings_date_time_title),
        "message-font-size" to
            RaftSettingsSearchCopy(
                Res.string.settings_appearance_message_font_size_title,
                Res.string.settings_appearance_message_font_size_description,
            ),
        "live-agent-activity" to
            RaftSettingsSearchCopy(
                Res.string.settings_appearance_live_agent_activity_title,
                Res.string.settings_appearance_live_agent_activity_description,
            ),
        "show-agent-model" to
            RaftSettingsSearchCopy(Res.string.settings_appearance_show_agent_model_title, Res.string.settings_appearance_show_agent_model_description),
        "hide-empty-sidebar-sections" to
            RaftSettingsSearchCopy(
                Res.string.settings_appearance_hide_empty_sidebar_sections_title,
                Res.string.settings_appearance_hide_empty_sidebar_sections_description,
            ),
        "send-with" to RaftSettingsSearchCopy(Res.string.settings_mobile_composer_key, Res.string.settings_mobile_composer_key_detail),
        "push-notifications" to
            RaftSettingsSearchCopy(
                Res.string.settings_notifications_message_title,
                Res.string.settings_notifications_message_detail,
                Res.string.settings_notifications_device_section,
            ),
        "pause-notifications" to
            RaftSettingsSearchCopy(Res.string.settings_notifications_pause_title, section = Res.string.settings_notifications_pause_section),
        "quiet-hours" to
            RaftSettingsSearchCopy(Res.string.settings_notifications_quiet_hours_title, section = Res.string.settings_notifications_pause_section),
        "notification-preview" to
            RaftSettingsSearchCopy(
                Res.string.settings_notifications_preview_mode_title,
                Res.string.settings_notifications_preview_mode_detail,
                Res.string.settings_notifications_content_section,
            ),
        "notification-sound" to
            RaftSettingsSearchCopy(
                Res.string.settings_notifications_sound_title,
                Res.string.settings_notifications_sound_detail,
                Res.string.settings_notifications_content_section,
            ),
        "theme" to RaftSettingsSearchCopy(Res.string.settings_theme_group_mode, Res.string.settings_appearance_theme_description),
        "server-theme" to
            RaftSettingsSearchCopy(Res.string.settings_theme_title, Res.string.settings_server_theme_description, descriptionWithServer = true),
        "mute-server" to RaftSettingsSearchCopy(Res.string.notification_level_mute, Res.string.notification_level_muted_note),
        "server-name" to RaftSettingsSearchCopy(Res.string.settings_server_label_name, section = Res.string.settings_profile_title),
        "public-access" to
            RaftSettingsSearchCopy(
                Res.string.settings_public_visibility_title,
                Res.string.settings_public_visibility_description,
                Res.string.settings_public_visibility_section_label,
            ),
        "leave-server" to
            RaftSettingsSearchCopy(
                Res.string.settings_server_action_leave,
                Res.string.settings_server_leave_detail,
                Res.string.settings_section_danger_zone_title,
            ),
        "plan" to RaftSettingsSearchCopy(Res.string.settings_search_plan),
        "invite-links" to RaftSettingsSearchCopy(Res.string.settings_search_invite_links),
        "single-sign-on" to RaftSettingsSearchCopy(Res.string.settings_search_single_sign_on, Res.string.settings_search_single_sign_on_description),
        "member-permissions" to RaftSettingsSearchCopy(Res.string.settings_section_member_permissions_title),
    )

/**
 * The pages on the server named after it, as their links are (web SERVER_PAGE_TITLE_ID): "Theme
 * on {server}", "Notifications on {server}".
 */
private val SettingsSearchServerPageTitles: Map<String, StringResource> =
    mapOf(
        SettingsNativeDetailPage.Theme.key to Res.string.settings_appearance_server_theme_link_title,
        SettingsNativeDetailPage.ServerNotifications.key to Res.string.settings_server_notifications_subtitle,
    )

/**
 * The settings this person can find ([raftSettingsSearchPlacements]) in the words of the app
 * language: names, descriptions and sections are the rows' own strings, page labels the list's
 * row titles ([pageTitles] by row key). [resolve] reads a string (with the server name where it
 * takes one); [aliasLocale] picks the other words ([settingsSearchAliasLocale]).
 */
internal fun raftSearchableSettings(
    context: RaftSettingsSearchContext,
    pageTitles: Map<String, String>,
    serverName: String,
    aliasLocale: String,
    resolve: (StringResource, String?) -> String,
): List<RaftSearchableSetting> =
    raftSettingsSearchPlacements(context).mapNotNull { placement ->
        val entry = placement.entry
        val copy = RaftSettingsSearchCopies[entry.id] ?: return@mapNotNull null
        val serverPage = SettingsSearchServerPageTitles[placement.rowKey]?.takeIf { serverName.isNotBlank() }
        RaftSearchableSetting(
            id = entry.id,
            rowKey = placement.rowKey,
            webPage = entry.webPage,
            name = resolve(copy.name, null),
            description = copy.description?.let { resolve(it, serverName.takeIf { copy.descriptionWithServer }) },
            pageLabel = serverPage?.let { resolve(it, serverName) } ?: pageTitles[placement.rowKey].orEmpty(),
            sectionLabel = copy.section?.let { resolve(it, null) },
            aliases = settingsSearchAliases(entry.aliases, aliasLocale),
        )
    }

/** The index's locale for the app language (the in-app choice, else the system's). */
@Composable
private fun rememberSettingsSearchAliasLocale(): String {
    val localeType = StringDesc.localeType
    val system = Locale.current
    return remember(localeType, system) {
        val tag = (localeType as? StringDesc.LocaleType.Custom)?.localeTag ?: system.toLanguageTag()
        settingsSearchAliasLocale(tag)
    }
}

/** The typed query, kept while Settings stays on one server (a phone's Back returns to the results). */
@Stable
internal class SettingsSearchState {
    var query by mutableStateOf("")

    val searching: Boolean get() = normalizeSettingsSearchText(query).isNotEmpty()
}

/** A [SettingsSearchState] for [scopeKey] (the current server): another server starts empty. */
@Composable
internal fun rememberSettingsSearchState(scopeKey: Any?): SettingsSearchState = remember(scopeKey) { SettingsSearchState() }

/**
 * What a Settings list needs to search: the query, who is searching ([context]; its rows come from
 * the list itself), and what opening a result does. [selectedId]: the result whose page is open
 * beside the list (the wide layout), shown selected. [active]: the list is on screen (a kept-alive
 * tab is not), so its Back handler may register.
 */
internal class SettingsSearchHost(
    val state: SettingsSearchState,
    val context: RaftSettingsSearchContext,
    val serverName: String,
    val active: Boolean,
    val onOpen: (RaftSearchableSetting) -> Unit,
    /** No match's Search everywhere, with the same query; null: no button. */
    val onSearchEverywhere: ((String) -> Unit)? = null,
    val selectedId: String? = null,
)

/**
 * Who is searching, from the signed-in [session] and the current server's flags (its rows are the
 * list's own, filled in by [rememberSettingsSearchResults]): the role, whether Account has Delete
 * account ([settingsAccountDeletionEntry]), and the flags Server > Public access and
 * Administration > Single sign-on show by.
 */
internal fun settingsSearchContext(
    session: StoredAuthSession?,
    flags: SharedServerFeatureFlags,
    serverId: String?,
    ticketSupported: Boolean = raftPlatformSupportsHandsFeedbackSubmit(),
): RaftSettingsSearchContext {
    val server = serverId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId)
    fun flag(key: String) = server != null && flags.isEnabled(server, key)
    return RaftSettingsSearchContext(
        rowKeys = emptyList(),
        role = session?.currentServerSummary()?.role,
        accountDeletion =
            settingsAccountDeletionEntry(settingsAccountDeletionFlagOn(flags, serverId), ticketSupported) !=
                SettingsAccountDeletionEntry.Hidden,
        publicServer = flag(PUBLIC_SERVER_FEATURE_FLAG_KEY),
        serverSso = flag(SERVER_SSO_FEATURE_FLAG_KEY),
    )
}

/** The results for [host]'s query over the list's [payload] rows; empty while the field is. */
@Composable
internal fun rememberSettingsSearchResults(host: SettingsSearchHost, payload: RaftSettingsHomePayload): List<RaftSettingsSearchResult> {
    val query = host.state.query
    val searching = host.state.searching
    val aliasLocale = rememberSettingsSearchAliasLocale()
    val localeType = StringDesc.localeType
    val context = host.context.copy(rowKeys = payload.items.map { it.key })
    val pageTitles = payload.items.associate { it.key to it.title }
    // Built on the first search, then kept while nothing it reads changes.
    val settings =
        remember(searching, context, pageTitles, host.serverName, aliasLocale, localeType) {
            if (!searching) {
                emptyList()
            } else {
                raftSearchableSettings(context, pageTitles, host.serverName, aliasLocale) { res, arg ->
                    if (arg != null) res.format(arg).resolve() else res.desc().resolve()
                }
            }
        }
    return remember(settings, query) { searchSettings(settings, query) }
}

/**
 * The field on top of a Settings list (web `role="search"` InputGroup): the search icon, Search
 * settings, and once there is text a clear button. Enter (the keyboard's Search) opens the first
 * result; Escape clears the query, and with an empty field leaves it; Back clears the query first.
 */
@Composable
internal fun SettingsSearchField(
    host: SettingsSearchHost,
    results: List<RaftSettingsSearchResult>,
    modifier: Modifier = Modifier,
) {
    val state = host.state
    val placeholder = stringResource(Res.string.settings_search_placeholder)
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    RaftBackHandler(enabled = host.active && state.query.isNotEmpty(), label = "settings_search") { state.query = "" }
    Box(modifier.fillMaxWidth().semantics { isTraversalGroup = true }) {
        RaftTextField(
            value = state.query,
            onValueChange = { state.query = it },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(SettingsSearchInputTag)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return@onPreviewKeyEvent false
                        if (state.query.isEmpty()) focusManager.clearFocus() else state.query = ""
                        true
                    },
            placeholder = placeholder,
            leading = {
                RaftLucideIcon(
                    RaftLucideIconName.Search,
                    Modifier.size(18.dp),
                    color = raftArgbHex(LocalRaftContentColor.current),
                )
            },
            trailingAction =
                if (state.query.isNotEmpty()) {
                    {
                        RaftTextFieldClearAction(
                            onClear = {
                                state.query = ""
                                runCatching { focusRequester.requestFocus() }
                            },
                            modifier = Modifier.testTag(SettingsSearchClearTag),
                            label = stringResource(Res.string.settings_search_clear),
                        )
                    }
                } else {
                    null
                },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let { host.onOpen(it.setting) } }),
        )
    }
}

/**
 * The results in a Settings list's place, as list cells 2dp apart (web `gap-0.5 py-2`), or No
 * match: No settings match "…", Try another word…, and Search everywhere with the same query.
 */
internal fun LazyListScope.settingsSearchResultItems(
    host: SettingsSearchHost,
    results: List<RaftSettingsSearchResult>,
    rowModifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        item(key = "settings-search-empty") { SettingsSearchNoMatch(host) }
        return
    }
    item(key = "settings-search-top") { Box(Modifier.padding(top = 7.dp)) }
    items(results, key = { "settings-search:" + it.setting.id }) { result ->
        SettingsSearchResultRow(
            result = result,
            query = host.state.query,
            selected = result.setting.id == host.selectedId,
            onClick = { host.onOpen(result.setting) },
            modifier = rowModifier,
        )
    }
    item(key = "settings-search-bottom") { Box(Modifier.padding(top = 7.dp)) }
}

/**
 * One result (web SettingsSearchResultRow): the setting's name over "Page · Section", and the other
 * word it was found by when that was the match; the matched words marked as in search
 * ([RaftSearchHitText]; a full-width query marks half-width words).
 */
@Composable
private fun SettingsSearchResultRow(
    result: RaftSettingsSearchResult,
    query: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val markQuery = foldSettingsSearchWidth(query)
    RaftSidebarItemStacked(
        title = { modifier, style, ink ->
            RaftSearchHitText(result.setting.name, markQuery, style, ink, modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        subtitle = { modifier, style, ink ->
            RaftSearchHitText(result.subtitle, markQuery, style, ink, modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier.testTag(settingsSearchResultTag(result.setting.id)),
        onClick = onClick,
        selected = selected,
    )
}

@Composable
private fun SettingsSearchNoMatch(host: SettingsSearchHost) {
    val query = host.state.query.trim()
    RaftEmptyState(
        title = stringResource(Res.string.settings_search_no_match, query),
        description = stringResource(Res.string.settings_search_no_match_description),
        modifier = Modifier.testTag(SettingsSearchEmptyTag),
        icon = {
            RaftLucideIcon(
                RaftLucideIconName.Search,
                Modifier.size(RaftEmptyStateDefaults.IconSize),
                color = raftArgbHex(LocalRaftContentColor.current),
            )
        },
        actions =
            host.onSearchEverywhere?.let { searchEverywhere ->
                {
                    RaftButton(
                        text = stringResource(Res.string.settings_search_everywhere),
                        onClick = { searchEverywhere(query) },
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        modifier = Modifier.testTag(SettingsSearchEverywhereTag),
                    )
                }
            },
    )
}

internal const val SettingsSearchInputTag = "settings-search-input"
internal const val SettingsSearchClearTag = "settings-search-clear"
internal const val SettingsSearchEmptyTag = "settings-search-empty"
internal const val SettingsSearchEverywhereTag = "settings-search-everywhere"

internal fun settingsSearchResultTag(id: String): String = "settings-search-result-$id"
