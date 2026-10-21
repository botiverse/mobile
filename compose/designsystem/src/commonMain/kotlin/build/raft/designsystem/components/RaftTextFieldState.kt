package build.raft.designsystem.components

import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/*
 * Every Raft text field edits a [TextFieldState] (the state-based BasicTextField), never the
 * value-based `BasicTextField(value, onValueChange)`.
 *
 * Why (task #69): on iOS, CMP 1.12's value-based field serves UIKit from a copy of the value that
 * is only refreshed when the field recomposes and lays out. Right after UIKit's setMarkedText the
 * copy is still the old value, so the next layout looks like an outside text change and Compose
 * calls textWillChange/textDidChange, which makes iOS drop the marked text: Pinyin "k r yi"
 * empties the candidate bar and commits the letters. The state-based field hands UIKit the live
 * TextFieldState, which the IME edit has already updated, so there is no stale round trip.
 *
 * Callers keep their controlled `value` / `onValueChange` API; [rememberRaftTextFieldState]
 * bridges it to the state (see [RaftTextFieldSync] for the rules).
 */

/**
 * Receives text-input diagnostics (IME edits and value syncs) when installed as
 * [RaftTextInputTracing.trace]. Messages carry lengths, ranges and a coarse character shape,
 * never the typed text.
 */
fun interface RaftTextInputTrace {
    fun event(field: String, message: String)
}

/** Process-wide switch for [RaftTextInputTrace]; a developer setting installs it. */
object RaftTextInputTracing {
    /**
     * Null (the default) turns tracing off: fields then do no tracing work beyond reading this
     * state. Snapshot state, so fields on screen pick a change up when it is made.
     */
    var trace: RaftTextInputTrace? by mutableStateOf(null)
}

/**
 * Keeps a [TextFieldState] (what the editor edits) and a caller's controlled value in step.
 *
 * - A user edit (IME, keyboard, paste, selection drag) is reported once through `onUserEdit`.
 *   Edits we push ourselves are never echoed back.
 * - An owner value is pushed into the state only when the owner chose something other than what
 *   the field last reported: a programmatic change (clear, mention insert, draft restore) or a
 *   rejected / rewritten edit (a length cap, a digits-only filter).
 * - While the IME is composing, a value the owner has not changed since our last report (the
 *   owner is lagging, or rejected a composing edit) is held back until the composition ends:
 *   pushing it would reset the IME's marked text, which is the bug this replaces. A genuinely new
 *   owner value (Send clearing the composer) is pushed even mid-composition.
 * - A composition-only difference is never pushed: the IME owns the composing region.
 *
 * [compareSelection] is false for `String` owners: they only see and decide the text.
 */
internal class RaftTextFieldSync(
    initial: TextFieldValue,
    private val compareSelection: Boolean,
    private val read: () -> TextFieldValue,
    private val write: (TextFieldValue) -> Unit,
) {
    enum class Outcome { Idle, Reported, Recheck, Deferred, Pushed }

    /** The last value both sides agreed on (what the field last reported or was last given). */
    private var agreed: TextFieldValue = initial

    /** The owner's value when the field last reported an edit; null when nothing is pending. */
    private var ownerAtReport: TextFieldValue? = null

    /** Rechecks spent on an owner value that did not move after a report. */
    private var unchangedChecks = 0

    /**
     * Reconciles once, after the host composed with [owner]. Returns [Outcome.Reported] or
     * [Outcome.Recheck] when the host should reconcile again next frame (to see the owner's answer).
     */
    fun reconcile(
        owner: TextFieldValue,
        onUserEdit: (TextFieldValue) -> Unit,
        trace: ((String) -> Unit)? = null,
    ): Outcome {
        val current = read()
        if (userEdited(current)) {
            agreed = current
            ownerAtReport = owner
            unchangedChecks = 0
            trace?.invoke("report ${current.describe()} owner=${owner.describe()}")
            if (differs(current, owner, withComposition = compareSelection)) onUserEdit(current)
            return Outcome.Reported
        }
        // Changes the owner does not track (a String owner's selection) are absorbed silently.
        agreed = current
        if (!differs(owner, current, withComposition = false)) {
            ownerAtReport = null
            unchangedChecks = 0
            return Outcome.Idle
        }
        val pendingBaseline = ownerAtReport
        val ownerMoved = pendingBaseline == null || differs(owner, pendingBaseline, withComposition = false)
        if (!ownerMoved) {
            if (current.composition != null) {
                trace?.invoke("defer composing=${current.composition} owner=${owner.describe()}")
                return Outcome.Deferred
            }
            // The owner has not answered the report yet. Give an owner that updates through a flow
            // one more frame before treating the old value as a rejection.
            if (unchangedChecks < UnchangedOwnerRechecks) {
                unchangedChecks++
                return Outcome.Recheck
            }
        }
        val next = if (compareSelection) owner else owner.copy(selection = current.selection)
        trace?.invoke("push ${next.describe()} from=${current.describe()} moved=$ownerMoved")
        write(next)
        agreed = read()
        ownerAtReport = null
        unchangedChecks = 0
        return Outcome.Pushed
    }

    private fun userEdited(current: TextFieldValue): Boolean =
        if (compareSelection) {
            current.text != agreed.text || current.selection != agreed.selection || current.composition != agreed.composition
        } else {
            current.text != agreed.text
        }

    private fun differs(a: TextFieldValue, b: TextFieldValue, withComposition: Boolean): Boolean =
        a.text != b.text ||
            (compareSelection && a.selection != b.selection) ||
            (withComposition && a.composition != b.composition)

    private companion object {
        const val UnchangedOwnerRechecks = 1
    }
}

