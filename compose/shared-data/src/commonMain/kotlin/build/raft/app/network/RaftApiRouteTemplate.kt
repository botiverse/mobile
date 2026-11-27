package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

/**
 * Static path words allowed verbatim in `mobile.http.client` `http.route` templates
 * (`#proj-mobile:#399`, `botiverse/slock#16971`). Mirrors `packages/web/src/observability/apiRouteWords.ts`
 * plus static mobile API path nouns so IDs, slugs, invite tokens, and names always collapse to `:id`.
 */
@RaftCoreInternalApi val MOBILE_API_ROUTE_WORDS: Set<String> = setOf(
    "accept", "accept-as-guest", "accept-invite", "access", "account", "account-agents", "account-deletion", "accounts", "ack",
    "actions", "active", "activity-log", "activity-summaries", "actors", "agent", "agent-access", "agent-dms",
    "agent-migrations", "agent-runtime-providers", "agent-usage", "agents", "agreement", "all", "announcement",
    "announcements", "appearance-settings", "app-link-previews", "app-login", "app-logins",
    "app-notifications", "app-origins", "approve", "apps", "appsumo", "archive", "archived", "artifacts", "assertion",
    "assignee", "assignment", "assignments", "assistant", "attachment-preview", "attachments",
    "attempts", "auth", "authors", "authorize", "avatar", "avatar-recipe", "batch", "billing", "bind", "binding",
    "bootstrap-tokens", "bot", "calls", "cancel", "cancel-request", "changes", "channel", "channels", "check", "checkout",
    "claim", "client-config", "client-metadata", "clients", "close", "code", "comments", "complete", "complete-profile",
    "computer", "computer-pairings", "confirm-hosted", "connect", "connection", "connection-authorization", "connections",
    "connect-my-servers", "connect-new-server", "connect-servers", "context", "context-view",
    "context-views", "conversation", "conversation-read-all", "conversations", "conversion-jobs",
    "convert-to-joint", "counts", "credentials", "dashboard", "data-export-settings", "default", "delegated", "deny",
    "describe", "desktop-download", "device", "disconnect", "disconnect-preview", "discover", "dismiss", "dm", "domains", "done", "enabled",
    "evaluate", "events", "execute", "external-availability", "external-connect-servers", "external-diagnostics",
    "external-invite-addresses", "external-invites", "external-setup-method", "external-status", "fcm-probe",
    "feature-flags", "feedback", "files", "findings", "follow", "followers", "forgot-password", "forward", "grant",
    "grantable-apps", "grants", "handoffs", "hang-up", "health", "hide", "history", "hosted-cindy", "hosted-runtime",
    "hosted-runtime-usage", "html-preview-url", "human", "human-outline", "identities", "impact", "inbox", "install",
    "installs", "integration-invites", "integrations", "interrupt", "interrupt-turn", "invite-info", "invites", "join",
    "join-community", "join-links", "joint-connections", "joint-invite", "joint-invites", "joint-servers", "labs", "leave",
    "link", "link-cards", "link-preview-consent", "link-preview-consents", "link-preview-decline", "log", "login", "logo",
    "logout", "lookup", "lounge", "machines", "marketplace", "mark-executed", "mcp", "me", "members",
    "members-can-connect", "mention-actions", "message-display-settings", "messages", "migrate", "migration",
    "mobile-config", "model", "models", "notification-levels", "notifications", "notification-settings", "number",
    "oauth", "objects", "offer", "onboarding-identity-adoption", "onboarding-settings", "operations", "operator-events",
    "options", "order", "orphaned", "overview", "owner", "passkey", "passkeys", "payment-method", "pending-link",
    "period-metrics", "permissions", "permission-updates", "platforms", "poll", "portal", "prepaid", "prepare", "preview",
    "pricing", "probe", "probes", "proc-jobs", "product-analytics-settings", "product-feedback", "profile",
    "prompt-events", "providers", "public", "public-search", "public-visibility", "push", "push-tokens", "quote",
    "raftbench", "read", "read-all", "readers", "reconfirm", "redemption", "refresh", "regenerate-secret", "registration",
    "register", "reject", "release-notes", "reminders", "request-approval", "request-publish", "requests",
    "request-unpublish", "renew", "rescan", "resend", "resend-verification", "reset", "reset-password", "resolve",
    "restart", "restore", "resume-all-agents", "retry", "revoke", "revoke-others", "role", "rotate-key", "rotate-secret",
    "rpc", "rules", "runtime-options", "runtimes", "saved", "scope-attestation", "scopes", "search", "seat-blocked",
    "seat-join-blocks", "seat-pack-quantity", "seat-request", "seat-requests", "seats", "seats-added", "second-factor",
    "seen", "send-create-agent-card", "server", "server-connect-requests", "server-vouch", "servers", "settle",
    "setting-changes", "sessions", "settings", "setup", "setup-handoff", "setup-projection", "setup-reset",
    "setup-transition", "setup-upgrade-prompt", "share-links", "sidebar-order", "snapshot-metrics", "sso", "start",
    "status", "stop", "stop-all-agents", "subagents", "subscribe", "subscription", "summaries", "sync", "system",
    "system-notifications-v2", "targets", "tasks", "test", "threads", "tickets", "timezone-observation", "token",
    "transcript", "translation-settings", "unarchive", "unclaim", "undone", "unfollow", "unfollowed", "unread", "upgrade",
    "upgrade-requests", "upload", "upload-capabilities", "upload-sessions", "url", "urls", "usage", "user",
    "username-available", "v2", "vapid-key", "verify", "verify-email", "voice", "voice-questions", "voice-settings",
    "web-traces", "webhook", "wechat", "withdraw-publish", "workers", "workspace", "workspace-files", "workspaces",
)

private val AbsoluteUrlSchemePattern = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/**
 * Normalizes a request URL to a bounded, low-cardinality route template matching web
 * `apiRouteTemplate` (`packages/web/src/observability/apiRouteTemplate.ts`):
 * - Drops query and fragment.
 * - For absolute URLs, keeps only the path after `/api/`; non-API absolute URLs map to `:other`.
 * - Replaces every path segment not in [MOBILE_API_ROUTE_WORDS] with `:id`.
 * Never throws.
 */
@RaftCoreInternalApi fun raftMobileApiRouteTemplate(url: String?): String =
    runCatching {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return ":other"
        var path = raw.substringBefore('?').substringBefore('#')
        if (AbsoluteUrlSchemePattern.containsMatchIn(path)) {
            val apiIndex = path.indexOf("/api/")
            if (apiIndex < 0) return ":other"
            path = path.substring(apiIndex + "/api".length)
        } else if (path.startsWith("/api/")) {
            path = path.substring("/api".length)
        }
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ":other"
        val normalized = segments.map { seg -> if (seg in MOBILE_API_ROUTE_WORDS) seg else ":id" }
        "/api/${normalized.joinToString("/")}"
    }.getOrDefault(":other")

/**
 * Self-reporting telemetry endpoints excluded from `mobile.http.client` so failed trace uploads
 * never trigger recursive trace emission.
 */
@RaftCoreInternalApi fun raftIsTelemetrySelfApiRoute(route: String): Boolean =
    route == "/api/web-traces" ||
        route.startsWith("/api/web-traces/") ||
        route == "/api/scope-attestation" ||
        route.startsWith("/api/scope-attestation/") ||
        route.endsWith("/scope-attestation") ||
        "/scope-attestation/" in route
