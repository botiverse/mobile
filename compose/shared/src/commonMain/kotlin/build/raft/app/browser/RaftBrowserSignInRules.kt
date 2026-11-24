package build.raft.app

import build.raft.app.data.percentEncode
import build.raft.app.links.RaftHttpUrl

/*
 * "Log in with Raft" from a page of the in-app browser (#356 §5): the rules, pure so each one is
 * unit-tested (RaftBrowserSignInRulesTest, RaftBrowserSignInSkipTest). Ports of slock's desktop rules
 * (apps/desktop/src/main/webPreviewRaftSignInRules.ts) and of what the web's sheet and setup page
 * decide (packages/web/src/features/desktopBrowserSignIn, features/login-with-raft/loginErrors.ts,
 * packages/shared/src/oauthRedirect.ts). In the order a request meets them:
 * 1. raftAuthorizeRequest: is the navigation Raft's authorize endpoint on this environment's API
 *    origin ([raftAuthorizeOrigin]), with one return address the page could be sent to? Or
 *    raftSetupEntryRequest: is it the setup page itself, opened directly on this environment's web
 *    app origin (no authorize fetch: the sheet's query is built from an allow-list, and step 3 is
 *    skipped)?
 * 2. raftSignInAskRefusal: may it ask, from this document, through these addresses, while the person
 *    is looking at the page?
 * 3. raftSetupAnswer: did Raft's server accept it, for the return address the page named? Then
 *    raftSignInSetupRequest: can the sheet show it in Raft's own words?
 * 4. raftSignInIsRegisteredReturnAddress: is that the App's registered return address?
 * 5. raftSignInSkipTarget: did the person already consent, so that no sheet is needed?
 * 6. What the page is sent: raftSignInSuccessUrl, raftSignInInvalidScopeUrl or raftSignInDeniedUrl,
 *    each loaded only when raftSignInResultUrl takes it.
 */

/** Where Raft's server sends an accepted authorize request (slock routes/oauth.ts `GET /authorize`). */
internal const val RAFT_SIGN_IN_SETUP_PATH = "/login-with-raft/setup"

/** The scopes of a request that names none (slock web `desktopBrowserSignInRequest`). */
private const val RAFT_SIGN_IN_DEFAULT_SCOPE = "openid profile"

/**
 * The scopes Raft has its own words for (slock web `OAUTH_SCOPE_PRESENTATION`): a request naming any
 * other is not shown, since the sheet would print it as the page wrote it.
 */
internal val RaftSignInVisibleScopes: Set<String> =
    setOf(
        "openid",
        "profile",
        "email",
        "identity",
        "agent:read",
        "app:read",
        "agent:event:write",
        "agent:notification:write",
        "agent:voice",
    )

/** The hosts an OAuth return address may use over plain http (slock shared `isSafeOAuthReturnUrl`). */
private val RaftOAuthLoopbackHosts = setOf("localhost", "127.0.0.1", RAFT_BROWSER_IPV6_LOOPBACK)

/**
 * The authorize endpoint's paths as the server routes them (slock app.ts mounts the OAuth router at
 * `/api/oauth` and at `/oidc/:server/api/oauth`): Express matches letters in either case and takes one
 * trailing slash, never a repeated one. Under a Server's mount, `oidcScopedMountGuard` compares the
 * rest with `/authorize` as written, so there only the mount's own letters may differ in case. The
 * segment is the server's `OIDC_SERVER_SEGMENT_PATTERN`, a Server id or slug.
 */
private val RaftRootAuthorizePath = Regex("/api/oauth/authorize/?", RegexOption.IGNORE_CASE)
private val RaftScopedAuthorizeMount = Regex("^/oidc/[A-Za-z0-9-]{1,128}/api/oauth", RegexOption.IGNORE_CASE)

/** A client id as a log line may carry it. */
private val RaftLoggableClientId = Regex("[A-Za-z0-9._:-]{1,128}")

/** What separates scopes, and the values of `prompt` (`/\s+/` on the web). */
private val RaftSignInListSeparator = Regex("\\s+")

