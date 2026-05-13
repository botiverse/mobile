package ai.slock.android.core.data.sample

import ai.slock.android.core.model.AgentProfile
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.MemberId
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.MemberProfile
import ai.slock.compose.model.toMemberProfile
import ai.slock.android.core.model.Presence
import ai.slock.compose.model.toMemberProfile

object AgentSampleData {
    val androidDeveloper = AgentProfile(
        id = MemberId("agent-android"),
        userId = "agent_android_developer",
        displayName = "Android-Developer",
        handle = "@Android-Developer",
        description = "Compose UI / components",
        roleDescription = "agent",
        avatarUrl = "pixel:diamond",
        avatarLabel = "A",
        presence = Presence.Active,
        statusLabel = "online",
        runtime = "Android UI worker",
        model = "GPT-5.5",
        reasoning = "high",
        hasOnlineMachine = true,
        permissionBadges = listOf("agent", "ui")
    )

    val apiIntegrationDeveloper = AgentProfile(
        id = MemberId("agent-api"),
        userId = "agent_api_integration_developer",
        displayName = "API-Integration-Developer",
        handle = "@API-Integration-Developer",
        description = "Data/API contracts and mock repositories",
        roleDescription = "agent",
        avatarUrl = "random:api-integration-developer",
        avatarLabel = "I",
        presence = Presence.Active,
        statusLabel = "online",
        runtime = "API/data worker",
        model = "GPT-5.5",
        reasoning = "high",
        hasOnlineMachine = true,
        permissionBadges = listOf("agent", "api")
    )

    val qaTester = AgentProfile(
        id = MemberId("agent-qa"),
        userId = "agent_qa_tester",
        displayName = "QA-Tester",
        handle = "@QA-Tester",
        description = "Test planning and acceptance",
        roleDescription = "agent",
        avatarUrl = "pixel:star",
        avatarLabel = "Q",
        presence = Presence.Away,
        statusLabel = "away",
        runtime = "QA worker",
        model = "GPT-5.4",
        reasoning = "medium",
        hasOnlineMachine = false,
        permissionBadges = listOf("agent", "qa")
    )

    val productUxDesigner = AgentProfile(
        id = MemberId("agent-product-ux"),
        userId = "agent_product_ux_designer",
        displayName = "Product-UX-Designer",
        handle = "@Product-UX-Designer",
        description = "Product specs and interaction design",
        roleDescription = "agent",
        avatarUrl = "random:product-ux-designer",
        avatarLabel = "P",
        presence = Presence.Offline,
        statusLabel = "offline",
        runtime = "Product design worker",
        model = "GPT-5.4",
        reasoning = "medium",
        hasOnlineMachine = false,
        permissionBadges = listOf("agent", "ux")
    )

    val agents: List<AgentProfile> = listOf(
        androidDeveloper,
        apiIntegrationDeveloper,
        qaTester,
        productUxDesigner
    )

    val agentMembers: List<MemberProfile> = agents.map { it.toMemberProfile() }
}
