package build.raft.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.AuthenticatedProductPager
import build.raft.app.AuthenticatedProductRoute
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftTraceIdNote
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.NetworkServerLabsRepository
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SERVER_LABS_UI_FEATURE_FLAG_KEY
import build.raft.app.data.ServerLab
import build.raft.app.data.ServerLabsReadback
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.raftStoredSessionFlow
import build.raft.app.get
import build.raft.app.messageRes
import build.raft.app.model.ServerId
import build.raft.app.raftArgbHex
import build.raft.app.raftBottomSafeAreaPadding
import build.raft.app.raftShellStoreScope
import build.raft.app.resources.*
import build.raft.app.thread.icon
import build.raft.app.thread.key
import build.raft.app.thread.messageRes
import build.raft.app.traceId
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftSection
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/*
 * Settings > Labs (task #proj-mobile:#181), the web's Labs tab: botiverse/slock staging
 * aa270b415d44 packages/web/src/features/settings/labs/LabsTabContent.tsx + LabsSection.tsx +
 * components/settings/ServerLabRow.tsx. The Settings row and the page show only while the
 * server's `server_labs_ui_v0` flag is on (web `useServerFeatureFlag(SERVER_LABS_UI_FEATURE_FLAG_KEY)`).
 * The server stays the authority: the master gate is the owner's, enrollments the owner's and
 * admins' (SettingsLabsStore.kt); everyone else reads the same page with the switches off and
 * the web's reasons under them.
 */

const val SETTINGS_LABS_PAGE = "compose_settings_labs"

@Page(SETTINGS_LABS_PAGE, supportInLocal = true)
internal class ComposeSettingsLabsPage : AuthenticatedProductPager(AuthenticatedProductRoute.SettingsLabs) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { SettingsLabsRoute() }
    }
}

/** Settings > Labs: identity only; the page loads the server's Labs itself. */
internal fun settingsLabsPageData(storeScope: RaftShellStoreScope): JSONObject =
    settingsStandalonePageData(SETTINGS_LABS_PAGE, storeScope)

internal fun Pager.openSettingsLabsPage(storeScope: RaftShellStoreScope) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SETTINGS_LABS_PAGE, settingsLabsPageData(storeScope))
}

/**
 * Whether Settings shows Labs for [serverId]: the server's `server_labs_ui_v0` evaluated on (web
 * `labsUiEnabled`). No role gate: every member sees the page, read-only unless the server says
 * otherwise. Off with no server or before the flag resolves.
 */
internal fun settingsLabsEnabled(flags: SharedServerFeatureFlags, serverId: String?): Boolean {
    val id = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return flags.isEnabled(ServerId(id), SERVER_LABS_UI_FEATURE_FLAG_KEY)
}

@Composable
private fun SettingsLabsRoute() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val storeScope =
        remember(pager.pageData.params.toString(), pager.pagerId) {
            pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
        }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val flags = sharedStores.serverFeatureFlags
    val scope = rememberCoroutineScope()
    // Followed, not read once: the wide Settings pane stays beside the server rail.
    val session by produceState<StoredAuthSession?>(null, authStore) {
        val wakes = (authStore as? RaftAuthSessionEventSource)?.authSessionEvents ?: emptyFlow()
        raftStoredSessionFlow(wakes) { authStore.readSession() }.collect { value = it }
    }
    val serverId = session?.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
    val role = session?.currentServerSummary()?.role
    val flagRevision by flags.snapshotRevision.collectAsState()
    val labsUiEnabled = remember(serverId, flagRevision) { settingsLabsEnabled(flags, serverId) }
    val store =
        remember(networkClient, scope) {
            SettingsLabsStore(
                repository = NetworkServerLabsRepository(networkClient),
                scope = scope,
                // A write may change how this server's flags evaluate (web refreshServerFeatureFlags).
                onApplied = { id -> scope.launch { flags.prefetch(ServerId(id), forceRefresh = true) } },
                logger = bridge::log,
            )
        }
    LaunchedEffect(store, serverId, labsUiEnabled) {
        if (labsUiEnabled) store.load(serverId)
    }
    val state by store.state.collectAsState()
    SettingsLabsScreen(
        state = state,
        role = role,
        labsUiEnabled = labsUiEnabled,
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
        onMasterChange = { enabled -> store.setMasterAccess(enabled, role) },
        onEnrollmentChange = { labKey, enabled -> store.setEnrollment(labKey, enabled, role) },
        bottomContentPadding = raftBottomSafeAreaPadding() + RaftAppSpacing.Lg,
    )
}

