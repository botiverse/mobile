package build.raft.app

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
            fields =
                listOf(
                    "title",
                    "subtitle",
                    "items",
                    "showSelection",
                    "selectedDestination",
                    "http3Enabled",
                    "curlEnabled",
                    "networkEngine"
                )
        )
        assertDescriptorExcludes(
            descriptor = SettingsHomePageLinkPayload.serializer().descriptor,
            fields = listOf("selected", "selectedDestination", "showSelection")
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
                      "showSelection": true,
                      "selectedDestination": "billing"
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
                RaftSettingsConnectedAccount(providerId = "password", provider = "Email password", identifier = "artin@example.com", status = "connected"),
                RaftSettingsConnectedAccount(providerId = "", provider = "Email password", identifier = "artin@example.com", status = "connected"),
                RaftSettingsConnectedAccount(providerId = "google", provider = "Google", identifier = "artin@example.com", status = "connected"),
                RaftSettingsConnectedAccount(providerId = "github", provider = "GitHub", identifier = "Not connected", status = "notConnected"),
                RaftSettingsConnectedAccount(providerId = "saml", provider = "SAML", identifier = "corp.example.com", status = "connected")
            )

        assertEquals(
            listOf("google", "github", "saml"),
            accounts.visibleSettingsConnectedAccounts(appleSignInSupported = true).map { it.providerId }
        )
    }

    // Sign in with Apple is iOS-only. The sign-in page gates its button on
    // `raftPlatformSupportsAppleSignIn()`; this list had no such gate, so turning Apple on
    // server-side put a Connect entry on Android that can never complete (artin, task #981).
    @Test
    fun unsupportedHostHidesTheAppleConnectOfferButKeepsAnExistingAppleLink() {
        val offered =
            RaftSettingsConnectedAccount(providerId = "apple", provider = "Apple", identifier = "Not connected", status = "notConnected")
        val linked =
            RaftSettingsConnectedAccount(providerId = "apple", provider = "Apple", identifier = "artin@example.com", status = "connected")
        val google =
            RaftSettingsConnectedAccount(providerId = "google", provider = "Google", identifier = "artin@example.com", status = "connected")

        // Android: the offer disappears, everything else is untouched.
        assertEquals(
            listOf("google"),
            listOf(offered, google).visibleSettingsConnectedAccounts(appleSignInSupported = false).map { it.providerId }
        )

        // Android: an Apple identity linked from an iOS host must still render. Hiding it
        // would read as "this account was unlinked", which is a different and worse lie
        // than "you cannot link it here".
        assertEquals(
            listOf("apple", "google"),
            listOf(linked, google).visibleSettingsConnectedAccounts(appleSignInSupported = false).map { it.providerId }
        )

        // iOS: the offer is legitimate and must survive.
        assertEquals(
            listOf("apple", "google"),
            listOf(offered, google).visibleSettingsConnectedAccounts(appleSignInSupported = true).map { it.providerId }
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
