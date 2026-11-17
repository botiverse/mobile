package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant

internal const val AppShellRestoreFrameTestTag = "app_shell_restore_frame"
internal const val AppShellRestoreFrameSkeletonTestTag = "app_shell_restore_frame_skeleton"

/** Rows the restore frame's list skeleton stands in with (a phone screen's worth). */
internal const val AppShellRestoreFrameSkeletonRows = 8

/**
 * The title of the restore frame's head for the tab the window opens on: the tab bar's label of
 * that tab (Chat for Home).
 */
internal fun appShellRestoreFrameTitle(selectedTab: String, labels: Map<String, String>): String {
    val destination =
        when (appShellPhoneBottomBarSelectedKey(selectedTab)) {
            "activity" -> RaftAppDestination.Activity
            "tasks" -> RaftAppDestination.Tasks
            AppShellSearchTabKey -> RaftAppDestination.Search
            "more" -> RaftAppDestination.Server
            else -> RaftAppDestination.Chat
        }
    return labels[destination.id].orEmpty()
}

/**
 * What a server switch's new window (Multiple app windows, `suppressRestoreLanding`) shows while
 * it reads the stored session: the shell itself, its head with the tab's name and the tab bar on
 * the app canvas, and a list skeleton where the tab's list will be. It used to be a plain
 * full-screen box, which read as a white screen (Hands f0be77a8). Nothing here acts on a tap (the
 * tab bar's taps go nowhere): it stands for a few frames until the shell is ready.
 */
@Composable
internal fun AppShellNewWindowRestoreFrame(
    adaptivePane: RaftAdaptivePaneState,
    selectedTab: String,
    animated: Boolean = true,
) {
    val shellScope = rememberRaftAppShellScope(adaptivePane)
    val labels = raftNavLabels(RaftNavPresentation.PhoneBar)
    val phoneBar = adaptivePane.layout != RaftAdaptivePaneLayout.DualPane
    val bottomTabs =
        appShellPhoneBottomTabs(
            selectedTab = selectedTab,
            homeLabel = labels[RaftAppDestination.Chat.id].orEmpty(),
            activityLabel = labels[RaftAppDestination.Activity.id].orEmpty(),
            searchLabel = labels[RaftAppDestination.Search.id].orEmpty(),
            tasksLabel = labels[RaftAppDestination.Tasks.id].orEmpty(),
            moreLabel = labels[RaftAppDestination.Server.id].orEmpty(),
        )
    RaftAppShellSurface(
        shellScope = shellScope,
        bottomTabs = bottomTabs,
        onBottomTabSelected = {},
        modifier = Modifier.testTag(AppShellRestoreFrameTestTag),
        showBottomTabs = phoneBar,
        interactionLocked = false,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            RaftTopPageHeader(
                title = appShellRestoreFrameTitle(selectedTab, labels),
                topSafeAreaOverride = shellScope.topSafeArea,
                contentStartPadding = 16.dp,
                titleStyle = RaftMainTabHeaderTitleStyle,
                showBodyTopBanner = false,
            )
            AppShellRestoreFrameSkeleton(animated = animated)
        }
    }
}

@Composable
private fun AppShellRestoreFrameSkeleton(animated: Boolean) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(AppShellRestoreFrameSkeletonTestTag)
                .padding(top = RaftAppSpacing.Sm)
    ) {
        repeat(AppShellRestoreFrameSkeletonRows) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftSkeleton(modifier = Modifier.size(36.dp), variant = RaftSkeletonVariant.Circle, animated = animated)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RaftSkeleton(
                        modifier = Modifier.fillMaxWidth(0.45f),
                        variant = RaftSkeletonVariant.Line,
                        animated = animated,
                    )
                    RaftSkeleton(
                        modifier = Modifier.fillMaxWidth(0.8f),
                        variant = RaftSkeletonVariant.Line,
                        animated = animated,
                    )
                }
            }
        }
    }
}
