package build.raft.app.data

import build.raft.app.network.RaftAppIdentityRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale

internal actual fun raftPlatformInstallChannelUpdates(): RaftInstallChannelUpdates? = RaftAppStoreInstallChannel

/**
 * The App Store as the iOS install channel of the minimum build gate: the public lookup
 * (`https://itunes.apple.com/lookup?bundleId=`) for this bundle in the device's region (then
 * without a region when the app is not listed there). Its version name gives the lowest build
 * number of the listed release ([raftBuildNumberOfVersionName]); its page is where Update goes.
 */
internal object RaftAppStoreInstallChannel : RaftInstallChannelUpdates {
    override val name: String = "app_store"

    private val client by lazy { HttpClient(Darwin) }

    @Volatile
    private var lastPage: String? = null

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
