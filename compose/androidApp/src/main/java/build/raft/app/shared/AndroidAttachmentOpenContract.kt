package build.raft.app.shared

/** Identifies which attachment authority produced a launchable content URI. */
internal enum class AndroidAttachmentOpenSourceKind {
    PublicDownload,
    PrivateCache
}

internal val AndroidAttachmentOpenSourceKind.diagnosticName: String
    get() =
        when (this) {
            AndroidAttachmentOpenSourceKind.PublicDownload -> "public_download"
            AndroidAttachmentOpenSourceKind.PrivateCache -> "private_cache"
        }

/**
 * Platform-neutral description of the content URI that Android may grant to another app.
 *
 * Public Downloads are durable product records. A FileProvider-backed private cache source is
 * intentionally transient even when its identity-stable cache file can be reused after reopen.
 */
internal data class AndroidAttachmentContentUriSource(
    val kind: AndroidAttachmentOpenSourceKind,
    val contentUri: String,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val durableCache: Boolean
) {
    init {
        require(contentUri.startsWith("content://", ignoreCase = true)) {
            "Attachment open sources must use content URIs"
        }
        require(mimeType.isNotBlank() && mimeType.contains('/')) {
            "Attachment open sources require a MIME type"
        }
        require(displayName.isNotBlank()) { "Attachment open sources require a display name" }
        require(sizeBytes > 0L) { "Attachment open sources require non-empty content" }
        require(durableCache == (kind == AndroidAttachmentOpenSourceKind.PublicDownload)) {
            "Only published attachment sources are durable"
        }
    }
}

/** The Android intent action selected without importing Android framework classes into the seam. */
internal enum class AndroidAttachmentOpenIntentAction(val intentAction: String) {
    View("android.intent.action.VIEW"),
    InstallPackage("android.intent.action.INSTALL_PACKAGE")
}

internal enum class AndroidAttachmentOpenAction(val wireName: String) {
    Open("open"),
    Install("install")
}

internal enum class AndroidAttachmentOpenStatus(val wireName: String) {
    Opened("opened"),
    Cancelled("cancelled"),
    Missing("missing"),
    NoApp("no_app"),
    StorageFailed("storage_failed"),
    Failed("failed")
}

/**
 * Semantic result returned to shared code. It intentionally cannot carry a path, content URI,
 * exception detail, or platform-authored user-visible message.
 */
internal data class AndroidAttachmentOpenResult(
    val status: AndroidAttachmentOpenStatus,
    val cached: Boolean = false,
    val retryable: Boolean = false,
    val sizeBytes: Long? = null,
    val source: AndroidAttachmentOpenSourceKind? = null,
    val action: AndroidAttachmentOpenAction? = null
) {
    init {
        if (status == AndroidAttachmentOpenStatus.Opened) {
            require(sizeBytes != null && sizeBytes > 0L)
            require(source != null)
            require(action != null)
        } else {
            require(sizeBytes == null && source == null && action == null)
        }
    }

    fun toBridgePayload(): Map<String, Any> =
        buildMap {
            put("status", status.wireName)
            put("cached", cached)
            if (retryable) put("retryable", true)
            if (status == AndroidAttachmentOpenStatus.Opened) {
                put("sizeBytes", checkNotNull(sizeBytes))
                put("source", checkNotNull(source).diagnosticName)
                put("action", checkNotNull(action).wireName)
            }
        }

    companion object {
        fun opened(
            source: AndroidAttachmentContentUriSource,
            action: AndroidAttachmentOpenAction
        ): AndroidAttachmentOpenResult =
            AndroidAttachmentOpenResult(
                status = AndroidAttachmentOpenStatus.Opened,
                cached = source.durableCache,
                sizeBytes = source.sizeBytes,
                source = source.kind,
                action = action
            )

        fun cancelled(): AndroidAttachmentOpenResult =
            AndroidAttachmentOpenResult(status = AndroidAttachmentOpenStatus.Cancelled)

        fun missing(): AndroidAttachmentOpenResult =
            AndroidAttachmentOpenResult(
                status = AndroidAttachmentOpenStatus.Missing,
                retryable = true
            )

        fun noApp(cached: Boolean): AndroidAttachmentOpenResult =
            AndroidAttachmentOpenResult(
                status = AndroidAttachmentOpenStatus.NoApp,
                cached = cached
            )

        fun storageFailed(): AndroidAttachmentOpenResult =
            AndroidAttachmentOpenResult(
                status = AndroidAttachmentOpenStatus.StorageFailed,
                retryable = true
            )

        fun failed(cached: Boolean = false): AndroidAttachmentOpenResult =
            AndroidAttachmentOpenResult(
                status = AndroidAttachmentOpenStatus.Failed,
                cached = cached,
                retryable = true
            )

        fun terminal(status: String): AndroidAttachmentOpenResult =
            when (status) {
                AndroidAttachmentOpenStatus.Cancelled.wireName -> cancelled()
                AndroidAttachmentOpenStatus.Missing.wireName -> missing()
                AndroidAttachmentOpenStatus.NoApp.wireName -> noApp(cached = false)
                AndroidAttachmentOpenStatus.StorageFailed.wireName -> storageFailed()
                else -> failed()
            }
    }
}

