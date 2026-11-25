package build.raft.webcontainer

import java.awt.Component
import java.awt.Toolkit
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/** Per-session callback sink registered in [MacOsNativeWebBridge]. */
internal interface MacOsNativeSessionHandler {
    fun onNavigationDecisionRequest(
        decisionId: Long,
        url: String,
        isMainFrame: Boolean,
        method: String,
        carriesScopedHeader: Boolean,
        isUserGesture: Boolean,
        starterCode: Int,
    )

    fun onStateChanged(
        url: String,
        title: String,
        canGoBack: Boolean,
        canGoForward: Boolean,
        isLoading: Boolean,
        progress: Int,
    )

    fun onLoadStarted(url: String)

    fun onLoadCommit(url: String)

    fun onLoadFinished(url: String)

    fun onLoadFailure(reason: String)

    fun onProcessTerminated(didCrash: Boolean)

    fun onJsbMessage(
        replyId: Long,
        rawPayload: String,
        frameOriginUrl: String,
        documentUrl: String,
        isMainFrame: Boolean,
    )

    fun onEvalResult(
        evalId: Long,
        result: String?,
    )

    fun onNewWindowRequested(url: String)
}

/**
 * Low-level JNI gateway to `libraft_webcontainer_macos.dylib` (`RaftMacWebContainerBridge.m`).
 *
 * Native library resolution order on macOS:
 * 1. Packaged app resources (`compose.application.resources.dir/libraft_webcontainer_macos.dylib`),
 *    which `jpackage` bundles directly inside `Raft.app/Contents/app/resources/` so `codesign --deep`
 *    and Gatekeeper validate it without temp extraction.
 * 2. Classpath resource `/native/darwin-<arch>/libraft_webcontainer_macos.dylib` (used by
 *    `./gradlew :desktopApp:test` and `:desktopApp:run`), extracted atomically into a
 *    content-addressed directory under `java.io.tmpdir` (pre-signed ad-hoc at build time).
 *
 * Non-macOS hosts or missing binaries leave [isLoaded] as `false` without throwing so callers
 * cleanly fall back to another [DesktopWebEngine].
 */
internal object MacOsNativeWebBridge {
    private const val LIB_FILENAME = "libraft_webcontainer_macos.dylib"

    private val sessions = ConcurrentHashMap<Long, MacOsNativeSessionHandler>()
    private val nextClearId = AtomicLong(0L)
    private val pendingClears = ConcurrentHashMap<Long, (Boolean) -> Unit>()

    @Volatile
    private var loadedWithoutInit: Boolean = false

    /** Returns `true` only if [isLoaded] has ALREADY succeeded in this process (never triggers dlopen). */
    val isLoadedIfInitialized: Boolean
        get() = loadedWithoutInit

    val isLoaded: Boolean by lazy { loadNativeLibraryIfMacOs().also { loadedWithoutInit = it } }

    fun registerSession(
        sessionId: Long,
        handler: MacOsNativeSessionHandler,
    ) {
        sessions[sessionId] = handler
    }

    fun unregisterSession(sessionId: Long) {
        sessions.remove(sessionId)
    }

    /**
     * Dispatches `WKWebsiteDataStore` cleanup to macOS Thread 0.
     *
     * When [onComplete] is `null`, runs fire-and-forget with `clearId = 0L` without allocating a
     * [pendingClears] entry. When non-null, returns a cancellation handle that removes the pending
     * callback immediately on timeout/coroutine cancellation, and invokes `onComplete(false)` on
     * missing library or JNI error (never false-positive `true`).
     */
    fun clearIsolationScopeAsync(
        namedScopeUuid: String?,
        onComplete: ((Boolean) -> Unit)? = null,
    ): () -> Unit {
        if (!isLoaded) {
            onComplete?.invoke(false)
            return {}
        }
        if (onComplete == null) {
            runCatching { nativeClearIsolationScope(0L, namedScopeUuid) }
            return {}
        }
        val clearId = nextClearId.incrementAndGet()
        pendingClears[clearId] = onComplete
        try {
            nativeClearIsolationScope(clearId, namedScopeUuid)
        } catch (_: Throwable) {
            pendingClears.remove(clearId)?.invoke(false)
            return {}
        }
        return { pendingClears.remove(clearId) }
    }

