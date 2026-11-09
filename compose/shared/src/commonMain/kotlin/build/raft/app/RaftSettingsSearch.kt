package build.raft.app

/*
 * Settings search (raft-design design/products/raft-chat/settings.md#searching-settings): the
 * app's own index (artin, #proj-raft-ui:#204, option A) and its matching, pure.
 *
 * The web keeps its index in botiverse/slock packages/shared/src/settingsSearch.v1.json (copied
 * here at 99ba05ae). This one lists only what the app has, with the same id for the same setting,
 * so `/settings/<page>#<id>` names the same row on the web and in the app. The words a setting is
 * shown with (name, description, section) are the app's own strings, the ones its row shows
 * ([RaftSettingsSearchCopy] in SettingsSearchUi.kt); this file holds where a setting is, who sees
 * it, and the other words people use for it.
 *
 * Not here, because the app has no row for them: passkeys and two-step (the Account page has no
 * such blocks, see [RaftSettingsInAppSections]), ai-providers (no Providers page), desktop-app (the
 * web's About card).
 */

/**
 * One setting the search can find.
 *
 * [id]: lowercase words joined by hyphens, the web's id for the same setting, and its anchor on
 * its page. [webPage]: the web settings slug, where a page drawn by the web opens it
 * (`/settings/<webPage>#<id>`). [aliases]: other words for it by display locale (the web's keys:
 * `en`, `zh-cn`, ...); `en` is always there and always searched.
 */
internal data class RaftSettingsSearchEntry(
    val id: String,
    val webPage: String,
    val aliases: Map<String, List<String>>,
    /** The Settings row (page) that holds it in the app, for this person's list. Null: not on it. */
    val rowKey: (RaftSettingsSearchContext) -> String?,
    /** Whether the page shows it to this person (a role that can't, a flag that is off). */
    val shown: (RaftSettingsSearchContext) -> Boolean = { true },
)

/**
 * What decides which settings this person can find: the rows their Settings list shows, in its
 * order ([rowKeys], the list's own keys), their role on the current server, and the server flags
 * that make a page show a block.
 */
internal data class RaftSettingsSearchContext(
    val rowKeys: List<String>,
    /** The current server role, `owner` / `admin` / `member` / `guest`; null without a server. */
    val role: String? = null,
    /** Account > Delete account has a row ([settingsAccountDeletionEntry] is not Hidden). */
    val accountDeletion: Boolean = false,
    /** `public_server_v0` on for the current server (Server > Public access, owner only). */
    val publicServer: Boolean = false,
    /** `server_sso_v1` on for the current server (Administration > Single sign-on, admins). */
    val serverSso: Boolean = false,
) {
    internal val normalizedRole: String? get() = role?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** web `isAdminOrOwner`: Billing and Administration are theirs (`viewBilling`, `inviteMembers`, ...). */
    internal val adminOrOwner: Boolean get() = normalizedRole == "owner" || normalizedRole == "admin"
}

private fun on(rowKey: String): (RaftSettingsSearchContext) -> String? = { context -> rowKey.takeIf { it in context.rowKeys } }

private val Account = on(SettingsDestinationAccount)
private val LanguageRegion = on(SettingsNativeDetailPage.LanguageRegion.key)
private val Appearance = on(SettingsNativeDetailPage.Appearance.key)
private val Notifications = on(SettingsNativeDetailPage.Notifications.key)
private val Theme = on(SettingsNativeDetailPage.Theme.key)
private val ServerNotifications = on(SettingsNativeDetailPage.ServerNotifications.key)
private val Server = on(SettingsNativeDetailPage.Server.key)
private val Billing = on(SettingsDestinationBilling)
private val Administration = on(SettingsDestinationAdministration)

/**
 * The composer key is a Mobile card on a phone; on a fine pointer the Mobile cards sit at the end of
 * Appearance and there is no Mobile row ([settingsAppPreferencesUnderAppearance]).
 */
private val MobileOrAppearance: (RaftSettingsSearchContext) -> String? = { context ->
    SettingsNativeDetailPage.Mobile.key.takeIf { it in context.rowKeys } ?: Appearance(context)
}

/** Light and dark are on the Theme page; with no current server (no Theme row) on Appearance. */
private val ThemeOrAppearance: (RaftSettingsSearchContext) -> String? = { context -> Theme(context) ?: Appearance(context) }

private fun words(en: List<String>, zhCn: List<String>): Map<String, List<String>> = mapOf("en" to en, "zh-cn" to zhCn)

