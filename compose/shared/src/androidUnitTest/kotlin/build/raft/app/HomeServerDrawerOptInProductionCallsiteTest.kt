package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeServerDrawerOptInProductionCallsiteTest {
    @Test
    fun sharedSectionHeaderKeepsTrailingLambdaSourceCompatibility() {
        val signature =
            sectionHeaderSource().substring(
                startIndex = sectionHeaderSource().indexOf("internal fun SlockCollapsibleSectionHeader(").also { assertFalse(it < 0) },
                endIndex = sectionHeaderSource().indexOf(") {", sectionHeaderSource().indexOf("internal fun SlockCollapsibleSectionHeader(")).also {
                    assertFalse(it < 0)
                }
            )

        assertContains(signature, "interactive: Boolean = true")
        assertContains(signature, "showChevron: Boolean = true")
        assertContains(signature, "trailingContent: (@Composable BoxScope.() -> Unit)? = null")
        assertTrue(signature.indexOf("trailingContent:") > signature.indexOf("showChevron:"))
    }

    @Test
    fun disabledPopoverStaysInHomeWhileEnabledDrawerWrapsTheWholeAppShell() {
        val homeSource = homeComponentsSource()
        val overlayBlock =
            homeSource.substring(
                startIndex = homeSource.indexOf("if (serverSwitcherExpanded) {").also { assertFalse(it < 0) },
                endIndex = homeSource.indexOf(
                    "if (notificationCenterExpanded",
                    homeSource.indexOf("if (serverSwitcherExpanded) {")
                ).also {
                    assertFalse(it < 0)
                }
            )
        val appShellSource = settingsHomePageSource()
        val drawerCallsite =
            appShellSource.substring(
                startIndex = appShellSource.indexOf("val mainRouteSurface:").also { assertFalse(it < 0) },
                endIndex = appShellSource.indexOf(
                    "    Box(modifier = Modifier.fillMaxSize()) {\n        SlockForegroundBannerHost(",
                    appShellSource.indexOf("val mainRouteContent:")
                ).also {
                    assertFalse(it < 0)
                }
            )
        val drawerHelper =
            appShellSource.substring(
                startIndex = appShellSource.indexOf("private fun AppShellServerDrawerHost(").also { assertFalse(it < 0) },
                endIndex = appShellSource.indexOf("internal fun appShellAuthGateViewModelKey").also { assertFalse(it < 0) }
            )
        val drawerHost = drawerCallsite + drawerHelper
        val drawerNavigation =
            appShellSource.substring(
                startIndex = appShellSource.indexOf("fun openServerDrawerConversation(").also { assertFalse(it < 0) },
                endIndex = appShellSource.indexOf("val mainRouteSurface:").also { assertFalse(it < 0) }
            )

        assertContains(overlayBlock, "if (!serverDrawerEnabled)")
        assertContains(overlayBlock, "modifier = Modifier.padding(bottom = bottomContentPadding)")
        assertContains(overlayBlock, "ServerSwitcherPanel(")
        assertFalse(overlayBlock.contains("HomeServerDrawerPanel("))
        assertContains(drawerHost, "rememberMoveableDrawerState(")
        assertContains(drawerHost, "MoveableDrawer(")
        assertContains(drawerHost, "AppShellTabs(")
        assertContains(drawerHost, "content = mainRouteSurface")
        assertContains(drawerHost, "HomeServerDrawerPanel(")
        assertContains(drawerHost, "userHeader = serverDrawerUserHeader")
        assertContains(drawerHost, "bottomSafeArea = shellScope.bottomSafeArea")
        assertContains(drawerHost, "SharedHomeIntent.DismissServerSwitcher")
        assertContains(drawerHost, "SharedHomeIntent.ToggleServerSwitcher")
        assertContains(drawerHost, "onlyUnread = serverDrawerOnlyUnread")
        assertContains(drawerHost, "serverDrawerPreferenceStore.saveOnlyUnread(enabled)")
        assertContains(drawerHost, "shouldOpenHomeServerDrawerOnEdgeSwipe(horizontalDragDp)")
        assertContains(drawerHost, "source=app_shell_root_edge")
        assertContains(drawerHost, "selectedTab=\$selectedTab")
        assertContains(drawerHost, ".width(32.dp)")
        assertContains(drawerHost, ".background(Color.Transparent)")
        assertContains(drawerHost, "server_drawer_open_first_visible")
        assertContains(drawerHost, "HomeServerDrawerColdStartPanel(")
        assertContains(drawerHost, "server_drawer_content_hydration")
        assertContains(drawerHost, "shouldHydrateHomeServerDrawerContent(")
        assertContains(drawerHost, "phase=canceled")
        assertContains(drawerHost, "phase=interactive")
        assertTrue(
            drawerHost.indexOf("server_drawer_open_end") <
                drawerHost.indexOf("server_drawer_content_hydration")
        )
        assertFalse(drawerHost.contains("server_drawer_open_store_fallback"))
        assertFalse(drawerHost.contains("requestDrawerOpen(\"direct_input\")"))
        assertContains(drawerHost, "drawerCommandFence")
        assertContains(drawerHost, "HomeServerDrawerCommandFence")
        assertContains(drawerHost, "server_drawer_open_canceled")
        assertContains(drawerHost, "drawerNativeJobOwnership.supersede(commandGeneration)")
        assertContains(drawerHost, "supersededNativeJobs.forEach { it.cancel() }")
        assertContains(drawerHost, "supersededNativeJobs.forEach { it.join() }")
        assertContains(drawerHost, "launchOwnedNativeJob { drawerState.open() }")
        assertContains(drawerHost, "launchOwnedNativeJob { drawerState.close() }")
        assertContains(drawerHost, "ownedNativeJobs.forEach { it.cancel() }")
        assertContains(drawerHost, "ownedNativeJobs.forEach { it.join() }")
        assertTrue(
            drawerHost.indexOf("launchOwnedNativeJob { drawerState.open() }") <
                drawerHost.indexOf("server_drawer_open_first_visible")
        )
        assertTrue(
            drawerHost.indexOf("supersededNativeJobs.forEach { it.join() }") <
                drawerHost.indexOf("launchOwnedNativeJob { drawerState.open() }")
        )
        assertContains(appShellSource, "serverSwitcherExpanded = false")
        assertContains(appShellSource, "serverDrawer = HomeServerDrawerState()")
        assertContains(drawerHost, "withoutServerDrawerVisibilityForDrawerRender()")
        assertContains(drawerHost, ".map { shellState -> shellState.home.serverSwitcherExpanded }")
        assertContains(drawerHost, ".distinctUntilChanged()")
        assertContains(drawerHost, "LaunchedEffect(homeShellStore, drawerState)")
        assertFalse(drawerHost.contains("LaunchedEffect(homeState.serverSwitcherExpanded"))
        assertFalse(drawerHost.contains("homeState.serverSwitcherExpanded"))
        assertContains(drawerHost, "onDismissBySwipe")
        assertTrue(
            drawerHost.indexOf("source=app_shell_root_edge") >
                drawerHost.indexOf("content = mainRouteSurface")
        )
        assertContains(drawerNavigation, "homeShellStore.state.value.home.serverDrawer.sections[serverId]")
        assertContains(drawerNavigation, "shellAuthStore.updateSelectedServer(")
        assertContains(drawerNavigation, "SharedHomeIntent.SelectServer(normalizedServerId)")
        assertContains(drawerNavigation, "initialServerId = normalizedServerId")
        assertContains(drawerNavigation, "openHomeConversation(")
        assertContains(drawerNavigation, "reason=stale_projection")
    }

    @Test
    fun enabledDrawerReusesHomeRowsResolversSafeAreaAndPremeasureRamp() {
        val source = homeComponentsSource()
        val coldPanel =
            source.substring(
                startIndex = source.indexOf("internal fun HomeServerDrawerColdStartPanel(").also { assertFalse(it < 0) },
                endIndex = source.indexOf("internal fun HomeServerDrawerPanel(").also { assertFalse(it < 0) }
            )
        val panel =
            source.substring(
                startIndex = source.indexOf("internal fun HomeServerDrawerPanel(").also { assertFalse(it < 0) },
                endIndex = source.indexOf("private fun HomeServerDrawerErrorRow(").also { assertFalse(it < 0) }
            )

        assertContains(panel, "homeServerDrawerHeaderHeight(topSafeArea)")
        assertContains(panel, "LaunchedEffect(Unit)")
        assertContains(panel, "onContentInteractive()")
        assertContains(coldPanel, "homeServerDrawerHeaderHeight(topSafeArea)")
        assertContains(coldPanel, "repeat(6)")
        assertContains(coldPanel, "HomeSidebarSkeletonRow(")
        assertContains(coldPanel, "SlockLucideIconName.LoaderCircle")
        assertFalse(coldPanel.contains("SlockAvatar("))
        assertContains(panel, "Spacer(Modifier.fillMaxWidth().height(bottomSafeArea))")
        assertContains(panel, "homeListInitialBeyondBoundsItemCount()")
        assertContains(panel, "homeListNextServerSwitchBeyondBoundsItemCount(")
        assertFalse(panel.contains("sharedListBeyondBoundsItemCount(rows.size)"))
        assertContains(panel, "ServerSwitcherRow(")
        assertContains(panel, "SlockCollapsibleSectionHeader(")
        assertContains(panel, "interactive = true")
        assertContains(panel, "showChevron = true")
        assertContains(panel, "DirectMessageRow(")
        assertContains(panel, "onClick = onClick")
        assertContains(panel, "userHeader.displayName")
        assertContains(panel, "userHeader.avatarWireValue")
        assertContains(panel, "overflow = TextOverflow.Ellipsis")
        assertContains(panel, "contentDescription = \"Only show unread\"")
        assertContains(panel, "stateDescription = if (onlyUnread) \"On\" else \"Off\"")
        assertContains(panel, "role = Role.Switch")
        assertContains(panel, "text = \"UNREAD\"")
        assertContains(panel, "SlockSwitchButton(")
        assertFalse(panel.contains("SlockLucideIconName.Funnel"))
        assertFalse(panel.contains("Create or join server"))
        assertFalse(panel.contains("ServerSwitcherActionRow(labels.switchOrCreateServer"))
        assertContains(source, "leadingStatusVisible = false")
        assertContains(panel, "shouldDismissHomeServerDrawerOnSwipe(horizontalDragDp)")
        assertFalse(panel.contains(".border(SlockStroke.Default, SlockColor.BorderBlack)\n                    .pointerInput"))
        assertContains(panel, "slockAgentStatusResolver(serverMembersStore, row.serverId)")
        assertContains(panel, "slockMemberAvatarResolver(serverMembersStore, row.serverId)")
        assertContains(panel, "agentStatusResolver = agentStatusResolver")
        assertContains(panel, "memberAvatarResolver = memberAvatarResolver")
        assertContains(source, "topSafeArea + SlockSize.PageHeaderHeight")
        assertContains(settingsHomePageSource(), "val user = readySession?.user")
        assertContains(settingsHomePageSource(), "displayName = user?.bestDisplayName ?: \"Raft user\"")
        assertContains(settingsHomePageSource(), "avatarWireValue = user?.avatarUrl")
    }

    private fun homeComponentsSource(): String {
        val commonMain =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .map { File(it, "src/commonMain/kotlin") }
                .firstOrNull(File::isDirectory)
                ?: error("Unable to locate shared commonMain sources")
        return File(commonMain, "build/raft/app/SlockHomeComponents.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun settingsHomePageSource(): String {
        val commonMain =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .map { File(it, "src/commonMain/kotlin") }
                .firstOrNull(File::isDirectory)
                ?: error("Unable to locate shared commonMain sources")
        return File(commonMain, "build/raft/app/SettingsHomePage.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun sectionHeaderSource(): String {
        val commonMain =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .map { File(it, "src/commonMain/kotlin") }
                .firstOrNull(File::isDirectory)
                ?: error("Unable to locate shared commonMain sources")
        return File(commonMain, "build/raft/app/SlockSectionHeader.kt")
            .readText()
            .replace("\r\n", "\n")
    }
}
