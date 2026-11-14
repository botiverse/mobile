package build.raft.app.shared

import android.app.Application
import android.content.Context
import android.os.Build
import build.hands.update.HandsDeviceId
import build.hands.update.internal.HandsClient
import build.raft.app.BuildConfig
import build.raft.app.RaftHostStrings
import build.raft.app.data.RaftInstallChannelLatest
import build.raft.app.data.RaftInstallChannelUpdates
import build.raft.app.data.RaftInstallChannelUpdatesHost
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The install channel the minimum build gate asks ([build.raft.app.data.RaftMinBuildGate]): Hands
 * for the APK build types, Google Play for `play` ([RaftAndroidPlayInstallChannel], src/play; the
 * other build types' copy is null).
 */
internal object RaftAndroidInstallChannels {
    fun install(application: Application) {
        RaftInstallChannelUpdatesHost.installed =
            if (BuildConfig.HANDS_APK_UPDATE_ENABLED) {
                RaftAndroidHandsInstallChannel(application)
            } else {
                RaftAndroidPlayInstallChannel.create(application)
            }
    }
}

/**
 * Hands, the APK builds' channel: the same update check as Settings > About (the route of
 * [resolveHandsUpdateRoute]). A build is available only when Hands offers one for this install
 * with an APK to download; Update opens that download.
 */
internal class RaftAndroidHandsInstallChannel(private val context: Context) : RaftInstallChannelUpdates {
    override val name: String = "hands"

    override suspend fun latest(): RaftInstallChannelLatest {
        if (BuildConfig.HANDS_BASE_URL.isBlank()) return RaftInstallChannelLatest.Unknown
        val route =
            resolveHandsUpdateRoute(
                alphaDeltaEnabled =
                    handsDeltaUpdateAllowed(
                        configuredEnabled = BuildConfig.HANDS_DELTA_UPDATE_ENABLED,
                        buildApplicationId = BuildConfig.APPLICATION_ID,
                        runtimePackageName = context.packageName,
                        appSlug = BuildConfig.HANDS_DELTA_APP_SLUG,
                        channel = BuildConfig.HANDS_DELTA_CHANNEL,
                    ),
                defaultAppSlug = BuildConfig.HANDS_APP_SLUG,
                defaultChannel = BuildConfig.HANDS_CHANNEL,
                deltaAppSlug = BuildConfig.HANDS_DELTA_APP_SLUG,
                deltaChannel = BuildConfig.HANDS_DELTA_CHANNEL,
            )
        val response =
            withContext(Dispatchers.IO) {
                HandsClient(BuildConfig.HANDS_BASE_URL).checkForUpdate(
                    slug = route.appSlug,
                    channel = route.channel,
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    productType = "android-apk",
                    platform = "android",
                    arch = Build.SUPPORTED_ABIS.firstOrNull { it.isNotBlank() },
                    filetype = "apk",
                    deviceId = HandsDeviceId.get(context),
                    languageTag =
                        handsUpdateLanguageTag(
                            appLanguageTag = RaftHostStrings.appLocaleLanguageTag(),
                            systemLanguageTag = Locale.getDefault().toLanguageTag(),
                        ),
                )
            }
        val offered: Long? = response.requireUpdate()?.first?.version_code
        val downloadUrl: String? = response.asset?.download_url?.trim()?.takeIf { it.startsWith("https://") }
        return when {
            offered != null && offered > 0L && downloadUrl != null ->
                RaftInstallChannelLatest.Available(build = offered, updateUrl = downloadUrl)
            offered == null -> RaftInstallChannelLatest.UpToDate
            else -> RaftInstallChannelLatest.Unknown
        }
    }
}