/**
 * The origin Raft's authorize endpoint must be on, from the environment's API base URL
 * ([apiBaseUrl], its path ignored): https with no port; in a debug build ([allowLoopback]) also one
 * on [RaftBrowserDebugLoopbackHosts], http or https, with any port. Null: nothing is caught (slock
 * desktop `raftAuthorizeOrigin`).
 */
internal fun raftAuthorizeOrigin(apiBaseUrl: String, allowLoopback: Boolean): String? {
    val url = raftBrowserUrlOf(apiBaseUrl)?.takeIf { it.userInfo == null } ?: return null
    if (url.scheme == "https" && url.port == null) return url.origin
    return url.origin.takeIf { allowLoopback && url.host in RaftBrowserDebugLoopbackHosts }
}

/**
 * The Login with Raft setup page's public address and its two older names, as the web app routes
 * them (slock packages/web App.tsx; React Router: letters in either case, one trailing slash or
 * none). An App may send a person there directly, with no authorize request first.
 */
private val RaftSetupEntryPath = Regex("/login-with-(?:raft|slock|slock-human)/setup/?", RegexOption.IGNORE_CASE)
private val RaftSetupEntryOptionalParams = listOf("scope", "state", "server")

/** Common contract of [RaftAuthorizeRequest] and [RaftSetupEntryRequest]. */
internal sealed interface RaftBrowserSignInEntry {
    /** The request's normalized return address: site checks and result validation read it. */
    val redirectUri: String
    /** The request's `client_id` when a log line may carry it, else null. For logs only. */
    val logClientId: String?
    /** The request's one `state` as the page wrote it, else null: echoed with a denial. */
    val state: String?
}

/** A navigation to Raft's authorize endpoint (slock desktop `RaftAuthorizeRequest`). */
internal data class RaftAuthorizeRequest(
    /** What the app asks the server: the API origin, the path as the page wrote it, its query. No fragment or credentials. */
    val fetchUrl: String,
    /**
     * The request's one `redirect_uri`, trimmed and written as [raftBrowserUrlOf] reads it: an http(s)
     * address without credentials or a fragment, not on a Raft host.
     */
    override val redirectUri: String,
    /** The request's `client_id` when a log line may carry it, else null. For logs only. */
    override val logClientId: String?,
    /** The request's one `state` as the page wrote it, else null (none, or more than one): echoed with a denial. */
    override val state: String?,
) : RaftBrowserSignInEntry

/**
 * The navigation to [url] as a request to Raft's authorize endpoint, or null (slock desktop
 * `raftAuthorizeRequest`). It is one only on exactly [authorizeOrigin] (no lookalike or sub-domain host,
 * no other port or deployment), without credentials, at `/api/oauth/authorize` or
 * `/oidc/<server>/api/oauth/authorize` in a spelling the server routes there (never the setup page or a
 * longer or encoded path), with exactly one `redirect_uri` the page could be sent to.
 */
internal fun raftAuthorizeRequest(
    url: String,
    authorizeOrigin: String?,
    raftHosts: Collection<String>,
): RaftAuthorizeRequest? {
    if (authorizeOrigin == null) return null
    val parsed = raftBrowserUrlOf(url) ?: return null
    if (parsed.origin != authorizeOrigin || parsed.userInfo != null || !isRaftAuthorizePath(parsed.path)) return null
    val query = RaftBrowserQuery.parse(parsed.query) ?: return null
    val redirectUri =
        query.all("redirect_uri").singleOrNull()?.let { raftSignInReturnAddress(it.trim(), raftHosts) } ?: return null
    val clientId = query.first("client_id")?.trim().orEmpty()
    val search = parsed.query?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
    return RaftAuthorizeRequest(
        fetchUrl = authorizeOrigin + parsed.path + search,
        redirectUri = redirectUri,
        logClientId = clientId.takeIf { RaftLoggableClientId.matches(it) },
        state = query.all("state").singleOrNull(),
    )
}

