package build.raft.app

import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager

const val SETTINGS_NATIVE_DETAIL_PAGE = "compose_settings_native_detail"

internal const val SETTINGS_NATIVE_DETAIL_PAGE_KEY = "settingsPage"
internal const val SETTINGS_NATIVE_DETAIL_ITEM_ID = "settingsItemId"
internal const val SETTINGS_NATIVE_DETAIL_AUTO_CLOSE_MS = "settingsAutoCloseMs"

private const val SETTINGS_NATIVE_DETAIL_MAX_AUTO_CLOSE_MS = 10 * 60 * 1_000L
private val SettingsNativeDetailLocalFeatureOverrideKey = Regex("^[a-z][a-z0-9_]{2,95}$")

internal enum class SettingsNativeDetailPage(
    val key: String,
    val storeDestination: SharedSettingsDestination,
) {
    LanguageRegion("language-region", SharedSettingsDestination.LanguageRegion),
    Appearance("appearance", SharedSettingsDestination.Appearance),
    Notifications("notifications", SharedSettingsDestination.Notifications),
    Mobile("mobile", SharedSettingsDestination.Mobile),
    Server("server", SharedSettingsDestination.Server),
    About("about", SharedSettingsDestination.About),
    Developer("developer", SharedSettingsDestination.Developer),
    OpenSourceLicenses("open-source-licenses", SharedSettingsDestination.OpenSourceLicenses),
    ApiEnvironment("api-environment", SharedSettingsDestination.Developer),
    Diagnostics("diagnostics", SharedSettingsDestination.Diagnostics),
    LocalFeatureOverrides("local-feature-overrides", SharedSettingsDestination.Diagnostics),
    LocalFeatureOverride("local-feature-override", SharedSettingsDestination.Diagnostics),
    ;

    companion object {
        fun fromKey(key: String): SettingsNativeDetailPage? =
            entries.firstOrNull { page -> page.key == key.trim() }
    }
}

internal val settingsNativeDetailPageKeys: Set<String> =
    SettingsNativeDetailPage.entries.mapTo(linkedSetOf()) { page -> page.key }

internal data class SettingsNativeDetailLaunchIntent(
    val autoCloseMs: Long = 0L,
)

internal class SettingsNativeDetailRoute private constructor(
    val page: SettingsNativeDetailPage,
    val itemId: String? = null,
    val launchIntent: SettingsNativeDetailLaunchIntent = SettingsNativeDetailLaunchIntent(),
) {
    companion object {
        fun create(
            page: SettingsNativeDetailPage,
            itemId: String? = null,
            launchIntent: SettingsNativeDetailLaunchIntent = SettingsNativeDetailLaunchIntent(),
        ): SettingsNativeDetailRoute? {
            val normalizedItemId =
                itemId
                    ?.trim()
                    ?.takeIf {
                        page == SettingsNativeDetailPage.LocalFeatureOverride &&
                            it.matches(SettingsNativeDetailLocalFeatureOverrideKey)
                    }
            if (page == SettingsNativeDetailPage.LocalFeatureOverride && normalizedItemId == null) {
                return null
            }
            return SettingsNativeDetailRoute(
                page = page,
                itemId = normalizedItemId,
                launchIntent =
                    launchIntent.takeIf { page == SettingsNativeDetailPage.Diagnostics }
                        ?: SettingsNativeDetailLaunchIntent(),
            )
        }
    }
}

internal suspend fun runSettingsNativeDetailLaunchSequence(
    route: SettingsNativeDetailRoute,
    loadPageContent: suspend () -> Unit,
    waitForAutoClose: suspend (Long) -> Unit,
    closePage: () -> Unit,
) {
    loadPageContent()
    route.launchIntent.autoCloseMs.takeIf { it > 0L }?.let { delayMs ->
        waitForAutoClose(delayMs)
        closePage()
    }
}