/** A read grant is propagated only to the activity selected for this launch. */
internal enum class AndroidAttachmentReadGrantScope {
    SelectedTarget
}

/**
 * Pure launch contract consumed by the Android host when it creates the real Intent/chooser.
 * ClipData plus the read flag keep the selected target's content-URI grant attached to a chooser.
 */
internal data class AndroidAttachmentOpenLaunchSpec(
    val source: AndroidAttachmentContentUriSource,
    val action: AndroidAttachmentOpenIntentAction,
    val launchInChooser: Boolean,
    val grantsReadPermission: Boolean,
    val readGrantScope: AndroidAttachmentReadGrantScope,
    val placesContentUriInClipData: Boolean
)

internal fun androidAttachmentOpenLaunchSpec(
    source: AndroidAttachmentContentUriSource,
    packageArchive: Boolean
): AndroidAttachmentOpenLaunchSpec =
    AndroidAttachmentOpenLaunchSpec(
        source = source,
        action =
            if (packageArchive) {
                AndroidAttachmentOpenIntentAction.InstallPackage
            } else {
                AndroidAttachmentOpenIntentAction.View
            },
        launchInChooser = !packageArchive,
        grantsReadPermission = true,
        readGrantScope = AndroidAttachmentReadGrantScope.SelectedTarget,
        placesContentUriInClipData = true
    )

internal fun androidAttachmentPublicOpenSource(
    record: AndroidAttachmentPublicRecord
): AndroidAttachmentContentUriSource =
    AndroidAttachmentContentUriSource(
        kind = AndroidAttachmentOpenSourceKind.PublicDownload,
        contentUri = checkNotNull(record.uri),
        mimeType = record.mimeType,
        displayName = record.displayName,
        sizeBytes = record.sizeBytes,
        durableCache = true
    )

/**
 * Result of the identity-scoped private materializer. A terminal result is already semantic and
 * never exposes native error text to shared code.
 */
internal sealed interface AndroidAttachmentPrivateMaterialization<out T> {
    data class Ready<T>(val value: T) : AndroidAttachmentPrivateMaterialization<T>

    data class Terminal(
        val result: AndroidAttachmentOpenResult
    ) : AndroidAttachmentPrivateMaterialization<Nothing>
}

/**
 * The executable production composition for Android Open-with.
 *
 * A verified public Downloads record is preferred and consumed while the identity lock is held.
 * Public absence releases that lock before recovery/network materialization. The lock is then
 * reacquired to revalidate the owner, prefer a public record created concurrently by an explicit
 * Save, and launch exactly one source. Inspection/launch failures never silently fall back to a
 * second authority.
 */
