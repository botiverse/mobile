package build.raft.app.core.data.auth

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import build.raft.app.data.InMemoryAuthSessionSnapshot
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.StoredAuthSession
import java.io.File
import java.security.MessageDigest

/**
 * App identity receipt, `raft-app-identity-receipt/v1`.
 *
 * Lets the QA runner confirm, over `adb shell content call`, WHICH signed package is running and
 * which account/server it is signed in to, without reading any of them in plain text. Shipped in
 * every build (store builds included), but:
 *  - the manifest declares it `android:enabled="false"`: on a normal device it is never
 *    instantiated. Only `adb shell pm enable` turns it on, and the runner turns it off again;
 *  - acquiring it needs `android.permission.DUMP` (manifest). `call()` is not covered by that
 *    permission, so [call] authorizes by [Binder.getCallingUid] alone: adb shell (2000) or root
 *    (0). Nothing passed in the request is trusted for authorization;
 *  - it only READS state that is already in memory: the session this process has already loaded
 *    ([KeyValueAuthSessionStore.readInMemorySessionSnapshot]), the package's own PackageInfo and
 *    `/proc/self/stat`. No session refresh, no network, no sign-in, no write, no side effect;
 *  - an accepted receipt is returned over binder only and never written to logcat. A refusal logs
 *    one line with its code ([REJECTED_MARKER]) and returns only that code.
 *
 * `user_ref` / `server_ref` are verification digests salted with the request's nonce (shared
 * vector with Testbed): they let the runner confirm the expected account and server. With the
 * optional `pairing_salt` (Testbed's, 64 hex, never equal to the nonce, never echoed) the receipt
 * also carries `user_pair_ref` / `server_pair_ref`: the same digests salted with it, so Testbed can
 * compare across runs. All four come from one in-memory session snapshot. They are data
 * minimisation, not a way to make the user unidentifiable. No email, name, token, raw user id or
 * plain server id/slug leaves the app.
 */
class AppIdentityReceiptProvider : ContentProvider() {
    private val responder = AppIdentityReceiptResponder()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // Authorization uses the binder caller's uid only; read it first, before anything else.
        val callingUid = Binder.getCallingUid()
        val result =
            responder.respond(
                callingUid = callingUid,
                method = method,
                arg = arg,
                extras = extras?.let(::stringExtrasOrNull),
                readFacts = { readFacts() }
            )
        return when (result) {
            is AppIdentityReceiptResult.Receipt -> Bundle().apply { putString(RESULT_RECEIPT, result.json) }
            is AppIdentityReceiptResult.Rejected -> {
                Log.w(LOG_TAG, "$REJECTED_MARKER code=${result.code}")
                Bundle().apply { putString(RESULT_ERROR, result.code) }
            }
        }
    }

    /** Every extra as a string; `null` when any value is not a string (rejected as bad_request). */
    private fun stringExtrasOrNull(extras: Bundle): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            val value = extras.get(key) as? String ?: return null
            out[key] = value
        }
        return out
    }

    private fun readFacts(): AppIdentityFacts {
        val context = requireNotNull(context)
        val packageName = context.packageName
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners
        val signer = signers?.singleOrNull()?.let { sha256Hex(it.toByteArray()) }
        val session = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(context)).readInMemorySessionSnapshot()
        return AppIdentityFacts(
            packageName = packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            signerSha256 = signer,
            debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            pid = Process.myPid(),
            processStartTicks = runCatching { parseAppIdentityProcStartTicks(File("/proc/self/stat").readText()) }.getOrNull(),
            session = session
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val LOG_TAG = "RaftAppIdentity"
        const val REJECTED_MARKER = "RAFT_APP_IDENTITY_REJECTED"
        const val RESULT_RECEIPT = "receipt"
        const val RESULT_ERROR = "error"
    }
}

/** What the provider reads at call time; `session == null` means this process has not loaded it. */
internal data class AppIdentityFacts(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val signerSha256: String?,
    val debuggable: Boolean,
    val pid: Int,
    val processStartTicks: String?,
    val session: InMemoryAuthSessionSnapshot?
)

internal sealed interface AppIdentityReceiptResult {
    data class Receipt(val json: String) : AppIdentityReceiptResult