/** The index, in the web's order; results follow the list's order of pages, then this one. */
internal val RaftSettingsSearchEntries: List<RaftSettingsSearchEntry> =
    listOf(
        RaftSettingsSearchEntry("display-name", "account", words(listOf("name", "nickname"), listOf("昵称", "名字", "名称")), Account),
        RaftSettingsSearchEntry("username", "account", words(listOf("handle", "@"), listOf("用户名", "账号名")), Account),
        RaftSettingsSearchEntry("email", "account", words(listOf("mail", "e-mail"), listOf("邮箱", "电子邮件")), Account),
        RaftSettingsSearchEntry(
            "password",
            "account",
            words(listOf("change password", "sign in", "login"), listOf("密码", "改密码", "登录")),
            Account,
        ),
        RaftSettingsSearchEntry(
            "sign-out",
            "account",
            words(listOf("log out", "logout", "sign off"), listOf("退出登录", "登出", "注销")),
            Account,
        ),
        RaftSettingsSearchEntry(
            "delete-account",
            "account",
            words(listOf("remove account", "close account"), listOf("删除账号", "注销账号")),
            Account,
            shown = { it.accountDeletion },
        ),
        RaftSettingsSearchEntry(
            "display-language",
            "language-region",
            words(listOf("language", "locale", "english", "chinese"), listOf("语言", "显示语言", "中文", "英文")),
            LanguageRegion,
        ),
        RaftSettingsSearchEntry(
            "translation-mode",
            "language-region",
            words(listOf("translate", "translation"), listOf("翻译", "自动翻译")),
            LanguageRegion,
        ),
        RaftSettingsSearchEntry(
            "time-format",
            "language-region",
            words(listOf("12-hour", "24-hour", "clock", "am pm"), listOf("时间格式", "12小时", "24小时", "时钟")),
            LanguageRegion,
        ),
        RaftSettingsSearchEntry("timezone", "language-region", words(listOf("time zone", "utc"), listOf("时区", "时差")), LanguageRegion),
        RaftSettingsSearchEntry(
            "message-font-size",
            "appearance",
            words(listOf("font", "text size", "zoom"), listOf("字号", "字体大小", "文字大小", "字体")),
            Appearance,
        ),
        RaftSettingsSearchEntry(
            "live-agent-activity",
            "appearance",
            words(listOf("activity", "working", "what agents are doing"), listOf("实时动态", "工作中", "agent 动态")),
            Appearance,
        ),
        RaftSettingsSearchEntry(
            "show-agent-model",
            "appearance",
            words(listOf("model", "opus", "sonnet"), listOf("模型", "显示模型")),
            Appearance,
        ),
        RaftSettingsSearchEntry(
            "hide-empty-sidebar-sections",
            "appearance",
            words(listOf("sidebar", "empty sections"), listOf("侧栏", "空分组", "隐藏分组")),
            Appearance,
        ),
        RaftSettingsSearchEntry(
            "send-with",
            "appearance",
            words(
                listOf("enter", "return", "send key", "new line", "shortcut", "cmd enter", "ctrl enter"),
                listOf("回车", "换行", "发送键", "发送方式", "快捷键"),
            ),
            MobileOrAppearance,
        ),
        RaftSettingsSearchEntry(
            "push-notifications",
            "notifications",
            words(listOf("push", "notification"), listOf("推送", "通知")),
            Notifications,
        ),
        RaftSettingsSearchEntry(
            "pause-notifications",
            "notifications",
            words(listOf("do not disturb", "dnd", "snooze", "mute"), listOf("暂停通知", "勿扰", "免打扰", "静音")),
            Notifications,
        ),
        RaftSettingsSearchEntry(
            "quiet-hours",
            "notifications",
            words(listOf("do not disturb", "dnd", "night", "schedule"), listOf("免打扰时段", "勿扰", "夜间")),
            Notifications,
        ),
        RaftSettingsSearchEntry(
            "notification-preview",
            "notifications",
            words(listOf("preview", "privacy", "lock screen"), listOf("通知预览", "隐私", "锁屏")),
            Notifications,
        ),
        RaftSettingsSearchEntry(
            "notification-sound",
            "notifications",
            words(listOf("sound", "ringtone", "audio"), listOf("提示音", "声音", "铃声")),
            Notifications,
        ),
        RaftSettingsSearchEntry(
            "theme",
            "theme",
            words(listOf("dark", "dark mode", "light", "night", "color scheme"), listOf("主题", "深色", "夜间", "暗色", "浅色")),
            ThemeOrAppearance,
        ),
        RaftSettingsSearchEntry(
            "server-theme",
            "theme",
            words(listOf("color", "colour", "skin", "pixel art"), listOf("服务器主题", "颜色", "配色", "皮肤")),
            Theme,
        ),
        RaftSettingsSearchEntry(
            "mute-server",
            "server-notifications",
            words(listOf("mute", "silence", "do not disturb"), listOf("静音这个服务器", "静音", "勿扰", "通知")),
            ServerNotifications,
        ),
        RaftSettingsSearchEntry(
            "server-name",
            "server",
            words(listOf("rename", "workspace name"), listOf("服务器名称", "改名", "工作区名称")),
            Server,
        ),
        // The section shows only to the owner, while the server's public_server_v0 is on (its read answers).
        RaftSettingsSearchEntry(
            "public-access",
            "server",
            words(listOf("public", "share", "guest"), listOf("公开访问", "公开", "分享", "游客")),
            Server,
            shown = { it.normalizedRole == "owner" && it.publicServer },
        ),
        // SettingsServerDangerZoneCard: Leave is an admin's or a member's; the owner deletes instead.
        RaftSettingsSearchEntry(
            "leave-server",
            "server",
            words(listOf("leave", "quit"), listOf("退出服务器", "离开")),
            Server,
            shown = { it.normalizedRole == "admin" || it.normalizedRole == "member" },
        ),
        // Pages the web draws inside the app (Billing, Administration) are the web's, owner/admin only
        // there (web canOpenSettingsTab: viewBilling; inviteMembers / editServerSettings).
        RaftSettingsSearchEntry(
            "plan",
            "billing",
            words(listOf("billing", "subscription", "invoice", "upgrade", "payment"), listOf("套餐", "付费", "订阅", "发票", "升级")),
            Billing,
            shown = { it.adminOrOwner },
        ),
        RaftSettingsSearchEntry(
            "invite-links",
            "administration",
            words(listOf("invite", "link", "add people"), listOf("邀请链接", "邀请", "加人")),
            Administration,
            shown = { it.adminOrOwner },
        ),
        // web useServerSsoShown: the section shows once GET /servers/:id/sso answers, which it does
        // for an admin while server_sso_v1 is on.
        RaftSettingsSearchEntry(
            "single-sign-on",
            "administration",
            words(listOf("sso", "saml", "okta", "google workspace"), listOf("单点登录", "SSO", "企业登录")),
            Administration,
            shown = { it.adminOrOwner && it.serverSso },
        ),
        RaftSettingsSearchEntry(
            "member-permissions",
            "administration",
            words(listOf("permissions", "roles", "who can"), listOf("成员权限", "权限", "角色")),
            Administration,
            shown = { it.adminOrOwner },
        ),
    )

