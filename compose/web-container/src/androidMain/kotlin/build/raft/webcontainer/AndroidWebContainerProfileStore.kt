package build.raft.webcontainer

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import build.raft.webcontainer.pool.WebIsolationClearResult
import java.util.concurrent.atomic.AtomicLong

/**
 * Android Chromium multi-profile binding and teardown helper (`androidx.webkit.ProfileStore`).
 *
 * Enforces:
 * - Each [WebIsolationScope.Ephemeral] WebView gets a unique per-instance scrubbable profile when
 *   `MULTI_PROFILE` is supported, deleted after that WebView is destroyed so separate ephemeral
 *   sessions never share cookies or persist across views.
 * - Fail-closed behavior: if `MULTI_PROFILE` is unsupported for [WebIsolationScope.Named] or
 *   `setProfile` throws for `Named`/`Ephemeral`, [applyProfile] returns `false` rather than
 *   silently leaking the `Default` user session.
 * - Active-instance eviction contract through [WebContainerAndroidKeepAlive] before `deleteProfile`.
 */
object AndroidWebContainerProfileStore {
    private const val PROFILE_PREFIX = "raft_wc_"
    private const val EPHEMERAL_PREFIX = "raft_wc_eph_"
    private val ephemeralCounter = AtomicLong(0L)

    /** True when the installed Android WebView provider supports `MULTI_PROFILE`. */
    fun supportsMultiProfile(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /** Deterministic Chromium profile name for a [WebIsolationScope.Named]. */
    fun profileNameFor(scope: WebIsolationScope.Named): String = "$PROFILE_PREFIX${scope.id}"

    /** Allocates a unique per-instance profile name for an ephemeral WebView session. */
    internal fun allocateEphemeralProfileName(): String =
        "$EPHEMERAL_PREFIX${ephemeralCounter.incrementAndGet()}"

    /**
     * Binds [webView] to the Chromium profile matching [scope] immediately after construction and
     * before any setting or load.
     *
     * @return `true` when [scope] is satisfied, or `false` when isolation could not be applied
     *   (caller must fail closed and refuse to load).
     */
    fun applyProfile(
        webView: WebView,
        scope: WebIsolationScope,
        ephemeralProfileName: String? = null,
    ): Boolean {
        return when (scope) {
            WebIsolationScope.Default -> true
            WebIsolationScope.Ephemeral -> {
                if (!supportsMultiProfile()) {
                    // Older Chromium WebView without MULTI_PROFILE: settings-level isolation
                    // (no DOM storage, no cache) is the platform ceiling.
                    true
                } else {
                    val name = ephemeralProfileName ?: allocateEphemeralProfileName()
                    runCatching {
                        ProfileStore.getInstance().getOrCreateProfile(name)
                        WebViewCompat.setProfile(webView, name)
                        true
                    }.getOrDefault(false)
                }
            }
            is WebIsolationScope.Named -> {
                if (!supportsMultiProfile()) return false
                runCatching {
                    val name = profileNameFor(scope)
                    ProfileStore.getInstance().getOrCreateProfile(name)
                    WebViewCompat.setProfile(webView, name)
                    true
                }.getOrDefault(false)
            }
        }
    }

    /** Deletes a per-instance ephemeral profile AFTER its owning WebView has been destroyed. */
    internal fun deleteEphemeralProfile(profileName: String): Boolean {
        if (!supportsMultiProfile()) return true
        return runCatching { ProfileStore.getInstance().deleteProfile(profileName) }.getOrDefault(false)
    }

    /**
     * Clears cookies/storage/profile data for [scope], first evicting and destroying all idle
     * pooled views bound to [scope] and deferring deletion if foreground views remain active.
     */
    fun clearIsolationScope(scope: WebIsolationScope): WebIsolationClearResult =
        WebContainerAndroidKeepAlive.clearIsolationScope(scope) { targetScope ->
            deletePlatformProfile(targetScope)
        }

    internal fun deletePlatformProfile(scope: WebIsolationScope): Boolean {
        return when (scope) {
            WebIsolationScope.Default -> {
                runCatching {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    true
                }.getOrDefault(false)
            }
            WebIsolationScope.Ephemeral -> {
                // Ephemeral instances own unique profiles scrubbed automatically upon destroy().
                true
            }
            is WebIsolationScope.Named -> {
                if (!supportsMultiProfile()) return false
                runCatching {
                    ProfileStore.getInstance().deleteProfile(profileNameFor(scope))
                }.getOrDefault(false)
            }
        }
    }
}
