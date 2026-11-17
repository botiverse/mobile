package build.raft.app.assistant

import build.raft.app.data.MyAssistantCreateOutcome
import build.raft.app.data.MyAssistantRecord
import build.raft.app.data.MyAssistantState
import build.raft.app.data.ScreenError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The Assistant page's states (web botiverse/slock `packages/web/src/assistant/AssistantRoute.tsx`
// and `CreateAssistantView.tsx`), apart from how they are drawn (AssistantPageViews.kt).

/** What the page shows (web AssistantRoute's branches). */
internal sealed interface AssistantPageView {
    /** "my assistant" is still answering: the head with Back, a spinner (web `AssistantLoading`). */
    data object Loading : AssistantPageView

    /** Not available here: the page leaves for Home (web `<Navigate to=home replace />`). */
    data object Off : AssistantPageView

    /** Ready with no assistant yet: Create your assistant ([form] is the Create view's own state). */
    data class Create(val form: AssistantCreateForm) : AssistantPageView

    /** The assistant: its head over the owner's DM with it ("Setting up your chat…" while the DM is null). */
    data class Conversation(val assistant: MyAssistantRecord) : AssistantPageView
}

/** Web AssistantServerPage: loading → off → no assistant (Create) → the conversation. */
internal fun assistantPageView(state: MyAssistantState, form: AssistantCreateForm): AssistantPageView =
    when (state) {
        MyAssistantState.Loading -> AssistantPageView.Loading
        MyAssistantState.Off -> AssistantPageView.Off
        is MyAssistantState.Ready -> state.assistant?.let(AssistantPageView::Conversation) ?: AssistantPageView.Create(form)
    }

/** Why the last create failed (web CreateAssistantView `CreateFailure`), with the request's trace ID. */
internal sealed interface AssistantCreateFailure {
    val error: ScreenError

    /** "Couldn't create your assistant. Try again." */
    data class Generic(override val error: ScreenError) : AssistantCreateFailure

    /** The agent or seat limit: the server's message ([ScreenError.serverMessage]) and the way to plans / seats. */
    data class Capacity(override val error: ScreenError) : AssistantCreateFailure
}

/** The Create view's state: [submitting] while the create is on its way, and the last [failure]. */
internal data class AssistantCreateForm(
    val submitting: Boolean = false,
    val failure: AssistantCreateFailure? = null,
)

/**
 * The Create view's store (web CreateAssistantView `submit`): Create is single flight (a press
 * while one is on its way does nothing; the shared store also coalesces creates for the same
 * person and server), clears the last failure, and maps the outcome. On [MyAssistantCreateOutcome.Done]
 * the page follows the cached answer (into the DM, Home, or Create again), so the form only stops
 * submitting.
 */
internal class AssistantCreateStore(
    private val create: suspend () -> MyAssistantCreateOutcome,
    private val scope: CoroutineScope,
) {
    private val formState = MutableStateFlow(AssistantCreateForm())
    val state: StateFlow<AssistantCreateForm> = formState.asStateFlow()

    fun submit() {
        var started = false
        formState.update { form ->
            if (form.submitting) {
                form
            } else {
                started = true
                AssistantCreateForm(submitting = true, failure = null)
            }
        }
        if (!started) return
        scope.launch {
            val failure =
                when (val outcome = create()) {
                    MyAssistantCreateOutcome.Done -> null
                    is MyAssistantCreateOutcome.CapacityLimit -> AssistantCreateFailure.Capacity(outcome.error)
                    is MyAssistantCreateOutcome.Failed -> AssistantCreateFailure.Generic(outcome.error)
                }
            formState.value = AssistantCreateForm(submitting = false, failure = failure)
        }
    }
}