/** The editor's state as a [TextFieldValue] (text, selection and the IME's composing range). */
fun TextFieldState.toRaftTextFieldValue(): TextFieldValue = TextFieldValue(text.toString(), selection, composition)

/**
 * Writes [value] into the state as a programmatic edit (which also ends any IME composition).
 * Text that already matches is left alone, so a selection-only push does not rewrite the text.
 */
internal fun TextFieldState.writeRaftTextFieldValue(value: TextFieldValue) {
    edit {
        if (!contentEquals(asCharSequence(), value.text)) replace(0, length, value.text)
        selection = value.selection.coercedTo(length)
    }
}

private fun contentEquals(a: CharSequence, b: CharSequence): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) if (a[i] != b[i]) return false
    return true
}

private fun TextRange.coercedTo(length: Int): TextRange = TextRange(start.coerceIn(0, length), end.coerceIn(0, length))

/** Lengths, ranges and a coarse character shape (ASCII vs other); never the text itself. */
internal fun TextFieldValue.describe(): String {
    var ascii = 0
    var other = 0
    for (c in text) if (c.code < 0x80) ascii++ else other++
    return "len=${text.length}(a$ascii/o$other) sel=${selection.start}-${selection.end} comp=${composition?.let { "${it.start}-${it.end}" } ?: "none"}"
}

/**
 * Holds the [TextFieldState] behind a controlled field and reconciles it with the owner's
 * value after every composition of the host.
 */
@Stable
private class RaftControlledTextFieldState(initial: TextFieldValue, compareSelection: Boolean) {
    val state = TextFieldState(initial.text, initial.selection.coercedTo(initial.text.length))
    var recheck by mutableIntStateOf(0)
    val sync =
        RaftTextFieldSync(
            initial = state.toRaftTextFieldValue(),
            compareSelection = compareSelection,
            read = { state.toRaftTextFieldValue() },
            write = { state.writeRaftTextFieldValue(it) },
        )
}

@Composable
private fun rememberControlledTextFieldState(
    owner: TextFieldValue,
    compareSelection: Boolean,
    traceName: String,
    onUserEdit: (TextFieldValue) -> Unit,
): TextFieldState {
    val holder = remember { RaftControlledTextFieldState(owner, compareSelection) }
    val currentOnUserEdit by rememberUpdatedState(onUserEdit)
    val trace = RaftTextInputTracing.trace
    // Read in composition so a user edit, a composition change or a recheck recomposes the host,
    // and the reconcile below runs after it.
    holder.state.text
    holder.state.selection
    holder.state.composition
    holder.recheck
    SideEffect {
        val outcome =
            holder.sync.reconcile(
                owner = owner,
                onUserEdit = { currentOnUserEdit(it) },
                trace = trace?.let { t -> { message: String -> t.event(traceName, message) } },
            )
        if (outcome == RaftTextFieldSync.Outcome.Reported || outcome == RaftTextFieldSync.Outcome.Recheck) {
            holder.recheck++
        }
    }
    return holder.state
}

