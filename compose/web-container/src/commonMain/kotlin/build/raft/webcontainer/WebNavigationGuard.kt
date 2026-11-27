package build.raft.webcontainer

/** Outcome of evaluating a main-frame navigation against [WebNavigationPolicy] and host hooks. */
sealed interface WebNavigationDecision {
    data object Allow : WebNavigationDecision

    data class Block(
        val url: String,
        val reason: String,
    ) : WebNavigationDecision

    data class InterceptedByHost(val url: String) : WebNavigationDecision

    data class ReissueWithHeader(
        val url: String,
        val headerName: String,
        val headerValue: String,
    ) : WebNavigationDecision

    data class ReissueWithoutHeader(
        val url: String,
        val headerName: String,
    ) : WebNavigationDecision
}

/** Tracks redirect-chain header state and reissue budget for a single main-frame navigation. */
class WebNavigationChainState(
    private val maxHops: Int = 32,
) {
    var carriesScopedHeader: Boolean = false
        private set
    var remainingReissues: Int = 0
        private set
    private var defaultReissueBudget: Int = 0
    var hasCommittedDocument: Boolean = false
        private set
    var starter: WebNavigationStarter = WebNavigationStarter.Other
        private set
    private val hops = mutableListOf<String>()

    fun currentHops(): List<String> = hops.toList()

    fun configureBudget(reissueBudget: Int) {
        defaultReissueBudget = reissueBudget.coerceAtLeast(0)
        if (remainingReissues == 0 && !carriesScopedHeader) {
            remainingReissues = defaultReissueBudget
        }
    }

    fun startChain(
        carriesHeader: Boolean,
        reissueBudget: Int,
        starter: WebNavigationStarter,
        initialHop: String? = null,
    ) {
        defaultReissueBudget = reissueBudget.coerceAtLeast(0)
        carriesScopedHeader = carriesHeader
        remainingReissues = defaultReissueBudget
        this.starter = starter
        hops.clear()
        if (initialHop != null) {
            recordHop(initialHop)
        }
    }

    fun recordHop(url: String) {
        if (url.isNotEmpty() && hops.size < maxHops && hops.lastOrNull() != url) {
            hops += url
        }
    }

    fun consumeReissue(nextCarriesHeader: Boolean): Boolean {
        if (remainingReissues <= 0) return false
        remainingReissues -= 1
        carriesScopedHeader = nextCarriesHeader
        return true
    }

    fun markCommitted() {
        hasCommittedDocument = true
        carriesScopedHeader = false
        remainingReissues = defaultReissueBudget
        starter = WebNavigationStarter.Page
        hops.clear()
    }

    fun reset(clearCommitted: Boolean = false) {
        carriesScopedHeader = false
        remainingReissues = defaultReissueBudget
        starter = WebNavigationStarter.Other
        hops.clear()
        if (clearCommitted) {
            hasCommittedDocument = false
        }
    }
}

/**
 * Evaluates a main-frame navigation request against host interception hooks, scheme allowlists,
 * origin locks, navigation-disabled document matching, external routing, and origin-scoped header
 * attachment/stripping.
 */
