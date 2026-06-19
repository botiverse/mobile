package ai.slock.compose

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsStandaloneLaunchPayloadTest {
    private val json = Json { encodeDefaults = true }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun settingsLaunchWireSchemasExcludeContentFields() {
        assertDescriptorExcludes(
            descriptor = WireSettingsHomeLaunchPayload.serializer().descriptor,
            fields = listOf("title", "subtitle", "items", "showSelection")
        )
        assertDescriptorExcludes(
            descriptor = WireSettingsAccountLaunchPayload.serializer().descriptor,
            fields = listOf(
                "profile",
                "connectedAccounts",
                "profileSectionTitle",
                "connectedAccountsTitle",
                "avatarInProgress",
                "passwordInProgress",
                "providerConnectingId"
            )
        )
        assertDescriptorExcludes(
            descriptor = WireSettingsBillingLaunchPayload.serializer().descriptor,
            fields = listOf("currentPlan", "plans", "labels", "trialActive", "usageRows")
        )
        assertDescriptorExcludes(
            descriptor = WireSettingsStorageLaunchPayload.serializer().descriptor,
            fields = listOf("rows", "totalSize", "safeClearNote", "feedback")
        )
        assertDescriptorExcludes(
            descriptor = WireSettingsComputersLaunchPayload.serializer().descriptor,
            fields = listOf("computers", "canAddComputer", "latestDaemonVersion", "emptyTitle")
        )
        assertDescriptorExcludes(
            descriptor = WireSettingsComputerDetailLaunchPayload.serializer().descriptor,
            fields = listOf("computer", "workspaces", "agents", "canOpenAgents", "canManageMachines")
        )
    }

    @Test
    fun settingsAccountLaunchIgnoresTopLevelProfileContent() {
        val payload =
            parseSettingsAccountPayload(
                payload = "",
                fallbackPageData =
                    """
                    {
                      "instanceId": "settings-account-launch",
                      "profile": {
                        "displayName": "Stale User",
                        "handle": "@stale",
                        "email": "stale@example.com"
                      },
                      "connectedAccounts": [
                        {"providerId":"github","provider":"GitHub","identifier":"stale","status":"connected"}
                      ],
                      "canEditProfile": true,
                      "canUploadAvatar": true,
                      "canChangePassword": true
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("settings-account-launch", payload.instanceId)
        assertEquals("", payload.profile.displayName)
        assertEquals("", payload.profile.email)
        assertEquals(emptyList(), payload.connectedAccounts)
        assertEquals(false, payload.canEditProfile)
        assertEquals(false, payload.canUploadAvatar)
        assertEquals(false, payload.canChangePassword)
    }

    @Test
    fun explicitAccountContentPayloadStillRendersSmokeData() {
        val payload =
            parseSettingsAccountPayload(
                payload = json.encodeToString(settingsAccountSmokePayload),
                fallbackPageData = """{"instanceId":"launch-only"}""",
                instanceId = "fallback"
            )

        assertEquals("settings-account-smoke", payload.instanceId)
        assertEquals("Artin", payload.profile.displayName)
        assertEquals(3, payload.connectedAccounts.size)
    }

    @Test
    fun settingsBillingLaunchIgnoresTopLevelPlanContent() {
        val payload =
            parseSettingsBillingPayload(
                payload = "",
                fallbackPageData =
                    """
                    {
                      "instanceId": "settings-billing-launch",
                      "status": "content",
                      "currentPlan": {"planName":"Enterprise","trialActive":true},
                      "plans": [
                        {"id":"enterprise","name":"Enterprise","price":"$999","features":["stale"]}
                      ]
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("settings-billing-launch", payload.instanceId)
        assertEquals("loading", payload.status)
        assertEquals(emptyList(), payload.plans)
        assertEquals(false, payload.currentPlan.trialActive)
    }

    @Test
    fun settingsStorageLaunchIgnoresTopLevelRows() {
        val payload =
            parseSettingsStoragePayload(
                payload = "",
                fallbackPageData =
                    """
                    {
                      "instanceId": "settings-storage-launch",
                      "totalSize": "99 GB",
                      "rows": [
                        {"target":"database","title":"Stale DB","size":"99 GB"}
                      ]
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("settings-storage-launch", payload.instanceId)
        assertEquals("0 B", payload.totalSize)
        assertEquals(emptyList(), payload.rows)
    }

    @Test
    fun settingsComputersLaunchIgnoresTopLevelComputerLists() {
        val payload =
            parseSettingsComputersPayload(
                payload = "",
                fallbackPageData =
                    """
                    {
                      "instanceId": "settings-computers-launch",
                      "computers": [
                        {"id":"machine-1","name":"Stale Machine","online":true}
                      ],
                      "latestDaemonVersion": "9.9.9",
                      "canAddComputer": true
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("settings-computers-launch", payload.instanceId)
        assertEquals("loading", payload.status)
        assertEquals(emptyList(), payload.computers)
        assertEquals(null, payload.latestDaemonVersion)
        assertEquals(false, payload.canAddComputer)
    }

    @Test
    fun settingsComputerDetailLaunchKeepsIdentityAndDropsLists() {
        val payload =
            parseSettingsComputerDetailPayload(
                payload = "",
                fallbackPageData =
                    """
                    {
                      "instanceId": "settings-computer-detail-launch",
                      "computerId": "machine-1",
                      "computerName": "Build Host",
                      "workspaces": [
                        {"id":"workspace-1","name":"Stale Workspace"}
                      ],
                      "agents": [
                        {"id":"agent-1","name":"Stale Agent"}
                      ],
                      "canManageMachines": true
                    }
                    """.trimIndent(),
                instanceId = "fallback"
            )

        assertEquals("settings-computer-detail-launch", payload.instanceId)
        assertEquals("machine-1", payload.computer.id)
        assertEquals("Build Host", payload.computer.name)
        assertEquals(emptyList(), payload.workspaces)
        assertEquals(emptyList(), payload.agents)
        assertEquals(false, payload.canManageMachines)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun assertDescriptorExcludes(descriptor: SerialDescriptor, fields: List<String>) {
        val names = (0 until descriptor.elementsCount)
            .map { index -> descriptor.getElementName(index) }
            .toSet()
        fields.forEach { field ->
            assertFalse(field in names, "$field must stay outside ${descriptor.serialName} launch pageData")
        }
    }
}
