package build.raft.app

import build.raft.app.settings.RaftSettingsConnectedAccount
import build.raft.app.settings.parseSettingsAccountPayload
import build.raft.app.settings.visibleSettingsConnectedAccounts
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStandaloneLaunchPayloadTest {
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
}