fun evaluateMainFrameNavigation(
    targetUrl: String,
    initialUrl: String,
    policy: WebNavigationPolicy,
    method: String = "GET",
    requestCarriesScopedHeader: Boolean = false,
    chainState: WebNavigationChainState? = null,
    isUserGesture: Boolean = false,
    isRedirect: Boolean = false,
    committedDocumentUrl: String = "",
    catchesMainFrameNavigation: ((WebMainFrameInterceptContext) -> Boolean)? = null,
    routesMainFrameNavigation: ((String) -> Boolean)? = null,
    routesMainFrameNavigationWithContext: ((WebMainFrameRouteContext) -> Boolean)? = null,
    mutateChainState: Boolean = true,
): WebNavigationDecision {
    val trimmed = targetUrl.trim()
    if (trimmed.isEmpty()) {
        return WebNavigationDecision.Block(url = "", reason = "empty_url")
    }

    // 1. Host-level catch hook (e.g., native OAuth / sign-in handoff).
    if (catchesMainFrameNavigation != null) {
        val context =
            WebMainFrameInterceptContext(
                url = trimmed,
                method = method,
                isUserGesture = isUserGesture,
                isRedirect = isRedirect,
                starter = chainState?.starter ?: WebNavigationStarter.Other,
                priorHops = chainState?.currentHops().orEmpty(),
            )
        if (catchesMainFrameNavigation(context)) {
            return WebNavigationDecision.InterceptedByHost(trimmed)
        }
    }

    // 2. Allow empty-source about:blank teardown/init documents cleanly.
    if (initialUrl.isBlank() && trimmed.equals("about:blank", ignoreCase = true)) {
        return WebNavigationDecision.Allow
    }

    // 3. Scheme allowlist (fail closed when non-empty).
    val scheme = extractUrlScheme(trimmed)
    if (scheme == null || (policy.normalizedAllowedSchemes.isNotEmpty() && scheme !in policy.normalizedAllowedSchemes)) {
        return WebNavigationDecision.Block(url = trimmed, reason = "scheme_not_allowed")
    }

    // 4. Origin lock.
    val targetOrigin = parseWebContainerOrigin(trimmed)
    val lockedOrigin = policy.lockedMainFrameOrigin
    if (lockedOrigin != null && targetOrigin != lockedOrigin) {
        return WebNavigationDecision.Block(url = trimmed, reason = "origin_locked")
    }

    // 5. Strict single-document fence when navigation is disabled.
    if (!policy.allowNavigation && !isSameDocumentIgnoringFragment(initialUrl.trim(), trimmed)) {
        return WebNavigationDecision.Block(url = trimmed, reason = "navigation_disabled")
    }

    // 6. Host external router (e.g., open external link in system browser).
    val routedExternally =
        if (routesMainFrameNavigationWithContext != null) {
            routesMainFrameNavigationWithContext(
                WebMainFrameRouteContext(
                    url = trimmed,
                    isRedirect = isRedirect,
                    isUserGesture = isUserGesture,
                    committedDocumentUrl = committedDocumentUrl,
                ),
            )
        } else {
            routesMainFrameNavigation != null && routesMainFrameNavigation(trimmed)
        }
    if (routedExternally) {
        return WebNavigationDecision.Block(url = trimmed, reason = "routed_externally")
    }

    if (!mutateChainState) {
        return WebNavigationDecision.Allow
    }

    // 7. Origin-scoped header attachment and cross-origin redirect stripping (GET only).
    // Match #356 §3 (`RaftAppEntry.kt`): only attach the origin-scoped header on explicit host
    // launches (`chainState == null`) or pre-commit non-gesture launch-chain redirects
    // (`!hasCommittedDocument && !isUserGesture && starter == Launch`). Cancelling and re-issuing a
    // post-commit link/GET-form navigation via `loadUrl`/`loadRequest` downgrades `Sec-Fetch-Site`
    // from `same-origin` to `none` and strips `Referer`, breaking CSRF-checked same-origin consent
    // flows such as Stamp's `GET /login?agree=1`. Cross-origin stripping always applies regardless
    // of commit state so scoped headers can never leak off-origin.
    val headerRule = policy.requestHeaderRule
    if (headerRule != null && method.equals("GET", ignoreCase = true)) {
        val onHeaderOrigin = targetOrigin == headerRule.allowedOrigin
        val chainHadHeader = requestCarriesScopedHeader || (chainState?.carriesScopedHeader == true)
        val allowHeaderAttach =
            chainState == null ||
                (
                    !chainState.hasCommittedDocument &&
                        !isUserGesture &&
                        chainState.starter == WebNavigationStarter.Launch
                )
        if (onHeaderOrigin && !requestCarriesScopedHeader && allowHeaderAttach) {
            val allowedByBudget = chainState?.consumeReissue(nextCarriesHeader = true) ?: true
            if (allowedByBudget) {
                return WebNavigationDecision.ReissueWithHeader(
                    url = trimmed,
                    headerName = headerRule.headerName,
                    headerValue = headerRule.headerValue,
                )
            }
        } else if (!onHeaderOrigin && chainHadHeader) {
            val allowedByBudget = chainState?.consumeReissue(nextCarriesHeader = false) ?: true
            if (allowedByBudget) {
                return WebNavigationDecision.ReissueWithoutHeader(
                    url = trimmed,
                    headerName = headerRule.headerName,
                )
            }
            // Budget exhausted while header would leak cross-origin: fail closed.
            return WebNavigationDecision.Block(url = trimmed, reason = "header_strip_budget_exhausted")
        }
    }

    chainState?.recordHop(trimmed)
    return WebNavigationDecision.Allow
}

