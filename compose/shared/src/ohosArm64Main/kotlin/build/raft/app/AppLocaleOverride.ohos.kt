package build.raft.app

import build.raft.app.data.AppLocale
import build.raft.app.data.kmmResourceLocaleTag
import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI

private const val SlockOhosAppLocaleServiceName = "slockOhosAppLocale"

/**
 * Host-side app-locale override channel (task #190). The KuiklyBase KNOI
 * `OhosResourceService` contract carries no locale parameter, so the active
 * app locale is pushed through this Slock-owned service instead. The ets host
 * (`SlockOhosKmmResource.ets`) rebuilds its override `ResourceManager` from
 * the tag; an empty tag (or `system`) restores system-locale resolution.
 */
@ServiceConsumer(name = SlockOhosAppLocaleServiceName)
interface SlockOhosAppLocaleService {
    fun setLocale(tag: String)
}

// Last tag the host has acknowledged. Null = nothing pushed yet; an empty
// string is a real value (follow system) and must still be pushed once so a
// user switching back to System clears a previously pushed override.
private var lastPushedAppLocaleTag: String? = null

internal actual fun applyAppLocaleOverride(locale: AppLocale) {
    val tag = locale.kmmResourceLocaleTag ?: ""
    if (tag == lastPushedAppLocaleTag) return
    val pushed =
        runCatching {
            KNOI.get<SlockOhosAppLocaleService>(SlockOhosAppLocaleServiceName)
                .setLocale(tag)
        }.isSuccess
    // Record only on success: if the ets provider is not registered yet the
    // next seed/string resolution retries instead of pinning the dropped tag.
    if (pushed) {
        lastPushedAppLocaleTag = tag
    }
}