/**
 * A navigation opened directly at Raft's setup page (`/login-with-raft/setup`,
 * `/login-with-slock/setup`, `/login-with-slock-human/setup`; slock desktop `RaftSetupEntryRequest`).
 * No authorize request is made: [setupSearch] is built from an allow-list of the page's parameters
 * only (`client_id`, `return_to`, and optional `scope`, `state`, `server`), never `flow`, `nonce`,
 * or PKCE parameters.
 */
internal data class RaftSetupEntryRequest(
    /** Query fed to [raftSignInSetupRequest] (leading `?`), built strictly from the allow-list. */
    val setupSearch: String,
    /** The request's return address, normalized as [RaftAuthorizeRequest.redirectUri]. */
    override val redirectUri: String,
    /** The request's `client_id` when a log line may carry it, else null. For logs only. */
    override val logClientId: String?,
    /** The request's one `state` as the page wrote it, else null (none given): echoed with a denial. */
    override val state: String?,
) : RaftBrowserSignInEntry

/**
 * The navigation to [url] as a request opened at the setup page itself on [setupOrigin], or null
 * (slock desktop `raftSetupEntryRequest`). No authorize request is made: [RaftSetupEntryRequest.setupSearch]
 * is built from an allow-list (`client_id`, `return_to`, and optional `scope`, `state`, `server`).
 *
 * It matches only when:
 * - the origin is exactly [setupOrigin] ([raftAuthorizeOrigin]'s rule applied to the web app
 *   origin), with no credentials, and the path is one of the three setup routes (either case, one
 *   trailing slash or none);
 * - `client_id` is given exactly once and not blank;
 * - the return address is given exactly once (`return_to`, or where absent `redirect_uri`; both
 *   given is refused unless each is given once with the same trimmed value; empty `return_to=` is
 *   refused with or without `redirect_uri`), trimmed, loadable, not on a Raft host, and with no
 *   fragment. The sheet receives the trimmed value (not the URL-parser-rewritten href) so
 *   character-for-character comparison against the App's registered return address stays intact;
 * - `scope`, `state`, and `server` are given at most once each; anything else is dropped.
 */
internal fun raftSetupEntryRequest(
    url: String,
    setupOrigin: String?,
    raftHosts: Collection<String>,
): RaftSetupEntryRequest? {
    if (setupOrigin == null) return null
    val parsed = raftBrowserUrlOf(url) ?: return null
    if (parsed.origin != setupOrigin || parsed.userInfo != null || !isRaftSetupEntryPath(parsed.path)) return null
    val query = RaftBrowserQuery.parse(parsed.query) ?: return null
    val rawClientId = query.all("client_id").singleOrNull() ?: return null
    val clientId = rawClientId.trim()
    if (clientId.isEmpty()) return null
    val returnTos = query.all("return_to")
    val redirectUris = query.all("redirect_uri")
    val returnTo =
        if (returnTos.isEmpty()) {
            redirectUris.singleOrNull()?.trim() ?: return null
        } else {
            if (returnTos.size != 1 || redirectUris.size > 1) return null
            val candidate = returnTos[0].trim()
            if (redirectUris.size == 1 && redirectUris[0].trim() != candidate) return null
            candidate
        }
    val redirectUri = raftSignInReturnAddress(returnTo, raftHosts) ?: return null
    val setupPairs = ArrayList<Pair<String, String>>(5)
    setupPairs += "client_id" to rawClientId
    setupPairs += "return_to" to returnTo
    var state: String? = null
    for (name in RaftSetupEntryOptionalParams) {
        val values = query.all(name)
        if (values.size > 1) return null
        if (values.size == 1) {
            setupPairs += name to values[0]
            if (name == "state") state = values[0]
        }
    }
    val setupSearch = "?" + setupPairs.joinToString("&") { (name, value) -> raftSignInQueryPair(name, value) }
    return RaftSetupEntryRequest(
        setupSearch = setupSearch,
        redirectUri = redirectUri,
        logClientId = clientId.takeIf { RaftLoggableClientId.matches(it) },
        state = state,
    )
}

