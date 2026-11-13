package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `app_install_source_read.store`: where this install came from, as the platform could tell.
 * Android only for now (Google Play Install Referrer).
 */
@Serializable
enum class RaftInstallStore(val wire: String) {
    /** Installed by Google Play and Play gave a referrer. */
    @SerialName("google_play") GooglePlay("google_play"),
    /** Not installed by Google Play (sideload, Hands APK), or Play gave no referrer. */
    @SerialName("direct") Direct("direct"),
    /** The referrer service could not answer (not supported, unavailable, developer error). */
    @SerialName("unknown") Unknown("unknown"),
}

/**
 * The install source as it is stored on the device and sent. Only the store and the three
 * validated campaign parameters: never the raw referrer, click ids (gclid ...), install/click
 * timestamps or device identifiers.
 */
@Serializable
data class RaftAppInstallSourceReadProperties(
    val store: RaftInstallStore,
    @SerialName("utm_source") val utmSource: String? = null,
    @SerialName("utm_medium") val utmMedium: String? = null,
    @SerialName("utm_campaign") val utmCampaign: String? = null,
    /**
     * True when this is the first version installed (`firstInstallTime == lastUpdateTime` at read
     * time), false when the install had been updated before the read (an existing user reaching
     * this build). Null (omitted) only if the platform could not tell.
     */
    @SerialName("fresh_install") val freshInstall: Boolean? = null,
)

/** Server `PRODUCT_EVENT_STRING_TOKEN` (`campaignToken`, max 64): no dot. */
private val RaftInstallSourceToken = Regex("^[a-z0-9_-]{1,64}$")

/**
 * Server `PRODUCT_EVENT_CAMPAIGN_PATH` (`campaignPath`, the `account_created.utm_campaign` rule):
 * a token or `owner/repo`, each segment 1-128 of `[a-z0-9_.-]` and not only dots (max 257).
 */
private val RaftInstallSourceCampaignPath =
    Regex("^(?!\\.+(?:/|$))[a-z0-9_.-]{1,128}(?:/(?!\\.+$)[a-z0-9_.-]{1,128})?$")

internal fun raftInstallSourceToken(value: String?): String? =
    value?.trim()?.lowercase()?.takeIf { RaftInstallSourceToken.matches(it) }

internal fun raftInstallSourceCampaign(value: String?): String? =
    value?.trim()?.lowercase()?.takeIf { RaftInstallSourceCampaignPath.matches(it) }

/**
 * A Google Play install referrer (`utm_source=...&utm_medium=...`) as the stored properties: blank
 * is [RaftInstallStore.Direct]; otherwise [RaftInstallStore.GooglePlay] with only utm_source,
 * utm_medium and utm_campaign kept, each dropped unless it passes the server's rule. Every other
 * parameter (gclid and other click ids included) is ignored.
 */
fun parseRaftInstallReferrer(referrer: String?): RaftAppInstallSourceReadProperties {
    val raw = referrer?.trim().orEmpty()
    if (raw.isEmpty()) return RaftAppInstallSourceReadProperties(RaftInstallStore.Direct)
    val params = HashMap<String, String?>()
    raw.split('&').forEach { pair ->
        val separator = pair.indexOf('=')
        if (separator <= 0) return@forEach
        val key = raftPercentDecode(pair.substring(0, separator)) ?: return@forEach
        if (key !in RaftInstallReferrerKeys || key in params) return@forEach
        params[key] = raftPercentDecode(pair.substring(separator + 1))
    }
    return RaftAppInstallSourceReadProperties(
        store = RaftInstallStore.GooglePlay,
        utmSource = raftInstallSourceToken(params["utm_source"]),
        utmMedium = raftInstallSourceToken(params["utm_medium"]),
        utmCampaign = raftInstallSourceCampaign(params["utm_campaign"]),
    )
}

private val RaftInstallReferrerKeys = setOf("utm_source", "utm_medium", "utm_campaign")

/** `application/x-www-form-urlencoded` decoding; null when the escapes or UTF-8 are malformed. */
private fun raftPercentDecode(value: String): String? {
    if ('%' !in value && '+' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char == '+' -> {
                bytes += ' '.code.toByte()
                index += 1
            }
            char == '%' -> {
                if (index + 2 >= value.length) return null
                val digits = value.substring(index + 1, index + 3)
                if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                val hex = digits.toInt(16)
                bytes += hex.toByte()
                index += 3
            }
            else -> {
                char.toString().encodeToByteArray().forEach { bytes += it }
                index += 1
            }
        }
    }
    return runCatching { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull()
}

/**
 * The install source, read once per install and reported once per install (Android only):
 * the host reads it at first launch and calls [recordRead]; after the first sign-in on the install
 * it becomes one `app_install_source_read` event, sent through [RaftProductEvents] and so only to
 * a server whose `/product-events/config` allows client events ("Share usage data"). It is marked
 * sent when handed over and never sent again, even if that account does not share usage data.
 *
 * State lives in the per-install device-state store ([raftDeviceStateKeyValueStore], excluded from
 * backup), so a restored backup reads and reports its own install. Hosts that never call [install]
 * (iOS, desktop) never read or send anything.
 */
object RaftInstallSourceReport {
    private const val PropertiesKey = "installSource:properties"
    private const val SentKey = "installSource:sent"

    private val lock = SynchronizedObject()
    private var store: SharedPreferenceStore? = null
    private var signedIn = false

    fun install(store: SharedPreferenceStore) {
        synchronized(lock) { this.store = store }
        maybeSend()
    }

    /** True until a read result has been stored on this install. */
    fun needsRead(): Boolean =
        synchronized(lock) {
            val current = store ?: return false
            current.getString(PropertiesKey) == null && current.getString(SentKey) == null
        }

    /** Stores the first read result on this install; later calls are ignored. */
    fun recordRead(properties: RaftAppInstallSourceReadProperties) {
        synchronized(lock) {
            val current = store ?: return
            if (current.getString(PropertiesKey) != null) return
            current.putString(
                PropertiesKey,
                RaftProductEventJson.encodeToString(RaftAppInstallSourceReadProperties.serializer(), properties),
            )
        }
        raftLogInfoInstallSource("install_source_read store=${properties.store.wire}")
        maybeSend()
    }

    internal fun onSignedIn() {
        synchronized(lock) { signedIn = true }
        maybeSend()
    }

    internal fun onSignedOut() {
        synchronized(lock) { signedIn = false }
    }

    private fun maybeSend() {
        val properties =
            synchronized(lock) {
                val current = store ?: return
                if (!signedIn || current.getString(SentKey) != null) return
                val raw = current.getString(PropertiesKey) ?: return
                val decoded =
                    runCatching {
                        RaftProductEventJson.decodeFromString(RaftAppInstallSourceReadProperties.serializer(), raw)
                    }.getOrNull()
                current.putString(SentKey, "1")
                decoded
            } ?: return
        RaftProductEvents.appInstallSourceRead(properties)
        raftLogInfoInstallSource("install_source_reported store=${properties.store.wire}")
    }
}

private fun raftLogInfoInstallSource(message: String) {
    build.raft.app.raftLogInfo("RaftInstallSource", message)
}
