package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockTheme

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SlockTaskMemberComponentsPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Lg)
        ) {
            SlockTaskBoard(
                columns = mapOf(
                    SlockTaskStatus.Todo to listOf(
                        SlockTaskCardData(
                            title = "Build task/member/empty-error components with previews",
                            source = "#onboarding-artin · #6",
                            owner = "@Android-Developer",
                            updatedAt = "13:56",
                            status = SlockTaskStatus.Todo
                        ),
                        SlockTaskCardData(
                            title = "长标题测试：组件需要支持中文 English mixed text without overflow",
                            source = "#qa",
                            owner = "@QA-Tester",
                            updatedAt = "now",
                            status = SlockTaskStatus.Todo
                        )
                    ),
                    SlockTaskStatus.InProgress to listOf(
                        SlockTaskCardData(
                            title = "Mock repository state contract",
                            source = "#onboarding-artin · API/Data",
                            owner = "@API-Integration-Developer",
                            updatedAt = "13:55",
                            status = SlockTaskStatus.InProgress
                        )
                    ),
                    SlockTaskStatus.InReview to emptyList(),
                    SlockTaskStatus.Done to listOf(
                        SlockTaskCardData(
                            title = "Chevron vector preview fix",
                            source = "#android-dev",
                            owner = "@Android-Developer",
                            updatedAt = "13:53",
                            status = SlockTaskStatus.Done
                        )
                    )
                )
            )

            Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
                SlockMemberRow(
                    name = "Product-UX-Designer",
                    role = "Design system and state matrix",
                    kind = SlockMemberKind.Agent,
                    presence = SlockPresence.Online,
                    detail = "Defines MVP scope, component semantics, and visual acceptance criteria.",
                    device = "Slock agent · active in #android-dev"
                )
                SlockMemberRow(
                    name = "artin",
                    role = "Project owner",
                    kind = SlockMemberKind.Human,
                    presence = SlockPresence.Online,
                    detail = "Reviews Android Studio Preview and provides source HTML / assets.",
                    device = "Human · macOS"
                )
                SlockMemberRow(
                    name = "Long Offline Member Name With Mixed 中文角色说明",
                    role = "External collaborator",
                    kind = SlockMemberKind.Human,
                    presence = SlockPresence.Unknown,
                    detail = "Device info is intentionally weak metadata and should wrap or truncate cleanly.",
                    device = "Unknown device"
                )
            }

            SlockEmptyState(
                title = "No review tasks",
                detail = "This column is empty. Empty states keep the layout intentional instead of leaving blank space.",
                action = {
                    SlockButton(
                        text = "Create task",
                        onClick = {},
                        variant = SlockButtonVariant.Cyan,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            )

            SlockErrorState(
                title = "Members failed to load",
                detail = "Network unavailable. Keep previous state recoverable and let the user retry.",
                onRetry = {}
            )
        }
    }
}