private fun isRaftSetupEntryPath(path: String): Boolean =
    path.none { it.code >= 0x80 } && RaftSetupEntryPath.matches(path)

private fun isRaftAuthorizePath(path: String): Boolean {
    // The WHATWG parser percent-encodes any other character: such a path is never the endpoint.
    if (path.any { it.code >= 0x80 }) return false
    if (RaftRootAuthorizePath.matches(path)) return true
    val mount = RaftScopedAuthorizeMount.find(path) ?: return false
    val rest = path.substring(mount.range.last + 1)
    return rest == "/authorize" || rest == "/authorize/"
}

/** An address the page may be sent to (slock desktop `webPreviewUrl`): http(s), no credentials, not on a Raft host. */
private fun raftSignInLoadableUrl(value: String, raftHosts: Collection<String>): RaftHttpUrl? =
    raftBrowserUrlOf(value)?.takeIf { it.userInfo == null && !raftBrowserIsRaftHost(it.host, raftHosts) }

/** An App's return address (slock desktop `returnAddress`): loadable, and no fragment, not even an empty one. */
private fun raftSignInReturnAddress(value: String, raftHosts: Collection<String>): String? =
    raftSignInLoadableUrl(value, raftHosts)?.takeIf { it.fragment == null }?.href

/**
 * v1's "same site" (#356 decision §9.3): the same origin, so the same scheme, host and port; anything
 * that is not an http(s) address is no site. Desktop's `sameSite` compares registrable domains (the
 * Public Suffix List), so `www.` and `auth.` of one domain, or two ports of it, are one site there and
 * two here.
 */
internal fun raftSignInSameSite(a: String, b: String): Boolean {
    val first = raftBrowserUrlOf(a) ?: return false
    return first.origin == raftBrowserUrlOf(b)?.origin
}

/** Who started a navigation to the authorize address, as the platform saw it. */
internal enum class RaftSignInStarter {
    /** The page's shown document: a link, a script, a form; the navigation may have been redirected since. */
    Page,

    /** The in-app browser's own load of the page's address, before any document committed, redirected since. */
    Launch,

    /** Anything else: a start the platform did not see. */
    Other,
}

/** A navigation to the authorize address as the platform saw it (slock desktop `RaftSignInAsk`). */
internal data class RaftSignInAsk(
    /** [RaftAuthorizeRequest.redirectUri]: the App's site is this address's. */
    val redirectUri: String,
    val starter: RaftSignInStarter,
    /** The address of the document the page shows, "" when there is none. */
    val page: String,
    /** The page has shown a document before (any, ever). */
    val committed: Boolean,
    /** Every address the navigation was at before the authorize one, in order; empty when it went straight there. */
    val hops: List<String>,
    /** The page is the one on screen. */
    val pageVisible: Boolean,
    /** The app is in the foreground. */
    val appForeground: Boolean,
)

internal enum class RaftSignInAskRefusal { HiddenPage, Background, Initiator, OtherSite }

/**
 * Why a navigation to the authorize address may not ask for the App's sign-in, or null when it may
 * (slock desktop `raftSignInAskRefusal`; "the App's site" is [raftSignInSameSite] with the request's
 * `redirect_uri`). Only while the page is on screen and the app in the foreground, and only:
 * - [RaftSignInStarter.Page]: from a shown document on the App's site;
 * - [RaftSignInStarter.Launch]: when nothing committed yet and the page's own address redirected here
 *   (a load straight to the authorize address says nothing about whose it is).
 * Either way every address the navigation went through, the first included, must be on the App's site.
 */
internal fun raftSignInAskRefusal(ask: RaftSignInAsk): RaftSignInAskRefusal? {
    if (!ask.pageVisible) return RaftSignInAskRefusal.HiddenPage
    when (ask.starter) {
        RaftSignInStarter.Page -> {
            if (!ask.appForeground) return RaftSignInAskRefusal.Background
            if (!raftSignInSameSite(ask.page, ask.redirectUri)) return RaftSignInAskRefusal.OtherSite
        }
        RaftSignInStarter.Launch -> {
            if (ask.committed || ask.hops.isEmpty()) return RaftSignInAskRefusal.Initiator
            if (!ask.appForeground) return RaftSignInAskRefusal.Background
        }
        RaftSignInStarter.Other -> return RaftSignInAskRefusal.Initiator
    }
    return if (ask.hops.all { raftSignInSameSite(it, ask.redirectUri) }) null else RaftSignInAskRefusal.OtherSite
}