/**
 * Builds the HTTP header map for a same-origin [WebNavigationDecision.ReissueWithHeader] load,
 * preserving a browser-supplied `Referer` header from [originalHeaders] (matched case-insensitively,
 * rejecting CR/LF) without ever fabricating a `Referer` when the browser omitted one.
 */
fun buildReissueWithHeaderHeaders(
    headerName: String,
    headerValue: String,
    originalHeaders: Map<String, String>?,
): Map<String, String> {
    val referer =
        if (headerName.equals("Referer", ignoreCase = true)) {
            null
        } else {
            extractOriginalRefererHeader(originalHeaders)
        }
    return if (referer != null) {
        linkedMapOf("Referer" to referer, headerName to headerValue)
    } else {
        mapOf(headerName to headerValue)
    }
}

internal fun extractOriginalRefererHeader(headers: Map<String, String>?): String? =
    headers
        ?.entries
        ?.firstOrNull { (k, v) -> k.equals("Referer", ignoreCase = true) && v.isNotBlank() }
        ?.value
        ?.takeIf { value -> value.none { ch -> ch == '\r' || ch == '\n' } }

/** Validates whether a subframe URL is permitted by [policy]. */
fun isAllowedSubframeUrl(
    rawUrl: String?,
    policy: WebNavigationPolicy,
): Boolean {
    val trimmed = rawUrl?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    if (trimmed.equals("about:blank", ignoreCase = true) || trimmed.equals("about:srcdoc", ignoreCase = true)) {
        return true
    }
    val scheme = extractUrlScheme(trimmed) ?: return false
    if (scheme == "blob" || scheme == "data") {
        return true
    }
    if (policy.normalizedAllowedSchemes.isNotEmpty() && scheme !in policy.normalizedAllowedSchemes) {
        return false
    }
    val allowedHosts = policy.normalizedSubframeHosts
    if (allowedHosts.isEmpty()) return true
    val origin = parseWebContainerOrigin(trimmed) ?: return false
    val host = origin.host.removeSuffix(".")
    return allowedHosts.any { rawAllowed ->
        val allowed = rawAllowed.removeSuffix(".")
        allowed.isNotEmpty() && (host == allowed || host.endsWith(".$allowed"))
    }
}

/**
 * Picks a safe URL to reload after renderer death or WebView recreation:
 * - Strips configured sensitive query keys and optional URL fragments (e.g., `#host=...`).
 * - Rejects bootstrap placeholder paths and cross-origin URLs.
 * - Falls back to [initialUrl] if [candidateUrl] is blank, malformed, or disallowed.
 */