/**
 * A [TextFieldState] that follows the controlled [value] and reports user edits through
 * [onValueChange]: the bridge every value-based Raft field uses to edit through the state-based
 * BasicTextField. [traceName] labels this field in [RaftTextInputTracing] diagnostics.
 */
@Composable
fun rememberRaftTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
    traceName: String = "field",
): TextFieldState =
    rememberControlledTextFieldState(
        owner = TextFieldValue(value),
        compareSelection = false,
        traceName = traceName,
        onUserEdit = { onValueChange(it.text) },
    )

/**
 * [rememberRaftTextFieldState] for owners of a [TextFieldValue] (selection and composition): a
 * composer inserting a mention at the caret, a draft restore placing the cursor.
 */
@Composable
fun rememberRaftTextFieldState(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    traceName: String = "field",
): TextFieldState =
    rememberControlledTextFieldState(
        owner = value,
        compareSelection = true,
        traceName = traceName,
        onUserEdit = onValueChange,
    )

/**
 * Traces each user edit as the editor applies it (IME commands included) when tracing is on; null
 * otherwise, so a field without tracing has no input transformation at all.
 */
@Composable
fun rememberRaftTextInputTraceTransformation(traceName: String): InputTransformation? {
    val trace = RaftTextInputTracing.trace ?: return null
    return remember(trace, traceName) {
        InputTransformation {
            trace.event(
                traceName,
                "edit len=${originalText.length}>$length " +
                    "sel=${originalSelection.start}-${originalSelection.end}>${selection.start}-${selection.end}",
            )
        }
    }
}

/** `singleLine` / `maxLines` / `minLines` of the value-based field as state-based line limits. */
fun raftTextFieldLineLimits(singleLine: Boolean, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1): TextFieldLineLimits =
    if (singleLine) {
        TextFieldLineLimits.SingleLine
    } else {
        val max = maxLines.coerceAtLeast(1)
        TextFieldLineLimits.MultiLine(minHeightInLines = minLines.coerceIn(1, max), maxHeightInLines = max)
    }

/**
 * [KeyboardActions] (one callback per IME action) as the state-based field's single handler for
 * [imeAction]: the matching callback runs, and an action without one keeps the default behaviour
 * (Done closes the keyboard, Next moves focus), as the value-based field did.
 */
fun KeyboardActions.toRaftKeyboardActionHandler(imeAction: ImeAction): KeyboardActionHandler? {
    val action =
        when (imeAction) {
            ImeAction.Done -> onDone
            ImeAction.Go -> onGo
            ImeAction.Next -> onNext
            ImeAction.Previous -> onPrevious
            ImeAction.Search -> onSearch
            ImeAction.Send -> onSend
            else -> null
        } ?: return null
    return KeyboardActionHandler { performDefaultAction ->
        val scope =
            object : KeyboardActionScope {
                override fun defaultKeyboardAction(imeAction: ImeAction) = performDefaultAction()
            }
        scope.action()
    }
}

/**
 * The value-based field's [VisualTransformation] as an [OutputTransformation]: a password mask
 * replaces every character with its mask; [VisualTransformation.None] is no transformation.
 */
fun VisualTransformation.toRaftOutputTransformation(): OutputTransformation? =
    when (this) {
        VisualTransformation.None -> null
        is PasswordVisualTransformation -> RaftMaskOutputTransformation(mask)
        else -> {
            val transformation = this
            OutputTransformation {
                val shown = transformation.filter(AnnotatedString(asCharSequence().toString())).text.text
                if (!contentEquals(asCharSequence(), shown)) replace(0, length, shown)
            }
        }
    }

private data class RaftMaskOutputTransformation(private val mask: Char) : OutputTransformation {
    override fun androidx.compose.foundation.text.input.TextFieldBuffer.transformOutput() {
        val masked = mask.toString().repeat(length)
        if (!contentEquals(asCharSequence(), masked)) replace(0, length, masked)
    }
}