    private fun loadNativeLibraryIfMacOs(): Boolean {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        if (!osName.contains("mac") && !osName.contains("darwin")) return false
        return runCatching {
            // Ensure AWT & libjawt.dylib are loaded before probing JAWT_GetAWT.
            val javaHome = System.getProperty("java.home")?.trim().orEmpty()
            runCatching { Toolkit.getDefaultToolkit() }
            val jawtLoadedByName = runCatching { System.loadLibrary("jawt") }.isSuccess
            if (!jawtLoadedByName && javaHome.isNotEmpty()) {
                for (rel in listOf("lib/libjawt.dylib", "jre/lib/libjawt.dylib")) {
                    val candidate = File(javaHome, rel)
                    if (candidate.isFile && runCatching { System.load(candidate.absolutePath) }.isSuccess) {
                        break
                    }
                }
            }

            // 1. Packaged Raft.app bundle resource (already inside Raft.app/Contents/app/resources/).
            var dylibLoaded = false
            val appResourcesDir = System.getProperty("compose.application.resources.dir")?.trim().orEmpty()
            if (appResourcesDir.isNotEmpty()) {
                val bundled = File(appResourcesDir, LIB_FILENAME)
                if (bundled.isFile) {
                    System.load(bundled.absolutePath)
                    dylibLoaded = true
                }
            }

            // 2. Classpath resource for Gradle run / test invocations.
            if (!dylibLoaded) {
                val arch = normalizeMacOsArch(System.getProperty("os.arch").orEmpty())
                val resourcePath = "/native/darwin-$arch/$LIB_FILENAME"
                val bytes =
                    MacOsNativeWebBridge::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
                        ?: return@runCatching false
                val sha =
                    MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                        .take(16)
                val cacheDir = File(System.getProperty("java.io.tmpdir"), "raft-webcontainer-native-$sha")
                cacheDir.mkdirs()
                val target = File(cacheDir, LIB_FILENAME)
                if (!target.isFile || target.length() != bytes.size.toLong()) {
                    val tmp =
                        File(cacheDir, "$LIB_FILENAME.${ProcessHandle.current().pid()}.${Thread.currentThread().id}.tmp")
                    tmp.writeBytes(bytes)
                    try {
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        if (tmp.exists()) runCatching { tmp.delete() }
                    }
                }
                System.load(target.absolutePath)
            }

            // Fail closed if JAWT_GetAWT cannot be resolved (cleanly falls back to Wry instead of blank screen).
            nativeProbeJawt(javaHome.takeIf { it.isNotEmpty() })
        }.getOrDefault(false)
    }

    internal fun normalizeMacOsArch(rawArch: String): String =
        when (rawArch.trim().lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x86_64"
            else -> rawArch.trim().lowercase()
        }

    // ---- JNI native entry points (all non-blocking / dispatch_async to Thread 0) ---------------

    @JvmStatic
    external fun nativeProbeJawt(javaHome: String?): Boolean

    @JvmStatic
    external fun nativeCreateSession(
        sessionId: Long,
        awtCanvas: Component,
        initialUrl: String,
        allowScripts: Boolean,
        cacheEnabled: Boolean,
        ephemeral: Boolean,
        namedScopeUuid: String?,
        jsbPortName: String?,
        userScriptsJson: String,
        allowsBackForwardGestures: Boolean,
        headerName: String?,
        headerValue: String?,
        attachInitialHeader: Boolean,
        initialX: Int,
        initialY: Int,
        initialW: Int,
        initialH: Int,
        initialVisible: Boolean,
    ): Boolean

    @JvmStatic
    external fun nativeUpdateBounds(
        sessionId: Long,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        visible: Boolean,
    )

    @JvmStatic
    external fun nativeLoadUrl(
        sessionId: Long,
        url: String,
        headerName: String?,
        headerValue: String?,
    )

    @JvmStatic
    external fun nativeGoBack(sessionId: Long)

    @JvmStatic
    external fun nativeGoForward(sessionId: Long)

    @JvmStatic
    external fun nativeReload(sessionId: Long)

    @JvmStatic
    external fun nativeStopLoading(sessionId: Long)

    @JvmStatic
    external fun nativeEvaluateJavaScript(
        sessionId: Long,
        evalId: Long,
        script: String,
        isolatedWorld: Boolean,
    )

    @JvmStatic
    external fun nativeCompleteNavigationDecision(
        sessionId: Long,
        decisionId: Long,
        actionCode: Int,
        reissueUrl: String?,
        headerName: String?,
        headerValue: String?,
    )

    @JvmStatic
    external fun nativeCompleteJsbReply(
        sessionId: Long,
        replyId: Long,
        replyJson: String,
    )