    data class Rejected(val code: String) : AppIdentityReceiptResult
}

internal data class AppIdentityReceiptRequest(
    val attemptId: String,
    val nonce: String,
    val requestId: String,
    /** Optional, Testbed's cross-run salt: only salts the pair refs, never echoed, never authorizes. */
    val pairingSalt: String? = null
)

/**
 * The receipt logic without Android types, so the JVM tests drive it directly. One instance per
 * provider (one per process): at most [maxAnswers] answers, and each `request_id` is answered once.
 */
internal class AppIdentityReceiptResponder(private val maxAnswers: Int = MAX_ANSWERS_PER_PROCESS) {
    private val answeredRequestIds = HashSet<String>()

    @Synchronized
    fun respond(
        callingUid: Int,
        method: String?,
        arg: String?,
        extras: Map<String, String>?,
        readFacts: () -> AppIdentityFacts
    ): AppIdentityReceiptResult {
        // 1. Only adb shell or root. Decided by the binder uid alone; nothing else is read first.
        if (callingUid != SHELL_UID && callingUid != ROOT_UID) return rejected("caller_not_allowed")
        // 2. One method, no arg, exactly the three request keys (+ optional pairing_salt) in their formats.
        if (method != METHOD_RECEIPT_V1) return rejected("unknown_method")
        val request = parseAppIdentityReceiptRequest(arg, extras) ?: return rejected("bad_request")
        // 3. Rate limit and one answer per request id.
        if (answeredRequestIds.size >= maxAnswers) return rejected("rate_limited")
        if (!answeredRequestIds.add(request.requestId)) return rejected("request_replayed")
        // 4. Read-only facts.
        val facts = runCatching(readFacts).getOrNull() ?: return rejected("unavailable")
        val session = facts.session ?: return rejected("session_not_loaded")
        val json = buildAppIdentityReceipt(request, facts, session.session)
        if (json.encodeToByteArray().size > MAX_RECEIPT_BYTES) return rejected("oversize")
        return AppIdentityReceiptResult.Receipt(json)
    }

    private fun rejected(code: String) = AppIdentityReceiptResult.Rejected(code)

    companion object {
        /** `android.os.Process.SHELL_UID` / `ROOT_UID` (not public SDK constants). */
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        const val METHOD_RECEIPT_V1 = "receipt_v1"
        const val MAX_ANSWERS_PER_PROCESS = 16
        const val MAX_RECEIPT_BYTES = 1024
    }
}

internal const val APP_IDENTITY_RECEIPT_CONTRACT = "raft-app-identity-receipt/v1"

// Domains shared with Testbed and the #4946 readiness receipt (agreed vector; do not change).
internal const val APP_IDENTITY_USER_REF_DOMAIN = "raft-qa-readiness-user/v1"
internal const val APP_IDENTITY_SERVER_REF_DOMAIN = "raft-qa-readiness-server/v1"

private val AttemptIdRegex = Regex("^pla_[0-9a-f]{32}$")
private val NonceRegex = Regex("^[0-9a-f]{64}$")
private val RequestIdRegex = Regex("^[0-9a-f]{32}$")
private val VersionNameRegex = Regex("^[0-9A-Za-z.+-]{1,32}$")
private val PairingSaltRegex = Regex("^[0-9a-f]{64}$")
private val RequiredKeys = setOf("attempt_id", "nonce", "request_id")
private const val PAIRING_SALT_KEY = "pairing_salt"

internal fun parseAppIdentityReceiptRequest(arg: String?, extras: Map<String, String>?): AppIdentityReceiptRequest? {
    if (arg != null || extras == null || extras.keys - PAIRING_SALT_KEY != RequiredKeys) return null
    val attemptId = extras.getValue("attempt_id")
    val nonce = extras.getValue("nonce")
    val requestId = extras.getValue("request_id")
    val pairingSalt = extras[PAIRING_SALT_KEY]
    if (!AttemptIdRegex.matches(attemptId) || !NonceRegex.matches(nonce) || !RequestIdRegex.matches(requestId)) return null
    if (pairingSalt != null && (!PairingSaltRegex.matches(pairingSalt) || pairingSalt == nonce)) return null
    return AppIdentityReceiptRequest(attemptId, nonce, requestId, pairingSalt)
}