@Page(SETTINGS_NATIVE_DETAIL_PAGE, supportInLocal = true)
internal class ComposeSettingsNativeDetailPage :
    AuthenticatedProductPager(AuthenticatedProductRoute.SettingsNativeDetail) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { SettingsNativeDetailScreen() }
    }
}

internal fun settingsNativeDetailPageData(
    page: SettingsNativeDetailPage,
    storeScope: SlockShellStoreScope,
    itemId: String? = null,
    launchIntent: SettingsNativeDetailLaunchIntent = SettingsNativeDetailLaunchIntent(),
): JSONObject? {
    val route = SettingsNativeDetailRoute.create(page, itemId, launchIntent) ?: return null
    return JSONObject().apply {
        put("pageName", SETTINGS_NATIVE_DETAIL_PAGE)
        put(SETTINGS_NATIVE_DETAIL_PAGE_KEY, route.page.key)
        route.itemId?.let { put(SETTINGS_NATIVE_DETAIL_ITEM_ID, it) }
        put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
        if (route.page == SettingsNativeDetailPage.Diagnostics) {
            route.launchIntent.autoCloseMs
                .coerceIn(0L, SETTINGS_NATIVE_DETAIL_MAX_AUTO_CLOSE_MS)
                .takeIf { it > 0L }
                ?.let { put(SETTINGS_NATIVE_DETAIL_AUTO_CLOSE_MS, it) }
        }
    }
}

internal fun settingsNativeDetailRoute(params: JSONObject): SettingsNativeDetailRoute? {
    val page =
        SettingsNativeDetailPage.fromKey(
            params.optString(SETTINGS_NATIVE_DETAIL_PAGE_KEY),
        ) ?: return null
    val itemId =
        params
            .optString(SETTINGS_NATIVE_DETAIL_ITEM_ID)
            .trim()
            .takeIf {
                page == SettingsNativeDetailPage.LocalFeatureOverride &&
                    it.matches(SettingsNativeDetailLocalFeatureOverrideKey)
            }
    if (page == SettingsNativeDetailPage.LocalFeatureOverride && itemId == null) return null
    val launchIntent =
        if (page == SettingsNativeDetailPage.Diagnostics) {
            SettingsNativeDetailLaunchIntent(
                autoCloseMs =
                    params.optLong(SETTINGS_NATIVE_DETAIL_AUTO_CLOSE_MS, 0L)
                        .coerceIn(0L, SETTINGS_NATIVE_DETAIL_MAX_AUTO_CLOSE_MS),
            )
        } else {
            SettingsNativeDetailLaunchIntent()
        }
    return SettingsNativeDetailRoute.create(
        page = page,
        itemId = itemId,
        launchIntent = launchIntent,
    )
}

internal fun Pager.openSettingsNativeDetailPage(
    page: SettingsNativeDetailPage,
    storeScope: SlockShellStoreScope,
    itemId: String? = null,
    launchIntent: SettingsNativeDetailLaunchIntent = SettingsNativeDetailLaunchIntent(),
): Boolean {
    val pageData =
        settingsNativeDetailPageData(
            page = page,
            storeScope = storeScope,
            itemId = itemId,
            launchIntent = launchIntent,
        ) ?: return false
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SETTINGS_NATIVE_DETAIL_PAGE, pageData)
    return true
}

internal fun settingsStandalonePageData(
    pageName: String,
    storeScope: SlockShellStoreScope,
): JSONObject =
    JSONObject().apply {
        put("pageName", pageName)
        put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
    }

internal fun Pager.openSettingsAccountPage(storeScope: SlockShellStoreScope) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(SETTINGS_ACCOUNT_PAGE, settingsStandalonePageData(SETTINGS_ACCOUNT_PAGE, storeScope))
}

internal fun Pager.openSettingsStoragePage(storeScope: SlockShellStoreScope) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(SETTINGS_STORAGE_PAGE, settingsStandalonePageData(SETTINGS_STORAGE_PAGE, storeScope))
}