internal class AndroidAttachmentOpenCoordinator<T>(
    private val ownerIsCurrent: () -> Boolean,
    private val withIdentityLock: (
        AndroidAttachmentPublicIdentity,
        () -> AndroidAttachmentOpenResult?
    ) -> AndroidAttachmentOpenResult?,
    private val resolvePublicSource: (
        AndroidAttachmentPublicIdentity
    ) -> AndroidAttachmentContentUriSource?,
    private val awaitPrivateRecovery: () -> Boolean,
    private val materializePrivate: (
        AndroidAttachmentPublicIdentity
    ) -> AndroidAttachmentPrivateMaterialization<T>,
    private val buildPrivateSource: (T) -> AndroidAttachmentContentUriSource?,
    private val launch: (
        AndroidAttachmentContentUriSource,
        T?
    ) -> AndroidAttachmentOpenResult,
    private val onPrivateSourceFailure: (Throwable) -> Unit = {},
    private val failureResult: (Throwable) -> AndroidAttachmentOpenResult
) {
    fun open(identity: AndroidAttachmentPublicIdentity): AndroidAttachmentOpenResult =
        try {
            openOrThrow(identity)
        } catch (throwable: Throwable) {
            failureResult(throwable)
        }

    private fun openOrThrow(identity: AndroidAttachmentPublicIdentity): AndroidAttachmentOpenResult {
        if (!ownerIsCurrent()) return AndroidAttachmentOpenResult.cancelled()

        val publicResult =
            withIdentityLock(identity) {
                if (!ownerIsCurrent()) {
                    AndroidAttachmentOpenResult.cancelled()
                } else {
                    resolvePublicSource(identity)?.let { publicSource ->
                        check(publicSource.kind == AndroidAttachmentOpenSourceKind.PublicDownload)
                        launch(publicSource, null)
                    }
                }
            }
        if (publicResult != null) return publicResult

        if (!awaitPrivateRecovery()) return AndroidAttachmentOpenResult.storageFailed()
        val materialized = materializePrivate(identity)
        if (materialized is AndroidAttachmentPrivateMaterialization.Terminal) {
            return materialized.result
        }
        val privateValue =
            (materialized as AndroidAttachmentPrivateMaterialization.Ready<T>).value
        val privateSource =
            try {
                buildPrivateSource(privateValue)
            } catch (throwable: Throwable) {
                onPrivateSourceFailure(throwable)
                return AndroidAttachmentOpenResult.storageFailed()
            } ?: return AndroidAttachmentOpenResult.storageFailed()
        check(privateSource.kind == AndroidAttachmentOpenSourceKind.PrivateCache)

        return withIdentityLock(identity) {
            if (!ownerIsCurrent()) {
                AndroidAttachmentOpenResult.cancelled()
            } else {
                val concurrentPublicSource = resolvePublicSource(identity)
                if (concurrentPublicSource != null) {
                    check(concurrentPublicSource.kind == AndroidAttachmentOpenSourceKind.PublicDownload)
                    launch(concurrentPublicSource, null)
                } else {
                    launch(privateSource, privateValue)
                }
            }
        } ?: failureResult(IllegalStateException("Attachment identity lock returned no result"))
    }
}

/**
 * Executable host launch semantics shared by production and unit tests. Android Intent creation,
 * package discovery, owner fencing, and startActivity remain injected platform operations.
 */
internal class AndroidAttachmentSourceLauncher(
    private val sourceIsReadable: () -> Boolean,
    private val hasVisibleHandler: () -> Boolean,
    private val runIfOwnerCurrent: (() -> Boolean) -> Boolean?,
    private val launchSource: () -> Unit,
    private val isActivityNotFound: (Throwable) -> Boolean,
    private val onFailure: (Throwable) -> Unit = {}
) {
    fun launch(
        source: AndroidAttachmentContentUriSource,
        action: AndroidAttachmentOpenAction,
        requiresVisibleHandler: Boolean
    ): AndroidAttachmentOpenResult {
        return try {
            if (!sourceIsReadable()) return AndroidAttachmentOpenResult.missing()
            if (requiresVisibleHandler && !hasVisibleHandler()) {
                return AndroidAttachmentOpenResult.noApp(source.durableCache)
            }
            val launched =
                runIfOwnerCurrent {
                    if (!sourceIsReadable()) {
                        false
                    } else {
                        launchSource()
                        true
                    }
                }
            when (launched) {
                null -> AndroidAttachmentOpenResult.cancelled()
                false -> AndroidAttachmentOpenResult.missing()
                true -> AndroidAttachmentOpenResult.opened(source, action)
            }
        } catch (throwable: Throwable) {
            if (isActivityNotFound(throwable)) {
                AndroidAttachmentOpenResult.noApp(source.durableCache)
            } else {
                onFailure(throwable)
                AndroidAttachmentOpenResult.failed(source.durableCache)
            }
        }
    }
}

/**
 * APK install retains one direct ACTION_VIEW fallback only when the installer action is absent.
 * Permission, argument, and other launch failures never trigger a second platform launch.
 */
internal fun executeAndroidAttachmentPlatformLaunch(
    allowViewFallback: Boolean,
    launchPrimary: () -> Unit,
    launchViewFallback: () -> Unit,
    isActivityNotFound: (Throwable) -> Boolean
) {
    try {
        launchPrimary()
    } catch (throwable: Throwable) {
        if (!allowViewFallback || !isActivityNotFound(throwable)) throw throwable
        launchViewFallback()
    }
}

/**
 * Identity-stable private cache name. The principal prefix preserves scoped cleanup while the full
 * public identity hash prevents attachment-id sanitization/truncation collisions.
 */
internal fun androidAttachmentPrivateCacheFileName(
    identity: AndroidAttachmentPublicIdentity
): String = "${identity.principalKey}-${identity.storageKey}"