/** The page: its head and, while the flag is on, the Labs section (web `LabsTabContent`). */
@Composable
internal fun SettingsLabsScreen(
    state: SettingsLabsState,
    role: String?,
    labsUiEnabled: Boolean,
    onBack: () -> Unit,
    onMasterChange: (Boolean) -> Unit,
    onEnrollmentChange: (labKey: String, enabled: Boolean) -> Unit,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
) {
    SettingsDetailShell(
        title = stringResource(Res.string.settings_labs_title),
        subtitle = "",
        onBack = onBack,
    ) {
        RaftDesignSystemTheme {
            LazyColumn(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                if (labsUiEnabled) {
                    item(key = "labs") {
                        SettingsLabsSection(state, role, onMasterChange, onEnrollmentChange)
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(bottomContentPadding)) }
            }
        }
    }
}

internal const val SettingsLabsListTestTag = "server-labs-list"
internal const val SettingsLabRowTestTag = "server-lab-row"
internal const val SettingsLabsMasterSwitchTestTag = "server-labs-master-switch"

/** web `LabsSection`: the "Labs" heading with the FlaskConical and the count, then one card. */
@Composable
internal fun SettingsLabsSection(
    state: SettingsLabsState,
    role: String?,
    onMasterChange: (Boolean) -> Unit,
    onEnrollmentChange: (labKey: String, enabled: Boolean) -> Unit,
) {
    val readback = state.readback?.takeIf { it.serverId == state.serverId }
    RaftSection(
        title = stringResource(Res.string.settings_labs_section_label),
        separator = false,
        count = readback?.labs?.size?.toString(),
        icon = { SettingsLabsIcon(RaftLucideIconName.FlaskConical) },
    ) {
        RaftCard(Modifier.fillMaxWidth()) {
            when {
                state.loading -> SettingsLabsSkeleton()
                state.unavailable -> SettingsLabsUnavailable()
                readback != null ->
                    SettingsLabsReadbackBody(
                        readback = readback,
                        authority = settingsLabsAuthority(role, readback),
                        savingTarget = state.savingTarget,
                        error = state.error,
                        onMasterChange = onMasterChange,
                        onEnrollmentChange = onEnrollmentChange,
                    )
                state.error != null -> SettingsLabsErrorBanner(state.error)
            }
        }
    }
}

@Composable
private fun SettingsLabsIcon(name: RaftLucideIconName, size: Dp = 16.dp) {
    RaftLucideIcon(name, modifier = Modifier.size(size), color = raftArgbHex(LocalRaftContentColor.current))
}

@Composable
private fun SettingsLabsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RaftSkeleton(Modifier.fillMaxWidth().height(40.dp))
        RaftSkeleton(Modifier.fillMaxWidth().height(64.dp))
        RaftSkeleton(Modifier.fillMaxWidth().height(64.dp))
    }
}

@Composable
private fun SettingsLabsUnavailable() {
    val colors = RaftTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RaftLucideIcon(
            RaftLucideIconName.TriangleAlert,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
            color = raftArgbHex(colors.foregroundMuted),
        )
        Column {
            RaftText(
                stringResource(Res.string.settings_labs_unavailable_title),
                style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
            )
            RaftText(
                stringResource(Res.string.settings_labs_unavailable_description),
                modifier = Modifier.padding(top = 2.dp),
                style = RaftTheme.typography.footnote,
                color = colors.foregroundMuted,
            )
        }
    }
}