/** The server's answer to the authorize fetch (no cookies, no redirect followed): its status and `Location`. */
internal data class RaftAuthorizeResponse(val status: Int, val location: String?)

/** A request the server accepted (slock desktop `RaftSetupAnswer`). */
internal data class RaftSetupAnswer(
    /** The setup address's query (with its `?`) as the server wrote it: what the sheet is fed. */
    val setupSearch: String,
    /** Where the answer goes: the server's `return_to`, equal to the request's `redirect_uri`. */
    val returnUrl: String,
)

/**
 * The server's answer to [request] when it accepted it, or null: a refusal, nothing is shown (slock
 * desktop `raftSetupAnswer`). Accepted is a 302 to exactly `/login-with-raft/setup` on the origin of the
 * environment's web app ([webUrl]), without credentials, whose query has one `client_id`, no
 * `redirect_uri` and one `return_to` that is the `redirect_uri` the page named (so the checks made on
 * the page's value hold for the server's).
 */
internal fun raftSetupAnswer(
    response: RaftAuthorizeResponse?,
    webUrl: String,
    request: RaftAuthorizeRequest,
    raftHosts: Collection<String>,
): RaftSetupAnswer? {
    if (response == null || response.status != 302) return null
    val location = raftBrowserUrlOf(response.location ?: return null) ?: return null
    if (location.userInfo != null || location.path != RAFT_SIGN_IN_SETUP_PATH) return null
    if (location.origin != raftBrowserUrlOf(webUrl)?.origin) return null
    // The setup page reads `return_to`, else `redirect_uri`: one of the first, none of the second.
    val query = RaftBrowserQuery.parse(location.query) ?: return null
    val returnTo = query.all("return_to").singleOrNull() ?: return null
    if (query.all("redirect_uri").isNotEmpty() || query.all("client_id").size != 1) return null
    val returnUrl = raftSignInReturnAddress(returnTo, raftHosts)?.takeIf { it == request.redirectUri } ?: return null
    return RaftSetupAnswer(setupSearch = "?${location.query.orEmpty()}", returnUrl = returnUrl)
}

/** A request the sheet may show (slock web `DesktopBrowserSignInRequest`). */
internal data class RaftSignInSetupRequest(
    /** [RaftSetupAnswer.setupSearch]. */
    val setupSearch: String,
    /** The host the answer goes to, in ASCII, with its port when it names one: what the sheet says. */
    val returnHost: String,
    val clientId: String,
    /** The `return_to` as the server wrote it: what the App's registered address is compared with. */
    val returnUrl: String,
    /** The requested scopes, separated by one space. */
    val scope: String,
)

/**
 * The server's accepted request as the sheet shows it, or null: one the sheet refuses to show (slock
 * web `desktopBrowserSignInRequest`). The sheet shows no text a page chose, so every requested scope
 * (default `openid profile`) must be one of [RaftSignInVisibleScopes], and the return address an http(s)
 * one whose host can be named. The App asked for is the first `client_id`, trimmed.
 */