/** An entry as this person's list has it: the row it is on, or none when they can't find it. */
internal data class RaftSettingsSearchPlacement(val entry: RaftSettingsSearchEntry, val rowKey: String)

/**
 * The settings this person can find, in the list's order: by page as the list shows them, then the
 * index's (web useSettingsSearchSettings). Those on a page the list does not show, or that the page
 * leaves out for them, are not there.
 */
internal fun raftSettingsSearchPlacements(
    context: RaftSettingsSearchContext,
    entries: List<RaftSettingsSearchEntry> = RaftSettingsSearchEntries,
): List<RaftSettingsSearchPlacement> {
    val pageOrder = context.rowKeys.withIndex().associate { (index, key) -> key to index }
    return entries
        .mapNotNull { entry ->
            val rowKey = entry.rowKey(context) ?: return@mapNotNull null
            if (rowKey !in pageOrder || !entry.shown(context)) return@mapNotNull null
            RaftSettingsSearchPlacement(entry, rowKey)
        }
        .withIndex()
        .sortedWith(compareBy({ pageOrder.getValue(it.value.rowKey) }, { it.index }))
        .map { it.value }
}

/** One setting as the search sees it: its own words, where it is, and the other words for it. */
internal data class RaftSearchableSetting(
    /** The index id, and the setting's anchor on its page. */
    val id: String,
    /** The Settings row (page) it is on. */
    val rowKey: String,
    /** The web settings slug, for a page drawn by the web. */
    val webPage: String,
    val name: String,
    val description: String? = null,
    val pageLabel: String,
    /** The section on the page; none where the page has no sections of its own. */
    val sectionLabel: String? = null,
    /** Other words for it: the display language's, then English. */
    val aliases: List<String> = emptyList(),
)

/** What the query matched, in result order: names, then other words, then descriptions, then where it is. */
internal enum class RaftSettingsSearchField { Name, Alias, Description, Where }

