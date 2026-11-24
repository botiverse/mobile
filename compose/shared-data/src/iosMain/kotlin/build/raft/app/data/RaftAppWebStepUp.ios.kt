@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The step-up page opens in a private (ephemeral) web session: no Safari cookies, so another
 * account signed in there can't get in the way (the page is keyed by the request and ignores
 * cookies anyway). iCloud Keychain passkeys still show there, to be confirmed on a device (design
 * open question 2). The one switch to go back to the shared-cookie session, as the browser login
 * uses (`RaftIosMobileOAuthHost.openAppLogin`).
 */
@RaftCoreInternalApi const val RAFT_APP_WEB_STEP_UP_EPHEMERAL_SESSION = true

private const val WEB_AUTH_SESSION_ERROR_DOMAIN = "com.apple.AuthenticationServices.WebAuthenticationSession"
private const val WEB_AUTH_SESSION_CANCELED_LOGIN = 1L

/** The browser login's callback (`RaftIosMobileOAuthHost.configuredCallbackScheme`). */
@RaftCoreInternalApi actual fun raftAppWebStepUpReturnUri(): String? {
    val raw = NSBundle.mainBundle.objectForInfoDictionaryKey("SlockOAuthCallbackScheme") as? String ?: return null
    return "${raw.trim().lowercase()}://login/callback".takeIf { it in APP_LOGIN_RETURN_URIS }
}

@Composable
@RaftCoreInternalApi actual fun rememberRaftAppWebStepUpBrowser(): RaftAppWebStepUpBrowser {
    val viewController = LocalUIViewController.current
    return remember(viewController) { IosAppWebStepUpBrowser { viewController.view.window } }
}

/** Sessions in flight: the presentation context provider is held weakly. Main thread only. */
private val inFlight = mutableSetOf<IosWebStepUpPresentation>()

private class IosAppWebStepUpBrowser(private val window: () -> UIWindow?) : RaftAppWebStepUpBrowser {
    override suspend fun open(url: String, callbackScheme: String): RaftAppWebStepUpBrowserResult {
        val pageUrl = NSURL.URLWithString(url.trim())
        if (pageUrl == null || pageUrl.scheme?.lowercase() != "https") return RaftAppWebStepUpBrowserResult.Failed("url_invalid")
        val result = withContext(Dispatchers.Main) { present(pageUrl, callbackScheme) }
        RaftAppWebStepUp.log(
            "app_web_step_up_session ephemeral=$RAFT_APP_WEB_STEP_UP_EPHEMERAL_SESSION outcome=" +
                when (result) {
                    is RaftAppWebStepUpBrowserResult.Callback -> "callback"
                    RaftAppWebStepUpBrowserResult.Closed -> "closed"
                    is RaftAppWebStepUpBrowserResult.Failed -> "failed:${result.reason}"
                }
        )
        return result
    }

    private suspend fun present(pageUrl: NSURL, callbackScheme: String): RaftAppWebStepUpBrowserResult =
        suspendCancellableCoroutine { continuation ->
            val presentation = IosWebStepUpPresentation(window)
            fun finish(result: RaftAppWebStepUpBrowserResult) {
                inFlight.remove(presentation)
                if (continuation.isActive) continuation.resume(result)
            }
            val session =
                ASWebAuthenticationSession(uRL = pageUrl, callbackURLScheme = callbackScheme) { callbackUrl: NSURL?, error: NSError? ->
                    dispatch_async(dispatch_get_main_queue()) {
                        val uri = callbackUrl?.absoluteString
                        finish(
                            when {
                                uri != null -> RaftAppWebStepUpBrowserResult.Callback(uri)
                                error?.domain == WEB_AUTH_SESSION_ERROR_DOMAIN && error.code == WEB_AUTH_SESSION_CANCELED_LOGIN ->
                                    RaftAppWebStepUpBrowserResult.Closed
                                else -> RaftAppWebStepUpBrowserResult.Failed("as:${error?.code ?: "none"}")
                            }
                        )
                    }
                }
            session.presentationContextProvider = presentation
            session.prefersEphemeralWebBrowserSession = RAFT_APP_WEB_STEP_UP_EPHEMERAL_SESSION
            presentation.session = session
            inFlight += presentation
            // The dialog went away (Cancel, the page closed): take the sheet down with it.
            continuation.invokeOnCancellation {
                dispatch_async(dispatch_get_main_queue()) {
                    session.cancel()
                    inFlight.remove(presentation)
                }
            }
            if (!session.start()) finish(RaftAppWebStepUpBrowserResult.Failed("start_refused"))
        }
}

private class IosWebStepUpPresentation(
    private val window: () -> UIWindow?,
) : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
    var session: ASWebAuthenticationSession? = null

    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
        window() ?: UIApplication.sharedApplication.keyWindow ?: UIWindow()
}
