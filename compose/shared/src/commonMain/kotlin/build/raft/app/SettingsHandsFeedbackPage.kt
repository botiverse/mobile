package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager

const val SETTINGS_HANDS_FEEDBACK_PAGE = "compose_settings_hands_feedback"

@Page(SETTINGS_HANDS_FEEDBACK_PAGE, supportInLocal = true)
internal class ComposeSettingsHandsFeedbackPage :
    AuthenticatedProductPager(AuthenticatedProductRoute.SettingsHandsFeedback) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { SettingsHandsFeedbackScreen() }
    }
}

internal fun Pager.openSettingsHandsFeedbackPage(storeScope: RaftShellStoreScope) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_HANDS_FEEDBACK_PAGE,
        settingsHandsFeedbackPageData(storeScope)
    )
}

internal fun settingsHandsFeedbackPageData(storeScope: RaftShellStoreScope): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_HANDS_FEEDBACK_PAGE)
        put(RAFT_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
    }

@Composable
private fun SettingsHandsFeedbackScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    var accountId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(environment.authStore) {
        accountId = environment.authStore.readSession()?.user?.id.orEmpty()
    }

    SettingsDetailShell(
        title = settingsHandsFeedbackTitle(),
        subtitle = "",
        iconName = RaftLucideIconName.MessageSquarePlus,
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    ) {
        val resolvedAccountId = accountId
        if (resolvedAccountId == null) {
            RaftCenteredLoading()
        } else {
            SharedHandsFeedbackContent(
                pager = pager,
                eventBus = sharedStores.eventBus,
                settingsCapabilities = environment.settingsCapabilities,
                sourceStoreScope = environment.storeScope,
                accountId = resolvedAccountId,
                // task #176: this standalone page is outside the app shell, so the
                // bottom composer reserves the system navigation bar itself.
                bottomContentPadding = raftBottomSafeAreaPadding() + RaftAppSpacing.Lg
            )
        }
    }
}

@Composable
private fun settingsHandsFeedbackTitle(): String =
    build.raft.app.resources.stringResource(
        Res.string.settings_feedback_title
    )
