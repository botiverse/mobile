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
        assertContains(drawerHost, "contentReady = drawerContentReady")
        assertFalse(drawerHost.contains("HomeServerDrawerColdStartPanel("))
        assertContains(drawerHost, "server_drawer_content_hydration")
        assertContains(drawerHost, "shouldHydrateHomeServerDrawerContent(")
        assertContains(drawerHost, "phase=canceled")
        assertContains(drawerHost, "markerGeneration = generation")
        assertContains(drawerHost, "if (markerAccepted) \"interactive\" else \"interactive_canceled\"")
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
        val panelEntry =
            source.substring(
                startIndex = source.indexOf("internal fun HomeServerDrawerPanel(").also { assertFalse(it < 0) },
                endIndex = source.indexOf("private fun HomeServerDrawerPanelMeasured(").also { assertFalse(it < 0) }
            )
        val panel =
            source.substring(
                startIndex = source.indexOf("internal fun HomeServerDrawerPanel(").also { assertFalse(it < 0) },
                endIndex = source.indexOf("private fun HomeServerDrawerErrorRow(").also { assertFalse(it < 0) }
            )

        assertContains(panelEntry, "HomeServerDrawerPanelMeasured(")
        assertFalse(panelEntry.contains("LazyColumn("))
        assertFalse(panelEntry.contains("SlockAvatar("))
        assertContains(panel, "private fun HomeServerDrawerPanelSurface(")
        assertContains(panel, "private fun HomeServerDrawerPanelHeader(")
        assertContains(panel, "private fun ColumnScope.HomeServerDrawerPanelBody(")
        assertContains(panel, "private fun HomeServerDrawerPanelRow(")
        assertContains(panel, "homeServerDrawerHeaderHeight(topSafeArea)")
        assertContains(panel, "contentReady: Boolean")
        assertContains(panel, "Modifier.onGloballyPositioned")
        assertContains(panel, "onContentInteractive: (Int) -> Unit")
        assertContains(panel, "onContentInteractive(generation)")
        assertFalse(panel.contains("LaunchedEffect(Unit)"))
        assertContains(panel, "if (contentReady) visibleRows.size else 6")
        assertContains(panel, "cold-skeleton:\$index")
        assertFalse(panel.contains("homeServerDrawerInitialInteractiveRowCount("))
        assertFalse(panel.contains("homeServerDrawerRetainedHydratedRowKeys("))
        assertFalse(panel.contains("homeServerDrawerNextHydratedRowKeys("))
        assertFalse(panel.contains("rows.map { row -> row.key }"))
        assertContains(panel, "LaunchedEffect(contentReady)")
        assertContains(panel, "activeOpenGeneration: Int?")
        assertContains(panel, "remember { HomeServerDrawerInteractiveMarkerGate() }")
        assertContains(panel, "interactiveMarkerGate.claim(generation)")
        assertContains(panel, "interactiveMarkerGate.reset()")
        assertContains(panel, "val interactiveRowIndex =")
        assertContains(panel, "row !is SlockHomeServerDrawerRow.Skeleton")
        assertContains(panel, "hydrationStartedAtMillis = defaultNowMillis()")
        assertContains(panel, "hydrationOrder = index")
        assertContains(panel, "homeServerDrawerRemainingHydrationDelayMillis(")
        assertContains(panel, "if (remainingDelayMillis > 0L) delay(remainingDelayMillis)")
        assertContains(panel, ".then(if (hydrated) interactiveLayoutModifier else Modifier)")
        assertContains(panel, "if (!hydrated || row == null)")
        assertContains(panel, "SlockLucideIconName.LoaderCircle")
        assertContains(panel, "if (contentReady) {")
        assertContains(panel, "Spacer(Modifier.fillMaxWidth().height(bottomSafeArea))")
        assertContains(panel, "homeListInitialBeyondBoundsItemCount()")
        assertFalse(panel.contains("homeListNextServerSwitchBeyondBoundsItemCount("))
        assertFalse(panel.contains("sharedListBeyondBoundsItemCount(rows.size)"))
        assertContains(panel, "ServerSwitcherRow(")
        assertContains(panel, "SlockCollapsibleSectionHeader(")
        assertContains(panel, "interactive = true")
        assertContains(panel, "showChevron = true")
        assertContains(panel, "private fun HomeServerDrawerConversationRow(")
        assertContains(panel, ".clickable(onClick = onClick)")
        assertContains(panel, "rememberSlockMemberAvatarWireValue(")
        assertContains(panel, "rememberSlockAgentStatusIndicatorState(")
        assertContains(panel, "DirectMessageStatusDot(agentStatus)")
        assertContains(panel, "CountPill(badge, SlockColor.AccentPink)")
        assertFalse(panel.contains("DirectMessageRow("))
        assertFalse(panel.contains("ChannelRow("))
        assertFalse(panel.contains("collectIsHoveredAsState"))
        assertFalse(panel.contains("detectDragGesturesAfterLongPress"))
        assertFalse(panel.contains("homeRowAnchorCapture"))
        assertContains(settingsHomePageSource(), "drawerContentGeneration = null")
        assertContains(settingsHomePageSource(), "shouldAcceptHomeServerDrawerInteractiveMarker(")
        assertContains(settingsHomePageSource(), "interactive_canceled")
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
        assertContains(panel, "rememberSlockAgentStatusIndicatorState(agentStatusResolver, memberId)")
        assertContains(panel, "rememberSlockMemberAvatarWireValue(memberAvatarResolver, item.presenceMemberId)")
        assertContains(panel, "homeConversationTitleColor(muted = item.activityMuted)")
        assertContains(panel, "homeConversationTitleColor(muted = item.activityMuted, joined = item.joined)")
        assertContains(panel, "Modifier.widthIn(max = 260.dp)")
        assertTrue(
            panel.indexOf("if (mutedIndicatorIcon != null)") <
                panel.indexOf("directMessageDescription?.let { description ->")
        )
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