    @JvmStatic
    external fun nativeClearIsolationScope(
        clearId: Long,
        namedScopeUuid: String?,
    )

    @JvmStatic
    external fun nativeDisposeSession(sessionId: Long)

    // ---- JNI callbacks invoked from macOS Thread 0 (strictly async hop onto AWT EDT) -----------

    @JvmStatic
    fun onNavigationDecisionRequest(
        sessionId: Long,
        decisionId: Long,
        url: String,
        isMainFrame: Boolean,
        method: String,
        carriesScopedHeader: Boolean,
        isUserGesture: Boolean,
        starterCode: Int,
    ) {
        val handler = sessions[sessionId]
        if (handler == null) {
            nativeCompleteNavigationDecision(sessionId, decisionId, 0, url, null, null)
            return
        }
        SwingUtilities.invokeLater {
            val active = sessions[sessionId]
            if (active == null) {
                nativeCompleteNavigationDecision(sessionId, decisionId, 0, url, null, null)
            } else {
                active.onNavigationDecisionRequest(
                    decisionId = decisionId,
                    url = url,
                    isMainFrame = isMainFrame,
                    method = method,
                    carriesScopedHeader = carriesScopedHeader,
                    isUserGesture = isUserGesture,
                    starterCode = starterCode,
                )
            }
        }
    }

    @JvmStatic
    fun onStateChanged(
        sessionId: Long,
        url: String,
        title: String,
        canGoBack: Boolean,
        canGoForward: Boolean,
        isLoading: Boolean,
        progress: Int,
    ) {
        SwingUtilities.invokeLater {
            sessions[sessionId]?.onStateChanged(url, title, canGoBack, canGoForward, isLoading, progress)
        }
    }

    @JvmStatic
    fun onLoadStarted(
        sessionId: Long,
        url: String,
    ) {
        SwingUtilities.invokeLater { sessions[sessionId]?.onLoadStarted(url) }
    }

    @JvmStatic
    fun onLoadCommit(
        sessionId: Long,
        url: String,
    ) {
        SwingUtilities.invokeLater { sessions[sessionId]?.onLoadCommit(url) }
    }

    @JvmStatic
    fun onLoadFinished(
        sessionId: Long,
        url: String,
    ) {
        SwingUtilities.invokeLater { sessions[sessionId]?.onLoadFinished(url) }
    }

    @JvmStatic
    fun onLoadFailure(
        sessionId: Long,
        reason: String,
    ) {
        SwingUtilities.invokeLater { sessions[sessionId]?.onLoadFailure(reason) }
    }

    @JvmStatic
    fun onProcessTerminated(
        sessionId: Long,
        didCrash: Boolean,
    ) {
        SwingUtilities.invokeLater { sessions[sessionId]?.onProcessTerminated(didCrash) }
    }

    @JvmStatic
    fun onJsbMessage(
        sessionId: Long,
        replyId: Long,
        rawPayload: String,
        frameOriginUrl: String,
        documentUrl: String,
        isMainFrame: Boolean,
    ) {
        val handler = sessions[sessionId]
        if (handler == null) {
            nativeCompleteJsbReply(
                sessionId,
                replyId,
                """{"v":1,"ok":false,"error":{"code":"BRIDGE_DISABLED","message":"Session unavailable"}}""",
            )
            return
        }
        SwingUtilities.invokeLater {
            val active = sessions[sessionId]
            if (active == null) {
                nativeCompleteJsbReply(
                    sessionId,
                    replyId,
                    """{"v":1,"ok":false,"error":{"code":"BRIDGE_DISABLED","message":"Session unavailable"}}""",
                )
            } else {
                active.onJsbMessage(replyId, rawPayload, frameOriginUrl, documentUrl, isMainFrame)
            }
        }
    }

    @JvmStatic
    fun onEvalResult(
        sessionId: Long,
        evalId: Long,
        result: String?,
    ) {
        SwingUtilities.invokeLater { sessions[sessionId]?.onEvalResult(evalId, result) }
    }

    @JvmStatic
    fun onNewWindowRequested(
        sessionId: Long,
        url: String,
    ) {
        SwingUtilities.invokeLater { sessions[sessionId]?.onNewWindowRequested(url) }
    }

    @JvmStatic
    fun onClearIsolationScopeComplete(clearId: Long) {
        SwingUtilities.invokeLater {
            pendingClears.remove(clearId)?.let { callback ->
                runCatching { callback(true) }
            }
        }
    }
}