internal data class RaftSettingsSearchResult(
    val setting: RaftSearchableSetting,
    val field: RaftSettingsSearchField,
    /** The other word that matched, when [field] is [RaftSettingsSearchField.Alias]. */
    val alias: String? = null,
) {
    /** The second line: page and section ("Appearance · Message box"), then the other word it was found by. */
    val subtitle: String
        get() {
            val where = setting.sectionLabel?.let { "${setting.pageLabel} · $it" } ?: setting.pageLabel
            return alias?.let { "$where · $it" } ?: where
        }
}

/**
 * The form the query and the words are compared in (web `normalizeSettingsSearchText`): lower
 * case, one space for each run of white space, none at the ends, and full-width letters, digits and
 * signs as their half-width forms ("Ｅｎｔｅｒ" is "Enter"). The web runs NFKC; Kotlin common has no
 * Unicode normalizer, so this folds the full-width ASCII block (U+FF01..U+FF5E) and the ideographic
 * space, the part of NFKC a typed query meets.
 */
internal fun normalizeSettingsSearchText(text: String): String =
    foldSettingsSearchWidth(text).lowercase().trim().replace(SettingsSearchWhitespace, " ")

private val SettingsSearchWhitespace = Regex("\\s+")

/** Full-width ASCII as half-width, the ideographic space as a space; everything else as it is. */
internal fun foldSettingsSearchWidth(text: String): String {
    if (text.none { it in '！'..'～' || it == '　' }) return text
    return buildString(text.length) {
        text.forEach { char ->
            append(
                when (char) {
                    in '！'..'～' -> (char.code - 0xFEE0).toChar()
                    '　' -> ' '
                    else -> char
                },
            )
        }
    }
}

private fun matchSetting(setting: RaftSearchableSetting, query: String): RaftSettingsSearchResult? {
    fun has(text: String?) = text != null && normalizeSettingsSearchText(text).contains(query)
    if (has(setting.name)) return RaftSettingsSearchResult(setting, RaftSettingsSearchField.Name)
    setting.aliases.firstOrNull(::has)?.let { return RaftSettingsSearchResult(setting, RaftSettingsSearchField.Alias, it) }
    if (has(setting.description)) return RaftSettingsSearchResult(setting, RaftSettingsSearchField.Description)
    val where = setting.sectionLabel?.let { "${setting.pageLabel} $it" } ?: setting.pageLabel
    if (has(where)) return RaftSettingsSearchResult(setting, RaftSettingsSearchField.Where)
    return null
}

/**
 * The settings that match [query], in result order (web `searchSettings`, `FIELD_ORDER`): those
 * whose name has it first, then another word, then the description, then page and section; within
 * each, the list's order ([settings] come in it). An empty query finds nothing: the list shows its
 * groups instead.
 */
internal fun searchSettings(settings: List<RaftSearchableSetting>, query: String): List<RaftSettingsSearchResult> {
    val normalized = normalizeSettingsSearchText(query)
    if (normalized.isEmpty()) return emptyList()
    return settings
        .mapIndexedNotNull { index, setting -> matchSetting(setting, normalized)?.let { it to index } }
        .sortedWith(compareBy({ it.first.field.ordinal }, { it.second }))
        .map { it.first }
}

/**
 * The other words to search for one display locale (web `settingsSearchAliases`): that locale's
 * own, then English, which is always searched; each word once.
 */
internal fun settingsSearchAliases(aliases: Map<String, List<String>>, locale: String): List<String> {
    val own = if (locale == "en") emptyList() else aliases[locale].orEmpty()
    return (own + aliases["en"].orEmpty()).distinct()
}

/**
 * The index's locale key (the web's display locales: `en`, `zh-cn`, `zh-tw`, `ja`, `ko`, `es`,
 * `pt-br`, `ru`) for a BCP 47 tag of the app language: `zh-CN` / `zh-Hans` is `zh-cn`, `zh-TW` /
 * `zh-Hant` / `zh-HK` is `zh-tw`, `pt-BR` is `pt-br`, any other the language alone.
 */
internal fun settingsSearchAliasLocale(languageTag: String): String {
    val parts = languageTag.trim().replace('_', '-').lowercase().split('-').filter { it.isNotEmpty() }
    val language = parts.firstOrNull() ?: return "en"
    return when (language) {
        "zh" ->
            if (parts.any { it == "hant" || it == "tw" || it == "hk" || it == "mo" }) "zh-tw" else "zh-cn"
        "pt" -> "pt-br"
        else -> language
    }
}
