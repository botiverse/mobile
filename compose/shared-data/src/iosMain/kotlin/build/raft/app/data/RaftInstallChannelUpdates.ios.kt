package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.network.RaftAppIdentityRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale

@RaftCoreInternalApi actual fun raftPlatformInstallChannelUpdates(): RaftInstallChannelUpdates? =
    if (raftIsTestFlightInstall()) RaftTestFlightInstallChannel else RaftAppStoreInstallChannel

@RaftCoreInternalApi actual fun raftPlatformEarlyAccessGuide(): RaftEarlyAccessPlatformGuide? =
    RaftEarlyAccessPlatformGuide(
        kind =
            if (raftIsTestFlightInstall()) {
                RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_ACTIVE
            } else {
                RAFT_EARLY_ACCESS_GUIDE_IOS_TESTFLIGHT_JOIN
            },
        url = RAFT_IOS_TESTFLIGHT_JOIN_URL,
    )

/**
 * The App Store as the iOS install channel of the minimum build gate: the public lookup
 * (`https://itunes.apple.com/lookup?bundleId=`) for this bundle in the device's region (then
 * without a region when the app is not listed there). Its version name gives the lowest build
 * number of the listed release ([raftBuildNumberOfVersionName]); its page is where Update goes.
 */
@RaftCoreInternalApi object RaftAppStoreInstallChannel : RaftInstallChannelUpdates {
    override val name: String = "app_store"

    private val client by lazy { HttpClient(Darwin) }

    @Volatile
    private var lastPage: String? = null

    /**
     * Only an App Store install shows the new-version notice. Its receipt is named `receipt`;
     * TestFlight's is `sandboxReceipt`, and Xcode / simulator builds have none, so those never
     * show it (the minimum build gate still asks the App Store for every build).
     */
    override val offersStoreUpdateNotice: Boolean by lazy { raftIsAppStoreInstall() }

    override suspend fun latest(): RaftInstallChannelLatest {
        val bundleId = NSBundle.mainBundle.bundleIdentifier?.takeIf { it.isNotBlank() } ?: return RaftInstallChannelLatest.Unknown
        val country = NSLocale.currentLocale.countryCode?.lowercase()?.takeIf { it.length == 2 }
        val listing = (country?.let { lookup(bundleId, it) }) ?: lookup(bundleId, null)
        listing?.trackViewUrl?.let { lastPage = it }
        return raftAppStoreLatest(listing, parseRaftBuildNumber(RaftAppIdentityRegistry.current()?.buildNumber))
    }

    override fun fallbackUpdateUrl(): String? = lastPage

    private suspend fun lookup(bundleId: String, country: String?): RaftAppStoreListing? {
        val url =
            buildString {
                append("https://itunes.apple.com/lookup?bundleId=").append(bundleId.encodeURLParameter())
                if (country != null) append("&country=").append(country.encodeURLParameter())
            }
        val response = client.get(url)
        if (!response.status.isSuccess()) return null
        return parseRaftAppStoreLookup(response.bodyAsText(), bundleId)
    }
}

/**
 * Hands read-only TestFlight install channel (`#proj-mobile:#391`): when installed via TestFlight
 * (`sandboxReceipt`), queries `GET https://hands.build/public/v2/apps/raft-ios/testflight/latest`
 * and compares the integer `version_code` against `CFBundleVersion`. Automatic checks wait briefly
 * for [RaftServicesPrivacyGate] and stay silent when privacy mode is on/unknown; explicit user
 * taps on Settings › About › Check for update always check directly.
 */
@RaftCoreInternalApi object RaftTestFlightInstallChannel : RaftInstallChannelUpdates {
    override val name: String = "testflight"

    private const val PRIVACY_WAIT_MILLIS: Long = 10_000L

    private val client by lazy { HttpClient(Darwin) }

    override val offersStoreUpdateNotice: Boolean = true

    override suspend fun latest(): RaftInstallChannelLatest = latest(userInitiated = false)

    override suspend fun latest(userInitiated: Boolean): RaftInstallChannelLatest {
        if (!userInitiated) {
            val mode =
                if (RaftServicesPrivacyGate.allowsAutomatic()) {
                    RaftPrivacyMode.Off
                } else {
                    RaftServicesPrivacyGate.awaitLaunchDecision(waitMillis = PRIVACY_WAIT_MILLIS)
                }
            if (!RaftServicesPrivacyGate.allowsAutomatic(mode)) {
                return RaftInstallChannelLatest.Unknown
            }
        }
        val response = client.get(RAFT_IOS_HANDS_TESTFLIGHT_LATEST_URL)
        val parsed = parseRaftHandsTestFlightResponse(response.status.value, response.bodyAsText())
        return raftHandsTestFlightLatest(
            response = parsed,
            currentBuild = parseRaftBuildNumber(RaftAppIdentityRegistry.current()?.buildNumber),
        )
    }

    override fun fallbackUpdateUrl(): String = RAFT_IOS_TESTFLIGHT_JOIN_URL
}

@Suppress("DEPRECATION")
private fun raftIsAppStoreInstall(): Boolean =
    runCatching {
        val receipt = NSBundle.mainBundle.appStoreReceiptURL ?: return@runCatching false
        val path = receipt.path ?: return@runCatching false
        receipt.lastPathComponent == "receipt" && NSFileManager.defaultManager.fileExistsAtPath(path)
    }.getOrDefault(false)

@Suppress("DEPRECATION")
private fun raftIsTestFlightInstall(): Boolean =
    runCatching {
        val receipt = NSBundle.mainBundle.appStoreReceiptURL ?: return@runCatching false
        val path = receipt.path ?: return@runCatching false
        receipt.lastPathComponent == "sandboxReceipt" && NSFileManager.defaultManager.fileExistsAtPath(path)
    }.getOrDefault(false)