fun sanitizeRendererRestoreUrl(
    initialUrl: String,
    candidateUrl: String?,
    policy: WebNavigationPolicy,
    isAllowedCandidate: (String) -> Boolean = { true },
): String {
    val fallback = initialUrl.trim()
    val rawCandidate = candidateUrl?.trim().orEmpty()
    if (rawCandidate.isEmpty() || rawCandidate.equals("about:blank", ignoreCase = true)) {
        return fallback
    }
    val initialOrigin = parseWebContainerOrigin(fallback)
    val candidateOrigin = parseWebContainerOrigin(rawCandidate)
    if (initialOrigin == null || candidateOrigin == null || candidateOrigin != initialOrigin) {
        return fallback
    }

    val withoutFragment =
        if (policy.restoreStripFragments) {
            rawCandidate.substringBefore('#')
        } else {
            rawCandidate
        }
    val queryStart = withoutFragment.indexOf('?')
    val basePart = if (queryStart >= 0) withoutFragment.substring(0, queryStart) else withoutFragment
    val afterAuthority = basePart.substringAfter("://", "")
    val slashIdx = afterAuthority.indexOf('/')
    val lowerPath = if (slashIdx >= 0) afterAuthority.substring(slashIdx).lowercase() else "/"
    if (policy.restoreBootstrapPathMarkers.any { marker -> marker.isNotBlank() && lowerPath.contains(marker.lowercase()) }) {
        return fallback
    }

    val sanitized =
        if (queryStart < 0 || policy.restoreStripQueryKeys.isEmpty()) {
            withoutFragment
        } else {
            val stripLower = policy.restoreStripQueryKeys.mapTo(HashSet()) { it.lowercase() }
            val query = withoutFragment.substring(queryStart + 1)
            val kept =
                query.split('&').filter { pair ->
                    if (pair.isEmpty()) return@filter false
                    val key = pair.substringBefore('=').lowercase()
                    key !in stripLower
                }
            if (kept.isEmpty()) basePart else "$basePart?${kept.joinToString("&")}"
        }

    return if (isAllowedCandidate(sanitized)) sanitized else fallback
}

/** Strips query string and fragment before logging or emitting telemetry (`scheme://host[:port]/path`). */
fun sanitizeUrlForTelemetry(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return ""
    val withoutFragment = trimmed.substringBefore('#')
    val withoutQuery = withoutFragment.substringBefore('?')
    val origin = parseWebContainerOrigin(withoutQuery) ?: return extractUrlScheme(withoutQuery)?.let { "$it://<opaque>" } ?: "<invalid>"
    val afterScheme = withoutQuery.substringAfter("://")
    val slashIdx = afterScheme.indexOf('/')
    val path = if (slashIdx >= 0) afterScheme.substring(slashIdx) else ""
    return "${origin.asOriginString()}$path"
}

/** Extracts lowercase RFC 3986 scheme before `:`, or `null` if invalid. */
fun extractUrlScheme(rawUrl: String): String? {
    val colonIdx = rawUrl.indexOf(':')
    if (colonIdx <= 0) return null
    val scheme = rawUrl.substring(0, colonIdx)
    if (!scheme[0].isLetter()) return null
    for (i in 1 until scheme.length) {
        val ch = scheme[i]
        if (!ch.isLetterOrDigit() && ch != '+' && ch != '-' && ch != '.') return null
    }
    return scheme.lowercase()
}

/** Compares two URLs by normalized origin, path, and query while ignoring `#fragment`. */
internal fun isSameDocumentIgnoringFragment(
    expectedUrl: String,
    candidateUrl: String,
): Boolean {
    if (expectedUrl.isEmpty() || candidateUrl.isEmpty()) return false
    val a = expectedUrl.substringBefore('#')
    val b = candidateUrl.substringBefore('#')
    if (a == b) return true
    val originA = parseWebContainerOrigin(a) ?: return false
    val originB = parseWebContainerOrigin(b) ?: return false
    if (originA != originB) return false
    val tailA = extractPathAndQuery(a)
    val tailB = extractPathAndQuery(b)
    return tailA == tailB
}

private fun extractPathAndQuery(urlWithoutFragment: String): String {
    val afterScheme = urlWithoutFragment.substringAfter("://", "")
    val idx = afterScheme.indexOfAny(charArrayOf('/', '?'))
    if (idx < 0) return "/"
    val tail = afterScheme.substring(idx)
    return if (tail.startsWith("?")) "/$tail" else tail
}
