package build.raft.app.data

import build.raft.app.RaftHostStrings

/**
 * Typed-code copy for the two OAuth conflict outcomes, shared by every platform.
 *
 * The server deliberately returns two different codes and they mean opposite
 * things to the user:
 *
 *  - `account_conflict` — this email already has a Raft password account and the
 *    provider identity is **not** bound to anyone. Auto-linking on matching
 *    email alone would be an account-takeover path, so the server stops here.
 *    The user is not locked out: sign in with the password, then connect the
 *    provider from Settings.
 *  - `provider_conflict` — the provider identity really does belong to a
 *    different Raft user. That is a genuine conflict.
 *
 * Collapsing them into one message told a user with a perfectly healthy account
 * that someone else had taken it (#proj-backend-general thread 3e806bba).
 *
 * This lives in `commonMain` so iOS and Android resolve the *same* localized
 * resource from the *same* branch. Keying copy off the code — never off the
 * message text — is what keeps the two platforms from drifting again.
 *
 * Returns `null` for any other code so callers keep their existing fallback.
 */
fun mobileOAuthConflictMessageOrNull(
    providerName: String,
    error: String?,
    accountConflictMessage: (String) -> String = RaftHostStrings::mobileOAuthAccountConflict,
    providerConflictMessage: (String) -> String = RaftHostStrings::mobileOAuthProviderConflict,
): String? =
    when (error?.trim()?.lowercase()) {
        "account_conflict" -> accountConflictMessage(providerName)
        "provider_conflict" -> providerConflictMessage(providerName)
        else -> null
    }
