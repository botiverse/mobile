package ai.slock.android.core.data.sample

import ai.slock.android.core.model.AgentProfile
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Presence

object AgentSampleData {
    val androidDeveloper = AgentProfile(
        id = MemberId("agent-android"),
        userId = "agent_android_developer",
        displayName = "Android-Developer",
        handle = "@Android-Developer",
        roleDescription = "Compose UI / components",
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
        roleDescription = "Data/API contracts and mock repositories",
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
        roleDescription = "Test planning and acceptance",
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
        roleDescription = "Product specs and interaction design",
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
