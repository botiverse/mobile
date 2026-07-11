package build.raft.app

import build.raft.app.data.SlockShellStoreScope
import build.raft.app.model.SlockTimeContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsStandaloneLaunchPayloadTest {
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
            fields = listOf("computers", "canAddComputer", "latestDaemonVersion", "latestComputerVersion", "emptyTitle")
        )
        assertDescriptorExcludes(
            descriptor = WireSettingsComputerDetailLaunchPayload.serializer().descriptor,
            fields = listOf("computer", "computerName", "workspaces", "agents", "canOpenAgents", "canManageMachines")
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
    fun explicitSettingsHomeContentPayloadIsTreatedAsLaunchOnly() {
        val payload =
            parseSettingsHomePayload(
                payload =
                    """
                    {
                      "instanceId": "settings-home-explicit",
                      "title": "Stale Settings",
                      "subtitle": "Should not render",
                      "items": [
                        {"key":"billing","title":"Stale Billing","subtitle":"content"}
                      ],
                      "showSelection": true
                    }
                    """.trimIndent(),
                fallbackPageData = """{"instanceId":"fallback"}""",
                instanceId = "fallback"
            )

        assertEquals("settings-home-explicit", payload.instanceId)
        assertEquals("Settings", payload.title)
        assertEquals("", payload.subtitle)
        assertEquals(emptyList(), payload.items)
    }

    @Test
    fun explicitAccountContentPayloadIsTreatedAsLaunchOnly() {
        val payload =
            parseSettingsAccountPayload(
                payload =
                    """
                    {
                      "instanceId": "settings-account-explicit",
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
                fallbackPageData = """{"instanceId":"launch-only"}""",
                instanceId = "fallback"
            )

        assertEquals("settings-account-explicit", payload.instanceId)
        assertEquals("", payload.profile.displayName)
        assertEquals("", payload.profile.email)
        assertEquals(emptyList(), payload.connectedAccounts)
        assertEquals(false, payload.canEditProfile)
        assertEquals(false, payload.canUploadAvatar)
        assertEquals(false, payload.canChangePassword)
    }

    @Test
    fun accountConnectedAccountsHidePasswordIdentityRows() {
        // React parity: web Connected accounts lists only social providers; the
        // email+password identity is covered by the Email field + Change
        // password row (artin, task #341).
        val accounts =
            listOf(
                KuiklySettingsConnectedAccount(providerId = "password", provider = "Email password", identifier = "artin@example.com", status = "connected"),
                KuiklySettingsConnectedAccount(providerId = "", provider = "Email password", identifier = "artin@example.com", status = "connected"),
                KuiklySettingsConnectedAccount(providerId = "google", provider = "Google", identifier = "artin@example.com", status = "connected"),
                KuiklySettingsConnectedAccount(providerId = "github", provider = "GitHub", identifier = "Not connected", status = "notConnected"),
                KuiklySettingsConnectedAccount(providerId = "saml", provider = "SAML", identifier = "corp.example.com", status = "connected")
            )

        assertEquals(
            listOf("google", "github", "saml"),
            accounts.visibleSettingsConnectedAccounts().map { it.providerId }
        )
    }

    @Test
    fun explicitBillingContentPayloadIsTreatedAsLaunchOnly() {
        val payload =
            parseSettingsBillingPayload(
                payload =
                    """
                    {
                      "instanceId": "settings-billing-explicit",
                      "status": "content",
                      "currentPlan": {"planName":"Enterprise","trialActive":true},
                      "plans": [
                        {"id":"enterprise","name":"Enterprise","price":"$999","features":["stale"]}
                      ]
                    }
                    """.trimIndent(),
                fallbackPageData = """{"instanceId":"fallback"}""",
                instanceId = "fallback"
            )

        assertEquals("settings-billing-explicit", payload.instanceId)
        assertEquals("loading", payload.status)
        assertEquals(emptyList(), payload.plans)
        assertEquals(false, payload.currentPlan.trialActive)
    }

    @Test
    fun explicitStorageContentPayloadIsTreatedAsLaunchOnly() {
        val payload =
            parseSettingsStoragePayload(
                payload =
                    """
                    {
                      "instanceId": "settings-storage-explicit",
                      "totalSize": "99 GB",
                      "rows": [
                        {"target":"database","title":"Stale DB","size":"99 GB"}
                      ]
                    }
                    """.trimIndent(),
                fallbackPageData = """{"instanceId":"fallback"}""",
                instanceId = "fallback"
            )

        assertEquals("settings-storage-explicit", payload.instanceId)
        assertEquals("0 B", payload.totalSize)
        assertEquals(emptyList(), payload.rows)
    }

    @Test
    fun explicitComputersContentPayloadIsTreatedAsLaunchOnly() {
        val payload =
            parseSettingsComputersPayload(
                payload =
                    """
                    {
                      "instanceId": "settings-computers-explicit",
                      "computers": [
                        {"id":"machine-1","name":"Stale Machine","online":true}
                      ],
                      "latestDaemonVersion": "9.9.9",
                      "latestComputerVersion": "9.9.8",
                      "canAddComputer": true
                    }
                    """.trimIndent(),
                fallbackPageData = """{"instanceId":"fallback"}""",
                instanceId = "fallback"
            )

        assertEquals("settings-computers-explicit", payload.instanceId)
        assertEquals("loading", payload.status)
        assertEquals(emptyList(), payload.computers)
        assertEquals(null, payload.latestDaemonVersion)
        assertEquals(null, payload.latestComputerVersion)
        assertEquals(false, payload.canAddComputer)
    }

    @Test
    fun explicitComputerDetailContentPayloadKeepsOnlyLaunchIdentity() {
        val payload =
            parseSettingsComputerDetailPayload(
                payload =
                    """
                    {
                      "instanceId": "settings-computer-detail-explicit",
                      "computerId": "machine-1",
                      "computerName": "Build Host",
                      "computer": {"id":"other","name":"Wrong Host"},
                      "workspaces": [
                        {"id":"workspace-1","name":"Stale Workspace"}
                      ],
                      "agents": [
                        {"id":"agent-1","name":"Stale Agent"}
                      ],
                      "canManageMachines": true
                    }
                    """.trimIndent(),
                fallbackPageData = """{"instanceId":"fallback"}""",
                instanceId = "fallback"
            )

        assertEquals("settings-computer-detail-explicit", payload.instanceId)
        assertEquals("machine-1", payload.computer.id)
        assertEquals("Computer", payload.computer.name)
        assertEquals(emptyList(), payload.workspaces)
        assertEquals(emptyList(), payload.agents)
        assertEquals(false, payload.canManageMachines)
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
        assertEquals("Computer", payload.computer.name)
        assertEquals(emptyList(), payload.workspaces)
        assertEquals(emptyList(), payload.agents)
        assertEquals(false, payload.canManageMachines)
    }

    @Test
    fun settingsComputerDetailPageDataCarriesIdentityOnly() {
        val pageData =
            settingsComputerDetailPageData(
                computerId = "machine-1",
                timeContext = SlockTimeContext(localOffsetMinutes = 480, todayEpochDay = 20628, nowEpochMillis = 1_782_222_000_000),
                storeScope = SlockShellStoreScope.create("shell-a", "server-a"),
            )

        assertEquals(SETTINGS_COMPUTER_DETAIL_PAGE, pageData.optString("pageName"))
        assertEquals("machine-1", pageData.optString("computerId"))
        assertEquals("machine-1", pageData.optString("machineId"))
        assertEquals("shell-a", pageData.optString(SLOCK_SHELL_INSTANCE_ID))
        assertEquals("server-a", pageData.optString(SLOCK_SHELL_SERVER_ID))
        assertEquals("480", pageData.optString("localOffsetMinutes"))
        assertEquals("20628", pageData.optString("todayEpochDay"))
        assertEquals("1782222000000", pageData.optString("nowEpochMillis"))
        assertEquals("24h", pageData.optString("timeFormat"))
        assertEquals("", pageData.optString("payload"))
        assertEquals("", pageData.optString("computerName"))
        assertEquals("", pageData.optString("workspaces"))
        assertEquals("", pageData.optString("agents"))
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
