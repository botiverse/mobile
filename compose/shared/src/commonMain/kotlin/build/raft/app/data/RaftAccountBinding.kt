package build.raft.app.data

import kotlinx.coroutines.CancellationException

/**
 * What one read of the stored auth session found. Unlike [RaftAuthSessionStore.readSession], which
 * answers null for both, an empty record ([SignedOut]) and a record this build cannot decode
 * ([Corrupt]) are different answers: only the first means "nobody is signed in".
 */
sealed interface RaftAuthSessionState {
    /** A decodable session is stored. */
    data class SignedIn(val session: StoredAuthSession) : RaftAuthSessionState

    /** No session record is stored: signed out (or never signed in). */
    data object SignedOut : RaftAuthSessionState

    /**
     * A session record is stored but cannot be decoded ([errorType] is a safe class name, never the
     * payload). The account is unusable until the user signs in again, but its data stays on the
     * device: nothing may be deleted on this answer.
     */
    data class Corrupt(val errorType: String) : RaftAuthSessionState
}

val RaftAuthSessionState.sessionOrNull: StoredAuthSession?
    get() = (this as? RaftAuthSessionState.SignedIn)?.session

/**
 * The account the process is bound to. [A] is the per-consumer identity (the presence auth context,
 * a composer draft account, ...); [map] derives one from another.
 *
 * Rule (architecture review B): destructive work (deleting drafts, clearing pending sends, retiring
 * caches) runs only on [SignedOut] or on a change from one [SignedIn] account to another. [Unknown]
 * and [Corrupt] never delete anything.
 */
internal sealed interface RaftAccountBinding<out A : Any> {
    /** Not known yet: the stored session has not been read in this process. */
    data object Unknown : RaftAccountBinding<Nothing>

    /** The stored session was read and there is none (sign-out, auth loss, never signed in). */
    data object SignedOut : RaftAccountBinding<Nothing>

    data class SignedIn<out A : Any>(val account: A) : RaftAccountBinding<A>

    /** The stored session cannot be decoded; see [RaftAuthSessionState.Corrupt]. */
    data class Corrupt(val errorType: String) : RaftAccountBinding<Nothing>
}

internal val <A : Any> RaftAccountBinding<A>.accountOrNull: A?
    get() = (this as? RaftAccountBinding.SignedIn<A>)?.account

/** True once the binding is known (anything but [RaftAccountBinding.Unknown]). */
internal val RaftAccountBinding<*>.isKnown: Boolean
    get() = this !is RaftAccountBinding.Unknown

/** Maps the signed-in account; an account [transform] rejects (blank ids) reads as signed out. */
internal inline fun <A : Any, B : Any> RaftAccountBinding<A>.map(transform: (A) -> B?): RaftAccountBinding<B> =
    when (this) {
        is RaftAccountBinding.SignedIn -> transform(account)?.let { RaftAccountBinding.SignedIn(it) } ?: RaftAccountBinding.SignedOut
        RaftAccountBinding.Unknown -> RaftAccountBinding.Unknown
        RaftAccountBinding.SignedOut -> RaftAccountBinding.SignedOut
        is RaftAccountBinding.Corrupt -> this
    }

/**
 * One typed read of the stored session as a binding of [A] (architecture review B): a decodable
 * session maps through [owner] (null, e.g. blank ids, reads as signed out); an undecodable one is
 * [RaftAccountBinding.Corrupt]; a read that throws is [RaftAccountBinding.Unknown]. Only
 * [RaftAccountBinding.SignedOut] may clear anything.
 */
internal suspend fun <A : Any> RaftAuthSessionStore.readAccountBinding(owner: (StoredAuthSession) -> A?): RaftAccountBinding<A> {
    val state =
        try {
            readSessionState()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return RaftAccountBinding.Unknown
        }
    return when (state) {
        is RaftAuthSessionState.SignedIn -> owner(state.session)?.let { RaftAccountBinding.SignedIn(it) } ?: RaftAccountBinding.SignedOut
        RaftAuthSessionState.SignedOut -> RaftAccountBinding.SignedOut
        is RaftAuthSessionState.Corrupt -> RaftAccountBinding.Corrupt(state.errorType)
    }
}

/** Diagnostics token for a binding that is not an account: `unknown` or `corrupt`. */
internal fun RaftAccountBinding<*>.unknownReason(): String =
    when (this) {
        RaftAccountBinding.Unknown -> "unknown"
        is RaftAccountBinding.Corrupt -> "corrupt"
        RaftAccountBinding.SignedOut -> "signed_out"
        is RaftAccountBinding.SignedIn -> "signed_in"
    }