internal fun raftSignInSetupRequest(setupSearch: String): RaftSignInSetupRequest? {
    val query = RaftBrowserQuery.parse(setupSearch) ?: return null
    val clientId = query.first("client_id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val scopes =
        (query.first("scope")?.takeIf { it.isNotEmpty() } ?: RAFT_SIGN_IN_DEFAULT_SCOPE)
            .split(RaftSignInListSeparator)
            .filter { it.isNotEmpty() }
    if (!scopes.all { it in RaftSignInVisibleScopes }) return null
    val returnTo = query.first("return_to").orEmpty()
    val returnUrl = raftBrowserUrlOf(returnTo) ?: return null
    return RaftSignInSetupRequest(
        setupSearch = setupSearch,
        returnHost = if (returnUrl.port == null) returnUrl.host else "${returnUrl.host}:${returnUrl.port}",
        clientId = clientId,
        returnUrl = returnTo,
        scope = scopes.joinToString(" "),
    )
}

/** A lookup's `availability` (slock web `LoginClientSummary`). */
internal enum class RaftSignInAvailability {
    /** Open there; also what a lookup from an older server, which sends none, means. */
    Ready,
    InstallRequired,
    Suspended,

    /** A value this app does not know: not ready. */
    Unknown,
}

/** How the App signs people in: on a chosen Server, or with their Raft account (`loginMode: "account"`). */
internal enum class RaftSignInLoginMode { Server, Account }

/** A lookup's `consent`: the person consented to the App before, and whether that covers every scope asked now. */
internal data class RaftSignInConsent(val granted: Boolean, val coversRequested: Boolean)

/** One Server's lookup of the requested App (`GET oauth/clients/lookup`), the part the rules read (slock web `SignInLookup`). */
internal data class RaftSignInLookup(
    val serverId: String,
    /** The App's registered name. */
    val name: String,
    /** The App's registered return address. */
    val returnUrl: String?,
    val availability: RaftSignInAvailability = RaftSignInAvailability.Ready,
    val loginMode: RaftSignInLoginMode = RaftSignInLoginMode.Server,
    /** `scopeValidation.allowed`; true when the lookup has no `scopeValidation`. */
    val scopeAllowed: Boolean = true,
    /** Sent only on a ready answer with an active consent. */
    val consent: RaftSignInConsent? = null,
)

/**
 * Whether [returnUrl] (the setup query's `return_to`) is the App's own registered return address: some
 * Server's lookup gives it character for character (slock web `isRegisteredReturnAddress`; the server
 * compares them the same way when it issues the code). A request with any other is refused, as a page
 * anywhere can ask with any App's `client_id` and a return address of its own.
 */
internal fun raftSignInIsRegisteredReturnAddress(returnUrl: String, lookups: Collection<RaftSignInLookup>): Boolean =
    returnUrl.isNotEmpty() && lookups.any { it.returnUrl == returnUrl }

/** A Server the person is in: its id, and its slug (a `server=` hint names either). */
internal data class RaftSignInServer(val id: String, val slug: String)

/** Where a request the person already consented to is approved without the sheet (slock web `SignInSkipTarget`). */
internal sealed interface RaftSignInSkipTarget {
    /** The App's registered name. */
    val name: String

    data class Account(override val name: String) : RaftSignInSkipTarget

    data class Server(val serverId: String, override val name: String) : RaftSignInSkipTarget
}

/**
 * Whether the request may be approved without showing the sheet, and where (slock web
 * `signInSkipTarget`): the person already consented to this App, for every scope it asks now, where
 * the sheet would sign them in. Anything less certain is null and shows the sheet:
 * - the App asks to confirm again (`prompt` naming `consent` or `login`);
 * - an account-mode App: its account consent covers the request, and the request names no Server;
 * - a server-mode App: among the Server the request names (`server=`, an id or a slug) or else all,
 *   [preferredServerId] when the App is ready there (covered or not: the Server the sheet would pick
 *   first), otherwise the one Server whose consent covers it, if there is exactly one.
 * The server checks the consent again when the code is asked for (`confirmSkipped`).
 */
internal fun raftSignInSkipTarget(
    request: RaftSignInSetupRequest,
    lookups: List<RaftSignInLookup>,
    servers: List<RaftSignInServer>,
    preferredServerId: String?,
): RaftSignInSkipTarget? {
    val query = RaftBrowserQuery.parse(request.setupSearch) ?: return null
    if (raftSignInAsksToConfirmAgain(query)) return null
    val registered = lookups.filter { it.returnUrl == request.returnUrl }
    if (registered.isEmpty()) return null
    val hint = query.first("server").orEmpty()
    if (registered.any { it.loginMode == RaftSignInLoginMode.Account }) {
        if (hint.isNotEmpty()) return null
        val covered =
            registered.firstOrNull { it.loginMode == RaftSignInLoginMode.Account && it.consentCovers(request.returnUrl) }
        return covered?.let { RaftSignInSkipTarget.Account(it.name) }
    }
    val candidates = raftSignInHintedLookups(registered, hint, servers)
    return raftSignInServerSkip(candidates, request.returnUrl, preferredServerId)
}

/** The App asked Raft to confirm again (OIDC `prompt`): the sheet is shown whatever was consented before. */
private fun raftSignInAsksToConfirmAgain(query: RaftBrowserQuery): Boolean {
    val prompt = query.first("prompt").orEmpty().split(RaftSignInListSeparator)
    return "consent" in prompt || "login" in prompt
}

/** [registered] on the Server [hint] names (its id or slug), or all of them when it names none. */
private fun raftSignInHintedLookups(
    registered: List<RaftSignInLookup>,
    hint: String,
    servers: List<RaftSignInServer>,
): List<RaftSignInLookup> {
    if (hint.isEmpty()) return registered
    val hinted = servers.filter { it.id == hint || it.slug == hint }.map { it.id }
    return registered.filter { it.serverId in hinted }
}

private fun raftSignInServerSkip(
    candidates: List<RaftSignInLookup>,
    returnUrl: String,
    preferredServerId: String?,
): RaftSignInSkipTarget? {
    val preferred = candidates.firstOrNull { it.serverId == preferredServerId }
    val chosen =
        if (preferred != null && preferred.availability == RaftSignInAvailability.Ready) {
            preferred.takeIf { it.consentCovers(returnUrl) }
        } else {
            candidates.filter { it.consentCovers(returnUrl) }.singleOrNull()
        }
    return chosen?.let { RaftSignInSkipTarget.Server(serverId = it.serverId, name = it.name) }
}

/** Ready there, registered with the request's own return address, its scopes allowed, and consented to all of them. */
private fun RaftSignInLookup.consentCovers(requestReturnUrl: String): Boolean =
    returnUrl == requestReturnUrl &&
        availability == RaftSignInAvailability.Ready &&
        scopeAllowed &&
        consent?.let { it.granted && it.coversRequested } == true

/** slock shared `isSafeOAuthReturnUrl`: https, or http on the loopback; no credentials, no fragment. */
private fun raftSignInSafeOAuthReturnUrl(value: String): RaftHttpUrl? =
    raftBrowserUrlOf(value)?.takeIf {
        (it.scheme == "https" || it.host in RaftOAuthLoopbackHosts) && it.userInfo == null && it.fragment == null
    }

/**
 * Where the page is sent with the code (slock shared `appendOAuthAuthorizationParams`): [returnUrl] (the
 * approval's) with `code` set and, when it is not empty, `state` (the setup query's) set, every other
 * pair of its query byte for byte. Null unless [returnUrl] is a safe OAuth return address (https, or
 * http on the loopback; no credentials, no fragment). Loaded only when [raftSignInResultUrl] takes it.
 */
internal fun raftSignInSuccessUrl(returnUrl: String, code: String, state: String?): String? {
    val url = raftSignInSafeOAuthReturnUrl(returnUrl) ?: return null
    return url.withQueryChanged(
        replaced = listOfNotNull("code" to code, state?.takeIf { it.isNotEmpty() }?.let { "state" to it }),
    )
}

/**
 * Where a refusal the App is owed goes (slock web `authorizationErrorRedirectUrl`): only for an
 * approval refused with `errorCode` `OAUTH_SCOPE_NOT_APPROVED` (the Server's admin did not approve the
 * App's scopes). Its `returnUrl` (the registered address the server matched) with `code` dropped,
 * `error=invalid_scope`, and the server's `error_description` and the setup query's `state` when they
 * are not empty (slock shared `appendOAuthErrorParams`). Null for every other refusal, which the sheet
 * words itself, and for a return address that is not a safe one.
 */
internal fun raftSignInInvalidScopeUrl(
    errorCode: String?,
    returnUrl: String?,
    description: String?,
    state: String?,
): String? {
    if (errorCode != "OAUTH_SCOPE_NOT_APPROVED" || returnUrl.isNullOrEmpty()) return null
    val url = raftSignInSafeOAuthReturnUrl(returnUrl) ?: return null
    return url.withQueryChanged(
        removed = setOf("code"),
        replaced =
            listOfNotNull(
                "error" to "invalid_scope",
                description?.takeIf { it.isNotEmpty() }?.let { "error_description" to it },
                state?.takeIf { it.isNotEmpty() }?.let { "state" to it },
            ),
    )
}

/**
 * The address the page may be sent for a request, or null (slock desktop `raftSignInResultUrl`): an
 * http(s) address without credentials, not on a Raft host, with no fragment, on [returnUrl]'s origin and
 * path, still carrying every pair of [returnUrl]'s query (the answer is the return address plus `code`
 * and `state`, or plus `error`). Given as [raftBrowserUrlOf] writes it.
 */
internal fun raftSignInResultUrl(value: String, returnUrl: String, raftHosts: Collection<String>): String? {
    val url = raftSignInLoadableUrl(value, raftHosts)?.takeIf { it.fragment == null } ?: return null
    val expected = raftBrowserUrlOf(returnUrl) ?: return null
    if (url.origin != expected.origin || url.path != expected.path) return null
    val query = RaftBrowserQuery.parse(url.query) ?: return null
    val expectedPairs = RaftBrowserQuery.parse(expected.query)?.pairs ?: return null
    return url.href.takeIf { expectedPairs.all { pair -> pair.value in query.all(pair.name) } }
}

/**
 * The OAuth denial for a request the person declined (RFC 6749 §4.1.2.1; slock desktop
 * `raftSignInDeniedUrl`): [returnUrl] as it is, with `error=access_denied` and, when the request had
 * one ([RaftAuthorizeRequest.state]), its `state` unchanged, added after its own query. Null where the
 * page may not be sent ([raftSignInResultUrl]).
 */
internal fun raftSignInDeniedUrl(returnUrl: String, state: String?, raftHosts: Collection<String>): String? {
    val url = raftBrowserUrlOf(returnUrl) ?: return null
    val denial = listOfNotNull("error" to "access_denied", state?.let { "state" to it })
    val denied = url.withQueryChanged(appended = denial) ?: return null
    return raftSignInResultUrl(denied, returnUrl, raftHosts)
}

/**
 * This address with its query changed as `URLSearchParams` changes one: the pairs named in [removed]
 * deleted; each of [replaced] set (`set`: the first pair of that name takes the value and later ones
 * go; none, it is appended); then [appended] appended. Every other pair stays as it was written, and
 * each new name and value is percent-encoded. Null when the query can't be read.
 */
private fun RaftHttpUrl.withQueryChanged(
    removed: Set<String> = emptySet(),
    replaced: List<Pair<String, String>> = emptyList(),
    appended: List<Pair<String, String>> = emptyList(),
): String? {
    val pairs = RaftBrowserQuery.parse(query)?.pairs ?: return null
    val values = replaced.toMap()
    val placed = HashSet<String>()
    val written = ArrayList<String>()
    for (pair in pairs) {
        if (pair.name in removed) continue
        val value = values[pair.name]
        if (value == null) {
            written += pair.raw
        } else if (placed.add(pair.name)) {
            written += raftSignInQueryPair(pair.name, value)
        }
    }
    for ((name, value) in replaced) {
        if (placed.add(name)) written += raftSignInQueryPair(name, value)
    }
    for ((name, value) in appended) written += raftSignInQueryPair(name, value)
    return copy(query = written.joinToString("&").takeIf { it.isNotEmpty() }).href
}

private fun raftSignInQueryPair(name: String, value: String): String = "${name.percentEncode()}=${value.percentEncode()}"