/** sha256(UTF-8 of "raft-qa-readiness-user/v1" NUL salt NUL userId), lowercase hex. Salt: nonce or pairing salt. */
internal fun appIdentityUserRef(salt: String, userId: String): String =
    sha256Hex("$APP_IDENTITY_USER_REF_DOMAIN\u0000$salt\u0000$userId".encodeToByteArray())

/** sha256(UTF-8 of "raft-qa-readiness-server/v1" NUL salt NUL serverId NUL slug). Salt: nonce or pairing salt. */
internal fun appIdentityServerRef(salt: String, serverId: String, slug: String): String =
    sha256Hex("$APP_IDENTITY_SERVER_REF_DOMAIN\u0000$salt\u0000$serverId\u0000$slug".encodeToByteArray())

/**
 * The server the user ACTUALLY selected, with its slug: the stored selection only, never the
 * implicit first-server fallback. The slug is the stored one, else the selected server's own slug.
 * No selection or no slug -> null, so `server_ref` (and `server_pair_ref`) are null and the runner
 * cannot pass the identity check.
 */
internal fun StoredAuthSession.appIdentitySelectedServer(): Pair<String, String>? {
    val selected = selectedServerId?.takeIf { it.isNotBlank() } ?: return null
    val slug = selectedServerSlug?.takeIf { it.isNotBlank() } ?: servers.firstOrNull { it.id == selected }?.slug?.takeIf { it.isNotBlank() }
    return slug?.let { selected to it }
}

/**
 * One flat JSON object, keys in a fixed order, values string/number/boolean/null only.
 * `session == null` (signed out) reports `signed_in: false` and null origin/refs. The pair refs are
 * present only when the request carried a pairing salt (and null when signed out). Every digest
 * comes from the same [session] snapshot.
 */
internal fun buildAppIdentityReceipt(request: AppIdentityReceiptRequest, facts: AppIdentityFacts, session: StoredAuthSession?): String {
    val server = session?.appIdentitySelectedServer()
    val fields =
        linkedMapOf<String, Any?>(
            "contract" to APP_IDENTITY_RECEIPT_CONTRACT,
            "attempt_id" to request.attemptId,
            "nonce" to request.nonce,
            "request_id" to request.requestId,
            "package" to facts.packageName,
            "version_name" to facts.versionName?.takeIf { VersionNameRegex.matches(it) },
            "version_code" to facts.versionCode,
            "signer_sha256" to facts.signerSha256,
            "debuggable" to facts.debuggable,
            "pid" to facts.pid,
            "process_start_ticks" to facts.processStartTicks,
            "signed_in" to (session != null),
            "session_origin" to session?.let { if (it.qaBootstrap == null) "app_login" else "qa_bootstrap" },
            "user_ref" to session?.let { appIdentityUserRef(request.nonce, it.user.id) },
            "server_ref" to server?.let { (id, slug) -> appIdentityServerRef(request.nonce, id, slug) }
        )
    request.pairingSalt?.let { salt ->
        fields["user_pair_ref"] = session?.let { appIdentityUserRef(salt, it.user.id) }
        fields["server_pair_ref"] = server?.let { (id, slug) -> appIdentityServerRef(salt, id, slug) }
    }
    return fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
        "\"$key\":${appIdentityJsonValue(value)}"
    }
}

/** `/proc/<pid>/stat` field 22 (start time in clock ticks since boot). */
internal fun parseAppIdentityProcStartTicks(stat: String): String? {
    val close = stat.lastIndexOf(')')
    if (close < 0 || close + 2 > stat.length) return null
    val fields = stat.substring(close + 2).trim().split(' ')
    return fields.getOrNull(19)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun appIdentityJsonValue(value: Any?): String =
    when (value) {
        null -> "null"
        is Boolean, is Int, is Long -> value.toString()
        else ->
            buildString {
                append('"')
                value.toString().forEach { c ->
                    when {
                        c == '"' -> append("\\\"")
                        c == '\\' -> append("\\\\")
                        c < ' ' -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                }
                append('"')
            }
    }