@Composable
private fun SettingsLabsReadbackBody(
    readback: ServerLabsReadback,
    authority: SettingsLabsAuthority,
    savingTarget: String?,
    error: SettingsLabsError?,
    onMasterChange: (Boolean) -> Unit,
    onEnrollmentChange: (labKey: String, enabled: Boolean) -> Unit,
) {
    val colors = RaftTheme.colors
    val mutationPending = savingTarget != null
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            val masterTitle = stringResource(Res.string.settings_labs_master_title)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    RaftText(
                        masterTitle,
                        style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                    )
                    if (!authority.canSetMasterAccess) {
                        RaftText(
                            stringResource(Res.string.settings_labs_master_owner_only),
                            modifier = Modifier.padding(top = 2.dp),
                            style = RaftTheme.typography.footnote,
                            color = colors.foregroundMuted,
                        )
                    }
                }
                RaftSwitch(
                    checked = readback.masterEnabled,
                    onCheckedChange = onMasterChange,
                    modifier = Modifier.testTag(SettingsLabsMasterSwitchTestTag),
                    enabled = authority.canSetMasterAccess && !mutationPending,
                    label = masterTitle,
                )
            }
            Spacer(Modifier.height(16.dp))
            RaftSeparator()
        }
        if (readback.labs.isEmpty()) {
            RaftText(
                stringResource(Res.string.settings_labs_empty_state),
                style = RaftTheme.typography.footnote,
                color = colors.foregroundHint,
            )
        } else {
            Column(Modifier.testTag(SettingsLabsListTestTag)) {
                readback.labs.forEachIndexed { index, lab ->
                    if (index > 0) RaftSeparator()
                    SettingsLabRow(
                        lab = lab,
                        authority = authority,
                        masterEnabled = readback.masterEnabled,
                        mutationPending = mutationPending,
                        saving = savingTarget == settingsLabTarget(lab.key),
                        first = index == 0,
                        last = index == readback.labs.lastIndex,
                        onEnrollmentChange = onEnrollmentChange,
                    )
                }
            }
        }
        if (error != null) SettingsLabsErrorBanner(error)
    }
}

/** web `ServerLabRow`: name, description, the read-only reason, and the enrollment switch. */
@Composable
private fun SettingsLabRow(
    lab: ServerLab,
    authority: SettingsLabsAuthority,
    masterEnabled: Boolean,
    mutationPending: Boolean,
    saving: Boolean,
    first: Boolean,
    last: Boolean,
    onEnrollmentChange: (labKey: String, enabled: Boolean) -> Unit,
) {
    val colors = RaftTheme.colors
    val editable = settingsLabEnrollmentEditable(lab, authority, masterEnabled)
    val reason = settingsLabDisabledReason(lab, authority, masterEnabled)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(SettingsLabRowTestTag)
            .padding(top = if (first) 0.dp else 12.dp, bottom = if (last) 0.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RaftText(
                lab.name,
                style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                color = if (editable) colors.foreground else colors.foregroundDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RaftText(
                lab.description,
                modifier = Modifier.padding(top = 2.dp),
                style = RaftTheme.typography.footnote,
                color = colors.foregroundMuted,
            )
            if (reason != null) {
                RaftText(
                    stringResource(reason.messageRes()),
                    modifier = Modifier.padding(top = 4.dp),
                    style = RaftTheme.typography.caption,
                    color = colors.foregroundHint,
                )
            }
        }
        RaftSwitch(
            checked = masterEnabled && lab.enrolled,
            onCheckedChange = { enabled -> onEnrollmentChange(lab.key, enabled) },
            enabled = editable && !mutationPending,
            label =
                if (saving) {
                    stringResource(Res.string.settings_labs_saving_enrollment, lab.name)
                } else {
                    stringResource(Res.string.settings_labs_enrollment_label, lab.name)
                },
        )
    }
}

private fun SettingsLabDisabledReason.messageRes() =
    when (this) {
        SettingsLabDisabledReason.MasterDisabled -> Res.string.settings_labs_enrollment_master_disabled
        SettingsLabDisabledReason.AdminOnly -> Res.string.settings_labs_enrollment_admin_only
        SettingsLabDisabledReason.LifecycleReadOnly -> Res.string.settings_labs_enrollment_lifecycle_read_only
    }

/** web `<Banner intent="warning" density="sm">`, with the failed request's trace id under it. */
@Composable
private fun SettingsLabsErrorBanner(error: SettingsLabsError) {
    Column {
        RaftBanner(
            description =
                stringResource(
                    when (error.kind) {
                        SettingsLabsErrorKind.FailedLoad -> Res.string.settings_labs_failed_load
                        SettingsLabsErrorKind.FailedUpdate -> Res.string.settings_labs_failed_update
                        SettingsLabsErrorKind.ServerMismatch -> Res.string.settings_labs_failed_server_mismatch
                    },
                ),
            status = RaftBannerStatus.Warning,
            size = RaftBannerSize.Sm,
        )
        RaftTraceIdNote(error.traceId, Modifier.padding(top = 4.dp))
    }
}
